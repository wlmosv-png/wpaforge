package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 字典回收站纯逻辑测试（字典页第一批）。
 *
 * <p>全部走真实文件 IO：建临时目录 → 造字典 → 移入 / 列出 / 恢复 / 清空，
 * 逐条断言「移入后源消失、回收站里出现」「恢复后回到主目录且内容不变」
 * 「重名不覆盖」「purge 才真正删除」。测完清理临时目录。
 */
public final class DictTrashTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static File write(File f, String content) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(content.getBytes("UTF-8"));
        }
        return f;
    }

    private static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        return new String(b, "UTF-8");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 字典回收站（纯 IO） ===");

        File root = new File(System.getProperty("java.io.tmpdir"),
                "dicttrash-test-" + System.currentTimeMillis());
        File dictDir = new File(root, "WPAForge-dict");
        write(new File(dictDir, "a.txt"), "11111111\n22222222\n");
        write(new File(dictDir, "b.dic"), "33333333\n");

        // ---- ① 移入回收站 ----
        File a = new File(dictDir, "a.txt");
        File in = DictTrash.trash(dictDir, a);
        ok("移入后返回回收站文件", in != null, "null");
        ok("移入后源文件消失", !a.exists(), "源仍在");
        ok("回收站文件存在", in != null && in.isFile(), "回收站无文件");
        ok("回收站文件在 .trash 子目录", in != null && in.getParentFile().getName().equals(".trash"),
                in == null ? "null" : in.getParentFile().getName());
        ok("内容不变", in != null && read(in).equals("11111111\n22222222\n"), "内容变");

        // ---- ② 主列表 scan 不扫回收站（子目录不递归） ----
        List<DictStore.Entry> scan = DictStore.scan(dictDir);
        ok("scan 不含回收站文件（只剩 b.dic）",
                scan.size() == 1 && scan.get(0).name.equals("b"), scan.size() + " 本");

        // ---- ③ 列出 ----
        List<File> ls = DictTrash.list(dictDir);
        ok("list 返回 1 条", ls.size() == 1, String.valueOf(ls.size()));
        ok("list 条目是那个文件", !ls.isEmpty() && ls.get(0).getName().equals("a.txt"), "");

        // ---- ④ 重名不覆盖：再移入一个同名文件 ----
        write(new File(dictDir, "a.txt"), "REPLACED\n");
        File in2 = DictTrash.trash(dictDir, new File(dictDir, "a.txt"));
        ok("同名二次移入成功", in2 != null, "null");
        ok("回收站里现在有 2 条（不覆盖）", DictTrash.list(dictDir).size() == 2,
                String.valueOf(DictTrash.list(dictDir).size()));
        ok("二次移入的文件名带时间戳后缀", in2 != null && !in2.getName().equals("a.txt"),
                in2 == null ? "null" : in2.getName());

        // ---- ⑤ 恢复 ----
        File restored = DictTrash.restore(dictDir, in);
        ok("恢复成功", restored != null, "null");
        ok("恢复后回到主目录", restored != null && restored.getParentFile().equals(dictDir),
                restored == null ? "null" : restored.getParentFile().getPath());
        ok("恢复后内容不变", restored != null && read(restored).equals("11111111\n22222222\n"), "内容变");
        ok("恢复后回收站少 1 条", DictTrash.list(dictDir).size() == 1,
                String.valueOf(DictTrash.list(dictDir).size()));

        // ---- ⑥ 恢复时主目录已存在同名 → 不覆盖，加 -恢复 后缀 ----
        File clash = new File(dictDir, "a.txt");   // 上面 restore 已放回 a.txt
        File in3 = DictTrash.trash(dictDir, clash);
        File clash2 = new File(dictDir, "a.txt");
        write(clash2, "CLASH\n");                  // 主目录再次出现同名
        File restored2 = DictTrash.restore(dictDir, in3);
        ok("恢复撞名不覆盖", restored2 != null && restored2.getName().startsWith("a") && !restored2.getName().equals("a.txt"),
                restored2 == null ? "null" : restored2.getName());
        ok("撞名恢复后主目录同名原文件仍在", read(new File(dictDir, "a.txt")).equals("CLASH\n"), "被覆盖");

        // ---- ⑦ 恢复目标不是文件 / 源不存在 ----
        ok("restore 传 null 返回 null", DictTrash.restore(dictDir, null) == null, "非 null");
        ok("trash 传不存在文件返回 null",
                DictTrash.trash(dictDir, new File(dictDir, "nope.txt")) == null, "非 null");

        // ---- ⑧ 彻底删除 ----
        int before = DictTrash.list(dictDir).size();
        ok("purge 前回收站非空", before > 0, String.valueOf(before));
        int purged = DictTrash.purgeAll(dictDir);
        ok("purgeAll 删除数 = 之前条数", purged == before, purged + " vs " + before);
        ok("purge 后回收站空", DictTrash.list(dictDir).isEmpty(), "非空");

        // ---- ⑨ 清理临时目录 ----
        purgeTree(root);
        ok("测试临时目录已清理", !root.exists(), "仍在");

        System.out.println();
        System.out.println("=== 回收站: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void purgeTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) purgeTree(c);
        }
        f.delete();
    }
}

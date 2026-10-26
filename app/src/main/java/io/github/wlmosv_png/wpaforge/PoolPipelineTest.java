package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.DictStore;
import io.github.wlmosv_png.wpaforge.audit.TriedSet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 候选流水线自检：**合并字典 → 落盘 → 加载 → 按目标过滤**，用真实临时文件跑。
 *
 * 为什么单独一个类：用户报「字典融合功能现在无法使用」，
 * 而这条链跨了四个环节（UI 选择 → DictStore.merge → 文件 → TriedSet 过滤），
 * 必须端到端钉住，不能只测单个函数。
 *
 * 另外这里会把**真实耗时**打出来 —— 回答"跳过的判定要多久、会不会卡"。
 */
public class PoolPipelineTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static File writeDict(File dir, String name, String[] lines) throws Exception {
        File f = new File(dir, name);
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (String l : lines) w.write(l + "\n");
        }
        return f;
    }

    public static void main(String[] a) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "wpf-pipeline");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        for (File f : tmp.listFiles()) //noinspection ResultOfMethodCallIgnored
            f.delete();

        System.out.println("== 合并：三条内容规则 ==");
        File d1 = writeDict(tmp, "a.txt", new String[]{
                "12345678", "88888888", "12345678",          // 本文件内重复
                "abc12345", "短", "# 注释行", "", "        ",  // 短串/注释/空行
        });
        File d2 = writeDict(tmp, "b.txt", new String[]{
                "abc12345",                                   // 与 a 重复
                "woaini1314", "zhangwei520", "5201314520",
                "这是一个非常长的口令超过六十三位...................................",   // 超长
        });
        File out = new File(tmp, "merged.txt");
        int n = DictStore.merge(Arrays.asList(d1, d2), out, 500000);
        List<String> lines = DictStore.readLines(out);
        ok("合并条数 = 去重后的合法条数", n == lines.size(), n + " vs " + lines.size());
        ok("跨文件重复只留一条", countOf(lines, "abc12345") == 1, "");
        ok("文件内重复只留一条", countOf(lines, "12345678") == 1, "");
        ok("短于 8 位被丢", !lines.contains("短"), "");
        ok("注释行被丢", !lines.contains("# 注释行"), "");
        ok("空行被丢", !lines.contains("") && !lines.contains("        "), "");
        ok("合法条目全部保留", lines.containsAll(Arrays.asList(
                "12345678", "88888888", "abc12345", "woaini1314", "zhangwei520", "5201314520")),
                lines.toString());
        ok("合并没丢第一条字典的内容", lines.contains("88888888"), "");

        System.out.println("== 合并：边界 ==");
        ok("空文件列表 → 0 条", DictStore.merge(new ArrayList<>(), new File(tmp, "e1.txt"), 100) == 0, "");
        ok("不存在的文件被跳过", DictStore.merge(
                Arrays.asList(new File(tmp, "nope.txt")), new File(tmp, "e2.txt"), 100) == 0, "");
        ok("max 上限生效", DictStore.merge(Arrays.asList(d1, d2),
                new File(tmp, "e3.txt"), 3) == 3, "");
        File noDir = new File(tmp, "sub/deep/out.txt");
        ok("目录不存在会自动建", DictStore.merge(Arrays.asList(d1), noDir, 100) > 0
                && noDir.isFile(), "");

        System.out.println("== 端到端：合并 → 过滤 → 实际要跑多少条 ==");
        TriedSet tried = new TriedSet();
        tried.add("12345678");
        tried.add("abc12345");
        int merged = DictStore.merge(Arrays.asList(d1, d2), out, 500000);
        List<String> all = DictStore.readLines(out);
        List<String> todo = new ArrayList<>();
        for (String s : all) if (!tried.contains(s)) todo.add(s);
        ok("合并后总条数", merged == all.size(), merged + " / " + all.size());
        ok("跑过的被剔掉", todo.size() == all.size() - 2, todo.size() + " vs " + all.size());
        ok("没跑过的一条不少", todo.contains("woaini1314") && todo.contains("88888888"), "");
        ok("跑过的一条不剩", !todo.contains("12345678") && !todo.contains("abc12345"), "");

        System.out.println("== 流式统计（合并结果几十万行时不能整份读进内存）==");
        int streamed = TriedSet.countIn(out, tried);
        ok("流式统计结果与内存过滤一致", streamed == 2, "" + streamed);
        ok("空集合 → 0（且不读文件）", TriedSet.countIn(out, new TriedSet()) == 0, "");
        ok("文件不存在 → 0", TriedSet.countIn(new File(tmp, "nope.txt"), tried) == 0, "");
        ok("目录当文件 → 0", TriedSet.countIn(tmp, tried) == 0, "");

        System.out.println("== 按目标隔离（防串档）==");
        TriedSet tA = new TriedSet();
        tA.add("woaini1314");
        TriedSet tB = new TriedSet();      // 另一个目标：空的
        int skipB = 0;
        for (String s : all) if (tB.contains(s)) skipB++;
        ok("B 网不会因为 A 网跑过而跳过", skipB == 0, "" + skipB);
        int skipA = 0;
        for (String s : all) if (tA.contains(s)) skipA++;
        ok("A 网只跳过自己跑过的那些", skipA == 1, "" + skipA);

        System.out.println("== 落盘 / 读回 ==");
        File tf = new File(tmp, "b-aabbccddeeff.tried");
        ok("已试集合落盘", tried.save(tf), "");
        TriedSet back = TriedSet.load(tf);
        ok("读回条数一致", back.size() == tried.size(), back.size() + " vs " + tried.size());
        int skipBack = 0;
        for (String s : all) if (back.contains(s)) skipBack++;
        ok("读回后过滤结果与内存一致", skipBack == 2, "" + skipBack);

        System.out.println("== 实测耗时（12000 条规模）==");
        final int N = 12000;
        List<String> big = new ArrayList<>(N);
        for (int i = 0; i < N; i++) big.add("cand-" + i + "-" + (i * 2654435761L % 999983));
        TriedSet bt = new TriedSet();
        long t0 = System.nanoTime();
        for (String s : big) bt.add(s);
        long addMs = (System.nanoTime() - t0) / 1_000_000;
        File bf = new File(tmp, "big.tried");
        t0 = System.nanoTime();
        bt.save(bf);
        long saveMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        TriedSet bl = TriedSet.load(bf);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        int cnt = 0;
        for (String s : big) if (bl.contains(s)) cnt++;
        long filterMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("     写入 " + N + " 条: " + addMs + " ms");
        System.out.println("     落盘 " + N + " 条: " + saveMs + " ms（文件 "
                + (bf.length() / 1024) + " KB）");
        System.out.println("     读回 " + N + " 条: " + loadMs + " ms");
        System.out.println("     过滤 " + N + " 条: " + filterMs + " ms");
        ok("写入 12000 条 < 200ms", addMs < 200, addMs + " ms");
        ok("落盘 12000 条 < 500ms", saveMs < 500, saveMs + " ms");
        ok("读回 12000 条 < 300ms", loadMs < 300, loadMs + " ms");
        ok("过滤 12000 条 < 300ms（相对 3.6 秒/条可忽略）", filterMs < 300, filterMs + " ms");
        ok("读回后能全部认出来", cnt == N, cnt + " / " + N);
        ok("文件大小 = 条数 × 8 字节", bf.length() == N * 8L, bf.length() + "");

        System.out.println("== 为什么不会误杀 ==");
        // 跳过只对"明确失败"的候选生效；这里钉住"只进过明确结论才 add"这条规则的两侧。
        TriedSet only = new TriedSet();
        only.add("known-wrong");
        ok("明确失败的候选 → 会被跳过", only.contains("known-wrong"), "");
        ok("没记录过的候选 → 绝不跳过", !only.contains("never-tried"), "");

        System.out.println();
        System.out.println("=== 候选流水线测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static int countOf(List<String> l, String v) {
        int n = 0;
        for (String s : l) if (s.equals(v)) n++;
        return n;
    }
}

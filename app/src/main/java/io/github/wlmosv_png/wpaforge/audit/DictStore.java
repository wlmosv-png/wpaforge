package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 字典仓库。负责发现、读取、保存、编辑字典文件。
 *
 * 支持两种格式：
 *  1. 普通字典 —— 每行一条密码
 *  2. 规则字典 —— 含"起始:/末尾:"等字段，按规则展开（见 DictRule）
 *
 * 所有字典都是纯文本文件，用户能直接用任何编辑器查看和修改。
 */
public final class DictStore {

    /** 一个字典条目 */
    public static final class Entry {
        public final File file;
        public final String name;
        public final long sizeBytes;
        public final boolean isRule;
        public int lineCount;      // 懒加载

        Entry(File f, boolean rule) {
            this.file = f;
            this.name = f.getName().replaceAll("\\.(txt|lst|dic)$", "");
            this.sizeBytes = f.length();
            this.isRule = rule;
        }

        public String sizeLabel() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return (sizeBytes / 1024) + " KB";
            return String.format("%.1f MB", sizeBytes / 1024.0 / 1024.0);
        }
    }

    /**
     * 字典目录。
     *
     * 三级降级，因为 Android 10+ 的分区存储可能让应用写不了公共 Download 目录：
     *   1. /sdcard/Download/WPAForge-dict/   用户找得到（首选）
     *   2. 应用专属外部目录                   一定可写，文件管理器也能看到
     *   3. 应用内部目录                       兜底
     *
     * 结果会缓存，避免每次调用都做 IO。
     */
    private static File cachedDir = null;
    private static int cachedTier = 0;
    /** 用户在设置里手动指定过的目录（可选） */
    private static File userPickedDir = null;
    private static final String PREF = "wpaforge_prefs";
    private static final String K_PICKED_DIR = "picked_dict_dir";

    /** 手动指定目录并持久化。重启后仍然生效。 */
    public static void setDir(Context ctx, File dir) {
        userPickedDir = dir;
        cachedDir = null;
        cachedTier = 0;
        if (ctx != null) {
            try {
                ctx.getApplicationContext()
                        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit()
                        .putString(K_PICKED_DIR, dir == null ? "" : dir.getAbsolutePath())
                        .apply();
            } catch (Throwable ignored) {}
        }
    }

    /** 清掉手动指定，恢复自动选择 */
    public static void clearDir(Context ctx) {
        setDir(ctx, null);
    }

    /** 启动时把上次选的目录读回来 */
    public static void restorePickedDir(Context ctx) {
        if (ctx == null || userPickedDir != null) return;
        try {
            String p = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(K_PICKED_DIR, "");
            if (p != null && !p.isEmpty()) {
                File d = new File(p);
                if (d.isDirectory() || d.mkdirs()) userPickedDir = d;
            }
        } catch (Throwable ignored) {}
    }

    public static File defaultDir(Context ctx) {
        if (userPickedDir != null) return userPickedDir;
        if (cachedDir != null) return cachedDir;

        // 第 1 级：公共 Download
        File publicDir = new File("/sdcard/Download/WPAForge-dict");
        if (ensureWritable(publicDir)) {
            cachedDir = publicDir;
            cachedTier = 1;
            return cachedDir;
        }

        // 第 2 级：应用专属外部目录
        if (ctx != null) {
            File ext = ctx.getExternalFilesDir("dict");
            if (ext != null && ensureWritable(new File(ext, "WPAForge-dict"))) {
                cachedDir = new File(ext, "WPAForge-dict");
                cachedTier = 2;
                return cachedDir;
            }
            // 第 3 级：内部目录
            File inner = new File(ctx.getFilesDir(), "WPAForge-dict");
            if (ensureWritable(inner)) {
                cachedDir = inner;
                cachedTier = 3;
                return cachedDir;
            }
        }

        // 全部失败也要返回一个（后续操作会报错，但至少不是 null）
        cachedDir = publicDir;
        cachedTier = 0;
        return cachedDir;
    }

    /**
     * 目录是否真的可用。
     *
     * 必须**同时**满足三件事，缺一不可：
     *   1. 是目录
     *   2. 能写（写探针再删）
     *   3. **能列目录**（listFiles 返回非 null）
     *
     * 第 3 条是后加的。之前只看前两条，结果在无 root 机的 scoped storage 下
     * 出现过一个很怪的现象：程序以为目录可用，把内置字典「跳过」了
     * （说明 exists() 返回了 true），但界面列表里一个都扫不出来，
     * 因为 listFiles() 被系统拒绝返回 null。
     * 用户看到的就是「释放 0 个，跳过 8 个」+「目录里还没有字典」。
     */
    private static boolean ensureWritable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            if (!dir.isDirectory()) return false;

            // 能写
            File probe = new File(dir, ".wprobe");
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(probe)) {
                f.write(1);
            } catch (Exception e) {
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();

            // 能列目录
            File[] probeList = dir.listFiles();
            if (probeList == null) return false;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前用的是第几级目录（1/2/3），0 表示全部失败 */
    public static int dirTier() {
        if (cachedDir == null) return 0;
        return cachedTier;
    }

    /** 目录说明，给界面显示 */
    public static String dirLabel(Context ctx) {
        File d = defaultDir(ctx);
        switch (dirTier()) {
            case 1: return d.getAbsolutePath();
            case 2: return d.getAbsolutePath() + "（应用专属目录）";
            case 3: return d.getAbsolutePath() + "（内部存储）";
            default: return d.getAbsolutePath() + "（不可写）";
        }
    }

    /** 目录不可写时的可读原因 */
    public static String dirProblem(Context ctx) {
        int tier = dirTier();
        if (tier == 0) {
            return "无法写入任何字典目录。请检查存储权限与剩余空间。";
        }
        return dirLabel(ctx);
    }

    /** 兼容旧签名。没有 Context 时只能尝试公共目录。 */
    public static File defaultDir() {
        return defaultDir(null);
    }

    /** 旧版本用过的目录，启动时会把里面的东西迁过来 */
    private static final String OLD_DIR = "/sdcard/Download/NetScope-dict";

    /**
     * 从旧目录迁移字典。只搬文件，不删旧目录（用户可自行清理）。
     * @return 迁移的文件数
     */
    public static int migrateFromOldDir() {
        File oldDir = new File(OLD_DIR);
        if (!oldDir.isDirectory()) return 0;
        File[] fs = oldDir.listFiles();
        if (fs == null || fs.length == 0) return 0;

        File dst = defaultDir(null);
        if (!ensureWritable(dst)) return 0;

        int moved = 0;
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".txt") || n.endsWith(".lst") || n.endsWith(".dic"))) continue;

            File target = new File(dst, f.getName());
            if (target.exists()) {
                String base = f.getName().replaceAll("\\.(txt|lst|dic)$", "");
                String ext = f.getName().substring(base.length());
                int seq = 1;
                while (target.exists()) {
                    target = new File(dst, base + "-legacy" + (seq == 1 ? "" : seq) + ext);
                    seq++;
                }
            }
            try {
                if (!f.renameTo(target)) copyFile(f, target);
                if (target.exists() && target.length() > 0) moved++;
            } catch (Exception ignored) {}
        }
        return moved;
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** 扫描目录下的所有字典文件 */
    public static List<Entry> scan(File dir) {
        List<Entry> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".txt") || n.endsWith(".lst") || n.endsWith(".dic"))) continue;
            out.add(new Entry(f, detectRule(f)));
        }
        out.sort((a, b) -> Long.compare(b.sizeBytes, a.sizeBytes));
        return out;
    }

    /** 判断是不是规则字典：文件前 512 字节含"起始:"或"start:" */
    private static boolean detectRule(File f) {
        if (f.length() > 4096) return false;   // 规则文件都很小
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 20) {
                if (line.contains(L.s("起始", "Start")) || line.contains(L.s("末尾", "End"))
                        || line.startsWith("start:") || line.startsWith("end:")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 读一个字典的所有行 */
    public static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.replace("\uFEFF", "").trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * 把字典转成候选列表。
     * 规则字典按规则展开，普通字典逐行读。
     */
    public static List<Candidate> load(Entry e, int max) {
        return load(e == null ? null : e.file, max);
    }

    public static List<Candidate> load(File f, int max) {
        List<Candidate> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        int limit = max <= 0 ? 200_000 : max;

        if (detectRule(f)) {
            List<String> lines = readLines(f);
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            DictRule rule = DictRule.parse(sb.toString());
            if (rule.isValid()) {
                return rule.expand(limit);
            }
        }

        // 普通字典：逐行读，边读边去重
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null && seen.size() < limit) {
                String t = line.replace("\uFEFF", "").trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.length() < 8 || t.length() > 63) continue;  // WPA 合法长度
                seen.add(t);
            }
        } catch (Exception ignored) {}

        for (String s : seen) out.add(new Candidate(s, Candidate.Origin.USER_DICT));
        return out;
    }

    /** 统计行数（普通字典）或估算条数（规则字典） */
    public static long count(File f) {
        if (f == null || !f.isFile()) return 0;
        if (detectRule(f)) {
            List<String> lines = readLines(f);
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            DictRule r = DictRule.parse(sb.toString());
            return r.estimateCount();
        }
        long n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16)) {
            while (r.readLine() != null) n++;
        } catch (Exception ignored) {}
        return n;
    }

    /** 保存（覆盖）一个普通字典 */
    public static boolean save(File f, List<String> lines) {
        try {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                for (String l : lines) w.write(l + "\n");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 保存一个规则字典 */
    public static boolean saveRule(File f, DictRule rule) {
        try {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(rule.serialize());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 合并多个字典去重后写出。用于L.s("合并字典", "Merged")功能。
     */
    public static int merge(List<File> sources, File target, int max) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (File f : sources) {
            if (set.size() >= max) break;
            for (String l : readLines(f)) {
                if (l.length() >= 8 && l.length() <= 63) set.add(l);
                if (set.size() >= max) break;
            }
        }
        try {
            if (target.getParentFile() != null) target.getParentFile().mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(target), StandardCharsets.UTF_8)) {
                for (String s : set) w.write(s + "\n");
            }
        } catch (Exception e) {
            return 0;
        }
        return set.size();
    }
}

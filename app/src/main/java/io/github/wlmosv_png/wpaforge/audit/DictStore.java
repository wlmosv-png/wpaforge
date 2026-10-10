package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

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

    /** 默认扫描目录 */
    public static File defaultDir() {
        return new File("/sdcard/Download/WPAForge-dict");
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

        File dst = defaultDir();
        if (!dst.exists() && !dst.mkdirs()) return 0;

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

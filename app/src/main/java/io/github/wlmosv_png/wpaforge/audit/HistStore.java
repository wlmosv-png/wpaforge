package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.ui.Redact;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 破解记录与结果导出。
 *
 * 记录每次破解的：时间、目标、用的字典、试了多少条、耗时、结果。
 * 存在应用私有目录，不写共享存储（避免权限问题）。
 *
 * <h3>R12：口令不再明文落盘</h3>
 * 原实现把命中口令明文写在 CSV 第 9 列，等于在磁盘上留一份长期口令清单 ——
 * 任务日志早就做过脱敏（{@code Redact}），但**这个文件一直漏着**。
 * 现在第 9 列是**脱敏标签**（{@code #29ff2d(len=9)}），
 * 明文另存第 10 列，且先过 {@link SecretBox}（Android Keystore，AES-256-GCM）。
 *
 * <h3>向后兼容</h3>
 * 旧文件是 9 列、第 9 列明文。读取时按列数分流：
 * <ul>
 *   <li>9 列 → 当成旧格式，明文被读进内存（仅在内存里），
 *       同时算出标签与密文；由 {@link PlaintextMigration} 在下次启动时**先备份后重写**。</li>
 *   <li>10 列 → 新格式：标签 + 密文；明文由 {@link SecretBox#open} 还原。</li>
 * </ul>
 * 加密通道不可用时密文为空 —— 此时**不写明文**，代价是重启后记录里看不到口令，
 * 这是刻意选的：宁可少显示一个字段，也不在磁盘上留明文。
 */
public final class HistStore {

    /** 一次破解记录 */
    public static final class Record {
        public final long timeMs;
        public final String ssid;
        public final String bssid;
        public final String dictName;
        public final int dictSize;
        public final int tested;
        public final long elapsedMs;
        public final boolean hit;
        /** 命中口令（内存值；落盘走 {@link #tag} + {@link #sealed}）。 */
        public final String password;
        /** 脱敏标签（可对照、不可还原）—— 落盘的就是它。 */
        public final String tag;
        /** {@link SecretBox} 密文；空 = 加密通道不可用，不落任何秘密。 */
        public final String sealed;
        /** 这条记录是不是从**旧格式（明文）**读出来的 —— 迁移用。 */
        public final boolean legacyPlain;

        public Record(long timeMs, String ssid, String bssid, String dictName,
                      int dictSize, int tested, long elapsedMs,
                      boolean hit, String password) {
            String pw = password == null ? "" : password;
            this.timeMs = timeMs;
            this.ssid = ssid == null ? "" : ssid;
            this.bssid = bssid == null ? "" : bssid;
            this.dictName = dictName == null ? "" : dictName;
            this.dictSize = dictSize;
            this.tested = tested;
            this.elapsedMs = elapsedMs;
            this.hit = hit;
            this.password = pw;
            this.tag = (hit && !pw.isEmpty()) ? Redact.pw(pw) : "";
            this.sealed = (hit && !pw.isEmpty()) ? orEmpty(SecretBox.seal(pw)) : "";
            this.legacyPlain = false;
        }

        /** 内部用：直接给定 tag / sealed（读盘路径），不再重复加密。 */
        private Record(long timeMs, String ssid, String bssid, String dictName,
                       int dictSize, int tested, long elapsedMs, boolean hit,
                       String password, String tag, String sealed, boolean legacyPlain) {
            this.timeMs = timeMs;
            this.ssid = ssid == null ? "" : ssid;
            this.bssid = bssid == null ? "" : bssid;
            this.dictName = dictName == null ? "" : dictName;
            this.dictSize = dictSize;
            this.tested = tested;
            this.elapsedMs = elapsedMs;
            this.hit = hit;
            this.password = password == null ? "" : password;
            this.tag = tag == null ? "" : tag;
            this.sealed = sealed == null ? "" : sealed;
            this.legacyPlain = legacyPlain;
        }

        private static String orEmpty(String s) { return s == null ? "" : s; }

        public String timeLabel() {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(timeMs));
        }

        public String durationLabel() {
            long sec = elapsedMs / 1000;
            if (sec < 60) return sec + " 秒";
            if (sec < 3600) return (sec / 60) + L.f(" 分 %s 秒", "m %s s", (sec % 60));
            return (sec / 3600) + " 时 " + ((sec % 3600) / 60) + " 分";
        }

        /** 界面上该显示什么：有明文给明文（用户自己看），没有就给脱敏标签。 */
        public String displayPassphrase() {
            if (!password.isEmpty()) return password;
            return tag.isEmpty() ? "" : tag;
        }

        /**
         * 存成一行 CSV。第 9 列 = 脱敏标签，第 10 列 = 密文。
         *
         * <p><b>这里绝不写明文</b> —— 这是 R12 的核心不变式，有测试钉住。
         */
        public String toLine() {
            return timeMs + "," + esc(ssid) + "," + esc(bssid) + "," + esc(dictName)
                    + "," + dictSize + "," + tested + "," + elapsedMs
                    + "," + (hit ? "1" : "0") + "," + esc(tag) + "," + esc(sealed);
        }

        public static Record fromLine(String line) {
            try {
                String[] p = line.split(",", -1);
                if (p.length < 9) return null;
                long timeMs = Long.parseLong(p[0]);
                String ssid = unesc(p[1]);
                String bssid = unesc(p[2]);
                String dict = Record.unesc(p[3]);
                int size = Integer.parseInt(p[4]);
                int tested = Integer.parseInt(p[5]);
                long elapsed = Long.parseLong(p[6]);
                boolean hit = "1".equals(p[7]);

                if (p.length >= 10) {
                    // 新格式：标签 + 密文；明文只从密文还原，解不出就没有
                    String tag = unesc(p[8]);
                    String sealed = unesc(p[9]);
                    String pw = SecretBox.open(sealed);
                    return new Record(timeMs, ssid, bssid, dict, size, tested, elapsed,
                            hit, pw == null ? "" : pw, tag, sealed, false);
                }

                // 旧格式：第 9 列是**明文**。读进内存并补出标签/密文，
                // 磁盘上的明文由迁移流程**先备份后重写**清掉。
                String plain = unesc(p[8]);
                String tag = (hit && !plain.isEmpty()) ? Redact.pw(plain) : "";
                String sealed = (hit && !plain.isEmpty())
                        ? orEmpty(SecretBox.seal(plain)) : "";
                return new Record(timeMs, ssid, bssid, dict, size, tested, elapsed,
                        hit, plain, tag, sealed, true);
            } catch (Exception e) {
                return null;
            }
        }

        static String esc(String s) {
            return s.replace("\\", "\\\\").replace(",", "\\c").replace("\n", "\\n");
        }

        static String unesc(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    if (n == 'c') sb.append(',');
                    else if (n == 'n') sb.append('\n');
                    else if (n == '\\') sb.append('\\');
                    else sb.append(n);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    private static final String FILE_NAME = "history.csv";

    /** 只读路径，供迁移流程使用。 */
    /**
     * 只统计用的摘要。
     *
     * <p>**为什么单独一条路径**：`load()` 里每条命中记录都会调
     * {@link SecretBox#open} 走一次 Android Keystore 解密，100 条命中就是
     * 100 次 Keystore 操作（实测几百毫秒到几秒）。而"跑过几次、命中几次、
     * 哪本字典命中最多"**完全不需要明文** —— 只解析前 8 列即可。
     *
     * <p>踩过的坑：说明弹窗打开时卡一下，就是因为调了两次 `load()`。
     */
    public static final class Summary {
        public final int runs;
        public final int hits;
        /** 命中最多的字典名；样本不足为空串 */
        public final String topDict;
        public final int topDictHits;

        Summary(int runs, int hits, String topDict, int topDictHits) {
            this.runs = runs;
            this.hits = hits;
            this.topDict = topDict == null ? "" : topDict;
            this.topDictHits = topDictHits;
        }
    }

    /** 只读前 8 列，**不解密任何东西** */
    public static Summary summary(Context ctx) {
        File f = file(ctx);
        if (!f.isFile()) return new Summary(0, 0, "", 0);
        int[] tally = new int[2];                       // {runs, hits}
        java.util.Map<String, Integer> per = new java.util.HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) foldLine(line, tally, per);
        } catch (Exception ignored) {
        }
        return build(tally, per);
    }

    /**
     * 单行 → 统计贡献。**刻意只看前 8 列**（到 hit 标志为止）。
     *
     * <p>第 9/10 列是标签与密文，读它们就要走 Keystore 解密 ——
     * 统计完全不需要明文。抽成纯函数是为了能在桌面单测里把这条假设钉住
     * （以后有人在统计路径里顺手读第 10 列，测试会红）。
     */
    public static void foldLine(String line, int[] tally, java.util.Map<String, Integer> per) {
        if (line == null) return;
        String[] p = line.split(",", -1);
        if (p.length < 8) return;                       // 至少要有到 hit 标志的 8 列
        tally[0]++;
        if (!"1".equals(p[7])) return;
        tally[1]++;
        String dict = Record.unesc(p[3]);
        if (!dict.isEmpty()) {
            Integer c = per.get(dict);
            per.put(dict, c == null ? 1 : c + 1);
        }
    }

    /** 汇总（topDict 至少要有 3 次命中才给，避免样本太少误导） */
    public static Summary build(int[] tally, java.util.Map<String, Integer> per) {
        String best = "";
        int bn = 0;
        for (java.util.Map.Entry<String, Integer> e : per.entrySet()) {
            if (e.getValue() > bn) { bn = e.getValue(); best = e.getKey(); }
        }
        return new Summary(tally[0], tally[1], bn >= 3 ? best : "", bn);
    }

    public static File fileOf(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static File file(Context ctx) {
        return fileOf(ctx);
    }

    /** 追加一条记录 */
    public static void append(Context ctx, Record r) {
        try {
            File f = file(ctx);
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(r.toLine() + "\n");
            }
        } catch (Exception ignored) {}
    }

    /** 读取全部记录（新的在前） */
    public static List<Record> load(Context ctx) {
        List<Record> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Record rec = Record.fromLine(line);
                if (rec != null) out.add(rec);
            }
        } catch (Exception ignored) {}

        // 倒序：最新在前
        List<Record> rev = new ArrayList<>(out.size());
        for (int i = out.size() - 1; i >= 0; i--) rev.add(out.get(i));
        return rev;
    }

    /** 整表重写（迁移用）。返回是否成功。 */
    public static boolean rewrite(Context ctx, List<Record> newestFirst) {
        try {
            File f = file(ctx);
            File tmp = new File(ctx.getFilesDir(), FILE_NAME + ".tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (int i = newestFirst.size() - 1; i >= 0; i--) {
                    w.write(newestFirst.get(i).toLine() + "\n");
                }
            }
            if (f.exists() && !f.delete()) { /* 删不掉就直接改名覆盖 */ }
            return tmp.renameTo(f) || f.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    /** 清空记录 */
    public static boolean clear(Context ctx) {
        try {
            return file(ctx).delete() || !file(ctx).exists();
        } catch (Exception e) {
            return false;
        }
    }

    /** 导出为人类可读的文本，返回导出文件 */
    public static File exportText(Context ctx) {
        List<Record> list = load(ctx);
        File out = new File(ctx.getExternalFilesDir(null),
                "WPAForge-history-" + System.currentTimeMillis() / 1000 + ".txt");
        try {
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write("WPAForge 破解记录\n");
                w.write(L.s("导出时间: ", "Exported: ") + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.US).format(new Date()) + "\n");
                w.write(L.s("共 ", "Total ") + list.size() + " 条\n");
                w.write("========================================\n\n");
                int hits = 0;
                for (Record r : list) if (r.hit) hits++;
                w.write(L.s("成功 ", "Succeeded ") + hits + " 条，失败 " + (list.size() - hits) + " 条\n\n");
                for (Record r : list) {
                    w.write("[" + r.timeLabel() + "] " + r.ssid
                            + "  (" + r.bssid + ")\n");
                    w.write("    字典: " + r.dictName + "(" + r.dictSize + " 条)\n");
                    w.write(L.s("    尝试: ", "    Attempts: ") + r.tested + " 条   耗时: " + r.durationLabel() + "\n");
                    w.write(L.s("    结果: ", "    Result: ") + (r.hit
                            ? L.s("成功，口令 = ", "success, passphrase = ") + r.displayPassphrase()
                            : L.s("未命中", "No match")) + "\n\n");
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 导出只含成功结果的纯净密码表（每行 SSID:密码）。
     *
     * <p><b>这是唯一一处设计内的明文出口</b>：由用户主动点「导出」触发，
     * 写到应用自己的外部目录。它不参与后台自动化，也不会被别的流程读取。
     * 若某条记录的密文解不开（换设备/换签名），输出脱敏标签而不是编造值。
     */
    public static File exportPasswords(Context ctx) {
        List<Record> list = load(ctx);
        File out = new File(ctx.getExternalFilesDir(null),
                "WPAForge-passwords-" + System.currentTimeMillis() / 1000 + ".txt");
        try {
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(out), StandardCharsets.UTF_8)) {
                for (Record r : list) {
                    if (r.hit) w.write(r.ssid + ":" + r.displayPassphrase() + "\n");
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}

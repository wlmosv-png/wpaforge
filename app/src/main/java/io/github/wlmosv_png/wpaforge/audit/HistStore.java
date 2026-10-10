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
        public final String password;

        public Record(long timeMs, String ssid, String bssid, String dictName,
                      int dictSize, int tested, long elapsedMs,
                      boolean hit, String password) {
            this.timeMs = timeMs;
            this.ssid = ssid == null ? "" : ssid;
            this.bssid = bssid == null ? "" : bssid;
            this.dictName = dictName == null ? "" : dictName;
            this.dictSize = dictSize;
            this.tested = tested;
            this.elapsedMs = elapsedMs;
            this.hit = hit;
            this.password = password == null ? "" : password;
        }

        public String timeLabel() {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(timeMs));
        }

        public String durationLabel() {
            long sec = elapsedMs / 1000;
            if (sec < 60) return sec + " 秒";
            if (sec < 3600) return (sec / 60) + L.f(" 分 %s 秒", "m %s s", (sec % 60));
            return (sec / 3600) + " 时 " + ((sec % 3600) / 60) + " 分";
        }

        /** 存成一行 CSV，字段里逗号做转义 */
        public String toLine() {
            return timeMs + "," + esc(ssid) + "," + esc(bssid) + "," + esc(dictName)
                    + "," + dictSize + "," + tested + "," + elapsedMs
                    + "," + (hit ? "1" : "0") + "," + esc(password);
        }

        public static Record fromLine(String line) {
            try {
                String[] p = line.split(",", -1);
                if (p.length < 9) return null;
                return new Record(
                        Long.parseLong(p[0]), unesc(p[1]), unesc(p[2]), unesc(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                        Long.parseLong(p[6]), "1".equals(p[7]), unesc(p[8]));
            } catch (Exception e) {
                return null;
            }
        }

        private static String esc(String s) {
            return s.replace("\\", "\\\\").replace(",", "\\c").replace("\n", "\\n");
        }

        private static String unesc(String s) {
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

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
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
                    w.write(L.s("    结果: ", "    Result: ") + (r.hit ? L.s("成功，口令 = ", "success, passphrase = ") + r.password : L.s("未命中", "No match")) + "\n\n");
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 导出只含成功结果的纯净密码表（每行 SSID:密码）。
     * 方便自己归档。
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
                    if (r.hit) w.write(r.ssid + ":" + r.password + "\n");
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}

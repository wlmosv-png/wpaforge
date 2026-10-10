package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线字典源。
 *
 * 说明：本应用不打包任何第三方字典数据，只提供公开来源的下载入口。
 * 所有链接都指向许可明确的开源项目（主要是 SecLists，MIT 许可）。
 * 用户自行决定是否下载，数据落在本机，与本应用仓库无关。
 */
public final class DictSources {

    public static final class Source {
        public final String name;
        public final String desc;
        public final String url;
        public final String license;
        /** 预估条数（用于界面提示） */
        public final String sizeHint;

        public Source(String name, String desc, String url, String license, String sizeHint) {
            this.name = name;
            this.desc = desc;
            this.url = url;
            this.license = license;
            this.sizeHint = sizeHint;
        }
    }

    private static final String BASE =
            "https://raw.githubusercontent.com/danielmiessler/SecLists/master/";

    /** 精选列表：优先 WPA 相关与高频弱口令，避免动辄千万条的大表 */
    public static List<Source> list() {
        List<Source> out = new ArrayList<>();
        out.add(new Source(
                L.s("WPA 专用 TOP 4800", "WPA-specific TOP 4800"),
                L.s("专门为 WPA 握手收集的高频口令，最贴合本工具", "High-frequency passphrases collected for WPA handshakes — best fit for this tool"),
                BASE + "Passwords/WiFi-WPA/probable-v2-wpa-top4800.txt",
                "MIT", L.s("约 4800 条", "~4800 entries")));

        out.add(new Source(
                L.s("WPA 专用 TOP 447", "WPA-specific TOP 447"),
                L.s("上面那份的精简版，几分钟就能跑完", "A trimmed version of the above — finishes in minutes"),
                BASE + "Passwords/WiFi-WPA/probable-v2-wpa-top447.txt",
                "MIT", L.s("约 447 条", "~447 entries")));

        out.add(new Source(
                L.s("2025 年度最常用口令 TOP 199", "Most-used passphrases 2025 — TOP 199"),
                L.s("当年统计的高频口令，时效性最好", "Freshest statistics from this year"),
                BASE + "Passwords/Common-Credentials/2025-199_most_used_passwords.txt",
                "MIT", L.s("约 199 条", "~199 entries")));

        out.add(new Source(
                L.s("2024 年度最常用口令 TOP 197", "Most-used passphrases 2024 — TOP 197"),
                L.s("上一年度统计", "Previous year's statistics"),
                BASE + "Passwords/Common-Credentials/2024-197_most_used_passwords.txt",
                "MIT", L.s("约 197 条", "~197 entries")));

        out.add(new Source(
                L.s("通用高频 TOP 10000", "Common high-frequency TOP 10000"),
                L.s("Pwdb 统计的 1 万条高频口令", "Pwdb's 10,000 high-frequency passphrases"),
                BASE + "Passwords/Common-Credentials/Pwdb_top-10000.txt",
                "MIT", L.s("约 10000 条", "~10,000 entries")));

        out.add(new Source(
                L.s("高频口令 TOP 12000", "High-frequency TOP 12000"),
                L.s("probable-v2 统计表，覆盖更广", "probable-v2 table, broader coverage"),
                BASE + "Passwords/Common-Credentials/probable-v2_top-12000.txt",
                "MIT", L.s("约 12000 条", "~12,000 entries")));

        out.add(new Source(
                L.s("xato 千万库 TOP 10000", "xato 10M library TOP 10000"),
                L.s("从千万级泄露库中摘出的前 1 万条", "Top 10,000 from a ten-million-entry leak corpus"),
                BASE + "Passwords/Common-Credentials/xato-net-10-million-passwords-10000.txt",
                "MIT", L.s("约 10000 条", "~10,000 entries")));

        out.add(new Source(
                L.s("键盘走位组合", "Keyboard-walk combinations"),
                L.s("qwerty、1qaz2wsx 这类键盘相邻键组合", "Adjacent-key patterns such as qwerty and 1qaz2wsx"),
                BASE + "Passwords/Keyboard-Walks/Keyboard-Combinations.txt",
                "MIT", L.s("约 13000 条", "~13,000 entries")));

        out.add(new Source(
                L.s("10 万条通用口令", "100,000 common passphrases"),
                L.s("NCSC 统计的 10 万条，量大（不建议直接跑，可配合智能排序）", "NCSC's 100,000 — large (not advisable to run directly; pair with smart ranking)"),
                BASE + "Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt",
                "MIT", L.s("约 100000 条", "~100,000 entries")));

        out.add(new Source(
                L.s("10k 最常用口令", "10k most common passphrases"),
                L.s("经典 10k 表", "The classic 10k table"),
                BASE + "Passwords/Common-Credentials/10k-most-common.txt",
                "MIT", L.s("约 10000 条", "~10,000 entries")));

        return out;
    }

    public interface Progress {
        void onStart(int totalBytes);
        void onProgress(long downloaded, int totalBytes);
        void onDone(File file, int lines);
        void onError(String message);
    }

    /**
     * 下载一个源到目标目录。会边下边过滤非法长度并去重，
     * 避免把 63 位以上的垃圾也写进去。
     */
    public static void download(Source src, File destDir, Progress cb) {
        HttpURLConnection conn = null;
        try {
            if (!destDir.exists()) destDir.mkdirs();
            String safeName = src.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = new File(destDir, safeName + ".txt");

            conn = (HttpURLConnection) new URL(src.url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WPAForge/0.5");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != 200) {
                cb.onError(L.s("服务器返回 ", "Server returned ") + code);
                return;
            }
            int total = conn.getContentLength();
            cb.onStart(total);

            // 边读边过滤
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            long downloaded = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    downloaded += line.length() + 1;
                    String t = line.trim();
                    if (t.length() >= 8 && t.length() <= 63) seen.add(t);
                    if (seen.size() % 500 == 0) cb.onProgress(downloaded, total);
                }
            }

            try (FileOutputStream fo = new FileOutputStream(out)) {
                for (String s : seen) {
                    fo.write((s + "\n").getBytes("UTF-8"));
                }
            }
            cb.onDone(out, seen.size());
        } catch (Exception e) {
            cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

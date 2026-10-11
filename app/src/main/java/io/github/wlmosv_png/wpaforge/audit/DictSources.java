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

    /**
     * 国内可用的 GitHub 镜像前缀。
     *
     * 为什么需要：raw.githubusercontent.com 在国内网络下经常直连不通，
     * 用户点下载只能看到超时。这里按**实测速度**排序，
     * 逐个尝试，哪个先通就用哪个。
     *
     * 空字符串表示直连原站。
     */
    private static final String[] MIRRORS = {
            "https://ghproxy.net/",
            "https://ghfast.top/",
            "",
    };

    /** 对一个原始 URL 生成全部候选地址（镜像优先） */
    public static String[] candidateUrls(String rawUrl) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String m : MIRRORS) {
            out.add(m.isEmpty() ? rawUrl : m + rawUrl);
        }
        return out.toArray(new String[0]);
    }

    /** 镜像名称，用于在界面上告诉用户实际走的哪条路 */
    public static String mirrorLabel(String url) {
        if (url == null) return "";
        if (url.contains("ghproxy.net")) return "ghproxy";
        if (url.contains("ghfast.top")) return "ghfast";
        if (url.startsWith("https://raw.githubusercontent.com")) return "GitHub 直连";
        return "";
    }

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

    /** 进度回调。onNote 有默认空实现，旧调用方不用改。 */
    public interface Progress {
        default void onNote(String line) { }
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
            if (destDir == null) {
                cb.onError("字典目录不可用：无法写入任何存储位置");
                return;
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                cb.onError("无法创建字典目录: " + destDir.getAbsolutePath()
                        + "\n请检查存储权限或剩余空间");
                return;
            }
            // 写探针确认真的可写，否则文件选择器会给出难懂的 ENOENT
            try {
                File probe = new File(destDir, ".probe");
                try (FileOutputStream f = new FileOutputStream(probe)) { f.write(1); }
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
            } catch (Exception e) {
                cb.onError("字典目录不可写: " + destDir.getAbsolutePath()
                        + "\n" + e.getClass().getSimpleName());
                return;
            }
            String safeName = src.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = new File(destDir, safeName + ".txt");

            // 多镜像轮询：逐个试，哪个先通就用哪个。
            // 原来只连 raw.githubusercontent.com，国内网络下基本必失败。
            String[] urls = candidateUrls(src.url);
            int code = -1;
            String lastErr = "";
            for (int i = 0; i < urls.length; i++) {
                String u = urls[i];
                String label = mirrorLabel(u);
                if (i > 0 && cb != null) {
                    cb.onNote(L.f("正在尝试镜像: %s", "Trying mirror: %s", label));
                }
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "WPAForge/0.6");
                    c.connect();
                    int cc = c.getResponseCode();
                    if (cc == 200) {
                        conn = c;
                        code = cc;
                        if (cb != null && !label.isEmpty()) {
                            cb.onNote(L.f("已连上: %s", "Connected via: %s", label));
                        }
                        break;
                    }
                    lastErr = L.s("服务器返回 ", "Server returned ") + cc;
                    c.disconnect();
                } catch (Exception e) {
                    // 这个镜像不行，换下一个
                    lastErr = e.getClass().getSimpleName();
                    if (c != null) {
                        try { c.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
            if (code != 200 || conn == null) {
                cb.onError(L.s("所有下载通道都不可用",
                        "All download channels are unavailable")
                        + "\n" + L.s("最后错误: ", "Last error: ") + lastErr
                        + "\n" + L.s("请检查网络，或用其他方式导入字典文件。",
                                "Check your network, or import a dictionary file manually."));
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

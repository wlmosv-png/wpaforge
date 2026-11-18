package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 本机已保存 WiFi 口令查询。
 *
 * 用途：自查。把你手机里存着的 WiFi 口令读出来，并评估哪些是弱口令。
 * 这比逐条爆破快得多 —— 对自家的网络，这是最快的自查方式。
 *
 * 读取路径（依次尝试，全部只做「能不能读」的探测，不按版本号猜）：
 *   1. /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml   (Android 11+，Wi-Fi 模块化之后)
 *   2. /data/misc/wifi/WifiConfigStore.xml                        (Android 10 及更早)
 *   3. /data/misc/wifi/WifiConfigStore.xml.bak
 *
 * 注意第 1 条注释原来写的是「Android 10+」，那是错的：Wi-Fi 到 Android 11
 * 才成为 Mainline 模块、配置才搬到 apexdata。10 及更早一直用
 * /data/misc/wifi/，所以两条路径都必须认。
 *
 * 口令在 XML 里是明文存储的（Android 就是这么做），
 * 所以只要能读到文件就能拿到口令。
 */
public final class SavedWifiReader {

    public static final class Saved {
        public final String ssid;
        public final String password;   // 可能为空（开放网络）
        public final String security;

        public Saved(String ssid, String password, String security) {
            this.ssid = ssid;
            this.password = password == null ? "" : password;
            this.security = security;
        }

        public boolean hasPassword() {
            return !password.isEmpty();
        }
    }

    private static final String[] CANDIDATE_PATHS = {
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml.bak"
    };

    private SavedWifiReader() {}

    /** 检查能否读取，返回空串表示可用 */
    public static String checkAccess() {
        String rootIssue = RootProber.checkRoot();
        if (!rootIssue.isEmpty()) return rootIssue;
        for (String p : CANDIDATE_PATHS) {
            String out = RootProber.exec(null, new String[]{"su", "-c",
                    "test -r " + p + " && echo READABLE"}, 6000);
            if (out != null && out.contains("READABLE")) return "";
        }
        return L.s("找不到可读的 WiFi 配置文件（可能本机 ROM 结构不同）", "No readable Wi-Fi config file found (this ROM may differ)");
    }

    /**
     * 这段 XML 看起来是不是 WiFi 配置存储？
     *
     * <p>为什么不能只看 {@code <string name="SSID">}（旧判据）：那是 Android 11+
     * 之前的结构；11 起 SSID 搬进 ConfigKey，条目里根本没有这个标签。
     * 只看它 → 新系统上恒判「不是配置」→ 一条都读不出来。
     *
     * <p>反过来同样不行：只要 ConfigKey/KeyMgmt 也不够，旧系统未必有这两个标签。
     * 所以认**任一**特征：SSID 字段、ConfigKey、KeyMgmt。
     */
    private static boolean looksLikeConfig(String xml) {
        if (xml == null) return false;
        return xml.contains("<string name=\"SSID\">")
                || xml.contains("<string name=\"ConfigKey\">")
                || xml.contains("<string name=\"KeyMgmt\">");
    }

    /** 读取全部已保存网络 */
    public static List<Saved> read() {
        List<Saved> out = new ArrayList<>();
        for (String path : CANDIDATE_PATHS) {
            String xml = RootProber.exec(null, new String[]{"su", "-c", "cat " + path}, 12000);
            if (!looksLikeConfig(xml)) continue;
            parse(xml, out);
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** 解析给定的 WifiConfigStore XML 文本（不执行任何命令）。 */
    public static List<Saved> parseXml(String xml) {
        List<Saved> out = new ArrayList<>();
        if (looksLikeConfig(xml)) parse(xml, out);
        return out;
    }

    /** 在已解析列表里按 SSID 精确查找；找不到返回 {@code null}。 */
    public static Saved find(List<Saved> list, String ssid) {
        if (list == null || ssid == null) return null;
        for (Saved s : list) if (ssid.equals(s.ssid)) return s;
        return null;
    }

    /** 直接从 XML 文本里找目标的已保存配置；找不到返回 {@code null}。 */
    public static Saved findIn(String xml, String ssid) {
        return find(parseXml(xml), ssid);
    }

    /**
     * 读取全部已保存网络（返回第一个可读路径的结果）。
     *
     * <p>与 {@link #read()} 同一实现，语义更直白的别名 —— 供恢复流程复用。
     */
    public static List<Saved> readAll() {
        return read();
    }

    /**
     * 解析 WifiConfigStore.xml。
     *
     * <p>结构是若干个 {@code <Network>…</Network>}，SSID 与 PreSharedKey 成对出现。
     * **两种版本的字段名不同**，都要认：
     * <pre>
     *   Android 11+  &lt;string name="ConfigKey"&gt;"SSID"WPA_PSK&lt;/string&gt;
     *                &lt;string name="PreSharedKey"&gt;"口令"&lt;/string&gt;
     *                （没有 SSID 字段，SSID 在 ConfigKey 里，带引号）
     *
     *   Android 10-  &lt;string name="SSID"&gt;&amp;quot;我的网&amp;quot;&lt;/string&gt;
     *                &lt;string name="PreSharedKey"&gt;&amp;quot;口令&amp;quot;&lt;/string&gt;
     *                &lt;string name="KeyMgmt"&gt;WPA_PSK&lt;/string&gt;
     *                （安全类型在 KeyMgmt，不在 ConfigKey）
     * </pre>
     * 两个字段都要认：只读 {@code SSID} 会丢掉 Android 11+ 的条目，
     * 只读 {@code ConfigKey} 会丢掉 Android 10- 的条目 —— 两条路都必须走。
     */
    private static void parse(String xml, List<Saved> out) {
        // 按 <network> 或 <Network> 切分
        String lower = xml.toLowerCase();
        int pos = 0;
        while (true) {
            int start = lower.indexOf("<network>", pos);
            if (start < 0) break;
            int end = lower.indexOf("</network>", start);
            if (end < 0) break;
            String block = xml.substring(start, end);
            pos = end + 1;

            String ssid = tagValue(block, "SSID");
            String psk = tagValue(block, "PreSharedKey");

            /* 安全类型 + SSID 的兜底：先看新版字段 ConfigKey，
             * 再看旧版字段 KeyMgmt。两者都是「这一条是不是 WPA_PSK」，
             * 只是字段名不同 —— 不能只认一个。 */
            String sec = tagValue(block, "ConfigKey");
            if (sec == null || sec.isEmpty()) sec = tagValue(block, "KeyMgmt");

            /* SSID 兜底：Android 11+ 的 <Network> 里没有 SSID 字段，
             * SSID 只存在于 ConfigKey 的第一段：
             *   ConfigKey = "SSID"WPA_PSK
             * 取第一个引号到第二个引号之间的内容。
             * 这一步必须在「判空跳过」**之前**做，否则新版结构会被整条丢弃。 */
            if (ssid == null || ssid.isEmpty()) {
                ssid = ssidFromConfigKey(sec);
            }

            if (ssid == null || ssid.isEmpty()) continue;
            // XML 里的值是带引号的，去掉
            ssid = stripQuotes(ssid);
            if (psk != null) psk = stripQuotes(psk);

            String security = "WPA";
            /* 大小写不敏感：旧实现在原始串上 contains("WPA_PSK")，
             * 而旧版配置 <string name="KeyMgmt">WPA_PSK</string> 恰好是大写才凑巧命中。
             * 统一先转大写再判断，两种格式都稳。 */
            String secU = sec == null ? "" : sec.toUpperCase(java.util.Locale.ROOT);
            if (secU.contains("WPA_PSK")) security = "WPA-PSK";
            else if (secU.contains("SAE")) security = "WPA3";
            else if (secU.contains("WEP")) security = "WEP";
            else if (secU.contains("NONE")) security = L.s("开放", "Open");

            /* 同一 SSID 可能有多条配置（系统里真实存在，例如换过密码的旧条目）。
             * 不在这里去重 —— 顺序与条数和改动前一致，界面照旧全部列出。 */
            out.add(new Saved(ssid, psk == null ? "" : psk, security));
        }
    }

    private static String tagValue(String block, String tag) {
        // 两种写法：<string name="SSID">值</string>
        String pattern = "name=\"" + tag + "\"";
        int i = block.indexOf(pattern);
        if (i < 0) return null;
        int gt = block.indexOf('>', i);
        if (gt < 0) return null;
        int lt = block.indexOf("</string>", gt);
        if (lt < 0) return null;
        return block.substring(gt + 1, lt).trim();
    }

    /**
     * 从 ConfigKey 里取 SSID。
     *
     * <p>样例：{@code "我的网"WPA_PSK} / {@code "MyNet"WPA2_PSK} / {@code "Open"None}。
     * 规则：取第一个 {@code "} 到下一个 {@code "} 之间的内容；取不到就返回 {@code null}
     * （**不猜**：宁可认不出这一条，也不能把安全类型当 SSID 用）。
     *
     * <p>为什么需要它：Android 11+ 的 WifiConfigStore.xml 里，
     * {@code <Network>} 内**没有 SSID 字段**，SSID 只以这种形式存在于 ConfigKey。
     */
    static String ssidFromConfigKey(String configKey) {
        if (configKey == null) return null;
        String v = configKey.replace("&quot;", "\"").trim();
        int open = v.indexOf('"');
        if (open < 0) return null;
        int close = v.indexOf('"', open + 1);
        if (close < 0) return null;
        String ssid = v.substring(open + 1, close);
        return ssid.isEmpty() ? null : ssid;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.replace("&quot;", "\"").trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * 评估一条口令的强度。返回 0~100，越大越弱。
     */
    public static int weaknessScore(String pw) {
        if (pw == null || pw.isEmpty()) return 0;
        int score = 0;
        int len = pw.length();
        if (len <= 8) score += 40;
        else if (len <= 10) score += 25;
        else if (len <= 12) score += 10;

        if (pw.matches("\\d+")) score += 30;
        else if (pw.matches("[A-Za-z]+")) score += 15;

        if (pw.matches("(.)\\1+")) score += 25;              // 全重复字符
        if (pw.toLowerCase().contains("1234")) score += 12;
        if (pw.toLowerCase().contains("password")) score += 20;
        if (pw.toLowerCase().contains("admin")) score += 15;
        if (pw.matches(".*(20\\d\\d)$")) score += 10;        // 年份结尾

        return Math.min(100, score);
    }

    public static String weaknessLabel(int score) {
        if (score >= 70) return L.s("极弱", "Very weak");
        if (score >= 50) return L.s("偏弱", "Weak");
        if (score >= 30) return L.s("一般", "Fair");
        return L.s("尚可", "Acceptable");
    }
}

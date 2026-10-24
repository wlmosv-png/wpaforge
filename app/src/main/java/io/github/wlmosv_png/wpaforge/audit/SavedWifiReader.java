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
 * 读取路径（按 Android 版本依次尝试）：
 *   1. /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml   (Android 10+)
 *   2. /data/misc/wifi/WifiConfigStore.xml                        (旧版)
 *   3. cmd wifi list-networks（只能拿到名字，拿不到口令）
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

    /** 读取全部已保存网络 */
    public static List<Saved> read() {
        List<Saved> out = new ArrayList<>();
        for (String path : CANDIDATE_PATHS) {
            String xml = RootProber.exec(null, new String[]{"su", "-c", "cat " + path}, 12000);
            if (xml == null || !xml.contains("<string name=\"SSID\">")) continue;
            parse(xml, out);
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** 解析给定的 WifiConfigStore XML 文本（不执行任何命令）。 */
    public static List<Saved> parseXml(String xml) {
        List<Saved> out = new ArrayList<>();
        if (xml != null && xml.contains("<string name=\"SSID\">")) parse(xml, out);
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
     * 结构是若干个 <Network>…</Network>，里面 SSID 与 PreSharedKey 成对出现。
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
            String sec = tagValue(block, "ConfigKey");

            if (ssid == null || ssid.isEmpty()) continue;
            // XML 里的值是带引号的，去掉
            ssid = stripQuotes(ssid);
            if (psk != null) psk = stripQuotes(psk);

            String security = "WPA";
            if (sec != null && sec.contains("WPA_PSK")) security = "WPA-PSK";
            else if (sec != null && sec.contains("SAE")) security = "WPA3";
            else if (sec != null && sec.contains("WEP")) security = "WEP";
            else if (sec != null && sec.contains("NONE")) security = L.s("开放", "Open");

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

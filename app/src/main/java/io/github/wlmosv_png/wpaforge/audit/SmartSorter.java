package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 智能排序器。
 *
 * 问题背景：字典动辄几十万条，而手机逐条连接约 7 秒/条。
 * 跑十万条要 8 天，不现实。所以真正要解决的是——
 * 从大字典里挑出最可能命中的前 N 条。
 *
 * 排序依据（分越高越优先）：
 *   1. 目标线索贴合度：SSID 文本、SSID 里的数字、BSSID 尾号
 *   2. 通用高频弱口令（这类占比虽小，但绝对命中数最多）
 *   3. 长度接近 8~11 位（人类设的口令长度习惯）
 *   4. 纯数字 > 纯字母 > 混合（中文用户的实际分布）
 *   5. 含年份/常见后缀
 *
 * 注意：这里只是重排顺序，不增删任何候选，命中率不受影响，
 * 只是让正确的那条更早出现。
 */
public final class SmartSorter {

    /** 打分后的候选 */
    public static final class Scored implements Comparable<Scored> {
        public final Candidate candidate;
        public final int score;
        public final String reason;

        Scored(Candidate c, int score, String reason) {
            this.candidate = c;
            this.score = score;
            this.reason = reason;
        }

        @Override
        public int compareTo(Scored o) {
            // 分高的在前；同分保持原顺序（稳定）
            return Integer.compare(o.score, this.score);
        }
    }

    /** 内置通用高频口令（公开统计里出现频率最高的那一批） */
    private static final String[] TOP_WEAK = {
            "12345678", "123456789", "1234567890", "88888888", "66666666",
            "11111111", "00000000", "12345679", "87654321", "98765432",
            "password", "Password", "password1", "a1234567", "abc12345",
            "qwertyuiop", "1qaz2wsx", "iloveyou", "5201314", "11223344"
    };

    /** 常见年份/后缀 */
    private static final String[] HOT_SUFFIX = {
            "123", "1234", "12345", "520", "521", "000", "888", "666",
            "2020", "2021", "2022", "2023", "2024", "2025", "2026", "8", "88"
    };

    private SmartSorter() {}

    /**
     * 按目标重排候选，返回前 limit 条（limit<=0 表示全排）。
     * 原始顺序里靠前的会被轻微加权，让字典作者的排序意图不至于完全丢失。
     */
    public static List<Candidate> prioritize(NetRecord target, List<Candidate> dict, int limit) {
        if (dict == null || dict.isEmpty()) return new ArrayList<>();

        String ssid = target == null ? "" : target.ssid;
        String ssidLower = ssid.toLowerCase();
        String ssidDigits = digitsOf(ssid);
        String macTail = "";
        if (target != null && target.bssid != null) {
            String mac = target.bssid.replace(":", "");
            if (mac.length() >= 6) macTail = mac.substring(mac.length() - 6).toLowerCase();
        }

        List<Scored> scored = new ArrayList<>(dict.size());
        for (int i = 0; i < dict.size(); i++) {
            Candidate c = dict.get(i);
            int s = score(c, ssidLower, ssidDigits, macTail, i, dict.size());
            scored.add(new Scored(c, s, ""));
        }

        Collections.sort(scored);

        int n = limit <= 0 ? scored.size() : Math.min(limit, scored.size());
        List<Candidate> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(scored.get(i).candidate);
        return out;
    }

    private static int score(Candidate c, String ssidLower, String ssidDigits,
                             String macTail, int index, int total) {
        String p = c.password;
        String pl = p.toLowerCase();
        int s = 0;

        // ---- 1. 目标线索（权重最高） ----
        if (!ssidLower.isEmpty()) {
            // 与 SSID 完全一致
            if (pl.equals(ssidLower)) s += 900;
            // 以 SSID 开头（SSID + 后缀）
            if (pl.startsWith(ssidLower)) s += 400;
            // 包含 SSID
            if (pl.contains(ssidLower)) s += 250;
        }
        if (!ssidDigits.isEmpty()) {
            // 含 SSID 里的数字串
            if (pl.contains(ssidDigits)) s += 350;
            // SSID 数字 + 常见后缀
            for (String sf : HOT_SUFFIX) {
                if (pl.equals(ssidDigits + sf)) { s += 300; break; }
            }
            // 数字重复（209209）
            if (pl.equals(ssidDigits + ssidDigits)) s += 280;
        }
        if (!macTail.isEmpty()) {
            if (pl.contains(macTail)) s += 320;
            if (pl.equals(macTail)) s += 200;
        }

        // ---- 2. 通用高频弱口令 ----
        for (int i = 0; i < TOP_WEAK.length; i++) {
            if (TOP_WEAK[i].equalsIgnoreCase(p)) {
                s += 260 - i * 4;    // 越靠前分越高
                break;
            }
        }

        // ---- 3. 长度习惯（8~11 位最常见） ----
        int len = p.length();
        if (len == 8) s += 60;
        else if (len == 9) s += 50;
        else if (len == 10) s += 45;
        else if (len == 11) s += 40;
        else if (len > 16) s -= 40;

        // ---- 4. 字符构成（中文用户偏数字） ----
        boolean allDigits = p.matches("\\d+");
        boolean allAlpha = p.matches("[A-Za-z]+");
        if (allDigits) s += 45;
        else if (allAlpha) s += 10;
        else s += 20;

        // ---- 5. 后缀加成 ----
        for (String sf : HOT_SUFFIX) {
            if (pl.endsWith(sf)) { s += 30; break; }
        }

        // ---- 6. 大量重复字符（11111111） ----
        if (isAllSameChar(p)) s += 55;

        // ---- 7. 字典里的原始顺序：轻微加权 ----
        if (total > 0) {
            s += (int) (40.0 * (1.0 - (double) index / total));
        }

        return s;
    }

    private static String digitsOf(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    private static boolean isAllSameChar(String s) {
        if (s.length() < 2) return false;
        char first = s.charAt(0);
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != first) return false;
        }
        return true;
    }

    /** 给界面用：说明排序依据 */
    public static String describe(NetRecord target) {
        if (target == null) return L.s("未选择目标，按通用弱口令优先排序", "No target selected; sorting by common weak passphrases");
        StringBuilder sb = new StringBuilder(L.s("排序依据: ", "Ranked by: "));
        sb.append("SSID「").append(target.ssid).append("」");
        String d = digitsOf(target.ssid);
        if (!d.isEmpty()) sb.append(L.s("、其中数字「", ", digits “")).append(d).append("」");
        if (target.bssid != null && !target.bssid.isEmpty()) {
            sb.append(L.s("、BSSID 尾号", ", BSSID tail"));
        }
        sb.append(L.s("，再叠加通用弱口令", ", plus common weak passphrases"));
        return sb.toString();
    }
}

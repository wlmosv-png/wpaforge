package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 目标专属字典生成器。
 *
 * 解决的问题：智能排序只能重排"字典里已有的"候选。
 * 如果字典里没有 "FAST_E972888" 这种派生口令，它永远排不出来。
 *
 * 本类根据目标的 SSID 文本与 BSSID，现场生成候选：
 *   - SSID 原文与各种大小写变形
 *   - SSID 里的数字串及其重复/加后缀
 *   - 去掉分隔符后的合并串
 *   - SSID 与数字的多种拼接
 *   - BSSID 尾号族
 *   - 再叠加通用弱口令与厂商规则
 *
 * 这些都排在候选队列最前面，几百条就能覆盖最常见的场景。
 * 全部是文本派生运算，不依赖任何第三方数据。
 */
public final class TargetDictBuilder {

    /** 常见连接符 */
    private static final char[] SEPARATORS = {'_', '-', '.', ' '};

    /** 高频数字后缀 */
    private static final String[] NUM_SUFFIX = {
            "0", "00", "000", "1", "11", "111", "12", "123", "1234", "12345",
            "123456", "520", "521", "1314", "888", "8888", "666", "6666",
            "999", "9999", "0000", "1111", "2023", "2024", "2025", "2026"
    };

    /** 高频字母后缀 */
    private static final String[] ALPHA_SUFFIX = {
            "a", "ab", "abc", "wifi", "wlan", "admin", "net", "root"
    };

    private TargetDictBuilder() {}

    public static final class Result {
        public final List<Candidate> candidates;
        /** 生成说明，用于界面展示 */
        public final String explanation;

        Result(List<Candidate> c, String e) {
            this.candidates = c;
            this.explanation = e;
            }
    }

    /**
     * 根据目标生成专属候选。
     *
     * @param target 目标网络
     * @param max    最多生成多少条
     */
    public static Result build(NetRecord target, int max) {
        if (target == null) {
            return new Result(new ArrayList<>(), L.s("未选择目标", "No target selected"));
        }
        int limit = max <= 0 ? 2000 : max;

        LinkedHashSet<String> pool = new LinkedHashSet<>();
        List<String> categories = new ArrayList<>();

        String ssid = target.ssid == null ? "" : target.ssid.trim();
        String ssidLower = ssid.toLowerCase();
        String ssidUpper = ssid.toUpperCase();
        String digits = digitsOf(ssid);
        String letters = lettersOf(ssid);
        String compact = ssid.replaceAll("[^A-Za-z0-9]", "");

        // 按类别分桶，最后轮转交错 —— 保证前几十条就覆盖所有类别，
        // 而不是某一类（比如 SSID+后缀）把前面全占满。
        List<List<String>> buckets = new ArrayList<>();

        // A. SSID 原文与核心变形
        List<String> a = new ArrayList<>();
        if (!ssid.isEmpty()) {
            a.add(ssid); a.add(ssidLower); a.add(ssidUpper);
            a.add(capitalize(ssidLower)); a.add(compact); a.add(compact.toLowerCase());
        }
        if (!a.isEmpty()) { buckets.add(a); categories.add(L.s("SSID 原文", "SSID literal")); }

        // B. SSID 里的数字族
        List<String> b = new ArrayList<>();
        if (!digits.isEmpty()) {
            b.add(digits + digits);
            b.add(digits + digits + digits);
            for (String sf : NUM_SUFFIX) b.add(digits + sf);
            for (String sf : new String[]{"8", "88", "6", "66", "9", "99"}) {
                b.add(digits + sf + digits);
            }
            if (!letters.isEmpty()) {
                b.add(digits + letters.toLowerCase());
                b.add(digits + capitalize(letters.toLowerCase()));
            }
        }
        if (!b.isEmpty()) { buckets.add(b); categories.add(L.s("数字族", "digit family")); }

        // C. SSID + 数字后缀
        List<String> c = new ArrayList<>();
        if (!ssid.isEmpty()) {
            for (String sf : NUM_SUFFIX) {
                c.add(ssidLower + sf);
                c.add(compact + sf);
                c.add(compact.toLowerCase() + sf);
            }
        }
        if (!c.isEmpty()) { buckets.add(c); categories.add(L.s("SSID+数字", "SSID+digits")); }

        // D. BSSID 尾号族
        List<String> d = new ArrayList<>();
        if (target.bssid != null) {
            String mac = target.bssid.replace(":", "").replace("-", "");
            if (mac.length() >= 8) {
                String tail6 = mac.substring(mac.length() - 6).toLowerCase();
                d.add(tail6);
                d.add(tail6.toUpperCase());
                for (String sf : NUM_SUFFIX) d.add(tail6 + sf);
            }
        }
        if (!d.isEmpty()) { buckets.add(d); categories.add(L.s("BSSID 尾号", "BSSID tail")); }

        // E. SSID 字母部分 + 数字
        List<String> e = new ArrayList<>();
        if (letters.length() >= 3) {
            e.add(letters); e.add(letters.toLowerCase());
            e.add(capitalize(letters.toLowerCase()));
            for (String sf : NUM_SUFFIX) {
                e.add(letters.toLowerCase() + sf);
                e.add(capitalize(letters.toLowerCase()) + sf);
            }
        }
        if (!e.isEmpty()) { buckets.add(e); categories.add(L.s("字母部分", "letter part")); }

        // F. SSID + 字母后缀
        List<String> f = new ArrayList<>();
        if (!ssid.isEmpty()) {
            for (String sf : ALPHA_SUFFIX) {
                f.add(ssidLower + sf);
                f.add(compact + sf);
                f.add(compact.toLowerCase() + sf);
            }
        }
        if (!f.isEmpty()) { buckets.add(f); categories.add(L.s("SSID+字母", "SSID+letters")); }

        // G. 片段交叉组合（FAST_E972 -> E972、E972FAST、E972E972…）
        List<String> g2 = new ArrayList<>();
        {
            String[] parts = ssid.split("[_\\-.\\s]+");
            if (parts.length >= 2) {
                for (String part : parts) {
                    if (part.isEmpty() || part.length() < 2) continue;
                    g2.add(part);
                    g2.add(part.toLowerCase());
                    g2.add(capitalize(part.toLowerCase()));
                    // 每个片段 + 数字后缀
                    for (String sf : NUM_SUFFIX) {
                        g2.add(part.toLowerCase() + sf);
                    }
                }
                // 片段交叉
                for (int i = 0; i < parts.length; i++) {
                    for (int j = 0; j < parts.length; j++) {
                        if (i == j) continue;
                        g2.add(parts[j] + parts[i]);
                        g2.add(parts[j].toLowerCase() + parts[i].toLowerCase());
                        g2.add(parts[i] + parts[j]);
                        g2.add(parts[i].toLowerCase() + parts[j].toLowerCase());
                    }
                }
                // 片段重复（E972E972）
                for (String part : parts) {
                    if (part.length() < 2) continue;
                    g2.add(part + part);
                    g2.add(part.toLowerCase() + part.toLowerCase());
                }
            }
        }
        if (!g2.isEmpty()) { buckets.add(g2); categories.add(L.s("片段组合", "segment combinations")); }

        // H. 竞品级分级词元拆分与年份符号扩散 (Tokenize + Years + Symbol Matrix)
        List<String> h = new ArrayList<>();
        if (!ssid.isEmpty()) {
            // 一级拆分：按连接符、wifi、net、5g、2.4g 切割
            String[] l1 = ssid.split("[-_.\\s]+|(?i)@|(?i)wifi|(?i)wlan|(?i)net|(?i)5g|(?i)2\\.4g");
            java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
            for (String p : l1) {
                if (p == null) continue;
                String pt = p.trim();
                if (pt.isEmpty()) continue;
                tokens.add(pt.toLowerCase());
                if (pt.matches("^[a-zA-Z]+$")) {
                    tokens.add(capitalize(pt.toLowerCase()));
                }
                // 二级拆分：按字母/数字交界分割
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("[a-zA-Z]+|\\d+").matcher(pt);
                int count = 0;
                List<String> sub = new ArrayList<>();
                while (m2.find()) {
                    sub.add(m2.group());
                    count++;
                }
                if (count > 1) {
                    for (String sp : sub) {
                        tokens.add(sp.toLowerCase());
                        if (sp.matches("^[a-zA-Z]+$")) {
                            tokens.add(capitalize(sp.toLowerCase()));
                        }
                    }
                }
            }

            int curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            String[] yrList = new String[]{
                String.valueOf(curYear), String.valueOf(curYear - 1),
                String.valueOf(curYear - 2), String.valueOf(curYear - 3)
            };

            for (String tk : tokens) {
                if (tk.isEmpty()) continue;
                String tkLow = tk.toLowerCase();
                String tkCap = capitalize(tkLow);
                boolean isLetter = tk.matches("^[a-zA-Z]+$");
                boolean isNum = tk.matches("^\\d+$");

                // 短词元自重复
                if (tk.length() <= 3) {
                    h.add(tkLow + tkLow);
                    if (isLetter) h.add(tkCap + tkLow);
                }

                // 词元 + 年份 / 符号年份
                for (String yr : yrList) {
                    h.add(tkLow + yr);
                    if (isLetter) h.add(tkCap + yr);
                    if (!isNum) {
                        h.add(tkLow + "@" + yr);
                        h.add(tkCap + "@" + yr);
                        h.add(yr + "@" + tkLow);
                        h.add(tkLow + "#" + yr);
                        h.add(tkCap + "#" + yr);
                    }
                }

                // 词元 + 高频后缀
                for (String sf : NUM_SUFFIX) {
                    h.add(tkLow + sf);
                    if (isLetter) h.add(tkCap + sf);
                }
            }
        }
        if (!h.isEmpty()) { buckets.add(h); categories.add(L.s("智能词元扩散", "token matrix")); }

        // 轮转交错：每轮从每个桶取 3 条。
        // 每桶独立游标；只要还有任意一个桶没读完就继续（不能因为
        // 某一轮恰好全是重复项就提前收工 —— 那会丢掉大量候选）。
        int[] cursor = new int[buckets.size()];
        while (pool.size() < limit) {
            boolean anyActive = false;
            for (int bi = 0; bi < buckets.size(); bi++) {
                List<String> bucket = buckets.get(bi);
                int taken = 0;
                while (taken < 6 && cursor[bi] < bucket.size()) {
                    String v = bucket.get(cursor[bi]++);
                    taken++;
                    if (v == null || v.isEmpty()) continue;
                    add(pool, v, limit);
                }
                if (cursor[bi] < bucket.size()) anyActive = true;
            }
            if (!anyActive) break;   // 所有桶都读完了
        }

        // 兜底：通用弱口令
        int beforeWeak = pool.size();
        DictGenerator g = new DictGenerator(400).classic().numericPatterns();
        for (Candidate cw : g.build()) {
            if (pool.size() >= limit) break;
            add(pool, cw.password, limit);
        }
        if (pool.size() > beforeWeak) categories.add(L.s("通用弱口令", "common weak"));

        List<Candidate> out = new ArrayList<>();
        for (String pw : pool) {
            out.add(new Candidate(pw, Candidate.Origin.VENDOR_RULE));
            if (out.size() >= limit) break;
        }

        String explain = categories.isEmpty()
                ? L.s("该 SSID 没有可派生的特征，仅生成通用弱口令", "This SSID has no derivable features; only common weak passphrases generated")
                : L.s("按目标派生: ", "Derived from target: ") + String.join(" / ", categories);
        return new Result(out, explain);
    }

    /**
     * 加入候选。
     *
     * 关键：WPA 口令最短 8 位。但用户想设的往往是 6~7 位的短串
     * （"972888"、"209209"），系统会强迫他们补到 8 位。
     * 所以这里对不足 8 位的，自动补几种最可能的填法，而不是直接丢弃。
     */
    private static void add(LinkedHashSet<String> pool, String pw, int limit) {
        if (pool.size() >= limit) return;
        if (pw == null) return;
        String p = pw.trim();
        if (p.isEmpty()) return;

        if (p.length() >= 8 && p.length() <= 63) {
            pool.add(p);
            return;
        }
        if (p.length() > 63) return;

        // 不足 8 位：按最可能的补法扩展
        int need = 8 - p.length();
        if (need <= 0) { pool.add(p); return; }

        // 补重复自己（972888 -> 97288897）
        StringBuilder rep = new StringBuilder(p);
        while (rep.length() < 8) rep.append(p);
        pool.add(rep.substring(0, 8));

        // 补 0
        StringBuilder z = new StringBuilder(p);
        while (z.length() < 8) z.append('0');
        pool.add(z.toString());

        // 补 8
        StringBuilder e = new StringBuilder(p);
        while (e.length() < 8) e.append('8');
        pool.add(e.toString());

        // 补 1
        StringBuilder o = new StringBuilder(p);
        while (o.length() < 8) o.append('1');
        pool.add(o.toString());

        if (need <= 2) {
            // 差得不多时，再多补一种：前缀 0
            String pre = "0".repeat(need) + p;
            pool.add(pre);
        }
    }

    private static String digitsOf(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isDigit(c)) sb.append(c);
        return sb.toString();
    }

    private static String lettersOf(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isLetter(c)) sb.append(c);
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

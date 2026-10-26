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
 *   - 画像（手机号 / 姓名拼音 / 生日 / 门牌 / 宽带账号 / 旧口令，见 SeedDeriver）
 *     分三层：原值 → 互拼 → 追加
 *   - 内置中文高频口令表（cn_top.txt）头部
 *   - 通用弱口令（含经典弱口令与纯数字规律）
 *   - SSID 原文与各种大小写变形（**默认关闭**，见 SeedModel.useSsid）
 *
 * 桶序 = 优先级，直出（不做轮转）。理由见下方注释 —— 实测命中的中位位置是第 1 条。
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

    /** 高频数字后缀。（包内可见：SeedDeriver 的线索展开复用同一份，避免两套规则漂移） */
    static final String[] NUM_SUFFIX = {
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
        return build(target, max, null, null, null);
    }

    /**
     * 带厂商名的派生。
     *
     * @param vendorName OUI 表查到的厂商名，可为 null（查不到就不生成厂商词元桶）
     */
    public static Result build(NetRecord target, int max, String vendorName) {
        return build(target, max, vendorName, null, null);
    }

    /** 带线索、不带内置中文高频表的派生（测试与旧调用方用这条） */
    public static Result build(NetRecord target, int max, String vendorName, SeedModel seeds) {
        return build(target, max, vendorName, seeds, null);
    }

    /**
     * 全参派生。
     *
     * @param seeds    用户线索，可为 null 或空（为空时行为与「只有 SSID」的旧版本一致）
     * @param cnCommon 内置中文高频口令表，可为 null（加载不到就不生成该桶）
     */
    public static Result build(NetRecord target, int max, String vendorName, SeedModel seeds,
                               List<String> cnCommon) {
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

        /* ── 桶序即优先级，直出、不轮转 ──────────────────────────────
         * 本机 107 次真实命中的实测：中位命中位置 = 第 1 条，90 分位 = 第 3 条，
         * 104/107 落在前 50 条内。所以这里要的不是"每个桶都公平覆盖"，
         * 而是"前 50 条只放高密度形态" —— 轮转恰恰会把密度平均掉。 */
        List<List<String>> buckets = new ArrayList<>();
        List<Integer> caps = new ArrayList<>();

        /* ── J. 画像（原值 / 互拼 / 追加）──
         * 用户填进来的是"关于这个网的事实"，A~I 全是猜测 —— 事实必须排在猜测前面。
         * 三层各自有上限：原值极少而精，互拼和追加按主流工具的做法封顶。 */
        if (seeds != null && !seeds.isEmpty()) {
            List<String> v = new ArrayList<>(), c = new ArrayList<>(), m = new ArrayList<>();
            for (SeedDeriver.Item it : SeedDeriver.profile(seeds)) {
                if (it.tier == SeedDeriver.Tier.VALUE) v.add(it.value);
                else if (it.tier == SeedDeriver.Tier.COMBO) c.add(it.value);
                else m.add(it.value);
            }
            if (!v.isEmpty()) {
                buckets.add(v); caps.add(20);
                categories.add(L.s("画像·原值", "profile values"));
            }
            if (!c.isEmpty()) {
                buckets.add(c); caps.add(SeedDeriver.MAX_COMBO);
                categories.add(L.s("画像·互拼", "profile combos"));
            }
            if (!m.isEmpty()) {
                buckets.add(m); caps.add(SeedDeriver.MAX_MANGLE);
                categories.add(L.s("画像·追加", "profile appends"));
            }
        }

        /* ── L. 通用弱口令（排在中文高频之前）──
         * 实测：107 次命中里 57 次（53%）来自这张 27KB 的小表 —— 密度最高的一块。
         * 旧版把它写在循环末尾当"兜底"，几乎永远轮不上。
         * 排序按证据：**本机实测密度** > 我整理的词表。 */
        {
            List<String> l = new ArrayList<>();
            for (Candidate cw : new DictGenerator(400).classic().numericPatterns().build()) {
                l.add(cw.password);
            }
            if (!l.isEmpty()) {
                buckets.add(l);
                caps.add(40);
                categories.add(L.s("通用弱口令", "common weak"));
            }
        }

        /* ── K. 中文高频口令（只取头部 30 条）──
         * 旧版只有英文数据集（password / sunshine / football），国内分布完全不在里面。
         * 表由 assets/builtin/cn_top.txt 提供，加载不到就不生成该桶 —— 不硬编码替代品。 */
        if (cnCommon != null && !cnCommon.isEmpty()) {
            buckets.add(new ArrayList<>(cnCommon));
            caps.add(30);
            categories.add(L.s("中文高频", "Chinese common"));
        }

        /* 以下 A~I 全是"按 SSID 猜"。默认关闭 —— 本机 107 次真实命中里
         * 没有一次来自 SSID 派生，而它却是候选量最大的一块。
         * 想试的话在画像里打开开关；打开后它排在所有画像候选之后。 */
        int ssidStart = buckets.size();
        int catSsidStart = categories.size();

        // A. SSID 原文与核心变形
        List<String> a = new ArrayList<>();
        if (!ssid.isEmpty()) {
            a.add(ssid); a.add(ssidLower); a.add(ssidUpper);
            a.add(capitalize(ssidLower)); a.add(compact); a.add(compact.toLowerCase());
        }
        if (!a.isEmpty()) { buckets.add(a); caps.add(12); categories.add(L.s("SSID 原文", "SSID literal")); }

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
        if (!b.isEmpty()) { buckets.add(b); caps.add(12); categories.add(L.s("数字族", "digit family")); }

        // C. SSID + 数字后缀
        List<String> c = new ArrayList<>();
        if (!ssid.isEmpty()) {
            for (String sf : NUM_SUFFIX) {
                c.add(ssidLower + sf);
                c.add(compact + sf);
                c.add(compact.toLowerCase() + sf);
            }
        }
        if (!c.isEmpty()) { buckets.add(c); caps.add(12); categories.add(L.s("SSID+数字", "SSID+digits")); }

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
        if (!d.isEmpty()) { buckets.add(d); caps.add(12); categories.add(L.s("BSSID 尾号", "BSSID tail")); }

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
        if (!e.isEmpty()) { buckets.add(e); caps.add(12); categories.add(L.s("字母部分", "letter part")); }

        // F. SSID + 字母后缀
        List<String> f = new ArrayList<>();
        if (!ssid.isEmpty()) {
            for (String sf : ALPHA_SUFFIX) {
                f.add(ssidLower + sf);
                f.add(compact + sf);
                f.add(compact.toLowerCase() + sf);
            }
        }
        if (!f.isEmpty()) { buckets.add(f); caps.add(12); categories.add(L.s("SSID+字母", "SSID+letters")); }

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
        if (!g2.isEmpty()) { buckets.add(g2); caps.add(12); categories.add(L.s("片段组合", "segment combinations")); }

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
        if (!h.isEmpty()) { buckets.add(h); caps.add(12); categories.add(L.s("智能词元扩散", "token matrix")); }

        // I. 厂商词元：OUI 表查到的厂商名本身就是强候选。
        //    依据不是「厂商默认密码表」（那种表编不准），而是两点可观察的事实：
        //      · 很多 SSID 直接带厂商名/型号（TP-LINK_xxxx、MERCURY_xxxx）
        //      · 主人设密码时爱拿品牌名加数字（xiaomi123、huawei2026）
        //    厂商名来自 IEEE OUI 登记表，查不到就整个桶不生成，绝不编一个。
        List<String> vi = new ArrayList<>();
        if (vendorName != null && !vendorName.trim().isEmpty()) {
            java.util.LinkedHashSet<String> forms = new java.util.LinkedHashSet<>();
            String raw = vendorName.trim();
            String all = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (all.length() >= 3 && all.length() <= 14) forms.add(all);
            String firstWord = raw.split("[\\s,./-]+")[0].toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
            if (firstWord.length() >= 3 && firstWord.length() <= 14) forms.add(firstWord);

            int vy = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            String[] vyrs = new String[]{
                String.valueOf(vy), String.valueOf(vy - 1),
                String.valueOf(vy - 2), String.valueOf(vy - 3)
            };

            for (String vf : forms) {
                String cap = capitalize(vf);
                vi.add(vf);
                vi.add(cap);
                if (vf.length() <= 6) vi.add(vf + vf);
                for (String yr : vyrs) {
                    vi.add(vf + yr);
                    vi.add(cap + yr);
                    vi.add(vf + "@" + yr);
                }
                for (String sf : NUM_SUFFIX) {
                    vi.add(vf + sf);
                    vi.add(cap + sf);
                }
            }
        }
        if (!vi.isEmpty()) { buckets.add(vi); caps.add(12); categories.add(L.s("厂商词元", "vendor token")); }

        // ── 顺序直出：按桶序，每桶按自己的上限取满，再进下一个桶 ──
        // 不做轮转：轮转等于"把密度平均化"，而密度差着一两个数量级。
        boolean useSsid = seeds != null && seeds.useSsid;
        for (int bi = 0; bi < buckets.size(); bi++) {
            if (bi >= ssidStart && !useSsid) continue;   // SSID 派生：默认不进
            if (pool.size() >= limit) break;
            int cap = bi < caps.size() ? caps.get(bi) : 12;
            int n = 0;
            for (String v : buckets.get(bi)) {
                if (n >= cap || pool.size() >= limit) break;
                if (add(pool, v, limit)) n++;
            }
        }

        // 来源标注：画像派生 / 中文高频 各自打标，审计报告里能看出命中来自哪一层
        java.util.Set<String> seedVals = new java.util.HashSet<>();
        if (seeds != null && !seeds.isEmpty()) {
            for (SeedDeriver.Item it : SeedDeriver.profile(seeds)) seedVals.add(it.value);
        }
        java.util.Set<String> cnVals = cnCommon == null
                ? java.util.Collections.emptySet() : new java.util.HashSet<>(cnCommon);

        List<Candidate> out = new ArrayList<>();
        for (String pw : pool) {
            Candidate.Origin origin = Candidate.Origin.VENDOR_RULE;
            if (seedVals.contains(pw)) origin = Candidate.Origin.USER_SEED;
            else if (cnVals.contains(pw)) origin = Candidate.Origin.USER_DICT;
            out.add(new Candidate(pw, origin));
            if (out.size() >= limit) break;
        }

        List<String> shown = new ArrayList<>(categories.subList(0, Math.min(catSsidStart, categories.size())));
        if (useSsid && categories.size() > catSsidStart) {
            shown.addAll(categories.subList(catSsidStart, categories.size()));
        } else if (!useSsid) {
            shown.add(L.s("SSID 派生: 关", "SSID derivation: off"));
        }
        String explain = shown.isEmpty()
                ? L.s("没有可用特征，仅生成通用弱口令", "No usable features; common weak passphrases only")
                : L.s("按目标派生: ", "Derived from target: ") + String.join(" / ", shown);
        return new Result(out, explain);
    }

    /**
     * 加入候选。**严格长度闸门，不做任何补位。**
     *
     * v1 这里有一整套"不足 8 位就补 0 / 补 8 / 补 1 / 前缀 0"的启发，
     * 后果是手机号后 6 位 `408514` 被炸成
     * `40851440 / 40851400 / 40851488 / 40851411 / 00408514` 五条
     * 没有任何人会设的串，白占 5 次真实连接尝试（实测 3.6 秒/次）。
     *
     * 依据：
     *  · WPA 口令最短 8 位 —— 短于 8 位的中间产物本身就不可能是答案；
     *  · "补到 8 位"除了极少数真实习惯（+年份 / +520）之外都是臆造，
     *    而那些习惯已经由 {@link SeedDeriver} 的追加层显式覆盖；
     *  · 主流工具（CUPP 的 wcfrom/wcto、hashcat 的规则集）全都靠长度闸门过滤，
     *    没有谁给短串补位。
     *
     * 形态闸门：纯数字只接受 8~11 位（本机 107 次真实命中最长 10 位，11 位留给手机号）；
     * 混排接受 8~12 位。
     *
     * @return true 表示确实新加进去了（用于按桶计上限）
     */
    private static boolean add(LinkedHashSet<String> pool, String pw, int limit) {
        if (pool.size() >= limit) return false;
        if (pw == null) return false;
        String p = pw.trim();
        if (p.length() < 8 || p.length() > 63) return false;
        boolean allDigits = true;
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch < '0' || ch > '9') { allDigits = false; break; }
        }
        if (allDigits && p.length() > 11) return false;
        if (!allDigits && p.length() > 12) return false;
        return pool.add(p);
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

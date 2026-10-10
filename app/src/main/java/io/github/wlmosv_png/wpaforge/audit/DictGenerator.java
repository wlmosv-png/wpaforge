package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典生成器。全部算法自研，基于公开的弱口令统计规律与厂商默认规则推导，
 * 不内置任何第三方词表文件。
 *
 * 设计要点：
 *  - 生成有序（规则优先级从高到低），高命中率规则排在前面
 *  - 全程去重，用 LinkedHashMap 保留顺序
 *  - 可设上限，避免内存爆掉
 */
public final class DictGenerator {

    /** 常见基础英文词（弱口令统计里高频，公开数据） */
    private static final String[] BASE_WORDS = {
            "admin", "password", "root", "guest", "test", "wifi", "wlan",
            "home", "house", "family", "love", "hello", "china", "beijing",
            "shanghai", "tel", "tp", "tplink", "xiaomi", "mi", "huawei",
            "mercury", "tenda", "netcore", "asus", "netgear", "dlink",
            "motorola", "linksys", "cisco", "public", "default", "manager"
    };

    /** 国内常见手机号段（公开的号段分配信息） */
    private static final String[] PHONE_PREFIX = {
            "130", "131", "132", "133", "134", "135", "136", "137", "138", "139",
            "150", "151", "152", "153", "155", "156", "157", "158", "159",
            "170", "171", "172", "173", "175", "176", "177", "178",
            "180", "181", "182", "183", "184", "185", "186", "187", "188", "189",
            "191", "198", "199"
    };

    /** 年份后缀候选 */
    private static final String[] YEAR_SUFFIX = {
            "2018", "2019", "2020", "2021", "2022", "2023", "2024", "2025", "2026",
            "123", "1234", "12345", "123456", "888", "666", "999", "000", "111"
    };

    private final LinkedHashMap<String, Candidate> pool = new LinkedHashMap<>();
    private final int maxSize;

    public DictGenerator(int maxSize) {
        this.maxSize = maxSize <= 0 ? 200000 : maxSize;
    }

    public DictGenerator() {
        this(200000);
    }

    private void add(String pw, Candidate.Origin origin) {
        if (pool.size() >= maxSize) return;
        if (pw == null || pw.isEmpty()) return;
        if (pw.length() < 8 || pw.length() > 63) return; // WPA 合法长度
        if (!pool.containsKey(pw)) {
            pool.put(pw, new Candidate(pw, origin));
        }
    }

    /** 最经典的弱口令 */
    public DictGenerator classic() {
        add("12345678", Candidate.Origin.DIGITS);
        add("123456789", Candidate.Origin.DIGITS);
        add("1234567890", Candidate.Origin.DIGITS);
        add("88888888", Candidate.Origin.DIGITS);
        add("66668888", Candidate.Origin.DIGITS);
        add("888888888", Candidate.Origin.DIGITS);
        add("11111111", Candidate.Origin.DIGITS);
        add("00000000", Candidate.Origin.DIGITS);
        add("password", Candidate.Origin.LETTERS);
        add("Password", Candidate.Origin.LETTERS);
        add("password123", Candidate.Origin.ALNUM);
        add("admin123", Candidate.Origin.ALNUM);
        add("admin888", Candidate.Origin.ALNUM);
        add("qwertyuiop", Candidate.Origin.LETTERS);
        add("1qaz2wsx", Candidate.Origin.ALNUM);
        add("a1234567", Candidate.Origin.ALNUM);
        add("abc12345", Candidate.Origin.ALNUM);
        add("iloveyou", Candidate.Origin.LETTERS);
        return this;
    }

    /** 纯数字规律：升序 / 重复 / 连号 */
    public DictGenerator numericPatterns() {
        // 8 位重复：11111111 .. 99999999
        for (int d = 0; d <= 9; d++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(d);
            add(sb.toString(), Candidate.Origin.DIGITS);
        }
        // 连续升序 8/9/10 位片段
        String ladder = "0123456789";
        for (int start = 0; start + 8 <= ladder.length(); start++) {
            add(ladder.substring(start, start + 8), Candidate.Origin.DIGITS);
        }
        // 常见"键盘邻接"数字列
        add("09876543", Candidate.Origin.DIGITS);
        add("98765432", Candidate.Origin.DIGITS);
        add("87654321", Candidate.Origin.DIGITS);
        return this;
    }

    /** 手机号段 × 后四位规律，模拟"手机号当密码" */
    public DictGenerator phoneSegments() {
        for (String p : PHONE_PREFIX) {
            add(p + "123456", Candidate.Origin.PHONE_SEGMENT);
            add(p + "888888", Candidate.Origin.PHONE_SEGMENT);
            add(p + "000000", Candidate.Origin.PHONE_SEGMENT);
            add(p + "666666", Candidate.Origin.PHONE_SEGMENT);
        }
        return this;
    }

    /** 基础词 × 数字后缀 */
    public DictGenerator wordVariants() {
        for (String w : BASE_WORDS) {
            for (String s : YEAR_SUFFIX) {
                add(w + s, Candidate.Origin.YEAR_SUFFIX);
            }
            // 首字母大写变体
            if (!w.isEmpty()) {
                String cap = Character.toUpperCase(w.charAt(0)) + w.substring(1);
                for (String s : YEAR_SUFFIX) {
                    add(cap + s, Candidate.Origin.YEAR_SUFFIX);
                }
            }
        }
        return this;
    }

    /** 词 + 常见数字的更多组合（长度 8 以上的） */
    public DictGenerator wordDigits() {
        String[] tails = {"1", "12", "123", "1234", "12345", "520", "521",
                "1314", "5201314", "0000", "8888", "6666", "168168", "112233"};
        for (String w : BASE_WORDS) {
            for (String t : tails) {
                add(w + t, Candidate.Origin.ALNUM);
                if (!w.isEmpty()) {
                    add(Character.toUpperCase(w.charAt(0)) + w.substring(1) + t,
                            Candidate.Origin.ALNUM);
                }
            }
        }
        return this;
    }

    /**
     * 厂商默认规则推导。
     * 输入 AP 的 MAC 与型号关键词，按公开的出厂规则生成候选。
     * 注意：这里只覆盖公开研究里已披露的通用规律，不针对具体在售设备。
     */
    public DictGenerator vendorRules(String bssid, String ssidHint) {
        if (bssid == null) return this;
        String mac = bssid.replace(":", "").replace("-", "").toUpperCase();
        if (mac.length() < 6) return this;

        // 通用规律：MAC 后 6 位作为候选（部分厂商出厂用这个）
        String tail = mac.substring(mac.length() - 6);
        add(tail.toLowerCase(), Candidate.Origin.VENDOR_RULE);
        add(tail.toUpperCase(), Candidate.Origin.VENDOR_RULE);
        // MAC 尾 6 位 + 常见后缀，凑够 8 位（出厂默认常按这个拼）
        String[] vSuffix = {"", "123", "1234", "888", "666", "000", "111",
                "2024", "2025", "2026"};
        for (String s : vSuffix) {
            add(tail.toLowerCase() + s, Candidate.Origin.VENDOR_RULE);
            add(tail.toUpperCase() + s, Candidate.Origin.VENDOR_RULE);
        }

        // SSID 里出现过的数字串（含 4 位，再拼常见后缀凑长度）
        if (ssidHint != null) {
            StringBuilder digits = new StringBuilder();
            for (char c : ssidHint.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    flushDigits(digits);
                }
            }
            flushDigits(digits);
        }
        return this;
    }

    private void flushDigits(StringBuilder digits) {
        if (digits.length() == 0) return;
        String d = digits.toString();
        if (d.length() >= 8) {
            add(d, Candidate.Origin.VENDOR_RULE);
        } else if (d.length() >= 4) {
            // 拼后缀凑到 8 位以上
            for (String s : new String[]{"", "0", "00", "000", "0000",
                    "123", "1234", "888", "666"}) {
                add(d + s, Candidate.Origin.VENDOR_RULE);
            }
        }
        digits.setLength(0);
    }

    /** 导入用户自备词表（每行一条） */
    public DictGenerator importLines(List<String> lines) {
        if (lines == null) return this;
        for (String line : lines) {
            String pw = line.trim();
            if (pw.isEmpty() || pw.startsWith("#")) continue;
            add(pw, Candidate.Origin.USER_DICT);
        }
        return this;
    }

    /** 当前池 */
    public List<Candidate> build() {
        return new ArrayList<>(pool.values());
    }

    public int size() {
        return pool.size();
    }

    /** 按来源统计，用于 UI 展示"字典构成" */
    public Map<String, Integer> stats() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Candidate c : pool.values()) {
            String k = c.origin.name();
            m.put(k, m.getOrDefault(k, 0) + 1);
        }
        return m;
    }
}

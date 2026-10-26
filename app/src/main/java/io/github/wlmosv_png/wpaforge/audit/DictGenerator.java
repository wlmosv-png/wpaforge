package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置生成器（2026-10-26 重做）。
 *
 * <p><b>重做的理由。</b>旧版按「英文词 × 数字后缀」铺了 1771 条，里面有三类
 * 站不住的条目：
 *
 * <ol>
 *   <li><b>手机段 + 假尾号</b>（{@code 130123456} / {@code 130888888}，160 条）——
 *       真实世界里用手机号当密码就是**11 位全号**（公开统计：11 位纯数字占全部
 *       家用 WiFi 密码 13.9%），「130 开头 + 123456」不是任何人的号码，
 *       纯粹是矩阵副产品；</li>
 *   <li><b>英文普通词全展开</b>（{@code family2018} / {@code love888}，1500+ 条）——
 *       本机历史里英文单词表**一次都没被跑过**，也没命中过；</li>
 *   <li><b>厂商词元 + 符号 + 年份</b>（{@code tplink@2026}）—— 与 SSID 派生那批
 *       一起按社会工程学审掉了。</li>
 * </ol>
 *
 * <p><b>新的骨架</b>：只留「有人真的会设」的形态 ——
 * 经典弱口令、数字结构族（重复/阶梯/键盘图形/镜像）、路由与宽带相关词 × 少量后缀、
 * 完整 MAC 原样。规模从 1771 压到约 500 条，剩下的按需生成：
 * 「字母 + 生日」交给 {@link BirthdayTable}（旧版是 48 万条静态表，
 * 而本机 6 次使用全在第 2 条就命中 —— 后面 48 万条白占 5.3 MB）。
 *
 * <p>设计要点不变：生成有序（高命中率规则在前）、全程去重、可设上限、
 * 闸门只做合法性过滤（WPA 合法长度 8–63，不补位、不臆造）。
 */
public final class DictGenerator {

    /** 强词后缀（年份 + 连号 + 谐音数字，都是真实存在的形态） */
    /**
     * 「设备/网络场景」强词。
     *
     * <p>只留这 7 个：它们是家用 AP 上真正会出现的词根 ——
     * 出厂默认、宽带账号、以及「WiFi」本身。
     * 旧版那 34 个通用英文词（family / house / hello / beijing…）属于
     * 「既不相关也不高频」，产出 {@code family2018} / {@code love888}
     * 这类本机从未命中的形态，已删。
     */
    private static final String[] STRONG_WORDS = {
            "admin", "password", "root", "guest", "test", "wifi", "wlan"
    };

    private static final String[] SUFFIX_STRONG = {
            "2018", "2019", "2020", "2021", "2022", "2023", "2024", "2025", "2026",
            "123", "1234", "12345", "123456", "888", "666", "000", "111",
            "520", "1314", "888888", "666666"
    };

    private final LinkedHashMap<String, Candidate> pool = new LinkedHashMap<>();
    private final int maxSize;

    public DictGenerator(int maxSize) {
        this.maxSize = maxSize <= 0 ? 200000 : maxSize;
    }

    public DictGenerator() {
        this(200000);
    }

    /**
     * 「内置生成器（快速）」的**唯一配方**。
     *
     * <p>所有调用方都必须走这里：界面生成、服务重建、界面上的条数统计。
     * 曾经三处各写一遍 {@code classic().numericPatterns().phoneSegments()...}，
     * 任何一处改了参数，重建出来的条数就和入队时不一致，
     * 续跑（暂停后继续）的 offset 会直接错位 —— 这类 bug 难查且致命。
     */
    public static DictGenerator quick(int maxSize) {
        return new DictGenerator(maxSize)
                .classic()
                .numericPatterns()
                .digitHighFreq()
                .routerWords();
    }

    private void add(String pw, Candidate.Origin origin) {
        if (pool.size() >= maxSize) return;
        if (pw == null || pw.isEmpty()) return;
        if (pw.length() < 8 || pw.length() > 63) return;   // WPA 合法长度，不补位
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

    /**
     * 纯数字结构族：重复、阶梯、键盘图形、镜像。
     *
     * <p>只生成**有形态依据**的数字，不做区间穷举（那是
     * {@link #birthdayRange} 与规则字典的活）。
     */
    public DictGenerator numericPatterns() {
        // 单数字重复 8/9/10 位
        for (int d = 0; d <= 9; d++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(d);
            add(sb.toString(), Candidate.Origin.DIGITS);
            add(sb.toString() + d, Candidate.Origin.DIGITS);
        }
        // 连号阶梯（升序 8 位，覆盖整个数字环）
        String ring = "0123456789012345678";
        for (int i = 0; i + 8 <= 16; i++) {
            add(ring.substring(i, i + 8), Candidate.Origin.DIGITS);
        }
        // 降序阶梯
        add("98765432", Candidate.Origin.DIGITS);
        add("87654321", Candidate.Origin.DIGITS);
        add("76543210", Candidate.Origin.DIGITS);
        add("09876543", Candidate.Origin.DIGITS);
        // 键盘图形（数字小键盘的竖列 / 横列 / 对角）
        add("147258369", Candidate.Origin.DIGITS);
        add("789456123", Candidate.Origin.DIGITS);
        add("741852963", Candidate.Origin.DIGITS);
        add("963852741", Candidate.Origin.DIGITS);
        add("159357258", Candidate.Origin.DIGITS);
        add("123654789", Candidate.Origin.DIGITS);
        return this;
    }

    /**
     * 8 位数字里的高频结构（镜像、AB 交替）。
     *
     * <p>为什么单列这个方法：8 位纯数字是**最大的单一族** ——
     * 公开统计（48,330 条真实 SSID + 密码样本）里，8 位密码中纯数字占 73%，
     * 而 8 位数字里带结构（重复、连号、镜像、AB 交替）的又占了可观的头部。
     * 旧版这里只有 17 条，太薄。
     */
    public DictGenerator digitHighFreq() {
        // 镜像：4 位块「正反」（12344321）与「重复」（12341234）。
        // 只取 5 个有说法的基底 —— 1234（顺子）、4321（倒顺）、
        // 8888/6666（国人偏爱）、0000（出厂形态）。
        String[] bases = {"1234", "4321", "8888", "6666", "0000"};
        for (String b : bases) {
            add(b + new StringBuilder(b).reverse(), Candidate.Origin.DIGITS);
            add(b + b, Candidate.Origin.DIGITS);
        }
        // AB 交替：12121212 / 10101010 …（重复打磨出来的常见形态）
        String[] ab = {"10", "12", "13", "18", "20", "21", "23", "24", "25", "26", "81", "89", "52"};
        for (String p : ab) add(p + p + p + p, Candidate.Origin.DIGITS);
        // 顺口串
        add("16816816", Candidate.Origin.DIGITS);
        add("11223344", Candidate.Origin.DIGITS);
        add("44332211", Candidate.Origin.DIGITS);
        return this;
    }

    /**
     * 路由 / 宽带场景词 × 少量后缀。
     *
     * <p><b>为什么只剩 7 个词。</b>旧版这里是「34 个英文词 × 19 个后缀」，
     * 产出的 {@code family2018} / {@code love888} 一类本机从没命中过。
     * 品牌词（tplink / mercury / cmcc…）也一并去掉了，理由分两层：
     *
     * <ul>
     *   <li>真实的出厂默认口令是**裸词**（{@code admin} / {@code tplink}），
     *       不是「品牌 + 年份」；</li>
     *   <li>而裸品牌词长度都在 8 位以下（{@code tplink} 6 位、{@code zte} 3 位），
     *       本来就通不过 WPA 的长度闸门 —— 配后缀硬凑才是臆造。</li>
     * </ul>
     *
     * <p>留下的是「设备/网络相关词 + 连号/年份」，形态与公开数据里
     * 「词 + 数字」占 35.9% 的分布一致。
     */
    public DictGenerator routerWords() {
        for (String w : STRONG_WORDS) {
            add(w, Candidate.Origin.LETTERS);
            String cap = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            for (String s : SUFFIX_STRONG) {
                add(w + s, Candidate.Origin.ALNUM);
                add(cap + s, Candidate.Origin.ALNUM);
            }
            for (String s : new String[]{"123", "1234", "123456"}) {
                add(w + "_" + s, Candidate.Origin.ALNUM);
            }
        }
        return this;
    }

    /**
     * 生日区间（穷举档，**默认不生成**）。
     *
     * <p>形态：{@code YYYYMMDD}。旧版把这 5.3 MB 的表静态打进包里，
     * 现在改成按需生成 —— 用哪段年份、要不要跑，用户自己决定，
     * 界面上必须能看到「约 N 条 ≈ 跑多久」。
     *
     * @param y1 起始年（含）
     * @param y2 结束年（含）
     */
    public DictGenerator birthdayRange(int y1, int y2) {
        for (String d : BirthdayTable.dates(y1, y2)) {
            add(d, Candidate.Origin.DIGITS);
        }
        return this;
    }

    /**
     * 前缀 + 生日（穷举档，**默认不生成**）。
     *
     * <p>形态依据来自公开统计：11 位「字母 + 数字」口令里，
     * 三位字母 + 八位数字占 31%，其中字母约七成是姓名首字母。
     * 所以前缀该由**线索里的名字**给出（见 {@link SeedDeriver}），
     * 而不是无脑铺 26 个字母。
     *
     * @param prefixes 前缀（如姓名首字母 "zw"、"lxy"）
     */
    public DictGenerator prefixedBirthday(List<String> prefixes, int y1, int y2) {
        if (prefixes == null || prefixes.isEmpty()) return this;
        for (String p : prefixes) {
            if (p == null || p.isEmpty()) continue;
            for (String d : BirthdayTable.dates(y1, y2)) {
                add(p.toLowerCase() + d, Candidate.Origin.ALNUM);
                if (Character.isLetter(p.charAt(0))) {
                    add(Character.toUpperCase(p.charAt(0)) + p.substring(1).toLowerCase() + d,
                            Candidate.Origin.ALNUM);
                }
            }
        }
        return this;
    }

    /**
     * 厂商默认规则推导：**只出完整 MAC 原样**。
     *
     * <p>与 SSID 派生同一条结论（2026-10-26 社会工程学审查第二批）：
     * 出厂默认用 MAC 是真实的，但形态是完整 12 位十六进制，
     * 不是「尾 6 位 + 00 / 111 / 12345」——后者是为凑长度而造串，
     * 本机 107 次命中里一次都没来自那一族。
     */
    public DictGenerator vendorRules(String bssid, String ssidHint) {
        if (bssid == null) return this;
        String mac = bssid.replace(":", "").replace("-", "").toUpperCase();
        if (mac.length() == 12) {
            add(mac.toLowerCase(), Candidate.Origin.VENDOR_RULE);
            add(mac, Candidate.Origin.VENDOR_RULE);
        }
        // SSID 里的数字串：够 8 位就原样用，不足 8 位一律丢弃
        // （旧版会给它补后缀凑长度 —— 那是臆造，已删）
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
        if (d.length() >= 8 && d.length() <= 16) {
            add(d, Candidate.Origin.VENDOR_RULE);
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

    /** 按来源统计，用于 UI 展示「字典构成」 */
    public Map<String, Integer> stats() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Candidate c : pool.values()) {
            String k = c.origin.name();
            m.put(k, m.getOrDefault(k, 0) + 1);
        }
        return m;
    }
}

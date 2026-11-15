package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 手机号字典（2026-11-14 新增）。
 *
 * <p><b>为什么要有这一块，以及为什么不能只有一块。</b>
 * 用户反馈：「内置三个字典都失败，可以加一个电话本，按号段穷举，还能按地区
 * 和年龄猜号段」。方向是对的 —— 公开统计里 11 位纯数字占家用 WiFi 口令约
 * 13.9%，而 11 位纯数字里绝大多数就是手机号。但**「穷举」这个词必须先算账**：
 *
 * <pre>
 *   全国可分配号段（前 3 位）54 个 × 后 8 位全排列 = 5.4×10^9 条
 *   在线逐条连 AP ≈ {@link DictSources#RATE_PER_SEC} 条/秒
 *     → 5.4e9 / 1.5 ≈ 114 年          （手机上：不可能）
 *   PC 显卡跑 hashcat 22000 模式 ≈ 10^6 条/秒量级
 *     → 5.4e9 / 1e6 ≈ 1.5 小时        （离线：完全可行）
 * </pre>
 *
 * <p>结论是分档，而不是「加一个大表」：
 *
 * <table>
 *   <tr><th>档</th><th>形态</th><th>规模</th><th>在哪跑</th></tr>
 *   <tr><td>A 结构尾号</td><td>号段(7位) × 高频尾号(几十条)</td>
 *       <td>几千条</td><td>在线，几分钟到几小时</td></tr>
 *   <tr><td>B 单号段全尾号</td><td>号段(7位) × 0000–9999</td>
 *       <td>1 万条/号段</td><td>在线，约 1.9 小时/号段</td></tr>
 *   <tr><td>C 全国号段</td><td>55 个号段 × 后 8 位全排列</td>
 *       <td>55 亿条</td><td>**只能离线导出**（GPU 小时级）</td></tr>
 * </table>
 *
 * <p><b>号段年份表的出处</b>：{@link #RAW}，取自中文维基《中国大陆移动终端
 * 通信号码》「号段分配」表（该表数据引自工信部公告与运营商公开报道）。
 * 年份是**公开的启用时间**，不含任何个人信息 —— 一个号段覆盖上万人，
 * 它标识的是「哪一年开始放号」，不是「谁的号」。所谓「按年龄猜号段」
 * 用的就是这个公开事实：40 岁上下的人首卡多在 2004–2007，落在 13x/15x 老段；
 * 20 岁上下的人首卡多在 2018 以后，落在 19x/16x 新段。
 *
 * <p><b>地区怎么来。</b>前 7 位 = 归属地的映射表有几十万行，不适合内置。
 * 所以本类只负责「从用户粘贴的号码/号段里抽出前 7 位」并去重
 * （{@link #extractSeg7}）—— 用户手上的本地号（自己的、家人的）天然就是
 * 本地号段的样本，且只取前 7 位，不保留任何完整号码。
 *
 * <p>纯逻辑，无 Android 依赖，可离线测试。
 */
public final class PhoneSegs {

    /** 运营商。只用于排序偏好与界面分组，不参与任何判定。 */
    public static final int OP_MOBILE = 0;      // 中国移动
    public static final int OP_UNICOM = 1;      // 中国联通
    public static final int OP_TELECOM = 2;     // 中国电信
    public static final int OP_BROADNET = 3;    // 中国广电
    public static final int OP_MVNO = 4;        // 虚拟运营商（170/171）

    /** 一个号段（前 3 位）。 */
    public static final class Seg {
        public final String p3;
        public final int op;
        /** 公开启用年份 */
        public final int year;

        Seg(String p3, int op, int year) {
            this.p3 = p3;
            this.op = op;
            this.year = year;
        }

        public String operator() {
            return opName(op);
        }

        @Override
        public String toString() {
            return p3 + "/" + operator() + "/" + year;
        }
    }

    /**
     * 号段表。{前 3 位, 运营商, 启用年}。
     *
     * <p>逐条核对过维基「号段分配」表的 rowspan 展开结果。原文里 198 出现了两次
     * （2017 移动、2019 移动），137/135/159/156 的 rowspan 也易错位 ——
     * 这里按运营商详情表交叉对齐后才落表。**年份是公开启用时间，为粗档**，
     * 用来做排序权重，不做任何强断言。
     */
    private static final Object[][] RAW = {
            {"130", OP_UNICOM, 1994}, {"139", OP_MOBILE, 1994},
            {"131", OP_UNICOM, 1995},
            {"133", OP_TELECOM, 1997}, {"136", OP_MOBILE, 1997}, {"138", OP_MOBILE, 1997},
            {"137", OP_MOBILE, 1999},
            {"135", OP_MOBILE, 2000},
            {"132", OP_UNICOM, 2004}, {"134", OP_MOBILE, 2004}, {"159", OP_UNICOM, 2004},
            {"156", OP_UNICOM, 2007}, {"158", OP_MOBILE, 2007},
            {"150", OP_MOBILE, 2008}, {"151", OP_MOBILE, 2008}, {"152", OP_MOBILE, 2008},
            {"153", OP_TELECOM, 2008}, {"155", OP_UNICOM, 2008}, {"157", OP_MOBILE, 2008},
            {"189", OP_TELECOM, 2008},
            {"145", OP_UNICOM, 2009}, {"186", OP_UNICOM, 2009}, {"188", OP_MOBILE, 2009},
            {"147", OP_MOBILE, 2010}, {"182", OP_MOBILE, 2010}, {"187", OP_MOBILE, 2010},
            {"180", OP_TELECOM, 2011}, {"183", OP_MOBILE, 2011},
            {"181", OP_TELECOM, 2012}, {"185", OP_UNICOM, 2012},
            {"184", OP_MOBILE, 2013},
            {"170", OP_MVNO, 2014}, {"171", OP_MVNO, 2014},
            {"176", OP_UNICOM, 2014}, {"178", OP_MOBILE, 2014},
            {"175", OP_UNICOM, 2015}, {"177", OP_TELECOM, 2015},
            {"173", OP_TELECOM, 2016}, {"195", OP_MOBILE, 2016},
            {"146", OP_UNICOM, 2017}, {"191", OP_TELECOM, 2017},
            {"198", OP_MOBILE, 2017}, {"199", OP_TELECOM, 2017},
            {"166", OP_UNICOM, 2018}, {"167", OP_UNICOM, 2018},
            {"149", OP_TELECOM, 2019}, {"190", OP_TELECOM, 2019}, {"192", OP_BROADNET, 2019},
            {"193", OP_TELECOM, 2019}, {"196", OP_UNICOM, 2019}, {"197", OP_MOBILE, 2019},
            {"162", OP_TELECOM, 2019}, {"165", OP_MOBILE, 2019}, {"172", OP_MOBILE, 2019},
    };

    /** 「首卡年龄」默认 20 岁 —— 用来把「年龄」换算成「当年启用过哪些号段」。 */
    public static final int FIRST_CARD_AGE = 20;

    /** 年龄窗口宽度（年）：首卡年往前放宽的幅度。 */
    private static final int AGE_WINDOW_BACK = 24;

    private PhoneSegs() {}

    private static final List<Seg> ALL = new ArrayList<>();

    static {
        for (Object[] r : RAW) ALL.add(new Seg((String) r[0], (Integer) r[1], (Integer) r[2]));
    }

    public static List<Seg> all() {
        return new ArrayList<>(ALL);
    }

    public static int count() {
        return ALL.size();
    }

    public static String opName(int op) {
        switch (op) {
            case OP_MOBILE:    return L.s("移动", "China Mobile");
            case OP_UNICOM:    return L.s("联通", "China Unicom");
            case OP_TELECOM:   return L.s("电信", "China Telecom");
            case OP_BROADNET:  return L.s("广电", "China Broadnet");
            case OP_MVNO:      return L.s("虚拟运营", "MVNO");
            default:           return L.s("其他", "Other");
        }
    }

    public static List<Seg> byOperator(int op) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : ALL) if (s.op == op) out.add(s);
        return out;
    }

    public static List<String> prefixesOf(List<Seg> segs) {
        List<String> out = new ArrayList<>();
        if (segs == null) return out;
        for (Seg s : segs) out.add(s.p3);
        return out;
    }

    /**
     * 按年龄挑号段，最接近的排最前。
     *
     * <p>逻辑：一个人第一次办手机号大约在 {@link #FIRST_CARD_AGE} 岁，
     * 那年能拿到的号段，其**启用年**必然 ≤ 首卡年、且不能太早（太早的号段
     * 早被老用户占满，新开户会去新号段）。所以窗口取
     * {@code [首卡年 - 24, 首卡年 + 2]}，按 |启用年 − 首卡年| 升序。
     *
     * <p>年龄或年份非法时返回全部（不猜）。
     */
    public static List<Seg> forAge(int age, int nowYear) {
        if (age <= 0 || age >= 120 || nowYear <= 1990) return all();
        final int firstCard = nowYear - age + FIRST_CARD_AGE;
        List<Seg> win = new ArrayList<>();
        for (Seg s : ALL) {
            if (s.year >= firstCard - AGE_WINDOW_BACK && s.year <= firstCard + 2) win.add(s);
        }
        if (win.isEmpty()) return all();
        Collections.sort(win, new Comparator<Seg>() {
            @Override
            public int compare(Seg a, Seg b) {
                int d = Math.abs(a.year - firstCard) - Math.abs(b.year - firstCard);
                if (d != 0) return d;
                return a.p3.compareTo(b.p3);
            }
        });
        return win;
    }

    /** 年龄窗口的首卡年（界面提示用：「约等于 2006 年办的卡」）。 */
    public static int firstCardYear(int age, int nowYear) {
        return nowYear - age + FIRST_CARD_AGE;
    }

    /**
     * 从任意文本里抽号段前 7 位，去重、保序。
     *
     * <p>接受两种输入：完整号码（1[3-9] 开头共 11 位）与裸号段（1[3-9] 开头共 7 位）。
     * 完整号码**只取前 7 位**，后 4 位直接丢弃 —— 本方法产出的东西会写进字典文件，
     * 绝不能把用户粘贴的完整号码带进去。
     *
     * <p>不用正则：手写扫描便于逐格穷举测试，也避免不同 JDK 的正则差异。
     */
    public static List<String> extractSeg7(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : ' ';
            if (c >= '0' && c <= '9') {
                run.append(c);
                continue;
            }
            flushRun(run, out);
        }
        return new ArrayList<>(out);
    }

    private static void flushRun(StringBuilder run, LinkedHashSet<String> out) {
        if (run.length() > 0) {
            String d = run.toString();
            if (isSeg7(d)) {
                out.add(d);
            } else if (d.length() >= 11 && isSeg7(d.substring(0, 7))) {
                out.add(d.substring(0, 7));
            }
            run.setLength(0);
        }
    }

    private static boolean isSeg7(String s) {
        if (s == null || s.length() != 7) return false;
        if (s.charAt(0) != '1') return false;
        char c = s.charAt(1);
        return c >= '3' && c <= '9';
    }

    /** 这段文本里有没有可用的号码/号段（界面用来提示「没识别到」）。 */
    public static boolean looksLikeNumbers(String text) {
        return !extractSeg7(text).isEmpty();
    }

    /**
     * 后 4 位的高频形态。
     *
     * <p><b>这是一层采样，不是全空间</b> —— 命中率天然低于「号段 × 全尾号」，
     * 换来的是在线跑得完。形态依据：
     * <ul>
     *   <li>全同号（8888/6666/0000…）—— 选号偏好，真实且常见；</li>
     *   <li>顺子（1234/6789…）；</li>
     *   <li>ABAB（1212/8989…）；</li>
     *   <li>出生年（{@code nowYear-60 … nowYear-8}）—— 尾号写成自己出生年；</li>
     *   <li>常见月日与谐音（0101/0214/0512/0520/1314…）。</li>
     * </ul>
     *
     * @param nowYear 当前年份，用来生成「出生年」那一族；≤0 时跳过该族
     */
    public static List<String> tailPatterns(int nowYear) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        // 全同
        for (int d = 0; d <= 9; d++) {
            out.add("" + d + d + d + d);
        }
        // 顺子（升 6 组 + 降 6 组）
        String asc = "0123456789";
        for (int i = 0; i + 4 <= 10; i++) {
            out.add(asc.substring(i, i + 4));
        }
        for (int i = 10; i - 4 >= 0; i--) {
            out.add(new StringBuilder(asc.substring(i - 4, i)).reverse().toString());
        }
        // ABAB
        String[] ab = {"10", "12", "13", "18", "20", "21", "23", "25", "52", "66", "68", "89"};
        for (String p : ab) out.add(p + p);
        // 出生年
        if (nowYear > 1990) {
            for (int y = nowYear - 60; y <= nowYear - 8; y++) {
                out.add(String.valueOf(y));
            }
        }
        // 月日与谐音
        String[] common = {"0101", "0214", "0308", "0501", "0504", "0512", "0601",
                "0701", "0801", "0901", "1001", "1225", "1314", "0520", "0521", "1688"};
        Collections.addAll(out, common);
        return new ArrayList<>(out);
    }

    /**
     * 展开成明文候选（号段 × 尾号），最多 {@code max} 条。
     *
     * <p>给 A 档用：几千条级别，适合干脆生成一个普通字典文件在线跑。
     */
    public static List<String> expandTails(List<String> seg7, List<String> tails, int max) {
        List<String> out = new ArrayList<>();
        if (seg7 == null || tails == null) return out;
        int limit = max <= 0 ? 200_000 : max;
        for (String s : seg7) {
            if (s == null) continue;
            for (String t : tails) {
                if (out.size() >= limit) return out;
                String pw = s + t;
                if (pw.length() >= 8 && pw.length() <= 63) out.add(pw);
            }
        }
        return out;
    }

    /**
     * 规则：号段（7 位）× 后 4 位全排列。
     *
     * <p>一个号段 1 万条 ≈ 在线 1.9 小时 —— 这是**在线真正可用的最大粒度**。
     * 超过 1 个号段就基本别在线跑了（{@link #onlineFeasible} 会拦）。
     */
    public static DictRule fullRule(List<String> seg7) {
        DictRule r = new DictRule();
        r.prefix = join(seg7);
        r.suffix = "";
        r.start = "0000";
        r.end = "9999";
        r.step = 1;
        return r;
    }

    /** 规则：全国号段 × 后 8 位全排列。**只作离线导出**。 */
    public static DictRule nationalRule() {
        return nationalRuleOf(prefixesOf(all()));
    }

    /** 规则：按年龄挑出的号段 × 后 8 位全排列。同样只作离线导出。 */
    public static DictRule ageRule(int age, int nowYear) {
        return nationalRuleOf(prefixesOf(forAge(age, nowYear)));
    }

    /** 规则：给定前 3 位号段 × 后 8 位全排列。 */
    public static DictRule nationalRuleOf(List<String> p3List) {
        DictRule r = new DictRule();
        r.prefix = join(p3List);
        r.suffix = "";
        r.start = "00000000";
        r.end = "99999999";
        r.step = 1;
        return r;
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        if (xs == null) return "";
        for (String x : xs) {
            if (x == null) continue;
            String t = x.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 在线跑不跑得完。
     *
     * <p>阈值直接用 {@link DictSources#HUGE_ENTRIES}（10 万条 ≈ 18 小时）——
     * 一套阈值，界面上不会出现两处口径不一致。
     */
    public static boolean onlineFeasible(long entries) {
        return entries > 0 && entries <= DictSources.HUGE_ENTRIES;
    }

    /** 条数 → 「约 N 分钟 / 小时 / 天 / 年」。与 {@link DictSources#estimateText} 同一口径。 */
    public static String estimateText(long entries) {
        if (entries <= 0) return "";
        long sec = (long) (entries / DictSources.RATE_PER_SEC);
        if (sec < 60) return L.s("约 " + sec + " 秒", "~" + sec + " s");
        if (sec < 3600) return L.s("约 " + (sec / 60) + " 分钟", "~" + (sec / 60) + " min");
        if (sec < 86400) return L.s("约 " + (sec / 3600) + " 小时", "~" + (sec / 3600) + " h");
        if (sec < 86400L * 365) return L.s("约 " + (sec / 86400) + " 天", "~" + (sec / 86400) + " d");
        return L.s("约 " + (sec / (86400L * 365)) + " 年", "~" + (sec / (86400L * 365)) + " y");
    }

    /** 一个号段的全尾号条数（1 万）—— 界面文案里反复用到。 */
    public static final int TAILS_PER_SEG = 10000;

    /** 3 位号段（前 3 位）的后 8 位空间。 */
    public static final long PREFIX3_SPACE = 100_000_000L;

    /**
     * 这批号段会生成多少条。
     *
     * <p><b>这个方法是预览与生成之间唯一的条数口径。</b>界面上的「约 N 条」
     * 与真正写盘/展开出来的条数必须由同一段代码给出 —— 分别在两处算，
     * 迟早会不一致，而爆破工具里的「假条数」意味着用户以为跑过了其实没跑。
     *
     * @param fast 只跑后 4 位高频形态（仅对 7 位号段有意义）
     */
    public static long entriesOf(List<String> segs, boolean fast, int nowYear) {
        long total = 0;
        int tails = tailPatterns(nowYear).size();
        if (segs == null) return 0;
        for (String s : segs) {
            if (s == null || s.isEmpty()) continue;
            if (s.length() == 7) {
                total += fast ? tails : TAILS_PER_SEG;
            } else {
                total += PREFIX3_SPACE;
            }
        }
        return total;
    }
}

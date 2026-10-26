package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 画像派生器（v2，2026-10-26 推倒重写）。
 *
 * ── 为什么重写 ────────────────────────────────────────────────
 * v1 把"线索"当成另一个 SSID 做字符串变形，结果对手机号 19395408514
 * 吐出 00408514 / 40851411 / 40851488 / 9540851495408514 这类
 * **没有任何人会设**的串，还把候选从 800 撑到 1495（按 3.6 秒/条 = 1 小时）。
 *
 * 参考了主流项目之后改成"画像"：
 *   · CUPP(6.6k★)      —— 只问人的信息（名/昵称/生日/伴侣/孩子/宠物/公司），
 *                          且有长度闸门 wcfrom=5 wcto=12
 *   · elpscrk(964★)    —— 每个维度只出 2~4 种形态，靠"维度互拼"扩展；
 *                          手机号只有 全号 / 前四 / 后四 三种
 *   · hashcat 年度规则  —— 变形只有"追加 1 / 12 / 123 / 年份 / 符号"这几种原子操作
 *   · pcfg_cracker     —— 输出必须按概率排序，评价指标是"平均第几次命中"
 *
 * ── 三层，按证据密度排序 ─────────────────────────────────────
 *   VALUE  原值：几乎就是答案本身，一个字段只给 1~3 条
 *   COMBO  互拼：拼音+生日 / 拼音+门牌 / 拼音+手机后四位…
 *   MANGLE 追加：只在原值后面加 1 / 12 / 123 / 年份 / 520 / ！…
 *
 * 本类**不负责**长度闸门与去重 —— 那些在 TargetDictBuilder.add() 里统一做，
 * 避免两处规则漂移。
 */
public final class SeedDeriver {

    /** 三层标签，供排序与界面说明用 */
    public enum Tier {
        VALUE,   // 原值
        COMBO,   // 互拼
        MANGLE   // 追加
    }

    public static final class Item {
        public final String value;
        public final Tier tier;
        /** 来源说明（拼音/手机号/生日…），只用于解释，不参与判定 */
        public final String from;

        Item(String v, Tier t, String from) {
            this.value = v;
            this.tier = t;
            this.from = from;
        }
    }

    /** 组合层上限：两条信息互拼，维度一多就会爆，必须封顶 */
    public static final int MAX_COMBO = 24;
    /** 追加层上限：只对最高价值的几个原值做追加 */
    public static final int MAX_MANGLE = 20;
    /** 参与追加的原值个数 */
    private static final int MANGLE_BASES = 3;

    /** 追加用的数字/符号（顺序即优先级，抄 hashcat top10_2025.rule 的高频段） */
    private static final String[] APPEND = {
            "1", "123", "1234", "520", "1314", "888", "666", "8", "88", "!",
    };

    private SeedDeriver() {}

    // ---------------------------------------------------------------- 主入口

    /** 生成全部画像候选，顺序 = 优先级（VALUE → COMBO → MANGLE） */
    public static List<Item> profile(SeedModel s) {
        List<Item> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;

        List<String> phones = new ArrayList<>();
        for (String p : s.phones) {
            String d = SeedModel.digits(p);
            if (d.length() == 11 && d.charAt(0) == '1') phones.add(d);
        }
        List<String> names = new ArrayList<>();
        for (String n : s.names) {
            String v = norm(n);
            if (!v.isEmpty() && !names.contains(v)) names.add(v);
        }
        String[] bd = SeedModel.birthdayForms(SeedModel.t(s.birthday));
        String full8 = bd[0], yy6 = bd[1], mmdd = bd[2];
        String year = full8.length() == 8 ? full8.substring(0, 4) : "";
        String land = SeedModel.digits(s.landline);
        String house = SeedModel.digits(s.houseNo);
        String old = SeedModel.t(s.oldPassword);

        List<String> values = new ArrayList<>();
        List<Item> combos = new ArrayList<>();
        List<Item> mangles = new ArrayList<>();

        // ---- VALUE：旧口令排最前（人改密码基本是在旧密码上微调）----
        if (!old.isEmpty()) values.add(old);

        // ---- VALUE：手机号 ----
        // 只给三种形态：全号 / 后 8 位 / "1"+后 8 位。
        // 后四位、前四位只有 4 个字符，WPA 口令最短 8 位 —— 单独用不可能成立，
        // 所以它们只在组合层当零件（name+后四位），不占原值额度。
        // 依据：本机 107 次真实命中里 9 位有 20 条（支持 "1"+后8 这种形态），
        // 而从没出现 11 位以外的手机号变形。
        for (String p : phones) {
            values.add(p);
            values.add(p.substring(3));
            values.add("1" + p.substring(3));
        }

        // ---- VALUE：生日 ----
        // 8 位整串是唯一能单独成立（>=8 位）的形态；6 位/月日留给组合层。
        if (full8.length() == 8) values.add(full8);

        // ---- VALUE：姓名拼音 ----
        // 只有长度 >= 8 的拼音才能单独当口令（zhangwei=8 ✓，wangwei=7 ✗）。
        // 短的（含首字母）一律只进组合层 —— 这是"不给没人会设的形态"的关键。
        for (String n : names) {
            if (n.length() >= 8) {
                values.add(n);
                values.add(cap(n));
            }
        }

        // ---- VALUE：宽带账号 / 固话 ----
        if (land.length() >= 8) {
            values.add(land);
            values.add(land.substring(land.length() - 8));
        }

        // ---- VALUE：自由词 ----
        for (String c : s.customs) {
            String v = SeedModel.t(c);
            if (v.length() >= 8) values.add(v);
        }

        java.util.LinkedHashSet<String> seen = new LinkedHashSet<>(values);
        for (String v : seen) out.add(new Item(v, Tier.VALUE, "原值"));

        // ---- COMBO：维度互拼（这是主流工具真正的杀招）----
        java.util.LinkedHashSet<String> cseen = new LinkedHashSet<>();
        for (String n : names) {
            String ini = initials(n);
            for (String p : phones) {
                String t4 = p.substring(7);
                String b8 = p.substring(3);
                addCombo(combos, cseen, n + t4, "拼音+手机后四位");
                addCombo(combos, cseen, cap(n) + t4, "拼音+手机后四位");
                addCombo(combos, cseen, b8 + ini, "手机后八位+首字母");
            }
            if (!mmdd.isEmpty()) {
                addCombo(combos, cseen, n + mmdd, "拼音+生日月日");
                addCombo(combos, cseen, cap(n) + mmdd, "拼音+生日月日");
            }
            if (!house.isEmpty()) addCombo(combos, cseen, n + house, "拼音+门牌");
            if (!year.isEmpty()) addCombo(combos, cseen, n + year, "拼音+出生年");
            if (!ini.isEmpty()) {
                if (!mmdd.isEmpty()) addCombo(combos, cseen, ini + mmdd, "首字母+生日月日");
                if (!house.isEmpty()) addCombo(combos, cseen, ini + house, "首字母+门牌");
                if (!year.isEmpty()) addCombo(combos, cseen, ini + year, "首字母+出生年");
            }
        }
        // 两个名字互拼（zhangwei + xiaobao）
        for (int i = 0; i < names.size(); i++) {
            for (int j = 0; j < names.size(); j++) {
                if (i == j) continue;
                addCombo(combos, cseen, names.get(i) + names.get(j), "两个名字互拼");
            }
        }
        if (!house.isEmpty() && !mmdd.isEmpty()) addCombo(combos, cseen, house + mmdd, "门牌+生日月日");
        if (!land.isEmpty() && !mmdd.isEmpty()) addCombo(combos, cseen, land + mmdd, "宽带账号+生日月日");
        if (combos.size() > MAX_COMBO) combos = combos.subList(0, MAX_COMBO);
        out.addAll(combos);

        // ---- MANGLE：只对最高价值的几个原值追加 ----
        List<String> bases = new ArrayList<>();
        if (!old.isEmpty()) bases.add(old);
        for (String n : names) {
            bases.add(n);
            if (bases.size() >= MANGLE_BASES) break;
        }
        for (String c : s.customs) {
            if (bases.size() >= MANGLE_BASES) break;
            String v = SeedModel.t(c);
            if (!v.isEmpty()) bases.add(v);
        }
        java.util.LinkedHashSet<String> mseen = new LinkedHashSet<>();
        for (String b : bases) {
            for (String ap : APPEND) {
                if (mangles.size() >= MAX_MANGLE) break;
                addMangle(mangles, mseen, b + ap, "追加");
            }
            if (mangles.size() >= MAX_MANGLE) break;
        }
        out.addAll(mangles);

        return out;
    }

    /** 只要原值层（界面预览、排序器加分用） */
    public static List<String> values(SeedModel s) {
        List<String> out = new ArrayList<>();
        for (Item it : profile(s)) {
            if (it.tier == Tier.VALUE) out.add(it.value);
        }
        return out;
    }

    /**
     * 短片段集合（长度 >= 4）：给排序器判断"候选里出现了画像的一部分"。
     * 例：手机后四位出现在 zhangwei8514 里 → 这条值得加分。
     */
    public static List<String> shortFragments(SeedModel s) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (s == null) return new ArrayList<>();
        for (String p : s.phones) {
            String d = SeedModel.digits(p);
            if (d.length() != 11) continue;
            out.add(d.substring(3));   // 后八位
            out.add(d.substring(7));   // 后四位
            out.add(d.substring(0, 4));// 前四位（号段）
        }
        String[] bd = SeedModel.birthdayForms(SeedModel.t(s.birthday));
        if (!bd[0].isEmpty()) {
            out.add(bd[0]);
            out.add(bd[0].substring(0, 4));   // 出生年
            out.add(bd[2]);                   // 月日
        }
        for (String n : s.names) {
            String v = norm(n);
            if (v.length() >= 4) out.add(v.substring(0, 4));
            String ini = initials(v);
            if (ini.length() >= 2) out.add(ini);
        }
        String h = SeedModel.digits(s.houseNo);
        if (h.length() >= 3) out.add(h);
        String l = SeedModel.digits(s.landline);
        if (l.length() >= 4) out.add(l.substring(Math.max(0, l.length() - 4)));
        for (String c : s.customs) {
            String v = SeedModel.t(c);
            if (v.length() >= 4) out.add(v);
        }
        if (!SeedModel.t(s.oldPassword).isEmpty()) out.add(SeedModel.t(s.oldPassword));

        List<String> list = new ArrayList<>();
        for (String x : out) {
            String v = x.toLowerCase(Locale.ROOT);
            if (v.length() >= 4) list.add(v);
        }
        return list;
    }

    // ---------------------------------------------------------------- 工具

    private static void addCombo(List<Item> out, LinkedHashSet<String> seen, String v, String from) {
        if (out.size() >= MAX_COMBO) return;
        String s = SeedModel.t(v);
        if (s.isEmpty() || s.length() > 63) return;
        if (seen.add(s)) out.add(new Item(s, Tier.COMBO, from));
    }

    private static void addMangle(List<Item> out, LinkedHashSet<String> seen, String v, String from) {
        if (out.size() >= MAX_MANGLE) return;
        String s = SeedModel.t(v);
        if (s.isEmpty() || s.length() > 63) return;
        if (seen.add(s)) out.add(new Item(s, Tier.MANGLE, from));
    }

    static String norm(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : SeedModel.t(raw).toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        String s = sb.toString();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /**
     * 姓名首字母。用标准普通话音节表做贪心最长匹配切分，全部切成功才给首字母，
     * 切不动就返回空 —— 宁可少给一条候选，也不给一条错的。
     *
     * zhangwei -> zhang|wei -> zw
     */
    public static String initials(String pinyin) {
        if (pinyin == null || pinyin.length() < 2) return "";
        List<String> syl = new ArrayList<>();
        int i = 0;
        while (i < pinyin.length()) {
            String hit = null;
            int max = Math.min(6, pinyin.length() - i);
            for (int len = max; len >= 1; len--) {
                String cand = pinyin.substring(i, i + len);
                if (SYLLABLES.contains(cand)) { hit = cand; break; }
            }
            if (hit == null) return "";
            syl.add(hit);
            i += hit.length();
        }
        if (syl.size() < 2) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : syl) sb.append(s.charAt(0));
        return sb.toString();
    }

    private static String cap(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 标准普通话音节表，只为音节切分用 */
    private static final java.util.Set<String> SYLLABLES = new java.util.HashSet<>();
    static {
        String all = "a ai an ang ao "
                + "ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu "
                + "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi "
                + "chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo "
                + "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du "
                + "duan dui dun duo "
                + "e ei en eng er "
                + "fa fan fang fei fen feng fo fou fu "
                + "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo "
                + "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo "
                + "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun "
                + "ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo "
                + "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu long lou "
                + "lu luan lun luo lv lve "
                + "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu "
                + "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong "
                + "nou nu nuan nuo nv nve "
                + "o ou "
                + "pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu "
                + "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun "
                + "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo "
                + "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi "
                + "shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo "
                + "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo "
                + "wa wai wan wang wei wen weng wo wu "
                + "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun "
                + "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun "
                + "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhen zheng zhi "
                + "zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo";
        for (String s : all.split(" ")) if (!s.isEmpty()) SYLLABLES.add(s);
    }
}

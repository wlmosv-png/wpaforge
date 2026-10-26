package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 线索派生器：把用户填的线索展开成一批候选口令。
 *
 * 与 {@link TargetDictBuilder} 的分工：
 *   · 本类只负责「一条线索能长成哪些口令」，输出原样字符串；
 *   · 长度合法性（>=8 位）、不足 8 位的补齐策略、去重与上限，
 *     统一交给 TargetDictBuilder.add() —— 那套规则已经在生产里跑过，
 *     不在这里再写一遍，避免两处规则漂移。
 *
 * 纯逻辑、不依赖 Android，可离线单测。
 *
 * 每条规则的存在理由都写在行内 —— 判断一条规则该不该加，
 * 看的是「真实家庭网络里有人这么设吗」，不是「理论上可能吗」。
 */
public final class SeedDeriver {

    /** 单个线索类别的展开上限，防止用户填 4 个号把候选池占满 */
    public static final int DEFAULT_PER_CAT = 400;
    /** 线索派生总量上限 */
    public static final int DEFAULT_TOTAL = 3000;

    private SeedDeriver() {}

    public static List<String> derive(SeedModel s) {
        return derive(s, DEFAULT_PER_CAT, DEFAULT_TOTAL);
    }

    // ---------------------------------------------------------------- 主入口

    public static List<String> derive(SeedModel s, int perCat, int total) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;

        int cap = perCat <= 0 ? DEFAULT_PER_CAT : perCat;
        int lim = total <= 0 ? DEFAULT_TOTAL : total;

        List<String> phones = new ArrayList<>();
        for (String p : s.phones) {
            String d = SeedModel.digits(p);
            if (d.length() == 11 && d.charAt(0) == '1') phones.add(d);
        }

        String[] bd = birthdayForms(SeedModel.t(s.birthday));
        String full8 = bd[0], yy6 = bd[1], mmdd = bd[2];

        String name = normPinyin(s.namePinyin);
        String ini = initials(name);

        String land = SeedModel.digits(s.landline);
        String house = SeedModel.digits(s.houseNo);

        put(out, phoneBucket(phones, ini, mmdd, house), cap, lim);
        put(out, birthdayBucket(full8, yy6, mmdd, name, ini), cap, lim);
        put(out, pinyinBucket(name, ini, mmdd, house, full8, phones), cap, lim);
        put(out, landlineBucket(land, mmdd), cap, lim);
        put(out, houseBucket(house, mmdd), cap, lim);
        put(out, customBucket(s.customs, mmdd, phones), cap, lim);

        return out;
    }

    /**
     * 短片段集合（长度 >= 4，且通常只有 5 种以内）。
     * 排序器用它判断「候选里出现了线索的一部分」，比如后 8 位手机号。
     */
    public static List<String> fragments(SeedModel s) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (s == null) return new ArrayList<>();
        for (String p : s.phones) {
            String d = SeedModel.digits(p);
            if (d.length() != 11) continue;
            out.add(d.substring(3));          // 后 8 位
            out.add(d.substring(5));          // 后 6 位
            out.add(d.substring(0, 3) + d.substring(5));
            out.add(d.substring(7));          // 后 4 位
        }
        String[] bd = birthdayForms(SeedModel.t(s.birthday));
        if (!bd[0].isEmpty()) out.add(bd[0]);
        if (!bd[2].isEmpty()) out.add(bd[2]);
        String name = normPinyin(s.namePinyin);
        if (!name.isEmpty()) { out.add(name); if (name.length() >= 4) out.add(name.substring(0, 4)); }
        String ini = initials(name);
        if (!ini.isEmpty()) out.add(ini);
        String house = SeedModel.digits(s.houseNo);
        if (!house.isEmpty()) out.add(house);
        String land = SeedModel.digits(s.landline);
        if (land.length() >= 4) out.add(land);
        for (String c : s.customs) {
            String v = SeedModel.t(c);
            if (v.length() >= 4) out.add(v);
        }
        List<String> list = new ArrayList<>();
        for (String x : out) if (x.length() >= 4) list.add(x.toLowerCase(Locale.ROOT));
        return list;
    }

    // ---------------------------------------------------------------- 各线索类别

    /**
     * 手机号。国内家庭网络里排第一的密码形态就是户主手机号本身或它的后 8 位。
     * 无法穷举（10^8），所以只能靠用户给 —— 这正是本功能存在的理由。
     */
    private static LinkedHashSet<String> phoneBucket(List<String> phones, String ini,
                                                     String mmdd, String house) {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        for (String p : phones) {
            String b8 = p.substring(3);        // 后 8 位
            String b6 = p.substring(5);        // 后 6 位
            String pre = p.substring(0, 3);    // 号段
            String t4 = p.substring(7);        // 后 4 位

            c.add(p);                          // 全号 11 位
            c.add(b8);                         // 后 8 位（最高频）
            c.add(b6);
            c.add(pre + b6);                   // 号段 + 后 6 位，9 位
            c.add(b8 + b8);
            c.add("1" + b8);                   // 前面补个 1 凑长度，很常见
            c.add(t4 + t4 + t4 + t4);          // 尾号四位重复
            for (String sf : new String[]{"a", "aa", "1", "00", "@", ".", "#"}) c.add(p + sf);
            c.add("a" + p);
            c.add("A" + p);
            c.add("@" + p);
            if (!ini.isEmpty()) {
                c.add(b8 + ini);
                c.add(ini + b8);
            }
            if (!mmdd.isEmpty()) c.add(b8 + mmdd);
            if (!house.isEmpty()) c.add(b8 + house);
        }
        return c;
    }

    /**
     * 生日。8 位是绝对主流；6 位（少了世纪）和 4 位月日也真实存在。
     * 不生成「年份区间」这种发散 —— 那交给远端的 rule_date 与第八桶。
     */
    private static LinkedHashSet<String> birthdayBucket(String full8, String yy6, String mmdd,
                                                        String name, String ini) {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        if (!full8.isEmpty()) {
            c.add(full8);
            c.add(full8 + "a");
            c.add(full8 + "aa");
            c.add(full8 + "520");
            c.add(full8 + "@");
            c.add("a" + full8);
            c.add("@" + full8);
            c.add(full8.substring(0, 4));      // 出生年（不足 8 位由 add() 补齐）
            if (!name.isEmpty()) c.add(name + full8);
            if (!ini.isEmpty()) c.add(ini + full8);
        }
        if (!yy6.isEmpty()) {
            c.add(yy6);
            c.add("19" + yy6);
            c.add("20" + yy6);
        }
        if (!mmdd.isEmpty()) {
            c.add(mmdd);
            c.add(mmdd + mmdd);
            c.add("0" + mmdd + "0" + mmdd);
            if (!name.isEmpty()) c.add(name + mmdd);
            if (!ini.isEmpty()) c.add(ini + mmdd);
        }
        return c;
    }

    /**
     * 姓名拼音。国内大量 WiFi 密码是「名字拼音 + 数字」或名字首字母 + 数字。
     * 没有拼音表也能做全拼，但要拿到首字母就必须做音节切分 —— 见 SYLLABLES。
     */
    private static LinkedHashSet<String> pinyinBucket(String name, String ini, String mmdd,
                                                      String house, String full8,
                                                      List<String> phones) {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        if (name.isEmpty()) return c;

        c.add(name);
        c.add(cap(name));
        c.add(name.toUpperCase(Locale.ROOT));
        if (!ini.isEmpty()) {
            c.add(ini);
            c.add(ini.toUpperCase(Locale.ROOT));
            c.add(cap(ini));
        }
        for (String sf : TargetDictBuilder.NUM_SUFFIX) {
            c.add(name + sf);
            c.add(cap(name) + sf);
            if (!ini.isEmpty()) c.add(ini + sf);
        }
        if (!mmdd.isEmpty()) {
            c.add(name + mmdd);
            c.add(cap(name) + mmdd);
            if (!ini.isEmpty()) c.add(ini + mmdd);
        }
        if (!full8.isEmpty()) {
            c.add(name + full8);
            if (!ini.isEmpty()) c.add(ini + full8);
        }
        if (!house.isEmpty()) c.add(name + house);
        if (!phones.isEmpty()) {
            String t4 = phones.get(0).substring(7);
            c.add(name + t4);
            if (!ini.isEmpty()) c.add(ini + t4);
        }
        return c;
    }

    /** 固话 / 宽带账号。部分地区装机默认密码就是号码本身或其后 8 位 */
    private static LinkedHashSet<String> landlineBucket(String land, String mmdd) {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        if (land.length() < 6) return c;
        c.add(land);
        c.add("0" + land);
        c.add(land + "0");
        c.add(land + "00");
        c.add(land + land);
        c.add(land + "888");
        c.add(land + "666");
        c.add(land + "520");
        if (land.length() >= 8) c.add(land.substring(land.length() - 8));
        c.add(land + land.substring(Math.max(0, land.length() - 4)));
        if (!mmdd.isEmpty()) c.add(land + mmdd);
        return c;
    }

    /** 门牌 / 楼层 / 房间号 */
    private static LinkedHashSet<String> houseBucket(String house, String mmdd) {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        if (house.isEmpty()) return c;
        c.add(house);
        c.add(house + "0");
        c.add(house + "00");
        c.add(house + "000");
        c.add(house + house);
        for (String sf : new String[]{"123", "1234", "520", "521", "1314", "888", "666",
                "2024", "2025", "2026", "168"}) {
            c.add(house + sf);
        }
        if (!mmdd.isEmpty()) c.add(house + mmdd);
        return c;
    }

    /** 自由词：用户自己想到的什么就加什么，一律原样 + 常见后缀 */
    private static LinkedHashSet<String> customBucket(List<String> customs, String mmdd,
                                                      List<String> phones) {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        if (customs == null) return c;
        for (String raw : customs) {
            String v = SeedModel.t(raw);
            if (v.isEmpty()) continue;
            c.add(v);
            c.add(v + "0");
            c.add(v + "00");
            for (String sf : new String[]{"123", "1234", "520", "521", "1314", "888", "666",
                    "2024", "2025", "2026", "@", "!"}) {
                c.add(v + sf);
            }
            if (!mmdd.isEmpty()) c.add(v + mmdd);
            if (!phones.isEmpty()) c.add(v + phones.get(0).substring(7));
        }
        return c;
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 按类别取前 cap 条并入总池；总量到顶就停。
     * 用 LinkedHashSet 保序去重 —— 先加的是「更可能命中」的那批。
     */
    private static void put(List<String> out, LinkedHashSet<String> cat, int cap, int lim) {
        int n = 0;
        for (String v : cat) {
            if (n >= cap || out.size() >= lim) return;
            if (v == null) continue;
            String s = v.trim();
            if (s.isEmpty() || s.length() > 63) continue;
            if (!out.contains(s)) out.add(s);
            n++;
        }
    }

    /**
     * 生日归一。返回 {8 位完整, 6 位YYMMDD, MMDD}，缺的给空串。
     * 接受 19900101 / 900101 / 0101 / 1990-01-01 这几种写法。
     */
    static String[] birthdayForms(String raw) {
        String d = SeedModel.digits(raw);
        String full = "", yy = "", mmdd = "";
        if (d.length() >= 8) {
            full = d.substring(d.length() - 8);
            yy = full.substring(2);
            mmdd = full.substring(4);
        } else if (d.length() == 6) {
            yy = d;
            full = "19" + d;
            mmdd = d.substring(2);
        } else if (d.length() == 4) {
            mmdd = d;
        } else if (d.length() == 5) {
            mmdd = d.substring(1);
        }
        return new String[]{full, yy, mmdd};
    }

    /** 拼音只留字母并转小写；超过 24 字符截断（正常姓名不会这么长） */
    static String normPinyin(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : SeedModel.t(raw).toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        String s = sb.toString();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /**
     * 姓名首字母。做法：用标准普通话音节表做贪心最长匹配切分，
     * 全部切成功才给首字母，切不动就返回空 —— 宁可少给一条候选，
     * 也不给一个错的（错的候选会占用真实连接尝试的份额）。
     *
     * zhangwei -> zhang|wei -> zw
     * wangxiaoming -> wang|xiao|ming -> wxm
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

    /**
     * 标准普通话音节表（含 lv/nv 的 v 写法）。
     * 只为音节切分用，不做任何发音或语义推断。
     */
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

package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.TestPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 手机号字典的门禁（2026-11-14 新增）。
 *
 * <p>这一块最容易出的不是逻辑错，是**数据漂移**：Java 里的号段表
 * （{@link PhoneSegs}）和内置换出的规则文件（{@code assets/builtin/rule_phone_cn.txt}）
 * 是两份独立的号段清单，任何一边加了段、另一边没加，界面显示的条数就是假的，
 * 而「假条数」在爆破工具里等于「用户以为跑过了其实没跑」。所以第一组用例
 * 就是拿磁盘上的 rule 文件反解出前缀表，与 Java 表逐项对齐。
 *
 * <p>第二组是算账：条数与在线可行性必须在同一口径上（1.5 条/秒、
 * 10 万条为在线上限），否则界面上会同时出现「约 2 小时」和「跑不完」。
 *
 * <p>第三组是 {@link DictRule} 的多前缀扩展：老行为一字不变 + 新行为均分预算。
 */
public class PhoneSegsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    public static void main(String[] args) {
        System.out.println("== 号段表自洽 ==");
        List<PhoneSegs.Seg> all = PhoneSegs.all();
        ok("号段数 54", PhoneSegs.count() == 54, "" + PhoneSegs.count());
        boolean shape = true, yearOk = true, dup = false;
        Set<String> seen = new LinkedHashSet<>();
        for (PhoneSegs.Seg s : all) {
            if (s.p3.length() != 3 || s.p3.charAt(0) != '1'
                    || s.p3.charAt(1) < '3' || s.p3.charAt(1) > '9') shape = false;
            if (s.year < 1994 || s.year > 2026) yearOk = false;
            if (!seen.add(s.p3)) dup = true;
        }
        ok("前 3 位都是 1[3-9] 形态", shape);
        ok("启用年都在 1994–2026", yearOk);
        ok("号段无重复", !dup);
        int sum = 0;
        for (int op = PhoneSegs.OP_MOBILE; op <= PhoneSegs.OP_MVNO; op++) {
            sum += PhoneSegs.byOperator(op).size();
        }
        ok("分运营商合计 = 总数", sum == PhoneSegs.count(), sum + " vs " + PhoneSegs.count());

        System.out.println("== Java 表 vs 内置规则文件（防数据漂移）==");
        String assetRel = "app/src/main/assets/builtin/rule_phone_cn.txt";
        File asset = new File(TestPaths.under(assetRel));
        ok("规则文件存在", asset.isFile(), asset.getAbsolutePath());
        if (asset.isFile()) {
            String text = readText(asset);
            DictRule fileRule = DictRule.parse(stripComments(text));
            ok("文件可解析为规则", fileRule.isValid(), text.length() + " 字节");
            List<String> fromFile = fileRule.prefixes();
            List<String> fromJava = PhoneSegs.prefixesOf(all);
            ok("文件前缀数 = Java 表条数",
                    fromFile.size() == fromJava.size(),
                    fromFile.size() + " vs " + fromJava.size());
            ok("文件前缀集合 = Java 表集合",
                    new LinkedHashSet<>(fromFile).equals(new LinkedHashSet<>(fromJava)),
                    "差异: " + diff(fromFile, fromJava));
            ok("文件起始/末尾 = 后 8 位全排列",
                    "00000000".equals(fileRule.start) && "99999999".equals(fileRule.end),
                    fileRule.start + "-" + fileRule.end);
            ok("文件估算 ≈ 54 亿条",
                    fileRule.estimateCount() == 54L * 100_000_000L,
                    "" + fileRule.estimateCount());
        }

        System.out.println("== 内置清单已登记 ==");
        ok("93 规则-中国手机号段 已登记",
                BuiltinDicts.find("93 规则-中国手机号段.txt") != null);
        ok("94 规则-本地号段模板 已登记",
                BuiltinDicts.find("94 规则-本地号段模板.txt") != null);
        ok("两条都在穷举档",
                BuiltinDicts.find("93 规则-中国手机号段.txt").tier == BuiltinDicts.TIER_BRUTE
                        && BuiltinDicts.find("94 规则-本地号段模板.txt").tier == BuiltinDicts.TIER_BRUTE);
        ok("本地模板的 asset 存在",
                new File(TestPaths.under("app/src/main/assets/builtin/rule_phone_local.txt")).isFile());

        System.out.println("== 年龄窗口 ==");
        ok("40 岁 → 首卡年 2006", PhoneSegs.firstCardYear(40, 2026) == 2006,
                "" + PhoneSegs.firstCardYear(40, 2026));
        List<String> a40 = PhoneSegs.prefixesOf(PhoneSegs.forAge(40, 2026));
        ok("40 岁窗口含 138/139 老段", a40.contains("138") && a40.contains("139"));
        ok("40 岁窗口含 135/136", a40.contains("135") && a40.contains("136"));
        ok("40 岁窗口不含 199（2017 才启用）", !a40.contains("199"));
        List<PhoneSegs.Seg> win40 = PhoneSegs.forAge(40, 2026);
        ok("40 岁窗口首项是 2006 前后启用的段",
                win40.get(0).p3.equals("156") || win40.get(0).p3.equals("158"),
                win40.get(0).toString());
        boolean sorted = true;
        for (int i = 1; i < win40.size(); i++) {
            int prev = Math.abs(win40.get(i - 1).year - 2006);
            int cur = Math.abs(win40.get(i).year - 2006);
            if (cur < prev) sorted = false;
        }
        ok("窗口按 |启用年 − 首卡年| 升序", sorted);
        List<String> a20 = PhoneSegs.prefixesOf(PhoneSegs.forAge(20, 2026));
        ok("20 岁窗口含 199/197 新段", a20.contains("199") && a20.contains("197"));
        ok("20 岁窗口不含 130（1994 太早）", !a20.contains("130"));
        ok("年龄非法 → 返回全部（不猜）",
                PhoneSegs.forAge(0, 2026).size() == PhoneSegs.count()
                        && PhoneSegs.forAge(200, 2026).size() == PhoneSegs.count());
        ok("年份非法 → 返回全部（不猜）",
                PhoneSegs.forAge(40, 1990).size() == PhoneSegs.count());

        System.out.println("== 从粘贴文本里抽号段（绝不能带出完整号码）==");
        List<String> e1 = PhoneSegs.extractSeg7("13805123456");
        ok("11 位号码 → 只留前 7 位", e1.size() == 1 && e1.get(0).equals("1380512"), "" + e1);
        List<String> e2 = PhoneSegs.extractSeg7("1380512");
        ok("裸号段原样保留", e2.size() == 1 && e2.get(0).equals("1380512"), "" + e2);
        List<String> e3 = PhoneSegs.extractSeg7("13805123456，13905123456；13805123456");
        ok("混合分隔 + 去重保序",
                e3.size() == 2 && e3.get(0).equals("1380512") && e3.get(1).equals("1390512"), "" + e3);
        ok("超长数字串只取前 7 位",
                PhoneSegs.extractSeg7("138051234567890").equals(java.util.Collections.singletonList("1380512")));
        ok("非 1[3-9] 开头不认", PhoneSegs.extractSeg7("23805123456").isEmpty()
                && PhoneSegs.extractSeg7("订单号 2026111400001234").isEmpty());
        ok("null 安全", PhoneSegs.extractSeg7(null).isEmpty());
        ok("文字里没号码 → 空", PhoneSegs.extractSeg7("常州移动的号").isEmpty());
        ok("looksLikeNumbers 与 extractSeg7 一致",
                PhoneSegs.looksLikeNumbers("13805123456")
                        && !PhoneSegs.looksLikeNumbers("没有号码"));
        // 关键不变式：任何情况下，抽出来的东西都不可能是完整 11 位号码
        boolean noFull = true;
        for (String s : PhoneSegs.extractSeg7("13805123456 1380512 2026111400001234")) {
            if (s.length() != 7) noFull = false;
        }
        ok("抽出的每一项长度都是 7（不含完整号码）", noFull);

        System.out.println("== 尾号形态 ==");
        List<String> tails = PhoneSegs.tailPatterns(2026);
        List<String> tailsNew = PhoneSegs.tailPatterns(0);
        ok("有全同号 8888/6666/0000",
                tails.contains("8888") && tails.contains("6666") && tails.contains("0000"));
        ok("有顺子 1234/6789", tails.contains("1234") && tails.contains("6789"));
        ok("有 ABAB 1212/8989", tails.contains("1212") && tails.contains("8989"));
        ok("有出生年 1990/2005", tails.contains("1990") && tails.contains("2005"));
        ok("有谐音 1314/0520", tails.contains("1314") && tails.contains("0520"));
        ok("全部都是 4 位数字", allFourDigits(tails), "" + tails.size());
        ok("nowYear=0 时不出生年族", !tailsNew.contains("1990"));
        ok("尾号表已去重", tails.size() == new LinkedHashSet<>(tails).size(),
                tails.size() + " vs " + new LinkedHashSet<>(tails).size());
        ok("规模在几十到两百条之间（够小才跑得完）",
                tails.size() >= 60 && tails.size() <= 200, "" + tails.size());

        System.out.println("== 明文展开（A 档：号段 × 结构尾号）==");
        List<String> one = PhoneSegs.expandTails(
                java.util.Collections.singletonList("1380512"),
                java.util.Arrays.asList("8888", "1234"), 100);
        ok("拼接正确", one.size() == 2
                && one.get(0).equals("13805128888")
                && one.get(1).equals("13805121234"), "" + one);
        List<String> many = PhoneSegs.expandTails(
                java.util.Arrays.asList("1380512", "1390512"), tails, 1_000_000);
        ok("两条号段 × 尾号表", many.size() == tails.size() * 2, "" + many.size());
        boolean all11 = true;
        for (String s : many) if (s.length() != 11) all11 = false;
        ok("全部 11 位", all11);
        ok("上限生效",
                PhoneSegs.expandTails(java.util.Arrays.asList("1380512"), tails, 10).size() == 10);

        System.out.println("== 规则生成与算账 ==");
        DictRule fr = PhoneSegs.fullRule(java.util.Collections.singletonList("1380512"));
        ok("单号段全尾号 = 1 万条", fr.estimateCount() == 10000, "" + fr.estimateCount());
        ok("单号段在线可行", PhoneSegs.onlineFeasible(fr.estimateCount()));
        ok("单号段约 1.9 小时", PhoneSegs.estimateText(10000).contains("小时"),
                PhoneSegs.estimateText(10000));

        DictRule nr = PhoneSegs.nationalRule();
        ok("全国号段规则 = 54 亿条",
                nr.estimateCount() == 54L * 100_000_000L, "" + nr.estimateCount());
        ok("全国在线不可行", !PhoneSegs.onlineFeasible(nr.estimateCount()));
        ok("全国预估到「年」级", PhoneSegs.estimateText(nr.estimateCount()).contains("年"),
                PhoneSegs.estimateText(nr.estimateCount()));

        DictRule ar = PhoneSegs.ageRule(40, 2026);
        long a40Segs = PhoneSegs.forAge(40, 2026).size();
        ok("年龄规则条数 = 窗口号段数 × 1 亿",
                ar.estimateCount() == a40Segs * 100_000_000L,
                ar.estimateCount() + " vs " + a40Segs + "e8");
        ok("年龄规则也判不可行（离线才有意义）", !PhoneSegs.onlineFeasible(ar.estimateCount()));

        System.out.println("== DictRule 多前缀（老行为一字不变）==");
        DictRule p1 = DictRule.parse("起始:00000000\n末尾:99999999");
        ok("空前缀仍是 1 亿条", p1.estimateCount() == 100_000_000L, "" + p1.estimateCount());
        ok("空前缀列表是 [\"\"]",
                p1.prefixes().size() == 1 && p1.prefixes().get(0).isEmpty());
        DictRule p2 = DictRule.parse("前缀:130,131,138\n起始:00000000\n末尾:99999999");
        ok("三前缀 → 前缀表 3 项", p2.prefixes().size() == 3, "" + p2.prefixes());
        ok("三前缀 → 3 亿条", p2.estimateCount() == 300_000_000L, "" + p2.estimateCount());
        DictRule p3 = DictRule.parse("前缀:130,131\n后缀:a,b\n起始:0\n末尾:9");
        ok("前缀 × 后缀 笛卡尔积 = 40", p3.estimateCount() == 40, "" + p3.estimateCount());
        DictRule p4 = DictRule.parse("前缀:130、131,132\n起始:0\n末尾:9");
        ok("中文逗号/顿号也能切", p4.prefixes().size() == 3, "" + p4.prefixes());

        System.out.println("== 多前缀展开：预算必须均分 ==");
        DictRule p5 = DictRule.parse("前缀:130,131,138\n起始:0000\n末尾:9999");
        List<Candidate> c5 = p5.expand(30);
        ok("总数不超上限", c5.size() == 30, "" + c5.size());
        Set<String> pfxSeen = new LinkedHashSet<>();
        for (Candidate c : c5) pfxSeen.add(c.password.substring(0, 3));
        ok("三个前缀都被抽到（不是全挤在第一个）",
                pfxSeen.size() == 3, "" + pfxSeen);
        DictRule p6 = DictRule.parse("前缀:130,131\n后缀:x\n起始:aaa\n末尾:zzz\n进制:字母");
        List<Candidate> c6 = p6.expand(20);
        Set<String> p6Seen = new LinkedHashSet<>();
        for (Candidate c : c6) p6Seen.add(c.password.substring(0, 3));
        ok("字母模式多前缀也均分", p6Seen.size() == 2, "" + p6Seen);
        DictRule p7 = DictRule.parse("前缀:130,131\n起始:0001\n末尾:0003");
        List<Candidate> c7 = p7.expand(100);
        ok("多前缀 × 小区间的全量展开 = 6 条", c7.size() == 6, "" + c7.size());
        ok("首条 = 1300001", c7.get(0).password.equals("1300001"), c7.get(0).password);
        ok("末条 = 1310003", c7.get(5).password.equals("1310003"), c7.get(5).password);

        System.out.println("== 序列化往返带多前缀 ==");
        DictRule p8 = DictRule.parse("前缀:130,131\n起始:0\n末尾:9");
        DictRule p8b = DictRule.parse(p8.serialize());
        ok("往返后前缀表一致", p8b.prefixes().equals(p8.prefixes()),
                p8.prefixes() + " -> " + p8b.prefixes());
        ok("往返后条数一致", p8b.estimateCount() == p8.estimateCount());

        System.out.println("== 预览条数 = 实际生成（防「假条数」）==");
        List<String> oneSeg = java.util.Collections.singletonList("1380512");
        ok("7 位号段 + 快档 = 尾号表条数",
                PhoneSegs.entriesOf(oneSeg, true, 2026) == tails.size(),
                PhoneSegs.entriesOf(oneSeg, true, 2026) + " vs " + tails.size());
        ok("预览条数 = expandTails 实际条数",
                PhoneSegs.entriesOf(oneSeg, true, 2026)
                        == PhoneSegs.expandTails(oneSeg, tails, 1_000_000).size());
        ok("7 位号段 + 全尾号 = 1 万",
                PhoneSegs.entriesOf(oneSeg, false, 2026) == PhoneSegs.TAILS_PER_SEG);
        ok("预览条数 = fullRule.estimateCount()",
                PhoneSegs.entriesOf(oneSeg, false, 2026) == PhoneSegs.fullRule(oneSeg).estimateCount());
        ok("3 位号段 = 1 亿",
                PhoneSegs.entriesOf(java.util.Collections.singletonList("138"), false, 2026)
                        == PhoneSegs.PREFIX3_SPACE);
        ok("预览条数 = nationalRuleOf.estimateCount()",
                PhoneSegs.entriesOf(java.util.Collections.singletonList("138"), false, 2026)
                        == PhoneSegs.nationalRuleOf(java.util.Collections.singletonList("138")).estimateCount());
        List<String> threeSeg = java.util.Arrays.asList("1380512", "1390512", "1370512");
        ok("多号段是加法而非乘法",
                PhoneSegs.entriesOf(threeSeg, true, 2026) == tails.size() * 3L,
                "" + PhoneSegs.entriesOf(threeSeg, true, 2026));
        ok("空表 = 0 条", PhoneSegs.entriesOf(new ArrayList<String>(), true, 2026) == 0
                && PhoneSegs.entriesOf(null, true, 2026) == 0);

        System.out.println();
        System.out.println("=== 手机号字典: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean allFourDigits(List<String> xs) {
        for (String s : xs) {
            if (s.length() != 4) return false;
            for (int i = 0; i < 4; i++) {
                if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
            }
        }
        return true;
    }

    private static String stripComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("#")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> diff(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        Set<String> sa = new LinkedHashSet<>(a), sb = new LinkedHashSet<>(b);
        for (String s : sa) if (!sb.contains(s)) out.add("文件独有:" + s);
        for (String s : sb) if (!sa.contains(s)) out.add("Java独有:" + s);
        return out;
    }

    private static String readText(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}

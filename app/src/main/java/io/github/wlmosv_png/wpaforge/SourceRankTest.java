package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.BuiltinDicts;
import io.github.wlmosv_png.wpaforge.audit.SourceRank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 来源排行（本机战绩驱动）自检。
 *
 * <p>盯四件事：
 * <ol>
 *   <li>代价公式 = (Σ已试 + 200) / (命中数 + 1)，**未命中的整本跑完也算代价**；</li>
 *   <li>排序：有历史的排前、代价小的更前；没历史的垫底且小表优先；</li>
 *   <li>从 history.csv 原始行汇总时只看前 8 列（不解密、不碰第 9/10 列）；</li>
 *   <li>内置清单的档位/理由自洽：核心档条数与清单一致、名称能被 find() 认出来。</li>
 * </ol>
 */
public class SourceRankTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    /** 造一行 history.csv：时间,SSID,BSSID,字典名,规模,已试,耗时ms,命中,标签,密文 */
    private static String line(String dict, int size, int tested, boolean hit) {
        return "1791690201830,B217,28:93:7D:45:DA:31," + dict + "," + size + ","
                + tested + "," + (tested * 6000L) + "," + (hit ? "1" : "0") + ",#abc,len=8,v1:xx";
    }

    public static void main(String[] a) {
        System.out.println("== 汇总与代价公式 ==");
        List<String> lines = new ArrayList<>();
        // 一本好表：跑 10 次、中 10 次、每次第 2 条就中
        for (int i = 0; i < 10; i++) lines.add(line("好表", 2000, 2, true));
        // 一本烂表：跑 10 次、一次没中、每次整本跑完
        for (int i = 0; i < 10; i++) lines.add(line("烂表", 5000, 5000, false));
        // 一本中等：跑 10 次、中 5 次、每次第 100 条中；另 5 次整本 3000 条跑完
        for (int i = 0; i < 5; i++) lines.add(line("中等", 3000, 100, true));
        for (int i = 0; i < 5; i++) lines.add(line("中等", 3000, 3000, false));

        Map<String, SourceRank.Stat> st = SourceRank.fromLines(lines);
        ok("三个来源都统计到", st.size() == 3, "size=" + st.size());

        SourceRank.Stat good = st.get("好表");
        SourceRank.Stat bad = st.get("烂表");
        SourceRank.Stat mid = st.get("中等");
        ok("好表 记录/命中", good.records == 10 && good.hits == 10,
                good.records + "/" + good.hits);
        ok("好表 命中位次中位 = 2", good.medianTested == 2, "" + good.medianTested);
        ok("好表 代价 = (20+200)/11 = 20", Math.abs(good.costPerHit() - 20.0) < 0.01,
                "" + good.costPerHit());

        ok("烂表 命中 0", bad.hits == 0, "" + bad.hits);
        ok("烂表 无命中位次(-1)", bad.medianTested == -1, "" + bad.medianTested);
        ok("烂表 代价 = (50000+200)/1 = 50200",
                Math.abs(bad.costPerHit() - 50200.0) < 0.01, "" + bad.costPerHit());

        ok("中等 代价 = (500+15000+200)/6",
                Math.abs(mid.costPerHit() - (500 + 15000 + 200) / 6.0) < 0.01,
                "" + mid.costPerHit());

        ok("好表 < 中等 < 烂表",
                good.costPerHit() < mid.costPerHit() && mid.costPerHit() < bad.costPerHit(),
                good.costPerHit() + " / " + mid.costPerHit() + " / " + bad.costPerHit());

        System.out.println("== 标签文案 ==");
        ok("好表标签含「平均 20 条中一次」", good.label().contains("20"), good.label());
        ok("烂表标签说明全没中", bad.label().contains("0") || bad.label().contains("没中"),
                bad.label());
        ok("无历史来源标签为空",
                new SourceRank.Stat("x", 0, 0, -1, 0, 0).label().isEmpty(), "");

        System.out.println("== 排序 ==");
        List<String> names = Arrays.asList("烂表", "没跑过的表", "中等", "好表", "另一张新表");
        Map<String, Integer> sizes = new HashMap<>();
        sizes.put("没跑过的表", 100);
        sizes.put("另一张新表", 9000);
        List<String> ordered = SourceRank.order(names, st, sizes);
        ok("好表排第一", ordered.get(0).equals("好表"), ordered.toString());
        // 没跑过的小表（100 条 → 先验 250）排在中等表（实测 2616）前面：
        // 「有历史 ≠ 值得先跑」，一条没中过的表更该靠后
        ok("没跑过的小表排在中等之前",
                ordered.indexOf("没跑过的表") < ordered.indexOf("中等"), ordered.toString());
        ok("烂表（一次没中，实测代价 5 万）排在没跑过的之后",
                ordered.indexOf("烂表") > ordered.indexOf("没跑过的表"), ordered.toString());
        ok("没跑过的两本：小表在前",
                ordered.indexOf("没跑过的表") < ordered.indexOf("另一张新表"), ordered.toString());
        ok("排序不丢条目", ordered.size() == names.size(), "" + ordered.size());
        ok("代价函数：有历史用实测值",
                Math.abs(SourceRank.costFor("好表", st, sizes) - 20.0) < 0.01, "");
        ok("代价函数：没历史用 规模/2+先验",
                Math.abs(SourceRank.costFor("没跑过的表", st, sizes) - 250.0) < 0.01, "");
        ok("代价函数：规模未知垫底",
                SourceRank.costFor("查无此表", st, sizes) == Double.MAX_VALUE, "");

        System.out.println("== 容错 ==");
        List<String> mixed = Arrays.asList(
                "bad-line",
                "",
                line("A", 100, 5, true),
                "1791690201830,B217,28:93:7D:45:DA:31,B,xx,3,1000,1,#t,v1:y",   // 坏数字
                "1791690201830,B217,28:93:7D:45:DA:31,,10,1,1000,1,#t,v1:y");   // 空名字
        Map<String, SourceRank.Stat> m2 = SourceRank.fromLines(mixed);
        ok("坏行被跳过不抛异常", m2.size() == 2, m2.keySet().toString());
        ok("坏规模按 0、已试条数照样算", m2.get("B") != null
                        && m2.get("B").maxSize == 0 && m2.get("B").sumTested == 3,
                m2.get("B") == null ? "null"
                        : m2.get("B").maxSize + "/" + m2.get("B").sumTested);
        ok("空名字不建条目", !m2.containsKey(""), m2.keySet().toString());
        ok("空输入返回空表", SourceRank.fromLines(null).isEmpty()
                && SourceRank.fromLines(new ArrayList<>()).isEmpty(), "");

        System.out.println("== 名称转义 ==");
        // esc() 把逗号写成「反斜杠 + c」；这里直接用转义后的字面量，
        // 断言解析回来的是「有逗号的原始名字」——否则同一本表会出现两个条目
        Map<String, SourceRank.Stat> esc = SourceRank.fromLines(Arrays.asList(
                "1,S,AA:BB:CC:DD:EE:FF,a\\cb,100,5,1000,1,#t,v1:y"));
        ok("带逗号的字典名按转义解析成一个条目", esc.size() == 1, esc.keySet().toString());
        ok("转义还原为原始名字", esc.containsKey("a,b"), esc.keySet().toString());

        System.out.println("== 内置清单自洽 ==");
        ok("核心档条目 >= 5", BuiltinDicts.itemsOfTier(BuiltinDicts.TIER_CORE).size() >= 5,
                "" + BuiltinDicts.itemsOfTier(BuiltinDicts.TIER_CORE).size());
        ok("核心档条数 = 各项之和", BuiltinDicts.coreEntries() > 0, "" + BuiltinDicts.coreEntries());
        ok("find() 认得中文名",
                BuiltinDicts.find("01 内置弱口令常用.txt") != null
                        && BuiltinDicts.find("01 内置弱口令常用.txt").tier == BuiltinDicts.TIER_CORE,
                "");
        ok("find() 认得英文名", BuiltinDicts.find("01 Built-in common weak.txt") != null, "");
        ok("find() 不认得无关名字", BuiltinDicts.find("用户自己的表.txt") == null, "");
        ok("扩展档只有字母数字混合", BuiltinDicts.itemsOfTier(BuiltinDicts.TIER_EXT).size() == 1, "");
        ok("穷举档是三条规则", BuiltinDicts.itemsOfTier(BuiltinDicts.TIER_BRUTE).size() == 3, "");
        ok("每项都有理由", BuiltinDicts.items().stream()
                .allMatch(i -> i.reasonZh != null && i.reasonZh.length() > 5), "");
        ok("已删的旧内置不在清单里",
                BuiltinDicts.find("04 内置英文单词.txt") == null
                        && BuiltinDicts.find("05 内置号码变形.txt") == null, "");
        ok("SecLists 两份已进清单",
                BuiltinDicts.find("10 WPA 起步 TOP 62.txt") != null
                        && BuiltinDicts.find("06 WPA 专用 TOP 4800.txt") != null, "");

        System.out.println();
        System.out.println("=== 来源排行测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

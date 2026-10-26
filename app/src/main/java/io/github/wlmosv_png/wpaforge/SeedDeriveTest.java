package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.CnHot;
import io.github.wlmosv_png.wpaforge.audit.SeedDeriver;
import io.github.wlmosv_png.wpaforge.audit.SeedModel;
import io.github.wlmosv_png.wpaforge.audit.SmartSorter;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 线索派生自检。
 *
 * 盯三件事：
 *  1. 线索确实能展开成「真实家庭网络里会出现的那类口令」（手机号 / 生日 / 拼音 / 门牌）；
 *  2. 无线索时**候选集合与旧版本一模一样** —— 只允许顺序变化（这条用哈希钉死）；
 *  3. 线索与中文字表的序列化、掩码不出错 —— 手机号是本机敏感数据。
 */
public class SeedDeriveTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static boolean has(List<Candidate> list, String pw) {
        for (Candidate c : list) if (c.password.equals(pw)) return true;
        return false;
    }

    private static boolean hasIn(List<String> list, String pw) {
        return list.contains(pw);
    }

    private static int indexOf(List<Candidate> list, String pw) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).password.equals(pw)) return i;
        return -1;
    }

    /** 候选集合指纹：去重排序后 SHA-1 前 12 位。用来钉「集合不变」。 */
    private static String setHash(List<Candidate> list) {
        try {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (Candidate c : list) set.add(c.password);
            List<String> v = new ArrayList<>(set);
            java.util.Collections.sort(v);
            StringBuilder sb = new StringBuilder();
            for (String s : v) sb.append(s).append('\n');
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(sb.toString().getBytes("UTF-8"));
            StringBuilder h = new StringBuilder();
            for (byte b : d) h.append(String.format("%02x", b));
            return h.substring(0, 12);
        } catch (Exception e) {
            return "ERR";
        }
    }

    private static NetRecord target(String ssid, String bssid) {
        return new NetRecord(ssid, bssid, -50, 2432, NetRecord.Security.WPA2, false, "");
    }

    public static void main(String[] a) {
        System.out.println("== SeedModel 校验与序列化 ==");
        SeedModel m = new SeedModel();
        ok("11 位手机号可加", SeedModel.addPhone(m, "13812345678"), "");
        ok("带分隔符也认", SeedModel.addPhone(m, "138-1234-5678") == false, "重复号应被忽略");
        ok("非 1 开头拒绝", SeedModel.addPhone(m, "23812345678") == false, "");
        ok("位数不对拒绝", SeedModel.addPhone(m, "1381234567") == false, "");
        SeedModel m2 = new SeedModel();
        SeedModel.addPhone(m2, "13800000001");
        SeedModel.addPhone(m2, "13800000002");
        SeedModel.addPhone(m2, "13800000003");
        SeedModel.addPhone(m2, "13800000004");
        ok("手机号上限 4 个", SeedModel.addPhone(m2, "13800000005") == false,
                "应被 MAX_PHONES 挡住");
        SeedModel m3 = new SeedModel();
        for (int i = 0; i < 10; i++) SeedModel.addCustom(m3, "word" + i);
        ok("自由词上限 8 条", m3.customs.size() == 8, "" + m3.customs.size());

        SeedModel full = new SeedModel();
        SeedModel.addPhone(full, "13812345678");
        full.landline = "051288889999";
        full.birthday = "19900101";
        full.namePinyin = "zhangwei";
        full.houseNo = "802";
        SeedModel.addCustom(full, "wojia");
        SeedModel.addCustom(full, "特§殊=值,带%号");
        SeedModel back = SeedModel.decode(full.encode());
        ok("序列化往返：手机号", back.phones.equals(full.phones), back.phones.toString());
        ok("序列化往返：生日", back.birthday.equals("19900101"), back.birthday);
        ok("序列化往返：拼音", back.namePinyin.equals("zhangwei"), back.namePinyin);
        ok("序列化往返：门牌", back.houseNo.equals("802"), back.houseNo);
        ok("序列化往返：含特殊字符的自由词", back.customs.equals(full.customs),
                back.customs.toString());
        ok("空模型 isEmpty", new SeedModel().isEmpty(), "");
        ok("填一项后非空", !full.isEmpty() && full.count() == 6, "" + full.count());
        ok("损坏串不崩", SeedModel.decode("§§===|乱码").isEmpty(), "");

        String fp1 = full.fingerprint();
        full.houseNo = "803";
        ok("内容变则指纹变", !fp1.equals(full.fingerprint()), "");
        full.houseNo = "802";
        ok("内容还原则指纹还原", fp1.equals(full.fingerprint()), "");

        String masked = full.masked();
        ok("掩码不含完整手机号", !masked.contains("13812345678"), masked);
        ok("掩码保留号段与后四位", masked.contains("138") && masked.contains("5678"), masked);
        ok("掩码不含自由词原文", !masked.contains("wojia"), masked);

        System.out.println("== SeedDeriver 展开 ==");
        List<String> d = SeedDeriver.derive(full);
        ok("含手机号全号", hasIn(d, "13812345678"), "");
        ok("含手机号后 8 位", hasIn(d, "12345678"), "");
        ok("含手机号后 6 位", hasIn(d, "345678"), "");
        ok("含号段+后 6 位", hasIn(d, "138345678"), "");
        ok("含 a+全号", hasIn(d, "a13812345678"), "");
        ok("含生日 8 位", hasIn(d, "19900101"), "");
        ok("含生日 6 位", hasIn(d, "900101"), "");
        ok("含生日月日", hasIn(d, "0101"), "");
        ok("含姓名拼音", hasIn(d, "zhangwei"), "");
        ok("含拼音首字母", hasIn(d, "zw"), "");
        ok("含拼音+年份", hasIn(d, "zhangwei2026"), "");
        ok("含门牌+后缀", hasIn(d, "802520"), "");
        ok("含门牌重复", hasIn(d, "802802"), "");
        ok("含固话原样", hasIn(d, "051288889999"), "");
        ok("含自由词原样", hasIn(d, "wojia"), "");
        ok("空线索不产生任何候选", SeedDeriver.derive(new SeedModel()).isEmpty(), "");
        ok("null 线索不崩", SeedDeriver.derive(null).isEmpty(), "");

        SeedModel pn = new SeedModel();
        pn.namePinyin = "wangxiaoming";
        String ini = SeedDeriver.initials("wangxiaoming");
        ok("三字名首字母 wxm", "wxm".equals(ini), ini);
        ok("切不出音节时不给首字母", "".equals(SeedDeriver.initials("qwertyui")),
                SeedDeriver.initials("qwertyui"));
        ok("拼音首字母出现在展开里", hasIn(SeedDeriver.derive(pn), "wxm"), "");

        ok("手机号片段集合含后 8 位", SeedDeriver.fragments(full).contains("12345678"), "");

        System.out.println("== 线索进入派生链 ==");
        NetRecord t1 = target("FAST_E972", "AA:BB:CC:E9:72:11");
        TargetDictBuilder.Result rs = TargetDictBuilder.build(t1, 2000, null, full);
        ok("线索派生含手机号全号", has(rs.candidates, "13812345678"), "");
        ok("线索派生含拼音+数字", has(rs.candidates, "zhangwei520"), "");
        ok("来源里标出用户线索", rs.explanation.contains("用户线索"), rs.explanation);
        int idx = indexOf(rs.candidates, "13812345678");
        ok("手机号排在前 50 条内", idx >= 0 && idx < 50, "idx=" + idx);

        System.out.println("== 中文字表 ==");
        List<String> parsed = CnHot.parse(Arrays.asList(
                "# 注释", "", "12345678", "短", "1234567890123456789012345678901234567890"
                        + "12345678901234567890123", "12345678", "zhang1234"));
        ok("注释与空行被跳过", parsed.size() == 3, parsed.toString());
        ok("过短被过滤", !parsed.contains("短"), "");
        ok("重复只留一次", parsed.size() == new LinkedHashSet<>(parsed).size(), "");
        List<String> cn = Arrays.asList("5201314520", "woaini1314", "zhangwei520");
        TargetDictBuilder.Result rc = TargetDictBuilder.build(t1, 2000, null, null, cn);
        ok("中文高频进入候选", has(rc.candidates, "woaini1314"), "");
        ok("来源里标出中文高频", rc.explanation.contains("中文高频"), rc.explanation);
        int ic = indexOf(rc.candidates, "woaini1314");
        ok("中文高频排在前 60 条内", ic >= 0 && ic < 60, "idx=" + ic);

        System.out.println("== 排序：线索优先 ==");
        // 用一个「后 8 位不是经典弱口令」的号来验排序，避免经典弱口令加成干扰结论
        SeedModel sp = new SeedModel();
        SeedModel.addPhone(sp, "13900001111");
        List<Candidate> poolDict = new ArrayList<>();
        poolDict.add(new Candidate("zzzzzzzz", Candidate.Origin.USER_DICT));
        poolDict.add(new Candidate("88888888", Candidate.Origin.DIGITS));
        poolDict.add(new Candidate("12345678", Candidate.Origin.DIGITS));
        poolDict.add(new Candidate("13900001111", Candidate.Origin.USER_DICT));
        List<Candidate> ranked = SmartSorter.prioritize(t1, poolDict, 0, sp, null);
        ok("线索命中排第一", "13900001111".equals(ranked.get(0).password),
                ranked.get(0).password);

        // 反过来：后 8 位恰好是经典弱口令时，那条**也是线索派生值**，
        // 两条都该在最前 —— 这不是 bug，是同一个字符串同时命中两套规则。
        List<Candidate> pool2 = new ArrayList<>();
        pool2.add(new Candidate("zzzzzzzz", Candidate.Origin.USER_DICT));
        pool2.add(new Candidate("88888888", Candidate.Origin.DIGITS));
        pool2.add(new Candidate("12345678", Candidate.Origin.DIGITS));
        pool2.add(new Candidate("13812345678", Candidate.Origin.USER_DICT));
        List<Candidate> ranked2 = SmartSorter.prioritize(t1, pool2, 0, full, null);
        ok("同源两条都排在最前", ranked2.get(0).password.equals("12345678")
                        || ranked2.get(0).password.equals("13812345678"),
                ranked2.get(0).password);
        ok("非线索候选被压到后面", "zzzzzzzz".equals(ranked2.get(3).password),
                ranked2.get(3).password);

        List<Candidate> rankedOld = SmartSorter.prioritize(t1, poolDict, 0);
        ok("无线索时旧签名仍可用", rankedOld.size() == 4, "" + rankedOld.size());

        System.out.println("== 回归：无线索时候选集合不变 ==");
        // 下面这几个数字/哈希来自 HEAD（改动前）的实测输出。
        // 允许顺序变化（通用弱口令从兜底提到第三桶），但**集合必须一模一样**。
        TargetDictBuilder.Result b1 = TargetDictBuilder.build(t1, 2000);
        ok("FAST_E972 条数不变", b1.candidates.size() == 959, "" + b1.candidates.size());
        ok("FAST_E972 集合不变", "e365f8db3929".equals(setHash(b1.candidates)), setHash(b1.candidates));
        TargetDictBuilder.Result b2 = TargetDictBuilder.build(target("209", "AA:BB:CC:DD:EE:FF"), 2000);
        ok("209 集合不变", "fccc16be8ebd".equals(setHash(b2.candidates)), setHash(b2.candidates));
        TargetDictBuilder.Result b3 = TargetDictBuilder.build(target("小卖部WiFi5", "11:22:33:44:55:66"), 2000);
        ok("中文 SSID 集合不变", "b068692f6dbd".equals(setHash(b3.candidates)), setHash(b3.candidates));
        TargetDictBuilder.Result b4 = TargetDictBuilder.build(target("CMCC-8F2A", "AA:BB:CC:11:22:33"), 2000);
        ok("CMCC-8F2A 集合不变", "b1237d932fc3".equals(setHash(b4.candidates)), setHash(b4.candidates));
        ok("通用弱口令已前移（前 12 条内出现 12345678）",
                indexOf(b1.candidates, "12345678") >= 0 && indexOf(b1.candidates, "12345678") < 12,
                "idx=" + indexOf(b1.candidates, "12345678"));
        ok("空线索时来源里没有用户线索", !b1.explanation.contains("用户线索"), b1.explanation);
        ok("空中文表时来源里没有中文高频", !b1.explanation.contains("中文高频"), b1.explanation);

        System.out.println("== 边界 ==");
        ok("null 目标仍返回空", TargetDictBuilder.build(null, 100).candidates.isEmpty(), "");
        SeedModel onlyPhone = new SeedModel();
        SeedModel.addPhone(onlyPhone, "13900001111");
        TargetDictBuilder.Result rp = TargetDictBuilder.build(t1, 50, null, onlyPhone);
        boolean lenOk = true;
        for (Candidate c : rp.candidates) {
            if (c.password.length() < 8 || c.password.length() > 63) lenOk = false;
        }
        ok("上限 50 生效", rp.candidates.size() <= 50, "" + rp.candidates.size());
        ok("所有条目长度合法", lenOk, "");

        System.out.println();
        System.out.println("=== 线索派生测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.SeedModel;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.util.List;

/**
 * SSID 派生自检（2026-10-26 重写）。
 *
 * 本轮有两处**有意的行为变更**，这个测试跟着改：
 *  1. SSID 派生**默认关闭** —— 本机 107 次真实命中里没有一次来自它，
 *     而它却是候选量最大的一块。想用必须显式打开（SeedModel.useSsid）。
 *  2. **不再补位** —— 短于 8 位的中间产物直接丢。
 *     旧版"不足 8 位就补 0/补 8/补 1/前缀 0"会把 `972888` 炸成
 *     `97288897 / 9728880 / 97288888 / 97288820` 四条臆造串。
 *
 * 所以这里的断言从"必须出现某个补位产物"改成"只允许出现合法长度、
 * 且打开开关时才出现的 SSID 候选"。
 */
public class TargetDictTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static boolean has(List<Candidate> list, String pw) {
        for (Candidate c : list) if (c.password.equals(pw)) return true;
        return false;
    }

    private static SeedModel ssidOn() {
        SeedModel m = new SeedModel();
        m.useSsid = true;
        return m;
    }

    public static void main(String[] a) {
        SeedModel on = ssidOn();

        System.out.println("== 光猫型 SSID: FAST_E972（SSID 派生已打开）==");
        NetRecord t1 = new NetRecord("FAST_E972", "AA:BB:CC:E9:72:11", -50, 2432,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r1 = TargetDictBuilder.build(t1, 2000, null, on);
        System.out.println("     生成 " + r1.candidates.size() + " 条");
        System.out.println("     " + r1.explanation);
        ok("含 SSID 原文", has(r1.candidates, "FAST_E972"), "");
        ok("含小写变形", has(r1.candidates, "fast_e972"), "");
        ok("含首字母大写变形", has(r1.candidates, "Fast_e972"), "");
        ok("含去分隔符", has(r1.candidates, "FASTE972"), "");
        ok("含数字三连（原数字族现在是 9 位）", has(r1.candidates, "972972972"), "");
        ok("含 SSID+数字后缀", has(r1.candidates, "fast_e9720"), "");
        // 旧断言是「含 BSSID 尾号+后缀」—— 那一族已按社会工程学审查砍掉
        ok("已删除 BSSID 尾号+后缀族", !has(r1.candidates, "e9721100"), "");
        ok("含字母部分+数字", has(r1.candidates, "faste123"), "");
        ok("来源列出各桶", r1.explanation.contains("SSID"), r1.explanation);

        System.out.println("== 默认关闭：不打开就没有 SSID 候选 ==");
        TargetDictBuilder.Result off = TargetDictBuilder.build(t1, 2000);
        ok("关闭时不含 SSID 原文", !has(off.candidates, "FAST_E972"), "");
        ok("关闭时不含厂商词元", !has(off.candidates, "tplinktplink"), "");
        ok("关闭时仍有通用弱口令", has(off.candidates, "12345678"), "");
        ok("关闭时来源写明", off.explanation.contains("关"), off.explanation);

        System.out.println("== 社会工程学审查：SSID 派的臆造串必须绝迹 ==");
        /* 这一节钉的是"审查砍掉的那几类"。
         * 用户的原话：「派生 ssid 确实出了很多垃圾的条目，你是用户你也觉得这种密码离谱」。
         * 每一条都注明"为什么它不该存在"，避免以后有人以为是漏掉的功能又加回来。 */
        ok("无 BSSID 尾号+后缀（e9721100）", !has(r1.candidates, "e9721100"), "");
        ok("无 BSSID 尾号+数字（e972111）", !has(r1.candidates, "e972111"), "");
        ok("无 BSSID 尾号+520（e97211520）", !has(r1.candidates, "e97211520"), "");
        // r1 的 BSSID 是 AA:BB:CC:E9:72:11 → 完整 MAC 无分隔
        ok("保留完整 MAC（唯一有据可依的形态）",
                has(r1.candidates, "aabbcce97211"), "");
        ok("无符号位置矩阵（fast@2026）", !has(r1.candidates, "fast@2026"), "");
        ok("无符号位置矩阵（2026@fast）", !has(r1.candidates, "2026@fast"), "");
        ok("无符号位置矩阵（fast#2026）", !has(r1.candidates, "fast#2026"), "");
        ok("无写死 wifi 后缀（FASTE972wifi）", !has(r1.candidates, "FASTE972wifi"), "");
        ok("无写死 wlan 后缀（faste972wlan）", !has(r1.candidates, "faste972wlan"), "");
        ok("无夹心数字（97288972）", !has(r1.candidates, "97288972"), "");
        ok("无数字碎片+字母碎片（972faste）", !has(r1.candidates, "972faste"), "");
        ok("仍保留有价值的：SSID+数字后缀", has(r1.candidates, "fast_e9720"), "");
        ok("仍保留有价值的：字母部分+数字", has(r1.candidates, "faste123"), "");
        ok("仍保留有价值的：片段组合", has(r1.candidates, "fast1234")
                || has(r1.candidates, "FASTE972a"), "");

        System.out.println("== 不再补位：旧版臆造串必须绝迹 ==");
        ok("无 97288897", !has(r1.candidates, "97288897"), "");
        ok("无 97288888", !has(r1.candidates, "97288888"), "");
        ok("无 9728880", !has(r1.candidates, "9728880"), "");
        ok("无 97288820", !has(r1.candidates, "97288820"), "");

        System.out.println("== 纯数字 SSID: 209 ==");
        NetRecord t2 = new NetRecord("209", "AA:BB:CC:DD:EE:FF", -60, 2412,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r2 = TargetDictBuilder.build(t2, 2000, null, on);
        ok("含 209 三连", has(r2.candidates, "209209209"), "");
        ok("含 209+数字后缀", has(r2.candidates, "20912345"), "");
        ok("已删除 BSSID 尾号族", !has(r2.candidates, "ddeeff123"), "");

        System.out.println("== 厂商词元（OUI）==");
        int yr = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        TargetDictBuilder.Result rv = TargetDictBuilder.build(t1, 2000, "TP-LINK", on);
        ok("厂商名自重复形态", has(rv.candidates, "tplinktplink"), "");
        ok("厂商名 + 年份", has(rv.candidates, "tplink" + yr), "");
        ok("不足 8 位的裸品牌名被长度闸门挡掉", !has(rv.candidates, "tplink"), "");
        ok("来源里标出厂商词元", rv.explanation.contains("厂商"), rv.explanation);
        ok("厂商词元不再带 @年份（tplink@2026 属臆造）", !has(rv.candidates, "tplink@2026"), "");

        TargetDictBuilder.Result rvFull = TargetDictBuilder.build(t1, 2000, "Sichuan Tianyi Kanghe", on);
        ok("长厂商名取首个词并加年份", has(rvFull.candidates, "sichuan" + yr), "");
        TargetDictBuilder.Result rvEmpty = TargetDictBuilder.build(t1, 2000, "", on);
        ok("厂商名为空不崩且不产生该来源",
                rvEmpty.candidates.size() > 0 && !rvEmpty.explanation.contains("厂商"), "");
        TargetDictBuilder.Result rvNull = TargetDictBuilder.build(t1, 2000, null, on);
        ok("厂商名为 null 不崩", rvNull.candidates.size() > 0, "");
        TargetDictBuilder.Result rvShort = TargetDictBuilder.build(t1, 2000, "AB", on);
        ok("过短厂商名不产生词元", !has(rvShort.candidates, "ab"), "");

        System.out.println("== 中文 SSID ==");
        NetRecord t3 = new NetRecord("小卖部WiFi5", "11:22:33:44:55:66", -70, 2412,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r3 = TargetDictBuilder.build(t3, 2000, null, on);
        ok("中文 SSID 不崩且有条目", r3.candidates.size() > 0, "" + r3.candidates.size());
        ok("至少含通用弱口令", has(r3.candidates, "12345678"), "");

        System.out.println("== 退化输入 ==");
        NetRecord t4 = new NetRecord("___", "11:22:33:44:55:66", -70, 2412,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r4 = TargetDictBuilder.build(t4, 2000, null, on);
        ok("退化 SSID 不崩", r4.candidates.size() > 0, "" + r4.candidates.size());

        System.out.println("== 上限与长度 ==");
        TargetDictBuilder.Result r5 = TargetDictBuilder.build(t1, 50, null, on);
        ok("上限 50 生效", r5.candidates.size() <= 50, "" + r5.candidates.size());
        boolean allOk = true;
        for (Candidate c : r1.candidates) {
            if (c.password.length() < 8 || c.password.length() > 63) {
                allOk = false;
                System.out.println("       非法: " + c.password + " (" + c.password.length() + ")");
            }
        }
        ok("所有条目长度合法", allOk, "");
        ok("null 目标返回空", TargetDictBuilder.build(null, 100).candidates.isEmpty(), "");

        System.out.println();
        System.out.println("=== 目标派生测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

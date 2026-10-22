package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.util.List;

/**
 * 目标派生字典自检。
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

    public static void main(String[] a) {
        System.out.println("== 光猫型 SSID: FAST_E972 ==");
        NetRecord t1 = new NetRecord("FAST_E972", "AA:BB:CC:E9:72:11", -50, 2432,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r1 = TargetDictBuilder.build(t1, 2000);
        System.out.println("     生成 " + r1.candidates.size() + " 条");
        System.out.println("     " + r1.explanation);
        ok("含 SSID 原文", has(r1.candidates, "FAST_E972"), "");
        ok("含小写变形", has(r1.candidates, "fast_e972"), "");
        ok("含去分隔符", has(r1.candidates, "FASTE972"), "");
        ok("含数字派生族", has(r1.candidates, "97288897")||has(r1.candidates,"9728880")||has(r1.candidates,"97288888")||has(r1.candidates,"97288820"), "查972888族");
        ok("含数字重复族", has(r1.candidates, "97297297")||has(r1.candidates,"97297220")||has(r1.candidates,"97297200"), "查972972族");
        ok("含 SSID+数字族", has(r1.candidates,"e972123e")||has(r1.candidates,"E972123E")||has(r1.candidates,"e9721230"), "查e972123族");
        ok("含 BSSID 尾号族", has(r1.candidates,"e97211e9")||has(r1.candidates,"e9721100")||has(r1.candidates,"E9721100"), "查e97211族");
        ok("含智能词元扩散(fast@2026/fast123)", has(r1.candidates, "fast@2026") || has(r1.candidates, "Fast@2026") || has(r1.candidates, "fast@2025") || has(r1.candidates, "fast123456"), "查智能词元扩散");
        ok("条数在合理范围 (<2000)", r1.candidates.size() < 2000, "" + r1.candidates.size());

        System.out.println("     前 12 条:");
        for (int i = 0; i < Math.min(12, r1.candidates.size()); i++) {
            System.out.println("       " + r1.candidates.get(i).password);
        }

        System.out.println("== 纯数字 SSID: 209 ==");
        NetRecord t2 = new NetRecord("209", "AA:BB:CC:DD:EE:FF", -60, 2412,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r2 = TargetDictBuilder.build(t2, 2000);
        ok("含 209209 族", has(r2.candidates,"20920920")||has(r2.candidates,"20920900")||has(r2.candidates,"209209209"), "查209209族");
        ok("含 209123 族", has(r2.candidates,"2091230")||has(r2.candidates,"20912320")||has(r2.candidates,"209123123"), "查209123族");
        ok("含 209888 族", has(r2.candidates,"2098880")||has(r2.candidates,"20988888")||has(r2.candidates,"20988820"), "查209888族");

        System.out.println("== 厂商词元（OUI）==");
        int yr = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        TargetDictBuilder.Result rv = TargetDictBuilder.build(t1, 2000, "TP-LINK");
        // 厂商名本身只有 6 位，按 WPA2 最短 8 位规则会被过滤掉 ——
        // 所以厂商桶必须出「加年份 / 加后缀 / 自重复」这类可用形态，而不是裸品牌名。
        ok("厂商名自重复形态", has(rv.candidates, "tplinktplink"), "");
        ok("厂商名 + 年份", has(rv.candidates, "tplink" + yr), "");
        ok("厂商名首字母大写 + 年份", has(rv.candidates, "Tplink" + yr), "");
        ok("厂商名 + @年份", has(rv.candidates, "tplink@" + yr), "");
        ok("不足 8 位的裸品牌名被长度规则挡掉（符合 WPA2 规则）",
                !has(rv.candidates, "tplink"), "");
        ok("来源里标出厂商词元", rv.explanation.contains("厂商"), rv.explanation);

        TargetDictBuilder.Result rvFull = TargetDictBuilder.build(t1, 2000, "Sichuan Tianyi Kanghe");
        ok("长厂商名取首个词并加年份", has(rvFull.candidates, "sichuan" + yr), "");
        TargetDictBuilder.Result rvEmpty = TargetDictBuilder.build(t1, 2000, "");
        ok("厂商名为空不崩且不产生该来源",
                rvEmpty.candidates.size() > 0 && !rvEmpty.explanation.contains("厂商"), "");
        TargetDictBuilder.Result rvNull = TargetDictBuilder.build(t1, 2000, null);
        ok("厂商名为 null 不崩", rvNull.candidates.size() > 0, "");
        TargetDictBuilder.Result rvShort = TargetDictBuilder.build(t1, 2000, "AB");
        ok("过短厂商名不产生词元", !has(rvShort.candidates, "ab"), "");

        System.out.println("== 中文 SSID ==");
        NetRecord t3 = new NetRecord("小卖部WiFi5", "11:22:33:44:55:66", -70, 2412,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r3 = TargetDictBuilder.build(t3, 2000);
        ok("中文 SSID 不崩且有条目", r3.candidates.size() > 0, "" + r3.candidates.size());
        ok("至少含通用弱口令", has(r3.candidates, "12345678"), "");

        System.out.println("== 无数字无字母 ==");
        NetRecord t4 = new NetRecord("___", "11:22:33:44:55:66", -70, 2412,
                NetRecord.Security.WPA2, false, "");
        TargetDictBuilder.Result r4 = TargetDictBuilder.build(t4, 2000);
        ok("退化情况不崩", r4.candidates.size() > 0, "" + r4.candidates.size());

        System.out.println("== 上限 ==");
        TargetDictBuilder.Result r5 = TargetDictBuilder.build(t1, 50);
        ok("上限 50 生效", r5.candidates.size() <= 50, "" + r5.candidates.size());

        System.out.println("== 长度合法性 ==");
        boolean allOk = true;
        for (Candidate c : r1.candidates) {
            if (c.password.length() < 8 || c.password.length() > 63) {
                allOk = false;
                System.out.println("       非法: " + c.password + " (" + c.password.length() + ")");
            }
        }
        ok("所有条目长度合法", allOk, "");

        System.out.println("== null 目标 ==");
        ok("null 不崩", TargetDictBuilder.build(null, 100).candidates.isEmpty(), "");

        System.out.println();
        System.out.println("=== 目标派生测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

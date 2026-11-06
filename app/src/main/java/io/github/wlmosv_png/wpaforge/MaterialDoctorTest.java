package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Hc22000;
import io.github.wlmosv_png.wpaforge.audit.MaterialDoctor;

/**
 * 材料完整度诊断。核心命题：**不能只看字段非空**。
 * 这些用例都是「字段在、但值没意义」的真实坏形态。
 */
public class MaterialDoctorTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    private static final String OFF_PMKID =
            "WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4"
                    + "*686173686361742d6573736964***";

    private static final String FRAME =
            "0103007502010a0000000000000000000148ce2ccba9c1fda130ff2fbbfb4fd3b063d1a93920b0f7df54a5cb"
                    + "f787b16171000000000000000000000000000000000000000000000000000000000000000000000000000000"
                    + "000000000000000000001630140100000fac040100000fac040100000fac028000";

    private static final String OFF_EAPOL_HEAD =
            "WPA*02*024022795224bffca545276c3762686f*6466b38ec3fc*225edc49b7aa"
                    + "*54502d4c494e4b5f484153484341545f54455354"
                    + "*10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e*";

    private static String eapol(String frame, String mp) {
        return OFF_EAPOL_HEAD + frame + "*" + mp;
    }

    private static MaterialDoctor.Report rep(String line) {
        return MaterialDoctor.examine(Hc22000.parseLine(line));
    }

    private static boolean hasBlocker(MaterialDoctor.Report r, String kw) {
        for (String s : r.blockers) {
            if (s.contains(kw)) return true;
        }
        return false;
    }

    private static boolean hasFact(MaterialDoctor.Report r, String name) {
        for (String[] f : r.facts) {
            if (f[0].contains(name)) return true;
        }
        return false;
    }

    private static String factOf(MaterialDoctor.Report r, String name) {
        for (String[] f : r.facts) {
            if (f[0].contains(name)) return f[1];
        }
        return null;
    }

    public static void main(String[] args) {
        System.out.println("== 前提检查：官方帧的 MIC 字段确实是全零 ==");
        eq("offset 81..96 = 16 字节全零", FRAME.substring(162, 194),
                "00000000000000000000000000000000");
        eq("帧总长 = 121 字节", FRAME.length() / 2, 121);

        System.out.println("== 正常材料：必须判可执行 ==");
        MaterialDoctor.Report p = rep(OFF_PMKID);
        ok("官方 PMKID 可执行", p.executable);
        ok("无阻断项", p.blockers.isEmpty());
        eq("类型识别", factOf(p, "类型"), "PMKID");
        ok("给出了 PMKID 值", factOf(p, "PMKID") != null);

        MaterialDoctor.Report e = rep(eapol(FRAME, "a2"));
        ok("官方 EAPOL 可执行", e.executable);
        ok("无阻断项", e.blockers.isEmpty());
        eq("类型识别", factOf(e, "类型"), "EAPOL");
        eq("EAPOL 帧长度", factOf(e, "EAPOL 帧长度"), "121 字节");
        eq("Key Version", factOf(e, "Key Version"), "2");
        eq("Message Pair", factOf(e, "Message Pair"), "a2");
        ok("给出 SNonce（帧内）", factOf(e, "SNonce") != null);
        ok("SNonce 不是空值", !"(空)".equals(factOf(e, "SNonce")));
        ok("给出了 ANonce", factOf(e, "ANonce") != null);
        ok("给出了 MIC", factOf(e, "MIC") != null);

        System.out.println("== 坏 1：MIC 字段没被抹零 ==");
        String dirty = FRAME.substring(0, 162) + "00112233445566778899aabbccddeeff"
                + FRAME.substring(194);
        MaterialDoctor.Report rd = rep(eapol(dirty, "a2"));
        ok("判为不可执行", !rd.executable);
        ok("原因点明 MIC 未置零", hasBlocker(rd, "MIC 字段未置零"));
        ok("摘要说明未执行", rd.summary().contains("未执行"));

        System.out.println("== 坏 2：帧内 key nonce 全零 ==");
        String zeroNonce = FRAME.substring(0, 34) + "0".repeat(64) + FRAME.substring(98);
        MaterialDoctor.Report rn = rep(eapol(zeroNonce, "a2"));
        ok("判为不可执行", !rn.executable);
        ok("原因点明 nonce 全零", hasBlocker(rn, "key nonce 全零"));

        System.out.println("== 坏 3：帧过短 ==");
        // 97 字节：刚好过解析器的下限（MIC 偏移 81 + 16），
        // 但低于"可执行"所需的 99（还要再加 key data length 两个字节）
        String tiny = FRAME.substring(0, 97 * 2);
        MaterialDoctor.Report rt = rep(eapol(tiny, "a2"));
        ok("判为不可执行", !rt.executable);
        ok("材料本身还能解析出来（否则测的不是诊断）",
                Hc22000.parseLine(eapol(tiny, "a2")) != null);
        ok("原因点明过短", hasBlocker(rt, "EAPOL 帧过短"));

        System.out.println("== 坏 4：Key Version 非法 ==");
        // key info 在 offset 5..6，低 3 位是 version；把 0x010a 改成 0x0107（version=7）
        String badVer = FRAME.substring(0, 10) + "0107" + FRAME.substring(14);
        MaterialDoctor.Report rv = rep(eapol(badVer, "a2"));
        ok("judge 不可执行", !rv.executable);
        ok("原因点明 Key Version", hasBlocker(rv, "Key Version"));

        System.out.println("== 坏 5：PMKID 全零 ==");
        String zeroPmkid = OFF_PMKID.replace("4d4fe7aac3a2cecab195321ceb99a7d0",
                "00000000000000000000000000000000");
        MaterialDoctor.Report rz = rep(zeroPmkid);
        ok("判为不可执行", !rz.executable);
        ok("原因点明全零", hasBlocker(rz, "全零"));

        System.out.println("== 坏 6：SSID 超过 32 字节 ==");
        StringBuilder longSsid = new StringBuilder();
        for (int i = 0; i < 40; i++) longSsid.append("41");   // 'A' x40
        String bigSsid = OFF_PMKID.replace("686173686361742d6573736964", longSsid.toString());
        MaterialDoctor.Report rb = rep(bigSsid);
        ok("判为不可执行", !rb.executable);
        ok("原因点明 SSID 超长", hasBlocker(rb, "SSID 超过"));

        System.out.println("== 坏 7：SSID 为空 ==");
        String noSsid = OFF_PMKID.replace("686173686361742d6573736964", "");
        MaterialDoctor.Report re = rep(noSsid);
        ok("判为不可执行", !re.executable);
        ok("原因点明缺 SSID", hasBlocker(re, "SSID"));

        System.out.println("== 坏 8：ANonce 全零 ==");
        String zeroAn = OFF_EAPOL_HEAD.replace(
                "10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e",
                "0000000000000000000000000000000000000000000000000000000000000000");
        MaterialDoctor.Report ra = rep(zeroAn + FRAME + "*a2");
        ok("判为不可执行", !ra.executable);
        ok("原因点明 ANonce", hasBlocker(ra, "ANonce"));

        System.out.println("== null 材料 ==");
        MaterialDoctor.Report r0 = MaterialDoctor.examine(null);
        ok("判为不可执行", !r0.executable);
        ok("原因点明为空", hasBlocker(r0, "为空"));

        System.out.println("== 诊断结论不能是「密码错」口径 ==");
        for (MaterialDoctor.Report r : new MaterialDoctor.Report[]{rd, rn, rt, rv, rz, rb, re, ra, r0}) {
            ok("问题描述里不含'未命中'字样", r.summary().indexOf("未命中") < 0);
        }

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}

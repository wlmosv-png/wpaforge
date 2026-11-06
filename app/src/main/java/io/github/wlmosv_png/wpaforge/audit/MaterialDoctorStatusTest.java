package io.github.wlmosv_png.wpaforge.audit;

/**
 * 材料诊断的**四分状态**：可执行 / 信息不完整 / 格式错误 / 不支持。
 *
 * <h3>为什么要分开</h3>
 * 「材料不完整」一句话对用户没用 —— 他要知道到底是**自己抓少了**（信息不完整，
 * 重新抓一次就行）还是**文件本身是坏的**（格式错误，得重新导出）。
 * 这两种的下一步完全不同，所以必须有确定的分类，而不是只看字段非空。
 *
 * <p>本类放在 {@code audit} 包内，是为了能直接构造带异常类型的 {@link Hc22000.Entry}
 * 来覆盖「不支持」这一档 —— 正常解析路径永远产不出这种条目。
 */
public class MaterialDoctorStatusTest {

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

    // 官方 hashcat 示例（wiki 22000 行原样）
    private static final String OFF_PMKID =
            "WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4*686173686361742d657373"
            + "6964***";
    private static final String OFF_EAPOL_HEAD =
            "WPA*02*024022795224bffca545276c3762686f*6466b38ec3fc*225edc49b7aa*54502d4c494e4b5f484153"
            + "484341545f54455354*10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e*";
    private static final String OFF_FRAME =
            "0103007502010a0000000000000000000148ce2ccba9c1fda130ff2fbbfb4fd3b063d1a93920b0f7df54a5cb"
            + "f787b16171000000000000000000000000000000000000000000000000000000000000000000000000000000"
            + "000000000000000000001630140100000fac040100000fac040100000fac028000";
    private static final String OFF_EAPOL = OFF_EAPOL_HEAD + OFF_FRAME + "*a2";

    private static MaterialDoctor.Report rep(String line) {
        return MaterialDoctor.examine(Hc22000.parseLine(line));
    }

    public static void main(String[] args) {
        System.out.println("== 可执行 ==");
        MaterialDoctor.Report good = rep(OFF_PMKID);
        eq("PMKID -> RUNNABLE", good.status(), MaterialDoctor.Status.RUNNABLE);
        ok("executable 与状态一致", good.executable);
        ok("可执行时 whyNot 为空", good.whyNot().isEmpty());
        ok("标签里有「可执行」", good.statusLabel().indexOf(MaterialDoctor.Status.RUNNABLE.label()) >= 0);

        MaterialDoctor.Report ge = rep(OFF_EAPOL);
        eq("EAPOL -> RUNNABLE", ge.status(), MaterialDoctor.Status.RUNNABLE);

        System.out.println("== 信息不完整（格式对，但关键值缺失）==");
        MaterialDoctor.Report zeroPmkid = rep(OFF_PMKID.replace("4d4fe7aac3a2cecab195321ceb99a7d0",
                "00000000000000000000000000000000"));
        eq("PMKID 全零 -> INCOMPLETE", zeroPmkid.status(), MaterialDoctor.Status.INCOMPLETE);
        ok("说明里点出「全零」", zeroPmkid.whyNot().contains("全零"));

        MaterialDoctor.Report noSsid = rep(OFF_PMKID.replace("686173686361742d6573736964", ""));
        eq("缺 SSID -> INCOMPLETE", noSsid.status(), MaterialDoctor.Status.INCOMPLETE);

        StringBuilder longSsid = new StringBuilder();
        for (int i = 0; i < 40; i++) longSsid.append("41");
        MaterialDoctor.Report bigSsid = rep(OFF_PMKID.replace("686173686361742d6573736964", longSsid.toString()));
        eq("SSID 超长 -> INCOMPLETE", bigSsid.status(), MaterialDoctor.Status.INCOMPLETE);

        String zeroAn = OFF_EAPOL_HEAD.replace(
                "10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e",
                "0000000000000000000000000000000000000000000000000000000000000000");
        MaterialDoctor.Report zAn = rep(zeroAn + OFF_FRAME + "*a2");
        eq("ANonce 全零 -> INCOMPLETE", zAn.status(), MaterialDoctor.Status.INCOMPLETE);

        System.out.println("== 格式错误（长度/取值根本不合格式）==");
        // MIC 字段没被抹零
        String dirty = OFF_FRAME.substring(0, Hc22000.EAPOL_MIC_OFFSET * 2)
                + "11223344556677889900112233445566"
                + OFF_FRAME.substring((Hc22000.EAPOL_MIC_OFFSET + 16) * 2);
        MaterialDoctor.Report rd = rep(OFF_EAPOL_HEAD + dirty + "*a2");
        eq("MIC 未置零 -> MALFORMED", rd.status(), MaterialDoctor.Status.MALFORMED);
        ok("说明里点出 MIC 未置零", rd.whyNot().contains("MIC"));

        // 帧内 nonce 全零
        String zeroNonce = OFF_FRAME.substring(0, Hc22000.EAPOL_SNONCE_OFFSET * 2)
                + "0000000000000000000000000000000000000000000000000000000000000000"
                + OFF_FRAME.substring((Hc22000.EAPOL_SNONCE_OFFSET + 32) * 2);
        MaterialDoctor.Report rn = rep(OFF_EAPOL_HEAD + zeroNonce + "*a2");
        eq("帧内 nonce 全零 -> MALFORMED", rn.status(), MaterialDoctor.Status.MALFORMED);

        // 帧过短：parseLine 会先把这种行挡掉，所以直接构造条目来覆盖诊断层
        Hc22000.Entry base = Hc22000.parseLine(OFF_EAPOL);
        MaterialDoctor.Report rt = MaterialDoctor.examine(new Hc22000.Entry(
                Hc22000.TYPE_EAPOL, base.micOrPmkid, base.apMac, base.staMac, base.ssid,
                base.aNonce, new byte[]{0x01, 0x03, 0x00, 0x75}, base.messagePair));
        eq("帧过短 -> MALFORMED", rt.status(), MaterialDoctor.Status.MALFORMED);

        // 校验值长度不对（8 字节而不是 16 字节）
        String shortCheck = OFF_PMKID.replace("4d4fe7aac3a2cecab195321ceb99a7d0", "4d4fe7aa");
        MaterialDoctor.Report rsc = rep(shortCheck);
        eq("校验值长度不对 -> MALFORMED", rsc.status(), MaterialDoctor.Status.MALFORMED);

        // Key Version 不是 1/2/3（key info 低 3 位在 byte6 的低 nibble）
        String badVer = OFF_FRAME.substring(0, 13) + "0" + OFF_FRAME.substring(14);
        MaterialDoctor.Report rv = rep(OFF_EAPOL_HEAD + badVer + "*a2");
        eq("Key Version 非法 -> MALFORMED", rv.status(), MaterialDoctor.Status.MALFORMED);

        System.out.println("== 不支持（记录类型不在 WPA*01 / WPA*02 之内）==");
        Hc22000.Entry weird = new Hc22000.Entry(9,
                Hc22000.parseLine(OFF_PMKID).micOrPmkid,
                Hc22000.parseLine(OFF_PMKID).apMac,
                Hc22000.parseLine(OFF_PMKID).staMac,
                "X", null, null, "");
        MaterialDoctor.Report rw = MaterialDoctor.examine(weird);
        eq("类型 9 -> UNSUPPORTED", rw.status(), MaterialDoctor.Status.UNSUPPORTED);
        ok("不支持时 whyNot 说明类型", rw.whyNot().contains("不支持"));

        System.out.println("== 空材料 ==");
        MaterialDoctor.Report r0 = MaterialDoctor.examine(null);
        eq("null -> INCOMPLETE（不是可执行）", r0.status(), MaterialDoctor.Status.INCOMPLETE);
        ok("null 不可执行", !r0.executable);

        System.out.println("== 四档互斥且每次只归一类 ==");
        MaterialDoctor.Status[] all = {good.status(), zeroPmkid.status(), rd.status(), rw.status()};
        eq("四档", all.length, 4);
        ok("四档各不相同",
                all[0] != all[1] && all[1] != all[2] && all[2] != all[3] && all[0] != all[3]);

        System.out.println("== 分类优先级：不支持 > 格式错误 > 信息不完整 ==");
        Hc22000.Entry bothBad = new Hc22000.Entry(9, new byte[16], new byte[6], new byte[6],
                "", null, null, "");
        eq("既类型不支持又值全零 -> UNSUPPORTED",
                MaterialDoctor.examine(bothBad).status(), MaterialDoctor.Status.UNSUPPORTED);

        System.out.println("== 结论里绝不出现「未命中」口径 ==");
        for (MaterialDoctor.Report r : new MaterialDoctor.Report[]{good, zeroPmkid, noSsid, bigSsid, zAn, rd, rn, rt, rsc, rv,
                rw, r0}) {
            ok("summary 不含「未命中」", r.summary().indexOf("未命中") < 0);
            ok("whyNot 不含「未命中」", r.whyNot().indexOf("未命中") < 0);
        }

        System.out.println("== statusLabel 永远非空（界面直接用）==");
        for (MaterialDoctor.Report r : new MaterialDoctor.Report[]{good, zeroPmkid, rd, rw, r0}) {
            ok("statusLabel 非空", !r.statusLabel().isEmpty());
        }

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}

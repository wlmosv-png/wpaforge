package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.OfflineVerdict;

/**
 * 结果卡的数据模型：编解码往返、自洽性、以及「日志里不许出现明文密码」。
 */
public class OfflineVerdictTest {

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

    private static OfflineVerdict hit() {
        return new OfflineVerdict(OfflineVerdict.State.HIT, "a.hc22000", "EAPOL",
                "TP-LINK_HASHCAT_TEST", "64:66:B3:8E:C3:FC", "07 通用口令榜", 3336, 2, 15,
                1793900000000L, "hashcat!", "");
    }

    private static OfflineVerdict miss() {
        return new OfflineVerdict(OfflineVerdict.State.MISS, "a.hc22000", "PMKID",
                "hashcat-essid", "FC:69:0C:15:82:64", "内置弱口令", 70, 70, 1346,
                1793900000000L, "", "");
    }

    private static OfflineVerdict cancelled() {
        return new OfflineVerdict(OfflineVerdict.State.CANCELLED, "a.hc22000", "EAPOL",
                "S", "AA:BB:CC:DD:EE:FF", "d", 6000, 300, 900, 1793900000000L, "", "");
    }

    public static void main(String[] args) {
        System.out.println("== 自洽性 ==");
        ok("NONE 自洽", OfflineVerdict.none().consistent());
        ok("HIT 自洽", hit().consistent());
        ok("MISS 自洽", miss().consistent());
        ok("CANCELLED 自洽", cancelled().consistent());
        ok("HIT 有结果", hit().hasResult());
        ok("NONE 无结果", !OfflineVerdict.none().hasResult());

        System.out.println("== 状态与数字必须一致 ==");
        ok("MISS 但没跑满 -> 不自洽",
                !new OfflineVerdict(OfflineVerdict.State.MISS, "f", "PMKID", "s", "a", "d",
                        100, 50, 10, 1, "", "").consistent());
        ok("HIT 但没密码 -> 不自洽",
                !new OfflineVerdict(OfflineVerdict.State.HIT, "f", "PMKID", "s", "a", "d",
                        100, 1, 10, 1, "", "").consistent());
        ok("CANCELLED 带密码 -> 不自洽",
                !new OfflineVerdict(OfflineVerdict.State.CANCELLED, "f", "PMKID", "s", "a", "d",
                        100, 50, 10, 1, "pw", "").consistent());
        ok("CANCELLED 跑超总数 -> 不自洽",
                !new OfflineVerdict(OfflineVerdict.State.CANCELLED, "f", "PMKID", "s", "a", "d",
                        100, 200, 10, 1, "", "").consistent());
        ok("NONE 带时间 -> 不自洽",
                !new OfflineVerdict(OfflineVerdict.State.NONE, "", "", "", "", "", 0, 0, 0,
                        12345L, "", "").consistent());

        System.out.println("== 只有跑满才算「字典已被穷尽」==");
        ok("MISS 跑满 -> exhausted", miss().dictionaryExhausted());
        ok("CANCELLED 不算 exhausted", !cancelled().dictionaryExhausted());
        ok("HIT 不算 exhausted", !hit().dictionaryExhausted());

        System.out.println("== 编解码往返 ==");
        for (OfflineVerdict v : new OfflineVerdict[]{hit(), miss(), cancelled(), OfflineVerdict.none()}) {
            OfflineVerdict back = OfflineVerdict.decode(v.encode());
            ok(v.state + " 往返状态一致", back.state == v.state);
            eq(v.state + " 往返 tested", back.tested, v.tested);
            eq(v.state + " 往返 dictSize", back.dictSize, v.dictSize);
            eq(v.state + " 往返 elapsed", back.elapsedMs, v.elapsedMs);
            eq(v.state + " 往返 finishedAt", back.finishedAt, v.finishedAt);
            eq(v.state + " 往返 hitPassword", back.hitPassword, v.hitPassword);
            eq(v.state + " 往返 ssid", back.ssid, v.ssid);
            eq(v.state + " 往返 dictName", back.dictName, v.dictName);
        }

        System.out.println("== 特殊字符必须被转义 ==");
        OfflineVerdict weird = new OfflineVerdict(OfflineVerdict.State.FAILED, "a|b.hc22000",
                "EAPOL", "ss|id", "AP", "dict|name", 10, 0, 1, 5, "",
                "原因含竖线|和反斜杠\\和换行\n以及回车\r");
        OfflineVerdict w2 = OfflineVerdict.decode(weird.encode());
        eq("文件名里的竖线", w2.fileName, "a|b.hc22000");
        eq("SSID 里的竖线", w2.ssid, "ss|id");
        eq("字典名里的竖线", w2.dictName, "dict|name");
        ok("原因里的竖线保留", w2.note.indexOf('|') >= 0);
        ok("原因里的反斜杠保留", w2.note.indexOf('\\') >= 0);
        ok("原因里的换行保留", w2.note.indexOf('\n') >= 0);
        ok("转义后仍自洽", w2.consistent());

        System.out.println("== 坏输入不抛异常 ==");
        eq("空串 -> NONE", OfflineVerdict.decode("").state, OfflineVerdict.State.NONE);
        eq("null -> NONE", OfflineVerdict.decode(null).state, OfflineVerdict.State.NONE);
        eq("垃圾 -> NONE", OfflineVerdict.decode("!!!@@@###").state, OfflineVerdict.State.NONE);
        eq("字段不足 -> NONE", OfflineVerdict.decode("v1|HIT|a").state, OfflineVerdict.State.NONE);
        eq("未知状态 -> NONE", OfflineVerdict.decode("v1|WAT|a|b|c|d|e|0|0|0|0|f|g").state,
                OfflineVerdict.State.NONE);
        eq("内部不自洽 -> NONE",
                OfflineVerdict.decode("v1|MISS|a|PMKID|s|x|d|100|50|1|1||").state,
                OfflineVerdict.State.NONE);

        System.out.println("== 日志行绝不能泄露密码 ==");
        String line = hit().logLine();
        ok("命中日志不含密码明文", line.indexOf("hashcat!") < 0);
        ok("命中日志给了位次", line.indexOf("2") >= 0);
        ok("取消日志说清是取消", cancelled().logLine().indexOf("取消") >= 0);
        ok("取消日志不叫未命中", cancelled().logLine().indexOf("未命中") < 0);
        ok("未命中日志才说未命中", miss().logLine().indexOf("未命中") >= 0);
        ok("材料异常日志点明未执行", new OfflineVerdict(OfflineVerdict.State.BAD_MATERIAL,
                "f", "EAPOL", "s", "a", "d", 0, 0, 0, 1, "", "MIC 未置零")
                .logLine().indexOf("未执行") >= 0);
        ok("NONE 不出日志行", OfflineVerdict.none().logLine().isEmpty());

        System.out.println("== 材料摘要 ==");
        eq("EAPOL 摘要", hit().materialSummary(), "EAPOL · TP-LINK_HASHCAT_TEST · AP 64:66:B3:8E:C3:FC");
        eq("PMKID 摘要", miss().materialSummary(), "PMKID · hashcat-essid · AP FC:69:0C:15:82:64");
        eq("空材料不炸", OfflineVerdict.none().materialSummary(), "");

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}

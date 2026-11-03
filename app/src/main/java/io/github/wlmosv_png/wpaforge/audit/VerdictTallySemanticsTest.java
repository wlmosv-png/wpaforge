package io.github.wlmosv_png.wpaforge.audit;

/**
 * 统计口径反例：**INFERRED（推断失败）不得计入「已证实失败」**。
 *
 * <h3>缺陷</h3>
 * {@code VerdictTally.add(Verdict)} 只看 verdict：
 * <pre>
 *   if (v == AUTH_FAIL) confirmedFail++;   // 不区分 DIRECT / INFERRED
 * </pre>
 * 但 AUTH_FAIL 有两种证据等级（D5 已区分）：
 * <ul>
 *   <li>DIRECT —— AP 带归属的直接否认，确证失败；</li>
 *   <li>INFERRED —— M1 重发 / 回连等**推断**，证据不足。</li>
 * </ul>
 * 结果：UI 上的「已证实失败 N 条」把推断失败也算了进去。
 * 真实现场：14:55 那次显示「已证实失败 284 条」，而判词里大量是
 * {@code ✗m1retry}（推断），并非确证。
 */
public final class VerdictTallySemanticsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) {
        System.out.println("== 统计口径：已证实失败 vs 推断失败 ==");

        // ---------- 1. 4 条 AUTH_FAIL 但都是 INFERRED
        VerdictTally t = new VerdictTally();
        for (int i = 0; i < 4; i++) {
            t.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED);
        }
        info("4 条 INFERRED：confirmedFail=" + t.confirmedFail()
                + " inferred=" + t.inferred() + " inconclusive=" + t.inconclusive());
        ok("**4 条推断失败不得计入「已证实失败」**",
                t.confirmedFail() == 0, "confirmedFail=" + t.confirmedFail());
        ok("4 条推断失败应单独统计", t.inferred() == 4, "inferred=" + t.inferred());

        // ---------- 2. 只有 DIRECT 才算确证失败
        VerdictTally t2 = new VerdictTally();
        t2.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);
        t2.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);
        t2.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED);
        t2.add(CrackEngine.Verdict.TIMEOUT, EvidenceGrade.Level.NONE);
        info("混合：confirmedFail=" + t2.confirmedFail()
                + " inferred=" + t2.inferred() + " inconclusive=" + t2.inconclusive());
        ok("确证失败只计 DIRECT", t2.confirmedFail() == 2, "confirmedFail=" + t2.confirmedFail());
        ok("推断失败单列", t2.inferred() == 1, "inferred=" + t2.inferred());
        ok("无结论单列", t2.inconclusive() == 1, "inconclusive=" + t2.inconclusive());

        // ---------- 3. 「未命中」是否站得住：推断失败也不能算确证
        ok("**有推断失败时 missProven 也不得为真**（未命中未被确证）",
                !t2.missProven(), "missProven=" + t2.missProven());

        // ---------- 4. 兼容重载：只传 verdict 时按保守默认（AUTH_FAIL -> INFERRED）
        VerdictTally t3 = new VerdictTally();
        t3.add(CrackEngine.Verdict.AUTH_FAIL);
        ok("只传 verdict 时按保守默认（不计入已证实失败）",
                t3.confirmedFail() == 0 && t3.inferred() == 1,
                "confirmedFail=" + t3.confirmedFail() + " inferred=" + t3.inferred());

        // ---------- 5. SUCCESS / ABORTED 不计入任何一类（行为不变）
        VerdictTally t4 = new VerdictTally();
        t4.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);
        t4.add(CrackEngine.Verdict.ABORTED, EvidenceGrade.Level.NONE);
        ok("SUCCESS / ABORTED 不计入统计（行为不变）",
                t4.executed() == 0 && t4.confirmedFail() == 0 && t4.inferred() == 0,
                "executed=" + t4.executed());

        System.out.println();
        System.out.println("=== 统计口径测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

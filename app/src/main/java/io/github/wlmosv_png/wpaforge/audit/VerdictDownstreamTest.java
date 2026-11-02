package io.github.wlmosv_png.wpaforge.audit;

/**
 * verdict 下游一致性测试（B5）。
 *
 * <h3>为什么需要它</h3>
 * B2 新增了两个 verdict：{@code INCOMPLETE}（连接还没结束）与
 * {@code TERMINATED_UNKNOWN}（连接已终止但无归因）。它们加进枚举之后，
 * 三处下游**没有同步**，每一处都是真退化：
 *
 * <pre>
 *   1) CrackEngine.verdictTag   —— 落 default，一律显示「=?不明」
 *      后果：界面上「可以重新裁决」与「真的不知道」长得一模一样。
 *   2) EvidenceGrade.defaultOf  —— 靠 default 兜底（语义对，但脆弱）
 *      后果：将来有人改 default 就会把它们变成可永久排除。
 *   3) VerdictTally.add         —— **完全不计入任何一类**，只把 executed 加一
 *      后果：界面「无结论 N 条」漏掉最该被看见的那一类，用户误判要不要重试。
 * </pre>
 *
 * <p>本测试把「枚举里每加一个 verdict，三处都必须显式覆盖」变成可执行的契约：
 * 任何新增 verdict 只要漏掉一处，这里立刻红。
 */
public final class VerdictDownstreamTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final CrackEngine.Verdict[] ALL = CrackEngine.Verdict.values();

    public static void main(String[] args) {
        System.out.println("=== verdict 下游一致性（B5）===");
        info("枚举共 " + ALL.length + " 个 verdict");

        // ---------------------------------------------------------- 1) 显示
        int unknownTag = 0;
        for (CrackEngine.Verdict v : ALL) {
            String tag = CrackEngine.verdictTag(7, "Passw0rd!", v, "src");
            info(String.format("%-20s -> %s", v, tag));
            if (tag.endsWith("=?不明")) unknownTag++;
        }
        ok("**没有任何 verdict 落进「?不明」**（每个都有确定语义）",
                unknownTag == 0, "落到不明=" + unknownTag);

        String tInc = CrackEngine.verdictTag(1, "x", CrackEngine.Verdict.INCOMPLETE, "");
        String tTerm = CrackEngine.verdictTag(1, "x", CrackEngine.Verdict.TERMINATED_UNKNOWN, "");
        String tUnk = CrackEngine.verdictTag(1, "x", CrackEngine.Verdict.UNKNOWN, "");
        ok("INCOMPLETE / TERMINATED_UNKNOWN / UNKNOWN 三者标签互不相同",
                !tInc.equals(tTerm) && !tTerm.equals(tUnk) && !tInc.equals(tUnk),
                tInc + " | " + tTerm + " | " + tUnk);
        ok("INCOMPLETE 标签暗示「可继续」（含 续）", tInc.contains("续"), tInc);
        ok("TERMINATED_UNKNOWN 标签暗示「已终止」（含 终）", tTerm.contains("终"), tTerm);

        // ---------------------------------------------------------- 2) 分级
        int badGrade = 0;
        for (CrackEngine.Verdict v : ALL) {
            EvidenceGrade.Level g = EvidenceGrade.defaultOf(v);
            if (v == CrackEngine.Verdict.SUCCESS && g != EvidenceGrade.Level.DIRECT) badGrade++;
            if ((v == CrackEngine.Verdict.INCOMPLETE
                    || v == CrackEngine.Verdict.TERMINATED_UNKNOWN
                    || v == CrackEngine.Verdict.TIMEOUT
                    || v == CrackEngine.Verdict.UNKNOWN)
                    && g != EvidenceGrade.Level.NONE) badGrade++;
        }
        ok("非失败终局（INCOMPLETE/TERMINATED_UNKNOWN/TIMEOUT/UNKNOWN）默认分级一律 NONE",
                badGrade == 0, "bad=" + badGrade);
        ok("SUCCESS 默认 DIRECT", EvidenceGrade.defaultOf(CrackEngine.Verdict.SUCCESS)
                == EvidenceGrade.Level.DIRECT, "");

        ok("INCOMPLETE 不可写永久记录（persistable=false）",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.INCOMPLETE)), "");
        ok("TERMINATED_UNKNOWN 不可写永久记录",
                !EvidenceGrade.persistable(
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.TERMINATED_UNKNOWN)), "");
        ok("INCOMPLETE 不推进续跑游标",
                !EvidenceGrade.advancesCursor(
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.INCOMPLETE)), "");
        ok("TERMINATED_UNKNOWN 不推进续跑游标",
                !EvidenceGrade.advancesCursor(
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.TERMINATED_UNKNOWN)), "");

        // ---------------------------------------------------------- 2b) 网络级
        System.out.println();
        System.out.println("-- NETWORK_AUTH_FAIL 必须独立成类（D1）--");
        VerdictTally tn = new VerdictTally();
        tn.add(CrackEngine.Verdict.NETWORK_AUTH_FAIL, EvidenceGrade.Level.NONE);
        info("NETWORK_AUTH_FAIL -> executed=" + tn.executed()
                + " 已证实=" + tn.confirmedFail() + " 推断=" + tn.inferred()
                + " 无结论=" + tn.inconclusive() + " 网络级=" + tn.networkAuthFail());
        ok("NETWORK_AUTH_FAIL 只计入 networkAuthFail（不混入已证实/推断/无结论）",
                tn.networkAuthFail() == 1 && tn.confirmedFail() == 0
                        && tn.inferred() == 0 && tn.inconclusive() == 0,
                tn.networkAuthFail() + "/" + tn.confirmedFail() + "/" + tn.inferred());
        ok("存在网络级失败时 missProven() 必须为 false（不能宣称「未命中」）",
                !tn.missProven(), "");
        ok("NETWORK_AUTH_FAIL 不计入退避样本（它不说明候选有问题）",
                !io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(
                        CrackEngine.Verdict.NETWORK_AUTH_FAIL), "");
        ok("NETWORK_AUTH_FAIL 不推进游标",
                !EvidenceGrade.advancesCursor(
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.NETWORK_AUTH_FAIL)), "");

        // ---------------------------------------------------------- 3) 统计闭合
        for (CrackEngine.Verdict v : ALL) {
            VerdictTally t = new VerdictTally();
            t.add(v, EvidenceGrade.defaultOf(v));
            int sum = t.confirmedFail() + t.inferred() + t.inconclusive()
                    + t.networkAuthFail();
            // U3：ERROR（通道出错/命令没生效）与 TARGET_GONE（目标不在范围）
            // 也**不算试过** —— 它们衡量环境，不是「这条候选被验证过」。
            // 这条口径必须与 VerdictView.countsAsTested 完全一致。
            boolean excluded = (v == CrackEngine.Verdict.SUCCESS
                    || v == CrackEngine.Verdict.ABORTED
                    || v == CrackEngine.Verdict.ERROR
                    || v == CrackEngine.Verdict.TARGET_GONE);
            int expectExecuted = excluded ? 0 : 1;
            ok(String.format("%-20s executed=%d 三类和=%d（应闭合）",
                            v, t.executed(), sum),
                    t.executed() == expectExecuted && sum == expectExecuted,
                    "executed=" + t.executed() + " sum=" + sum);
        }

        VerdictTally t2 = new VerdictTally();
        t2.add(CrackEngine.Verdict.INCOMPLETE, EvidenceGrade.Level.NONE);
        ok("INCOMPLETE 计入「无结论」（此前完全不计入任何一类）",
                t2.inconclusive() == 1 && t2.executed() == 1,
                "inconclusive=" + t2.inconclusive() + " executed=" + t2.executed());

        VerdictTally t3 = new VerdictTally();
        t3.add(CrackEngine.Verdict.TERMINATED_UNKNOWN, EvidenceGrade.Level.NONE);
        ok("TERMINATED_UNKNOWN 计入「无结论」",
                t3.inconclusive() == 1 && t3.executed() == 1,
                "inconclusive=" + t3.inconclusive());

        // 混合场景：仍必须闭合
        VerdictTally t4 = new VerdictTally();
        t4.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);   // confirmedFail
        t4.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED); // inferred
        t4.add(CrackEngine.Verdict.INCOMPLETE, EvidenceGrade.Level.NONE);    // inconclusive
        t4.add(CrackEngine.Verdict.TERMINATED_UNKNOWN, EvidenceGrade.Level.NONE);
        t4.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);     // 不计
        info("混合: executed=" + t4.executed() + " 已证实失败=" + t4.confirmedFail()
                + " 推断失败=" + t4.inferred() + " 无结论=" + t4.inconclusive());
        ok("混合场景闭合：executed = 已证实 + 推断 + 无结论 + 网络级",
                t4.executed() == t4.confirmedFail() + t4.inferred()
                        + t4.inconclusive() + t4.networkAuthFail(),
                "executed=" + t4.executed());
        ok("混合场景具体数值正确（4 条计入，1 已证实 / 1 推断 / 2 无结论）",
                t4.executed() == 4 && t4.confirmedFail() == 1
                        && t4.inferred() == 1 && t4.inconclusive() == 2,
                t4.confirmedFail() + "/" + t4.inferred() + "/" + t4.inconclusive());

        System.out.println();
        System.out.println("=== verdict 下游一致性: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
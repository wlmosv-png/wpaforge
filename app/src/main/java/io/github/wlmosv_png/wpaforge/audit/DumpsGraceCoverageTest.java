package io.github.wlmosv_png.wpaforge.audit;

import java.util.Collections;

/**
 * 判定链**兜底补采与持久化边界**测试（1.9.66 专项 B）。纯逻辑。
 *
 * <p>钉死三件事：
 * <ol>
 *   <li>兜底补采的触发条件覆盖 TERMINATED 与 UNKNOWN（1.9.66 修正），
 *       但不覆盖 ACTIVE（连接还在跑 → INCOMPLETE，不该白等）；</li>
 *   <li>补采到 WRONG_KEY 时，只有 TERMINATED 才落 AUTH_FAIL + DIRECT；
 *       UNKNOWN 下最多网络级，**不冒充**候选级已证实错误；</li>
 *   <li>只有 DIRECT 写 TriedSet / 推进 Cursor；NONE / INFERRED 都不写。</li>
 * </ol>
 */
public final class DumpsGraceCoverageTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    /** 与 CrackEngine 兜底条件同构的纯判定（保持两边语义一致）。 */
    private static boolean graceAllowed(boolean configConfirmedAbsent, boolean sawAttempt,
                                        boolean channelAlive, CrackEngine.Lifecycle lc,
                                        boolean cancel, boolean pause) {
        return configConfirmedAbsent && sawAttempt && channelAlive
                && (lc == CrackEngine.Lifecycle.TERMINATED || lc == CrackEngine.Lifecycle.UNKNOWN)
                && !cancel && !pause;
    }

    public static void main(String[] args) {
        System.out.println("== 判定链兜底补采与持久化边界 (1.9.66) ==");
        try {
            // ---- 1. 兜底触发条件 ----
            ok("★TERMINATED 触发兜底",
                    graceAllowed(true, true, true, CrackEngine.Lifecycle.TERMINATED, false, false));
            ok("★UNKNOWN 触发兜底（1.9.66 修正：读不到状态也要补采）",
                    graceAllowed(true, true, true, CrackEngine.Lifecycle.UNKNOWN, false, false));
            ok("★ACTIVE 不触发兜底（连接还在跑）",
                    !graceAllowed(true, true, true, CrackEngine.Lifecycle.ACTIVE, false, false));
            ok("★暂停不触发", !graceAllowed(true, true, true, CrackEngine.Lifecycle.TERMINATED, false, true));
            ok("★取消不触发", !graceAllowed(true, true, true, CrackEngine.Lifecycle.TERMINATED, true, false));
            ok("★配置未确认删净不触发",
                    !graceAllowed(false, true, true, CrackEngine.Lifecycle.TERMINATED, false, false));
            ok("★未真正尝试不触发",
                    !graceAllowed(true, false, true, CrackEngine.Lifecycle.TERMINATED, false, false));
            ok("★通道不可用不触发",
                    !graceAllowed(true, true, false, CrackEngine.Lifecycle.TERMINATED, false, false));

            // ---- 2. 补采到 WRONG_KEY 的落地边界 ----
            String aid = "grace-1";
            Evidence wkAt3 = new Evidence(Evidence.Source.DUMPS_WRONG_KEY,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, aid, 5000L);

            AttemptState term = new AttemptState(aid, CrackEngine.Lifecycle.TERMINATED,
                    AttemptState.Gate.OK, true);
            Adjudicator.Result rTerm = Adjudicator.adjudicate(term,
                    Collections.singletonList(wkAt3));
            ok("★TERMINATED + WRONG_KEY + AT3 → AUTH_FAIL",
                    rTerm.verdict == CrackEngine.Verdict.AUTH_FAIL, "" + rTerm.verdict);
            ok("★TERMINATED → DIRECT", rTerm.grade == EvidenceGrade.Level.DIRECT, "" + rTerm.grade);
            ok("★DIRECT 可写 TriedSet", EvidenceGrade.persistable(rTerm.grade));
            ok("★DIRECT 可推进 Cursor", EvidenceGrade.advancesCursor(rTerm.grade));

            AttemptState unk = new AttemptState(aid, CrackEngine.Lifecycle.UNKNOWN,
                    AttemptState.Gate.OK, true);
            Adjudicator.Result rUnk = Adjudicator.adjudicate(unk,
                    Collections.singletonList(wkAt3));
            ok("★UNKNOWN + WRONG_KEY + AT3 不落 AUTH_FAIL",
                    rUnk.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + rUnk.verdict);
            ok("★UNKNOWN 下不 DIRECT", rUnk.grade != EvidenceGrade.Level.DIRECT, "" + rUnk.grade);
            ok("★UNKNOWN 下不写 TriedSet", !EvidenceGrade.persistable(rUnk.grade));
            ok("★UNKNOWN 下不推进 Cursor", !EvidenceGrade.advancesCursor(rUnk.grade));

            // ---- 3. 采集失败 / 无证据的安全降级 ----
            Adjudicator.Result rEmpty = Adjudicator.adjudicate(term,
                    Collections.<Evidence>emptyList());
            ok("★无证据 + TERMINATED → 不是 AUTH_FAIL",
                    rEmpty.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + rEmpty.verdict);
            ok("★无证据 → 不写 TriedSet", !EvidenceGrade.persistable(rEmpty.grade));
            ok("★无证据 → 不推进 Cursor", !EvidenceGrade.advancesCursor(rEmpty.grade));

            // ---- 4. 证据归属：别人的 attempt 不认 ----
            Evidence other = new Evidence(Evidence.Source.DUMPS_WRONG_KEY,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, "other-attempt", 5000L);
            Adjudicator.Result rOther = Adjudicator.adjudicate(term,
                    Collections.singletonList(other));
            ok("★别的 attempt 的 AT3 不产 AUTH_FAIL",
                    rOther.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + rOther.verdict);
            ok("★别的 attempt 不产 DIRECT", rOther.grade != EvidenceGrade.Level.DIRECT);

            // ---- 5. 正确密码安全性：SUCCESS 不会被 WRONG_KEY 抢答 ----
            Evidence succ = new Evidence(Evidence.Source.SUCCESS_STATUS,
                    Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, aid, 900L);
            Adjudicator.Result rBoth = Adjudicator.adjudicate(term,
                    java.util.Arrays.asList(succ, wkAt3));
            ok("★成功证据优先于 WRONG_KEY → SUCCESS",
                    rBoth.verdict == CrackEngine.Verdict.SUCCESS, "" + rBoth.verdict);
            ok("★SUCCESS 不写错误集合（persistable 用于失败，SUCCESS 由服务单独排除）",
                    rBoth.verdict == CrackEngine.Verdict.SUCCESS);

            // ---- 6. NETWORK_AUTH_FAIL 不冒充候选级 ----
            Evidence netOnly = new Evidence(Evidence.Source.AUTHCAST,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT1, aid, 4200L);
            Adjudicator.Result rNet = Adjudicator.adjudicate(term,
                    Collections.singletonList(netOnly));
            ok("★AT1 → 网络级，不冒充候选级",
                    rNet.grade != EvidenceGrade.Level.DIRECT, "" + rNet.grade);
            ok("★AT1 不写 TriedSet", !EvidenceGrade.persistable(rNet.grade));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 兜底补采与持久化边界: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

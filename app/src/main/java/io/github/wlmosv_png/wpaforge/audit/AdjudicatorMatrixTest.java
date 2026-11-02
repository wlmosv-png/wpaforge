package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一裁决器穷举矩阵测试（C3）。
 *
 * <h3>为什么用穷举而不是挑几个用例</h3>
 * {@link Adjudicator} 是**唯一**产出 Verdict 与 Grade 的地方（迁移完成后
 * {@code R11UnifiedExitTest} 会守这条）。它出错不会再有任何下游拦截 ——
 * 所以必须把「(闸门 × 生命周期 × 证据主张 × 归属强度)」的**整个组合空间**
 * 跑一遍，逐格检查五条不变量，而不是抽查。
 *
 * <pre>
 *   4 闸门 × 3 生命周期 × 4 主张 × 4 归属 = 192 格
 *   外加：未归属证据 / 空证据 / 多来源重复 / 冲突证据
 * </pre>
 */
public final class AdjudicatorMatrixTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String AID = "m5k3p1x9-3f9a2b7c-1#11";

    private static AttemptState st(AttemptState.Gate g, CrackEngine.Lifecycle lc) {
        return new AttemptState(AID, lc, g, false);
    }

    private static Evidence ev(Evidence.Source src, Evidence.Claim c,
                               Evidence.Attribution a, String aid) {
        return new Evidence(src, c, a, aid, 1000L);
    }

    public static void main(String[] args) {
        System.out.println("=== 统一裁决器穷举矩阵（C3）===");

        AttemptState.Gate[] gates = AttemptState.Gate.values();
        CrackEngine.Lifecycle[] lcs = CrackEngine.Lifecycle.values();
        Evidence.Claim[] claims = Evidence.Claim.values();
        Evidence.Attribution[] ats = Evidence.Attribution.values();

        int cells = 0, badAuthFail = 0, badDirect = 0, badGate = 0,
                badActive = 0, badTerm = 0, badUnknownLc = 0, badSuccess = 0;

        for (AttemptState.Gate g : gates) {
            for (CrackEngine.Lifecycle lc : lcs) {
                for (Evidence.Claim c : claims) {
                    for (Evidence.Attribution a : ats) {
                        cells++;
                        List<Evidence> list = new ArrayList<>();
                        if (a != Evidence.Attribution.NONE) {
                            list.add(ev(Evidence.Source.WPALOG_A, c, a, AID));
                        }
                        Adjudicator.Result r = Adjudicator.adjudicate(st(g, lc), list);

                        // I1：AUTH_FAIL 只能由 WRONG_KEY 产生
                        if (r.verdict == CrackEngine.Verdict.AUTH_FAIL
                                && c != Evidence.Claim.WRONG_KEY) badAuthFail++;
                        // I2：DIRECT 只能是 AT3
                        if (r.grade == EvidenceGrade.Level.DIRECT
                                && a != Evidence.Attribution.AT3) badDirect++;
                        // I3：闸门未过 -> 永远 UNKNOWN / NONE
                        if (g != AttemptState.Gate.OK
                                && (r.verdict != CrackEngine.Verdict.UNKNOWN
                                    || r.grade != EvidenceGrade.Level.NONE)) badGate++;
                        // I4：NOT_CONNECTED 必须按生命周期分派
                        if (c == Evidence.Claim.NOT_CONNECTED
                                && a != Evidence.Attribution.NONE
                                && g == AttemptState.Gate.OK) {
                            if (lc == CrackEngine.Lifecycle.ACTIVE
                                    && r.verdict != CrackEngine.Verdict.INCOMPLETE) badActive++;
                            if (lc == CrackEngine.Lifecycle.TERMINATED
                                    && r.verdict != CrackEngine.Verdict.TERMINATED_UNKNOWN) badTerm++;
                            if (lc == CrackEngine.Lifecycle.UNKNOWN
                                    && r.verdict != CrackEngine.Verdict.UNKNOWN) badUnknownLc++;
                        }
                        // I5：SUCCESS 只在 CONNECTED + AT3
                        if (r.verdict == CrackEngine.Verdict.SUCCESS
                                && (c != Evidence.Claim.CONNECTED
                                    || a != Evidence.Attribution.AT3)) badSuccess++;
                    }
                }
            }
        }

        eq("矩阵格子数 = 4 闸门 × 3 生命周期 × 4 主张 × 4 归属 = 192", 192, cells);
        eq("I1 AUTH_FAIL 只由 WRONG_KEY 产生（AP-1 永不产 AUTH_FAIL）", 0, badAuthFail);
        eq("I2 DIRECT 只由 AT3 产生（无 AT3 不得永久排除）", 0, badDirect);
        eq("I3 闸门未过 -> 一律 UNKNOWN / NONE", 0, badGate);
        eq("I4a NOT_CONNECTED + ACTIVE -> INCOMPLETE", 0, badActive);
        eq("I4b NOT_CONNECTED + TERMINATED -> TERMINATED_UNKNOWN", 0, badTerm);
        eq("I4c NOT_CONNECTED + UNKNOWN -> UNKNOWN（未知不得当终止）", 0, badUnknownLc);
        eq("I5 SUCCESS 只在 CONNECTED + AT3", 0, badSuccess);

        // ---------------------------------------------------------- 未归属证据
        System.out.println();
        System.out.println("-- 未归属证据必须被丢弃 --");
        AttemptState ok2 = st(AttemptState.Gate.OK, CrackEngine.Lifecycle.ACTIVE);

        Adjudicator.Result foreign = Adjudicator.adjudicate(ok2,
                java.util.Collections.singletonList(
                        new Evidence(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                Evidence.Attribution.AT3, "other-attempt#3", 1000L)));
        info("属别人的 AT3 WRONG_KEY -> " + foreign);
        ok("别人的证据不得判 AUTH_FAIL", 
                foreign.verdict != CrackEngine.Verdict.AUTH_FAIL, "verdict=" + foreign.verdict);
        ok("别人的证据不得给 DIRECT", 
                foreign.grade != EvidenceGrade.Level.DIRECT, "grade=" + foreign.grade);

        Adjudicator.Result nullId = Adjudicator.adjudicate(ok2,
                java.util.Collections.singletonList(
                        new Evidence(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                Evidence.Attribution.AT3, null, 1000L)));
        ok("attemptId 为空的证据不得被采信",
                nullId.verdict != CrackEngine.Verdict.AUTH_FAIL, "verdict=" + nullId.verdict);

        Adjudicator.Result emptyId = Adjudicator.adjudicate(ok2,
                java.util.Collections.singletonList(
                        new Evidence(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                Evidence.Attribution.AT3, "", 1000L)));
        ok("attemptId 为空串的证据不得被采信",
                emptyId.verdict != CrackEngine.Verdict.AUTH_FAIL, "verdict=" + emptyId.verdict);

        // ---------------------------------------------------------- 无证据
        System.out.println();
        System.out.println("-- 无证据 --");
        Adjudicator.Result none = Adjudicator.adjudicate(ok2, new ArrayList<Evidence>());
        info("空证据 -> " + none);
        ok("无证据 -> TIMEOUT / NONE（不是 AUTH_FAIL）",
                none.verdict == CrackEngine.Verdict.TIMEOUT
                        && none.grade == EvidenceGrade.Level.NONE, none.toString());
        Adjudicator.Result nullList = Adjudicator.adjudicate(ok2, null);
        ok("证据集合为 null 时安全（不抛、不误判）",
                nullList.verdict == CrackEngine.Verdict.TIMEOUT, nullList.toString());
        Adjudicator.Result nullState = Adjudicator.adjudicate(null, null);
        ok("AttemptState 为 null 时安全降级为 TIMEOUT/UNKNOWN 类，不抛",
                nullState.verdict != CrackEngine.Verdict.SUCCESS, nullState.toString());

        // ---------------------------------------------------------- 多来源重复
        System.out.println();
        System.out.println("-- 同一 Attempt 多来源重复证据 --");
        List<Evidence> dup = new ArrayList<>();
        dup.add(ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT1, AID));
        dup.add(ev(Evidence.Source.AUTHCAST, Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT1, AID));
        dup.add(ev(Evidence.Source.M1RETRY, Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT1, AID));
        Adjudicator.Result rd = Adjudicator.adjudicate(
                st(AttemptState.Gate.OK, CrackEngine.Lifecycle.TERMINATED), dup);
        info("三条 AT1 WRONG_KEY -> " + rd);
        ok("多来源重复证据只裁决一次（AUTH_FAIL / INFERRED）",
                rd.verdict == CrackEngine.Verdict.AUTH_FAIL
                        && rd.grade == EvidenceGrade.Level.INFERRED, rd.toString());

        // ---------------------------------------------------------- 冲突证据
        System.out.println();
        System.out.println("-- 冲突证据（成功 vs 失败）--");
        List<Evidence> conflict = new ArrayList<>();
        conflict.add(ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                Evidence.Attribution.AT1, AID));
        conflict.add(ev(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, AID));
        Adjudicator.Result rc = Adjudicator.adjudicate(
                st(AttemptState.Gate.OK, CrackEngine.Lifecycle.ACTIVE), conflict);
        info("AT1 失败 + AT3 成功 -> " + rc);
        ok("成功优先于失败（有 AT3 成功证据时不得判负）",
                rc.verdict == CrackEngine.Verdict.SUCCESS, rc.toString());

        List<Evidence> conflict2 = new ArrayList<>();
        conflict2.add(ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                Evidence.Attribution.AT3, AID));
        conflict2.add(ev(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT1, AID));
        Adjudicator.Result rc2 = Adjudicator.adjudicate(
                st(AttemptState.Gate.OK, CrackEngine.Lifecycle.TERMINATED), conflict2);
        info("AT3 失败 + AT1 成功（归属不明的成功）-> " + rc2);
        ok("归属不明的成功不得掩盖 AT3 级别的失败 —— 但也不能判成功",
                rc2.verdict != CrackEngine.Verdict.SUCCESS, rc2.toString());

        // ---------------------------------------------------------- 归属升级
        System.out.println();
        System.out.println("-- 归属等级逐级升级 --");
        for (Evidence.Attribution a : ats) {
            Adjudicator.Result r = Adjudicator.adjudicate(
                    st(AttemptState.Gate.OK, CrackEngine.Lifecycle.TERMINATED),
                    java.util.Collections.singletonList(
                            ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY, a, AID)));
            info("WRONG_KEY / " + a + " -> " + r + " persistable="
                    + EvidenceGrade.persistable(r.grade));
        }
        ok("只有 AT3 的 AUTH_FAIL 可持久化（其余一律 INFERRED）",
                EvidenceGrade.persistable(Adjudicator.adjudicate(
                        st(AttemptState.Gate.OK, CrackEngine.Lifecycle.TERMINATED),
                        java.util.Collections.singletonList(
                                ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                        Evidence.Attribution.AT3, AID))).grade)
                && !EvidenceGrade.persistable(Adjudicator.adjudicate(
                        st(AttemptState.Gate.OK, CrackEngine.Lifecycle.TERMINATED),
                        java.util.Collections.singletonList(
                                ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                        Evidence.Attribution.AT2, AID))).grade), "");

        // ---------------------------------------------------------- 结果构造
        System.out.println();
        System.out.println("-- Result -> Attempt --");
        Adjudicator.Result r0 = Adjudicator.adjudicate(
                st(AttemptState.Gate.OK, CrackEngine.Lifecycle.ACTIVE),
                java.util.Collections.singletonList(
                        ev(Evidence.Source.FAILOVER, Evidence.Claim.NOT_CONNECTED,
                                Evidence.Attribution.AT1, AID)));
        CrackEngine.Attempt at = Adjudicator.toAttempt("Passw0rd", r0, 1234L, "extra");
        info("构造出 Attempt: verdict=" + at.verdict + " grade=" + at.grade
                + " reason=" + at.reason + " detail=" + at.detail);
        ok("toAttempt 保留 verdict / grade / reason",
                at.verdict == CrackEngine.Verdict.INCOMPLETE
                        && at.grade == EvidenceGrade.Level.NONE
                        && "not-connected-active".equals(at.reason), at.reason);
        ok("toAttempt 追加 detail 后缀", at.detail.contains("extra"), at.detail);
        ok("toAttempt 不误设 confirmedBy（证据来源在 bySource）",
                at.confirmedBy == null || at.confirmedBy.isEmpty(),
                "by=" + at.confirmedBy);

        System.out.println();
        System.out.println("=== 裁决矩阵测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void eq(String name, int expect, int actual) {
        ok(name, expect == actual, "期望=" + expect + " 实际=" + actual);
    }
}
package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 矩阵 A：Attempt 生命周期 × 证据 × Verdict × 账本档位 × 跳过策略。
 *
 * <h3>为什么必须先写这个测试</h3>
 * 2026-11-02 专项审计构造出两条真实路径，它们把「请求还没结束」的尝试
 * 写进了「省时档可跳过」的集合：
 * <pre>
 *   Lifecycle.UNKNOWN  + 无证据          → TIMEOUT  → 账本 TIMEOUT        → 省时档跳过
 *   Lifecycle.ACTIVE   + 未归属 WRONG_KEY → NETWORK_AUTH_FAIL → NETWORK_TERMINATED → 省时档跳过
 * </pre>
 * 这两条在本类里是**必须失败的断言**，修复后才允许通过。
 *
 * <h3>测试方式</h3>
 * 全部走**真实生产实现**：{@link Adjudicator#adjudicate}、
 * {@link CrackEngine#classifyDeadline}、{@link AttemptLedger.Level#classify}、
 * {@link AttemptLedger.SkipPolicy#skips}。本类不复制任何判定规则。
 */
public final class AdjudicatorLifecycleMatrixTest {

    private static int pass = 0, fail = 0;
    private static final List<String> violations = new ArrayList<>();

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; }
        else { fail++; violations.add(name + " :: " + detail); System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static CrackEngine.Lifecycle[] LCS = CrackEngine.Lifecycle.values();
    private static Evidence.Claim[] CLAIMS = Evidence.Claim.values();
    private static Evidence.Attribution[] ATTRS = Evidence.Attribution.values();

    public static void main(String[] args) {
        System.out.println("=== A. 生命周期 × 证据 × Verdict × 账本 × 跳过策略 ===");

        // ---------- ① 穷举：单条证据 ----------
        for (CrackEngine.Lifecycle lc : LCS) {
            for (Evidence.Claim c : CLAIMS) {
                for (Evidence.Attribution at : ATTRS) {
                    Adjudicator.Result r = adj(lc, c, at);
                    checkInvariants("单证据 lc=" + lc + " claim=" + c + " attr=" + at, lc, r.verdict, r.grade);
                }
            }
        }

        // ---------- ② 穷举：双条证据（含冲突组合） ----------
        for (CrackEngine.Lifecycle lc : LCS) {
            for (Evidence.Claim c1 : CLAIMS) {
                for (Evidence.Claim c2 : CLAIMS) {
                    for (Evidence.Attribution a1 : new Evidence.Attribution[]{
                            Evidence.Attribution.NONE, Evidence.Attribution.AT3}) {
                        for (Evidence.Attribution a2 : new Evidence.Attribution[]{
                                Evidence.Attribution.NONE, Evidence.Attribution.AT3}) {
                            Adjudicator.Result r = adj2(lc, c1, a1, c2, a2);
                            checkInvariants("双证据 lc=" + lc + " " + c1 + "/" + a1 + " + " + c2 + "/" + a2,
                                    lc, r.verdict, r.grade);
                        }
                    }
                }
            }
        }

        // ---------- ③ classifyDeadline 的真实出口 ----------
        for (CrackEngine.Lifecycle lc : LCS) {
            for (boolean notAttempted : new boolean[]{false, true}) {
                for (boolean channelDead : new boolean[]{false, true}) {
                    for (boolean statusShows : new boolean[]{false, true}) {
                        CrackEngine.DeadlineOutcome o = CrackEngine.classifyDeadline(
                                notAttempted, false, false, channelDead, statusShows, lc);
                        checkInvariants("classifyDeadline lc=" + lc + " notAtt=" + notAttempted
                                        + " chDead=" + channelDead + " status=" + statusShows,
                                lc, o.verdict, EvidenceGrade.defaultOf(o.verdict));
                    }
                }
            }
        }

        // ---------- ④ 明确要求：不能把所有东西都降级成 UNKNOWN ----------
        {
            Adjudicator.Result strong = adj(CrackEngine.Lifecycle.TERMINATED,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3);
            ok("TERMINATED + WRONG_KEY@AT3 仍必须产出候选级 AUTH_FAIL（不得退化成 UNKNOWN）",
                    strong.verdict == CrackEngine.Verdict.AUTH_FAIL
                            && strong.grade == EvidenceGrade.Level.DIRECT,
                    strong.verdict + "/" + strong.grade);

            Adjudicator.Result conn = adj(CrackEngine.Lifecycle.ACTIVE,
                    Evidence.Claim.CONNECTED, Evidence.Attribution.AT3);
            ok("ACTIVE + CONNECTED@AT3 仍必须是 SUCCESS（生命周期不得屏蔽成功）",
                    conn.verdict == CrackEngine.Verdict.SUCCESS
                            && conn.grade == EvidenceGrade.Level.DIRECT,
                    conn.verdict + "/" + conn.grade);

            Adjudicator.Result term = adj(CrackEngine.Lifecycle.TERMINATED,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT1);
            ok("TERMINATED + WRONG_KEY@AT1 仍是网络级 NETWORK_AUTH_FAIL（不升级为候选级）",
                    term.verdict == CrackEngine.Verdict.NETWORK_AUTH_FAIL,
                    String.valueOf(term.verdict));
        }

        // ---------- ⑤ reason 必须能区分 UNKNOWN 与 TERMINATED ----------
        {
            CrackEngine.DeadlineOutcome t = CrackEngine.classifyDeadline(
                    false, false, false, false, false, CrackEngine.Lifecycle.TERMINATED);
            CrackEngine.DeadlineOutcome u = CrackEngine.classifyDeadline(
                    false, false, false, false, false, CrackEngine.Lifecycle.UNKNOWN);
            ok("状态未知与确认终止的 reason 不同（可区分）",
                    !t.reason.equals(u.reason), t.reason + " vs " + u.reason);
            ok("状态未知不得被解释成「请求已终止」的 TIMEOUT",
                    u.verdict != CrackEngine.Verdict.TIMEOUT, String.valueOf(u.verdict));
            ok("确认终止仍可产出 TIMEOUT",
                    t.verdict == CrackEngine.Verdict.TIMEOUT, String.valueOf(t.verdict));
        }

        // ---------- ⑥ 持久化入口：哪些 verdict 允许写 triedSet ----------
        {
            // triedSet 的口径 = EvidenceGrade.persistable(grade) 且不是命中
            checkPersistable(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT, false,
                    "命中不得写进「已证实错误」集合");
            checkPersistable(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT, true,
                    "候选级直接否认允许写");
            checkPersistable(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED, false,
                    "非 DIRECT 不得写");
            for (CrackEngine.Verdict v : new CrackEngine.Verdict[]{
                    CrackEngine.Verdict.NETWORK_AUTH_FAIL, CrackEngine.Verdict.TIMEOUT,
                    CrackEngine.Verdict.UNKNOWN, CrackEngine.Verdict.INCOMPLETE,
                    CrackEngine.Verdict.TERMINATED_UNKNOWN, CrackEngine.Verdict.ERROR,
                    CrackEngine.Verdict.ABORTED}) {
                checkPersistable(v, EvidenceGrade.defaultOf(v), false, v + " 不得写 triedSet");
            }
        }

        System.out.println();
        System.out.println("=== A 矩阵: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) {
            System.out.println("违规前 8 条：");
            for (int i = 0; i < Math.min(8, violations.size()); i++) {
                System.out.println("   - " + violations.get(i));
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 不变量

    /**
     * 在一条真实结论上检查全部不变量。
     *
     * @param where 组合描述（失败时用于定位）
     * @param lc    产生这条结论时的生命周期
     */
    private static void checkInvariants(String where, CrackEngine.Lifecycle lc,
                                        CrackEngine.Verdict v, EvidenceGrade.Level grade) {
        AttemptLedger.Level lv = AttemptLedger.Level.classify(v, grade);

        // 不变量 1：未确认终止 → 不得产生候选级永久结论
        if (lc != CrackEngine.Lifecycle.TERMINATED) {
            ok("[1] " + where + " 未确认终止不得产 AUTH_FAIL",
                    v != CrackEngine.Verdict.AUTH_FAIL, String.valueOf(v));
            ok("[1b] " + where + " 未确认终止不得产 DIRECT 级结论",
                    grade != EvidenceGrade.Level.DIRECT || v == CrackEngine.Verdict.SUCCESS,
                    v + "/" + grade);
        }

        // 不变量 2：未确认终止 → 不得进入省时档跳过集合
        if (lc != CrackEngine.Lifecycle.TERMINATED) {
            ok("[2] " + where + " 未确认终止不得被省时档跳过",
                    !AttemptLedger.SkipPolicy.SAVE_TIME.skips(lv),
                    v + " → " + lv);
        }

        // 不变量 3：任何非成功结论都不得被解释成「请求已终止」的网络级失败
        //           （除非生命周期确实是 TERMINATED）
        if (lc == CrackEngine.Lifecycle.ACTIVE) {
            ok("[3] " + where + " ACTIVE 不得产 NETWORK_AUTH_FAIL",
                    v != CrackEngine.Verdict.NETWORK_AUTH_FAIL, String.valueOf(v));
        }

        // 不变量 4：生命周期读不到时，结论必须明确表达「不确定」
        if (lc == CrackEngine.Lifecycle.UNKNOWN && v == CrackEngine.Verdict.TIMEOUT) {
            ok("[4] " + where + " UNKNOWN 不得产 TIMEOUT", false, "产出了 TIMEOUT");
        }

        // 不变量 5：保守档只跳已确定错误
        if (lv != AttemptLedger.Level.CONFIRMED_WRONG) {
            ok("[5] " + where + " 保守档只能跳 CONFIRMED_WRONG",
                    !AttemptLedger.SkipPolicy.CONSERVATIVE.skips(lv),
                    v + " → " + lv);
        }
    }

    /** triedSet 的可持久化语义（第 6 组）。 */
    private static void checkPersistable(CrackEngine.Verdict v, EvidenceGrade.Level g,
                                         boolean want, String why) {
        boolean writes = EvidenceGrade.persistable(g) && v != CrackEngine.Verdict.SUCCESS;
        ok("[6] " + v + "/" + g + " → triedSet=" + writes + "（" + why + "）",
                writes == want, "期望 " + want);
    }

    // ------------------------------------------------------------------ 构造

    private static Adjudicator.Result adj(CrackEngine.Lifecycle lc,
                                          Evidence.Claim c, Evidence.Attribution at) {
        AttemptState st = new AttemptState("aid", lc, AttemptState.Gate.OK, false);
        return Adjudicator.adjudicate(st, Collections.singletonList(
                new Evidence(Evidence.Source.WPALOG_A, c, at, "aid", 0L)));
    }

    private static Adjudicator.Result adj2(CrackEngine.Lifecycle lc,
                                           Evidence.Claim c1, Evidence.Attribution a1,
                                           Evidence.Claim c2, Evidence.Attribution a2) {
        AttemptState st = new AttemptState("aid", lc, AttemptState.Gate.OK, false);
        List<Evidence> evs = new ArrayList<>();
        evs.add(new Evidence(Evidence.Source.WPALOG_A, c1, a1, "aid", 0L));
        evs.add(new Evidence(Evidence.Source.SUCCESS_STATUS, c2, a2, "aid", 1L));
        return Adjudicator.adjudicate(st, evs);
    }
}

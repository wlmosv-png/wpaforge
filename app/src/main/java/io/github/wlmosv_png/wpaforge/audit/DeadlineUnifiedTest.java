package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * D4：到期归类**只有一个判定核心**（{@link Adjudicator}）。
 *
 * <h3>钉住两件事</h3>
 * <ol>
 *   <li><b>穷举</b> {@code classifyDeadline} 的 5 个布尔 × 3 种生命周期的全部组合
 *       （32 × 3 = 96 格），逐格断言业务结论与理由码。</li>
 *   <li><b>一致性</b>：每一格的结论必须与「按同样的输入构造 AttemptState + Evidence，
 *       直接问 {@link Adjudicator}」得到的结论相同（{@code not-attempted} 的
 *       ERROR 改写除外）。这一条保证它没有变成第二套判定。</li>
 * </ol>
 *
 * <p>修复前该函数是独立五分支 if 链，且 tryOneShell 还会在拿到结果后
 * 二次覆盖成 INCOMPLETE —— 本测试对修复前的实现会因「ACTIVE 且无证据」
 * 返回 TIMEOUT 而失败（真阳性验证）。
 */
public final class DeadlineUnifiedTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 按 classifyDeadline 的同一翻译规则构造等价状态（用于一致性对照）。 */
    private static CrackEngine.Verdict expectedCore(boolean notAttempted, boolean onTargetNow,
                                                    boolean netidConflict, boolean channelDead,
                                                    boolean statusShowsTarget,
                                                    CrackEngine.Lifecycle lc) {
        AttemptState.Gate gate = (notAttempted || channelDead)
                ? AttemptState.Gate.UNREADABLE : AttemptState.Gate.OK;
        Evidence.Claim claim;
        if (onTargetNow || netidConflict || statusShowsTarget) claim = Evidence.Claim.CONNECTED;
        else claim = Evidence.Claim.NOT_CONNECTED;
        // 与生产同口径：本函数的证据绑定到一个**固定的窗口占位 id**，
        // AttemptState 用同一个 id —— 否则证据会被 Adjudicator 当「未归属」丢弃，
        // 全部格子退化到规则 9（这正是本测试第一次跑出来抓到的问题）。
        final String aid = "deadline-window";
        AttemptState st = new AttemptState(aid, lc, gate, false);
        Evidence e = new Evidence(Evidence.Source.SUCCESS_STATUS, claim,
                Evidence.Attribution.NONE, aid, 0L);
        boolean zeroObservation = !notAttempted && !onTargetNow && !netidConflict
                && !channelDead && !statusShowsTarget;
        // classifyDeadline 对「零观察」传空证据（规则 9 -> 按生命周期分派）；
        // 其余情形传一条占位证据。对照侧必须完全同构，才能证明「不是两套逻辑」。
        return Adjudicator.adjudicate(st,
                zeroObservation ? Collections.<Evidence>emptyList()
                        : Collections.singletonList(e)).verdict;
    }

    public static void main(String[] args) {
        System.out.println("=== D4 到期归类统一裁决 ===");

        CrackEngine.Lifecycle[] lcs = {
                CrackEngine.Lifecycle.ACTIVE,
                CrackEngine.Lifecycle.TERMINATED,
                CrackEngine.Lifecycle.UNKNOWN
        };

        int cells = 0, mismatch = 0, badHttp = 0;
        for (int m = 0; m < 32; m++) {
            boolean notAttempted   = (m & 1) != 0;
            boolean onTargetNow    = (m & 2) != 0;
            boolean netidConflict  = (m & 4) != 0;
            boolean channelDead    = (m & 8) != 0;
            boolean statusShowsTarget = (m & 16) != 0;

            for (CrackEngine.Lifecycle lc : lcs) {
                cells++;
                CrackEngine.DeadlineOutcome out = CrackEngine.classifyDeadline(
                        notAttempted, onTargetNow, netidConflict, channelDead,
                        statusShowsTarget, lc);
                CrackEngine.Verdict core = expectedCore(notAttempted, onTargetNow,
                        netidConflict, channelDead, statusShowsTarget, lc);

                // ① 与统一核心一致（not-attempted 的 ERROR 是唯一允许的业务改写）
                boolean sameAsCore = notAttempted
                        ? (out.verdict == CrackEngine.Verdict.ERROR
                            && core == CrackEngine.Verdict.UNKNOWN)
                        : out.verdict == core;
                if (!sameAsCore) {
                    mismatch++;
                    System.out.println("  FAIL 一致性 " + tag(m, lc)
                            + " 实际=" + out.verdict + " 核心=" + core);
                }

                // ② 硬语义：ACTIVE 且无任何「已连上」类输入 -> INCOMPLETE
                if (!notAttempted && !channelDead && !onTargetNow && !netidConflict
                        && !statusShowsTarget && lc == CrackEngine.Lifecycle.ACTIVE) {
                    if (out.verdict != CrackEngine.Verdict.INCOMPLETE) {
                        badHttp++;
                        System.out.println("  FAIL ACTIVE 应为 INCOMPLETE: " + tag(m, lc)
                                + " -> " + out.verdict);
                    }
                    if (!"no-evidence-active".equals(out.reason)) {
                        badHttp++;
                        System.out.println("  FAIL reason 应为 no-evidence-active: "
                                + out.reason);
                    }
                }
                // ③ 硬语义：TERMINATED 且零观察 -> TIMEOUT（窗口结束、连接已终止、
                //    没有任何可归属证据）。D4 之后它不再被并进 UNKNOWN。
                if (!notAttempted && !channelDead && !onTargetNow && !netidConflict
                        && !statusShowsTarget && lc == CrackEngine.Lifecycle.TERMINATED) {
                    if (out.verdict != CrackEngine.Verdict.TIMEOUT
                            || !"no-evidence".equals(out.reason)) {
                        badHttp++;
                        System.out.println("  FAIL TERMINATED 零观察应为 TIMEOUT/no-evidence: "
                                + tag(m, lc) + " -> " + out.verdict + "/" + out.reason);
                    }
                }
                // ④' 硬语义：「连着目标但无法归属」必须 UNKNOWN（不得判成功/失败）
                if (!notAttempted && !channelDead && (onTargetNow || netidConflict
                        || statusShowsTarget)) {
                    if (out.verdict != CrackEngine.Verdict.UNKNOWN) {
                        badHttp++;
                        System.out.println("  FAIL 归属不明应为 UNKNOWN: "
                                + tag(m, lc) + " -> " + out.verdict);
                    }
                }
                // ④ 硬语义：任何情况下都不得产出 AUTH_FAIL / SUCCESS
                if (out.verdict == CrackEngine.Verdict.AUTH_FAIL
                        || out.verdict == CrackEngine.Verdict.SUCCESS) {
                    badHttp++;
                    System.out.println("  FAIL 到期归类不得产出 "
                            + out.verdict + ": " + tag(m, lc));
                }
                // ⑤ not-attempted -> ERROR 且 reason 固定
                if (notAttempted) {
                    if (out.verdict != CrackEngine.Verdict.ERROR
                            || !"not-attempted".equals(out.reason)) {
                        badHttp++;
                        System.out.println("  FAIL not-attempted 映射错: "
                                + out.verdict + "/" + out.reason);
                    }
                }
            }
        }

        info("穷举格数=" + cells);
        ok("全部格子与 Adjudicator 一致", mismatch == 0, "不一致 " + mismatch + " 格");
        ok("ACTIVE->INCOMPLETE / TERMINATED->TERMINATED_UNKNOWN / 无 AUTH_FAIL",
                badHttp == 0, "违例 " + badHttp + " 处");

        // ---- 默认重载（无生命周期参数）必须等于 UNKNOWN 生命周期 ----
        CrackEngine.DeadlineOutcome a = CrackEngine.classifyDeadline(false, false, false, false, false);
        CrackEngine.DeadlineOutcome b = CrackEngine.classifyDeadline(false, false, false, false, false,
                CrackEngine.Lifecycle.UNKNOWN);
        ok("五参重载 == UNKNOWN 生命周期", a.verdict == b.verdict
                && a.reason.equals(b.reason), a + " vs " + b);
        // 2026-11-02 审计：状态读不到 → 不得报 TIMEOUT（那等于说「请求已终止」）。
        ok("无证据 + 状态读不到 -> INCOMPLETE/no-evidence-state-unknown",
                a.verdict == CrackEngine.Verdict.INCOMPLETE
                        && "no-evidence-state-unknown".equals(a.reason),
                a.verdict + "/" + a.reason);

        // ---- 反向：闸门失败入口必须拒绝 OK ----
        boolean threw = false;
        try {
            Adjudicator.gateBlocked("pw", "x#1", AttemptState.Gate.OK, 1L, "d");
        } catch (IllegalArgumentException iae) {
            threw = true;
        }
        ok("gateBlocked 拒绝 Gate.OK", threw, "应抛 IllegalArgumentException");

        // ---- 闸门失败的真实产物：UNKNOWN / NONE / reason ----
        CrackEngine.Attempt at = Adjudicator.gateBlocked("pw", "x#1",
                AttemptState.Gate.STALE_CONFIG, 7L, "d");
        ok("闸门失败 -> UNKNOWN", at.verdict == CrackEngine.Verdict.UNKNOWN, at.verdict.toString());
        ok("闸门失败 -> grade NONE（不可永久排除）",
                at.grade == EvidenceGrade.Level.NONE, String.valueOf(at.grade));
        ok("闸门失败 -> reason=gate-stale_config",
                "gate-stale_config".equals(at.reason), at.reason);
        ok("闸门失败 -> 不推进游标",
                !EvidenceGrade.advancesCursor(at.grade), "advanced");

        System.out.println();
        System.out.println("=== D4 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String tag(int m, CrackEngine.Lifecycle lc) {
        List<String> bits = new ArrayList<>();
        if ((m & 1) != 0) bits.add("notAttempted");
        if ((m & 2) != 0) bits.add("onTargetNow");
        if ((m & 4) != 0) bits.add("netidConflict");
        if ((m & 8) != 0) bits.add("channelDead");
        if ((m & 16) != 0) bits.add("statusShowsTarget");
        return "[" + String.join(",", bits) + "|" + lc + "]";
    }
}

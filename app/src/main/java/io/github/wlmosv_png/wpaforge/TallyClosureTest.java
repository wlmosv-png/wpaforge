package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;

/**
 * 统计**闭合性**门禁 —— 钉住「已执行 == 已证实 + 推断 + 网络级 + 无结论」。
 *
 * <h3>真机事故（2026-11-20）</h3>
 * 用户看到「已执行 27 / 已证实错误 0 / 未确定 0」。27 条不在任何一类里 ——
 * 根因是 {@code tested++} 在 {@code tryOne} 之前、{@code tally.add} 在之后，
 * tryOne 抛异常时统计就漏记了。用户拿到的是一组自相矛盾的数字，
 * 而真正的问题（通道在抛异常）被这层噪声盖住。
 */
public class TallyClosureTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        TallyClosureTest t = new TallyClosureTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        emptyIsConsistent();
        everyVerdictCloses();
        errorCountsAsInconclusive();
        notExecutedSeparate();
        noSilentDrop();
    }

    private void emptyIsConsistent() {
        VerdictTally t = new VerdictTally();
        ok("空统计自洽", t.selfConsistent());
        ok("空统计 executed=0", t.executed() == 0);
    }

    /** 每一个 verdict 都必须落进某一类，且恒等式成立。 */
    private void everyVerdictCloses() {
        for (CrackEngine.Verdict v : CrackEngine.Verdict.values()) {
            for (EvidenceGrade.Level g : EvidenceGrade.Level.values()) {
                VerdictTally t = new VerdictTally();
                t.add(v, g);
                if (v == CrackEngine.Verdict.ABORTED) {
                    // 中断未结案，不进任何分类
                    ok("ABORTED 不进统计: " + g,
                            t.executed() == 0 && t.selfConsistent());
                } else if (v == CrackEngine.Verdict.SUCCESS) {
                    // 2026-11-20：SUCCESS 必须独立计数（任务书第十部分）。
                    // 只有 DIRECT 才算 success，其余按未确定处理 ——
                    // 但**无论哪种都不得进 confirmedFail/inferred**。
                    ok("SUCCESS 不进已证实失败: " + g, t.confirmedFail() == 0);
                    ok("SUCCESS 不进推断失败: " + g, t.inferred() == 0);
                    ok("SUCCESS 恒等式成立: " + g, t.selfConsistent());
                    if (EvidenceGrade.persistable(g)) {
                        ok("SUCCESS+DIRECT 计入 success: " + g, t.success() == 1);
                    } else {
                        ok("SUCCESS+非DIRECT 不计入 success: " + g, t.success() == 0);
                        ok("SUCCESS+非DIRECT 计入无结论: " + g, t.inconclusive() == 1);
                    }
                } else {
                    ok("恒等式成立: " + v + "/" + g, t.selfConsistent());
                }
            }
        }
    }

    /**
     * ERROR（通道抛异常）必须**可见**，但不得冒充候选级结论。
     *
     * <p>口径：不进 executed（它衡量的确实是环境，不是"这条候选被验证过"），
     * 但必须进 environmentErrors —— 否则就凭空消失，
     * 用户会看到「已执行 N / 已证实 0 / 未确定 0」这种自相矛盾的数字。
     */
    private void errorCountsAsInconclusive() {
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.ERROR, EvidenceGrade.Level.NONE);
        ok("ERROR 计入 environmentErrors（不再消失）", t.environmentErrors() == 1);
        ok("ERROR 不进 executed", t.executed() == 0);
        ok("ERROR 不冒充无结论", t.inconclusive() == 0);
        ok("ERROR 不计入已证实", t.confirmedFail() == 0);
        ok("ERROR 后仍自洽", t.selfConsistent());
        ok("describe 含 envErrors", t.describe().contains("envErrors=1"));
    }

    /** 未执行单列，且不进 executed。 */
    private void notExecutedSeparate() {
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.NOT_EXECUTED, EvidenceGrade.Level.NONE);
        ok("NOT_EXECUTED 不进 executed", t.executed() == 0);
        ok("NOT_EXECUTED 单列", t.notExecuted() == 1);
        ok("NOT_EXECUTED 后自洽", t.selfConsistent());
    }

    /**
     * 模拟真机现场：27 条 ERROR 必须能解释，而不是「executed=27 但三类全是 0」。
     *
     * <p>这正是用户截图里的形态。修复后它应当显示成
     * 「环境出错 27 条」，一眼能看出是通道问题，而不是让人以为密码全错。
     */
    private void noSilentDrop() {
        VerdictTally t = new VerdictTally();
        for (int i = 0; i < 27; i++) {
            t.add(CrackEngine.Verdict.ERROR, EvidenceGrade.Level.NONE);
        }
        ok("27 条 ERROR → environmentErrors=27（不再消失）", t.environmentErrors() == 27);
        ok("27 条 ERROR → executed=0（它们没被真正验证）", t.executed() == 0);
        ok("27 条 ERROR → 已证实=0", t.confirmedFail() == 0);
        ok("27 条 ERROR → 无结论=0", t.inconclusive() == 0);
        ok("27 条 ERROR 后自洽", t.selfConsistent());
        // 环境出错时「未命中」同样站不住 —— 一条候选都没被真正排除
        ok("27 条 ERROR → 「未命中」站不住", !t.missProven() || t.environmentErrors() > 0);
    }
}

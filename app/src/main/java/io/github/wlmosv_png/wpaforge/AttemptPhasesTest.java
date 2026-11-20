package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptPhases;

/**
 * 七阶段状态机门禁（2026-11-20，任务书第四部分）。
 *
 * <p>核心不变量：**没有走完全部关键阶段，就不许进 DIRECT**。
 * 这把「凭什么能说这条成功」从注释变成可断言的条件。
 */
public class AttemptPhasesTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        AttemptPhasesTest t = new AttemptPhasesTest();
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
        fullPathEligible();
        missingStageNotEligible();
        anyGateFailureBlocks();
        phasesRecordedInOrder();
        summaryHasNoSecret();
        timingRecorded();
    }

    /** 全部阶段走完 → 允许 DIRECT。 */
    private void fullPathEligible() {
        AttemptPhases p = new AttemptPhases("run#1-3");
        long t = 1000L;
        for (AttemptPhases.Phase ph : AttemptPhases.Phase.values()) {
            p.begin(ph, t);
            p.finish(true, "ok", "", t + 10);
            t += 20;
        }
        ok("全阶段成功 → eligibleForDirect", p.eligibleForDirect());
        ok("全阶段成功 → 不 blocked", !p.blocked());
        ok("全阶段成功 → failedPhase 为 null", p.failedPhase() == null);
        ok("attemptId 保留", "run#1-3".equals(p.attemptId()));
    }

    /** 少任何一个关键阶段都不许 DIRECT。 */
    private void missingStageNotEligible() {
        // 只走 PRECHECK + ISOLATE，缺 SUBMIT/OBSERVE/ATTRIBUTE
        AttemptPhases p = new AttemptPhases("run#1-4");
        p.begin(AttemptPhases.Phase.PRECHECK, 1000L);
        p.finish(true, "ok", "", 1010L);
        p.begin(AttemptPhases.Phase.ISOLATE, 1020L);
        p.finish(true, "ok", "", 1030L);
        ok("缺 SUBMIT/OBSERVE/ATTRIBUTE → 不许 DIRECT", !p.eligibleForDirect());
        ok("已走完的阶段仍可查", p.passed(AttemptPhases.Phase.PRECHECK)
                && p.passed(AttemptPhases.Phase.ISOLATE));
        ok("未走的阶段 passed=false", !p.passed(AttemptPhases.Phase.OBSERVE));

        // 空状态机
        AttemptPhases empty = new AttemptPhases("run#1-5");
        ok("空状态机 → 不许 DIRECT", !empty.eligibleForDirect());
    }

    /** 任一闸门失败即 blocked，且 failedPhase 精确。 */
    private void anyGateFailureBlocks() {
        AttemptPhases p = new AttemptPhases("run#1-6");
        p.begin(AttemptPhases.Phase.PRECHECK, 1000L);
        p.finish(true, "ok", "", 1010L);
        p.begin(AttemptPhases.Phase.ISOLATE, 1020L);
        p.finish(false, "stale-config", "旧配置删不掉", 1030L);
        ok("ISOLATE 失败 → blocked", p.blocked());
        ok("ISOLATE 失败 → failedPhase=ISOLATE",
                p.failedPhase() == AttemptPhases.Phase.ISOLATE);
        ok("ISOLATE 失败 → 不许 DIRECT", !p.eligibleForDirect());
        // 即使后面阶段都成功也不许
        p.begin(AttemptPhases.Phase.SUBMIT, 1040L);
        p.finish(true, "ok", "", 1050L);
        ok("后续阶段成功也不解除 blocked", !p.eligibleForDirect());
        ok("failedPhase 仍是第一个失败", p.failedPhase() == AttemptPhases.Phase.ISOLATE);
    }

    /** 阶段必须按声明顺序被记录（顺序错乱说明接入有 bug）。 */
    private void phasesRecordedInOrder() {
        AttemptPhases p = new AttemptPhases("run#1-7");
        AttemptPhases.Phase[] want = AttemptPhases.Phase.values();
        long t = 1000L;
        for (AttemptPhases.Phase ph : want) {
            p.begin(ph, t);
            p.finish(true, "ok", "", t + 5);
            t += 10;
        }
        java.util.List<AttemptPhases.Step> steps = p.steps();
        ok("阶段数正确", steps.size() == want.length);
        boolean inOrder = true;
        for (int i = 0; i < want.length; i++) {
            if (steps.get(i).phase != want[i]) inOrder = false;
        }
        ok("阶段顺序与声明一致", inOrder);
    }

    /** 诊断摘要绝不能含口令。 */
    private void summaryHasNoSecret() {
        AttemptPhases p = new AttemptPhases("run#1-8");
        p.begin(AttemptPhases.Phase.PRECHECK, 1000L);
        p.finish(true, "ok", "", 1010L);
        p.begin(AttemptPhases.Phase.ISOLATE, 1020L);
        p.finish(false, "stale-config", "", 1030L);
        String s = p.summary();
        ok("摘要含 attemptId", s.contains("run#1-8"));
        ok("摘要含阶段名", s.contains("预检") || s.contains("PRECHECK"));
        ok("摘要含 BLOCKED", s.contains("BLOCKED"));
        ok("摘要不含 8 位以上纯数字（口令形态）",
                !s.matches(".*\\b\\d{8,}\\b.*"));
    }

    /** 时间戳必须真实记录（不是 0）。 */
    private void timingRecorded() {
        AttemptPhases p = new AttemptPhases("run#1-9");
        AttemptPhases.Step s = p.begin(AttemptPhases.Phase.PRECHECK, 5000L);
        ok("begin 记录进入时刻", s.startedAtMs == 5000L);
        ok("未结束时 elapsed=0", s.elapsedMs() == 0L);
        p.finish(true, "ok", "", 5042L);
        ok("finish 记录结束时刻", s.endedAtMs == 5042L);
        ok("elapsed 正确", s.elapsedMs() == 42L);
        ok("totalMs 正确", p.totalMs() == 42L);
    }
}

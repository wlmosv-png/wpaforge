package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Adjudicator;
import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.AttributionPrecondition;
import io.github.wlmosv_png.wpaforge.audit.Evidence;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.SuccessRule;

import java.util.ArrayList;
import java.util.List;

/**
 * **反证测试**：旧连接 / 自动重连 / 延迟回调场景下，错误密码绝不能变 SUCCESS。
 *
 * <p>任务书第五阶段明确要求「必须加入反证测试：故意让错误密码处于旧连接或
 * 自动重连环境，验证任何成功路径都会被拒绝」。
 *
 * <p>本类刻意构造「协议层看起来完全像成功」的输入，然后逐层证明被拦住：
 * <ol>
 *   <li>{@link SuccessRule#assess} 的 netId 冲突检查</li>
 *   <li>{@link AttributionPrecondition} 的前提降级</li>
 *   <li>{@link Adjudicator} 的 AT3 门槛</li>
 * </ol>
 */
public class OldConnectionCounterTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        OldConnectionCounterTest t = new OldConnectionCounterTest();
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
        protocolLooksLikeSuccess();
        netIdConflictBlocks();
        preconditionBlocks();
        staleGateBlocks();
        autoReconnectScenario();
        delayedCallbackScenario();
    }

    /** 先确认：三条件全满足时 SuccessRule 确实会说「这是成功帧」。 */
    private void protocolLooksLikeSuccess() {
        SuccessRule.Assessment a = SuccessRule.assess(
                "TargetAP", "TargetAP", "COMPLETED", "192.168.1.5", 12, 12);
        ok("三条件 + netId 一致 → SUCCESS", a.verdict == CrackEngine.Verdict.SUCCESS);
        ok("证据标签含 netId", a.evidence.contains("netId"));
    }

    /** 关键反证 ①：netId 与本次配置不一致 → 必须 UNKNOWN，不是 SUCCESS。 */
    private void netIdConflictBlocks() {
        // 场景：系统回连了**旧的同名配置**（netId=7），而本次建的是 netId=12
        SuccessRule.Assessment a = SuccessRule.assess(
                "TargetAP", "TargetAP", "COMPLETED", "192.168.1.5", 7, 12);
        ok("netId 冲突 → 不是 SUCCESS", a.verdict != CrackEngine.Verdict.SUCCESS);
        ok("netId 冲突 → UNKNOWN", a.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("netId 冲突 → 理由码 netid-conflict", "netid-conflict".equals(a.reason));
    }

    /** 关键反证 ②：前提不足时 AT3 被降级，进不了 DIRECT。 */
    private void preconditionBlocks() {
        // 净检失败（旧配置删不掉）
        AttributionPrecondition stale = AttributionPrecondition.staleConfig();
        ok("净检失败 → AT3 降 AT2",
                stale.capAttribution(Evidence.Attribution.AT3) == Evidence.Attribution.AT2);

        // 断开失败（上一条还连着）
        AttributionPrecondition notDropped = AttributionPrecondition.linkNotDropped();
        ok("断开失败 → AT3 降 AT2",
                notDropped.capAttribution(Evidence.Attribution.AT3) == Evidence.Attribution.AT2);
    }

    /** 关键反证 ③：Gate 不是 OK 时，即使证据是 AT3 也不采信。 */
    private void staleGateBlocks() {
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, "run#1-3", 1000L));
        AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.STALE_CONFIG, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("STALE_CONFIG 闸门 → 不是 SUCCESS",
                r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("STALE_CONFIG 闸门 → 不可持久化", !EvidenceGrade.persistable(r.grade));
    }

    /**
     * 自动重连场景：系统自己连回了目标（旧密码仍在），此时错误候选被提交。
     *
     * <p>真实链条上，这种情况会先被 `forgetTargetByShell` + 回读挡住
     * （`STALE_CONFIG`），或者在 `baselineDecision` 判 `DECIDE_LINKED` 后
     * 走 `disconnectAndVerify` 断开。任一步失败 → gateBlocked。
     * 这里验证「即使漏到 Adjudicator，AT2 也不会成功」。
     */
    private void autoReconnectScenario() {
        // 系统自动重连产生的 CONNECTED，归属只能到 AT2（无法绑定本次配置）
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT2, "run#1-3", 1000L));
        AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.OK, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("自动重连(AT2) → UNKNOWN", r.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("自动重连(AT2) → grade NONE", r.grade == EvidenceGrade.Level.NONE);
        ok("自动重连(AT2) → 不触发成功副作用",
                r.verdict != CrackEngine.Verdict.SUCCESS
                        && !EvidenceGrade.persistable(r.grade));
    }

    /**
     * 延迟回调场景：上一条（正确密码）的回调晚到，被当成这一条的证据。
     *
     * <p>防护是 attemptId 严格相等 —— 证据属于 run#1-2，当前是 run#1-3。
     */
    private void delayedCallbackScenario() {
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, "run#1-2", 1000L));   // 上一条的
        AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.OK, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("延迟回调 → 证据被丢弃，不是 SUCCESS",
                r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("延迟回调 → 不可持久化", !EvidenceGrade.persistable(r.grade));

        // 对照：同一条的证据才算
        List<Evidence> same = new ArrayList<>();
        same.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, "run#1-3", 1000L));
        Adjudicator.Result r2 = Adjudicator.adjudicate(st, same);
        ok("同条证据 → SUCCESS（对照组）", r2.verdict == CrackEngine.Verdict.SUCCESS);
    }
}

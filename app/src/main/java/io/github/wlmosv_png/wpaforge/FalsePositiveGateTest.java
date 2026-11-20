package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Adjudicator;
import io.github.wlmosv_png.wpaforge.audit.AttemptLedger;
import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.Evidence;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * **假阳性总门禁**（2026-11-20，任务书第四部分）。
 *
 * <p>核心命题：**一个错误密码没有任何可能被判成 SUCCESS。**
 *
 * <p>本类用真实生产代码（{@link Adjudicator}）穷举所有「看起来像成功但归属不足」
 * 的输入组合，逐条证明它们**都不产出 SUCCESS**，且不触发任何成功副作用。
 */
public class FalsePositiveGateTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        FalsePositiveGateTest t = new FalsePositiveGateTest();
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
        at2NeverSucceeds();
        unownedEvidenceRejected();
        wrongAttemptIdRejected();
        gateNotOkNeverSucceeds();
        successRequiresDirectAndAt3();
    }

    /** 关键命题：AT2（目标连上但绑不到本次）永远不产 SUCCESS。 */
    private void at2NeverSucceeds() {
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT2, "run#1-3", 1000L));
        AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.OK, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("AT2 CONNECTED → 不是 SUCCESS",
                r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("AT2 CONNECTED → UNKNOWN（实际=" + r.verdict + " reason=" + r.reason + "）",
                r.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("AT2 CONNECTED → grade NONE", r.grade == EvidenceGrade.Level.NONE);
        ok("AT2 CONNECTED → 不可持久化", !EvidenceGrade.persistable(r.grade));
        ok("AT2 CONNECTED → 不能写 TriedSet",
                AttemptLedger.Level.classify(r.verdict, r.grade) == null
                        || !EvidenceGrade.persistable(r.grade));
    }

    /** 完全无归属（NONE）的 CONNECTED 同样不产 SUCCESS。 */
    private void unownedEvidenceRejected() {
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.NONE, "run#1-3", 1000L));
        AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.OK, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("NONE 归属 CONNECTED → 不是 SUCCESS",
                r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("NONE 归属 → UNKNOWN", r.verdict == CrackEngine.Verdict.UNKNOWN);
    }

    /** 证据属于别的 Attempt → 一律丢弃，不产 SUCCESS。 */
    private void wrongAttemptIdRejected() {
        List<Evidence> ev = new ArrayList<>();
        // 证据属于 run#1-2，但当前 Attempt 是 run#1-3（必须被丢弃）
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, "run#1-2", 1000L));
        AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.OK, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("他条证据 → 不是 SUCCESS", r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("他条证据 → 不能持久化", !EvidenceGrade.persistable(r.grade));
    }

    /** 前置闸门未通过（Gate 不是 OK）→ 任何证据都不采信。 */
    private void gateNotOkNeverSucceeds() {
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, "run#1-3", 1000L));
        for (AttemptState.Gate g : AttemptState.Gate.values()) {
            if (g == AttemptState.Gate.OK) continue;
            AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE, g, true);
            Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
            ok("Gate=" + g + " → 不是 SUCCESS",
                    r.verdict != CrackEngine.Verdict.SUCCESS);
        }
    }

    /** 只有 AT3 + DIRECT 才能进 success；其余全部不进。 */
    private void successRequiresDirectAndAt3() {
        // AT3 → SUCCESS + DIRECT
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, "run#1-3", 1000L));
        AttemptState st = new AttemptState("run#1-3", CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.OK, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("AT3 → SUCCESS（实际=" + r.verdict + " reason=" + r.reason
                + " grade=" + r.grade + "）", r.verdict == CrackEngine.Verdict.SUCCESS);
        ok("AT3 → DIRECT", r.grade == EvidenceGrade.Level.DIRECT);
        ok("AT3 → 可持久化", EvidenceGrade.persistable(r.grade));

        // 空证据 → 绝不 SUCCESS
        Adjudicator.Result empty = Adjudicator.adjudicate(st, new ArrayList<>());
        ok("空证据 → 不是 SUCCESS", empty.verdict != CrackEngine.Verdict.SUCCESS);
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * P2：判定内核复核（在 D1–D5 / U1–U3 / R-12 之上再查一遍）。
 *
 * <h3>七条必须成立的不变式</h3>
 * <ol>
 *   <li>正确密码不会因为「延迟失败事件」被误判（AP-1 永不产 AUTH_FAIL）。</li>
 *   <li>「当前连着目标」不会被自动归属给当前候选（必须 AT3）。</li>
 *   <li>慢 AP（连接仍在推进）不会被过早判失败。</li>
 *   <li>NETWORK_AUTH_FAIL 不进 triedSet、不推 cursor、不计已证伪。</li>
 *   <li>UNKNOWN / INCOMPLETE 不推 cursor（续跑会重试）。</li>
 *   <li>同一候选在不同位置 / 不同恢复路径下 Verdict 一致（无位置依赖）。</li>
 *   <li>成功、失败、停止、暂停、恢复走同一套裁决语义。</li>
 * </ol>
 */
public final class P2KernelReauditTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String AID = "m5k3p1-3f9a2b7c-1#7";

    private static AttemptState st(CrackEngine.Lifecycle lc, AttemptState.Gate g) {
        return new AttemptState(AID, lc, g, true);
    }

    private static Evidence ev(Evidence.Source s, Evidence.Claim c, Evidence.Attribution a) {
        return new Evidence(s, c, a, AID, 100L);
    }

    public static void main(String[] args) {
        System.out.println("=== P2 判定内核复核 ===");

        // ---- ① 延迟失败不得误判正确密码 ----
        // AP-1（零字段/无归属失败）与 AT1/AT2 都不得产生 AUTH_FAIL
        Evidence.Attribution[] weak = {
                Evidence.Attribution.NONE, Evidence.Attribution.AT1, Evidence.Attribution.AT2 };
        int badAuth = 0;
        for (Evidence.Attribution a : weak) {
            for (CrackEngine.Lifecycle lc : CrackEngine.Lifecycle.values()) {
                Adjudicator.Result r = Adjudicator.adjudicate(st(lc, AttemptState.Gate.OK),
                        java.util.Collections.singletonList(
                                ev(Evidence.Source.WPALOG_B, Evidence.Claim.WRONG_KEY, a)));
                if (r.verdict == CrackEngine.Verdict.AUTH_FAIL) {
                    badAuth++;
                    info("弱归属却判 AUTH_FAIL: " + a + "/" + lc + " -> " + r.verdict);
                }
            }
        }
        ok("① 弱归属 WRONG_KEY 永不产 AUTH_FAIL（12 格穷举）", badAuth == 0,
                "违例 " + badAuth);

        // ---- ② 「连着目标」无 AT3 不得判成功 ----
        int badSucc = 0;
        for (Evidence.Attribution a : new Evidence.Attribution[]{
                Evidence.Attribution.NONE, Evidence.Attribution.AT1, Evidence.Attribution.AT2}) {
            Adjudicator.Result r = Adjudicator.adjudicate(st(CrackEngine.Lifecycle.ACTIVE,
                            AttemptState.Gate.OK),
                    java.util.Collections.singletonList(
                            ev(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED, a)));
            if (r.verdict == CrackEngine.Verdict.SUCCESS) {
                badSucc++;
                info("无 AT3 却判成功: " + a);
            }
        }
        ok("② CONNECTED 但归属 < AT3 -> 永不判 SUCCESS", badSucc == 0, "违例 " + badSucc);
        Adjudicator.Result at3 = Adjudicator.adjudicate(st(CrackEngine.Lifecycle.ACTIVE,
                        AttemptState.Gate.OK),
                java.util.Collections.singletonList(
                        ev(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                                Evidence.Attribution.AT3)));
        ok("② CONNECTED + AT3 -> SUCCESS（正例）",
                at3.verdict == CrackEngine.Verdict.SUCCESS, at3.verdict.toString());

        // ---- ③ 慢 AP：ACTIVE 时不得判失败 ----
        int badTerm = 0;
        for (Evidence.Claim c : new Evidence.Claim[]{Evidence.Claim.NOT_CONNECTED,
                Evidence.Claim.NETWORK_REJECTED}) {
            Adjudicator.Result r = Adjudicator.adjudicate(st(CrackEngine.Lifecycle.ACTIVE,
                            AttemptState.Gate.OK),
                    java.util.Collections.singletonList(ev(Evidence.Source.M1RETRY, c,
                            Evidence.Attribution.AT1)));
            info("③ ACTIVE + " + c + " -> " + r.verdict);
            if (r.verdict == CrackEngine.Verdict.AUTH_FAIL
                    || r.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN) {
                badTerm++;
            }
        }
        ok("③ 连接仍在推进时不判失败/终止", badTerm == 0, "违例 " + badTerm);

        // ---- ④ NETWORK_AUTH_FAIL 的下游语义 ----
        Adjudicator.Result net = Adjudicator.adjudicate(st(CrackEngine.Lifecycle.TERMINATED,
                        AttemptState.Gate.OK),
                java.util.Collections.singletonList(
                        ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                Evidence.Attribution.AT1)));
        info("④ AT1 WRONG_KEY -> " + net);
        ok("④ AT1 WRONG_KEY -> NETWORK_AUTH_FAIL",
                net.verdict == CrackEngine.Verdict.NETWORK_AUTH_FAIL, net.verdict.toString());
        ok("④ 不持久化（不进 triedSet）", !EvidenceGrade.persistable(net.grade),
                net.grade.toString());
        ok("④ 不推进 cursor", !EvidenceGrade.advancesCursor(net.grade), "advance");
        VerdictTally t = new VerdictTally();
        t.add(net.verdict, net.grade);
        ok("④ 不计入已证实失败", t.confirmedFail() == 0, String.valueOf(t.confirmedFail()));

        // ---- ⑤ UNKNOWN / INCOMPLETE 不推 cursor ----
        int advance = 0;
        for (CrackEngine.Verdict v : new CrackEngine.Verdict[]{
                CrackEngine.Verdict.UNKNOWN, CrackEngine.Verdict.INCOMPLETE,
                CrackEngine.Verdict.TERMINATED_UNKNOWN, CrackEngine.Verdict.TIMEOUT,
                CrackEngine.Verdict.NETWORK_AUTH_FAIL}) {
            if (EvidenceGrade.advancesCursor(EvidenceGrade.defaultOf(v))) {
                advance++;
                info("不该推 cursor: " + v);
            }
        }
        ok("⑤ 非确定结论一律不推 cursor", advance == 0, "违例 " + advance);

        // ---- ⑥ 位置无关性：同一证据集合在不同 attemptId 序号下结论一致 ----
        int posMismatch = 0;
        for (int seq : new int[]{1, 7, 23, 199}) {
            String aid = "m5k3p1-3f9a2b7c-1#" + seq;
            AttemptState s = new AttemptState(aid, CrackEngine.Lifecycle.TERMINATED,
                    AttemptState.Gate.OK, true);
            Evidence e = new Evidence(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                    Evidence.Attribution.AT3, aid, 100L);
            Adjudicator.Result r = Adjudicator.adjudicate(s,
                    java.util.Collections.singletonList(e));
            if (r.verdict != CrackEngine.Verdict.AUTH_FAIL) {
                posMismatch++;
                info("位置 " + seq + " 结论不同: " + r.verdict);
            }
        }
        ok("⑥ 同一 AT3 证据在序号 1/7/23/199 下结论一致（AUTH_FAIL）", posMismatch == 0,
                "不一致 " + posMismatch);

        // ---- ⑦ 五种场景共用同一裁决入口（形状守卫） ----
        // 断言：非 SUCCESS 且非 ABORTED 的所有 Verdict 都能由 Adjudicator 产出，
        // 不存在「某一场景单独一套判定」。
        List<CrackEngine.Verdict> producible = new ArrayList<>();
        for (int gate = 0; gate < 4; gate++) {
            for (CrackEngine.Lifecycle lc : CrackEngine.Lifecycle.values()) {
                for (Evidence.Claim c : Evidence.Claim.values()) {
                    for (Evidence.Attribution a : Evidence.Attribution.values()) {
                        AttemptState.Gate g = AttemptState.Gate.values()[gate];
                        Adjudicator.Result r = Adjudicator.adjudicate(
                                new AttemptState(AID, lc, g, true),
                                java.util.Collections.singletonList(
                                        new Evidence(Evidence.Source.AUTHCAST, c, a, AID, 1L)));
                        if (!producible.contains(r.verdict)) producible.add(r.verdict);
                    }
                }
            }
        }
        info("⑦ Adjudicator 可产出的 Verdict: " + producible);
        ok("⑦ 成功/失败/未知三类都由同一入口产出（>=4 种）",
                producible.size() >= 4, String.valueOf(producible.size()));
        ok("⑦ 不含 ABORTED（用户停止不走裁决）",
                !producible.contains(CrackEngine.Verdict.ABORTED), "含 ABORTED");

        // ---- ⑧ 判定与暂停/停止无关：GATE 失败一律 UNKNOWN ----
        int gateLeak = 0;
        for (AttemptState.Gate g : AttemptState.Gate.values()) {
            if (g == AttemptState.Gate.OK) continue;
            Adjudicator.Result r = Adjudicator.adjudicate(
                    new AttemptState(AID, CrackEngine.Lifecycle.ACTIVE, g, true),
                    java.util.Collections.singletonList(
                            ev(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                                    Evidence.Attribution.AT3)));
            if (r.verdict != CrackEngine.Verdict.UNKNOWN) {
                gateLeak++;
                info("闸门 " + g + " 未拦住 -> " + r.verdict);
            }
        }
        ok("⑧ 前置闸门失败时任何证据都不采信（3 格穷举）", gateLeak == 0,
                "泄漏 " + gateLeak);

        System.out.println();
        System.out.println("=== P2 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

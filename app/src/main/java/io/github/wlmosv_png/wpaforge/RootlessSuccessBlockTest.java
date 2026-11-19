package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Adjudicator;
import io.github.wlmosv_png.wpaforge.audit.AttemptLedger;
import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.Evidence;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.TriedSet;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;
import io.github.wlmosv_png.wpaforge.cap.SpecifierVerdict;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;

/**
 * **成功链封锁**门禁（2026-11-18 定稿）。
 *
 * <h3>为什么需要这个类</h3>
 * 无 Root 在线验证只拿到「系统连上了一条符合请求条件的网络」，
 * 拿不到「这次握手是用本条候选的 PSK 完成的」。曾经的实现给它
 * {@code Verdict.SUCCESS} + {@code grade=INFERRED}，而
 * {@code Attempt.isSuccess()} 只判 {@code verdict==SUCCESS} ——
 * 于是界面照样显示「密码正确」。本类逐条钉住修复后的真实业务行为，
 * **全部走生产类**（{@code Adjudicator} / {@code EvidenceGrade} /
 * {@code VerdictTally} / {@code AttemptLedger} / {@code TriedSet} / {@code Attempt}），
 * 不是源码字符串检查。
 */
public class RootlessSuccessBlockTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        RootlessSuccessBlockTest t = new RootlessSuccessBlockTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private void ok(String name, boolean cond) {
        if (cond) pass++;
        else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        t1_onAvailableNotSuccess();
        t2_inferredNoConfirmedBy();
        t3_inferredNoSuccessStats();
        t4_inferredNoCursorAdvance();
        t5_inferredNoSuccessPersistence();
        t6_rootDirectStillWorks();
        t7_unknownNotInTriedSet();
        t8_unknownNoPermanentExclusion();
        t9_successImpliesDirect();
        t10_triedSetIsolation();
    }

    // ---------------------------------------------------------------- 1

    /** Specifier 的 onAvailable 不产生成功终态。 */
    private void t1_onAvailableNotSuccess() {
        SpecifierVerdict.Decision d = SpecifierVerdict.decide(true, false, false, false, false, false);
        CrackEngine.Attempt a = attemptOf(d, "run#1");

        ok("onAvailable → 不是 SUCCESS",
                a.verdict != CrackEngine.Verdict.SUCCESS);
        ok("onAvailable → UNKNOWN", a.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("onAvailable → isSuccess() 为 false", !a.isSuccess());
        ok("onAvailable → 等级 NONE", a.grade == EvidenceGrade.Level.NONE);
        ok("onAvailable → 归类码 on-target-unattributed",
                "on-target-unattributed".equals(a.reason));
        ok("onAvailable → 决策层也不认成功", !d.isSuccess() && d.isConnectedUnattributed());
    }

    // ---------------------------------------------------------------- 2

    /**
     * INFERRED 的 Attempt 不触发 confirmedBy。
     *
     * <p>两个层面都验：① 无 Root 真实路径的 Attempt 本来就 {@code isSuccess()==false}，
     * 所以 {@code CrackService} 里
     * {@code if (a.isSuccess() && confirmedBy != null && !isEmpty)} 这一支不可达；
     * ② 即便有人手工造出「SUCCESS + INFERRED」，结构性兜底也会让它不算成功。
     */
    private void t2_inferredNoConfirmedBy() {
        CrackEngine.Attempt real = attemptOf(
                SpecifierVerdict.decide(true, false, false, false, false, false), "run#2");
        ok("真实路径 isSuccess()=false → confirmedBy 分支不可达", !real.isSuccess());

        // 手工构造「SUCCESS + INFERRED」：模拟上一版那半修
        CrackEngine.Attempt synthetic = synthAttempt(CrackEngine.Verdict.SUCCESS,
                EvidenceGrade.Level.INFERRED, "specifier");
        ok("合成 SUCCESS+INFERRED：isSuccess() 必须为 false", !synthetic.isSuccess());
        ok("合成 SUCCESS+INFERRED：confirmedBy 存在但不会被采用",
                !synthetic.confirmedBy.isEmpty() && !synthetic.isSuccess());
    }

    // ---------------------------------------------------------------- 3

    /** INFERRED 不增加成功统计。 */
    private void t3_inferredNoSuccessStats() {
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.INFERRED);
        ok("SUCCESS+INFERRED 不计入已证实失败", t.confirmedFail() == 0);
        ok("SUCCESS+INFERRED 不计入已执行", t.executed() == 0);
        ok("SUCCESS+INFERRED 不计入无结论", t.inconclusive() == 0);

        // 推理型失败单独计数，绝不冒充「已证实错误」
        VerdictTally t2 = new VerdictTally();
        t2.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED);
        ok("AUTH_FAIL+INFERRED 不增加已证实失败", t2.confirmedFail() == 0);
        ok("AUTH_FAIL+INFERRED 记入推断失败", t2.inferred() == 1);

        // 无 Root 连上走 UNKNOWN → 计为「无结论」，不是成功也不是失败
        VerdictTally t3 = new VerdictTally();
        t3.add(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE);
        ok("无 Root 连上不计成功统计（confirmedFail=0）", t3.confirmedFail() == 0);
        ok("无 Root 连上计入无结论", t3.inconclusive() == 1);
    }

    // ---------------------------------------------------------------- 4

    /** INFERRED 不推进已验证进度。 */
    private void t4_inferredNoCursorAdvance() {
        ok("INFERRED 不推进游标",
                !EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED));
        ok("NONE 不推进游标",
                !EvidenceGrade.advancesCursor(EvidenceGrade.Level.NONE));
        ok("只有 DIRECT 推进游标",
                EvidenceGrade.advancesCursor(EvidenceGrade.Level.DIRECT));

        CrackEngine.Attempt real = attemptOf(
                SpecifierVerdict.decide(true, false, false, false, false, false), "run#4");
        ok("无 Root 连上：Attempt 等级不推进游标",
                !EvidenceGrade.advancesCursor(real.grade));
    }

    // ---------------------------------------------------------------- 5

    /** INFERRED 不写入成功持久化。 */
    private void t5_inferredNoSuccessPersistence() {
        ok("INFERRED 不可持久化", !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED));
        ok("NONE 不可持久化", !EvidenceGrade.persistable(EvidenceGrade.Level.NONE));
        ok("DIRECT 可持久化", EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT));

        // 账本分类：SUCCESS 不进历史，INFERRED 的失败也不进「已确认错误」档
        ok("账本不记 SUCCESS",
                AttemptLedger.Level.classify(CrackEngine.Verdict.SUCCESS,
                        EvidenceGrade.Level.DIRECT) == null);
        ok("账本不把 AUTH_FAIL+INFERRED 记成已确认错误",
                AttemptLedger.Level.classify(CrackEngine.Verdict.AUTH_FAIL,
                        EvidenceGrade.Level.INFERRED) != AttemptLedger.Level.CONFIRMED_WRONG);

        // 结构性兜底：SUCCESS+INFERRED 的 Attempt 不算成功 → 不会被当作命中持久化
        CrackEngine.Attempt synthetic = synthAttempt(CrackEngine.Verdict.SUCCESS,
                EvidenceGrade.Level.INFERRED, "specifier");
        ok("合成 SUCCESS+INFERRED 不会被当成命中", !synthetic.isSuccess());
    }

    // ---------------------------------------------------------------- 6

    /** Root DIRECT 成功路径正常。 */
    private void t6_rootDirectStillWorks() {
        Adjudicator.Result r = Adjudicator.adjudicate(
                new AttemptState("run#R", CrackEngine.Lifecycle.TERMINATED,
                        AttemptState.Gate.OK, true),
                Collections.singletonList(new Evidence(Evidence.Source.SUCCESS_STATUS,
                        Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, "run#R", 1000L)));
        ok("Root CONNECTED+AT3 → SUCCESS", r.verdict == CrackEngine.Verdict.SUCCESS);
        ok("Root CONNECTED+AT3 → DIRECT", r.grade == EvidenceGrade.Level.DIRECT);

        CrackEngine.Attempt a = Adjudicator.toAttempt("12345678", r, 100L, "", "status");
        ok("Root 成功 Attempt.isSuccess() 为 true", a.isSuccess());
        ok("Root 成功可持久化", EvidenceGrade.persistable(a.grade));
        ok("Root 成功推进游标", EvidenceGrade.advancesCursor(a.grade));
    }

    // ---------------------------------------------------------------- 7

    /** UNKNOWN 不进入 TriedSet。 */
    private void t7_unknownNotInTriedSet() {
        CrackEngine.Attempt a = attemptOf(
                SpecifierVerdict.decide(true, false, false, false, false, false), "run#7");
        boolean wouldWrite = !a.isSuccess() && EvidenceGrade.persistable(a.grade);
        ok("无 Root 连上不满足 TriedSet 写入条件", !wouldWrite);

        CrackEngine.Attempt u = attemptOf(
                SpecifierVerdict.decide(false, true, false, false, false, false), "run#7b");
        ok("onUnavailable 不满足 TriedSet 写入条件",
                !(!u.isSuccess() && EvidenceGrade.persistable(u.grade)));
        ok("onUnavailable 不是 AUTH_FAIL", u.verdict != CrackEngine.Verdict.AUTH_FAIL);
    }

    // ---------------------------------------------------------------- 8

    /** UNKNOWN 不永久排除候选。 */
    private void t8_unknownNoPermanentExclusion() {
        CrackEngine.Attempt a = attemptOf(
                SpecifierVerdict.decide(true, false, false, false, false, false), "run#8");
        ok("UNKNOWN 不可持久化（不会永久排除）",
                !EvidenceGrade.persistable(a.grade));
        ok("UNKNOWN 不推进游标（下次还会试）",
                !EvidenceGrade.advancesCursor(a.grade));
        ok("UNKNOWN 不进账本「已确认错误」档",
                AttemptLedger.Level.classify(a.verdict, a.grade)
                        != AttemptLedger.Level.CONFIRMED_WRONG);
    }

    // ---------------------------------------------------------------- 9

    /**
     * 任何 SUCCESS 都必然带 DIRECT（延迟成功场景同理）。
     *
     * <p>把 CONNECTED 主张在四种归属下全跑一遍：只要出 SUCCESS，等级就必须是 DIRECT；
     * 不是 AT3 的一律不得成为 SUCCESS。这条同时守住「延迟成功测试」依赖的不变式。
     */
    private void t9_successImpliesDirect() {
        int successCount = 0, bad = 0;
        for (Evidence.Attribution at : Evidence.Attribution.values()) {
            Adjudicator.Result r = Adjudicator.adjudicate(
                    new AttemptState("run#9", CrackEngine.Lifecycle.TERMINATED,
                            AttemptState.Gate.OK, true),
                    Collections.singletonList(new Evidence(Evidence.Source.SUCCESS_STATUS,
                            Evidence.Claim.CONNECTED, at, "run#9", 1000L)));
            if (r.verdict == CrackEngine.Verdict.SUCCESS) {
                successCount++;
                if (r.grade != EvidenceGrade.Level.DIRECT) bad++;
            } else if (at == Evidence.Attribution.AT3) {
                bad++;
            }
        }
        ok("CONNECTED 只在不低于 AT3 时成 SUCCESS", successCount >= 1);
        ok("**任何 SUCCESS 都是 DIRECT**", bad == 0);
        ok("低于 AT3 的 CONNECTED 不得成为 SUCCESS",
                Adjudicator.adjudicate(
                        new AttemptState("run#9b", CrackEngine.Lifecycle.TERMINATED,
                                AttemptState.Gate.OK, true),
                        Collections.singletonList(new Evidence(Evidence.Source.SUCCESS_STATUS,
                                Evidence.Claim.CONNECTED, Evidence.Attribution.AT2,
                                "run#9b", 1000L)))
                        .verdict != CrackEngine.Verdict.SUCCESS);
    }

    // ---------------------------------------------------------------- 10

    /** Rootless 与 Root 的 TriedSet 互相隔离。 */
    private void t10_triedSetIsolation() {
        String root = TriedSet.targetKey("28:93:7d:41:cc:39", "B212", "root");
        String rootless = TriedSet.targetKey("28:93:7d:41:cc:39", "B212", "rootless");
        String legacy = TriedSet.targetKey("28:93:7d:41:cc:39", "B212");
        ok("Root 键与旧键一致（历史不丢）", root.equals(legacy));
        ok("无 Root 键与 Root 键不同", !rootless.equals(root));
        ok("无 Root 键带模式前缀", rootless.startsWith("rootless~"));
        ok("剥前缀可还原", TriedSet.stripScope(rootless).equals(legacy));
    }

    // ---------------------------------------------------------------- 工具

    /** 走**生产**裁决链把决策落成 Attempt（与 tryOneSpecifier 同一入口）。 */
    private CrackEngine.Attempt attemptOf(SpecifierVerdict.Decision d, String attemptId) {
        AttemptState st = new AttemptState(attemptId, CrackEngine.Lifecycle.TERMINATED,
                SpecifierVerdict.gateOf(d), d.netIdKnown);
        List<Evidence> evs = d.claim == null
                ? Collections.<Evidence>emptyList()
                : Collections.singletonList(new Evidence(d.source, d.claim, d.attribution,
                        attemptId, 1000L));
        Adjudicator.Result r = Adjudicator.adjudicate(st, evs);
        return Adjudicator.toAttempt("12345678", r, 100L, "", "specifier");
    }

    /**
     * 手工构造任意 verdict + grade 的 Attempt（反射，仅测试用）。
     *
     * <p>目的是直接验证结构性兜底：即便有人造出「SUCCESS + INFERRED」，
     * {@code isSuccess()} 也必须为 false。
     */
    private CrackEngine.Attempt synthAttempt(CrackEngine.Verdict v,
                                             EvidenceGrade.Level g, String by) {
        try {
            Constructor<CrackEngine.Attempt> c = CrackEngine.Attempt.class
                    .getDeclaredConstructor(String.class, CrackEngine.Verdict.class,
                            long.class, String.class, String.class);
            c.setAccessible(true);
            CrackEngine.Attempt a = c.newInstance("12345678", v, 100L, "detail", by);
            a.grade = g;
            return a;
        } catch (Throwable t) {
            throw new IllegalStateException("无法构造 Attempt: " + t, t);
        }
    }
}

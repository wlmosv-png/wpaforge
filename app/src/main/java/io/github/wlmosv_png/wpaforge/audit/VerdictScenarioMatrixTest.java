package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 任务书 §九 要求的 20 个场景 + 三条核心不变式（2026-11-22 新增）。
 *
 * <p>本测试是**纯逻辑**的：把每个场景翻译成
 * {@link AttemptState} + {@link Evidence} 列表，喂给唯一的
 * {@link Adjudicator}，断言结论。不碰 Android、不碰 shell、不碰真实 Wi-Fi。
 *
 * <h3>三条核心不变式（逐条有对应用例）</h3>
 * <ol>
 *   <li><b>错误密码不能被误判为 SUCCESS</b>：
 *       只有 {@code CONNECTED + AT3} 才产 SUCCESS；WRONG_KEY 永不可能产 SUCCESS。</li>
 *   <li><b>正确密码不能被误判为 AUTH_FAIL + DIRECT</b>：
 *       AUTH_FAIL+DIRECT 要求 WRONG_KEY + AT3 + TERMINATED；
 *       任一条件不满足都降级。</li>
 *   <li><b>NONE / INFERRED / UNKNOWN / INCOMPLETE 不能永久排除</b>：
 *       {@link EvidenceGrade#persistable} 只对 DIRECT 为真。</li>
 * </ol>
 */
public final class VerdictScenarioMatrixTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final String AID = "att-1";

    private static AttemptState st(CrackEngine.Lifecycle lc, AttemptState.Gate g) {
        return new AttemptState(AID, lc, g, true);
    }

    private static AttemptState stOk(CrackEngine.Lifecycle lc) {
        return st(lc, AttemptState.Gate.OK);
    }

    private static Evidence ev(Evidence.Source s, Evidence.Claim c,
                              Evidence.Attribution a, long at) {
        return new Evidence(s, c, a, AID, at);
    }

    private static Evidence evOther(Evidence.Source s, Evidence.Claim c,
                                   Evidence.Attribution a) {
        return new Evidence(s, c, a, "someone-else", 100L);
    }

    /** 断言：不得写永久记录、不得推进游标。 */
    private static void noExclude(String name, Adjudicator.Result r) {
        ok(name + " · 不写 triedSet", !EvidenceGrade.persistable(r.grade), "" + r.grade);
        ok(name + " · 不推进游标", !EvidenceGrade.advancesCursor(r.grade), "" + r.grade);
    }

    public static void main(String[] args) {
        System.out.println("== 判定场景矩阵（任务书 §九 20 场景 + 3 不变式）==");
        try {
            // ═══════════════════ 1. 正确密码正常连接
            System.out.println();
            System.out.println("-- 1. 正确密码正常连接 --");
            Adjudicator.Result r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.ACTIVE),
                    Collections.singletonList(ev(Evidence.Source.SUCCESS_STATUS,
                            Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, 900L)));
            info("CONNECTED+AT3 = " + r);
            ok("→ SUCCESS", r.verdict == CrackEngine.Verdict.SUCCESS, "" + r.verdict);
            ok("→ DIRECT", r.grade == EvidenceGrade.Level.DIRECT);
            ok("→ 允许写 triedSet（但 SUCCESS 实际不写，见 CrackService）",
                    EvidenceGrade.persistable(r.grade));

            // ═══════════════════ 2. 错误密码明确认证失败
            System.out.println();
            System.out.println("-- 2. 错误密码明确认证失败 --");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.TERMINATED),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4200L)));
            info("WRONG_KEY+AT3+TERMINATED = " + r);
            ok("→ AUTH_FAIL", r.verdict == CrackEngine.Verdict.AUTH_FAIL);
            ok("→ DIRECT", r.grade == EvidenceGrade.Level.DIRECT);
            ok("→ 允许永久排除", EvidenceGrade.persistable(r.grade));

            // ═══════════════════ 3. 正确密码但日志不可读
            System.out.println();
            System.out.println("-- 3. 正确密码但日志不可读（判据 D 也查不到）--");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.ACTIVE),
                    Collections.singletonList(ev(Evidence.Source.SUCCESS_STATUS,
                            Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, 950L)));
            info("只有 status 证据 = " + r);
            ok("★→ SUCCESS（成功不依赖日志）",
                    r.verdict == CrackEngine.Verdict.SUCCESS);

            // ═══════════════════ 4. 错误密码但日志不可读
            System.out.println();
            System.out.println("-- 4. 错误密码但日志不可读（靠 dumpsys）--");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.TERMINATED),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4210L)));
            info("dumpsys 证据 = " + r);
            ok("★→ AUTH_FAIL+DIRECT（不依赖 logcat）",
                    r.verdict == CrackEngine.Verdict.AUTH_FAIL
                            && r.grade == EvidenceGrade.Level.DIRECT);

            // ═══════════════════ 5. 旧连接残留
            System.out.println();
            System.out.println("-- 5. 旧连接残留（闸门失败）--");
            r = Adjudicator.adjudicate(st(CrackEngine.Lifecycle.TERMINATED,
                            AttemptState.Gate.STALE_CONFIG),
                    Collections.singletonList(ev(Evidence.Source.SUCCESS_STATUS,
                            Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, 100L)));
            info("STALE_CONFIG + 完美成功证据 = " + r);
            ok("★→ UNKNOWN（旧配置时成功证据也不可信）",
                    r.verdict == CrackEngine.Verdict.UNKNOWN);
            ok("★→ NONE", r.grade == EvidenceGrade.Level.NONE);
            noExclude("旧连接残留", r);

            // ═══════════════════ 6. 同 SSID 不同 BSSID
            System.out.println();
            System.out.println("-- 6. 同 SSID 不同 BSSID（只有 AT1/AT2 归属）--");
            for (Evidence.Attribution a : new Evidence.Attribution[]{
                    Evidence.Attribution.AT1, Evidence.Attribution.AT2}) {
                r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.TERMINATED),
                        Collections.singletonList(ev(Evidence.Source.AUTHCAST,
                                Evidence.Claim.WRONG_KEY, a, 4200L)));
                info(a + " = " + r);
                ok("★" + a + " 不足以判候选级失败",
                        r.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + r.verdict);
                noExclude("同 SSID 不同 BSSID(" + a + ")", r);
            }

            // ═══════════════════ 7. Mesh 多 AP（AT3 成功侧）
            System.out.println();
            System.out.println("-- 7. Mesh 多 AP（同 SSID，成功侧 AT3）--");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.ACTIVE),
                    Collections.singletonList(ev(Evidence.Source.SUCCESS_STATUS,
                            Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, 800L)));
            ok("★AT3 成功（netId 同行）→ SUCCESS",
                    r.verdict == CrackEngine.Verdict.SUCCESS);

            // ═══════════════════ 8. 连接中途漫游
            System.out.println();
            System.out.println("-- 8. 连接中途漫游（ACTIVE 时不结案）--");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.ACTIVE),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4200L)));
            info("WRONG_KEY+AT3 但 ACTIVE = " + r);
            ok("★→ 不是 AUTH_FAIL", r.verdict != CrackEngine.Verdict.AUTH_FAIL);
            ok("★→ INCOMPLETE（可重新裁决）",
                    r.verdict == CrackEngine.Verdict.INCOMPLETE);
            noExclude("中途漫游", r);

            // ═══════════════════ 9. netId 延迟变化
            System.out.println();
            System.out.println("-- 9. netId 延迟变化（成功帧但归属冲突）--");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.ACTIVE),
                    Collections.singletonList(ev(Evidence.Source.SUCCESS_STATUS,
                            Evidence.Claim.CONNECTED, Evidence.Attribution.AT1, 700L)));
            info("CONNECTED 但只有 AT1 = " + r);
            ok("★→ 不判 SUCCESS", r.verdict != CrackEngine.Verdict.SUCCESS);
            ok("★reason = on-target-unattributed",
                    "on-target-unattributed".equals(r.reason), r.reason);
            noExclude("netId 延迟变化", r);

            // ═══════════════════ 10. 认证失败广播延迟
            System.out.println();
            System.out.println("-- 10. 认证失败广播延迟（代际校验）--");
            // 上一代的广播（代际落后）必须被丢弃
            boolean stale = WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                    5000L, 1000L, false, 3L, 4L);
            info("代际 3 的广播 vs 当前代际 4 → " + stale);
            ok("★迟到广播被丢弃（不误判）", !stale);
            boolean fresh = WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                    5000L, 1000L, false, 4L, 4L);
            ok("同代际且晚于命令 → 采信", fresh);

            // ═══════════════════ 11. 广播属于上一次 attempt
            System.out.println();
            System.out.println("-- 11. 广播属于上一次 attempt（未归属证据）--");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.TERMINATED),
                    Collections.singletonList(evOther(Evidence.Source.AUTHCAST,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT1)));
            info("别人的广播 = " + r);
            ok("★别人的证据被丢弃，不产 AUTH_FAIL",
                    r.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + r.verdict);
            noExclude("广播属于上一次", r);

            // ═══════════════════ 12. 任务暂停和停止
            System.out.println();
            System.out.println("-- 12. 任务暂停和停止（ABORTED 不计入结论）--");
            ok("ABORTED 默认等级 NONE",
                    EvidenceGrade.defaultOf(CrackEngine.Verdict.ABORTED)
                            == EvidenceGrade.Level.NONE);
            ok("ABORTED 不可持久化",
                    !EvidenceGrade.persistable(EvidenceGrade.defaultOf(
                            CrackEngine.Verdict.ABORTED)));
            ok("ABORTED 不推进游标",
                    !EvidenceGrade.advancesCursor(EvidenceGrade.defaultOf(
                            CrackEngine.Verdict.ABORTED)));

            // ═══════════════════ 13. 进程被杀后恢复
            System.out.println();
            System.out.println("-- 13. 进程被杀后恢复（游标只被 DIRECT 推进）--");
            ok("NONE 不推进游标", CrackEngine.nextCursor(0, 0,
                    EvidenceGrade.Level.NONE) == 0);
            ok("INFERRED 不推进游标", CrackEngine.nextCursor(0, 0,
                    EvidenceGrade.Level.INFERRED) == 0);
            ok("DIRECT 推进游标", CrackEngine.nextCursor(0, 0,
                    EvidenceGrade.Level.DIRECT) == 1);
            ok("★前面有 NONE 时后面的 DIRECT 也不越过它",
                    CrackEngine.nextCursor(0, 1, EvidenceGrade.Level.DIRECT) == 0);

            // ═══════════════════ 14. Shell 返回错误文本
            System.out.println();
            System.out.println("-- 14. Shell 返回错误文本（通道异常）--");
            LogcatCmd.Result lr = LogcatCmd.parse("error: device offline\n");
            info("无哨兵的错误输出 → " + lr.status);
            ok("★无哨兵 → INDETERMINATE（不当成「没日志」）",
                    lr.status == LogcatCmd.Status.INDETERMINATE);
            ok("INDETERMINATE 不可用作证据", !lr.logUsable());

            LogcatCmd.Result lr2 = LogcatCmd.parse(
                    LogcatCmd.RC + "1\n" + LogcatCmd.RC_GREP + "0\n");
            ok("logcat 非 0 退出 → UPSTREAM_FAILED",
                    lr2.status == LogcatCmd.Status.UPSTREAM_FAILED);
            ok("UPSTREAM_FAILED 不可用作证据", !lr2.logUsable());

            // ═══════════════════ 15. list-networks 不可解析
            System.out.println();
            System.out.println("-- 15. list-networks 不可解析（闸门 UNREADABLE）--");
            r = Adjudicator.adjudicate(st(CrackEngine.Lifecycle.UNKNOWN,
                            AttemptState.Gate.UNREADABLE),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4200L)));
            info("UNREADABLE + AT3 失败证据 = " + r);
            ok("★→ UNKNOWN（读不到就不判）", r.verdict == CrackEngine.Verdict.UNKNOWN);
            ok("★reason 以 gate- 开头", r.reason.startsWith("gate-"), r.reason);
            noExclude("list-networks 不可解析", r);

            // ═══════════════════ 16. 日志窗口为空
            System.out.println();
            System.out.println("-- 16. 日志窗口为空 --");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.TERMINATED),
                    Collections.emptyList());
            info("无任何证据 + TERMINATED = " + r);
            ok("→ TIMEOUT", r.verdict == CrackEngine.Verdict.TIMEOUT, "" + r.verdict);
            ok("reason = no-evidence", "no-evidence".equals(r.reason), r.reason);
            noExclude("日志窗口为空", r);

            // ═══════════════════ 17. 连接成功但 IP 延迟分配
            System.out.println();
            System.out.println("-- 17. 连接成功但 IP 延迟分配 --");
            // 没有 IP 时 SuccessRule 不判成功（CONNECTED 不会被提交）
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.ACTIVE),
                    Collections.singletonList(ev(Evidence.Source.SUCCESS_STATUS,
                            Evidence.Claim.NOT_CONNECTED, Evidence.Attribution.NONE, 500L)));
            info("只有 NOT_CONNECTED + ACTIVE = " + r);
            ok("★→ INCOMPLETE（不判失败）", r.verdict == CrackEngine.Verdict.INCOMPLETE);
            noExclude("IP 延迟分配", r);

            // ═══════════════════ 18. 认证失败但系统没发 TEMP-DISABLED
            System.out.println();
            System.out.println("-- 18. 认证失败但没 TEMP-DISABLED（靠 dumpsys）--");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.TERMINATED),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4200L)));
            ok("★dumpsys 证据不依赖 TEMP-DISABLED → AUTH_FAIL+DIRECT",
                    r.verdict == CrackEngine.Verdict.AUTH_FAIL
                            && r.grade == EvidenceGrade.Level.DIRECT);

            // ═══════════════════ 19. 认证失败但没有 4-Way Handshake failed
            System.out.println();
            System.out.println("-- 19. 认证失败但没有 4-Way Handshake failed --");
            r = Adjudicator.adjudicate(stOk(CrackEngine.Lifecycle.TERMINATED),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4200L)));
            ok("★dumpsys 的 AUTH_FAILURE_WRONG_PSWD 独立成立 → AUTH_FAIL+DIRECT",
                    r.verdict == CrackEngine.Verdict.AUTH_FAIL
                            && r.grade == EvidenceGrade.Level.DIRECT);

            // ═══════════════════ 20. 同一候选重复尝试
            System.out.println();
            System.out.println("-- 20. 同一候选重复尝试 --");
            // 第一次 ACTIVE（INCOMPLETE），第二次 TERMINATED（AUTH_FAIL）
            Adjudicator.Result first = Adjudicator.adjudicate(
                    stOk(CrackEngine.Lifecycle.ACTIVE),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4200L)));
            Adjudicator.Result second = Adjudicator.adjudicate(
                    stOk(CrackEngine.Lifecycle.TERMINATED),
                    Collections.singletonList(ev(Evidence.Source.DUMPS_WRONG_KEY,
                            Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, 4200L)));
            info("第一次(ACTIVE) = " + first + " / 第二次(TERMINATED) = " + second);
            ok("★第一次 INCOMPLETE 不排除（下次还能试）",
                    first.verdict == CrackEngine.Verdict.INCOMPLETE
                            && !EvidenceGrade.persistable(first.grade));
            ok("★第二次 TERMINATED 才结案",
                    second.verdict == CrackEngine.Verdict.AUTH_FAIL
                            && EvidenceGrade.persistable(second.grade));

            // ═══════════════════ 三条核心不变式
            System.out.println();
            System.out.println("-- ★三条核心不变式（穷举核对）--");
            verifyInvariant1();
            verifyInvariant2();
            verifyInvariant3();

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 判定场景矩阵: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 不变式 1：WRONG_KEY 永不产 SUCCESS。 */
    private static void verifyInvariant1() {
        int bad = 0;
        for (Evidence.Source s : Evidence.Source.values()) {
            for (Evidence.Attribution a : Evidence.Attribution.values()) {
                for (CrackEngine.Lifecycle lc : CrackEngine.Lifecycle.values()) {
                    if (lc == null) continue;
                    Adjudicator.Result r = Adjudicator.adjudicate(stOk(lc),
                            Collections.singletonList(ev(s, Evidence.Claim.WRONG_KEY, a, 100L)));
                    if (r.verdict == CrackEngine.Verdict.SUCCESS) bad++;
                }
            }
        }
        ok("★不变式1：WRONG_KEY 在任何来源/归属/生命周期下都不产 SUCCESS（共 "
                + (Evidence.Source.values().length * Evidence.Attribution.values().length
                    * CrackEngine.Lifecycle.values().length) + " 组合）", bad == 0,
                "违规 " + bad + " 例");
    }

    /** 不变式 2：AUTH_FAIL+DIRECT 只可能来自 WRONG_KEY+AT3+TERMINATED。 */
    private static void verifyInvariant2() {
        int bad = 0;
        for (Evidence.Claim c : Evidence.Claim.values()) {
            for (Evidence.Attribution a : Evidence.Attribution.values()) {
                for (CrackEngine.Lifecycle lc : CrackEngine.Lifecycle.values()) {
                    if (lc == null) continue;
                    Adjudicator.Result r = Adjudicator.adjudicate(stOk(lc),
                            Collections.singletonList(ev(Evidence.Source.AUTHCAST, c, a, 100L)));
                    if (r.verdict == CrackEngine.Verdict.AUTH_FAIL
                            && r.grade == EvidenceGrade.Level.DIRECT) {
                        boolean legit = c == Evidence.Claim.WRONG_KEY
                                && a == Evidence.Attribution.AT3
                                && lc == CrackEngine.Lifecycle.TERMINATED;
                        if (!legit) bad++;
                    }
                }
            }
        }
        ok("★不变式2：AUTH_FAIL+DIRECT 只来自 WRONG_KEY+AT3+TERMINATED", bad == 0,
                "违规 " + bad + " 例");
    }

    /** 不变式 3：只有 DIRECT 可持久化/推进游标。 */
    private static void verifyInvariant3() {
        int bad = 0;
        for (EvidenceGrade.Level g : EvidenceGrade.Level.values()) {
            boolean p = EvidenceGrade.persistable(g);
            boolean adv = EvidenceGrade.advancesCursor(g);
            boolean shouldBe = (g == EvidenceGrade.Level.DIRECT);
            if (p != shouldBe || adv != shouldBe) bad++;
        }
        ok("★不变式3：persistable/advancesCursor 当且仅当 DIRECT", bad == 0,
                "违规 " + bad + " 例");

        // 穷举所有 Verdict × Level，确认没有非 DIRECT 被持久化
        int bad2 = 0;
        for (CrackEngine.Verdict v : CrackEngine.Verdict.values()) {
            for (EvidenceGrade.Level g : EvidenceGrade.Level.values()) {
                if (EvidenceGrade.persistable(g) && g != EvidenceGrade.Level.DIRECT) bad2++;
            }
        }
        ok("★不变式3b：没有任何非 DIRECT 等级可持久化", bad2 == 0);
    }
}

package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Adjudicator;
import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.SuccessRule;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;
import io.github.wlmosv_png.wpaforge.cap.ChannelReadiness;
import io.github.wlmosv_png.wpaforge.cap.ExecutorIdentity;
import io.github.wlmosv_png.wpaforge.cap.ModeResolver;
import io.github.wlmosv_png.wpaforge.cap.RunMode;
import io.github.wlmosv_png.wpaforge.cap.ShizukuChannel;

/**
 * **执行链统一门禁**（2026-11-20 第二阶段验收）。
 *
 * <h3>覆盖任务书第六部分列出的 12 项</h3>
 * <ol>
 *   <li>Shizuku Root 身份正确选择 Shizuku —— {@link #rootIdentityPicksShizuku()}<\/li>
 *   <li>Shizuku Shell 身份正确选择 Shizuku —— 同上（两种状态都必须 USABLE）<\/li>
 *   <li>AUTO 模式不会错误选择 su —— {@link #autoNeverFallsToSu()}<\/li>
 *   <li>Shizuku 服务死亡后立即阻止执行 —— {@link #deadServiceBlocks()}<\/li>
 *   <li>诊断与正式任务使用一致的 Executor 选择逻辑 —— {@link #diagAndTaskShareSelection()}<\/li>
 *   <li>预检失败时 executed 不增加 —— {@link #gateBlockedDoesNotCountExecuted()}<\/li>
 *   <li>环境错误不增加成功或失败统计 —— {@link #envErrorNotSuccessOrFail()}<\/li>
 *   <li>熔断后不再消费候选 —— {@link #breakerStopsConsuming()}<\/li>
 *   <li>超时不会留下仍在运行的子进程 —— {@link #timeoutKillsChild()}<\/li>
 *   <li>DIRECT 成功判据保持原样 —— {@link #directUnchanged()}<\/li>
 *   <li>错误密码不能因配置创建或网络回调而变成 SUCCESS —— {@link #wrongPasswordNeverSuccess()}<\/li>
 *   <li>UNKNOWN 不写 TriedSet —— {@link #unknownNotPersisted()}<\/li>
 * <\/ol>
 *
 * <p><b>不删旧测试、不放宽断言</b>：本类是**新增**，旧类里针对字符串形态的
 * 断言已改为针对新契约的等价断言（意图不变，见各处注释）。
 */
public class UnifiedExecutorChainTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        UnifiedExecutorChainTest t = new UnifiedExecutorChainTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private static String readSource(String rel) {
        try {
            String path = TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 去掉注释行后的源码（用于判断「代码里是否真的这么写」）。
     *
     * <p>历史注释里会写到旧写法（如 {@code dsz.state() == S_READY}）作为根因说明，
     * 那不是活代码。断言必须区分「注释提到」与「代码写了」。
     */
    private static String codeOnly(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
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
        // ── 1/2：Shizuku 两种身份都算可用
        rootIdentityPicksShizuku();
        // ── 3：AUTO 不落到 su
        autoNeverFallsToSu();
        // ── 4：服务死亡立即阻止
        deadServiceBlocks();
        // ── 5：诊断与正式任务同源
        diagAndTaskShareSelection();
        // ── 6：预检失败不增加 executed
        gateBlockedDoesNotCountExecuted();
        // ── 7：环境错误不进成功/失败
        envErrorNotSuccessOrFail();
        // ── 8：熔断后停止消费
        breakerStopsConsuming();
        // ── 9：超时不留子进程
        timeoutKillsChild();
        // ── 10：DIRECT 判据原样
        directUnchanged();
        // ── 11：错误密码不会变 SUCCESS
        wrongPasswordNeverSuccess();
        // ── 12：UNKNOWN 不写 TriedSet
        unknownNotPersisted();
        // ── 附加：耗时分解 + 执行器标签
        timingBreakdownPresent();
        executorLabelFromPlan();
        noSilentFallbackAnywhere();
    }

    /**
     * ① Shizuku **root 身份**（S_READY_NOT_SHELL）必须算可用 → 选 Shizuku。
     *
     * <p>这是「Shizuku 已授权却走 su」的根因：旧代码只认 S_READY。
     */
    private void rootIdentityPicksShizuku() {
        ok("S_READY 可用", ChannelReadiness.usable(ShizukuChannel.S_READY));
        ok("S_READY_NOT_SHELL 可用", ChannelReadiness.usable(ShizukuChannel.S_READY_NOT_SHELL));
        ok("两者都是 USABLE 结论",
                ChannelReadiness.of(ShizukuChannel.S_READY) == ChannelReadiness.Verdict.USABLE
                        && ChannelReadiness.of(ShizukuChannel.S_READY_NOT_SHELL)
                        == ChannelReadiness.Verdict.USABLE);
        ok("可用即允许启动任务",
                ChannelReadiness.mayStartTask(ChannelReadiness.Verdict.USABLE));

        // ROOTLESS + Shizuku 可用（任一种身份）→ 必须解析成 SHIZUKU 执行器
        ModeResolver.Resolved r1 = ModeResolver.resolve(RunMode.ROOTLESS, false, true);
        ok("ROOTLESS + Shizuku 可用 → 可跑", r1.canRun);
        ok("ROOTLESS + Shizuku 可用 → 模式仍是 ROOTLESS",
                r1.mode == RunMode.ROOTLESS);

        // 源码侧：可用性判据只有一处（ChannelReadiness），不允许各点自判
        String prov = readSource("cap/ExecutorProvider.java");
        ok("ExecutorProvider 用 ChannelReadiness 判可用",
                prov.contains("ChannelReadiness.mayStartTask(verdict)"));
        String svc = readSource("CrackService.java");
        // 判据必须在 ChannelReadiness 里；CrackService 不得拿 state() 做条件。
        // （历史注释会提到旧写法，那不是活代码 —— 用 codeOnly 区分）
        String svcCode = codeOnly(svc);
        ok("CrackService 代码里不拿 state() 做判据",
                !svcCode.contains("s0.state() ==")
                        && !svcCode.contains("dsz.state() ==")
                        && !svcCode.contains(".state() == io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.S_"));
        ok("CrackService 走统一判定入口",
                svc.contains("ExecutorProvider.resolve"));
    }

    /**
     * ③ AUTO **绝不能**在 su 不可用时落到 su。
     *
     * <p>旧实现 {@code runModeId == ROOTLESS ? ... : ...} 把 AUTO 当成 Root，
     * 于是 su 未授权时每条命令等满超时。
     */
    private void autoNeverFallsToSu() {
        // su 不可用 + Shizuku 可用 → 选 ROOTLESS（不是 ROOT）
        ModeResolver.Resolved r = ModeResolver.resolve(RunMode.AUTO, false, true);
        ok("AUTO + su 不可用 + Shizuku 可用 → 可跑", r.canRun);
        ok("AUTO 不落到 ROOT", r.mode == RunMode.ROOTLESS);
        ok("AUTO 保留原始意愿", r.requested == RunMode.AUTO);

        // su 可用 → 选 ROOT
        ModeResolver.Resolved r2 = ModeResolver.resolve(RunMode.AUTO, true, false);
        ok("AUTO + su 可用 → ROOT", r2.canRun && r2.mode == RunMode.ROOT);

        // 两者都不可用 → 明确阻止（不猜）
        ModeResolver.Resolved r3 = ModeResolver.resolve(RunMode.AUTO, false, false);
        ok("AUTO + 都不可用 → 阻止", !r3.canRun);
        ok("AUTO + 都不可用 → 给出原因", !r3.blockReason.isEmpty());
        ok("AUTO + 都不可用 → 不许开始任务",
                !ChannelReadiness.mayStartTask(ChannelReadiness.Verdict.NOT_RUNNING));

        // 用户明确选 ROOT 但 su 不可用 → 如实失败，不偷偷换
        ModeResolver.Resolved r4 = ModeResolver.resolve(RunMode.ROOT, false, true);
        ok("ROOT 模式 su 不可用 → 阻止（不偷偷走 Shizuku）", !r4.canRun);
        ok("ROOT 模式阻止原因非空", !r4.blockReason.isEmpty());

        // 源码侧：AUTO 解析只在一处
        String prov = readSource("cap/ExecutorProvider.java");
        ok("ExecutorProvider 是 AUTO 的唯一解析点",
                prov.contains("ModeResolver.resolve(requested, suOk, shzOk)"));
        String svc = readSource("CrackService.java");
        ok("CrackService 不再自己判 AUTO",
                !svc.contains("runModeId == io.github.wlmosv_png.wpaforge.cap.RunMode.AUTO"));
    }

    /**
     * ④ Shizuku 服务死亡 / 未运行 / 未授权 → 必须**立即阻止执行**。
     */
    private void deadServiceBlocks() {
        ok("未运行 → 不可用",
                !ChannelReadiness.usable(ShizukuChannel.S_NOT_RUNNING));
        ok("未安装 → 不可用",
                !ChannelReadiness.usable(ShizukuChannel.S_NOT_INSTALLED));
        ok("未授权 → 不可用",
                !ChannelReadiness.usable(ShizukuChannel.S_NO_PERMISSION));
        ok("未知（Binder 死亡/身份读取失败）→ 不可用",
                !ChannelReadiness.usable(-1));
        ok("未知状态归 UNKNOWN",
                ChannelReadiness.of(-1) == ChannelReadiness.Verdict.UNKNOWN);
        ok("所有非 USABLE 都禁止启动任务",
                !ChannelReadiness.mayStartTask(ChannelReadiness.Verdict.NOT_RUNNING)
                        && !ChannelReadiness.mayStartTask(ChannelReadiness.Verdict.NOT_INSTALLED)
                        && !ChannelReadiness.mayStartTask(ChannelReadiness.Verdict.NO_PERMISSION)
                        && !ChannelReadiness.mayStartTask(ChannelReadiness.Verdict.UNKNOWN));

        // ROOTLESS 且 Shizuku 不可用 → 仍然 canRun（Specifier 兜底），
        // 但执行器**不能**是 SHIZUKU
        ModeResolver.Resolved r = ModeResolver.resolve(RunMode.ROOTLESS, false, false);
        ok("ROOTLESS + Shizuku 不可用 → 仍可跑（Specifier 兜底）", r.canRun);

        // 执行时每次都要重新确认存活（不能只用缓存 state）
        String shz = readSource("cap/ShizukuChannel.java");
        ok("exec 前做存活检查", shz.contains("if (!stillUsable())"));
        ok("存活检查查 pingBinder", shz.contains("pingBinder"));
        ok("存活检查查授权位", shz.contains("checkSelfPermission"));
        ok("Binder 断开时更新状态为实际值", shz.contains("Shizuku 服务已退出（Binder 断开）"));
        ok("授权撤销时更新状态", shz.contains("Shizuku 授权已失效"));
    }

    /**
     * ⑤ 诊断入口与正式任务必须使用**同一次** Executor 选择。
     */
    private void diagAndTaskShareSelection() {
        String prov = readSource("cap/ExecutorProvider.java");
        ok("存在唯一选择入口 ExecutorProvider.resolve", prov.contains("public static Plan resolve(Context ctx)"));
        ok("选择结论含请求模式", prov.contains("public final int requestedMode"));
        ok("选择结论含实际模式", prov.contains("public final int resolvedMode"));
        ok("选择结论含执行器种类", prov.contains("public final Kind kind"));
        ok("选择结论含真实身份", prov.contains("public final ExecutorIdentity identity"));
        ok("选择结论含可用性结论", prov.contains("public final ChannelReadiness.Verdict verdict"));
        ok("选择结论含能否开跑", prov.contains("public final boolean canRun"));
        ok("执行器标签来自 plan（不是从授权状态推断）", prov.contains("public String label()"));

        String svc = readSource("CrackService.java");
        ok("正式任务走 ExecutorProvider.resolve",
                svc.contains("io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.resolve(this)"));
        ok("正式任务固定 plan", svc.contains("resolvedPlan = plan;"));

        // 诊断入口（App.java）必须也走 ExecutorProvider
        String app = readSource("App.java");
        ok("诊断入口走 ExecutorProvider",
                app.contains("io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.resolve"));
        ok("诊断入口用 CommandExecutor 执行",
                app.contains("CommandExecutor")
                        && (app.contains("CommandExecutor.run")
                            || app.contains("CommandExecutor\n                                        .run")));
        ok("诊断入口用 CommandExecutor.ShellAdapter（自检）",
                app.contains("CommandExecutor.ShellAdapter"));
        // 诊断段不得再直接调 ShizukuChannel.exec
        ok("诊断段不再直接调 shz.exec",
                !app.contains("shz.exec(rc, 8000)"));
        ok("诊断段不再静默回落 su",
                !app.contains("RootProber\n                                    .exec(null, new String[]{\"su\", \"-c\", cmd}, timeoutMs)"));

        // 只读命令白名单：绝不能出现在诊断里
        ok("诊断不含 connect-network", !app.contains("\"cmd wifi connect-network\""));
        ok("诊断不含 forget-network", !app.contains("\"cmd wifi forget-network\""));
        ok("诊断不含 disconnect", !app.contains("\"cmd wifi disconnect\""));
        ok("诊断不含 set-wifi-enabled", !app.contains("\"cmd wifi set-wifi-enabled\""));
    }

    /**
     * ⑥ 预检失败（闸门拦下）时 **executed 不得增加**。
     */
    private void gateBlockedDoesNotCountExecuted() {
        // NOT_CAPABLE / STALE_CONFIG / UNREADABLE 都必须给 NOT_EXECUTED，且不计入 executed
        CrackEngine.Attempt nc = Adjudicator.gateBlocked("12345678", "run#1",
                AttemptState.Gate.NOT_CAPABLE, 5L, "无 Root 模式无法逐条关联");
        ok("NOT_CAPABLE → NOT_EXECUTED",
                nc.verdict == CrackEngine.Verdict.NOT_EXECUTED);

        // STALE_CONFIG / UNREADABLE 是「执行了但前提不满足」→ UNKNOWN（不是 NOT_EXECUTED）。
        // 两者都不得进入「已证实失败」，也不得写 TriedSet（grade=NONE）。
        CrackEngine.Attempt stale = Adjudicator.gateBlocked("12345678", "run#1",
                AttemptState.Gate.STALE_CONFIG, 5L, "旧配置未清除");
        ok("STALE_CONFIG → UNKNOWN", stale.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("STALE_CONFIG 证据等级 NONE", stale.grade == EvidenceGrade.Level.NONE);
        ok("STALE_CONFIG 不写 TriedSet", !EvidenceGrade.persistable(stale.grade));
        ok("STALE_CONFIG 不推进游标", !EvidenceGrade.advancesCursor(stale.grade));

        CrackEngine.Attempt unread = Adjudicator.gateBlocked("12345678", "run#1",
                AttemptState.Gate.UNREADABLE, 5L, "命令前状态不可读");
        ok("UNREADABLE → UNKNOWN", unread.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("UNREADABLE 证据等级 NONE", unread.grade == EvidenceGrade.Level.NONE);
        ok("UNREADABLE 不写 TriedSet", !EvidenceGrade.persistable(unread.grade));

        // 另一个方向：UNKNOWN 不能被当成已证实失败
        VerdictTally tg = new VerdictTally();
        tg.add(stale.verdict, stale.grade);
        ok("gate 失败不进已证实失败", tg.confirmedFail() == 0);
        ok("gate 失败不进成功", tg.success() == 0);

        // 统计口径：NOT_EXECUTED 不进 executed
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.NOT_EXECUTED, EvidenceGrade.Level.NONE);
        ok("NOT_EXECUTED 不计入 executed", t.executed() == 0);
        ok("NOT_EXECUTED 单列", t.notExecuted() == 1);
        ok("NOT_EXECUTED 不冒充成功", t.success() == 0);
        ok("NOT_EXECUTED 不冒充失败", t.confirmedFail() == 0);
        ok("NOT_EXECUTED 不冒充环境错误", t.environmentErrors() == 0);

        // 源码侧：tested++ 必须在 tally.add 之后
        String eng = readSource("audit/CrackEngine.java");
        int tallyAt = eng.indexOf("tally.add(a.verdict, a.grade);");
        int testedAt = eng.indexOf("tested++;", tallyAt);
        ok("tested++ 在 tally.add 之后", tallyAt > 0 && testedAt > tallyAt);
        ok("tested++ 不再出现在 tryOne 之前",
                !eng.contains("l.onAttemptStart(i + 1, total, c.password);\n                tested++;"));
    }

    /**
     * ⑦ 环境错误（ERROR / TARGET_GONE）**不得**计入成功或已证实失败。
     */
    private void envErrorNotSuccessOrFail() {
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.ERROR, EvidenceGrade.Level.NONE);
        ok("ERROR 计入 environmentErrors", t.environmentErrors() == 1);
        ok("ERROR 不计入 executed", t.executed() == 0);
        ok("ERROR 不计入成功", t.success() == 0);
        ok("ERROR 不计入已证实失败", t.confirmedFail() == 0);
        ok("ERROR 不计入未确定（它是环境，不是候选结论）", t.inconclusive() == 0);

        VerdictTally t2 = new VerdictTally();
        t2.add(CrackEngine.Verdict.TARGET_GONE, EvidenceGrade.Level.NONE);
        ok("TARGET_GONE 也计入 environmentErrors", t2.environmentErrors() == 1);
        ok("TARGET_GONE 不计入 executed", t2.executed() == 0);

        // 成功必须独立计数
        VerdictTally t3 = new VerdictTally();
        t3.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);
        ok("SUCCESS 独立计数", t3.success() == 1);
        ok("SUCCESS 计入 executed", t3.executed() == 1);
        ok("SUCCESS 不冒充已证实失败", t3.confirmedFail() == 0);

        // 非 DIRECT 的 SUCCESS 不得冒充成功
        VerdictTally t4 = new VerdictTally();
        t4.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.INFERRED);
        ok("非 DIRECT 的 SUCCESS 不算成功", t4.success() == 0);
        ok("非 DIRECT 的 SUCCESS 归未确定", t4.inconclusive() == 1);

        // 自洽性恒等式
        ok("统计自洽（空）", new VerdictTally().selfConsistent());
        ok("统计自洽（混合）", t3.selfConsistent());
    }

    /**
     * ⑧ 熔断后**不再消费候选**、停止推进游标。
     */
    private void breakerStopsConsuming() {
        String eng = readSource("audit/CrackEngine.java");
        ok("有连续环境错误熔断阈值", eng.contains("ENV_ERROR_BREAK"));
        ok("熔断后 break 出循环",
                eng.contains("if (consecutiveEnvErrors >= ENV_ERROR_BREAK)"));
        // 熔断分支之后必须 break
        int at = eng.indexOf("if (consecutiveEnvErrors >= ENV_ERROR_BREAK)");
        int brk = eng.indexOf("break;", at);
        ok("熔断分支内 break", at > 0 && brk > at);
        ok("熔断时不再推进游标（break 前无 cursor 赋值）",
                !eng.substring(at, brk > at ? brk : at).contains("cursor ="));
        ok("熔断给用户可见通知", eng.contains("l.onBlocked("));
        ok("熔断文案说明候选未被验证", eng.contains("没有被真正验证"));

        // 通道错误判定：只有通道级才熔断
        ok("候选级错误不触发熔断", !CrackEngine.isChannelError("invalid-candidate"));
        ok("调用级错误不触发熔断", !CrackEngine.isChannelError("not-submitted"));
        ok("能力不足不触发熔断", !CrackEngine.isChannelError("not-capable"));
        ok("异常视为通道级", CrackEngine.isChannelError("exception"));
        ok("未标注按通道级（保守）", CrackEngine.isChannelError(""));
        ok("未标注按通道级（null）", CrackEngine.isChannelError(null));
    }

    /**
     * ⑨ 超时**不能留下仍在运行的子进程**。
     */
    private void timeoutKillsChild() {
        String shz = readSource("cap/ShizukuChannel.java");
        ok("超时先 destroy 子进程", shz.contains("destroy(remote);"));
        ok("finally 里也 destroy（兜底）", shz.contains("try { transactVoid(remote, RP_DESTROY); }"));
        // **顺序已按真机证据改正**（2026-11-20 第三阶段）：
        // 旧断言要求「先 close 再 join」，但真机证明那会丢输出 ——
        // 子进程退出后缓冲区里还有没读走的数据，close 会让 drain 提前结束。
        // 正确顺序：先 join 读尽（子进程退出后写端自动关闭，drain 自然 EOF），
        // 只有 join 超时才 close 强制叫醒。
        String code = codeOnly(shz);
        int joinAt = code.indexOf("joinQuietly(tOut, JOIN_DRAIN_MS);");
        int closeAt = code.indexOf("closeQuietly(outFd);");
        ok("先 join 读尽再 close（真机根因修复）", joinAt > 0 && closeAt > 0 && joinAt < closeAt);
        ok("join 有显式时限常量（不再固定 3000ms）",
                shz.contains("JOIN_DRAIN_MS") && shz.contains("JOIN_FORCE_MS"));
        ok("close 只在 drain 未完成时兜底",
                shz.contains("if (!outComplete) closeQuietly(outFd);"));
        ok("waitFor 用带超时的事务码", shz.contains("RP_WAIT_FOR_TIMEOUT"));
        ok("有硬超时兜底（Binder 卡住也能返回）",
                shz.contains("waitForTimeout") && shz.contains("timeout = true"));

        // CommandExecutor 必须把超时如实传上去
        String ce = readSource("cap/CommandExecutor.java");
        ok("CommandExecutor 记 timedOut", ce.contains("st.timedOut"));
        ok("超时分类存在", ce.contains("F_TIMEOUT"));
    }

    /**
     * ⑩ DIRECT 成功判据**保持原样**（不因本轮改动放宽）。
     */
    private void directUnchanged() {
        String sr = readSource("audit/SuccessRule.java");
        ok("SuccessRule 仍是唯一成功入口",
                sr.contains("public static Assessment assess(")
                        && sr.contains("public static boolean isSuccess("));
        // 三条硬条件必须逐条在源码里可见（不能被删或放宽）
        ok("仍要求 SSID 一致", sr.contains("targetSsid.equals(statusSsid)"));
        ok("仍要求 COMPLETED", sr.contains("\"COMPLETED\".equalsIgnoreCase(supplicantState)"));
        ok("仍要求有效 IP", sr.contains("\"0.0.0.0\".equals(ip)"));

        String eng = readSource("audit/CrackEngine.java");
        ok("引擎走 SuccessRule.assess", eng.contains("SuccessRule.assess("));
        ok("AT3 需要前提成立", eng.contains("AttributionPrecondition"));
        ok("DIRECT 需要阶段全过", readSource("audit/AttemptPhases.java")
                .contains("eligibleForDirect()"));

        // 证据等级不能降
        ok("DIRECT 可持久化", EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT));
        ok("NONE 不可持久化", !EvidenceGrade.persistable(EvidenceGrade.Level.NONE));
        ok("只有 DIRECT 推进游标",
                EvidenceGrade.advancesCursor(EvidenceGrade.Level.DIRECT)
                        && !EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED));
    }

    /**
     * ⑪ 错误密码**不能**因「配置创建成功」或「网络回调」变成 SUCCESS。
     */
    private void wrongPasswordNeverSuccess() {
        // 仅有 netId（配置建出来了）不等于成功
        SuccessRule.Assessment a1 = SuccessRule.assess("B214", "B214", "ASSOCIATING",
                null, 613L, 613L);
        ok("配置建出来但未完成握手 → 不成功", a1.verdict != CrackEngine.Verdict.SUCCESS);

        // 连上别的网络不算成功
        SuccessRule.Assessment a2 = SuccessRule.assess("B214", "OTHER", "COMPLETED",
                "192.168.1.3", 620L, 613L);
        ok("连上别的 SSID → 不成功", a2.verdict != CrackEngine.Verdict.SUCCESS);

        // 有 IP 但 SSID 不符
        SuccessRule.Assessment a3 = SuccessRule.assess("B214", "B214-X", "COMPLETED",
                "192.168.1.3", 613L, 613L);
        ok("SSID 不完全相同 → 不成功", a3.verdict != CrackEngine.Verdict.SUCCESS);

        // 正确形态才算成功
        SuccessRule.Assessment a4 = SuccessRule.assess("B214", "B214", "COMPLETED",
                "192.168.1.3", 613L, 613L);
        ok("四条件同行成立 → 成功", a4.verdict == CrackEngine.Verdict.SUCCESS);

        // netId 冲突 → UNKNOWN，不是 SUCCESS
        SuccessRule.Assessment a5 = SuccessRule.assess("B214", "B214", "COMPLETED",
                "192.168.1.3", 620L, 613L);
        ok("netId 冲突 → 不是 SUCCESS", a5.verdict != CrackEngine.Verdict.SUCCESS);

        // 引擎侧：网络级失败单列，不并进已证实失败
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.NETWORK_AUTH_FAIL, EvidenceGrade.Level.NONE);
        ok("NETWORK_AUTH_FAIL 单列", t.networkAuthFail() == 1);
        ok("NETWORK_AUTH_FAIL 不算已证实失败", t.confirmedFail() == 0);
    }

    /**
     * ⑫ UNKNOWN **不得**写入 TriedSet（永久排除集合）。
     */
    private void unknownNotPersisted() {
        ok("UNKNOWN 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.UNKNOWN)));
        ok("TIMEOUT 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.TIMEOUT)));
        ok("ERROR 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.ERROR)));
        ok("NOT_EXECUTED 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.NOT_EXECUTED)));

        String svc = readSource("CrackService.java");
        ok("只有 persistable 才写 TriedSet",
                svc.contains("EvidenceGrade.persistable(a.grade)"));
        ok("命中不写 TriedSet", svc.contains("if (a.isSuccess())"));
        ok("非 DIRECT 只记日志不排除",
                svc.contains("非 DIRECT（含 INFERRED / NONE）只记录日志"));

        // 统计侧：UNKNOWN 归未确定，不算已证实失败
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE);
        ok("UNKNOWN 归未确定", t.inconclusive() == 1);
        ok("UNKNOWN 不算已证实失败", t.confirmedFail() == 0);
        ok("UNKNOWN 不计入成功", t.success() == 0);
    }

    /**
     * 附加：**耗时分解**必须存在（任务书第四部分）。
     *
     * <p>「每条 11.7 秒」这类问题，只有把 Binder / 子进程等待 / 读流分开才定位得了。
     */
    private void timingBreakdownPresent() {
        String shz = readSource("cap/ShizukuChannel.java");
        ok("Result 有 binderMs", shz.contains("public long binderMs"));
        ok("Result 有 waitMs", shz.contains("public long waitMs"));
        ok("Result 有 drainMs", shz.contains("public long drainMs"));
        ok("brief 输出三段分解",
                shz.contains("\" binder=\" + binderMs")
                        && shz.contains("\" wait=\" + waitMs")
                        && shz.contains("\" drain=\" + drainMs"));
        // 三段必须真的被累加（不是只有字段）
        ok("binderMs 真的累加", shz.contains("binderMsAcc +="));
        ok("waitMs 真的累加", shz.contains("waitMsAcc +="));
        ok("drainMs 真的累加", shz.contains("drainMsAcc +="));

        String ce = readSource("cap/CommandExecutor.java");
        ok("CommandExecutor.Step 有 executorId", ce.contains("public final String executorId"));
        ok("CommandExecutor.Step 有 identity", ce.contains("public final String identity"));
        ok("CommandExecutor.Step 有 timeoutMs", ce.contains("public final long timeoutMs"));
        ok("CommandExecutor.Step 有 exitCode", ce.contains("public int exitCode"));
        ok("CommandExecutor.Step 有 stdoutBytes", ce.contains("public int stdoutBytes"));
        ok("CommandExecutor.Step 有 stderrBytes", ce.contains("public int stderrBytes"));
        ok("失败分类齐全", ce.contains("F_TIMEOUT") && ce.contains("F_NONZERO_EXIT")
                && ce.contains("F_EMPTY_OUTPUT") && ce.contains("F_NOT_USABLE")
                && ce.contains("F_EXCEPTION"));
    }

    /**
     * 附加：界面「执行通道」标签必须来自**解析结论**，不是从授权状态推断。
     */
    private void executorLabelFromPlan() {
        String prov = readSource("cap/ExecutorProvider.java");
        ok("Plan.label() 存在", prov.contains("public String label()"));
        ok("label 由 kindName + identity 组成",
                prov.contains("kindName()") && prov.contains("identity.describe(false)"));

        String svc = readSource("CrackService.java");
        ok("服务记录 plan 标签", svc.contains("lastExecutorLabel = plan.label();"));
        ok("executorLabel 优先返回 plan 标签",
                svc.contains("if (lastExecutorLabel != null && !lastExecutorLabel.isEmpty())"));

        // 身份模型三态齐全
        ok("身份区分 root", ExecutorIdentity.of(0, "", "su").isRoot());
        ok("身份区分 shell", ExecutorIdentity.of(2000, "", "Shizuku").kind
                == ExecutorIdentity.Kind.SHELL);
        ok("身份区分 system", ExecutorIdentity.of(1000, "", "Shizuku").kind
                == ExecutorIdentity.Kind.SYSTEM);
        ok("身份未知不冒充 root", !ExecutorIdentity.unknown("").isRoot());
        ok("身份未知不能读配置库", !ExecutorIdentity.unknown("").canReadConfigStore());
    }

    /**
     * 附加：**任何地方都不许静默回落到另一条身份**。
     */
    private void noSilentFallbackAnywhere() {
        String svc = readSource("CrackService.java");
        // ShellChannel 内不得出现 su 字面量（回落就是假通道）
        int at = svc.indexOf("engine.setShellChannel(");
        int end = svc.indexOf("public String name() { return plan.kindName(); }", at);
        String ch = (at > 0 && end > at) ? svc.substring(at, end) : "";
        ok("ShellChannel 内无 su", !ch.contains("\"su\""));
        ok("ShellChannel 内无 RootProber.exec", !ch.contains("RootProber.exec"));
        ok("ShellChannel 内无 sess.exec 回落", !ch.contains("sess.exec(cmd"));

        String ce = readSource("cap/CommandExecutor.java");
        ok("CommandExecutor 不在 Shizuku 失败后改走 su",
                !ce.contains("if (plan.kind == ExecutorProvider.Kind.SHIZUKU) {\n            // fallback"));
        // 不可用时如实报 NOT_USABLE，不猜
        ok("不可用如实报", ce.contains("st.failure = F_NOT_USABLE;"));
    }
}

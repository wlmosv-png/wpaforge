package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptPhases;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.ui.RunPhaseModel;

/**
 * **界面状态映射**门禁（2026-11-20 第二阶段，任务书第五部分）。
 *
 * <h3>要钉死的契约</h3>
 * <ol>
 *   <li>界面状态只由后端真实事件决定（七阶段轨迹 / Verdict / 通道结论）。<\/li>
 *   <li>「拿到下一条候选」**不得**显示成「正在验证密码」。<\/li>
 *   <li>十二个状态必须与后端事件一一对应，不许出现「猜」出来的中间态。<\/li>
 *   <li>非 DIRECT 的成功不许显示成「已验证成功」；推断失败不许显示成「已证实错误」。<\/li>
 *   <li>环境错误不许显示成密码错。<\/li>
 * </ol>
 *\/>
 */
public class RunPhaseModelTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        RunPhaseModelTest t = new RunPhaseModelTest();
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
        twelveStatesExist();
        noPhasesMeansPreparing();
        verifyingOnlyAfterSubmit();
        phaseTrajectoryMaps();
        blockedPhasesMapToEnvError();
        verdictMapping();
        nonDirectNeverVerified();
        envErrorNotPasswordProblem();
        uiWiringUsesModel();
    }

    /** 十二个状态必须齐全（任务书第五部分逐个点名）。 */
    private void twelveStatesExist() {
        RunPhaseModel.UiState[] all = RunPhaseModel.UiState.values();
        ok("状态共 12 个", all.length == 12);
        ok("有 准备中", has(RunPhaseModel.UiState.PREPARING));
        ok("有 预检通过", has(RunPhaseModel.UiState.PRECHECK_OK));
        ok("有 隔离中", has(RunPhaseModel.UiState.ISOLATING));
        ok("有 已提交", has(RunPhaseModel.UiState.SUBMITTED));
        ok("有 观察中", has(RunPhaseModel.UiState.OBSERVING));
        ok("有 归因中", has(RunPhaseModel.UiState.ATTRIBUTING));
        ok("有 已验证成功", has(RunPhaseModel.UiState.VERIFIED));
        ok("有 已证实错误", has(RunPhaseModel.UiState.PROVEN_WRONG));
        ok("有 未确定", has(RunPhaseModel.UiState.UNCERTAIN));
        ok("有 环境错误", has(RunPhaseModel.UiState.ENV_ERROR));
        ok("有 已阻止", has(RunPhaseModel.UiState.BLOCKED));
        ok("有 已停止", has(RunPhaseModel.UiState.STOPPED));
        // 终态集合
        ok("成功是终态", RunPhaseModel.UiState.VERIFIED.terminal());
        ok("已证实错误是终态", RunPhaseModel.UiState.PROVEN_WRONG.terminal());
        ok("环境错误是终态", RunPhaseModel.UiState.ENV_ERROR.terminal());
        ok("已阻止是终态", RunPhaseModel.UiState.BLOCKED.terminal());
        ok("准备中不是终态", !RunPhaseModel.UiState.PREPARING.terminal());
        ok("观察中不是终态", !RunPhaseModel.UiState.OBSERVING.terminal());
    }

    private boolean has(RunPhaseModel.UiState s) {
        for (RunPhaseModel.UiState x : RunPhaseModel.UiState.values()) {
            if (x == s) return true;
        }
        return false;
    }

    /**
     * **没有阶段轨迹 = 准备中**，不是「正在验证」。
     *
     * <p>这是本轮最核心的一条：后端还没提交候选时，界面不许说在验证。
     */
    private void noPhasesMeansPreparing() {
        ok("null 轨迹 → 准备中", RunPhaseModel.of(null) == RunPhaseModel.UiState.PREPARING);
        ok("空轨迹 → 准备中",
                RunPhaseModel.of(new AttemptPhases("run#1")) == RunPhaseModel.UiState.PREPARING);
        ok("准备中不算在验证", !RunPhaseModel.verifying(RunPhaseModel.UiState.PREPARING));
    }

    /** 只有 SUBMIT 之后才允许说「正在验证」。 */
    private void verifyingOnlyAfterSubmit() {
        ok("准备中不验证", !RunPhaseModel.verifying(RunPhaseModel.UiState.PREPARING));
        ok("预检通过不验证", !RunPhaseModel.verifying(RunPhaseModel.UiState.PRECHECK_OK));
        ok("隔离中不验证", !RunPhaseModel.verifying(RunPhaseModel.UiState.ISOLATING));
        ok("已提交算验证", RunPhaseModel.verifying(RunPhaseModel.UiState.SUBMITTED));
        ok("观察中算验证", RunPhaseModel.verifying(RunPhaseModel.UiState.OBSERVING));
        ok("归因中算验证", RunPhaseModel.verifying(RunPhaseModel.UiState.ATTRIBUTING));
        ok("已验证成功不再是进行中", !RunPhaseModel.verifying(RunPhaseModel.UiState.VERIFIED));
        ok("环境错误不再验证", !RunPhaseModel.verifying(RunPhaseModel.UiState.ENV_ERROR));
        ok("已阻止不验证", !RunPhaseModel.verifying(RunPhaseModel.UiState.BLOCKED));
    }

    /** 七阶段轨迹必须逐一映射到正确状态。 */
    private void phaseTrajectoryMaps() {
        AttemptPhases p = new AttemptPhases("run#1");
        // PRECHECK 进行中 → 准备中（还没有通过）
        p.begin(AttemptPhases.Phase.PRECHECK, 1000L);
        ok("PRECHECK 进行中 → 准备中",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.PREPARING);
        p.finish(true, "ok", "", 1010L);
        ok("PRECHECK 通过 → 预检通过",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.PRECHECK_OK);

        // ISOLATE 进行中
        p.begin(AttemptPhases.Phase.ISOLATE, 1020L);
        ok("ISOLATE 进行中 → 隔离中",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.ISOLATING);
        p.finish(true, "ok", "", 1100L);
        ok("ISOLATE 完成 → 回到预检通过（尚未提交）",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.PRECHECK_OK);

        // SUBMIT
        p.begin(AttemptPhases.Phase.SUBMIT, 1110L);
        ok("SUBMIT 进行中 → 隔离中",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.ISOLATING);
        p.finish(true, "ok", "", 1120L);
        ok("SUBMIT 完成 → 已提交",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.SUBMITTED);

        // OBSERVE
        p.begin(AttemptPhases.Phase.OBSERVE, 1130L);
        ok("OBSERVE 进行中 → 已提交",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.SUBMITTED);
        p.finish(true, "ok", "", 2000L);
        ok("OBSERVE 完成 → 观察中",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.OBSERVING);

        // ATTRIBUTE
        p.begin(AttemptPhases.Phase.ATTRIBUTE, 2010L);
        ok("ATTRIBUTE 进行中 → 观察中",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.OBSERVING);
        p.finish(true, "AT3", "", 2020L);
        ok("ATTRIBUTE 完成 → 归因中",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.ATTRIBUTING);

        // ADJUDICATE / FINALIZE → 由 verdict 决定，阶段上先给未确定
        p.begin(AttemptPhases.Phase.ADJUDICATE, 2030L);
        p.finish(true, "SUCCESS", "", 2040L);
        ok("ADJUDICATE 完成 → 未确定（等 verdict 定终态）",
                RunPhaseModel.of(p) == RunPhaseModel.UiState.UNCERTAIN);
    }

    /** 闸门失败必须映射成环境错误（不是密码错）。 */
    private void blockedPhasesMapToEnvError() {
        AttemptPhases p1 = new AttemptPhases("run#1");
        p1.begin(AttemptPhases.Phase.ISOLATE, 1000L);
        p1.finish(false, "stale-config", "无法清除旧配置", 1100L);
        ok("ISOLATE 闸门失败 → 环境错误",
                RunPhaseModel.of(p1) == RunPhaseModel.UiState.ENV_ERROR);
        ok("ISOLATE 失败不被当成密码错",
                RunPhaseModel.of(p1) != RunPhaseModel.UiState.PROVEN_WRONG);

        AttemptPhases p2 = new AttemptPhases("run#1");
        p2.begin(AttemptPhases.Phase.PRECHECK, 1000L);
        p2.finish(false, "pre-state-unknown", "", 1010L);
        ok("PRECHECK 闸门失败 → 环境错误",
                RunPhaseModel.of(p2) == RunPhaseModel.UiState.ENV_ERROR);
    }

    /** Verdict → 终态。 */
    private void verdictMapping() {
        ok("SUCCESS+DIRECT → 已验证成功",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT)
                        == RunPhaseModel.UiState.VERIFIED);
        ok("AUTH_FAIL+DIRECT → 已证实错误",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT)
                        == RunPhaseModel.UiState.PROVEN_WRONG);
        ok("ERROR → 环境错误",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.ERROR, EvidenceGrade.Level.NONE)
                        == RunPhaseModel.UiState.ENV_ERROR);
        ok("TARGET_GONE → 环境错误",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.TARGET_GONE, EvidenceGrade.Level.NONE)
                        == RunPhaseModel.UiState.ENV_ERROR);
        ok("ABORTED → 已停止",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.ABORTED, EvidenceGrade.Level.NONE)
                        == RunPhaseModel.UiState.STOPPED);
        ok("NOT_EXECUTED → 已阻止",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.NOT_EXECUTED, EvidenceGrade.Level.NONE)
                        == RunPhaseModel.UiState.BLOCKED);
        ok("TIMEOUT → 未确定",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.TIMEOUT, EvidenceGrade.Level.NONE)
                        == RunPhaseModel.UiState.UNCERTAIN);
        ok("UNKNOWN → 未确定",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE)
                        == RunPhaseModel.UiState.UNCERTAIN);
        ok("NETWORK_AUTH_FAIL → 未确定",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.NETWORK_AUTH_FAIL,
                        EvidenceGrade.Level.NONE) == RunPhaseModel.UiState.UNCERTAIN);
        ok("INCOMPLETE → 未确定",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.INCOMPLETE, EvidenceGrade.Level.NONE)
                        == RunPhaseModel.UiState.UNCERTAIN);
        ok("null verdict → 未确定",
                RunPhaseModel.ofVerdict(null, null) == RunPhaseModel.UiState.UNCERTAIN);
    }

    /** 非 DIRECT 的结论**不许**升级成确定态。 */
    private void nonDirectNeverVerified() {
        ok("SUCCESS+INFERRED 不算已验证成功",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.SUCCESS,
                        EvidenceGrade.Level.INFERRED) != RunPhaseModel.UiState.VERIFIED);
        ok("SUCCESS+NONE 不算已验证成功",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.SUCCESS,
                        EvidenceGrade.Level.NONE) != RunPhaseModel.UiState.VERIFIED);
        ok("AUTH_FAIL+INFERRED 不算已证实错误",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.AUTH_FAIL,
                        EvidenceGrade.Level.INFERRED) != RunPhaseModel.UiState.PROVEN_WRONG);
        ok("AUTH_FAIL+NONE 不算已证实错误",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.AUTH_FAIL,
                        EvidenceGrade.Level.NONE) != RunPhaseModel.UiState.PROVEN_WRONG);
        ok("非 DIRECT 的 SUCCESS 归未确定",
                RunPhaseModel.ofVerdict(CrackEngine.Verdict.SUCCESS,
                        EvidenceGrade.Level.INFERRED) == RunPhaseModel.UiState.UNCERTAIN);
    }

    /** 环境错误**不能**显示成密码错。 */
    private void envErrorNotPasswordProblem() {
        ok("ERROR 的界面标签不是「已证实错误」",
                !RunPhaseModel.UiState.ENV_ERROR.label(true).equals(
                        RunPhaseModel.UiState.PROVEN_WRONG.label(true)));
        ok("环境错误标签含「环境」",
                RunPhaseModel.UiState.ENV_ERROR.label(true).contains("环境"));
        ok("未确定标签不是「密码错」",
                !RunPhaseModel.UiState.UNCERTAIN.label(true).contains("密码"));
        ok("已阻止标签不是「密码错」",
                !RunPhaseModel.UiState.BLOCKED.label(true).contains("密码"));
    }

    /** UI 必须真的用这个模型，不能只写在测试里。 */
    private void uiWiringUsesModel() {
        String act;
        try {
            String path = TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/MainActivity.java");
            act = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            act = "";
        }
        ok("MainActivity 用 RunPhaseModel", act.contains("RunPhaseModel"));
        ok("有 runPhase 字段", act.contains("runPhase"));
        ok("onAttempt 不再直接标成验证中",
                !act.contains("curCandIndex = index;\n                // 正在验证"));
        ok("onAttemptResult 走 ofVerdict",
                act.contains("RunPhaseModel.ofVerdict"));
        // 服务侧必须暴露真实执行器标签供 UI 显示
        String svc;
        try {
            String path = TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/CrackService.java");
            svc = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            svc = "";
        }
        ok("服务暴露 plan()", svc.contains("public io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.Plan plan()"));
        ok("服务暴露 executorAdapter()", svc.contains("public io.github.wlmosv_png.wpaforge.cap.CommandExecutor.ShellAdapter executorAdapter()"));

        // **环境错误必须单独成列**（不并进「未确定」）
        ok("有环境错误列字段", act.contains("statEnvView"));
        ok("环境错误列标题独立",
                act.contains("\"环境错误\", \"ENV ERRORS\""));
        ok("onTallyFull 刷新环境错误列",
                act.contains("statEnvView.setText"));
        // 「已执行」不能被当成「验证过」：两者是不同列
        ok("已执行与已验证成功是两列",
                act.contains("\"已执行\", \"TRIED\"")
                        && act.contains("\"已验证成功\", \"VERIFIED\""));
    }
}

package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.AttemptPhases;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;

/**
 * **执行阶段 → 界面状态**的唯一映射（2026-11-20 第二阶段）。
 *
 * <h3>要修的问题</h3>
 * 改造前界面的「状态」是从**候选循环次数**推出来的：
 * <pre>
 *   onAttempt(index) → 立刻显示「正在试 xxx」
 *   onProgress(tested) → 进度条前进
 * </pre>
 * 于是后端只是「拿到了下一条候选、还没发任何命令」时，界面已经在说
 * 「正在验证密码」。用户看到进度在动，以为在真跑 —— 实际可能是通道早就死了、
 * 每条都立刻环境错误（真机截图：已执行 2 / 全部为 0 / 进度 2.6%）。
 *
 * <h3>现在的契约</h3>
 * <ol>
 *   <li>界面状态**只**由本类决定，输入是后端真实事件：
 *       {@link AttemptPhases}（七阶段轨迹）、{@link CrackEngine.Verdict}、
 *       以及「通道是否可用」的解析结论。</li>
 *   <li>十二个状态与后端事件**一一对应**，没有「猜」的余地：
 *       <pre>
 *   准备中     PREPARING     已解析执行器、尚未开始第一条
 *   预检通过   PRECHECK_OK   PRECHECK 阶段 ok
 *   隔离中     ISOLATING     ISOLATE 阶段进行中
 *   已提交     SUBMITTED     SUBMIT 阶段 ok（命令已交给通道）
 *   观察中     OBSERVING     OBSERVE 阶段进行中
 *   归因中     ATTRIBUTING   ATTRIBUTE 阶段进行中
 *   已验证成功 VERIFIED      verdict == SUCCESS 且 grade == DIRECT
 *   已证实错误 PROVEN_WRONG  verdict == AUTH_FAIL 且 grade == DIRECT
 *   未确定     UNCERTAIN     TIMEOUT / UNKNOWN / INCOMPLETE / NETWORK_AUTH_FAIL
 *   环境错误   ENV_ERROR     ERROR / TARGET_GONE（通道或环境问题）
 *   已阻止     BLOCKED       通道不可用，任务未开始
 *   已停止     STOPPED       用户主动停止 / 暂停
 *       </pre></li>
 *   <li><b>后端没有提交候选时，绝不显示「正在验证」</b>：
 *       {@link #verifying()} 只有 SUBMITTED 之后才为 true。</li>
 * </ol>
 *
 * <p>纯逻辑、无 Android 依赖，可离线穷举。
 */
public final class RunPhaseModel {

    /** 界面状态。声明顺序不作为优先级使用。 */
    public enum UiState {
        /** 已解析执行器，尚未开始第一条。 */
        PREPARING("准备中", "preparing"),
        /** 预检（读命令前状态）通过。 */
        PRECHECK_OK("预检通过", "precheck-ok"),
        /** 隔离中（删净旧配置 / 断开上一跳）。 */
        ISOLATING("隔离中", "isolating"),
        /** 命令已提交给通道（**不代表成功**）。 */
        SUBMITTED("已提交", "submitted"),
        /** 观察中（轮询 status 采证据）。 */
        OBSERVING("观察中", "observing"),
        /** 归因中（把证据绑定到本次尝试）。 */
        ATTRIBUTING("归因中", "attributing"),
        /** 已验证成功（SUCCESS 且 DIRECT）。 */
        VERIFIED("已验证成功", "verified"),
        /** 已证实错误（AUTH_FAIL 且 DIRECT）。 */
        PROVEN_WRONG("已证实错误", "proven-wrong"),
        /** 未确定（有执行但没拿到可归因证据）。 */
        UNCERTAIN("未确定", "uncertain"),
        /** 环境错误（通道或环境问题，不是密码问题）。 */
        ENV_ERROR("环境错误", "env-error"),
        /** 已阻止（通道不可用，任务未开始）。 */
        BLOCKED("已阻止", "blocked"),
        /** 已停止（用户主动停止 / 暂停）。 */
        STOPPED("已停止", "stopped");

        public final String zh;
        public final String key;

        UiState(String zh, String key) {
            this.zh = zh;
            this.key = key;
        }

        /** 是否属于「正在做某件事」（可以显示呼吸/流光）。 */
        public boolean active() {
            return this == ISOLATING || this == SUBMITTED
                    || this == OBSERVING || this == ATTRIBUTING
                    || this == PRECHECK_OK || this == PREPARING;
        }

        /** 是否属于终态（本条已有结论）。 */
        public boolean terminal() {
            return this == VERIFIED || this == PROVEN_WRONG
                    || this == UNCERTAIN || this == ENV_ERROR
                    || this == BLOCKED || this == STOPPED;
        }

        public String label(boolean zh) {
            return zh ? this.zh : this.key;
        }
    }

    private RunPhaseModel() {
    }

    /**
     * 由**七阶段轨迹**推出当前界面状态。
     *
     * <p>只认已经发生过的事实：没有任何阶段时给 {@link UiState#PREPARING}，
     * 绝不因为「拿到了下一条候选」就跳到「正在验证」。
     *
     * @param phases 本条尝试的阶段轨迹（可为 null）
     */
    public static UiState of(AttemptPhases phases) {
        if (phases == null) return UiState.PREPARING;
        if (phases.blocked()) {
            // 闸门失败：按失败阶段归类，但**不是**「密码错」
            AttemptPhases.Phase f = phases.failedPhase();
            if (f == AttemptPhases.Phase.PRECHECK) return UiState.ENV_ERROR;
            if (f == AttemptPhases.Phase.ISOLATE) return UiState.ENV_ERROR;
            if (f == AttemptPhases.Phase.SUBMIT) return UiState.ENV_ERROR;
            return UiState.UNCERTAIN;
        }
        // 已经走过的最后阶段决定显示什么
        java.util.List<AttemptPhases.Step> steps = phases.steps();
        if (steps.isEmpty()) return UiState.PREPARING;
        AttemptPhases.Step last = steps.get(steps.size() - 1);
        switch (last.phase) {
            case PRECHECK:
                return last.endedAtMs > 0 ? UiState.PRECHECK_OK : UiState.PREPARING;
            case ISOLATE:
                return last.endedAtMs > 0 ? UiState.PRECHECK_OK : UiState.ISOLATING;
            case SUBMIT:
                return last.endedAtMs > 0 ? UiState.SUBMITTED : UiState.ISOLATING;
            case OBSERVE:
                return last.endedAtMs > 0 ? UiState.OBSERVING : UiState.SUBMITTED;
            case ATTRIBUTE:
                return last.endedAtMs > 0 ? UiState.ATTRIBUTING : UiState.OBSERVING;
            case ADJUDICATE:
            case FINALIZE:
            default:
                return UiState.UNCERTAIN;
        }
    }

    /**
     * 由**结论**推出终态界面状态。
     *
     * @param verdict 结论
     * @param grade   证据等级（只有 DIRECT 才允许「已验证成功 / 已证实错误」）
     */
    public static UiState ofVerdict(CrackEngine.Verdict verdict,
                                    io.github.wlmosv_png.wpaforge.audit.EvidenceGrade.Level grade) {
        if (verdict == null) return UiState.UNCERTAIN;
        boolean direct = io.github.wlmosv_png.wpaforge.audit.EvidenceGrade.persistable(grade);
        switch (verdict) {
            case SUCCESS:
                // 非 DIRECT 的成功不许显示成「已验证成功」
                return direct ? UiState.VERIFIED : UiState.UNCERTAIN;
            case AUTH_FAIL:
                // 推断失败不许显示成「已证实错误」
                return direct ? UiState.PROVEN_WRONG : UiState.UNCERTAIN;
            case ERROR:
            case TARGET_GONE:
                return UiState.ENV_ERROR;
            case ABORTED:
                return UiState.STOPPED;
            case NOT_EXECUTED:
                return UiState.BLOCKED;
            case NETWORK_AUTH_FAIL:
            case TIMEOUT:
            case UNKNOWN:
            case INCOMPLETE:
            case TERMINATED_UNKNOWN:
            default:
                return UiState.UNCERTAIN;
        }
    }

    /**
     * 是否**允许**在界面上说「正在验证密码」。
     *
     * <p>这是本轮最核心的一条：只有命令真的交给通道之后
     * （{@link UiState#SUBMITTED} 及其后续阶段）才算在验证。
     * 仅仅是「拿到了下一条候选」不算。
     */
    public static boolean verifying(UiState s) {
        return s == UiState.SUBMITTED
                || s == UiState.OBSERVING
                || s == UiState.ATTRIBUTING;
    }

    /**
     * 一行状态说明（**来自后端事实**，不含推测）。
     *
     * @param s       当前状态
     * @param executor 执行器标签（如 {@code Shizuku / shell(uid=2000)}，可空）
     * @param detail  后端给的失败原因（可空）
     */
    public static String describe(UiState s, String executor, String detail, boolean zh) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.label(zh));
        if (executor != null && !executor.isEmpty()) {
            sb.append(" · ").append(executor);
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(" · ").append(detail);
        }
        return sb.toString();
    }
}

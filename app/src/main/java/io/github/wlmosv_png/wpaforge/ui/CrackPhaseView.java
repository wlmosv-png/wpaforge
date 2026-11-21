package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;

/**
 * **破解页任务状态的 UI 派生器**（2026-11-21 UI 专项）。
 *
 * <h3>这个类是什么</h3>
 * 纯展示层：把后端**已经存在**的两类事实翻译成界面能显示的状态。
 * <ul>
 *   <li>{@link TaskQueue.State} —— 队列事实（RUNNING / PAUSED / PENDING / STOPPED）<\/li>
 *   <li>{@link CrackEngine.Verdict} —— 最近一条候选的裁决结论<\/li>
 * <\/ul>
 *
 * <h3>它**不**做什么（重要）<\/h3>
 * <ul>
 *   <li><b>不产生任何新事实<\/b>：不猜测、不推算、不补全。<\/li>
 *   <li><b>不伪造阶段<\/b>：v1.9.60 后端不产生「预检 / 隔离 / 已提交 / 观察 / 归因」
 *       这类阶段事件，所以本类**没有**这些状态 —— 编造出来就是骗人。<\/li>
 *   <li><b>不参与判定<\/b>：纯 String + 颜色选择，不改变任何业务行为。<\/li>
 * <\/ul>
 *
 * <h3>状态清单（每一项都对应真实后端事实）<\/h3>
 * <pre>
 *   空闲         队列里没有任务，且没有上轮结论
 *   准备中       TaskQueue.State.PENDING
 *   运行中       TaskQueue.State.RUNNING
 *   收尾中       RUNNING + 服务标记 finishing（由调用方传入）
 *   已暂停       TaskQueue.State.PAUSED
 *   已验证成功   最近 Verdict == SUCCESS
 *   已证实错误   最近 Verdict == AUTH_FAIL（DIRECT 才到这里，由调用方保证）
 *   网络级失败   最近 Verdict == NETWORK_AUTH_FAIL
 *   未确定       最近 Verdict ∈ {TIMEOUT, UNKNOWN, INCOMPLETE, TERMINATED_UNKNOWN}
 *   环境错误     最近 Verdict ∈ {ERROR, TARGET_GONE}
 *   已中断       最近 Verdict == ABORTED
 * <\/pre>
 *
 * <p>纯逻辑、无 Android 依赖，可离线穷举。
 */
public final class CrackPhaseView {

    /** 界面状态。 */
    public enum Phase {
        /** 没有任务、也没有上轮结论。 */
        IDLE("未开始", "idle", Tone.NEUTRAL, StatusIcons.Kind.CIRCLE),
        /** 队列里有 PENDING 任务。 */
        PREPARING("准备中", "preparing", Tone.NEUTRAL, StatusIcons.Kind.DOTS),
        /** 队列里有 RUNNING 任务。 */
        RUNNING("正在运行", "running", Tone.ACCENT, StatusIcons.Kind.PLAY),
        /** RUNNING 且服务在收尾。 */
        FINISHING("收尾中", "finishing", Tone.ACCENT, StatusIcons.Kind.DOTS),
        /** 队列里有 PAUSED 任务。 */
        PAUSED("已暂停", "paused", Tone.WARN, StatusIcons.Kind.PAUSE),
        /** 最近一条结论是 SUCCESS。 */
        VERIFIED("破解成功", "verified", Tone.OK, StatusIcons.Kind.CHECK),
        /** 最近一条结论是 AUTH_FAIL + DIRECT（候选级证伪）。 */
        PROVEN_WRONG("已证实密码错误", "proven-wrong", Tone.DANGER, StatusIcons.Kind.CROSS),
        /**
         * 最近一条是网络级认证失败（不可归因到候选）。
         *
         * <p>1.9.67：主界面口径改为**中性「未命中」** —— 逐条尝试失败是正常过程，
         * 不是软件故障。真实原因仍完整保留在详情行与审计页。
         */
        NETWORK_FAIL("未命中", "no match", Tone.NEUTRAL, StatusIcons.Kind.DOT),
        /**
         * 最近一条没有可归因证据（UNKNOWN / TIMEOUT / INCOMPLETE / TERMINATED_UNKNOWN）。
         *
         * <p>1.9.67：同样改为中性「未命中」。底层 Verdict/Grade/reason 一个字未改。
         */
        UNCERTAIN("未命中", "no match", Tone.NEUTRAL, StatusIcons.Kind.DOT),
        /** 最近一条是通道/环境问题 —— 这是**真正需要用户处理**的异常。 */
        ENV_ERROR("环境异常", "env-error", Tone.DANGER, StatusIcons.Kind.WARN),
        /** 最近一条被用户中断。 */
        ABORTED("已停止", "stopped", Tone.NEUTRAL, StatusIcons.Kind.STOP);

        public final String zh;
        public final String key;
        public final Tone tone;
        /** 状态图标（Canvas 自绘，跨 ROM 一致；与文字同色）。 */
        public final StatusIcons.Kind icon;

        Phase(String zh, String key, Tone tone, StatusIcons.Kind icon) {
            this.zh = zh;
            this.key = key;
            this.tone = tone;
            this.icon = icon;
        }

        public String label(boolean isZh) {
            return isZh ? this.zh : this.key;
        }
    }

    /**
     * 色调（语义固定，不随主题变）。
     *
     * <p>任务书要求：黄=当前操作/重点、绿=真实成功、红=真实错误、灰=未知/不可用。
     */
    public enum Tone {
        /** 中性灰：未知、未开始、普通信息。 */
        NEUTRAL,
        /** 主强调黄：当前正在进行的操作。 */
        ACCENT,
        /** 警示黄：停住了或证据不足，但不是错。 */
        WARN,
        /** 绿：真实成功。 */
        OK,
        /** 红：真实错误或环境故障。 */
        DANGER
    }

    private CrackPhaseView() {
    }

    /**
     * 由**队列状态 + 最近结论**推出界面状态。
     *
     * @param queueState      {@link TaskQueue.State}（null = 队列空）
     * @param finishing       服务是否在收尾（仅 RUNNING 时有意义）
     * @param lastVerdict     最近一条候选的 Verdict（null = 还没有结论）
     * @param hasOutcomeLabel 是否已经有上轮结束结论（命中/未命中/停止）
     */
    public static Phase derive(TaskQueueState queueState, boolean finishing,
                               CrackEngine.Verdict lastVerdict, boolean hasOutcomeLabel) {
        return derive(queueState, finishing, lastVerdict, null, hasOutcomeLabel);
    }

    /**
     * 带**证据等级**的派生（2026-11-21 修复）。
     *
     * <h3>为什么要 grade</h3>
     * 旧实现只看 {@code v == AUTH_FAIL} 就报「已证实错误」（{@link Phase#PROVEN_WRONG}）。
     * 但 {@code AUTH_FAIL} 有两种等级：
     * <ul>
     *   <li>{@code DIRECT} —— 绑到本次配置的直接否认，可以叫「已证实」；</li>
     *   <li>{@code INFERRED} —— 推断（{@code EvidenceGrade.defaultOf} 的默认值），
     *       证据不足，**不能**叫「已证实」。</li>
     * </ul>
     * 修复前把两者都显示成「已证实错误」，等于在界面上把推断升级成事实。
     *
     * <p>{@code grade} 为 {@code null}（旧调用方）时保持原行为，避免破坏既有接线。
     */
    public static Phase derive(TaskQueueState queueState, boolean finishing,
                               CrackEngine.Verdict lastVerdict,
                               EvidenceGrade.Level grade, boolean hasOutcomeLabel) {
        // ① 队列事实优先：有任务在动就显示任务状态
        if (queueState == TaskQueueState.RUNNING) {
            return finishing ? Phase.FINISHING : Phase.RUNNING;
        }
        if (queueState == TaskQueueState.PAUSED) return Phase.PAUSED;
        if (queueState == TaskQueueState.PENDING) return Phase.PREPARING;

        // ② 队列已停 / 空：看最近一条结论
        Phase fromVerdict = ofVerdict(lastVerdict, grade);
        if (fromVerdict != null) return fromVerdict;

        // ③ 有上轮结束语但还没有具体 Verdict（例如队列刚清空）
        if (hasOutcomeLabel) return Phase.IDLE;

        return Phase.IDLE;
    }

    /**
     * Verdict → Phase 的**唯一映射**。
     *
     * <p>刻意用 {@code ==} 逐项列举而不是 {@code switch}，这样后端新增 Verdict
     * 时这里会编译失败，强迫我重新审视 —— 而不是静默落进 default 显示错的东西。
     *
     * @return null = 没有结论（尚未开始）
     */
    public static Phase ofVerdict(CrackEngine.Verdict v) {
        return ofVerdict(v, null);
    }

    /**
     * Verdict + 证据等级 → Phase 的**唯一映射**（2026-11-21）。
     *
     * <p>核心约束：**只有 {@code AUTH_FAIL + DIRECT} 才是「已证实错误」**。
     * {@code AUTH_FAIL + INFERRED} 必须显示成「未确定」——
     * 界面上不允许把推断说成事实。
     *
     * <p>{@code grade == null}（旧调用方）时按 {@code AUTH_FAIL} 原样映射，
     * 保持既有行为；生产接线必须传真实等级。
     */
    public static Phase ofVerdict(CrackEngine.Verdict v, EvidenceGrade.Level grade) {
        if (v == null) return null;
        if (v == CrackEngine.Verdict.SUCCESS) return Phase.VERIFIED;
        if (v == CrackEngine.Verdict.AUTH_FAIL) {
            // 关键：推断级失败**不得**显示成「已证实错误」。
            // **只有 DIRECT 才叫「已证实错误」**。
            // INFERRED / NONE 都是证据不足，必须落到「未确定」。
            if (grade == EvidenceGrade.Level.DIRECT) return Phase.PROVEN_WRONG;
            if (grade != null) return Phase.UNCERTAIN;
            // grade == null：旧调用方，保持原行为（生产接线已改为传真实等级）
            return Phase.PROVEN_WRONG;
        }
        if (v == CrackEngine.Verdict.NETWORK_AUTH_FAIL) return Phase.NETWORK_FAIL;
        if (v == CrackEngine.Verdict.TIMEOUT) return Phase.UNCERTAIN;
        if (v == CrackEngine.Verdict.UNKNOWN) return Phase.UNCERTAIN;
        if (v == CrackEngine.Verdict.INCOMPLETE) return Phase.UNCERTAIN;
        if (v == CrackEngine.Verdict.TERMINATED_UNKNOWN) return Phase.UNCERTAIN;
        if (v == CrackEngine.Verdict.ERROR) return Phase.ENV_ERROR;
        if (v == CrackEngine.Verdict.TARGET_GONE) return Phase.ENV_ERROR;
        if (v == CrackEngine.Verdict.ABORTED) return Phase.ABORTED;
        return Phase.UNCERTAIN;
    }

    /**
     * 队列状态的**最小镜像**。
     *
     * <p>为什么要镜像而不是直接用 {@code TaskQueue.State}：
     * 本类要能离线单测，而 {@code TaskQueue} 带 Android 依赖（SharedPreferences）。
     * 镜像只有 4 个值，且 {@code CrackPhaseViewTest} 会用断言钉死
     * 「镜像与真实枚举一一对应」，防止两边漂移。
     */
    public enum TaskQueueState {
        RUNNING, PAUSED, PENDING, DONE_HIT, DONE_MISS, STOPPED;

        /**
         * 把真实枚举映射过来（真实枚举为 null 时返回 null）。
         *
         * <p>2026-11-21 修复：旧镜像只有 4 个值，{@code DONE_HIT} /
         * {@code DONE_MISS} 被静默吞成 {@code STOPPED} —— 两个**语义完全不同**的
         * 终态（已命中 / 未命中）在界面上长得一样。
         * 现在逐个对应，且 {@link CrackPhaseViewTest} 用断言钉死「镜像与真实枚举
         * 一一对应」，防止再次漂移。
         */
        public static TaskQueueState of(Object realState) {
            if (realState == null) return null;
            String n = realState.toString();
            if ("RUNNING".equals(n)) return RUNNING;
            if ("PAUSED".equals(n)) return PAUSED;
            if ("PENDING".equals(n)) return PENDING;
            if ("DONE_HIT".equals(n)) return DONE_HIT;
            if ("DONE_MISS".equals(n)) return DONE_MISS;
            return STOPPED;
        }
    }

    /**
     * 这条状态是否代表「正在进行中」（用于决定要不要跑呼吸动画）。
     *
     * <p>只有 RUNNING / FINISHING 为真 —— 其余都是静止态。
     */
    public static boolean active(Phase p) {
        return p == Phase.RUNNING || p == Phase.FINISHING;
    }

    /**
     * 这条状态是否代表「本条候选已有终局结论」。
     */
    public static boolean concluded(Phase p) {
        return p == Phase.VERIFIED || p == Phase.PROVEN_WRONG
                || p == Phase.NETWORK_FAIL || p == Phase.UNCERTAIN
                || p == Phase.ENV_ERROR || p == Phase.ABORTED;
    }

    /**
     * 给用户的补充说明（**只描述已有事实，不预测**）。
     */
    public static String hint(Phase p, boolean isZh) {
        switch (p) {
            case RUNNING:
                return isZh ? "正在逐条验证候选" : "verifying candidates one by one";
            case FINISHING:
                return isZh ? "候选已跑完，正在收尾恢复配置" : "candidates done; restoring config";
            case PAUSED:
                return isZh ? "点开始可继续，进度不会丢" : "tap start to resume; progress is kept";
            case PREPARING:
                return isZh ? "正在登记目标配置" : "registering target config";
            case VERIFIED:
                return isZh ? "这条口令被证明是正确的" : "this passphrase was proven correct";
            case PROVEN_WRONG:
                return isZh ? "这条口令被证明是错的" : "this passphrase was proven wrong";
            case NETWORK_FAIL:
                return isZh ? "网络认证失败，但无法归因到某条候选" 
                        : "network auth failed; cannot attribute to a candidate";
            case UNCERTAIN:
                return isZh ? "没拿到可归因的证据，不是密码错" 
                        : "no attributable evidence; not a wrong password";
            case ENV_ERROR:
                return isZh ? "通道或环境出错，与密码无关" 
                        : "channel/environment error; unrelated to the password";
            case ABORTED:
                return isZh ? "本次尝试被中断" : "this attempt was interrupted";
            case IDLE:
            default:
                return isZh ? "选好目标和字典后即可开始" : "pick a target and dictionary to start";
        }
    }
}

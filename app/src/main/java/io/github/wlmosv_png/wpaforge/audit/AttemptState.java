package io.github.wlmosv_png.wpaforge.audit;

/**
 * 一次尝试的**状态**（C3 统一裁决架构）。
 *
 * <p>它与 {@link Evidence} 分工明确：
 * <ul>
 *   <li>{@code Evidence} 回答「观察到了什么、能绑多紧」；</li>
 *   <li>{@code AttemptState} 回答「这次尝试此刻处于什么状态」。</li>
 * </ul>
 * 裁决 = 状态 × 证据，只由 {@link Adjudicator} 做一次。
 *
 * <h3>为什么前置闸门要进裁决</h3>
 * 删净旧配置 / 断开上一条连接失败时，status 里的「目标 + COMPLETED + IP」
 * 完全可能来自**旧配置**或**上一条连接**。这种情况下任何证据都不可信 ——
 * 包括看起来完美的成功证据。把闸门结果放进状态，裁决器才能一刀切掉。
 */
public final class AttemptState {

    /** 命令发出前的两道闸门是否通过。 */
    public enum Gate {
        /** 配置已删净、需要断开时已确认断开。 */
        OK,
        /** 系统里还挂着目标的旧配置（netId 仍在）。 */
        STALE_CONFIG,
        /** status 与 list-networks 都读不到，无法确认前置状态。 */
        UNREADABLE,
        /** 上一条连接未能断开。 */
        DISCONNECT_FAILED,
        /**
         * **本条根本没有被执行的能力**（无 Root 模式下的逐条关联）。
         *
         * <h3>为什么必须单独一格，不能复用别的闸门</h3>
         * 这是「没能力做」而不是「做了但前置不满足」：
         * <ul>
         *   <li>复用 {@code STALE_CONFIG} → 文案会说「旧配置未清除」，是假话；</li>
         *   <li>复用 {@code UNREADABLE} → 文案会说「读不到状态」，也是假话；</li>
         *   <li>走正常裁决路径 → 会因为没有证据落到「未连接」，再被下游
         *       理解成「这条密码不对」—— <b>这就是伪造结论</b>。</li>
         * </ul>
         * 单独一格的作用是：让裁决器能一眼把它归到 UNKNOWN，
         * 并让下游的 TriedSet / 游标 / 账本**都不动**（没有观察到的失败，
         * 就不许记账）。
         */
        NOT_CAPABLE
    }

    public final String attemptId;
    /** 连接生命周期三态。{@code UNKNOWN} **不得**当作 {@code TERMINATED}。 */
    public final CrackEngine.Lifecycle lifecycle;
    public final Gate gate;
    /** 本次命令是否建出了配置（拿到 netId）。只作诊断，**不构成归属证据**。 */
    public final boolean netIdKnown;

    public AttemptState(String attemptId, CrackEngine.Lifecycle lifecycle,
                        Gate gate, boolean netIdKnown) {
        this.attemptId = attemptId == null ? "" : attemptId;
        this.lifecycle = lifecycle == null ? CrackEngine.Lifecycle.UNKNOWN : lifecycle;
        this.gate = gate == null ? Gate.OK : gate;
        this.netIdKnown = netIdKnown;
    }

    /** 便捷：闸门是否放行。 */
    public boolean gateOk() {
        return gate == Gate.OK;
    }

    @Override
    public String toString() {
        return "state(" + attemptId + " lc=" + lifecycle + " gate=" + gate
                + " netId=" + (netIdKnown ? "known" : "unknown") + ")";
    }
}
package io.github.wlmosv_png.wpaforge.audit;

/**
 * **AT3 的结构性前提** —— 把「凭什么能说这条成功是本候选造成的」写成可断言的对象。
 *
 * <h3>为什么需要它（2026-11-20 审计）</h3>
 * {@link SuccessRule} 的三条件（SSID + COMPLETED + IP）只是**协议层事实**。
 * 「这条连接是本候选造成的」靠的是**命令之前的两道闸门**：
 * <ol>
 *   <li><b>净检</b>：目标配置已删净，且回读确认（{@code forgetTargetByShell} +
 *       {@code netIdOfTarget < 0}）；</li>
 *   <li><b>断开确认</b>：命令前若还连着目标，已真正断开并确认
 *       （{@code baselineDecision} + {@code disconnectAndVerify}）。</li>
 * </ol>
 *
 * <p>历史上这两个前提散落在 {@code tryOneShell} 的几百行里，**没有任何一个对象
 * 能回答「这一次尝试的前提到底成不成立」**。于是：
 * <ul>
 *   <li>写测试只能靠 grep 源码片段，改个变量名就红；</li>
 *   <li>代码里漏掉一道闸门，成功判据就悄悄变宽，而没有任何断言会失败；</li>
 *   <li>报告里说的「四条件同行」与代码里的「三条件 + netId 否决」对不上。</li>
 * </ul>
 *
 * <p>本类把前提收敛成一个不可变值对象，{@link Adjudicator} 在给 AT3 之前
 * **必须**拿到一个 {@link #satisfied()} 为真的实例。前提不成立时，
 * 三条件即使全部成立也只能降级为 UNKNOWN —— 这是硬约束。
 *
 * <p>纯逻辑、无 Android 依赖，可离线穷举。
 */
public final class AttributionPrecondition {

    /** 净检：目标旧配置已删净并回读确认。 */
    public final boolean configCleared;
    /** 断开：命令前若连着目标，已断开并确认。 */
    public final boolean linkDroppedOrAbsent;
    /** 命令前状态可读（status 或 list-networks 至少一个读到了）。 */
    public final boolean preStateReadable;
    /** 本条用的是本次新建的配置（不是复用了旧 netId）。 */
    public final boolean freshConfig;

    private AttributionPrecondition(boolean cleared, boolean dropped,
                                    boolean readable, boolean fresh) {
        this.configCleared = cleared;
        this.linkDroppedOrAbsent = dropped;
        this.preStateReadable = readable;
        this.freshConfig = fresh;
    }

    /** 全部前提成立 —— 只有这种实例才允许支撑 AT3。 */
    public static AttributionPrecondition allSatisfied() {
        return new AttributionPrecondition(true, true, true, true);
    }

    /** 净检失败（旧配置删不掉）。 */
    public static AttributionPrecondition staleConfig() {
        return new AttributionPrecondition(false, true, true, false);
    }

    /** 断开失败（上一条的连接还在）。 */
    public static AttributionPrecondition linkNotDropped() {
        return new AttributionPrecondition(true, false, true, false);
    }

    /** 命令前状态读不到（无法确认系统是否还挂着目标）。 */
    public static AttributionPrecondition preStateUnknown() {
        return new AttributionPrecondition(true, true, false, false);
    }

    /** 通用构造（生产代码按实际探测结果填）。 */
    public static AttributionPrecondition of(boolean cleared, boolean dropped,
                                             boolean readable, boolean fresh) {
        return new AttributionPrecondition(cleared, dropped, readable, fresh);
    }

    /** 是否满足 AT3 的全部结构性前提。 */
    public boolean satisfied() {
        return configCleared && linkDroppedOrAbsent && preStateReadable && freshConfig;
    }

    /**
     * 不满足时的原因码（进日志与断言，**不含口令**）。
     *
     * @return 空串 = 满足；否则是第一个不满足的前提名
     */
    public String blockingReason() {
        if (!configCleared) return "stale-config";
        if (!linkDroppedOrAbsent) return "link-not-dropped";
        if (!preStateReadable) return "pre-state-unknown";
        if (!freshConfig) return "not-fresh-config";
        return "";
    }

    /**
     * 给定这份前提，{@link Evidence.Attribution} 最高能给到哪一级。
     *
     * <p>这是本类存在的核心价值：**前提不足时不许给 AT3**。
     *
     * @param observed 实际观察到的归属（来自事件本身）
     */
    public Evidence.Attribution capAttribution(Evidence.Attribution observed) {
        if (observed == null) return Evidence.Attribution.NONE;
        // 前提不满足 → AT3 一律降到 AT2（"同一个 AP" 仍然成立，但"本次造成的"不成立）
        if (!satisfied() && observed == Evidence.Attribution.AT3) {
            return Evidence.Attribution.AT2;
        }
        return observed;
    }

    @Override
    public String toString() {
        return "Precondition{cleared=" + configCleared
                + ", dropped=" + linkDroppedOrAbsent
                + ", readable=" + preStateReadable
                + ", fresh=" + freshConfig
                + ", satisfied=" + satisfied() + "}";
    }
}

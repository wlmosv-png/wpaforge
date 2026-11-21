package io.github.wlmosv_png.wpaforge.audit;

/**
 * 判据 D（{@code dumpsys wifi} framework 级证据）的**采集时序纯逻辑**。
 *
 * <p>纯逻辑、无 Android 依赖、可离线穷举 —— 把「什么时候该查 dumpsys」
 * 从 {@link CrackEngine} 的主循环里抽出来，正是为了能对时序缺陷做单元测试。
 *
 * <h3>1.9.64 的缺陷（真机实测）<\/h3>
 * <pre>
 *   DUMPS_PROBE_AT_MS = { 2600, 3600 }        ← 只有两个固定时刻
 *   dumpsProbedSlot    = 一次性               ← 查过 3600 之后再也不查
 * <\/pre>
 * 而真机的认证失败事件通常在连接尝试结束后才写入 {@code mConnectionEvents}，
 * 实测耗时 **3.8~4.9 秒**（部分 AP / 部分 ROM / 弱信号重试会更晚）。
 * 2600ms 与 3600ms 两个探测点都早于这部分事件，且 {@code dumpsProbedSlot}
 * 的一次性限制让「提前查询后不再查询」成为必然 —— 于是真实的
 * {@code AUTH_FAILURE_WRONG_PSWD} 被漏读，错误密码降级为 UNKNOWN。
 *
 * <h3>1.9.65 的目标机制（生命周期驱动 + 有限轮询）<\/h3>
 * <ol>
 *   <li><b>固定时刻表</b>（{@link #SCHEDULE_MS}）：覆盖已观测样本，作为兜底；
 *       5200ms 是兜底，<b>不是</b>唯一修复；</li>
 *   <li><b>生命周期终止触发</b>：观察到连接进入 {@link CrackEngine.Lifecycle#TERMINATED}
 *       后，立即触发一次采集（不必等下一个固定时刻）；</li>
 *   <li><b>主循环退出后的兜底轮询</b>：循环因超时退出时，认证失败事件可能尚未落盘，
 *       在 {@link #TERMINATION_GRACE_MS} 截止时间内按 {@link #TERMINATION_POLL_MS}
 *       有间隔地**有限**轮询；</li>
 *   <li><b>一次查询没有证据，绝不永久标记「已探测完毕」</b> —— 时刻表推进、
 *       终止触发、兜底轮询三者并行，只要在截止时间内就还能再查；</li>
 *   <li>达到截止时间仍无证据，才交给裁决层降级为 UNKNOWN / INCOMPLETE。</li>
 * </ol>
 *
 * <p><b>安全不变量</b>：本类只决定「什么时候查」，**不决定**「查到什么算什么」。
 * 归因（时间窗口、配置归属、uid=0、WRONG_PSWD）一律由
 * {@link WifiEvidenceProbe} 与 {@link Adjudicator} 负责，本类不产生任何结论。
 */
public final class DumpsProbePlan {

    /**
     * 循环内的固定采集时刻（相对命令发出时刻 {@code commandAt}，单位毫秒）。
     *
     * <p>2600 / 3600 保留 1.9.64 的既有节奏；5200 是**兜底**，覆盖真机实测的
     * 3.8~4.9s 落盘区间。它不是唯一修复 —— 晚于 5200ms 的事件由
     * 终止触发与兜底轮询接管。
     */
    public static final long[] SCHEDULE_MS = { 2600L, 3600L, 5200L };

    /** 相邻两次采集的最小间隔（相对 commandAt 的已过毫秒），防止终止态下密集查询。 */
    public static final long MIN_GAP_MS = 500L;

    /** 主循环退出后，认证失败证据的兜底复核截止时长。 */
    public static final long TERMINATION_GRACE_MS = 3000L;

    /** 兜底复核的轮询间隔。 */
    public static final long TERMINATION_POLL_MS = 600L;

    /** 下一个待触发的固定时刻（相对 commandAt）；超出时刻表后为 {@link Long#MAX_VALUE}。 */
    private long nextProbeAtMs;

    /** 上一次实际采集时的已过毫秒；-1 = 还没采集过。 */
    private long lastProbeElapsedMs = -1L;

    /** 是否已因生命周期终止做过采集。 */
    private boolean terminationProbed;

    public DumpsProbePlan() {
        this.nextProbeAtMs = SCHEDULE_MS[0];
    }

    /**
     * 是否应在此刻（{@code elapsedMs} 相对 commandAt）做一次 dumpsys 采集。
     *
     * @param elapsedMs        自命令发出以来的毫秒数
     * @param configConfirmedAbsent 前置闸门已确认目标配置删净（判据 D 的归属前提）
     * @param lifecycle        当前观察到的连接生命周期
     */
    public boolean due(long elapsedMs, boolean configConfirmedAbsent,
                       CrackEngine.Lifecycle lifecycle) {
        if (!configConfirmedAbsent) return false;
        // ① 固定时刻表
        if (elapsedMs >= nextProbeAtMs) return true;
        // ② 生命周期终止触发：连接已结束，立即采一次（不必等下一个固定时刻）
        if (lifecycle == CrackEngine.Lifecycle.TERMINATED
                && elapsedMs - lastProbeElapsedMs >= MIN_GAP_MS) {
            return true;
        }
        return false;
    }

    /** 记录一次采集，并把时刻表推进到下一个尚未到达的固定时刻。 */
    public void markProbed(long elapsedMs) {
        lastProbeElapsedMs = elapsedMs;
        for (long t : SCHEDULE_MS) {
            if (t > elapsedMs) {
                nextProbeAtMs = t;
                return;
            }
        }
        // 已越过全部固定时刻：后续采集只由终止触发与兜底轮询接管。
        nextProbeAtMs = Long.MAX_VALUE;
    }

    /** 标记一次因生命周期终止而做的采集。 */
    public void markTerminationProbed() {
        this.terminationProbed = true;
    }

    /** 主循环退出后，兜底复核的截止时刻（相对 commandAt）。 */
    public long graceDeadlineMs(long elapsedMs) {
        return elapsedMs + TERMINATION_GRACE_MS;
    }

    /** 兜底轮询：在截止时刻之前，是否还应再查一次。 */
    public boolean graceDue(long elapsedMs, long graceDeadlineMs) {
        return elapsedMs < graceDeadlineMs;
    }

    /** 是否已经做过至少一次采集。 */
    public boolean everProbed() {
        return lastProbeElapsedMs >= 0;
    }

    /** 是否已因生命周期终止做过采集（供判词说明）。 */
    public boolean isTerminationProbed() {
        return terminationProbed;
    }

    /** 上一个固定时刻表里、尚未被越过的时刻（测试用）。 */
    public long nextScheduledMs() {
        return nextProbeAtMs;
    }

    /** 上一次采集时的已过毫秒（测试用）。 */
    public long lastProbeElapsedMs() {
        return lastProbeElapsedMs;
    }
}

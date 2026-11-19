package io.github.wlmosv_png.wpaforge.cap;

/**
 * 单通道的**请求占用仲裁**（纯逻辑，可离线穷举）。
 *
 * <h3>存在的唯一理由</h3>
 * 一个 {@code WifiNetworkSpecifier} 通道在同一时刻只允许有一个在途请求。
 * 改造前用 {@code currentCm} / {@code currentCallback} 两个通道级字段记录在途状态，
 * 于是：旧请求的看门狗醒来时读到的已经是新请求的回调，会把**新**请求注销掉。
 * 这种串扰在真机上表现为「候选莫名其妙全都没结论」，且极难复现。
 *
 * <p>现在把所有权的判定收敛成一个对象：谁持有槽位、谁可以完成、谁可以释放，
 * 全部按 {@code attemptId} 校验。规则只有四条：
 * <ol>
 *   <li>{@link #acquire} 只有在**上一个请求已经 release** 之后才成功；</li>
 *   <li>{@link #release} 必须带对 id —— 拿旧 id 释放不掉新请求；</li>
 *   <li>关闭后不再接受新请求，{@link #close} 会把在途请求的 id 交还给调用方去注销；</li>
 *   <li>「是否过期」只由单调时间计算，不看墙钟。</li>
 * </ol>
 *
 * <p>纯逻辑、无 Android 依赖 —— 这是 {@code SpecifierChannelFixTest} 能真正测到
 * D2/D3/D9 的前提。
 */
public final class AttemptSlot {

    /** 上一个请求还没结束（回调已给结论但资源尚未释放，或仍在等回调）。 */
    public static final String R_BUSY = "busy";
    /** 通道已关闭，不再接受新请求。 */
    public static final String R_CLOSED = "closed";
    /** attemptId 非法（空串 / 空白）。 */
    public static final String R_BAD_ID = "bad-attempt-id";

    private String activeId;
    private long deadlineElapsed;
    private boolean closed;
    /** 已完成的请求数（诊断用）。 */
    private int completed;

    /**
     * 尝试占用槽位。
     *
     * @param attemptId        本条候选的尝试标识（必须唯一且非空）
     * @param deadlineElapsed  单调时钟下的截止时刻（{@code SystemClock.elapsedRealtime()} 口径）
     * @return 空串 = 占用成功；否则是原因码 {@link #R_BUSY} / {@link #R_CLOSED} / {@link #R_BAD_ID}
     */
    public synchronized String acquire(String attemptId, long deadlineElapsed) {
        if (attemptId == null || attemptId.trim().isEmpty()) return R_BAD_ID;
        if (closed) return R_CLOSED;
        if (activeId != null) return R_BUSY;
        activeId = attemptId;
        this.deadlineElapsed = deadlineElapsed;
        return "";
    }

    /**
     * 释放槽位。
     *
     * <p><b>只有持有者能释放</b>：id 不匹配一律拒绝。这一条就是 D2 的修复本体 ——
     * 旧请求的清理代码拿着自己的 id 来释放，绝不可能把新请求的槽位清空。
     *
     * @return 是否真的释放了
     */
    public synchronized boolean release(String attemptId) {
        if (attemptId == null || !attemptId.equals(activeId)) return false;
        activeId = null;
        deadlineElapsed = 0L;
        completed++;
        return true;
    }

    /** 当前持有者 id；空闲返回空串。 */
    public synchronized String activeId() {
        return activeId == null ? "" : activeId;
    }

    public synchronized boolean idle() {
        return activeId == null;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int completedCount() {
        return completed;
    }

    /** 本条是否已经超过截止时刻（单调时钟）。没有在途请求时恒 false。 */
    public synchronized boolean expired(long nowElapsed) {
        return activeId != null && deadlineElapsed > 0L && nowElapsed >= deadlineElapsed;
    }

    /** 截止时刻；无在途请求返回 0。 */
    public synchronized long deadline() {
        return activeId == null ? 0L : deadlineElapsed;
    }

    /**
     * 关闭通道。
     *
     * @return 被中止的在途请求 id；没有在途请求返回空串。
     *         调用方必须据此注销该请求的回调 —— 只置一个 flag 不算取消。
     */
    public synchronized String close() {
        closed = true;
        String id = activeId;
        activeId = null;
        deadlineElapsed = 0L;
        return id == null ? "" : id;
    }

    /** 取消在途请求（不关闭通道）。返回被取消的 id，或空串。 */
    public synchronized String cancelActive() {
        String id = activeId;
        activeId = null;
        deadlineElapsed = 0L;
        return id == null ? "" : id;
    }

    /** 重新打开（供下次任务复用通道实例）。 */
    public synchronized void reopen() {
        closed = false;
    }
}

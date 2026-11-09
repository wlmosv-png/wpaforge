package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS 任务的运行状态机（纯逻辑，可离线测试）。
 *
 * <h3>为什么单独抽出来</h3>
 * 用户实测「停止按钮点了没反应」。根因是 UI 层把「运行中」与「是否可停」绑错了
 * （运行中反而禁用了停止按钮），没有一处权威的状态机来约束「何时能开始、何时能停、
 * 重复点击怎么办、线程退出后回到哪」。把这三态固定下来，UI 只查询、不各自推断。
 *
 * <h3>状态</h3>
 * <pre>
 *   IDLE ──requestStart()──▶ RUNNING ──requestStop()──▶ STOPPING
 *     ▲                                                      │
 *     └──────────────── workerExited() ─────────────────────┘
 * </pre>
 *
 * 规则：只有 IDLE 能开始；只有 RUNNING 能发出停止；STOPPING 期间重复停止返回 false；
 * 任何状态下 workerExited() 都回到 IDLE（线程真的退出才算停）。
 *
 * <p>线程安全：攻击线程与 UI 线程都会碰它，所有方法加锁。
 */
public final class WpsRunState {

    // ---- TaskExecutionState（任务执行状态，与"判定结果/证据等级"彻底分开） ----
    /** 从未启动或已复位。 */
    public static final int IDLE = 0;
    /** 正在跑。 */
    public static final int RUNNING = 1;
    /** 用户已请求停止、等当前操作收尾。 */
    public static final int STOPPING = 2;
    /** 用户停止后线程已退出（**不是失败**，也不是 PIN 错）。 */
    public static final int STOPPED = 3;
    /** 候选跑完 / 命中，自然结束。 */
    public static final int COMPLETED = 4;
    /** 异常终止。 */
    public static final int FAILED = 5;

    private int state = IDLE;

    public synchronized int state() {
        return state;
    }

    public synchronized boolean isIdle() {
        return state == IDLE;
    }

    public synchronized boolean isRunning() {
        return state == RUNNING;
    }

    public synchronized boolean isStopping() {
        return state == STOPPING;
    }

    /** 是否处于"活动"状态（RUNNING/STOPPING）—— 决定能否开始/能否停止。 */
    public synchronized boolean isActive() {
        return state == RUNNING || state == STOPPING;
    }

    /** 是否处于终态（STOPPED/COMPLETED/FAILED）。 */
    public synchronized boolean isTerminal() {
        return state == STOPPED || state == COMPLETED || state == FAILED;
    }

    /**
     * 请求开始。**任何非活动状态**都可开始（IDLE/STOPPED/COMPLETED/FAILED），
     * 活动状态（RUNNING/STOPPING）拒绝 —— 保证"停止后能安全重新开始"。
     */
    public synchronized boolean requestStart() {
        if (isActive()) return false;
        state = RUNNING;
        return true;
    }

    /** 请求停止。只有 RUNNING 成功；已在停止中/空闲返回 false（防重复停止）。 */
    public synchronized boolean requestStop() {
        if (state != RUNNING) return false;
        state = STOPPING;
        return true;
    }

    /**
     * 执行线程退出（终态版本）。
     *
     * @param terminal {@link #STOPPED} / {@link #COMPLETED} / {@link #FAILED}；
     *                 其他值按 COMPLETED 处理
     * @return 此前是否处于活动状态
     */
    public synchronized boolean workerExited(int terminal) {
        boolean wasActive = isActive();
        state = (terminal == STOPPED || terminal == FAILED) ? terminal : COMPLETED;
        return wasActive;
    }

    /** 执行线程退出（旧签名，复位到 IDLE；仅供兼容与测试）。 */
    public synchronized boolean workerExited() {
        boolean wasActive = isActive();
        state = IDLE;
        return wasActive;
    }

    /** 状态名（日志用）。 */
    public synchronized String label() {
        switch (state) {
            case RUNNING: return "RUNNING";
            case STOPPING: return "STOPPING";
            case STOPPED: return "STOPPED";
            case COMPLETED: return "COMPLETED";
            case FAILED: return "FAILED";
            default: return "IDLE";
        }
    }
}

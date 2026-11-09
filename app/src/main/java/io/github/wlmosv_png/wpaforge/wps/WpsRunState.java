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

    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int STOPPING = 2;

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

    /** 请求开始。只有 IDLE 成功；运行中/停止中返回 false（防重复启动）。 */
    public synchronized boolean requestStart() {
        if (state != IDLE) return false;
        state = RUNNING;
        return true;
    }

    /** 请求停止。只有 RUNNING 成功；已在停止中/空闲返回 false（防重复停止）。 */
    public synchronized boolean requestStop() {
        if (state != RUNNING) return false;
        state = STOPPING;
        return true;
    }

    /** 执行线程退出：任何状态都回到 IDLE。返回此前是否处于运行或停止中。 */
    public synchronized boolean workerExited() {
        boolean wasActive = state != IDLE;
        state = IDLE;
        return wasActive;
    }

    /** 状态名（日志用）。 */
    public synchronized String label() {
        switch (state) {
            case RUNNING: return "RUNNING";
            case STOPPING: return "STOPPING";
            default: return "IDLE";
        }
    }
}

package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * WPS 任务生命周期（纯逻辑）。
 *
 * <h3>职责</h3>
 * 把「运行状态机」{@link WpsRunState} 与「断点存储」{@link WpsCheckpointStore}
 * 收在同一个入口，并维护**当前任务绑定**（任务 ID / 目标 / 策略），
 * 供 UI 与执行链查询"现在能不能开始、能不能停、断点属于谁"。
 *
 * <h3>为什么不把断点直接留在 Activity</h3>
 * 断点属于谁（BSSID/SSID/策略）必须与"当前任务"同一处判定，
 * 否则换目标后"继续"会串到别人的进度上（历史事故）。
 */
public final class WpsTaskManager {

    /** 当前任务绑定。 */
    public static final class Binding {
        public String taskId = "";
        public String bssid = "";
        public String ssid = "";
        public int mode = -1;
        public long startedAtMs;
        public boolean bound;

        public String label() {
            return bound ? (taskId.isEmpty() ? "(unnamed)" : taskId) : "(none)";
        }
    }

    private final WpsRunState run = new WpsRunState();
    private final WpsCheckpointStore store;      // 可为 null（测试/未配置）
    private final Binding binding = new Binding();

    public WpsTaskManager(WpsCheckpointStore store) {
        this.store = store;
    }

    // ---------------- 运行状态 ----------------

    public WpsRunState state() {
        return run;
    }

    public boolean isRunning() {
        return run.isRunning();
    }

    public boolean isStopping() {
        return run.isStopping();
    }

    public boolean isIdle() {
        return run.isIdle();
    }

    // ---------------- 绑定与生命周期 ----------------

    /** 绑定新任务并进入 RUNNING。若已有任务在跑则拒绝（返回 false）。 */
    public synchronized boolean begin(String taskId, String bssid, String ssid, int mode, long nowMs) {
        if (!run.requestStart()) return false;
        binding.taskId = taskId == null ? "" : taskId;
        binding.bssid = bssid == null ? "" : bssid;
        binding.ssid = ssid == null ? "" : ssid;
        binding.mode = mode;
        binding.startedAtMs = nowMs;
        binding.bound = true;
        return true;
    }

    /** 只启动运行状态（不绑定任务；绑定用 {@link #bind}）。 */
    public boolean beginRun() {
        return run.requestStart();
    }

    /** 记录任务绑定（任务创建后调用）。 */
    public synchronized void bind(String taskId, String bssid, String ssid, int mode, long nowMs) {
        binding.taskId = taskId == null ? "" : taskId;
        binding.bssid = bssid == null ? "" : bssid;
        binding.ssid = ssid == null ? "" : ssid;
        binding.mode = mode;
        binding.startedAtMs = nowMs;
        binding.bound = true;
    }

    /** 请求停止（幂等）。 */
    public boolean requestStop() {
        return run.requestStop();
    }

    /** 执行线程退出：回到 IDLE。绑定保留（供历史/收尾读），但不代表仍在跑。 */
    public boolean workerExited() {
        return run.workerExited();
    }

    public synchronized Binding binding() {
        return binding;
    }

    // ---------------- 断点 ----------------

    /** 保存断点（写入前会带上当前绑定，便于后续校验归属）。 */
    public synchronized boolean saveCheckpoint(WpsCheckpoint cp) {
        if (store == null || cp == null) return false;
        cp.taskId = binding.taskId;
        cp.updatedAtMs = System.currentTimeMillis();
        return store.save(cp);
    }

    /** 加载断点并校验归属（bssid/ssid/mode 三者与给定值一致才可用）。 */
    public WpsCheckpoint.Parse loadFor(String bssid, String ssid, int mode) {
        if (store == null) {
            WpsCheckpoint.Parse p = new WpsCheckpoint.Parse();
            p.error = "断点存储不可用";
            return p;
        }
        return store.loadFor(bssid, ssid, mode);
    }

    /** 磁盘上是否有任意断点（只用于「重置进度」按钮的可用性）。 */
    public boolean hasAnyCheckpoint() {
        if (store == null) return false;
        try {
            return store.load().ok();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只清断点（不动历史台账）。 */
    public boolean clearCheckpoint() {
        if (store == null) return false;
        try {
            return store.clear();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供 UI 展示的"能不能续跑"（除断点外还要求当前有绑定目标）。 */
    public boolean resumeReady(String bssid, String ssid, int mode) {
        if (bssid == null || bssid.isEmpty()) return false;
        return loadFor(bssid, ssid, mode).ok();
    }
}

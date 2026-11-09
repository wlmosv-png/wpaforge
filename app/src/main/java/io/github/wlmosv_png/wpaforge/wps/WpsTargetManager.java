package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * WPS 目标与扫描状态的**唯一权威**（纯逻辑，可离线测）。
 *
 * <h3>它解决的问题</h3>
 * 现状把「扫描状态」「目标列表」「当前选中」摊成 MainActivity 里的几个裸字段，
 * 各处自行推断 —— 直接导致过两类真实 bug：
 * <ul>
 *   <li>顶部目标卡用了建页时写死的局部变量 → 扫描完成后仍显示"待检测"；</li>
 *   <li>扫描中/完成/失败三种状态混用同一个布尔 → 空态文案错位。</li>
 * </ul>
 * 现在状态只在这里迁移，UI 只读 {@link Snapshot}，不推断。
 *
 * <h3>状态</h3>
 * <pre>
 *   NEVER ──beginScan()──▶ SCANNING ──finishScan()──▶ DONE
 *                            │
 *                            └────failScan()───────▶ FAILED
 *   DONE / FAILED ──beginScan()──▶ SCANNING（重新扫描）
 * </pre>
 *
 * <h3>修订号</h3>
 * 任何变更都会使 {@link #revision()} 自增。UI 缓存上次渲染的修订号，
 * 只在变化时重绘 —— 这是"不会出现状态已变但界面没刷"的机制性保证。
 */
public final class WpsTargetManager {

    public static final int NEVER = 0;
    public static final int SCANNING = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;

    /** 扫描结果（目标列表由本类持有，外部通过 {@link #targets()} 读写）。 */
    private final List<WpsController.Target> targets = new ArrayList<>();
    private String selectedBssid = "";

    private int state = NEVER;
    private long lastScanAtMs;
    private int rawCount;
    private int wpsCount;
    private String lastError = "";
    private long revision;

    // ---------------- 状态 ----------------

    public synchronized int scanState() {
        return state;
    }

    public synchronized boolean isScanning() {
        return state == SCANNING;
    }

    public synchronized boolean neverScanned() {
        return state == NEVER;
    }

    public synchronized long lastScanAt() {
        return lastScanAtMs;
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized int rawCount() {
        return rawCount;
    }

    public synchronized int wpsCount() {
        return wpsCount;
    }

    public synchronized long revision() {
        return revision;
    }

    // ---------------- 状态迁移 ----------------

    /** 开始扫描。清空错误原因；**不动目标列表与选中**（避免扫描中列表闪空）。 */
    public synchronized void beginScan() {
        state = SCANNING;
        lastError = "";
        revision++;
    }

    /**
     * 扫描成功结束。
     *
     * @param list     解析出的目标（替换现有列表）
     * @param rawCount 原始 AP 数（含未检测到 WPS 的）
     * @param wpsCount 其中检测到 WPS 信息的条数
     */
    public synchronized void finishScan(List<WpsController.Target> list, int rawCount, int wpsCount) {
        targets.clear();
        if (list != null) targets.addAll(list);
        this.rawCount = rawCount;
        this.wpsCount = wpsCount;
        state = DONE;
        lastScanAtMs = System.currentTimeMillis();
        lastError = "";
        reconcileSelection();
        revision++;
    }

    /** 扫描失败结束。**不替换目标列表**（保留上一次的结果，避免误清）。 */
    public synchronized void failScan(String reason) {
        state = FAILED;
        lastError = reason == null ? "" : reason;
        revision++;
    }

    /** 记录扫描失败原因（由流式诊断行填充）。 */
    public synchronized void setError(String err) {
        lastError = err == null ? "" : err;
        revision++;
    }

    /** 清空错误原因。 */
    public synchronized void clearError() {
        if (lastError.isEmpty()) return;
        lastError = "";
        revision++;
    }

    /** 显式清空（如换目标）。 */
    public synchronized void clearTargets() {
        targets.clear();
        selectedBssid = "";
        revision++;
    }

    // ---------------- 目标列表 ----------------

    /** 当前目标列表（**活引用**，调用方可直接 add/remove；变更后请调用 {@link #touch()}）。 */
    public synchronized List<WpsController.Target> targets() {
        return targets;
    }

    /**
     * 通知"列表被外部改动过"：重新校验选中项是否仍存在，并推进修订号。
     *
     * <p>存在的原因：{@link #targets()} 返回活引用，历史代码会直接往里 add，
     * 这里提供一个显式的"对账"入口。
     */
    public synchronized void touch() {
        reconcileSelection();
        revision++;
    }

    /** 批量替换列表（等价 finishScan 的列表部分，但不改扫描状态）。 */
    public synchronized void setTargets(List<WpsController.Target> list) {
        targets.clear();
        if (list != null) targets.addAll(list);
        reconcileSelection();
        revision++;
    }

    /** 按 BSSID 找目标；找不到返回 null。 */
    public synchronized WpsController.Target find(String bssid) {
        if (bssid == null || bssid.isEmpty()) return null;
        for (WpsController.Target t : targets) {
            if (t != null && bssid.equalsIgnoreCase(t.bssid)) return t;
        }
        return null;
    }

    // ---------------- 选择 ----------------

    /** 当前选中的目标；未选或选中项已消失 → null。 */
    public synchronized WpsController.Target selected() {
        if (selectedBssid.isEmpty()) return null;
        return find(selectedBssid);
    }

    public synchronized String selectedBssid() {
        return selectedBssid;
    }

    /**
     * 选中目标。
     *
     * @return 是否发生了改变（UI 据此决定要不要重绘）
     */
    public synchronized boolean select(WpsController.Target t) {
        String id = t == null ? "" : t.bssid;
        if (id.equalsIgnoreCase(selectedBssid)) return false;
        selectedBssid = id;
        revision++;
        return true;
    }

    public synchronized void clearSelection() {
        if (selectedBssid.isEmpty()) return;
        selectedBssid = "";
        revision++;
    }

    /**
     * 对账：选中项若已不在列表里 → 清空选择（"目标消失"必须刷新）。
     *
     * @return 是否发生了变化
     */
    public synchronized boolean reconcileSelection() {
        if (selectedBssid.isEmpty()) return false;
        if (find(selectedBssid) != null) return false;
        selectedBssid = "";
        revision++;
        return true;
    }

    // ---------------- 快照 ----------------

    /** UI 只读快照。 */
    public static final class Snapshot {
        public int scanState;
        public long lastScanAtMs;
        public int rawCount;
        public int wpsCount;
        public String lastError = "";
        public int targetCount;
        public String selectedBssid = "";
        public long revision;

        /** 是否应该提示"去扫描"（从未扫过）。 */
        public boolean shouldPromptScan() {
            return scanState == NEVER;
        }

        /** 是否应该提示"扫描中"。 */
        public boolean scanning() {
            return scanState == SCANNING;
        }

        /** 是否有目标可选。 */
        public boolean hasTargets() {
            return targetCount > 0;
        }

        /** 扫描结束但一个都没有。 */
        public boolean emptyAfterScan() {
            return scanState == DONE && targetCount == 0;
        }

        /** 扫描失败。 */
        public boolean failed() {
            return scanState == FAILED;
        }
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.scanState = state;
        s.lastScanAtMs = lastScanAtMs;
        s.rawCount = rawCount;
        s.wpsCount = wpsCount;
        s.lastError = lastError;
        s.targetCount = targets.size();
        s.selectedBssid = selectedBssid;
        s.revision = revision;
        return s;
    }
}

package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS 页面的**只读状态快照**（门面）。
 *
 * <h3>职责</h3>
 * UI 渲染前只读这一个对象，**不再自行推断业务状态**。
 * 每个字段都来自某个管理器，不存在"UI 猜"的成分。
 *
 * <h3>为什么需要门面</h3>
 * 历史 bug 的共同形态就是"UI 从多个散字段拼状态"：扫描是否完成、能不能开始、
 * 选中了谁、能力算什么 —— 拼错一处就出现"扫描完成仍显示待检测"。
 * 门面把这些字段一次性组装、只读暴露。
 */
public final class WpsFacade {

    private WpsFacade() {
    }

    /** 只读快照。 */
    public static final class UiState {
        // —— 扫描 / 目标 ——
        public int scanState;
        public boolean scanning;
        public boolean neverScanned;
        public int targetCount;
        public int rawCount;
        public int wpsCount;
        public String scanError = "";
        public long lastScanAtMs;
        public String selectedBssid = "";

        // —— 任务 ——
        public boolean idle;
        public boolean running;
        public boolean stopping;
        public String taskId = "";

        // —— 策略 ——
        public int strategyMode = -1;
        public String strategyLabel = "";
        public boolean strategyReady;
        public String strategyReason = "";
        /** 当前策略能产出的候选条数。 */
        public int candidateCount;

        // —— 能力 ——
        public String capabilityVerdict = WpsCapabilityManager.V_UNKNOWN;
        public String capabilityLabel = "";
        public boolean capabilityVerified;      // 恒 false
        public boolean capabilityStale;

        /** 修订号：任何上游状态变化都会让它改变；UI 据此决定是否重绘。 */
        public long revision;

        // —— 派生（只读判断，不在 UI 里重算） ——
        public boolean canStart;
        public boolean canStop;

        /** 空态应显示什么（UI 直接照抄，不做 if 推断）。 */
        public String emptyHintZh = "";
        public String emptyHintEn = "";
    }

    /**
     * 组装 UI 快照。
     *
     * @param targets   目标管理器（必需）
     * @param runState  运行状态机（必需）
     * @param strategyMode 当前策略
     * @param candidateCount 当前策略能产出的候选数
     * @param capabilityMode 能力结论（可为 null → 视为 UNKNOWN）
     * @param capabilityLabel 能力标签（可为空）
     * @param capabilityStale 能力数据是否过期
     * @param nowMs 当前时间
     */
    public static UiState snapshot(WpsTargetManager targets,
                                   WpsRunState runState,
                                   int strategyMode,
                                   int candidateCount,
                                   String capabilityMode,
                                   String capabilityLabel,
                                   boolean capabilityStale,
                                   long nowMs) {
        UiState s = new UiState();
        s.scanState = targets.scanState();
        s.scanning = targets.isScanning();
        s.neverScanned = targets.neverScanned();
        s.targetCount = targets.targets().size();
        s.rawCount = targets.rawCount();
        s.wpsCount = targets.wpsCount();
        s.scanError = targets.lastError();
        s.lastScanAtMs = targets.lastScanAt();
        s.selectedBssid = targets.selectedBssid();

        s.idle = runState.isIdle();
        s.running = runState.isRunning();
        s.stopping = runState.isStopping();

        s.strategyMode = strategyMode;
        WpsStrategyManager.Strategy st = WpsStrategyManager.of(strategyMode);
        s.strategyLabel = st == null ? "" : st.label(true);
        s.candidateCount = candidateCount;
        WpsStrategyManager.Readiness rd = WpsStrategyManager
                .readiness(strategyMode, candidateCount, !s.selectedBssid.isEmpty());
        s.strategyReady = rd.ready;
        s.strategyReason = rd.reasonZh;

        s.capabilityVerdict = capabilityMode == null ? WpsCapabilityManager.V_UNKNOWN : capabilityMode;
        s.capabilityLabel = capabilityLabel == null ? "" : capabilityLabel;
        s.capabilityVerified = false;             // 能力检测永远不等于"已验证"
        s.capabilityStale = capabilityStale;

        s.canStart = s.idle && s.strategyReady;
        s.canStop = s.running;

        // —— 空态文案：由状态决定，UI 不推断 ——
        if (s.scanning) {
            s.emptyHintZh = "扫描中，请稍候…（附近网络会自动列出，无需手动选择）";
            s.emptyHintEn = "Scanning… nearby networks will list automatically";
        } else if (s.neverScanned) {
            s.emptyHintZh = "尚未扫描 —— 点上方「扫描 AP」发现附近网络";
            s.emptyHintEn = "Not scanned yet - tap Scan APs to discover nearby networks";
        } else if (s.scanState == WpsTargetManager.FAILED && !s.scanError.isEmpty()) {
            s.emptyHintZh = s.scanError;
            s.emptyHintEn = s.scanError;
        } else if (s.targetCount == 0) {
            s.emptyHintZh = "未发现附近网络";
            s.emptyHintEn = "No nearby networks found";
        } else {
            s.emptyHintZh = "";
            s.emptyHintEn = "";
        }

        s.revision = targets.revision()
                + (s.running ? 1_000_000L : 0)
                + (s.stopping ? 2_000_000L : 0)
                + (s.strategyReady ? 4_000_000L : 0)
                + (s.capabilityStale ? 8_000_000L : 0);
        return s;
    }
}

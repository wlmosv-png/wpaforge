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
        /** 执行状态名（IDLE/RUNNING/STOPPING/STOPPED/COMPLETED/FAILED）。 */
        public String execState = "IDLE";
        public boolean terminal;
        public String taskId = "";

        // —— 策略 ——
        public int strategyMode = -1;
        public String strategyLabel = "";
        public String strategyPurpose = "";
        public boolean strategyReady;
        public String strategyReason = "";
        /** 当前策略能产出的候选条数。 */
        public int candidateCount;
        /** 是否需要用户先导入数据才可用。 */
        public boolean strategyNeedsImport;
        /** 任务运行中时不允许切换策略。 */
        public boolean strategySwitchable;

        // —— 能力（展示模型；唯一来源 = WpsCapabilityManager）——
        /** 内部状态：PIN_OK / NO_PIN / LOCKED / UNKNOWN / INCOMPLETE / CONFLICT。 */
        public String capabilityVerdict = WpsCapabilityManager.V_UNKNOWN;
        /** 普通用户可读文案（不透出内部枚举名）。 */
        public String capabilityText = "";
        /** 来源文案（root BSS 查询 / 本地 IE 解析 / 无可用来源）。 */
        public String capabilitySource = "";
        /** 数据是否完整。 */
        public boolean capabilityComplete;
        /** 是否过期。 */
        public boolean capabilityStale;
        /** 是否冲突。 */
        public boolean capabilityConflict;
        /** 采集时间（0 = 无）。 */
        public long capabilityAtMs;
        /** 详细诊断（多行，给"能力详情"用；含两通道读取情况）。 */
        public String capabilityDetail = "";

        // —— 结果（三正交维度）——
        /** 执行状态名（IDLE/RUNNING/STOPPING/STOPPED/COMPLETED/FAILED）。 */
        public String resultExec = "IDLE";
        /** 判定结果（目标层面；"尚未开始" 时为空）。 */
        public String resultVerdict = "";
        /** 判定结果人话。 */
        public String resultVerdictText = "";
        /** 证据等级人话。 */
        public String resultLevelText = "";

        // —— 数据（我的列表）——
        public int customCount;
        public String customSummary = "";

        // —— 按钮矩阵（由真实任务状态决定，UI 不再自行推断）——
        public String primaryLabel = "";
        public boolean primaryEnabled;
        public String stopLabel = "";
        public boolean stopEnabled;

        public boolean capabilityVerified;      // 恒 false

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
                                   WpsCapabilityManager.Capability capability,
                                   boolean resumeReady,
                                   String resultVerdict,
                                   String resultLevelText,
                                   int customCount,
                                   long nowMs) {
        UiState s = new UiState();
        boolean zh = true;   // 文案以中文为主；英文由调用方按需取用（保持纯逻辑不引 L）

        // ---------- 扫描 / 目标 ----------
        s.scanState = targets.scanState();
        s.scanning = targets.isScanning();
        s.neverScanned = targets.neverScanned();
        s.targetCount = targets.targets().size();
        s.rawCount = targets.rawCount();
        s.wpsCount = targets.wpsCount();
        s.scanError = targets.lastError();
        s.lastScanAtMs = targets.lastScanAt();
        s.selectedBssid = targets.selectedBssid();

        // ---------- 任务执行状态 ----------
        s.idle = runState.isIdle();
        s.running = runState.isRunning();
        s.stopping = runState.isStopping();
        s.terminal = runState.isTerminal();
        s.execState = runState.label();
        s.resultExec = runState.label();

        // ---------- 策略 ----------
        s.strategyMode = strategyMode;
        WpsStrategyManager.Strategy st = WpsStrategyManager.of(strategyMode);
        s.strategyLabel = st == null ? "" : st.label(zh);
        s.strategyPurpose = st == null ? "" : st.description(zh);
        s.candidateCount = candidateCount;
        s.strategySwitchable = !runState.isActive();
        WpsStrategyManager.Readiness rd = WpsStrategyManager
                .readiness(strategyMode, candidateCount, !s.selectedBssid.isEmpty());
        s.strategyReady = rd.ready;
        s.strategyReason = rd.reasonZh;
        s.strategyNeedsImport = (strategyMode == WpsStrategyManager.MODE_CUSTOM && candidateCount <= 0);

        // ---------- 能力（唯一来源） ----------
        if (capability == null) {
            s.capabilityVerdict = WpsCapabilityManager.V_UNKNOWN;
            s.capabilityText = WpsUiKit.capVerdictText(WpsCapabilityManager.V_UNKNOWN, zh);
            s.capabilitySource = WpsUiKit.capSourceText("", zh);
            s.capabilityDetail = "（尚未检测）";
        } else {
            s.capabilityVerdict = capability.verdict;
            s.capabilityText = WpsUiKit.capVerdictText(capability.verdict, zh);
            s.capabilitySource = WpsUiKit.capSourceText(capability.primarySource, zh);
            s.capabilityStale = capability.stale;
            s.capabilityConflict = capability.conflict;
            s.capabilityAtMs = capability.atMs;
            s.capabilityComplete = WpsCapabilityManager.V_PIN_OK.equals(capability.verdict)
                    || WpsCapabilityManager.V_NO_PIN.equals(capability.verdict)
                    || WpsCapabilityManager.V_LOCKED.equals(capability.verdict);
            s.capabilityDetail = detailOf(capability, nowMs);
        }
        s.capabilityVerified = false;   // 能力检测永远不等于"已验证"

        // ---------- 结果 ----------
        s.resultVerdict = resultVerdict == null ? "" : resultVerdict;
        s.resultVerdictText = s.resultVerdict.isEmpty()
                ? (s.idle ? "尚未尝试 —— 点「开始验证」才会知道这个目标能不能验证"
                          : "（进行中）")
                : WpsEngine.verdictText(s.resultVerdict, zh);
        s.resultLevelText = resultLevelText == null ? "" : resultLevelText;

        // ---------- 数据 ----------
        s.customCount = customCount;
        s.customSummary = "我的列表 " + customCount + " 条";

        // ---------- 按钮矩阵 ----------
        // 2026-11-09 修复（回归）：开始按钮**不得**被"策略可用性"门控。
        // 之前写成 `&& s.strategyReady`，导致厂商算法无规则 / 「我的列表」为空时
        // 按钮永久禁用 —— 用户点不动，也看不到任何解释。
        // 正确语义：只要有目标且不在运行，就可以按「开始」；策略没数据时
        // startWps() 的**策略预检**会给出结构化结论（"本策略对本目标没有可用数据"），
        // 这是真实的业务响应，比死按钮更好。
        s.canStart = !runState.isActive() && !s.selectedBssid.isEmpty();
        s.canStop = runState.isRunning();
        if (runState.isIdle()) {
            s.primaryLabel = resumeReady ? "继续" : "开始";
            s.primaryEnabled = s.canStart;
            s.stopLabel = "停止";
            s.stopEnabled = false;
        } else if (runState.isRunning()) {
            s.primaryLabel = "运行中…";
            s.primaryEnabled = false;
            s.stopLabel = "停止";
            s.stopEnabled = true;
        } else if (runState.isStopping()) {
            s.primaryLabel = "停止中…";
            s.primaryEnabled = false;
            s.stopLabel = "停止中…";
            s.stopEnabled = false;          // 禁用重复请求
        } else {                            // STOPPED / COMPLETED / FAILED
            s.primaryLabel = "重新开始";
            s.primaryEnabled = s.canStart;
            s.stopLabel = "停止";
            s.stopEnabled = false;
        }

        // ---------- 空态文案 ----------
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
            s.emptyHintZh = "扫描完成，但附近没有发现网络";
            s.emptyHintEn = "Scan finished, but no networks found";
        } else {
            s.emptyHintZh = "";
            s.emptyHintEn = "";
        }

        s.revision = targets.revision()
                + (s.running ? 1_000_000L : 0)
                + (s.stopping ? 2_000_000L : 0)
                + (s.strategyReady ? 4_000_000L : 0)
                + (s.terminal ? 8_000_000L : 0)
                + s.customCount;
        return s;
    }

    /** 能力详情（多行）：两通道读取情况 / BSSID 校验 / 完整性 / 冲突 / 采集时间。 */
    private static String detailOf(WpsCapabilityManager.Capability c, long nowMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("结论：").append(WpsUiKit.capVerdictText(c.verdict, true)).append('\n');
        sb.append("来源：").append(WpsUiKit.capSourceText(c.primarySource, true)).append('\n');
        sb.append("通道A(root BSS)：").append(channelLine(c.root)).append('\n');
        sb.append("通道B(本地 IE)：").append(channelLine(c.localIe)).append('\n');
        sb.append("目标 BSSID：").append(c.targetBssid.isEmpty() ? "(未知)" : c.targetBssid).append('\n');
        sb.append("数据完整性：").append(c.conflict ? "冲突"
                : (c.root != null && c.root.complete) || (c.localIe != null && c.localIe.complete)
                        ? "至少一条通道完整" : "不完整").append('\n');
        if (c.atMs > 0 && nowMs > 0) {
            long ageSec = Math.max(0, (nowMs - c.atMs) / 1000);
            sb.append("采集时间：").append(ageSec).append(" 秒前")
                    .append(c.stale ? "（已过期）" : "").append('\n');
        }
        if (!c.issues.isEmpty()) sb.append("问题：").append(c.issueSummary());
        return sb.toString().trim();
    }

    private static String channelLine(WpsCapabilityManager.Channel ch) {
        if (ch == null) return "未使用";
        if (!ch.available) return "不可用";
        String v = ch.configMethods == WpsIeParser.UNKNOWN
                ? "未读到 config_methods"
                : ("config_methods=0x" + Integer.toHexString(ch.configMethods));
        return (ch.complete ? "完整" : "不完整") + " · " + v
                + (ch.bssid.isEmpty() ? "" : " · " + ch.bssid);
    }
}

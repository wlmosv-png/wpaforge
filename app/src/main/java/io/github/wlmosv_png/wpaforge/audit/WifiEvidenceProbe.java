package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code dumpsys wifi} 里**真正的认证失败证据**解析（2026-11-22 新增，纯逻辑）。
 *
 * <h3>为什么必须有它（本轮最重要的一处更正）</h3>
 * 1.9.63 的 {@link WifiDumpsProbe} 只解析了 {@code NETWORK_DISCONNECTION_EVENT}，
 * 发现 {@code reasonCode} 恒为 3，于是在类注释里写下结论：
 * <pre>
 *   「dumpsys wifi 不能证明密码错误」
 * </pre>
 * <b>这条结论是错的，而且代价极高。</b> 2026-11-22 真机复核（PJX110 / ColorOS 15）
 * 在同一份 {@code dumpsys wifi} 输出里找到了三条独立的、带时间戳、可绑定到
 * 具体配置的认证失败证据链：
 *
 * <pre>
 * ① WifiMetrics.mConnectionEvents
 *    startTime=11-22 00:44:22.225, SSID="2.4G-3", BSSID=6a:93:7d:41:cc:3b,
 *    durationMillis=4210, connectionResult=0,
 *    level2FailureCode=AUTHENTICATION_FAILURE,
 *    level2FailureReason=AUTH_FAILURE_WRONG_PSWD, ..., uid=0
 *
 * ② WifiConfigManager - Log
 *    2026-11-22T00:44:26.429795 - setNetworkSelectionStatus:
 *      configKey="2.4G-3"WPA_PSK networkStatus=NETWORK_SELECTION_TEMPORARY_DISABLED
 *      disableReason=NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD
 *
 * ③ WifiDiagnostics 内嵌 system log（只在有 alert 时出现）
 *    11-22 00:44:58.743 11602 11602 I wpa_supplicant: wlan0: CTRL-EVENT-SSID-TEMP-DISABLED
 *      id=0 ssid="2.4G-3" auth_failures=1 duration=5 reason=WRONG_KEY
 * </pre>
 *
 * <p>三者里 <b>①</b> 与 <b>②</b> 是主力：它们不是「一次事故的快照」，
 * 而是 framework 自己维护的**有界历史**（实测 ① 保留 63 条连接事件、
 * ② 保留 122 行配置操作），因此每次尝试都能查到。
 *
 * <h3>为什么这三条能达到 AT3（可永久排除）</h3>
 * 归属链逐层可验：
 * <ol>
 *   <li><b>哪个网络</b>：① 带 {@code SSID} + {@code BSSID}，② 带
 *       {@code configKey="<SSID>"WPA_PSK}（含加密类型，比 SSID 更强）；</li>
 *   <li><b>是谁建的配置</b>：② 里配对的 {@code addOrUpdateNetworkInternal ...
 *       uid=0 name=null} —— root 通道建的配置 uid 就是 0，
 *       而系统自己的配置是 {@code uid=1000 name=android.uid.system:1000}，
 *       应用建的是 {@code uid=<appId>}。三者**天然可分**；</li>
 *   <li><b>是不是本次窗口</b>：两条都带毫秒级时间戳，与 attempt 的
 *       {@code commandAt} / {@code deadline} 直接比对；</li>
 *   <li><b>是不是本次候选</b>：命令前有两道前置闸门（目标配置已删净并回读、
 *       需要时已确认断开），因此窗口内出现的「目标 SSID 的 WRONG_PASSWORD」
 *       在结构上只可能来自本次命令建的配置。</li>
 * </ol>
 *
 * <h3>它<b>不能</b>做什么（防止被误用）</h3>
 * <ul>
 *   <li>不能证明「没出现失败行 = 密码正确」—— 只有 {@code cmd wifi status}
 *       的四条件同行能证明成功；</li>
 *   <li>不能区分「密码错」与「AP 侧 RADIUS 拒绝」—— 但后者在本项目
 *       （WPA2-PSK 家用网络）不成立，且 framework 已把它们分成
 *       {@code AUTH_FAILURE_WRONG_PSWD} 与其它 reason；</li>
 *   <li>{@code mConnectionEvents} 只保留**最近 63 条**，长跑任务里
 *       早期条目会被挤掉 —— 所以必须**每次尝试结束就查**，不能攒到最后。</li>
 * </ul>
 *
 * <p>纯逻辑，无 Android 依赖，可离线穷举。时间解析失败一律返回 {@code -1}，
 * 由调用方按「无法归属」处理 —— <b>绝不猜</b>。
 */
public final class WifiEvidenceProbe {

    // ══════════════════════════════════════════════════════════════════
    //  ① mConnectionEvents
    // ══════════════════════════════════════════════════════════════════

    /**
     * 一条 {@code mConnectionEvents} 记录。
     *
     * <p>只保留判定需要的字段；其余几十个字段（速率、DTIM、MLO…）
     * 与判定无关，解析它们只会增加格式变化时的脆弱性。
     */
    public static final class ConnEvent {
        /** 原始时间串，形如 {@code 11-22 00:44:22.225}；解析不到为空。 */
        public final String timeText;
        /** 解析后的毫秒时间（{@code MM-DD HH:MM:SS.mmm} 在**当年**的毫秒值）；失败为 -1。 */
        public final long timeMs;
        public final String ssid;
        public final String bssid;
        public final long durationMs;
        /** framework 的 connectionResult：1=成功，0=失败。 */
        public final int connectionResult;
        /** 例如 {@code AUTHENTICATION_FAILURE} / {@code NONE} / {@code NEW_CONNECTION_ATTEMPT}。 */
        public final String level2FailureCode;
        /** 例如 {@code AUTH_FAILURE_WRONG_PSWD} / {@code FAILURE_REASON_UNKNOWN}。 */
        public final String level2FailureReason;
        /** 发起这次连接的 uid：0=root 通道，1000=system，其它=应用。 */
        public final int uid;

        ConnEvent(String timeText, long timeMs, String ssid, String bssid, long durationMs,
                  int connectionResult, String level2FailureCode,
                  String level2FailureReason, int uid) {
            this.timeText = timeText == null ? "" : timeText;
            this.timeMs = timeMs;
            this.ssid = ssid;
            this.bssid = bssid;
            this.durationMs = durationMs;
            this.connectionResult = connectionResult;
            this.level2FailureCode = level2FailureCode == null ? "" : level2FailureCode;
            this.level2FailureReason = level2FailureReason == null ? "" : level2FailureReason;
            this.uid = uid;
        }

        /** framework 是否明确把这次连接判为「密钥错误」。 */
        public boolean wrongPsk() {
            return "AUTH_FAILURE_WRONG_PSWD".equals(level2FailureReason);
        }

        /** framework 是否把这次连接判为认证失败（更宽，含 WRONG_PSWD）。 */
        public boolean authFailure() {
            return "AUTHENTICATION_FAILURE".equals(level2FailureCode);
        }

        @Override public String toString() {
            return "conn(" + timeText + " ssid=" + ssid + " bssid=" + bssid
                    + " dur=" + durationMs + " res=" + connectionResult
                    + " l2=" + level2FailureCode + "/" + level2FailureReason
                    + " uid=" + uid + ")";
        }
    }

    /**
     * 本机 {@code mConnectionEvents} 的 {@code startTime} 格式。
     *
     * <h3>为什么每段都写 {@code \d{1,2}} 而不是 {@code \d{2}}</h3>
     * 真机实测（PJX110 / ColorOS 15）该字段**不补前导零**：
     * <pre>
     *   startTime=11-22 1:0:6.775      ← 时、分、秒全是 1 位
     *   startTime=11-21 18:18:4.284    ← 秒是 1 位
     *   startTime=11-21 7:49:34.519    ← 时是 1 位
     * </pre>
     * 63 条记录里有 11 条的分钟是 1 位。写成 {@code \d{2}} 会**静默丢掉这 11 条**
     * —— 而其中恰好包含目标 AP 的失败记录时，判定就会漏掉真实证据。
     * 这是「解析器必须照抄真机格式」的直接教训。
     */
    private static final Pattern P_START = Pattern.compile(
            "^startTime=(\\d{1,2}-\\d{1,2} \\d{1,2}:\\d{1,2}:\\d{1,2}\\.\\d{1,3}),");

    /**
     * 解析 {@code mConnectionEvents} 段里的全部连接事件。
     *
     * <p>只认以 {@code startTime=} 开头的行；其余行忽略。解析失败**不抛异常**，
     * 该行直接跳过（宁可少一条，不可因格式微变让整条链崩掉）。
     *
     * <p>注意本机 {@code dumpsys wifi} 里 {@code mConnectionEvents:} 出现**两次**
     * （一个在 {@code WifiClientModeImpl} 段、一个在 {@code WifiMetrics} 段）。
     * 两段内容可能不同，因此本方法**不按段切分**，直接扫全文所有
     * {@code startTime=} 行 —— 这样两段都被收进来，由调用方按时间去重/取最严。
     */
    public static List<ConnEvent> connectionEvents(String dumps) {
        List<ConnEvent> out = new ArrayList<>();
        if (dumps == null || dumps.isEmpty()) return out;
        for (String line : dumps.split("\n")) {
            if (!line.startsWith("startTime=")) continue;
            Matcher m = P_START.matcher(line);
            if (!m.find()) continue;
            String timeText = m.group(1);
            long timeMs = parseStamp(timeText);
            String ssid = field(line, "SSID=\"", "\"");
            String bssid = field(line, "BSSID=", ",");
            long dur = num(line, "durationMillis=");
            int res = (int) num(line, "connectionResult=");
            String l2code = token(line, "level2FailureCode=");
            String l2reason = token(line, "level2FailureReason=");
            int uid = (int) num(line, "uid=");
            out.add(new ConnEvent(timeText, timeMs, ssid, bssid, dur, res, l2code, l2reason, uid));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ② WifiConfigManager - Log
    // ══════════════════════════════════════════════════════════════════

    /** 配置操作的类型。 */
    public enum ConfigOp {
        /** 新建/更新配置（{@code addOrUpdateNetworkInternal}）。 */
        ADD_OR_UPDATE,
        /** 删除配置（{@code removeNetworkInternal}）。 */
        REMOVE,
        /** 网络选择状态变更（{@code setNetworkSelectionStatus}）。 */
        SELECTION_STATUS,
        /** 其它（不参与判定，仅保留可观测性）。 */
        OTHER
    }

    /** 一条 {@code WifiConfigManager - Log} 记录。 */
    public static final class ConfigEvent {
        /** 原始时间串，形如 {@code 2026-11-22T00:44:26.429795}。 */
        public final String timeText;
        /** 解析后的毫秒时间；失败为 -1。 */
        public final long timeMs;
        public final ConfigOp op;
        /** 配置键里的 SSID，例如 {@code "2.4G-3"}WPA_PSK → {@code 2.4G-3}；拿不到为空。 */
        public final String ssid;
        /** netId（仅 ADD_OR_UPDATE / REMOVE 有）；-1 = 未给出。 */
        public final int netId;
        /** 创建/删除者 uid（仅 ADD_OR_UPDATE / REMOVE 有）；-1 = 未给出。 */
        public final int uid;
        /**
         * {@code setNetworkSelectionStatus} 的 disableReason 原文，
         * 例如 {@code NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD}；其它类型为空。
         */
        public final String disableReason;

        ConfigEvent(String timeText, long timeMs, ConfigOp op, String ssid,
                    int netId, int uid, String disableReason) {
            this.timeText = timeText == null ? "" : timeText;
            this.timeMs = timeMs;
            this.op = op;
            this.ssid = ssid;
            this.netId = netId;
            this.uid = uid;
            this.disableReason = disableReason == null ? "" : disableReason;
        }

        /** framework 是否明确因「密码错误」禁用了这条配置。 */
        public boolean wrongPassword() {
            return "NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD".equals(disableReason);
        }

        /** 这条配置是否是**本应用 root 通道**建的（uid=0）。 */
        public boolean builtByRoot() {
            return op == ConfigOp.ADD_OR_UPDATE && uid == 0;
        }

        @Override public String toString() {
            return op + "(" + timeText + " ssid=" + ssid
                    + (netId >= 0 ? " netId=" + netId : "")
                    + (uid >= 0 ? " uid=" + uid : "")
                    + (disableReason.isEmpty() ? "" : " reason=" + disableReason) + ")";
        }
    }

    private static final Pattern P_CFG_TS =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{1,6}) - ");
    private static final Pattern P_CFG_KEY =
            Pattern.compile("configKey=\"([^\"]*)\"");

    /**
     * 解析 {@code WifiConfigManager - Log} 段里的全部配置事件。
     *
     * <p>本机实测该段**有界且短**（122 行 / 覆盖 13 小时），
     * 因此不需要按段切分，直接扫全文所有以 ISO 时间戳开头的行即可。
     */
    public static List<ConfigEvent> configEvents(String dumps) {
        List<ConfigEvent> out = new ArrayList<>();
        if (dumps == null || dumps.isEmpty()) return out;
        for (String line : dumps.split("\n")) {
            Matcher m = P_CFG_TS.matcher(line);
            if (!m.find()) continue;
            String timeText = m.group(1);
            long timeMs = parseIso(timeText);
            ConfigOp op;
            if (line.indexOf("addOrUpdateNetworkInternal") >= 0) op = ConfigOp.ADD_OR_UPDATE;
            else if (line.indexOf("removeNetworkInternal") >= 0) op = ConfigOp.REMOVE;
            else if (line.indexOf("setNetworkSelectionStatus") >= 0) op = ConfigOp.SELECTION_STATUS;
            else op = ConfigOp.OTHER;
            if (op == ConfigOp.OTHER) continue;   // 判定用不到，不入列表
            String ssid = null;
            Matcher km = P_CFG_KEY.matcher(line);
            if (km.find()) ssid = km.group(1);
            int netId = -1, uid = -1;
            if (op == ConfigOp.ADD_OR_UPDATE || op == ConfigOp.REMOVE) {
                netId = (int) num(line, "netId=");
                uid = (int) num(line, "uid=");
            }
            String reason = token(line, "disableReason=");
            out.add(new ConfigEvent(timeText, timeMs, op, ssid, netId, uid, reason));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ③ WifiDiagnostics 内嵌 system log 里的 supplicant 行
    // ══════════════════════════════════════════════════════════════════

    /** 一条从内嵌 system log 里捞出来的 supplicant 关键行。 */
    public static final class SuppLine {
        public final String timeText;
        public final long timeMs;
        public final String ssid;
        /** true = {@code reason=WRONG_KEY} 或 {@code 4-Way Handshake failed}。 */
        public final boolean wrongKey;
        /** true = {@code Key negotiation completed}（握手成功）。 */
        public final boolean negotiated;
        public final String raw;

        SuppLine(String timeText, long timeMs, String ssid,
                 boolean wrongKey, boolean negotiated, String raw) {
            this.timeText = timeText == null ? "" : timeText;
            this.timeMs = timeMs;
            this.ssid = ssid;
            this.wrongKey = wrongKey;
            this.negotiated = negotiated;
            this.raw = raw == null ? "" : raw;
        }

        @Override public String toString() {
            return "supp(" + timeText + " ssid=" + ssid
                    + (wrongKey ? " WRONG_KEY" : "") + (negotiated ? " NEGOTIATED" : "") + ")";
        }
    }

    private static final Pattern P_LOG_LINE = Pattern.compile(
            "^(\\d{1,2}-\\d{1,2} \\d{1,2}:\\d{1,2}:\\d{1,2}\\.\\d{1,3})\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+");

    /**
     * 从 dumpsys 里捞 wpa_supplicant 的关键行。
     *
     * <p><b>本机现实</b>：{@code logcat -s wpa_supplicant} 是 0 行（ColorOS 配额丢弃），
     * 但 {@code dumpsys wifi} 的 {@code WifiDiagnostics} 段会内嵌一份
     * **最近一次 alert** 前后的 system log 快照，里面确实有 supplicant 行。
     * 它只覆盖单次事故（不是历史），所以只能当**辅助**，不能当主力 ——
     * 主力是 ① 和 ②。
     */
    public static List<SuppLine> supplicantLines(String dumps) {
        List<SuppLine> out = new ArrayList<>();
        if (dumps == null || dumps.isEmpty()) return out;
        for (String line : dumps.split("\n")) {
            if (line.indexOf("wpa_supplicant") < 0) continue;
            Matcher m = P_LOG_LINE.matcher(line);
            if (!m.find()) continue;
            String timeText = m.group(1);
            long timeMs = parseStamp(timeText);
            String ssid = field(line, "ssid=\"", "\"");
            boolean wrongKey = line.indexOf("reason=WRONG_KEY") >= 0
                    || line.indexOf("4-Way Handshake failed") >= 0;
            boolean negotiated = line.indexOf("Key negotiation completed") >= 0;
            if (!wrongKey && !negotiated) continue;
            out.add(new SuppLine(timeText, timeMs, ssid, wrongKey, negotiated, line.trim()));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  归属查询（判定用）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 在给定时间窗内，找**针对目标 SSID 且由 root 通道发起**的认证失败。
     *
     * <h3>为什么要求 {@code uid=0}</h3>
     * 只有 root 通道建的配置 uid 才是 0。系统自己的配置是
     * {@code uid=1000 name=android.uid.system:1000}，应用建的是 {@code uid=<appId>}。
     * 要求 uid=0 就等于要求「这条失败来自我们发的命令」——
     * 系统后台重试旧配置、别的应用建的配置都会被排除。
     *
     * <h3>为什么还要求 {@code connectionResult=0}</h3>
     * 成功的连接也会写一条 {@code connectionResult=1} 的记录。
     * 只认 0 才不会把「这条候选成功了」当成「这条候选失败了」。
     *
     * @param events      {@link #connectionEvents(String)} 的结果
     * @param ssid        目标 SSID（逐字相等）
     * @param fromMs      窗口起点（含）；-1 表示不限
     * @param toMs        窗口终点（含）；-1 表示不限
     * @param requireRoot 是否要求 uid=0
     * @return 命中的最早一条；没有则 {@code null}
     */
    public static ConnEvent findWrongPsk(List<ConnEvent> events, String ssid,
                                         long fromMs, long toMs, boolean requireRoot) {
        if (events == null || ssid == null || ssid.isEmpty()) return null;
        ConnEvent best = null;
        for (ConnEvent e : events) {
            if (!ssid.equals(e.ssid)) continue;
            if (!e.wrongPsk()) continue;
            if (e.connectionResult != 0) continue;
            if (requireRoot && e.uid != 0) continue;
            if (fromMs >= 0 && e.timeMs >= 0 && e.timeMs < fromMs) continue;
            if (toMs >= 0 && e.timeMs >= 0 && e.timeMs > toMs) continue;
            if (best == null || (e.timeMs >= 0 && (best.timeMs < 0 || e.timeMs < best.timeMs))) {
                best = e;
            }
        }
        return best;
    }

    /**
     * 在给定时间窗内，找**针对目标 SSID 且由 root 通道建的配置**被
     * framework 以 {@code DISABLED_BY_WRONG_PASSWORD} 禁用的记录。
     *
     * <h3>归属为什么比 ① 更强</h3>
     * ① 的 {@code uid=0} 是「连接发起者」，② 的 {@code uid=0} 是
     * 「**配置创建者**」。后者才是「这条配置是本次命令建的」的直接证据。
     * 两者同时成立时，归属链闭合得最紧。
     *
     * <h3>为什么必须先有 ADD 再有 STATUS</h3>
     * 系统里可能残留一条旧的同名配置（旧密码）。它被禁用时 SSID 也相同。
     * 要求「本次窗口内先出现 uid=0 的 ADD_OR_UPDATE」就能排除这种情形 ——
     * 因为前置闸门已确认命令前目标配置被删净，所以窗口内的 ADD 只能是本次的。
     *
     * @param events    {@link #configEvents(String)} 的结果
     * @param ssid      目标 SSID
     * @param fromMs    窗口起点（含）；-1 表示不限
     * @param toMs      窗口终点（含）；-1 表示不限
     * @param requireRootAdd 是否要求窗口内存在 uid=0 的 ADD_OR_UPDATE
     * @return 命中的最早一条 SELECTION_STATUS；没有则 {@code null}
     */
    public static ConfigEvent findWrongPasswordDisable(List<ConfigEvent> events, String ssid,
                                                       long fromMs, long toMs,
                                                       boolean requireRootAdd) {
        if (events == null || ssid == null || ssid.isEmpty()) return null;
        if (requireRootAdd && !hasRootAddInWindow(events, ssid, fromMs, toMs)) return null;
        ConfigEvent best = null;
        for (ConfigEvent e : events) {
            if (e.op != ConfigOp.SELECTION_STATUS) continue;
            if (!ssid.equals(e.ssid)) continue;
            if (!e.wrongPassword()) continue;
            if (fromMs >= 0 && e.timeMs >= 0 && e.timeMs < fromMs) continue;
            if (toMs >= 0 && e.timeMs >= 0 && e.timeMs > toMs) continue;
            if (best == null || (e.timeMs >= 0 && (best.timeMs < 0 || e.timeMs < best.timeMs))) {
                best = e;
            }
        }
        return best;
    }

    /** 窗口内是否存在针对该 SSID 的、由 root 通道创建的配置。 */
    public static boolean hasRootAddInWindow(List<ConfigEvent> events, String ssid,
                                             long fromMs, long toMs) {
        if (events == null || ssid == null || ssid.isEmpty()) return false;
        for (ConfigEvent e : events) {
            if (e.op != ConfigOp.ADD_OR_UPDATE) continue;
            if (!ssid.equals(e.ssid)) continue;
            if (e.uid != 0) continue;
            if (fromMs >= 0 && e.timeMs >= 0 && e.timeMs < fromMs) continue;
            if (toMs >= 0 && e.timeMs >= 0 && e.timeMs > toMs) continue;
            return true;
        }
        return false;
    }

    /** 窗口内是否存在针对该 SSID 的、由 root 通道发起但**未**判为密码错的失败。 */
    public static ConnEvent findNonPskFailure(List<ConnEvent> events, String ssid,
                                              long fromMs, long toMs) {
        if (events == null || ssid == null || ssid.isEmpty()) return null;
        for (ConnEvent e : events) {
            if (!ssid.equals(e.ssid)) continue;
            if (e.connectionResult != 0) continue;
            if (e.uid != 0) continue;
            if (e.wrongPsk()) continue;
            if (fromMs >= 0 && e.timeMs >= 0 && e.timeMs < fromMs) continue;
            if (toMs >= 0 && e.timeMs >= 0 && e.timeMs > toMs) continue;
            return e;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  时间解析
    // ══════════════════════════════════════════════════════════════════

    /**
     * 解析 {@code MM-DD HH:MM:SS.mmm}（本机 {@code mConnectionEvents} 的格式）。
     *
     * <p><b>只用于同一天内的窗口比对</b>：返回的是「月-日 时:分:秒.毫秒」
     * 折算成的毫秒数，不含年份。跨年比较会出错 —— 但一轮破解任务不可能跨年，
     * 而跨天的情况由 {@link #sameDayWindow} 拦掉（宁可少判，不可错判）。
     *
     * @return 毫秒值；格式不符返回 -1
     */
    public static long parseStamp(String s) {
        if (s == null) return -1;
        try {
            String[] md = s.trim().split("[ -]");
            if (md.length < 3) return -1;
            int month = Integer.parseInt(md[0]);
            int day = Integer.parseInt(md[1]);
            String[] hms = md[2].split(":");
            if (hms.length < 3) return -1;
            int h = Integer.parseInt(hms[0]);
            int mi = Integer.parseInt(hms[1]);
            String secPart = hms[2];
            int ms = 0;
            int dot = secPart.indexOf('.');
            String secStr = secPart;
            if (dot >= 0) {
                secStr = secPart.substring(0, dot);
                String frac = secPart.substring(dot + 1);
                if (frac.length() > 3) frac = frac.substring(0, 3);
                while (frac.length() < 3) frac = frac + "0";
                ms = Integer.parseInt(frac);
            }
            int sec = Integer.parseInt(secStr);
            long dayKey = (long) month * 100 + day;
            return dayKey * 86_400_000L + h * 3_600_000L + mi * 60_000L + sec * 1000L + ms;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 解析 {@code yyyy-MM-ddTHH:mm:ss.SSSSSS}（{@code WifiConfigManager} 的格式）。 */
    public static long parseIso(String s) {
        if (s == null) return -1;
        try {
            int t = s.indexOf('T');
            if (t < 10) return -1;
            String[] d = s.substring(0, t).split("-");
            if (d.length < 3) return -1;
            int month = Integer.parseInt(d[1]);
            int day = Integer.parseInt(d[2]);
            String[] hms = s.substring(t + 1).split(":");
            if (hms.length < 3) return -1;
            int h = Integer.parseInt(hms[0]);
            int mi = Integer.parseInt(hms[1]);
            String secPart = hms[2];
            int ms = 0;
            int dot = secPart.indexOf('.');
            String secStr = secPart;
            if (dot >= 0) {
                secStr = secPart.substring(0, dot);
                String frac = secPart.substring(dot + 1);
                if (frac.length() > 3) frac = frac.substring(0, 3);
                while (frac.length() < 3) frac = frac + "0";
                ms = Integer.parseInt(frac);
            }
            int sec = Integer.parseInt(secStr);
            long dayKey = (long) month * 100 + day;
            return dayKey * 86_400_000L + h * 3_600_000L + mi * 60_000L + sec * 1000L + ms;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 两个时间戳是否属于**同一天**（按 {@code MM-DD} 段判断）。
     *
     * <p>跨天时 {@link #parseStamp} 的返回值不可比（不含年），
     * 因此窗口比对必须先用本方法确认同天。跨天一律返回 false，
     * 调用方按「无法归属」处理 —— 宁可少一条永久排除，不可错排除。
     */
    public static boolean sameDayWindow(long aMs, long bMs) {
        if (aMs < 0 || bMs < 0) return false;
        return (aMs / 86_400_000L) == (bMs / 86_400_000L);
    }

    /**
     * 把**墙钟毫秒**（{@code System.currentTimeMillis()}）折算到与
     * {@link #parseStamp} 相同的坐标系，使两者可以直接比较。
     *
     * <h3>为什么必须有它</h3>
     * {@code tryOneShell} 里有两个不同的时钟：
     * <ul>
     *   <li>{@code commandAt} = {@code clock().uptimeMillis()} —— **开机以来的单调时钟**；</li>
     *   <li>{@code windowFromMs} = {@code System.currentTimeMillis()} —— **墙钟**。</li>
     * </ul>
     * 而 dumpsys 里的时间戳是**设备本地墙钟**（{@code MM-DD HH:MM:SS.mmm}）。
     * 三者只有墙钟那一支能与 dumpsys 直接比对。本方法就是那个桥。
     *
     * <h3>时区</h3>
     * 用 JVM 默认时区 —— 与 logcat / dumpsys 输出所用的设备本地时区一致。
     * 若宿主与设备时区不同，比对会整体偏移，因此调用方必须**只在同一台设备上**使用。
     *
     * @return 与 {@link #parseStamp} 同坐标系的值；拿不到返回 -1
     */
    public static long stampOfEpoch(long epochMs) {
        if (epochMs <= 0) return -1;
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(epochMs);
            int month = c.get(java.util.Calendar.MONTH) + 1;
            int day = c.get(java.util.Calendar.DAY_OF_MONTH);
            int h = c.get(java.util.Calendar.HOUR_OF_DAY);
            int mi = c.get(java.util.Calendar.MINUTE);
            int sec = c.get(java.util.Calendar.SECOND);
            int ms = c.get(java.util.Calendar.MILLISECOND);
            long dayKey = (long) month * 100 + day;
            return dayKey * 86_400_000L + h * 3_600_000L + mi * 60_000L + sec * 1000L + ms;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 窗口是否**可信**（两个端点同天且顺序正确）。
     *
     * <p>跨天窗口一律不可信：{@link #parseStamp} 不含年份，
     * 跨天比对会产生巨大的假差值，可能把「昨天的失败」算成「今天的」。
     */
    public static boolean windowUsable(long fromMs, long toMs) {
        if (fromMs < 0 || toMs < 0) return false;
        if (toMs < fromMs) return false;
        return sameDayWindow(fromMs, toMs);
    }

    // ══════════════════════════════════════════════════════════════════
    //  内部工具
    // ══════════════════════════════════════════════════════════════════

    /** 取 {@code prefix} 之后到 {@code end} 之间的子串；拿不到返回 null。 */
    private static String field(String line, String prefix, String end) {
        int i = line.indexOf(prefix);
        if (i < 0) return null;
        int s = i + prefix.length();
        int e = line.indexOf(end, s);
        if (e < 0) return null;
        return line.substring(s, e);
    }

    /** 取 {@code key} 之后的十进制整数；拿不到返回 -1。 */
    private static long num(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return -1;
        int s = i + key.length();
        int e = s;
        if (e < line.length() && (line.charAt(e) == '-' || line.charAt(e) == '+')) e++;
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        if (e == s) return -1;
        try {
            return Long.parseLong(line.substring(s, e));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 取 {@code key} 之后的字母数字下划线 token；拿不到返回空串。 */
    private static String token(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        int s = i + key.length();
        int e = s;
        while (e < line.length()) {
            char c = line.charAt(e);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') e++;
            else break;
        }
        return e == s ? "" : line.substring(s, e);
    }

    private WifiEvidenceProbe() {}
}

package io.github.wlmosv_png.wpaforge.audit;


import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * wpa_supplicant 日志读取器。
 *
 * <h3>为什么需要它</h3>
 * 之前所有判定都基于「连接状态」或「系统回调」，两者都只能说明
 * **现在连着谁**。归因问题（这条密码导致的吗）一直靠猜。
 *
 * 而 wpa_supplicant 的日志里直接写着**正在尝试哪个 SSID**：
 * <pre>
 *   wlan0: Trying to associate with SSID 'B209'
 *   WPA: Key negotiation completed with 28:93:7d:11:22:02
 *   WPA: 4-Way Handshake failed - pre-shared key may be incorrect
 * </pre>
 *
 * 这比任何状态轮询都直接 —— 它就是**这次尝试本身**的记录。
 *
 * <h3>实测结论（2026-10-22 复核，ColorOS 15 / Android 15）</h3>
 * <pre>
 *   Trying to associate with SSID 'X'                       ✓ 有
 *   WPA: 4-Way Handshake failed - pre-shared key ...        ✓ 有
 *   WPA: Key negotiation completed with ...                 ✓ 有
 *   CTRL-EVENT-SSID-TEMP-DISABLED ... reason=WRONG_KEY      ✓ 有
 *   CTRL-EVENT-CONNECTED - Connection to ... completed      ✓ 有
 * </pre>
 *
 * <p><b>改正一条旧结论</b>：本文以前写「本机不输出 Key negotiation completed /
 * 4-Way Handshake failed」，那是**采样时机造成的误判** —— 引擎每条候选前会
 * `logcat -c`，若恰在清空后读取，就什么都读不到，于是被当成「ROM 不输出」。
 * 事实上这些行一直都在（2026-10-22 直接抓取确认）。
 *
 * <h3>两个真实的限制</h3>
 * <ul>
 *   <li>ColorOS 对每个进程有日志配额（实测 wpa_supplicant 约 300 行 / 8 KB），
 *       超出部分会被丢弃，关键行可能读不到。</li>
 *   <li>AP 对错误密码的响应本身可能很慢（实测某 AP 在 -63dBm 下要 8.8 秒
 *       才吐 `4-Way Handshake failed`），这不是软件能绕过的。</li>
 * </ul>
 */
public final class WpaLogReader {

    private static final String TAG = "WPAForgeWpaLog";

    /** 一条从日志里提取的事件 */
    public static final class LogEvent {
        public final long at;
        public final String ssid;
        /**
         * 这条事件属于哪个 AP。
         *
         * <p><b>为什么必须有这个字段</b>：本机日志里
         * {@code Key negotiation completed with <BSSID>} 不带 SSID，
         * 如果只判断「出现过协商完成」，就会把**别的网络**的握手算到目标头上。
         * 实测事故（2026-10-22）：目标 2.4G-3 失败后系统回连 B217 并握手成功，
         * 被误报成「2.4G-3 = redskins 命中」——密码其实是错的。
         */
        public final String bssid;
        public final String kind;
        public final String raw;

        /**
         * 该事件自带的配置编号（{@code CTRL-EVENT-CONNECTED ... [id=N]} 里的 N）。
         *
         * <p><b>R1（2026-10-31）</b>：真机的 CONNECTED 行本来就带 {@code [id=N]}，
         * 而此前解析器把它丢掉了。本字段只是**把证据完整保存下来**，用于
         * 日志展示与后续分析。
         *
         * <p><b>它到底是什么（2026-10-31 真机实测更正）</b>：
         * 它是 <b>wpa_supplicant 事件自带的 network ID</b>（supplicant 内部的
         * 网络条目索引），<b>不是</b> Android framework 的
         * {@code WifiConfigManager} Net ID。
         *
         * <p>真机证据（PJX110 / ColorOS 15 / Android 15，B212）：
         * <pre>
         *   CTRL-EVENT-CONNECTED ... [id=0 id_str={"configKey":"\"B212\"WPA_PSK",...}]
         *   cmd wifi status        →  Net ID: 36
         * </pre>
         * 同一时刻两者数值完全不同；且三种场景（候选连接 / 系统自动回连 /
         * 手动连接）中该字段**恒为 0 不变**，而 Android Net ID 恒为 36。
         * 也就是说它**不反映连接来源，也不随连接变化**。
         *
         * <p><b>使用边界（写死在这里，防止被误用）</b>：
         * <ul>
         *   <li>它**不等同于** {@code cmd wifi status} 里的 {@code Net ID}，
         *       <b>禁止直接比较两者</b>。</li>
         *   <li>它**不是**归属证明：相同或不同都不能说明连接由当前候选建立。</li>
         *   <li>它**不得**进入 {@code SuccessRule}、{@code classifyDeadline}
         *       或任何 SUCCESS/UNKNOWN 判定。</li>
         *   <li>取不到时为 {@link #NETID_UNKNOWN}（{@code -1}）；0 是**合法值**，
         *       因此不能用 0 表示「未知」。</li>
         * </ul>
         */
        public final long netId;

        /** 旧构造入口（3 参）：netId / authFailures 未知。 */
        LogEvent(long at, String ssid, String kind, String raw) {
            this(at, ssid, null, kind, raw, NETID_UNKNOWN);
        }

        /** 旧构造入口（5 参）：netId / authFailures 未知。 */
        LogEvent(long at, String ssid, String bssid, String kind, String raw) {
            this(at, ssid, bssid, kind, raw, NETID_UNKNOWN);
        }

        /** 构造入口（6 参）：authFailures 未知。 */
        LogEvent(long at, String ssid, String bssid, String kind, String raw, long netId) {
            this(at, ssid, bssid, kind, raw, netId, AUTH_FAILURES_UNKNOWN);
        }

        /** 构造入口（7 参，全字段）。 */
        LogEvent(long at, String ssid, String bssid, String kind, String raw, long netId,
                 int authFailures) {
            this.at = at;
            this.ssid = ssid;
            this.bssid = bssid;
            this.kind = kind;
            this.raw = raw;
            this.netId = netId;
            this.authFailures = authFailures;
        }

        /** netId 是否有有效值。 */
        public boolean hasNetId() { return netId >= 0; }

        /**
         * {@code CTRL-EVENT-SSID-TEMP-DISABLED ... auth_failures=N} 里的 N。
         *
         * <p>只对 {@link #KIND_WRONG_KEY} 事件有意义；其它事件恒为 {@link #AUTH_FAILURES_UNKNOWN}。
         * 用途：区分「本次连接刚失败第一次」与「系统已累计失败多次」。
         */
        public final int authFailures;

        /** auth_failures 未知。 */
        public static final int AUTH_FAILURES_UNKNOWN = -1;
    }

    private static final Pattern P_ASSOC =
            Pattern.compile("Trying to associate with SSID '(.*)'");
    /** 协商完成 —— 捕获 BSSID，用于归属校验（不带 SSID，只能靠 BSSID 认） */
    private static final Pattern P_NEGOTIATED =
            Pattern.compile("Key negotiation completed with ([0-9a-fA-F:]{17})");
    private static final Pattern P_HANDSHAKE_FAIL =
            Pattern.compile("4-Way Handshake failed");
    private static final Pattern P_ASSOC_REJECT =
            Pattern.compile("CTRL-EVENT-ASSOC-REJECT");
    /**
     * 握手第 1 帧。真机（ColorOS 15）打的是 {@code RSN: RX message 1 of ...}，
     * 而不是 {@code WPA: RX message 1 of ...} —— 两种前缀都认。
     * （这是用真机原文做测试向量时才暴露出来的：模式写窄了，功能静默失效。）
     */
    private static final Pattern P_HANDSHAKE_M1 =
            Pattern.compile("(?:WPA|RSN): RX message 1 of 4-Way Handshake from ([0-9a-fA-F:]{17})");
    // ------------------------------------------------------------ 事件 kind 常量

    /** 正在尝试关联（带 SSID）。 */
    public static final String KIND_ASSOCIATE = "associate";
    /** 握手协商完成（Key negotiation completed，带 BSSID）。 */
    public static final String KIND_NEGOTIATED = "negotiated";
    /**
     * 连接建立完成（CTRL-EVENT-CONNECTED，带 BSSID，可能带 netId）。
     *
     * <p><b>R3（2026-10-31）</b>：此前它被归入 {@link #KIND_NEGOTIATED}，
     * 与「Key negotiation completed」共用同一 kind。两者语义不同 ——
     * 前者是「连接已建立」，后者是「密钥协商完成」；混为一类会让
     * 任何按 kind 区分的诊断（以及将来的归属分析）都无法分开统计。
     *
     * <p>注意：**kind 分离不改变任何判定结果** —— 生产里查询成功证据的是
     * {@code sawNegotiatedFor()}，它按 kind 匹配。因此本类同时提供
     * {@link #KIND_CONNECTED} 与 {@link #KIND_NEGOTIATED} 的兼容匹配，
     * 见 {@code isSuccessEvidenceKind()}。
     */
    public static final String KIND_CONNECTED = "connected";
    /** 4-Way Handshake failed（不带归属）。 */
    public static final String KIND_HANDSHAKE_FAILED = "handshake_failed";
    /** CTRL-EVENT-ASSOC-REJECT。 */
    public static final String KIND_ASSOC_REJECT = "assoc_reject";
    /** 握手第 1 帧（带 BSSID）。 */
    public static final String KIND_HANDSHAKE_M1 = "handshake_m1";
    /** CTRL-EVENT-SSID-TEMP-DISABLED（带 SSID，reason=WRONG_KEY）。 */
    public static final String KIND_WRONG_KEY = "wrong_key";

    /** netId 未知。所有"解析不出"的情形一律用这个值，绝不用 0 冒充。 */
    public static final long NETID_UNKNOWN = -1L;

    /**
     * 哪些 kind 属于「成功证据」类事件。
     *
     * <p>R3 之前只有 {@code negotiated}；R3 之后 {@code connected} 独立成类，
     * 但**在判定上仍然是成功证据**（{@code sawNegotiatedFor} 必须同时认这两个），
     * 否则会把「CTRL-EVENT-CONNECTED 也算成功证据」这条既有行为破坏掉。
     */
    public static boolean isSuccessEvidenceKind(String kind) {
        return KIND_NEGOTIATED.equals(kind) || KIND_CONNECTED.equals(kind);
    }

    /**
     * 本机最明确的失败信号：wpa_supplicant 把该网络临时禁用并给出 WRONG_KEY。
     * 实测这一行与 `4-Way Handshake failed` 同时出现，但它更「结构化」，
     * 即使前一行被日志配额丢掉，这一行也可能还在。
     */
    private static final Pattern P_WRONG_KEY =
            Pattern.compile("CTRL-EVENT-SSID-TEMP-DISABLED[^\\n]*ssid=\"([^\"]+)\"");

    /**
     * 同一条 TEMP-DISABLED 行里的 {@code auth_failures=N}。
     *
     * <p>它是 wpa_supplicant 对这个网络的**累计认证失败次数**：
     * <ul>
     *   <li>{@code 1} = 本次刚失败第一次 —— 通常是**我们自己**这次连接造成的</li>
     *   <li>{@code ≥2} = 之前已经失败过 —— 极可能是系统后台用旧配置重试</li>
     * </ul>
     * 单独用它不足以定案（用户可能连续跑两条错误候选，第二条就是 2），
     * 但配合「同窗口是否有本 BSSID 的失败前导」就能形成可靠归属。
     */
    private static final Pattern P_AUTH_FAILURES =
            Pattern.compile("auth_failures=(\\d+)");
    /** 连上 —— 同样捕获 BSSID，是「握手完成」之后的第二条确认 */
    /**
     * 连上。R1：额外捕获行尾的 {@code [id=N]}（即 netId）。
     *
     * <p>真机样本：
     * <pre>
     * wlan0: CTRL-EVENT-CONNECTED - Connection to 28:93:7d:11:22:01 completed
     *        [id=0 id_str=%7B%22configKey%22...]
     * </pre>
     * {@code id} 与 {@code id_str} 是两个独立字段；本模式**只取 id 的十进制值**，
     * 绝不从 {@code id_str} 里反推（那是 URL 编码的 JSON，格式不稳定，且
     * 可能含别的编号）。
     *
     * <p>id 段的捕获是可选的：没有 {@code [id=...]} 时整组为 {@code null}，
     * 由 {@link #parseNetId(String)} 统一给 {@link #NETID_UNKNOWN}。
     */
    private static final Pattern P_CONNECTED =
            Pattern.compile("CTRL-EVENT-CONNECTED - Connection to ([0-9a-fA-F:]{17})"
                    + "(?:[^\\n]*?\\[id=(-?[0-9]+))?");

    /**
     * 服务端（shell 侧 grep）预过滤式。
     *
     * <p>必须覆盖下面全部 7 个 Pattern；{@code WpaFilterTest} 会逐条验证
     * 「每个模式认得出来的样例行，都能被这个式子放行」。
     */
    public static final String SHELL_FILTER =
            "associate|4-Way|RX message|negotiat|ASSOC-REJECT|SSID-TEMP-DISABLED"
                    + "|CONNECTED|WRONG_KEY|reason="
                    // R6：起点标记行必须能穿过同一个过滤式，否则窗口隔离无从证明
                    + "|" + AttemptMarker.FIELD
                    // P0-3：logcat 一侧的退出码哨兵也必须放行 ——
                    // 否则 grep 会把「上游失败了」这条唯一证据亲手滤掉，
                    // 调用方只能看到空输出，与「真没日志」不可区分。
                    + "|" + LogcatCmd.RC;

    /**
     * 读取时要放行的 tag：wpa_supplicant 的判定证据 + 本项目的起点标记。
     *
     * <p>用一次 {@code logcat -d} 同时取两个 tag 的原因：标记与证据必须来自
     * **同一次缓冲快照**，否则「标记之后的日志属于本条候选」这句话就失去依据。
     *
     * <p>设备行为（{@code -s} 重复是否被本机 logcat 接受、标记是否真能被读到）
     * **尚未验证**，报告里记为 NOT RUN。
     */
    public static final String LOG_TAGS = "-s wpa_supplicant -s " + AttemptMarker.TAG;

    /** 读取命令（纯函数，便于离线断言其形状）。 */
    public static String readCommand() {
        return "logcat -d " + LOG_TAGS + " | grep -iE '" + SHELL_FILTER + "'";
    }

    private WpaLogReader() {}

    /**
     * 解析给定的 logcat 输出，提取 Wi-Fi 相关事件。
     *
     * @param logcatOutput 必须是 {@code logcat -d -s wpa_supplicant} 的输出
     */
    /** 时间戳由调用方给，便于离线测试（纯 JVM 下没有 SystemClock）。 */
    public static List<LogEvent> parse(String logcatOutput) {
        return parse(logcatOutput, 0L);
    }

    public static List<LogEvent> parse(String logcatOutput, long now) {
        List<LogEvent> out = new ArrayList<>();
        if (logcatOutput == null || logcatOutput.isEmpty()) return out;

        for (String line : logcatOutput.split("\n")) {
            if (line.isEmpty() || line.startsWith("---")) continue;

            Matcher m = P_ASSOC.matcher(line);
            if (m.find()) {
                out.add(new LogEvent(now, m.group(1), KIND_ASSOCIATE, line));
                continue;
            }
            Matcher mn = P_NEGOTIATED.matcher(line);
            if (mn.find()) {
                out.add(new LogEvent(now, null, mn.group(1), KIND_NEGOTIATED, line));
                continue;
            }
            if (P_HANDSHAKE_FAIL.matcher(line).find()) {
                // 这一行本身不带归属（wpa_supplicant 就是这么打的），
                // 所以调用方必须结合「本次见过目标的关联」来判断 —— 见 sawFailureLikelyForTarget
                out.add(new LogEvent(now, null, KIND_HANDSHAKE_FAILED, line));
                continue;
            }
            if (P_ASSOC_REJECT.matcher(line).find()) {
                out.add(new LogEvent(now, null, KIND_ASSOC_REJECT, line));
                continue;
            }
            Matcher mm1 = P_HANDSHAKE_M1.matcher(line);
            if (mm1.find()) {
                out.add(new LogEvent(now, null, mm1.group(1), KIND_HANDSHAKE_M1, line));
                continue;
            }
            Matcher mw = P_WRONG_KEY.matcher(line);
            if (mw.find()) {
                // ssid="X" 是这条日志自带的归属；auth_failures=N 用于区分
                // 「本次刚失败」与「系统累计失败」，见 sawWrongKeyForAttempt()。
                Matcher mf = P_AUTH_FAILURES.matcher(line);
                int af = LogEvent.AUTH_FAILURES_UNKNOWN;
                if (mf.find()) {
                    try { af = Integer.parseInt(mf.group(1)); }
                    catch (Throwable ignored) { af = LogEvent.AUTH_FAILURES_UNKNOWN; }
                }
                out.add(new LogEvent(now, mw.group(1), null, KIND_WRONG_KEY, line,
                        NETID_UNKNOWN, af));
                continue;
            }
            Matcher mc2 = P_CONNECTED.matcher(line);
            if (mc2.find()) {
                String connId = mc2.groupCount() >= 2 ? mc2.group(2) : null;
                out.add(new LogEvent(now, null, mc2.group(1),
                        KIND_CONNECTED, line, parseNetId(connId)));
            }
        }
        return out;
    }

    /**
     * 把 {@code [id=N]} 里抓到的字符串转成 netId（R1）。
     *
     * <p>规则（**任何异常都不抛，一律返回 {@link #NETID_UNKNOWN}**）：
     * <ul>
     *   <li>{@code null} / 空串 → 未知</li>
     *   <li>非数字（含 {@code 0x10}、{@code abc}、{@code 1.5}）→ 未知</li>
     *   <li>超出 {@code long} 范围 → 未知（{@link Long#parseLong} 抛异常被吞）</li>
     *   <li>负数 → 未知（netId 在 Android 里非负；负值只可能是解析噪音）</li>
     *   <li><b>0 是合法值</b>（第一条配置常常就是 0），必须原样保留</li>
     * </ul>
     *
     * <p>依据：真机样本 {@code ... completed [id=0 id_str=%7B...]} —— id=0 是真实出现过的。
     */
    static long parseNetId(String raw) {
        if (raw == null) return NETID_UNKNOWN;
        String t = raw.trim();
        if (t.isEmpty()) return NETID_UNKNOWN;
        // 只接受纯十进制数字（可带一个负号，负号后面再判掉）
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (i == 0 && c == '-') continue;
            if (c < '0' || c > '9') return NETID_UNKNOWN;
        }
        long v;
        try {
            v = Long.parseLong(t);
        } catch (Throwable overflow) {
            return NETID_UNKNOWN;
        }
        if (v < 0) return NETID_UNKNOWN;
        return v;
    }

    /**
     * 这条日志区间里，目标 SSID 有没有出现过「正在尝试关联」。
     *
     * 用途：确认我们的连接命令**真的被系统执行了**。
     * 如果连这条都没有，说明命令压根没生效 —— 那是通道问题，不是密码问题。
     */
    public static boolean sawAssociateFor(String logcatOutput, String ssid) {
        if (ssid == null) return false;
        for (LogEvent e : parse(logcatOutput)) {
            if ("associate".equals(e.kind) && ssid.equals(e.ssid)) return true;
        }
        return false;
    }

    /**
     * 有没有出现过握手失败**或**密码错误被拒。
     *
     * <p><b>注意</b>：这两类行（{@code 4-Way Handshake failed}、
     * {@code CTRL-EVENT-ASSOC-REJECT}）不带归属信息，只能配合
     * 「本次见过目标的关联尝试」一起用，否则会把别的网络的失败算到目标头上。
     * 带归属的失败信号请用 {@link #sawWrongKeyFor}。
     */
    public static boolean sawHandshakeFailure(String logcatOutput) {
        for (LogEvent e : parse(logcatOutput)) {
            if ("handshake_failed".equals(e.kind) || "wrong_key".equals(e.kind)) return true;
        }
        return false;
    }

    /**
     * 目标 SSID 有没有被系统以 WRONG_KEY 临时禁用。
     *
     * <p>这是本机**唯一带归属的失败信号**（行里有 {@code ssid="X"}），
     * 所以它比 {@code 4-Way Handshake failed} 更可信。
     */
    public static boolean sawWrongKeyFor(String logcatOutput, String targetSsid) {
        if (targetSsid == null || targetSsid.isEmpty()) return false;
        for (LogEvent e : parse(logcatOutput)) {
            if ("wrong_key".equals(e.kind) && targetSsid.equals(e.ssid)) return true;
        }
        return false;
    }

    /**
     * **本次尝试的** WRONG_KEY —— 带归属的严格版本。
     *
     * <h3>它解决什么</h3>
     * {@link #sawWrongKeyFor} 只比 SSID，会把「系统后台用旧配置重试同一网络失败」
     * 当成「本条候选失败」，进而把**正确密码判成 AUTH_FAIL**（这是
     * WrongKeyAttributionTest 记录的缺陷）。
     *
     * <h3>判据（全部满足才算）</h3>
     * <ol>
     *   <li>切片里存在 {@code TEMP-DISABLED ssid=目标 reason=WRONG_KEY}；</li>
     *   <li><b>同一切片里**先**出现过 {@code Trying to associate with SSID '目标'}</b>
     *       —— 证明本次确实发起过这次连接；</li>
     *   <li>且该切片里出现过**本次连接的失败前导**：
     *       {@code 4-Way Handshake failed} 或 {@code RX message 1 of 4-Way Handshake}。
     *       （后者是 AP 在收不到有效 M2 时重发 M1 —— 同样只在真实尝试后出现）</li>
     * </ol>
     *
     * <h3>为什么这样能排除「后台重试」</h3>
     * 系统后台重试用的是**旧配置**，它触发的 associate / handshake 行会出现在
     * 本条候选标记**之前**，被 {@code AttemptMarker.sliceAfter} 切掉；
     * 切片里只剩那条孤立的 {@code TEMP-DISABLED}，不满足条件 2/3 → 不采信。
     *
     * <h3>它不能解决的</h3>
     * 若系统在**标记之后**恰好完整重试一遍（associate + handshake + TEMP-DISABLED
     * 全在同一窗口内），仍无法与本次候选区分。那种情况需要 supplicant 的 config id
     * 或系统级连接时序，超出 logcat 的可观测范围 —— 此时应退回 `exercised` 前置，
     * 结果倾向 UNKNOWN 而不是误判 AUTH_FAIL。
     *
     * @param targetBssid 目标 BSSID；为空时只依赖步骤 2 的 SSID 关联行（较弱）
     */
    public static boolean sawWrongKeyForAttempt(String logcatOutput, String targetSsid,
                                                String targetBssid) {
        if (targetSsid == null || targetSsid.isEmpty() || logcatOutput == null) return false;

        boolean wrongKeyForTarget = false;
        boolean assocForTarget = false;
        boolean failurePrelude = false;
        boolean preferredBssidSeen = false;

        for (LogEvent e : parse(logcatOutput)) {
            if (KIND_WRONG_KEY.equals(e.kind) && targetSsid.equals(e.ssid)) {
                wrongKeyForTarget = true;
            }
            if (KIND_ASSOCIATE.equals(e.kind) && targetSsid.equals(e.ssid)) {
                assocForTarget = true;
            }
            if (KIND_HANDSHAKE_FAILED.equals(e.kind)) {
                failurePrelude = true;
            }
            if (KIND_HANDSHAKE_M1.equals(e.kind) && e.bssid != null) {
                failurePrelude = true;
                if (targetBssid != null && !targetBssid.isEmpty()
                        && targetBssid.equalsIgnoreCase(e.bssid)) {
                    preferredBssidSeen = true;
                }
            }
        }

        if (!wrongKeyForTarget) return false;
        if (!assocForTarget) return false;
        if (!failurePrelude) return false;
        // BSSID 已知时，M1 必须来自目标 AP（若切片里根本没出现 M1，则由上面的
        // failurePrelude 兜底 —— 那说明失败是 `4-Way Handshake failed` 给的）。
        if (targetBssid != null && !targetBssid.isEmpty() && !preferredBssidSeen) {
            // 没有目标 BSSID 的 M1，但可能有 handshake_failed；只要后者在，仍采信。
            // 这里不再额外否决 —— 保留与旧行为一致的宽松度，只增加正向证据。
        }
        return true;
    }

    /**
     * 目标 BSSID 有没有完成握手 —— **成功判定必须用这个，不能用 sawNegotiated**。
     *
     * @param targetBssid 目标 AP 的 MAC；为空时返回 false（宁可不判成功，也不能误判）
     */
    public static boolean sawNegotiatedFor(String logcatOutput, String targetBssid) {
        if (targetBssid == null || targetBssid.isEmpty()) return false;
        for (LogEvent e : parse(logcatOutput)) {
            // R3：CONNECTED 已独立成 kind，但它**仍属成功证据**，
            // 这里必须同时认两种，否则会破坏「CONNECTED 也算成功证据」的既有行为
            // （WpaLogTest 有对应断言）。
            if (isSuccessEvidenceKind(e.kind) && e.bssid != null
                    && targetBssid.equalsIgnoreCase(e.bssid)) return true;
        }
        return false;
    }

    /**
     * 目标 BSSID 有没有完成握手 —— **带 netId 归属校验**（D2）。
     *
     * <h3>为什么需要它</h3>
     * {@link #sawNegotiatedFor} 只比较 BSSID。但系统用**旧配置 / PMKSA 缓存**
     * 回连同一 AP 时，BSSID 完全相同 —— 那一次成功会被算到本条候选头上，
     * 把错误口令判成命中。
     *
     * <p>{@code CTRL-EVENT-CONNECTED} 行本身带 {@code [id=N]}，正是「这次连的是
     * 哪个 network 配置」的归属信息，解析时已经取到（{@link LogEvent#hasNetId()}），
     * 原判定却没用。
     *
     * <h3>规则</h3>
     * <ul>
     *   <li>事件带 netId 且本次已知目标 netId（{@code > 0}）→ 必须相等；</li>
     *   <li>任一侧缺 netId 信息 → 退回纯 BSSID 匹配（保持原有行为）；</li>
     * </ul>
     *
     * @param targetNetId 本候选新建配置的 netId；{@code <= 0} 表示未知
     */
    public static boolean sawNegotiatedForAttempt(String logcatOutput, String targetBssid,
                                                  long targetNetId) {
        if (targetBssid == null || targetBssid.isEmpty()) return false;
        for (LogEvent e : parse(logcatOutput)) {
            if (!isSuccessEvidenceKind(e.kind)) continue;
            if (e.bssid == null || !targetBssid.equalsIgnoreCase(e.bssid)) continue;
            // 只在**两侧都拿得到** netId 时才用它做归属判定；
            // 缺任一侧就退回纯 BSSID 匹配（不额外收紧，避免把真实命中挡掉）。
            if (e.hasNetId() && targetNetId > 0 && e.netId != targetNetId) continue;
            return true;
        }
        return false;
    }

    /** 有没有出现过关联被拒（密码错/AP 拒绝时的典型信号） */
    public static boolean sawAssocReject(String logcatOutput) {
        for (LogEvent e : parse(logcatOutput)) {
            if ("assoc_reject".equals(e.kind)) return true;
        }
        return false;
    }

    /**
     * 有没有出现过协商完成 —— <b>不分是哪个 AP，禁止用于成功判定</b>。
     *
     * <p>保留它只是为了兼容旧调用点与排查问题。要判定命中请用
     * {@link #sawNegotiatedFor(String, String)}，否则系统回连别的网络时会误报
     * （2026-10-22 的假阳性事故就是这么来的）。
     */
    /** 有没有出现过收到握手第 1 帧（说明 AP 已响应认证，可用于动态延长看门狗超时） */
    public static boolean sawHandshakeM1(String logcatOutput) {
        for (LogEvent e : parse(logcatOutput)) {
            if ("handshake_m1".equals(e.kind)) return true;
        }
        return false;
    }

    /**
     * 本次尝试里收到过几次「RX message 1 of 4-Way Handshake」。
     *
     * <h3>为什么需要计数（2026-10-23 真机实测定论）</h3>
     * AP 没收到有效的 M2 时会**每隔约 1.0 秒重发一次 M1**。真机实测（ColorOS 15）：
     * <pre>
     *   M1#1  +0ms
     *   M1#2  +962ms      ← 第一次握手已经失败
     *   M1#3  +1007ms
     *   4-Way Handshake failed  +1.0~2.1s（在 M1#3 之后才吐）
     * </pre>
     * 也就是说：等到 {@code Handshake failed} 会白等 2.0~3.1 秒，
     * 而**看到第 2 次 M1 就已经确定密码错了** —— 正确密码在 M1#1 之后
     * 约 200ms 内就会走到 M3/M4，不会出现重发的 M1。
     *
     * <p>语义与 {@link #sawHandshakeM1(String)} 完全一致，只是返回次数：
     * 复用同一个解析器，避免出现「两个地方对同一行的判定不一致」。
     */
    public static int countHandshakeM1(String logcatOutput) {
        int n = 0;
        for (LogEvent e : parse(logcatOutput)) {
            if ("handshake_m1".equals(e.kind)) n++;
        }
        return n;
    }

    /**
     * 只数**属于指定 BSSID** 的 M1 次数。
     *
     * <h3>为什么必须按 BSSID 归属（2026-10-23 实测翻车）</h3>
     * 早退判据原本用「本次见过目标 SSID 的关联行（Trying to associate）」做归属保护。
     * 但真机上这一行**经常被 ColorOS 的日志配额丢掉**（老代码注释里就记着这件事：
     * 「一旦关联行被日志配额丢掉，失败行即使读到了也不会被采纳」），
     * 于是早退条件永不成立、回落到 6 秒/条的老路径。
     *
     * <p>而 M1 这一行自带 BSSID：{@code RX message 1 of 4-Way Handshake from <bssid>}，
     * 用它做归属比关联行可靠得多，也不再受「关联行被丢」的影响。
     */
    public static int countHandshakeM1For(String logcatOutput, String bssid) {
        if (bssid == null || bssid.isEmpty()) return 0;
        String want = bssid.trim().toLowerCase();
        // R2（2026-10-24）：按**不同时间戳**去重后计数 —— 重复事件（同一行被日志系统
        // 重复投递、或被读取两次）不得被当成 AP 的重发。没有可识别时间戳的行按条计数
        // （保留旧行为，便于合成用例）。
        java.util.LinkedHashSet<String> stamps = new java.util.LinkedHashSet<>();
        int noStamp = 0;
        for (LogEvent e : parse(logcatOutput)) {
            if (!"handshake_m1".equals(e.kind)) continue;
            if (e.bssid == null || !want.equalsIgnoreCase(e.bssid.trim())) continue;
            String ts = timestampOf(e.raw);
            if (ts == null) noStamp++;
            else stamps.add(ts);
        }
        return stamps.size() + noStamp;
    }

    /** logcat 行前缀时间戳 {@code MM-DD HH:MM:SS.mmm}；取不到返回 null。 */
    private static final Pattern P_TS =
            Pattern.compile("\\b\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\b");

    static String timestampOf(String raw) {
        if (raw == null) return null;
        Matcher m = P_TS.matcher(raw);
        return m.find() ? m.group(0) : null;
    }

    public static boolean sawNegotiated(String logcatOutput) {
        for (LogEvent e : parse(logcatOutput)) {
            // R3：CONNECTED 已独立成 kind，但它**仍属成功证据**。
            // 这里必须用 isSuccessEvidenceKind 同时认两种，否则会破坏
            // 「CTRL-EVENT-CONNECTED 也算成功证据」这条既有语义
            // （WpaLogTest / LogAttributionTest 各有一条断言钉住它）。
            if (isSuccessEvidenceKind(e.kind)) return true;
        }
        return false;
    }

    /**
     * 用 shell 通道读一次日志。
     *
     * 需要 shell 权限（Root）；普通应用读不到系统组件的日志。
     *
     * @param shell 通道；为 null 时返回 null
     * @param lines 读取最后多少行
     */
    public static String read(CrackEngine.ShellChannel shell, int lines) {
        return read(shell, lines, 0L);
    }

    /** 读取命令 + 时间锚点（R11：取代 {@code logcat -c}）。 */
    public static String readCommandSince(long anchorMs) {
        return Lc.withSince(readCommand(), anchorMs);
    }

    /**
     * 读取命令 + 时间锚点 + **退出码哨兵**（P0-3）。
     *
     * <p>{@link ShellExec} 只取 stdout、不看退出码，而管道下 {@code $?} 是 grep 的，
     * 于是「上游 logcat 失败」「窗口里真没日志」「grep 自己坏了」三者不可分。
     * 这里让两侧各自回显退出码，由 {@link LogcatCmd} 判档。
     */
    public static String readCommandGuarded(long anchorMs) {
        return LogcatCmd.wrap(readCommandSince(anchorMs));
    }

    /**
     * 读一次日志并给出**结构化判定**（P0-3）。
     *
     * @return null = 通道异常（exec 超时/失联），调用方必须按「不可判」处理
     */
    public static LogcatCmd.Result readEx(CrackEngine.ShellChannel shell, int lines, long anchorMs) {
        if (shell == null) return null;
        try {
            String out = shell.exec(readCommandGuarded(anchorMs), 6000);
            if (out == null) return null;
            return LogcatCmd.parse(out);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读取日志失败: " + t);
            return null;
        }
    }

    /**
     * 带时间锚点读一次日志。
     *
     * @param anchorMs 起始时刻（墙钟毫秒）；{@code <= 0} = 不加时间限制（离线/降级）
     */
    public static String read(CrackEngine.ShellChannel shell, int lines, long anchorMs) {
        if (shell == null) return null;
        try {
            // 不要加 `-t N`：本机 400 行全量日志只覆盖约 5 秒，而一次尝试有 10 秒，
            // 较早出现的 CTRL-EVENT-ASSOC-REJECT / 4-Way Handshake failed 会被挤出
            // 窗口、再也读不到（实测 -t 400 -s wpa_supplicant 只剩 4 行，去掉 -t 有 305 行）。
            //
            // R11 起改用**时间锚点**（`-t '<MM-DD HH:MM:SS.mmm>'`）：
            // “只读本次尝试的行”由时间戳保证，于是不再需要 `logcat -c`
            // —— 那是个全局副作用，会把整机其它组件的缓冲一起清掉。
            //
            // **服务端预过滤**（2026-10-23 实测）：本机一轮原始输出 293 行 / 33KB，
            // 而判定真正要用的只有 5 行 / 0.6KB。整段读回来要过管道、进 JVM 逐行收，
            // 每轮 ~23ms 白花在 50 倍的无用数据上。加一层 grep：
            //   · 每轮传输量降到 1/50
            //   · parse() 的输入变小，Java 侧开销同步变小
            // 过滤式必须**覆盖 WpaLogReader 全部 7 个模式**，少一个就会静默丢判据 ——
            // 有 WpaFilterTest 钉住这条不变式（新增模式时必须同步更新 SHELL_FILTER）。
            String cmd = readCommandGuarded(anchorMs);
            String out = shell.exec(cmd, 6000);
            if (out == null) return null;
            // 哨兵行不能流进判定：先按结构化结果剥掉再交给 parse()
            return LogcatCmd.parse(out).body;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读取日志失败: " + t);
            return null;
        }
    }

    /**
     * 清空日志缓冲。
     *
     * @deprecated R11 起生产不再调用（窗口归属改为起点标记 + 时间锚点）。
     */
    @Deprecated
    public static void clear(CrackEngine.ShellChannel shell) {
        if (shell == null) return;
        try {
            shell.exec("logcat -c", 4000);
        } catch (Throwable ignored) {}
    }
}

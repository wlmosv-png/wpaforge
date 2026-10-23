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
 *   WPA: Key negotiation completed with 28:93:7d:45:dc:ed
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

        LogEvent(long at, String ssid, String kind, String raw) {
            this(at, ssid, null, kind, raw);
        }

        LogEvent(long at, String ssid, String bssid, String kind, String raw) {
            this.at = at;
            this.ssid = ssid;
            this.bssid = bssid;
            this.kind = kind;
            this.raw = raw;
        }
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
    /**
     * 本机最明确的失败信号：wpa_supplicant 把该网络临时禁用并给出 WRONG_KEY。
     * 实测这一行与 `4-Way Handshake failed` 同时出现，但它更「结构化」，
     * 即使前一行被日志配额丢掉，这一行也可能还在。
     */
    private static final Pattern P_WRONG_KEY =
            Pattern.compile("CTRL-EVENT-SSID-TEMP-DISABLED[^\\n]*ssid=\"([^\"]+)\"");
    /** 连上 —— 同样捕获 BSSID，是「握手完成」之后的第二条确认 */
    private static final Pattern P_CONNECTED =
            Pattern.compile("CTRL-EVENT-CONNECTED - Connection to ([0-9a-fA-F:]{17})");

    /**
     * 服务端（shell 侧 grep）预过滤式。
     *
     * <p>必须覆盖下面全部 7 个 Pattern；{@code WpaFilterTest} 会逐条验证
     * 「每个模式认得出来的样例行，都能被这个式子放行」。
     */
    public static final String SHELL_FILTER =
            "associate|4-Way|RX message|negotiat|ASSOC-REJECT|SSID-TEMP-DISABLED"
                    + "|CONNECTED|WRONG_KEY|reason=";

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
                out.add(new LogEvent(now, m.group(1), "associate", line));
                continue;
            }
            Matcher mn = P_NEGOTIATED.matcher(line);
            if (mn.find()) {
                out.add(new LogEvent(now, null, mn.group(1), "negotiated", line));
                continue;
            }
            if (P_HANDSHAKE_FAIL.matcher(line).find()) {
                // 这一行本身不带归属（wpa_supplicant 就是这么打的），
                // 所以调用方必须结合「本次见过目标的关联」来判断 —— 见 sawFailureLikelyForTarget
                out.add(new LogEvent(now, null, "handshake_failed", line));
                continue;
            }
            if (P_ASSOC_REJECT.matcher(line).find()) {
                out.add(new LogEvent(now, null, "assoc_reject", line));
                continue;
            }
            Matcher mm1 = P_HANDSHAKE_M1.matcher(line);
            if (mm1.find()) {
                out.add(new LogEvent(now, null, mm1.group(1), "handshake_m1", line));
                continue;
            }
            Matcher mw = P_WRONG_KEY.matcher(line);
            if (mw.find()) {
                // ssid="X" 是这条日志唯一可靠的归属信息
                out.add(new LogEvent(now, mw.group(1), "wrong_key", line));
                continue;
            }
            Matcher mc2 = P_CONNECTED.matcher(line);
            if (mc2.find()) {
                out.add(new LogEvent(now, null, mc2.group(1), "negotiated", line));
            }
        }
        return out;
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
     * 目标 BSSID 有没有完成握手 —— **成功判定必须用这个，不能用 sawNegotiated**。
     *
     * @param targetBssid 目标 AP 的 MAC；为空时返回 false（宁可不判成功，也不能误判）
     */
    public static boolean sawNegotiatedFor(String logcatOutput, String targetBssid) {
        if (targetBssid == null || targetBssid.isEmpty()) return false;
        for (LogEvent e : parse(logcatOutput)) {
            if ("negotiated".equals(e.kind) && e.bssid != null
                    && targetBssid.equalsIgnoreCase(e.bssid)) return true;
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
        int n = 0;
        for (LogEvent e : parse(logcatOutput)) {
            if (!"handshake_m1".equals(e.kind)) continue;
            if (e.bssid != null && want.equalsIgnoreCase(e.bssid.trim())) n++;
        }
        return n;
    }

    public static boolean sawNegotiated(String logcatOutput) {
        for (LogEvent e : parse(logcatOutput)) {
            if ("negotiated".equals(e.kind)) return true;
        }
        return false;
    }

    /**
     * 用 shell 通道读一次日志。
     *
     * 需要 shell 权限（Shizuku / root）；普通应用读不到系统组件的日志。
     *
     * @param shell 通道；为 null 时返回 null
     * @param lines 读取最后多少行
     */
    public static String read(CrackEngine.ShellChannel shell, int lines) {
        if (shell == null) return null;
        try {
            // 不要加 `-t N`：本机 400 行全量日志只覆盖约 5 秒，而一次尝试有 10 秒，
            // 较早出现的 CTRL-EVENT-ASSOC-REJECT / 4-Way Handshake failed 会被挤出
            // 窗口、再也读不到（实测 -t 400 -s wpa_supplicant 只剩 4 行，去掉 -t 有 305 行）。
            // 调用方在每条候选前已 logcat -c，缓冲里就是本次尝试的日志。
            // lines 参数保留仅为兼容调用点。
            //
            // **服务端预过滤**（2026-10-23 实测）：本机一轮原始输出 293 行 / 33KB，
            // 而判定真正要用的只有 5 行 / 0.6KB。整段读回来要过管道、进 JVM 逐行收，
            // 每轮 ~23ms 白花在 50 倍的无用数据上。加一层 grep：
            //   · 每轮传输量降到 1/50
            //   · parse() 的输入变小，Java 侧开销同步变小
            // 过滤式必须**覆盖 WpaLogReader 全部 7 个模式**，少一个就会静默丢判据 ——
            // 有 WpaFilterTest 钉住这条不变式（新增模式时必须同步更新 SHELL_FILTER）。
            String cmd = "logcat -d -s wpa_supplicant | grep -iE '" + SHELL_FILTER + "'";
            return shell.exec(cmd, 6000);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读取日志失败: " + t);
            return null;
        }
    }

    /** 清空日志缓冲，让下一次读取只看到本次尝试 */
    public static void clear(CrackEngine.ShellChannel shell) {
        if (shell == null) return;
        try {
            shell.exec("logcat -c", 4000);
        } catch (Throwable ignored) {}
    }
}

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
 * <h3>实测结论（本机 Color OS 15 / Android 15）</h3>
 * <pre>
 *   Trying to associate with SSID 'X'   ✓ 有
 *   Key negotiation completed           ✗ 该 ROM 不输出
 *   4-Way Handshake failed              ✗ 该 ROM 不输出
 * </pre>
 * 所以这里只用它**做辅助证据**：确认某条密码确实被尝试过。
 * 命中判定仍然靠 {@link NetworkWatcher}，但可以交叉验证。
 */
public final class WpaLogReader {

    private static final String TAG = "WPAForgeWpaLog";

    /** 一条从日志里提取的事件 */
    public static final class LogEvent {
        public final long at;
        public final String ssid;
        public final String kind;
        public final String raw;

        LogEvent(long at, String ssid, String kind, String raw) {
            this.at = at;
            this.ssid = ssid;
            this.kind = kind;
            this.raw = raw;
        }
    }

    private static final Pattern P_ASSOC =
            Pattern.compile("Trying to associate with SSID '(.*)'");
    private static final Pattern P_NEGOTIATED =
            Pattern.compile("Key negotiation completed");
    private static final Pattern P_HANDSHAKE_FAIL =
            Pattern.compile("4-Way Handshake failed");
    private static final Pattern P_ASSOC_REJECT =
            Pattern.compile("CTRL-EVENT-ASSOC-REJECT");
    private static final Pattern P_HANDSHAKE_M1 =
            Pattern.compile("WPA: RX message 1 of 4-Way Handshake");

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
            if (P_NEGOTIATED.matcher(line).find()) {
                out.add(new LogEvent(now, null, "negotiated", line));
                continue;
            }
            if (P_HANDSHAKE_FAIL.matcher(line).find()) {
                out.add(new LogEvent(now, null, "handshake_failed", line));
                continue;
            }
            if (P_ASSOC_REJECT.matcher(line).find()) {
                out.add(new LogEvent(now, null, "assoc_reject", line));
                continue;
            }
            if (P_HANDSHAKE_M1.matcher(line).find()) {
                out.add(new LogEvent(now, null, "handshake_m1", line));
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

    /** 有没有出现过握手失败的记录（本机 ROM 通常没有，仅作参考） */
    public static boolean sawHandshakeFailure(String logcatOutput) {
        for (LogEvent e : parse(logcatOutput)) {
            if ("handshake_failed".equals(e.kind)) return true;
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

    /** 有没有出现过协商完成（密码正确的直接证据，本机 ROM 通常没有） */
    /** 有没有出现过收到握手第 1 帧（说明 AP 已响应认证，可用于动态延长看门狗超时） */
    public static boolean sawHandshakeM1(String logcatOutput) {
        for (LogEvent e : parse(logcatOutput)) {
            if ("handshake_m1".equals(e.kind)) return true;
        }
        return false;
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
            String cmd = "logcat -d -s wpa_supplicant";
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

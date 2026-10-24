package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WiFi 连接状态日志读取器（ColorOS / AOSP 通用）。
 *
 * <h3>为什么不用 wpa_supplicant 日志</h3>
 * 参考实现（bszapp/android-wifi-pojie）用 wpa_supplicant 的
 * {@code WPA: Key negotiation completed} 判成功。
 * 但实测本机（ColorOS 15 / Android 15）**不输出**这行，
 * 只输出 {@code nl80211: ... assoc} 之类，抓不到可靠的成功证据。
 *
 * <h3>ColorOS / AOSP 上真正的证据：WifiClientModeImpl 状态机</h3>
 * 系统 WiFi 状态机在每次状态迁移时打一条日志：
 * <pre>
 *   尝试中：ClientModeImpl$ConnectableState ... "B209"WPA_PSK nid=608
 *   已连上：ClientModeImpl$L2ConnectedState screen=on 36 0 "B209" 28:93:7d:11:22:02 rssi=-48 ...
 * </pre>
 * 关键区别：
 * <ul>
 *   <li>L2ConnectedState 后面**带 SSID 和 BSSID** → 真的连上了这个 AP</li>
 *   <li>失败回连别的网络时，L2ConnectedState 那行**不带 SSID**（只有一个 IP 段），
 *       所以绝不能只凭 "出现了 L2ConnectedState" 就判成功 —— 必须 SSID 对上</li>
 * </ul>
 * 这就是本机可用的黄金判据，比轮询 WifiInfo 更早、更准。
 *
 * <h3>实测记录</h3>
 * <pre>
 *   连对：ConnectableState "B209"WPA_PSK → L2ConnectedState ... "B209" 28:93:7d:...
 *   连错：ConnectableState "B209"WPA_PSK → （无 B209 的 L2ConnectedState）→ 回连 211
 * </pre>
 */
public final class WifiStateLog {

    private static final String TAG = "WPAForgeWifiState";

    /** 一条状态日志事件 */
    public static final class Event {
        public final long at;
        public final String ssid;   // 可能为 null（无 SSID 的状态行）
        public final String kind;   // connected / attempt / disconnected
        public final String raw;

        Event(long at, String ssid, String kind, String raw) {
            this.at = at;
            this.ssid = ssid;
            this.kind = kind;
            this.raw = raw;
        }

        @Override
        public String toString() {
            return kind + ":" + ssid + "@" + at;
        }
    }

    /**
     * 已连上：ClientModeImpl$L2ConnectedState ... "SSID" AA:BB:...
     *
     * 注意 BSSID 段：后面紧跟 MAC。没有 MAC 的 L2ConnectedState（回连、未知）
     * 不会被这条正则命中，这是刻意的 —— 我们要的是「SSID 明确的连接」。
     */
    private static final Pattern P_CONNECTED = Pattern.compile(
            "ClientModeImpl\\$L2ConnectedState[^\"\\n]*\"([^\"]+)\"\\s+([0-9a-fA-F]{2}:)");

    /** 尝试中：ClientModeImpl$ConnectableState ... "SSID"WPA_PSK */
    private static final Pattern P_ATTEMPT = Pattern.compile(
            "ClientModeImpl\\$ConnectableState[^\"\\n]*\"([^\"]+)\"");

    /** 断开：ClientModeImpl$DisconnectedState */
    private static final Pattern P_DISCONNECTED = Pattern.compile(
            "ClientModeImpl\\$DisconnectedState");

    private WifiStateLog() {}

    /**
     * 解析 logcat 输出，抽取 WiFi 状态事件。
     *
     * @param logcatOutput logcat 文本
     * @param now          这些事件的时间基准（离线测试传 0）
     */
    public static List<Event> parse(String logcatOutput, long now) {
        List<Event> out = new ArrayList<>();
        if (logcatOutput == null || logcatOutput.isEmpty()) return out;

        for (String line : logcatOutput.split("\n")) {
            if (line.isEmpty()) continue;

            Matcher mc = P_CONNECTED.matcher(line);
            if (mc.find()) {
                out.add(new Event(now, mc.group(1), "connected", line));
                continue;
            }
            Matcher ma = P_ATTEMPT.matcher(line);
            if (ma.find()) {
                out.add(new Event(now, ma.group(1), "attempt", line));
                continue;
            }
            if (P_DISCONNECTED.matcher(line).find()) {
                out.add(new Event(now, null, "disconnected", line));
            }
        }
        return out;
    }

    public static List<Event> parse(String logcatOutput) {
        return parse(logcatOutput, 0L);
    }

    /**
     * 核心判据：这段日志里，目标 SSID 有没有出现过「已连上（带 BSSID）」。
     *
     * @return true = 目标明确连上了
     */
    public static boolean sawConnectedFor(String logcatOutput, String ssid) {
        if (ssid == null || logcatOutput == null) return false;
        for (Event e : parse(logcatOutput)) {
            if ("connected".equals(e.kind) && ssid.equals(e.ssid)) return true;
        }
        return false;
    }

    /**
     * 目标有没有出现过「正在尝试」。
     *
     * 用途：区分「根本没试」和「试了但失败」。
     * 如果连尝试都没有，是通道问题，不是密码问题。
     *
     * 判据放宽为：只要出现过目标的 ConnectableState（尝试）**或**
     * L2ConnectedState（已连上，必然也尝试过），都算「试过」。
     * 之所以要带上 L2ConnectedState —— 有些 ROM/日志级别下
     * ConnectableState 那行不带 SSID，成功日志里可能找不到它。
     */
    public static boolean sawAttemptFor(String logcatOutput, String ssid) {
        if (ssid == null || logcatOutput == null) return false;
        for (Event e : parse(logcatOutput)) {
            if (ssid.equals(e.ssid)
                    && ("attempt".equals(e.kind) || "connected".equals(e.kind))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从日志里抽「最近一次成功连接的 SSID」。
     * 用于交叉验证：系统说连上了谁。
     */
    public static String lastConnectedSsid(String logcatOutput) {
        String last = null;
        for (Event e : parse(logcatOutput)) {
            if ("connected".equals(e.kind) && e.ssid != null) last = e.ssid;
        }
        return last;
    }

    // ---------------------------------------------------------------- shell IO

    /**
     * 读 WiFi 状态日志。
     *
     * **不能用 `-s WifiClientModeImpl`**：ColorOS/AOSP 打关键状态行时，
     * tag 实际是 `WifiClientModeImpl[<id>:wlan0]`（带实例后缀），
     * 用 `-s` 精确匹配 tag 会**一条都拿不到**（实测：-s 得 0 条，全量 grep 得 15 条）。
     * 所以这里读 main 缓冲的尾部若干行，过滤交给本地正则做。
     */
    public static String read(CrackEngine.ShellChannel shell, int lines) {
        return read(shell, lines, 0L);
    }

    /**
     * 带时间锚点的读取（R11：不再依赖 {@code logcat -c}）。
     *
     * @param anchorMs 起始时刻（墙钟毫秒）；{@code <= 0} 表示不加时间限制
     */
    public static LogcatCmd.Result readEx(CrackEngine.ShellChannel shell, long anchorMs) {
        if (shell == null) return null;
        try {
            String cmd = Lc.withSince("logcat -d -e 'ClientModeImpl'", anchorMs);
            String out = shell.exec(LogcatCmd.wrap(cmd), 8000);
            if (out == null) return null;
            return LogcatCmd.parse(out);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读取状态日志失败: " + t);
            return null;
        }
    }

    public static String read(CrackEngine.ShellChannel shell, int lines, long anchorMs) {
        LogcatCmd.Result r = readEx(shell, anchorMs);
        return r == null ? null : r.body;
    }

    /** 旧实现（无哨兵）保留为私有参照，避免误用。 */
    private static String readRaw(CrackEngine.ShellChannel shell, long anchorMs) {
        if (shell == null) return null;
        try {
            // 用 -e 正则按行内容过滤，而不是读尾部 N 行。两个实测原因：
            //   · 本机状态机 tag 带实例后缀（WifiClientModeImpl[123:wlan0]），
            //     `-s WifiClientModeImpl` 精确匹配拿不到（实测 0 条）
            //   · `-t N` 先取全部缓冲的最后 N 行再过滤，本机 600 行只覆盖约 7 秒，
            //     一次尝试（10 秒）里较早的 L2ConnectedState 会被挤出窗口（实测 0 条）
            // R11 起另加**时间锚点**（-t '<MM-DD HH:MM:SS.mmm>'），
            // 于是“只读本次尝试的行”由时间戳保证，不再需要清空全局缓冲。
            String cmd = Lc.withSince("logcat -d -e 'ClientModeImpl'", anchorMs);
            return shell.exec(cmd, 8000);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读取状态日志失败: " + t);
            return null;
        }
    }

    /**
     * 日志清理的**结果**（不是「成功/失败」两个字，而是分开记录可判与不可判的部分）。
     *
     * <p>为什么需要它：{@code logcat -c} 成功时**不产生任何输出**，
     * 所以「输出为空」既可能是成功、也可能是命令没跑起来 —— 不能当失败判据。
     * 唯一可判的是**这一帧有没有拿到**（{@code ShellChannel.exec} 返回 null
     * 表示超时或会话失联）。因此这里只把 {@link #frameOk} 作为「清理动作是否
     * 可被信任为已执行」的依据，并把输出原样保留供诊断。
     */
    public static final class ClearResult {
        /** 是否真的发出了清空命令（shell 通道为空时为 false）。 */
        public final boolean issued;
        /** exec 是否返回了帧（null = 超时/失联，无法确认命令是否执行）。 */
        public final boolean frameOk;
        /** 命令输出（正常为空串）。 */
        public final String output;

        ClearResult(boolean issued, boolean frameOk, String output) {
            this.issued = issued;
            this.frameOk = frameOk;
            this.output = output == null ? "" : output;
        }

        /** 清理动作可否被认为已执行（后续仍要靠起点标记证明窗口边界）。 */
        public boolean ok() { return issued && frameOk; }

        public String describe() {
            return (issued ? "" : "not-issued") + (frameOk ? "" : "no-frame")
                    + (ok() ? "ok" : "");
        }
    }

    /**
     * 清空日志缓冲并**返回结果**（替代原来的 void 版本）。
     *
     * @deprecated R11 起生产代码**不再调用**：窗口归属改为「起点标记 + 时间锚点」，
     *     不再需要动全局缓冲。保留它是为了留住历史审计的对照物
     *     （R3 把它列为“高风险假设：从未校验清空是否成功”），
     *     删掉会让那段结论失去引用目标。新代码请用
     *     {@link #read(CrackEngine.ShellChannel, int, long)}。
     */
    @Deprecated
    public static ClearResult clearChecked(CrackEngine.ShellChannel shell) {
        if (shell == null) return new ClearResult(false, false, null);
        try {
            String out = shell.exec("logcat -c", 4000);
            return new ClearResult(true, out != null, out);
        } catch (Throwable t) {
            return new ClearResult(true, false, null);
        }
    }

    /**
     * 清空日志缓冲，让读取只包含本次尝试的记录。
     *
     * @deprecated 同 {@link #clearChecked}：生产不再调用。
     */
    @Deprecated
    public static void clear(CrackEngine.ShellChannel shell) {
        clearChecked(shell);
    }
}

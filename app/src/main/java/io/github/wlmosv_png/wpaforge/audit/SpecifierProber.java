package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 免 root 精确连接器（Android 10+）。
 *
 * <h3>为什么用它，而不是 WifiNetworkSuggestion</h3>
 * <pre>
 * WifiNetworkSuggestion  提交进白名单，系统自己挑时间连。
 *                        应用**无法知道**它到底试没试 → 判定只能靠猜 → 误判。
 * WifiNetworkSpecifier   指定「就这个 SSID、就这个密码」，系统立刻去连，
 *                        成功/失败通过 NetworkCallback 明确回调。
 * </pre>
 *
 * 这是 Android 10 起，普通应用**唯一**能精确控制单次连接的方法。
 *
 * <h3>判定语义（这是全部意义所在）</h3>
 * <pre>
 *   onAvailable()   → 系统确认连上了这个网络 → **密码正确**
 *   onUnavailable() → 系统尝试后失败       → **密码错误**
 *   onLost()        → 已连上又被断开
 *   超时            → 明确报超时，**不判成功**
 * </pre>
 *
 * <h3>已知限制（必须如实告诉用户）</h3>
 * <ul>
 *   <li>每次 requestNetwork 会弹一次系统确认框（App 在前台时）；
 *       部分 ROM 会合并成一次。</li>
 *   <li>连接成功后系统会**自动断开**（这是 specifier 的语义：一次性连接）。</li>
 *   <li>速度取决于系统，通常 2~8 秒一条，比 Root/Shizuku 慢。</li>
 * </ul>
 *
 * <p>这些限制是系统设计，不是实现缺陷。免 root 想更快只能走 Shizuku。
 */
public final class SpecifierProber {

    private static final String TAG = "WPAForgeSpecifier";

    /** 单条候选的最长等待。系统尝试带 4-way 握手，给足时间。 */
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;

    /** 结果 */
    public enum Verdict {
        /** 系统确认连上 → 密码正确 */
        SUCCESS,
        /** 系统尝试后失败 → 密码错误 */
        AUTH_FAIL,
        /** 超时，没等到任何回调 → 不判成功 */
        TIMEOUT,
        /** 出错（权限、SSID 非法、重复请求等） */
        ERROR,
        /** 用户点了停止 */
        ABORTED
    }

    public static final class Result {
        public final String password;
        public final Verdict verdict;
        public final long elapsedMs;
        public final String detail;

        Result(String password, Verdict v, long ms, String detail) {
            this.password = password;
            this.verdict = v;
            this.elapsedMs = ms;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isSuccess() { return verdict == Verdict.SUCCESS; }
    }

    /** 进度回调 */
    public interface Listener {
        void onAttemptStart(int index, int total, String candidate);
        void onAttemptResult(Result r);
        void onProgress(int tested, int total, double rate, long etaMs);
        /** 一句话说明，不中断任务 */
        default void onLog(String line) { }
        /** 整个任务结束 */
        void onFinished(int tested, int total, String hitPassword);
        /** 无法开始（权限/版本/目标非法） */
        void onBlocked(String title, String detail);
    }

    private final Context appCtx;
    private final WifiManager wifi;
    private final ConnectivityManager cm;

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);

    /** 当前活跃的回调，用于取消 */
    private volatile ConnectivityManager.NetworkCallback activeCallback;

    public SpecifierProber(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        this.cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    // ---------------------------------------------------------------- 前置检查

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

    /**
     * 这个 ROM 上能不能用。
     *
     * 逐项检查并给出可诊断的原因 —— 不做「能用/不能用」的黑盒判断。
     */
    public static CheckResult check(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new CheckResult(false,
                    L.s("系统版本过低", "Android version too old"),
                    L.s("精确连接需要 Android 10（API 29）或更高。\n\n"
                            + "当前设备是 Android " + Build.VERSION.RELEASE + "。\n"
                            + "这台设备只能用 Root 模式。",
                        "Precise connection needs Android 10 (API 29) or higher.\n\n"
                            + "This device runs Android " + Build.VERSION.RELEASE + ".\n"
                            + "Only root mode is available here."));
        }
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return new CheckResult(false,
                    L.s("无法访问连接管理服务", "Cannot access ConnectivityManager"),
                    L.s("系统没有提供 ConnectivityManager。", "The system exposed no ConnectivityManager."));
        }
        WifiManager wm = (WifiManager)
                ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            return new CheckResult(false,
                    L.s("无法访问 WiFi 服务", "Cannot access the Wi-Fi service"),
                    L.s("系统没有提供 WifiManager。", "The system exposed no WifiManager."));
        }
        if (!wm.isWifiEnabled()) {
            return new CheckResult(false,
                    L.s("WiFi 未开启", "Wi-Fi is off"),
                    L.s("请先打开 WiFi 开关再试。", "Turn Wi-Fi on first."));
        }
        return new CheckResult(true, "", "");
    }

    // ---------------------------------------------------------------- 单条连接

    /**
     * 连一条候选，**阻塞**直到有明确结论或超时。
     *
     * 请放在工作线程调用。
     *
     * @param ssid      目标 SSID（不含引号）
     * @param password  候选密码
     * @param timeoutMs 最长等待
     */
    public Result tryConnect(String ssid, String password, long timeoutMs) {
        final long t0 = SystemClock.uptimeMillis();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new Result(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0,
                    L.s("需要 Android 10+", "needs Android 10+"));
        }
        if (ssid == null || ssid.isEmpty()) {
            return new Result(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0, L.s("SSID 为空", "empty SSID"));
        }
        if (password == null || password.length() < 8 || password.length() > 63) {
            // WPA 合法长度 8..63。长度不合法连试都不用试。
            return new Result(password, Verdict.AUTH_FAIL,
                    SystemClock.uptimeMillis() - t0,
                    L.s("长度不合法（WPA 要求 8-63 字符）", "illegal length (WPA needs 8-63 chars)"));
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger outcome = new AtomicInteger(0); // 0=未定 1=成功 2=失败

        WifiNetworkSpecifier specifier;
        try {
            specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build();
        } catch (Throwable t) {
            return new Result(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0,
                    L.s("构建请求失败: ", "build specifier failed: ") + t.getClass().getSimpleName());
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // 关键：不要 INTERNET 能力，否则系统会因为它「没有外网」而拒绝
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        final ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // 二次校验：确认这个 network 真的是目标 SSID。
                // 只信 onAvailable 不够 —— 有些 ROM 会因为别的网络触发。
                if (verifyConnected(ssid)) {
                    outcome.set(1);
                } else {
                    // 回调来了但 SSID 对不上 → 不算成功
                    android.util.Log.w(TAG, "onAvailable 但 SSID 不匹配: "
                            + currentSsid() + " != " + ssid);
                    outcome.set(2);
                }
                latch.countDown();
            }

            @Override
            public void onUnavailable() {
                // 系统明确说「连不上」→ 密码错误（或 AP 不在范围内）
                outcome.set(2);
                latch.countDown();
            }

            @Override
            public void onLost(Network network) {
                // 已连上后被断开。如果是等待期间丢的，当作未成功。
                if (latch.getCount() > 0) {
                    outcome.set(2);
                    latch.countDown();
                }
            }
        };

        activeCallback = cb;

        try {
            cm.requestNetwork(request, cb);
        } catch (SecurityException se) {
            activeCallback = null;
            return new Result(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0,
                    L.s("缺少权限: ", "missing permission: ") + se.getMessage());
        } catch (Throwable t) {
            activeCallback = null;
            return new Result(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0,
                    L.s("发起请求失败: ", "requestNetwork failed: ") + t.getClass().getSimpleName());
        }

        try {
            // 分片等待，方便响应取消
            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            while (SystemClock.uptimeMillis() < deadline) {
                if (cancelFlag.get() || pauseFlag.get()) {
                    return new Result(password, Verdict.ABORTED,
                            SystemClock.uptimeMillis() - t0,
                            L.s("已中断", "aborted"));
                }
                if (latch.await(200, TimeUnit.MILLISECONDS)) break;
            }

            long ms = SystemClock.uptimeMillis() - t0;

            if (latch.getCount() > 0) {
                // 超时：**不判成功**
                return new Result(password, Verdict.TIMEOUT, ms,
                        L.s("超时（系统未给出结论）", "timeout (no verdict from system)"));
            }

            switch (outcome.get()) {
                case 1:
                    return new Result(password, Verdict.SUCCESS, ms,
                            L.s("已连上", "connected"));
                case 2:
                    return new Result(password, Verdict.AUTH_FAIL, ms,
                            L.s("未连上", "not connected"));
                default:
                    return new Result(password, Verdict.TIMEOUT, ms,
                            L.s("未得到结论", "no verdict"));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Result(password, Verdict.ABORTED,
                    SystemClock.uptimeMillis() - t0, L.s("已中断", "interrupted"));
        } finally {
            release(cb);
            activeCallback = null;
        }
    }

    /** 释放回调。必须调，否则系统会一直挂着请求。 */
    private void release(ConnectivityManager.NetworkCallback cb) {
        try {
            cm.unregisterNetworkCallback(cb);
        } catch (Throwable ignored) {}
        try {
            // 有些 ROM 需要显式断开，否则 specifier 连接一直占用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cm.bindProcessToNetwork(null);
            }
        } catch (Throwable ignored) {}
    }

    /** 当前连着的 SSID（已去引号） */
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

    /**
     * 二次校验：当前 Wi-Fi 是不是真的连到了目标。
     *
     * onAvailable 表示「有一个满足 NetworkRequest 的网络可用」，
     * 但某些 ROM 会在这上面做手脚（比如切换到别的同 SSID AP）。
     * 这里再核一遍 SSID，双保险。
     */
    private boolean verifyConnected(String ssid) {
        // 给系统一点时间把 WifiInfo 更新过来
        for (int i = 0; i < 10; i++) {
            String cur = currentSsid();
            if (cur != null && cur.equals(ssid)) return true;
            try { Thread.sleep(150); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    // ---------------------------------------------------------------- 批量跑

    public void cancel() {
        cancelFlag.set(true);
        ConnectivityManager.NetworkCallback cb = activeCallback;
        if (cb != null) {
            try { cm.unregisterNetworkCallback(cb); } catch (Throwable ignored) {}
        }
    }

    public void pause() { pauseFlag.set(true); }
    public void resume() { pauseFlag.set(false); }
    public boolean isPaused() { return pauseFlag.get(); }
}

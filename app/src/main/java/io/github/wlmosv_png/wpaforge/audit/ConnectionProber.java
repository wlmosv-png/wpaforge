package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.NetworkRequest.Builder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 逐条候选连接器。
 *
 * 做法：调用系统的 Wi-Fi 连接 API，一条接一条地尝试，
 * 监听真实的 supplicant 状态回调来判断"错密码"还是"连上了"。
 *
 * 重要事实（决定使用姿势）：
 *  - 单次尝试需要 3~8 秒（等认证结果或超时），所以速度是每分钟 8~20 条，
 *    不是每秒几百条。这是 Android 的物理限制，无法绕过。
 *  - 认证反馈是真实的：错密码会收到 AUTHENTICATION_FAILURE，成功会拿到网络。
 *  - 因此字典必须小而精（建议 ≤500 条），不要丢十万条进去跑。
 *
 * 使用边界：只应对自己有权连接的网络使用（自有路由器改密码测试、
 * 公司授权的渗透测试）。对无权访问的网络，本模块既无意义也不被允许。
 */
public final class ConnectionProber {

    public enum Verdict {
        PENDING,     // 正在尝试
        SUCCESS,     // 连上了
        AUTH_FAIL,   // 认证失败（密码错）
        TIMEOUT,     // 超时，无明确反馈
        UNAVAILABLE  // 网络不可达 / 其他错误
    }

    /** 一条尝试记录 */
    public static final class Attempt {
        public final String ssid;
        public final String candidate;
        public final Verdict verdict;
        public final long elapsedMs;
        public final String note;

        public Attempt(String ssid, String candidate, Verdict v, long ms, String note) {
            this.ssid = ssid;
            this.candidate = candidate;
            this.verdict = v;
            this.elapsedMs = ms;
            this.note = note == null ? "" : note;
        }
    }

    public interface Listener {
        /** 开始尝试某一条 */
        void onAttemptStart(int index, int total, String ssid, String candidate);
        /** 某一条得出结果 */
        void onAttemptResult(Attempt attempt);
        /** 进度：已试 / 总数 / 成功数 / 速率（条/秒）/ 预计剩余毫秒 */
        void onProgress(int tested, int total, int success, double rate, long etaMs);
        /** 全部跑完（含被中止） */
        void onFinished(boolean aborted, int tested, int success);
        /** 成功命中 */
        void onHit(String ssid, String password);
        /** 无法开始时的说明 */
        void onBlocked(String reason);
    }

    private static final long CONNECT_TIMEOUT_MS = 8000L;  // 单条最长等待
    private static final long SETTLE_MS = 400L;            // 两条之间的间隔，给系统喘气
    private static final String TAG = "WPAForgeProber";

    private final Context appCtx;
    private final WifiManager wifi;
    private final ConnectivityManager cm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private Listener listener;
    private BroadcastReceiver supplicantReceiver;
    private ConnectivityManager.NetworkCallback netCallback;

    // 当前这一条的实时状态（由回调写入，主循环读取）
    private volatile Verdict currentVerdict = Verdict.PENDING;
    private volatile boolean currentAuthenticated = false;
    /** 最近一次失败的详细信息，直接显示到界面上，省得翻日志 */
    private volatile String lastError = "";

    public ConnectionProber(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        this.cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void cancel() {
        cancelFlag.set(true);
    }

    /**
     * 跑一轮。会阻塞调用线程，请放在工作线程。
     *
     * @param target     目标网络（必须有 BSSID 与加密类型）
     * @param candidates 候选口令，建议 ≤500 条
     */
    public void run(NetRecord target, List<Candidate> candidates, Listener l) {
        this.listener = l;

        if (wifi == null || cm == null) {
            l.onBlocked(L.s("设备不支持 Wi-Fi 连接管理", "Device does not support Wi-Fi connection management"));
            return;
        }
        if (target == null || target.bssid.isEmpty()) {
            l.onBlocked(L.s("目标网络信息不完整（缺少 BSSID）", "Incomplete target info (missing BSSID)"));
            return;
        }
        if (target.security == NetRecord.Security.OPEN) {
            l.onBlocked(L.s("目标网络是开放的，不需要口令", "Target is open, no passphrase needed"));
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            l.onBlocked(L.s("候选字典为空，请先生成字典", "Candidate dictionary is empty"));
            return;
        }
        if (running.getAndSet(true)) {
            l.onBlocked(L.s("已有任务在运行", "A task is already running"));
            return;
        }
        cancelFlag.set(false);

        // 关键前提检查：如果当前正连着目标网络，requestNetwork 会立刻返回成功，
        // 不管传什么密码 —— 那会造成假阳性。必须先断开。
        if (isCurrentlyOn(target)) {
            l.onBlocked("你当前正连接着 " + target.displayName()
                    + "。请先在系统 WiFi 设置里断开该网络，再跑试连，"
                    + "否则每次尝试都会误报成功。");
            running.set(false);
            return;
        }

        registerListeners();

        int total = candidates.size();
        int tested = 0;
        int success = 0;
        long startAt = SystemClock.uptimeMillis();

        try {
            for (Candidate c : candidates) {
                if (cancelFlag.get()) break;

                tested++;
                l.onAttemptStart(tested, total, target.displayName(), c.password);

                long t0 = SystemClock.uptimeMillis();
                Verdict v = attemptOne(target, c.password);
                long dt = SystemClock.uptimeMillis() - t0;

                if (v == Verdict.SUCCESS) {
                    success++;
                    l.onAttemptResult(new Attempt(target.displayName(), c.password,
                            v, dt, "已连接"));
                    l.onHit(target.displayName(), c.password);
                    break;
                }

                l.onAttemptResult(new Attempt(target.displayName(), c.password, v, dt,
                        noteOf(v)));

                double rate = tested / Math.max(0.001, (SystemClock.uptimeMillis() - startAt) / 1000.0);
                long eta = rate > 0 ? (long) ((total - tested) / rate * 1000) : -1;
                l.onProgress(tested, total, success, rate, eta);

                if (cancelFlag.get()) break;
                sleepQuiet(SETTLE_MS);
            }
        } finally {
            unregisterListeners();
            cleanupConfig(target);
            running.set(false);
            l.onFinished(cancelFlag.get(), tested, success);
        }
    }

    /** 当前是否正连着目标网络（按 SSID 判断） */
    private boolean isCurrentlyOn(NetRecord target) {
        try {
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getSSID() == null) return false;
            String cur = info.getSSID();
            // 系统返回的 SSID 带引号
            String plain = cur.startsWith("\"") && cur.endsWith("\"")
                    ? cur.substring(1, cur.length() - 1) : cur;
            return plain.equals(target.ssid);
        } catch (Exception e) {
            return false;
        }
    }

    private String noteOf(Verdict v) {
        switch (v) {
            case AUTH_FAIL: return L.s("认证失败", "Authentication failed");
            case TIMEOUT: return L.s("超时未响应", "No response, timed out");
            case UNAVAILABLE: return lastError.isEmpty() ? L.s("网络不可达", "Network unreachable") : lastError;
            default: return "";
        }
    }

    /**
     * 单条尝试。
     * Android 10+ 用 WifiNetworkSpecifier 走"应用级连接请求"，
     * 配合 NetworkCallback 能拿到真实的成功/失败。
     */
    private Verdict attemptOne(NetRecord target, String password) {
        currentVerdict = Verdict.PENDING;
        currentAuthenticated = false;

        if (Build.VERSION.SDK_INT >= 29) {
            return attemptModern(target, password);
        } else {
            return attemptLegacy(target, password);
        }
    }

    private Verdict attemptModern(NetRecord target, String password) {
        WifiNetworkSpecifier.Builder sb = new WifiNetworkSpecifier.Builder();
        sb.setSsid(target.ssid);
        // 强制指定 BSSID，绕开"系统里已有同名保存配置"导致的歧义。
        // 这是关键：不锁 BSSID 时，系统可能用已保存的凭据直接连，
        // 我们就看不到真实的"这个密码对不对"。
        try {
            sb.setBssid(parseMacObj(target.bssid));
        } catch (Exception e) {
            android.util.Log.w(TAG, L.s("setBssid 失败，退回仅 SSID 匹配: ", "setBssid failed, falling back to SSID only: ") + e);
        }
        sb.setWpa2Passphrase(password);

        NetworkRequest req = new Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(sb.build())
                .build();

        final Object lock = new Object();
        final AtomicBoolean done = new AtomicBoolean(false);
        final Verdict[] result = {Verdict.TIMEOUT};

        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                android.util.Log.i(TAG, "onAvailable net=" + network + " pw=" + password);
                synchronized (lock) {
                    if (!done.get()) {
                        result[0] = Verdict.SUCCESS;
                        done.set(true);
                        lock.notifyAll();
                    }
                }
            }
            @Override public void onUnavailable() {
                android.util.Log.i(TAG, "onUnavailable pw=" + password);
                synchronized (lock) {
                    if (!done.get()) {
                        result[0] = Verdict.AUTH_FAIL;
                        done.set(true);
                        lock.notifyAll();
                    }
                }
            }
            @Override public void onLost(Network network) {
                android.util.Log.i(TAG, "onLost net=" + network + " pw=" + password);
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                android.util.Log.d(TAG, "onCapabilitiesChanged validated="
                        + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
        };

        try {
            cm.requestNetwork(req, cb);
            android.util.Log.i(TAG, L.s("requestNetwork 已提交 pw=", "requestNetwork submitted pw=") + password
                    + " ssid=" + target.ssid + " bssid=" + target.bssid);
        } catch (Exception e) {
            android.util.Log.e(TAG, L.s("requestNetwork 异常", "requestNetwork exception"), e);
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return Verdict.UNAVAILABLE;
        }

        long deadline = SystemClock.uptimeMillis() + CONNECT_TIMEOUT_MS;
        synchronized (lock) {
            while (!done.get()) {
                long remain = deadline - SystemClock.uptimeMillis();
                if (remain <= 0) break;
                try {
                    lock.wait(remain);
                } catch (InterruptedException e) {
                    break;
                }
                if (cancelFlag.get()) break;
            }
        }

        try {
            cm.unregisterNetworkCallback(cb);
        } catch (Exception ignored) {}

        android.util.Log.i(TAG, L.s("attemptOne 结果=", "attemptOne result=") + result[0] + " pw=" + password);
        return result[0];
    }

    /** Android 9 及以下：走传统的 addNetwork / enableNetwork */
    @SuppressWarnings("deprecation")
    private Verdict attemptLegacy(NetRecord target, String password) {
        try {
            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = quote(target.ssid);
            cfg.preSharedKey = quote(password);
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);

            int netId = wifi.addNetwork(cfg);
            if (netId < 0) return Verdict.UNAVAILABLE;

            currentAuthenticated = false;
            wifi.disconnect();
            boolean enabled = wifi.enableNetwork(netId, true);
            if (!enabled) {
                wifi.removeNetwork(netId);
                return Verdict.UNAVAILABLE;
            }

            long deadline = SystemClock.uptimeMillis() + CONNECT_TIMEOUT_MS;
            while (SystemClock.uptimeMillis() < deadline) {
                if (cancelFlag.get()) break;
                if (currentAuthenticated) {
                    wifi.saveConfiguration();
                    return Verdict.SUCCESS;
                }
                sleepQuiet(200);
            }

            wifi.removeNetwork(netId);
            wifi.saveConfiguration();
            return currentVerdict == Verdict.PENDING ? Verdict.TIMEOUT : currentVerdict;
        } catch (Exception e) {
            return Verdict.UNAVAILABLE;
        }
    }

    // ---------------- 回调注册 ----------------

    private void registerListeners() {
        supplicantReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(intent.getAction())) return;
                SupplicantState st = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
                int err = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                if (err == WifiManager.ERROR_AUTHENTICATING) {
                    currentVerdict = Verdict.AUTH_FAIL;
                }
                if (st == SupplicantState.COMPLETED) {
                    currentAuthenticated = true;
                    currentVerdict = Verdict.SUCCESS;
                }
            }
        };
        IntentFilter f = new IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            appCtx.registerReceiver(supplicantReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appCtx.registerReceiver(supplicantReceiver, f);
        }
    }

    private void unregisterListeners() {
        if (supplicantReceiver != null) {
            try { appCtx.unregisterReceiver(supplicantReceiver); } catch (Exception ignored) {}
            supplicantReceiver = null;
        }
        if (netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            netCallback = null;
        }
    }

    /** 收拾掉本次留下的配置，别污染系统 Wi-Fi 列表。
     *  注意：绝不删除用户的真实配置。只做日志记录，让用户自己决定。 */
    private void cleanupConfig(NetRecord target) {
        try {
            @SuppressWarnings("deprecation")
            List<WifiConfiguration> cfgs = wifi.getConfiguredNetworks();
            if (cfgs == null) return;
            String wanted = "\"" + target.ssid + "\"";
            for (WifiConfiguration c : cfgs) {
                if (c.SSID != null && c.SSID.equals(wanted)) {
                    android.util.Log.i(TAG, L.s("保留用户已有配置，不删除: ", "Keeping existing user config: ") + c.SSID);
                }
            }
        } catch (Exception ignored) {}
    }

    private static byte[] parseMac(String mac) {
        String[] parts = mac.split(":");
        if (parts.length != 6) return new byte[6];
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            try {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** API 29+ 的 WifiNetworkSpecifier 需要 MacAddress 对象 */
    private static android.net.MacAddress parseMacObj(String mac) {
        try {
            return android.net.MacAddress.fromString(mac);
        } catch (Exception e) {
            return android.net.MacAddress.fromString("02:00:00:00:00:00");
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}

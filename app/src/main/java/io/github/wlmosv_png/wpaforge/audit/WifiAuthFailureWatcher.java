package io.github.wlmosv_png.wpaforge.audit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;

/**
 * 认证失败监听器 —— 用**系统广播**判定「密码错误」，完全不依赖 logcat。
 *
 * <h3>为什么要它（2026-10-23 真机实测的结论）</h3>
 * 之前所有早退判据都建立在「能读到 wpa_supplicant 日志」这个前提上。
 * 但实测（ColorOS 15 / 一加 Ace 3 Pro）：
 * <pre>
 *   同一台机器，wpa_supplicant 日志**有时 300+ 行、有时 0 行**
 *   （系统对每个进程有日志配额，超出部分被丢弃）
 *   甚至 App 自己的通道自检都打印过「logcat 可读: 否」
 * </pre>
 * 日志一不可读，早退全部失效，只能回落去等状态机（实测 5.7~6.5 秒/条）。
 *
 * <h3>这个类做什么</h3>
 * 系统在 WPA 握手失败时会发
 * {@link WifiManager#SUPPLICANT_STATE_CHANGED_ACTION}，且
 * {@code EXTRA_SUPPLICANT_ERROR == ERROR_AUTHENTICATING}。
 * **系统自己弹的那个「密码错误，请重试」对话框就是靠这个广播** ——
 * 它由 framework 发出，厂商的日志配额砍不掉它，也不需要 root。
 *
 * <p>判定规则（纯函数 {@link #authErrorImpliesWrongKey}）：
 * 「命令发出之后出现过认证失败」且「没有见过成功证据」→ 这条候选密码错。
 * 只可能漏判、不可能误判成功 —— 成功始终由四条件同行独占判定。
 */
public final class WifiAuthFailureWatcher {

    private static final String TAG = "WPAForgeAuthCast";

    private final Context appCtx;
    private BroadcastReceiver receiver;
    private volatile boolean registered = false;

    /** 最近一次「认证失败」广播的时刻（uptimeMillis，0 = 从未） */
    private volatile long lastErrorAt = 0L;
    /** 该广播时刻读到的 SSID（可能为空，仅作参考归属） */
    private volatile String lastErrorSsid = null;
    private volatile int errorCount = 0;

    public WifiAuthFailureWatcher(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** 注册监听。可重复调用。 */
    public void start() {
        if (registered) return;
        final WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                try {
                    if (intent == null) return;
                    if (!WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(intent.getAction())) return;
                    int err = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                    if (err != WifiManager.ERROR_AUTHENTICATING) return;
                    String ssid = null;
                    try {
                        WifiInfo wi = wm.getConnectionInfo();
                        if (wi != null) ssid = clean(wi.getSSID());
                    } catch (Throwable ignored) {}
                    lastErrorSsid = ssid;
                    lastErrorAt = SystemClock.uptimeMillis();
                    errorCount++;
                } catch (Throwable ignored) {}
            }
        };
        IntentFilter f = new IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ 必须显式给导出标志；只收系统广播 → NOT_EXPORTED
                appCtx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appCtx.registerReceiver(receiver, f);
            }
            registered = true;
        } catch (Throwable t) {
            registered = false;
            android.util.Log.w(TAG, "registerReceiver 失败: " + t);
        }
    }

    /** 反注册。 */
    public void stop() {
        if (!registered || receiver == null) { registered = false; return; }
        try { appCtx.unregisterReceiver(receiver); } catch (Throwable ignored) {}
        registered = false;
    }

    public boolean isRegistered() { return registered; }

    /** 最近一次认证失败的时刻（uptimeMillis；0 = 从未） */
    public long lastErrorAt() { return lastErrorAt; }

    /** 最近一次认证失败时读到的 SSID（可能为空） */
    public String lastErrorSsid() { return lastErrorSsid; }

    /** 认证失败广播累计次数（排查用） */
    public int errorCount() { return errorCount; }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        if ("<unknown ssid>".equals(t) || "0x".equals(t)) return null;
        return t;
    }

    /**
     * 判据的**纯函数**形式（便于离线单测，不碰 Android API）。
     *
     * <p>「命令发出之后出现过认证失败」且「没有见过成功证据」→ 密码错。
     *
     * @param errorAt        最近一次认证失败广播的时刻（uptimeMillis）
     * @param attemptStartAt 本条候选发出连接命令的时刻（uptimeMillis）
     * @param successSeen    本轮是否已见到属于目标的成功证据（四条件 / 握手完成）
     */
    public static boolean authErrorImpliesWrongKey(long errorAt, long attemptStartAt,
                                                   boolean successSeen) {
        return !successSeen && errorAt > attemptStartAt && attemptStartAt > 0;
    }

    /** SSID 归属：广播里读到 SSID 才校验；读不到（<unknown ssid>）时不做过滤。 */
    public static boolean ssidMatches(String errorSsid, String targetSsid) {
        if (errorSsid == null || errorSsid.isEmpty()) return true;
        if (targetSsid == null) return true;
        return targetSsid.equals(errorSsid);
    }
}

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

    /**
     * 代际号：每次 {@link #clear()} 自增。
     *
     * <p>广播是**异步投递**的：第 N 条（错误密码）的失败广播可能在
     * 第 N+1 条发命令之后才到达。只靠「时间戳晚于命令时刻」拦不住它，
     * 因为它确实晚于 N+1 的 commandAt。代际号把「这条广播产生于哪一代」钉死：
     * 只有与当前代际一致的广播才被采信。
     */
    private volatile long generation = 0L;

    /** 最近一次「认证失败」广播产生时的代际 */
    private volatile long lastErrorGeneration = -1L;

    /** 最近一次「认证失败」广播的时刻（uptimeMillis，0 = 从未） */
    private volatile long lastErrorAt = 0L;
    /** 该广播时刻读到的 SSID（可能为空，仅作参考归属） */
    private volatile String lastErrorSsid = null;
    private volatile int errorCount = 0;

    public WifiAuthFailureWatcher(Context ctx) {
        // 容忍 null：纯逻辑自测时不需要真实 Context（start() 之外的方法都不碰 appCtx）。
        this.appCtx = ctx == null ? null : ctx.getApplicationContext();
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
                    lastErrorGeneration = generation;
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

    /**
     * 测试注入标志（**生产永不置位**）。
     *
     * <p>为什么需要：{@link #isRegistered()} 为假时判定段直接跳过整个 authcast
     * 分支。而 JVM 测试里没有真实 BroadcastReceiver，注册必然失败 ——
     * 于是「authcast 这条唯一能给 DIRECT 的失败出口」在测试里**完全不可达**，
     * 成了判定链上唯一没被生产入口测试覆盖的路径。
     */
    private volatile boolean autoInjectForTest = false;
    private volatile String autoInjectSsid = null;

    public boolean isRegistered() { return registered || autoInjectForTest; }

    /**
     * **仅供测试**：让每一条候选的窗口内都「稳定收到一次认证失败广播」。
     *
     * <p>语义：模拟真机上「每次尝试都失败并弹认证失败」的持续状态。
     * 实现上不注册任何 Receiver，只在 {@link #clear()} 末尾把
     * 「最近一次失败」重设为当前代际 + 极大时刻（保证 {@code errorAt > commandAt}）。
     *
     * <p>生产代码路径**完全不变**：本方法只被测试调用，不影响真实采集。
     */
    public void armAutoAuthFailureForTest(String ssid) {
        this.autoInjectSsid = ssid;
        this.autoInjectForTest = true;
    }

    /** 仅供测试：撤销注入。 */
    public void disarmAutoAuthFailureForTest() {
        this.autoInjectForTest = false;
        this.autoInjectSsid = null;
        lastErrorAt = 0L;
        lastErrorSsid = null;
        lastErrorGeneration = -1L;
    }

    /** 最近一次认证失败的时刻（uptimeMillis；0 = 从未） */
    public long lastErrorAt() { return lastErrorAt; }

    /** 最近一次认证失败时读到的 SSID（可能为空） */
    public String lastErrorSsid() { return lastErrorSsid; }

    /** 认证失败广播累计次数（排查用） */
    public int errorCount() { return errorCount; }

    /**
     * 清空「最近一次认证失败」的跨轮残留（每条候选发命令前调用）。
     *
     * <p>为什么必须有它（2026-11-01 漏判审计）：{@code lastErrorAt}/{@code lastErrorSsid}
     * 是实例级、跨轮不清零。虽然判定条件 {@code errorAt > attemptStartAt} 用时间戳
     * 挡「上一条的失败」，但广播是异步投递的 —— 第 N 条（错误密码）的认证失败广播
     * 若延迟到第 N+1 条（正确密码）发命令之后才到达，且 120ms 复核窗口内成功证据
     * 尚未出现，第 N+1 条就会被抢答成 AUTH_FAIL（漏判）。发命令前清零，让每条候选
     * 只认「自己窗口内新到的失败广播」。
     */
    public void clear() {
        generation++;
        lastErrorAt = 0L;
        lastErrorSsid = null;
        lastErrorGeneration = -1L;
        // 测试注入：把「失败广播」重设到**当前代际**（真机上每条候选都会被系统
        // 再广播一次失败，代际本来就是新的）。生产不会走到这里。
        if (autoInjectForTest) {
            lastErrorSsid = autoInjectSsid;
            lastErrorAt = Long.MAX_VALUE;
            lastErrorGeneration = generation;
        }
    }

    /** 当前代际（排查用）。 */
    public long currentGeneration() { return generation; }

    /** 最近一次失败广播所属的代际（-1 = 从未收到）。 */
    public long lastErrorGeneration() { return lastErrorGeneration; }

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

    /**
     * 带**代际校验**的版本（审计 A3）：迟到广播必须被丢弃。
     *
     * <p>判定条件在时间戳之外再加一条：`广播产生的代际 == 当前代际`。
     * 上一轮 clear() 之前产生的广播，代际必然落后，即使它的时间戳晚于本条命令时刻，
     * 也不会再把下一条候选误判成失败。
     *
     * @param errorGeneration 该广播产生时的代际
     * @param currentGeneration 当前代际（clear() 时自增）
     */
    public static boolean authErrorImpliesWrongKeyGen(long errorAt, long attemptStartAt,
                                                      boolean successSeen,
                                                      long errorGeneration,
                                                      long currentGeneration) {
        if (errorGeneration != currentGeneration) return false;   // 迟到广播，丢弃
        return authErrorImpliesWrongKey(errorAt, attemptStartAt, successSeen);
    }

    /**
     * **严格归属**：广播里的 SSID 必须与目标逐字相等才算属于本目标。
     *
     * <p>与 {@link #ssidMatches} 的区别：读不到 SSID 时返回 **false**（不采信），
     * 而不是放行。放行会让「别的网络的认证失败」被算到目标头上 —— 审计 A4。
     * 采信一条归属不明的广播去永久排除一个候选，代价远大于少排除一条。
     */
    public static boolean ssidOwnedByTarget(String errorSsid, String targetSsid) {
        if (errorSsid == null || errorSsid.isEmpty()) return false;
        if (targetSsid == null || targetSsid.isEmpty()) return false;
        return targetSsid.equals(errorSsid);
    }

    /** SSID 归属：广播里读到 SSID 才校验；读不到（<unknown ssid>）时不做过滤。 */
    public static boolean ssidMatches(String errorSsid, String targetSsid) {
        if (errorSsid == null || errorSsid.isEmpty()) return true;
        if (targetSsid == null) return true;
        return targetSsid.equals(errorSsid);
    }
}

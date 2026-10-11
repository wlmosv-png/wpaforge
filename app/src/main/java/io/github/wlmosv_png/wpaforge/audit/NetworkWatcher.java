package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接事件监听器 —— 判定层的唯一真值来源。
 *
 * <h3>为什么要有这个类</h3>
 * 之前的判定靠**轮询** `WifiInfo.getSSID()` 和解析 `cmd wifi status` 文本。
 * 这两条都不可靠：
 * <pre>
 *   WifiInfo.getSSID()   Android 10+ 受限且有延迟；
 *                        更要命的是它只回答「现在连着谁」，
 *                        不回答「是谁造成的连接」
 *   cmd wifi status      文本解析，随版本变、可能被截断
 * </pre>
 * 于是出现了「任何密码都成功」「破解出来的密码连不上」这类问题 ——
 * 本质上是因为**我们从来没有拿到过一个可信的"连上了"事件**。
 *
 * <h3>这个类做什么</h3>
 * 注册 {@link ConnectivityManager#registerNetworkCallback}，
 * 让系统在**网络可用 / 丢失的那一刻**主动通知我们。
 * 回调里带的是 {@link Network} 对象，可以用
 * {@link ConnectivityManager#getNetworkCapabilities(Network)} 反查 SSID ——
 * 这是系统级的事实，不是轮询猜的。
 *
 * <h3>判定的核心问题：归因</h3>
 * 光知道「X 连上了」不够，还要知道「是刚才那条候选造成的吗」。
 * 做法是把事件**加上时间戳**，判定时问：
 * <pre>
 *   在「发出这条候选的时刻」之后，有没有出现过 X 的 onAvailable？
 * </pre>
 * 只有这个问题的答案是「有」，才算这条候选的功劳。
 * 候选发出之前就存在的连接，无论多新，都不算。
 */
public final class NetworkWatcher {

    private static final String TAG = "WPAForgeNetWatch";

    /** 一条连接事件 */
    public static final class Event {
        public final String ssid;
        /** true = 变为可用，false = 丢失 */
        public final boolean available;
        /** 事件发生时刻（uptimeMillis） */
        public final long at;
        /** 事件来源，排查用 */
        public final String source;

        Event(String ssid, boolean available, long at, String source) {
            this.ssid = ssid;
            this.available = available;
            this.at = at;
            this.source = source;
        }

        @Override
        public String toString() {
            return (available ? "+" : "-") + ssid + "@" + at + "(" + source + ")";
        }
    }

    private final Context appCtx;
    private final ConnectivityManager cm;
    private final WifiManager wifi;

    /** 事件历史，有界（只保留最近 200 条） */
    private final List<Event> events = new ArrayList<>();
    private static final int MAX_EVENTS = 200;

    /** 当前活跃的 network → ssid 映射，用于 onLost 时反查 */
    private final java.util.Map<Network, String> activeNetworks = new java.util.HashMap<>();

    private ConnectivityManager.NetworkCallback callback;
    private boolean registered = false;

    /** 回调触发次数（不管能不能拿到 SSID） */
    private int callbackHits = 0;
    /** 触发但拿不到 SSID 的次数 —— 判定失效最可能的原因 */
    private int ssidMisses = 0;
    /** 是否从 caps 成功拿到过 SSID */
    private boolean capsWorked = false;

    public NetworkWatcher(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 开始监听。幂等。
     *
     * 用 {@code addTransportType(WIFI)} 过滤 —— 只关心 Wi-Fi，
     * 移动数据、蓝牙的上下线与我们无关。
     */
    public synchronized void start() {
        if (registered || cm == null) return;
        try {
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    String ssid = ssidOf(network);
                    synchronized (NetworkWatcher.this) {
                        if (ssid != null) activeNetworks.put(network, ssid);
                        record(ssid, true, "onAvailable");
                    }
                }

                @Override
                public void onLost(Network network) {
                    String ssid;
                    synchronized (NetworkWatcher.this) {
                        ssid = activeNetworks.remove(network);
                        if (ssid == null) ssid = ssidOf(network);
                        record(ssid, false, "onLost");
                    }
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    // 有些 ROM 只在 capabilities 变化时更新 SSID，
                    // 这里补一次记录（避免 onAvailable 时 SSID 还没就绪）
                    String ssid = ssidFromCaps(caps);
                    if (ssid == null) return;
                    synchronized (NetworkWatcher.this) {
                        String prev = activeNetworks.get(network);
                        if (prev == null || !prev.equals(ssid)) {
                            activeNetworks.put(network, ssid);
                            record(ssid, true, "caps");
                        }
                    }
                }
            };

            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(req, callback);
            registered = true;
            android.util.Log.i(TAG, "已开始监听 Wi-Fi 网络事件");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "注册监听失败: " + t);
            registered = false;
        }
    }

    /** 监听是否真的注册成功了。判定完全依赖它，必须能对外报告。 */
    public synchronized boolean isRegistered() {
        return registered;
    }

    public synchronized int callbackHits() { return callbackHits; }
    public synchronized int ssidMisses() { return ssidMisses; }
    public synchronized boolean capsWorked() { return capsWorked; }

    /** 一句话诊断，给界面用 */
    public synchronized String diagnose() {
        if (!registered) return "listener not registered";
        if (callbackHits == 0) return "registered, but the system fired no callbacks";
        if (ssidMisses == callbackHits) {
            return "fired " + callbackHits + "x, SSID unavailable every time"
                    + (capsWorked ? "" : " (getTransportInfo also unavailable)");
        }
        return "fired " + callbackHits + "x, " + ssidMisses + " without SSID";
    }

    public synchronized void stop() {
        if (!registered || cm == null) return;
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (Throwable ignored) {}
        registered = false;
        callback = null;
        activeNetworks.clear();
    }

    // ------------------------------------------------------------ 查询

    /**
     * **核心判据**：从 {@code sinceMs} 之后，目标 SSID 有没有出现过「变为可用」事件。
     *
     * @param sinceMs 通常传「这条候选发出的时刻」
     * @return 首次出现的时刻；没出现返回 -1
     */
    public synchronized long firstAvailableAfter(String ssid, long sinceMs) {
        if (ssid == null) return -1;
        for (Event e : events) {
            if (e.at < sinceMs) continue;
            if (e.available && ssid.equals(e.ssid)) return e.at;
        }
        return -1;
    }

    /** 从 {@code sinceMs} 之后目标 SSID 是否出现过丢失事件 */
    public synchronized boolean lostAfter(String ssid, long sinceMs) {
        if (ssid == null) return false;
        for (Event e : events) {
            if (e.at < sinceMs) continue;
            if (!e.available && ssid.equals(e.ssid)) return true;
        }
        return false;
    }

    /** 清空历史（每次任务开始前调） */
    public synchronized void clear() {
        events.clear();
    }

    /** 最近的事件，用于日志 */
    public synchronized List<Event> recent(int n) {
        int from = Math.max(0, events.size() - n);
        return new ArrayList<>(events.subList(from, events.size()));
    }

    public synchronized int eventCount() {
        return events.size();
    }

    // ------------------------------------------------------------ 内部

    private void record(String ssid, boolean available, String source) {
        callbackHits++;
        if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid)) {
            ssidMisses++;
            android.util.Log.w(TAG, "callback fired but no SSID (source=" + source
                    + ") hits=" + callbackHits + " misses=" + ssidMisses);
            return;
        }
        if (events.size() >= MAX_EVENTS) events.remove(0);
        events.add(new Event(ssid, available, SystemClock.uptimeMillis(), source));
    }

    /**
     * 拿某个 Network 对应的 SSID。
     *
     * 优先 NetworkCapabilities.getTransportInfo()（最精确），
     * 拿不到时退回 WifiInfo —— 前者在部分 ROM 上返回 null 或
     * `<unknown ssid>`，直接丢弃事件会让判定彻底失效，
     * 退一步用 WifiInfo 至少能保留事件。
     */
    private String ssidOf(Network network) {
        try {
            if (network != null && cm != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                String byCaps = ssidFromCaps(caps);
                if (byCaps != null) return byCaps;
            }
        } catch (Throwable ignored) {}
        // 兜底
        String byWifi = currentSsid();
        if (byWifi != null) {
            android.util.Log.i(TAG, "SSID 由 WifiInfo 兜底取得: " + byWifi);
        }
        return byWifi;
    }

    private String ssidFromCaps(NetworkCapabilities caps) {
        if (caps == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Object ti = caps.getTransportInfo();
                if (ti instanceof WifiInfo) {
                    String v = cleanSsid(((WifiInfo) ti).getSSID());
                    if (v != null) capsWorked = true;
                    return v;
                }
                android.util.Log.w(TAG, "getTransportInfo unavailable: " + ti);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String cleanSsid(String raw) {
        if (raw == null) return null;
        String s = raw;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if ("<unknown ssid>".equals(s) || "0x".equals(s)) return null;
        return s;
    }

    /**
     * 当前实际连着的 SSID（兜底用）。
     *
     * 注意：这个值**不能单独用来判定命中** ——
     * 它只说明"现在连着谁"，不说明"是谁连的"。
     * 判定请用 {@link #firstAvailableAfter}。
     */
    public String currentSsid() {
        try {
            if (wifi == null) return null;
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return null;
            return cleanSsid(info.getSSID());
        } catch (Throwable t) {
            return null;
        }
    }
}

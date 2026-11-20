package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * **Android 公共 Wi-Fi API 最小可行验证模块**（2026-11-20 第四阶段）。
 *
 * <h3>定位</h3>
 * 这**不是**破解引擎，是**一次一条凭据**的可行性验证器：
 * <ul>
 *   <li>只验证调用方明确提供的一条凭据；<\/li>
 *   <li>系统要确认框就正常弹（不绕过、不模拟）；<\/li>
 *   <li>记录请求 ID / Network / SSID / BSSID / 回调顺序 / 耗时 / 最终七态；<\/li>
 *   <li>**绝不后台循环试密码**；<\/li>
 *   <li>绝不拿 onAvailable 单独证明口令正确，也绝不拿 onUnavailable 单独证明口令错误。<\/li>
 * <\/ul>
 *
 * <h3>与 {@link SpecifierChannel} 的关系</h3>
 * 那个是正式执行链里的通道实现（含超时、稳定窗口、旧网络排除）；
 * 这个是**诊断与可行性验证**用的独立观察器，输出更细的证据链
 * （含 {@code onCapabilitiesChanged} / {@code getTransportInfo} /
 * {@code LinkProperties}），两者共用 {@link PublicWifiVerdict} 的七态模型，
 * 但**互不替代**。
 *
 * <h3>回调顺序（真实观测到的）</h3>
 * <pre>
 *   requestNetwork()
 *        │
 *        ├─ [系统确认框] → 用户点允许/拒绝
 *        │
 *        ├─ onAvailable(Network)
 *        ├─ onCapabilitiesChanged(Network, NetworkCapabilities)   ← 可能多次
 *        ├─ onLinkPropertiesChanged(Network, LinkProperties)
 *        └─ onLost(Network)
 * <\/pre>
 * 或
 * <pre>
 *   onUnavailable()      ← 超时 / 没找到 / 用户拒绝，三者不可区分
 * <\/pre>
 */
public final class PublicWifiProbe {

    /** 默认总时限：够用户看清确认框并点一下。 */
    public static final long DEFAULT_TIMEOUT_MS = 45_000L;
    /** 连上后的稳定观察窗口（期间掉线不算成功）。 */
    public static final long DEFAULT_STABILITY_MS = 1_500L;

    private PublicWifiProbe() {
    }

    /**
     * 一次验证的完整记录（**不含口令明文**）。
     *
     * <p>这是交付物「系统授权行为和连接回调的真实证据」的载体：
     * 每个字段都是真实回调里记下来的，没有推断。
     */
    public static final class Record {
        /** 请求 ID（本进程内唯一，便于日志对照）。 */
        public String requestId = "";
        /** 目标 SSID（明文可记：不是敏感信息）。 */
        public String ssid = "";
        /** 目标 BSSID（可空）。 */
        public String bssid = "";
        /** 加密方式。 */
        public String security = "";
        /** 口令长度（**不记明文**）。 */
        public int passwordLength = 0;

        /** 请求是否被系统接受（requestNetwork 未抛异常）。 */
        public boolean requestAccepted;
        /** 提交时刻（elapsedRealtime）。 */
        public long requestAtMs;
        /** 结束时刻。 */
        public long endAtMs;

        /** 是否收到 onAvailable。 */
        public boolean sawAvailable;
        /** onAvailable 的时刻。 */
        public long availableAtMs;
        /** Network 的 handle（用于和请求前的快照比对）。 */
        public long networkHandle = -1L;
        /** 该 Network 是否在请求前就已存在。 */
        public boolean networkPreexisting;

        /** 是否收到 onCapabilitiesChanged。 */
        public boolean sawCapabilities;
        /** 是否拿到 transportInfo（WifiInfo）。 */
        public boolean hasTransportInfo;
        /** transportInfo 里的 SSID。 */
        public String transportSsid = "";
        /** transportInfo 里的 BSSID。 */
        public String transportBssid = "";
        /** SSID 是否等于目标。 */
        public boolean transportSsidMatches;

        /** 是否收到 onLinkPropertiesChanged。 */
        public boolean sawLinkProperties;
        /** 是否有有效地址。 */
        public boolean hasValidAddress;
        /** 地址摘要（如 {@code 192.168.1.23/24}）。 */
        public String addressBrief = "";

        /** 是否收到 onLost。 */
        public boolean sawLost;
        /** 是否在 available 之前 lost。 */
        public boolean sawLostBeforeAvailable;
        /** 是否在稳定窗口内掉线。 */
        public boolean droppedWithinStability;

        /** 是否收到 onUnavailable。 */
        public boolean sawUnavailable;
        /** 是否超时（我们自己算）。 */
        public boolean timedOut;
        /** 是否权限被拒。 */
        public boolean permissionDenied;
        /** 是否网络描述被拒。 */
        public boolean specifierRejected;
        /** 请求前是否已连着目标。 */
        public boolean preConnectedToTarget;

        /** 回调顺序（真实发生顺序，形如 {@code available>capabilities>linkprops}）。 */
        public String callbackOrder = "";
        /** 最终七态。 */
        public PublicWifiVerdict.State state = PublicWifiVerdict.State.UNKNOWN;
        /** 结束原因（机器可读）。 */
        public String reason = "";
        /** 说明（人话）。 */
        public String detail = "";
        /** 异常类名（无异常为空）。 */
        public String exceptionClass = "";

        /** 总耗时。 */
        public long elapsedMs() {
            return endAtMs > requestAtMs ? endAtMs - requestAtMs : 0L;
        }

        /** 是否释放了网络资源（close 里置位）。 */
        public boolean released;

        /**
         * 一行摘要（**不含口令**，可写日志）。
         *
         * <pre>
         *   req=r1 ssid="TEST" state=auth-confirmed 1234ms
         *       order=available>capabilities>linkprops
         *       ssid=T bssid=aa:bb:.. addr=192.168.1.23/24 pre=false
         * <\/pre>
         */
        public String brief(boolean zh) {
            StringBuilder sb = new StringBuilder();
            sb.append("req=").append(requestId.isEmpty() ? "<none>" : requestId);
            sb.append(" ssid=\"").append(ssid).append('"');
            sb.append(" state=").append(state.key);
            sb.append(" ").append(elapsedMs()).append("ms");
            if (!callbackOrder.isEmpty()) sb.append(" order=").append(callbackOrder);
            if (hasTransportInfo) {
                sb.append(" tSsid=").append(transportSsid.isEmpty() ? "?" : transportSsid);
                if (!transportBssid.isEmpty()) sb.append(" tBssid=").append(transportBssid);
            }
            if (hasValidAddress) sb.append(" addr=").append(addressBrief);
            sb.append(" pre=").append(preConnectedToTarget)
              .append(" preNet=").append(networkPreexisting);
            if (!reason.isEmpty()) sb.append(" reason=").append(reason);
            if (!exceptionClass.isEmpty()) sb.append(" ex=").append(exceptionClass);
            return sb.toString();
        }

        /** 转成 {@link PublicWifiVerdict.Facts}。 */
        public PublicWifiVerdict.Facts toFacts() {
            PublicWifiVerdict.Facts f = new PublicWifiVerdict.Facts();
            f.requestAccepted = requestAccepted;
            f.sawAvailable = sawAvailable;
            f.sawUnavailable = sawUnavailable;
            f.sawLostAfterAvailable = sawLost && sawAvailable;
            f.sawLostBeforeAvailable = sawLostBeforeAvailable;
            f.networkPreexisting = networkPreexisting;
            f.transportSsidMatches = transportSsidMatches;
            f.hasTransportInfo = hasTransportInfo;
            f.hasValidAddress = hasValidAddress;
            f.preConnectedToTarget = preConnectedToTarget;
            f.timedOut = timedOut;
            f.permissionDenied = permissionDenied;
            f.specifierRejected = specifierRejected;
            f.droppedWithinStability = droppedWithinStability;
            return f;
        }
    }

    /** 验证回调（可选）。 */
    public interface Listener {
        /** 每发生一个真实回调就调一次（用于把证据链写进日志）。 */
        void onEvent(String event);

        /** 结束时调一次。 */
        void onDone(Record r);
    }

    /**
     * **同步执行一次验证**（必须在非主线程调用）。
     *
     * <p>内部把请求投到主线程（ConnectivityManager 的回调要求），
     * 然后用 latch 等结果。整个过程**不重试、不循环**。
     *
     * @param ctx        上下文
     * @param ssid       目标 SSID
     * @param bssid      目标 BSSID（可空）
     * @param security   加密方式（wpa2 / wpa3 / open）
     * @param password   口令（只用于构造请求，不记录）
     * @param timeoutMs  总时限
     * @param stabilityMs 稳定窗口
     * @param listener   可选回调
     * @return 完整记录
     */
    public static Record probeOnce(Context ctx, String ssid, String bssid, String security,
                                   String password, long timeoutMs, long stabilityMs,
                                   Listener listener) {
        Record r = new Record();
        r.ssid = ssid == null ? "" : ssid;
        r.bssid = bssid == null ? "" : bssid;
        r.security = security == null ? "" : security;
        r.passwordLength = password == null ? 0 : password.length();
        r.requestId = "pw" + Long.toString(System.currentTimeMillis() % 100000L, 36)
                + "-" + Integer.toHexString(System.identityHashCode(r) & 0xffff);
        r.requestAtMs = SystemClock.elapsedRealtime();

        if (ctx == null) {
            return finish(r, PublicWifiVerdict.State.UNKNOWN, "no-context", "没有 Context");
        }
        // 口令格式先挡：不合格的候选不能变成「认证失败」
        String bad = SpecifierParams.rejectReason(security, password);
        if (!bad.isEmpty()) {
            return finish(r, PublicWifiVerdict.State.UNKNOWN, bad,
                    "候选参数不被系统接受（不是口令错误）");
        }

        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final Object lock = new Object();
        final ConnectivityManager[] cmRef = new ConnectivityManager[1];
        final ConnectivityManager.NetworkCallback[] cbRef =
                new ConnectivityManager.NetworkCallback[1];
        final java.util.Set<Long> preHandles = new java.util.HashSet<>();
        final StringBuilder order = new StringBuilder();
        final long[] availAt = new long[]{0L};
        final long[] lostAt = new long[]{0L};

        Runnable post = () -> {
            try {
                ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
                if (cm == null) {
                    synchronized (lock) {
                        r.reason = "no-cm";
                        r.detail = "系统没有 ConnectivityManager";
                    }
                    done.countDown();
                    return;
                }
                cmRef[0] = cm;

                // ① 请求前是否已连着目标（旧连接污染）
                String preSsid = currentWifiSsid(ctx);
                if (!r.ssid.isEmpty() && r.ssid.equals(preSsid)) {
                    r.preConnectedToTarget = true;
                }
                // ② 记下请求前就存在的 WiFi 网络
                try {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            preHandles.add(n.getNetworkHandle());
                        }
                    }
                } catch (Throwable ignored) {}

                if (r.preConnectedToTarget) {
                    // 已连着目标：系统可能直接用旧连接满足请求，本次口令无法被证明
                    synchronized (lock) {
                        r.reason = "pre-existing-connection";
                        r.detail = "请求前系统已连接目标，无法证明本次口令参与认证";
                    }
                    done.countDown();
                    return;
                }

                // ③ 构造网络描述
                WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder();
                b.setSsid(r.ssid);
                String sec = SpecifierParams.normSec(security);
                if (SpecifierParams.SEC_WPA3.equals(sec)) {
                    b.setWpa3Passphrase(password);
                } else if (SpecifierParams.SEC_OPEN.equals(sec)
                        || SpecifierParams.SEC_OWE.equals(sec)) {
                    // 开放网络：不设口令
                } else {
                    b.setWpa2Passphrase(password);
                }
                if (!r.bssid.isEmpty()) {
                    try {
                        b.setBssid(MacAddress.fromString(r.bssid));
                    } catch (Throwable t) {
                        synchronized (lock) {
                            r.reason = "invalid-bssid";
                            r.detail = "BSSID 不被系统接受：" + t.getClass().getSimpleName();
                            r.exceptionClass = t.getClass().getSimpleName();
                        }
                        done.countDown();
                        return;
                    }
                }

                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(b.build())
                        .build();

                // ④ 装回调（顺序：onAvailable → onCapabilitiesChanged → onLinkPropertiesChanged）
                ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network n) {
                        synchronized (lock) {
                            if (r.endAtMs > 0) return;          // 迟到回调：结论已定
                            r.sawAvailable = true;
                            availAt[0] = SystemClock.elapsedRealtime();
                            r.availableAtMs = availAt[0];
                            try {
                                r.networkHandle = n.getNetworkHandle();
                                r.networkPreexisting = preHandles.contains(r.networkHandle);
                            } catch (Throwable ignored) {}
                            append(order, "available");
                        }
                        if (listener != null) listener.onEvent("onAvailable");
                    }

                    @Override
                    public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                        synchronized (lock) {
                            if (r.endAtMs > 0) return;
                            r.sawCapabilities = true;
                            append(order, "capabilities");
                            // **关键证据**：从 NetworkCapabilities 取 WifiInfo
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && nc != null) {
                                    Object ti = nc.getTransportInfo();
                                    if (ti instanceof WifiInfo) {
                                        WifiInfo wi = (WifiInfo) ti;
                                        r.hasTransportInfo = true;
                                        String s = wi.getSSID();
                                        if (s != null) {
                                            s = stripQuotes(s);
                                            r.transportSsid = s;
                                            r.transportSsidMatches = r.ssid.equals(s);
                                        }
                                        String bs = wi.getBSSID();
                                        if (bs != null && !"02:00:00:00:00:00".equals(bs)) {
                                            r.transportBssid = bs;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (listener != null) listener.onEvent("onCapabilitiesChanged");
                    }

                    @Override
                    public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                        synchronized (lock) {
                            if (r.endAtMs > 0) return;
                            r.sawLinkProperties = true;
                            append(order, "linkprops");
                            // **关键证据**：有有效地址才说明 DHCP 真的完成了
                            try {
                                if (lp != null) {
                                    for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                                        java.net.InetAddress a = la.getAddress();
                                        if (a != null && !a.isAnyLocalAddress()
                                                && !a.isLoopbackAddress()) {
                                            r.hasValidAddress = true;
                                            r.addressBrief = a.getHostAddress()
                                                    + "/" + la.getPrefixLength();
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (listener != null) listener.onEvent("onLinkPropertiesChanged");
                    }

                    @Override
                    public void onLost(Network n) {
                        synchronized (lock) {
                            if (r.endAtMs > 0) return;
                            r.sawLost = true;
                            lostAt[0] = SystemClock.elapsedRealtime();
                            append(order, "lost");
                            if (!r.sawAvailable) {
                                r.sawLostBeforeAvailable = true;
                            } else if (availAt[0] > 0
                                    && lostAt[0] - availAt[0] < stabilityMs) {
                                r.droppedWithinStability = true;
                            }
                        }
                        if (listener != null) listener.onEvent("onLost");
                        done.countDown();
                    }

                    @Override
                    public void onUnavailable() {
                        synchronized (lock) {
                            if (r.endAtMs > 0) return;
                            r.sawUnavailable = true;
                            append(order, "unavailable");
                        }
                        if (listener != null) listener.onEvent("onUnavailable");
                        done.countDown();
                    }
                };
                cbRef[0] = cb;

                try {
                    cm.requestNetwork(req, cb, (int) Math.min(Integer.MAX_VALUE, timeoutMs));
                    synchronized (lock) {
                        r.requestAccepted = true;
                        append(order, "requested");
                    }
                    if (listener != null) listener.onEvent("requestNetwork accepted");
                } catch (SecurityException se) {
                    synchronized (lock) {
                        r.permissionDenied = true;
                        r.reason = "security";
                        r.detail = "缺少必要权限：" + se.getMessage();
                        r.exceptionClass = "SecurityException";
                    }
                    if (listener != null) listener.onEvent("SecurityException: " + se.getMessage());
                    done.countDown();
                } catch (IllegalArgumentException iae) {
                    synchronized (lock) {
                        r.specifierRejected = true;
                        r.reason = "bad-args";
                        r.detail = "系统拒绝了这个网络描述：" + iae.getMessage();
                        r.exceptionClass = "IllegalArgumentException";
                    }
                    if (listener != null) listener.onEvent("IllegalArgumentException: " + iae.getMessage());
                    done.countDown();
                }
            } catch (Throwable t) {
                synchronized (lock) {
                    r.reason = "error";
                    r.detail = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage());
                    r.exceptionClass = t.getClass().getSimpleName();
                }
                done.countDown();
            }
        };
        main.post(post);

        // 等待结果（超时由 requestNetwork 的 timeout 参数 + 这里的兜底共同保证）
        boolean got;
        try {
            got = done.await(Math.max(1000L, timeoutMs + 3000L),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            got = false;
        }

        synchronized (lock) {
            if (!got && r.endAtMs == 0) {
                r.timedOut = true;
                r.reason = "await-timeout";
            }
            // 连上后还没掉：给稳定窗口再观察一下（真实场景里 onLost 会在这期间到达）
            if (got && r.sawAvailable && !r.sawLost && stabilityMs > 0) {
                long remain = availAt[0] > 0
                        ? stabilityMs - (SystemClock.elapsedRealtime() - availAt[0]) : 0L;
                if (remain > 0) {
                    try { lock.wait(remain); } catch (InterruptedException ignored) {}
                }
            }
            r.callbackOrder = order.toString();
        }

        // 释放：一定要 unregister，否则回调会持续（任务书第 6 点）
        final ConnectivityManager cm2 = cmRef[0];
        final ConnectivityManager.NetworkCallback cb2 = cbRef[0];
        if (cm2 != null && cb2 != null) {
            try {
                cm2.unregisterNetworkCallback(cb2);
                r.released = true;
            } catch (Throwable ignored) {}
        }

        PublicWifiVerdict.State st = PublicWifiVerdict.of(r.toFacts());
        String reason = r.reason.isEmpty() ? st.key : r.reason;
        String detail = r.detail.isEmpty()
                ? PublicWifiVerdict.explain(st, true) : r.detail;
        finish(r, st, reason, detail);
        if (listener != null) listener.onDone(r);
        return r;
    }

    /** 便捷重载：默认时限与稳定窗口。 */
    public static Record probeOnce(Context ctx, String ssid, String bssid, String security,
                                   String password, Listener listener) {
        return probeOnce(ctx, ssid, bssid, security, password,
                DEFAULT_TIMEOUT_MS, DEFAULT_STABILITY_MS, listener);
    }

    private static Record finish(Record r, PublicWifiVerdict.State st,
                                 String reason, String detail) {
        r.state = st == null ? PublicWifiVerdict.State.UNKNOWN : st;
        r.reason = reason == null ? "" : reason;
        r.detail = detail == null ? "" : detail;
        r.endAtMs = SystemClock.elapsedRealtime();
        return r;
    }

    private static void append(StringBuilder sb, String ev) {
        if (sb.length() > 0) sb.append('>');
        sb.append(ev);
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 读当前连接的 SSID（公开 API；缺定位权限时返回空）。 */
    private static String currentWifiSsid(Context ctx) {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) ctx.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            return stripQuotes(info.getSSID());
        } catch (Throwable t) {
            return "";
        }
    }
}

package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.wlmosv_png.wpaforge.L;

/**
 * **无 Root 在线验证通道**：用 {@link WifiNetworkSpecifier} 让系统去试候选口令。
 *
 * <h3>它到底做了什么</h3>
 * {@code WifiNetworkSpecifier} 是 Android 10+ 唯一让普通应用**指定口令**发起连接的
 * 公开接口。系统收到请求后由 {@code wpa_supplicant} 用我们给的口令去关联、跑四步握手，
 * 结果通过 {@link ConnectivityManager.NetworkCallback} 回来。
 *
 * <h3>它能证明什么、不能证明什么（照实说）</h3>
 * <ul>
 *   <li><b>能证明</b>：在「本次请求拿到的是一个**新建**网络」且「请求前没有连着目标」的
 *       前提下，{@code onAvailable} 说明这条口令被 AP 接受了。</li>
 *   <li><b>不能证明</b>：AOSP 的 {@code WifiNetworkAgentSpecifier.satisfiesNetworkSpecifier()}
 *       比较 SSID / BSSID / 频段 / {@code allowedKeyManagement}，**不比较 PSK**
 *       （见 frameworks/base 与 modules/Wifi 的对应实现）。因此一个**已存在**的到手连接
 *       可以满足新的 specifier，而本次候选根本没参与认证。本类用两道闸挡它：
 *       ① 请求前若已连着目标（同 BSSID，或 BSSID 未知时同 SSID）直接拒绝判定；
 *       ② {@code onAvailable} 返回的 Network 若在请求前就存在，一律判为「无法证明」。</li>
 *   <li><b>不能证明</b>：{@code onUnavailable} 到底是「口令被拒」还是「超时 / AP 不在 /
 *       用户没点确认」。{@link ConnectivityManager.NetworkCallback#onUnavailable()}
 *       的官方说明是「在超时内没找到网络，或所请求的网络请求无法被满足」——
 *       两者在公开 API 上**分不开**。所以本通道永不主张「口令被拒」。</li>
 * </ul>
 *
 * <h3>关于系统确认框与授权复用（必须如实说明）</h3>
 * 首次对某个 AP 发起请求时，系统会弹「连接到该网络？」让用户确认。AOSP
 * {@code WifiNetworkFactory} 会把批准结果按
 * {@code AccessPoint(SSID, BSSID, securityType)} 记进按包名的列表
 * （非开放网络只要 SSID + 安全类型匹配即算命中），上限 50 条并落盘持久化。
 * 也就是说：**首次批准之后，对同一 AP 的后续候选有可能不再弹框**。
 * 但这是 AOSP 的行为，厂商 ROM（含 ColorOS）是否保留、是否加严，**必须真机确认**。
 * 本类不承诺「无交互连续验证」——它只承诺每次尝试都是独立提交、独立收尾。
 *
 * <h3>为什么单独一个类</h3>
 * 它和 Root 的 {@code cmd wifi} 是两套物理机制（一个走 shell uid，一个走应用 uid 到
 * ConnectivityService 的 binder），判定信号也不同。硬塞进同一个 {@code ShellChannel}
 * 只会让两边都变形。
 */
public final class SpecifierChannel implements ConnectChannel {

    /** 单次候选的默认截止时间（单调时钟，从请求发出算起）。 */
    public static final long DEFAULT_TIMEOUT_MS = 40_000L;
    /**
     * 连接稳定性观察窗：{@code onAvailable} 之后再观察这么久，
     * 期间若收到 {@code onLost} 则**不判成功**（避免「连上就掉」被记成命中）。
     */
    public static final long STABILITY_MS = 1_200L;
    /** 等上一条请求彻底收尾的最长时间。超时说明资源没释放干净，直接判本条未执行。 */
    public static final long SETTLE_MS = 6_000L;
    /** 看门狗/稳定性线程的轮询粒度（决定取消响应的最大延迟）。 */
    private static final long TICK_MS = 50L;

    private final Context ctx;
    private final android.os.Handler handler;
    private final AttemptSlot slot = new AttemptSlot();
    private volatile boolean opened;
    private volatile long timeoutMs = DEFAULT_TIMEOUT_MS;
    private volatile long stabilityMs = STABILITY_MS;

    private volatile boolean probeDone;
    private volatile String probeReasonZh = "";
    private volatile String probeReasonEn = "";

    /** 最近一次提交的凭据（不可变句柄），仅为兼容旧调用方保留。 */
    private volatile Token lastToken;

    public SpecifierChannel(Context ctx) {
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
        this.handler = null;
    }

    /** 显式指定 Looper 所在的 Handler（从后台线程发起时用）。 */
    public SpecifierChannel(Context ctx, android.os.Handler h) {
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
        this.handler = h;
    }

    public void setTimeoutMs(long ms) {
        if (ms > 0) timeoutMs = ms;
    }

    public void setStabilityMs(long ms) {
        if (ms >= 0) stabilityMs = ms;
    }

    // ------------------------------------------------------------ 提交句柄

    /**
     * 一条已提交（或未提交）候选的**不可变句柄**。
     *
     * <p>所有结果都挂在句柄上，不再有「通道级 lastResult」这种东西 ——
     * 这是 D9 的修复本体：读结果必须带上这一条的身份。
     */
    public static final class Token {
        public final String attemptId;
        public final String ssid;
        public final String security;
        /** 口令的不可逆短标签（只用于把日志/结果对回候选，不暴露明文）。 */
        public final String passwordTag;
        /** 非空 = 没有提交成功，原因码。 */
        public final String rejectCode;
        public final String rejectDetail;

        final Attempt attempt;

        Token(String attemptId, String ssid, String security, String passwordTag,
              String rejectCode, String rejectDetail, Attempt attempt) {
            this.attemptId = attemptId == null ? "" : attemptId;
            this.ssid = ssid == null ? "" : ssid;
            this.security = security == null ? "" : security;
            this.passwordTag = passwordTag == null ? "" : passwordTag;
            this.rejectCode = rejectCode == null ? "" : rejectCode;
            this.rejectDetail = rejectDetail == null ? "" : rejectDetail;
            this.attempt = attempt;
        }

        public boolean submitted() {
            return attempt != null && rejectCode.isEmpty();
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public boolean open() {
        slot.reopen();
        opened = true;
        return true;
    }

    @Override
    public boolean isOpen() {
        return opened;
    }

    /**
     * 关闭通道：**取消在途请求**、注销回调、停止计时线程、唤醒等待者。
     *
     * <p>改造前这里只置 {@code opened=false}，于是「停止」之后请求仍在系统里跑，
     * 下一次任务开始时可能撞上一个还没结束的旧请求（D3）。
     * 本方法可重复调用。
     */
    @Override
    public void close() {
        opened = false;
        Attempt a = activeAttempt();
        if (a != null) a.abort("channel-closed");
        slot.close();
    }

    private Attempt activeAttempt() {
        String id = slot.activeId();
        if (id.isEmpty()) return null;
        Token t = lastToken;
        if (t != null && t.attempt != null && id.equals(t.attemptId)) return t.attempt;
        return null;
    }

    @Override
    public String name() {
        return "Specifier";
    }

    // ------------------------------------------------------------ 能力自述

    @Override
    public boolean canAssociate() {
        return probeSupport() == null;
    }

    /**
     * 净检：公开 API **无法**删除系统里已保存的配置。
     *
     * <p>Root 链的安全前提是「先删净旧配置，否则系统可能拿旧口令连上」。
     * 无 Root 下做不到，所以本通道不声称能净检，而是用两道替代闸：
     * 请求前确认没连着目标 + 请求后确认拿到的是新网络（见类注释）。
     */
    @Override
    public boolean canForget() {
        return false;
    }

    /** 公开 API 无法删除已保存配置 —— **恒 false，且不发任何请求**。 */
    @Override
    public boolean forgetTarget(String ssid) {
        return false;
    }

    // ------------------------------------------------------------ 提交

    /** 兼容旧调用方：不带 attemptId 的提交。 */
    @Override
    public boolean associate(String ssid, String security, String password,
                             String bssid, boolean macRotate) {
        Token t = submit(ssid, security, password, bssid, "");
        return t.submitted();
    }

    /**
     * 提交一条候选。
     *
     * <p>串行语义是**显式**的：如果上一条请求还没彻底释放，这里会等待（最多
     * {@link #SETTLE_MS}），等不到就返回 {@code busy} 而不提交 ——
     * 绝不允许两个候选同时在系统里跑（D4）。
     */
    public Token submit(String ssid, String security, String password,
                        String bssid, String attemptId) {
        String tag = passwordTag(password);
        String aId = attemptId == null ? "" : attemptId.trim();

        if (!canAssociate()) {
            String why = probeSupport();
            return new Token(aId, ssid, security, tag, "not-supported",
                    why == null ? "" : why, null);
        }
        if (ssid == null || ssid.isEmpty()) {
            return new Token(aId, ssid, security, tag, "no-ssid", "SSID 为空", null);
        }
        String reject = SpecifierParams.rejectReason(security, password);
        if (!reject.isEmpty()) {
            return new Token(aId, ssid, security, tag, reject,
                    "候选不符合系统要求，未提交（未尝试，不是密码错误）", null);
        }
        // D1：BSSID 必须真的生效；不合法/广播地址要明确拒绝，绝不静默忽略。
        final String bssidNorm = normalizeBssid(bssid);
        if ("".equals(bssidNorm)) {
            // bssid 传了但不可用（非法 / 广播 / 全零）→ 明确拒绝，不猜
            if (bssid != null && !bssid.trim().isEmpty()) {
                return new Token(aId, ssid, security, tag, "invalid-bssid",
                        "传入的 BSSID 无法用于约束目标（非法格式 / 广播地址）", null);
            }
        }

        Attempt a = new Attempt(aId, ssid, SpecifierParams.normSec(security), password, bssidNorm);
        String why = acquire(a);
        if (!why.isEmpty()) {
            return new Token(aId, ssid, security, tag, why,
                    AttemptSlot.R_CLOSED.equals(why) ? "通道已关闭"
                            : (AttemptSlot.R_BUSY.equals(why) ? "上一条请求尚未结束" : "尝试标识非法"),
                    null);
        }
        Token t = new Token(aId, ssid, security, tag, "", "", a);
        lastToken = t;
        a.start();
        return t;
    }

    /** 占用槽位（带等待，保证串行）。 */
    private String acquire(Attempt a) {
        long until = SystemClock.elapsedRealtime() + SETTLE_MS;
        while (true) {
            String why = slot.acquire(a.attemptId, a.deadlineElapsed);
            if (why.isEmpty() || !AttemptSlot.R_BUSY.equals(why)) return why;
            if (SystemClock.elapsedRealtime() >= until) return AttemptSlot.R_BUSY;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 等待

    /** 兼容旧调用方：等最近一次提交。 */
    public Outcome await(long maxWaitMs) {
        Token t = lastToken;
        if (t == null) {
            Outcome o = new Outcome();
            o.code = "no-attempt";
            o.detail = "尚未提交任何请求";
            return o;
        }
        return await(t, maxWaitMs);
    }

    /**
     * 等某一条候选出结论。
     *
     * <p><b>超时会主动取消请求</b>（不是单纯放弃等待）——
     * 否则旧请求会在下一条候选跑起来之后才回调，制造串扰（D4）。
     */
    public Outcome await(Token t, long maxWaitMs) {
        if (t == null || !t.submitted()) {
            Outcome o = new Outcome();
            o.preBlocked = true;
            o.code = t == null ? "no-attempt" : t.rejectCode;
            o.detail = t == null ? "尚未提交任何请求" : t.rejectDetail;
            o.attemptId = t == null ? "" : t.attemptId;
            return o;
        }
        long wait = maxWaitMs > 0 ? maxWaitMs : timeoutMs + 5_000L;
        return t.attempt.await(wait);
    }

    // ------------------------------------------------------------ 结果

    /** 一次尝试的结论（不可变值对象）。 */
    public static final class Outcome {
        /** 系统认为连上了，且本次请求拿到的是**新**网络。 */
        public boolean connected;
        /** 系统未能建立连接（认证失败 / 超时 / AP 不在，公开 API 分不开）。 */
        public boolean unavailable;
        /**
         * 用户拒绝确认。
         *
         * <p><b>公开回调不会设置它</b>：AOSP 里用户取消走的是
         * {@code handleRejectUserSelection} → 连接失败 → 应用侧收到的仍是
         * {@code onUnavailable()}。保留该字段只为表达「无法区分」这一事实，
         * 生产路径恒 false。
         */
        public boolean userRejected;
        /** 提交前/后被判定为无法证明（已有连接、平台拒绝、非法参数）。 */
        public boolean preBlocked;
        /** 本地截止时刻已到，系统一直没给答复。 */
        public boolean timedOut;
        /** 连接一度建立但立刻丢失 —— **不得**记成成功。 */
        public boolean unstable;
        /** 机器可读原因码。 */
        public String code = "";
        public String detail = "";
        /** 本条的身份（D9：结果必须能对回尝试）。 */
        public String attemptId = "";
        public String passwordTag = "";
        /** 单调时钟耗时（毫秒）。 */
        public long elapsedMs;

        public boolean conclusive() {
            return connected || unavailable || timedOut || preBlocked || unstable;
        }

        public boolean isSuccess() {
            return connected && !unstable;
        }
    }

    // ------------------------------------------------------------ 单次尝试

    private final class Attempt {
        final String attemptId;
        final String ssid;
        final String security;
        final String password;
        /** 规范化后的目标 BSSID；空串 = 未约束。 */
        final String bssid;
        final long deadlineElapsed;

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Outcome> result = new AtomicReference<>();
        final AtomicBoolean settled = new AtomicBoolean(false);

        /** 本尝试独占的资源（D2：不再放通道级字段）。 */
        ConnectivityManager cm;
        ConnectivityManager.NetworkCallback cb;
        Network network;
        /** 请求发出前就存在的 WiFi 网络句柄（用于识别「被已有连接满足」）。 */
        final Set<Long> preexistingHandles = new HashSet<>();

        Attempt(String attemptId, String ssid, String security, String password, String bssid) {
            this.attemptId = attemptId;
            this.ssid = ssid;
            this.security = security;
            this.password = password;
            this.bssid = bssid == null ? "" : bssid;
            this.deadlineElapsed = SystemClock.elapsedRealtime() + timeoutMs;
        }

        void start() {
            if (handler != null) handler.post(this::postRequest);
            else postRequest();

            Thread watchdog = new Thread(() -> {
                while (SystemClock.elapsedRealtime() < deadlineElapsed) {
                    if (settled.get()) return;
                    sleepQuietly(TICK_MS);
                }
                finish(timedOut("deadline"));
            }, "wpaforge-spec-deadline");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        /** 主线程/回调线程执行：建请求、装回调、发出去。 */
        void postRequest() {
            if (settled.get()) return;
            try {
                if (ctx == null) {
                    finish(preBlocked("no-context", "没有 Context"));
                    return;
                }
                cm = ctx.getSystemService(ConnectivityManager.class);
                if (cm == null) {
                    finish(preBlocked("no-cm", "系统没有 ConnectivityManager"));
                    return;
                }

                // D8 第一道闸：请求前如果已经连着目标，本次候选无法被证明参与认证。
                String pre = connectedTargetAtStart();
                if (!pre.isEmpty()) {
                    finish(preBlocked("pre-existing-connection",
                            "请求前系统已连接目标（" + pre + "），无法证明本次候选参与认证"));
                    return;
                }
                // 记下请求前就存在的 WiFi 网络，供 onAvailable 时比对
                preexistingHandles.addAll(snapshotWifiHandles(cm));

                WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder();
                b.setSsid(unquote(ssid));
                if (SpecifierParams.isWpa3(security)) b.setWpa3Passphrase(password);
                else b.setWpa2Passphrase(password);
                // D1：真正把目标 AP 固定下来
                if (!bssid.isEmpty()) {
                    try {
                        b.setBssid(MacAddress.fromString(bssid));
                    } catch (Throwable t) {
                        finish(preBlocked("invalid-bssid",
                                "BSSID 无法被系统接受：" + t.getClass().getSimpleName()));
                        return;
                    }
                }

                final NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(b.build())
                        .build();

                cb = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network n) {
                        onNetworkAvailable(n);
                    }

                    @Override
                    public void onUnavailable() {
                        // 官方语义：超时内没找到网络，或请求无法被满足 —— 两者分不开。
                        finish(unavailable("unavailable"));
                    }

                    @Override
                    public void onLost(Network n) {
                        onNetworkLost(n);
                    }
                };

                try {
                    cm.requestNetwork(req, cb, (int) Math.min(Integer.MAX_VALUE, timeoutMs));
                } catch (SecurityException se) {
                    finish(preBlocked("security", "缺少必要权限：" + se.getMessage()));
                } catch (IllegalArgumentException iae) {
                    finish(preBlocked("bad-args", "系统拒绝了这个网络描述：" + iae.getMessage()));
                }
            } catch (Throwable t) {
                finish(preBlocked("error", t.getClass().getSimpleName() + ": " + t.getMessage()));
            }
        }

        /** onAvailable 的完整处理（D7/D8）。 */
        void onNetworkAvailable(Network n) {
            if (settled.get()) return;      // 迟到回调：结论已定，丢弃
            long handle;
            try {
                handle = n.getNetworkHandle();
            } catch (Throwable t) {
                handle = -1L;
            }
            // D8 第二道闸：返回的 Network 若在请求前就存在，说明是已有连接满足了请求，
            // 本次候选并没有参与认证 —— 绝不能判成功。
            if (handle >= 0 && preexistingHandles.contains(handle)) {
                finish(preBlocked("pre-existing-network",
                        "系统用请求前就已存在的网络满足了本次请求，无法证明候选参与认证"));
                return;
            }
            network = n;
            // D7：先观察一段时间，期间掉线就不算成功。
            if (stabilityMs <= 0) {
                finish(connectedOutcome());
                return;
            }
            Thread stability = new Thread(() -> {
                long until = SystemClock.elapsedRealtime() + stabilityMs;
                while (SystemClock.elapsedRealtime() < until) {
                    if (settled.get()) return;
                    sleepQuietly(TICK_MS);
                }
                finish(connectedOutcome());
            }, "wpaforge-spec-stability");
            stability.setDaemon(true);
            stability.start();
        }

        /** onLost 的完整处理（D7）：建立前丢失 = 没连上；建立后立刻丢失 = 不稳定。 */
        void onNetworkLost(Network n) {
            if (settled.get()) return;      // 已收尾后的迟到 onLost 一律忽略
            if (network == null) {
                // 还没 onAvailable 就 lost：等价于没连上（绝不等于密码错误）
                finish(unavailable("lost-before-available"));
            } else {
                finish(unstableOutcome());
            }
        }

        Outcome connectedOutcome() {
            Outcome o = new Outcome();
            o.connected = true;
            o.code = "available";
            o.detail = "系统用这条口令连上目标，且是本次请求新建的连接";
            return o;
        }

        Outcome unstableOutcome() {
            Outcome o = new Outcome();
            o.unstable = true;
            o.code = "dropped-immediately";
            o.detail = "连接建立后立刻丢失，不足以证明口令可用";
            return o;
        }

        Outcome timeoutedOutcome() {
            return timedOut("deadline");
        }

        Outcome timedOut(String why) {
            Outcome o = new Outcome();
            o.timedOut = true;
            o.code = "timeout:" + why;
            o.detail = "系统在规定时间内没有给出可用答复";
            return o;
        }

        Outcome unavailable(String why) {
            Outcome o = new Outcome();
            o.unavailable = true;
            o.code = why;
            o.detail = "系统未能建立连接（认证失败 / 超时 / AP 不在，公开 API 分不开）";
            return o;
        }

        Outcome preBlocked(String code, String detail) {
            Outcome o = new Outcome();
            o.preBlocked = true;
            o.code = code;
            o.detail = detail;
            return o;
        }

        /** 外部取消（close / 任务停止 / 等待超时）。 */
        void abort(String why) {
            Outcome o = new Outcome();
            o.timedOut = true;
            o.code = "aborted:" + why;
            o.detail = "本次请求被取消，未得到结论";
            finish(o);
        }

        Outcome await(long ms) {
            try {
                if (!done.await(ms, TimeUnit.MILLISECONDS)) {
                    // 等待超时 → **主动取消**，绝不让旧请求活到下一条候选之后（D4）
                    abort("await-timeout");
                }
            } catch (InterruptedException ignored) {
            }
            Outcome o = result.get();
            if (o != null) return o;
            Outcome fallback = new Outcome();
            fallback.timedOut = true;
            fallback.code = "no-result";
            fallback.detail = "内部状态异常：没有结果";
            fallback.attemptId = attemptId;
            return fallback;
        }

        /**
         * 落地结论（**只认第一次**），并释放本尝试独占的全部资源。
         *
         * <p>重复调用、迟到回调、取消后回调全部在 {@code settled} 上被挡掉（D6/D9）。
         */
        void finish(Outcome o) {
            if (!settled.compareAndSet(false, true)) return;
            o.elapsedMs = Math.max(0L, SystemClock.elapsedRealtime()
                    - (deadlineElapsed - timeoutMs));
            o.attemptId = attemptId;
            o.passwordTag = passwordTag(password);
            result.set(o);
            releaseNetwork();
            slot.release(attemptId);
            done.countDown();
        }

        /** 注销回调并释放 Network。幂等。 */
        void releaseNetwork() {
            ConnectivityManager c = cm;
            ConnectivityManager.NetworkCallback b = cb;
            cm = null;
            cb = null;
            network = null;
            if (c != null && b != null) {
                try {
                    c.unregisterNetworkCallback(b);
                } catch (Throwable ignored) {
                }
            }
        }

        // ---- 目标判定辅助 ----

        /** 请求前是否已连着目标（同 BSSID；BSSID 未知时退化为同 SSID）。返回描述或空串。 */
        String connectedTargetAtStart() {
            try {
                if (ctx == null) return "";
                WifiManager wm = ctx.getSystemService(WifiManager.class);
                if (wm == null) return "";
                WifiInfo info = wm.getConnectionInfo();
                if (info == null) return "";
                String curSsid = unquote(info.getSSID());
                if (curSsid == null || !ssid.equals(curSsid)) return "";
                if (bssid.isEmpty()) return "SSID=" + curSsid;
                String curBssid = info.getBSSID();
                if (curBssid != null && bssid.equalsIgnoreCase(curBssid)) {
                    return "SSID=" + curSsid + "/BSSID=" + curBssid;
                }
                return "";
            } catch (Throwable t) {
                // 读不到就按「已连着」处理 —— 宁可作废也不伪造
                return "无法读取当前连接";
            }
        }

        Set<Long> snapshotWifiHandles(ConnectivityManager c) {
            Set<Long> out = new HashSet<>();
            try {
                Network[] all = c.getAllNetworks();
                if (all == null) return out;
                for (Network n : all) {
                    NetworkCapabilities cap = c.getNetworkCapabilities(n);
                    if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        out.add(n.getNetworkHandle());
                    }
                }
            } catch (Throwable ignored) {
            }
            return out;
        }
    }

    // ------------------------------------------------------------ 工具

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static String unquote(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * 规范化 BSSID。
     *
     * @return 合法 MAC（{@code aa:bb:cc:dd:ee:ff} 原样小写）；无法使用返回空串
     */
    public static String normalizeBssid(String bssid) {
        if (bssid == null) return "";
        String t = bssid.trim();
        if (t.isEmpty()) return "";
        if (!t.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")) return "";
        String low = t.toLowerCase(Locale.ROOT);
        // 广播 / 全零 都不是可约束的目标
        if ("ff:ff:ff:ff:ff:ff".equals(low) || "00:00:00:00:00:00".equals(low)) return "";
        // 这里只用正则判定形状，**不调用 MacAddress.fromString** ——
        // 那是 Android 运行时的类，桌面单测里是桩，会把合法值也判掉。
        // 真正交给 Builder 时仍会 try/catch（见 postRequest），双保险。
        return low;
    }

    /** 口令的不可逆短标签（不落明文）。 */
    static String passwordTag(String password) {
        if (password == null || password.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < d.length; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------ 支持度

    /**
     * 本机能不能用这条路。返回 {@code null} 表示能用，否则返回中文原因。
     *
     * <p>只做静态检查（版本 + 服务存在）；真正能不能跑以 {@link #submit} 的返回值为准。
     */
    public String probeSupport() {
        if (probeDone) return probeReasonZh.isEmpty() ? null : probeReasonZh;
        probeDone = true;
        if (ctx == null) {
            setProbe("没有应用上下文", "no app context");
            return probeReasonZh;
        }
        if (Build.VERSION.SDK_INT < 29) {
            setProbe("系统版本低于 Android 10（没有 WifiNetworkSpecifier）",
                    "Android below 10: WifiNetworkSpecifier does not exist");
            return probeReasonZh;
        }
        if (ctx.getSystemService(ConnectivityManager.class) == null) {
            setProbe("系统没有提供 ConnectivityManager", "no ConnectivityManager");
            return probeReasonZh;
        }
        probeReasonZh = "";
        probeReasonEn = "";
        return null;
    }

    private void setProbe(String zh, String en) {
        probeReasonZh = zh;
        probeReasonEn = en;
    }

    public String probeReasonEn() {
        return probeReasonEn;
    }

    // ------------------------------------------------------------ 可选能力

    @Override
    public String readState() {
        return null;
    }

    @Override
    public String listSavedNetworks() {
        return null;
    }

    @Override
    public String readSupplicantLog() {
        return null;
    }

    @Override
    public boolean canPrivilegedScan() {
        return false;
    }

    @Override
    public Capability.Set0 state() {
        List<Capability> list = new ArrayList<>();
        String why = probeSupport();

        list.add(Capability.unsupported(Capability.C_SHELL,
                "无 Root 模式不执行任何 shell 命令", "rootless never runs shell commands"));
        list.add(Capability.available(Capability.C_WIFI_READ,
                "公开 API 可读当前连接（WifiInfo / ConnectivityManager）",
                "current connection readable via public APIs"));

        if (why == null) {
            list.add(Capability.degraded(Capability.C_WIFI_CONNECT,
                    "可用系统确认框逐次在线验证：首次对某 AP 需你点一次确认，"
                            + "之后对同一 AP 系统可能不再弹框（AOSP 行为，厂商是否保留待真机确认）；"
                            + "失败只代表「系统未能建立连接」，认证失败与超时分不开，"
                            + "因此永远不会被记成已证实的错误密码",
                    "per-candidate online verification via the system approval flow: the first "
                            + "request for an AP needs one confirmation, later ones may skip it "
                            + "(AOSP behaviour; vendor builds unverified). A failure only means "
                            + "'the system could not establish the connection' - auth failure and "
                            + "timeout are indistinguishable, so it is never recorded as proven-wrong",
                    "把它当「逐条验证」：一次一个候选、独立收尾；"
                            + "要无人值守跑整本字典仍需 root。",
                    "Treat it as per-candidate verification, one attempt at a time; unattended "
                            + "dictionary runs still need root."));
        } else {
            list.add(Capability.unsupported(Capability.C_WIFI_CONNECT, why, probeReasonEn,
                    "改用离线分析（握手/PMKID 文件）或换到已 root 的设备。",
                    "Use offline analysis or a rooted device."));
        }

        list.add(Capability.unsupported(Capability.C_WIFI_FORGET,
                "公开 API 不能删除系统已保存的网络；本通道改用「请求前未连着目标 + 只认新建连接」两道闸代替净检",
                "public APIs cannot remove saved networks; this channel instead requires "
                        + "'not connected before' and 'only a newly created network counts'",
                "先在系统设置里取消保存该网络，再验证。",
                "Remove the saved network in system settings first."));
        list.add(Capability.unsupported(Capability.C_WPA_LOG,
                "本应用 UID 读不到 wpa_supplicant 日志，失败无法升到 DIRECT 级",
                "supplicant log is not readable, so failures cannot reach DIRECT"));
        list.add(Capability.available(Capability.C_SCAN,
                "startScan / getScanResults 可用（需定位或附近设备权限）",
                "startScan / getScanResults available"));
        list.add(Capability.unsupported(Capability.C_SCAN_PRIV,
                "特权扫描依赖 cmd wifi", "privileged scan needs cmd wifi"));
        list.add(Capability.unsupported(Capability.C_WPS_PIN,
                "WPS PIN 需要向 supplicant 控制口写命令，必须 root",
                "WPS PIN requires the supplicant socket and root"));
        list.add(Capability.degraded(Capability.C_WPS_CAP,
                "只能读 ScanResult 里的 WPS 信息元素", "only the WPS IE from ScanResult"));
        list.add(Capability.unsupported(Capability.C_SAVED_CFG,
                "WifiConfigStore.xml 位于 /data/misc，非 root 不可读",
                "/data/misc WifiConfigStore.xml is unreadable without root"));
        list.add(Capability.unsupported(Capability.C_KEEPALIVE,
                "appops / 电池白名单写入需要 su", "appops / allowlist writes need su"));
        list.add(Capability.degraded(Capability.C_LAN_PROBE,
                "ping / ARP 受 SELinux 限制，只有 TCP 探测可靠",
                "ping and /proc/net/arp are SELinux-restricted; only TCP probing is reliable"));

        return new Capability.Set0("rootless", System.currentTimeMillis(), list);
    }

    /** 供界面展示的「这条路怎么用」一句话（中文）。 */
    public static String usageHintZh() {
        return "无 Root 在线验证：先把目标网络从系统里「取消保存」，再逐个候选验证。"
                + "每个候选都是一次独立请求；系统确认后由它真正去关联。"
                + "只有「本次请求新建的连接」才算这条口令可用；没连上时系统不告诉你是密码错还是超时，"
                + "所以这类结果一律保留为未知，不会记成已证实的错误密码。";
    }

    public static String usageHintEn() {
        return "Rootless online verification: remove the target from saved networks first, then "
                + "verify candidates one at a time. Each candidate is an independent request; "
                + "the system associates for real after you confirm. Only a connection newly "
                + "created by this request counts as a working passphrase. When it fails the "
                + "system does not say whether the passphrase was wrong or it timed out, so such "
                + "results stay unknown and are never recorded as proven-wrong.";
    }

    /** 双语文案帮助（供 UI 层取用，避免各处各写一份）。 */
    public static String label(boolean zh, String key) {
        if ("title".equals(key)) return zh ? "在线验证（无 Root）" : "Online verify (rootless)";
        if ("usage".equals(key)) return zh ? usageHintZh() : usageHintEn();
        return zh ? L.s("在线验证", "Online verify") : "Online verify";
    }
}

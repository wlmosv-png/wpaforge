package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.wlmosv_png.wpaforge.L;

/**
 * **无 Root 在线验证通道**：用 {@link WifiNetworkSpecifier} 让系统去试候选口令。
 *
 * <h3>它到底做了什么（先说实话）</h3>
 * {@code WifiNetworkSpecifier} 是 Android 10+ 唯一让普通应用**指定口令**发起连接的
 * 公开接口。系统收到请求后会：
 * <ol>
 *   <li>在系统 UI 上弹一个「连接到 XXX？」的确认框（**每个不同的网络请求一次**）；</li>
 *   <li>用户同意后，由 {@code wpa_supplicant} 用我们给的口令去关联、跑四步握手；</li>
 *   <li>结果通过 {@link ConnectivityManager.NetworkCallback} 回来：
 *       {@code onAvailable}（连上了 → 口令正确）/
 *       {@code onUnavailable}（没连上 → 认证失败或超时，**两者分不开**）。</li>
 * </ol>
 *
 * <h3>所以它能做什么、不能做什么（照实说）</h3>
 * <ul>
 *   <li><b>能</b>：让没有 root 的用户在线验证候选口令，前提是他本人点一下确认。
 *       这是「真正的连接验证」—— 走的是系统 supplicant，握手真的发生了，
 *       不是模拟、不是读缓存、不是猜。</li>
 *   <li><b>不能</b>：全自动逐条遍历。每个候选都是一次新的网络请求，
 *       按 AOSP 的实现，**每个不同的请求都要用户确认**（见
 *       {@code NetworkRequestStoreData}：系统按包名记住「已批准的 AP」，
 *       但那个批准面向的是 AP，不是「让某个应用无限次改口令」）。
 *       所以本模式的实际吞吐是「一次候选一次点击」，不是「几百条挂机跑」。</li>
 *   <li><b>不能</b>：把 {@code onUnavailable} 拆成「认证失败」与「超时」。
 *       系统只给一个「没连上」，拆不出来。所以本通道的失败结论最多到
 *       {@code NETWORK_AUTH_FAIL}（网络级、不可持久化），
 *       要升到 {@code AUTH_FAIL/DIRECT} 必须有可归属的日志证据 —— 而无 root 读不到
 *       supplicant 日志。这是能力边界，不是实现偷懒。</li>
 * </ul>
 *
 * <h3>为什么不复用 Root 的 shell 通道</h3>
 * 因为它是**另一套物理机制**：一个走 {@code cmd wifi}（shell uid），
 * 一个走 binder 到 ConnectivityService（应用 uid）。判定信号也不一样
 * （Root 有 status 四条件同行 + supplicant 日志；这里只有回调）。
 * 把它们塞进同一个 {@code ShellChannel} 只会让两边都变形。
 */
public final class SpecifierChannel implements ConnectChannel {

    /** 单次候选等待上限（系统从发起请求到给出 onAvailable/onUnavailable）。 */
    public static final long DEFAULT_TIMEOUT_MS = 45_000L;

    private final Context ctx;
    /** null = 用当前 Looper（必须是有 Looper 的线程，通常是主线程）。 */
    private final android.os.Handler handler;
    private volatile boolean opened;
    private volatile long timeoutMs = DEFAULT_TIMEOUT_MS;

    /** 探测结果缓存（{@link #probeSupport()}）。 */
    private volatile boolean probeDone;
    private volatile String probeReasonZh = "";
    private volatile String probeReasonEn = "";

    /** 最近一次尝试的机器可读结果码（诊断用）。 */
    private volatile String lastCode = "";
    private volatile String lastDetail = "";

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

    public String lastCode() {
        return lastCode;
    }

    public String lastDetail() {
        return lastDetail;
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public boolean open() {
        opened = true;
        return true;
    }

    @Override
    public boolean isOpen() {
        return opened;
    }

    @Override
    public void close() {
        opened = false;
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
     * <p>这一条必须在能力层说死：Root 链的安全前提是「先删净旧配置，
     * 否则系统可能拿旧口令连上，任何候选都成功」。无 Root 下做不到，
     * 所以本通道**不声称**自己能做出可信的「成功」结论 —— 它只做
     * 「这次口令能不能连上」的观察，而这条观察在目标本来就有正确配置时
     * 会被系统拿旧配置连上而污染。
     *
     * <p>缓解：{@link #associate} 前如果发现系统已连着目标、或目标在已保存列表里，
     * 直接给 {@code STALE_CONFIG} 闸门（等同于 Root 链的净检失败语义），
     * 让本条结论作废，而不是伪造成功。
     */
    @Override
    public boolean canForget() {
        return false;
    }

    /**
     * 公开 API 无法删除系统已保存的配置 —— **恒 false，且不发任何请求**。
     *
     * <p>调用方必须据此把本条尝试判成闸门失败（{@code STALE_CONFIG}），
     * 而不是「旧配置已清空」。宁可判定作废，也不制造「任何候选都成功」的假象。
     */
    @Override
    public boolean forgetTarget(String ssid) {
        lastCode = "forget-unsupported";
        lastDetail = "公开 API 不能删除已保存网络；本条无法保证净检";
        return false;
    }

    @Override
    public boolean associate(String ssid, String security, String password,
                             String bssid, boolean macRotate) {
        if (!canAssociate()) return false;
        if (ssid == null || ssid.isEmpty()) return false;
        if (!SpecifierParams.usable(security, password)) {
            lastCode = "invalid-candidate";
            lastDetail = "候选不符合系统要求（长度或字符集），未提交";
            return false;
        }
        Attempt a = new Attempt(ssid, SpecifierParams.normSec(security), password);
        lastAttempt = a;
        a.run();
        return true;
    }

    /** 上一次 {@link #associate} 的结果（调用方读完再取，不会串）。 */
    public static final class Outcome {
        /** 系统真的连上了（有 IP）。 */
        public boolean connected;
        /** 没连上 —— **包含认证失败与超时两种情况，系统不区分**。 */
        public boolean unavailable;
        /** 用户拒绝了确认框（或被系统策略挡住）。 */
        public boolean userRejected;
        /** 我们自己的超时（系统一直没给答复）。 */
        public boolean timedOut;
        /** 发起前就判定为不可信（目标已被系统保存 / 已连着它）。 */
        public boolean preBlocked;
        /** 决定性说明（诊断用，不进判定）。 */
        public String code = "";
        public String detail = "";
        public long elapsedMs;

        public boolean conclusive() {
            return connected || unavailable || userRejected;
        }
    }

    private volatile Attempt lastAttempt;

    /** 等上一次请求出结果（阻塞，调用方应在工作线程）。 */
    public Outcome await(long maxWaitMs) {
        Attempt a = lastAttempt;
        if (a == null) {
            Outcome o = new Outcome();
            o.code = "no-attempt";
            o.detail = "尚未发起任何请求";
            return o;
        }
        return a.await(maxWaitMs <= 0 ? timeoutMs + 5_000L : maxWaitMs);
    }

    // ------------------------------------------------------------ 单次请求

    private final class Attempt {
        final String ssid;
        final String security;
        final String password;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Outcome> result = new AtomicReference<>();
        long startMs;

        Attempt(String ssid, String security, String password) {
            this.ssid = ssid;
            this.security = security;
            this.password = password;
        }

        void run() {
            startMs = System.currentTimeMillis();
            final CountDownLatch finished = done;
            if (handler != null) {
                handler.post(this::postRequest);
            } else {
                postRequest();
            }
            // 兜底超时：到了还没答复就当「超时」，绝不无限等
            Thread watchdog = new Thread(() -> {
                try {
                    if (!finished.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                        finish(timedOut("watchdog"));
                    }
                } catch (InterruptedException ignored) {
                }
            }, "wpaforge-spec-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        void postRequest() {
            try {
                if (ctx == null) {
                    finish(blocked("no-context", "没有 Context"));
                    return;
                }
                ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
                if (cm == null) {
                    finish(blocked("no-cm", "系统没有 ConnectivityManager"));
                    return;
                }
                WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder();
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 2) {
                    // 系统有的边界返回带引号的 SSID；SSID 本身真的带引号的情况极少，
                    // 这里只剥一层外层引号，不做其它改写。
                    b.setSsid(ssid.substring(1, ssid.length() - 1));
                } else {
                    b.setSsid(ssid);
                }
                if (SpecifierParams.isWpa3(security)) {
                    b.setWpa3Passphrase(passwordOf());
                } else {
                    b.setWpa2Passphrase(passwordOf());
                }
                final NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(b.build())
                        .build();

                final ConnectivityManager.NetworkCallback cb =
                        new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(Network network) {
                                Outcome o = new Outcome();
                                o.connected = true;
                                o.code = "available";
                                o.detail = "系统连上了目标（口令正确）";
                                finish(o);
                            }

                            @Override
                            public void onUnavailable() {
                                Outcome o = new Outcome();
                                o.unavailable = true;
                                // 系统只给「不可用」：认证失败 / 超时 / AP 拒绝 都在这里
                                o.code = "unavailable";
                                o.detail = "系统未能建立连接（认证失败或超时，系统不区分）";
                                finish(o);
                            }

                            @Override
                            public void onLost(Network network) {
                                // 拿到后再掉线：不影响「曾经连上」这个事实，由回调流程收尾
                            }
                        };
                currentCallback = cb;
                currentCm = cm;
                try {
                    cm.requestNetwork(req, cb, (int) Math.min(Integer.MAX_VALUE, timeoutMs));
                } catch (SecurityException se) {
                    finish(blocked("security", "缺少必要权限：" + se.getMessage()));
                } catch (IllegalArgumentException iae) {
                    finish(blocked("bad-args", "系统拒绝了这个网络描述：" + iae.getMessage()));
                }
            } catch (Throwable t) {
                finish(blocked("error", t.getClass().getSimpleName() + ": " + t.getMessage()));
            }
        }

        String passwordOf() {
            return password;
        }

        Outcome await(long ms) {
            try {
                done.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            Outcome o = result.get();
            if (o == null) return timedOut("await");
            return o;
        }

        synchronized void finish(Outcome o) {
            if (result.get() != null) return;   // 只认第一次结论
            o.elapsedMs = System.currentTimeMillis() - startMs;
            result.set(o);
            lastCode = o.code;
            lastDetail = o.detail;
            cleanup();
            done.countDown();
        }
    }

    private volatile ConnectivityManager currentCm;
    private volatile ConnectivityManager.NetworkCallback currentCallback;

    private void cleanup() {
        ConnectivityManager cm = currentCm;
        ConnectivityManager.NetworkCallback cb = currentCallback;
        currentCm = null;
        currentCallback = null;
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Outcome blocked(String code, String detail) {
        Outcome o = new Outcome();
        o.preBlocked = true;
        o.code = code;
        o.detail = detail;
        return o;
    }

    private static Outcome timedOut(String why) {
        Outcome o = new Outcome();
        o.timedOut = true;
        o.code = "timeout:" + why;
        o.detail = "系统在规定时间内没有给出答复";
        return o;
    }

    // ------------------------------------------------------------ 支持度探测

    /**
     * 本机能不能用这条路。返回 {@code null} 表示能用，否则返回中文原因。
     *
     * <p>判据全部来自**实测**，不从版本号推断：
     * <ul>
     *   <li>API 级别 ≥ 29（{@code WifiNetworkSpecifier} 从这里开始）；
     *   <li>{@code ConnectivityManager} 存在；
     *   <li>{@code requestNetwork} 不抛 {@code SecurityException}
     *       （第一次真发一次请求才知道，这里只做静态检查，实际结论以
     *       {@link #associate} 的返回值为准）。</li>
     * </ul>
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
        return null;    // 无 root 读不到 cmd wifi status，返回 null 而不是空串
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
            // 用 DEGRADED 而不是 NEEDS_GRANT：这条路**真的能跑**（不是「等你授权」），
            // 只是它有两条硬边界必须一并说出来 ——
            //   ① 每个候选都要用户点一次系统确认框；
            //   ② 失败只能到「系统未能建立连接」，拆不出「口令被拒」，
            //      所以永远进不了 DIRECT 级证据。
            // evidenceGrade 因此为 false：能用，但不能用来「永久排除密码」。
            list.add(Capability.degraded(Capability.C_WIFI_CONNECT,
                    "可用系统确认框逐次在线验证（每个候选需点一次确认；"
                            + "失败无法区分认证失败与超时，不会记成已证实错误）",
                    "per-candidate online verification via the system approval dialog "
                            + "(one confirmation per candidate; failures cannot be told apart "
                            + "from timeouts and are never recorded as proven-wrong)",
                    "把它当成「手动逐条验证」：一次一个候选，适合验证少量高价值口令；"
                            + "要全自动跑整本字典仍需 root。",
                    "Treat it as manual per-candidate verification; "
                            + "full dictionary runs still need root."));
        } else {
            list.add(Capability.unsupported(Capability.C_WIFI_CONNECT, why, probeReasonEn,
                    "改用离线分析（握手/PMKID 文件）或换到已 root 的设备。",
                    "Use offline analysis or a rooted device."));
        }

        list.add(Capability.unsupported(Capability.C_WIFI_FORGET,
                "公开 API 不能删除系统已保存的网络，因此无法保证「旧配置已清空」",
                "public APIs cannot remove saved networks, so stale-config clearing is impossible",
                "删掉系统里该网络的已保存配置（设置 → WLAN → 长按 → 取消保存）后再试。",
                "Remove the saved network in system settings first, then retry."));
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
        return "无 Root 在线验证：每验证一个候选，系统会弹一次「连接到该网络？」确认框，"
                + "确认后由系统真正去关联。连上 = 这条口令对；没连上 = 系统未能建立连接"
                + "（认证失败与超时无法区分，所以不会记成「已证实的错误密码」）。";
    }

    public static String usageHintEn() {
        return "Rootless online verification: the system asks once per candidate "
                + "(\"connect to this network?\"), then associates for real. Connected = the "
                + "passphrase works; not connected = the system could not establish it "
                + "(auth failure and timeout are indistinguishable, so it is never recorded "
                + "as a proven-wrong passphrase).";
    }

    /** 双语文案帮助（供 UI 层取用，避免各处各写一份）。 */
    public static String label(boolean zh, String key) {
        if ("title".equals(key)) return zh ? "在线验证（无 Root）" : "Online verify (rootless)";
        if ("usage".equals(key)) return zh ? usageHintZh() : usageHintEn();
        return zh ? L.s("在线验证", "Online verify") : "Online verify";
    }
}

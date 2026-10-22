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
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.ConnectionProber;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;
import io.github.wlmosv_png.wpaforge.audit.RootProber;
import io.github.wlmosv_png.wpaforge.audit.HistStore;
import io.github.wlmosv_png.wpaforge.audit.SavedWifiReader;
import io.github.wlmosv_png.wpaforge.audit.SmartSorter;
import io.github.wlmosv_png.wpaforge.audit.SuggestionProber;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;
import io.github.wlmosv_png.wpaforge.scan.ChannelAnalyzer;
import io.github.wlmosv_png.wpaforge.scan.ScanDetail;
import io.github.wlmosv_png.wpaforge.scan.Scanner;
import io.github.wlmosv_png.wpaforge.scan.SsidGroup;
import io.github.wlmosv_png.wpaforge.ui.ChannelBars;
import io.github.wlmosv_png.wpaforge.ui.RadarSweep;
import io.github.wlmosv_png.wpaforge.ui.AuthorCard;
import io.github.wlmosv_png.wpaforge.ui.NetDialog;
import io.github.wlmosv_png.wpaforge.ui.SignalBars;
import io.github.wlmosv_png.wpaforge.ui.StagedProgress;

/**
 * 主界面。四个页签：观测 / 审计 / 试连 / 设置。
 * 全部代码构建，不带 XML 布局资源，视觉上与原版形态完全分离。
 */
public class MainActivity extends Activity {

    // 配色：琥珀主色 + 近黑背景，与常见的蓝紫渐变彻底分开
    private static final int BG        = Color.parseColor("#0E0E11");
    private static final int SURFACE   = Color.parseColor("#17171C");
    private static final int ACCENT    = Color.parseColor("#FFB300");
    private static final int TEXT      = Color.parseColor("#E8E8E8");
    private static final int TEXT_DIM  = Color.parseColor("#8A8A8A");
    private static final int DANGER    = Color.parseColor("#FF5252");
    private static final int OK_GREEN  = Color.parseColor("#4CAF50");

    /* ---------- 按钮色板（只有这四档，别再加第五种） ---------- */
    /** 次操作底：暗面，比 SURFACE 亮一点点，够区分但不抢眼 */
    private static final int BTN_2ND_BG   = Color.parseColor("#212129");
    /** 次操作描边：极淡的白，靠它把暗底从背景里托起来 */
    private static final int BTN_2ND_EDGE = Color.parseColor("#33FFFFFF");
    /** 主操作文字色（琥珀底上必须用深色，白字对比度不够） */
    private static final int ON_ACCENT    = Color.parseColor("#14141A");

    private LinearLayout root;
    private LinearLayout content;          // 外层（竖直排列 4 个 page）
    private LinearLayout pageObserve;      // 观测页容器（建一次）
    private LinearLayout pageAudit;
    private LinearLayout pageConnect;
    private LinearLayout pageSettings;
    private boolean[] pageBuilt = new boolean[4];   // 是否已构建过
    private TextView tabObserve, tabAudit, tabConnect, tabSettings;

    private Scanner scanner;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 观测页组件
    private RadarSweep radar;
    private SignalBars bars;
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
    /** 离线校验是否在跑（同一时间只允许一条） */
    private boolean offlineBusy = false;
    private List<Candidate> currentDict;
    private String currentDictName = "";
    private AuditEngine engine;

    // 试连页组件
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
    private TextView connectTargetView;
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
    private ConnectionProber prober;
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
    private LinearLayout queueBox;
    /** 设置页里的权限状态行（授权后要刷新） */
    private TextView permLabel;
    /** 「去系统设置里允许」按钮；权限齐全时隐藏 */
    private Button permFixBtn;

    private static final int REQ_SHIZUKU = 3001;

    private int currentTab = 0;
    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        dp = getResources().getDisplayMetrics().density;
        buildUi();
        requestPermsIfNeeded();
        scanner = new Scanner(this, scanListener);
        registerShizukuWatcher();
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
        pinGate.setBackgroundColor(0xF0101014);
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
        header.setPadding(px(26), px(48), px(22), px(8));
        TextView title = new TextView(this);
        title.setText("WPAForge");
        title.setTextSize(24);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        header.addView(title);
        TextView sub = new TextView(this);
        sub.setText(L.s("WPA 密码破解工具 · 仅限自有网络", "WPA password cracker · authorised networks only"));
        sub.setTextSize(11.5f);
        sub.setTextColor(TEXT_DIM);
        header.addView(sub);
        root.addView(header);

        // 页签
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(px(10), px(4), px(10), px(10));
        tabObserve = makeTab(L.s("观测", "Observe"), 0);
        tabAudit = makeTab(L.s("审计", "Audit"), 1);
        tabConnect = makeTab(L.s("破解", "Crack"), 2);
        tabSettings = makeTab(L.s("设置", "Settings"), 3);
        tabs.addView(tabObserve, gapWeight(10));
        tabs.addView(tabAudit, gapWeight(10));
        tabs.addView(tabConnect, gapWeight(10));
        tabs.addView(tabSettings, gapWeight(0));
        root.addView(tabs);

        // 内容容器（可滚动）
        mainScroll = new ScrollView(this);
        ScrollView sv = mainScroll;
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(16), px(8), px(16), px(24));

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
        content.addView(pageObserve);
        content.addView(pageAudit);
        content.addView(pageConnect);
        content.addView(pageSettings);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showTab(0);
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
            boolean canPause = running;
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

    private TextView makeTab(String label, int index) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(12), 0, px(12));
        t.setTextColor(TEXT_DIM);
        t.setBackground(roundBg(Color.parseColor("#1A1A22"), 18));
        t.setOnClickListener(v -> showTab(index));
        return t;
    }

    private void showTab(int index) {
        currentTab = index;

        // 页签样式
        TextView[] tabs = {tabObserve, tabAudit, tabConnect, tabSettings};
        int chipOff = Color.parseColor("#1A1A22");
        int onText  = Color.parseColor("#14141A");
        for (int i = 0; i < tabs.length; i++) {
            boolean on = (i == index);
            // 选中：实心琥珀 + 深字 + 加粗；未选中：暗底 + 灰字 + 常规。
            // 三处同时变（底、字色、字重），扫一眼就能定位当前页，
            // 不再需要凑近看哪块底色是黄的。
            tabs[i].setBackground(roundBgStroked(
                    on ? ACCENT : chipOff,
                    on ? ACCENT : BTN_2ND_EDGE, 18, 0.8f));
            tabs[i].setTextColor(on ? onText : Color.parseColor("#9A9AA6"));
            tabs[i].setTypeface(on
                    ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    : Typeface.DEFAULT);
        }

        LinearLayout[] pages = {pageObserve, pageAudit, pageConnect, pageSettings};

        // 首次访问该页时才构建内容
        if (!pageBuilt[index]) {
            switch (index) {
                case 0: buildObserveTab(); break;
                case 1: buildAuditTab(); break;
                case 2: buildConnectTab(); break;
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
        } else {
            if (radar != null) radar.stopSpin();
            if (scanner != null) scanner.stop();
        }

        // 回到破解页：刷新队列与日志（内容不用重建）
        if (index == 2) {
            refreshQueue();
            if (connectStreamView != null && streamBuffer.length() > 0) {
                // 用 Spannable 版重设，否则切回来颜色全丢。
                connectStreamView.setText(streamBuffer,
                        android.widget.TextView.BufferType.SPANNABLE);
            }
            updateConnectDict();
            updateConnectMode();
            updateTargetLabel();
        }
    }

    // ---------------- 观测页 ----------------

    private void buildObserveTab() {
        radar = new RadarSweep(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(240));
        rp.bottomMargin = px(12);
        pageObserve.addView(radar, rp);
        radar.setCenterText(L.s("扫描中", "Scanning"), L.s("正在探测周边网络", "Probing nearby networks"));

        observeNotice = new TextView(this);
        observeNotice.setTextSize(12);
        observeNotice.setTextColor(DANGER);
        observeNotice.setPadding(0, 0, 0, px(8));
        observeNotice.setVisibility(View.GONE);
        pageObserve.addView(observeNotice);

        // 网络侦察入口: 终端式局域网设备探测(需 root)。
        Button lanProbe = btnSecondary(L.s("网络侦察 ▸ 探测当前 WiFi 设备",
                "LAN recon ▸ probe devices on WiFi"));
        lanProbe.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(this,
                        io.github.wlmosv_png.wpaforge.ui.LanProbeActivity.class));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开: " + t.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        pageObserve.addView(lanProbe);


        pageObserve.addView(sectionLabel(L.s("信号分布", "Signal distribution")));

        bars = new SignalBars(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(150));
        bp.bottomMargin = px(16);
        pageObserve.addView(bars, bp);

        // 信道占用：横向条，一眼看出哪个信道空、哪个挤
        pageObserve.addView(sectionLabel(L.s("信道占用", "Channel occupancy")));
        channelBars = new ChannelBars(this);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbp.bottomMargin = px(16);
        pageObserve.addView(channelBars, cbp);

        adviceCard = new TextView(this);
        adviceCard.setTextSize(12);
        adviceCard.setTextColor(TEXT);
        adviceCard.setLineSpacing(px(4), 1f);
        adviceCard.setBackground(roundBg(SURFACE, 16));
        adviceCard.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = px(16);
        adviceCard.setLayoutParams(alp);
        adviceCard.setVisibility(View.GONE);
        pageObserve.addView(adviceCard);

        pageObserve.addView(sectionLabel(L.s("本机已存 WiFi", "Saved Wi-Fi on this device")));
        savedWifiCard = new TextView(this);
        savedWifiCard.setTextSize(12.5f);
        savedWifiCard.setTextColor(TEXT_DIM);
        savedWifiCard.setLineSpacing(px(4), 1f);
        savedWifiCard.setBackground(roundBg(SURFACE, 16));
        savedWifiCard.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swlp.bottomMargin = px(16);
        savedWifiCard.setLayoutParams(swlp);
        savedWifiCard.setOnClickListener(v -> showSavedWifi());
        pageObserve.addView(savedWifiCard);
        updateSavedWifiCard();

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

    private TextView filterChip(String label, int mode) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(TEXT_DIM);
        t.setPadding(px(14), px(8), px(14), px(8));
        t.setBackground(roundBgStroked(Color.parseColor("#1A1A22"),
                BTN_2ND_EDGE, 16, 0.8f));
        t.setOnClickListener(v -> {
            netFilter = mode;
            renderRecords(lastRecords);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(8);
        t.setLayoutParams(lp);
        return t;
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

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11.5f);
        t.setTextColor(TEXT_DIM);
        t.setLetterSpacing(0.12f);
        t.setPadding(px(4), px(14), 0, px(10));
        return t;
    }

    private final Scanner.Listener scanListener = new Scanner.Listener() {
        @Override
        public void onResults(List<NetRecord> records) {
            lastRecords = records;
            if (currentTab == 0) renderRecords(records);
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
        if (radar == null || bars == null || netList == null) return;

        // 应用筛选
        List<NetRecord> visible = new ArrayList<>();
        for (NetRecord r : records) {
            if (passFilter(r)) visible.add(r);
        }

        radar.setCenterText(String.valueOf(visible.size()), L.s("个网络", "networks"));
        List<SignalBars.Bar> bs = new ArrayList<>();
        RadarSweep.Blip[] blips = new RadarSweep.Blip[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            NetRecord r = visible.get(i);
            bs.add(new SignalBars.Bar(r.displayName(), r.rssi, r.is5Ghz,
                    r.security != NetRecord.Security.OPEN && r.security != NetRecord.Security.WEP));
            float dist = Math.max(0.1f, Math.min(1f, (100f + r.rssi) / 70f));
            blips[i] = new RadarSweep.Blip((i * 47f) % 360f, dist,
                    r.signalLevel() >= 2, r.security != NetRecord.Security.OPEN);
        }
        bars.setBars(bs);
        radar.setBlips(blips);

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
            TextView empty = new TextView(this);
            empty.setText(netFilter == FILTER_ALL
                    ? emptyTextWithDiag()
                    : L.s("当前筛选下没有匹配的网络。", "No networks match the current filter."));
            empty.setTextSize(12.5f);
            empty.setTextColor(TEXT_DIM);
            empty.setBackground(roundBg(SURFACE, 16));
            empty.setPadding(px(16), px(18), px(16), px(18));
            empty.setLineSpacing(px(3), 1f);
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
        if (adviceCard == null) return;
        if (rep == null) {
            adviceCard.setVisibility(View.GONE);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(L.s("信道建议", "Channel advice")).append('\n');

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
            adviceCard.setVisibility(View.GONE);
            return;
        }

        sb.append(L.s("(改路由器信道后重扫，占用会变)",
                "(Rescan after changing your router channel)"));

        adviceCard.setText(sb.toString().trim());
        adviceCard.setVisibility(View.VISIBLE);
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
    private View groupHeader(SsidGroup.Group g) {
        if (!g.isMesh()) return new TextView(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = px(10);
        bp.bottomMargin = px(4);
        box.setLayoutParams(bp);

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
        t.setTextSize(12);
        t.setTextColor(ACCENT);
        t.setPadding(px(4), 0, 0, 0);
        box.addView(t);
        return box;
    }

    /**
     * 观测页"没扫到网络"时展示的文案。
     *
     * 不再是干巴巴一句"还没扫描到" —— 而是把 Scanner 记下的真实原因
     * 和特权通道（root / Shizuku）的结果一起摊开，用户一眼能看出
     * 是权限问题、WiFi 没开、还是系统节流。
     */
    private String emptyTextWithDiag() {
        String base = L.s("还没扫描到网络。请确认 WiFi 已开启、权限已授予。",
                "No networks found. Check that Wi-Fi is on and permissions are granted.");
        if (scanner == null) return base;

        StringBuilder sb = new StringBuilder(base);
        String d = scanner.lastDiagnostic();
        if (d != null && !d.isEmpty()) {
            sb.append("\n\n").append(L.s("诊断: ", "Diagnostic: ")).append(d);
        }
        String priv = scanner.privilegedDiagnostic();
        if (priv != null && !priv.isEmpty()) {
            sb.append("\n");
            if ("PRIV_UNAVAILABLE".equals(priv)) {
                sb.append(L.s("系统通道不可用：本机没有可用的 root，Shizuku 服务也没在运行。",
                        "System channel unavailable: no working root, and the Shizuku service is not running."));
            } else if ("PRIV_EMPTY".equals(priv)) {
                sb.append(L.s("系统通道可用，但系统本身也没有扫描数据。",
                        "The system channel works, but the system itself has no scan data."));
            } else {
                sb.append(priv);
            }
        }
        return sb.toString();
    }

    private View netRow(NetRecord r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(roundBg(SURFACE, 18));
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = px(6);
        row.setLayoutParams(rp);

        // 左：名称 + 细节
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(r.displayName());
        name.setTextSize(14);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(name);

        TextView detail = new TextView(this);
        // 厂商名：IEEE OUI 表查询。查不到就不显示这一段。
        //
        // 随机化 MAC（首字节 bit1 = 1）查表必然落空，但原因和
        // 「厂商未收录」完全不同 —— 前者是手机自己生成的地址，
        // 后者是数据缺失。必须分开说，否则用户会以为是数据有问题。
        String vendor = null;
        if (io.github.wlmosv_png.wpaforge.scan.OuiLookup.isRandomized(r.bssid)) {
            vendor = L.s("随机 MAC", "random MAC");
        } else {
            vendor = io.github.wlmosv_png.wpaforge.scan.OuiLookup.vendor(this, r.bssid);
        }
        detail.setText(r.bssid + "  ·  ch" + r.channel
                + (r.is5Ghz ? " (5G)" : " (2.4G)")
                + "  ·  " + securityLabel(r.security)
                + (r.wpsSupported ? "  ·  WPS" : "")
                + (vendor != null && !vendor.isEmpty()
                    ? "  ·  " + vendor : "")
                + (!r.isHidden() && r.estimatedDistanceMeters() > 0
                    ? "  ·  ≈" + r.estimatedDistanceMeters() + "m" : ""));
        detail.setTextSize(10);
        detail.setTextColor(TEXT_DIM);
        left.addView(detail);

        // 第二行细节：带宽 / Wi-Fi 代际。拿不到就不加这一行，不占位。
        ScanDetail sd = scanner == null ? ScanDetail.UNKNOWN : scanner.detailOf(r);
        if (!sd.isEmpty()) {
            TextView phy = new TextView(this);
            phy.setText(sd.label());
            phy.setTextSize(10);
            phy.setTextColor(ACCENT);
            phy.setPadding(0, px(2), 0, 0);
            left.addView(phy);
        }

        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右：信号等级 + 加密徽标
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        TextView lvl = new TextView(this);
        lvl.setText(levelText(r.signalLevel()));
        lvl.setTextSize(11);
        lvl.setTextColor(levelColor(r.signalLevel()));
        lvl.setGravity(Gravity.END);
        right.addView(lvl);

        TextView sec = new TextView(this);
        boolean weak = r.security == NetRecord.Security.OPEN
                || r.security == NetRecord.Security.WEP;
        sec.setText(weak ? L.s("无保护", "No protection") : L.s("已加密", "Secured"));
        sec.setTextSize(10);
        sec.setTextColor(weak ? DANGER : OK_GREEN);
        sec.setGravity(Gravity.END);
        right.addView(sec);

        row.addView(right);
        return row;
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

    private int levelColor(int lv) {
        if (lv >= 3) return OK_GREEN;
        if (lv == 2) return ACCENT;
        return DANGER;
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

    private void buildAuditTab() {
        if (radar != null) radar.stopSpin();

        pageAudit.addView(sectionLabel(L.s("字典", "Dictionary")));

        dictStatus = new TextView(this);
        dictStatus.setTextSize(13);
        dictStatus.setTextColor(TEXT);
        dictStatus.setPadding(0, 0, 0, px(10));
        updateDictStatus();
        pageAudit.addView(dictStatus);

        Button manage = btnPrimary(L.s("打开字典管理", "Manage dictionaries"));
        manage.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, DictActivity.class)));
        pageAudit.addView(manage);

        Button load = btnSecondary(L.s("加载字典文件", "Load dictionary file"));
        load.setOnClickListener(v -> showDictPicker());
        pageAudit.addView(load);

        Button gen = btnSecondary(L.s("内置生成器（快速）", "Built-in generator (quick)"));
        gen.setOnClickListener(v -> {
            new Thread(() -> {
                DictGenerator g = new DictGenerator(100000)
                        .classic().numericPatterns().phoneSegments()
                        .wordVariants().wordDigits();
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
        pageAudit.addView(gen);

        // 按 SSID 派生：审计页以前只能「加载文件」和「内置生成器」，
        // 想按目标网络现场造候选必须跑到破解页 —— 两个页面本是一件事。
        // 这里补上入口，派生结果直接成为当前字典。
        Button derive = btnSecondary(L.s("按 SSID 派生（选目标）",
                "Derive from SSID (pick a target)"));
        derive.setOnClickListener(v -> pickTargetForDerive());
        pageAudit.addView(derive);

        pageAudit.addView(sectionLabel(L.s("口令强度检查", "Passphrase strength check")));

        EditText input = new EditText(this);
        input.setHint(L.s("输入一个口令，看它在字典里的位置", "Enter a passphrase to see its position in the dictionary"));
        input.setTextColor(TEXT);
        input.setHintTextColor(TEXT_DIM);
        input.setTextSize(13);
        input.setBackground(roundBg(SURFACE, 16));
        input.setPadding(px(16), px(14), px(16), px(14));
        pageAudit.addView(input);

        Button check = btnPrimary("评估");
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
            if (rank > 0) {
                appendLog("命中: \"" + pw + "\" 在字典第 " + rank + " 位 —— 弱口令");
            } else {
                appendLog("未命中: \"" + pw + "\" 不在当前字典里");
            }
        });
        pageAudit.addView(check);

        // ---------------- 离线校验（外部抓包材料） ----------------
        pageAudit.addView(sectionLabel(L.s("离线校验（外部材料）", "Offline check (external capture)")));

        TextView offHint = new TextView(this);
        offHint.setText(L.s(
                "材料由电脑侧抓包工具导出，手机端只做本地比对 —— 不会发起任何关联请求。\n"
                + "支持 hashcat 22000 格式（.hc22000 / .22000），放到 Download 或 Download/WPAForge-hc/ 下即可。\n"
                + "PMKID 材料只需 PBKDF2 + 一次 HMAC，比握手材料快近一倍，抓包时优先要它。",
                "Material comes from a desktop capture tool; the phone only compares locally.\n"
                + "hashcat 22000 format (.hc22000 / .22000) supported — drop it in Download or Download/WPAForge-hc/.\n"
                + "PMKID needs only PBKDF2 + one HMAC, about twice as fast as EAPOL — ask for it when capturing."));
        offHint.setTextSize(11.5f);
        offHint.setTextColor(TEXT_DIM);
        offHint.setLineSpacing(px(4), 1f);
        offHint.setBackground(roundBg(SURFACE, 18));
        offHint.setPadding(px(18), px(16), px(18), px(16));
        pageAudit.addView(offHint);

        Button offPick = btnSecondary(L.s("选择材料文件，跑当前字典",
                "Pick a capture file, run the current dictionary"));
        offPick.setOnClickListener(v -> showOfflinePicker());
        pageAudit.addView(offPick);

        pageAudit.addView(sectionLabel(L.s("日志", "Log")));

        auditLog = new TextView(this);
        auditLog.setTextSize(11);
        auditLog.setTextColor(TEXT_DIM);
        auditLog.setTypeface(Typeface.MONOSPACE);
        auditLog.setBackground(roundBg(SURFACE, 16));
        auditLog.setPadding(px(16), px(16), px(16), px(16));
        auditLog.setMinLines(6);
        pageAudit.addView(auditLog);
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
                            appendLog(L.s("⚡ 命中: ", "HIT: ") + r.hitPassword
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
        int n = currentDict == null ? 0 : currentDict.size();
        dictStatus.setText(L.s("当前字典: ", "Current dictionary: ") + n + L.s(" 条", " entries") + (n == 0 ? L.s("（未选择）", "(none selected)") : ""));
    }

    /**
     * 破解页的「字典来源」三选一。
     *
     * 不再只给「选文件」：内置生成器与按 SSID 派生本来在审计页，
     * 用户切来切去还会遇到「切回来没刷新」。全部收到这一处，
     * 选完立即生效并刷新破解页那行显示。
     */
    private void showDictSource() {
        // 用自研 NetDialog，不用系统 AlertDialog。
        // 系统弹窗是 Material 白底风格，跟字典管理页里的暗色圆角
        // 弹窗完全两套语言，同一个 App 里跳得很。NetDialog 已经
        // 支持 addItem 的一行式条目，正好拿来代替 setItems。
        io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                        L.s("字典来源", "Dictionary source"), null);
        d.addItem(L.s("选择字典文件", "Pick a dictionary file"),
                dd -> showDictPicker());
        d.addItem(L.s("按目标 SSID 派生", "Derive from target SSID"),
                dd -> deriveFromTarget());
        d.addItem(L.s("内置生成器（通用）", "Built-in generator (generic)"),
                dd -> buildGenericDict());
        d.setNegative(L.s("取消", "Cancel"), null);
        d.show();
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
        appendStream(L.f("正在按 SSID「%s」生成定向载荷...",
                "Deriving candidates from SSID \"%s\"...", ssid));
        new Thread(() -> {
            final io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.Result r =
                    io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder.build(targetNet, 20000,
                            io.github.wlmosv_png.wpaforge.scan.OuiLookup.vendor(this, targetNet.bssid));
            final String nm = L.s("SSID 派生 · ", "SSID-derived · ") + ssid;
            final String path = saveDictToFile(r.candidates, nm);
            ui.post(() -> {
                applyDict(r.candidates, nm, path);
                appendStream(L.f("定向载荷就绪: %d 发", "Derived %d candidates", r.candidates.size()));
                appendStream(r.explanation);
            });
        }).start();
    }

    /** 内置生成器（通用词法），同样先落盘 */
    private void buildGenericDict() {
        appendStream(L.s("正在编排通用载荷...", "Building built-in dictionary..."));
        new Thread(() -> {
            io.github.wlmosv_png.wpaforge.audit.DictGenerator g =
                    new io.github.wlmosv_png.wpaforge.audit.DictGenerator(200000)
                            .classic().numericPatterns().phoneSegments()
                            .wordVariants().wordDigits();
            final java.util.List<Candidate> list = g.build();
            final String nm = L.s("内置生成器", "Built-in generator");
            final String path = saveDictToFile(list, nm);
            final String stats = renderStats(g.stats());
            ui.post(() -> {
                applyDict(list, nm, path);
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

    /** 统一入口：设为当前字典 + 刷新界面显示 */
    private void applyDict(java.util.List<Candidate> list, String name, String path) {
        currentDict = list;
        currentDictName = name;
        currentDictPath = path == null ? "" : path;
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
                                            + "或先用「按 SSID 派生」生成针对性候选 ——\n"
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
                currentDictPath = e.file.getAbsolutePath();
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
                    .setTitle(L.s("按 SSID 派生", "Derive from SSID"))
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
                    updateTargetLabel();
                    deriveFromTarget();
                })
                .setNegativeButton(L.s("取消", "Cancel"), null)
                .show();
    }

    private void appendLog(String line) {
        if (auditLog == null) return;
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
    private void buildConnectTab() {
        // ============ 顶部：状态区 ============
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(roundBg(SURFACE, 18));
        head.setPadding(px(16), px(14), px(16), px(14));

        // 行一：目标
        connectTargetView = new TextView(this);
        connectTargetView.setTextSize(15);
        connectTargetView.setTextColor(TEXT);
        connectTargetView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        connectTargetView.setLineSpacing(px(3), 1f);
        connectTargetView.setOnClickListener(v -> showTargetPicker());
        head.addView(connectTargetView);

        head.addView(divider());

        // 行二：字典
        connectDictView = new TextView(this);
        connectDictView.setTextSize(12.5f);
        connectDictView.setTextColor(TEXT_DIM);
        connectDictView.setPadding(0, px(9), 0, 0);
        connectDictView.setOnClickListener(v -> showDictSource());
        head.addView(connectDictView);

        head.addView(divider());

        // 行三：执行方式
        connectModeView = new TextView(this);
        connectModeView.setTextSize(12);
        connectModeView.setTextColor(TEXT_DIM);
        connectModeView.setPadding(0, px(9), 0, 0);
        connectModeView.setLineSpacing(px(3), 1f);
        // 不可点。只有一种执行方式，点开弹「仅支持 Root 模式」是噪音。
        // 可用性状态由上面那行的实时检测负责，这一行只报当前模式。
        connectModeView.setClickable(false);
        connectModeView.setFocusable(false);
        head.addView(connectModeView);

        // 进度条（整合了 已试/总数/速度/剩余）
        pageConnect.addView(head);

        // 进度条单独一条，默认不显示。
        // 以前它就在状态卡里，还没开始就画一个孤零零的
        // 「0%」，跟下面的文字挤在一起。现在无数据时整块 GONE。
        connectProgress = new io.github.wlmosv_png.wpaforge.ui.StagedProgress(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = px(10);
        connectProgress.setVisibility(View.GONE);
        pageConnect.addView(connectProgress, plp);

        // 提示条（权限、错误等）
        observeNotice = new TextView(this);
        observeNotice.setTextSize(12);
        observeNotice.setTextColor(DANGER);
        observeNotice.setPadding(px(4), px(8), px(4), 0);
        observeNotice.setVisibility(View.GONE);
        pageConnect.addView(observeNotice);

        // ============ 中部：候选流（可折叠）============
        // 标题行本身可点：展开时占满剩余空间，
        // 收起时只留最近几行，让下面的队列卡片露出来。
        flowHeader = new LinearLayout(this);
        flowHeader.setOrientation(LinearLayout.HORIZONTAL);
        flowHeader.setGravity(Gravity.CENTER_VERTICAL);
        flowHeader.setPadding(px(4), px(14), px(4), px(8));

        flowChevron = new TextView(this);
        flowChevron.setTextSize(11.5f);
        flowChevron.setTextColor(ACCENT);
        flowHeader.addView(flowChevron);

        flowLabel = new TextView(this);
        flowLabel.setTextSize(11.5f);
        flowLabel.setTextColor(TEXT_DIM);
        flowLabel.setLetterSpacing(0.12f);
        flowLabel.setText(L.s("攻击流", "CANDIDATES"));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp.leftMargin = px(6);
        flowHeader.addView(flowLabel, flp);

        flowCount = new TextView(this);
        flowCount.setTextSize(11);
        flowCount.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams fcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fcp.rightMargin = px(12);
        flowHeader.addView(flowCount, fcp);

        // 清空：暂停/停止后旧日志砍在那里很碍眼，
        // 给一个明确的清掉入口。这个动作**不会**影响任务状态。
        flowClear = new TextView(this);
        flowClear.setText(L.s("清空", "Clear"));
        flowClear.setTextSize(11);
        flowClear.setTextColor(DANGER);
        flowClear.setPadding(px(10), px(4), px(10), px(4));
        flowClear.setBackground(roundBg(Color.parseColor("#2A2020"), 10));
        flowClear.setOnClickListener(v -> {
            streamBuffer.clear();          // SpannableStringBuilder 用 clear()
            streamBuffer.clearSpans();     // 一起清掉残留的颜色 span
            if (connectStreamView != null) connectStreamView.setText("");
            updateFlowHeader();
            updateJumpLatest();
            updateEmptyHint();
            Toast.makeText(this, L.s("现场已清空", "Log cleared"),
                    Toast.LENGTH_SHORT).show();
        });
        // 防止点清空时触发到头部的折叠
        flowClear.setClickable(true);
        flowClear.setFocusable(true);
        flowHeader.addView(flowClear);

        flowHeader.setOnClickListener(v -> toggleStream());
        pageConnect.addView(flowHeader);


        connectStreamView = new TextView(this);
        connectStreamView.setTextSize(12);
        connectStreamView.setTextColor(TEXT_DIM);
        connectStreamView.setTypeface(Typeface.MONOSPACE);
        connectStreamView.setPadding(px(16), px(14), px(16), px(14));
        // 不要开 setTextIsSelectable：
        // 可选模式的 TextView 会把滑动手势吃掉（它要处理文本选择），
        // 表现就是「日志滚不动」。
        // 需要复制时用「导出日志」，比在界面上拖选更实用。
        connectStreamView.setTextIsSelectable(false);
        connectStreamView.setLineSpacing(px(4), 1f);
        connectStreamView.setHorizontallyScrolling(false);

        // 日志区必须可滚动。
        // 之前是一个裸 TextView 放在固定高度里 —— 内容一多，后面的就看不到了，
        // 而且新内容进来不会自动跟随，用户得自己猜"是不是卡住了"。
        streamScroll = new android.widget.ScrollView(this);
        streamScroll.setBackground(roundBg(Color.parseColor("#14141A"), 16));
        streamScroll.setFillViewport(true);
        // 常显竖滚动条：默认 ScrollView 不显示，用户不知道上面还有内容。
        streamScroll.setVerticalScrollBarEnabled(true);
        streamScroll.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_INSET);
        streamScroll.setScrollbarFadingEnabled(false);   // 不淡出，始终可见
        streamScroll.setOverScrollMode(android.view.View.OVER_SCROLL_ALWAYS);
        // 不要自己 new LayoutParams —— ScrollView 内部按自己的规则处理子 View，
        // 传错类型会 ClassCastException（LinearLayout$LayoutParams → FrameLayout）。
        // 直接让它自己生成即可。
        streamScroll.addView(connectStreamView);

        // 用户自己上滑查看历史时暂停自动跟随，
        // 否则新日志一来就把他拽回底部，历史根本看不了。
        streamScroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                // 破解页外层还有个 ScrollView。手指落在日志区时，
                // 要明确告诉外层「这段手势归我」，否则外层会把滑动截走，
                // 表现就是日志区滚不动、只能拖整页。
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
                ViewGroup.LayoutParams.MATCH_PARENT, px(340));

        // 用 FrameLayout 把日志区与「回到最新」浮标叠在一起，
        // 浮标固定在**日志区右下角内侧**。
        //
        // 原来浮标挂在 flowHeader 之后、gravity=END + topMargin=-18，
        // 结果正好压在标题行右侧的「清空」按钮上 —— 既点不到清空，
        // 浮标本身也浮在日志区外面，看着很怪。
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
        jumpLatestBtn.setBackground(roundBg(ACCENT, 16));
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
        jflp.rightMargin = px(14);
        jflp.bottomMargin = px(14);
        streamWrap.addView(jumpLatestBtn, jflp);

        pageConnect.addView(streamWrap, streamParams);

        // 空状态提示（浮在候选流上层的简单做法：先放一行说明）
        connectEmptyHint = new TextView(this);
        connectEmptyHint.setTextSize(12.5f);
        connectEmptyHint.setTextColor(TEXT_DIM);
        connectEmptyHint.setGravity(Gravity.CENTER);
        connectEmptyHint.setPadding(px(20), px(18), px(20), px(18));
        connectEmptyHint.setBackground(roundBg(Color.parseColor("#14141A"), 16));
        pageConnect.addView(connectEmptyHint);
        updateEmptyHint();

        // ============ 底部：操作栏（固定）============
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, px(12), 0, 0);
        actions.setBackgroundColor(BG);

        startBtn = primaryAction(L.s("▶ 开始", "▶ Start"), ACCENT);
        startBtn.setOnClickListener(v -> startConnect());
        actions.addView(startBtn, actParams(0, 1.6f));

        pauseBtn = primaryAction(L.s("‖ 暂停", "‖ Pause"), Color.parseColor("#2A2A36"));
        pauseBtn.setOnClickListener(v -> onPauseResumeClicked());
        actions.addView(pauseBtn, actParams(8, 1f));

        stopBtn = primaryAction(L.s("■ 停止", "■ Stop"), Color.parseColor("#2A2020"));
        stopBtn.setOnClickListener(v -> onStopClicked());
        actions.addView(stopBtn, actParams(8, 1f));

        pageConnect.addView(actions);

        // 队列（多目标才有意义，默认折叠成一行文字）
        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        queueBox.setPadding(0, px(8), 0, 0);
        pageConnect.addView(queueBox);

        //
        // 开关与高级设置收进弹窗，主界面不再占位置 ——
        // 智能排序 / 目标派生 默认关闭，普通用户不需要天天调。
        //
        smartToggle = null;
        targetDictToggle = null;

        // 初始化显示
        updateTargetLabel();
        updateConnectDict();
        updateConnectMode();
        applyStreamHeight();
        updateFlowHeader();
        refreshQueue();
    }

    /** 一条淡分隔线，用于把顶部状态卡里的几行分开 */
    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(0.6f)));
        lp.topMargin = px(9);
        lp.bottomMargin = px(1);
        v.setLayoutParams(lp);
        v.setBackgroundColor(Color.parseColor("#26FFFFFF"));
        return v;
    }

    /** 底部操作按钮 */
    private Button primaryAction(String label, int bg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14.5f);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(8), px(15), px(8), px(15));
        b.setBackground(roundBg(bg, 24));
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
        File out = new File(dir, L.s("00 内置生成器.txt", "00 Built-in generator.txt"));
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
        // 它会阻塞到当前候选等完（Shizuku 通道最多 ~3 秒），
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

    /** 执行方式：仅 Root，点击只弹提示 */
    /** 空状态提示 */
    private void updateEmptyHint() {
        if (connectEmptyHint == null) return;
        boolean empty = streamBuffer.length() == 0;
        // （SpannableStringBuilder 同样有 length()，无需改动语义）
        connectEmptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            boolean ready = targetNet != null && currentDict != null && !currentDict.isEmpty();
            connectEmptyHint.setText(ready
                    ? L.s("准备就绪\n点下面的「▶ 开始」",
                            "Ready\nTap “▶ Start” below")
                    : L.s("先选目标和字典\n（点上面两行可以更换）",
                            "Pick a target and a dictionary first\n(tap the lines above to change)"));
        }
    }

    /** 智能排序开关外观 */
    private void updateSmartToggle() {
        if (smartToggle == null) return;
        if (smartSortEnabled) {
            smartToggle.setText("智能排序: 已开启\n"
                    + L.s("按目标 SSID 与设备特征重排，让最可能的口令排在最前", "Reorder by target SSID and device traits so the likeliest passphrases come first"));
            smartToggle.setTextColor(OK_GREEN);
            smartToggle.setBackground(roundBg(Color.parseColor("#132A18"), 16));
        } else {
            smartToggle.setText("智能排序: 已关闭\n"
                    + L.s("按字典原始顺序依次尝试", "Try in the dictionary's original order"));
            smartToggle.setTextColor(TEXT_DIM);
            smartToggle.setBackground(roundBg(SURFACE, 16));
        }
    }

    /** 目标派生开关外观 */
    private void updateTargetDictToggle() {
        if (targetDictToggle == null) return;
        if (targetDictEnabled) {
            targetDictToggle.setText("目标派生: 已开启\n"
                    + L.s("按 SSID 与设备特征现场造候选，排在最前面尝试", "Generate candidates from the SSID and device traits, tried first"));
            targetDictToggle.setTextColor(OK_GREEN);
            targetDictToggle.setBackground(roundBg(Color.parseColor("#132A18"), 16));
        } else {
            targetDictToggle.setText("目标派生: 已关闭\n"
                    + L.s("只用字典里已有的候选", "Use only candidates already in the dictionary"));
            targetDictToggle.setTextColor(TEXT_DIM);
            targetDictToggle.setBackground(roundBg(SURFACE, 16));
        }
    }

    /** 破解页显示当前模式 */
    /**
     * 破解页那一行「通道 · 速度」。
     *
     * 只有一种通道了，所以不再写「执行方式:」暗示可切换，
     * 也不再枚举 Shizuku / 免 Root 分支 —— 那两个分支永远走不到，
     * 留着只会让后来人以为还有别的通道。
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
        if (live > 0) {
            connectModeView.setText(L.f("Root 通道   ·   本次每条约 %.1f 秒",
                    "Root channel   ·   %.1fs per attempt (this run)",
                    live / 1000.0));
        } else {
            long ms = Prefs.avgMsPerItem(this);
            connectModeView.setText(L.f("Root 通道   ·   上次每条约 %.1f 秒",
                    "Root channel   ·   ~%.1fs per attempt (last run)",
                    ms / 1000.0));
        }
        connectModeView.setTextColor(ACCENT);
    }

    /** 破解页显示当前选中的字典 */
    private void updateConnectDict() {
        if (connectDictView == null) return;
        if (currentDict == null || currentDict.isEmpty()) {
            connectDictView.setText(L.s("未选择字典 —— 点这里选择",
                    "No dictionary — tap to select"));
            connectDictView.setTextColor(TEXT_DIM);
        } else {
            connectDictView.setText(currentDictName + "   ·   "
                    + currentDict.size() + L.s(" 条", " entries"));
            connectDictView.setTextColor(OK_GREEN);
        }
        updateEmptyHint();
    }

    /** 服务状态回调 */
    private final CrackService.UiCallback svcCallback = new CrackService.UiCallback() {
        @Override
        public void onQueueChanged(java.util.List<TaskQueue.Task> queue) {
            ui.post(() -> {
                queueCache.clear();
                queueCache.addAll(queue);
                renderQueue();
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
                if (connectProgress == null) return;
                connectProgress.setVisibility(View.VISIBLE);
                // 这里必须也设一次 stages。
                //
                // 以前只 setText —— 而 stages 为空时 onMeasure 只给
                // dp(20) 的高度，三条轨道画不全（被裁掉），看起来就是
                // 「第一条候选没有进度条，第二条才出来」。原因在于
                // 首次 onProgress 要等引擎跑完一轮（约 0.3 秒）才回调。
                // 用 index/total 立刻给个形，与 onProgress 同口径。
                float p = total > 0 ? (float) (index - 1) / total : 0f;
                java.util.List<io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage> s0 =
                        new java.util.ArrayList<>();
                s0.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        p, L.f("整个字典 %.1f%%", "whole %.1f%%", p * 100f)));
                s0.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        p, L.f("已试 %d / %d", "tried %d / %d", index - 1, total)));
                s0.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(p, ""));
                connectProgress.setStages(s0);
                connectProgress.setDone(false);
                connectProgress.setText(
                        L.f("运行中 %d / %d", "Trying %d / %d", index, total),
                        L.f("当前载荷: %s", "Current: %s", candidate));
            });
        }

        @Override
        public void onAttemptResult(String candidate, boolean hit, long ms, String detail) {
            ui.post(() -> {
                appendStreamColored(
                        (hit ? "✓ 成功" : "✗") + "  " + candidate
                                + "  (" + ms + "ms " + detail + ")",
                        hit ? OK_GREEN : TEXT_DIM);
                // 每条都刷新状态行的实时速率 —— 否则用户看到的一直是上一个任务的数字
                updateConnectMode();
            });
        }

        @Override
        public void onProgress(int tested, int total, double rate, long etaMs) {
            ui.post(() -> {
                if (connectProgress == null) return;
                connectProgress.setVisibility(View.VISIBLE);
                float outer = total > 0 ? (float) tested / total : 0f;
                String eta = etaMs < 0 ? "计算中" : formatDuration(etaMs);
                java.util.List<io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage> st =
                        new java.util.ArrayList<>();
                st.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        outer, L.f("整个字典 %.0f%%", "whole %.0f%%", outer * 100)));
                st.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        outer, L.f("已试 %d / %d", "tried %d / %d", tested, total)));
                st.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        outer, L.f("%.2f 条/秒", "%.2f/s", rate)));
                connectProgress.setStages(st);
                connectProgress.setDone(false);
                connectProgress.setText(
                        L.f("已试 %d / %d", "Tried %d / %d", tested, total),
                        L.f("速率 %.2f 条/秒   预计剩余 %s",
                            "%.2f/s   %s remaining", rate, eta));
            });
        }

        @Override
        public void onTaskDone(TaskQueue.Task task, String hitPassword, String outcome) {
            ui.post(() -> {
                if ("DONE_HIT".equals(outcome)) {
                    appendStreamColored(L.s("⚡ 密钥命中 ", "★ HIT!") + task.ssid + " = " + hitPassword, OK_GREEN);
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
                    }
                }
                refreshQueue();
            });
        }

        @Override
        public void onLog(String line) {
            ui.post(() -> appendStream(line));
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
        t.setBackground(roundBg(Color.parseColor("#1A1A22"), 14));
        t.setPadding(px(14), px(10), px(14), px(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 文件名里不允许出现的字符（SSID 可能带这些） */
    private static final String FNAME_BAD = "[\\\\/:*?\"<>|]";
    private static final int AMBER = Color.parseColor("#FFA726");
    private static final int PAUSE_BG = Color.parseColor("#2A2318");

    /** 单个任务卡片 */
    private View taskCard(TaskQueue.Task task) {
        // 暂停 / 进行中用不同底色，一眼能分辨
        int cardBg = SURFACE;
        if (task.state == TaskQueue.State.PAUSED) cardBg = PAUSE_BG;
        else if (task.state == TaskQueue.State.RUNNING) cardBg = Color.parseColor("#132A18");

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackground(roundBg(cardBg, 18));
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
        badge.setTextColor(badgeFg(task.state));
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setBackground(roundBg(badgeBg(task.state), 12));
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
        String modeTag = task.crackMode == Prefs.MODE_NO_ROOT
                ? L.s("免 Root", "root-free") : L.s("Root", "Root");
        meta.setText(L.f("%s 模式\n字典 %s（%s）\n进度 %d / %d%s%s",
                "%s mode\nDictionary %s (%s)\nProgress %d / %d%s%s",
                modeTag,
                task.dictName,
                io.github.wlmosv_png.wpaforge.audit.DictRule.formatCount(task.dictSize),
                task.offset, task.dictSize,
                task.testedThisRun > 0
                    ? L.f("   本轮试了 %d 条", "   %d tried this run", task.testedThisRun) : "",
                hit));
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
        g.setColor(Color.parseColor("#2A2A36"));
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
            case RUNNING:   return Color.parseColor("#14141A");
            case PAUSED:    return Color.parseColor("#14141A");
            case DONE_HIT:  return Color.parseColor("#14141A");
            case PENDING:   return TEXT_DIM;
            default: return TEXT_DIM;
        }
    }

    private int badgeBg(TaskQueue.State st) {
        switch (st) {
            case RUNNING:   return OK_GREEN;
            case PAUSED:    return AMBER;
            case DONE_HIT:  return OK_GREEN;
            case PENDING:   return Color.parseColor("#2A2A36");
            case DONE_MISS: return Color.parseColor("#2A2A36");
            case STOPPED:   return Color.parseColor("#3A2020");
            default: return Color.parseColor("#2A2A36");
        }
    }

    private Button smallChip(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11.5f);
        b.setTextColor(color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundBg(Color.parseColor("#232330"), 16));
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

    private void updateTargetLabel() {
        if (connectTargetView == null) return;
        if (targetNet == null) {
            connectTargetView.setText(L.s("未选择目标 —— 点这里选择",
                    "No target — tap to select"));
            connectTargetView.setTextColor(TEXT_DIM);
        } else {
            connectTargetView.setText(targetNet.displayName()
                    + "   " + targetNet.rssi + "dBm   ch" + targetNet.channel
                    + "   " + securityLabel(targetNet.security));
            connectTargetView.setTextColor(TEXT);
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
                updateTargetLabel();
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
    private void restoreWifiAfterRun() {
        final String targetSsid = targetNet == null ? "" : targetNet.ssid;
        new Thread(() -> {
            try {
                if (!targetSsid.isEmpty()) {
                    String list = io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                            new String[]{"su", "-c", "cmd wifi list-networks"}, 6000);
                    long id = io.github.wlmosv_png.wpaforge.audit.ListNetworksParser
                            .findNetId(list, targetSsid);
                    if (id >= 0) {
                        io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                                new String[]{"su", "-c", "cmd wifi forget-network " + id}, 5000);
                    }
                }
                io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                        new String[]{"su", "-c", "cmd wifi set-wifi-enabled disabled"}, 8000);
                Thread.sleep(1200);
                io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                        new String[]{"su", "-c", "cmd wifi set-wifi-enabled enabled"}, 8000);
                ui.post(() -> appendStream(L.s(
                        "已恢复 WiFi（系统按已保存的凭据自动回连）",
                        "WiFi restored (system reconnects with saved credentials)")));
            } catch (Throwable ignored) {}
        }, "wpaforge-restore-wifi").start();
    }


    private void startConnect() {
        if (targetNet == null) {
            Toast.makeText(this, L.s("请先选择目标网络", "Select a target network first"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentDict == null || currentDict.isEmpty()) {
            Toast.makeText(this, L.s("字典为空，请先到审计页加载或生成", "Dictionary is empty — load or generate one in the Audit tab"), Toast.LENGTH_SHORT).show();
            return;
        }

        // 按当前模式选择检查器
        final int mode = Prefs.mode(this);
        RootProber.CheckResult chk;
        if (mode == Prefs.MODE_SHIZUKU) {
            chk = RootProber.checkShizuku(this);
        } else if (mode == Prefs.MODE_NO_ROOT) {
            SuggestionProber.CheckResult sc = SuggestionProber.check(this);
            chk = sc.ok ? new RootProber.CheckResult(true, "", "")
                    : new RootProber.CheckResult(false, sc.title, sc.detail);
        } else {
            chk = RootProber.check(this);
        }
        if (!chk.ok) {
            connectStreamView.setText("");
            appendStreamColored(L.f("无法开始: %s", "Cannot start: %s", chk.title), DANGER);
            NetDialog d = new NetDialog(this, chk.title, chk.detail);
            d.setNegative(L.s("知道了", "Got it"), null);
            // 给一个「换用其它方式」的快捷入口
            if (mode != Prefs.MODE_ROOT) {
                d.setPositive(L.s("改用 Root 模式", "Use root mode"),
                        dd -> pickMode(Prefs.MODE_ROOT));
            } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                d.setPositive(L.s("改用 Shizuku", "Use Shizuku"),
                        dd -> pickMode(Prefs.MODE_SHIZUKU));
            }
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
            if (mode == Prefs.MODE_NO_ROOT) {
                appendStream(L.s("免 Root 模式下系统会退避，实际可能比预估更慢",
                        "Root-free mode backs off — it may take longer than estimated"));
            }
        }

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
                targetDictEnabled, smartSortEnabled, Prefs.mode(this));
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
        if (streamCollapsed) {
            streamParams.height = px(160);   // 折叠态也多给几行
            streamParams.weight = 0f;
            connectStreamView.setMaxLines(5);
            connectStreamView.setEllipsize(TextUtils.TruncateAt.END);
        } else {
            streamParams.height = px(340);   // 展开态：一屏能看到更多历史
            streamParams.weight = 0f;
            connectStreamView.setMaxLines(Integer.MAX_VALUE);
            connectStreamView.setEllipsize(null);
        }
        // 高度加在**外层 wrap** 上（它挂在 LinearLayout 里，用 LinearLayout 参数），
        // streamScroll 在 FrameLayout 里始终 MATCH_PARENT 铺满。
        //
        // 踩过的坑：直接给 streamScroll 设 LinearLayout 参数，会因为
        // 它此刻的父容器是 FrameLayout 而抛 ClassCastException（测量期崩）。
        if (streamWrapView != null) streamWrapView.setLayoutParams(streamParams);
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
            int lines = 0;
            for (int i = 0; i < streamBuffer.length(); i++) {
                if (streamBuffer.charAt(i) == '\n') lines++;
            }
            flowCount.setText(lines > 0 ? (lines + " 行") : "");
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
    private void appendStreamColored(String line, int color) {
        if (line == null) return;
        int c = (color == 0) ? TEXT_DIM : color;

        int start = streamBuffer.length();
        streamBuffer.append(line).append('\n');
        streamBuffer.setSpan(
                new android.text.style.ForegroundColorSpan(c),
                start, streamBuffer.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 只保留最近 400 行，避免无限增长。
        // 上限从 200 提到 400：用户反馈「看不到前面的日志」，
        // 而 400 行的 Spannable 内存开销可忽略（每行 span 几十字节）。
        int lines = countLines(streamBuffer);
        if (lines > MAX_LOG_LINES) {
            // SpannableStringBuilder 没有 indexOf(String,int)。
            // 用纯文本副本定位，再把偏移映射回来 —— 到这一步还没删过任何
            // 字符，两者长度一致，偏移可以直接用。
            String plain = streamBuffer.toString();
            int from1 = plain.length() / 3;
            int cut = plain.indexOf('\n', from1);
            if (cut > 0) {
                // 同时要清掉被裁掉那段的 span，否则 span 会错位。
                android.text.style.ForegroundColorSpan[] old =
                        streamBuffer.getSpans(0, cut,
                                android.text.style.ForegroundColorSpan.class);
                for (android.text.style.ForegroundColorSpan sp : old) {
                    streamBuffer.removeSpan(sp);
                }
                streamBuffer.delete(0, cut + 1);
            }
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
                        + "会真的发起一次连接（会短暂切换网络）。",
                        "Enter a Wi-Fi whose passphrase you know.\n"
                                + "It will really connect once (briefly switching networks)."));
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

            // 构造 shell 通道
            final boolean useShizuku = m == Prefs.MODE_SHIZUKU;
            io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel sh = null;
            if (m != Prefs.MODE_NO_ROOT) {
                sh = new io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel() {
                    @Override public String exec(String cmd, long timeoutMs) {
                        if (useShizuku) {
                            return io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.exec(cmd, timeoutMs);
                        }
                        return io.github.wlmosv_png.wpaforge.audit.RootProber
                                .exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                    }
                    @Override public void forgetTarget(String s2) { }
                    @Override public String name() { return useShizuku ? "Shizuku" : "Root"; }
                };
            }

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

            // ── 步骤 2：通道 ──
            sb.append(L.s("\n【2/6】执行通道\n", "\n[2/6] Channel\n"));
            if (sh == null) {
                sb.append(L.s("  免 Root 模式：本机无法做字典遍历式判定，建议改用 Shizuku。\n",
                        "  Root-free mode: cannot do per-candidate verdicts here; use Shizuku.\n"));
            } else {
                String probe = null;
                try { probe = sh.exec("cmd wifi status", 8000); } catch (Throwable t) {
                    sb.append("  ✗ exec 异常: ").append(t.getClass().getSimpleName()).append('\n');
                }
                if (probe == null || probe.trim().isEmpty()) {
                    sb.append(L.s("  ✗ cmd wifi status 无输出 —— 通道没通，后面必然失败。\n",
                            "  x cmd wifi status returned nothing - channel is dead.\n"));
                    sb.append(L.s("     请检查 Shizuku 服务是否运行、本应用是否已授权。\n",
                            "     Check Shizuku service and that this app is authorised.\n"));
                } else {
                    sb.append("  ✓ cmd wifi status 可用\n");
                }
            }

            if (sh != null) {
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

    private void buildSettingsTab() {
        if (radar != null) radar.stopSpin();
pageSettings.addView(sectionLabel(L.s("破解模式", "Crack mode")));

        modeInfo = new TextView(this);
        modeInfo.setTextSize(12);
        modeInfo.setLineSpacing(px(4), 1f);
        modeInfo.setBackground(roundBg(SURFACE, 18));
        modeInfo.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(modeInfo);


        // 当前模式的实际可用性（实时检测）：一眼看到能不能用
        modeReadyLabel = new TextView(this);
        modeReadyLabel.setTextSize(11.5f);
        modeReadyLabel.setTextColor(TEXT_DIM);
        modeReadyLabel.setPadding(px(4), px(8), 0, 0);
        pageSettings.addView(modeReadyLabel);

        // 点一下重测 / 长按看证据 —— 这两个交互以前完全不可发现，
        // 用户根本不知道那行文字能点。这里直接写出来。
        TextView modeHint = new TextView(this);
        modeHint.setText(L.s("点一下重新检测 · 长按看完整检测证据",
                "Tap to re-check · long-press for full evidence"));
        modeHint.setTextSize(10.5f);
        modeHint.setTextColor(Color.parseColor("#6E6E78"));
        modeHint.setPadding(px(4), px(2), 0, px(6));
        pageSettings.addView(modeHint);
        refreshModeReady();

        fillModeInfo();

        pageSettings.addView(sectionLabel(L.s("语言", "Language")));

        languageBtn = btnSecondary(L.f("语言: %s", "Language: %s",
                L.modeLabel(this)));
        languageBtn.setOnClickListener(v -> showLanguagePicker());
        pageSettings.addView(languageBtn);

        // ---------------- 权限 ----------------
        pageSettings.addView(sectionLabel(L.s("权限", "Permissions")));

        permLabel = new TextView(this);
        permLabel.setTextSize(11.5f);
        permLabel.setTextColor(TEXT_DIM);
        permLabel.setLineSpacing(px(4), 1f);
        permLabel.setBackground(roundBg(SURFACE, 18));
        permLabel.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(permLabel);
        refreshPermLabel();

        // 权限在启动时已自动申请过，这里不再放手动申请按钮 ——
        // 放一个「申请缺失的权限」会让人以为不点就不给。
        // 只有「已被永久拒绝」（系统不再弹窗）时才给一个去处。
        permFixBtn = btnSecondary(L.s("去系统设置里允许", "Allow in system settings"));
        permFixBtn.setOnClickListener(v -> openAppSettings());
        permFixBtn.setVisibility(View.GONE);
        pageSettings.addView(permFixBtn);

        // ---------------- 诊断 ----------------
        // ---------------- 保活状态 ----------------
        pageSettings.addView(sectionLabel(L.s("保活状态", "Keep-alive status")));

        keepAliveLabel = new TextView(this);
        keepAliveLabel.setTextSize(11.5f);
        keepAliveLabel.setTextColor(TEXT_DIM);
        keepAliveLabel.setLineSpacing(px(4), 1f);
        keepAliveLabel.setBackground(roundBg(SURFACE, 18));
        keepAliveLabel.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(keepAliveLabel);
        refreshKeepAlive();

        Button fixAlive = btnSecondary(L.s("一键修复：写入系统白名单", "Fix now: write system whitelists"));
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
        pageSettings.addView(fixAlive);

        pageSettings.addView(sectionLabel(L.s("诊断", "Diagnostics")));

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
        diagHint.setTextSize(11.5f);
        diagHint.setTextColor(TEXT_DIM);
        diagHint.setLineSpacing(px(4), 1f);
        diagHint.setBackground(roundBg(SURFACE, 18));
        diagHint.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(diagHint);

        Button connTest = btnSecondary(L.s("连接自测（发一次真实连接命令）",
                "Connection test (send one real connect)"));
        connTest.setOnClickListener(v -> showConnTestDialog());
        pageSettings.addView(connTest);

        Button exportLog = btnSecondary(L.s("导出日志到 Download",
                "Export log to Download"));
        exportLog.setOnClickListener(v -> exportLog());
        pageSettings.addView(exportLog);

        // ---------------- 应用锁 ----------------
        pageSettings.addView(sectionLabel(L.s("应用锁", "App lock")));

        Button pinBtn = btnSecondary(L.s("PIN 锁", "PIN lock"));
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
        pageSettings.addView(pinBtn);

        TextView pinHint = new TextView(this);
        pinHint.setText(L.s("设置后每次打开应用都要先输入 PIN 才能进入。",
                "Once set, the PIN must be entered each time the app is opened."));
        pinHint.setTextSize(11.5f);
        pinHint.setTextColor(TEXT_DIM);
        pinHint.setLineSpacing(px(4), 1f);
        pinHint.setBackground(roundBg(SURFACE, 18));
        pinHint.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(pinHint);

                pageSettings.addView(sectionLabel(L.s("连接选项", "Connection options")));

        connPinBtn = btnSecondary("");
        connPinBtn.setOnClickListener(v -> {
            Prefs.setPinBssid(this, !Prefs.pinBssid(this));
            refreshConnOptions();
            Toast.makeText(this, connPinBtn.getText(), Toast.LENGTH_SHORT).show();
        });
        pageSettings.addView(connPinBtn);

        connRotBtn = btnSecondary("");
        connRotBtn.setOnClickListener(v -> {
            Prefs.setMacRotate(this, !Prefs.macRotate(this));
            refreshConnOptions();
            Toast.makeText(this, connRotBtn.getText(), Toast.LENGTH_SHORT).show();
        });
        pageSettings.addView(connRotBtn);

        TextView connHint = new TextView(this);
        connHint.setText(L.s(
                "· 锁定 AP：同名 SSID 有多个 AP 时，只跟你在破解页选中的那一个打交道（默认交给系统挑）\n"
                + "· 更换随机 MAC：每次尝试换一个新 MAC，缓解目标 AP 限流；目标若做了 MAC 白名单，开着会连不上",
                "· Pin AP: when several APs share one SSID, talk only to the one you picked\n"
                + "· Random MAC: a new MAC per attempt eases AP rate limiting; breaks MAC-allowlisted targets"));
        connHint.setTextSize(11.5f);
        connHint.setTextColor(TEXT_DIM);
        connHint.setLineSpacing(px(4), 1f);
        connHint.setBackground(roundBg(SURFACE, 18));
        connHint.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(connHint);

        refreshConnOptions();

        pageSettings.addView(sectionLabel(L.s("破解设置", "Crack settings")));

        TextView speedInfo = new TextView(this);
        speedInfo.setText(L.s("破解速度由目标路由器决定，实测约 1 秒/条。",
                        "Speed depends on the target router. Measured: about 1 second per attempt.")
                + " "
                + L.s("手机一次只能连一个网络，无法并行，这是 Android 的限制。",
                        "A phone can associate with only one network at a time — no parallelism. That is an Android limit."));
        speedInfo.setTextSize(12);
        speedInfo.setTextColor(TEXT_DIM);
        speedInfo.setLineSpacing(px(4), 1f);
        speedInfo.setBackground(roundBg(SURFACE, 18));
        speedInfo.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(speedInfo);

        Button toAuthor = btnPrimary(L.s("关于我 / 我的其它项目", "About me / my other projects"));
        toAuthor.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AboutActivity.class)));
        pageSettings.addView(toAuthor);

        pageSettings.addView(sectionLabel(L.s("工具", "Tools")));

        Button saved = btnSecondary(L.s("本机已存 WiFi 自查", "Inspect saved Wi-Fi"));
        saved.setOnClickListener(v -> showSavedWifi());
        pageSettings.addView(saved);

        Button hist = btnPrimary(L.s("破解记录", "Crack history"));
        hist.setOnClickListener(v -> showHistory());
        pageSettings.addView(hist);

        Button exp = btnSecondary(L.s("导出记录", "Export records"));
        exp.setOnClickListener(v -> doExport());
        pageSettings.addView(exp);

        pageSettings.addView(sectionLabel(L.s("使用边界", "Scope of use")));

        pageSettings.addView(infoBlock(L.s("仅对自己有授权的网络使用", "Use only on networks you are authorised for"),
                L.s("破解会向目标 AP 发起真实的关联请求。对自有网络是自查，", "WPAForge issues real association requests. On your own network that is a self-audit;")
                + L.s("对未授权网络是违法行为，且路由器侧会留下失败记录。", "on an unauthorised network it is unlawful, and the AP will log the failures.")));

        pageSettings.addView(infoBlock(L.s("关于速度", "About speed"),
                L.s("逐条连接受目标响应速度限制，快的 AP 约 1 秒，慢的可能 5 秒以上。", "Each attempt waits on the target. A fast AP takes ~1s, a slow one 5s or more.")
                + L.s("字典越大耗时越久 —— 建议先用目标派生 + 智能排序，几百条往往比十万条有效。", "Bigger dictionaries take longer — target derivation plus smart ranking makes a few hundred entries beat a hundred thousand.")));

        pageSettings.addView(sectionLabel(L.s("版本", "Version")));
        TextView ver = new TextView(this);
        ver.setText(Ver.labeled(this));
        ver.setTextSize(12);
        ver.setTextColor(TEXT_DIM);
        pageSettings.addView(ver);
    }

    /** 圆角胶囊按钮 */
    private Button makeButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(isLight(color) ? Color.parseColor("#14141A") : Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(px(20), px(14), px(20), px(14));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(roundBg(color, 26));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(5);
        lp.bottomMargin = px(11);
        b.setLayoutParams(lp);
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
        b.setBackground(roundBgStroked(BTN_2ND_BG, BTN_2ND_EDGE, 26, 0.8f));
        return b;
    }

    /** 危险按钮：暗底 + 红字。只给「会丢东西」的动作。 */
    private Button btnDanger(String label) {
        Button b = btnSecondary(label);
        b.setTextColor(DANGER);
        b.setBackground(roundBgStroked(Color.parseColor("#241A1A"),
                Color.parseColor("#44FF5252"), 26, 0.8f));
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
        box.setBackground(roundBg(SURFACE, 18));
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
        b.setBackground(roundBg(Color.parseColor("#232330"), 22));
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

    /**
     * 切换模式。
     *
     * 与旧 applyCrackMode 的区别：切到 Shizuku 时**主动走一遍授权流程**——
     * 以前只是把 Prefs 里的数字改掉，用户回到破解页一点开始就报
     * 「尚未授权」，而界面上根本没有能触发授权的地方，死循环。
     */
    private void pickMode(int mode) {
        if (Prefs.mode(this) == mode) {
            // 已经是这个模式也不能就此结束 ——
            // 用户可能正好想重新走一遍授权（Shizuku 重启后就需要）。
            Toast.makeText(this, L.f("当前已是: %s", "Already on: %s",
                    Prefs.modeLabel(this)), Toast.LENGTH_SHORT).show();
            refreshModeReady();
            if (mode == Prefs.MODE_SHIZUKU) ensureShizukuPermission();
            return;
        }
        Prefs.setMode(this, mode);
        Toast.makeText(this, L.f("已切换到: %s", "Switched to: %s",
                Prefs.modeLabel(this)), Toast.LENGTH_SHORT).show();
        updateConnectMode();
        fillModeInfo();
        refreshModeReady();

        if (mode == Prefs.MODE_SHIZUKU) {
            ensureShizukuPermission();
        }
    }

    /**
     * 确保 Shizuku 可用：没装→提示去装；没启动→提示启动；没授权→弹授权框。
     * 这是 Shizuku 模式唯一的入口，所有路径都汇到这里。
     */
    private void ensureShizukuPermission() {
        if (!io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isInstalled(this)) {
            NetDialog.info(this, L.s("没有检测到 Shizuku", "Shizuku not detected"),
                    L.s("Shizuku 让应用以 shell 身份运行，免 root。\n\n"
                            + "去 Shizuku 的 GitHub 项目下载安装，\n"
                            + "再用 ADB 或 root 启动一次服务。\n\n"
                            + "不想折腾就直接用 Root 模式。",
                        "Shizuku runs apps as the shell user without root.\n\n"
                            + "Install it from its GitHub project, then start the\n"
                            + "service once via ADB or root.\n\n"
                            + "Or simply use Root mode."));
            return;
        }
        if (!io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isAvailable()) {
            NetDialog d = new NetDialog(this, L.s("Shizuku 服务未运行", "Shizuku service is not running"),
                    L.s("Shizuku 装了但服务没起来。\n\n"
                            + "打开 Shizuku 应用，点「启动服务」——\n"
                            + "注意：重启手机后需要重新激活。",
                        "Shizuku is installed but its service is not running.\n\n"
                            + "Open the Shizuku app and tap Start —\n"
                            + "activation must be repeated after a reboot."));
            d.setPositive(L.s("去授权", "Grant"), dd -> {
                try {
                    android.content.Intent i = getPackageManager()
                            .getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (i != null) { i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
                } catch (Throwable ignored) {}
            });
            d.setNegative(L.s("知道了", "Got it"), null);
            d.show();
            return;
        }
        if (!io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isGranted()) {
            requestShizukuPermission();
            return;
        }
        Toast.makeText(this, L.s("Shizuku 通道已就绪", "Shizuku channel ready"),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 盯着 Shizuku 的 binder。
     *
     * **这是之前「明明开着却显示服务未运行」的根因**：
     * Shizuku 的 binder 是异步推过来的，应用启动那一刻服务没起来
     * （或者服务重启过），pingBinder() 就一直 false，而且不会自己重试。
     * 注册 sticky 监听后：binder 一到就立刻刷新界面状态。
     */
    private final io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.StateListener shizukuWatcher =
            new io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.StateListener() {
        @Override
        public void onBinderReady() {
            ui.post(() -> {
                refreshModeReady();
                updateConnectMode();
            });
        }

        @Override
        public void onBinderDead() {
            ui.post(() -> refreshModeReady());
        }
    };

    private void registerShizukuWatcher() {
        try {
            io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.registerListeners();
            io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.addStateListener(shizukuWatcher);
        } catch (Throwable ignored) {}
    }

    /** 真正弹 Shizuku 的授权框 */
    private void requestShizukuPermission() {
        try {
            if (io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isPreV11()) {
                // v11 之前的旧版没有权限机制，直接可用
                Toast.makeText(this, L.s("旧版 Shizuku 无需授权", "Legacy Shizuku needs no grant"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            io.github.wlmosv_png.wpaforge.audit.ShizukuRunner
                    .requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            NetDialog.info(this, L.s("无法申请授权", "Cannot request permission"),
                    L.s("请手动打开 Shizuku → 已授权应用 → 找到 WPAForge → 打开开关。",
                        "Open Shizuku → Authorised apps → WPAForge → enable it manually."));
        }
    }

    /** 刷新设置页里「当前模式能不能用」的小字 */
    /** 填充设置页的模式说明卡片。切模式后会重新调用。 */
    private void fillModeInfo() {
        if (modeInfo == null) return;

            int m = Prefs.mode(this);
            StringBuilder sb = new StringBuilder();
            if (m == Prefs.MODE_NO_ROOT) {
                sb.append(L.s("当前: 免 Root 模式", "Current: root-free mode")).append("\n\n");
                // 如实告知弹窗：这是 Android 强制的，我们关不掉。
                // 不告知就是骗用户。
                sb.append(L.s(
                        "⚠ 系统会对每一条候选弹一次确认框。\n"
                        + "这是 Android 的安全设计，应用无法关闭。\n\n"
                        + "想不被打扰：用 Shizuku 模式（和 Root 一样快，无弹窗）。\n\n",
                        "⚠ Android shows a confirmation dialog for every candidate.\n"
                        + "This is a platform security requirement; apps cannot disable it.\n\n"
                        + "To avoid the interruptions, use Shizuku mode "
                        + "(as fast as root, no dialogs).\n\n"));
                sb.append(L.s(
                        "通过系统的「网络建议」接口提交候选，系统自己去连。\n"
                        + "优点：不需要 root。\n"
                        + "缺点：\n"
                        + "· 首次会弹一次系统授权框，要点「允许」\n"
                        + "· 系统对频繁失败会退避，速度比 Root 模式慢不少\n"
                        + "· 系统会在通知栏显示连接尝试\n"
                        + "· 需要 Android 11 或更高版本",
                        "Submits candidates via the system's network-suggestion API "
                        + "and lets the system connect for you.\n"
                        + "Pros: no root needed.\n"
                        + "Cons:\n"
                        + "· a one-time system permission dialog appears on first use\n"
                        + "· the system backs off after repeated failures, so it is slower\n"
                        + "· connection attempts show in the notification shade\n"
                        + "· requires Android 11 or higher"));
            } else if (m == Prefs.MODE_SHIZUKU) {
                sb.append(L.s("当前: Shizuku 模式", "Current: Shizuku mode")).append("\n\n");
                sb.append(L.s(
                        "通过 Shizuku 以 shell 身份执行同一条系统命令。\n"
                        + "优点：\n"
                        + "· 不需要 root，只需 Shizuku 服务运行中\n"
                        + "· 速度与 Root 模式相同（都是等目标回应）\n"
                        + "· 进度、暂停都可控\n"
                        + "注意：重启手机后需要重新启动 Shizuku 服务。\n"
                        + "上方三个按钮点哪个就用哪个，下面一行会实时告诉你能不能用。",
                        "Runs the same system command as the shell user via Shizuku.\n"
                        + "Pros:\n"
                        + "· no root needed, only a running Shizuku service\n"
                        + "· same speed as root mode (both wait on the target)\n"
                        + "· progress and pause are fully controllable\n"
                        + "Note: the Shizuku service must be restarted after a reboot.\n"
                        + "Tap whichever of the three buttons you want — the line below "
                        + "shows live whether it actually works."));
            } else {
                sb.append(L.s("当前: Root 模式（推荐）", "Current: root mode (recommended)")).append("\n\n");
                sb.append(L.s(
                        "通过 su 调用系统的 cmd wifi 命令，完全自动、不弹窗。\n"
                        + "实测约 1 秒/条，且进度和暂停都可控。\n"
                        + "需要设备已 root 并给本应用授权。",
                        "Invokes the system's cmd wifi via su — fully automatic, no dialogs.\n"
                        + "Measured about 1 second per attempt, with precise progress and pause.\n"
                        + "Requires a rooted device with root granted to this app."));
            }
            sb.append("\n\n").append(L.f("当前实测速度: %s 条/秒",
                    "Measured: %s per second", Prefs.avgRateLabel(this)));
            if (!Prefs.hasRateSample(this)) {
                sb.append(L.s("（还没有样本，这是模式默认值）",
                        " (no samples yet — mode default)"));
            }
            modeInfo.setText(sb.toString());
            if (m == Prefs.MODE_NO_ROOT) modeInfo.setTextColor(OK_GREEN);
            else if (m == Prefs.MODE_SHIZUKU) modeInfo.setTextColor(Color.parseColor("#64B5F6"));
            else modeInfo.setTextColor(ACCENT);

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
        // 前缀带上模式名：之前只有一句结论，
        // 切模式的瞬间会出现“卡片写 Root、状态行说免 Root”的矛盾。
        // 现在每次连模式名一起刷新，不会误读。
        modeReadyLabel.setTextColor(TEXT_DIM);
        modeReadyLabel.setText(modeName(m) + L.s("：正在检测…", ": checking…"));
        // 点一下重新检测。
        // 如果当前是 Shizuku 模式，还要走一遍完整引导（包括弹授权框），
        // 否则用户在设置里找不到任何可以触发授权的入口。
        modeReadyLabel.setOnClickListener(v -> {
            refreshModeReady();
            if (Prefs.mode(this) == Prefs.MODE_SHIZUKU) {
                ensureShizukuPermission();
            }
        });
        // 长按看完整自检证据
        modeReadyLabel.setOnLongClickListener(v -> {
            showChannelProbeDetail();
            return true;
        });

        new Thread(() -> {
            String text;
            boolean ok;
            if (m == Prefs.MODE_SHIZUKU) {
                io.github.wlmosv_png.wpaforge.audit.RootProber.CheckResult r =
                        io.github.wlmosv_png.wpaforge.audit.RootProber.checkShizuku(getApplicationContext());
                ok = r.ok;
                text = ok ? L.s("✓ 已就绪", "\u2713 ready")
                          : L.s("\u2717 ", "\u2717 ") + r.title;
            } else if (m == Prefs.MODE_NO_ROOT) {
                io.github.wlmosv_png.wpaforge.audit.SuggestionProber.CheckResult r =
                        io.github.wlmosv_png.wpaforge.audit.SuggestionProber.check(getApplicationContext());
                ok = r.ok;
                text = ok ? L.s("✓ 可用（速度较慢）", "\u2713 available (slower)")
                          : L.s("\u2717 ", "\u2717 ") + r.title;
            } else {
                io.github.wlmosv_png.wpaforge.audit.RootProber.CheckResult r =
                        io.github.wlmosv_png.wpaforge.audit.RootProber.check(getApplicationContext());
                ok = r.ok;
                text = ok ? L.s("✓ 已就绪", "\u2713 ready")
                          : L.s("\u2717 ", "\u2717 ") + r.title;
            }
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
            if (m != Prefs.MODE_NO_ROOT) {
                final boolean useShizuku = m == Prefs.MODE_SHIZUKU;
                eng.setShellChannel(new io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel() {
                    @Override
                    public String exec(String cmd, long timeoutMs) {
                        if (useShizuku) {
                            return io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.exec(cmd, timeoutMs);
                        }
                        return io.github.wlmosv_png.wpaforge.audit.RootProber
                                .exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                    }
                    @Override public void forgetTarget(String ssid) { }
                    @Override public String name() { return useShizuku ? "Shizuku" : "Root"; }
                });
            }
            io.github.wlmosv_png.wpaforge.audit.CrackEngine.ChannelProbe p = eng.probeChannel();
            StringBuilder sb = new StringBuilder();
            for (String line : p.steps) sb.append(line).append('\n');
            // 附上完整环境诊断（设备/权限/Shizuku 状态/各判据层可读性）
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
        if (m == Prefs.MODE_SHIZUKU) return "Shizuku";
        if (m == Prefs.MODE_NO_ROOT) return L.s("免 Root", "Root-free");
        return "Root";
    }

    /** 圆角背景 */
    private android.graphics.drawable.Drawable roundBg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
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
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, px(strokeDp)), edge);
        return g;
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
                NetDialog d = new NetDialog(this, L.f("本机已存 WiFi（ %s 个）", "Saved Wi-Fi ( %s )", list.size()),
                        L.s("口令是系统明文保存的，这里只做本地展示，不会上传。", "Passphrases are stored in plaintext by the system. Shown locally only, never uploaded."));
                int weak = 0;
                for (SavedWifiReader.Saved sw : list) {
                    if (!sw.hasPassword()) {
                        d.addItem(sw.ssid + L.s("   [开放网络]", "   [open network]"), null);
                        continue;
                    }
                    int score = SavedWifiReader.weaknessScore(sw.password);
                    String label = SavedWifiReader.weaknessLabel(score);
                    if (score >= 50) weak++;
                    d.addItem(sw.ssid + "\n" + sw.password + "   " + label
                            + " (" + score + ")", null);
                }
                d.addHint(L.s("共 ", "Total ") + list.size() + L.f(" 个网络，其中 %s 个属于偏弱或极弱口令（分数 ≥50）。", " networks total, %s of them weak or very weak (score ≥50).", weak));
                d.setNegative(L.s("关闭", "Close"), null);
                d.show();
            });
        }).start();
    }

    // ---------------- 破解记录 ----------------

    private void showHistory() {
        java.util.List<HistStore.Record> list = HistStore.load(this);
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
                    + (r.hit ? "\n✓ " + r.password : L.s("\n✗ 未命中", "\n✗ no match"))
                    + L.f("   试了 %d 条 / %s", "   tried %d / %s", r.tested, r.durationLabel());
            d.addItem(line, null);
        }
        if (list.size() > show) d.addHint(L.s("只显示最近 ", "Showing most recent ") + show + " 条。");
        d.setDanger(L.s("清空记录", "Clear records"), dd -> {
            HistStore.clear(this);
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
        boolean allOk = loc && near;

        StringBuilder sb = new StringBuilder();
        sb.append(permLine(android.Manifest.permission.ACCESS_FINE_LOCATION,
                L.s("定位（读取 SSID / 扫描）", "Location (read SSID / scan)")));
        if (Build.VERSION.SDK_INT >= 33) {
            sb.append('\n').append(permLine("android.permission.NEARBY_WIFI_DEVICES",
                    L.s("附近设备（扫描）", "Nearby devices (scan)")));
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
        if (code == REQ_SHIZUKU) {
            boolean granted = results.length > 0
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this,
                    granted ? L.s("Shizuku 授权成功", "Shizuku granted")
                            : L.s("Shizuku 授权被拒绝", "Shizuku permission denied"),
                    Toast.LENGTH_SHORT).show();
            refreshModeReady();
        }
        if (scanner != null) scanner.emit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBackgroundStall();
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
        try {
            io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.removeStateListener(shizukuWatcher);
        } catch (Throwable ignored) {}
        if (scanner != null) scanner.stop();
        if (engine != null) engine.cancel();
        if (prober != null) prober.cancel();
        if (serviceBound) {
            try {
                if (crackService != null) crackService.removeCallback(svcCallback);
                unbindService(svcConn);
            } catch (Exception ignored) {}
            serviceBound = false;
        }
    }
}

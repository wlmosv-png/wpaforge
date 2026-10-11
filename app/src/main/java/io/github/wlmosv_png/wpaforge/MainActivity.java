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
import io.github.wlmosv_png.wpaforge.scan.Scanner;
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
    private List<NetRecord> lastRecords = new ArrayList<>();

    // 审计页组件
    private TextView dictStatus;
    /** 设置页的模式说明卡片（切模式后要刷新） */
    private TextView modeInfo;
    /** 设置页里的“当前模式能不能用”小字 */
    private TextView modeReadyLabel;
    private TextView auditLog;
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
    /** 是否自动跟随到底部（用户上滑后关闭） */
    private boolean streamAutoScroll = true;
    private LinearLayout.LayoutParams streamParams;
    private boolean streamCollapsed = false;
    private ConnectionProber prober;
    private RootProber rootProber;
    private volatile boolean rootProberRunning = false;
    private NetRecord targetNet;
    private Thread connectThread;

    // 服务绑定
    private CrackService crackService;
    private boolean serviceBound = false;
    private final java.util.List<TaskQueue.Task> queueCache = new java.util.ArrayList<>();
    /** 当前用作字典的文件路径（空表示用内置生成器） */
    private String currentDictPath = "";
    private LinearLayout queueBox;

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
            tabs[i].setBackground(roundBg(on ? ACCENT : chipOff, 18));
            tabs[i].setTextColor(on ? onText : TEXT_DIM);
            tabs[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
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
                connectStreamView.setText(streamBuffer.toString().trim());
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
            FILTER_OPEN = 2, FILTER_HIDDEN = 3;
    private int netFilter = FILTER_ALL;
    private LinearLayout filterRow;

    private TextView filterChip(String label, int mode) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(TEXT_DIM);
        t.setPadding(px(14), px(8), px(14), px(8));
        t.setBackground(roundBg(Color.parseColor("#1A1A22"), 16));
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
        for (NetRecord r : visible) {
            netList.addView(netRow(r));
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
        detail.setText(r.bssid + "  ·  ch" + r.channel
                + (r.is5Ghz ? " (5G)" : " (2.4G)")
                + "  ·  " + securityLabel(r.security)
                + (!r.isHidden() && r.estimatedDistanceMeters() > 0
                    ? "  ·  ≈" + r.estimatedDistanceMeters() + "m" : ""));
        detail.setTextSize(10);
        detail.setTextColor(TEXT_DIM);
        left.addView(detail);

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
            case WPA3: return "WPA3";
            case WPA2_WPA3: return "WPA2/3";
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

        Button manage = makeButton(L.s("打开字典管理", "Manage dictionaries"), ACCENT);
        manage.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, DictActivity.class)));
        pageAudit.addView(manage);

        Button load = makeButton(L.s("加载字典文件", "Load dictionary file"), Color.parseColor("#4CAF50"));
        load.setOnClickListener(v -> showDictPicker());
        pageAudit.addView(load);

        Button gen = makeButton(L.s("内置生成器（快速）", "Built-in generator (quick)"), Color.parseColor("#5A5A66"));
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
                    appendLog(L.s("构成: ", "Breakdown: ") + g.stats());
                });
            }).start();
        });
        pageAudit.addView(gen);

        pageAudit.addView(sectionLabel(L.s("口令强度检查", "Passphrase strength check")));

        EditText input = new EditText(this);
        input.setHint(L.s("输入一个口令，看它在字典里的位置", "Enter a passphrase to see its position in the dictionary"));
        input.setTextColor(TEXT);
        input.setHintTextColor(TEXT_DIM);
        input.setTextSize(13);
        input.setBackground(roundBg(SURFACE, 16));
        input.setPadding(px(16), px(14), px(16), px(14));
        pageAudit.addView(input);

        Button check = makeButton("评估", ACCENT);
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

    private void updateDictStatus() {
        if (dictStatus == null) return;
        int n = currentDict == null ? 0 : currentDict.size();
        dictStatus.setText(L.s("当前字典: ", "Current dictionary: ") + n + L.s(" 条", " entries") + (n == 0 ? L.s("（未选择）", "(none selected)") : ""));
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
        NetDialog dlg = new NetDialog(this, "选择字典",
                L.s("共 ", "Total ") + entries.size() + " 个。规则字典按规则实时展开，不占空间。");
        for (int i = 0; i < entries.size(); i++) {
            final io.github.wlmosv_png.wpaforge.audit.DictStore.Entry e = entries.get(i);
            String tag = e.isRule ? L.s("规则", "Rule") : L.s("普通", "Plain");
            dlg.addItem(e.name + "   " + e.sizeLabel() + " · " + tag, d -> loadDictFile(e));
        }
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.show();
    }

    private void loadDictFile(io.github.wlmosv_png.wpaforge.audit.DictStore.Entry e) {
        appendLog(L.f("正在读取 %s ...", "Reading %s ...", e.name));
        new Thread(() -> {
            java.util.List<Candidate> list = io.github.wlmosv_png.wpaforge.audit.DictStore.load(e, 200000);
            ui.post(() -> {
                currentDict = list;
                currentDictName = e.name;
                currentDictPath = e.file.getAbsolutePath();
                updateDictStatus();
                appendLog("已加载 " + e.name + " -> " + list.size() + L.s(" 条", " entries"));
                if (list.size() > 300) {
                    appendLog(L.f("提示: 约 %s 跑完（实测约 1 秒/条）",
                        "About %s to finish (~1s per attempt)", humanTime(list.size())));
                }
            });
        }).start();
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
        connectDictView.setOnClickListener(v -> showDictPicker());
        head.addView(connectDictView);

        head.addView(divider());

        // 行三：执行方式
        connectModeView = new TextView(this);
        connectModeView.setTextSize(12);
        connectModeView.setTextColor(TEXT_DIM);
        connectModeView.setPadding(0, px(9), 0, 0);
        connectModeView.setLineSpacing(px(3), 1f);
        connectModeView.setOnClickListener(v -> showModePicker());
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
        flowLabel.setText(L.s("候选流", "CANDIDATES"));
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
            streamBuffer.setLength(0);
            if (connectStreamView != null) connectStreamView.setText("");
            updateFlowHeader();
            updateEmptyHint();
            Toast.makeText(this, L.s("日志已清空", "Log cleared"),
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
        connectStreamView.setTextIsSelectable(true);
        connectStreamView.setLineSpacing(px(4), 1f);
        connectStreamView.setHorizontallyScrolling(false);

        // 日志区必须可滚动。
        // 之前是一个裸 TextView 放在固定高度里 —— 内容一多，后面的就看不到了，
        // 而且新内容进来不会自动跟随，用户得自己猜"是不是卡住了"。
        streamScroll = new android.widget.ScrollView(this);
        streamScroll.setBackground(roundBg(Color.parseColor("#14141A"), 16));
        streamScroll.setFillViewport(true);
        // 不要自己 new LayoutParams —— ScrollView 内部按自己的规则处理子 View，
        // 传错类型会 ClassCastException（LinearLayout$LayoutParams → FrameLayout）。
        // 直接让它自己生成即可。
        streamScroll.addView(connectStreamView);

        // 用户自己上滑查看历史时暂停自动跟随，
        // 否则新日志一来就把他拽回底部，历史根本看不了。
        streamScroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                streamAutoScroll = false;
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                android.view.View child = streamScroll.getChildAt(0);
                if (child != null
                        && streamScroll.getScrollY() + streamScroll.getHeight()
                           >= child.getMeasuredHeight() - px(24)) {
                    streamAutoScroll = true;
                }
            }
            return false;
        });

        streamParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(240));
        pageConnect.addView(streamScroll, streamParams);

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
            appendStreamColored(L.s("▶ 继续执行…", "▶ Resuming…"), OK_GREEN);
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
        appendStreamColored(L.s("■ 正在停止…", "■ Stopping…"), DANGER);
        refreshQueue();
    }

    /** 执行方式选择 */
    private void showModePicker() {
        NetDialog d = new NetDialog(this, L.s("执行方式", "Execution mode"),
                L.s("三种方式跑的是同一条系统命令，区别只在拿权限的途径。",
                        "All three run the same system command — only the way of obtaining permission differs."));

        int cur = Prefs.mode(this);

        d.addItem(L.s("Root 模式", "Root mode")
                        + "\n" + L.s("su 执行 · 约 1 秒/条 · 需要 root",
                                "via su · ~1s each · requires root")
                        + (cur == Prefs.MODE_ROOT ? "\n✓ " + L.s("当前", "current") : ""),
                dd -> pickMode(Prefs.MODE_ROOT));

        d.addItem(L.s("Shizuku 模式", "Shizuku mode")
                        + "\n" + L.s("shell 身份执行 · 同样约 1 秒/条 · 不需要 root",
                                "as shell user · also ~1s each · no root needed")
                        + (cur == Prefs.MODE_SHIZUKU ? "\n✓ " + L.s("当前", "current") : ""),
                dd -> pickMode(Prefs.MODE_SHIZUKU));

        d.addItem(L.s("普通模式", "Normal mode")
                        + "\n" + L.s("系统接口 · 每条需手动确认 · 很慢",
                                "system API · manual confirmation per attempt · slow")
                        + (cur == Prefs.MODE_NO_ROOT ? "\n✓ " + L.s("当前", "current") : ""),
                dd -> pickMode(Prefs.MODE_NO_ROOT));

        d.addHint(L.s("Shizuku 是目前免 root 的最佳方案：\n"
                        + "先在 Shizuku 应用里激活一次（用 ADB 或 root），\n"
                        + "然后回到这里授权即可。重启手机后需要重新激活。",
                "Shizuku is the best root-free option:\n"
                        + "activate it once in the Shizuku app (via ADB or root),\n"
                        + "then grant permission here. Re-activation is needed after a reboot."));
        d.setNegative(L.s("取消", "Cancel"), null);
        d.show();
    }

    /** 空状态提示 */
    private void updateEmptyHint() {
        if (connectEmptyHint == null) return;
        boolean empty = streamBuffer.length() == 0;
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
    private void updateConnectMode() {
        if (connectModeView == null) return;
        int mode = Prefs.mode(this);
        String label;
        int color;
        if (mode == Prefs.MODE_SHIZUKU) {
            label = L.s("Shizuku", "Shizuku");
            color = Color.parseColor("#64B5F6");
        } else if (mode == Prefs.MODE_NO_ROOT) {
            label = L.s("普通（手动确认）", "Normal (manual)");
            color = AMBER;
        } else {
            label = L.s("Root", "Root");
            color = ACCENT;
        }
        connectModeView.setText(L.f("执行方式: %s   ·   实测 %s 条/秒   ·   点此更改",
                "Mode: %s   ·   %s/s   ·   tap to change",
                label, Prefs.avgRateLabel(this)));
        connectModeView.setTextColor(color);
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
            ui.post(() -> {
                if (connectProgress == null) return;
                connectProgress.setVisibility(View.VISIBLE);
                java.util.List<io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage> st =
                        new java.util.ArrayList<>();
                float outer = total > 0 ? (index - 1f) / total : 0f;
                st.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        outer, L.f("整个字典 %.0f%%", "whole %.0f%%", outer * 100)));
                st.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(
                        outer, L.f("当前位置 %d / %d", "at %d / %d", index, total)));
                st.add(new io.github.wlmosv_png.wpaforge.ui.StagedProgress.Stage(outer, ""));
                connectProgress.setStages(st);
                connectProgress.setDone(false);
                connectProgress.setText(
                        L.f("正在尝试 %d / %d", "Trying %d / %d", index, total),
                        L.f("当前候选: %s", "Current: %s", candidate));
            });
        }

        @Override
        public void onAttemptResult(String candidate, boolean hit, long ms, String detail) {
            ui.post(() -> appendStreamColored(
                    (hit ? "✓ 成功" : "✗") + "  " + candidate
                            + "  (" + ms + "ms " + detail + ")",
                    hit ? OK_GREEN : TEXT_DIM));
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
                    appendStreamColored(L.s("★ 命中！", "★ HIT!") + task.ssid + " = " + hitPassword, OK_GREEN);
                    Toast.makeText(MainActivity.this,
                            L.s("破解成功: ", "Cracked: ") + task.ssid, Toast.LENGTH_LONG).show();
                    // 记一笔历史
                    HistStore.append(MainActivity.this, new HistStore.Record(
                            System.currentTimeMillis(), task.ssid, task.bssid,
                            task.dictName, task.dictSize, task.testedThisRun,
                            task.elapsedMs, true, hitPassword));
                } else if ("PAUSED".equals(outcome)) {
                    appendStreamColored(L.s("已暂停（进度 ", "Paused (progress ") + task.offset + "/" + task.dictSize
                            + L.s("，可继续）", ", resumable)"), ACCENT);
                } else if ("STOPPED".equals(outcome)) {
                    appendStreamColored(L.s("已停止（进度 ", "Stopped (progress ") + task.offset + "/" + task.dictSize + "）", DANGER);
                    restoreWifiAfterRun();
                } else {
                    appendStream(L.s("任务结束: ", "Task finished: ") + task.ssid + " 未命中");
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
    private final StringBuilder streamBuffer = new StringBuilder();

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
        NetDialog dlg = new NetDialog(this, L.s("选择目标网络", "Select target network"),
                L.s("共 ", "Total ") + selectable.size() + " 个加密网络，按信号强弱排列。");
        for (int i = 0; i < selectable.size(); i++) {
            final NetRecord nr = selectable.get(i);
            String secl = securityLabel(nr.security);
            dlg.addItem(nr.displayName() + "   " + nr.rssi + "dBm · " + secl, d -> {
                targetNet = nr;
                updateTargetLabel();
            });
        }
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.show();
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

    /** 破解结束后把 WiFi 连回原本的网络 */
    private void restoreWifiAfterRun() {
        if (wifiBeforeRun.isEmpty()) return;
        if (targetNet != null && wifiBeforeRun.equals(targetNet.ssid)) return; // 本来就是它，不用恢复
        final String want = wifiBeforeRun;
        new Thread(() -> {
            try {
                // 目标网络的残留配置先清掉，避免它继续抢占
                if (targetNet != null) {
                    io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                            new String[]{"su", "-c", "cmd wifi list-networks"}, 6000);
                }
                io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this, new String[]{"su", "-c",
                        "cmd wifi connect-network " + shellQuote(want) + " wpa2"}, 8000);
                ui.post(() -> appendStream(L.s("已尝试连回原网络: ", "Reconnected to previous network: ") + want));
            } catch (Exception ignored) {}
        }).start();
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
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
            streamParams.height = px(108);
            streamParams.weight = 0f;
            connectStreamView.setMaxLines(5);
            connectStreamView.setEllipsize(TextUtils.TruncateAt.END);
        } else {
            streamParams.height = px(240);
            streamParams.weight = 0f;
            connectStreamView.setMaxLines(Integer.MAX_VALUE);
            connectStreamView.setEllipsize(null);
        }
        // 高度加在滚动容器上。
        // 之前这里对 connectStreamView 调 setLayoutParams ——
        // 那是 ScrollView 的子 View，拿到 LinearLayout 的 params 会 ClassCastException。
        if (streamScroll != null) streamScroll.setLayoutParams(streamParams);
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

    private void appendStreamColored(String line, int color) {
        if (line == null) return;
        streamBuffer.append(line).append('\n');
        // 只保留最近 200 行，避免无限增长
        int lines = 0;
        for (int i = 0; i < streamBuffer.length(); i++) {
            if (streamBuffer.charAt(i) == '\n') lines++;
        }
        if (lines > 200) {
            int cut = streamBuffer.indexOf("\n", streamBuffer.length() / 2);
            if (cut > 0) streamBuffer.delete(0, cut + 1);
        }
        if (connectStreamView != null) {
            connectStreamView.setText(streamBuffer.toString().trim());
            connectStreamView.setTextColor(color);
            updateFlowHeader();
            if (streamAutoScroll && streamScroll != null) {
                streamScroll.post(() -> streamScroll.fullScroll(android.view.View.FOCUS_DOWN));
            }
            // 注意：不要在这里做 fullScroll，那会把整个页面的滚动位置带跑，
            // 表现就是"切个页签回来内容全空了"。候选流本身是自适应的，
            // 让用户自己滑。
        }
    }

    // ---------------- 设置页 ----------------

    private void buildSettingsTab() {
        if (radar != null) radar.stopSpin();

pageSettings.addView(sectionLabel(L.s("破解模式", "Crack mode")));

        modeInfo = new TextView(this);
        modeInfo.setTextSize(12);
        modeInfo.setLineSpacing(px(4), 1f);
        modeInfo.setBackground(roundBg(SURFACE, 18));
        modeInfo.setPadding(px(18), px(16), px(18), px(16));
        pageSettings.addView(modeInfo);

        // 三个模式全部给出来。之前这里只有 Root 和免 Root
        // 两个按钮，Shizuku 只能从破解页的“执行方式”里换，
        // 导致用户在设置里根本找不到第三个模式。
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, px(10), 0, 0);
        Button rootMode = secondaryButton(L.s("Root 模式", "Root mode"), ACCENT);
        rootMode.setOnClickListener(v -> pickMode(Prefs.MODE_ROOT));
        modeRow.addView(rootMode, thirdColParams(8));
        Button shizukuMode = secondaryButton(L.s("Shizuku", "Shizuku"), Color.parseColor("#64B5F6"));
        shizukuMode.setOnClickListener(v -> pickMode(Prefs.MODE_SHIZUKU));
        modeRow.addView(shizukuMode, thirdColParams(8));
        Button noRootMode = secondaryButton(L.s("免 Root", "Root-free"), OK_GREEN);
        noRootMode.setOnClickListener(v -> pickMode(Prefs.MODE_NO_ROOT));
        modeRow.addView(noRootMode, thirdColParams(0));
        pageSettings.addView(modeRow);

        // 当前模式的实际可用性（实时检测）：一眼看到能不能用
        modeReadyLabel = new TextView(this);
        modeReadyLabel.setTextSize(11.5f);
        modeReadyLabel.setTextColor(TEXT_DIM);
        modeReadyLabel.setPadding(px(4), px(8), 0, 0);
        pageSettings.addView(modeReadyLabel);
        refreshModeReady();

        fillModeInfo();

        pageSettings.addView(sectionLabel(L.s("语言", "Language")));

        languageBtn = makeButton(L.f("语言: %s", "Language: %s",
                L.modeLabel(this)), Color.parseColor("#2A2A36"));
        languageBtn.setOnClickListener(v -> showLanguagePicker());
        pageSettings.addView(languageBtn);

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

        Button toAuthor = makeButton(L.s("关于我 / 我的其它项目", "About me / my other projects"), ACCENT);
        toAuthor.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AboutActivity.class)));
        pageSettings.addView(toAuthor);

        pageSettings.addView(sectionLabel(L.s("工具", "Tools")));

        Button saved = makeButton(L.s("本机已存 WiFi 自查", "Inspect saved Wi-Fi"), Color.parseColor("#4CAF50"));
        saved.setOnClickListener(v -> showSavedWifi());
        pageSettings.addView(saved);

        Button hist = makeButton(L.s("破解记录", "Crack history"), ACCENT);
        hist.setOnClickListener(v -> showHistory());
        pageSettings.addView(hist);

        Button exp = makeButton(L.s("导出记录", "Export records"), Color.parseColor("#5A5A66"));
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
        if (currentTab == 0) {
            // 从后台回来要把雷达动画接上，否则会停在最后一帧
            if (radar != null) radar.startSpin();
            if (scanner != null) scanner.start();
        }
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

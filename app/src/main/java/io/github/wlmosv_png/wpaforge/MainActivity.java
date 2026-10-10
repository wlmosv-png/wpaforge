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
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;
import io.github.wlmosv_png.wpaforge.scan.ChannelAnalyzer;
import io.github.wlmosv_png.wpaforge.scan.Scanner;
import io.github.wlmosv_png.wpaforge.ui.RadarSweep;
import io.github.wlmosv_png.wpaforge.ui.AuthorCard;
import io.github.wlmosv_png.wpaforge.ui.NetDialog;
import io.github.wlmosv_png.wpaforge.ui.SignalBars;
import io.github.wlmosv_png.wpaforge.ui.ThinProgress;

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
    private LinearLayout content;
    private TextView tabObserve, tabAudit, tabConnect, tabSettings;

    private Scanner scanner;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 观测页组件
    private RadarSweep radar;
    private SignalBars bars;
    private LinearLayout netList;
    private TextView observeNotice;
    private List<NetRecord> lastRecords = new ArrayList<>();

    // 审计页组件
    private TextView dictStatus;
    private TextView auditLog;
    private List<Candidate> currentDict;
    private String currentDictName = "";
    private AuditEngine engine;

    // 试连页组件
    private Button languageBtn;
    private TextView connectDictView;
    private TextView smartToggle;
    private TextView targetDictToggle;
    private boolean smartSortEnabled = true;
    private boolean targetDictEnabled = true;
    private Button startBtn;
    private Button stopBtn;
    private ThinProgress connectProgress;
    private TextView connectTargetView;
    private TextView connectStatsView;
    private TextView connectStreamView;
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
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(16), px(8), px(16), px(24));
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showTab(0);
    }

    /** 统一控制"运行中"状态：运行中才能停止，空闲时才能开始 */
    private void setRunning(boolean running) {
        if (startBtn != null) {
            startBtn.setEnabled(!running);
            startBtn.setAlpha(running ? 0.45f : 1f);
        }
        if (stopBtn != null) {
            stopBtn.setEnabled(running);
            stopBtn.setAlpha(running ? 1f : 0.45f);
        }
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
        tabObserve.setTextColor(index == 0 ? ACCENT : TEXT_DIM);
        tabAudit.setTextColor(index == 1 ? ACCENT : TEXT_DIM);
        tabConnect.setTextColor(index == 2 ? ACCENT : TEXT_DIM);
        tabSettings.setTextColor(index == 3 ? ACCENT : TEXT_DIM);
        tabObserve.setTypeface(index == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabAudit.setTypeface(index == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabConnect.setTypeface(index == 2 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabSettings.setTypeface(index == 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        int chipOff = Color.parseColor("#1A1A22");
        int onText  = Color.parseColor("#14141A");
        tabObserve.setBackground(roundBg(index == 0 ? ACCENT : chipOff, 18));
        tabAudit.setBackground(roundBg(index == 1 ? ACCENT : chipOff, 18));
        tabConnect.setBackground(roundBg(index == 2 ? ACCENT : chipOff, 18));
        tabSettings.setBackground(roundBg(index == 3 ? ACCENT : chipOff, 18));
        tabObserve.setTextColor(index == 0 ? onText : TEXT_DIM);
        tabAudit.setTextColor(index == 1 ? onText : TEXT_DIM);
        tabConnect.setTextColor(index == 2 ? onText : TEXT_DIM);
        tabSettings.setTextColor(index == 3 ? onText : TEXT_DIM);

        content.removeAllViews();
        if (index == 0) buildObserveTab();
        else if (index == 1) buildAuditTab();
        else if (index == 2) buildConnectTab();
        else buildSettingsTab();

        if (index == 0 && scanner != null) {
            // buildObserveTab 里会重建 radar 实例，这里必须对新实例启动
            if (radar != null) radar.startSpin();
            scanner.start();
        } else if (scanner != null) {
            if (radar != null) radar.stopSpin();
            scanner.stop();
        }
    }

    // ---------------- 观测页 ----------------

    private void buildObserveTab() {
        radar = new RadarSweep(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(240));
        rp.bottomMargin = px(12);
        content.addView(radar, rp);
        radar.setCenterText(L.s("扫描中", "Scanning"), L.s("正在探测周边网络", "Probing nearby networks"));

        observeNotice = new TextView(this);
        observeNotice.setTextSize(12);
        observeNotice.setTextColor(DANGER);
        observeNotice.setPadding(0, 0, 0, px(8));
        observeNotice.setVisibility(View.GONE);
        content.addView(observeNotice);

        content.addView(sectionLabel(L.s("信号分布", "Signal distribution")));

        bars = new SignalBars(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(150));
        bp.bottomMargin = px(16);
        content.addView(bars, bp);

        content.addView(sectionLabel(L.s("发现网络", "Networks found")));

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
        content.addView(filterBar);

        netList = new LinearLayout(this);
        netList.setOrientation(LinearLayout.VERTICAL);
        content.addView(netList);

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
                    ? L.s("还没扫描到网络。请确认 WiFi 已开启、权限已授予。", "No networks found. Check that Wi-Fi is on and permissions are granted.")
                    : L.s("当前筛选下没有匹配的网络。", "No networks match the current filter."));
            empty.setTextSize(12.5f);
            empty.setTextColor(TEXT_DIM);
            empty.setBackground(roundBg(SURFACE, 16));
            empty.setPadding(px(16), px(18), px(16), px(18));
            netList.addView(empty);
        }

        // 信道建议
        ChannelAnalyzer.Report rep = ChannelAnalyzer.analyze(records);
        if (rep.recommended24 > 0 || rep.recommended5 > 0) {
            TextView tip = new TextView(this);
            StringBuilder sb = new StringBuilder(L.s("信道建议  ", "Channel suggestion  "));
            if (rep.recommended24 > 0) sb.append("2.4G→ch").append(rep.recommended24).append("  ");
            if (rep.recommended5 > 0) sb.append("5G→ch").append(rep.recommended5);
            tip.setText(sb.toString());
            tip.setTextSize(12);
            tip.setTextColor(OK_GREEN);
            tip.setPadding(0, px(12), 0, 0);
            netList.addView(tip);
        }
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

        content.addView(sectionLabel(L.s("字典", "Dictionary")));

        dictStatus = new TextView(this);
        dictStatus.setTextSize(13);
        dictStatus.setTextColor(TEXT);
        dictStatus.setPadding(0, 0, 0, px(10));
        updateDictStatus();
        content.addView(dictStatus);

        Button manage = makeButton(L.s("打开字典管理", "Manage dictionaries"), ACCENT);
        manage.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, DictActivity.class)));
        content.addView(manage);

        Button load = makeButton(L.s("加载字典文件", "Load dictionary file"), Color.parseColor("#4CAF50"));
        load.setOnClickListener(v -> showDictPicker());
        content.addView(load);

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
        content.addView(gen);

        content.addView(sectionLabel(L.s("口令强度检查", "Passphrase strength check")));

        EditText input = new EditText(this);
        input.setHint(L.s("输入一个口令，看它在字典里的位置", "Enter a passphrase to see its position in the dictionary"));
        input.setTextColor(TEXT);
        input.setHintTextColor(TEXT_DIM);
        input.setTextSize(13);
        input.setBackground(roundBg(SURFACE, 16));
        input.setPadding(px(16), px(14), px(16), px(14));
        content.addView(input);

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
        content.addView(check);

        content.addView(sectionLabel(L.s("日志", "Log")));

        auditLog = new TextView(this);
        auditLog.setTextSize(11);
        auditLog.setTextColor(TEXT_DIM);
        auditLog.setTypeface(Typeface.MONOSPACE);
        auditLog.setBackground(roundBg(SURFACE, 16));
        auditLog.setPadding(px(16), px(16), px(16), px(16));
        auditLog.setMinLines(6);
        content.addView(auditLog);
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
                        io.github.wlmosv_png.wpaforge.audit.DictStore.defaultDir());
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

    private void buildConnectTab() {
        if (radar != null) radar.stopSpin();

        content.addView(sectionLabel(L.s("目标网络", "Target network")));

        connectTargetView = new TextView(this);
        connectTargetView.setTextSize(13);
        connectTargetView.setTextColor(TEXT);
        connectTargetView.setBackground(roundBg(SURFACE, 18));
        connectTargetView.setPadding(px(18), px(16), px(18), px(16));
        content.addView(connectTargetView);

        Button pick = makeButton(L.s("选择目标网络", "Select target network"), ACCENT);
        pick.setOnClickListener(v -> showTargetPicker());
        content.addView(pick);

        connectDictView = new TextView(this);
        connectDictView.setTextSize(12);
        connectDictView.setTextColor(TEXT_DIM);
        connectDictView.setBackground(roundBg(SURFACE, 16));
        connectDictView.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = px(10);
        content.addView(connectDictView, dlp);
        updateConnectDict();

        smartToggle = new TextView(this);
        smartToggle.setTextSize(12);
        smartToggle.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = px(10);
        smartToggle.setOnClickListener(v -> {
            smartSortEnabled = !smartSortEnabled;
            updateSmartToggle();
        });
        content.addView(smartToggle, slp);
        updateSmartToggle();

        targetDictToggle = new TextView(this);
        targetDictToggle.setTextSize(12);
        targetDictToggle.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = px(10);
        targetDictToggle.setOnClickListener(v -> {
            targetDictEnabled = !targetDictEnabled;
            updateTargetDictToggle();
        });
        content.addView(targetDictToggle, tlp);
        updateTargetDictToggle();

        content.addView(sectionLabel(L.s("进度", "Progress")));

        connectProgress = new ThinProgress(this);
        content.addView(connectProgress);

        connectStatsView = new TextView(this);
        connectStatsView.setTextSize(12);
        connectStatsView.setTextColor(TEXT_DIM);
        connectStatsView.setPadding(0, px(4), 0, px(12));
        content.addView(connectStatsView);

        startBtn = makeButton(L.s("加入队列并开始", "Add to queue and start"), ACCENT);
        startBtn.setOnClickListener(v -> startConnect());
        content.addView(startBtn);

        Button pauseBtn = makeButton(L.s("暂停（可续跑）", "Pause (resumable)"), Color.parseColor("#FFA726"));
        pauseBtn.setOnClickListener(v -> {
            if (crackService != null && crackService.isWorking()) {
                crackService.pause();
                appendStreamColored(L.s("正在暂停…（进度会保留）", "Pausing… (progress kept)"), ACCENT);
            } else {
                Toast.makeText(this, L.s("当前没有正在跑的任务", "No task is currently running"), Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(pauseBtn);

        stopBtn = makeButton("停止破解", DANGER);
        stopBtn.setOnClickListener(v -> {
            if (rootProber == null || !rootProberRunning) return;
            appendStreamColored(L.s("正在停止…", "Stopping…"), ACCENT);
            rootProber.cancel();
            stopBtn.setEnabled(false);
            stopBtn.setAlpha(0.45f);
        });
        content.addView(stopBtn);
        setRunning(false);

        content.addView(sectionLabel(L.s("任务队列", "Task queue")));

        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(queueBox);

        Button clearDone = makeButton(L.s("清理已完成任务", "Clear finished tasks"), Color.parseColor("#5A5A66"));
        clearDone.setOnClickListener(v -> {
            int n = TaskQueue.clearFinished(this);
            Toast.makeText(this, "清理了 " + n + L.s(" 条", " entries"), Toast.LENGTH_SHORT).show();
            refreshQueue();
        });
        content.addView(clearDone);

        content.addView(sectionLabel(L.s("候选流", "Candidate stream")));

        connectStreamView = new TextView(this);
        connectStreamView.setTextSize(11);
        connectStreamView.setTextColor(TEXT_DIM);
        connectStreamView.setTypeface(Typeface.MONOSPACE);
        connectStreamView.setBackground(roundBg(SURFACE, 16));
        connectStreamView.setPadding(px(16), px(16), px(16), px(16));
        connectStreamView.setMinLines(12);
        connectStreamView.setTextIsSelectable(true);
        content.addView(connectStreamView);

        TextView warn = new TextView(this);
        warn.setText("速度说明：每条需等系统认证结果，约 3–12 秒。"
                + "手机一次只能连一个网络，无法并行，所以再优化也有这个上限。"
                + "首次使用请先在 Magisk 里给本应用授予 root。");
        warn.setTextSize(10);
        warn.setTextColor(TEXT_DIM);
        warn.setPadding(0, px(12), 0, 0);
        content.addView(warn);

        updateTargetLabel();
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

    /** 破解页显示当前选中的字典 */
    private void updateConnectDict() {
        if (connectDictView == null) return;
        if (currentDict == null || currentDict.isEmpty()) {
            connectDictView.setText(L.s("字典: 未选择 —— 请先去「审计」页加载或生成", "Dictionary: none — load or generate one in the Audit tab"));
            connectDictView.setTextColor(TEXT_DIM);
        } else {
            connectDictView.setText(L.s("字典: ", "Dictionary: ") + currentDictName
                    + "（" + currentDict.size() + L.s(" 条）", " entries)"));
            connectDictView.setTextColor(OK_GREEN);
        }
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
                connectProgress.setProgress(total > 0 ? (index - 1f) / total : 0f);
                connectProgress.setText(L.f("正在尝试 %d / %d", "Trying %d / %d", index, total), "");
                connectProgress.setSubText(L.f("当前候选: %s", "Current: %s", candidate));
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
                connectProgress.setProgress(total > 0 ? (float) tested / total : 0f);
                String eta = etaMs < 0 ? "计算中" : formatDuration(etaMs);
                connectProgress.setText(L.f("已试 %d / %d", "Tried %d / %d", tested, total), "");
                connectProgress.setSubText(L.f("速率 %.2f 条/秒   预计剩余 %s",
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
                refreshQueue();
            });
        }

        @Override
        public void onLog(String line) {
            ui.post(() -> appendStream(line));
        }
    };

    /** 渲染队列 */
    private void renderQueue() {
        if (queueBox == null) return;
        queueBox.removeAllViews();

        if (queueCache.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(L.s("队列为空。选好目标和字典后点「加入队列并开始」。", "Queue is empty. Pick a target and dictionary, then tap “Add to queue and start”."));
            t.setTextSize(12);
            t.setTextColor(TEXT_DIM);
            t.setBackground(roundBg(SURFACE, 16));
            t.setPadding(px(16), px(16), px(16), px(16));
            queueBox.addView(t);
            return;
        }

        // 顶部汇总条：一眼看清有几条在跑、几条暂停、几条待办
        int running = 0, paused = 0, pending = 0, done = 0;
        for (TaskQueue.Task t : queueCache) {
            switch (t.state) {
                case RUNNING: running++; break;
                case PAUSED: paused++; break;
                case PENDING: pending++; break;
                case DONE_HIT: case DONE_MISS: done++; break;
                default: break;
            }
        }
        TextView summary = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (running > 0) sb.append(L.f("● 进行中 %d   ", "● Running %d   ", running));
        if (paused > 0) sb.append(L.f("‖ 已暂停 %d   ", "‖ Paused %d   ", paused));
        if (pending > 0) sb.append(L.f("○ 待办 %d   ", "○ Pending %d   ", pending));
        if (done > 0) sb.append(L.f("✓ 已完成 %d", "✓ Done %d", done));
        if (sb.length() == 0) sb.append(L.s("共 ", "Total ")).append(queueCache.size()).append(" 项");
        summary.setText(sb.toString().trim());
        summary.setTextSize(12);
        summary.setTextColor(running > 0 ? OK_GREEN : (paused > 0 ? AMBER : TEXT_DIM));
        summary.setBackground(roundBg(Color.parseColor("#1A1A22"), 16));
        summary.setPadding(px(16), px(12), px(16), px(12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = px(10);
        queueBox.addView(summary, slp);

        for (TaskQueue.Task task : queueCache) {
            queueBox.addView(taskCard(task));
        }
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
        meta.setText("字典 " + task.dictName + "（" + io.github.wlmosv_png.wpaforge.audit.DictRule.formatCount(task.dictSize) + "）"
                + "\n进度 " + task.offset + " / " + task.dictSize
                + (task.testedThisRun > 0
                    ? "   本轮试了 " + task.testedThisRun + L.s(" 条", " entries") : "")
                + hit);
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
            if (crackService != null) {
                queueCache.clear();
                queueCache.addAll(TaskQueue.load(this));
            } else {
                queueCache.clear();
                queueCache.addAll(TaskQueue.load(this));
            }
            renderQueue();
        });
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
            connectTargetView.setText(L.s("未选择目标 —— 请先在观测页扫描，再点上方按钮选择", "No target selected — scan in the Observe tab, then use the button above."));
            connectTargetView.setTextColor(TEXT_DIM);
        } else {
            connectTargetView.setText(targetNet.displayName() + "\n"
                    + targetNet.bssid + "  ·  ch" + targetNet.channel
                    + "  ·  " + targetNet.rssi + "dBm  ·  " + targetNet.security);
            connectTargetView.setTextColor(TEXT);
        }
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

        String issue = RootProber.checkRoot();
        if (!issue.isEmpty()) {
            connectStreamView.setText("");
            appendStreamColored(L.s("Root 模式不可用: ", "Root mode unavailable: ") + issue, DANGER);
            Toast.makeText(this, issue, Toast.LENGTH_LONG).show();
            return;
        }

        // 记下原网络，跑完连回去
        rememberCurrentWifi();

        // 入队。crackService 可能还没绑定好（bindService 是异步的），
        // 所以先直接写持久化队列，不依赖服务实例 —— 避免 NPE。
        final TaskQueue.Task t = TaskQueue.enqueue(this, targetNet,
                currentDictPath, currentDictName);
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

    private void appendStream(String line) {
        appendStreamColored(line, TEXT_DIM);
    }

    private void appendStreamColored(String line, int color) {
        if (connectStreamView == null) return;
        String cur = connectStreamView.getText().toString();
        String next = cur.isEmpty() ? line : cur + "\n" + line;
        String[] lines = next.split("\n");
        if (lines.length > 60) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 60; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            next = sb.toString().trim();
        }
        connectStreamView.setText(next);
        connectStreamView.setTextColor(color);
    }

    // ---------------- 设置页 ----------------

    private void buildSettingsTab() {
        if (radar != null) radar.stopSpin();

content.addView(sectionLabel(L.s("语言", "Language")));

        languageBtn = makeButton(L.f("语言: %s", "Language: %s",
                L.modeLabel(this)), Color.parseColor("#2A2A36"));
        languageBtn.setOnClickListener(v -> showLanguagePicker());
        content.addView(languageBtn);

                content.addView(sectionLabel(L.s("破解设置", "Crack settings")));

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
        content.addView(speedInfo);

        Button toAuthor = makeButton(L.s("关于我 / 我的其它项目", "About me / my other projects"), ACCENT);
        toAuthor.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AboutActivity.class)));
        content.addView(toAuthor);

        content.addView(sectionLabel(L.s("工具", "Tools")));

        Button saved = makeButton(L.s("本机已存 WiFi 自查", "Inspect saved Wi-Fi"), Color.parseColor("#4CAF50"));
        saved.setOnClickListener(v -> showSavedWifi());
        content.addView(saved);

        Button hist = makeButton(L.s("破解记录", "Crack history"), ACCENT);
        hist.setOnClickListener(v -> showHistory());
        content.addView(hist);

        Button exp = makeButton(L.s("导出记录", "Export records"), Color.parseColor("#5A5A66"));
        exp.setOnClickListener(v -> doExport());
        content.addView(exp);

        content.addView(sectionLabel(L.s("使用边界", "Scope of use")));

        content.addView(infoBlock(L.s("仅对自己有授权的网络使用", "Use only on networks you are authorised for"),
                L.s("破解会向目标 AP 发起真实的关联请求。对自有网络是自查，", "WPAForge issues real association requests. On your own network that is a self-audit;")
                + L.s("对未授权网络是违法行为，且路由器侧会留下失败记录。", "on an unauthorised network it is unlawful, and the AP will log the failures.")));

        content.addView(infoBlock(L.s("关于速度", "About speed"),
                L.s("逐条连接受目标响应速度限制，快的 AP 约 1 秒，慢的可能 5 秒以上。", "Each attempt waits on the target. A fast AP takes ~1s, a slow one 5s or more.")
                + L.s("字典越大耗时越久 —— 建议先用目标派生 + 智能排序，几百条往往比十万条有效。", "Bigger dictionaries take longer — target derivation plus smart ranking makes a few hundred entries beat a hundred thousand.")));

        content.addView(sectionLabel(L.s("版本", "Version")));
        TextView ver = new TextView(this);
        ver.setText("WPAForge 0.5.0 (5)");
        ver.setTextSize(12);
        ver.setTextColor(TEXT_DIM);
        content.addView(ver);
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

    private void showSavedWifi() {
        final NetDialog dlg = new NetDialog(this, L.s("本机已存 WiFi", "Saved Wi-Fi"),
                L.s("正在读取系统配置…", "Reading system configuration…"));
        dlg.setNegative(L.s("关闭", "Close"), null);
        dlg.show();

        new Thread(() -> {
            String issue = SavedWifiReader.checkAccess();
            if (!issue.isEmpty()) {
                ui.post(() -> dlg.dismiss());
                ui.post(() -> NetDialog.info(this, L.s("无法读取", "Cannot read"), issue
                        + "\n\n这项功能需要 root，用来读取系统保存的 WiFi 配置。"));
                return;
            }
            java.util.List<SavedWifiReader.Saved> list = SavedWifiReader.read();
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

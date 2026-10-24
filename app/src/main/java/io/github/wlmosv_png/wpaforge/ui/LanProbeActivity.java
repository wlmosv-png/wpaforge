package io.github.wlmosv_png.wpaforge.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.scan.Evidence;
import io.github.wlmosv_png.wpaforge.scan.LanProbe;
import io.github.wlmosv_png.wpaforge.scan.LanProbeExecutor;
import io.github.wlmosv_png.wpaforge.scan.ProbeHistory;

/**
 * 网络侦察页（v2：卡片 + 终端双区）。
 *
 * <h3>为什么改版</h3>
 * v1 是纯文本终端，信息量够但"不好看"，且一次扫描后结果留不住。
 * v2 拆成两块：
 *
 *   上：**结果卡片区**（滚动）
 *       · 概览卡：存活 N 台 / 新增 M 台 / 来自哪个网段
 *       · 设备卡：每台一张，IP + MAC + 厂商徽标 + 端口摘要
 *       · 新出现的设备**红色高亮 + NEW 角标**，一眼看到陌生人
 *   下：**终端日志区**（可折叠）
 *       · 保留 v1 的终端观感，扫描过程逐行滚出
 *       · 默认折叠成几行，点开看全过程
 *
 * <h3>与历史对比</h3>
 * 每次扫描存快照（{@link ProbeHistory}），下次自动 diff：
 * 新设备标红、消失的灰色划掉、一直在的折叠。
 */
public class LanProbeActivity extends Activity {

    /* ---------- 配色 ---------- */
    private static final int BG        = AmberTheme.BG;
    private static final int SURFACE   = AmberTheme.CARD;
    private static final int SURFACE_2 = AmberTheme.CARD_2;
    private static final int ACCENT    = AmberTheme.ACCENT;   // 终端绿（主色）
    private static final int ACCENT_DIM= AmberTheme.ACCENT_DIM;
    private static final int TERM_FG   = AmberTheme.TERM_FG;
    private static final int TERM_DIM  = AmberTheme.TERM_DIM;
    private static final int TEXT      = AmberTheme.TEXT;
    private static final int TEXT_DIM  = AmberTheme.TEXT_DIM;
    private static final int DANGER    = AmberTheme.DANGER;
    private static final int WARN      = AmberTheme.ACCENT;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout resultBox;    // 卡片区
    private TextView termView;         // 终端
    private ScrollView termScroll;
    private LinearLayout termWrap;
    private TextView statusChip;
    private TextView scanBtn;
    private boolean termCollapsed = true;
    private boolean running = false;

    private final StringBuilder termBuf = new StringBuilder();

    /* 上次扫描结果缓存。Activity 被系统重建后用它恢复画面， */
    /* 否则扫描完的结果会渲染到已被丢弃的旧 View 上，页面看着像没扫过。 */
    private java.util.List<LanProbe.Host> cachedHosts;
    private ProbeHistory.Diff cachedDiff;
    private java.util.Map<String, java.util.List<LanProbe.Port>> cachedPorts;
    private java.util.Map<String, String> cachedBanners;
    private boolean cachedPortsTrustworthy = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ======== 顶栏 ========
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText("网络侦察");
        title.setTextSize(17);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusChip = new TextView(this);
        statusChip.setTextSize(11);
        statusChip.setTextColor(TEXT_DIM);
        statusChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        statusChip.setBackground(round(SURFACE_2, 10));
        statusChip.setText("待扫描");
        bar.addView(statusChip);

        root.addView(bar);

        // ======== 结果卡片区（可滚动）========
        ScrollView cardScroll = new ScrollView(this);
        cardScroll.setFillViewport(true);
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setPadding(dp(12), 0, dp(12), dp(8));
        cardScroll.addView(resultBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(cardScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        showEmptyState();

        // ======== 终端区（可折叠）========
        LinearLayout termHeader = new LinearLayout(this);
        termHeader.setOrientation(LinearLayout.HORIZONTAL);
        termHeader.setGravity(Gravity.CENTER_VERTICAL);
        termHeader.setPadding(dp(16), dp(8), dp(16), dp(6));
        termHeader.setBackground(round(SURFACE, 0));
        termHeader.setClickable(true);

        TextView chev = new TextView(this);
        chev.setText("▸");
        chev.setTextSize(12);
        chev.setTextColor(ACCENT);
        termHeader.addView(chev);

        TextView lbl = new TextView(this);
        lbl.setText(L.s("  扫描日志", "  SCAN LOG"));
        lbl.setTextSize(11.5f);
        lbl.setTextColor(TEXT_DIM);
        lbl.setLetterSpacing(0.08f);
        termHeader.addView(lbl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(this);
        clear.setText(L.s("清空", "Clear"));
        clear.setTextSize(11);
        clear.setTextColor(TEXT_DIM);
        clear.setPadding(dp(10), dp(4), dp(10), dp(4));
        clear.setBackground(round(SURFACE_2, 8));
        clear.setOnClickListener(v -> {
            termBuf.setLength(0);
            termView.setText("");
        });
        termHeader.addView(clear);
        root.addView(termHeader);

        termWrap = new LinearLayout(this);
        termWrap.setOrientation(LinearLayout.VERTICAL);
        // 终端区：下沉底 + 一条极淡描边，与卡片同族（布局不动）
        termWrap.setBackground(roundStroke(AmberTheme.TERM_BG,
                (int) AmberTheme.RADIUS_CARD, AmberTheme.EDGE, 0.8f));
        termScroll = new ScrollView(this);
        termScroll.setBackgroundColor(AmberTheme.TERM_BG);
        termScroll.setVerticalScrollBarEnabled(true);
        termScroll.setScrollbarFadingEnabled(false);
        termView = new TextView(this);
        termView.setTextSize(11.5f);
        termView.setTextColor(TERM_FG);
        termView.setTypeface(Typeface.MONOSPACE);
        termView.setLineSpacing(dp(2), 1f);
        termView.setPadding(dp(14), dp(10), dp(14), dp(10));
        termScroll.addView(termView);
        termWrap.addView(termScroll);
        root.addView(termWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
        termHeader.setOnClickListener(v -> {
            termCollapsed = !termCollapsed;
            chev.setText(termCollapsed ? "▸" : "▾");
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) termWrap.getLayoutParams();
            lp.height = termCollapsed ? dp(96) : dp(240);
            termWrap.setLayoutParams(lp);
        });

        // ======== 底部按钮 ========
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(12), dp(8), dp(12), dp(16));

        scanBtn = new TextView(this);
        scanBtn.setText(L.s("▶  开始扫描", "▶  SCAN"));
        scanBtn.setTextSize(14);
        scanBtn.setTextColor(AmberTheme.ON_ACCENT);
        scanBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        scanBtn.setGravity(Gravity.CENTER);
        scanBtn.setPadding(0, dp(14), 0, dp(14));
        scanBtn.setBackground(round(ACCENT, 22));
        scanBtn.setOnClickListener(v -> startScan());
        actions.addView(scanBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f));

        TextView back = new TextView(this);
        back.setText(L.s("返回", "Back"));
        back.setTextSize(14);
        back.setTextColor(TEXT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, dp(14), 0, dp(14));
        back.setBackground(round(SURFACE_2, 22));
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = dp(8);
        actions.addView(back, blp);

        root.addView(actions);
        setContentView(root);

        // 如果之前扫过（Activity 被重建过），把结果恢复出来
        if (cachedHosts != null && cachedDiff != null) {
            renderResults(cachedHosts, cachedDiff,
                    cachedPorts, cachedBanners, cachedPortsTrustworthy);
            statusChip.setText(L.s("完成", "Done"));
            statusChip.setTextColor(ACCENT);
        }

        term("[root@wpaforge]# " + L.s("网络侦察就绪", "recon ready"));
        term(L.s("[?] 仅扫描当前已连接的 WiFi 网段",
                 "[?] scans the current WiFi subnet only"));
    }

    /* ================= 空状态 ================= */

    private void showEmptyState() {
        resultBox.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(20), dp(40), dp(20), dp(40));
        card.setBackground(round(SURFACE, 16));

        TextView icon = new TextView(this);
        icon.setText("◈");
        icon.setTextSize(34);
        icon.setTextColor(ACCENT_DIM);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);

        TextView t = new TextView(this);
        t.setText(L.s("点下方开始扫描", "Tap SCAN to begin"));
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(6));
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(L.s("将探测当前网段的设备、厂商与开放端口，\n并与上次结果对比标出新出现的设备。",
                      "Probes devices, vendors and open ports on this subnet,\nand highlights devices new since the last scan."));
        d.setTextSize(11.5f);
        d.setTextColor(TEXT_DIM);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(dp(4), 1f);
        card.addView(d);

        resultBox.addView(card);
    }

    /* ================= 扫描主流程 ================= */

    private void startScan() {
        if (running) return;
        running = true;
        scanBtn.setAlpha(0.4f);
        statusChip.setText(L.s("扫描中…", "Scanning…"));
        statusChip.setTextColor(WARN);

        new Thread(() -> {
            if (!isRoot()) {
                ui.post(() -> {
                    term("[-] FATAL " + L.s("需要 root 权限", "root required"));
                    statusChip.setText(L.s("需要 Root", "Root required"));
                    statusChip.setTextColor(DANGER);
                    running = false;
                    scanBtn.setAlpha(1f);
                });
                return;
            }

            final String selfIp = LanProbeExecutor.selfIp(this);
            if (selfIp == null) {
                ui.post(() -> {
                    term("[-] ERR " + L.s("读不到本机 IP（WiFi 未连接？）",
                            "cannot read local IP (WiFi off?)"));
                    statusChip.setText(L.s("失败", "Failed"));
                    statusChip.setTextColor(DANGER);
                    running = false;
                    scanBtn.setAlpha(1f);
                });
                return;
            }
            final String gateway = LanProbeExecutor.gatewayIp(selfIp);

            ui.post(() -> {
                term("[root@wpaforge]# start-scan");
                term(String.format("[*] %s %s  (%s %s)",
                        L.s("网段", "subnet"), subnet(selfIp) + ".0/24",
                        L.s("本机", "self"), selfIp));
            });

            List<LanProbe.Host> hosts;
            try {
                hosts = LanProbeExecutor.pingSweep(selfIp, gateway,
                        s -> ui.post(() -> term(s)), this);
            } catch (Throwable t) {
                ui.post(() -> {
                    term("[-] ERR " + t.getClass().getSimpleName());
                    statusChip.setText(L.s("失败", "Failed"));
                    statusChip.setTextColor(DANGER);
                    running = false;
                    scanBtn.setAlpha(1f);
                });
                return;
            }

            // 和历史对比
            final ProbeHistory.Snapshot prev = ProbeHistory.save(this, hosts);
            final ProbeHistory.Diff diff = ProbeHistory.diff(prev, hosts);

            // 先判断这个网络的端口结果可不可信。
            //
            // 有些网络（AP 或上层链路）对**任意** TCP 目标都回 SYN-ACK，
            // 此时端口扫描必然「全开」，banner 也可能是劫持层伪造的。
            // 与其给一堆假数据，不如明确说测不准并跳过。
            final boolean portsTrustworthy =
                    LanProbeExecutor.portsAreTrustworthy(selfIp);

            // 端口 + banner
            final java.util.Map<String, java.util.List<LanProbe.Port>> portMap =
                    new java.util.LinkedHashMap<>();
            final java.util.Map<String, String> bannerMap = new java.util.LinkedHashMap<>();
            ui.post(() -> term("[*] " + L.s("探测端口与 banner...", "probing ports and banners...")));
            if (!portsTrustworthy) {
                ui.post(() -> term("[!] " + L.s(
                        "本网络对任意目标都回 SYN-ACK，跳过端口探测（结果不可信）",
                        "network answers SYN for any target; skipping port probe")));
            }
            for (LanProbe.Host h : hosts) {
                if (h.isSelf) continue;
                if (!portsTrustworthy) break;
                ui.post(() -> term("\n  -- " + h.ip + " --"));
                java.util.List<LanProbe.Port> opened = new java.util.ArrayList<>();
                for (int p : LanProbe.probePorts()) {
                    boolean open = LanProbeExecutor.portOpen(h.ip, p);
                    LanProbe.Port pt = new LanProbe.Port(p, open);
                    if (open) {
                        opened.add(pt);
                        String banner = "";
                        try { banner = LanProbeExecutor.grabBanner(h.ip, p); } catch (Throwable ignored) {}
                        if (!banner.isEmpty()) bannerMap.put(h.ip + ":" + p, banner);
                        final String bl = banner;
                        final int fp = p;
                        ui.post(() -> term(String.format("    %5d/tcp  open   %s", fp, bl)));
                    }
                }
                portMap.put(h.ip, opened);
            }

            ui.post(() -> {
                renderResults(hosts, diff, portMap, bannerMap, portsTrustworthy);
                term("\n[root@wpaforge]# " + L.s("扫描完成", "scan complete"));
                statusChip.setText(L.s("完成", "Done"));
                statusChip.setTextColor(ACCENT);
                running = false;
                scanBtn.setAlpha(1f);
            });
        }, "wpa-lan-probe").start();
    }

    /* ================= 结果渲染 ================= */

    private void renderResults(List<LanProbe.Host> hosts,
                               ProbeHistory.Diff diff,
                               java.util.Map<String, java.util.List<LanProbe.Port>> portMap,
                               java.util.Map<String, String> bannerMap,
                               boolean portsTrustworthy) {
        // 先存缓存：Activity 若在扫描期间被系统重建，
        // onCreate 会用它把画面恢复出来。
        cachedHosts = hosts;
        cachedDiff = diff;
        cachedPorts = portMap;
        cachedBanners = bannerMap;
        cachedPortsTrustworthy = portsTrustworthy;
        resultBox.removeAllViews();

        // ---- 概览卡 ----
        LinearLayout sum = new LinearLayout(this);
        sum.setOrientation(LinearLayout.VERTICAL);
        sum.setPadding(dp(18), dp(16), dp(18), dp(16));
        sum.setBackground(round(SURFACE, 18));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(10);
        sum.setLayoutParams(slp);

        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.addView(statBlock(String.valueOf(hosts.size()),
                L.s("在线设备", "devices"), ACCENT), weight());
        if (diff.hasBaseline) {
            boolean alert = !diff.added.isEmpty();
            nums.addView(statBlock(String.valueOf(diff.added.size()),
                    L.s("新出现", "new"), alert ? DANGER : TEXT_DIM), weight());
            nums.addView(statBlock(String.valueOf(diff.removed.size()),
                    L.s("已离开", "gone"), TEXT_DIM), weight());
        } else {
            nums.addView(statBlock("—", L.s("首次扫描", "first run"), TEXT_DIM), weight());
        }
        sum.addView(nums);

        if (!portsTrustworthy) {
            TextView nt = new TextView(this);
            nt.setText(L.s(
                    "⚠ 端口结果不可信，已隐藏。\n"
                            + "常见原因：本机开了代理/VPN（如 Clash 的 TUN 模式）—— "
                            + "它会替任何目标代答 SYN-ACK，扫到的都是它的假响应。\n"
                            + "关闭代理后重试即可看到真实端口。也可能是所在网络对任意目标回 SYN。",
                    "⚠ Port results unreliable and hidden.\n"
                            + "Common cause: a local proxy/VPN (e.g. Clash TUN) answers SYN-ACK"
                            + " for every target, so what you scanned is the proxy, not the device.\n"
                            + "Turn the proxy off and rescan. Some networks may behave the same."));
            nt.setTextSize(11.5f);
            nt.setTextColor(WARN);
            nt.setLineSpacing(dp(3), 1f);
            nt.setPadding(0, dp(10), 0, 0);
            sum.addView(nt);
        }

        if (diff.hasBaseline && !diff.added.isEmpty()) {
            TextView warn = new TextView(this);
            warn.setText(L.s("⚠ 检测到新设备，请确认是否熟悉",
                    "⚠ New device(s) detected — do you recognise them?"));
            warn.setTextSize(11.5f);
            warn.setTextColor(DANGER);
            warn.setPadding(0, dp(10), 0, 0);
            sum.addView(warn);
        }
        resultBox.addView(sum);

        // ---- 新设备优先展示 ----
        if (!diff.added.isEmpty()) {
            resultBox.addView(sectionHeader(L.s("新出现", "NEW"), DANGER));
            for (ProbeHistory.Dev d : diff.added) {
                resultBox.addView(deviceCard(hostOf(hosts, d), portMap, bannerMap, true, portsTrustworthy));
            }
        }

        // ---- 已知设备 ----
        java.util.List<LanProbe.Host> known = new java.util.ArrayList<>();
        java.util.Set<String> addedKeys = new java.util.HashSet<>();
        for (ProbeHistory.Dev d : diff.added) addedKeys.add(d.key());
        for (LanProbe.Host h : hosts) {
            ProbeHistory.Dev d = new ProbeHistory.Dev(h.ip, h.mac, h.vendor);
            if (!addedKeys.contains(d.key())) known.add(h);
        }
        if (!known.isEmpty()) {
            resultBox.addView(sectionHeader(
                    L.s("已知设备", "KNOWN") + "  " + known.size(), TEXT_DIM));
            for (LanProbe.Host h : known) {
                resultBox.addView(deviceCard(h, portMap, bannerMap, false, portsTrustworthy));
            }
        }

        // ---- 已离开 ----
        if (!diff.removed.isEmpty()) {
            resultBox.addView(sectionHeader(
                    L.s("本次未出现", "NOT SEEN") + "  " + diff.removed.size(), TEXT_DIM));
            for (ProbeHistory.Dev d : diff.removed) {
                resultBox.addView(ghostCard(d));
            }
        }
    }

    private LanProbe.Host hostOf(List<LanProbe.Host> list, ProbeHistory.Dev d) {
        for (LanProbe.Host h : list) {
            if (h.ip.equals(d.ip) || (!d.mac.isEmpty() && h.mac.equalsIgnoreCase(d.mac))) return h;
        }
        return new LanProbe.Host(d.ip, d.mac, d.vendor, false, false);
    }

    /** 一台统计数字 */
    private View statBlock(String num, String label, int color) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        TextView n = new TextView(this);
        n.setText(num);
        n.setTextSize(24);
        n.setTextColor(color);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        n.setGravity(Gravity.CENTER);
        b.addView(n);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(10.5f);
        l.setTextColor(TEXT_DIM);
        l.setGravity(Gravity.CENTER);
        b.addView(l);
        return b;
    }

    /** 分组标题 */
    private View sectionHeader(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10.5f);
        t.setTextColor(color);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(14), 0, dp(8));
        return t;
    }

    /**
     * 弹出这台设备的证据链。
     *
     * 结论下面逐条列出实际读到的值（ARP 表、OUI 查询、ICMP 回应…），
     * 让判断可复现、可反驳 —— 而不是只给一个厂商名让人盲信。
     */
    private void showEvidence(LanProbe.Host h) {
        io.github.wlmosv_png.wpaforge.scan.Evidence.Claim c =
                LanProbeExecutor.claimFor(this, h);

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(c.confidenceLabel()).append("]  ")
          .append(c.statement).append('\n').append('\n');
        for (io.github.wlmosv_png.wpaforge.scan.Evidence.Item it : c.items) {
            sb.append(it.decisive ? "● " : "○ ")
              .append(it.source).append('\n')
              .append("    ").append(it.detail).append('\n').append('\n');
        }
        sb.append(L.s("● 决定性证据   ○ 辅助信息",
                       "● decisive   ○ supporting"));

        new io.github.wlmosv_png.wpaforge.ui.NetDialog(this,
                L.s("判断依据", "Evidence"), sb.toString())
                .setPositive(L.s("关闭", "Close"), null)
                .show();
    }

    /** 设备卡 */
    private View deviceCard(LanProbe.Host h,
                            java.util.Map<String, java.util.List<LanProbe.Port>> portMap,
                            java.util.Map<String, String> bannerMap,
                            boolean isNew,
                            boolean portsTrustworthy) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(isNew
                ? roundStroke(SURFACE, (int) AmberTheme.RADIUS_CARD, AmberTheme.DANGER_EDGE, 1.5f)
                : round(SURFACE, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        // 第一行：图标 + 标题 + 标签
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView ip = new TextView(this);
        ip.setText(h.ip);
        ip.setTextSize(15);
        ip.setTextColor(isNew ? DANGER : TEXT);
        ip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(ip);

        if (isNew) head.addView(chip("NEW", DANGER));
        if (h.isGateway) head.addView(chip(L.s("网关", "GW"), WARN));
        if (h.isSelf) head.addView(chip(L.s("本机", "SELF"), ACCENT));

        card.addView(head);

        // 第二行：厂商 + MAC
        StringBuilder line2 = new StringBuilder();
        if (!h.vendor.isEmpty()) line2.append(h.vendor);
        else if (!h.displayVendor().isEmpty()) line2.append(h.displayVendor());
        if (!h.mac.isEmpty()) {
            if (line2.length() > 0) line2.append("   ·   ");
            line2.append(h.mac);
        }
        if (line2.length() > 0) {
            TextView v = new TextView(this);
            v.setText(line2.toString());
            v.setTextSize(11.5f);
            v.setTextColor(TEXT_DIM);
            v.setPadding(0, dp(4), 0, 0);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(v);
        }

        // 第三行：端口（有才显示）
        java.util.List<LanProbe.Port> ports = portsTrustworthy ? portMap.get(h.ip) : null;
        if (ports != null && !ports.isEmpty()) {
            LinearLayout pr = new LinearLayout(this);
            pr.setOrientation(LinearLayout.VERTICAL);
            pr.setPadding(0, dp(10), 0, 0);
            TextView ph = new TextView(this);
            ph.setText(L.s("开放端口", "OPEN PORTS") + "  " + ports.size());
            ph.setTextSize(10);
            ph.setTextColor(ACCENT);
            ph.setLetterSpacing(0.1f);
            pr.addView(ph);

            for (LanProbe.Port p : ports) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(3), 0, 0);

                TextView pn = new TextView(this);
                pn.setText(String.valueOf(p.port));
                pn.setTextSize(11.5f);
                pn.setTextColor(TEXT);
                pn.setTypeface(Typeface.MONOSPACE);
                pn.setMinWidth(dp(42));
                row.addView(pn);

                TextView sv = new TextView(this);
                sv.setText(p.service);
                sv.setTextSize(11);
                sv.setTextColor(TEXT_DIM);
                sv.setMinWidth(dp(80));
                row.addView(sv);

                String banner = bannerMap.get(h.ip + ":" + p.port);
                if (banner != null && !banner.isEmpty()) {
                    TextView bv = new TextView(this);
                    bv.setText(banner);
                    bv.setTextSize(10.5f);
                    bv.setTextColor(ACCENT_DIM);
                    bv.setTypeface(Typeface.MONOSPACE);
                    bv.setSingleLine(true);
                    bv.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(bv, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                pr.addView(row);
            }
            card.addView(pr);
        }

        // 依据入口：这一台的结论怎么来的，点开可看
        TextView why = new TextView(this);
        why.setText(L.s("查看判断依据 ▸", "Why this verdict ▸"));
        why.setTextSize(11f);
        why.setTextColor(ACCENT_DIM);
        why.setPadding(0, dp(10), 0, 0);
        why.setOnClickListener(v -> showEvidence(h));
        card.addView(why);

        return card;
    }

    /** 已离开的设备：灰、划掉 */
    private View ghostCard(ProbeHistory.Dev d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(round(SURFACE_2, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        TextView ip = new TextView(this);
        ip.setText(d.ip);
        ip.setTextSize(13.5f);
        ip.setTextColor(AmberTheme.TEXT_FAINT);
        ip.setPaintFlags(ip.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        card.addView(ip);

        StringBuilder sb = new StringBuilder();
        if (!d.vendor.isEmpty()) sb.append(d.vendor);
        if (!d.mac.isEmpty()) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(d.mac);
        }
        if (sb.length() > 0) {
            TextView v = new TextView(this);
            v.setText(sb.toString());
            v.setTextSize(10.5f);
            v.setTextColor(AmberTheme.TEXT_FAINT);
            v.setPadding(0, dp(3), 0, 0);
            card.addView(v);
        }
        return card;
    }

    private TextView chip(String text, int color) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(9.5f);
        c.setTextColor(color);
        c.setPadding(dp(7), dp(2), dp(7), dp(2));
        c.setBackground(roundStroke(Color.TRANSPARENT, 6,
                (color & 0x00FFFFFF) | 0x55000000, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable roundStroke(int fill, int radiusDp, int stroke, float wDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, dp(wDp)), stroke);
        return g;
    }

    private void term(String s) {
        termBuf.append(s).append('\n');
        if (termBuf.length() > 24000) termBuf.delete(0, 8000);
        termView.setText(termBuf.toString());
        termScroll.post(() -> termScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String subnet(String ip) {
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot) : ip;
    }

    private boolean isRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            boolean root = false;
            while ((line = r.readLine()) != null) if (line.contains("uid=0")) root = true;
            p.destroy();
            return root;
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

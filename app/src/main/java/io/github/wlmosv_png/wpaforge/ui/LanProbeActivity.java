package io.github.wlmosv_png.wpaforge.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.scan.LanProbe;
import io.github.wlmosv_png.wpaforge.scan.LanProbeExecutor;

import java.util.List;

/**
 * 局域网探测终端页。
 *
 * 全屏、等宽字体、黑底绿字，模拟黑客终端在跑扫描。
 * 点「开始扫描」后逐行滚出结果：
 *
 *   [root@wpaforge]# scan --t 192.168.1.0/24
 *   [*] 目标网段 192.168.1.0/24，主机数 254
 *   [.] 正在扫描 ...
 *   [+] 存活主机 2 个
 *       192.168.1.1  28:93:7D:41:CC:38  [华为]  (网关)
 *
 * 实现注意：全部用代码构建（无 XML），与主界面风格一致。
 */
public class LanProbeActivity extends Activity {

    private static final int FG   = Color.parseColor("#8AFF8A");
    private static final int FG_DIM = Color.parseColor("#5FBF6A");
    private static final int FG_WHITE = Color.parseColor("#E8F4E8");
    private static final int HIGHLIGHT = Color.parseColor("#FFD54F");
    private static final int DANGER = Color.parseColor("#FF8A80");
    private static final int BG    = Color.parseColor("#0A0E0A");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView term;
    private ScrollView scroll;
    private boolean running = false;
    private StringBuilder buf = new StringBuilder();
    private String selfIp;
    private String gateway;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        // 标题栏
        TextView title = new TextView(this);
        title.setText("  LAN PROBE  //  WPAForge");
        title.setTextColor(FG_WHITE);
        title.setTextSize(13);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.addView(title);

        // 终端区域
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        term = new TextView(this);
        term.setTextColor(FG);
        term.setTextSize(12.5f);
        term.setTypeface(Typeface.MONOSPACE);
        term.setLineSpacing(dp(2), 1f);
        term.setTextIsSelectable(false);
        scroll.addView(term, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, slp);

        // 按钮行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(8), dp(6), dp(8), dp(14));

        TextView scan = makeBtn("► 开始扫描", FG, BG);
        scan.setOnClickListener(v -> startScan());
        row.addView(scan, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = makeBtn("清屏", FG_DIM, BG);
        clear.setOnClickListener(v -> { buf.setLength(0); term.setText(""); });
        row.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView back = makeBtn("退出", DANGER, BG);
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row);

        // 初始 banner
        print("[root@wpaforge]# " + (L.isZh() ? "网络侦察模块已就绪" : "network recon ready"));
        print("[?] " + (L.isZh() ? "仅扫描当前已连接的 WiFi 网段" : "scans the current WiFi subnet only"));
        print("");
    }

    private TextView makeBtn(String text, int fg, int bg) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(13);
        b.setTypeface(Typeface.MONOSPACE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(10), dp(6), dp(10));
        b.setBackground(createBg(bg, 20));
        return b;
    }

    private android.graphics.drawable.Drawable createBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private void startScan() {
        if (running) return;
        running = true;

        // root 权限检查
        if (!isRoot()) {
            print(term("[-]", "FATAL", DANGER, "需要 root（su）。WPAForge 的破解通道可能已授权，请确认。"));
            running = false;
            return;
        }

        new Thread(() -> {
            ui.post(() -> print("\n[root@wpaforge]# start-scan"));

            selfIp = LanProbeExecutor.selfIp(this);
            if (selfIp == null) {
                ui.post(() -> print(term("[-]", "ERR", DANGER, "读取不到本机 IP（WiFi 可能未连接）")));
                running = false;
                return;
            }
            gateway = LanProbeExecutor.gatewayIp(selfIp);
            String cidr = LanProbe.ipOnly(selfIp) == null ? "?" : subnet(selfIp);

            ui.post(() -> print(String.format("[*] %s %s  （%s）",
                    L.isZh() ? "目标网段" : "target", cidr + "/24",
                    L.isZh() ? "本机 " + selfIp : "self " + selfIp)));
            ui.post(() -> print(L.isZh() ? "[*] 正在扫描存活主机..." : "[*] sweeping for live hosts..."));

            final List<LanProbe.Host> hosts;
            try {
                hosts = LanProbeExecutor.pingSweep(selfIp, gateway,
                        s -> ui.post(() -> print(s)), this);
            } catch (Throwable t) {
                ui.post(() -> print(term("[-]", "ERR", DANGER, "扫描异常: " + t.getMessage())));
                running = false;
                return;
            }

            ui.post(() -> print(String.format("", "")));
            ui.post(() -> print(String.format("[+] %s %d",
                    L.isZh() ? "存活主机" : "live hosts", hosts.size())));
            for (LanProbe.Host h : hosts) {
                ui.post(() -> print("   " + h.line()));
            }

            // 端口扫描：对每个存活主机跑常见端口
            if (!hosts.isEmpty()) {
                ui.post(() -> print("\n[" + (L.isZh() ? "*] 探测常见端口..." : "*] probing common ports...")));
                for (LanProbe.Host h : hosts) {
                    if (h.isSelf) continue;   // 不扫自己
                    ui.post(() -> print("\n  -- " + h.ip + " --"));
                    try {
                        LanProbeExecutor.portScan(h.ip, s -> ui.post(() -> print("     " + s)));
                    } catch (Throwable ignored) {
                    }
                }
            }

            ui.post(() -> print("\n[root@wpaforge]# " + (L.isZh() ? "扫描完成" : "scan complete")));
            running = false;
        }, "wpa-lan-probe").start();
    }

    private String subnet(String ip) {
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot) : ip;
    }

    private boolean isRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            boolean root = false;
            while ((line = r.readLine()) != null) {
                if (line.contains("uid=0")) root = true;
            }
            p.destroy();
            return root;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 终端行：前缀 + 正文（带颜色） */
    private static String term(String tag, String status, int color, String msg) {
        // 用 ANSI 风格标记，简单拼装
        return tag + " " + status + " " + msg;
    }

    private void print(String s) {
        buf.append(s).append('\n');
        // 截断避免无限增长
        if (buf.length() > 20000) buf.delete(0, 8000);
        term.setText(buf.toString());
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
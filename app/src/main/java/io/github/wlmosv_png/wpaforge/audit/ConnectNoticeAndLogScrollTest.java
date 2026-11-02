package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * 用户实测三连（2026-11-02 截图）的可执行验收。
 *
 * <ol>
 *   <li><b>「小窗里滚不动日志」</b> —— 折叠时 {@code maxLines=4 + ellipsize=END}
 *       把 TextView 压成恰好 4 行高，ScrollView 里没有可滚内容。</li>
 *   <li><b>「文字残留」</b> —— 同一个裁剪方式让尾部被吃掉，小窗永远显示
 *       日志**开头**（任务启动时的保活提示）；且破解页那条「WiFi 未开启」
 *       来自被双父 bug 搬过来的观测页控件，WiFi 恢复后没人清它。</li>
 *   <li><b>「展开按钮太小」</b> —— 原为 11.5sp 裸三角，无背景、无触摸扩张。</li>
 * </ol>
 */
public final class ConnectNoticeAndLogScrollTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String read(String rel) throws IOException {
        String root = TestPaths.javaSrcRoot();
        if (root == null || root.isEmpty()) throw new IOException("no root");
        return new String(Files.readAllBytes(Paths.get(root + rel)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 提示条归属 + 日志滚动 + 展开按钮 ===");
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");

        // ---- ① 双父 bug：两个页面各用各的控件 ----
        ok("① 破解页用独立字段 connectNotice", ma.contains("private TextView connectNotice;"),
                "字段缺失");
        ok("① 观测页仍用 observeNotice", ma.contains("private TextView observeNotice;"),
                "字段缺失");
        ok("① 破解页 addView(connectNotice)", ma.contains("pageConnect.addView(connectNotice);"),
                "未挂到破解页");
        ok("① 观测页 addView(observeNotice)", ma.contains("pageObserve.addView(observeNotice,"),
                "未挂到观测页");
        // 破解页里不得再出现 observeNotice 的赋值
        int connectTabStart = ma.indexOf("buildConnectTab");
        String connectTab = connectTabStart > 0
                ? ma.substring(connectTabStart, Math.min(ma.length(), connectTabStart + 60000))
                : "";
        ok("① 破解页构建代码里不再出现 observeNotice",
                !connectTab.contains("observeNotice"), "仍在复用");
        info("① 破解页构建段长度 " + connectTab.length());

        // ---- ② 残留：WiFi 恢复后必须自动消失 ----
        ok("② 存在 refreshConnectNotice", ma.contains("private void refreshConnectNotice()"),
                "缺失");
        ok("② 缓存真实 WiFi 状态（不是记上一次的提示）",
                ma.contains("wm.isWifiEnabled()"), "未查真实状态");
        ok("② WiFi 开着时隐藏提示",
                ma.contains("connectNotice.setVisibility(View.GONE)"), "缺失");
        ok("② 状态刷新时会调用它（updateConnectMode 内）",
                ma.contains("refreshConnectNotice();"), "未接线");
        ok("② 拿不到状态时不显示假警告",
                ma.contains("拿不到状态时**不显示**警告"), "缺少说明");

        // ---- ③ 日志区：可滚动 + 显示最新 ----
        ok("③ 不再用 maxLines(4)/maxLines(1) 裁剪",
                !ma.contains("connectStreamView.setMaxLines(4)")
                        && !ma.contains("connectStreamView.setMaxLines(1)"), "仍在裁剪");
        ok("③ maxLines 明确为 MAX_VALUE",
                ma.contains("connectStreamView.setMaxLines(Integer.MAX_VALUE)"), "缺失");
        ok("③ 不再对日志 SetEllipsize 截断尾部",
                !ma.contains("connectStreamView.setEllipsize(TextUtils.TruncateAt.END)"),
                "仍在截断");
        ok("③ 折叠高度走常量（132dp）", ma.contains("STREAM_COLLAPSED_DP = 132"), "缺失");
        ok("③ 展开高度走常量（320dp）", ma.contains("STREAM_EXPANDED_DP = 320"), "缺失");
        ok("③ 日志容器是 ScrollView（可滚动）",
                ma.contains("streamScroll = new android.widget.ScrollView(this)"), "缺失");
        ok("③ 手指落在日志区时抢回手势（外层不截走）",
                ma.contains("requestDisallowInterceptTouchEvent(true)"), "缺失");
        ok("③ 切换折叠后滚到最新",
                ma.contains("streamCollapsed = !streamCollapsed;"), "缺失");

        // ---- ④ 展开按钮重绘 ----
        ok("④ 按钮尺寸 32dp（原为裸文字三角）",
                ma.contains("int chev = px(32);"), "尺寸未改");
        ok("④ 按钮有圆形底色", ma.contains("flowChevron.setBackground(roundBg(ACCENT, chev / 2f))"),
                "无背景");
        ok("④ 按钮字号加大到 14sp", ma.contains("flowChevron.setTextSize(14f)"), "字号未改");
        ok("④ 按钮自带点击（不依赖整行）",
                ma.contains("flowChevron.setOnClickListener(v -> toggleStream())"), "缺失");
        ok("④ 有无障碍描述",
                ma.contains("展开日志") && ma.contains("收起日志"), "缺失");

        System.out.println();
        System.out.println("=== 提示条/滚动/按钮: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

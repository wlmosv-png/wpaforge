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

        // ---- ① 破解页**不再**承担 Wi-Fi 开关状态展示（2026-11-03 需求）----
        ok("① 破解页已无 connectNotice 控件", !ma.contains("connectNotice"), "仍在");
        ok("① 破解页已无 refreshConnectNotice", !ma.contains("refreshConnectNotice"), "仍在");
        ok("① 观测页仍用独立字段 observeNotice",
                ma.contains("private TextView observeNotice;"), "字段缺失");
        ok("① 观测页 addView(observeNotice)",
                ma.contains("pageObserve.addView(observeNotice,"), "未挂到观测页");

        // 破解页构建段（用于判断破解页自己有没有 Wi-Fi 提示代码）
        int connectTabStart = ma.indexOf("buildConnectTab");
        String connectTab = connectTabStart > 0
                ? ma.substring(connectTabStart, Math.min(ma.length(), connectTabStart + 60000))
                : "";
        info("① 破解页构建段长度 " + connectTab.length());

        // ---- ② 破解页构建段不再给 Wi-Fi 提示赋值（提示条已整体移除）----
        ok("② 破解页构建段无 Wi-Fi 开关状态提示",
                !connectTab.contains("WiFi 未开启") && !connectTab.contains("Wi-Fi is off"), "仍在");
        ok("② 破解页构建段不再查系统开关",
                !connectTab.contains("isWifiEnabled()"), "仍在");
        // 观测页自身的 Wi-Fi 检查必须保留（扫描热点确实需要开 Wi-Fi）
        ok("② 观测页的 Wi-Fi 检查仍保留",
                ma.contains("private String observeBlocker() {"), "被误删");

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
        // 第二次重绘：用户否掉了「黄色大圆钮」——要一个三角形。
        // 设计取舍：视觉上只有一枚小三角，可点性靠 padding 撑开触摸区。
        // 第三版：折叠 ▶ / 展开 ▼（最通用的折叠语义）。
        // 上一版用 ▼/▲，折叠态朝下容易被读成「已经展开了」——用户实测反馈。
        ok("④ 折叠 ▶ / 展开 ▼（通用折叠语义）",
                ma.contains("flowChevron.setText(streamCollapsed ? \"▶\" : \"▼\")"),
                "箭头语义不对");
        ok("④ 不再有大圆底", !ma.contains("roundBg(ACCENT, chev / 2f)"), "圆底仍在");
        ok("④ 无背景（flowChevron.setBackground(null)）",
                ma.contains("flowChevron.setBackground(null)"), "仍有背景");
        ok("④ 触控靠 padding 撑开（不是靠放大视觉）",
                ma.contains("flowChevron.setPadding(px(10), px(10), px(12), px(10))"), "缺 padding");
        ok("④ 字号 15sp（比初版 11.5 清楚，又不像按钮）",
                ma.contains("flowChevron.setTextSize(AmberTheme.FS_STATE)"), "字号未改");
        ok("④ 按钮自带点击（不依赖整行）",
                ma.contains("flowChevron.setOnClickListener(v -> toggleStream())"), "缺失");
        ok("④ 有无障碍描述",
                ma.contains("展开日志") && ma.contains("收起日志"), "缺失");

        // ---- ⑤ 观测页提示条：唯一决策点 + 按真实状态重算 ----
        ok("⑤ 存在唯一决策点 refreshObserveNotice",
                ma.contains("private void refreshObserveNotice("), "缺失");
        ok("⑤ 有按真实状态判断的 observeBlocker",
                ma.contains("private String observeBlocker()"), "缺失");
        ok("⑤ 破解运行中不报「扫不到」（那是切网，不是故障）",
                ma.contains("CrackService.isRunning()") && ma.contains("引擎正在反复断开"),
                "缺失");
        ok("⑤ 有结果即隐藏提示", ma.contains("observeNotice.setVisibility(View.GONE)"),
                "缺失");
        ok("⑤ onNotice 只记录、不当场写屏（避免闪烁）",
                ma.contains("lastScanNotice = message;") && ma.contains("refreshObserveNotice(lastRecords)"),
                "仍在当场写屏");
        ok("⑤ 旧的「三个写入点」已收敛（renderRecords 里不再直接写文案）",
                !ma.contains("暂时扫不到网络 · \", \"No networks yet · \")\n                    + scanner.scanGateLabel()"),
                "旧写入点仍在");

        // ---- ⑥ 空态收紧（没跑过就不摆占位数字）----
        ok("⑥ 统计行 / 速率行提升为成员（便于整行收起）",
                ma.contains("private LinearLayout statRowView;")
                        && ma.contains("private LinearLayout footRowView;"), "未提升");
        ok("⑥ 有空态判定 hasData",
                ma.contains("boolean hasData = st == TaskQueue.State.RUNNING"), "缺失");
        ok("⑥ 空态收起统计行", ma.contains("statRowView.setVisibility(hasData ? View.VISIBLE : View.GONE)"),
                "未收起");
        ok("⑥ 空态收起速率行", ma.contains("footRowView.setVisibility(hasData ? View.VISIBLE : View.GONE)"),
                "未收起");
        ok("⑥ 空态收起右上角完成度数字",
                ma.contains("statPctView.setVisibility(hasData ? View.VISIBLE : View.GONE)"),
                "未收起");

        System.out.println();
        System.out.println("=== 提示条/滚动/按钮: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

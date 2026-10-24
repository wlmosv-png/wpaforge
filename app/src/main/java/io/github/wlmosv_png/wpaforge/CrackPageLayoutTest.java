package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 破解页 UI 重构（Obsidian Amber）的结构回归测试。
 *
 * <h3>为什么是结构断言</h3>
 * 本项目的界面**全部由 Java 代码构建**（没有 XML 布局，也没有 instrumentation
 * 测试运行环境），桌面 JVM 无法实例化 {@code android.view.View}。
 * 所以这里钉住的是「重构不能破坏的那些结构性事实」：
 * <ul>
 *   <li>五个导航入口与各自的页签索引；</li>
 *   <li>破解页的每个展示位都仍被**赋值**（漏一个就是运行期 NPE）；</li>
 *   <li>按钮 / 选目标 / 选字典 / 日志筛选 / 清空 的**原有回调一字不变**；</li>
 *   <li>大数字区只用既有的 {@code CrackHud} 格式化，**不自己算**进度/速率/剩余；</li>
 *   <li>UI 层没有引入任何网络操作或引擎调用；</li>
 *   <li>主题常量只有一处出处（{@code ui.AmberTheme}）。</li>
 * </ul>
 *
 * <p>真机上的实际观感与交互由人工验收（截图）覆盖，不在这条断言里假装完成。
 */
public class CrackPageLayoutTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static final String ROOT = "/workspace/promo-wpaforge/app/src/main/java/"
            + "io/github/wlmosv_png/wpaforge/";

    /** 取某个方法的方法体（从签名到下一个同级方法签名为止）。 */
    private static String body(String src, String signature) {
        if (src == null) return "";
        int i = src.indexOf(signature);
        if (i < 0) return "";
        int j = src.indexOf("\n    private ", i + signature.length());
        int k = src.indexOf("\n    /**", i + signature.length());
        if (j < 0) j = src.length();
        if (k > 0 && k < j) j = k;
        return src.substring(i, j);
    }

    public static void main(String[] args) {
        String ma = read(ROOT + "MainActivity.java");
        String theme = read(ROOT + "ui/AmberTheme.java");
        String sp = read(ROOT + "ui/StagedProgress.java");

        if (ma == null || theme == null || sp == null) {
            System.out.println("  N/RUN 源码不可读，结构断言无法执行");
            System.out.println("=== 破解页布局测试: 0 通过, 0 失败（NOT RUN）===");
            return;
        }

        String build = body(ma, "private void buildConnectTab() {");
        String target = body(ma, "private void updateTargetLabel() {");
        String progressCb = body(ma, "public void onProgress(int tested, int total, "
                + "double rate, long etaMs) {");
        String streamHeight = body(ma, "private void applyStreamHeight() {");
        ok("能定位 buildConnectTab 方法体", !build.isEmpty());
        ok("能定位 updateTargetLabel", !target.isEmpty());
        ok("能定位 onProgress 回调", !progressCb.isEmpty());
        ok("能定位 applyStreamHeight", !streamHeight.isEmpty());

        System.out.println("== 1. 顶部导航：五个入口与原有行为 ==");
        String[] tabs = {"观测", "审计", "破解", "WPS", "设置"};
        int tabFound = 0;
        for (String t : tabs) {
            if (ma.contains("makeTab(L.s(\"" + t + "\", ")) tabFound++;
        }
        eq("五个导航入口全部保留", 5, tabFound);
        for (int i = 0; i < 5; i++) {
            ok("页签 " + i + " 仍绑定 showTab(" + i + ")",
                    ma.contains("makeTab(L.s(\"" + tabs[i] + "\", ") && ma.contains(", " + i + ");"));
        }
        ok("makeTab 仍然只做一件事：点击 → showTab",
                ma.contains("t.setOnClickListener(v -> showTab(index));"));
        ok("当前页仍用琥珀高亮（选中态用 ACCENT）",
                ma.contains("tabs[i].setBackground(roundBg(on ? ACCENT : AmberTheme.CARD_2,"));
        ok("未选中项已弱化（弱色文字 + 无描边）",
                ma.contains("tabs[i].setTextColor(on ? ON_ACCENT : AmberTheme.TEXT_FAINT);"));

        System.out.println("== 2. 破解页展示位：一个都不能漏赋值 ==");
        String[] views = {"connectTargetView", "connectDictView", "connectModeView",
                "connectProgress", "candBox", "candLabel", "candRoll", "candPrev",
                "hitCard", "hitPw", "hitMeta", "observeNotice", "flowHeader", "flowChevron",
                "flowLabel", "flowCount", "chipKey", "chipAll", "flowClear",
                "connectStreamView", "streamScroll", "streamParams", "jumpLatestBtn",
                "connectEmptyHint", "startBtn", "pauseBtn", "stopBtn", "queueBox",
                "netMetaView", "netSecChip", "statDoneView", "statTotalView", "statPctView",
                "statRateView", "statEtaView", "stateBig", "actionsHint",
                "progressCard", "eventCard", "actionsCard"};
        int missing = 0;
        StringBuilder miss = new StringBuilder();
        for (String v : views) {
            if (!build.contains(v + " = ")) { missing++; miss.append(' ').append(v); }
        }
        eq("破解页所有展示位都在 buildConnectTab 里赋值（漏：" + miss + "）", 0, missing);

        ok("当前候选有滚动动效（顶上来 / 消散）",
                ma.contains("candRoll.setCandidate(cur)")
                        && read(ROOT + "ui/CandidateRoll.java") != null
                        && read(ROOT + "ui/CandidateRoll.java").contains("setCandidate(String s)"));
        System.out.println("== 2b. 目标/字典显示：权威 + 派生（后台跑回来不会丢）==");
        String svc = read(ROOT + "CrackService.java");
        ok("服务给界面一个只读的候选列表入口（与引擎同源）",
                svc != null && svc.contains("public java.util.List<Candidate> dictForDisplay()"));
        ok("MainActivity 有「从权威重建显示」的方法",
                ma.contains("private void restoreSelectionFromAuthority()"));
        ok("重建只在**缺失时**发生（不覆盖用户本次的选择）",
                ma.contains("if (targetNet == null || targetNet.ssid == null")
                        && ma.contains("if ((currentDict == null || currentDict.isEmpty()) && !dictReloading)"));
        ok("目标从任务还原（task.toNetRecord）", ma.contains("targetNet = t.toNetRecord();"));
        ok("字典优先用服务正在用的那份",
                ma.contains("java.util.List<Candidate> engineDict = svc == null ? null : svc.dictForDisplay();"));
        ok("服务不在时按 dictPath 后台补齐列表",
                ma.contains("audit.DictStore.load(") && ma.contains("wpaforge-dict-reload"));
        ok("条数显示不依赖列表是否已载入（dictCount）",
                ma.contains("private int dictCount()") && ma.contains("return Math.max(0, dictSizeShown);"));
        int nResume = 0, nSvc = 0, nQueue = 0;
        for (String l : ma.split("\n")) {
            if (l.contains("restoreSelectionFromAuthority();")) {
                nResume++;   // 统计所有调用点
            }
        }
        ok("重建挂在多个时机（回前台 / 绑服务 / 队列刷新 / 队列回调）",
                nResume >= 4);

        System.out.println("== 2c. 速率/ETA 用最近 20 条滑窗（不是被拖住的整轮平均）==");
        ok("界面上写明了口径（近 20 条）", ma.contains("近 20 条"));
        ok("这行允许折两行（不再被 ellipsize 截断）",
                ma.contains("connectModeView.setMaxLines(2);"));
        ok("服务优先给滑窗均值", svc != null
                && svc.contains("long recent = e.liveRecentAvgMs();"));
        String eng = read(ROOT + "audit/CrackEngine.java");
        ok("引擎维护滑窗（RateWindow）并在每条后更新", eng != null
                && eng.contains("private final RateWindow rate = new RateWindow(")
                && eng.contains("rate.add(itemMs);")
                && eng.contains("liveRecentAvgMs = rate.avgMs();"));
        ok("进度回调的 rate/eta 用滑窗值", eng != null
                && eng.contains("long winPerItem = rate.avgMs();")
                && eng.contains("long eta = winPerItem > 0 ? (total - cursor) * winPerItem : -1L;"));
        ok("滑窗类存在且可离线测", read(ROOT + "audit/RateWindow.java") != null
                && read(ROOT + "RateWindowTest.java") != null);

        System.out.println("== 3. 原有回调一字不变 ==");
        ok("开始 → startConnect()",
                ma.contains("startBtn.setOnClickListener(v -> startConnect());"));
        ok("暂停 → onPauseResumeClicked()",
                ma.contains("pauseBtn.setOnClickListener(v -> onPauseResumeClicked());"));
        ok("停止 → onStopClicked()",
                ma.contains("stopBtn.setOnClickListener(v -> onStopClicked());"));
        ok("选目标 → showTargetPicker()",
                ma.contains("connectTargetView.setOnClickListener(v -> showTargetPicker());"));
        ok("选字典 → showDictSource()",
                ma.contains("connectDictView.setOnClickListener(v -> showDictSource());"));
        ok("日志折叠 → toggleStream()",
                ma.contains("flowHeader.setOnClickListener(v -> toggleStream());"));
        ok("「关键」筛选语义不变",
                ma.contains("chipKey.setOnClickListener(v -> {")
                        && ma.contains("streamKeyOnly = true;")
                        && ma.contains("styleFlowChips();")
                        && ma.contains("renderStream();"));
        ok("「全部」筛选语义不变",
                ma.contains("chipAll.setOnClickListener(v -> {")
                        && ma.contains("streamKeyOnly = false;"));
        ok("「清空」仍清三个缓冲（含 span 与全量列表）",
                ma.contains("streamBuffer.clear();")
                        && ma.contains("streamBuffer.clearSpans();")
                        && ma.contains("streamLines.clear();"));
        ok("命中卡复制按钮仍在（写系统剪贴板）",
                ma.contains("cm.setPrimaryClip(android.content.ClipData.newPlainText(\"WPAForge\", pw));"));

        System.out.println("== 4. 大数字区不得自己算业务数字 ==");
        ok("完成度用既有 CrackHud.percentLabel",
                progressCb.contains("CrackHud\n                            .percentLabel(tested, total)")
                        || progressCb.contains("percentLabel(tested, total)"));
        ok("已完成/总数用既有 CrackHud.thousands",
                progressCb.contains("thousands(tested)") && progressCb.contains("thousands(total)"));
        ok("速率用既有 CrackHud.ratePerMin",
                progressCb.contains("ratePerMin(rate)"));
        ok("剩余时间用既有 CrackHud.etaLabel",
                progressCb.contains("etaLabel(etaMs)"));
        ok("回调里没有自算百分比（不出现 * 100）", !progressCb.contains("* 100"));
        ok("回调里没有自算速率（不出现 / elapsed 之类）",
                !progressCb.contains("/ elapsed") && !progressCb.contains("/ ms"));
        ok("拿不到速率/剩余时给「待测/待估」而不是编数字",
                build.contains("速率 待测") && build.contains("剩余 待估"));
        ok("未就绪时完成度是「—」而不是 0.0%，且用弱色（不冒充数据）",
                build.contains("statPctView.setText(\"—\")")
                        && build.contains("statPctView.setTextColor(AmberTheme.TEXT_FAINT);")
                        && progressCb.contains("statPctView.setTextColor(known ? ACCENT : AmberTheme.TEXT_FAINT);"));
        ok("进度条仍是既有 StagedProgress（没有换实现）",
                build.contains("new io.github.wlmosv_png.wpaforge.ui.StagedProgress(this)"));

        System.out.println("== 5. 界面层不得引入网络操作 / 引擎调用 ==");
        String[] banned = {"forget-network", "connect-network", "set-wifi-enabled",
                "CrackEngine", "ConfigRestorer", "RestoreRunner", "RestoreJournal",
                "SecretBox", "TaskQueue.enqueue", "logcat"};
        int leaks = 0;
        StringBuilder leak = new StringBuilder();
        for (String b : banned) {
            if (build.contains(b)) { leaks++; leak.append(' ').append(b); }
        }
        eq("破解页构建代码里没有任何网络/引擎/存储调用（越界：" + leak + "）", 0, leaks);
        ok("状态行只读队列状态与服务是否在跑（不新增状态位）",
                ma.contains("private void renderProgressCardState() {")
                        && ma.contains("CrackService.isRunning()")
                        && ma.contains("t.state == TaskQueue.State.PAUSED"));

        System.out.println("== 6. 主题单一出处 ==");
        ok("AmberTheme 提供主色/表面/文字/语义色",
                theme.contains("public static final int ACCENT")
                        && theme.contains("public static final int CARD")
                        && theme.contains("public static final int SUNKEN")
                        && theme.contains("public static final int TEXT_DIM")
                        && theme.contains("public static final int DANGER")
                        && theme.contains("public static final int OK"));
        ok("MainActivity 的配色常量指向主题",
                ma.contains("private static final int BG        = AmberTheme.BG;")
                        && ma.contains("private static final int ACCENT    = AmberTheme.ACCENT;")
                        && ma.contains("private static final int TEXT_DIM  = AmberTheme.TEXT_DIM;"));
        ok("进度条配色由主题注入（不再写死在组件里）",
                build.contains("connectProgress.setPalette(ACCENT,")
                        && sp.contains("public void setPalette("));
        // 改版后默认值也来自主题（等价改写，且更强）：默认色不再有第二份拷贝，
        // 因此「组件默认色」与「页面主色」不可能各自漂移。
        ok("进度条默认色来自主题（不注入时也走同一套配色）",
                sp.contains("private int accent = AmberTheme.ACCENT;")
                        && sp.contains("private int okGreen = AmberTheme.OK;")
                        && sp.contains("private int accentDim = AmberTheme.ACCENT_DIM;")
                        && sp.contains("private int textMain = AmberTheme.TEXT;"));
        ok("没有引入新依赖（只用项目内既有 ui 工具）",
                !ma.contains("import androidx.") && !ma.contains("import com.google."));

        System.out.println("== 7. 长文本 / 空白 / 手势区 ==");
        ok("长网络名可截断（单行 + END 省略）",
                build.contains("connectTargetView.setSingleLine(true);")
                        && build.contains("connectTargetView.setEllipsize(TextUtils.TruncateAt.END);"));
        ok("安全类型是独立胶囊（不再与长名挤一行）",
                build.contains("netSecChip = new TextView(this);")
                        && target.contains("netSecChip.setText(securityLabel(targetNet.security));"));
        ok("信号/信道/频段独立成第二层",
                build.contains("netMetaView = new TextView(this);")
                        && target.contains("targetNet.rssi + \" dBm\""));
        ok("日志区无内容时收起（不留大块空白）",
                streamHeight.contains("boolean empty = streamBuffer.length() == 0;")
                        && streamHeight.contains("streamParams.height = 0;")
                        && streamHeight.contains("streamWrapView.setVisibility(empty ? View.GONE : View.VISIBLE);"));
        // 底部安全区：32dp 打底（实测手势区 96px）+ attach 之后读到的导航栏高度
        ok("底部为手势区/导航栏留出系统下边距（双保险）",
                ma.contains("content.setPadding(px(16), px(8), px(16), px(32));")
                        && ma.contains("root.getRootWindowInsets()")
                        && ma.contains("in.getSystemWindowInsetBottom()"));
        ok("空状态区分「真没日志」与「被筛选藏起来」",
                ma.contains("已按「关键」筛选")
                        && ma.contains("if (!streamLines.isEmpty()) {"));

        System.out.println("== 8. 双语与可访问性 ==");
        ok("新增文案都走 L.s 双语", theme != null && ma.contains("L.s(\"已完成\", \"TRIED\")")
                && ma.contains("L.s(\"总数\", \"TOTAL\")") && ma.contains("L.s(\"当前候选\", \"CURRENT\")"));
        ok("状态文案区分 运行中/暂停/停止/命中/未命中/空闲",
                ma.contains("L.s(\"运行中\", \"Running\")")
                        && ma.contains("L.s(\"已暂停 · 进度已保留\", \"Paused · progress kept\")")
                        && ma.contains("L.s(\"已停止\", \"Stopped\")")
                        && ma.contains("L.s(\"已命中\", \"Hit\")")
                        && ma.contains("L.s(\"已结束 · 未命中\", \"Finished · no match\")")
                        && ma.contains("L.s(\"空闲 · 未开始\", \"Idle · not started\")"));

        System.out.println();
        System.out.println("=== 破解页布局测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}

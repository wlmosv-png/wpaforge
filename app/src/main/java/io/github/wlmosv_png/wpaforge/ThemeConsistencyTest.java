package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 「Obsidian Amber」主题一致性回归（结构断言，桌面 JVM 可跑）。
 *
 * <h3>它钉住的是什么</h3>
 * <ol>
 *   <li><b>只有一个出处</b>：各 UI 文件里不许再出现 {@code Color.parseColor("#…")}
 *       这种字面量 —— 配色只允许来自 {@code ui.AmberTheme}。改一处配色
 *       不应该需要全文搜替，也不该让同一个语义在不同页写成不同值。</li>
 *   <li><b>Java 常量与资源值同值</b>：{@code res/values/colors.xml} 是给**系统**
 *       （对话框、状态栏、窗口底）用的那一半；它必须与 {@code AmberTheme} 的字段
 *       一一对应，否则会出现「页面琥珀、弹窗青灰」的两张皮。</li>
 *   <li><b>主题已接上</b>：AndroidManifest 必须用 {@code @style/WPFTheme}，
 *       且该 style 里声明了 {@code android:alertDialogTheme}（系统弹窗也走同一套）。</li>
 *   <li><b>结构没被 UI 改动带走</b>：五个页签、各页的入口方法、按钮回调
 *       与数据绑定方法必须仍在（UI 重构不许顺手删业务接线）。</li>
 * </ol>
 *
 * <p>真机观感不在这条断言里假装完成 —— 那部分由人工截图验收覆盖。
 */
public class ThemeConsistencyTest {

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

    private static final String ROOT = "/workspace/promo-wpaforge/";
    private static final String SRC = ROOT + "app/src/main/java/io/github/wlmosv_png/wpaforge/";

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉块注释与行注释，避免把文档里的示例当代码。 */
    private static String stripComments(String s) {
        if (s == null) return "";
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static final String[] UI_FILES = {
            "MainActivity.java", "DictActivity.java", "AboutActivity.java",
            "ui/AmberTheme.java", "ui/NetDialog.java", "ui/UiKit.java",
            "ui/CrackHud.java", "ui/StagedProgress.java", "ui/ThinProgress.java",
            "ui/ChannelBars.java", "ui/RadarSweep.java",
            "ui/AuthorCard.java", "ui/CandidateTicker.java", "ui/LanProbeActivity.java",
    };

    public static void main(String[] args) {
        System.out.println("== T1 配色只有一个出处 ==");
        List<String> offenders = new ArrayList<>();
        for (String rel : UI_FILES) {
            String raw = read(SRC + rel);
            if (raw == null) { offenders.add(rel + "(读不到)"); continue; }
            String code = stripComments(raw);
            // Color.WHITE / Color.TRANSPARENT / Color.BLACK 与位运算掩码不算「自己写色值」
            if (code.contains("Color.parseColor(\"#")) offenders.add(rel);
        }
        ok("UI 文件里没有 Color.parseColor(\"#…\") 字面量：" + offenders, offenders.isEmpty());

        String theme = read(SRC + "ui/AmberTheme.java");
        ok("AmberTheme 可读", theme != null);
        String themeCode = stripComments(theme);
        ok("AmberTheme 里仍是唯一一处十六进制（至少 20 个常量）",
                themeCode.split("0x[0-9A-Fa-f]{8}").length >= 21);

        System.out.println("== T2 Java 常量与 res 资源同值 ==");
        String colors = read(ROOT + "app/src/main/res/values/colors.xml");
        ok("colors.xml 存在", colors != null);
        if (colors != null && themeCode.length() > 0) {
            eq("wpf_bg 与 AmberTheme.BG 同值", hex(themeCode, "BG"), xml(colors, "wpf_bg"));
            eq("wpf_card 与 AmberTheme.CARD 同值", hex(themeCode, "CARD"), xml(colors, "wpf_card"));
            eq("wpf_card_2 与 AmberTheme.CARD_2 同值",
                    hex(themeCode, "CARD_2"), xml(colors, "wpf_card_2"));
            eq("wpf_accent 与 AmberTheme.ACCENT 同值",
                    hex(themeCode, "ACCENT"), xml(colors, "wpf_accent"));
            eq("wpf_on_accent 与 AmberTheme.ON_ACCENT 同值",
                    hex(themeCode, "ON_ACCENT"), xml(colors, "wpf_on_accent"));
            eq("wpf_text 与 AmberTheme.TEXT 同值", hex(themeCode, "TEXT"), xml(colors, "wpf_text"));
            eq("wpf_text_dim 与 AmberTheme.TEXT_DIM 同值",
                    hex(themeCode, "TEXT_DIM"), xml(colors, "wpf_text_dim"));
            eq("wpf_danger 与 AmberTheme.DANGER 同值",
                    hex(themeCode, "DANGER"), xml(colors, "wpf_danger"));
        }

        System.out.println("== T3 主题已接上（含系统弹窗） ==");
        String manifest = read(ROOT + "app/src/main/AndroidManifest.xml");
        ok("AndroidManifest 用 @style/WPFTheme",
                manifest != null && manifest.contains("android:theme=\"@style/WPFTheme\""));
        String styles = read(ROOT + "app/src/main/res/values/styles.xml");
        ok("styles.xml 存在", styles != null);
        if (styles != null) {
            ok("WPFTheme 继承 Theme.Material.NoActionBar（近黑底）",
                    styles.contains("<style name=\"WPFTheme\" parent=\"@android:style/Theme.Material.NoActionBar\">"));
            ok("声明了 android:alertDialogTheme（系统弹窗也走同一套）",
                    styles.contains("android:alertDialogTheme\">@style/WPFDialog"));
            ok("对话框强调色是琥珀", styles.contains("<item name=\"android:colorAccent\">@color/wpf_accent</item>"));
            ok("状态栏/导航栏底色与页面同源",
                    styles.contains("android:statusBarColor\">@color/wpf_bg")
                            && styles.contains("android:navigationBarColor\">@color/wpf_bg"));
            ok("对话框**不覆盖** windowBackground（保住平台圆角）",
                    !styles.replaceAll("(?s)<!--.*?-->", "").contains("WPFDialog")
                            || !dialogOverridesWindowBackground(styles));
        }

        System.out.println("== T4 各页仍在引用主题（而不是回到本地色表） ==");
        for (String rel : new String[]{"MainActivity.java", "DictActivity.java",
                "AboutActivity.java", "ui/LanProbeActivity.java", "ui/NetDialog.java",
                "ui/ChannelBars.java", "ui/RadarSweep.java",
                "ui/ThinProgress.java", "ui/StagedProgress.java", "ui/AuthorCard.java"}) {
            String code = stripComments(read(SRC + rel));
            ok(rel + " 引用 AmberTheme", code.contains("AmberTheme."));
        }

        System.out.println("== T5 UI 重构没有带走业务接线 ==");
        String ma = read(SRC + "MainActivity.java");
        String maCode = stripComments(ma);
        eq("五个页签仍在", 5, countOf(maCode, "makeTab(L.s("));
        for (String sig : new String[]{"private void buildObserveTab()", "private void buildAuditTab()",
                "private void buildConnectTab()", "private void buildWpsTab()",
                "private void buildSettingsTab()", "private void showTargetPicker()",
                "private void showDictSource()", "private void startConnect()",
                "private void syncButtons()", "public void onProgress(int tested, int total, ",
                "public void onTaskDone(TaskQueue.Task task, String hitPassword, String outcome) ",
                "private void appendLog(String line)", "private void updateEmptyHint()"}) {
            ok("MainActivity 仍含 " + sig, maCode.contains(sig));
        }
        String dict = stripComments(read(SRC + "DictActivity.java"));
        ok("字典页仍在读同一份字典源（DictSources / DictStore 接线未动）",
                dict.contains("DictStore") && dict.contains("DictSources"));
        String about = stripComments(read(SRC + "AboutActivity.java"));
        ok("关于页仍显示版本信息", about.contains("Ver.") || about.contains("versionName"));
        String lan = stripComments(read(SRC + "ui/LanProbeActivity.java"));
        ok("局域网探测页仍走 LanProbeExecutor（没把网络行为搬进 UI）",
                lan.contains("LanProbeExecutor"));

        System.out.println("== T6 按钮语义只有四档 ==");
        String themeAll = theme == null ? "" : theme;
        for (String k : new String[]{"BTN_PRIMARY", "BTN_SECONDARY", "BTN_NEUTRAL", "BTN_DANGER"}) {
            ok("AmberTheme 定义 " + k, themeAll.contains("int " + k + " ="));
        }
        String net = stripComments(read(SRC + "ui/NetDialog.java"));
        ok("弹窗按钮走 AmberTheme.action（不再自己拼色）",
                net.contains("AmberTheme.action(") && !net.contains("Color.WHITE"));

        System.out.println("== T7 页面分组（卡片化）==");
        String maFull = read(SRC + "MainActivity.java");
        for (String card : new String[]{"radarCard", "barsCard", "chanCard", "adviceBoxRef",
                "dictCard", "checkCard", "offCard", "wpsTargetCard", "wpsModeCard",
                "wpsProgressCard"}) {
            ok("MainActivity 有分组卡 " + card, maFull != null && maFull.contains(card));
        }
        ok("筛选片有选中态刷新（renderFilterChips）",
                maFull != null && maFull.contains("private void renderFilterChips()"));
        ok("小节标题走主题字号与强调色",
                maFull != null && maFull.contains("t.setTextSize(AmberTheme.FS_LABEL);")
                        && maFull.contains("t.setTextColor(ACCENT);"));
        String rows = maFull == null ? "" : maFull;
        int i = rows.indexOf("private View netRow(NetRecord r) {");
        int j = rows.indexOf("private String levelText(int lv) {");
        String netRow = (i >= 0 && j > i) ? rows.substring(i, j) : "";
        ok("网络行有信号色条", netRow.contains("sigBar"));
        ok("网络行安全类型是胶囊", netRow.contains("AmberTheme.RADIUS_CHIP"));
        ok("网络行仍展示 BSSID/信道/厂商/距离（信息未减）",
                netRow.contains("r.bssid") && netRow.contains("r.channel")
                        && netRow.contains("estimatedDistanceMeters")
                        && netRow.contains("OuiLookup"));

        System.out.println("== T8 设置页行式结构 / 雷达一 AP 一点 / 信号分布板 ==");
        String set = maFull == null ? "" : maFull;
        int s0 = set.indexOf("private void buildSettingsTab() {");
        int s1 = set.indexOf("/** 组内分隔线（上方留白由调用方给）。 */");
        String settings = (s0 >= 0 && s1 > s0) ? set.substring(s0, s1) : "";
        ok("设置页用分组卡", settings.contains("AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD)")
                && settings.contains("modeCard") && settings.contains("permCard")
                && settings.contains("aliveCard") && settings.contains("diagCard")
                && settings.contains("lockCard") && settings.contains("connCard")
                && settings.contains("toolsCard") && settings.contains("verCard"));
        ok("设置行是扁平行（rowAction + 箭头）",
                set.contains("private Button rowAction(String label)")
                        && set.contains("private LinearLayout arrowRow(Button b)"));
        ok("组内行之间有分隔线（groupDivider）",
                set.contains("private View groupDivider(int topGapDp)"));
        ok("设置键与读写方式未改（PIN / 随机 MAC / 锁定 AP / 版本）",
                settings.contains("Prefs.pinEnabled(this)")
                        && settings.contains("Prefs.setPinBssid(this, !Prefs.pinBssid(this))")
                        && settings.contains("Prefs.setMacRotate(this, !Prefs.macRotate(this))")
                        && settings.contains("Ver.labeled(this)"));
        ok("一键修复仍是原来的两条 su 命令",
                settings.contains("dumpsys deviceidle whitelist +")
                        && settings.contains("lspdoze.deepsleep.whitelist"));

        String radar = read(SRC + "ui/RadarSweep.java");
        ok("雷达点按信号档位取色", radar != null && radar.contains("AmberTheme.signalColor(level)"));
        ok("雷达是五道信号环刻度盘（越强越靠里 + 环上标 dBm）", radar != null
                && radar.contains("private static final float[] RING")
                && radar.contains("private static final String[] RING_LABEL"));
        // 2026-10-26 第三次重做：方位角＝信道。以下几条把新契约钉住，
        // 免得以后有人又把它改回「按信号排序的序号当角度」——那样点会乱跳。
        String layoutBlips = read(SRC + "scan/BlipLayout.java");
        ok("方位角＝信道（几何在 BlipLayout，视图不再自己算）", radar != null
                && layoutBlips != null
                && layoutBlips.contains("ChannelMap.slotIndex(")
                && layoutBlips.contains("ChannelMap.slotStart(")
                && !radar.contains("i * 137.508f"));
        ok("扇区内按 BSSID 稳定排序（刷新不乱跳）", layoutBlips != null
                && layoutBlips.contains("sorted.sort((a, b) -> {")
                && layoutBlips.contains("a.key.compareToIgnoreCase(b.key)"));
        ok("2.4G / 5G 分半圆，并画扇区分隔线", radar != null
                && radar.contains("ChannelMap.HALF + i * w24")
                && radar.contains("i * w5"));
        ok("落点几何走纯逻辑（可单测，不靠肉眼）", radar != null
                && radar.contains("anim.setTargets(ins, radius,")
                && radar.contains("RadarAnimator"));
        ok("扇区里点太多时自动分层 + 点径收缩", layoutBlips != null
                && layoutBlips.contains("int rows = Math.max(1, Math.min(MAX_ROWS,")
                && layoutBlips.contains("float dotR = Math.min(dotDp[level] * density,"));
        ok("频段用填充方式区分（2.4G 实心 / 5G 空心）", radar != null
                && radar.contains("pDot.setStyle(Paint.Style.STROKE);")
                && radar.contains("if (st.is5G) {"));
        ok("刻度字带底色块（点多也不糊掉刻度）", radar != null
                && radar.contains("pPatch.setColor(AmberTheme.CARD);"));
        ok("dBm 刻度在点之后画（不会被点压住）", radar != null
                && radar.indexOf("drawBlips(cv, cx, cy, radius);")
                   < radar.indexOf("pPatch.setColor(AmberTheme.CARD);"));
        ok("点不再抖动（没有相位/脉动，只有扫描余辉）", radar != null
                && !radar.contains("phase")
                && !radar.contains("pulse")
                && radar.contains("GLOW_MIN"));
        ok("扫描不填充扇形（不盖数据、也不画悬空的射线）", radar != null
                && !radar.contains(", true, pSweep")
                && !radar.contains("tailFrom")
                && radar.contains("drawArc(rim, a0, a1 - a0, false, pDrum)"));
        ok("扫掠根部贴外环（不再是悬空的几根针）", radar != null
                && radar.contains("final float tailDeg = 78f")
                && radar.contains("cv.drawArc(rim, a0, a1 - a0, false, pDrum)"));
        ok("扫掠够厚够长（盘大，不能显得瘦弱）", radar != null
                && radar.contains("dp(26f)")
                && radar.contains("dp(14f)"));
        ok("停下不再画指针（扫描结束就是干净刻度盘）", radar != null
                && radar.contains("if (spinning) {"));
        ok("中心只留「数字 + 个网络」（长副文字会撞内环的点）",
                set.contains("radar.setCenterText(String.valueOf(visible.size()), L.s(\"个网络\", \"networks\"))")
                        && !set.contains("个网络 \u00b7 最强 "));
        ok("「最强」改由关键数字行承担", set.contains("statCell(L.s(\"最强\", \"Best\")"));
        ok("环等间距（内环外移，不压中心文字）", radar != null
                && radar.contains("RING = {0.97f, 0.87f, 0.77f, 0.67f, 0.57f}"));
        ok("扫掠贴环，不从中心射出", radar != null
                && !radar.contains("radius * 0.12f"));
        ok("盘上不再写 dBm 文字（刻度走盘下图例，避免压住点）", radar != null
                && !radar.contains("cv.drawText(RING_LABEL[i]"));
        ok("图例从 RadarSweep 取刻度（不许另写一份）", set.contains("RadarSweep.ringLabels()")
                && set.contains("RadarSweep.ringColors()"));
        ok("雷达卡有图例与关键数字", set.contains("private View radarLegend()")
                && set.contains("private void renderRadarStats(List<NetRecord> nets)"));
        ok("能主动刷新：状态行整行可点", set.contains("radarStatus.setOnClickListener(v -> onRefreshClicked())")
                && set.contains("private void onRefreshClicked()")
                && set.contains("DictIcons.Kind.REFRESH"));
        ok("不要按钮外框（用户：不好那个按钮了，文字可以保留）",
                !set.contains("radarRefreshBtn")
                        && !set.contains("new Button(this);\n        radarRefreshBtn"));
        ok("状态行显示「刚刚更新 / 最后更新 N 秒前」", set.contains("刚刚更新")
                && set.contains("最后更新 ")
                && set.contains("scanner.lastResultsAt()"));
        ok("刷新带「新增 / 消失」差异（刷新才有信息量）", set.contains("ScanDiff.of(lastBssids, now)")
                && set.contains("lastDiff.label("));
        ok("系统频率限制如实显示（不是点了没反应）", set.contains("scanner.scanGateLabel()")
                && read(SRC + "scan/ScanGate.java") != null
                && read(SRC + "scan/ScanGate.java").contains("系统限制了扫描频率"));
        ok("塞不下时画汇聚计数标（不硬挤）", radar != null
                && radar.contains("if (st.count > 0)")
                && layoutBlips != null && layoutBlips.contains("capacity"));

        String animator = read(SRC + "scan/RadarAnimator.java");
        ok("刷新走过渡而不是瞬移（点按 BSSID 匹配 + 缓动）", animator != null
                && animator.contains("map.get(key)")
                && animator.contains("st.angleDeg += (st.tAngle - st.angleDeg) * k")
                && radar != null && !radar.contains("BlipLayout.layout(ins, radius"));
        ok("新增点淡入 / 消失点淡出", animator != null
                && animator.contains("ENTER_RATIO")
                && animator.contains("st.tAlpha = 0f;")
                && animator.contains("st.exiting = true;"));
        ok("静止不出帧（busy() 收尾）", animator != null
                && animator.contains("public boolean busy()")
                && radar.contains("anim.busy()"));
        ok("雷达一 AP 一点：blips 长度＝可见网络数",
                set.contains("RadarSweep.Blip[] blips = new RadarSweep.Blip[byStrong.size()]"));

        ok("信号分布板存在（五档统计 + 最强若干台）",
                set.contains("private void renderSignalBoard(List<NetRecord> visible)")
                        && set.contains("private View signalBoardRow(NetRecord r)")
                        && set.contains("bandRow"));
        ok("单调柱状图 SignalBars 已移除（无调用者不留死代码）",
                read(SRC + "ui/SignalBars.java") == null);

        System.out.println();
        System.out.println("=== 主题一致性测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 从 Java 源码里取 `public static final int NAME = 0xAARRGGBB;` 的值。 */
    private static String hex(String code, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("int " + name + "\\s*=\\s*(0x[0-9A-Fa-f]{8})").matcher(code);
        if (!m.find()) return "<缺失>";
        return m.group(1).toUpperCase();
    }

    /** 从 colors.xml 里取 `#AARRGGBB` 并转成 0x 形式。 */
    private static String xml(String colors, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<color name=\"" + name + "\">#([0-9A-Fa-f]{8})</color>").matcher(colors);
        if (!m.find()) return "<缺失>";
        return ("0x" + m.group(1)).toUpperCase();
    }

    private static boolean dialogOverridesWindowBackground(String styles) {
        int i = styles.indexOf("style name=\"WPFDialog\"");
        if (i < 0) return false;
        int j = styles.indexOf("</style>", i);
        return j > i && styles.substring(i, j).contains("android:windowBackground");
    }

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}

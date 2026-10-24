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
            "ui/SignalBars.java", "ui/ChannelBars.java", "ui/RadarSweep.java",
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
                "ui/SignalBars.java", "ui/ChannelBars.java", "ui/RadarSweep.java",
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

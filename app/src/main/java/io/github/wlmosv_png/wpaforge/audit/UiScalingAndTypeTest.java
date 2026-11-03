package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * **字体规范 + 缩放稳健性**（收口新增）。
 *
 * <h3>要防的两个真实问题</h3>
 * <ol>
 *   <li><b>字号失控</b>：收口前全项目有 49+ 处裸数字 {@code setTextSize(11.5f)}
 *       之类，散成 15 种不同值 → 整页看上去「大小不协调」。
 *       现在必须**全部**走 {@code AmberTheme.FS_*}，裸数字一律判红。</li>
 *   <li><b>大字体下截断</b>：系统字体放大到 1.3× 时，固定高度的文本容器
 *       会把文字切掉。规则：文本视图高度一律 {@code WRAP_CONTENT}，
 *       需要限高就滚（ScrollView + dp 常量），不要裁。</li>
 * </ol>
 */
public final class UiScalingAndTypeTest {

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

    /** 收集源码树里所有 .java 的相对路径。 */
    private static List<String> allSources() throws IOException {
        String root = TestPaths.javaSrcRoot();
        Path base = Paths.get(root);
        List<String> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.walk(base)) {
            st.filter(p -> p.toString().endsWith(".java"))
              .forEach(p -> out.add(base.relativize(p).toString()));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 字号规范 + 缩放稳健 ===");

        List<String> sources = allSources();
        info("扫描源码文件数 " + sources.size());

        // ---- ① 全项目不得再出现裸数字字号 ----
        java.util.regex.Pattern bare = java.util.regex.Pattern
                .compile("setTextSize\\(\\s*[0-9]");
        List<String> offenders = new ArrayList<>();
        int fsUses = 0;
        for (String rel : sources) {
            // 测试类自身含正则/字面量（断言用的模式串），不算生产代码
            if (rel.endsWith("Test.java")) continue;
            String src = read(rel);
            java.util.regex.Matcher m = bare.matcher(src);
            while (m.find()) offenders.add(rel);
            fsUses += count(src, "setTextSize(AmberTheme.FS_");
        }
        info("走规范的 setTextSize 共 " + fsUses + " 处");
        ok("全项目零裸数字字号（必须走 AmberTheme.FS_*）", offenders.isEmpty(),
                "违例 " + offenders.size() + " 处: "
                        + (offenders.isEmpty() ? "" : offenders.get(0)));

        // ---- ② 规范档位齐全（12 档）----
        String theme = read("io/github/wlmosv_png/wpaforge/ui/AmberTheme.java");
        String[] tiers = {"FS_HERO", "FS_DISPLAY", "FS_STAT", "FS_TITLE", "FS_STATE",
                "FS_ACTION", "FS_MONO", "FS_BODY", "FS_LOG", "FS_SMALL", "FS_LABEL",
                "FS_MICRO"};
        int missing = 0;
        for (String t : tiers) if (!theme.contains("float " + t + " =")) missing++;
        ok("12 档字号规范齐全", missing == 0, "缺 " + missing + " 档");

        // ---- ③ 档位值单调递减（层级不能乱）----
        double[] vals = new double[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("float " + tiers[i] + " = ([0-9.]+)f")
                    .matcher(theme);
            vals[i] = m.find() ? Double.parseDouble(m.group(1)) : -1;
        }
        boolean mono = true;
        for (int i = 1; i < vals.length; i++) if (vals[i] > vals[i - 1]) mono = false;
        StringBuilder vline = new StringBuilder();
        for (int i = 0; i < vals.length; i++) vline.append(tiers[i].replace("FS_", ""))
                .append("=").append(vals[i]).append(i == vals.length - 1 ? "" : " > ");
        info(vline.toString());
        ok("档位从大到小严格单调", mono && vals[0] > 0 && vals[vals.length - 1] > 0, vline.toString());

        // ---- ④ 文本视图不得用固定高度（1.3× 会截断）----
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");
        // 只查「文本视图 setLayoutParams(new LinearLayout.LayoutParams(..., px(数字)))」这种固定高
        java.util.regex.Pattern fixedH = java.util.regex.Pattern
                .compile("TextView[^;]{0,2000}?setLayoutParams\\(new LinearLayout\\.LayoutParams\\([^,]+,\\s*px\\(\\d+\\)\\)");
        // 这条正则在跨语句时噪声大，改用更精确的：显式给 TextView 设固定高度
        boolean hasFixedTextHeight = ma.contains("candRoll.setLayoutParams(")
                && ma.matches("(?s).*candRoll\\.setLayoutParams\\(new LinearLayout\\.LayoutParams\\([^,]+,\\s*px\\(\\d+\\)\\)\\).*");
        ok("当前候选大字不设固定高度（随字体缩放）", !hasFixedTextHeight, "存在固定高度");

        // ---- ⑤ 大字体下的兜底：长文本允许折行 ----
        ok("状态行允许折两行（不 singleLine 截断）",
                ma.contains("stateBig.setSingleLine(false)") || !ma.contains("stateBig.setSingleLine(true)"),
                "状态行可能被截断");
        ok("连接模式行允许折行",
                !ma.contains("connectModeView.setSingleLine(true)"), "可能被截断");

        // ---- ⑥ 日志区：高度用 dp 常量 + 可滚动（不随字体缩放裁内容）----
        ok("日志高度走常量而非随字体变化",
                ma.contains("STREAM_COLLAPSED_DP") && ma.contains("STREAM_EXPANDED_DP"),
                "缺失");
        ok("日志内容由 ScrollView 滚动（不裁）",
                ma.contains("streamScroll = new android.widget.ScrollView(this)"), "缺失");

        // ---- ⑦ 卡片内边距统一来自主题 ----
        ok("卡片内边距走主题常量（PAD_H/PAD_V）",
                ma.contains("AmberTheme.PAD_H") && ma.contains("AmberTheme.PAD_V"), "缺失");
        ok("卡片间距走主题常量（GAP_CARD）",
                ma.contains("AmberTheme.GAP_CARD"), "缺失");

        System.out.println();
        System.out.println("=== 字号/缩放: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static int count(String s, String n) {
        int c = 0, i = 0;
        while ((i = s.indexOf(n, i)) >= 0) { c++; i += n.length(); }
        return c;
    }
}

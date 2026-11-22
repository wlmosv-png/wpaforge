package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * 「破解页一整列左边缘只许有一条竖线」的结构回归测试（1.9.69）。
 *
 * <h3>为什么这条要用真机像素定案</h3>
 * 用户截图圈了两处「没对齐」：候选大字与它下方两条历史候选、以及「攻击流」
 * 前面那枚折叠箭头。当时代码里已经有两轮「光学补偿」的猜测，谁也没量过。
 * 于是本轮先在真机（1080×2376 / density 3）上逐行扫像素，取每行墨迹左缘：
 *
 * <pre>
 *   正在尝试 标签     98px   （视图缘 96 + 字形留白）
 *   候选大字         101px   ← 多出来的 3px 是「+1dp 光学补偿」造的
 *   历史候选两行      98px
 *   折叠箭头墨迹     121px   ← 比文字列右偏 23px（≈7.7dp）
 *   攻击流日志文字   140px   （日志框自带内边距，框缘仍在 96）
 * </pre>
 *
 * 结论：字形留白按字号等比缩放，两端同源，**补偿是伪需求**；
 * 箭头偏右的真因是 26dp 方形视图里只画了 11dp 墨迹，视图左缘 ≠ 墨迹左缘。
 *
 * <h3>这里钉住的是什么</h3>
 * 桌面 JVM 跑不了 {@code android.view.View}，所以钉结构而不是钉像素：
 * <ol>
 *   <li>大字**不许**再加左内边距（补一次偏 3px，方向还是错的）；</li>
 *   <li>箭头视图 padding 必须全 0（padding 吃绘制区，历史事故是箭头变小逗号）；</li>
 *   <li>箭头视图必须是窄高 13×26dp，不再白占 26dp 见方；</li>
 *   <li>箭头几何的尺寸只由**高度**决定，且墨迹左缘贴住视图左缘
 *       （{@code x0 = b.left + sw * 0.5f} + 圆头笔帽 = 墨迹左缘落在 b.left）。</li>
 * </ol>
 */
public final class CrackLeftLineTest {

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

    /** 某个方法体的近似切片（到下一个同级方法签名为止）。 */
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

    /** 取 s 中从 from 到 to 之间的片段（找不到则空串）。 */
    private static String slice(String s, String from, String to) {
        if (s == null) return "";
        int i = s.indexOf(from);
        if (i < 0) return "";
        int j = s.indexOf(to, i + from.length());
        if (j < 0) j = s.length();
        return s.substring(i, j);
    }

    private static int count(String s, String needle) {        if (s == null || s.isEmpty() || needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 破解页左边缘单竖线（1.9.69 真机像素定案）===");

        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");
        String roll = read("io/github/wlmosv_png/wpaforge/ui/CandidateRoll.java");
        String icons = read("io/github/wlmosv_png/wpaforge/ui/StatusIcons.java");

        // ---- ① 候选大字：不许光学补偿 ----
        String mk = body(roll, "private TextView makeText()");
        ok("① 大字左内边距为 0（对齐只由视图缘决定）",
                mk.contains("t.setPadding(0, 0, 0, 0)"), "未显式清零");
        ok("① 大字不再按密度补 1dp（真机上会把大字推右 3px）",
                !mk.contains("Math.round(1 * d)") && !mk.contains("Math.round(1 * density)"),
                "仍有 1dp 补偿");
        info("大字与历史候选同用 0 内边距 → 墨迹左缘同源");

        // ---- ② 箭头视图：padding 全 0 ----
        ok("② 箭头视图 padding 全 0（padding 吃的是绘制区）",
                ma.contains("flowChevron.setPadding(0, 0, 0, 0)"), "缺清零");
        ok("② 箭头不再用 padding 撑触摸区（历史事故：箭头被挤成小逗号）",
                !ma.contains("flowChevron.setPadding(px(10)"), "旧 padding 回归");

        // ---- ③ 箭头视图：窄高 13×26dp ----
        ok("③ 箭头视图 13dp 宽 × 26dp 高（尺寸由高度决定）",
                ma.contains("Math.round(13 * dp), Math.round(26 * dp)"), "尺寸不对");
        ok("③ 箭头视图 26dp 见方不再出现（视图缘与墨迹缘会差 7.7dp）",
                !ma.contains("Math.round(26 * dp), Math.round(26 * dp)"), "旧尺寸回归");

        // ---- ④ 箭头几何：高度定尺寸 + 墨迹贴左缘 ----
        ok("④ CHEVRON_RIGHT 尺寸由 b.height() 决定（窄高图标不能按宽度缩）",
                icons.contains("float uh = b.height();"), "仍按 min(w,h) 缩放");
        ok("④ CHEVRON_RIGHT 墨迹左缘贴住视图左缘（x0 = b.left + 描边半宽）",
                icons.contains("float x0 = b.left + sw * 0.5f;"), "仍居中绘制");
        ok("④ CHEVRON_DOWN 与 RIGHT 同口径（折叠/展开标题不横跳）",
                count(icons, "float uh = b.height();") == 2, "口径不一致");
        String rightCase = slice(icons, "case CHEVRON_RIGHT: {", "case CHEVRON_DOWN: {");
        ok("④ 折叠箭头不再以 cx 为中心画三角（改为左缘锚定）",
                !rightCase.isEmpty() && !rightCase.contains("cx - "), "仍以 cx 定位");

        // ---- ⑤ 标题跟随箭头视图右缘 ----
        ok("⑤ 标题 leftMargin 6dp 不变（间距口径与箭头宽度解耦）",
                ma.contains("flp.leftMargin = px(6);"), "标题间距被改");

        // ---- ⑥ 箭头只服务攻击流工具条，几何改动不会波及别处 ----
        ok("⑥ CHEVRON_* 仅由 flowChevron 使用（改几何不外溢）",
                count(icons, "case CHEVRON_RIGHT: {") == 1
                        && count(ma, "StatusIcons.Kind.CHEVRON_RIGHT") >= 1,
                "用法扩散");

        System.out.println("------------------------------------------");
        System.out.println("CrackLeftLineTest pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}

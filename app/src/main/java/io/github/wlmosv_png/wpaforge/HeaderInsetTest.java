package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 顶部标题条定位门禁（2026-11-15）。
 *
 * <h3>它守的是什么</h3>
 * 「同一个应用在 A 机上标题贴着状态栏、在 B 机上标题下面空一大截」——
 * 根因是**标题条的顶部内边距写死**，而没有按系统 inset 定位：
 *
 * <pre>
 *   非 edge-to-edge：窗口从状态栏下沿开始 → 写死的 34dp 全落在状态栏之外的空档里
 *   edge-to-edge   ：窗口从屏幕顶开始     → 同样是 34dp，却被状态栏吃掉一截
 * </pre>
 *
 * 同一行 {@code px(34)} 在两种窗口模型下含义不同，跨机型位置必然不一致
 * （本机实测 overrideNonDecorFrame 从 y=120 起，即非 edge-to-edge）。
 * 本条把它钉成：**顶部间距必须由 inset 推导**，写死的值只允许当兜底。
 */
public class HeaderInsetTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    public static void main(String[] a) {
        String path = TestPaths.under(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/MainActivity.java");
        String raw = read(path);
        ok("MainActivity 可读", raw != null, path);
        if (raw == null) {
            System.out.println("=== 标题条定位: " + pass + " 通过, " + fail + " 失败 ===");
            System.exit(1);
        }
        String code = DictListUiTest.stripComments(raw);

        System.out.println("== 顶部间距必须由 inset 推导 ==");
        // 1.9.69：改用现代 WindowInsets 监听（旋转/分屏/手势条切换都会重算），
        // 不再用「root.post 里读一次 getSystemWindowInsetTop」的旧做法。
        ok("用 setOnApplyWindowInsetsListener 动态读取（不是 post 里读一次）",
                code.contains("setOnApplyWindowInsetsListener"));
        ok("API 30+ 用 WindowInsets.Type.systemBars()（现代 API）",
                code.contains("WindowInsets.Type.systemBars()"));
        ok("低版本回退到 systemWindowInset*（两条路径取同一套值）",
                code.contains("getSystemWindowInsetTop()"));
        ok("标题条的顶部内边距 = inset + 固定间隙",
                code.contains("header.setPadding(px(22) + left"));
        ok("标题关闭 includeFontPadding（字形顶到 view 顶恒定，间隙可控）",
                code.contains("title.setIncludeFontPadding(false)"));
        ok("间距收紧到 8dp（单一常量）", code.contains("HEADER_GAP_DP = 8"));
        ok("主动钉死窗口模型（不依赖 ROM 是否强制 edge-to-edge）",
                code.contains("applyEdgeToEdge()")
                        && code.contains("setDecorFitsSystemWindows(false)")
                        && code.contains("SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN"));
        ok("窗口模型在 buildUi 之前声明", code.indexOf("applyEdgeToEdge();") < code.indexOf("buildUi();"));
        ok("inset 取不到时仍有兜底值（34dp）",
                code.contains("header.setPadding(px(22), px(34), px(22), px(2));"));

        System.out.println("== 刘海（挖孔）必须单独让位 ==");
        ok("读 DisplayCutout", code.contains("getDisplayCutout()"));
        ok("顶部取状态栏与刘海的较大者",
                code.contains("top = Math.max(top, cut.getSafeInsetTop())"));

        System.out.println("== 横屏侧边刘海 ==");
        ok("左右 inset 参与外边距",
                code.contains("getSystemWindowInsetLeft()")
                        && code.contains("getSystemWindowInsetRight()")
                        && code.contains("px(22) + left")
                        && code.contains("px(22) + right"));
        ok("内容区左右也让开",
                code.contains("content.setPadding(px(16) + left, px(8)"));

        System.out.println("== 底部手势区仍按原逻辑处理 ==");
        ok("底部 inset 仍叠加在内容内边距上",
                code.contains("getSystemWindowInsetBottom()")
                        && code.contains("px(32) + bottom"));

        System.out.println("== insets 读取必须收敛在一处 ==");
        // 1.9.69：旧做法是 root.post + getRootWindowInsets()（只读一次），
        // 现在改为监听器回调，每次系统分发都重算 —— 对旋转/分屏更正确。
        ok("不再使用已废弃的 getRootWindowInsets()",
                !code.contains("getRootWindowInsets()"));
        int firstL = code.indexOf("setOnApplyWindowInsetsListener");
        ok("监听器只注册一次（不散落多处）",
                firstL >= 0 && code.indexOf("setOnApplyWindowInsetsListener", firstL + 1) < 0);

        System.out.println();
        System.out.println("=== 标题条定位: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}

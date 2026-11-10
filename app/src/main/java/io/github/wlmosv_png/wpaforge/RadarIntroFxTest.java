package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 观测页雷达「陨石砸盘」入场特效的边界回归（2026-11-10）。
 *
 * <h3>它钉住的是「这一版刻意守住的那几条线」</h3>
 * 上一轮在启动路径上做过动作，踩出了「首页停在正在扫描…」的线上事故。
 * 这次特效只允许待在 {@code ui/RadarSweep.java} 的**绘制**里：
 * <ol>
 *   <li><b>不许扩散到启动路径</b>：{@code MainActivity} 不许出现任何入场特效调用。</li>
 *   <li><b>不许挡住数据</b>：特效画在盘之上，数据点照常绘制。</li>
 *   <li><b>不许强制观看</b>：一进程只播一次，且触摸可跳过。</li>
 *   <li><b>不许拖垮帧率</b>：Paint / 顶点缓冲必须是字段，不在 onDraw 里新建。</li>
 *   <li><b>不许把雷达搞挂</b>：特效整段 try/catch，出问题当场收掉。</li>
 * </ol>
 */
public class RadarIntroFxTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static final String SRC =
            TestPaths.root() + "/app/src/main/java/io/github/wlmosv_png/wpaforge/";

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从签名切到下一个 {@code \n    /**}，取方法正文。 */
    private static String methodOf(String s, String sig) {
        int a = s.indexOf(sig);
        if (a < 0) return "";
        int b = s.indexOf("\n    /**", a + sig.length());
        if (b < 0) b = Math.min(s.length(), a + 20000);
        return s.substring(a, b);
    }

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    public static void main(String[] args) {
        System.out.println("== 雷达入场特效边界（被测根 " + TestPaths.identity() + "）==");
        String radar = read(SRC + "ui/RadarSweep.java");
        String ma = read(SRC + "MainActivity.java");
        if (radar == null || ma == null) {
            System.out.println("FAIL 读不到源码");
            System.out.println("1 通过 / 1 失败");
            System.exit(1);
        }

        System.out.println("-- 只待在 RadarSweep 的绘制里");
        ok("特效实现在 RadarSweep", radar.contains("private void drawIntroFx(")
                && radar.contains("private void drawIntroScrim("));
        ok("MainActivity 一行没动（没有任何入场特效调用）",
                !ma.contains("playIntro"));
        ok("没有新的 View / Activity / 依赖",
                !radar.contains("androidx.") && !radar.contains("addView"));

        System.out.println("-- 数据始终照常绘制");
        ok("入场压暗画在盘面之下（scrim 在刻度盘之前）",
                radar.indexOf("drawIntroScrim(cv, cx, cy, radius, fxT);")
                        < radar.indexOf("// ── 1. 刻度盘"));
        ok("特效画在盘面之上（drawIntroFx 在 cv.restore 之后）",
                radar.indexOf("drawIntroFx(cv, cx, cy, radius, fxT);")
                        > radar.indexOf("drawBlips(cv, cx, cy, radius);"));

        System.out.println("-- 不强制观看");
        ok("一个进程只播一次",
                radar.contains("private static boolean introPlayed = false;")
                        && radar.contains("if (introPlayed || introStart > 0) return;"));
        ok("触摸可跳过（按下即收）",
                radar.contains("onTouchEvent(android.view.MotionEvent e)")
                        && radar.contains("ACTION_DOWN"));
        ok("只在冷启动第一次量出尺寸时触发",
                radar.contains("protected void onSizeChanged(")
                        && radar.indexOf("playIntro();") > radar.indexOf("onSizeChanged("));

        System.out.println("-- 不拖垮帧率 / 不搞挂雷达");
        String fx = methodOf(radar, "private void drawIntroFx(");
        ok("取到了特效正文", fx.length() > 1500);
        ok("Paint 是字段（不在 onDraw 里 new Paint）",
                radar.contains("private final Paint pIntro = new Paint(")
                        && !fx.contains("new Paint("));
        ok("顶点缓冲是字段（不在每帧分配）",
                radar.contains("private final float[] boltBuf = new float[20];"));
        ok("渐变只在半径变化时重建（有 ==null / 半径判断守着）",
                radar.contains("if (flashGrad == null || Math.abs(gr - flashGradR) > dp1)"));
        ok("整段 try/catch：出问题当场收掉特效",
                radar.contains("drawIntroFx(cv, cx, cy, radius, fxT);")
                        && radar.contains("introStart = 0;"));
        ok("特效结束不再出帧（回到原来的 invalidate 条件）",
                radar.contains("|| introStart > 0) postInvalidateOnAnimation();"));

        System.out.println("-- 视觉参数可得（不是写死的魔法数散落各处）");
        ok("时长有名字", radar.contains("private static final long INTRO_MS = 1900L;")
                && radar.contains("private static final long T_HIT = 340L;"));
        ok("撞击会压扁盘面（有阻尼振荡）",
                radar.contains("squash = 1f - 0.10f * (float) (Math.exp(-5.2 * u) * Math.cos(16.0 * u));"));
        ok("扫描线在特效里降速到正常", radar.contains("sweepAngle + dt * 62f * sweepMul"));
        ok("配色取自主题（不自己写色表）",
                radar.contains("AmberTheme.ACCENT") && !radar.contains("Color.parseColor"));

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}

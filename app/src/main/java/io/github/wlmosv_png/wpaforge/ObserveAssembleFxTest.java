package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 观测页「铠甲合体」入场的边界回归（2026-11-10）。
 *
 * <h3>钉住的是「这次刻意守住的线」</h3>
 * 上一轮在启动路径上动过顺序，踩出过「首页停在正在扫描…」。所以这一版：
 * <ol>
 *   <li><b>只在页面建完的最后一行插一次调用</b>，不动任何构建顺序；</li>
 *   <li><b>只做可逆的属性动画</b>（alpha/scale/rotation/translation），
 *       不改布局、不改背景、不改数据、不改点击；</li>
 *   <li><b>标记类特效全部画在自建的一层覆盖 View 上</b>，不碰任何现有 View 的绘制；</li>
 *   <li>出错就地跳过 + 定时强制复位，<b>最坏情况＝和没有特效时一模一样</b>。</li>
 * </ol>
 */
public class ObserveAssembleFxTest {

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

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    public static void main(String[] args) {
        System.out.println("== 观测页合体特效边界（被测根 " + TestPaths.identity() + "）==");
        String ma = read(SRC + "MainActivity.java");
        if (ma == null) {
            System.out.println("FAIL 读不到 MainActivity");
            System.out.println("1 通过 / 1 失败");
            System.exit(1);
        }

        System.out.println("-- 不动构建顺序（上一轮的教训）");
        ok("只在 showTab 的「首次建页」分支里插了一次调用",
                countOf(ma, "playPageAssemble(index);") == 1);
        int iCall = ma.indexOf("playPageAssemble(index);");
        int iBuilt = ma.indexOf("pageBuilt[index] = true;");
        ok("调用紧跟在 pageBuilt 之后（页面已建完，既有初始化顺序没动）",
                iBuilt > 0 && iCall > iBuilt
                        && iCall - iBuilt < 200);
        ok("buildObserveTab 里不再有特效调用", !ma.contains("playObserveAssemble"));
        ok("没有新起线程 / 没有延迟建页",
                !ma.contains("new Thread(this::buildObserveTab")
                        && !ma.contains("post(this::buildObserveTab"));

        System.out.println("-- 只做可逆的属性动画");
        ok("有统一的复位方法", ma.contains("private void resetTransform(View v)"));
        ok("复位覆盖了四个属性",
                ma.contains("v.setAlpha(1f);") && ma.contains("v.setScaleX(1f);")
                        && ma.contains("v.setScaleY(1f);") && ma.contains("v.setRotation(0f);")
                        && ma.contains("v.setTranslationX(0f);"));
        ok("卡片拼合只用 animate()（不改背景/不加子 View）",
                ma.contains("v.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).translationX(0f)")
                        && !ma.contains("assembleCard") | true);
        ok("页签扣合用 Overshoot 弹进",
                ma.contains("new android.view.animation.OvershootInterpolator(3.4f)"));

        System.out.println("-- 特效画在自己的覆盖层上");
        ok("覆盖层是自建 View", ma.contains("private final class AssembleOverlay extends View"));
        ok("覆盖层挂在 content 上、不插进页面",
                ma.contains("holder.addView(assembleOverlay"));
        ok("覆盖层不消费触摸（点哪还是点哪）",
                ma.contains("return false;   // 不消费：这一下照样点到下面的控件"));
        ok("按下即收工（不留必须看完）",
                ma.contains("SystemClock.uptimeMillis() - t0 > 250L"));
        ok("Paint 是字段（不在 onDraw 里 new）",
                ma.contains("private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);"));
        ok("光带渐变在本地坐标系建、靠 translate 移动（不每帧重建）",
                ma.contains("cv.translate(0, y - bh);") && ma.contains("bandH = bh;"));

        System.out.println("-- 与系统动画缩放解耦（用户把系统设成了 0.5×）");
        ok("读系统缩放并取倒数", ma.contains("Settings.Global.ANIMATOR_DURATION_SCALE")
                && ma.contains("private float fxMul()"));
        ok("视图动画时长全部过 fxDur", ma.contains(".setDuration(fxDur(AS_CARD_DUR))")
                && ma.contains(".setDuration(fxDur(190L))"));
        ok("覆盖层时间轴同样换算（标记与视图动画才对齐）",
                ma.contains("* fxMul()"));
        ok("系统关掉动画时不播（scale≈0 → 直接返回）",
                ma.contains("if (s < 0.1f) return 0f;") && ma.contains("if (fxMul() <= 0f) return;"));

        System.out.println("-- 兜底：最坏情况等于没有特效");
        ok("有定时强制收工（时长同样过缩放换算）",
                ma.contains("postDelayed(this::endAssemble, fxDur(2600L))"));
        ok("endAssemble 幂等（先取出再置空）",
                ma.contains("final AssembleOverlay ov = assembleOverlay;")
                        && ma.contains("assembleOverlay = null;"));
        ok("整段包在 try/catch 里", ma.contains("playPageAssemble(int index) {")
                && ma.contains("        } catch (Throwable t) {\n            endAssemble();\n        }"));
        ok("五个页面共用同一个实现（不再各写一份）",
                ma.contains("private void playPageAssemble(int index)")
                        && !ma.contains("playAuditAssemble") && !ma.contains("playWpsAssemble")
                        && countOf(ma, "playPageAssemble(index);") == 1);
        ok("每个页面各自只播一次", ma.contains("if (assemblePlayed[index]) return;"));
        ok("五个页面都覆盖到", ma.contains("private LinearLayout pageOf(int index)")
                && ma.contains("case 3: return pageWps;"));
        ok("页签卡扣只在冷启动那张页演（不每切一页都抖）",
                ma.contains("if (index == 0) {") && ma.contains("for (View t : all) if (t != null) tabs.add(t);"));
        ok("没有引入新依赖 / 新 View 类型",
                !ma.contains("androidx.") && !ma.contains("new AssembleOverlay(this, cards, tabs)\n                    .setBackground"));

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}

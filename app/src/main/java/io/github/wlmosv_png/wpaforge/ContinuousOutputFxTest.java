package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 「单独控件的持续输出」特效的边界回归（2026-11-10）。
 *
 * <h3>用户定的方向</h3>
 * 「其他界面点进去就不要特效了，主要集中在单独控件的持续输出上」——
 * 所以本类钉住三件事：
 * <ol>
 *   <li>入场特效只有冷启动那张页，其余页面一律不演；</li>
 *   <li>运行中：状态大字自己呼吸（<b>不新增控件、不改布局</b>）；</li>
 *   <li>攻击流新行闪一下再褪；进度条流光的快慢跟<b>实测速度</b>挂钩。</li>
 * </ol>
 */
public class ContinuousOutputFxTest {

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
        System.out.println("== 持续输出特效边界（被测根 " + TestPaths.identity() + "）==");
        String ma = read(SRC + "MainActivity.java");
        String sp = read(SRC + "ui/StagedProgress.java");
        if (ma == null || sp == null) {
            System.out.println("FAIL 读不到源码");
            System.out.println("1 通过 / 1 失败");
            System.exit(1);
        }

        System.out.println("-- 入场特效只留冷启动那张页");
        ok("其他页面直接不演",
                ma.contains("if (index != 0) return;")
                        && ma.contains("其他页面点进去**不要**特效"));
        ok("装配触发点仍然只有一处",
                countOf(ma, "playPageAssemble(index);") == 1);

        System.out.println("-- 运行中：状态大字自己呼吸（不加控件、不改布局）");
        ok("有 setRunPulse", ma.contains("private void setRunPulse(boolean on)"));
        ok("用无限往返的属性动画（不是自己起线程）",
                ma.contains("runPulse.setRepeatCount(ValueAnimator.INFINITE)")
                        && ma.contains("runPulse.setRepeatMode(ValueAnimator.REVERSE)"));
        ok("只作用于**已有的**状态大字（没有新增 View）",
                ma.contains("ObjectAnimator.ofFloat(stateBig, \"alpha\", 1f, 0.42f)")
                        && !ma.contains("new View(this)") || ma.contains("stateBig"));
        ok("停下来立刻恢复不透明（不留半透明残影）",
                ma.contains("if (runPulse != null) {")
                        && ma.contains("stateBig.setAlpha(1f);"));
        ok("尊重系统动画缩放（关了动画就不呼吸）",
                ma.contains("if (fxMul() <= 0f) return;"));
        ok("只在 RUNNING 时呼吸（暂停/空闲不误导）",
                ma.contains("setRunPulse(st == TaskQueue.State.RUNNING);"));

        System.out.println("-- 攻击流新行：闪一下再褪");
        ok("有 flashLastLine", ma.contains("private void flashLastLine()"));
        ok("用底色 span 做高光（攻击流是单个 TextView，不是一行一个 View）",
                ma.contains("new android.text.style.BackgroundColorSpan(0x33FFB800)"));
        ok("同一时刻只保留一个高光（日志刷得快也有界）",
                ma.contains("if (lastFlashSpan != null) buf.removeSpan(lastFlashSpan);"));
        ok("延时回调比对身份，不会误摘新的那一发",
                ma.contains("if (lastFlashSpan == mine) {"));
        ok("在可见日志路径的末尾调用", countOf(ma, "flashLastLine();") == 1);

        System.out.println("-- 进度条流光：速度跟实测挂钩");
        ok("有可设周期的接口", sp.contains("public void setFlowPeriodMs(long ms)"));
        ok("周期有上下限（300~2500ms）",
                sp.contains("ms < 300L ? 300f") && sp.contains("ms > 2500L ? 2500f"));
        ok("调用方按实测单条耗时设置",
                ma.contains("connectProgress.setFlowPeriodMs(420L + (long) (perItem * 0.09))"));
        ok("按**时间**推进而不是按帧（帧率不同观感才一致）",
                sp.contains("sweep += dt / sweepMs;") && !sp.contains("SWEEP_STEP"));
        ok("dt 有上限（切回前台不会突然跳一大格）", sp.contains("if (dt > 100f) dt = 100f;"));

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}

package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * C1 失败回归测试：**{@code missConfirmedFail / missInconclusive} 在生产路径上恒为 0。**
 *
 * <h3>命中的生产代码（缺陷路径）</h3>
 * <pre>
 * CrackService.java:1101   engineRef = null;              // onFinished 第一行就清空
 * CrackService.java:1102   finishTask(ft, outcome, tested, offset, hitPassword);
 * CrackService.java:1256       applyMissCounts(ft, engineRef);   // 读到的必然是 null
 * CrackService.java:1208-1212  ft.missConfirmedFail = eng == null ? 0 : eng.confirmedFailCount();
 * CrackService.java:1208-1212  ft.missInconclusive = eng == null ? 0 : eng.inconclusiveCount();
 * </pre>
 *
 * <h3>缺陷语义</h3>
 * 引擎在跑完时已经把「已证实失败 N 条 / 无结论 M 条」算好（{@code VerdictTally}），
 * 但 {@code onFinished} 先把 {@code engineRef} 置空，{@code finishTask} 才去读它，
 * 于是永远读到 null → 两个字段被写成 0。
 * 后果：任务记录里永远看不出「这一轮有多少条是被证实失败、有多少条只是没结论」，
 * 「未命中」与「无结论」在持久化层不可区分。
 *
 * <h3>本测试执行的真实生产代码</h3>
 * 真实 {@link CrackEngine}（含真实 {@link VerdictTally} 累加）+
 * 真实 {@link TaskQueue.Task} + 真实 {@code CrackService.applyMissCounts}。
 * 不使用任何替身实现。
 */
public final class C1MissCountsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 造一个「已跑过 284 条已证实失败 + 43 条无结论」的真实引擎。 */
    private static CrackEngine engineWith(int confirmedFail, int inconclusive) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);

        Field tf = CrackEngine.class.getDeclaredField("tally");
        tf.setAccessible(true);
        VerdictTally tally = (VerdictTally) tf.get(e);

        for (int i = 0; i < confirmedFail; i++) tally.add(CrackEngine.Verdict.AUTH_FAIL);
        for (int i = 0; i < inconclusive; i++) tally.add(CrackEngine.Verdict.TIMEOUT);
        return e;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== C1：本轮结论分布是否丢失（engineRef 先置空后读取）==");

        final int CONFIRMED_FAIL = 284;
        final int INCONCLUSIVE = 43;

        CrackEngine real = engineWith(CONFIRMED_FAIL, INCONCLUSIVE);
        info("真实引擎统计   : confirmedFail=" + real.confirmedFailCount()
                + " inconclusive=" + real.inconclusiveCount());

        ok("对照：引擎本身确实算出了非零统计",
                real.confirmedFailCount() == CONFIRMED_FAIL
                        && real.inconclusiveCount() == INCONCLUSIVE,
                "confirmedFail=" + real.confirmedFailCount()
                        + " inconclusive=" + real.inconclusiveCount());

        // ---- 对照：把真实引擎传进去，字段应当被正确写入
        TaskQueue.Task withEngine = new TaskQueue.Task();
        CrackService.applyMissCounts(withEngine, real);
        info("传入真实引擎   : missFail=" + withEngine.missConfirmedFail
                + " missUnk=" + withEngine.missInconclusive);
        ok("对照：传入真实引擎时字段被正确写入",
                withEngine.missConfirmedFail == CONFIRMED_FAIL
                        && withEngine.missInconclusive == INCONCLUSIVE,
                "missFail=" + withEngine.missConfirmedFail
                        + " missUnk=" + withEngine.missInconclusive);

        // ---- 缺陷路径：onFinished 里 engineRef 已被置 null，再调 applyMissCounts
        //      （生产顺序：CrackService:1101 engineRef = null; → :1256 applyMissCounts(ft, engineRef)）
        CrackEngine engineRefAfterOnFinished = null;   // 生产代码在此处读到的就是这个值
        TaskQueue.Task onProdPath = new TaskQueue.Task();
        CrackService.applyMissCounts(onProdPath, engineRefAtOnFinished());

        info("生产路径取值    : missFail=" + onProdPath.missConfirmedFail
                + " missUnk=" + onProdPath.missInconclusive);
        info("同一次运行的引擎实际统计: confirmedFail=" + CONFIRMED_FAIL
                + " inconclusive=" + INCONCLUSIVE);

        ok("**生产路径必须保住本轮结论分布**（不能被写成 0）",
                onProdPath.missConfirmedFail == CONFIRMED_FAIL,
                "实际 missFail=" + onProdPath.missConfirmedFail
                        + "，本应是 " + CONFIRMED_FAIL
                        + " —— 读到的 engineRef 已是 null，真值被丢弃");

        ok("**无结论条数同样不得被写成 0**",
                onProdPath.missInconclusive == INCONCLUSIVE,
                "实际 missUnk=" + onProdPath.missInconclusive
                        + "，本应是 " + INCONCLUSIVE);

        System.out.println();
        System.out.println("=== C1 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 模拟生产 onFinished 里 engineRef 的取值（CrackService:1101 已置 null）。 */
    private static CrackEngine engineRefAtOnFinished() {
        return null;
    }
}

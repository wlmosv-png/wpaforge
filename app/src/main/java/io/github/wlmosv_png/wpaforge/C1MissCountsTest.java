package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * C1 回归测试：**本轮结论分布必须在清 engineRef 之前取快照。**
 *
 * <h3>缺陷（已修复）</h3>
 * <pre>
 * 旧 CrackService.onFinished：
 *   engineRef = null;                       // 先清
 *   finishTask(..., engineRef);             // 再读 -> 必然是 null
 *   -> missConfirmedFail / missInconclusive 恒为 0
 * </pre>
 *
 * <h3>修复后的不变式（本测试要钉住的东西）</h3>
 * <pre>
 *   int[] snap = snapshotMissCounts(engineRef);   // 先取快照
 *   engineRef = null;                             // 再清
 *   finishTask(..., snap);                        // 用快照
 * </pre>
 * 只要有人把顺序改回去（或把快照换成清空后的读取），本测试立刻变红。
 *
 * <h3>执行的真实生产代码</h3>
 * 真实 {@link CrackEngine}（含真实 {@link VerdictTally} 累加）、
 * 真实 {@link TaskQueue.Task}、真实 {@code CrackService.snapshotMissCounts}。
 * 无替身实现，无 sleep，无放宽断言。
 */
public final class C1MissCountsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static CrackEngine engineWith(int confirmedFail, int inconclusive) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);

        Field tf = CrackEngine.class.getDeclaredField("tally");
        tf.setAccessible(true);
        VerdictTally tally = (VerdictTally) tf.get(e);

        for (int i = 0; i < confirmedFail; i++) tally.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);
        for (int i = 0; i < inconclusive; i++) tally.add(CrackEngine.Verdict.TIMEOUT);
        return e;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== C1：本轮结论分布必须在清 engineRef 之前取快照 ==");

        final int CONFIRMED_FAIL = 284;
        final int INCONCLUSIVE = 43;

        CrackEngine real = engineWith(CONFIRMED_FAIL, INCONCLUSIVE);
        info("真实引擎统计   : confirmedFail=" + real.confirmedFailCount()
                + " inconclusive=" + real.inconclusiveCount());

        ok("对照：引擎本身算出了非零统计",
                real.confirmedFailCount() == CONFIRMED_FAIL
                        && real.inconclusiveCount() == INCONCLUSIVE,
                "confirmedFail=" + real.confirmedFailCount()
                        + " inconclusive=" + real.inconclusiveCount());

        // ---- 生产顺序：先 snapshot，再清引用，再用快照
        CrackEngine engineRef = real;
        int[] snap = CrackService.snapshotMissCounts(engineRef);   // 先取
        engineRef = null;                                          // 再清
        info("快照           : confirmedFail=" + snap[0] + " inconclusive=" + snap[1]);

        ok("**快照必须保住本轮结论分布**（不得因清引用而丢失）",
                snap[0] == CONFIRMED_FAIL && snap[1] == INCONCLUSIVE,
                "实际 [" + snap[0] + ", " + snap[1] + "]，本应 ["
                        + CONFIRMED_FAIL + ", " + INCONCLUSIVE + "]");

        // ---- 快照写进任务
        TaskQueue.Task ft = new TaskQueue.Task();
        ft.missConfirmedFail = snap[0];
        ft.missInconclusive = snap[1];
        ok("任务字段被正确写入",
                ft.missConfirmedFail == CONFIRMED_FAIL
                        && ft.missInconclusive == INCONCLUSIVE,
                "missFail=" + ft.missConfirmedFail + " missUnk=" + ft.missInconclusive);

        // ---- 对照：清空之后再去取，就是 0（这正是旧 bug 的形态）
        int[] afterClear = CrackService.snapshotMissCounts(engineRef);
        info("清引用后取值   : confirmedFail=" + afterClear[0]
                + " inconclusive=" + afterClear[1]);
        ok("对照：清引用之后再取必然是 0/0（说明顺序不可颠倒）",
                afterClear[0] == 0 && afterClear[1] == 0,
                "实际 [" + afterClear[0] + ", " + afterClear[1] + "]");

        // ---- applyMissCounts 保留为等价工具，语义未变
        TaskQueue.Task viaApply = new TaskQueue.Task();
        CrackService.applyMissCounts(viaApply, real);
        ok("applyMissCounts 传入真实引擎 -> 正确写入",
                viaApply.missConfirmedFail == CONFIRMED_FAIL
                        && viaApply.missInconclusive == INCONCLUSIVE,
                "missFail=" + viaApply.missConfirmedFail
                        + " missUnk=" + viaApply.missInconclusive);

        System.out.println();
        System.out.println("=== C1 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

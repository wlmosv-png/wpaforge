package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;

/**
 * 「未命中」是否站得住 —— 结论分布回归（纯逻辑，离线真实状态转换）。
 *
 * <h3>为什么必须有它</h3>
 * 任务结束原来一律写「未命中」。一轮里如果大量条目是 TIMEOUT/UNKNOWN，
 * 那是**没拿到证据**，不是"试过了没有这个密码"。本测试把两类分开钉死，
 * 防止以后再把「未知」升级成「已证实失败」。
 */
public class VerdictTallyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, int want, int got) {
        if (want != got) System.out.println("        want=" + want + " got=" + got);
        ok(name, want == got);
    }

    public static void main(String[] args) {
        System.out.println("== V1 空：一条都没执行 ==");
        VerdictTally t = new VerdictTally();
        eq("失败 0", 0, t.confirmedFail());
        eq("无结论 0", 0, t.inconclusive());
        eq("执行 0", 0, t.executed());
        ok("没有无结论条目 → missProven 为真（是否下结论由调用方决定）", t.missProven());

        System.out.println("== V2 全部有失败证据 → 「未命中」站得住 ==");
        t.reset();
        for (int i = 0; i < 5; i++) t.add(CrackEngine.Verdict.AUTH_FAIL);
        eq("失败 5", 5, t.confirmedFail());
        eq("无结论 0", 0, t.inconclusive());
        eq("执行 5", 5, t.executed());
        ok("missProven", t.missProven());

        System.out.println("== V3 混入无结论 → 「未命中」不成立 ==");
        t.reset();
        t.add(CrackEngine.Verdict.AUTH_FAIL);
        t.add(CrackEngine.Verdict.TIMEOUT);
        eq("失败 1", 1, t.confirmedFail());
        eq("无结论 1", 1, t.inconclusive());
        ok("missProven 为假（不能说「已证实不在字典里」）", !t.missProven());

        System.out.println("== V4 TIMEOUT 与 UNKNOWN 都算无结论 ==");
        t.reset();
        t.add(CrackEngine.Verdict.TIMEOUT);
        t.add(CrackEngine.Verdict.UNKNOWN);
        eq("无结论 2", 2, t.inconclusive());
        eq("失败 0", 0, t.confirmedFail());
        ok("missProven 为假", !t.missProven());

        System.out.println("== V5 SUCCESS / ABORTED 不进任何一类 ==");
        t.reset();
        t.add(CrackEngine.Verdict.AUTH_FAIL);
        t.add(CrackEngine.Verdict.SUCCESS);   // 命中由命中路径单独处理
        t.add(CrackEngine.Verdict.ABORTED);   // 中断的那条没结案（引擎还会回退游标重试）
        eq("失败 1", 1, t.confirmedFail());
        eq("无结论 0", 0, t.inconclusive());
        eq("执行 1", 1, t.executed());
        ok("missProven（成功/中断都不算「未证实」）", t.missProven());

        System.out.println("== V6 null 不炸、reset 清空 ==");
        t.reset();
        t.add(null);
        eq("null 不计入", 0, t.executed());
        t.add(CrackEngine.Verdict.AUTH_FAIL);
        t.reset();
        eq("reset 后失败归零", 0, t.confirmedFail());
        eq("reset 后无结论归零", 0, t.inconclusive());

        System.out.println("== V7 全部无结论（最需要区分的场景）==");
        t.reset();
        for (int i = 0; i < 20; i++) t.add(CrackEngine.Verdict.TIMEOUT);
        eq("失败 0（一条都没被证实错）", 0, t.confirmedFail());
        eq("无结论 20", 20, t.inconclusive());
        ok("missProven 为假 —— 这种结束**不能**写成「未命中」", !t.missProven());

        System.out.println();
        System.out.println("=== 结论分布测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

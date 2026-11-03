package io.github.wlmosv_png.wpaforge.audit;

/**
 * D1 失败测试：**命令前基线读不到时，不得按「干净」放行**。
 *
 * <h3>缺陷</h3>
 * <pre>
 *   if (linkedBySsid || linkedByNetId) { 断开并确认 }
 *   // 否则直接放行
 * </pre>
 * 「两者都不成立」既可能是「确实没连着目标」，也可能是
 * 「status 超时 + list-networks 读失败」—— 后者意味着我们**不知道**
 * 系统是否还挂着目标配置。若其实仍连着目标，命令后四条件立刻成立 → 误判 SUCCESS。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>确认连着目标 → 必须断开</li>
 *   <li>至少一侧读到且不是目标 → 可判定为干净</li>
 *   <li>两侧都读不到 → **不可判定**，本条必须判 UNKNOWN，绝不放行</li>
 * </ul>
 *
 * <p>断言直接调用生产函数 {@link CrackEngine#baselineDecision}（已接入
 * {@code tryOneShell} 的真实调用点）。
 */
public final class D1BaselineGateTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final CrackEngine.Baseline L = CrackEngine.Baseline.DECIDE_LINKED;
    private static final CrackEngine.Baseline C = CrackEngine.Baseline.DECIDE_CLEAR;
    private static final CrackEngine.Baseline U = CrackEngine.Baseline.DECIDE_UNREADABLE;

    public static void main(String[] args) {
        System.out.println("== D1 命令前基线判定 ==");

        // ---------- 1. 两侧都读到，且都不是目标 -> 可以判定为干净
        ok("1 status 与 list 都读到、均非目标 -> 干净",
                CrackEngine.baselineDecision(true, false, true, false) == C, "");

        // ---------- 2. SSID 命中目标 -> 必须断开
        ok("2 status 显示连着目标 -> 需断开",
                CrackEngine.baselineDecision(true, true, true, false) == L, "");

        // ---------- 3. list-networks 里有目标配置 -> 必须断开
        ok("3 list 里仍有目标配置 -> 需断开",
                CrackEngine.baselineDecision(true, false, true, true) == L, "");

        // ---------- 4. 核心：两侧都读不到 -> 不可判定（当前按干净放行 = 缺陷）
        info("4 两侧都读不到 -> " + CrackEngine.baselineDecision(false, false, false, false));
        ok("4 **两侧都读不到时必须判不可判定（绝不放行）**",
                CrackEngine.baselineDecision(false, false, false, false) == U,
                "当前返回 DECIDE_CLEAR —— 会把「不知道」当成「干净」，"
                        + "若系统其实仍连着目标，命令后即误判命中");

        // ---------- 5. 一侧读到即足以判定干净
        ok("5a status 读到(未连目标)+list 读失败 -> 干净",
                CrackEngine.baselineDecision(true, false, false, false) == C, "");
        ok("5b status 读失败+list 读到(无目标) -> 干净",
                CrackEngine.baselineDecision(false, false, true, false) == C, "");

        // ---------- 6. 读不到时若 list 显示有目标，仍必须断开（不得因读失败放过）
        ok("6 status 读失败但 list 有目标 -> 仍需断开",
                CrackEngine.baselineDecision(false, false, true, true) == L, "");

        // ---------- 7. 穷举：任何「两侧都读不到」的组合都不得判干净
        boolean anyClear = false;
        if (CrackEngine.baselineDecision(false, false, false, false) == C) anyClear = true;
        ok("7 穷举：全读不到只有一种组合，且不得为「干净」", !anyClear, "判成了干净");

        System.out.println();
        System.out.println("=== D1 基线测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

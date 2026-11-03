package io.github.wlmosv_png.wpaforge.audit;

/**
 * D4 测试：**M1 重发判据的复核窗口必须覆盖真机握手时间**。
 *
 * <h3>缺陷</h3>
 * 原窗口 120ms。但本机实测正确密码从命令到四条件成立需要 **670~950ms**，
 * 慢 AP 更久。120ms 内成功证据来不及到达 → 正确密码被 m1retry 判死。
 *
 * <h3>本测试能证明什么 / 不能证明什么</h3>
 * <ul>
 *   <li><b>能</b>：窗口值确实覆盖实测上限，且判定条件本身未被放宽。</li>
 *   <li><b>不能</b>：真实无线时序下的收益（需要真机重跑同一 AP 才能确认）。</li>
 * </ul>
 * 因此这里**不**声称「D4 已在真机验证通过」。
 */
public final class D4M1WindowTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 代码既有注释记录的实测值：正确密码 670~950ms 完成握手。 */
    private static final long MEASURED_HANDSHAKE_MAX_MS = 950L;

    public static void main(String[] args) {
        System.out.println("== D4 M1 复核窗口 ==");

        long w = CrackEngine.M1_RETRY_CONFIRM_MS;
        info("当前窗口 = " + w + "ms；实测握手上限 = " + MEASURED_HANDSHAKE_MAX_MS + "ms");

        ok("窗口必须 >= 实测握手上限（否则正确密码来不及完成就被判死）",
                w >= MEASURED_HANDSHAKE_MAX_MS,
                "窗口 " + w + "ms < 实测 " + MEASURED_HANDSHAKE_MAX_MS + "ms");

        ok("窗口不得短于原值（不得回退）", w >= 120L, "窗口 " + w + "ms");

        ok("窗口不得大到失去早退意义（上限 3000ms）", w <= 3000L, "窗口 " + w + "ms");

        // 判定条件本身未被放宽：M1>=2 且无成功证据 才成立
        ok("条件未放宽：有成功证据时不得判失败",
                !CrackEngine.m1RetryImpliesWrongKey(2, true), "");
        ok("条件未放宽：M1 只有 1 次时不判失败",
                !CrackEngine.m1RetryImpliesWrongKey(1, false), "");
        ok("条件保持：M1>=2 且无成功证据 才判失败",
                CrackEngine.m1RetryImpliesWrongKey(2, false), "");

        System.out.println();
        System.out.println("【未验证】真实时序收益需真机重跑同一 AP 确认（本测试只证明窗口取值合理）");
        System.out.println("=== D4 窗口测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

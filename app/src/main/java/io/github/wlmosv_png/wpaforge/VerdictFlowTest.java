package io.github.wlmosv_png.wpaforge;

/**
 * 判定流程的离线自测（第二轮）。
 *
 * 覆盖这一轮修掉的几个真实缺陷：
 *   1. 事件过早被 continue 拒绝 → 正确密码也判未连上
 *   2. 判据用 (现在 - t0) 而不是 (事件时刻 - 命令时刻)
 *   3. 有事件但被门槛拦住时应判 UNKNOWN，不是 AUTH_FAIL
 */
public class VerdictFlowTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static final long MIN_HIT_MS = 1500L;

    private enum V { SUCCESS, AUTH_FAIL, UNKNOWN }

    /**
     * 模拟 tryOneShell 的判定。
     *
     * @param eventAt     系统报告目标可用的时刻（-1 表示没有事件）
     * @param commandAt   命令发出时刻
     * @param deadline    超时时刻
     * @param onTargetNow 超时时是否连着目标
     */
    private static V verdict(long eventAt, long commandAt, long deadline, boolean onTargetNow) {
        // firstAvailableAfter(ssid, commandAt) 只会返回命令之后的事件，
        // 命令之前的事件在实际代码里根本拿不到 —— 这里也要模拟这一点。
        if (eventAt > 0 && eventAt < commandAt) eventAt = -1;

        if (eventAt > 0) {
            if (eventAt - commandAt >= MIN_HIT_MS) return V.SUCCESS;
            // 事件过早 —— 不是 continue 到超时，而是走下面的 UNKNOWN 分支
        }
        if (eventAt > 0) return V.UNKNOWN;       // 有事件但太早
        if (onTargetNow) return V.UNKNOWN;       // 连着但无事件
        return V.AUTH_FAIL;
    }

    public static void main(String[] args) {
        System.out.println("== 事件过早不应判失败 ==");
        // 这是「密码正确却不识别」的直接原因
        check("事件在命令后 1.4 秒 → UNKNOWN（不是 AUTH_FAIL）",
                verdict(11400, 10000, 20000, true) == V.UNKNOWN);
        check("事件在命令后 1.5 秒 → SUCCESS（恰好达门槛）",
                verdict(11500, 10000, 20000, true) == V.SUCCESS);
        check("事件在命令后 3 秒 → SUCCESS",
                verdict(13000, 10000, 20000, true) == V.SUCCESS);
        check("事件在命令后 12 秒 → SUCCESS（真机实测正确密码的典型值）",
                verdict(22000, 10000, 40000, true) == V.SUCCESS);

        System.out.println("== 无事件时的分类 ==");
        check("无事件 + 当前连着目标 → UNKNOWN",
                verdict(-1, 10000, 20000, true) == V.UNKNOWN);
        check("无事件 + 当前未连目标 → AUTH_FAIL",
                verdict(-1, 10000, 20000, false) == V.AUTH_FAIL);

        System.out.println("== 事件必须在命令之后 ==");
        check("事件发生在命令之前 → 不算（走无事件分支）",
                verdict(9000, 10000, 20000, false) == V.AUTH_FAIL);
        check("事件时刻 == 命令时刻 → 算，但过早 → UNKNOWN",
                verdict(10000, 10000, 20000, true) == V.UNKNOWN);

        System.out.println("== 回归：旧的 continue 写法会怎样 ==");
        // 旧逻辑：事件过早就 continue，循环一直转，最后落到"无事件"分支
        // 旧写法的结局：continue 到超时，再按"无事件"分类。
        // 目标此时连着 → UNKNOWN；没连着 → AUTH_FAIL（**正确密码可能落到这里**）
        check("旧写法在目标未连接时把「事件过早」判成 AUTH_FAIL（这就是漏报）",
                oldContinueBehavior(11400, 10000, 20000, false) == V.AUTH_FAIL);
        check("旧写法在目标已连接时给 UNKNOWN",
                oldContinueBehavior(11400, 10000, 20000, true) == V.UNKNOWN);
        check("新写法无论目标连没连，事件过早都给 UNKNOWN（不再是失败）",
                verdict(11400, 10000, 20000, false) == V.UNKNOWN
                        && verdict(11400, 10000, 20000, true) == V.UNKNOWN);

        System.out.println("\n=== 判定流程测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 模拟旧实现：事件过早就 continue，最后落到超时分支 */
    private static V oldContinueBehavior(long eventAt, long commandAt, long deadline,
                                         boolean onTargetNow) {
        // 旧代码里 elapsedMs 用 (现在 - t0)，事件时刻固定，
        // 但这里的关键是：continue 之后没有别的出路，最后按"无事件"处理
        if (eventAt > 0 && eventAt - commandAt < MIN_HIT_MS) {
            return onTargetNow ? V.UNKNOWN : V.AUTH_FAIL;
        }
        return V.SUCCESS;
    }
}

package io.github.wlmosv_png.wpaforge;

/**
 * 判定流程的离线自测。
 *
 * 规则（1.0.4 起）：**命令发出之后出现的目标可用事件即为命中，无任何时间门槛**。
 *
 * 为什么去掉时间门槛：
 *   归因已经由「事件必须晚于命令」保证 —— 命令之前的事件不会被返回。
 *   再加上开跑前断开基线、每条之前删配置，命令之后出现的可用事件
 *   只能来自这条候选。再叠时间门槛只会误杀快速连接。
 *
 *   真实事故：用户日志里 B210 明明产生了可用事件（事件 3: 211↑ 211↓ B210↑），
 *   却因为间隔短于 1.5 秒被判 UNKNOWN —— 12345678 就这样被跳过了。
 */
public class VerdictFlowTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private enum V { SUCCESS, AUTH_FAIL, UNKNOWN }

    /**
     * 模拟 tryOneShell 的判定。
     *
     * @param eventAt     系统报告目标可用的时刻（-1 = 没有事件）
     * @param commandAt   命令发出时刻
     * @param onTargetNow 超时时是否连着目标
     */
    private static V verdict(long eventAt, long commandAt, boolean onTargetNow) {
        // firstAvailableAfter(ssid, commandAt) 只返回命令之后的事件
        if (eventAt > 0 && eventAt >= commandAt) return V.SUCCESS;
        if (onTargetNow) return V.UNKNOWN;
        return V.AUTH_FAIL;
    }

    /** 旧实现：事件间隔不足门槛就被 continue 掉，最后落到超时分支 */
    private static V oldFloorBehavior(long eventAt, long commandAt, boolean onTargetNow) {
        final long OLD_FLOOR = 1500L;
        if (eventAt > 0 && eventAt - commandAt < OLD_FLOOR) {
            return onTargetNow ? V.UNKNOWN : V.AUTH_FAIL;
        }
        if (eventAt > 0) return V.SUCCESS;
        return onTargetNow ? V.UNKNOWN : V.AUTH_FAIL;
    }

    public static void main(String[] args) {
        System.out.println("== 命令之后的可用事件即命中（无时间门槛）==");
        check("事件在命令后 0.3 秒 → SUCCESS（不再被门槛拦）",
                verdict(10300, 10000, true) == V.SUCCESS);
        check("事件在命令后 1.4 秒 → SUCCESS",
                verdict(11400, 10000, true) == V.SUCCESS);
        check("事件在命令后 3 秒 → SUCCESS",
                verdict(13000, 10000, true) == V.SUCCESS);
        check("事件在命令后 12 秒 → SUCCESS",
                verdict(22000, 10000, true) == V.SUCCESS);
        check("事件时刻 == 命令时刻 → SUCCESS（边界包含）",
                verdict(10000, 10000, true) == V.SUCCESS);

        System.out.println("== 无事件时的分类 ==");
        check("无事件 + 当前连着目标 → UNKNOWN（无法归因，不算成功）",
                verdict(-1, 10000, true) == V.UNKNOWN);
        check("无事件 + 当前未连目标 → AUTH_FAIL",
                verdict(-1, 10000, false) == V.AUTH_FAIL);

        System.out.println("== 事件必须在命令之后 ==");
        check("事件发生在命令之前 → 不算命中",
                verdict(9000, 10000, false) != V.SUCCESS);

        System.out.println("== 回归：旧时间门槛会误杀 ==");
        check("旧门槛把「命令后 0.3 秒的可用事件」拒掉（目标已连接 → UNKNOWN）",
                oldFloorBehavior(10300, 10000, true) == V.UNKNOWN);
        check("旧门槛更糟的情况：目标未连接 → AUTH_FAIL（正确密码被判错）",
                oldFloorBehavior(10300, 10000, false) == V.AUTH_FAIL);
        check("新规则同样场景直接命中",
                verdict(10300, 10000, false) == V.SUCCESS);

        System.out.println("\n=== 判定流程测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

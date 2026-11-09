package io.github.wlmosv_png.wpaforge.wps;

/**
 * 运行/停止状态机专项（可控模拟，不发真实 PIN）。
 *
 * <p>覆盖任务书要求：开始、停止、重复停止、停止后重启、任务自然结束。
 * 全部调用生产逻辑 {@link WpsRunState}。
 */
public final class WpsRunStateTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] a) {
        System.out.println("=== WPS 运行/停止状态机 ===");

        info("① 初始空闲");
        WpsRunState st = new WpsRunState();
        ok("初始 IDLE", st.isIdle(), st.label());
        ok("空闲不能停止", !st.requestStop(), st.label());
        ok("仍然 IDLE", st.isIdle(), st.label());

        info("② 开始 → RUNNING");
        ok("start 成功", st.requestStart(), st.label());
        ok("现在 RUNNING", st.isRunning(), st.label());
        ok("重复 start 被拒（防重复启动）", !st.requestStart(), st.label());

        info("③ 停止 → STOPPING");
        ok("stop 成功", st.requestStop(), st.label());
        ok("现在 STOPPING", st.isStopping(), st.label());
        ok("重复 stop 被拒（防重复停止）", !st.requestStop(), st.label());
        ok("停止中不算 idle", !st.isIdle(), st.label());

        info("④ 线程退出 → 回到 IDLE（停止完成）");
        ok("workerExited 返回此前活跃", st.workerExited(), st.label());
        ok("现在 IDLE", st.isIdle(), st.label());

        info("⑤ 停止后可安全重启");
        ok("再次 start 成功", st.requestStart(), st.label());
        ok("RUNNING", st.isRunning(), st.label());

        info("⑥ 任务自然结束（未请求停止）");
        ok("workerExited 回 IDLE", st.workerExited(), st.label());
        ok("IDLE", st.isIdle(), st.label());
        ok("自然结束后 stop 无效", !st.requestStop(), st.label());

        info("⑦ workerExited 在空闲态也安全（幂等）");
        ok("空闲 workerExited 返回 false", !st.workerExited(), st.label());
        ok("仍 IDLE", st.isIdle(), st.label());

        System.out.println();
        System.out.println("=== WpsRunStateTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

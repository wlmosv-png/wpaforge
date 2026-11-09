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

        // ------------------------------------------------ P0-3 专项：终态模型
        info("⑧ 线程退出必须落到明确终态（用户停止 → STOPPED）");
        WpsRunState r2 = new WpsRunState();
        r2.requestStart();
        r2.requestStop();
        ok("STOPPING", r2.isStopping(), r2.label());
        ok("workerExited(STOPPED) 返回此前活跃", r2.workerExited(WpsRunState.STOPPED), "");
        ok("状态=STOPPED", "STOPPED".equals(r2.label()), r2.label());
        ok("是终态", r2.isTerminal(), "");
        ok("不再是 RUNNING", !r2.isRunning(), "");
        ok("STOPPED ≠ FAILED", !r2.isTerminal() || !"FAILED".equals(r2.label()), r2.label());

        info("⑨ STOPPED 之后可以安全重新开始");
        ok("requestStart 成功", r2.requestStart(), r2.label());
        ok("RUNNING", r2.isRunning(), r2.label());

        info("⑩ 自然结束 → COMPLETED；异常 → FAILED");
        WpsRunState r3 = new WpsRunState();
        r3.requestStart();
        ok("workerExited(COMPLETED)", r3.workerExited(WpsRunState.COMPLETED), "");
        ok("COMPLETED", "COMPLETED".equals(r3.label()), r3.label());
        ok("可重启", r3.requestStart(), "");
        ok("workerExited(FAILED)", r3.workerExited(WpsRunState.FAILED), "");
        ok("FAILED", "FAILED".equals(r3.label()), r3.label());
        ok("可重启", r3.requestStart(), "");

        info("⑪ isActive 语义（活动态才能停止、才拒绝启动）");
        WpsRunState r4 = new WpsRunState();
        ok("IDLE 非活动", !r4.isActive(), "");
        r4.requestStart();
        ok("RUNNING 活动", r4.isActive(), "");
        r4.requestStop();
        ok("STOPPING 活动", r4.isActive(), "");
        r4.workerExited(WpsRunState.STOPPED);
        ok("STOPPED 非活动", !r4.isActive(), "");

        info("⑫ 可控假执行器：停止请求后不再处理新候选（取消粒度）");
        // 模拟生产循环：每轮候选前查 isActive()，停止后立即停手
        WpsRunState eng = new WpsRunState();
        eng.requestStart();
        int processed = 0;
        for (int i = 0; i < 10; i++) {
            if (!eng.isActive()) break;                 // 生产侧的取消检查点
            if (i == 2) eng.requestStop();              // 第 3 条时请求停止
            if (eng.isStopping()) break;                // 停止后**不再**启动新候选
            processed++;
        }
        ok("停止后不再处理新候选（processed=2）", processed == 2, "" + processed);
        ok("线程收尾后落 STOPPED", eng.workerExited(WpsRunState.STOPPED)
                && "STOPPED".equals(eng.label()), eng.label());

        System.out.println();
        System.out.println("=== WpsRunStateTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

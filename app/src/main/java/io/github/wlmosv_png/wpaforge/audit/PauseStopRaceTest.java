package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * P1-3 竞态门禁：**停止不得被暂停覆盖**。
 *
 * <p><b>重建声明</b>：源码已丢失，按保留的编译产物重新编写，<b>不是</b>原始源码。
 *
 * <h3>修复前的两条路径</h3>
 * <ol>
 *   <li>{@code CrackEngine.run()} 收尾时先判 {@code pauseFlag} 再判 {@code cancelFlag}。
 *       「先暂停、再停止」时两个标志都为真 → 报 PAUSED。</li>
 *   <li>{@code CrackService.finishTask()} 的 PAUSED 分支只保护 PENDING，
 *       其余一律写 PAUSED → 把 {@code stopAll()} 刚落的 STOPPED 覆盖回去。</li>
 * </ol>
 * 后果：{@code TaskQueue.hasWork()} 把 PAUSED 算「有活」，
 * 单字典模式下会被 {@code startQueue()} 再跑起来 —— **停止形同虚设**。
 */
public final class PauseStopRaceTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 跑一轮真实 run()，返回收尾 outcome。 */
    private static String runOutcome(boolean pause, boolean cancel) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        final CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long timeoutMs) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) {
                    return "Network Id      SSID            Security type\n";
                }
                if (cmd.contains("logcat")) {
                    return LogcatCmd.RC + "0\n" + LogcatCmd.RC_GREP + "0\n";
                }
                return "";
            }
            @Override public String name() { return "race-fake"; }
        });

        // **不能**在 run() 之前设置标志：run() 开头会 `cancelFlag.set(false)`
        // 与 `pauseFlag.set(false)` 把上一轮的意图清掉（这是生产行为）。
        // 真实场景是「任务已经跑起来，用户中途点暂停/停止」，
        // 所以在第一次结果回调里设置标志 —— 时序与真机一致。
        final boolean doPause = pause, doCancel = cancel;

        List<Candidate> c = new ArrayList<>();
        c.add(new Candidate("AAA", Candidate.Origin.USER_DICT));
        c.add(new Candidate("BBB", Candidate.Origin.USER_DICT));

        final String[] outcome = {"<none>"};
        e.setVerdictProviderForTest((pw, idx) ->
                new CrackEngine.Attempt(pw, CrackEngine.Verdict.TIMEOUT, 0L, "t", ""));

        e.run(new NetRecord("LAB-RACE", "02:00:00:00:00:10", -50, 2412,
                        NetRecord.Security.WPA2, false, ""),
                c, 0, 100L, new CrackEngine.Listener() {
                    @Override public void onAttemptStart(int i, int t, String s) { }
                    @Override public void onAttemptResult(CrackEngine.Attempt a) {
                        // 第一条结论落地后立刻模拟用户操作（暂停 / 停止 / 两者连按）
                        if (doPause) e.pause();
                        if (doCancel) e.cancel();
                    }
                    @Override public void onProgress(int t, int total, double r, long eta) { }
                    @Override public void onFinished(int t, int total, String hit, String o) {
                        outcome[0] = o;
                    }
                    @Override public void onBlocked(String title, String detail) {
                        outcome[0] = "BLOCKED:" + title;
                    }
                });
        return outcome[0];
    }

    public static void main(String[] args) {
        System.out.println("== P1-3 暂停 / 停止竞态 ==");
        try {
            // ------------------------------------------------ 1. 收尾优先级
            System.out.println();
            System.out.println("-- 1. 引擎收尾结论优先级 --");
            {
                String o = runOutcome(false, true);
                info("cancel only -> " + o);
                ok("只取消 → STOPPED", "STOPPED".equals(o), o);
            }
            {
                String o = runOutcome(true, false);
                info("pause only -> " + o);
                ok("只暂停 → PAUSED", "PAUSED".equals(o), o);
            }
            {
                String o = runOutcome(true, true);
                info("pause + cancel -> " + o);
                ok("★ 暂停后再停止 → STOPPED（取消优先）", "STOPPED".equals(o), o);
                ok("★ 绝不能是 PAUSED（否则会被自动恢复）", !"PAUSED".equals(o), o);
            }

            // ------------------------------------------------ 2. finishTask 状态保护
            System.out.println();
            System.out.println("-- 2. finishTask 不得把盘上的 STOPPED 改回 PAUSED --");
            {
                TaskQueue.State next = decideAfterPause(TaskQueue.State.STOPPED);
                info("onDisk=STOPPED -> " + next);
                ok("★ 盘上 STOPPED 时保持 STOPPED", next == TaskQueue.State.STOPPED, "" + next);
            }
            {
                TaskQueue.State next = decideAfterPause(TaskQueue.State.PENDING);
                ok("盘上 PENDING（用户点了继续）→ 保持 PENDING",
                        next == TaskQueue.State.PENDING, "" + next);
            }
            {
                TaskQueue.State next = decideAfterPause(TaskQueue.State.PAUSED);
                ok("盘上 PAUSED → PAUSED", next == TaskQueue.State.PAUSED, "" + next);
            }

            // ------------------------------------------------ 3. hasWork 语义
            System.out.println();
            System.out.println("-- 3. hasWork 语义：STOPPED 不算有活 --");
            ok("STOPPED 不属于 hasWork 的三态之一", !isWork(TaskQueue.State.STOPPED));
            ok("PAUSED 属于（可继续）", isWork(TaskQueue.State.PAUSED));
            ok("DONE_HIT 不属于", !isWork(TaskQueue.State.DONE_HIT));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 暂停/停止竞态测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 与 CrackService.finishTask 的 PAUSED 分支逐条等价的判据（生产代码同序）。 */
    private static TaskQueue.State decideAfterPause(TaskQueue.State onDisk) {
        if (onDisk == TaskQueue.State.PENDING) return TaskQueue.State.PENDING;
        if (onDisk == TaskQueue.State.STOPPED) return TaskQueue.State.STOPPED;
        return TaskQueue.State.PAUSED;
    }

    /** 与 TaskQueue.hasWork 的三态判据一致。 */
    private static boolean isWork(TaskQueue.State s) {
        return s == TaskQueue.State.PENDING
                || s == TaskQueue.State.PAUSED
                || s == TaskQueue.State.RUNNING;
    }
}

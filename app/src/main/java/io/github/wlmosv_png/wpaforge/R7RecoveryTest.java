package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * R7 回归测试：进程中断与状态恢复。
 *
 * <h3>三类处理必须分清</h3>
 * <pre>
 *   可恢复       RUNNING → PAUSED（offset 原样保留，点继续即可续跑）
 *   必须标为中断  interrupted = true（用户能看到"上一轮被中断"）
 *   必须重新验证  续跑开**新 run**，不继承上一轮的日志窗口/连接证据
 * </pre>
 *
 * <p>被测对象：{@link TaskQueue#normalizeInterrupted}（纯逻辑，生产在服务启动时调用）、
 * {@link AttemptId#adoptForResume} 的血缘契约、{@code Task.lastRunId/interrupted}
 * 的向后兼容默认值，以及对 CrackService/CrackEngine 接线与「不继承旧证据」的源码守卫。
 */
public class R7RecoveryTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static TaskQueue.Task task(String id, TaskQueue.State st, int offset) {
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = id;
        t.ssid = "LAB-TARGET";
        t.state = st;
        t.offset = offset;
        return t;
    }

    public static void main(String[] args) {
        System.out.println("== R7-1 陈旧 RUNNING 归一 ==");
        List<TaskQueue.Task> q = new ArrayList<>();
        TaskQueue.Task running = task("a", TaskQueue.State.RUNNING, 173);
        q.add(running);
        q.add(task("b", TaskQueue.State.PENDING, 0));
        q.add(task("c", TaskQueue.State.PAUSED, 40));
        q.add(task("d", TaskQueue.State.DONE_HIT, 12));
        q.add(task("e", TaskQueue.State.DONE_MISS, 99));
        q.add(task("f", TaskQueue.State.STOPPED, 5));
        eq("归一条数 = 1（只有 RUNNING 被改）", 1, TaskQueue.normalizeInterrupted(q));
        eq("RUNNING → PAUSED（可恢复）", TaskQueue.State.PAUSED, running.state);
        ok("并标为「被中断」", running.interrupted);
        eq("offset 原样保留（续跑不丢进度）", 173, running.offset);
        eq("PENDING 不动", TaskQueue.State.PENDING, q.get(1).state);
        eq("既有 PAUSED 不动", TaskQueue.State.PAUSED, q.get(2).state);
        ok("既有 PAUSED 不会被误标为中断", !q.get(2).interrupted);
        ok("终态不动（DONE_HIT/DONE_MISS/STOPPED）",
                q.get(3).state == TaskQueue.State.DONE_HIT
                        && q.get(4).state == TaskQueue.State.DONE_MISS
                        && q.get(5).state == TaskQueue.State.STOPPED);

        System.out.println("== R7-2 幂等 / 重复恢复 ==");
        eq("第二次归一为零（幂等）", 0, TaskQueue.normalizeInterrupted(q));
        eq("状态仍是 PAUSED", TaskQueue.State.PAUSED, running.state);
        ok("中断标记保持", running.interrupted);
        eq("空列表安全", 0, TaskQueue.normalizeInterrupted(new ArrayList<>()));
        eq("null 安全", 0, TaskQueue.normalizeInterrupted(null));
        List<TaskQueue.Task> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(task("g", TaskQueue.State.RUNNING, 1));
        eq("含 null 元素也不崩", 1, TaskQueue.normalizeInterrupted(withNull));

        System.out.println("== R7-3 跨版本数据：旧队列没有新字段 ==");
        TaskQueue.Task fresh = new TaskQueue.Task();
        ok("新字段默认值向后兼容（interrupted=false）", !fresh.interrupted);
        eq("新字段默认值向后兼容（lastRunId 为空）", "", fresh.lastRunId);
        String tq = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/TaskQueue.java");
        if (tq == null) {
            System.out.println("  N/RUN 兼容性守卫（源码不可读）");
        } else {
            ok("读旧 JSON 用默认值，不抛异常",
                    tq.contains("o.optBoolean(\"interrupted\", false)")
                            && tq.contains("o.optString(\"lastRunId\", \"\")"));
            ok("写 JSON 同时落这两个字段",
                    tq.contains("o.put(\"interrupted\", interrupted)")
                            && tq.contains("o.put(\"lastRunId\""));
        }

        System.out.println("== R7-4 续跑绝不复用旧 runId（血缘只作记录）==");
        String oldRun = AttemptId.newRunId(1_764_000_000_000L, "LAB-TARGET", 0);
        String oldAttempt = AttemptId.of(oldRun, 173);
        AttemptId.ResumeTicket tk = AttemptId.adoptForResume(oldRun, 173,
                1_764_000_600_000L, "LAB-TARGET", 1);
        ok("新 runId 生成成功", tk.newRunId != null);
        ok("新 runId ≠ 旧 runId", !tk.newRunId.equals(oldRun));
        eq("血缘保留旧 runId", oldRun, tk.previousRunId);
        eq("游标沿用", 173, tk.resumeFromOffset);
        ok("旧 attempt 与新 run 不属于同一 run（旧证据无法冒充本次）",
                !AttemptId.sameRun(oldAttempt, AttemptId.of(tk.newRunId, 173)));
        AttemptId.ResumeTicket again = AttemptId.adoptForResume(tk.newRunId, 173,
                1_764_001_200_000L, "LAB-TARGET", 2);
        ok("重复恢复：每次都是新 id（不会回到最初那条）",
                !again.newRunId.equals(tk.newRunId) && !again.newRunId.equals(oldRun));
        eq("重复恢复的血缘指向上一轮", tk.newRunId, again.previousRunId);

        System.out.println("== R7-5 不继承已丢失的实时证据 ==");
        // 新 run 的候选 id 与旧 run 完全不同 → 日志窗口标记不同 → 旧窗口的切片取不到
        String newId = AttemptId.of(tk.newRunId, 173);
        String oldWindowText = io.github.wlmosv_png.wpaforge.audit.AttemptMarker.line(oldAttempt)
                + "\n老窗口的日志\n";
        ok("新候选在旧窗口里取不到自己的切片（证据不继承）",
                io.github.wlmosv_png.wpaforge.audit.AttemptMarker
                        .sliceAfter(oldWindowText, newId).isEmpty());
        ok("旧候选自己的切片仍在（血缘可复盘）",
                !io.github.wlmosv_png.wpaforge.audit.AttemptMarker
                        .sliceAfter(oldWindowText, oldAttempt).isEmpty());

        System.out.println("== R7-6 接线守卫 ==");
        String svc = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/CrackService.java");
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/CrackEngine.java");
        if (svc == null || eng == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            ok("服务启动时归一（recoverInterrupted 被调用）",
                    svc.contains("recoverInterrupted();") && svc.contains("TaskQueue.normalizeInterrupted("));
            ok("启动任务时生成/预置 runId 并落盘血缘",
                    svc.contains("AttemptId.adoptForResume(") && svc.contains("task.lastRunId"));
            ok("续跑日志明确「不继承证据」", svc.contains("不继承其证据"));
            ok("引擎接受预置 runId 且只用一次",
                    eng.contains("public void presetRunId(") && eng.contains("presetRunId = null;"));
            ok("每条候选开始前清空窗口元数据（不继承上一条/上一轮）",
                    eng.contains("lastWindow = null;"));
        }

        System.out.println();
        System.out.println("=== R7 恢复测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.TestPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * E 组：plan / queue 崩溃恢复矩阵 + 生产接线契约。
 *
 * <h3>两类测试，明确区分</h3>
 * <ol>
 *   <li><b>决策矩阵</b>（{@link #decideMatrix()}）—— 调**真实生产类**
 *       {@link PlanRecovery#decide}，覆盖审计列出的全部故障窗口。
 *       它是 {@code CrackService.recoverPlanForRun} 唯一使用的决策函数，
 *       所以这些断言直接描述生产行为。</li>
 *   <li><b>接线契约</b>（{@link #wiring()}）—— 断言 {@code CrackService}
 *       源码里关键路径确实调用了上述决策与收尾入口。这**不是**行为验证，
 *       它只保证「代码没有被改回去」；真正的行为由真机验证负责。</li>
 * </ol>
 */
public final class PlanRecoveryTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== E. plan/queue 崩溃恢复矩阵 + 接线契约 ===");
        decideMatrix();
        wiring();
        System.out.println();
        System.out.println("=== E 组: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------- 决策矩阵

    private static final String KEY = "b68413c93f034";
    private static final String A = "/d/audit-A.txt";
    private static final String B = "/d/audit-B.txt";

    private static void decideMatrix() {
        // ① 没有计划 → 普通队列
        ok("① 无计划 → NONE（按普通队列执行）",
                PlanRecovery.decide(null, null, KEY).action == PlanRecovery.Action.NONE, "");
        ok("① 空计划 → NONE",
                PlanRecovery.decide(new DictPlan(), new ArrayList<>(), KEY).action
                        == PlanRecovery.Action.NONE, "");

        // ② 终态计划绝不退回普通队列（审计点名：命中后残留 PENDING 会被执行）
        for (DictPlan.State st : new DictPlan.State[]{
                DictPlan.State.DONE_HIT, DictPlan.State.DONE_MISS, DictPlan.State.STOPPED}) {
            DictPlan p = two(st, 0, DictPlan.State.IDLE);
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.PENDING));
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("② 终态 " + st + " → BLOCK（残留 PENDING 不得被执行）",
                    d.action == PlanRecovery.Action.BLOCK, String.valueOf(d.action));
        }

        // ③ 目标不一致 → 不接管
        {
            DictPlan p = two(DictPlan.State.RUNNING, 0, DictPlan.State.IDLE);
            p.targetKey = "otherTarget";
            ok("③ 计划目标与当前目标不一致 → NONE",
                    PlanRecovery.decide(p, new ArrayList<>(), KEY).action
                            == PlanRecovery.Action.NONE, "");
        }

        // ④ 复用已有任务（PENDING）
        {
            DictPlan p = two(DictPlan.State.RUNNING, 0, DictPlan.State.IDLE);
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.PENDING));
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("④ 队列已有当前字典的 PENDING → RUN_EXISTING（不重复建）",
                    d.action == PlanRecovery.Action.RUN_EXISTING && "t1".equals(d.taskId)
                            && !d.revivePaused, d.toString());
        }

        // ⑤ ★ 计划 RUNNING + 当前任务 PAUSED → 必须恢复（真机失效的那条）
        {
            DictPlan p = two(DictPlan.State.RUNNING, 0, DictPlan.State.IDLE);
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.PAUSED));
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("⑤ 计划 RUNNING + 当前任务 PAUSED → RUN_EXISTING 且要求恢复执行",
                    d.action == PlanRecovery.Action.RUN_EXISTING && d.revivePaused,
                    d.toString());
        }

        // ⑥ 进程被杀后（盘上 RUNNING）→ 同样可恢复
        {
            DictPlan p = two(DictPlan.State.RUNNING, 0, DictPlan.State.IDLE);
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.RUNNING));
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("⑥ 当前任务 RUNNING（进程被杀遗留）→ RUN_EXISTING 且要求恢复",
                    d.action == PlanRecovery.Action.RUN_EXISTING && d.revivePaused, d.toString());
        }

        // ⑦ ★ 计划已推进、队列没有下一本 → 幂等补建
        {
            DictPlan p = two(DictPlan.State.RUNNING, 1, DictPlan.State.IDLE);
            p.items.get(1).offset = 7;
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.DONE_MISS));      // 第 1 本已完成
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("⑦ 计划第 2 本无对应任务 → CREATE_NEXT",
                    d.action == PlanRecovery.Action.CREATE_NEXT, d.toString());
            ok("⑦ 补建的是第 2 本的字典（不是第 1 本，已完成字典不重跑）",
                    B.equals(d.dictPath), String.valueOf(d.dictPath));
            ok("⑦ offset 沿用计划里的值（不归零）", d.offset == 7, String.valueOf(d.offset));
        }

        // ⑧ ★ 崩溃窗口：当前本已完成、计划没跟上 → 先推进再补建
        {
            DictPlan p = two(DictPlan.State.RUNNING, 0, DictPlan.State.DONE_MISS);
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.DONE_MISS));
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("⑧ 当前本已完成但计划未推进 → ADVANCE_THEN_CREATE",
                    d.action == PlanRecovery.Action.ADVANCE_THEN_CREATE, d.toString());
            ok("⑧ 推进结论为 MISS，新状态 RUNNING",
                    d.advanceWith == DictPlan.Outcome.MISS
                            && d.planState == DictPlan.State.RUNNING,
                    d.advanceWith + "/" + d.planState);
            ok("⑧ 推进后补建的是下一本", B.equals(d.dictPath), String.valueOf(d.dictPath));
        }

        // ⑨ 用户主动停止的那一本：不得被自动恢复成继续
        {
            DictPlan p = two(DictPlan.State.RUNNING, 0, DictPlan.State.IDLE);
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.STOPPED));
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("⑨ 当前任务被用户停止 → BLOCK 且计划落 STOPPED（不得自动继续）",
                    d.action == PlanRecovery.Action.BLOCK
                            && d.planState == DictPlan.State.STOPPED, d.toString());
        }

        // ⑩ 当前本已命中 → 计划落 DONE_HIT，不再继续
        {
            DictPlan p = two(DictPlan.State.RUNNING, 0, DictPlan.State.IDLE);
            List<TaskQueue.Task> q = new ArrayList<>();
            q.add(task("t1", A, TaskQueue.State.DONE_HIT));
            PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
            ok("⑩ 当前任务已命中 → BLOCK 且计划落 DONE_HIT",
                    d.action == PlanRecovery.Action.BLOCK
                            && d.planState == DictPlan.State.DONE_HIT, d.toString());
        }

        // ⑪ 幂等：同一输入反复决策，结果一致（连续恢复两次、三次）
        {
            for (int round = 1; round <= 3; round++) {
                DictPlan p = two(DictPlan.State.RUNNING, 1, DictPlan.State.IDLE);
                List<TaskQueue.Task> q = new ArrayList<>();
                q.add(task("t1", A, TaskQueue.State.DONE_MISS));
                PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
                ok("⑪ 第 " + round + " 次决策结果一致（CREATE_NEXT / " + B + "）",
                        d.action == PlanRecovery.Action.CREATE_NEXT && B.equals(d.dictPath),
                        d.toString());
            }
        }

        // ⑫ 三本字典：任意一本缺任务都能补对
        {
            DictPlan p = new DictPlan();
            p.state = DictPlan.State.RUNNING;
            p.targetKey = KEY;
            p.autoAdvance = true;
            for (String nm : new String[]{"A", "B", "C"}) {
                DictPlan.Item it = new DictPlan.Item("/d/audit-" + nm + ".txt", nm);
                it.dictSize = 10;
                p.add(it);
            }
            for (int idx = 0; idx < 3; idx++) {
                p.index = idx;
                List<TaskQueue.Task> q = new ArrayList<>();
                PlanRecovery.Decision d = PlanRecovery.decide(p, q, KEY);
                ok("⑫ 第 " + (idx + 1) + " 本缺失 → 补建它自己",
                        d.action == PlanRecovery.Action.CREATE_NEXT
                                && p.items.get(idx).path.equals(d.dictPath),
                        d.action + " / " + d.dictPath);
            }
        }

        // ⑬ 普通队列（无计划）不受影响 —— 回归
        {
            ok("⑬ 无计划时决策恒为 NONE，普通队列语义不受影响",
                    PlanRecovery.decide(new DictPlan(), null, KEY).action
                            == PlanRecovery.Action.NONE, "");
        }
    }

    // ------------------------------------------------------------- 接线契约

    private static void wiring() throws Exception {
        File src = new File(TestPaths.javaPkgDir(), "CrackService.java");
        if (!src.isFile()) {
            ok("CrackService.java 可读（接线断言的前提）", false, src.getAbsolutePath());
            return;
        }
        String s = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);

        ok("startQueue 先走计划恢复（不再因「没有 PENDING」就收工）",
                s.contains("if (recoverPlanForRun()) return;"), "缺少调用");
        ok("recoverPlanForRun 使用 PlanRecovery.decide（决策只有一处实现）",
                s.contains("PlanRecovery.decide("), "缺少调用");
        ok("计划恢复会接 DictPlan.recover（中断归一不再是无接线的死代码）",
                s.contains("DictPlan.recover(p.state)"), "缺少调用");
        ok("runQueue 对计划指向的 PAUSED 任务做接管",
                s.contains("PlanRecovery.sameDict(task, plan.current())"), "缺少接管");
        ok("停止会同步把计划落 STOPPED",
                s.contains("planStop();"), "缺少调用");
        ok("候选为空走统一收尾（不再绕过计划上报）",
                s.contains("finishTaskEarly(task, TaskQueue.State.DONE_MISS"), "缺少调用");
        ok("环境闸门未通过也走统一收尾",
                s.contains("finishTaskEarly(task, TaskQueue.State.PAUSED"), "缺少调用");
        ok("命中终止判定仍在 runQueue 的循环里（不是只在新模块绕过）",
                s.contains("queueStopsAfter(task.state)"), "缺少调用");
        ok("命中（SUCCESS）不写 triedSet",
                s.contains("a.verdict == CrackEngine.Verdict.SUCCESS"), "缺少分支");
        ok("补建失败不假装成功（检查 ensurePlanTask 返回值）",
                s.contains("if (nt == null)"), "缺少检查");
    }

    // ------------------------------------------------------------- 构造

    private static DictPlan two(DictPlan.State st, int index, DictPlan.State curLocal) {
        DictPlan p = new DictPlan();
        p.state = st;
        p.autoAdvance = true;
        p.targetKey = KEY;
        p.targetSsid = "B108";
        p.targetBssid = "68:41:3C:93:F0:34";
        DictPlan.Item a = new DictPlan.Item(A, "audit-A");
        a.dictSize = 3;
        DictPlan.Item b = new DictPlan.Item(B, "audit-B");
        b.dictSize = 4;
        p.add(a);
        p.add(b);
        p.index = Math.max(0, Math.min(index, 1));
        p.items.get(p.index).local = curLocal;
        return p;
    }

    private static TaskQueue.Task task(String id, String dictPath, TaskQueue.State st) {
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = id;
        t.ssid = "B108";
        t.bssid = "68:41:3C:93:F0:34";
        t.dictPath = dictPath;
        t.dictName = dictPath.contains("A") ? "audit-A" : "audit-B";
        t.state = st;
        return t;
    }
}

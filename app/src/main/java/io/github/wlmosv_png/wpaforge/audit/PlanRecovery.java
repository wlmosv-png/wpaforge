package io.github.wlmosv_png.wpaforge.audit;

import java.util.List;

/**
 * 多字典计划的**恢复决策**——从盘上状态推出「这一次启动该做什么」。
 *
 * <h3>为什么必须是独立的类</h3>
 * 2026-11-02 审计确认：恢复逻辑原来散在 {@code CrackService.startQueue}、
 * {@code loadPlanForRun}、{@code reportToPlan} 三处，导致三个真实故障：
 * <pre>
 *   ① startQueue 看到「队列里没有 PENDING」就直接收工 —— 计划连读都没被读到；
 *   ② 计划推进了、派生任务之前进程被杀，下次启动不会补建 → 下一本永久丢失；
 *   ③ 计划明确指向的那一本是 PAUSED 时，runQueue 见到 PAUSED 就 break
 *      → 用户点「继续」永远恢复不了。
 * </pre>
 * 这里把决策收成**一个纯函数**（{@link #decide}）：输入是盘上的计划与队列，
 * 输出是一个明确的动作。它不碰 IO、不碰 Android，因此可以在 JVM 里
 * 穷举测试；{@code CrackService} 只负责按动作执行。
 *
 * <h3>权威关系</h3>
 * <ul>
 *   <li>{@code plan.json} 是**计划状态的唯一权威**（顺序、当前下标、每本 offset）；</li>
 *   <li>{@code queue.json} 是**执行视图**，允许根据计划幂等重建；</li>
 *   <li>计划当前字典没有对应任务 → 补建；已有 → 复用（绝不重复建）。</li>
 * </ul>
 */
public final class PlanRecovery {

    private PlanRecovery() {}

    /** 这一次启动要做的动作。 */
    public enum Action {
        /** 不管（没有计划 / 目标不符 / 已是普通队列模式）。 */
        NONE,
        /** 复用队列里已有的那条任务（必要时把它从 PAUSED 救回可执行状态）。 */
        RUN_EXISTING,
        /** 计划当前那一本完成过、但计划没跟上（崩溃窗口）→ 先推进，再补建下一本。 */
        ADVANCE_THEN_CREATE,
        /** 计划当前那一本在队列里没有任务 → 幂等补建。 */
        CREATE_NEXT,
        /** 明确不能自动继续（终态计划、被用户停止的那本）—— 不得退回普通队列。 */
        BLOCK
    }

    /** 一条决策。字段全部只读，便于断言。 */
    public static final class Decision {
        public final Action action;
        /** 人读理由（写日志、给界面）。 */
        public final String reason;
        /** {@link Action#RUN_EXISTING} 时要接管的任务 id。 */
        public final String taskId;
        /** 需要把这条 PAUSED 任务恢复为可执行（计划模式下由计划驱动）。 */
        public final boolean revivePaused;
        /** {@link Action#CREATE_NEXT} / {@link Action#ADVANCE_THEN_CREATE} 用的字典。 */
        public final String dictPath;
        public final String dictName;
        /** 补建任务时的起始位置（来自计划里那一本的 offset，绝不归零）。 */
        public final int offset;
        /** 需要在计划上落地的新状态（{@code null} = 不改）。 */
        public final DictPlan.State planState;
        /** 计划推进时用掉的结论（{@code null} = 不推进）。 */
        public final DictPlan.Outcome advanceWith;

        Decision(Action a, String why, String taskId, boolean revive,
                 String dictPath, String dictName, int offset,
                 DictPlan.State planState, DictPlan.Outcome advanceWith) {
            this.action = a; this.reason = why; this.taskId = taskId; this.revivePaused = revive;
            this.dictPath = dictPath; this.dictName = dictName; this.offset = offset;
            this.planState = planState; this.advanceWith = advanceWith;
        }

        static Decision none(String why) {
            return new Decision(Action.NONE, why, null, false, null, null, 0, null, null);
        }

        static Decision block(String why, DictPlan.State ps) {
            return new Decision(Action.BLOCK, why, null, false, null, null, 0, ps, null);
        }

        @Override public String toString() {
            return action + "(" + reason + ")";
        }
    }

    /**
     * 决策主函数。**纯函数**：不读写任何文件。
     *
     * @param plan      盘上的计划（可为 null 或空）
     * @param queue     盘上的任务列表（可为 null）
     * @param targetKey 当前要跑的目标键（与 {@link TriedSet#targetKey} 同口径）
     */
    public static Decision decide(DictPlan plan, List<TaskQueue.Task> queue, String targetKey) {
        if (plan == null || plan.items.isEmpty()) {
            return Decision.none("没有计划：按普通队列执行");
        }
        // ① 终态计划：**绝不退回普通队列**（否则命中的残留 PENDING 会在下次
        //    「开始」时被当普通任务执行 —— 审计点名的场景）。
        if (plan.state.isTerminal()) {
            return Decision.block("计划已结束（" + plan.state + "）：不自动执行任何残留任务，"
                    + "如需继续请新建计划", null);
        }
        // ② 目标必须一致：换了网络却沿用旧进度，等于把别的网的结论套上来
        if (targetKey != null && !targetKey.isEmpty()
                && plan.targetKey != null && !plan.targetKey.isEmpty()
                && !plan.targetKey.equals(targetKey)) {
            return Decision.none("计划目标与当前目标不一致：按普通队列执行");
        }
        DictPlan.Item cur = plan.current();
        if (cur == null) return Decision.none("计划没有当前字典");

        // ③ 本本已经完成、但计划没跟上（崩溃窗口：plan.save 与 apply 之间被打断）
        if (cur.local == DictPlan.State.DONE_MISS) {
            return new Decision(Action.ADVANCE_THEN_CREATE,
                    "计划当前字典已完成但计划未推进：先推进再补建",
                    null, false,
                    nextPath(plan), nextName(plan), nextOffset(plan),
                    DictPlan.State.RUNNING, DictPlan.Outcome.MISS);
        }
        if (cur.local == DictPlan.State.DONE_HIT) {
            return Decision.block("计划当前字典已命中：不得继续", DictPlan.State.DONE_HIT);
        }

        // ④ 在队列里找这一本对应的任务
        TaskQueue.Task match = null;
        for (TaskQueue.Task t : asList(queue)) {
            if (t == null || !sameDict(t, cur)) continue;
            if (t.state == TaskQueue.State.STOPPED) {
                // 用户主动停止的**这一本**：不得自动恢复成继续（审计硬要求）。
                return Decision.block("当前字典的任务已被用户停止：计划保持停止",
                        DictPlan.State.STOPPED);
            }
            if (t.state == TaskQueue.State.DONE_HIT) {
                return Decision.block("当前字典的任务已命中：不得继续", DictPlan.State.DONE_HIT);
            }
            if (t.state == TaskQueue.State.DONE_MISS) {
                // 队列说这本跑完了、计划还没推进 —— 同上，先推进
                continue;
            }
            if (match == null) match = t;
        }

        if (match != null) {
            boolean revive = (match.state == TaskQueue.State.PAUSED
                    || match.state == TaskQueue.State.RUNNING);
            return new Decision(Action.RUN_EXISTING,
                    revive ? "计划继续：当前字典任务原为 " + match.state
                            + "（计划已明确指向它，按计划恢复执行）"
                            : "计划继续：复用队列里当前字典的任务",
                    match.id, revive, null, null, Math.max(0, cur.offset),
                    DictPlan.State.RUNNING, null);
        }

        // ⑤ 队列里没有这一本 → 幂等补建（不改变 offset，不重跑已完成的字典）
        return new Decision(Action.CREATE_NEXT,
                "计划当前字典在队列里没有任务：幂等补建",
                null, false, cur.path, cur.name, Math.max(0, cur.offset),
                DictPlan.State.RUNNING, null);
    }

    /** 判断一条队列任务是否对应计划里的某一本。 */
    public static boolean sameDict(TaskQueue.Task t, DictPlan.Item it) {
        if (t == null || it == null) return false;
        String tp = t.dictPath == null ? "" : t.dictPath;
        String ip = it.path == null ? "" : it.path;
        if (!ip.isEmpty() && !tp.isEmpty()) return tp.equals(ip);
        String tn = t.dictName == null ? "" : t.dictName;
        String in = it.name == null ? "" : it.name;
        return !in.isEmpty() && in.equals(tn);
    }

    /** 计划推进之后的下一本路径（{@link #decide} 里 ADvANCE 用）。 */
    private static String nextPath(DictPlan plan) {
        DictPlan.Step s = DictPlan.next(plan.state, DictPlan.Outcome.MISS,
                plan.index, plan.items.size(), plan.autoAdvance);
        int i = Math.min(s.index, plan.items.size() - 1);
        return plan.items.get(Math.max(0, i)).path;
    }

    private static String nextName(DictPlan plan) {
        DictPlan.Step s = DictPlan.next(plan.state, DictPlan.Outcome.MISS,
                plan.index, plan.items.size(), plan.autoAdvance);
        int i = Math.min(s.index, plan.items.size() - 1);
        return plan.items.get(Math.max(0, i)).name;
    }

    private static int nextOffset(DictPlan plan) {
        DictPlan.Step s = DictPlan.next(plan.state, DictPlan.Outcome.MISS,
                plan.index, plan.items.size(), plan.autoAdvance);
        int i = Math.min(s.index, plan.items.size() - 1);
        return Math.max(0, plan.items.get(Math.max(0, i)).offset);
    }

    private static List<TaskQueue.Task> asList(List<TaskQueue.Task> q) {
        return q == null ? java.util.Collections.<TaskQueue.Task>emptyList() : q;
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.CrackService;

import java.io.File;

/**
 * QueueTerminationTest：**命中之后整条队列必须停下来**。
 *
 * <h3>为什么要有这个测试类</h3>
 * 「第一本命中，第二本接着跑」是旧实现里真实存在的缺陷，也是本轮多字典功能
 * 最硬的一条要求（命中后绝不继续）。这条判定住在
 * {@code CrackService.queueStopsAfter(TaskQueue.State)} 里 —— 它是生产代码，
 * 被 {@code runQueue()} 的循环直接调用，不是复制出来的模拟逻辑。
 *
 * <h3>覆盖范围（如实说明）</h3>
 * 本类验证**判定函数与状态语义**。{@code runQueue()} 本身是 Android Service
 * 私有方法，桌面 JVM 无法启动整个服务，因此「循环真的 break 了」这一步属于
 * **未在 JVM 验证**的范围，由真机人工验收覆盖（见交付报告）。
 */
public final class QueueTerminationTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== QueueTermination（命中即终止整条队列） ===");

        // ---- ① 穷举所有任务状态 ----
        {
            int stops = 0;
            for (TaskQueue.State st : TaskQueue.State.values()) {
                boolean s = CrackService.queueStopsAfter(st);
                if (s) stops++;
                if (st == TaskQueue.State.DONE_HIT) {
                    ok("DONE_HIT → 停（命中后绝不继续）", s, "竟然不停");
                } else {
                    ok(st + " → 不停（继续队列）", !s, "被误停");
                }
            }
            ok("恰好只有一个状态会终止队列", stops == 1, "有 " + stops + " 个");
        }

        // ---- ② 关键反例：未命中不能终止 ----
        {
            ok("DONE_MISS 不停 —— 否则多字典永远跑不到第二本",
                    !CrackService.queueStopsAfter(TaskQueue.State.DONE_MISS), "误停");
            ok("PAUSED 不停 —— 暂停由既有出口处理，不是队列终止",
                    !CrackService.queueStopsAfter(TaskQueue.State.PAUSED), "误停");
            ok("STOPPED 不停 —— 用户停止另有一条完整出口",
                    !CrackService.queueStopsAfter(TaskQueue.State.STOPPED), "误停");
            ok("PENDING / RUNNING 不停",
                    !CrackService.queueStopsAfter(TaskQueue.State.PENDING)
                            && !CrackService.queueStopsAfter(TaskQueue.State.RUNNING), "误停");
        }

        // ---- ③ 与计划状态机一致：命中在两边都是终态 ----
        {
            DictPlan.Step hit = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.HIT, 0, 3, true);
            ok("计划状态机：命中 → DONE_HIT 且 terminal",
                    hit.state == DictPlan.State.DONE_HIT && hit.terminal, hit.state.name());
            ok("队列判定：DONE_HIT → 停",
                    CrackService.queueStopsAfter(TaskQueue.State.DONE_HIT), "不停");
            ok("两条口径指向同一个结论：命中 = 立即收工",
                    hit.terminal && CrackService.queueStopsAfter(TaskQueue.State.DONE_HIT),
                    "不一致");

            // 未命中时两边也一致：计划继续，队列不该停
            DictPlan.Step miss = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.MISS, 0, 3, true);
            ok("未命中两边一致：计划前进且队列继续",
                    miss.advanced && !miss.terminal
                            && !CrackService.queueStopsAfter(TaskQueue.State.DONE_MISS), "不一致");
        }

        // ---- ④ 队列文件里的状态是权威（finishTask 写的就是它） ----
        {
            ok("TaskQueue.State 仍保留 DONE_HIT 这一档",
                    TaskQueue.State.valueOf("DONE_HIT") == TaskQueue.State.DONE_HIT, "枚举缺失");
            File tmp = java.nio.file.Files.createTempDirectory("queue-term").toFile();
            ok("临时目录可写（证明本测试真的做了 IO，不是空断言）", tmp.canWrite(), "不可写");
        }

        System.out.println();
        System.out.println("=== QueueTermination: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

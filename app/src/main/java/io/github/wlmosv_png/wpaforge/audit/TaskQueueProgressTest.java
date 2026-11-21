package io.github.wlmosv_png.wpaforge.audit;

/**
 * 任务卡「进度数字自洽」测试（1.9.68）。纯逻辑。
 *
 * <p>真机事故：卡片显示「进度 0 / 3336」，同一张卡又说「本轮试了 22 条」，
 * 进度条却是满格 —— 三个数字互相矛盾。
 *
 * <p>根因：{@code offset} 只在任务结束时由服务写回（它是「续跑安全边界」，
 * 跑动中不能动），而进度条用 {@code widthPixels * offset/dictSize - 64px} 猜像素宽度，
 * p=0 时算出负数、p 稍大就溢满。
 *
 * <p>本测试钉死：展示口径 = 续跑边界 + 本轮已试，且进度条比例与该口径同源。
 */
public final class TaskQueueProgressTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    private static TaskQueue.Task t(int offset, int tested, int size) {
        TaskQueue.Task task = new TaskQueue.Task();
        task.offset = offset;
        task.testedThisRun = tested;
        task.dictSize = size;
        return task;
    }

    public static void main(String[] args) {
        System.out.println("== 任务卡进度口径 (1.9.68) ==");
        try {
            // 1. 真机现场：offset=0、本轮试了 22、字典 3336
            TaskQueue.Task a = t(0, 22, 3336);
            ok("★offset=0 + 本轮22 → 已完成 = 22", a.completedCount() == 22,
                    "" + a.completedCount());
            ok("★显示进度 ≈ 22/3336（不是满格）",
                    a.displayProgress() < 0.01f && a.displayProgress() > 0f,
                    "" + a.displayProgress());

            // 2. 续跑到 100 之后再试 5 条
            TaskQueue.Task b = t(100, 5, 1000);
            ok("续跑边界 + 本轮 = 105", b.completedCount() == 105, "" + b.completedCount());
            ok("进度 10.5%", Math.abs(b.displayProgress() - 0.105f) < 0.001f,
                    "" + b.displayProgress());

            // 3. 上限钳制：不会超过字典长度
            TaskQueue.Task c = t(990, 100, 1000);
            ok("★超出上限钳到字典长度", c.completedCount() == 1000, "" + c.completedCount());
            ok("进度钳到 1.0", c.displayProgress() == 1f, "" + c.displayProgress());

            // 4. 非法输入不炸：dictSize=0
            TaskQueue.Task d = t(0, 0, 0);
            ok("dictSize=0 → 已完成 0", d.completedCount() == 0);
            ok("dictSize=0 → 进度 0", d.displayProgress() == 0f);

            // 5. 负数/异常值不产生负进度
            TaskQueue.Task e = t(-5, -3, 100);
            ok("★负值不会算出负进度", e.completedCount() >= 0 && e.displayProgress() >= 0f,
                    e.completedCount() + "/" + e.displayProgress());

            // 6. 数字自洽：completedCount 与 displayProgress 同源
            TaskQueue.Task f = t(250, 250, 1000);
            ok("★completed 与 progress 同源",
                    f.completedCount() == 500
                            && Math.abs(f.displayProgress() - 0.5f) < 0.0001f,
                    f.completedCount() + " / " + f.displayProgress());

            // 7. 旧的 progress()（只按 offset）仍保留原语义，不被误改
            TaskQueue.Task g = t(0, 22, 3336);
            ok("旧 progress() 仍只看 offset（续跑语义不变）", g.progress() == 0f);

        } catch (Throwable e) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + e);
            e.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 任务卡进度口径: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

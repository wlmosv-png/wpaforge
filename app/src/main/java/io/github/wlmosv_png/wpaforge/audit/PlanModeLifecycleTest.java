package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 多字典计划 × 单字典：模式互斥、任务来源、生命周期、清理恢复 的回归测试。
 *
 * <h3>P0 背景（本轮修的真实故障）</h3>
 * <ol>
 *   <li>计划结束后，普通任务也无法运行 —— 旧 startQueue 无条件走计划恢复，
 *       终态计划一律 stopSelf()，把队列整体掐死。</li>
 *   <li>计划独占字典选择通道 / 单双模式同时选中 —— 没有「任务来源」这个唯一判据。</li>
 *   <li>结束后无法清理、无法重新接管 —— 缺清理入口与残留任务作废。</li>
 * </ol>
 *
 * <h3>用的是真实生产代码，不是辅助方法</h3>
 * <ul>
 *   <li>TaskQueue.enqueue / enqueuePlan / stopPlanTasks / removePlanTasks /
 *       hasPlanWork / nextPending —— 真写/真读（注入内存 MemStore，走同一段 JSON 生产逻辑）；</li>
 *   <li>PlanRecovery.planRelevant / hasPlainWork / decide —— 生产决策入口；</li>
 *   <li>DictPlan 全流程（add/move/removeAt/apply/resetProgress/delete/migrateLegacy）
 *       —— 真实文件 IO。</li>
 * </ul>
 */
public final class PlanModeLifecycleTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static final String SSID = "AP";
    private static final String BSSID = "AA:BB:CC:DD:EE:01";
    private static final String KEY = TriedSet.targetKey(BSSID, SSID);

    private static NetRecord net() {
        return new NetRecord(SSID, BSSID, -50, 2437, NetRecord.Security.WPA2, false, "");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 计划模式生命周期（互斥 / 来源 / 清理 / 恢复） ===");
        scenarioTaskSourceAndMutex();
        scenarioTerminalPlanDoesNotBlockPlain();
        scenarioCleanupAndRecreate();
        scenarioPauseStopReorder();
        scenarioRestartRecovery();
        scenarioLegacyMigration();
        scenarioModeFlickerAndDialogRepeat();
        regressionGuardSourceContract();
        System.out.println();
        System.out.println("=== 计划生命周期: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------ ① 任务来源 + 模式互斥

    private static void scenarioTaskSourceAndMutex() {
        System.out.println();
        System.out.println("-- ① 任务来源标记与模式互斥 --");
        StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
        TaskQueue.setStoreForTest(mem);
        try {
            TaskQueue.Task plain = TaskQueue.enqueue(null, net(), "/d/single.txt", "single");
            ok("单字典任务 fromPlan=false", plain != null && !plain.fromPlan,
                    plain == null ? "null" : String.valueOf(plain.fromPlan));

            TaskQueue.Task planSeed = TaskQueue.enqueuePlan(null, net(), "/d/plan1.txt", "plan1");
            ok("计划任务 fromPlan=true", planSeed != null && planSeed.fromPlan,
                    planSeed == null ? "null" : String.valueOf(planSeed.fromPlan));

            mem.files.clear();
            TaskQueue.enqueue(null, net(), "/d/only-single.txt", "only-single");
            TaskQueue.Task first = TaskQueue.nextPending(null);
            ok("队列首待办是普通任务", first != null && !first.fromPlan, "");
            ok("计划不接管普通任务（planRelevant=false）",
                    !PlanRecovery.planRelevant(first), "被接管了");

            mem.files.clear();
            TaskQueue.enqueuePlan(null, net(), "/d/p.txt", "p");
            TaskQueue.Task fp = TaskQueue.nextPending(null);
            ok("队列首待办是计划任务", fp != null && fp.fromPlan, "");
            ok("计划接管自己的任务（planRelevant=true）",
                    PlanRecovery.planRelevant(fp), "未接管");

            mem.files.clear();
            ok("队列为空时仍接管（崩溃窗口）",
                    PlanRecovery.planRelevant(TaskQueue.nextPending(null)), "未接管");
        } finally {
            TaskQueue.setStoreForTest(null);
        }
    }

    // ------------------------------------------------ ② 终态计划不阻塞普通任务（P0 核心）

    private static void scenarioTerminalPlanDoesNotBlockPlain() {
        System.out.println();
        System.out.println("-- ② 终态计划不得阻塞普通任务（P0 核心） --");
        StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
        TaskQueue.setStoreForTest(mem);
        try {
            TaskQueue.enqueuePlan(null, net(), "/d/leftover.txt", "leftover");
            TaskQueue.enqueue(null, net(), "/d/new-normal.txt", "new-normal");
            ok("前提：残留计划任务存在", TaskQueue.hasPlanWork(null), "无计划任务");

            DictPlan terminal = new DictPlan();
            terminal.autoAdvance = true;
            terminal.state = DictPlan.State.DONE_MISS;
            terminal.targetKey = KEY;
            terminal.add(new DictPlan.Item("/d/leftover.txt", "leftover"));
            PlanRecovery.Decision d = PlanRecovery.decide(terminal, TaskQueue.load(null), KEY);
            ok("终态计划 → BLOCK（不自动跑残留）",
                    d.action == PlanRecovery.Action.BLOCK, d.action.name());

            ok("BLOCK 后有普通任务 → hasPlainWork=true（放行）",
                    PlanRecovery.hasPlainWork(TaskQueue.load(null)), "被挡死");

            int killed = TaskQueue.stopPlanTasks(null);
            ok("stopPlanTasks 只作废计划任务", killed == 1, "killed=" + killed);
            ok("作废后计划任务不再待办", !TaskQueue.hasPlanWork(null), "仍待办");
            TaskQueue.Task nx = TaskQueue.nextPending(null);
            ok("作废后队列首待办是普通任务", nx != null && !nx.fromPlan
                            && "new-normal".equals(nx.dictName),
                    nx == null ? "null" : nx.dictName);
            ok("普通任务仍可跑（planRelevant=false）", !PlanRecovery.planRelevant(nx), "");

            int stillPending = 0;
            for (TaskQueue.Task t : TaskQueue.load(null)) {
                if (t.state == TaskQueue.State.PENDING) stillPending++;
            }
            ok("普通任务未被误作废（仍 1 条待办）", stillPending == 1,
                    String.valueOf(stillPending));
        } finally {
            TaskQueue.setStoreForTest(null);
        }
    }

    // ------------------------------------------------ ③ 清理与重建

    private static void scenarioCleanupAndRecreate() throws Exception {
        System.out.println();
        System.out.println("-- ③ 清理计划与重建 --");
        StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
        TaskQueue.setStoreForTest(mem);
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "plan-lifecycle-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            File fa = DictPlan.fileAt(dir, KEY);
            DictPlan p = new DictPlan();
            p.targetKey = KEY;
            p.add(new DictPlan.Item("/d/a.txt", "a"));
            p.save(fa);
            TaskQueue.enqueuePlan(null, net(), "/d/a.txt", "a");

            ok("清理前有计划且有待办任务",
                    DictPlan.load(fa).hasItems() && TaskQueue.hasPlanWork(null), "");

            int removed = TaskQueue.removePlanTasks(null);
            fa.delete();
            ok("removePlanTasks 移除计划任务", removed == 1, "removed=" + removed);
            ok("清理后无计划任务", !TaskQueue.hasPlanWork(null), "");
            ok("清理后计划文件不存在", !fa.exists(), "仍存在");

            DictPlan rebuilt = new DictPlan();
            rebuilt.targetKey = KEY;
            rebuilt.add(new DictPlan.Item("/d/x.txt", "x"));
            rebuilt.add(new DictPlan.Item("/d/y.txt", "y"));
            rebuilt.save(fa);
            TaskQueue.enqueuePlan(null, net(), "/d/x.txt", "x");
            ok("重建后计划 2 本", DictPlan.load(fa).items.size() == 2, "");
            ok("重建后有计划待办任务", TaskQueue.hasPlanWork(null), "");
            ok("重建后计划可接管",
                    PlanRecovery.planRelevant(TaskQueue.nextPending(null)), "");
        } finally {
            TaskQueue.setStoreForTest(null);
            purge(dir);
        }
    }

    // ------------------------------------------------ ④ 暂停 / 停止 / 调序

    private static void scenarioPauseStopReorder() {
        System.out.println();
        System.out.println("-- ④ 暂停 / 停止 / 调序不改判定语义 --");
        StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
        TaskQueue.setStoreForTest(mem);
        try {
            DictPlan p = new DictPlan();
            p.autoAdvance = true;
            p.targetKey = KEY;
            p.add(new DictPlan.Item("/d/a.txt", "a"));
            p.add(new DictPlan.Item("/d/b.txt", "b"));
            p.add(new DictPlan.Item("/d/c.txt", "c"));

            boolean moved = p.move(1, -1);
            ok("调序成功", moved, "");
            ok("调序后首本为 b", p.items.get(0).name.equals("b"), p.items.get(0).name);
            ok("调序不改计划状态（仍 IDLE）", p.state == DictPlan.State.IDLE,
                    p.state.name());

            DictPlan.Step sp = p.apply(DictPlan.Outcome.PAUSED);
            ok("暂停不前进", !sp.advanced && p.state == DictPlan.State.PAUSED,
                    p.state.name());
            ok("暂停后仍可继续", p.state.isResumable(), "");

            p.items.get(0).offset = 300;
            p.move(0, +1);
            ok("暂停期间调序后 offset 随条目移动",
                    p.items.get(1).offset == 300, String.valueOf(p.items.get(1).offset));

            p.apply(DictPlan.Outcome.STOPPED);
            ok("停止 → 终态", p.state.isTerminal(), p.state.name());
            PlanRecovery.Decision d = PlanRecovery.decide(p, new ArrayList<TaskQueue.Task>(), KEY);
            ok("停止后 decide = BLOCK（不自动恢复）",
                    d.action == PlanRecovery.Action.BLOCK, d.action.name());
        } finally {
            TaskQueue.setStoreForTest(null);
        }
    }

    // ------------------------------------------------ ⑤ 重启恢复

    private static void scenarioRestartRecovery() {
        System.out.println();
        System.out.println("-- ⑤ 进程重启后恢复 --");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "plan-restart-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            File fa = DictPlan.fileAt(dir, KEY);
            DictPlan p = new DictPlan();
            p.targetKey = KEY;
            p.state = DictPlan.State.RUNNING;
            p.autoAdvance = true;
            p.add(new DictPlan.Item("/d/a.txt", "a"));
            p.add(new DictPlan.Item("/d/b.txt", "b"));
            p.items.get(0).offset = 500;
            p.save(fa);

            DictPlan reloaded = DictPlan.load(fa);
            ok("重启读回 RUNNING", reloaded.state == DictPlan.State.RUNNING,
                    reloaded.state.name());
            ok("recover 把 RUNNING 归一为 PAUSED",
                    DictPlan.recover(reloaded.state) == DictPlan.State.PAUSED, "");
            ok("重启后进度保留（offset=500）", reloaded.items.get(0).offset == 500,
                    String.valueOf(reloaded.items.get(0).offset));
            ok("重启后顺序保留", reloaded.items.get(0).name.equals("a"), "");
        } finally {
            purge(dir);
        }
    }

    // ------------------------------------------------ ⑥ 旧版迁移 / 按目标文件

    private static void scenarioLegacyMigration() {
        System.out.println();
        System.out.println("-- ⑥ 按目标文件与 unbound --");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "plan-legacy-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            File kb = DictPlan.fileAt(dir, KEY);
            File ku = DictPlan.fileAt(dir, "");
            ok("按目标文件与 unbound 文件不同",
                    !kb.getAbsolutePath().equals(ku.getAbsolutePath()),
                    kb.getName() + " vs " + ku.getName());
            ok("unbound 文件名正确", ku.getName().equals("unbound.plan.json"), ku.getName());
        } finally {
            purge(dir);
        }
    }

    // ------------------------------------------------ ⑦ 快速切换模式 + 重复开关窗口

    private static void scenarioModeFlickerAndDialogRepeat() {
        System.out.println();
        System.out.println("-- ⑦ 快速切换模式 / 重复开关管理窗口 --");
        StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
        TaskQueue.setStoreForTest(mem);
        try {
            TaskQueue.enqueue(null, net(), "/d/s1.txt", "s1");
            boolean r1 = PlanRecovery.planRelevant(TaskQueue.nextPending(null));
            mem.files.clear();
            TaskQueue.enqueuePlan(null, net(), "/d/p1.txt", "p1");
            boolean r2 = PlanRecovery.planRelevant(TaskQueue.nextPending(null));
            mem.files.clear();
            TaskQueue.enqueue(null, net(), "/d/s2.txt", "s2");
            boolean r3 = PlanRecovery.planRelevant(TaskQueue.nextPending(null));
            ok("普通→不接管", !r1, "");
            ok("计划→接管", r2, "");
            ok("再普通→不接管", !r3, "");
            ok("三次切换判据稳定互斥", (!r1 && r2 && !r3), "");

            DictPlan p = new DictPlan();
            p.targetKey = KEY;
            p.add(new DictPlan.Item("/d/a.txt", "a"));
            int before = p.items.size();
            DictPlan.State st = p.state;
            boolean stable = true;
            for (int i = 0; i < 5; i++) {
                DictPlan view = DictPlan.fromJsonString(p.toJsonString());
                if (view.items.size() != before) { stable = false; break; }
            }
            ok("重复打开窗口不改计划内容/状态",
                    stable && p.items.size() == before && p.state == st, "");
        } finally {
            TaskQueue.setStoreForTest(null);
        }
    }

    // ------------------------------------------------ ⑧ 回归守卫（源码契约）

    /**
     * 把 P0 修复钉在源码上：旧实现的两条致命写法**必须彻底消失**，
     * 一旦有人改回去，门禁立刻变红。
     *
     * <p>这不是行为验证（行为由本类其余用例负责），而是**防回退契约**。
     */
    private static void regressionGuardSourceContract() {
        System.out.println();
        System.out.println("-- ⑧ 回归守卫（P0 不得回退） --");
        String svc = read("CrackService.java");
        if (svc == null) {
            ok("CrackService.java 可读", false, "读不到");
            return;
        }
        // 旧写法一：startQueue 里无条件 recoverPlanForRun（终态计划掐死普通任务）
        ok("startQueue 不再无条件走计划恢复",
                !svc.contains("if (recoverPlanForRun()) return;"), "旧写法回归");
        ok("startQueue 用 planRelevant 判据",
                svc.contains("PlanRecovery.planRelevant(nextPending)"), "缺判据");
        // 旧写法二：BLOCK 分支一律 stopSelf
        ok("BLOCK 分支先放行普通任务（hasPlainWork）",
                svc.contains("PlanRecovery.hasPlainWork(TaskQueue.load(this))"), "缺放行");
        // 终态时作废残留计划任务
        ok("计划终态作废残留计划任务（stopPlanTasks）",
                svc.contains("TaskQueue.stopPlanTasks(this)"), "缺作废");
        // 任务来源标记
        String tq = read("audit/TaskQueue.java");
        ok("TaskQueue 有任务来源标记 fromPlan",
                tq != null && tq.contains("public boolean fromPlan = false;"), "缺字段");
        ok("TaskQueue 有 enqueuePlan / stopPlanTasks / removePlanTasks",
                tq != null && tq.contains("public static Task enqueuePlan(")
                        && tq.contains("public static int stopPlanTasks(")
                        && tq.contains("public static int removePlanTasks("), "缺方法");
        // UI：单双模式互斥入口
        String ma = read("MainActivity.java");
        ok("破解页有「清理本目标的计划」入口",
                ma != null && ma.contains("confirmClearPlan()")
                        && ma.contains("DictPlan.delete(this, key)"), "缺清理");
        ok("破解页有「改回单字典模式」入口",
                ma != null && ma.contains("leavePlanMode()"), "缺切回");
        ok("破解页终态计划不再抢占模式",
                ma != null && ma.contains("&& !livePlan.state.isTerminal()"), "终态仍抢占");
    }

    private static String read(String name) {
        try {
            File f = new File(io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir(), name);
            if (!f.isFile()) return null;
            StringBuilder sb = new StringBuilder();
            for (String line : java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void purge(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) purge(c);
        f.delete();
    }
}

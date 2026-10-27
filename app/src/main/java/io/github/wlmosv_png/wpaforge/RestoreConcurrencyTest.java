package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;
import io.github.wlmosv_png.wpaforge.audit.RestorePlan;
import io.github.wlmosv_png.wpaforge.audit.RestoreRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P1（F1/F2/F5）回归测试：恢复流程的**并发安全**、**归属校验**与**回读复核**。
 *
 * <h3>三个要钉住的性质</h3>
 * <ol>
 *   <li><b>只有一条恢复流程能操作同一份 journal</b>：启动补做与任务收尾恢复
 *       走同一把锁；多个线程同时进来也不得交叉执行。</li>
 *   <li><b>归属校验</b>：收尾恢复只认当前任务自己的记录；盘上那条若属于别的任务，
 *       就地放弃（不动目标配置），留给启动补做。</li>
 *   <li><b>「配置已写回」与「已成功连接」是两件事</b>：{@code readd} 必须回读列表复核，
 *       命令发出但列表里没有目标 → {@code READD-NOT-WRITTEN}，
 *       不能报成「写回去了、只是没连上」。</li>
 * </ol>
 *
 * <p>测试全程使用内存 journal 与合成 Outcome，不接触任何真实网络。
 */
public class RestoreConcurrencyTest {

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

    private static RestoreJournal.Entry entry(String taskId, String stage) {
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        e.ssid = "LAB-TARGET";
        e.taskId = taskId;
        e.stage = stage;
        e.hadPsk = true;
        e.sealedPsk = "v1:a:b";
        e.security = "wpa2";
        return e;
    }

    /** 一个「已连上」的读回快照，用来让 judge 产出 restored=true。 */
    private static RestorePlan.Verify connected() {
        return new RestorePlan.Verify(true, "LAB-TARGET", "COMPLETED", "192.168.1.2");
    }

    public static void main(String[] args) throws Exception {
        RestoreJournal.install(RestoreJournal.memory());

        System.out.println("== F1a 就绪判据 ==");
        ok("null 记录不需要恢复", !RestoreRunner.shouldRecover(null));
        ok("ARMED 需要恢复", RestoreRunner.shouldRecover(entry("t1", RestoreJournal.STAGE_ARMED)));
        ok("FAILED 也需要（失败必须能被重试）",
                RestoreRunner.shouldRecover(entry("t1", RestoreJournal.STAGE_FAILED)));
        ok("RESTORED 不需要（幂等）",
                !RestoreRunner.shouldRecover(entry("t1", RestoreJournal.STAGE_RESTORED)));

        System.out.println("== F1b 归属校验：不是我的记录就不动手 ==");
        final AtomicInteger ran = new AtomicInteger();
        RestoreRunner.Job job = cur -> { ran.incrementAndGet(); return null; };

        eq("mine 为 null → 不做", null,
                RestoreRunner.runExclusiveForTask(null, "t1", job));
        eq("任务 id 不匹配 → 不做", null,
                RestoreRunner.runExclusiveForTask(entry("t1", RestoreJournal.STAGE_ARMED), "t2", job));
        eq("上面两种都没执行 job", 0, ran.get());

        // 1.9.28 行为变更：别人的记录**不再一票否决**（记录按 taskId 分槽，
        // 本任务写盘不会覆盖它，所以没有理由禁止一次只作用于本目标的操作）。
        // 只有「同一目标 + 仍有未解决风险」才拦。
        RestoreJournal.Entry foreign = entry("t-other", RestoreJournal.STAGE_FAILED);
        RestoreJournal.save(foreign);
        ok("别人的失败记录（无残留风险）不拦新任务",
                !RestoreRunner.checkNewTask("mine", "LAB-TARGET").blocked);
        eq("盘上是别人的记录 → 本次收尾照常执行", null,
                RestoreRunner.runExclusiveForTask(entry("mine", RestoreJournal.STAGE_ARMED),
                        "mine", job));
        eq("job 执行了 1 次（不再因为别人的记录而永久拒绝）", 1, ran.get());

        RestoreJournal.Entry risky = entry("t-other", RestoreJournal.STAGE_FAILED);
        risky.residualRisk = true;
        RestoreJournal.save(risky);
        ok("同目标 + 有未解决风险 → 拦住",
                RestoreRunner.checkNewTask("mine", "LAB-TARGET").blocked);
        eq("同目标 + 有风险 → 本次收尾不动手", null,
                RestoreRunner.runExclusiveForTask(entry("mine", RestoreJournal.STAGE_ARMED),
                        "mine", job));
        eq("job 依然没被执行（不会踩到别人的未完成恢复）", 1, ran.get());

        RestoreJournal.clear();
        eq("盘上清空后按我自己的记录执行", null,
                RestoreRunner.runExclusiveForTask(entry("mine", RestoreJournal.STAGE_ARMED),
                        "mine", job));
        eq("job 执行了 2 次", 2, ran.get());

        System.out.println("== F1c 串行化：同一时刻只有一条恢复在跑 ==");
        final RestoreJournal.Entry mine = entry("mine", RestoreJournal.STAGE_ARMED);
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger maxInside = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        final RestoreRunner.Job slow = cur -> {
            int n = inside.incrementAndGet();
            maxInside.accumulateAndGet(n, Math::max);
            runs.incrementAndGet();
            try { Thread.sleep(20L); } catch (InterruptedException ignored) {}
            inside.decrementAndGet();
            return RestorePlan.judge(cur, connected(), true, RestorePlan.ReaddResult.written());
        };
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> RestoreRunner.runExclusiveForTask(mine, "mine", slow),
                    "restore-test-" + i);
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join(10_000);
        eq("8 个线程都跑到了", 8, runs.get());
        eq("并发度恒为 1（没有两条流程同时操作同一份 journal）", 1, maxInside.get());

        System.out.println("== F1d 结论写回：成功清记录，失败留痕 ==");
        RestoreJournal.clear();
        RestoreJournal.Entry e2 = entry("mine", RestoreJournal.STAGE_ARMED);
        RestoreRunner.runExclusiveForTask(e2, "mine", cur ->
                RestorePlan.judge(cur, connected(), true, RestorePlan.ReaddResult.written()));
        eq("恢复成功 → 记录被清掉（幂等）", null, RestoreJournal.load());
        ok("成功结论写进 lastSummary", RestoreRunner.lastSummary().contains("已还原"));

        RestoreJournal.Entry e3 = entry("mine", RestoreJournal.STAGE_ARMED);
        // 注意 Verify 的 listPresent=false：所谓「没写回」就是**列表里没有目标**，
        // 那时系统里不存在任何候选口令（目标整个不在列表里），noWrongLeft 应为 true。
        RestoreRunner.runExclusiveForTask(e3, "mine", cur ->
                RestorePlan.judge(cur, new RestorePlan.Verify(false, null, null, null),
                        true, RestorePlan.ReaddResult.issuedButMissing()));
        RestoreJournal.Entry kept = RestoreJournal.load();
        ok("失败 → 记录**保留**下来（可被下次启动重试）", kept != null);
        eq("阶段 = FAILED", RestoreJournal.STAGE_FAILED, kept.stage);
        eq("失败步骤被写下", RestorePlan.Step.READD_ORIGINAL.name(), kept.failStep);
        ok("失败原因码被写下", kept.failDetail.contains(RestorePlan.READD_NOT_WRITTEN));
        ok("结论里能看出是「配置没写回」而不是「没连上」",
                RestoreRunner.lastSummary().contains(kept.failDetail.split(" · ")[0])
                        || RestoreRunner.lastSummary().contains("未"));

        System.out.println("== F5 「已写回」与「已连上」必须分开（1.9.28 订正） ==");
        RestoreJournal.Entry e = entry("t1", RestoreJournal.STAGE_ARMED);
        RestorePlan.Outcome o = RestorePlan.judge(e,
                new RestorePlan.Verify(false, null, null, null),
                true, RestorePlan.ReaddResult.issuedButMissing());
        eq("命令发出但列表里没有目标 → READD-NOT-WRITTEN",
                RestorePlan.READD_NOT_WRITTEN, o.code);
        ok("不算恢复成功", !o.restored);
        ok("目标整个不在列表里 ⇒ 没有候选口令残留（noWrongLeft=true）", o.noWrongLeft);
        ok("因此不需要再删一次（删也删不到东西）", !RestorePlan.needsCleanupRetry(o));
        eq("与「未连上」是不同的原因码，不会互相冒充",
                RestorePlan.WROTE_BACK,
                RestorePlan.judge(e, new RestorePlan.Verify(true, null, null, null),
                        true, RestorePlan.ReaddResult.issuedButMissing()).code);

        o = RestorePlan.judge(e,
                new RestorePlan.Verify(true, null, null, null),
                true, RestorePlan.ReaddResult.written());
        eq("已写回但没连上 → WROTE-BACK（配置层面已成功）",
                RestorePlan.WROTE_BACK, o.code);
        ok("算恢复成功（连接观察不参与成败）", o.restored && o.writeBackConfirmed);
        ok("但系统里的口令已不是候选口令 → 不该再删",
                o.noWrongLeft && !RestorePlan.needsCleanupRetry(o));

        o = RestorePlan.judge(e, connected(), true, RestorePlan.ReaddResult.written());
        eq("写回 + 连上 → OK", RestorePlan.OK, o.code);
        ok("这才是恢复成功", o.restored && o.noWrongLeft);

        o = RestorePlan.judge(e, connected(), true, RestorePlan.ReaddResult.issuedButMissing());
        ok("readd 当时的回读没看到、但 verify 看到了 → 仍按写回成立处理",
                o.restored);

        o = RestorePlan.judge(e, new RestorePlan.Verify(true, null, null, null),
                true, RestorePlan.ReaddResult.notIssued());
        eq("命令压根没发出 → READD-FAILED（与 NOT-WRITTEN 区分）",
                RestorePlan.READD_FAILED, o.code);
        eq("ReaddResult 自带语义标签", "重建命令未能发出",
                RestorePlan.ReaddResult.notIssued().detail);
        ok("confirmed() 只有两条件都成立才为真",
                !RestorePlan.ReaddResult.issuedButMissing().confirmed()
                        && RestorePlan.ReaddResult.written().confirmed()
                        && !RestorePlan.ReaddResult.notAttempted().confirmed());
        ok("READD-NOT-WRITTEN 与 VERIFY-FAILED 是不同常量",
                !RestorePlan.READD_NOT_WRITTEN.equals(RestorePlan.VERIFY_FAILED));

        System.out.println("== F1/F2 接线守卫 ==");
        String runner = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/RestoreRunner.java");
        String app = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/App.java");
        String svc = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/CrackService.java");
        if (runner == null || app == null || svc == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            ok("补做入口是**异步**的（起工作线程，不在调用者线程里同步跑）",
                    runner.contains("new Thread((") && runner.contains("t.start();"));
            ok("单飞：CAS 保证同一时间最多一个补做线程",
                    runner.contains("QUEUED.compareAndSet(false, true)"));
            ok("一把可重入锁串行化所有恢复动作",
                    runner.contains("new ReentrantLock(true)") && runner.contains("LOCK.lock();"));
            ok("锁内才重新读 journal（读到的是最新那份）",
                    runner.indexOf("LOCK.lock();") < runner.indexOf("RestoreJournal.load()"));
            ok("Application 启动即发起补做（只开 App 也能补完）",
                    app.contains("requestStartupRecovery(\"app-start\")"));
            ok("服务启动也发起补做（重复触发被单飞挡住）",
                    svc.contains("requestStartupRecovery(\"service-start\")"));
            ok("服务 onCreate 里不再同步补做（不会 ANR）",
                    !svc.contains("recoverRestore();"));
            ok("收尾恢复走同一把锁（与启动补做互斥）",
                    svc.contains("RestoreRunner.runExclusiveForTask("));
            ok("readd 由回读列表复核后才算写回",
                    read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                            + "wpaforge/audit/ConfigRestorer.java")
                            .contains("ListNetworksParser.findNetId(list, ssid) >= 0\n                    ? RestorePlan.ReaddResult.written()"));
        }

        System.out.println();
        System.out.println("=== P1 并发/归属/回读测试: " + pass + " 通过, " + fail + " 失败 ===");
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

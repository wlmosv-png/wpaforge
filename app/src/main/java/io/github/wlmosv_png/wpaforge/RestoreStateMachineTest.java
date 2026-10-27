package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ConfigRestorer;
import io.github.wlmosv_png.wpaforge.audit.RestoreAdmin;
import io.github.wlmosv_png.wpaforge.audit.RestoreGuard;
import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;
import io.github.wlmosv_png.wpaforge.audit.RestorePlan;
import io.github.wlmosv_png.wpaforge.audit.RestoreRunner;

import java.util.List;

/**
 * 1.9.28 恢复状态机回归测试（针对一次真实事故）。
 *
 * <h3>事故现场（就是这套用例的基线）</h3>
 * <pre>
 * stage=FAILED
 * ssid=兰州拉面   bssid=90:76:9F:EA:D9:52
 * taskId=t1793101193592_270
 * failStep=VERIFY
 * failDetail=VERIFY-FAILED · 原配置已写回但未观察到连上
 * </pre>
 * 后果：这条记录被当成“未完成的待办”，闸门用 {@code OTHER_PENDING}
 * 挡掉**之后每一个**新任务；每次启动还再补做一次、再失败一次 ——
 * 用户点了几十次开始、杀了几次后台都没用（状态在磁盘上）。
 *
 * <h3>覆盖范围（对应用户列出的十条）</h3>
 * <ol>
 *   <li>写回成功但目标不在覆盖范围内；</li>
 *   <li>写回成功但连接超时 / 拿不到有效 IP；</li>
 *   <li>写回失败 → 必须保留可恢复的失败状态；</li>
 *   <li>历史 FAILED 记录不得造成永久 OTHER_PENDING；</li>
 *   <li>真有未解决风险时，新任务仍受保护；</li>
 *   <li>反复启动不得让同一条失败记录无限重试；</li>
 *   <li>重试到上限后状态与界面行为；</li>
 *   <li>记录损坏 / 字段缺失 / 版本不兼容不得误报成功；</li>
 *   <li>多任务并发、重复触发、进程中断不丢记录；</li>
 *   <li>清理 / 重试与后台状态一致。</li>
 * </ol>
 *
 * <p>第 10 条里「清理」的按钮回调需要 Android Context（写备份文件），
 * JVM 里无法执行 —— 这里覆盖的是它调用的那两层（{@link RestoreJournal#remove}
 * / {@link RestoreJournal#removeBroken}）与界面读到的 {@link RestoreAdmin#rows()}；
 * 按钮的真实点击与备份文件由真机验证覆盖（见交付说明）。
 */
public class RestoreStateMachineTest {

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

    /** 一条“有原配置”的记录（正常路径的输入）。 */
    private static RestoreJournal.Entry entry(String taskId) {
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        e.schema = RestoreJournal.SCHEMA;
        e.ssid = "LAB-TARGET";
        e.bssid = "AA:BB:CC:DD:EE:FF";
        e.taskId = taskId;
        e.security = "wpa2";
        e.hadPsk = true;
        e.sealedPsk = "v1:iv:ct";
        e.snapshotOk = true;
        e.channelOk = true;
        e.armedAt = 1L;
        return e;
    }

    private static void install(String raw) {
        RestoreJournal.Store st = RestoreJournal.memory();
        if (raw != null) st.write(raw);
        RestoreJournal.install(st);
    }

    public static void main(String[] args) {
        System.out.println("== S1 写回成功、但目标不在覆盖范围内（配置恢复 ≠ 连接成功） ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("t1");
            // 此刻连着别的网、目标不在列表里可连（但配置已写回并被回读确认）
            RestorePlan.Outcome o = RestorePlan.judge(e,
                    new RestorePlan.Verify(true, "OTHER-NET", "COMPLETED", "192.168.1.9"),
                    true, RestorePlan.ReaddResult.written());
            eq("码 = WROTE-BACK", RestorePlan.WROTE_BACK, o.code);
            ok("算恢复成功（写回确认）", o.restored && o.writeBackConfirmed);
            ok("连接观察 = 未连上目标（不参与成败）", !o.connObserved);
            ok("没有失败步骤（不是失败）", o.failedAt == null);
            ok("无候选残留", o.noWrongLeft && !o.residualRisk);
            ok("不需要补删（否则会把刚写回的正确配置删掉）",
                    !RestorePlan.needsCleanupRetry(o));
        }

        System.out.println("== S2 写回成功、但连接超时 / 拿不到有效 IP ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("t1");
            RestorePlan.Outcome timedOut = RestorePlan.judge(e,
                    new RestorePlan.Verify(true, "LAB-TARGET", "DISCONNECTED", null),
                    true, RestorePlan.ReaddResult.written());
            eq("超时（没连上）仍算写回成功", RestorePlan.WROTE_BACK, timedOut.code);
            ok("只是观察值：未连上", !timedOut.connObserved);
            ok("没有有效 IP", !timedOut.ipObserved);

            RestorePlan.Outcome noIp = RestorePlan.judge(e,
                    new RestorePlan.Verify(true, "LAB-TARGET", "COMPLETED", "0.0.0.0"),
                    true, RestorePlan.ReaddResult.written());
            eq("COMPLETED 但 IP=0.0.0.0 → 仍算写回成功", RestorePlan.WROTE_BACK, noIp.code);
            ok("没有有效 IP ⇒ 不算“已连上”", !noIp.connObserved && !noIp.ipObserved);

            RestorePlan.Outcome unknown = RestorePlan.judge(e,
                    new RestorePlan.Verify(true, null, null, null),
                    true, RestorePlan.ReaddResult.written());
            eq("状态读不到（只有列表可见）→ 也算写回成功", RestorePlan.WROTE_BACK, unknown.code);
        }

        System.out.println("== S3 写回失败 → 必须留下可恢复的失败状态 ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("t1");
            RestorePlan.Outcome notWritten = RestorePlan.judge(e,
                    new RestorePlan.Verify(false, null, null, null),
                    true, RestorePlan.ReaddResult.issuedButMissing());
            eq("命令发出但看不到目标 → READD-NOT-WRITTEN",
                    RestorePlan.READD_NOT_WRITTEN, notWritten.code);
            ok("不算成功", !notWritten.restored && !notWritten.writeBackConfirmed);
            ok("可重试（通道抖动值得再试一次）", notWritten.retryable);
            ok("目标整个不在列表 ⇒ 无候选残留", notWritten.noWrongLeft && !notWritten.residualRisk);

            RestoreRunner.applyStage(e, notWritten);
            eq("写回失败 → stage=FAILED（留痕）", RestoreJournal.STAGE_FAILED, e.stage);
            eq("尝试次数 +1", 1, e.attempts);
            ok("失败原因与步骤写进记录",
                    e.failDetail.contains(RestorePlan.READD_NOT_WRITTEN)
                            && RestorePlan.Step.READD_ORIGINAL.name().equals(e.failStep));
            ok("记录确实落盘", RestoreJournal.loadFor("t1") != null);

            RestoreJournal.Entry e2 = entry("t2");
            RestorePlan.Outcome residual = RestorePlan.judge(e2,
                    new RestorePlan.Verify(true, null, null, null),
                    true, RestorePlan.ReaddResult.notIssued());
            eq("命令没发出 + 目标仍在列表 → READD-FAILED",
                    RestorePlan.READD_FAILED, residual.code);
            ok("命令没发出且目标还在列表 ⇒ 有残留风险", residual.residualRisk);
            ok("要求补一次清理", RestorePlan.needsCleanupRetry(residual));

            RestoreJournal.Entry e3 = entry("t3");
            RestorePlan.Outcome removeFailed = RestorePlan.judge(e3,
                    new RestorePlan.Verify(true, null, null, null),
                    false, RestorePlan.ReaddResult.notIssued());
            eq("连残留都删不掉 → REMOVE-FAILED", RestorePlan.REMOVE_FAILED, removeFailed.code);
            ok("最坏情况：既没还原也可能留着候选口令",
                    removeFailed.residualRisk && !removeFailed.noWrongLeft);
        }

        System.out.println("== S4 历史 FAILED 记录（事故基线）不得永久阻塞新任务 ==");
        {
            // 事故里那条记录的原样（1.9.27 的旧格式：没有 version= 头、没有 schema=）
            install("stage=FAILED\n"
                    + "ssid=兰州拉面\n"
                    + "bssid=90:76:9F:EA:D9:52\n"
                    + "security=wpa2\n"
                    + "originalNetId=-1\n"
                    + "hadPsk=1\n"
                    + "sealedPsk=v1:abcd:efgh\n"
                    + "snapshotOk=1\n"
                    + "runId=t1793101193592_270\n"
                    + "taskId=t1793101193592_270\n"
                    + "channelOk=1\n"
                    + "armedAt=1793101271161\n"
                    + "finishedAt=1793101488137\n"
                    + "failStep=VERIFY\n"
                    + "failDetail=VERIFY-FAILED · 原配置已写回但未观察到连上\n");

            List<RestoreJournal.Entry> all = RestoreJournal.loadAll();
            eq("旧记录能被读出来（不丢）", 1, all.size());
            RestoreJournal.Entry e = all.get(0);
            eq("迁移到新 schema", RestoreJournal.SCHEMA, e.schema);
            ok("VERIFY 类失败 ⇒ 判定为“写回已确认”（这就是事实本身）",
                    e.writeBackConfirmed);
            ok("风险已消除（没有候选残留）", !e.residualRisk);
            ok("只再自动尝试最后 1 次（旧记录不再无限重试）",
                    e.remainingAttempts() == 1);

            RestoreRunner.Block blk = RestoreRunner.checkNewTask(
                    "t-new-task", "牛梦瑞诊所2");
            ok("**别人的目标开新任务不再被挡**（事故的直接症状）", !blk.blocked);
            RestoreRunner.Block sameTarget = RestoreRunner.checkNewTask(
                    "t-new-task", "兰州拉面");
            ok("同目标也不再被挡（无残留风险）", !sameTarget.blocked);
            ok("盘上仍能看到这条历史记录（不许静默丢弃）",
                    RestoreJournal.loadFor("t1793101193592_270") != null);
        }

        System.out.println("== S5 真有未解决风险时，新任务仍受保护 ==");
        {
            // ① 旧记录失败在“删残留”那一步 → 真有污染风险
            install("stage=FAILED\n"
                    + "ssid=RISKY-AP\n"
                    + "taskId=t-old\n"
                    + "hadPsk=1\n"
                    + "sealedPsk=v1:a:b\n"
                    + "failStep=REMOVE_WRONG_CONFIG\n"
                    + "failDetail=REMOVE-FAILED · 无法清除候选配置\n");
            RestoreRunner.Block same = RestoreRunner.checkNewTask("t-new", "RISKY-AP");
            ok("同目标 + 有残留风险 → 拦住", same.blocked);
            eq("原因码 = OTHER_PENDING", RestoreGuard.Reason.OTHER_PENDING, same.reason);
            RestoreRunner.Block other = RestoreRunner.checkNewTask("t-new", "OTHER-AP");
            ok("不同目标不受影响（记录分槽，不会互相覆盖）", !other.blocked);
            ok("但仍然提示用户盘上有待办", !other.note.isEmpty());

            // ② 记录损坏 / 版本不认识 → 未知，fail closed
            install("stage=ARMED\n这行根本没有等号\n");
            RestoreRunner.Block broken = RestoreRunner.checkNewTask("t-new", "ANY");
            ok("记录解析失败 → 拦住（不许当成“没有待办”）", broken.blocked);
            eq("原因码 = JOURNAL_UNREADABLE",
                    RestoreGuard.Reason.JOURNAL_UNREADABLE, broken.reason);

            // ③ 闸门事实层面：不可解析 / 堆积 也各有明确理由
            RestoreGuard.Decision d1 = RestoreGuard.decide(new RestoreGuard.Facts(
                    false, true, true, false, false, false, true, true, true, 0));
            eq("journalUnreadable → JOURNAL_UNREADABLE",
                    RestoreGuard.Reason.JOURNAL_UNREADABLE, d1.reason);
            RestoreGuard.Decision d2 = RestoreGuard.decide(new RestoreGuard.Facts(
                    false, true, true, false, false, false, true, true,
                    false, RestoreJournal.MAX_PENDING));
            eq("堆积到上限 → TOO_MANY_PENDING",
                    RestoreGuard.Reason.TOO_MANY_PENDING, d2.reason);
            ok("这两种都需要人工处理", d2.needsHuman());
        }

        System.out.println("== S6 反复启动不得把同一条失败记录刷成死循环 ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("t-loop");
            e.stage = RestoreJournal.STAGE_ARMED;
            RestoreJournal.save(e);

            RestorePlan.Outcome failOut = RestorePlan.judge(e,
                    new RestorePlan.Verify(false, null, null, null),
                    true, RestorePlan.ReaddResult.issuedButMissing());
            RestoreRunner.applyStage(e, failOut);
            eq("第一次失败 → FAILED", RestoreJournal.STAGE_FAILED, e.stage);
            eq("尝试次数 1", 1, e.attempts);
            ok("仍在冷却期内（距上次尝试 1 秒）→ 自动补做会跳过",
                    !e.cooldownPassed(e.lastAttemptAt + 1000L));
            ok("冷却期过后才允许再试一次",
                    e.cooldownPassed(e.lastAttemptAt + RestoreJournal.RETRY_COOLDOWN_MS + 1L));
            ok("还没到上限 → 仍算待办", e.needsRetry() && RestoreRunner.shouldRecover(e));
        }

        System.out.println("== S7 重试到上限 → 归档；界面与后台状态一致 ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("t-max");
            e.stage = RestoreJournal.STAGE_ARMED;
            RestoreJournal.save(e);
            RestorePlan.Outcome failOut = RestorePlan.judge(e,
                    new RestorePlan.Verify(false, null, null, null),
                    true, RestorePlan.ReaddResult.issuedButMissing());
            for (int i = 0; i < RestoreJournal.MAX_ATTEMPTS; i++) {
                RestoreRunner.applyStage(e, failOut);
            }
            eq("到上限 → ARCHIVED", RestoreJournal.STAGE_ARCHIVED, e.stage);
            eq("尝试次数 = 上限", RestoreJournal.MAX_ATTEMPTS, e.attempts);
            ok("归档后不再自动重试", !e.needsRetry() && !RestoreRunner.shouldRecover(e));
            ok("归档后不再阻塞", !e.blocking());
            ok("归档原因写进记录（可追踪，不静默消失）",
                    e.failDetail.contains("已归档"));

            List<RestoreAdmin.Row> rows = RestoreAdmin.rows();
            eq("界面能读到这条记录", 1, rows.size());
            RestoreAdmin.Row r = rows.get(0);
            ok("行摘要显示「已归档」", r.line().contains("已归档"));
            eq("剩余重试次数 = 0", 0, r.remaining);
            ok("界面汇总说明它不影响新任务", RestoreAdmin.summary().contains("归档"));
            ok("新任务可以照常开（这就是本轮的最终标准：不锁死）",
                    !RestoreRunner.checkNewTask("t-after", "ANY-AP").blocked);
        }

        System.out.println("== S8 损坏 / 字段缺失 / 版本不兼容：不得误报成功 ==");
        {
            install("stage=ARMED\nssid=X\n没有等号的一行\n");
            List<RestoreJournal.Entry> all = RestoreJournal.loadAll();
            eq("读出一条（坏的也要显式化）", 1, all.size());
            ok("标记为 broken", all.get(0).broken());
            ok("broken 不算终态（不冒充“已恢复”）", !all.get(0).settled());
            ok("broken 按未知拦截（fail closed）", all.get(0).blocking());
            ok("pendingUnknown() 为真", RestoreJournal.pendingUnknown());

            install("version=3\nstage=RESTORED\nssid=X\n");
            List<RestoreJournal.Entry> v3 = RestoreJournal.loadAll();
            ok("更高版本的记录不被当成正常记录", v3.size() == 1 && v3.get(0).broken());
            ok("绝不冒充恢复成功", !v3.get(0).settled() && !v3.get(0).writeBackConfirmed);
            ok("闸门据此拦住", RestoreRunner.checkNewTask("t", "X").blocked);

            install("");
            eq("空内容 → 没有记录", 0, RestoreJournal.loadAll().size());
            ok("空内容不算未知", !RestoreJournal.pendingUnknown());
            ok("空内容不拦新任务", !RestoreRunner.checkNewTask("t", "X").blocked);
        }

        System.out.println("== S9 多槽：并发 / 重复触发 / 中断都不许丢记录 ==");
        {
            install(null);
            RestoreJournal.Entry a = entry("ta");
            RestoreJournal.Entry b = entry("tb");
            b.ssid = "SECOND-AP";
            RestoreJournal.save(a);
            RestoreJournal.save(b);
            eq("两条并存", 2, RestoreJournal.loadAll().size());

            RestoreJournal.save(a);
            eq("重复 save 同一任务不产生副本", 2, RestoreJournal.loadAll().size());

            RestoreJournal.Entry a2 = entry("ta");
            a2.stage = RestoreJournal.STAGE_FAILED;
            RestoreJournal.save(a2);
            eq("更新自己那一槽后仍是两条", 2, RestoreJournal.loadAll().size());
            eq("更新确实生效", RestoreJournal.STAGE_FAILED,
                    RestoreJournal.loadFor("ta").stage);
            ok("别人的那条没被覆盖", RestoreJournal.loadFor("tb") != null
                    && "SECOND-AP".equals(RestoreJournal.loadFor("tb").ssid));

            ok("删一条后另一条仍在",
                    RestoreJournal.remove("ta") && RestoreJournal.loadAll().size() == 1
                            && RestoreJournal.loadFor("tb") != null);

            // 并发写：8 个线程各写自己的任务槽，一条都不许丢
            install(null);
            Thread[] ts = new Thread[8];
            for (int i = 0; i < ts.length; i++) {
                final int n = i;
                ts[i] = new Thread(() -> {
                    RestoreJournal.Entry e = entry("t-conc-" + n);
                    e.ssid = "AP-" + n;
                    RestoreJournal.save(e);
                }, "journal-conc-" + i);
            }
            for (Thread t : ts) t.start();
            for (Thread t : ts) {
                try { t.join(10_000); } catch (InterruptedException ignored) {}
            }
            eq("并发写入 8 条 → 一条都没丢", 8, RestoreJournal.loadAll().size());
        }

        System.out.println("== S10 清理 / 重试与后台状态保持一致 ==");
        {
            install("stage=ARMED\n这行坏了\n");
            RestoreJournal.Entry good = entry("t-good");
            good.stage = RestoreJournal.STAGE_ARMED;
            RestoreJournal.save(good);
            eq("界面看到 2 条（1 条正常 + 1 条损坏）", 2, RestoreAdmin.rows().size());

            ok("清理损坏记录走得通（按 key 删不掉的那些）",
                    RestoreJournal.removeBroken());
            eq("坏记录清掉后只剩 1 条", 1, RestoreAdmin.rows().size());
            ok("pendingUnknown 也随之消失", !RestoreJournal.pendingUnknown());

            ok("清理正常记录", RestoreJournal.remove("t-good"));
            eq("清空后界面是空态", 0, RestoreAdmin.rows().size());
            ok("空态文案说明没有待办", RestoreAdmin.summary().contains("没有待处理"));
            ok("清空后新任务立即可以跑",
                    !RestoreRunner.checkNewTask("t-next", "ANY-AP").blocked);

            // 重试路径：同一条记录重试失败 → 计数与阶段同步推进（UI 读同一份数据）
            install(null);
            RestoreJournal.Entry r = entry("t-retry");
            r.stage = RestoreJournal.STAGE_ARMED;
            RestoreJournal.save(r);
            RestorePlan.Outcome o2 = RestorePlan.judge(r,
                    new RestorePlan.Verify(false, null, null, null),
                    true, RestorePlan.ReaddResult.issuedButMissing());
            RestoreRunner.applyStage(r, o2);
            RestoreAdmin.Row row = RestoreAdmin.rows().get(0);
            eq("界面上的尝试次数与记录一致", r.attempts, row.entry.attempts);
            eq("界面上的剩余次数与记录一致", r.remainingAttempts(), row.remaining);
            eq("界面上的阶段与记录一致", r.stage, row.entry.stage);
        }

        System.out.println();
        System.out.println("=== 1.9.28 恢复状态机测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

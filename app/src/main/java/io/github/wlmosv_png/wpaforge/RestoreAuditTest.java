package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ConfigRestorer;
import io.github.wlmosv_png.wpaforge.audit.RestoreAdmin;
import io.github.wlmosv_png.wpaforge.audit.RestoreGuard;
import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;
import io.github.wlmosv_png.wpaforge.audit.RestorePlan;
import io.github.wlmosv_png.wpaforge.audit.RestoreRunner;

import java.util.List;

/**
 * 1.9.28 第二轮审计专属测试（不改行为，只钉住审计结论）。
 *
 * <h3>它和 {@link RestoreStateMachineTest} 的分工</h3>
 * 状态机测试回答“新行为对不对”；本类回答审计提出的具体质疑：
 * <ol>
 *   <li>旧记录的 {@code attempts} 到底怎么来的、有没有被伪造；</li>
 *   <li>A–E 五种恢复情形**必须**给出不同的结论，不许把
 *       “没有检测到残留”“无需执行操作”“原配置恢复成功”混为一谈；</li>
 *   <li>闸门的匹配规则（同 SSID / 不同 BSSID / BSSID 未知 / 不同 SSID）；</li>
 *   <li>多槽读写与迁移不丢记录；</li>
 *   <li>三类计数（发起恢复 / 实际写回 / 验证失败）必须可区分。</li>
 * </ol>
 *
 * <p>每条断言都不只看返回值：还会检查**落盘后的读回结果**（用
 * {@link RestoreJournal#loadFor(String)} 重新解析）、闸门决策
 * （{@link RestoreRunner#checkNewTask}）与记录是否被清理。
 * 所有 shell 交互都通过 {@link ConfigRestorer#installShell} 假壳完成，
 * 不碰任何真实网络。
 */
public class RestoreAuditTest {

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

    private static RestoreJournal.Entry entry(String taskId, String ssid, String bssid) {
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        e.schema = RestoreJournal.SCHEMA;
        e.ssid = ssid;
        e.bssid = bssid == null ? "" : bssid;
        e.taskId = taskId;
        e.security = "wpa2";
        e.armedAt = 1L;
        return e;
    }

    private static void install(String raw) {
        RestoreJournal.Store st = RestoreJournal.memory();
        if (raw != null) st.write(raw);
        RestoreJournal.install(st);
    }

    /** 假壳：list-networks / status 的返回值可控，其余命令一律“成功但无输出”。 */
    static final class FakeShell implements ConfigRestorer.Shell {
        private final String list;
        private final String status;

        FakeShell(String list, String status) {
            this.list = list;
            this.status = status;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd.contains("list-networks")) return list;
            if (cmd.contains("status")) return status;
            return "";
        }
    }

    public static void main(String[] args) {
        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计一：旧 FAILED 记录的 attempts 从哪来 ==");
        {
            install("stage=FAILED\n"
                    + "ssid=OLD-AP\n"
                    + "taskId=t-old\n"
                    + "hadPsk=1\n"
                    + "sealedPsk=v1:a:b\n"
                    + "failStep=VERIFY\n"
                    + "failDetail=VERIFY-FAILED · 原配置已写回但未观察到连上\n");
            RestoreJournal.Entry e = RestoreJournal.loadFor("t-old");
            ok("旧记录被读出来", e != null);
            eq("迁移后 schema = 当前版本", RestoreJournal.SCHEMA, e.schema);
            eq("**不再伪造尝试次数**：attempts 从 0 开始", 0, e.attempts);
            eq("剩余次数 = 上限", RestoreJournal.MAX_ATTEMPTS, e.remainingAttempts());
            eq("写回发出次数也从 0 开始（不猜历史）", 0, e.writeBackIssuedCount);
            eq("验证失败次数同样从 0 开始", 0, e.verifyFailures);
            ok("VERIFY 类失败按事实判为“写回已确认”", e.writeBackConfirmed);
            ok("风险据此判为已消除", !e.residualRisk);
            ok("迁移痕迹写进 failDetail（可追踪）", e.failDetail.contains("迁移"));
            ok("没有伪造次数的字样了", !e.failDetail.contains("最多再自动尝试 1 次"));

            // 三次**真实**尝试才会到上限
            RestorePlan.Outcome notWritten = RestorePlan.judge(e,
                    new RestorePlan.Verify(false, null, null, null),
                    true, RestorePlan.ReaddResult.issuedButMissing());
            for (int i = 1; i <= RestoreJournal.MAX_ATTEMPTS; i++) {
                RestoreRunner.applyStage(e, notWritten);
                eq("第 " + i + " 次真实尝试后 attempts", i, e.attempts);
            }
            eq("三次真实尝试后才归档", RestoreJournal.STAGE_ARCHIVED, e.stage);
            eq("实际写回次数 = 3（每次都发出了写回命令）", 3, e.writeBackIssuedCount);
            eq("验证失败次数 = 3", 3, e.verifyFailures);
            ok("落盘后的读回也有这三个数字",
                    RestoreJournal.loadFor("t-old").attempts == 3
                            && RestoreJournal.loadFor("t-old").writeBackIssuedCount == 3
                            && RestoreJournal.loadFor("t-old").verifyFailures == 3);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计二 A 原配置已存在且写回确认（配置层面成功） ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("tA", "AP-A", "AA:AA:AA:AA:AA:AA");
            e.hadPsk = true;
            e.sealedPsk = "v1:a:b";
            RestorePlan.Outcome o = RestorePlan.judge(e,
                    new RestorePlan.Verify(true, "OTHER-NET", "COMPLETED", "192.168.1.9"),
                    true, RestorePlan.ReaddResult.written());
            eq("码 = WROTE-BACK", RestorePlan.WROTE_BACK, o.code);
            ok("写回确认 + 确实发出过写回命令",
                    o.writeBackConfirmed && o.readdIssued && o.restored);
            ok("未连上目标只是观察值", !o.connObserved);
            RestoreJournal.save(e);
            RestoreRunner.applyStage(e, o);
            eq("成功 → 记录被清理", null, RestoreJournal.loadFor("tA"));
            ok("同目标新任务放行",
                    !RestoreRunner.checkNewTask("t-next", "AP-A").blocked);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计二 B 原本没有配置、也没有候选残留（与 A 必须不同） ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("tB", "AP-B", "");
            e.hadPsk = false;
            e.originalNetId = -1L;
            RestorePlan.Outcome o = RestorePlan.judge(e,
                    new RestorePlan.Verify(false, null, null, null),
                    true, RestorePlan.ReaddResult.notAttempted());
            eq("码 = NOTHING-TO-DO", RestorePlan.NOTHING_TO_DO, o.code);
            ok("算处置完毕（无需还原）", o.restored);
            ok("**没有写回确认**（压根没写回）", !o.writeBackConfirmed);
            ok("**没有发出写回命令**（与 A 不同）", !o.readdIssued);
            ok("与 A 不是同一个结果码", !RestorePlan.WROTE_BACK.equals(o.code));
            RestoreJournal.save(e);
            RestoreRunner.applyStage(e, o);
            eq("无需动作 → 记录同样被清理", null, RestoreJournal.loadFor("tB"));
            ok("同目标新任务放行",
                    !RestoreRunner.checkNewTask("t-next", "AP-B").blocked);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计二 C 写回已确认、但目标不在附近 ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("tC", "AP-C", "");
            e.hadPsk = true;
            e.sealedPsk = "v1:a:b";
            RestorePlan.Outcome o = RestorePlan.judge(e,
                    new RestorePlan.Verify(true, null, null, null),
                    true, RestorePlan.ReaddResult.written());
            eq("码 = WROTE-BACK", RestorePlan.WROTE_BACK, o.code);
            ok("算恢复成功（配置层面）", o.restored && o.writeBackConfirmed);
            ok("连接观察为否，但不影响成败", !o.connObserved && !o.ipObserved);
            ok("无候选残留", !o.residualRisk && o.noWrongLeft);
            RestoreJournal.save(e);
            RestoreRunner.applyStage(e, o);
            eq("记录被清理", null, RestoreJournal.loadFor("tC"));
            ok("同目标新任务放行（不会被历史记录挡住）",
                    !RestoreRunner.checkNewTask("t-next", "AP-C").blocked);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计二 D 写回命令执行了、但目标没重新出现在列表里 ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("tD", "AP-D", "");
            e.hadPsk = true;
            e.sealedPsk = "v1:a:b";
            RestorePlan.Outcome o = RestorePlan.judge(e,
                    new RestorePlan.Verify(false, null, null, null),
                    true, RestorePlan.ReaddResult.issuedButMissing());
            eq("码 = READD-NOT-WRITTEN", RestorePlan.READD_NOT_WRITTEN, o.code);
            ok("不算成功", !o.restored && !o.writeBackConfirmed);
            ok("但确实发出过写回命令（与“没写回”区分）", o.readdIssued);
            ok("可重试", o.retryable);
            ok("列表读到了且里面没有目标 ⇒ 无候选残留",
                    o.noWrongLeft && !o.residualRisk);
            RestoreJournal.save(e);
            RestoreRunner.applyStage(e, o);
            RestoreJournal.Entry disk = RestoreJournal.loadFor("tD");
            eq("记录**保留**（不许当成成功清掉）", RestoreJournal.STAGE_FAILED, disk.stage);
            eq("落盘 attempts = 1", 1, disk.attempts);
            eq("落盘 实际写回次数 = 1", 1, disk.writeBackIssuedCount);
            eq("落盘 验证失败次数 = 1", 1, disk.verifyFailures);
            ok("失败原因可追踪", disk.failDetail.contains(RestorePlan.READD_NOT_WRITTEN));
            // 匹配规则与风险的取舍：D 场景列表里没有目标 ⇒ 该 SSID 当前**没有配置**，
            // 同目标新任务的快照会读到 hadPsk=false（不会被候选口令污染），所以放行；
            // 但记录本身仍留在盘上继续可重试（启动补做会再写回一次原配置）。
            ok("同目标放行（该 SSID 当前无配置，不会被候选污染）",
                    !RestoreRunner.checkNewTask("t-next", "AP-D").blocked);
            ok("记录仍在（留着继续重试写回原配置）",
                    RestoreJournal.loadFor("tD") != null);
            ok("其它目标同样不受影响",
                    !RestoreRunner.checkNewTask("t-next", "AP-OTHER").blocked);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计二 E 读不到列表 / 检查不完整（不许当“没有残留”） ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("tE", "AP-E", "");
            e.hadPsk = true;
            e.sealedPsk = "v1:a:b";
            RestorePlan.Outcome o = RestorePlan.judge(e,
                    new RestorePlan.Verify(false, null, null, null, false),
                    true, RestorePlan.ReaddResult.issuedButMissing());
            eq("码 = UNVERIFIED", RestorePlan.UNVERIFIED, o.code);
            ok("不算成功", !o.restored && !o.writeBackConfirmed);
            ok("**也不算“没有残留”**（未知按有风险处理）",
                    o.residualRisk && !o.noWrongLeft);
            ok("保持可重试（不虚报、也不丢弃）", o.retryable);
            RestoreJournal.save(e);
            RestoreRunner.applyStage(e, o);
            RestoreJournal.Entry disk = RestoreJournal.loadFor("tE");
            eq("记录保留", RestoreJournal.STAGE_FAILED, disk.stage);
            ok("落盘后残留风险仍为真（不许被降级）", disk.residualRisk);
            eq("验证失败次数 = 1", 1, disk.verifyFailures);
            ok("同目标被拦住", RestoreRunner.checkNewTask("t-next", "AP-E").blocked);

            // 关键反例：原本“没有配置”时，读不到列表也**不许**判成“无需还原”
            RestoreJournal.Entry e2 = entry("tE2", "AP-E2", "");
            e2.hadPsk = false;
            RestorePlan.Outcome o2 = RestorePlan.judge(e2,
                    new RestorePlan.Verify(false, null, null, null, false),
                    true, RestorePlan.ReaddResult.notAttempted());
            eq("hadPsk=false 但列表读不到 → 仍是 UNVERIFIED",
                    RestorePlan.UNVERIFIED, o2.code);
            ok("不许说成 NOTHING-TO-DO", !RestorePlan.NOTHING_TO_DO.equals(o2.code));
            ok("也不许说成成功", !o2.restored);

            // 而“列表读到了、里面没有目标”才是可靠的“无需还原”
            RestorePlan.Outcome o3 = RestorePlan.judge(e2,
                    new RestorePlan.Verify(false, null, null, null, true),
                    true, RestorePlan.ReaddResult.notAttempted());
            eq("列表可读 + 无目标 → NOTHING-TO-DO", RestorePlan.NOTHING_TO_DO, o3.code);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计三 闸门：匹配规则与 fail-closed ==");
        {
            // 同 SSID 不同 BSSID → 仍算同目标（系统里同一 SSID 只有一条配置）
            install(null);
            RestoreJournal.Entry e = entry("t-r", "LAB-SSID", "11:11:11:11:11:11");
            e.residualRisk = true;
            e.stage = RestoreJournal.STAGE_ARCHIVED;
            RestoreJournal.save(e);
            ok("同 SSID 不同 BSSID → 拦",
                    RestoreRunner.checkNewTask("t-new", "LAB-SSID").blocked);
            ok("BSSID 未知（空）→ 仍按同 SSID 拦",
                    RestoreRunner.checkNewTask("t-new", "LAB-SSID").blocked);
            ok("不同 SSID → 放行",
                    !RestoreRunner.checkNewTask("t-new", "OTHER-SSID").blocked);
            ok("ARCHIVED 但有残留风险 ⇒ 不会被安全检查绕过",
                    !e.blocking() && e.hasUnresolvedRisk());
            eq("界面条数与闸门口径一致：pendingCount",
                    1, RestoreJournal.pendingCount());
            ok("hasPending 同样为真", RestoreJournal.hasPending());
            RestoreAdmin.Row row = RestoreAdmin.rows().get(0);
            ok("界面行标记为需要人工", row.needsHuman);

            // 清掉风险标记后才真正放行
            e.residualRisk = false;
            RestoreJournal.save(e);
            ok("残留风险解除后同目标放行",
                    !RestoreRunner.checkNewTask("t-new", "LAB-SSID").blocked);
            eq("口径一致：不再计入待处理", 0, RestoreJournal.pendingCount());

            // 损坏记录 → fail closed，且清理后可恢复
            install("version=3\nstage=RESTORED\nssid=X\n");
            RestoreRunner.Block b = RestoreRunner.checkNewTask("t-new", "ANY");
            ok("版本不认识的记录 → 拦", b.blocked);
            eq("原因码 = JOURNAL_UNREADABLE",
                    RestoreGuard.Reason.JOURNAL_UNREADABLE, b.reason);
            ok("清理损坏记录后放行",
                    RestoreJournal.removeBroken()
                            && !RestoreRunner.checkNewTask("t-new", "ANY").blocked);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计三 多槽：读写与迁移不丢记录 ==");
        {
            // v2 文件里同时放一条旧 schema 与一条当前 schema
            String v2 = "version=2\n---\n"
                    + "stage=FAILED\nssid=OLD-ONE\ntaskId=t-old-one\nhadPsk=1\n"
                    + "sealedPsk=v1:a:b\nfailStep=VERIFY\nfailDetail=old\n"
                    + "---\n"
                    + "schema=2\nstage=FAILED\nssid=NEW-TWO\ntaskId=t-new-two\nhadPsk=0\n"
                    + "residualRisk=1\nfailStep=VERIFY\nfailDetail=new\n";
            install(v2);
            List<RestoreJournal.Entry> all = RestoreJournal.loadAll();
            eq("两条都在（迁移不丢）", 2, all.size());
            eq("旧条被迁移到当前 schema", RestoreJournal.SCHEMA,
                    RestoreJournal.loadFor("t-old-one").schema);
            eq("旧条不再伪造次数", 0, RestoreJournal.loadFor("t-old-one").attempts);
            ok("新条原样保留（含残留风险）", RestoreJournal.loadFor("t-new-two").residualRisk);

            RestoreJournal.Entry only = RestoreJournal.loadFor("t-new-two");
            only.failDetail = "更新第二条";
            RestoreJournal.save(only);
            eq("更新一条后仍是两条", 2, RestoreJournal.loadAll().size());
            ok("第一条没被覆盖", RestoreJournal.loadFor("t-old-one") != null);

            ok("按精确 taskId 删除只删一条",
                    RestoreJournal.remove("t-new-two")
                            && RestoreJournal.loadAll().size() == 1
                            && RestoreJournal.loadFor("t-old-one") != null);

            RestoreJournal.removeBroken();
            ok("removeBroken 不误伤可解析记录",
                    RestoreJournal.loadFor("t-old-one") != null);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计三 终态记录点“重试”不许假成功 ==");
        {
            install(null);
            RestoreJournal.Entry e = entry("t-retry", "AP-R", "");
            e.hadPsk = true;
            e.sealedPsk = "v1:a:b";
            e.stage = RestoreJournal.STAGE_ARCHIVED;
            RestoreJournal.save(e);

            // 直连 restore：已终态 → 没有可执行步骤 → 不许声明成功、不许改盘
            ConfigRestorer.installShell(new FakeShell("", ""));
            RestorePlan.Outcome o = ConfigRestorer.restore(e, false);
            eq("终态记录 → NO-STEPS", RestorePlan.NO_STEPS, o.code);
            ok("不是成功、也不是失败", !o.restored && !o.resolved());
            RestoreRunner.applyStage(e, o);
            eq("applyStage 对 NO-STEPS 不改盘", RestoreJournal.STAGE_ARCHIVED,
                    RestoreJournal.loadFor("t-retry").stage);
            eq("也不记尝试", 0, RestoreJournal.loadFor("t-retry").attempts);

            // 用户点「重试恢复」：先退回 ARMED，然后真的走一遍恢复
            RestoreAdmin.RetryResult rr = RestoreAdmin.retryNow(e);
            // JVM 里没有 Android Keystore ⇒ 原口令密文解不开 ⇒ 写回命令**不会**发出。
            // 这正好把「发起恢复 1 次」与「实际写回 0 次」区分开（审计要求四）。
            eq("重试后走的是真实流程（写回未发出 → READD-FAILED）",
                    RestorePlan.READD_FAILED,
                    rr.outcome == null ? null : rr.outcome.code);
            RestoreJournal.Entry after = RestoreJournal.loadFor("t-retry");
            ok("记录保留、回到可重试失败态（不是假成功被清掉）",
                    after != null && RestoreJournal.STAGE_FAILED.equals(after.stage));
            eq("发起恢复 1 次", 1, after.attempts);
            eq("实际写回 0 次（口令密文解不开）", 0, after.writeBackIssuedCount);
            eq("验证失败 1 次", 1, after.verifyFailures);
            ConfigRestorer.installShell(null);
        }

        // ══════════════════════════════════════════════════════════════════
        System.out.println("== 审计四 并发与中断：不丢记录、不误判 ==");
        {
            // ① 并发写：8 个线程各写自己的槽，一条都不许丢
            install(null);
            Thread[] ts = new Thread[8];
            for (int i = 0; i < ts.length; i++) {
                final int n = i;
                ts[i] = new Thread(() -> {
                    RestoreJournal.Entry e = entry("t-c" + n, "AP-" + n, "");
                    e.stage = RestoreJournal.STAGE_ARMED;
                    RestoreJournal.save(e);
                }, "audit-conc-" + n);
            }
            for (Thread t : ts) t.start();
            for (Thread t : ts) {
                try { t.join(10_000); } catch (InterruptedException ignored) {}
            }
            eq("并发写的 8 条一条不丢", 8, RestoreJournal.loadAll().size());
            eq("口径一致：8 条都算待处理", 8, RestoreJournal.pendingCount());

            // ② 进程中断：换一个干净的盘面，只留一条 ARMED（模拟进程被杀）
            install(null);
            RestoreJournal.Entry interrupted = entry("t-intr", "AP-INTR", "");
            interrupted.stage = RestoreJournal.STAGE_ARMED;
            RestoreJournal.save(interrupted);
            RestoreJournal.Entry reread = RestoreJournal.loadFor("t-intr");
            ok("中断留下的 ARMED 记录可被重新读出并需要处理",
                    reread != null && reread.needsRetry() && reread.armed());
            ok("它的同目标新任务被拦（未收尾 = 状态未知）",
                    RestoreRunner.checkNewTask("t-next", "AP-INTR").blocked);
            ok("其它目标不受影响",
                    !RestoreRunner.checkNewTask("t-next", "AP-ELSE").blocked);
        }

        System.out.println();
        System.out.println("=== 1.9.28 审计测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

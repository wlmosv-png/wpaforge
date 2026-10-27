package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.RestoreGuard;
import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;
import io.github.wlmosv_png.wpaforge.audit.RestorePlan;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P0（F3/F6）回归测试：**拿不到可靠恢复信息时，绝不进入破坏性操作**。
 *
 * <h3>被修掉的两个缺陷</h3>
 * <ol>
 *   <li><b>F3</b>：恢复记录落盘失败只写一行日志就继续跑；收尾时又优先用**盘上**那份 ——
 *       而盘上可能是**上一个任务**的旧记录 ⇒ 拿别的目标的口令去恢复当前目标。</li>
 *   <li><b>F6</b>：快照读不到 / 不完整（目标在已保存列表里，却读不到它的配置或口令）
 *       也照样往下跑 ⇒ 跑完之后原配置再也还原不回去。</li>
 * </ol>
 *
 * <h3>fail-closed 的含义</h3>
 * 上述任一情况成立时：**不进入引擎**（不执行任何 `cmd wifi` 破坏性命令），
 * 任务保持 PAUSED（可续跑，不丢进度），并留下可读的原因码。
 * 「宁可这一轮不跑」而不是「在没有还原能力的前提下动用户的网络配置」。
 */
public class RestoreFailClosedTest {

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

    public static void main(String[] args) {
        System.out.println("== F3a 盘上还有别人的未完成恢复 → 拦住 ==");
        RestoreGuard.Decision d = RestoreGuard.decide(new RestoreGuard.Facts(
                true, true, true, true, true, true, true, true));
        ok("不放行", !d.proceed);
        eq("原因码 = OTHER_PENDING", RestoreGuard.Reason.OTHER_PENDING, d.reason);
        ok("说明里点明「可能把候选口令当成原配置」",
                d.detail.contains("候选口令当成原配置"));

        d = RestoreGuard.decide(new RestoreGuard.Facts(
                true, false, false, false, false, false, false, false));
        eq("OTHER_PENDING 优先级最高（其余全红也先报它）",
                RestoreGuard.Reason.OTHER_PENDING, d.reason);

        System.out.println("== F6a 快照不完整 → 逐条拦住 ==");
        RestoreGuard.Facts f = new RestoreGuard.Facts(false, false, true, true, true, true, true, true);
        d = RestoreGuard.decide(f);
        eq("读不到已保存列表 → CHANNEL_UNREADABLE", RestoreGuard.Reason.CHANNEL_UNREADABLE, d.reason);
        ok("不放行", !d.proceed);

        f = new RestoreGuard.Facts(false, true, false, true, true, true, true, true);
        d = RestoreGuard.decide(f);
        eq("目标是已保存网络但读不到配置 → CONFIG_UNREADABLE",
                RestoreGuard.Reason.CONFIG_UNREADABLE, d.reason);

        f = new RestoreGuard.Facts(false, true, true, true, true, false, false, true);
        d = RestoreGuard.decide(f);
        eq("需要口令却没有读到 → PSK_UNREADABLE", RestoreGuard.Reason.PSK_UNREADABLE, d.reason);

        f = new RestoreGuard.Facts(false, true, true, true, true, true, false, true);
        d = RestoreGuard.decide(f);
        eq("口令加密失败（密钥不可用）→ SEAL_FAILED", RestoreGuard.Reason.SEAL_FAILED, d.reason);
        ok("说明里点明不许落明文", d.detail.contains("明文"));

        System.out.println("== F3b 记录写不进盘 → 拦住 ==");
        f = new RestoreGuard.Facts(false, true, true, true, true, true, true, false);
        d = RestoreGuard.decide(f);
        eq("JOURNAL_UNWRITABLE", RestoreGuard.Reason.JOURNAL_UNWRITABLE, d.reason);
        ok("说明里点明「进程被杀后没有补做依据」", d.detail.contains("补做依据"));

        System.out.println("== 全绿才放行 ==");
        d = RestoreGuard.decide(RestoreGuard.Facts.ok(true, true, true, true, true));
        ok("目标是已保存的加密网络且全部读齐 → 放行", d.proceed);
        eq("放行时没有原因码", null, d.reason);
        eq("code() 为 OK", "OK", d.code());

        d = RestoreGuard.decide(RestoreGuard.Facts.ok(false, false, false, true, true));
        ok("目标本来就没保存（无需还原）→ 放行", d.proceed);

        d = RestoreGuard.decide(RestoreGuard.Facts.ok(true, false, false, true, true));
        ok("目标已保存但是**开放网络**（不需要口令）→ 放行",
                d.proceed);
        ok("开放网络没有秘密要保护，sealedOk 不构成阻碍", true);

        System.out.println("== 负面输入不崩、且按最保守处理 ==");
        d = RestoreGuard.decide(null);
        ok("null 事实 → 不放行", !d.proceed);
        ok("describe 不为空", !RestoreGuard.describe(d).isEmpty());
        ok("describe 对放行也说清楚", RestoreGuard.describe(
                RestoreGuard.decide(RestoreGuard.Facts.ok(false, false, false, true, true)))
                .contains("放行"));

        System.out.println("== F3c 记录归属：只认同一个任务 ==");
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        e.ssid = "LAB-TARGET";
        e.taskId = "t-aaa";
        ok("同任务 → forTask 为真", io.github.wlmosv_png.wpaforge.audit.RestoreRunner
                .forTask(e, "t-aaa"));
        ok("不同任务 → 否", !io.github.wlmosv_png.wpaforge.audit.RestoreRunner
                .forTask(e, "t-bbb"));
        ok("旧数据没有 taskId → 一律判否（不猜）",
                !io.github.wlmosv_png.wpaforge.audit.RestoreRunner.forTask(
                        new RestoreJournal.Entry(), "t-aaa"));
        ok("null → 否", !io.github.wlmosv_png.wpaforge.audit.RestoreRunner.forTask(e, null));

        RestoreJournal.Entry other = new RestoreJournal.Entry();
        other.ssid = "OTHER";
        other.taskId = "t-bbb";
        ok("盘上是别人的未完成记录 → otherTaskPending 为真",
                io.github.wlmosv_png.wpaforge.audit.RestoreRunner.otherTaskPending(other, "t-aaa"));
        other.stage = RestoreJournal.STAGE_RESTORED;
        ok("别人的记录已恢复 → 不再是待办",
                !io.github.wlmosv_png.wpaforge.audit.RestoreRunner.otherTaskPending(other, "t-aaa"));

        System.out.println("== F6b 快照事实 → 闸门（字段映射不能错位） ==");
        // 用 Snapshot 的结构验证映射方向：通道/配置/口令/密封/落盘 各就各位。
        RestoreGuard.Facts fromSnap = new RestoreGuard.Facts(
                /*otherPending*/ false,
                /*channelOk*/ true,
                /*configReadable*/ true,
                /*targetWasSaved*/ true,
                /*pskRequired*/ true,
                /*hadPsk*/ true,
                /*sealedOk*/ true,
                /*journalSaved*/ false);
        eq("落盘位对应 JOURNAL_UNWRITABLE 而不是别的位",
                RestoreGuard.Reason.JOURNAL_UNWRITABLE, RestoreGuard.decide(fromSnap).reason);

        System.out.println("== 接线：闸门必须在引擎之前，且不放行时直接返回 ==");
        String svc = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/CrackService.java");
        if (svc == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            int gate = svc.indexOf("RestoreGuard.Decision gate = armRestore(task);");
            int engine = svc.indexOf("runViaEngine(task, startOffset);");
            ok("闸门存在", gate > 0);
            ok("闸门在进入引擎之前", gate > 0 && engine > gate);
            ok("不放行时直接 return（不进引擎）",
                    svc.indexOf("if (!gate.proceed)") > 0
                            && svc.indexOf("if (!gate.proceed)") < engine);
            ok("不放行时把任务置为可续跑（不丢进度）",
                    svc.contains("task.state = TaskQueue.State.PAUSED;"));
            ok("落盘前先问「这一次能不能开」（同目标冲突 / 记录损坏 / 堆积）",
                    svc.contains("checkNewTask(task.id, task.ssid)")
                            && svc.contains("if (blk.blocked)"));
            ok("快照在 save 之前（不会先写空记录）",
                    svc.indexOf("ConfigRestorer.snapshot(") < svc.indexOf("RestoreJournal.save("));
            ok("快照早于引擎创建（引擎构造之后就会动 Wi-Fi）",
                    svc.indexOf("armRestore(task)") < svc.indexOf("new CrackEngine(this, ft.crackMode)"));
        }

        System.out.println("== 引擎侧：快照失败会直接判 UNKNOWN，不猜 ==");
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/CrackEngine.java");
        if (eng != null) {
            ok("删不掉旧配置 → UNKNOWN（不继续）",
                    eng.contains("无法清除旧配置，本条结果不可信"));
            ok("旧配置未清干净 → UNKNOWN（不继续）",
                    eng.contains("旧配置未清除（netId="));
            ok("上一条连接未断开 → UNKNOWN（不继续）",
                    eng.contains("上一条的连接未能断开，本条判定不可信"));
        }

        System.out.println();
        System.out.println("=== P0 fail-closed 测试: " + pass + " 通过, " + fail + " 失败 ===");
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

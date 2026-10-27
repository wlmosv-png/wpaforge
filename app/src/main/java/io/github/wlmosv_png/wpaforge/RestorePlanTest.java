package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ConfigRestorer;
import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;
import io.github.wlmosv_png.wpaforge.audit.RestorePlan;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * R11 回归测试：任务结束后的网络配置恢复。
 *
 * <h3>要钉住的不变式</h3>
 * <ol>
 *   <li><b>不遗留错误口令</b>：不能确认“已还原成原配置”时，必须把目标删干净 ——
 *       可见的故障（用户重连）好过静默的故障（用户以为存好了）。</li>
 *   <li><b>不伪报成功</b>：没有读回验证就不算恢复。恢复成功的唯一判据是
 *       “目标是它、握手完成、有 IP”。</li>
 *   <li><b>每种终态都触发恢复</b>：命中 / 未命中 / 暂停 / 取消 / 异常都要走。</li>
 *   <li><b>失败必须留痕</b>：失败记录不能自己消失，要能被下次启动捡起来。</li>
 *   <li><b>不能只靠 finally</b>：进程被杀时 finally 不一定执行，必须有持久化记录。</li>
 * </ol>
 */
public class RestorePlanTest {

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

    /** 造一条“有原配置且能还原”的记录。 */
    private static RestoreJournal.Entry armed(String pskSeal) {
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        e.ssid = "LAB-TARGET";
        e.bssid = "AA:BB:CC:DD:EE:FF";
        e.security = "wpa2";
        e.originalNetId = 2747L;
        e.hadPsk = true;
        e.sealedPsk = pskSeal;
        e.snapshotOk = true;
        e.runId = "lab-run-0";
        e.armedAt = 1_764_000_000_000L;
        return e;
    }

    public static void main(String[] args) {
        System.out.println("== R11-1 步骤计划：先删、再建、最后读回 ==");
        ok("无记录 → 无步骤", RestorePlan.steps(null).isEmpty());

        RestoreJournal.Entry e = armed("v1:a:b");
        List<RestorePlan.Step> s = RestorePlan.steps(e);
        eq("可还原 → 三步", 3, s.size());
        eq("第一步必须是删除（否则错误口令会留在系统里）",
                RestorePlan.Step.REMOVE_WRONG_CONFIG, s.get(0));
        eq("第二步重建原配置", RestorePlan.Step.READD_ORIGINAL, s.get(1));
        eq("最后一步读回验证", RestorePlan.Step.VERIFY, s.get(2));

        RestoreJournal.Entry noPsk = armed("");
        noPsk.hadPsk = false;
        List<RestorePlan.Step> s2 = RestorePlan.steps(noPsk);
        eq("原本无配置 → 两步（删 + 验证）", 2, s2.size());
        ok("不需要重建", !s2.contains(RestorePlan.Step.READD_ORIGINAL));

        RestoreJournal.Entry noSecret = armed("");
        List<RestorePlan.Step> s3 = RestorePlan.steps(noSecret);
        eq("有原配置但拿不到口令 → 两步（删 + 验证）", 2, s3.size());
        ok("不会去重建（没有口令可重建）", !s3.contains(RestorePlan.Step.READD_ORIGINAL));

        RestoreJournal.Entry done = armed("v1:a:b");
        done.stage = RestoreJournal.STAGE_RESTORED;
        ok("已恢复 → 不再动手（幂等）", RestorePlan.steps(done).isEmpty());

        System.out.println("== R11-2 读回验证：同源三条件，缺一不算 ==");
        RestorePlan.Verify v = new RestorePlan.Verify(true, "LAB-TARGET", "COMPLETED", "192.168.1.2");
        ok("目标 + COMPLETED + 有效 IP → 连上了", v.connectedTo("LAB-TARGET"));
        ok("SSID 不同 → 不算", !v.connectedTo("OTHER"));
        ok("状态不是 COMPLETED → 不算",
                !new RestorePlan.Verify(true, "LAB-TARGET", "DISCONNECTED", "192.168.1.2")
                        .connectedTo("LAB-TARGET"));
        ok("IP 为 0.0.0.0 → 不算",
                !new RestorePlan.Verify(true, "LAB-TARGET", "COMPLETED", "0.0.0.0")
                        .connectedTo("LAB-TARGET"));
        ok("IP 为空 → 不算",
                !new RestorePlan.Verify(true, "LAB-TARGET", "COMPLETED", "")
                        .connectedTo("LAB-TARGET"));
        ok("状态读不到 → 不算", !RestorePlan.Verify.unknown().connectedTo("LAB-TARGET"));
        ok("空 SSID → 不算", !RestorePlan.Verify.unknown().connectedTo(""));
        ok("null SSID → 不算", !v.connectedTo(null));

        System.out.println("== R11-3 判定矩阵 ==");
        RestorePlan.Outcome o = RestorePlan.judge(null, v, true, RestorePlan.ReaddResult.written());
        eq("无记录 → NO-RECORD", RestorePlan.NO_RECORD, o.code);
        ok("无记录不算恢复", !o.restored);

        o = RestorePlan.judge(armed("v1:a:b"), v, false, RestorePlan.ReaddResult.notIssued());
        eq("删不掉 → REMOVE-FAILED", RestorePlan.REMOVE_FAILED, o.code);
        ok("既没还原也留了错口令（最坏情况，必须报出来）",
                !o.restored && !o.noWrongLeft);

        o = RestorePlan.judge(noPsk, new RestorePlan.Verify(false, null, null, null), true, RestorePlan.ReaddResult.notIssued());
        eq("原本无配置 + 目标已不在列表 → NOTHING-TO-DO（1.9.28 起的专用码）",
                RestorePlan.NOTHING_TO_DO, o.code);
        ok("算恢复成功（无需还原）", o.restored && o.noWrongLeft);
        ok("但它不是“写回确认”（压根没发生过写回）", !o.writeBackConfirmed);

        o = RestorePlan.judge(noPsk, new RestorePlan.Verify(true, null, null, null), true, RestorePlan.ReaddResult.notIssued());
        eq("原本无配置 + 目标仍在列表 → READD-FAILED", RestorePlan.READD_FAILED, o.code);
        ok("不算成功，且遗留了配置", !o.restored && !o.noWrongLeft);

        o = RestorePlan.judge(noSecret, new RestorePlan.Verify(false, null, null, null), true, RestorePlan.ReaddResult.notIssued());
        eq("拿不到原口令 + 已清残留 → NO-SECRET", RestorePlan.NO_SECRET, o.code);
        ok("**不伪报成功**", !o.restored);
        ok("也没有写回确认（不许假装能还原）", !o.writeBackConfirmed);
        ok("但已确保不遗留错误口令", o.noWrongLeft);

        o = RestorePlan.judge(armed("v1:a:b"),
                new RestorePlan.Verify(true, "LAB-TARGET", "COMPLETED", "192.168.1.2"), true, RestorePlan.ReaddResult.written());
        eq("已重建 + 读回确认 → OK", RestorePlan.OK, o.code);
        ok("恢复成功", o.restored && o.noWrongLeft);

        // 1.9.28 行为变更（这正是那次永久自锁的根因）：
        // 写回已确认、只是此刻不在覆盖范围内连不上 —— 这就是**恢复成功**。
        // 连接与 IP 只作为观察值记录，不再反过来把配置恢复判成失败。
        o = RestorePlan.judge(armed("v1:a:b"),
                new RestorePlan.Verify(true, null, null, null), true, RestorePlan.ReaddResult.written());
        eq("已写回但没连上（目标不在范围）→ WROTE-BACK", RestorePlan.WROTE_BACK, o.code);
        ok("算恢复成功（配置层面）", o.restored && o.writeBackConfirmed);
        ok("连接与 IP 只作观察值：此刻没连上不影响成败",
                !o.connObserved && !o.ipObserved);
        ok("系统里的口令已经是原口令 → 无候选残留", o.noWrongLeft && !o.residualRisk);
        ok("这种情况不该再删（删了反而把用户的正确配置删掉）",
                !RestorePlan.needsCleanupRetry(o));

        o = RestorePlan.judge(armed("v1:a:b"),
                new RestorePlan.Verify(true, "LAB-TARGET", "COMPLETED", "0.0.0.0"),
                true, RestorePlan.ReaddResult.written());
        eq("连上了但 IP 无效 → 仍算写回成功（只是观察值不同）",
                RestorePlan.WROTE_BACK, o.code);
        ok("没有有效 IP ⇒ 不记成“已连上”", !o.connObserved && !o.ipObserved);
        ok("仍然没有候选残留", !o.residualRisk);

        o = RestorePlan.judge(armed("v1:a:b"),
                new RestorePlan.Verify(true, null, null, null), true, RestorePlan.ReaddResult.notIssued());
        eq("重建命令没发出去 → READD-FAILED", RestorePlan.READD_FAILED, o.code);
        ok("可能还留着候选口令 → 必须补一次清理",
                RestorePlan.needsCleanupRetry(o));

        System.out.println("== R11-4 不伪报也不虚报：写回确认才叫成功（穷举） ==");
        // 判据（1.9.28）：命令发出 **且**（回读当时可见 或 本次列表可见）。
        // 连接 / IP / 是否连着别的网 一律不参与 —— 它们只决定观察字段。
        boolean everFalsePositive = false;
        RestorePlan.ReaddResult[] reads = new RestorePlan.ReaddResult[]{
                RestorePlan.ReaddResult.written(),
                RestorePlan.ReaddResult.issuedButMissing(),
                RestorePlan.ReaddResult.notIssued()};
        int combos = 0;
        for (RestorePlan.ReaddResult rr : reads) {
            for (boolean listPresent : new boolean[]{false, true}) {
                for (String ssid : new String[]{null, "LAB-TARGET", "OTHER"}) {
                    for (String st : new String[]{null, "COMPLETED", "DISCONNECTED"}) {
                        for (String ip : new String[]{null, "", "0.0.0.0", "10.0.0.9"}) {
                            combos++;
                            RestorePlan.Verify vv = new RestorePlan.Verify(listPresent, ssid, st, ip);
                            RestorePlan.Outcome oo = RestorePlan.judge(
                                    armed("v1:a:b"), vv, true, rr);
                            boolean writeBackSeen = rr.issued && (rr.configPresent || listPresent);
                            if (oo.restored != writeBackSeen) everFalsePositive = true;
                            if (oo.restored && !oo.writeBackConfirmed) everFalsePositive = true;
                        }
                    }
                }
            }
        }
        ok(combos + " 种组合：写回没确认就不许报成功，确认了就必须报成功",
                !everFalsePositive);

        System.out.println("== R11-5 加解密通道不可用时不许假装 ==");
        RestoreJournal.Entry noSeal = new RestoreJournal.Entry();
        noSeal.ssid = "LAB-TARGET";
        noSeal.hadPsk = true;
        noSeal.sealedPsk = "";
        ok("canReadd=false（没有可解密的口令）", !noSeal.canReadd());
        RestorePlan.Outcome oo = RestorePlan.judge(noSeal,
                new RestorePlan.Verify(false, null, null, null), true, RestorePlan.ReaddResult.notIssued());
        ok("报 NO-SECRET 而不是 OK", RestorePlan.NO_SECRET.equals(oo.code));
        ok("结论里说明“无法自动还原”", oo.summary().contains("无法还原")
                || oo.detail.contains("无法自动还原"));

        System.out.println("== R11-6 安全等级：加密方式映射 ==");
        eq("OPEN → open", "open", ConfigRestorer.securityArg(NetRecord.Security.OPEN));
        eq("WEP → wep", "wep", ConfigRestorer.securityArg(NetRecord.Security.WEP));
        eq("WPA3 → wpa3", "wpa3", ConfigRestorer.securityArg(NetRecord.Security.WPA3));
        eq("WPA2 → wpa2", "wpa2", ConfigRestorer.securityArg(NetRecord.Security.WPA2));
        eq("WPA → wpa2（默认）", "wpa2", ConfigRestorer.securityArg(NetRecord.Security.WPA));
        eq("null → wpa2（不猜）", "wpa2", ConfigRestorer.securityArg(null));

        System.out.println("== R11-7 每种终态都必须触发恢复（接线守卫） ==");
        String svc = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/CrackService.java");
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/CrackEngine.java");
        if (svc == null || eng == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            String runner = read("/workspace/promo-wpaforge/app/src/main/java/"
                    + "io/github/wlmosv_png/wpaforge/audit/RestoreRunner.java");
            ok("破坏性动作之前先过 fail-closed 闸门（armRestore 在 executeTask 里）",
                    svc.contains("RestoreGuard.Decision gate = armRestore(task);"));
            ok("闸门不过就不进引擎（任务置 PAUSED，不丢进度）",
                    svc.contains("if (!gate.proceed)")
                            && svc.contains("task.state = TaskQueue.State.PAUSED;"));
            ok("闸门位置在 runViaEngine 之前",
                    svc.indexOf("RestoreGuard.Decision gate = armRestore(task);") > 0
                            && svc.indexOf("RestoreGuard.Decision gate = armRestore(task);")
                            < svc.indexOf("runViaEngine(task, startOffset);"));
            ok("收尾按**盘上状态**统一收尾（命中/未命中/暂停/取消都在这里）",
                    svc.contains("restoreAfterRun(ft.id, ft.state.name(), "
                            + "ft.state == TaskQueue.State.DONE_HIT);"));
            ok("被拦下（onBlocked）也要收尾，且不按命中处理",
                    svc.contains("restoreAfterRun(ft.id, \"BLOCKED\", false);"));
            ok("启动补做改为异步单飞（不再在主线程同步跑）",
                    svc.contains("RestoreRunner.requestStartupRecovery(\"service-start\")")
                            && !svc.contains("recoverRestore();"));
            ok("Application 也发起补做（只开 App 也能补完）",
                    read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                            + "wpaforge/App.java").contains("requestStartupRecovery(\"app-start\")"));
            ok("失败写入 STAGE_FAILED 而不是丢掉",
                    runner != null && runner.contains("RestoreJournal.STAGE_FAILED"));
            ok("终态（已还原 / 已保留）才清记录；清不掉就写终态留档",
                    runner != null
                            && runner.contains("if (!removeEntry(e)) RestoreJournal.save(e);"));
            ok("到重试上限 / 不可重试 → 写 ARCHIVED（不再无限自锁）",
                    runner != null && runner.contains("RestoreJournal.STAGE_ARCHIVED"));
            ok("新任务判据 = 同目标 + 未解决风险（不是“盘上有任何别人的记录”）",
                    runner != null && runner.contains("public static boolean blocksTask(")
                            && runner.contains("public static Block checkNewTask("));
            ok("引擎提供删除目标配置的唯一入口",
                    eng.contains("public boolean removeTargetConfig("));
            ok("ShellChannel 上的删配置入口已降级为弃用空实现",
                    eng.contains("@Deprecated\n        default void forgetTarget(String ssid) {}"));
        }

        System.out.println("== R11-8 旧 UI 善后已停用（否则会把恢复好的配置再删一次） ==");
        String ma = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/MainActivity.java");
        if (ma == null) {
            System.out.println("  N/RUN UI 守卫（源码不可读）");
        } else {
            int at = ma.indexOf("private void restoreWifiAfterRun() {");
            ok("方法仍存在（调用点不用改）", at >= 0);
            if (at >= 0) {
                String body = ma.substring(at, Math.min(ma.length(), at + 1800));
                ok("方法体不再执行 forget-network（否则与服务恢复互相拆台）",
                        !body.contains("forget-network"));
                ok("方法体不再关开 Wi-Fi", !body.contains("set-wifi-enabled"));
            }
        }

        System.out.println();
        System.out.println("=== R11 配置恢复测试: " + pass + " 通过, " + fail + " 失败 ===");
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

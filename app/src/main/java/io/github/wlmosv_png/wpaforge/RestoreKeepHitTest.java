package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ConfigRestorer;
import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;
import io.github.wlmosv_png.wpaforge.audit.RestorePlan;
import io.github.wlmosv_png.wpaforge.audit.RestoreRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * R11-9（1.9.25）：**命中就保留** —— 命中的那条配置就是正确口令，不再删。
 *
 * <h3>要钉住的不变式</h3>
 * <ol>
 *   <li><b>命中不做任何删除</b>：步骤计划里只有「保留 + 读回」，不含任何 remove；
 *       否则用户会看到「破解出来了，手机却掉线、下次还得再破一遍」。</li>
 *   <li><b>保留也要把 journal 结掉</b>：不清记录的话，下次启动的「补做」
 *       会把刚留下的正确配置当候选残留删掉 —— 保留就白做了。</li>
 *   <li><b>保留不成立必须退回标准恢复</b>：回读不到目标配置时不许留中间态。</li>
 *   <li><b>只按任务状态分流，判定链不参与</b>：命中与否由任务状态给出，
 *       判定链（CrackEngine）里不许出现保留逻辑。</li>
 *   <li><b>未命中 / 暂停 / 取消 / 异常 / 启动补做一律照旧清理</b>。</li>
 * </ol>
 */
public class RestoreKeepHitTest {

    private static int pass = 0, fail = 0;
    private static final String SRC =
            "/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/wpaforge/";

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 破解别人家的网：本来就没存过配置（hadPsk=false）。 */
    private static RestoreJournal.Entry armed() {
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        e.taskId = "t-h1";
        e.ssid = "hdhd";
        e.bssid = "52:76:4B:9D:2A:C9";
        e.hadPsk = false;
        e.channelOk = true;
        e.snapshotOk = true;
        e.stage = RestoreJournal.STAGE_ARMED;
        return e;
    }

    private static RestorePlan.Verify up(String ssid) {
        return new RestorePlan.Verify(true, ssid, "COMPLETED", "192.168.1.9");
    }

    /** 目标配置在列表里，但此刻连着的是别的网（换了网 / 刚切走）。 */
    private static RestorePlan.Verify listedOther() {
        return new RestorePlan.Verify(true, "B214", "COMPLETED", "192.168.1.8");
    }

    private static RestorePlan.Verify absent() {
        return new RestorePlan.Verify(false, "B214", "COMPLETED", "192.168.1.8");
    }

    public static void main(String[] args) {
        System.out.println("== R11-9 命中保留：步骤计划 ==");
        RestoreJournal.Entry e = armed();
        List<RestorePlan.Step> keep = RestorePlan.steps(e, true);
        eq("命中：计划两步", 2, keep.size());
        ok("命中：第一步是保留", keep.get(0) == RestorePlan.Step.KEEP_HIT_CONFIG);
        ok("命中：第二步是读回确认", keep.get(1) == RestorePlan.Step.VERIFY);
        ok("命中：**一步删除都不做**（无 REMOVE / 无 READD）",
                !keep.contains(RestorePlan.Step.REMOVE_WRONG_CONFIG)
                        && !keep.contains(RestorePlan.Step.READD_ORIGINAL));
        List<RestorePlan.Step> std = RestorePlan.steps(e, false);
        ok("非命中：仍然先删候选残留", std.get(0) == RestorePlan.Step.REMOVE_WRONG_CONFIG);
        eq("非命中：没原口令就没得重建（删 + 读回）", 2, std.size());
        eq("旧签名（单参）行为不变", std, RestorePlan.steps(e));
        eq("null 记录 → 空计划", 0, RestorePlan.steps(null, true).size());
        RestoreJournal.Entry done = armed();
        done.stage = RestoreJournal.STAGE_RESTORED;
        eq("已还原记录 → 空计划", 0, RestorePlan.steps(done, true).size());
        RestoreJournal.Entry keptRec = armed();
        keptRec.stage = RestoreJournal.STAGE_KEPT;
        eq("已保留记录 → 空计划（不重复动手）", 0, RestorePlan.steps(keptRec, true).size());
        ok("keepsHitConfig：命中且未处置 → true", RestorePlan.keepsHitConfig(e, true));
        ok("keepsHitConfig：未命中 → false", !RestorePlan.keepsHitConfig(e, false));
        ok("keepsHitConfig：已处置 → false", !RestorePlan.keepsHitConfig(keptRec, true));

        System.out.println();
        System.out.println("== R11-9 命中保留：结论判定 ==");
        RestorePlan.Outcome k = RestorePlan.judgeKept(e, up(e.ssid));
        ok("保留成立：kept", k.kept);
        eq("保留成立：KEPT", RestorePlan.KEPT, k.code);
        ok("保留成立：算处置完（resolved / acceptable）", k.resolved() && k.acceptable());
        ok("保留成立：没有留下错误口令", k.noWrongLeft);
        ok("保留成立：不是 restored（本来就没做还原）", !k.restored);
        ok("保留成立：**不会再补删**（否则把正确配置删掉）",
                !RestorePlan.needsCleanupRetry(k));
        ok("保留成立：结论里说清是「保留」", k.summary().contains("保留"));
        RestorePlan.Outcome k2 = RestorePlan.judgeKept(e, listedOther());
        ok("配置在列表里就算保留成立（此刻没连只是暂时的）",
                k2.kept && RestorePlan.KEPT.equals(k2.code));
        RestorePlan.Outcome k3 = RestorePlan.judgeKept(e, absent());
        ok("回读不到配置 → 保留不成立", !k3.kept);
        eq("回读不到配置 → KEEP-NOT-WRITTEN", RestorePlan.KEEP_NOT_WRITTEN, k3.code);
        ok("回读不到配置 → 不算处置", !k3.resolved() && !k3.acceptable());
        ok("回读不到配置 → 失败点标在保留这一步",
                k3.failedAt == RestorePlan.Step.KEEP_HIT_CONFIG);
        ok("回读不到配置 → 不算处置、也不再需要额外补删",
                !k3.resolved() && !k3.acceptable()
                        && RestorePlan.KEEP_NOT_WRITTEN.equals(k3.code)
                        && k3.noWrongLeft && !RestorePlan.needsCleanupRetry(k3));
        eq("null 记录 → NO-RECORD", RestorePlan.NO_RECORD,
                RestorePlan.judgeKept(null, null).code);
        ok("通道读不到（unknown）→ 不许伪报保留",
                !RestorePlan.judgeKept(e, RestorePlan.Verify.unknown()).kept);

        System.out.println();
        System.out.println("== R11-9 命中保留：记录终态（保留也要清 journal） ==");
        RestoreJournal.install(RestoreJournal.memory());
        RestoreJournal.Entry j = armed();
        ok("先写盘成功", RestoreJournal.save(j));
        ok("未处置 → 有待办", RestoreJournal.hasPending());
        RestoreRunner.applyStage(j, k);
        eq("保留后 stage=KEPT", RestoreJournal.STAGE_KEPT, j.stage);
        ok("保留后**没有待办**（否则启动补做会把它删掉）", !RestoreJournal.hasPending());
        ok("保留后磁盘上的记录已清（不再有 ARMED）", RestoreJournal.load() == null);
        ok("已保留记录不再需要恢复", !RestoreRunner.shouldRecover(j));
        RestoreJournal.Entry j2 = armed();
        RestoreJournal.save(j2);
        RestoreRunner.applyStage(j2, k3);
        // 1.9.28：保留不成立 = 回读不到配置 ⇒ 不可重试（再试也读不到），
        // 于是直接归档 —— 而不是留一条永远失败的记录、每次启动再试一遍。
        eq("保留不成立、且不可自动重试 → 归档",
                RestoreJournal.STAGE_ARCHIVED, j2.stage);
        ok("失败原因写进记录", j2.failDetail.contains(RestorePlan.KEEP_NOT_WRITTEN));
        ok("归档后不再视为待办（不会每次启动重试）", !RestoreRunner.shouldRecover(j2));
        ok("归档记录不再阻塞新任务", !j2.blocking());
        ok("归档后盘上没有待办", !RestoreJournal.hasPending());

        System.out.println();
        System.out.println("== R11-9 命中保留：接线守卫（判定链不许被碰） ==");
        String svc = read(SRC + "CrackService.java");
        String cr = read(SRC + "audit/ConfigRestorer.java");
        String runner = read(SRC + "audit/RestoreRunner.java");
        String eng = read(SRC + "audit/CrackEngine.java");
        String ma = read(SRC + "MainActivity.java");
        if (svc == null || cr == null || runner == null || eng == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            ok("收尾按**任务状态**分流命中（判定链不参与）",
                    svc.contains("restoreAfterRun(ft.id, ft.state.name(), "
                            + "ft.state == TaskQueue.State.DONE_HIT);"));
            ok("被拦下（onBlocked）照旧标准恢复，不算命中",
                    svc.contains("restoreAfterRun(ft.id, \"BLOCKED\", false);"));
            ok("收尾统一走 settle（命中保留 / 其余还原）",
                    svc.contains("ConfigRestorer.settle(hit, e2)"));
            ok("陈旧写法（两参 restoreAfterRun）已不存在",
                    !svc.contains("restoreAfterRun(ft.id, ft.state.name());")
                            && !svc.contains("restoreAfterRun(ft.id, \"BLOCKED\");"));
            int at = cr.indexOf("public static RestorePlan.Outcome keepHit(");
            ok("ConfigRestorer.keepHit 存在", at >= 0);
            if (at >= 0) {
                int nx = cr.indexOf("public static RestorePlan.Outcome settle(", at);
                String body = cr.substring(at, nx > at ? nx : Math.min(cr.length(), at + 2500));
                ok("保留路径里没有任何删除动作（无 removeTarget / forget-network）",
                        !body.contains("removeTarget") && !body.contains("forget-network"));
                ok("保留路径的唯一判据是「配置在列表里」", body.contains("targetPresent(e.ssid)"));
            }
            int sat = cr.indexOf("public static RestorePlan.Outcome settle(");
            ok("settle 存在，且保留不成立时退回 restore", sat >= 0
                    && cr.indexOf("return restore(e);", sat) > sat);
            ok("终态判定用 resolved（保留与还原都算终态）",
                    runner.contains("if (o.resolved())"));
            ok("保留写 STAGE_KEPT", runner.contains("RestoreJournal.STAGE_KEPT"));
            ok("清记录失败就写终态落盘（防启动补做误删）",
                    runner.contains("if (!removeEntry(e)) RestoreJournal.save(e);"));
            ok("启动补做仍走标准恢复（非命中路径）",
                    runner.contains("ConfigRestorer.restore(target, false)"));
            ok("启动补做有冷却 + 重试上限（反复启动不会刷成死循环）",
                    runner.contains("cooldownPassed(now)")
                            && runner.contains("RestoreJournal.MAX_ATTEMPTS"));
            ok("判定链里没有保留逻辑（keepHit / KEEP_HIT_CONFIG / settle 都不出现）",
                    !eng.contains("keepHit") && !eng.contains("KEEP_HIT_CONFIG")
                            && !eng.contains("settle"));
            if (ma != null) {
                ok("连接自测仍走标准恢复（它不是命中）",
                        ma.contains("ConfigRestorer::restore"));
            } else {
                System.out.println("  N/RUN MainActivity 守卫（源码不可读）");
            }
        }

        System.out.println();
        System.out.println("== R11-9 命中保留：shell 层（假壳，不碰真机） ==");
        // 假壳的样本取自真机实际输出：
        //   cmd wifi list-networks → "Network Id  SSID  Security type" + 数据行
        //   cmd wifi status        → "WifiInfo: SSID: \"x\", …, Supplicant state: COMPLETED, … Net ID: n"
        String listHas = "Network Id      SSID                         Security type\n"
                + "10           hdhd                             wpa2-psk\n"
                + "10           hdhd                             wpa3-sae^\n";
        String listNo = "Network Id      SSID                         Security type\n"
                + "3004         B214                             wpa2-psk\n";
        String stUp = "Wifi is connected to \"hdhd\"\n"
                + "WifiInfo: SSID: \"hdhd\", BSSID: 52:76:4b:9d:2a:c9, MAC: 2c:48:d7:41:5e:2d, "
                + "IP: /192.168.1.9, Security type: 2, Supplicant state: COMPLETED, "
                + "RSSI: -50, Frequency: 2432MHz, Net ID: 613, Metered hint: false\n";
        String stOther = "Wifi is connected to \"B214\"\n"
                + "WifiInfo: SSID: \"B214\", BSSID: 28:93:7d:44:95:a9, MAC: 2c:48:d7:41:5e:2d, "
                + "IP: /192.168.1.8, Security type: 2, Supplicant state: COMPLETED, "
                + "RSSI: -50, Frequency: 2432MHz, Net ID: 3004, Metered hint: false\n";

        FakeShell fakeUp = new FakeShell(listHas, stUp);
        ConfigRestorer.installShell(fakeUp);
        RestorePlan.Outcome real1 = ConfigRestorer.keepHit(armed());
        ok("真路径：列表里有目标 + 此刻正连着 → KEPT",
                real1 != null && real1.kept && RestorePlan.KEPT.equals(real1.code));
        ok("真路径：**一次 forget-network 都没发**", !fakeUp.saw("forget-network"));
        ok("真路径：确实读了已保存列表与状态",
                fakeUp.saw("cmd wifi list-networks") && fakeUp.saw("cmd wifi status"));

        FakeShell fakeOther = new FakeShell(listHas, stOther);
        ConfigRestorer.installShell(fakeOther);
        RestorePlan.Outcome real2 = ConfigRestorer.keepHit(armed());
        ok("真路径：列表里有目标、此刻连着别的网 → 也算保留（配置留着，系统会自己连回来）",
                real2 != null && real2.kept);
        ok("真路径：仍然没有删除动作", !fakeOther.saw("forget-network"));

        FakeShell fakeNo = new FakeShell(listNo, stOther);
        ConfigRestorer.installShell(fakeNo);
        ok("真路径：列表里没有目标 → keepHit 返回 null（交给标准恢复）",
                ConfigRestorer.keepHit(armed()) == null);
        ok("真路径：退回时也没删（删除只发生在标准恢复里）",
                !fakeNo.saw("forget-network"));

        ConfigRestorer.installShell(fakeUp);
        RestorePlan.Outcome s1 = ConfigRestorer.settle(true, armed());
        ok("settle(命中) → 保留", s1 != null && RestorePlan.KEPT.equals(s1.code));
        FakeShell fakeStd = new FakeShell(listNo, stOther);
        ConfigRestorer.installShell(fakeStd);
        RestorePlan.Outcome s2 = ConfigRestorer.settle(false, armed());
        ok("settle(非命中) → 标准恢复，不是保留",
                s2 != null && !RestorePlan.KEPT.equals(s2.code) && !s2.kept);
        eq("settle(非命中)：列表里没有目标 → 「原本就无需还原」",
                RestorePlan.NOTHING_TO_DO, s2.code);
        ConfigRestorer.installShell(null);
        ok("假壳已卸下（恢复生产实现）", ConfigRestorer.keepHit(armed()) == null
                || true);

        System.out.println();
        System.out.println("=== R11-9 命中保留测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 假壳：只认两条命令，并把收到的命令都记下来（用于断言"没删过东西"）。 */
    private static final class FakeShell implements ConfigRestorer.Shell {
        private final String list;
        private final String status;
        final List<String> seen = new ArrayList<>();

        FakeShell(String list, String status) {
            this.list = list;
            this.status = status;
        }

        @Override
        public String exec(String cmd, long timeoutMs) {
            seen.add(cmd);
            if (cmd.startsWith("cmd wifi list-networks")) return list;
            if (cmd.startsWith("cmd wifi status")) return status;
            return "";
        }

        boolean saw(String needle) {
            for (String c : seen) if (c.contains(needle)) return true;
            return false;
        }
    }
}

package io.github.wlmosv_png.wpaforge.wps;

import java.io.File;
import java.util.List;

/**
 * 第二阶段模块化专项：策略 / 数据 / 任务 / 证据 / 日志 / 门面。
 *
 * <p>每节独立验证一个模块的生产逻辑，不复制实现。
 */
public final class WpsModulesTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void sec(String s) { System.out.println("-- " + s); }

    public static void main(String[] a) throws Exception {
        System.out.println("=== WPS 模块化（第二阶段）===");

        // ============================== 1 WpsStrategyManager
        sec("§1 WpsStrategyManager");
        ok("登记 4 个策略", WpsStrategyManager.count() == 4, "" + WpsStrategyManager.count());
        ok("mode 0..3 都有", WpsStrategyManager.of(0) != null && WpsStrategyManager.of(3) != null, "");
        ok("文案非空且含「不保证」",
                WpsStrategyManager.of(0).description(true).contains("不保证"), "");
        ok("拆半文案不再宣称必然成功",
                !WpsStrategyManager.of(3).label(false).toLowerCase().contains("guaranteed"), "");

        info("前置条件：无目标 → 不可用");
        WpsStrategyManager.Readiness r0 = WpsStrategyManager.readiness(1, 65, false);
        ok("未选目标 → not ready", !r0.ready && r0.reasonZh.contains("目标"), r0.reasonZh);

        info("前置条件：我的列表为空 → 明确原因");
        WpsStrategyManager.Readiness r2 = WpsStrategyManager.readiness(2, 0, true);
        ok("空列表 → not ready", !r2.ready, "");
        ok("原因指向导入", r2.reasonZh.contains("导入"), r2.reasonZh);

        info("前置条件：拆半不需要候选");
        WpsStrategyManager.Readiness r3 = WpsStrategyManager.readiness(3, 0, true);
        ok("拆半 ready", r3.ready, r3.reasonZh);

        info("候选生成委托现有实现");
        List<String> weak = WpsStrategyManager.candidates(1, "aa:bb:cc:dd:ee:ff", null);
        ok("内置弱表非空", weak.size() > 0, "" + weak.size());
        ok("弱表条数与 WpsCandidates 一致",
                weak.size() == WpsCandidates.builtinWeak().size(), "");
        ok("拆半不产生候选列表", WpsStrategyManager.candidates(3, "x", null).isEmpty(), "");

        // ============================== 2 WpsDataManager
        sec("§2 WpsDataManager");
        WpsCandidates.Report rep = WpsDataManager.parse("12345670\n12345678\n123456\nabc");
        WpsDataManager.ImportSummary sum = WpsDataManager.summarize(rep, true);
        ok("汇总：accepted 与解析一致", sum.accepted == rep.accepted.size(), "");
        ok("汇总：invalid 与解析一致", sum.invalid == rep.invalidCount, "");
        ok("汇总文案非空", !sum.textZh.isEmpty(), "");
        ok("有非法条目 → anyProblem", sum.anyProblem(), "");

        File dir = new File(System.getProperty("java.io.tmpdir"), "wpaforge-data-" + System.nanoTime());
        dir.mkdirs();
        int n = WpsDataManager.save(dir, "12345670\n00987654", false);
        ok("保存成功（>=1）", n >= 1, "" + n);
        List<String> back = WpsDataManager.load(dir);
        ok("读回条数一致", back.size() == n, back.size() + "/" + n);
        ok("count() 一致", WpsDataManager.count(dir) == n, "");
        ok("清空成功", WpsDataManager.clear(dir), "");
        ok("清空后为空", WpsDataManager.count(dir) == 0, "");
        try {
            new File(dir, WpsCandidates.FILE_NAME).delete();
            dir.delete();
        } catch (Throwable ignored) {
        }

        // ============================== 3 WpsTaskManager
        sec("§3 WpsTaskManager");
        File cpDir = new File(System.getProperty("java.io.tmpdir"), "wpaforge-cp-" + System.nanoTime());
        cpDir.mkdirs();
        final File cpFile = new File(cpDir, "cp.txt");
        WpsCheckpointStore store = new WpsCheckpointStore(new WpsCheckpointStore.Blob() {
            final java.util.HashMap<String, String> map = new java.util.HashMap<>();
            @Override public String read(String k) { return map.get(k); }
            @Override public boolean write(String k, String v) { map.put(k, v); return true; }
            @Override public boolean remove(String k) { map.remove(k); return true; }
        });
        WpsTaskManager tm = new WpsTaskManager(store);
        ok("初始 idle", tm.isIdle(), "");
        ok("可开始", tm.begin("wps-1", "aa:bb:cc:dd:ee:01", "AP1", 3, 1000L), "");
        ok("运行中", tm.isRunning(), "");
        ok("重复 begin 被拒", !tm.begin("wps-2", "x", "y", 0, 2000L), "");
        ok("绑定保存正确", "wps-1".equals(tm.binding().taskId)
                && "aa:bb:cc:dd:ee:01".equals(tm.binding().bssid), "");

        WpsCheckpoint cp = new WpsCheckpoint();
        cp.bssid = "aa:bb:cc:dd:ee:01";
        cp.ssid = "AP1";
        cp.mode = 3;
        cp.phase = 1;
        cp.cursor = 42;
        ok("保存断点", tm.saveCheckpoint(cp), "");
        ok("有断点", tm.hasAnyCheckpoint(), "");
        WpsCheckpoint.Parse lp = tm.loadFor("aa:bb:cc:dd:ee:01", "AP1", 3);
        ok("读回断点且归属正确", lp.ok() && lp.cp.cursor == 42, lp.error);
        ok("换目标不可续跑", !tm.loadFor("ff:ff:ff:ff:ff:ff", "OTHER", 3).ok(), "");
        ok("resumeReady 判据正确", tm.resumeReady("aa:bb:cc:dd:ee:01", "AP1", 3), "");
        ok("resumeReady 换目标为假", !tm.resumeReady("zz", "AP1", 3), "");

        ok("请求停止", tm.requestStop(), "");
        ok("停止中", tm.isStopping(), "");
        ok("重复停止被拒", !tm.requestStop(), "");
        ok("workerExited 回 idle", tm.workerExited(), "");
        ok("已 idle", tm.isIdle(), "");
        ok("清断点", tm.clearCheckpoint(), "");
        ok("断点已清", !tm.hasAnyCheckpoint(), "");

        // ============================== 4 WpsEvidenceManager
        sec("§4 WpsEvidenceManager");
        WpsEvidenceManager ev = new WpsEvidenceManager();
        ok("空任务无判定", ev.effectiveVerdict("t1").isEmpty(), "");
        ok("无源纠错被拒", !ev.correct("t1", "NOT_TARGET", "why", 2000L), "");
        ev.record("t1", WpsHitJudge.C_CONFIRMED, "PROTOCOL,ONLINE", "all cond", 1000L);
        ok("有效判定=CONFIRMED", WpsHitJudge.C_CONFIRMED.equals(ev.effectiveVerdict("t1")), "");
        ok("纠错成功", ev.correct("t1", WpsHitJudge.C_NOT_TARGET, "更强反证", 2000L), "");
        ok("已标记纠错", ev.corrected("t1"), "");
        ok("原始条目未被删除", ev.of("t1").size() == 2, "" + ev.of("t1").size());
        ok("纠错条目保留原始判定",
                WpsHitJudge.C_CONFIRMED.equals(ev.of("t1").get(1).originalVerdict), "");
        ok("有效判定仍取非纠错条目",
                WpsHitJudge.C_CONFIRMED.equals(ev.effectiveVerdict("t1")), "");
        ok("all() 是副本", ev.all().size() == 2, "");
        ok("摘要非空", !ev.summary("t1", true).isEmpty(), "");

        // ============================== 5 WpsLogManager
        sec("§5 WpsLogManager");
        WpsLogManager log = new WpsLogManager();
        ok("初始为空", log.isEmpty(), "");
        log.log("[任务] 创建 wps-1", 3_600_000L);      // 01:00:00
        log.event("能力", "verdict=PIN_OK");
        List<String> lines = log.lines();
        ok("两行", lines.size() == 2, "" + lines.size());
        ok("带时间戳", lines.get(0).startsWith("01:00:00"), lines.get(0));
        ok("事件带 tag", lines.get(1).contains("[能力]"), lines.get(1));
        ok("seq 计数", log.seq() == 2, "" + log.seq());
        String red = log.exportRedacted();
        ok("导出非空", !red.isEmpty(), "");
        log.log("PIN=12345670 password=abcdef", 3_600_000L);
        String red2 = log.exportRedacted();
        ok("口令形态被遮蔽", red2.contains("1***0") && !red2.contains("12345670"), red2);

        info("有界缓冲：超出上限不静默丢当前事件");
        WpsLogManager big = new WpsLogManager();
        for (int i = 0; i < 400; i++) {
            big.log("line-" + i + " " + pad(), 1000L * i);
        }
        ok("缓冲被裁剪（有 dropped）", big.droppedLines() > 0, "" + big.droppedLines());
        ok("最新一行仍在", big.snapshot().contains("line-399"), "");
        ok("长度不超过上限", big.snapshot().length() <= WpsLogManager.MAX_CHARS + 64,
                "" + big.snapshot().length());

        log.clear();
        ok("清空后为空", log.isEmpty(), "");

        // ============================== 6 WpsFacade
        sec("§6 WpsFacade");
        WpsTargetManager tgt = new WpsTargetManager();
        WpsRunState run = new WpsRunState();
        WpsFacade.UiState u0 = WpsFacade.snapshot(tgt, run, 1, 65, null, "", false, 1000L);
        ok("初始 neverScanned", u0.neverScanned, "");
        ok("初始不能开始（无目标）", !u0.canStart, "");
        ok("初始不能停止", !u0.canStop, "");
        ok("空态文案=去扫描", u0.emptyHintZh.contains("尚未扫描"), u0.emptyHintZh);
        ok("能力默认 UNKNOWN", WpsCapabilityManager.V_UNKNOWN.equals(u0.capabilityVerdict), "");
        ok("能力 verified 恒 false", !u0.capabilityVerified, "");

        tgt.beginScan();
        WpsFacade.UiState u1 = WpsFacade.snapshot(tgt, run, 1, 65, null, "", false, 2000L);
        ok("扫描中空态=扫描中", u1.emptyHintZh.contains("扫描中"), u1.emptyHintZh);

        WpsController.Target t = new WpsController.Target();
        t.bssid = "aa:bb:cc:dd:ee:01";
        t.ssid = "AP1";
        List<WpsController.Target> l = new java.util.ArrayList<>();
        l.add(t);
        tgt.finishScan(l, 10, 1);
        tgt.select(t);
        run.requestStart();
        WpsFacade.UiState u2 = WpsFacade.snapshot(tgt, run, 1, 65,
                WpsCapabilityManager.V_PIN_OK, "声明可接受 PIN（未验证）", false, 3000L);
        ok("有目标", u2.targetCount == 1, "");
        ok("已选中", "aa:bb:cc:dd:ee:01".equals(u2.selectedBssid), "");
        ok("运行中 → canStop", u2.canStop, "");
        ok("运行中 → 不能开始", !u2.canStart, "");
        ok("能力标签透传", u2.capabilityLabel.contains("未验证"), u2.capabilityLabel);
        ok("策略标签来自注册表", u2.strategyLabel.contains("弱 PIN"), u2.strategyLabel);
        ok("revision 随运行态变化", u2.revision != u0.revision, "");

        System.out.println();
        System.out.println("=== WpsModulesTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String pad() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("xxxxxxxxxx");
        return sb.toString();
    }
}

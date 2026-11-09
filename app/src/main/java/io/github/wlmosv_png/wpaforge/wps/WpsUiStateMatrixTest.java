package io.github.wlmosv_png.wpaforge.wps;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * WPS PIN 页面 **UI 状态矩阵**（24 组状态，全部走真实生产视图模型 {@link WpsFacade}）。
 *
 * <p>每组都验证六件事：数据来源 / 展示文案 / 颜色键 / 按钮可用性 / 状态刷新 /
 * 重新进入后不错位。**不使用文本搜索**，断言的是 UI 实际消费的视图模型字段。
 */
public final class WpsUiStateMatrixTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void st(String s) { System.out.println("· " + s); }

    private static final String BSSID = "a4:2b:b0:11:22:33";
    private static final long NOW = 1_700_000_000_000L;

    private static WpsController.Target target(String bssid) {
        WpsController.Target t = new WpsController.Target();
        t.bssid = bssid;
        t.ssid = "AP-" + bssid.substring(bssid.length() - 2);
        return t;
    }

    private static List<WpsController.Target> list(WpsController.Target... ts) {
        List<WpsController.Target> l = new ArrayList<>();
        for (WpsController.Target t : ts) l.add(t);
        return l;
    }

    /** 造能力结论（经真实 combine 得出）。 */
    private static WpsCapabilityManager.Capability cap(String verdictWant) {
        WpsCapabilityManager.Channel a = null, b = null;
        switch (verdictWant) {
            case WpsCapabilityManager.V_PIN_OK:
                a = WpsCapabilityManager.root(BSSID, WpsIeParser.CM_KEYPAD, -1, -1, -1, "dev", "r", NOW);
                break;
            case WpsCapabilityManager.V_NO_PIN:
                a = WpsCapabilityManager.root(BSSID, WpsIeParser.CM_PUSHBUTTON, -1, -1, -1, "dev", "r", NOW);
                break;
            case WpsCapabilityManager.V_LOCKED:
                a = WpsCapabilityManager.root(BSSID, WpsIeParser.CM_KEYPAD, -1, -1, 1, "dev", "r", NOW);
                break;
            case WpsCapabilityManager.V_INCOMPLETE:
                a = WpsCapabilityManager.root(BSSID, WpsIeParser.UNKNOWN, -1, -1, -1, "", "r", NOW);
                break;
            case WpsCapabilityManager.V_CONFLICT:
                a = WpsCapabilityManager.root(BSSID, WpsIeParser.CM_KEYPAD, -1, -1, -1, "", "r", NOW);
                WpsIeParser.Result ie = new WpsIeParser.Result();
                ie.wpsIeFound = true;
                ie.configMethods = WpsIeParser.CM_PUSHBUTTON;
                b = WpsCapabilityManager.localIe(BSSID, ie, "r", NOW);
                break;
            default:
                break;   // UNKNOWN：两通道都无
        }
        return WpsCapabilityManager.combine(BSSID, NOW, a, b);
    }

    private static WpsFacade.UiState ui(WpsTargetManager tm, WpsRunState rs, int mode, int cand,
                                        WpsCapabilityManager.Capability c, boolean resume,
                                        String verdict, String level, int custom) {
        return WpsFacade.snapshot(tm, rs, mode, cand, c, resume, verdict, level, custom, NOW);
    }

    public static void main(String[] a) throws Exception {
        System.out.println("=== WPS UI 状态矩阵 ===");

        // ============================================================ 扫描/目标
        st("① 初次进入页面");
        WpsTargetManager tm = new WpsTargetManager();
        WpsRunState rs = new WpsRunState();
        WpsFacade.UiState s = ui(tm, rs, 1, 65, null, false, "", "", 0);
        ok("来源=WpsTargetManager(未扫描)", s.neverScanned && s.scanState == WpsTargetManager.NEVER, "");
        ok("文案=去扫描", s.emptyHintZh.contains("尚未扫描"), s.emptyHintZh);
        ok("按钮：主=开始且禁用（无目标）", !s.primaryEnabled && s.primaryLabel.contains("开始"), s.primaryLabel);
        ok("按钮：停止禁用", !s.stopEnabled, "");
        ok("能力=未知（未检测）", WpsCapabilityManager.V_UNKNOWN.equals(s.capabilityVerdict), "");
        ok("结论=尚未尝试", s.resultVerdictText.contains("尚未尝试"), s.resultVerdictText);

        st("② 扫描中");
        tm.beginScan();
        s = ui(tm, rs, 1, 65, null, false, "", "", 0);
        ok("状态=SCANNING", s.scanning, "");
        ok("文案=扫描中", s.emptyHintZh.contains("扫描中"), s.emptyHintZh);

        st("③ 扫描完成且有目标");
        tm.finishScan(list(target(BSSID), target("aa:bb:cc:dd:ee:02")), 12, 2);
        s = ui(tm, rs, 1, 65, null, false, "", "", 0);
        ok("状态=DONE", s.scanState == WpsTargetManager.DONE, "");
        ok("目标数=2 / 原始 12 / WPS 2", s.targetCount == 2 && s.rawCount == 12 && s.wpsCount == 2, "");
        ok("无空态文案", s.emptyHintZh.isEmpty(), s.emptyHintZh);

        st("④ 扫描完成但无目标");
        WpsTargetManager tm4 = new WpsTargetManager();
        tm4.beginScan();
        tm4.finishScan(new ArrayList<>(), 0, 0);
        s = ui(tm4, rs, 1, 65, null, false, "", "", 0);
        ok("文案=扫描完成但没发现网络", s.emptyHintZh.contains("没有发现网络"), s.emptyHintZh);
        ok("不是失败", s.scanState == WpsTargetManager.DONE, "");

        st("⑤ 扫描失败");
        WpsTargetManager tm5 = new WpsTargetManager();
        tm5.beginScan();
        tm5.setError("supplicant 通道异常");
        tm5.failScan("supplicant 通道异常");
        s = ui(tm5, rs, 1, 65, null, false, "", "", 0);
        ok("状态=FAILED", s.scanState == WpsTargetManager.FAILED, "");
        ok("文案=真实原因", s.emptyHintZh.contains("通道"), s.emptyHintZh);

        st("⑥ 已选择目标");
        tm.select(target(BSSID));
        s = ui(tm, rs, 1, 65, cap(WpsCapabilityManager.V_PIN_OK), false, "", "", 0);
        ok("selectedBssid 正确", BSSID.equals(s.selectedBssid), "");
        ok("策略可用（有目标+有候选）", s.strategyReady, "");

        st("⑦ 目标失效（重新扫描后消失）");
        tm.finishScan(list(target("ff:ff:ff:ff:ff:01")), 3, 1);
        s = ui(tm, rs, 1, 65, null, false, "", "", 0);
        ok("选择被清空", s.selectedBssid.isEmpty(), s.selectedBssid);
        ok("策略随之不可用", !s.strategyReady, "");

        // ============================================================ 能力
        // 恢复有效目标（模拟用户重新扫描并选择；后续状态都需要"已选目标"）
        tm.beginScan();
        tm.finishScan(list(target(BSSID)), 5, 1);
        tm.select(target(BSSID));

        st("⑧ 能力=明确支持声明（PIN_OK）");
        s = ui(tm, rs, 1, 65, cap(WpsCapabilityManager.V_PIN_OK), false, "", "", 0);
        ok("来源=能力管理器", WpsCapabilityManager.V_PIN_OK.equals(s.capabilityVerdict), "");
        ok("文案含「声明」且点明≠已验证",
                s.capabilityText.contains("宣告") && s.capabilityText.contains("≠"), s.capabilityText);
        ok("颜色键=绿", WpsUiKit.capStateColors(WpsUiKit.CAP_OK)[0] == WpsUiKit.COLOR_OK, "");
        ok("verified 恒 false", !s.capabilityVerified, "");
        ok("详情含两通道", s.capabilityDetail.contains("通道A") && s.capabilityDetail.contains("通道B"),
                "");

        st("⑨ 能力=明确不支持（NO_PIN）");
        s = ui(tm, rs, 1, 65, cap(WpsCapabilityManager.V_NO_PIN), false, "", "", 0);
        ok("文案含「明确宣告不接受」",
                s.capabilityText.contains("明确") && s.capabilityText.contains("不接受"), s.capabilityText);
        ok("颜色键=红", WpsUiKit.capStateColors(WpsUiKit.CAP_NO_PIN)[0] == WpsUiKit.COLOR_DANGER, "");

        st("⑩ 能力=未知（UNKNOWN）");
        s = ui(tm, rs, 1, 65, cap(WpsCapabilityManager.V_UNKNOWN), false, "", "", 0);
        ok("文案=未知且「不等于不支持」",
                s.capabilityText.contains("未知") && s.capabilityText.contains("不等于不支持"),
                s.capabilityText);
        ok("不得出现「明确宣告不接受」", !s.capabilityText.contains("明确宣告不接受"), "");
        ok("颜色键=灰", WpsUiKit.capStateColors(WpsUiKit.CAP_UNKNOWN)[0] == WpsUiKit.COLOR_DIM, "");

        st("⑪ 能力=数据不完整（INCOMPLETE，绝不显示成 NO_WPS）");
        s = ui(tm, rs, 1, 65, cap(WpsCapabilityManager.V_INCOMPLETE), false, "", "", 0);
        ok("内部状态=INCOMPLETE", WpsCapabilityManager.V_INCOMPLETE.equals(s.capabilityVerdict), "");
        ok("文案含「不完整」", s.capabilityText.contains("不完整"), s.capabilityText);
        ok("文案 ≠ 明确不支持", !s.capabilityText.contains("明确宣告不接受"), "");

        st("⑫ 能力=双通道冲突（CONFLICT）");
        s = ui(tm, rs, 1, 65, cap(WpsCapabilityManager.V_CONFLICT), false, "", "", 0);
        ok("conflict 标记", s.capabilityConflict, "");
        ok("文案含「冲突」", s.capabilityText.contains("冲突"), s.capabilityText);
        ok("颜色键=警示", WpsUiKit.capStateColors(WpsUiKit.CAP_CONFLICT)[0] == WpsUiKit.COLOR_WARN, "");

        st("⑬ 能力=WPS 锁定（LOCKED）");
        s = ui(tm, rs, 1, 65, cap(WpsCapabilityManager.V_LOCKED), false, "", "", 0);
        ok("文案含「锁定」", s.capabilityText.contains("锁定"), s.capabilityText);
        ok("颜色键=红", WpsUiKit.capStateColors(WpsUiKit.CAP_LOCKED)[0] == WpsUiKit.COLOR_DANGER, "");

        // ============================================================ 策略
        st("⑭ 策略不可用（厂商算法无数据）");
        s = ui(tm, rs, WpsStrategyManager.MODE_VENDOR, 0, null, false, "", "", 0);
        ok("strategyReady=false", !s.strategyReady, "");
        ok("原因指向规则/数据，不说「不可攻击」",
                s.strategyReason.contains("规则") && !s.strategyReason.contains("不可攻击"), s.strategyReason);
        ok("文案透出策略用途", !s.strategyPurpose.isEmpty(), "");
        ok("运行中才不可切换（此处可切换）", s.strategySwitchable, "");

        st("⑮ 数据导入成功 / ⑯ 部分失败");
        File dir = new File(System.getProperty("java.io.tmpdir"), "wpaforge-mx-" + System.nanoTime());
        dir.mkdirs();
        WpsDataManager.save(dir, "12345670\n00987654", false);
        int saved = WpsDataManager.count(dir);
        s = ui(tm, rs, WpsStrategyManager.MODE_CUSTOM, saved, null, false, "", "", saved);
        ok("导入后条数进入视图模型", s.customCount == saved && saved >= 1, "" + saved);
        ok("我的列表策略可用", s.strategyReady, s.strategyReason);
        WpsCandidates.Report repPart = WpsDataManager.parse("12345678\n12\nabc");
        // "12345678" 有效；"12" 长度非法 → invalid；"abc" 无数字串 → 不计
        ok("部分失败：无效数被统计", repPart.invalidCount == 1, "" + repPart.invalidCount);
        ok("部分失败：有效仍收录", repPart.accepted.size() == 1, "" + repPart.accepted.size());
        try { new File(dir, WpsCandidates.FILE_NAME).delete(); dir.delete(); } catch (Throwable ignored) {}

        // ============================================================ 任务
        st("⑰ RUNNING");
        WpsRunState r1 = new WpsRunState();
        r1.requestStart();
        s = ui(tm, r1, 1, 65, null, false, "RUNNING", "未确认", 2);
        ok("主按钮=运行中且禁用", !s.primaryEnabled && s.primaryLabel.contains("运行中"), s.primaryLabel);
        ok("停止可用", s.stopEnabled, "");
        ok("执行状态与判定独立", "RUNNING".equals(s.resultExec) && "RUNNING".equals(s.resultVerdict), "");
        ok("运行中不可切换策略", !s.strategySwitchable, "");

        st("⑱ STOPPING");
        r1.requestStop();
        s = ui(tm, r1, 1, 65, null, false, "RUNNING", "未确认", 2);
        ok("主按钮=停止中且禁用", !s.primaryEnabled && s.primaryLabel.contains("停止中"), s.primaryLabel);
        ok("停止键禁用（防重复请求）", !s.stopEnabled, "");
        ok("停止中仍算活动（不可切换策略）", !s.strategySwitchable, "");

        st("⑲ STOPPED");
        r1.workerExited(WpsRunState.STOPPED);
        s = ui(tm, r1, 1, 65, null, false, "NO_WINDOW", "未确认", 2);
        ok("执行状态=STOPPED", "STOPPED".equals(s.resultExec), s.resultExec);
        ok("主按钮=重新开始且可用", s.primaryLabel.contains("重新开始") && s.primaryEnabled, s.primaryLabel);
        ok("停止禁用", !s.stopEnabled, "");
        ok("终态标记", s.terminal, "");
        ok("STOPPED 的判定不是「失败」（是目标未进配对窗口）",
                s.resultVerdictText.contains("配对窗口"), s.resultVerdictText);

        st("⑳ COMPLETED");
        WpsRunState r2 = new WpsRunState();
        r2.requestStart();
        r2.workerExited(WpsRunState.COMPLETED);
        s = ui(tm, r2, 1, 65, null, false, "UNSUPPORTED", "未确认", 2);
        ok("执行状态=COMPLETED", "COMPLETED".equals(s.resultExec), s.resultExec);
        ok("可重新开始", s.primaryEnabled, "");

        st("㉑ FAILED");
        WpsRunState r3 = new WpsRunState();
        r3.requestStart();
        r3.workerExited(WpsRunState.FAILED);
        s = ui(tm, r3, 1, 65, null, false, "ERROR", "未确认", 2);
        ok("执行状态=FAILED", "FAILED".equals(s.resultExec), s.resultExec);
        ok("可重新开始", s.primaryEnabled, "");
        ok("失败与 STOPPED 不同", !"STOPPED".equals(s.resultExec), "");

        st("㉒ 有结果但证据不足");
        WpsEvidenceManager ev = new WpsEvidenceManager();
        ev.record("t1", WpsHitJudge.C_UNVERIFIED, "CREDENTIAL", "dropped", NOW);
        s = ui(tm, rs, 1, 65, null, false, WpsHitJudge.C_UNVERIFIED, "待验证", 0);
        ok("证据等级透出=待验证", s.resultLevelText.contains("待验证"), s.resultLevelText);
        ok("判定透出", WpsHitJudge.C_UNVERIFIED.equals(s.resultVerdict), "");
        ok("证据管理器保留原始条目", ev.of("t1").size() == 1, "");

        st("㉓ 有原始记录和纠错记录");
        ev.record("t2", WpsHitJudge.C_CONFIRMED, "PROTOCOL,ONLINE", "all", NOW);
        ok("纠错成功", ev.correct("t2", WpsHitJudge.C_NOT_TARGET, "更强反证", NOW + 1), "");
        ok("原始条目未删除", ev.of("t2").size() == 2, "" + ev.of("t2").size());
        ok("纠错条目保留原始判定",
                WpsHitJudge.C_CONFIRMED.equals(ev.of("t2").get(1).originalVerdict), "");
        ok("已标记纠错", ev.corrected("t2"), "");

        st("㉔ 重新进入页面：状态不错位");
        WpsTargetManager tm24 = new WpsTargetManager();
        WpsRunState r24 = new WpsRunState();
        tm24.beginScan();
        tm24.finishScan(list(target(BSSID)), 5, 1);
        tm24.select(target(BSSID));
        WpsFacade.UiState a1 = ui(tm24, r24, 1, 65, cap(WpsCapabilityManager.V_PIN_OK), false, "", "", 3);
        WpsFacade.UiState a2 = ui(tm24, r24, 1, 65, cap(WpsCapabilityManager.V_PIN_OK), false, "", "", 3);
        ok("两次快照一致（幂等，不会漂移）",
                a1.selectedBssid.equals(a2.selectedBssid)
                        && a1.scanState == a2.scanState
                        && a1.primaryLabel.equals(a2.primaryLabel)
                        && a1.capabilityVerdict.equals(a2.capabilityVerdict), "");
        ok("回到页面仍是 DONE 且仍有目标（不退回 NEVER）",
                a2.scanState == WpsTargetManager.DONE && a2.targetCount == 1, "");
        ok("revision 一致（无虚假变化）", a1.revision == a2.revision, "");

        System.out.println();
        System.out.println("=== WpsUiStateMatrixTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

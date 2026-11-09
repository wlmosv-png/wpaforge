package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标与扫描状态机专项（纯逻辑）。
 *
 * <p>覆盖任务书要求：扫描完成 / 选择目标 / 目标消失 / 重新扫描 都必须正确刷新。
 * 全部断言调用生产逻辑 {@link WpsTargetManager}。
 */
public final class WpsTargetManagerTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static WpsController.Target t(String bssid) {
        WpsController.Target x = new WpsController.Target();
        x.bssid = bssid;
        x.ssid = "AP-" + bssid.substring(bssid.length() - 2);
        return x;
    }

    private static List<WpsController.Target> list(WpsController.Target... ts) {
        List<WpsController.Target> l = new ArrayList<>();
        for (WpsController.Target x : ts) l.add(x);
        return l;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 目标与扫描状态机 ===");

        // -------------------------------------------------- 1 初始
        info("① 初始 NEVER");
        WpsTargetManager m = new WpsTargetManager();
        WpsTargetManager.Snapshot s0 = m.snapshot();
        ok("scanState=NEVER", s0.scanState == WpsTargetManager.NEVER, "" + s0.scanState);
        ok("shouldPromptScan", s0.shouldPromptScan(), "");
        ok("无目标", !s0.hasTargets(), "");
        ok("未选中", m.selected() == null, "");

        // -------------------------------------------------- 2 扫描中
        info("② beginScan → SCANNING（不清列表）");
        long rev0 = m.revision();
        m.beginScan();
        ok("isScanning", m.isScanning(), "");
        ok("revision 自增", m.revision() > rev0, "");

        // -------------------------------------------------- 3 扫描完成
        info("③ finishScan → DONE + 列表替换 + 计数");
        m.finishScan(list(t("aa:bb:cc:dd:ee:01"), t("aa:bb:cc:dd:ee:02")), 12, 2);
        WpsTargetManager.Snapshot s1 = m.snapshot();
        ok("DONE", s1.scanState == WpsTargetManager.DONE, "" + s1.scanState);
        ok("targetCount=2", s1.targetCount == 2, "" + s1.targetCount);
        ok("rawCount=12", s1.rawCount == 12, "" + s1.rawCount);
        ok("wpsCount=2", s1.wpsCount == 2, "" + s1.wpsCount);
        ok("lastScanAt>0", s1.lastScanAtMs > 0, "");
        ok("不再提示扫描", !s1.shouldPromptScan(), "");
        ok("有目标", s1.hasTargets(), "");

        // -------------------------------------------------- 4 选择
        info("④ 选择目标 + 修订号");
        long rev1 = m.revision();
        boolean ch = m.select(t("aa:bb:cc:dd:ee:02"));
        ok("选择发生变化", ch, "");
        ok("revision 自增", m.revision() > rev1, "");
        ok("selected 正确", "aa:bb:cc:dd:ee:02".equals(m.selected().bssid), "");
        ok("重复选同一个 → 无变化", !m.select(t("aa:bb:cc:dd:ee:02")), "");

        // -------------------------------------------------- 5 目标消失
        info("⑤ 目标消失（重新扫描后选中项不在列表）→ 选择被清空");
        m.finishScan(list(t("aa:bb:cc:dd:ee:01"), t("aa:bb:cc:dd:ee:03")), 8, 2);
        ok("选中被清空", m.selected() == null, String.valueOf(m.selected()));
        ok("selectedBssid 为空", m.selectedBssid().isEmpty(), m.selectedBssid());

        // -------------------------------------------------- 6 重新扫描
        info("⑥ 重新扫描 → SCANNING → DONE");
        m.select(t("aa:bb:cc:dd:ee:01"));
        ok("已选中", m.selected() != null, "");
        m.beginScan();
        ok("SCANNING", m.isScanning(), "");
        ok("扫描中不清选中", m.selected() != null, "");
        m.finishScan(list(t("aa:bb:cc:dd:ee:01")), 3, 1);
        ok("DONE", m.snapshot().scanState == WpsTargetManager.DONE, "");
        ok("选中仍在（列表里还有它）", m.selected() != null, "");

        // -------------------------------------------------- 7 失败
        info("⑦ failScan → FAILED（不替换列表）");
        int before = m.snapshot().targetCount;
        m.failScan("supplicant 通道异常");
        WpsTargetManager.Snapshot s7 = m.snapshot();
        ok("FAILED", s7.scanState == WpsTargetManager.FAILED, "" + s7.scanState);
        ok("failed()", s7.failed(), "");
        ok("错误原因保留", s7.lastError.contains("通道"), s7.lastError);
        ok("列表未被清空", s7.targetCount == before, "" + s7.targetCount);

        info("⑧ 失败后重新扫描会清掉错误原因");
        m.beginScan();
        ok("错误已清", m.lastError().isEmpty(), m.lastError());
        ok("SCANNING", m.isScanning(), "");

        // -------------------------------------------------- 8 空结果
        info("⑨ 扫描成功但 0 个目标 → emptyAfterScan");
        m.finishScan(list(), 0, 0);
        WpsTargetManager.Snapshot s9 = m.snapshot();
        ok("emptyAfterScan", s9.emptyAfterScan(), "");
        ok("不是 failed", !s9.failed(), "");

        // -------------------------------------------------- 9 活引用与 touch
        info("⑩ targets() 活引用 + touch() 对账");
        m.targets().add(t("aa:bb:cc:dd:ee:09"));
        long rev9 = m.revision();
        m.touch();
        ok("touch 推进修订号", m.revision() > rev9, "");
        ok("新加的目标可被选中", m.select(m.find("aa:bb:cc:dd:ee:09")), "");

        info("⑪ clearTargets 清空列表与选择");
        m.clearTargets();
        ok("列表空", m.snapshot().targetCount == 0, "");
        ok("选择空", m.selected() == null, "");

        System.out.println();
        System.out.println("=== WpsTargetManagerTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

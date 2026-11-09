package io.github.wlmosv_png.wpaforge.wps;

import java.io.File;

/**
 * 第五轮任务书专项：纠错保守化 / 无身份缺省不默认同目标 / 纠错持久化 / 旧 HIT 不复活。
 *
 * <p>全部调用生产逻辑（{@link WpsEngine}、{@link WpsLedger}、{@link WpsLedgerStore}），
 * 测试侧不复制判定或编解码实现。
 */
public final class WpsCorrectionAuditTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 造一个 HIT 会话（已判命中）。 */
    private static WpsEngine.Session hitSession(String taskId, String ssid, String bssid) {
        WpsEngine.Session s = new WpsEngine.Session();
        s.taskId = taskId;
        s.verdict = WpsEngine.V_HIT;
        s.hitVerdict = WpsHitJudge.C_CONFIRMED;
        s.hitPin = "12345670";
        s.hitSsid = ssid;
        s.hitBssid = bssid;
        s.hitOnline = true;
        return s;
    }

    /** 造一条 NOT_TARGET 反证。 */
    private static WpsEngine.Attempt contra(String taskId, String targetSsid,
                                            String connSsid, String connBssid) {
        WpsController.Result r = new WpsController.Result();
        r.status = "SUCCESS";
        r.hitVerdict = WpsHitJudge.C_NOT_TARGET;
        r.targetSsid = targetSsid;
        r.hitSsid = connSsid;
        r.hitBssid = connBssid;
        WpsEngine.Attempt a = new WpsEngine.Attempt(r);
        a.taskId = taskId;
        return a;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 纠错保守化 / 持久化专项 ===");

        // ------------------------------------------------ 1 无身份缺省不默认同目标
        info("① 缺任务 ID 与目标身份 → 必须保守拒绝纠错");
        WpsEngine.Session s1 = hitSession("", "TARGET-AP", "a4:2b:b0:11:22:33");
        WpsEngine.observe(s1, contra("", "", "别的网", ""));
        ok("无任何共同身份字段 → 不纠错（仍 HIT）",
                WpsEngine.V_HIT.equals(s1.verdict), s1.verdict);
        ok("未标记纠错", !s1.hitCorrected, "");

        info("② 明确同目标（SSID 对得上）→ 允许纠错");
        WpsEngine.Session s2 = hitSession("", "TARGET-AP", "a4:2b:b0:11:22:33");
        WpsEngine.observe(s2, contra("", "TARGET-AP", "别的网", ""));
        ok("同 SSID → 纠错生效", s2.hitCorrected && !WpsEngine.V_HIT.equals(s2.verdict),
                s2.verdict);

        info("③ 明确同任务（taskId 对得上）→ 允许纠错");
        WpsEngine.Session s3 = hitSession("wps-1", "TARGET-AP", "a4:2b:b0:11:22:33");
        WpsEngine.observe(s3, contra("wps-1", "", "别的网", ""));
        ok("同任务 ID → 纠错生效", s3.hitCorrected, "");

        info("④ 任务 ID 明确冲突 → 拒绝（旧任务事件）");
        WpsEngine.Session s4 = hitSession("wps-1", "TARGET-AP", "a4:2b:b0:11:22:33");
        WpsEngine.observe(s4, contra("wps-999", "TARGET-AP", "别的网", ""));
        ok("任务 ID 冲突 → 整条忽略（仍 HIT）",
                WpsEngine.V_HIT.equals(s4.verdict), s4.verdict);

        info("⑤ 幂等：重复反证不产生重复纠错");
        WpsEngine.Session s5 = hitSession("wps-1", "TARGET-AP", "a4:2b:b0:11:22:33");
        WpsEngine.observe(s5, contra("wps-1", "TARGET-AP", "别的网", ""));
        String firstOrig = s5.hitOriginalVerdict;
        long firstAt = s5.hitCorrectedAtMs;
        WpsEngine.observe(s5, contra("wps-1", "TARGET-AP", "又一条别的网", ""));
        ok("原始判定不被第二次纠错覆盖",
                firstOrig.equals(s5.hitOriginalVerdict), s5.hitOriginalVerdict);
        ok("纠错时刻不变（幂等）", firstAt == s5.hitCorrectedAtMs, "");

        info("⑥ 纠错后原始证据保留");
        ok("原始 PIN 保留", "12345670".equals(s5.hitOriginalPin), s5.hitOriginalPin);
        ok("原始判定保留", WpsHitJudge.C_CONFIRMED.equals(s5.hitOriginalVerdict),
                s5.hitOriginalVerdict);
        ok("修正结果记录", WpsHitJudge.C_NOT_TARGET.equals(s5.hitCorrectedVerdict),
                s5.hitCorrectedVerdict);

        // ------------------------------------------------ 2 持久化往返
        info("⑦ 纠错结果写入台账并读回（模拟重启）");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "wpaforge-corr-" + System.nanoTime());
        dir.mkdirs();
        File f = new File(dir, "ledger.txt");
        WpsLedgerStore store = new WpsLedgerStore(f);

        WpsLedger.Task t = new WpsLedger.Task();
        t.taskId = "wps-2233-1";
        t.bssid = "a4:2b:b0:11:22:33";
        t.ssid = "TARGET-AP";
        t.status = WpsLedger.ST_DONE;
        t.corrected = true;
        t.originalVerdict = WpsHitJudge.C_CONFIRMED;
        t.originalPin = "12345670";
        t.correctedVerdict = WpsHitJudge.C_NOT_TARGET;
        t.correctionReason = "更强反证";
        t.correctedAtMs = 1699999999999L;
        t.hitPin = "";            // 纠错后不再是命中
        t.hitVerdict = WpsHitJudge.C_NOT_TARGET;
        ok("写台账成功", store.upsert(t), "");

        WpsLedgerStore store2 = new WpsLedgerStore(f);   // 模拟重启：新实例读同一文件
        java.util.List<WpsLedger.Task> back = store2.load();
        ok("读回 1 条", back.size() == 1, "size=" + back.size());
        if (back.size() == 1) {
            WpsLedger.Task r = back.get(0);
            ok("corrected 读回", r.corrected, "");
            ok("originalVerdict 读回", WpsHitJudge.C_CONFIRMED.equals(r.originalVerdict),
                    r.originalVerdict);
            ok("originalPin 读回", "12345670".equals(r.originalPin), r.originalPin);
            ok("correctedVerdict 读回", WpsHitJudge.C_NOT_TARGET.equals(r.correctedVerdict),
                    r.correctedVerdict);
            ok("correctedAtMs 读回", r.correctedAtMs == 1699999999999L, "" + r.correctedAtMs);
            ok("纠错后 hasHit=false（旧 HIT 不复活）", !r.hasHit(), r.hitPin);
        }

        // ------------------------------------------------ 3 向后兼容
        info("⑧ 旧格式记录（无纠错键）仍可读，默认 corrected=false");
        File f2 = new File(dir, "old.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(f2)) {
            w.write("WPS_TASK v=1 schema=1 taskId=old-1 bssid=a4%3A2b%3Ab0%3A11%3A22%3A33 "
                    + "ssid=OLD status=DONE hitPin=99999999 hitVerdict=HIT_CONFIRMED\n");
        }
        java.util.List<WpsLedger.Task> old = new WpsLedgerStore(f2).load();
        ok("旧记录可读", old.size() == 1, "size=" + old.size());
        if (old.size() == 1) {
            ok("旧记录 corrected 默认 false", !old.get(0).corrected, "");
            ok("旧记录 hitPin 保留", old.get(0).hasHit(), "");
        }

        // 清理
        try {
            new File(dir, "ledger.txt").delete();
            new File(dir, "ledger.txt.bak").delete();
            new File(dir, "old.txt").delete();
            new File(dir, "old.txt.bak").delete();
            dir.delete();
        } catch (Throwable ignored) {
        }

        System.out.println();
        System.out.println("=== WpsCorrectionAuditTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

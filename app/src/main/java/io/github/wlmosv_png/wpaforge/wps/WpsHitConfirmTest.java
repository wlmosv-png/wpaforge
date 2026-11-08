package io.github.wlmosv_png.wpaforge.wps;

/**
 * 第二阶段专项：**600ms 二次确认必须真的进证据体系**。
 *
 * <h3>要挡住的真实事故</h3>
 * 修复前 {@code hitConfirm}（连上 600ms 后再查一次状态）**采集了但不参与判定**，
 * 只往 {@code /data/local/tmp/wpaforge-hit.txt} 写一行。后果：
 * 一次只稳了半秒、马上就掉的连接，照样在界面上显示「已命中」，
 * 而用户拿到的 PIN 拿去连目标连不上 —— 证据就在手里，却没人看。
 *
 * <p>现在：{@code confirm == 0} 会把结论降级成「疑似」（记下来、不判命中、不停任务）；
 * {@code confirm == -1}（这次没读到）**不降级** —— 读不到复核不等于复核失败，
 * 不能因为一次读取失败就把协议确认过的 PIN 丢掉。
 *
 * <p>全部断言调用生产逻辑。
 */
public final class WpsHitConfirmTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static WpsHitJudge.In base(int confirm) {
        WpsHitJudge.In in = new WpsHitJudge.In("a4:2b:b0:11:22:33", "TARGET-AP", 3, 9,
                "COMPLETED", "WPA2-PSK");
        in.connSsid = "TARGET-AP";
        in.connBssid = "a4:2b:b0:11:22:33";
        in.connIp = "192.168.1.5";
        in.confirm = confirm;
        in.sawTargetWpsSession = true;
        return in;
    }

    private static WpsHelper.Stats stats(int confirm) {
        WpsHelper.Stats st = new WpsHelper.Stats();
        st.targetBssid = "a4:2b:b0:11:22:33";
        st.targetSsid = "TARGET-AP";
        st.baseNetId = 3;
        st.hitNetId = 9;
        st.hitState = "COMPLETED";
        st.hitKeyMgmt = "WPA2-PSK";
        st.hitSsid = "TARGET-AP";
        st.hitBssid = "a4:2b:b0:11:22:33";
        st.hitIp = "192.168.1.5";
        st.hitConfirm = confirm;
        st.sawTargetWpsSession = true;
        WpsHitJudge.In ev = WpsHelper.evidenceOf(st);
        st.hitVerdict = WpsHitJudge.judge(ev);
        st.hitFacts = WpsHitJudge.facts(ev);
        st.hitWhy = WpsHitJudge.why(ev, false);
        return st;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 二次确认（600ms）专项 ===");

        info("① confirm=1（复核时仍在线）→ 命中");
        ok("confirm=1 → CONFIRMED", WpsHitJudge.isHit(WpsHitJudge.judge(base(1))),
                WpsHitJudge.judge(base(1)));

        info("② confirm=0（600ms 内就掉了）→ 只能算疑似");
        String v0 = WpsHitJudge.judge(base(0));
        ok("confirm=0 → UNVERIFIED", WpsHitJudge.C_UNVERIFIED.equals(v0), v0);
        WpsHitJudge.In in0 = base(0);
        ok("confirm=0 不得给 PROTOCOL 事实",
                !WpsHitJudge.hasFact(WpsHitJudge.facts(in0), WpsHitJudge.F_PROTOCOL),
                WpsHitJudge.facts(in0));
        ok("confirm=0 仍算「已交付凭据」（配置建立过）",
                WpsHitJudge.hasFact(WpsHitJudge.facts(in0), WpsHitJudge.F_CREDENTIAL),
                WpsHitJudge.facts(in0));
        ok("原因可读（含 600ms 字样）",
                WpsHitJudge.why(in0, true).contains("600"), WpsHitJudge.why(in0, true));

        info("③ confirm=-1（这次没读到复核）→ 不得因此降级");
        String vUnknown = WpsHitJudge.judge(base(-1));
        ok("confirm=-1 → 仍判命中（读不到 ≠ 失败）", WpsHitJudge.isHit(vUnknown), vUnknown);

        info("③b 复核值的产生口径（读不到 ≠ 掉了）");
        ok("空回复 → -1（不得当失败）", WpsHelper.confirmOf("") == -1, "" + WpsHelper.confirmOf(""));
        ok("null → -1", WpsHelper.confirmOf(null) == -1, "" + WpsHelper.confirmOf(null));
        ok("仍在线 → 1",
                WpsHelper.confirmOf("wpa_state=COMPLETED\nkey_mgmt=WPA2-PSK") == 1,
                "" + WpsHelper.confirmOf("wpa_state=COMPLETED\nkey_mgmt=WPA2-PSK"));
        ok("仍在 WPS → 0",
                WpsHelper.confirmOf("wpa_state=COMPLETED\nkey_mgmt=WPS") == 0, "");
        ok("已断开 → 0",
                WpsHelper.confirmOf("wpa_state=SCANNING\nkey_mgmt=WPA2-PSK") == 0, "");
        ok("空回复经判定 → 仍算命中（不降级）",
                WpsHitJudge.isHit(WpsHitJudge.judge(base(WpsHelper.confirmOf("")))), "");

        info("④ 生产链路：证据行 → App 解析");
        String line0 = WpsHelper.evidenceLine(stats(0), "12345670");
        info("    " + line0);
        WpsController.Result r0 = WpsController.parseBatchLine(
                "PIN=12345670 RESULT=SUCCESS_UNVERIFIED");
        WpsController.applyEvidence(r0, line0);
        ok("解析出 confirm=0", r0.hitConfirm == 0, "" + r0.hitConfirm);
        ok("解析出 UNVERIFIED", WpsHitJudge.C_UNVERIFIED.equals(r0.hitVerdict), r0.hitVerdict);
        ok("result.isUnverified()", r0.isUnverified(), r0.status);
        ok("result 带证据", r0.hasHitEvidence(), r0.hitVerdict);

        info("⑤ 引擎：疑似命中不得判 HIT、不得停任务、不得占 hitPin");
        WpsEngine.Session s = new WpsEngine.Session();
        WpsEngine.observe(s, new WpsEngine.Attempt(r0));
        ok("不判 HIT", !WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("不停任务", !WpsEngine.shouldStop(s), s.verdict);
        ok("hitPin 保持空", s.hitPin == null, String.valueOf(s.hitPin));
        ok("疑似计数 = 1", s.suspectCount == 1, "" + s.suspectCount);
        ok("疑似 PIN 被记下", "12345670".equals(s.suspectPin), s.suspectPin);
        ok("疑似原因非空", s.suspectNote != null && !s.suspectNote.isEmpty(), s.suspectNote);

        info("⑥ 疑似之后，真命中仍然能判出来（不丢真结果）");
        String line1 = WpsHelper.evidenceLine(stats(1), "99999999");
        WpsController.Result r1 = WpsController.parseBatchLine("PIN=99999999 RESULT=SUCCESS");
        WpsController.applyEvidence(r1, line1);
        WpsEngine.observe(s, new WpsEngine.Attempt(r1));
        ok("随后确证命中 → HIT", WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("命中 PIN 正确", "99999999".equals(s.hitPin), s.hitPin);
        ok("疑似计数保留（证据不丢）", s.suspectCount == 1, "" + s.suspectCount);

        info("⑦ 命中是终态：后续失败事件不得改口（第二阶段审计 G6）");
        WpsEngine.observe(s, new WpsEngine.Attempt("00000000", "NO_RESPONSE", 9000, 0, 0));
        WpsEngine.observe(s, new WpsEngine.Attempt("00000001", "PIN_REJECTED", 8000, 1, 1200, true));
        ok("失败事件后仍是 HIT", WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("命中 PIN 未被覆盖", "99999999".equals(s.hitPin), s.hitPin);
        ok("证据摘要仍可读", WpsEngine.evidenceSummary(s).contains("TARGET-AP"),
                WpsEngine.evidenceSummary(s));

        System.out.println();
        System.out.println("=== WpsHitConfirmTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

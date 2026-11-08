package io.github.wlmosv_png.wpaforge.wps;

/**
 * 命中结论的生命周期：确证后不改口、证据不足不停任务、别的网络不冒充命中。
 *
 * <h3>要挡住的事故<\/h3>
 * ① 「命中」被后续失败事件改回「进行中」—— 界面从「已命中（PIN=xxx）」
 * 退回「进行中」用户会以为程序抽风，更糟的是有人会因此把 PIN 丢掉。
 * ② 证据不足（600ms 内就掉了 / 归属读不到）被判成命中 → 用户拿这条 PIN 连不上目标。
 * ③ 「连到了别的网络」被记成命中 → 这是历史上最典型的假阳性，
 * 而且它必须进 notTargetCount 单独计数，不能和「证据不足」混成一个数。
 * ④ 兼容路径（老格式输出没有证据字段）里，SUCCESS 不能再也不认。
 *
 * <p>全部断言调用 {@link WpsEngine} / {@link WpsHitJudge} / {@link WpsController.Result}
 * 生产逻辑，本类只负责构造输入。
 */
public final class WpsVerdictLifecycleTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   " + detail);
        }
    }

    private static void info(String s) {
        System.out.println("  ·    " + s);
    }

    private static final String TARGET_BSSID = "a4:2b:b0:11:22:33";
    private static final String TARGET_SSID = "TARGET-AP";

    /** 造一份「helper 侧采集到的事实」；判定全部交给生产代码。 */
    private static WpsHelper.Stats stats(String connSsid, String connBssid, String connIp, int confirm) {
        WpsHelper.Stats st = new WpsHelper.Stats();
        st.targetBssid = TARGET_BSSID;
        st.targetSsid = TARGET_SSID;
        st.baseNetId = 3;
        st.hitNetId = 9;
        st.hitState = "COMPLETED";
        st.hitKeyMgmt = "WPA2-PSK";
        st.hitSsid = connSsid;
        st.hitBssid = connBssid;
        st.hitIp = connIp;
        st.hitConfirm = confirm;
        st.sawTargetWpsSession = true;
        WpsHitJudge.In ev = WpsHelper.evidenceOf(st);
        st.hitVerdict = WpsHitJudge.judge(ev);
        st.hitFacts = WpsHitJudge.facts(ev);
        st.hitWhy = WpsHitJudge.why(ev, false);
        return st;
    }

    /** 走完整生产链：批处理行 → 证据行合并 → Result。 */
    private static WpsController.Result resultOf(String pin, String status, WpsHelper.Stats st) {
        WpsController.Result r = WpsController.parseBatchLine("PIN=" + pin + " RESULT=" + status);
        WpsController.applyEvidence(r, WpsHelper.evidenceLine(st, pin));
        return r;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 命中结论生命周期 ===");

        info("① 确证命中后，失败事件不得改口");
        WpsController.Result rHit = resultOf("12345670", "SUCCESS",
                stats(TARGET_SSID, TARGET_BSSID, "192.168.1.5", 1));
        ok("链路判出 CONFIRMED", WpsHitJudge.C_CONFIRMED.equals(rHit.hitVerdict), rHit.hitVerdict);
        ok("Result 认自己是成功", rHit.isSuccess(), rHit.status);

        WpsEngine.Session s = new WpsEngine.Session();
        WpsEngine.observe(s, new WpsEngine.Attempt(rHit));
        ok("判 HIT", WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("命中 PIN 正确", "12345670".equals(s.hitPin), s.hitPin);
        ok("命中后应停止任务", WpsEngine.shouldStop(s), s.verdict);

        WpsEngine.observe(s, new WpsEngine.Attempt("00000000", "NO_RESPONSE", 9000, 0, 0));
        ok("失败事件后仍是 HIT", WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        WpsEngine.observe(s, new WpsEngine.Attempt("00000001", "PIN_REJECTED", 8000, 1, 1200, true));
        ok("再次失败仍不改口", WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("hitPin 未被后续失败覆盖", "12345670".equals(s.hitPin), s.hitPin);

        info("② 命中后证据摘要仍可读");
        String ev = WpsEngine.evidenceSummary(s);
        info("    " + ev);
        ok("摘要非空", !ev.isEmpty(), ev);
        ok("摘要含目标 SSID", ev.contains(TARGET_SSID), ev);
        ok("摘要含结论", ev.contains(WpsHitJudge.C_CONFIRMED), ev);
        ok("摘要含联网事实（online=1）", ev.contains("online=1"), ev);
        ok("摘要含 netId 对比", ev.contains("netId=9") && ev.contains("base=3"), ev);
        ok("会话事实位含 PROTOCOL/ONLINE",
                WpsHitJudge.hasFact(s.hitFacts, WpsHitJudge.F_PROTOCOL)
                        && WpsHitJudge.hasFact(s.hitFacts, WpsHitJudge.F_ONLINE), s.hitFacts);

        info("③ 证据不足（600ms 内掉线）→ 不停任务、不占 hitPin");
        WpsController.Result rSus = resultOf("11112222", "SUCCESS_UNVERIFIED",
                stats(TARGET_SSID, TARGET_BSSID, "192.168.1.5", 0));
        ok("confirm=0 → UNVERIFIED", WpsHitJudge.C_UNVERIFIED.equals(rSus.hitVerdict),
                rSus.hitVerdict);
        ok("Result 认自己是未验证", rSus.isUnverified(), rSus.status);
        WpsEngine.Session s3 = new WpsEngine.Session();
        WpsEngine.observe(s3, new WpsEngine.Attempt(rSus));
        ok("疑似不判 HIT", !WpsEngine.V_HIT.equals(s3.verdict), s3.verdict);
        ok("疑似不停任务", !WpsEngine.shouldStop(s3), s3.verdict);
        ok("疑似不占 hitPin", s3.hitPin == null, String.valueOf(s3.hitPin));
        ok("疑似计数 = 1", s3.suspectCount == 1, "" + s3.suspectCount);
        ok("疑似原因非空", s3.suspectNote != null && !s3.suspectNote.isEmpty(), s3.suspectNote);
        ok("非命中会话没有证据摘要", WpsEngine.evidenceSummary(s3).isEmpty(),
                WpsEngine.evidenceSummary(s3));

        info("④ 「连到别的网络」→ NOT_TARGET，计入 notTargetCount");
        WpsController.Result rOther = resultOf("12345670", "SUCCESS_UNVERIFIED",
                stats("我家WiFi", "aa:bb:cc:dd:ee:01", "192.168.1.9", 1));
        ok("判出 NOT_TARGET", WpsHitJudge.C_NOT_TARGET.equals(rOther.hitVerdict),
                rOther.hitVerdict);
        WpsEngine.Session s4 = new WpsEngine.Session();
        WpsEngine.observe(s4, new WpsEngine.Attempt(rOther));
        ok("NOT_TARGET 不判 HIT", !WpsEngine.V_HIT.equals(s4.verdict), s4.verdict);
        ok("notTargetCount = 1", s4.notTargetCount == 1, "" + s4.notTargetCount);
        ok("NOT_TARGET 不混进疑似计数", s4.suspectCount == 0, "" + s4.suspectCount);
        ok("NOT_TARGET 不占 hitPin", s4.hitPin == null, String.valueOf(s4.hitPin));
        ok("NOT_TARGET 不停任务", !WpsEngine.shouldStop(s4), s4.verdict);

        info("⑤ 兼容：没有证据字段的 SUCCESS 仍判命中");
        WpsController.Result rLegacy = WpsController.parseBatchLine("PIN=99998888 RESULT=SUCCESS");
        ok("老格式没有证据字段", !rLegacy.hasHitEvidence(), rLegacy.hitVerdict);
        WpsEngine.Session s5 = new WpsEngine.Session();
        WpsEngine.observe(s5, new WpsEngine.Attempt(rLegacy));
        ok("无证据的 SUCCESS 仍判 HIT", WpsEngine.V_HIT.equals(s5.verdict), s5.verdict);
        ok("命中 PIN 正确", "99998888".equals(s5.hitPin), s5.hitPin);
        String evLegacy = WpsEngine.evidenceSummary(s5);
        ok("老格式摘要标注 legacy", evLegacy.contains("legacy"), evLegacy);

        info("⑥ 疑似之后真命中仍能正常判出");
        WpsEngine.Session s6 = new WpsEngine.Session();
        WpsEngine.observe(s6, new WpsEngine.Attempt(resultOf("11112222", "SUCCESS_UNVERIFIED",
                stats(TARGET_SSID, TARGET_BSSID, "192.168.1.5", 0))));
        ok("先记疑似、任务继续", WpsEngine.V_RUNNING.equals(s6.verdict) && s6.suspectCount == 1,
                s6.verdict + "/" + s6.suspectCount);
        WpsEngine.observe(s6, new WpsEngine.Attempt(resultOf("12345670", "SUCCESS",
                stats(TARGET_SSID, TARGET_BSSID, "192.168.1.5", 1))));
        ok("随后真命中 → HIT", WpsEngine.V_HIT.equals(s6.verdict), s6.verdict);
        ok("命中 PIN 是后一条", "12345670".equals(s6.hitPin), s6.hitPin);
        ok("疑似计数保留（证据不丢）", s6.suspectCount == 1, "" + s6.suspectCount);
        ok("命中后证据摘要可读",
                WpsEngine.evidenceSummary(s6).contains(TARGET_SSID), WpsEngine.evidenceSummary(s6));

        info("⑦ 引擎结论 → 台账任务：命中可落盘且不被误清");
        long now = 1700000000000L;
        WpsLedger.Task t = WpsLedger.start(WpsLedger.newTaskId(TARGET_BSSID, now),
                TARGET_BSSID, TARGET_SSID, 3, now);
        t.status = WpsLedger.ST_DONE;
        t.hitPin = s6.hitPin;
        t.hitVerdict = s6.hitVerdict;
        t.evidence = WpsEngine.evidenceSummary(s6);
        t.hitOnline = s6.hitOnline;
        t.doneCount = 137;
        WpsLedger.Task back = WpsLedger.Task.decode(t.encode());
        ok("任务可编解码", back != null, "");
        ok("hasHit 成立", back != null && back.hasHit(), "");
        ok("hitPin 往返", back != null && "12345670".equals(back.hitPin),
                back == null ? "null" : back.hitPin);
        ok("证据摘要往返（含空格也不碎）",
                back != null && back.evidence.contains(TARGET_SSID) && back.evidence.contains("online=1"),
                back == null ? "null" : back.evidence);
        ok("命中时联网事实往返", back != null && back.hitOnline, "");
        ok("hits()/latestHit 认这条",
                WpsLedger.hits(java.util.Collections.singletonList(back)).size() == 1
                        && WpsLedger.latestHit(java.util.Collections.singletonList(back)) != null, "");

        System.out.println();
        System.out.println("=== WpsVerdictLifecycleTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

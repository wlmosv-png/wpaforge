package io.github.wlmosv_png.wpaforge.wps;

/**
 * 第四轮任务书专项：证据五分层 / 复核三态 / HIT 纠错 / 身份与因果分离。
 *
 * <h3>本测试要守住的原则</h3>
 * <ul>
 *   <li>观察到 WPS 会话 ≠ 协议成功；协议终局读不到就保持 UNKNOWN，不自行推断；</li>
 *   <li>复核三态：PASS / FAIL / UNKNOWN；UNKNOWN 不等于 PASS，也不抹除已成立的事实；</li>
 *   <li>HIT 终态不被普通失败覆盖，但更强的目标不匹配反证可以触发纠错，且原始证据保留；</li>
 *   <li>逻辑身份（SSID）与物理身份（BSSID）分离；新 netId 不等于「我建的这个配置」。</li>
 * </ul>
 *
 * <p>全部断言调用**生产逻辑**（{@link WpsHitJudge}、{@link WpsEngine}、
 * {@link WpsHelper}、{@link WpsController}），测试侧不复制判定实现。
 */
public final class WpsEvidenceLayerTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 造一次「像成功」的观察。 */
    private static WpsHitJudge.In obs(String connSsid, String connBssid, int confirm,
                                      boolean sawTargetSession) {
        WpsHitJudge.In in = new WpsHitJudge.In("a4:2b:b0:11:22:33", "TARGET-AP", 3, 9,
                "COMPLETED", "WPA2-PSK");
        in.connSsid = connSsid;
        in.connBssid = connBssid;
        in.connIp = "192.168.1.5";
        in.confirm = confirm;
        in.sawTargetWpsSession = sawTargetSession;
        return in;
    }

    public static void main(String[] a) {
        System.out.println("=== 证据五分层 / 复核三态 / HIT 纠错 / 身份因果 ===");

        // ============================================================ P0-1 证据分层
        info("① 观察到 WPS 会话 ≠ 协议成功");
        WpsHitJudge.In sess = obs("TARGET-AP", "a4:2b:b0:11:22:33",
                WpsHitJudge.CONFIRM_PASS, true);
        String f1 = WpsHitJudge.facts(sess);
        info("    facts=" + f1);
        ok("产生 WPS_SESSION_OBSERVED", WpsHitJudge.hasFact(f1, WpsHitJudge.F_WPS_SESSION_OBSERVED), f1);
        ok("产生 TARGET_SESSION_ATTRIBUTED", WpsHitJudge.hasFact(f1, WpsHitJudge.F_TARGET_SESSION_ATTRIBUTED), f1);
        ok("不产生 WPS_TERMINAL_SUCCESS（读不到终局就该 UNKNOWN）",
                !WpsHitJudge.hasFact(f1, WpsHitJudge.F_WPS_TERMINAL_SUCCESS), f1);
        ok("terminalSuccessKnown 恒 false（不伪造）", !WpsHitJudge.terminalSuccessKnown(sess), "");

        info("② 五层各自独立：顶层缺失不影响底层成立");
        ok("新配置出现（CREDENTIAL_CONFIG_CREATED）",
                WpsHitJudge.hasFact(f1, WpsHitJudge.F_CREDENTIAL_CONFIG_CREATED), f1);
        ok("目标连接已确认（CONFIRM=PASS 才给）",
                WpsHitJudge.hasFact(f1, WpsHitJudge.F_TARGET_CONNECTION_CONFIRMED), f1);

        // ============================================================ P0-2 复核三态
        info("③ confirm=-1（未知）→ 不产生「连接已确认」，但保留已成立的事实");
        WpsHitJudge.In unknown = obs("TARGET-AP", "a4:2b:b0:11:22:33",
                WpsHitJudge.CONFIRM_UNKNOWN, true);
        String fu = WpsHitJudge.facts(unknown);
        info("    facts=" + fu);
        ok("含 CONFIRM_UNKNOWN 事实", WpsHitJudge.hasFact(fu, WpsHitJudge.F_CONFIRM_UNKNOWN), fu);
        ok("UNKNOWN 不给「连接已确认」",
                !WpsHitJudge.hasFact(fu, WpsHitJudge.F_TARGET_CONNECTION_CONFIRMED), fu);
        ok("UNKNOWN 仍保留已成立的 CREDENTIAL_CONFIG_CREATED（不抹除）",
                WpsHitJudge.hasFact(fu, WpsHitJudge.F_CREDENTIAL_CONFIG_CREATED), fu);

        info("④ confirm 三态文案");
        ok("PASS 文案", "PASS".equals(WpsHitJudge.confirmState(WpsHitJudge.CONFIRM_PASS)), "");
        ok("FAIL 文案", "FAIL".equals(WpsHitJudge.confirmState(WpsHitJudge.CONFIRM_FAIL)), "");
        ok("UNKNOWN 文案（绝不写成 PASS）",
                "UNKNOWN".equals(WpsHitJudge.confirmState(WpsHitJudge.CONFIRM_UNKNOWN)), "");

        // ============================================================ P0-4 身份与因果
        info("⑤ 逻辑身份（SSID）与物理身份（BSSID）分离");
        WpsHitJudge.In roam = obs("TARGET-AP", "a4:2b:b0:11:22:2c", WpsHitJudge.CONFIRM_PASS, true);
        ok("同 SSID → 逻辑身份一致", WpsHitJudge.logicalIdentityMatches(roam), "");
        ok("不同 BSSID → 物理身份不一致", !WpsHitJudge.physicalIdentityMatches(roam), "");
        WpsHitJudge.In sameBssid = obs("TARGET-AP", "a4:2b:b0:11:22:33", WpsHitJudge.CONFIRM_PASS, true);
        ok("同 BSSID → 物理身份一致", WpsHitJudge.physicalIdentityMatches(sameBssid), "");

        info("⑥ 新 netId ≠ 本次任务创建的配置");
        ok("causalityProven 恒 false（无任务前后快照 + 任务 ID + 时间线联合证据）",
                !WpsHitJudge.causalityProven(sameBssid), "");

        // ============================================================ P0-3 HIT 纠错
        info("⑦ HIT 终态：普通失败不覆盖；更强反证可纠错");
        WpsEngine.Session s = new WpsEngine.Session();
        s.taskId = "wps-2233-1";
        WpsController.Result hit = new WpsController.Result();
        hit.pin = "12345670";
        hit.status = "SUCCESS";
        hit.hitVerdict = WpsHitJudge.C_CONFIRMED;
        hit.hitIp = "192.168.1.5";
        hit.hitConfirm = WpsHitJudge.CONFIRM_PASS;
        hit.hitSsid = "TARGET-AP";
        hit.hitBssid = "a4:2b:b0:11:22:33";
        hit.targetSsid = "TARGET-AP";
        hit.sawTargetWpsSession = true;
        WpsEngine.observe(s, new WpsEngine.Attempt(hit));
        ok("先判 HIT", WpsEngine.V_HIT.equals(s.verdict), s.verdict);

        WpsEngine.observe(s, new WpsEngine.Attempt("00000000", "NO_RESPONSE", 9000, 0, 0));
        WpsEngine.observe(s, new WpsEngine.Attempt("00000001", "PIN_REJECTED", 8000, 1, 1200, true));
        ok("普通失败后仍是 HIT（终态保护仍在）", WpsEngine.V_HIT.equals(s.verdict), s.verdict);

        info("⑧ 旧任务事件不得改动新任务结果");
        WpsController.Result stale = new WpsController.Result();
        stale.status = "NO_RESPONSE";
        stale.hitVerdict = WpsHitJudge.C_NOT_TARGET;
        stale.hitSsid = "别的网";
        stale.targetSsid = "别的目标";        // 与当前会话目标不同 → 属别的任务
        WpsEngine.Attempt at = new WpsEngine.Attempt(stale);
        at.taskId = "wps-9999-2";             // 任务 ID 也不同
        WpsEngine.observe(s, at);
        ok("旧任务反证被忽略（仍是 HIT）", WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("未发生纠错", !s.hitCorrected, "");

        info("⑨ 同目标/同任务的更强反证 → 撤销命中，且原始证据保留");
        WpsController.Result contra = new WpsController.Result();
        contra.status = "SUCCESS";            // 状态看似成功
        contra.hitVerdict = WpsHitJudge.C_NOT_TARGET;
        contra.hitSsid = "别的网";
        contra.targetSsid = "TARGET-AP";      // 与当前会话同目标
        WpsEngine.Attempt at2 = new WpsEngine.Attempt(contra);
        at2.taskId = "wps-2233-1";            // 同任务
        WpsEngine.observe(s, at2);
        ok("命中被撤销（不再 HIT）", !WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("已发生纠错", s.hitCorrected, "");
        ok("原始判定保留", WpsHitJudge.C_CONFIRMED.equals(s.hitOriginalVerdict), s.hitOriginalVerdict);
        ok("原始 PIN 保留", "12345670".equals(s.hitOriginalPin), s.hitOriginalPin);
        ok("最新修正结果记录", WpsHitJudge.C_NOT_TARGET.equals(s.hitCorrectedVerdict),
                s.hitCorrectedVerdict);
        ok("纠错原因可读", !s.hitCorrectionReason.isEmpty(), s.hitCorrectionReason);
        ok("纠错摘要非空", !WpsEngine.correctionSummary(s).isEmpty(), "");

        System.out.println();
        System.out.println("=== WpsEvidenceLayerTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

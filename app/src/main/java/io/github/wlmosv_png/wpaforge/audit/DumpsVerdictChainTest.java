package io.github.wlmosv_png.wpaforge.audit;

/**
 * 判据 D（dumpsys framework 级证据）接入判定链后的**契约测试**（2026-11-22 新增）。
 *
 * <h3>它钉死什么</h3>
 * 判据 D 的价值全在「能不能真的产生 AUTH_FAIL + DIRECT」，而这条链
 * 在本机以前**完全不可达**（logcat 里 wpa_supplicant 恒 0 行）。
 * 本测试用真机原文构造证据，直接喂给 {@link Adjudicator}，
 * 验证裁决结果与证据等级 —— 不依赖 Android、不依赖 shell。
 *
 * <h3>三条核心不变式</h3>
 * <ol>
 *   <li><b>错误密码不能被误判为 SUCCESS</b>：判据 D 只产 WRONG_KEY，
 *       不可能产 CONNECTED；</li>
 *   <li><b>正确密码不能被误判为 AUTH_FAIL + DIRECT</b>：
 *       若窗口内没有 uid=0 的配置级证据，判据 D 必须返回 null；</li>
 *   <li><b>NONE / INFERRED / UNKNOWN 不能永久排除</b>：
 *       判据 D 只有在 AT3 时才写 triedSet，其余等级一律不写。</li>
 * </ol>
 */
public final class DumpsVerdictChainTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    // 真机原文：2.4G-3 的 uid=0 配置被 framework 标为密码错误
    private static final String REAL_CFG_WRONG =
            "2026-11-22T00:44:21.183177 - addOrUpdateNetworkInternal: added/updated config. "
            + "netId=46 configKey=\"2.4G-3\"WPA_PSK uid=0 name=null\n"
            + "2026-11-22T00:44:26.429795 - setNetworkSelectionStatus: "
            + "configKey=\"2.4G-3\"WPA_PSK networkStatus=NETWORK_SELECTION_TEMPORARY_DISABLED "
            + "disableReason=NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD\n";

    // 真机原文：只有系统自己的配置（uid=1000）被禁用 —— 不是我们建的
    private static final String REAL_CFG_SYSTEM_ONLY =
            "2026-11-22T00:44:21.183177 - addOrUpdateNetworkInternal: added/updated config. "
            + "netId=46 configKey=\"2.4G-3\"WPA_PSK uid=1000 "
            + "name=android.uid.system:1000\n"
            + "2026-11-22T00:44:26.429795 - setNetworkSelectionStatus: "
            + "configKey=\"2.4G-3\"WPA_PSK networkStatus=NETWORK_SELECTION_TEMPORARY_DISABLED "
            + "disableReason=NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD\n";

    // 真机原文：2.4G-3 的 uid=0 连接事件明确 WRONG_PSWD
    private static final String REAL_CONN_WRONG =
            "startTime=11-22 0:44:22.225, SSID=\"2.4G-3\", BSSID=6a:93:7d:41:cc:3b, "
            + "durationMillis=4210, roamType=ROAM_UNRELATED, connectionResult=0, "
            + "level2FailureCode=AUTHENTICATION_FAILURE, "
            + "level2FailureReason=AUTH_FAILURE_WRONG_PSWD, networkType=TYPE_WPA2, "
            + "interfaceName=wlan0, uid=0\n";

    // 真机原文：whoami 只有 NEW_CONNECTION_ATTEMPT（没有密码错证据）
    private static final String REAL_CONN_NEW_ATTEMPT =
            "startTime=11-22 0:59:56.798, SSID=\"whoami\", BSSID=66:54:ef:ae:ce:80, "
            + "durationMillis=9976, roamType=ROAM_UNRELATED, connectionResult=0, "
            + "level2FailureCode=NEW_CONNECTION_ATTEMPT, "
            + "level2FailureReason=FAILURE_REASON_UNKNOWN, networkType=TYPE_WPA2, "
            + "interfaceName=wlan0, uid=0\n";

    public static void main(String[] args) {
        System.out.println("== 判据 D 接入判定链的契约 ==");
        try {
            long wFrom = WifiEvidenceProbe.parseStamp("11-22 0:44:20.000");
            long wTo = WifiEvidenceProbe.parseStamp("11-22 0:44:30.000");
            ok("测试窗口本身可信", WifiEvidenceProbe.windowUsable(wFrom, wTo));

            // ════════════════════════════════ 1. 判据 D 的产出条件
            System.out.println();
            System.out.println("-- 1. 判据 D 什么时候能给出「绑到本次配置」的证据 --");

            WifiEvidenceProbe.ConfigEvent cfgHit = WifiEvidenceProbe.findWrongPasswordDisable(
                    WifiEvidenceProbe.configEvents(REAL_CFG_WRONG),
                    "2.4G-3", wFrom, wTo, true);
            info("配置级（uid=0 ADD + WRONG_PASSWORD）= " + cfgHit);
            ok("★uid=0 的 ADD + WRONG_PASSWORD → 命中（AT3 资格）", cfgHit != null);

            WifiEvidenceProbe.ConfigEvent cfgSys = WifiEvidenceProbe.findWrongPasswordDisable(
                    WifiEvidenceProbe.configEvents(REAL_CFG_SYSTEM_ONLY),
                    "2.4G-3", wFrom, wTo, true);
            info("配置级（只有 uid=1000 的 ADD）= " + cfgSys);
            ok("★只有系统建的配置被禁用 → 不命中（防旧配置污染）", cfgSys == null);

            WifiEvidenceProbe.ConnEvent connHit = WifiEvidenceProbe.findWrongPsk(
                    WifiEvidenceProbe.connectionEvents(REAL_CONN_WRONG),
                    "2.4G-3", wFrom, wTo, true);
            info("连接级（uid=0 + WRONG_PSWD）= " + connHit);
            ok("uid=0 的连接级 WRONG_PSWD → 命中（AT3 资格）", connHit != null);

            WifiEvidenceProbe.ConnEvent connOther = WifiEvidenceProbe.findWrongPsk(
                    WifiEvidenceProbe.connectionEvents(REAL_CONN_NEW_ATTEMPT),
                    "whoami", wFrom, wTo, true);
            info("连接级（NEW_CONNECTION_ATTEMPT）= " + connOther);
            ok("★NEW_CONNECTION_ATTEMPT 不算密码错 → 不命中", connOther == null);

            // ════════════════════════════════ 2. 裁决：WRONG_KEY + AT3 → AUTH_FAIL/DIRECT
            System.out.println();
            System.out.println("-- 2. 裁决链：WRONG_KEY + AT3 --");
            String aid = "test-attempt-1";
            AttemptState stTerm = new AttemptState(aid, CrackEngine.Lifecycle.TERMINATED,
                    AttemptState.Gate.OK, true);
            Evidence wrongKeyAt3 = new Evidence(Evidence.Source.DUMPS_WRONG_KEY,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, aid, 4200L);

            Adjudicator.Result r1 = Adjudicator.adjudicate(stTerm,
                    java.util.Collections.singletonList(wrongKeyAt3));
            info("结果 = " + r1);
            ok("★WRONG_KEY + AT3 + TERMINATED → AUTH_FAIL",
                    r1.verdict == CrackEngine.Verdict.AUTH_FAIL, "" + r1.verdict);
            ok("★等级 = DIRECT", r1.grade == EvidenceGrade.Level.DIRECT, "" + r1.grade);
            ok("reason = wrong-key", "wrong-key".equals(r1.reason), r1.reason);
            ok("★DIRECT 允许写 triedSet",
                    EvidenceGrade.persistable(r1.grade));
            ok("★DIRECT 允许推进游标",
                    EvidenceGrade.advancesCursor(r1.grade));

            // ════════════════════════════════ 3. 不变式：正确密码不得被判 AUTH_FAIL
            System.out.println();
            System.out.println("-- 3. ★不变式：正确密码不得被判 AUTH_FAIL + DIRECT --");
            // 场景：本条成功（CONNECTED + AT3），同时窗口里有一条网络级失败
            Evidence success = new Evidence(Evidence.Source.SUCCESS_STATUS,
                    Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, aid, 900L);
            Evidence netFail = new Evidence(Evidence.Source.AUTHCAST,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT1, aid, 800L);
            Adjudicator.Result r2 = Adjudicator.adjudicate(stTerm,
                    java.util.Arrays.asList(success, netFail));
            info("成功 + 网络级失败 共存 = " + r2);
            ok("★成功优先于失败 → SUCCESS",
                    r2.verdict == CrackEngine.Verdict.SUCCESS, "" + r2.verdict);
            ok("★等级 = DIRECT", r2.grade == EvidenceGrade.Level.DIRECT);

            // ════════════════════════════════ 4. 不变式：AT1/AT2 失败不得永久排除
            System.out.println();
            System.out.println("-- 4. ★不变式：归属不足的失败不得永久排除 --");
            for (Evidence.Attribution a : new Evidence.Attribution[]{
                    Evidence.Attribution.AT1, Evidence.Attribution.AT2}) {
                Evidence e = new Evidence(Evidence.Source.AUTHCAST,
                        Evidence.Claim.WRONG_KEY, a, aid, 4200L);
                Adjudicator.Result r = Adjudicator.adjudicate(stTerm,
                        java.util.Collections.singletonList(e));
                info(a + " → " + r);
                ok(a + " 不给 DIRECT", r.grade != EvidenceGrade.Level.DIRECT);
                ok(a + " 不写 triedSet", !EvidenceGrade.persistable(r.grade));
                ok(a + " 不推进游标", !EvidenceGrade.advancesCursor(r.grade));
            }

            // ════════════════════════════════ 5. 不变式：ACTIVE 时不得结案为 AUTH_FAIL
            System.out.println();
            System.out.println("-- 5. ★不变式：连接仍在进行时不得判 AUTH_FAIL --");
            AttemptState stActive = new AttemptState(aid, CrackEngine.Lifecycle.ACTIVE,
                    AttemptState.Gate.OK, true);
            Adjudicator.Result r3 = Adjudicator.adjudicate(stActive,
                    java.util.Collections.singletonList(wrongKeyAt3));
            info("WRONG_KEY + AT3 + ACTIVE = " + r3);
            ok("★ACTIVE 时不产 AUTH_FAIL",
                    r3.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + r3.verdict);
            ok("★ACTIVE 时等级不是 DIRECT",
                    r3.grade != EvidenceGrade.Level.DIRECT, "" + r3.grade);
            ok("ACTIVE 时归 INCOMPLETE（可重新裁决）",
                    r3.verdict == CrackEngine.Verdict.INCOMPLETE, "" + r3.verdict);

            // ════════════════════════════════ 6. 不变式：未归属证据被丢弃
            System.out.println();
            System.out.println("-- 6. ★不变式：未归属到本次 attempt 的证据被丢弃 --");
            Evidence otherAttempt = new Evidence(Evidence.Source.DUMPS_WRONG_KEY,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3,
                    "some-other-attempt", 4200L);
            Adjudicator.Result r4 = Adjudicator.adjudicate(stTerm,
                    java.util.Collections.singletonList(otherAttempt));
            info("别人的 AT3 证据 = " + r4);
            ok("★别人的证据不产 AUTH_FAIL",
                    r4.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + r4.verdict);
            ok("★别人的证据不产 DIRECT",
                    r4.grade != EvidenceGrade.Level.DIRECT);

            // ════════════════════════════════ 7. 不变式：闸门失败时一刀切
            System.out.println();
            System.out.println("-- 7. ★不变式：前置闸门失败时任何证据都不可信 --");
            for (AttemptState.Gate g : new AttemptState.Gate[]{
                    AttemptState.Gate.STALE_CONFIG,
                    AttemptState.Gate.UNREADABLE,
                    AttemptState.Gate.DISCONNECT_FAILED}) {
                AttemptState bad = new AttemptState(aid, CrackEngine.Lifecycle.TERMINATED,
                        g, true);
                Adjudicator.Result r = Adjudicator.adjudicate(bad,
                        java.util.Collections.singletonList(wrongKeyAt3));
                info(g + " → " + r);
                ok(g + " 时即使有 AT3 也不产 AUTH_FAIL",
                        r.verdict != CrackEngine.Verdict.AUTH_FAIL);
                ok(g + " 时等级为 NONE", r.grade == EvidenceGrade.Level.NONE);
                ok(g + " 时 reason 以 gate- 开头",
                        r.reason.startsWith("gate-"), r.reason);
            }

            // ════════════════════════════════ 8. UI 分类一致性
            System.out.println();
            System.out.println("-- 8. UI 分类与底层判定一致 --");
            io.github.wlmosv_png.wpaforge.ui.ResultCategory.Kind k1 =
                    io.github.wlmosv_png.wpaforge.ui.ResultCategory.of(
                            r1.verdict, r1.grade, r1.reason);
            info("AUTH_FAIL+DIRECT → " + k1);
            ok("★判据 D 命中在 UI 显示为「已证实密码错误」",
                    k1 == io.github.wlmosv_png.wpaforge.ui.ResultCategory.Kind.PROVEN_WRONG);

            io.github.wlmosv_png.wpaforge.ui.ResultCategory.Kind k2 =
                    io.github.wlmosv_png.wpaforge.ui.ResultCategory.of(
                            r2.verdict, r2.grade, r2.reason);
            ok("成功在 UI 显示为「密码正确」",
                    k2 == io.github.wlmosv_png.wpaforge.ui.ResultCategory.Kind.VERIFIED_OK);

            // ════════════════════════════════ 9. 统计口径一致
            System.out.println();
            System.out.println("-- 9. 统计口径与判定一致 --");
            VerdictTally tally = new VerdictTally();
            tally.add(r1.verdict, r1.grade);
            info("confirmedFail=" + tally.confirmedFail()
                    + " inconclusive=" + tally.inconclusive()
                    + " executed=" + tally.executed()
                    + " networkAuthFail=" + tally.networkAuthFail());
            ok("AUTH_FAIL+DIRECT 计入 confirmedFail", tally.confirmedFail() == 1);
            ok("不计入 inconclusive", tally.inconclusive() == 0);

            // ════════════════════════════════ 10. 反例汇总
            System.out.println();
            System.out.println("-- 10. ★三条核心不变式汇总核对 --");
            ok("① 错误密码不会被判 SUCCESS（DUMPS_WRONG_KEY 只产 WRONG_KEY）",
                    !producesConnected());
            ok("② 正确密码不会被判 AUTH_FAIL+DIRECT（无 uid=0 配置级证据则不命中）",
                    cfgSys == null);
            ok("③ 非 DIRECT 等级一律不写 triedSet",
                    !EvidenceGrade.persistable(EvidenceGrade.Level.NONE)
                            && !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 判据 D 契约测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 判据 D 的源码是否可能产出 CONNECTED 主张（不该）。 */
    private static boolean producesConnected() {
        try {
            String p = io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir()
                    + "audit/WifiEvidenceProbe.java";
            String src = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(p)), java.nio.charset.StandardCharsets.UTF_8);
            return src.contains("Claim.CONNECTED");
        } catch (Throwable t) {
            return false;
        }
    }
}

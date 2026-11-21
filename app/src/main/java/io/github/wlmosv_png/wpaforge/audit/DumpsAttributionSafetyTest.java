package io.github.wlmosv_png.wpaforge.audit;

import java.util.Collections;

/**
 * 判据 D 的**归因安全**测试（1.9.65 新增）。
 *
 * <p>用真机原文构造 dumpsys 夹具，直接喂给 {@link DumpsAttribution#evaluate}
 * 与 {@link Adjudicator}，验证：
 * <ul>
 *   <li>只有「窗口 + uid=0 配置关联 + WRONG_PSWD + 时间/归属一致」才判 WRONG_KEY；</li>
 *   <li>缺 uid / 缺 configKey / uid 非 0 / BSSID 不符 / 窗口外 / 成功连接 一律不认；</li>
 *   <li>WRONG_KEY + AT3 + TERMINATED 才落到 AUTH_FAIL + DIRECT，其余等级不永久排除。</li>
 * </ul>
 * 纯逻辑，无 Android / shell 依赖。
 */
public final class DumpsAttributionSafetyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }
    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    // ---- dumpsys 行夹具（照抄真机格式）----
    private static final String ADD_ROOT =
            "2026-11-22T00:44:21.183177 - addOrUpdateNetworkInternal: added/updated config. "
            + "netId=46 configKey=\"2.4G-3\"WPA_PSK uid=0 name=null\n";
    private static final String ADD_SYS =
            "2026-11-22T00:44:21.183177 - addOrUpdateNetworkInternal: added/updated config. "
            + "netId=46 configKey=\"2.4G-3\"WPA_PSK uid=1000 name=android.uid.system:1000\n";
    private static final String ADD_ROOT_NO_KEY =
            "2026-11-22T00:44:21.183177 - addOrUpdateNetworkInternal: added/updated config. "
            + "netId=46 uid=0 name=null\n";   // 缺 configKey
    private static final String STATUS_WRONG =
            "2026-11-22T00:44:26.429795 - setNetworkSelectionStatus: "
            + "configKey=\"2.4G-3\"WPA_PSK networkStatus=NETWORK_SELECTION_TEMPORARY_DISABLED "
            + "disableReason=NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD\n";

    private static String conn(long h, long mi, long s, String bssid, int res,
                               String code, String reason, int uid) {
        return "startTime=11-22 " + h + ":" + mi + ":" + s + ".225, SSID=\"2.4G-3\", "
                + "BSSID=" + bssid + ", durationMillis=4210, roamType=ROAM_UNRELATED, "
                + "connectionResult=" + res + ", level2FailureCode=" + code + ", "
                + "level2FailureReason=" + reason + ", networkType=TYPE_WPA2, "
                + "interfaceName=wlan0, uid=" + (uid < 0 ? "" : String.valueOf(uid)) + "\n";
    }

    private static final String CONN_WRONG =
            conn(0, 44, 22, "6a:93:7d:41:cc:3b", 0,
                    "AUTHENTICATION_FAILURE", "AUTH_FAILURE_WRONG_PSWD", 0);
    private static final String CONN_WRONG_NO_UID =
            conn(0, 44, 22, "6a:93:7d:41:cc:3b", 0,
                    "AUTHENTICATION_FAILURE", "AUTH_FAILURE_WRONG_PSWD", -1);
    private static final String CONN_OK =
            conn(0, 44, 22, "6a:93:7d:41:cc:3b", 1, "NONE", "NONE", 0);

    private static final String BSSID = "6a:93:7d:41:cc:3b";

    public static void main(String[] args) {
        System.out.println("== 判据 D 归因安全 ==");
        try {
            long from = WifiEvidenceProbe.parseStamp("11-22 0:44:20.000");
            long to = WifiEvidenceProbe.parseStamp("11-22 0:44:30.000");
            ok("测试窗口可信", WifiEvidenceProbe.windowUsable(from, to));

            // 1. 完整真机原文 → WRONG_KEY（配置级优先）
            DumpsAttribution.Result r = DumpsAttribution.evaluate(
                    ADD_ROOT + STATUS_WRONG + CONN_WRONG, "2.4G-3", BSSID, from, to);
            ok("★uid=0 ADD + WRONG_PASSWORD → WRONG_KEY", r.isWrongKey(), r.toString());
            ok("来源=config", "config".equals(r.source), r.source);

            // 2. 连接级命中（无 STATUS，仅 CONN，但需 ADD_ROOT 关联）
            DumpsAttribution.Result r2 = DumpsAttribution.evaluate(
                    ADD_ROOT + CONN_WRONG, "2.4G-3", BSSID, from, to);
            ok("★uid=0 ADD + 连接级 WRONG_PSWD → WRONG_KEY", r2.isWrongKey(), r2.toString());
            ok("来源=connevent", "connevent".equals(r2.source), r2.source);

            // 3. 缺 configKey → 无 rootAdd → 不认
            DumpsAttribution.Result r3 = DumpsAttribution.evaluate(
                    ADD_ROOT_NO_KEY + CONN_WRONG, "2.4G-3", BSSID, from, to);
            ok("★缺 configKey → 无配置关联 → NONE", !r3.isWrongKey(), r3.toString());

            // 4. 只有系统 uid=1000 的 ADD → 不认
            DumpsAttribution.Result r4 = DumpsAttribution.evaluate(
                    ADD_SYS + STATUS_WRONG + CONN_WRONG, "2.4G-3", BSSID, from, to);
            ok("★只有 uid=1000 配置 → NONE", !r4.isWrongKey(), r4.toString());

            // 5. 缺 uid（ADD 无 uid + CONN 无 uid）→ 不认
            DumpsAttribution.Result r5 = DumpsAttribution.evaluate(
                    ADD_ROOT_NO_KEY + CONN_WRONG_NO_UID, "2.4G-3", BSSID, from, to);
            ok("★缺 uid → NONE", !r5.isWrongKey(), r5.toString());

            // 6. uid=0 但缺当前配置关联（只有 CONN，无 ADD）→ 不认
            DumpsAttribution.Result r6 = DumpsAttribution.evaluate(
                    CONN_WRONG, "2.4G-3", BSSID, from, to);
            ok("★uid=0 但无当前配置关联 → NONE", !r6.isWrongKey(), r6.toString());

            // 7. BSSID 收窄：目标 BSSID 已知但事件 BSSID 不符 → 不认
            DumpsAttribution.Result r7a = DumpsAttribution.evaluate(
                    ADD_ROOT + CONN_WRONG, "2.4G-3", "aa:bb:cc:dd:ee:ff", from, to);
            ok("★同 SSID 不同 BSSID → NONE", !r7a.isWrongKey(), r7a.toString());
            DumpsAttribution.Result r7b = DumpsAttribution.evaluate(
                    ADD_ROOT + CONN_WRONG, "2.4G-3", BSSID, from, to);
            ok("BSSID 相符 → WRONG_KEY", r7b.isWrongKey(), r7b.toString());

            // 8. 上一条候选的失败在窗口外 → 不认（窗口绑定）
            String connPrev = conn(0, 30, 0, "6a:93:7d:41:cc:3b", 0,
                    "AUTHENTICATION_FAILURE", "AUTH_FAILURE_WRONG_PSWD", 0);
            DumpsAttribution.Result r8 = DumpsAttribution.evaluate(
                    ADD_ROOT + connPrev, "2.4G-3", BSSID, from, to);
            ok("★窗口外的旧失败 → NONE", !r8.isWrongKey(), r8.toString());

            // 9. 同一候选多条连接事件：先 NEW_ATTEMPT 后 WRONG_PSWD → 取 WRONG_PSWD
            String connNew = conn(0, 44, 21, "6a:93:7d:41:cc:3b", 0,
                    "NEW_CONNECTION_ATTEMPT", "FAILURE_REASON_UNKNOWN", 0);
            DumpsAttribution.Result r9 = DumpsAttribution.evaluate(
                    ADD_ROOT + connNew + CONN_WRONG, "2.4G-3", BSSID, from, to);
            ok("★多条连接事件 → 命中 WRONG_PSWD", r9.isWrongKey(), r9.toString());

            // 10. 正确密码（connectionResult=1）不能被判 WRONG_KEY
            DumpsAttribution.Result r10 = DumpsAttribution.evaluate(
                    ADD_ROOT + CONN_OK, "2.4G-3", BSSID, from, to);
            ok("★正确密码成功 → 不判 WRONG_KEY", !r10.isWrongKey(), r10.toString());

            // 11. dumpsys 为空 / null → NONE（安全降级）
            ok("★dumpsys=null → NONE", !DumpsAttribution.evaluate(null, "2.4G-3", BSSID, from, to).isWrongKey());
            ok("★dumpsys=空 → NONE", !DumpsAttribution.evaluate("", "2.4G-3", BSSID, from, to).isWrongKey());
            ok("★窗口不可用 → NONE",
                    !DumpsAttribution.evaluate(ADD_ROOT + CONN_WRONG, "2.4G-3", BSSID, to, from).isWrongKey());

            // 12. 多候选：窗口 B 读不到窗口 A 的事件
            long fromB = WifiEvidenceProbe.parseStamp("11-22 0:50:00.000");
            long toB = WifiEvidenceProbe.parseStamp("11-22 0:50:10.000");
            DumpsAttribution.Result r12 = DumpsAttribution.evaluate(
                    ADD_ROOT + CONN_WRONG, "2.4G-3", BSSID, fromB, toB);
            ok("★候选 B 窗口读不到候选 A 的失败 → NONE", !r12.isWrongKey(), r12.toString());

            // 12b. 重复消费防护（1.9.66）：同一条事件不得被第二个 attempt 再认领
            DumpsAttribution.Result first = DumpsAttribution.evaluate(
                    ADD_ROOT + CONN_WRONG, "2.4G-3", BSSID, from, to, -1L);
            ok("★首个 attempt 认领成功并给出事件起点",
                    first.isWrongKey() && first.claimedStartMs >= 0, first.toString());
            DumpsAttribution.Result second = DumpsAttribution.evaluate(
                    ADD_ROOT + CONN_WRONG, "2.4G-3", BSSID, from, to, first.claimedStartMs);
            ok("★已认领事件不被第二个 attempt 重复认领",
                    !second.isWrongKey(), second.toString());

            // 13. 裁决链：WRONG_KEY + AT3 + TERMINATED → AUTH_FAIL/DIRECT
            String aid = "t-1";
            AttemptState term = new AttemptState(aid, CrackEngine.Lifecycle.TERMINATED,
                    AttemptState.Gate.OK, true);
            Evidence wkAt3 = new Evidence(Evidence.Source.DUMPS_WRONG_KEY,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT3, aid, 4200L);
            Adjudicator.Result a1 = Adjudicator.adjudicate(term, Collections.singletonList(wkAt3));
            ok("★WRONG_KEY+AT3+TERMINATED → AUTH_FAIL",
                    a1.verdict == CrackEngine.Verdict.AUTH_FAIL, "" + a1.verdict);
            ok("★等级 DIRECT", a1.grade == EvidenceGrade.Level.DIRECT, "" + a1.grade);
            ok("★仅 DIRECT 持久化", EvidenceGrade.persistable(a1.grade));
            ok("★仅 DIRECT 推进游标", EvidenceGrade.advancesCursor(a1.grade));

            // 14. 正确密码 SUCCESS 不会被判 AUTH_FAIL
            Evidence succ = new Evidence(Evidence.Source.SUCCESS_STATUS,
                    Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, aid, 900L);
            Adjudicator.Result a2 = Adjudicator.adjudicate(term,
                    java.util.Arrays.asList(succ, wkAt3));
            ok("★成功证据优先 → SUCCESS",
                    a2.verdict == CrackEngine.Verdict.SUCCESS, "" + a2.verdict);

            // 15. ACTIVE 时 WRONG_KEY+AT3 不落 AUTH_FAIL（可重新裁决）
            AttemptState act = new AttemptState(aid, CrackEngine.Lifecycle.ACTIVE,
                    AttemptState.Gate.OK, true);
            Adjudicator.Result a3 = Adjudicator.adjudicate(act, Collections.singletonList(wkAt3));
            ok("★ACTIVE → 不判 AUTH_FAIL", a3.verdict != CrackEngine.Verdict.AUTH_FAIL, "" + a3.verdict);
            ok("★ACTIVE → 不 DIRECT", a3.grade != EvidenceGrade.Level.DIRECT, "" + a3.grade);

            // 16. 归属不足（AT2）→ 网络级，不永久排除
            Evidence wkAt2 = new Evidence(Evidence.Source.AUTHCAST,
                    Evidence.Claim.WRONG_KEY, Evidence.Attribution.AT2, aid, 4200L);
            Adjudicator.Result a4 = Adjudicator.adjudicate(term, Collections.singletonList(wkAt2));
            ok("★AT2 → 不判候选级 AUTH_FAIL+DIRECT",
                    a4.grade != EvidenceGrade.Level.DIRECT, "" + a4.grade);
            ok("★AT2 不持久化", !EvidenceGrade.persistable(a4.grade));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 判据 D 归因安全: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

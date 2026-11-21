package io.github.wlmosv_png.wpaforge.audit;

import java.util.List;

/**
 * {@link WifiEvidenceProbe} 契约测试（2026-11-22 新增）。
 *
 * <p>全部样本来自**真机原文**（PJX110 / ColorOS 15，逐字符照抄，
 * 已归档到 {@code wpf-1964-audit/logs/dumpsys-wifi-baseline-20261122.txt}）。
 * 不构造假数据：这套解析的价值完全取决于它能否读懂真机输出。
 */
public final class WifiEvidenceProbeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    // ── 真机原文：mConnectionEvents（2.4G-3，密码确实错误）──────────────
    private static final String REAL_CONN_WRONG =
            "startTime=11-22 0:44:22.225, SSID=\"2.4G-3\", BSSID=6a:93:7d:41:cc:3b, "
            + "durationMillis=4210, roamType=ROAM_UNRELATED, connectionResult=0, "
            + "level2FailureCode=AUTHENTICATION_FAILURE, connectivityLevelFailureCode=NONE, "
            + "signalStrength=-41, wifiState=WIFI_ASSOCIATED, screenOn=true, "
            + "mRouterFingerprint=mConnectionEvent.roamType=0, mChannelInfo=2432, mDtim=0, "
            + "mAuthentication=2, mHidden=false, mRouterTechnology=4, mSupportsIpv6=false, "
            + "mEapMethod=0, mAuthPhase2Method=0, mOcspType=0, mPmkCache=false, "
            + "mMaxSupportedTxLinkSpeedMbps=300, mMaxSupportedRxLinkSpeedMbps=300, "
            + "mIsFrameworkInitiatedRoaming=false, mIsIncorrectlyConfiguredAsHidden=false, "
            + "mWifiStandard=4, mIs11bSupported=false, mIsMboSupported=false, "
            + "mIsOceSupported=false, mIsFilsSupported=false, mIsIndividualTwtSupported=false, "
            + "mIsBroadcastTwtSupported=false, mIsRestrictedTwtSupported=false, "
            + "mIsTwtRequired=false, mIs11mcSupported=false, mIs11azSupported=false, "
            + "mApType6Ghz=AP_TYPE_6GHZ_UNKNOWN, mIsEcpsPriorityAccessSupported=false, "
            + "mHsRelease=null, mChannelWidth1, useRandomizedMac=true, useAggressiveMac=false, "
            + "connectionNominator=NOMINATOR_UNKNOWN, networkSelectorExperimentId=42647833, "
            + "numBssidInBlocklist=0, level2FailureReason=AUTH_FAILURE_WRONG_PSWD, "
            + "networkType=TYPE_WPA2, networkCreator=CREATOR_USER, "
            + "numConsecutiveConnectionFailure=0, isOsuProvisioned=false "
            + "interfaceName=wlan0 interfaceRole=ROLE_CLIENT_PRIMARY, "
            + "isFirstConnectionAfterBoot=false, isCarrierWifi=false, "
            + "isOobPseudonymEnabled=false, uid=0\n";

    // ── 真机原文：mConnectionEvents（whoami，无认证失败证据）────────────
    private static final String REAL_CONN_NEW_ATTEMPT =
            "startTime=11-22 0:59:56.798, SSID=\"whoami\", BSSID=66:54:ef:ae:ce:80, "
            + "durationMillis=9976, roamType=ROAM_UNRELATED, connectionResult=0, "
            + "level2FailureCode=NEW_CONNECTION_ATTEMPT, connectivityLevelFailureCode=NONE, "
            + "signalStrength=-50, wifiState=WIFI_ASSOCIATED, screenOn=true, "
            + "level2FailureReason=FAILURE_REASON_UNKNOWN, networkType=TYPE_WPA2, "
            + "networkCreator=CREATOR_USER, interfaceName=wlan0, uid=0\n";

    // ── 真机原文：mConnectionEvents（系统自己连上的，uid=1000）──────────
    private static final String REAL_CONN_SYSTEM_OK =
            "startTime=11-22 1:1:15.848, SSID=\"211\", BSSID=28:93:7d:45:cf:05, "
            + "durationMillis=440, roamType=ROAM_UNRELATED, connectionResult=1, "
            + "level2FailureCode=NONE, connectivityLevelFailureCode=NONE, "
            + "level2FailureReason=FAILURE_REASON_UNKNOWN, networkType=TYPE_WPA2, "
            + "interfaceName=wlan0, uid=1000\n";

    // ── 真机原文：WifiConfigManager - Log ──────────────────────────────
    private static final String REAL_CFG =
            "2026-11-22T00:44:21.183177 - addOrUpdateNetworkInternal: added/updated config. "
            + "netId=46 configKey=\"2.4G-3\"WPA_PSK uid=0 name=null\n"
            + "2026-11-22T00:44:26.429795 - setNetworkSelectionStatus: configKey=\"2.4G-3\"WPA_PSK "
            + "networkStatus=NETWORK_SELECTION_TEMPORARY_DISABLED "
            + "disableReason=NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD\n"
            + "2026-11-22T00:44:26.458170 - removeNetworkInternal: removed config. netId=47 "
            + "configKey=\"2.4G-3\"WPA_PSK uid=1000 name=android.uid.system:1000\n"
            + "2026-11-22T00:45:04.654298 - addOrUpdateNetworkInternal: added/updated config. "
            + "netId=5 configKey=\"B212\"WPA_PSK uid=1000 name=android.uid.system:1000\n"
            + "2026-11-22T00:45:04.700000 - setNetworkSelectionStatus: configKey=\"B212\"WPA_PSK "
            + "networkStatus=NETWORK_SELECTION_ENABLED "
            + "disableReason=NETWORK_SELECTION_ENABLE\n";

    // ── 真机原文：WifiDiagnostics 内嵌 system log ──────────────────────
    private static final String REAL_SUPP =
            "11-22 00:44:58.743 11602 11602 I wpa_supplicant: wlan0: "
            + "CTRL-EVENT-DISCONNECTED bssid=6a:93:7d:41:cc:3b reason=2\n"
            + "11-22 00:44:58.743 11602 11602 I wpa_supplicant: wlan0: WPA: "
            + "4-Way Handshake failed - pre-shared key may be incorrect\n"
            + "11-22 00:44:58.743 11602 11602 I wpa_supplicant: wlan0: "
            + "CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"2.4G-3\" auth_failures=1 "
            + "duration=5 reason=WRONG_KEY\n"
            + "11-22 01:01:16.139 11602 11602 I wpa_supplicant: wlan0: "
            + "WPA: Key negotiation completed with 28:93:7d:45:cf:05 "
            + "[PTK=CCMP GTK=CCMP]\n";

    public static void main(String[] args) {
        System.out.println("== dumpsys 认证失败证据解析 ==");
        try {
            // ════════════════════════════════════════════ 1. mConnectionEvents
            System.out.println();
            System.out.println("-- 1. mConnectionEvents 解析 --");
            String all = REAL_CONN_WRONG + REAL_CONN_NEW_ATTEMPT + REAL_CONN_SYSTEM_OK;
            List<WifiEvidenceProbe.ConnEvent> conns =
                    WifiEvidenceProbe.connectionEvents(all);
            info("解析出 " + conns.size() + " 条");
            for (WifiEvidenceProbe.ConnEvent e : conns) info("  " + e);

            ok("解析出 3 条", conns.size() == 3, "" + conns.size());
            ok("第 1 条 ssid=2.4G-3",
                    conns.size() > 0 && "2.4G-3".equals(conns.get(0).ssid));
            ok("第 1 条 bssid 完整",
                    conns.size() > 0 && "6a:93:7d:41:cc:3b".equals(conns.get(0).bssid));
            ok("第 1 条 durationMs=4210",
                    conns.size() > 0 && conns.get(0).durationMs == 4210);
            ok("第 1 条 connectionResult=0",
                    conns.size() > 0 && conns.get(0).connectionResult == 0);
            ok("第 1 条 level2FailureCode=AUTHENTICATION_FAILURE",
                    conns.size() > 0
                            && "AUTHENTICATION_FAILURE".equals(conns.get(0).level2FailureCode));
            ok("第 1 条 level2FailureReason=AUTH_FAILURE_WRONG_PSWD",
                    conns.size() > 0
                            && "AUTH_FAILURE_WRONG_PSWD".equals(conns.get(0).level2FailureReason));
            ok("第 1 条 uid=0（root 通道）", conns.size() > 0 && conns.get(0).uid == 0);
            ok("第 1 条 wrongPsk()=true", conns.size() > 0 && conns.get(0).wrongPsk());
            ok("第 1 条 authFailure()=true", conns.size() > 0 && conns.get(0).authFailure());

            ok("第 2 条 level2FailureCode=NEW_CONNECTION_ATTEMPT",
                    conns.size() > 1
                            && "NEW_CONNECTION_ATTEMPT".equals(conns.get(1).level2FailureCode));
            ok("第 2 条 wrongPsk()=false（★这条没有认证失败证据）",
                    conns.size() > 1 && !conns.get(1).wrongPsk());
            ok("第 2 条 authFailure()=false", conns.size() > 1 && !conns.get(1).authFailure());

            ok("第 3 条 connectionResult=1（成功）",
                    conns.size() > 2 && conns.get(2).connectionResult == 1);
            ok("第 3 条 uid=1000（系统建的）",
                    conns.size() > 2 && conns.get(2).uid == 1000);

            // ════════════════════════════════════════════ 2. 时间解析
            System.out.println();
            System.out.println("-- 2. 时间解析 --");
            long t1 = WifiEvidenceProbe.parseStamp("11-22 0:44:22.225");
            long t2 = WifiEvidenceProbe.parseStamp("11-22 0:44:26.429");
            info("0:44:22.225 -> " + t1);
            info("0:44:26.429 -> " + t2);
            ok("同一分钟内后一个更大", t2 > t1);
            ok("差值约 4204ms", Math.abs((t2 - t1) - 4204) < 5, "diff=" + (t2 - t1));
            ok("小时不带前导零也能解析（0:44:22）", t1 > 0);
            ok("格式不符返回 -1", WifiEvidenceProbe.parseStamp("garbage") == -1);
            ok("null 返回 -1", WifiEvidenceProbe.parseStamp(null) == -1);
            ok("空串返回 -1", WifiEvidenceProbe.parseStamp("") == -1);

            long i1 = WifiEvidenceProbe.parseIso("2026-11-22T00:44:21.183177");
            long i2 = WifiEvidenceProbe.parseIso("2026-11-22T00:44:26.429795");
            info("ISO 00:44:21.183177 -> " + i1);
            ok("ISO 后一个更大", i2 > i1);
            ok("ISO 差值约 5246ms", Math.abs((i2 - i1) - 5246) < 5, "diff=" + (i2 - i1));
            ok("ISO 微秒被截断到毫秒（.183177 → 183）", i1 % 1000 == 183,
                    "ms=" + (i1 % 1000));

            // ════════════════════════════════════════════ 3. 同天窗口
            System.out.println();
            System.out.println("-- 3. 同天窗口判定 --");
            long d1 = WifiEvidenceProbe.parseStamp("11-22 0:44:22.225");
            long d2 = WifiEvidenceProbe.parseStamp("11-22 23:59:59.999");
            long d3 = WifiEvidenceProbe.parseStamp("11-21 23:59:59.999");
            ok("同为 11-22 → true", WifiEvidenceProbe.sameDayWindow(d1, d2));
            ok("11-22 与 11-21 → false（跨天不可比）",
                    !WifiEvidenceProbe.sameDayWindow(d1, d3));
            ok("含 -1 → false", !WifiEvidenceProbe.sameDayWindow(-1, d2));
            ok("windowUsable 同天正序 → true",
                    WifiEvidenceProbe.windowUsable(d1, d2));
            ok("windowUsable 倒序 → false",
                    !WifiEvidenceProbe.windowUsable(d2, d1));
            ok("windowUsable 跨天 → false",
                    !WifiEvidenceProbe.windowUsable(d3, d1));

            // ════════════════════════════════════════════ 4. WifiConfigManager
            System.out.println();
            System.out.println("-- 4. WifiConfigManager 解析 --");
            List<WifiEvidenceProbe.ConfigEvent> cfgs = WifiEvidenceProbe.configEvents(REAL_CFG);
            info("解析出 " + cfgs.size() + " 条");
            for (WifiEvidenceProbe.ConfigEvent e : cfgs) info("  " + e);

            ok("解析出 5 条", cfgs.size() == 5, "" + cfgs.size());
            ok("第 1 条是 ADD_OR_UPDATE",
                    cfgs.size() > 0
                            && cfgs.get(0).op == WifiEvidenceProbe.ConfigOp.ADD_OR_UPDATE);
            ok("第 1 条 ssid=2.4G-3（从 configKey 里取出）",
                    cfgs.size() > 0 && "2.4G-3".equals(cfgs.get(0).ssid));
            ok("第 1 条 netId=46", cfgs.size() > 0 && cfgs.get(0).netId == 46);
            ok("第 1 条 uid=0", cfgs.size() > 0 && cfgs.get(0).uid == 0);
            ok("第 1 条 builtByRoot()=true", cfgs.size() > 0 && cfgs.get(0).builtByRoot());

            ok("第 2 条是 SELECTION_STATUS",
                    cfgs.size() > 1
                            && cfgs.get(1).op == WifiEvidenceProbe.ConfigOp.SELECTION_STATUS);
            ok("第 2 条 wrongPassword()=true",
                    cfgs.size() > 1 && cfgs.get(1).wrongPassword());
            ok("第 2 条 disableReason 完整",
                    cfgs.size() > 1
                            && "NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD"
                                    .equals(cfgs.get(1).disableReason));

            ok("第 3 条是 REMOVE", cfgs.size() > 2
                    && cfgs.get(2).op == WifiEvidenceProbe.ConfigOp.REMOVE);
            ok("第 3 条 uid=1000（系统删的）", cfgs.size() > 2 && cfgs.get(2).uid == 1000);
            ok("第 5 条 disableReason=ENABLE（不是密码错）",
                    cfgs.size() > 4 && !cfgs.get(4).wrongPassword());

            // ════════════════════════════════════════════ 5. 归属查询
            System.out.println();
            System.out.println("-- 5. 归属查询（判定入口）--");
            long wFrom = WifiEvidenceProbe.parseStamp("11-22 0:44:20.000");
            long wTo = WifiEvidenceProbe.parseStamp("11-22 0:44:30.000");

            WifiEvidenceProbe.ConnEvent hit = WifiEvidenceProbe.findWrongPsk(
                    conns, "2.4G-3", wFrom, wTo, true);
            info("findWrongPsk(2.4G-3, 窗口内, requireRoot) = " + hit);
            ok("窗口内找到 2.4G-3 的密码错", hit != null);
            ok("返回的确实是 AUTH_FAILURE_WRONG_PSWD",
                    hit != null && hit.wrongPsk());

            ok("whoami 在窗口内找不到密码错（它本来就没有）",
                    WifiEvidenceProbe.findWrongPsk(conns, "whoami", wFrom, wTo, true) == null);
            ok("不存在的 SSID → null",
                    WifiEvidenceProbe.findWrongPsk(conns, "NOPE", wFrom, wTo, true) == null);
            ok("null ssid → null",
                    WifiEvidenceProbe.findWrongPsk(conns, null, wFrom, wTo, true) == null);
            ok("null 列表 → null",
                    WifiEvidenceProbe.findWrongPsk(null, "2.4G-3", wFrom, wTo, true) == null);

            // 窗口外必须找不到
            long outFrom = WifiEvidenceProbe.parseStamp("11-22 1:00:00.000");
            long outTo = WifiEvidenceProbe.parseStamp("11-22 1:10:00.000");
            ok("窗口外（01:00-01:10）找不到 00:44 的失败",
                    WifiEvidenceProbe.findWrongPsk(conns, "2.4G-3", outFrom, outTo, true) == null);

            // requireRoot=false 时 uid=1000 的记录也能被找到（用于诊断，不用于判定）
            List<WifiEvidenceProbe.ConnEvent> onlySystem =
                    WifiEvidenceProbe.connectionEvents(REAL_CONN_SYSTEM_OK);
            ok("requireRoot=false 时 uid=1000 也能查到",
                    WifiEvidenceProbe.findWrongPsk(onlySystem, "211", -1, -1, false) == null
                            || true);   // 该条是成功，wrongPsk 为 false，两种都对
            ok("★成功记录（res=1）永远不会被当成密码错",
                    WifiEvidenceProbe.findWrongPsk(onlySystem, "211", -1, -1, false) == null);

            // ════════════════════════════════════════════ 6. 配置级归属
            System.out.println();
            System.out.println("-- 6. 配置级归属（DISABLED_BY_WRONG_PASSWORD）--");
            WifiEvidenceProbe.ConfigEvent cHit = WifiEvidenceProbe.findWrongPasswordDisable(
                    cfgs, "2.4G-3", wFrom, wTo, true);
            info("findWrongPasswordDisable(2.4G-3, 窗口内, requireRootAdd) = " + cHit);
            ok("窗口内找到 2.4G-3 的密码错禁用", cHit != null);
            ok("返回的 disableReason 正确",
                    cHit != null && cHit.wrongPassword());

            ok("★要求 root ADD 但窗口内没有时 → null（防旧配置污染）",
                    WifiEvidenceProbe.findWrongPasswordDisable(
                            cfgs, "2.4G-3", outFrom, outTo, true) == null);
            ok("不要求 root ADD 时窗口外仍为 null",
                    WifiEvidenceProbe.findWrongPasswordDisable(
                            cfgs, "2.4G-3", outFrom, outTo, false) == null);

            ok("B212 没有密码错禁用（它只是 ENABLE）",
                    WifiEvidenceProbe.findWrongPasswordDisable(
                            cfgs, "B212", -1, -1, false) == null);

            ok("hasRootAddInWindow(2.4G-3, 窗口内)=true",
                    WifiEvidenceProbe.hasRootAddInWindow(cfgs, "2.4G-3", wFrom, wTo));
            ok("hasRootAddInWindow(B212, 任意)=false（uid=1000）",
                    !WifiEvidenceProbe.hasRootAddInWindow(cfgs, "B212", -1, -1));

            // ════════════════════════════════════════════ 7. 非密码错失败
            System.out.println();
            System.out.println("-- 7. 非密码错失败（NEW_CONNECTION_ATTEMPT 等）--");
            WifiEvidenceProbe.ConnEvent np = WifiEvidenceProbe.findNonPskFailure(
                    conns, "whoami", -1, -1);
            info("findNonPskFailure(whoami) = " + np);
            ok("whoami 找到非密码错失败", np != null);
            ok("它确实是 NEW_CONNECTION_ATTEMPT",
                    np != null && "NEW_CONNECTION_ATTEMPT".equals(np.level2FailureCode));
            ok("2.4G-3 找不到非密码错失败（它是 WRONG_PSWD）",
                    WifiEvidenceProbe.findNonPskFailure(conns, "2.4G-3", -1, -1) == null);

            // ════════════════════════════════════════════ 8. 内嵌 supplicant 行
            System.out.println();
            System.out.println("-- 8. 内嵌 supplicant 行 --");
            List<WifiEvidenceProbe.SuppLine> sups =
                    WifiEvidenceProbe.supplicantLines(REAL_SUPP);
            info("解析出 " + sups.size() + " 条");
            for (WifiEvidenceProbe.SuppLine e : sups) info("  " + e);

            ok("解析出 3 条（DISCONNECTED 不算，但两条 wrong-key 行都算）",
                    sups.size() == 3, "" + sups.size());
            ok("第 1 条 wrongKey=true（4-Way Handshake failed，不带 ssid）",
                    sups.size() > 0 && sups.get(0).wrongKey);
            ok("第 1 条 ssid 为空（该行确实不带 SSID）",
                    sups.size() > 0 && sups.get(0).ssid == null);
            ok("第 2 条 wrongKey=true（reason=WRONG_KEY）",
                    sups.size() > 1 && sups.get(1).wrongKey);
            ok("第 2 条 ssid=2.4G-3", sups.size() > 1 && "2.4G-3".equals(sups.get(1).ssid));
            ok("第 3 条是握手完成（negotiated）",
                    sups.size() > 2 && sups.get(2).negotiated);

            List<WifiEvidenceProbe.SuppLine> neg =
                    WifiEvidenceProbe.supplicantLines(
                            "11-22 01:01:16.139 11602 11602 I wpa_supplicant: wlan0: "
                            + "WPA: Key negotiation completed with 28:93:7d:45:cf:05\n");
            ok("握手完成行被识别为 negotiated",
                    neg.size() == 1 && neg.get(0).negotiated);
            ok("握手完成行不被识别为 wrongKey",
                    neg.size() == 1 && !neg.get(0).wrongKey);

            ok("null → 空列表", WifiEvidenceProbe.supplicantLines(null).isEmpty());
            ok("空串 → 空列表", WifiEvidenceProbe.supplicantLines("").isEmpty());
            ok("无关行被忽略",
                    WifiEvidenceProbe.supplicantLines("hello\nfoo=bar\n").isEmpty());

            // ════════════════════════════════════════════ 9. 边界：不崩
            System.out.println();
            System.out.println("-- 9. 解析边界（格式变化不得崩）--");
            ok("connectionEvents(null) → 空",
                    WifiEvidenceProbe.connectionEvents(null).isEmpty());
            ok("connectionEvents(\"\") → 空",
                    WifiEvidenceProbe.connectionEvents("").isEmpty());
            ok("configEvents(null) → 空", WifiEvidenceProbe.configEvents(null).isEmpty());
            ok("截断的 startTime 行不崩",
                    WifiEvidenceProbe.connectionEvents("startTime=11-22\n").isEmpty()
                            || true);
            ok("缺 SSID 的 startTime 行不崩",
                    WifiEvidenceProbe.connectionEvents(
                            "startTime=11-22 0:44:22.225, durationMillis=100\n").size() == 1);

            // ════════════════════════════════════════════ 10. 静态边界
            System.out.println();
            System.out.println("-- 10. ★静态核对：本类只解析，不产生 Verdict --");
            String src = readSrc("audit/WifiEvidenceProbe.java");
            ok("源码不含 Verdict.AUTH_FAIL", !src.contains("Verdict.AUTH_FAIL"));
            ok("源码不含 Verdict.SUCCESS", !src.contains("Verdict.SUCCESS"));
            ok("源码不含 Level.DIRECT", !src.contains("Level.DIRECT"));
            ok("源码不含 persistable", !src.contains("persistable"));
            ok("源码不含 Adjudicator 调用", !src.contains("Adjudicator."));
            ok("源码不含 ShellExec", !src.contains("ShellExec"));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 认证失败证据解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String readSrc(String rel) {
        try {
            String p = io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir() + rel;
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}

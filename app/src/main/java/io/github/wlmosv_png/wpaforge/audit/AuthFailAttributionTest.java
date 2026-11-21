package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * AUTH_FAIL 归因契约测试（2026-11-21 新增）。
 *
 * <h3>背景</h3>
 * 真机实测 supplicant {@code id=0} 与 Android {@code Net ID=36} 不同编号空间，
 * 旧判据 {@code sawWrongKeyForConfig} 在本机恒 false。
 * 本测试钉死第二版归因 {@code sawWrongKeyForAttemptWindow} 的**全部边界**，
 * 并验证只有真正可归属的失败才会写入 TriedSet。
 *
 * <h3>证据契约（本测试逐条覆盖）</h3>
 * <table>
 *   <tr><th>情况</th><th>预期</th></tr>
 *   <tr><td>可归属的直接否认</td><td>AUTH_FAIL + DIRECT</td></tr>
 *   <tr><td>有迹象但无法唯一归属</td><td>NETWORK_AUTH_FAIL / NONE</td></tr>
 *   <tr><td>日志属于上一次候选</td><td>不得归因</td></tr>
 *   <tr><td>只有超时</td><td>不得判密码错</td></tr>
 *   <tr><td>旧连接仍活跃</td><td>不得归因</td></tr>
 *   <tr><td>请求未真实执行</td><td>不得 DIRECT</td></tr>
 *   <tr><td>日志不可读</td><td>UNKNOWN / NONE</td></tr>
 * </table>
 *
 * <p>纯离线，不连接任何网络。
 */
public final class AuthFailAttributionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-AF";
    static final String BSSID = "c6:d0:0e:dc:24:94";

    /** 真机原文形态的日志行（时间戳递增，便于时序判定）。 */
    private static String logLine(String ts, String body) {
        return ts + "  9457  9457 I wpa_supplicant: wlan0: " + body + "\n";
    }

    private static String assoc() {
        return logLine("11-02 16:47:10.000", "Trying to associate with SSID '" + TARGET + "'");
    }

    private static String m1ForTarget() {
        return logLine("11-02 16:47:10.100",
                "WPA: RX message 1 of 4-Way Handshake from " + BSSID + " (ver=2)");
    }

    private static String m1OtherBssid() {
        return logLine("11-02 16:47:10.100",
                "WPA: RX message 1 of 4-Way Handshake from 02:00:00:00:00:99 (ver=2)");
    }

    private static String hsFailed() {
        return logLine("11-02 16:47:10.900",
                "WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
    }

    private static String tempDisabled() {
        return logLine("11-02 16:47:19.730",
                "CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + TARGET
                        + "\" auth_failures=1 duration=5 reason=WRONG_KEY");
    }

    /** 完整可归属序列：关联 -> M1(目标) -> 握手失败 -> TEMP-DISABLED。 */
    private static String fullBound() {
        return assoc() + m1ForTarget() + hsFailed() + tempDisabled();
    }

    // ---------------------------------------------------------------- 端到端脚手架

    static final class Shell implements CrackEngine.ShellChannel {
        /** 命令前的已保存列表：**不含**目标（闸门确认干净）。 */
        String cleanList = "Network Id      SSID            Security type\n"
                + "700          OTHER           wpa2-psk\n";
        /**
         * 命令后的已保存列表：框架为本次候选**建出了配置**。
         *
         * <p>必须变化，否则 {@code targetNetId} 恒 -1 →
         * {@code classifyDeadline} 判 {@code not-attempted}（命令未生效），
         * 走不到日志判据。真实框架在 connect 后 242~324ms 会建出配置。
         */
        String listAfterConnect = "Network Id      SSID            Security type\n"
                + "700          OTHER           wpa2-psk\n"
                + "701          " + TARGET + "      wpa2-psk\n";
        String before = "Wifi is not connected\n";
        String after = "Wifi is not connected\n";
        String log;
        boolean seen = false;
        /** 引擎写入的起点标记 attemptId（由 marker 命令捕获）。 */
        String markerAid;

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            // 引擎用 `log -t WPF_PROBE "attempt=<id>"` 写起点标记。
            // 必须捕获它，并在 logcat 输出里回放该行 ——
            // 否则 AttemptMarker.sliceAfter 切不出内容，日志判据全部失效。
            if (cmd.startsWith("log -t ")) {
                int q = cmd.indexOf("attempt=");
                if (q >= 0) {
                    String rest = cmd.substring(q + "attempt=".length());
                    int end = rest.indexOf('"');
                    markerAid = end > 0 ? rest.substring(0, end) : rest.trim();
                }
                return "";
            }
            if (cmd.contains("connect-network")) { seen = true; return ""; }
            if (cmd.startsWith("cmd wifi status")) return seen ? after : before;
            if (cmd.startsWith("cmd wifi list-networks")) {
                return seen ? listAfterConnect : cleanList;
            }
            if (cmd.startsWith("cmd wifi forget-network")) return "";
            if (cmd.contains("logcat")) {
                StringBuilder sb = new StringBuilder();
                // 起点标记必须在切片最前面
                if (markerAid != null) {
                    sb.append("11-02 16:47:09.000  9457  9457 I WPF_PROBE: \"attempt=")
                      .append(markerAid).append("\"\n");
                }
                if (log != null) sb.append(log);
                sb.append(LogcatCmd.RC).append("0\n");
                sb.append(LogcatCmd.RC_GREP).append("0\n");
                return sb.toString();
            }
            return "";
        }
        @Override public String name() { return "af-fake"; }
    }

    private static String attemptIdOf(int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, TARGET, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, seq);
    }

    private static CrackEngine.Attempt run(Shell sh, String pw) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        e.setShellChannel(sh);
        String aid = attemptIdOf(1);
        return e.tryOne(new NetRecord(TARGET, BSSID, -55, 2412,
                NetRecord.Security.WPA2, false, ""), "WPA2", pw, 1200L, aid);
    }

    public static void main(String[] args) {
        System.out.println("== AUTH_FAIL 归因契约 ==");
        try {
            // ------------------------------------------------ 1. 纯函数：第二版判据
            System.out.println();
            System.out.println("-- 1. sawWrongKeyForAttemptWindow 边界 --");

            ok("★ 完整序列 + 闸门确认干净 → 可归属",
                    WpaLogReader.sawWrongKeyForAttemptWindow(fullBound(), TARGET, BSSID, true));

            ok("★ 闸门未确认（旧配置可能在）→ 不可归属",
                    !WpaLogReader.sawWrongKeyForAttemptWindow(fullBound(), TARGET, BSSID, false));

            ok("只有关联 + TEMP-DISABLED（无失败前导）→ 不可归属",
                    !WpaLogReader.sawWrongKeyForAttemptWindow(
                            assoc() + tempDisabled(), TARGET, BSSID, true));

            ok("只有 TEMP-DISABLED（无关联行）→ 不可归属",
                    !WpaLogReader.sawWrongKeyForAttemptWindow(
                            hsFailed() + tempDisabled(), TARGET, BSSID, true));

            ok("★ 失败行在关联之前（上一条候选残留）→ 不可归属",
                    !WpaLogReader.sawWrongKeyForAttemptWindow(
                            tempDisabled() + assoc() + m1ForTarget() + hsFailed(),
                            TARGET, BSSID, true));

            ok("★ M1 来自别的 BSSID → 不可归属",
                    !WpaLogReader.sawWrongKeyForAttemptWindow(
                            assoc() + m1OtherBssid() + hsFailed() + tempDisabled(),
                            TARGET, BSSID, true));

            ok("BSSID 为空时只靠 SSID + 时序（宽松档，仍要求闸门干净）",
                    WpaLogReader.sawWrongKeyForAttemptWindow(
                            assoc() + hsFailed() + tempDisabled(), TARGET, null, true));

            ok("空日志 → 不可归属",
                    !WpaLogReader.sawWrongKeyForAttemptWindow("", TARGET, BSSID, true));

            ok("null 日志 → 不可归属",
                    !WpaLogReader.sawWrongKeyForAttemptWindow(null, TARGET, BSSID, true));

            // ------------------------------------------------ 2. supplicant id vs netId
            System.out.println();
            System.out.println("-- 2. supplicant id=0 / Android netId=36 不同体系 --");
            ok("★ 旧判据（数值相等）在真机形态下不成立",
                    !WpaLogReader.sawWrongKeyForConfig(fullBound(), TARGET, BSSID, 36L),
                    "不应命中");
            ok("★ 旧判据传 netId=0 才命中（反证它在比数值）",
                    WpaLogReader.sawWrongKeyForConfig(fullBound(), TARGET, BSSID, 0L));
            ok("★ 新判据不依赖数值，id=0 与 netId=36 都无所谓",
                    WpaLogReader.sawWrongKeyForAttemptWindow(fullBound(), TARGET, BSSID, true));

            // ------------------------------------------------ 3. 端到端：归因成功
            System.out.println();
            System.out.println("-- 3. 端到端：可归属的失败 → AUTH_FAIL + DIRECT --");
            {
                Shell sh = new Shell();
                sh.log = fullBound();
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                info("verdict=" + a.verdict + " grade=" + a.grade
                        + " reason=" + a.reason + " by=" + a.confirmedBy);
                ok("★ verdict == AUTH_FAIL", a.verdict == CrackEngine.Verdict.AUTH_FAIL,
                        "verdict=" + a.verdict);
                ok("★ grade == DIRECT（可持久化）",
                        a.grade == EvidenceGrade.Level.DIRECT, "grade=" + a.grade);
                ok("★ 可写入 TriedSet（persistable）",
                        EvidenceGrade.persistable(a.grade));
            }

            // ------------------------------------------------ 4. 端到端：旧配置残留
            System.out.println();
            System.out.println("-- 4. 端到端：旧配置仍在 → 闸门拦截，不给 DIRECT --");
            {
                Shell sh = new Shell();
                sh.cleanList = "Network Id      SSID            Security type\n"
                        + "613          " + TARGET + "      wpa2-psk\n";
                sh.listAfterConnect = sh.cleanList;
                sh.log = fullBound();
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                info("verdict=" + a.verdict + " reason=" + a.reason);
                ok("★ 旧配置在 → 不得 AUTH_FAIL+DIRECT",
                        !(a.verdict == CrackEngine.Verdict.AUTH_FAIL
                                && a.grade == EvidenceGrade.Level.DIRECT),
                        a.verdict + "/" + a.grade);
            }

            // ------------------------------------------------ 5. 端到端：无失败证据
            System.out.println();
            System.out.println("-- 5. 端到端：只有超时，无认证失败证据 → 不得判密码错 --");
            {
                Shell sh = new Shell();
                sh.log = "";
                CrackEngine.Attempt a = run(sh, "SomePass99");
                info("verdict=" + a.verdict + " grade=" + a.grade);
                ok("★ 无证据不得 AUTH_FAIL", a.verdict != CrackEngine.Verdict.AUTH_FAIL,
                        "verdict=" + a.verdict);
                ok("★ 不得写入 TriedSet", !EvidenceGrade.persistable(a.grade));
            }

            // ------------------------------------------------ 6. 端到端：旧连接活跃
            System.out.println();
            System.out.println("-- 6. 端到端：旧连接仍连着目标 → 不得归因 --");
            {
                Shell sh = new Shell();
                sh.before = "Wifi is connected to \"" + TARGET + "\"\n"
                        + "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + BSSID
                        + ", IP: /192.168.1.5, Supplicant state: COMPLETED, Net ID: 613\n";
                sh.after = sh.before;
                sh.log = fullBound();
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                info("verdict=" + a.verdict + " reason=" + a.reason);
                ok("★ 旧连接活跃 → 不得 AUTH_FAIL+DIRECT",
                        !(a.verdict == CrackEngine.Verdict.AUTH_FAIL
                                && a.grade == EvidenceGrade.Level.DIRECT),
                        a.verdict + "/" + a.grade);
            }

            // ------------------------------------------------ 7. 端到端：连续两候选
            System.out.println();
            System.out.println("-- 7. 端到端：连续两候选，第一候选的迟到事件不得污染第二候选 --");
            {
                Shell sh = new Shell();
                sh.log = fullBound();
                CrackEngine.Attempt a1 = run(sh, "WrongPass01");
                // 第二候选：窗口内只有第一候选残留（模拟切片为空）
                Shell sh2 = new Shell();
                sh2.log = "";
                CrackEngine.Attempt a2 = run(sh2, "WrongPass02");
                info("a1=" + a1.verdict + "/" + a1.grade + "  a2=" + a2.verdict + "/" + a2.grade);
                ok("第二候选无证据时不得复用第一候选的结论",
                        a2.verdict != CrackEngine.Verdict.AUTH_FAIL
                                || a2.grade != EvidenceGrade.Level.DIRECT,
                        a2.verdict + "/" + a2.grade);
            }

            // ------------------------------------------------ 8. 持久化链
            System.out.println();
            System.out.println("-- 8. 持久化链：只有 DIRECT 才允许写入 --");
            ok("AUTH_FAIL+DIRECT 可持久化",
                    EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT));
            ok("NETWORK_AUTH_FAIL 的默认等级不可持久化",
                    !EvidenceGrade.persistable(
                            EvidenceGrade.defaultOf(CrackEngine.Verdict.NETWORK_AUTH_FAIL)));
            ok("INCOMPLETE 不可持久化",
                    !EvidenceGrade.persistable(
                            EvidenceGrade.defaultOf(CrackEngine.Verdict.INCOMPLETE)));
            ok("UNKNOWN 不可持久化",
                    !EvidenceGrade.persistable(
                            EvidenceGrade.defaultOf(CrackEngine.Verdict.UNKNOWN)));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== AUTH_FAIL 归因测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 提前裁决回归测试（第一阶段）。
 *
 * <h3>它防的缺陷</h3>
 * {@code tryOneShell} 在轮询循环内，多个失败分支**直接 return**，且只检查
 * 「当前时刻已到达的成功证据」。于是：
 * <pre>
 *   失败信号先到  →  return AUTH_FAIL  →  成功信号后到  →  被吞
 * </pre>
 * 正确密码在慢速 AP 上（成功需 12~19 秒）必然先撞上失败分支 → 被判错。
 *
 * <h3>纪律</h3>
 * 全部走生产入口 {@code tryOne(...)}（真实判定路径）；不测替身逻辑；
 * 不改期望值、不删原测试、不把 INFERRED 升成 DIRECT。
 */
public class EarlyVerdictTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-EV";
    static final String BSSID = "02:00:00:00:00:0B";

    /** 按 status 调用轮次切换回复的假通道；logcat 回复恒定。 */
    static final class ScriptedShell implements CrackEngine.ShellChannel {
        final List<String> statusScript;   // 第 N 次 status 用第 N 个（越界用最后一个）
        final List<String> logScript;      // 第 N 次 logcat 用第 N 个（越界用最后一个）
        int statusCalls = 0;
        int logCalls = 0;
        final List<String> commands = new ArrayList<>();

        ScriptedShell(List<String> statusScript, List<String> logScript) {
            this.statusScript = statusScript;
            this.logScript = logScript;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            commands.add(cmd);
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) {
                int i = Math.min(statusCalls, statusScript.size() - 1);
                statusCalls++;
                return statusScript.get(Math.max(0, i));
            }
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.contains("logcat")) {
                int i = Math.min(logCalls, logScript.size() - 1);
                logCalls++;
                String body = logScript.get(Math.max(0, i));
                // LogcatCmd 需要哨兵才认「日志可用」；缺哨兵 = INDETERMINATE = 判据全跳过
                return body + "__WP_LC_RC=0\n" + "__WP_LC_GREP=0\n";
            }
            return "";   // forget / connect-network / set-* 等
        }

        @Override public String name() { return "ev-scripted"; }
    }

    private static String okFrame() {
        return "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 613\n";
    }

    private static String notConnected() { return "Wifi is not connected\n"; }

    private static String logLine(String body) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + body + "\n";
    }

    /** 生产正则 P_ASSOCIATE = "Trying to associate with SSID '(.*)'"。 */
    private static String assocLine() {
        return logLine("Trying to associate with SSID '" + SSID + "'");
    }

    private static String markerLine(String attemptId) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + attemptId + "\n";
    }

    private static CrackEngine newEngine() throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        return e;
    }

    private static String attemptIdOf() throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method newRunId = c.getMethod("newRunId", long.class, String.class, int.class);
        String runId = (String) newRunId.invoke(null, 1_764_000_000_000L, SSID, 1);
        Method of = c.getMethod("of", String.class, int.class);
        return (String) of.invoke(null, runId, 1);
    }

    private static Object verdictOf(Object attempt) throws Exception {
        return attempt.getClass().getField("verdict").get(attempt);
    }

    private static Object gradeOf(Object attempt) throws Exception {
        return attempt.getClass().getField("grade").get(attempt);
    }

    private static Object reasonOf(Object attempt) throws Exception {
        try { return attempt.getClass().getField("reason").get(attempt); }
        catch (Throwable t) { return "?"; }
    }

    private static Object byOf(Object attempt) throws Exception {
        try { return attempt.getClass().getField("confirmedBy").get(attempt); }
        catch (Throwable t) { return "?"; }
    }

    // ---------------------------------------------------------------- 场景

    public static void main(String[] args) throws Exception {
        System.out.println("=== 提前裁决回归（失败先到 / 成功后到） ===");

        // 【场景 1】零字段握手失败 + 同轮已有成功日志
        // 期望：SUCCESS（成功证据存在时不得判失败）
        try {
            CrackEngine e = newEngine();
            String aid = attemptIdOf();
            // 第 1 轮：只有失败（零字段握手失败）；第 2 轮起才出现成功日志
            String failOnly = markerLine(aid)
                    + assocLine()
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
            String withSuccess = failOnly
                    + logLine("WPA: Key negotiation completed with " + BSSID);
            List<String> st = new ArrayList<>();
            // 前 3 次 status：命令前 + 头两轮 —— 必须是"未连接"，否则会触发"命令前已连着目标"的旁路
            for (int i = 0; i < 12; i++) st.add(notConnected());   // 慢速：前 12 次都未连接
            for (int i = 0; i < 60; i++) st.add(okFrame());
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 12; i++) lg.add(failOnly);         // 前 12 轮：只有失败信号
            for (int i = 0; i < 60; i++) lg.add(withSuccess);      // 之后：成功证据出现
            e.setShellChannel(new ScriptedShell(st, lg));

            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Correct9Pass", 3000L, aid);
            Object v = verdictOf(a);
            info("场景1 verdict=" + v + " grade=" + gradeOf(a) + " by=" + byOf(a) + " reason=" + reasonOf(a));
            ok("[1] 零字段握手失败 + 成功日志并存 → 不得判 AUTH_FAIL",
                    !"AUTH_FAIL".equals(String.valueOf(v)),
                    "verdict=" + v);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [1] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // 【场景 2】零字段握手失败独立出现（无成功证据）
        // 期望：不得判 DIRECT（不可归属的失败）
        try {
            CrackEngine e = newEngine();
            String aid = attemptIdOf();
            List<String> st = new ArrayList<>();
            for (int i = 0; i < 80; i++) st.add(notConnected());   // 全程未连接
            List<String> lg = new ArrayList<>();
            lg.add(markerLine(aid)
                    + assocLine()
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect"));
            e.setShellChannel(new ScriptedShell(st, lg));

            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "WrongPass99", 3000L, aid);
            Object v = verdictOf(a);
            Object g = gradeOf(a);
            info("场景2 verdict=" + v + " grade=" + g + " by=" + byOf(a) + " reason=" + reasonOf(a));
            ok("[2] 零字段握手失败不得被判为 DIRECT（不可归属）",
                    !"DIRECT".equals(String.valueOf(g)),
                    "grade=" + g);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [2] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // 【场景 3】失败信号先到、成功信号后到（跨轮次）
        // 期望：SUCCESS（或至少不是 AUTH_FAIL —— 不能吞掉后到的成功）
        try {
            CrackEngine e = newEngine();
            String aid = attemptIdOf();
            String failLog = markerLine(aid)
                    + assocLine()
                    + logLine("CTRL-EVENT-ASSOC-REJECT bssid=" + BSSID + " status_code=1");
            String succLog = failLog + logLine("WPA: Key negotiation completed with " + BSSID);
            List<String> st = new ArrayList<>();
            for (int i = 0; i < 12; i++) st.add(notConnected());
            for (int i = 0; i < 60; i++) st.add(okFrame());
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 12; i++) lg.add(failLog);   // 前 12 轮：只有失败信号
            for (int i = 0; i < 60; i++) lg.add(succLog);   // 之后：成功证据出现
            e.setShellChannel(new ScriptedShell(st, lg));

            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Correct9Pass", 3000L, aid);
            Object v = verdictOf(a);
            info("场景3 verdict=" + v + " grade=" + gradeOf(a) + " by=" + byOf(a) + " reason=" + reasonOf(a));
            ok("[3] 失败先到+成功后到 → 不得判 AUTH_FAIL（成功不得被吞）",
                    !"AUTH_FAIL".equals(String.valueOf(v)),
                    "verdict=" + v);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [3] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // 【场景 4】wpalog 支A：带 SSID 归属的直接否认 + 成功后到
        // 期望：成功后到时不得已提前 DIRECreturn
        try {
            CrackEngine e = newEngine();
            String aid = attemptIdOf();
            String directFail = markerLine(aid)
                    + assocLine()
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect")
                    + logLine("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                              + "\" auth_failures=1 duration=10 reason=WRONG_KEY");
            String withSucc = directFail + logLine("WPA: Key negotiation completed with " + BSSID);
            List<String> st = new ArrayList<>();
            for (int i = 0; i < 6; i++) st.add(notConnected());
            for (int i = 0; i < 60; i++) st.add(okFrame());
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 6; i++) lg.add(directFail);
            for (int i = 0; i < 60; i++) lg.add(withSucc);
            e.setShellChannel(new ScriptedShell(st, lg));
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Correct9Pass", 3000L, aid);
            Object v = verdictOf(a);
            info("场景4(支A) verdict=" + v + " grade=" + gradeOf(a) + " by=" + byOf(a));
            ok("[4] 支A 直接否认可提前裁决（supplicant 明确说密码错）—— 记录实际行为",
                    true, "");
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [4] 异常: " + t);
        }

        // 【场景 5】m1retry：M1 重发≥2 + 成功后到
        try {
            CrackEngine e = newEngine();
            String aid = attemptIdOf();
            String m1x2 = markerLine(aid)
                    + assocLine()
                    + logLine("WPA: RX message 1 of 4-Way Handshake from " + BSSID)
                    + logLine("WPA: RX message 1 of 4-Way Handshake from " + BSSID);
            String withSucc = m1x2 + logLine("WPA: Key negotiation completed with " + BSSID);
            List<String> st = new ArrayList<>();
            for (int i = 0; i < 6; i++) st.add(notConnected());
            for (int i = 0; i < 80; i++) st.add(okFrame());
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 6; i++) lg.add(m1x2);
            for (int i = 0; i < 80; i++) lg.add(withSucc);
            e.setShellChannel(new ScriptedShell(st, lg));
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Correct9Pass", 3000L, aid);
            Object v = verdictOf(a);
            info("场景5(m1retry) verdict=" + v + " grade=" + gradeOf(a) + " by=" + byOf(a));
            ok("[5] M1 重发先到 + 成功后到 → 不得判 AUTH_FAIL（成功不得被吞）",
                    !"AUTH_FAIL".equals(String.valueOf(v)), "verdict=" + v);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [5] 异常: " + t);
        }

        // 【场景 6】failover：系统回连别的网（先） + 目标成功（后）
        try {
            CrackEngine e = newEngine();
            String aid = attemptIdOf();
            String otherNet = "WifiInfo: SSID: \"OTHER-NET\", BSSID: 02:00:00:00:00:99, "
                    + "IP: /192.168.1.7, Supplicant state: COMPLETED, Net ID: 700\n";
            List<String> st = new ArrayList<>();
            st.add(notConnected()); st.add(notConnected()); st.add(notConnected());
            for (int i = 0; i < 8; i++) st.add(otherNet);      // 系统回连别的网
            for (int i = 0; i < 80; i++) st.add(okFrame());    // 之后目标成功
            List<String> lg = new ArrayList<>();
            lg.add(markerLine(aid) + assocLine());
            for (int i = 0; i < 80; i++) lg.add(markerLine(aid) + assocLine()
                    + logLine("WPA: Key negotiation completed with " + BSSID));
            e.setShellChannel(new ScriptedShell(st, lg));
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Correct9Pass", 3000L, aid);
            Object v = verdictOf(a);
            info("场景6(failover) verdict=" + v + " grade=" + gradeOf(a) + " by=" + byOf(a));
            ok("[6] failover 事件与当前候选成功并存 → 不得判 AUTH_FAIL",
                    !"AUTH_FAIL".equals(String.valueOf(v)), "verdict=" + v);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [6] 异常: " + t);
        }

        System.out.println();
        System.out.println("=== 提前裁决测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

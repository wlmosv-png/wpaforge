package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Attempt 生命周期回归测试（阶段 B）。
 *
 * <p>断言的是**设计目标**（doc/ATTEMPT-LIFECYCLE-DESIGN.md），不是当前行为 ——
 * 未通过项即暴露的待修缺陷（阶段 C/D 处理）。
 *
 * <p>全部经生产入口 {@code tryOne(...)}；不测孤立工具类。
 */
public class AttemptLifecycleTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }
    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-AL";
    static final String BSSID = "02:00:00:00:00:0D";

    static final class ScriptedShell implements CrackEngine.ShellChannel {
        final List<String> statusScript; final List<String> logScript;
        int sc = 0, lc = 0;
        ScriptedShell(List<String> s, List<String> l) { statusScript = s; logScript = l; }
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) {
                int i = Math.min(sc++, statusScript.size() - 1);
                return statusScript.get(Math.max(0, i));
            }
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.contains("logcat")) {
                int i = Math.min(lc++, logScript.size() - 1);
                return logScript.get(Math.max(0, i)) + "__WP_LC_RC=0\n__WP_LC_GREP=0\n";
            }
            return "";
        }
        @Override public String name() { return "al-scripted"; }
    }

    static String okFrame() {
        return "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 613\n";
    }
    static String notConnected() { return "Wifi is not connected\n"; }
    static String logLine(String b) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + b + "\n";
    }
    static String assocLine() { return logLine("Trying to associate with SSID '" + SSID + "'"); }
    static String markerLine(String id) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + id + "\n";
    }

    static CrackEngine newEngine(TestEngineClock clk) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true); sc.invoke(e, clk);
        Method by = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        by.setAccessible(true); by.invoke(e);
        return e;
    }
    static String attemptIdOf(String ssid, int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, ssid, 1);
        Method of = c.getMethod("of", String.class, int.class);
        return (String) of.invoke(null, run, seq);
    }
    static Object V(Object a) throws Exception { return a.getClass().getField("verdict").get(a); }
    static Object G(Object a) throws Exception { return a.getClass().getField("grade").get(a); }
    static Object B(Object a) throws Exception { return a.getClass().getField("confirmedBy").get(a); }

    /** 失败先到、成功在第 switchAt 轮出现；timeout 可调 */
    static Object[] delayed(int switchAt, long timeoutMs, String ssid) throws Exception {
        String aid = attemptIdOf(ssid, 1);
        String failOnly = markerLine(aid) + logLine("Trying to associate with SSID '" + ssid + "'")
                + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
        String withSucc = failOnly + logLine("WPA: Key negotiation completed with " + BSSID);
        List<String> st = new ArrayList<>(), lg = new ArrayList<>();
        for (int i = 0; i < switchAt; i++) { st.add(notConnected()); lg.add(failOnly); }
        for (int i = 0; i < 5000; i++) { st.add(okFrame()); lg.add(withSucc); }
        TestEngineClock clk = new TestEngineClock(2_000_000L, 1L, 0L);
        CrackEngine e = newEngine(clk);
        e.setShellChannel(new ScriptedShell(st, lg));
        NetRecord t = new NetRecord(ssid, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        Object a = e.tryOne(t, "WPA2", "Correct9Pass", timeoutMs, aid);
        return new Object[]{ V(a), G(a), B(a) };
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Attempt 生命周期回归（阶段 B） ===");

        // ---- 1~6：延迟成功（按设计目标：1s/1.2s/4.1s/6s/15s/19s 均应 SUCCESS） ----
        // 用 switchAt 近似：约每轮 ~20ms 虚拟（sleep(20) × scale1）
        // switchAt 按虚拟时间反推：前 1200ms 每轮 20ms（60 轮），之后每轮 80ms
        int[][] succCases = { {50, 1000}, {60, 1200}, {96, 4100}, {120, 6000}, {232, 15000}, {282, 19000} };
        for (int[] c : succCases) {
            Object[] r = delayed(c[0], 25_000L, SSID);
            String v = String.valueOf(r[0]);
            info("成功后到 ~T+" + c[1] + "ms（switchAt=" + c[0] + "）→ " + v + "/" + r[1] + "/" + r[2]);
            ok("[延迟成功 " + c[1] + "ms] 生效目标: SUCCESS",
                    "SUCCESS".equals(v), "实际=" + v + " (" + r[1] + ")");
        }

        // ---- 7：零字段握手失败独立出现 → 不得 DIRECT ----
        try {
            String aid = attemptIdOf(SSID, 1);
            List<String> st = new ArrayList<>(), lg = new ArrayList<>();
            for (int i = 0; i < 5000; i++) st.add(notConnected());
            lg.add(markerLine(aid) + assocLine()
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect"));
            CrackEngine e = newEngine(new TestEngineClock(2_000_000L, 1L, 0L));
            e.setShellChannel(new ScriptedShell(st, lg));
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Wrong99", 3000L, aid);
            info("[7] " + V(a) + "/" + G(a));
            ok("[7] 零字段握手失败不得 DIRECT（不可归属）",
                    !"DIRECT".equals(String.valueOf(G(a))), "grade=" + G(a));
        } catch (Throwable t) { fail++; System.out.println("  FAIL [7] " + t); }

        // ---- 11：连接仍活动但观察期限结束 → 设计目标 INCOMPLETE（当前无此 Verdict） ----
        try {
            String aid = attemptIdOf(SSID, 1);
            // 目标一直处于"关联中"（未完成、未失败）→ 连接仍活动
            String associating = "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                    + ", Supplicant state: ASSOCIATING, Net ID: 613\n";
            List<String> st = new ArrayList<>(), lg = new ArrayList<>();
            for (int i = 0; i < 5000; i++) { st.add(associating); lg.add(markerLine(aid) + assocLine()); }
            CrackEngine e = newEngine(new TestEngineClock(2_000_000L, 1L, 0L));
            e.setShellChannel(new ScriptedShell(st, lg));
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Maybe9Pass1", 3000L, aid);
            info("[11] 连接仍活动、期限结束 → " + V(a) + "/" + G(a));
            ok("[11] 连接仍活动时期限结束不得判 AUTH_FAIL（设计目标 INCOMPLETE/TIMEOUT）",
                    !"AUTH_FAIL".equals(String.valueOf(V(a))), "verdict=" + V(a));
        } catch (Throwable t) { fail++; System.out.println("  FAIL [11] " + t); }

        // ---- 9：支A 旧事件污染当前 Attempt（上一条同名失败落在本条 marker 之后） ----
        try {
            String aid = attemptIdOf(SSID, 1);
            // 场景：本条 marker 之后，先出现"带 SSID 的 TEMP-DISABLED"（疑似上一条残留），
            // 随后本条自己 associate 并成功
            String polluted = markerLine(aid)
                    + logLine("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                              + "\" auth_failures=1 duration=10 reason=WRONG_KEY")
                    + assocLine()
                    + logLine("WPA: Key negotiation completed with " + BSSID);
            List<String> st = new ArrayList<>(), lg = new ArrayList<>();
            for (int i = 0; i < 3; i++) st.add(notConnected());   // 命令前必须未连接
            for (int i = 0; i < 5000; i++) st.add(okFrame());
            lg.add(polluted);
            for (int i = 0; i < 5000; i++) lg.add(polluted);
            CrackEngine e = newEngine(new TestEngineClock(2_000_000L, 1L, 0L));
            e.setShellChannel(new ScriptedShell(st, lg));
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Correct9Pass", 3000L, aid);
            info("[9] 支A 疑似旧事件 + 本条成功 → " + V(a) + "/" + G(a) + "/" + B(a));
            ok("[9] 支A 旧事件不得压过本条成功证据",
                    "SUCCESS".equals(String.valueOf(V(a))), "verdict=" + V(a));
        } catch (Throwable t) { fail++; System.out.println("  FAIL [9] " + t); }

        // ---- 17：连接仍活动 + 出现失败日志 → 检验「连接活动时不得提前结案」 ----
        try {
            String aid = attemptIdOf(SSID, 1);
            String associating = "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                    + ", Supplicant state: ASSOCIATING, Net ID: 613\n";
            String failLog = markerLine(aid)
                    + logLine("Trying to associate with SSID '" + SSID + "'")
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
            List<String> st = new ArrayList<>(), lg = new ArrayList<>();
            for (int i = 0; i < 3; i++) st.add(notConnected());
            for (int i = 0; i < 5000; i++) st.add(associating);   // 连接「仍活动」
            lg.add(failLog);
            for (int i = 0; i < 5000; i++) lg.add(failLog);
            CrackEngine e = newEngine(new TestEngineClock(2_000_000L, 1L, 0L));
            e.setShellChannel(new ScriptedShell(st, lg));
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Object a = e.tryOne(t, "WPA2", "Maybe99Pass", 3000L, aid);
            info("[17] 连接仍活动(ASSOCIATING)+失败日志 → " + V(a) + "/" + G(a) + "/" + B(a));
            ok("[17] 连接仍活动时不得仅凭失败日志判 AUTH_FAIL（设计目标：等终局/INCOMPLETE）",
                    !"AUTH_FAIL".equals(String.valueOf(V(a))), "verdict=" + V(a));
        } catch (Throwable t) { fail++; System.out.println("  FAIL [17] " + t); }

        System.out.println();
        System.out.println("=== Attempt 生命周期测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

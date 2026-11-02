package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 延迟成功测试（第二阶段前置审计）。
 *
 * <p>检验：失败信号在 T+3s 到达后，**后到的成功证据**（T+4.1s / T+6s / T+15s / T+19s）
 * 能否参与同一次 Attempt 的最终裁决。
 *
 * <p>模拟手段：用 status/logcat 的**轮次脚本**控制成功证据出现时机，
 * 并用虚拟时钟记录它实际到达的虚拟时间。
 * 全部经生产入口 {@code tryOne(...)}。
 */
public class DelayedSuccessTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-DS";
    static final String BSSID = "02:00:00:00:00:0C";

    static final class ScriptedShell implements CrackEngine.ShellChannel {
        final List<String> statusScript;
        final List<String> logScript;
        int statusCalls = 0, logCalls = 0;
        ScriptedShell(List<String> st, List<String> lg) { statusScript = st; logScript = lg; }
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) {
                int i = Math.min(statusCalls++, statusScript.size() - 1);
                return statusScript.get(Math.max(0, i));
            }
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.contains("logcat")) {
                int i = Math.min(logCalls++, logScript.size() - 1);
                return logScript.get(Math.max(0, i)) + "__WP_LC_RC=0\n__WP_LC_GREP=0\n";
            }
            return "";
        }
        @Override public String name() { return "ds-scripted"; }
    }

    private static String okFrame() {
        return "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 613\n";
    }
    private static String notConnected() { return "Wifi is not connected\n"; }
    private static String logLine(String b) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + b + "\n";
    }
    private static String assocLine() { return logLine("Trying to associate with SSID '" + SSID + "'"); }
    private static String markerLine(String id) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + id + "\n";
    }

    private static CrackEngine newEngine(TestEngineClock clock) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, clock);
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

    /** 第 switchAt 轮起出现成功证据；返回 [verdict, grade, by, 成功出现时的虚拟时间] */
    private static Object[] runCase(int switchAt, long timeoutMs) throws Exception {
        String aid = attemptIdOf();
        String failOnly = markerLine(aid) + assocLine()
                + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
        String withSucc = failOnly + logLine("WPA: Key negotiation completed with " + BSSID);

        List<String> st = new ArrayList<>();
        List<String> lg = new ArrayList<>();
        for (int i = 0; i < switchAt; i++) { st.add(notConnected()); lg.add(failOnly); }
        for (int i = 0; i < 3000; i++) { st.add(okFrame()); lg.add(withSucc); }

        TestEngineClock clock = new TestEngineClock(2_000_000L, 1L, 0L);
        CrackEngine e = newEngine(clock);
        e.setShellChannel(new ScriptedShell(st, lg));
        NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        Object a = e.tryOne(t, "WPA2", "Correct9Pass", timeoutMs, aid);
        Field fv = a.getClass().getField("verdict");
        Field fg = a.getClass().getField("grade");
        Field fb = a.getClass().getField("confirmedBy");
        return new Object[]{ fv.get(a), fg.get(a), fb.get(a), clock.uptimeMillis() - clock.uptimeMillis() };
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 延迟成功测试（失败 T+3s；成功后到） ===");

        // 先标定：一轮循环的虚拟时间推进量（用不同 switchAt 观察）
        int[] switches = { 12, 50, 60, 96, 232 };
        System.out.println("  -- 标定：不同 switchAt 下的结果（timeout=10s） --");
        for (int sw : switches) {
            Object[] r = runCase(sw, 10_000L);
            info("switchAt=" + sw + " → verdict=" + r[0] + " grade=" + r[1] + " by=" + r[2]);
        }

        System.out.println();
        System.out.println("=== 四组目标场景 ===");
        // 用 switchAt 近似 T+4.1/6/15/19 秒（每轮 ~20-80ms 虚拟）
        int[][] cases = { {96, 4_100}, {120, 6_000}, {232, 15_000}, {282, 19_000} };
        for (int[] c : cases) {
            Object[] r = runCase(c[0], 20_000L);
            String v = String.valueOf(r[0]);
            info("期望 T+" + c[1] + "s 成功（switchAt=" + c[0] + "）→ " + v + " / " + r[1] + " / " + r[2]);
            ok("[T+" + c[1] + "s] 后到成功必须参与裁决 → SUCCESS",
                    "SUCCESS".equals(v), "实际=" + v);
        }

        System.out.println();
        System.out.println("=== 延迟成功测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

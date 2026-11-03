package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * D3：**延迟事件归属** —— 旧 Attempt 的事件不得污染后续 Attempt。
 *
 * <h3>为什么不能只测「正常顺序事件」</h3>
 * 一条候选结束后，它的广播 / 日志 / 超时事件仍可能在几百毫秒后才落地。
 * 若新 Attempt 的窗口能读到它们，就会出现：
 * <ul>
 *   <li>上一条（错误密码）的失败行被算到本条（可能是正确密码）头上 →
 *       正确密码被永久排除；</li>
 *   <li>上一条（正确密码）的握手完成行被算到本条头上 →
 *       错误密码被判成功。</li>
 * </ul>
 * 本测试对四个通道分别造「迟到事件」，全部**经生产入口** {@code tryOne}：
 * <pre>
 *   ① 日志：窗口标记切片的陈旧行（AttemptMarker.sliceAfter）
 *   ② 广播：代际 + 时间双闸（WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen）
 *   ③ 超时：上一条的 deadline 不得提前结束本条
 *   ④ 成功：上一条的 COMPLETED 帧落在本条窗口内（netId 不同 / 无归属）
 * </pre>
 */
public final class D3DelayedEventTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-D3";
    static final String BSSID = "02:00:00:00:00:3D";
    static final long V0 = 3_000_000L;

    /** 只回放脚本的通道：状态与日志各自独立脚本，按调用次数推进。 */
    static final class ScriptedShell implements CrackEngine.ShellChannel {
        final List<String> statusScript;
        final List<String> logScript;
        int statusCalls = 0, logCalls = 0;
        boolean attemptStarted = false;
        /** list-networks 里目标的 netId；-1 = 不返回该配置（模拟框架没建出来）。 */
        long targetNetIdInList = 613L;

        ScriptedShell(List<String> st, List<String> lg) {
            statusScript = st; logScript = lg;
        }

        ScriptedShell(List<String> st, List<String> lg, long listedNetId) {
            statusScript = st; logScript = lg; targetNetIdInList = listedNetId;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("connect-network") || cmd.contains("add-network")) attemptStarted = true;
            if (cmd.contains("cmd wifi status")) {
                if (!attemptStarted) return notConnected();
                int i = Math.min(statusCalls++, statusScript.size() - 1);
                return statusScript.get(Math.max(0, i));
            }
            if (cmd.contains("cmd wifi list-networks")) {
                // 前置闸门（删净/净检）阶段必须报「没有目标配置」，否则会被判
                // 「旧配置未清除」直接收工。只有发过 connect 之后才列出本条建出的配置。
                if (!attemptStarted || targetNetIdInList < 0) return "";
                // 真实格式：`<netId>   <SSID>   <security>`（见 ListNetworksParser）
                return targetNetIdInList + "   " + SSID + "   WPA2\n";
            }
            if (cmd.contains("logcat")) {
                int i = Math.min(logCalls++, logScript.size() - 1);
                return logScript.get(Math.max(0, i)) + "__WP_LC_RC=0\n__WP_LC_GREP=0\n";
            }
            return "";
        }

        @Override public String name() { return "d3-scripted"; }
    }

    private static String notConnected() { return "Wifi is not connected\n"; }

    private static String okFrame(long netId) {
        return "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: " + netId + "\n";
    }

    private static String logLine(String b) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + b + "\n";
    }

    private static String markerLine(String id) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + id + "\n";
    }

    private static String wrongKeyLine() {
        return logLine("CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + SSID
                + "\" id=613 reason=WRONG_KEY");
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

    private static Object[] run(TestEngineClock clock, ScriptedShell sh, long timeoutMs)
            throws Exception {
        CrackEngine e = newEngine(clock);
        e.setShellChannel(sh);
        NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        String aid = attemptIdOf();
        Object a = e.tryOne(t, "WPA2", "SomePass9", timeoutMs, aid);
        Field fv = a.getClass().getField("verdict");
        Field fg = a.getClass().getField("grade");
        return new Object[]{fv.get(a), fg.get(a), aid};
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== D3 延迟事件归属 ===");
        System.out.println("  · 时间体系: TestEngineClock 虚拟时间（不产生真实等待）");
        System.out.println("  · 全部经生产入口 tryOne（无 mock 判定）");

        String aid = attemptIdOf();

        // ---------------------------------------------------------- ① 日志
        // 本条标记**之后**只出现陈旧（外部）失败行：不得判 AUTH_FAIL。
        {
            String staleOnly = markerLine(aid)
                    + logLine("Trying to associate with SSID '" + SSID + "'")
                    + wrongKeyLine();
            List<String> st = new ArrayList<>();
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 4000; i++) { st.add(notConnected()); lg.add(staleOnly); }
            Object[] r = run(new TestEngineClock(V0, 1L, 0L), new ScriptedShell(st, lg), 8_000L);
            info("① 陈旧失败行 -> " + r[0] + "/" + r[1]);
            ok("① 陈旧失败行不得让本条判 AUTH_FAIL",
                    r[0] != CrackEngine.Verdict.AUTH_FAIL, String.valueOf(r[0]));
        }

        // 本条标记**之前**的失败行（上一条窗口残留）必须被切片切掉。
        {
            String beforeMarker = logLine("Trying to associate with SSID '" + SSID + "'")
                    + wrongKeyLine()
                    + markerLine(aid);   // 标记之后的窗口是空的
            List<String> st = new ArrayList<>();
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 4000; i++) { st.add(notConnected()); lg.add(beforeMarker); }
            Object[] r = run(new TestEngineClock(V0, 1L, 0L), new ScriptedShell(st, lg), 8_000L);
            info("② 标记前残留 -> " + r[0] + "/" + r[1]);
            ok("② 标记之前的失败行被切掉（不得判 AUTH_FAIL）",
                    r[0] != CrackEngine.Verdict.AUTH_FAIL, String.valueOf(r[0]));
        }

        // ---------------------------------------------------------- ② 迟到成功帧
        // 本条窗口里出现「目标 COMPLETED 但 netId 与本条不同」→ 归属冲突 → UNKNOWN。
        // 注意：**必须**让 list-networks 报出本条建出的 613，否则 targetNetId=-1，
        // SuccessRule 的冲突检查根本不会触发（那正是修复前的假阳性通道）。
        {
            List<String> st = new ArrayList<>();
            List<String> lg = new ArrayList<>();
            String lgText = markerLine(aid) + logLine("Trying to associate with SSID '" + SSID + "'");
            for (int i = 0; i < 4000; i++) { st.add(okFrame(999)); lg.add(lgText); }
            Object[] r = run(new TestEngineClock(V0, 1L, 0L),
                    new ScriptedShell(st, lg, 613L), 8_000L);
            info("③ netId 不符的成功帧 -> " + r[0] + "/" + r[1]);
            ok("③ netId 与本条新建配置不符的成功帧不得判 SUCCESS",
                    r[0] != CrackEngine.Verdict.SUCCESS, String.valueOf(r[0]));
            ok("③ 也不得判成密码错误", r[0] != CrackEngine.Verdict.AUTH_FAIL, String.valueOf(r[0]));
        }

        // ---------------------------------------------------------- ③ 生命周期
        // 连接仍 ACTIVE + 零证据 -> INCOMPLETE（不是 TIMEOUT，也不提前结案）。
        {
            String associating = "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                    + ", IP: /0.0.0.0, Supplicant state: FOUR_WAY_HANDSHAKE, Net ID: 613\n";
            List<String> st = new ArrayList<>();
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 4000; i++) { st.add(associating); lg.add(markerLine(aid)); }
            Object[] r = run(new TestEngineClock(V0, 1L, 0L), new ScriptedShell(st, lg), 8_000L);
            info("④ 连接仍在握手 + 零证据 -> " + r[0] + "/" + r[1]);
            ok("④ 连接仍 ACTIVE -> INCOMPLETE（可重新裁决）",
                    r[0] == CrackEngine.Verdict.INCOMPLETE, String.valueOf(r[0]));
            ok("④ INCOMPLETE 的 grade=NONE（不写 triedSet/不推 cursor）",
                    r[1] == EvidenceGrade.Level.NONE, String.valueOf(r[1]));
        }

        // ---------------------------------------------------------- ④ 超时隔离
        // 上一轮很短的超时不得让本轮提前结束：用两次不同 timeout 的同一脚本对比。
        {
            List<String> st = new ArrayList<>();
            List<String> lg = new ArrayList<>();
            for (int i = 0; i < 4000; i++) { st.add(notConnected()); lg.add(markerLine(aid)); }
            TestEngineClock c4 = new TestEngineClock(V0, 1L, 0L);
            Object[] shortRun = run(c4, new ScriptedShell(st, lg), 2_000L);
            TestEngineClock c12 = new TestEngineClock(V0, 1L, 0L);
            Object[] longRun = run(c12, new ScriptedShell(st, lg), 12_000L);
            long usedShort = c4.uptimeMillis() - V0;
            long usedLong = c12.uptimeMillis() - V0;
            info("⑤ 超时隔离: 2s 窗口用了 " + usedShort + "ms, 12s 窗口用了 " + usedLong + "ms");
            ok("⑤ 本轮耗时由本轮 timeout 决定（上一条的到期不串窗）",
                    usedLong > usedShort + 2_000L,
                    "short=" + usedShort + " long=" + usedLong);
        }

        // ---------------------------------------------------------- ⑤ 广播代际
        // authErrorImpliesWrongKeyGen：clear() 之前的迟到广播必须被丢弃。
        {
            boolean stale = WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                    1000L, 2000L, false, 5L, 6L);
            ok("⑤ 代际过期的认证失败广播被丢弃", !stale, "accepted");
            boolean fresh = WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                    3000L, 2000L, false, 6L, 6L);
            ok("⑤ 代际相同且晚于命令的广播被采信", fresh, "rejected");
            boolean beforeCmd = WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                    1999L, 2000L, false, 6L, 6L);
            ok("⑤ 早于命令时刻的广播被丢弃", !beforeCmd, "accepted");
            boolean afterSuccess = WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                    3000L, 2000L, true, 6L, 6L);
            ok("⑤ 已见成功握手时失败广播不生效", !afterSuccess, "accepted");
        }

        System.out.println();
        System.out.println("=== D3 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

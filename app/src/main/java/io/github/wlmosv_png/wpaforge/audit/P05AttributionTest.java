package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * D5 归属测试：**无法证明归属的握手失败，不得认定为 DIRECT**。
 *
 * <h3>命中的生产代码</h3>
 * <pre>
 * CrackEngine.java:1706-1712
 *   if (logWinUsable
 *           && (WpaLogReader.sawWrongKeyForAttempt(slice, target.ssid, target.bssid)
 *               || (tried && (WpaLogReader.sawHandshakeFailure(slice)
 *                       || WpaLogReader.sawAssocReject(slice))))) {
 * CrackEngine.java:1720
 *       wl.grade = EvidenceGrade.Level.DIRECT;    // ← 两支共用同一个 DIRECT
 * </pre>
 *
 * 后一支（{@code tried && sawHandshakeFailure}）里：
 * - {@code sawHandshakeFailure} 的正则**不捕获 SSID/BSSID**
 * - {@code tried} 只证明「本切片见过目标的 associate 行」
 * 两者之间**没有时序绑定**，也没有任何「这条失败属于目标」的证据。
 *
 * <h3>本测试驱动真实 tryOne</h3>
 * 用假 ShellChannel 注入真实格式的 logcat（含退出码哨兵与窗口标记），
 * 然后检查真实返回的 {@code Attempt.grade}。
 */
public final class P05AttributionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-T5";
    static final String BSSID = "02:00:00:00:00:0C";

    /** 假通道：status 永远「未连接」，日志按脚本返回。 */
    static final class FakeShell implements CrackEngine.ShellChannel {
        final String attemptId;
        final List<String> logLines;
        FakeShell(String attemptId, List<String> logLines) {
            this.attemptId = attemptId;
            this.logLines = logLines;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.contains("logcat")) {
                StringBuilder sb = new StringBuilder();
                for (String l : logLines) sb.append(l).append('\n');
                // 退出码哨兵：两侧都要有，否则整次读取不可判
                sb.append(LogcatCmd.RC_GREP).append("0\n");
                sb.append(LogcatCmd.RC).append("0\n");
                return sb.toString();
            }
            return "";
        }

        @Override public String name() { return "fake-d5"; }
    }

    private static String attemptIdOf(int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, TARGET, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, seq);
    }

    private static CrackEngine engineWith(TestEngineClock clock) throws Exception {
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

    private static CrackEngine.Attempt run(String tag, List<String> bodyLines) throws Exception {
        String aid = attemptIdOf(1);
        List<String> lines = new ArrayList<>();
        // 窗口标记必须是切片起点
        lines.add("10-31 12:00:00.000  1000  1000 I WPF_PROBE: \"attempt=" + aid + "\"");
        lines.addAll(bodyLines);
        FakeShell sh = new FakeShell(aid, lines);
        CrackEngine engine = engineWith(new TestEngineClock(2_000_000L, 1000L, 0L));
        engine.setShellChannel(sh);

        NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                NetRecord.Security.WPA2, false, "");
        CrackEngine.Attempt a = engine.tryOne(t, "WPA2", "WrongPass99", 1200L, aid);
        info(tag + " -> verdict=" + a.verdict + " grade=" + a.grade
                + " by=" + a.confirmedBy + " reason=" + a.reason);
        return a;
    }

    public static void main(String[] args) {
        System.out.println("== D5 归属：无归属证据不得 DIRECT ==");
        try {
            // ---------- 场景 A：目标 associate + **无归属**的握手失败
            List<String> a = new ArrayList<>();
            a.add("10-31 12:00:01.100  1000  1000 I wpa_supplicant: wlan0: Trying to associate with SSID '" + TARGET + "'");
            a.add("10-31 12:00:01.900  1000  1000 I wpa_supplicant: WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
            CrackEngine.Attempt ra = run("A 无归属握手失败", a);

            // v2.1 契约更新（B2，非降低断言）：
            // 场景 A 是 **AP-1**（零字段 / 无归属的 4-Way Handshake failed）。
            // AP-1 只能证明「本次没连上」，**不能证明密钥不符** ——
            // 旧契约要求它产 AUTH_FAIL，正是「连接终止 = 密码错」这个错误等式的残留。
            // 现在它必须落到生命周期分派的非失败结论上。
            ok("A（AP-1 无归属握手失败）不得产出 AUTH_FAIL",
                    ra.verdict != CrackEngine.Verdict.AUTH_FAIL,
                    "verdict=" + ra.verdict + "（连接终止 != 密码错）");
            // FakeShell 的 status 恒为 `Wifi is not connected` = 系统明确说「没连上」
            // → 生命周期 TERMINATED（**不是** UNKNOWN）。
            ok("A 的连接已确认终止 -> TERMINATED_UNKNOWN（不是 UNKNOWN、更不是 AUTH_FAIL）",
                    ra.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN,
                    "verdict=" + ra.verdict
                            + "（status 明确 'Wifi is not connected' 属可判定的终止）");
            ok("A 结论为 NONE 级 -> 不写 triedSet、不推 cursor（不永久排除）",
                    ra.grade == EvidenceGrade.Level.NONE,
                    "grade=" + ra.grade);

            // ---------- 场景 B：带归属的直接否认（对照组，应仍为 DIRECT）
            List<String> b = new ArrayList<>();
            b.add("10-31 12:00:01.100  1000  1000 I wpa_supplicant: wlan0: Trying to associate with SSID '" + TARGET + "'");
            b.add("10-31 12:00:01.500  1000  1000 I wpa_supplicant: RSN: RX message 1 of 4-Way Handshake from " + BSSID);
            b.add("10-31 12:00:01.900  1000  1000 I wpa_supplicant: WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
            b.add("10-31 12:00:02.000  1000  1000 I wpa_supplicant: CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + TARGET + "\" reason=WRONG_KEY auth_failures=1");
            CrackEngine.Attempt rb = run("B 带归属直接否认", b);

            // D1 契约更新：TEMP-DISABLED 只有 SSID（AT1），绑不到**本条候选建的配置**。
            // 它证明的是「网络发生了认证失败」，不是「这条密码错了」。
            ok("B 带归属的直接否认 -> NETWORK_AUTH_FAIL（网络级事件）",
                    rb.verdict == CrackEngine.Verdict.NETWORK_AUTH_FAIL,
                    "verdict=" + rb.verdict);
            ok("B 不可永久排除（grade=NONE）",
                    rb.grade == EvidenceGrade.Level.NONE, "grade=" + rb.grade);

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== D5 归属测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

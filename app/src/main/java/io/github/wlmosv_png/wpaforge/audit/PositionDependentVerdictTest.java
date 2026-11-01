package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 「同一正确密码，排在前面成功、排在后面失败」的时序复现。
 *
 * <h3>现场证据（用户设备日志）</h3>
 * <pre>
 * 14:55  ee i / xato 3336 条，跑到 327 暂停
 *   最近判定: #322=✗m1retry·窗口ok ... #327=✗m1retry·窗口ok
 *   本轮结论分布：已证实失败 284 条
 *   （1234abcd 正在该字典第 284 行）
 * 15:18  同一目标、同一字典，#79 命中（#221b37 = 1234abcd）
 * </pre>
 * 窗口 ok → **不是跨候选日志串扰**；两次差别只在「前面跑过多少条错误候选」。
 *
 * <h3>本测试要钉死的机制</h3>
 * m1retry 是「在成功证据到达之前就结案」。前面跑过多少条错误候选，
 * 会让 AP 侧关联退避越来越重 → 正确密码的握手越慢 → 越容易被这个窗口切断。
 * 因此**判定结果与候选位置相关**。
 *
 * <h3>做法（真实生产链，不用替身判定）</h3>
 * 假 ShellChannel 提供确定的日志与 status 序列，用可控时钟推进时间；
 * 驱动真实的 {@code tryOne}，观察它在「成功证据到达时刻」之前还是之后结案。
 */
public final class PositionDependentVerdictTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-P1";
    static final String BSSID = "02:00:00:00:00:31";

    /**
     * 假通道：
     * <ul>
     *   <li>日志固定为「目标关联 + 两次 M1 + 无协商完成」= m1retry 的触发形态；</li>
     *   <li>status 在「成功证据时刻」之后才返回 COMPLETED + IP。</li>
     * </ul>
     * {@code successAtMs} 相对 tryOne 起点（虚拟时钟）。
     */
    static final class MockShell implements CrackEngine.ShellChannel {
        final long successAtMs;
        final TestEngineClock clock;
        String aid;
        MockShell(TestEngineClock clock, long successAtMs) {
            this.clock = clock;
            this.successAtMs = successAtMs;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("logcat")) {
                StringBuilder sb = new StringBuilder();
                if (aid != null) {
                    sb.append("10-31 12:00:00.000  1000  1000 I WPF_PROBE: \"attempt=")
                      .append(aid).append("\"\n");
                }
                sb.append("10-31 12:00:01.100  1000  1000 I wpa_supplicant: wlan0: ")
                  .append("Trying to associate with SSID '").append(TARGET).append("'\n");
                // 两次 M1 → 触发 m1RetryImpliesWrongKey(2,false)
                sb.append("10-31 12:00:01.500  1000  1000 I wpa_supplicant: RSN: ")
                  .append("RX message 1 of 4-Way Handshake from ").append(BSSID).append("\n");
                sb.append("10-31 12:00:02.500  1000  1000 I wpa_supplicant: RSN: ")
                  .append("RX message 1 of 4-Way Handshake from ").append(BSSID).append("\n");
                sb.append(LogcatCmd.RC_GREP).append("0\n");
                sb.append(LogcatCmd.RC).append("0\n");
                return sb.toString();
            }
            if (cmd.contains("WPF_PROBE")) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("attempt=([A-Za-z0-9#-]+)").matcher(cmd);
                if (m.find()) aid = m.group(1);
                return "";
            }
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.startsWith("cmd wifi status")) {
                long elapsed = clock.uptimeMillis() - startAt;
                // 阶段 1（命令发出前）：系统未连接 -> 闸门② 无需断开，直接放行
                if (elapsed < 50L) return "Wifi is not connected\n";
                // 阶段 3（成功证据到达）：目标 + COMPLETED + 有效 IP
                if (elapsed >= successAtMs) {
                    return "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + BSSID
                            + ", IP: /192.168.9.9, Supplicant state: COMPLETED, Net ID: 42\n";
                }
                // 阶段 2：目标正在关联（让 sawAttempt 成立，走完整判定链）
                return "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + BSSID
                        + ", Supplicant state: ASSOCIATING, Net ID: -1\n";
            }
            return "";
        }
        @Override public String name() { return "pos-mock"; }

        long startAt;
    }

    private static String attemptIdOf() throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, TARGET, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, 1);
    }

    private static CrackEngine.Attempt runAt(long successAtMs) throws Exception {
        // scale=1：真实等待 1ms 即虚拟推进 1ms（realBlockCap=0 -> 不真正阻塞）。
        // 之前用 1000 倍会瞬间跳过整个观察窗口，测不到时序。
        TestEngineClock clock = new TestEngineClock(1_000_000L, 1L, 0L);
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, clock);
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);

        MockShell sh = new MockShell(clock, successAtMs);
        e.setShellChannel(sh);
        sh.startAt = clock.uptimeMillis();

        NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                NetRecord.Security.WPA2, false, "");
        return e.tryOne(t, "WPA2", "Correct9Pass", 6000L, attemptIdOf());
    }

    public static void main(String[] args) {
        System.out.println("== 位置相关判定：成功证据到达时刻 vs m1retry 结案窗口 ==");
        System.out.println("   m1retry 复核窗口 = " + CrackEngine.M1_RETRY_CONFIRM_MS + "ms");

        try {
            // ---------- 1. 成功证据很快到达（对应「排在前面」）
            CrackEngine.Attempt a1 = runAt(300L);
            info("成功证据 @300ms  -> verdict=" + a1.verdict
                    + " grade=" + a1.grade + " by=" + a1.confirmedBy);
            ok("1 成功证据早到 -> 判 SUCCESS（对应排在前面的情况）",
                    a1.verdict == CrackEngine.Verdict.SUCCESS,
                    "verdict=" + a1.verdict + " by=" + a1.confirmedBy);

            // ---------- 2. 成功证据稍慢（仍在窗口内）
            CrackEngine.Attempt a2 = runAt(800L);
            info("成功证据 @800ms  -> verdict=" + a2.verdict
                    + " grade=" + a2.grade + " by=" + a2.confirmedBy);
            ok("2 成功证据在窗口内（800ms）-> 仍判 SUCCESS",
                    a2.verdict == CrackEngine.Verdict.SUCCESS,
                    "verdict=" + a2.verdict + " by=" + a2.confirmedBy);

            // ---------- 3. 成功证据晚于窗口（对应「排在后面、AP 退避变慢」）
            CrackEngine.Attempt a3 = runAt(optimisticBeyondWindow());
            info("成功证据 @" + optimisticBeyondWindow() + "ms -> verdict=" + a3.verdict
                    + " grade=" + a3.grade + " by=" + a3.confirmedBy);
            if (a3.verdict == CrackEngine.Verdict.SUCCESS) {
                ok("3 成功证据晚于窗口 -> 仍判 SUCCESS（窗口足够覆盖）", true, "");
            } else {
                // 这是**残余风险**：窗口之外的慢成功会被 m1retry 切断
                info("   -> 被 m1retry 切断（" + a3.verdict + "/" + a3.grade
                        + "）—— 这正是「排在后面更容易被判错」的机制");
                ok("3 成功证据晚于窗口 -> 结论应为「非 DIRECT」而非误报成功",
                        a3.grade != EvidenceGrade.Level.DIRECT,
                        "grade=" + a3.grade + "（若为 DIRECT 会永久化，问题更严重）");
            }

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 位置相关判定测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 窗口之外一点点（用来暴露残余风险。 */
    private static long optimisticBeyondWindow() {
        return CrackEngine.M1_RETRY_CONFIRM_MS + 200L;
    }
}

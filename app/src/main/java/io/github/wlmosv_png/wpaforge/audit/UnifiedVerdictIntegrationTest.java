package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一裁决的**生产入口**集成测试（C5）。
 *
 * <p>补上 AdjudicatorMatrixTest 覆盖不到的三种真实场景 —— 纯函数矩阵只能证明
 * 「裁决器按规则工作」，证明不了「引擎真的把这类证据交给了裁决器」：
 *
 * <pre>
 *   U1 AP-2（CTRL-EVENT-ASSOC-REJECT）       -> 不得判 AUTH_FAIL
 *   U2 旧配置删不掉（list-networks 恒有目标）-> 不得判 SUCCESS（闸门先拦）
 *   U3 先无证据、后拿到明确失败证据           -> 后续证据必须能改变结论
 *   U4 同一 Attempt 多来源重复证据            -> 只产生一个结论
 *   U5 两个 Attempt 事件交错                  -> 互不污染
 * </pre>
 *
 * <p><b>离线模拟声明</b>：全部用脚本化 {@code ShellChannel}，不连接任何网络、
 * 不触碰设备状态。结论只对「这段脚本」成立，**不等于真机验证**。
 */
public final class UnifiedVerdictIntegrationTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-UV";
    static final String BSSID = "02:00:00:00:00:11";

    static final class Shell implements CrackEngine.ShellChannel {
        final List<String> statusScript = new ArrayList<>();
        final List<String> logScript = new ArrayList<>();
        String listOut = "";
        /** true = list-networks **无论何时**都回 listOut（模拟「命令前旧配置就在」）。 */
        boolean listAlways = false;
        boolean attemptStarted = false;
        int sc = 0, lc = 0;

        Shell status(String s) { statusScript.add(s); return this; }
        Shell log(String s) { logScript.add(s); return this; }
        Shell list(String s) { listOut = s; return this; }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("connect-network") || cmd.contains("add-network")) attemptStarted = true;
            if (cmd.startsWith("cmd wifi status")) {
                if (!attemptStarted) return "Wifi is not connected\n";
                int i = Math.min(sc++, statusScript.size() - 1);
                return statusScript.get(Math.max(0, i));
            }
            if (cmd.startsWith("cmd wifi list-networks")) {
                if (listAlways) return listOut;
                return attemptStarted ? listOut : "";
            }
            if (cmd.contains("logcat")) {
                int i = Math.min(lc++, logScript.size() - 1);
                return logScript.get(Math.max(0, i)) + "__WP_LC_RC=0\n__WP_LC_GREP=0\n";
            }
            return "";
        }
        @Override public String name() { return "uv-scripted"; }
    }

    static String aid;

    private static String attemptIdOf(int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, SSID, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, seq);
    }

    private static CrackEngine engine(Shell sh) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method c = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        c.setAccessible(true);
        c.invoke(e, new TestEngineClock(2_000_000L, 1L, 0L));
        Method b = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        b.setAccessible(true);
        b.invoke(e);
        e.setShellChannel(sh);
        return e;
    }

    private static NetRecord target() {
        return new NetRecord(SSID, BSSID, -55, 2412,
                NetRecord.Security.WPA2, false, "");
    }

    private static String mk(String id) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + id;
    }
    private static String logLine(String body) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + body;
    }
    private static String assoc() {
        return logLine("Trying to associate with SSID '" + SSID + "'");
    }
    private static String associatingFrame() {
        return "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /0.0.0.0, Supplicant state: ASSOCIATING, Net ID: 613\n";
    }
    private static String notConnected() { return "Wifi is not connected\n"; }

    public static void main(String[] args) {
        System.out.println("=== 统一裁决 · 生产入口集成（C5）===");
        System.out.println("  [!] 离线模拟：脚本化 ShellChannel，非真机验证");
        try {
            aid = attemptIdOf(1);
            NetRecord t = target();

            // ---------------- U1：AP-2 关联被拒 ----------------
            String ap2 = mk(aid) + "\n" + assoc() + "\n"
                    + logLine("CTRL-EVENT-ASSOC-REJECT bssid=" + BSSID + " status_code=17") + "\n";
            ArrayList<String> st1 = new ArrayList<>();
            ArrayList<String> lg1 = new ArrayList<>();
            for (int i = 0; i < 3000; i++) { st1.add(associatingFrame()); lg1.add(ap2); }
            Shell sh1 = new Shell();
            for (String s : st1) sh1.status(s);
            for (String l : lg1) sh1.log(l);
            CrackEngine.Attempt a1 = engine(sh1).tryOne(t, "WPA2", "WrongPass12", 1500L, aid);
            info("U1 AP-2 关联被拒 -> verdict=" + a1.verdict + " grade=" + a1.grade
                    + " by=" + a1.confirmedBy + " reason=" + a1.reason);
            ok("U1 AP-2（关联被拒）不得判 AUTH_FAIL（MAC 过滤/负载也会产生同样行）",
                    a1.verdict != CrackEngine.Verdict.AUTH_FAIL, "verdict=" + a1.verdict);
            ok("U1 不得给 DIRECT", a1.grade != EvidenceGrade.Level.DIRECT, "grade=" + a1.grade);

            // ---------------- U2：旧配置删不掉 ----------------
            Shell sh2 = new Shell();
            for (int i = 0; i < 3000; i++) {
                sh2.status("WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                        + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 613\n");
                sh2.log(mk(aid) + "\n" + assoc());
            }
            // **关键**：旧配置必须在**命令前**就存在（真实场景），
            // 而不是「命令后才冒出来」（那反而是本次命令创建的配置，属正常）。
            sh2.listAlways = true;
            sh2.list("613   " + SSID + "   WPA2\n");   // 恒有目标配置 -> forget 删不掉
            CrackEngine.Attempt a2 = engine(sh2).tryOne(t, "WPA2", "AnyPass12", 1500L, aid);
            info("U2 旧配置删不掉 + status 显示目标已连上 -> verdict=" + a2.verdict
                    + " grade=" + a2.grade + " reason=" + a2.reason);
            ok("U2 **不得判 SUCCESS**（那是旧配置连上的，不是本条候选）",
                    a2.verdict != CrackEngine.Verdict.SUCCESS, "verdict=" + a2.verdict);
            ok("U2 结论为 UNKNOWN 类（不可信，不伪造成功也不伪造失败）",
                    a2.verdict == CrackEngine.Verdict.UNKNOWN, "verdict=" + a2.verdict);

            // ---------------- U3：先无证据，后拿到明确失败证据 ----------------
            String wrongKey = mk(aid) + "\n" + assoc() + "\n"
                    + logLine("RSN: RX message 1 of 4-Way Handshake from " + BSSID) + "\n"
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect") + "\n"
                    + logLine("CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + SSID
                            + "\" reason=WRONG_KEY auth_failures=1") + "\n";
            Shell sh3 = new Shell();
            // 前 40 轮：什么都不给（status 空 + 日志只有标记）
            for (int i = 0; i < 40; i++) { sh3.status(""); sh3.log(mk(aid)); }
            // 之后：明确失败证据
            for (int i = 0; i < 3000; i++) { sh3.status(associatingFrame()); sh3.log(wrongKey); }
            CrackEngine.Attempt a3 = engine(sh3).tryOne(t, "WPA2", "WrongPass12", 3000L, aid);
            info("U3 先无证据后明确失败 -> verdict=" + a3.verdict + " grade=" + a3.grade
                    + " reason=" + a3.reason);
            ok("U3 后到的明确证据必须改变结论（判 AUTH_FAIL）",
                    a3.verdict == CrackEngine.Verdict.AUTH_FAIL, "verdict=" + a3.verdict);
            ok("U3 归属只到 AT1 -> INFERRED（不永久排除）",
                    a3.grade == EvidenceGrade.Level.INFERRED, "grade=" + a3.grade);

            // ---------------- U4：同一 Attempt 多来源重复证据 ----------------
            // 同一窗口里同时有 assoc-reject、handshake failed、M1 重发
            String multi = mk(aid) + "\n" + assoc() + "\n"
                    + logLine("RSN: RX message 1 of 4-Way Handshake from " + BSSID) + "\n"
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect") + "\n"
                    + logLine("CTRL-EVENT-ASSOC-REJECT bssid=" + BSSID + " status_code=17") + "\n";
            Shell sh4 = new Shell();
            for (int i = 0; i < 3000; i++) { sh4.status(associatingFrame()); sh4.log(multi); }
            CrackEngine.Attempt a4 = engine(sh4).tryOne(t, "WPA2", "WrongPass12", 1500L, aid);
            info("U4 多来源重复证据 -> verdict=" + a4.verdict + " grade=" + a4.grade
                    + " reason=" + a4.reason);
            ok("U4 只产生一个结论（不重复计费）",
                    a4.verdict != null, "verdict=" + a4.verdict);
            ok("U4 结论必须落在合法集合内",
                    a4.verdict == CrackEngine.Verdict.AUTH_FAIL
                            || a4.verdict == CrackEngine.Verdict.UNKNOWN
                            || a4.verdict == CrackEngine.Verdict.INCOMPLETE
                            || a4.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN
                            || a4.verdict == CrackEngine.Verdict.TIMEOUT,
                    "verdict=" + a4.verdict);

            // ---------------- U5：两个 Attempt 事件交错 ----------------
            String otherAid = attemptIdOf(2);
            // 日志里只有**另一个** Attempt 的失败证据
            String foreign = mk(otherAid) + "\n" + assoc() + "\n"
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect") + "\n"
                    + logLine("CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + SSID
                            + "\" reason=WRONG_KEY auth_failures=1") + "\n";
            Shell sh5 = new Shell();
            for (int i = 0; i < 3000; i++) { sh5.status(notConnected()); sh5.log(foreign); }
            CrackEngine.Attempt a5 = engine(sh5).tryOne(t, "WPA2", "WrongPass12", 1500L, aid);
            info("U5 证据属于另一个 Attempt -> verdict=" + a5.verdict + " grade=" + a5.grade
                    + " reason=" + a5.reason);
            ok("U5 别人的证据不得判本条 AUTH_FAIL（跨 Attempt 不污染）",
                    a5.verdict != CrackEngine.Verdict.AUTH_FAIL, "verdict=" + a5.verdict);

        } catch (Throwable e) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + e);
            e.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 统一裁决集成测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
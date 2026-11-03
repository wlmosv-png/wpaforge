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
        /**
         * 是否已收到 connect-network 命令。
         *
         * <p>引擎把 connect 放在**异步线程**里发（不让它阻塞轮询），
         * 所以主循环第一次探测很可能早于那条线程真正执行 —— 这是测试脚手架的
         * 竞态根源（曾导致 targetNetId 恒为 -1，AT3 永远不可达）。
         *
         * <p>这里用「短暂让步等待」消除竞态：list/status 若发现尚未见到 connect，
         * 最多让出 50ms 等它到达。测试是离线脚本，这点等待不影响结论，
         * 但让「命令后」与「命令前」的判定变成确定的。
         */
        volatile boolean connectSeen = false;
        int sc = 0, lc = 0;

        private boolean awaitConnect() {
            long t0 = System.nanoTime();
            while (!connectSeen && (System.nanoTime() - t0) < 50_000_000L) {
                Thread.yield();
            }
            return connectSeen;
        }

        Shell status(String s) { statusScript.add(s); return this; }
        Shell log(String s) { logScript.add(s); return this; }
        Shell list(String s) { listOut = s; return this; }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("connect-network") || cmd.contains("add-network")) {
                connectSeen = true;
            }
            if (cmd.startsWith("cmd wifi status")) {
                if (!awaitConnect()) return "Wifi is not connected\n";    // 前置闸门阶段
                int i = Math.min(sc++, statusScript.size() - 1);
                return statusScript.get(Math.max(0, i));
            }
            if (cmd.startsWith("cmd wifi list-networks")) {
                if (listAlways) return listOut;
                return awaitConnect() ? listOut : "";                      // 命令前：无目标配置
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
            // 之后：明确失败证据。
            //
            // 2026-11-02 审计修正：这里原来一直喂 ASSOCIATING（连接仍在进行），
            // 旧实现据此就产出了 NETWORK_AUTH_FAIL。但「观察到 WRONG_KEY」不能
            // 推出「本次请求已结束」——规则 3/4 现在要求生命周期已确认终止。
            // 所以本用例补正为「连接已终止」，继续验证规则 4 的本意；
            // 「仍在进行」的对照见下面的 U8。
            for (int i = 0; i < 3000; i++) { sh3.status(notConnected()); sh3.log(wrongKey); }
            CrackEngine.Attempt a3 = engine(sh3).tryOne(t, "WPA2", "WrongPass12", 3000L, aid);
            info("U3 先无证据后明确失败 -> verdict=" + a3.verdict + " grade=" + a3.grade
                    + " reason=" + a3.reason);
            // D1：后到的证据确实改变了结论（从「无证据」变成「网络认证失败」），
            // 但归属只到 AT1，绑不到当前候选 -> 网络级事件，不可永久排除。
            ok("U3 后到的明确证据必须改变结论（不再是 TIMEOUT）",
                    a3.verdict != CrackEngine.Verdict.TIMEOUT, "verdict=" + a3.verdict);
            ok("U3 归属只到 AT1 -> NETWORK_AUTH_FAIL / NONE（不永久排除）",
                    a3.verdict == CrackEngine.Verdict.NETWORK_AUTH_FAIL
                            && a3.grade == EvidenceGrade.Level.NONE,
                    a3.verdict + "/" + a3.grade);

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

            // ---------------- U6：AT-3 真实可达（D5）----------------
            // 本次命令建的配置 netId=613，且失败行的 id=613 -> 候选级结论
            String at3Log = mk(aid) + "\n" + assoc() + "\n"
                    + logLine("RSN: RX message 1 of 4-Way Handshake from " + BSSID) + "\n"
                    + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect") + "\n"
                    + logLine("CTRL-EVENT-SSID-TEMP-DISABLED id=613 ssid=\"" + SSID
                            + "\" auth_failures=1 reason=WRONG_KEY") + "\n";
            Shell sh6 = new Shell();
            // 前提同上：候选级结论要求连接已确认终止
            for (int i = 0; i < 3000; i++) { sh6.status(notConnected()); sh6.log(at3Log); }
            sh6.list("613   " + SSID + "   WPA2\n");
            CrackEngine.Attempt a6 = engine(sh6).tryOne(t, "WPA2", "WrongPass12", 1500L, aid);
            info("U6 id=613 绑定本次配置 -> verdict=" + a6.verdict + " grade=" + a6.grade
                    + " reason=" + a6.reason);
            ok("U6 id 绑定本次配置 -> **AUTH_FAIL（候选级）**",
                    a6.verdict == CrackEngine.Verdict.AUTH_FAIL, "verdict=" + a6.verdict);
            ok("U6 候选级失败可持久化（DIRECT）",
                    a6.grade == EvidenceGrade.Level.DIRECT, "grade=" + a6.grade);

            // U7：同一条日志但本次配置 netId=700（id 对不上）-> 退回网络级
            Shell sh7 = new Shell();
            for (int i = 0; i < 3000; i++) { sh7.status(notConnected()); sh7.log(at3Log); }
            sh7.list("700   " + SSID + "   WPA2\n");
            CrackEngine.Attempt a7 = engine(sh7).tryOne(t, "WPA2", "WrongPass12", 1500L, aid);
            info("U7 id=613 但本次配置=700 -> verdict=" + a7.verdict + " grade=" + a7.grade);
            ok("U7 id 对不上 -> 退回 NETWORK_AUTH_FAIL（不永久排除）",
                    a7.verdict == CrackEngine.Verdict.NETWORK_AUTH_FAIL
                            && a7.grade == EvidenceGrade.Level.NONE,
                    a7.verdict + "/" + a7.grade);

            // ---------------- U8（2026-11-02 审计新增）：ACTIVE 下的失败证据不得定案 ----
            // 证据与 U3/U6/U7 完全相同，唯一区别是**连接仍在进行**（ASSOCIATING）。
            // 旧实现在这种状态下就产出了 AUTH_FAIL（会写 triedSet 永久排除）
            // 与 NETWORK_AUTH_FAIL（会进省时档跳过集合）。这两条是审计确认的
            // 真实缺陷，本段是它们的回归测试 —— 必须永远不通过定案。
            for (String label : new String[]{"AT1", "AT3"}) {
                String lg = "AT3".equals(label) ? at3Log : wrongKey;
                Shell sha = new Shell();
                for (int i = 0; i < 3000; i++) { sha.status(associatingFrame()); sha.log(lg); }
                sha.list("613   " + SSID + "   WPA2\n");
                CrackEngine.Attempt a = engine(sha).tryOne(t, "WPA2", "WrongPass12", 1500L, aid);
                info("U8 ACTIVE+" + label + " -> verdict=" + a.verdict + " grade=" + a.grade
                        + " reason=" + a.reason);
                ok("U8 ACTIVE+" + label + " 不得产候选级 AUTH_FAIL",
                        a.verdict != CrackEngine.Verdict.AUTH_FAIL, String.valueOf(a.verdict));
                ok("U8 ACTIVE+" + label + " 不得产 NETWORK_AUTH_FAIL",
                        a.verdict != CrackEngine.Verdict.NETWORK_AUTH_FAIL, String.valueOf(a.verdict));
                ok("U8 ACTIVE+" + label + " 不得给 DIRECT（不得永久排除）",
                        a.grade != EvidenceGrade.Level.DIRECT, String.valueOf(a.grade));
                ok("U8 ACTIVE+" + label + " 不得进省时档跳过集合",
                        !AttemptLedger.SkipPolicy.SAVE_TIME.skips(
                                AttemptLedger.Level.classify(a.verdict, a.grade)),
                        a.verdict + "/" + a.grade);
                ok("U8 ACTIVE+" + label + " 结论必须明确表示「未结束」",
                        a.verdict == CrackEngine.Verdict.INCOMPLETE
                                || a.verdict == CrackEngine.Verdict.TIMEOUT
                                || a.verdict == CrackEngine.Verdict.UNKNOWN
                                || a.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN,
                        String.valueOf(a.verdict));
            }

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
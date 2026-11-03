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
        /** 虚拟起点（与 TestEngineClock 的 startMs 一致），用于把绝对虚拟时刻换成相对量。 */
        static final long V0 = 2_000_000L;
        final List<String> statusScript;
        final List<String> logScript;
        int statusCalls = 0, logCalls = 0;
        /** 由 runCase 注入：用来记录成功证据**首次可被观察到**的虚拟时刻。 */
        TestEngineClock clock;
        /** 成功帧首次被 status 返回时的虚拟时刻（相对 V0）；-1 = 从未出现。 */
        long successAt = -1L;
        /** 成功帧首次被 status 返回时的调用序号（1 起）。 */
        int successCall = -1;
        /**
         * 是否已经发出过连接命令（connect-network）。
         *
         * <p>为什么需要它：引擎在**发命令前**有两道前置闸门（删净目标配置、若已连着
         * 目标先断开并确认断开）。如果脚本从第一次 status 起就报「目标的
         * FOUR_WAY_HANDSHAKE」，前置闸门会认为「断开没生效」而直接收工 ——
         * 那测的就不是判定链了。真实场景下命令前确实没连着目标。
         */
        boolean attemptStarted = false;
        ScriptedShell(List<String> st, List<String> lg) { statusScript = st; logScript = lg; }
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("connect-network") || cmd.contains("add-network")) attemptStarted = true;
            if (cmd.startsWith("cmd wifi status")) {
                if (!attemptStarted) return notConnected();   // 前置阶段：确实没连
                int i = Math.min(statusCalls++, statusScript.size() - 1);
                String r = statusScript.get(Math.max(0, i));
                // 只把「COMPLETED」当成功帧：FOUR_WAY_HANDSHAKE 也是 WifiInfo 行，
                // 但它是「连接进行中」，不能算成功证据（否则时间线全错）。
                if (successAt < 0 && clock != null && r != null
                        && r.contains("Supplicant state: COMPLETED")) {
                    successAt = clock.uptimeMillis() - V0;
                    successCall = statusCalls;
                }
                return r;
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

    /**
     * 第 switchAt 轮起出现成功证据。
     *
     * @param midStatus switchAt 之前的 status 输出。默认 {@code Wifi is not connected}
     *                  （= 连接已明确终止）；传 {@link #associatingFrame()} 可模拟
     *                  「慢速 AP：连接仍在进行中」（更接近真机慢成功场景）。
     * @return [verdict, grade, by, 成功帧虚拟到达(ms,-1=从未), 总虚拟时长, status 调用次数]
     */
    private static Object[] runCase(int switchAt, long timeoutMs, String midStatus) throws Exception {
        String aid = attemptIdOf();
        String failOnly = markerLine(aid) + assocLine()
                + logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
        String withSucc = failOnly + logLine("WPA: Key negotiation completed with " + BSSID);

        List<String> st = new ArrayList<>();
        List<String> lg = new ArrayList<>();
        String mid = midStatus == null ? notConnected() : midStatus;
        for (int i = 0; i < switchAt; i++) { st.add(mid); lg.add(failOnly); }
        for (int i = 0; i < 3000; i++) { st.add(okFrame()); lg.add(withSucc); }

        TestEngineClock clock = new TestEngineClock(ScriptedShell.V0, 1L, 0L);
        CrackEngine e = newEngine(clock);
        ScriptedShell sh = new ScriptedShell(st, lg);
        sh.clock = clock;
        e.setShellChannel(sh);
        NetRecord t = new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        Object a = e.tryOne(t, "WPA2", "Correct9Pass", timeoutMs, aid);
        Field fv = a.getClass().getField("verdict");
        Field fg = a.getClass().getField("grade");
        Field fb = a.getClass().getField("confirmedBy");
        // B2 修正：原第 4 个元素是 `clock.uptimeMillis() - clock.uptimeMillis()` —— **恒为 0**，
        // 也就是「时间线」从来没被真正打印过（假数据）。现在返回真实量：
        //   [3] 成功证据首次可被观察到的虚拟时刻（相对起点，-1=从未）
        //   [4] 本轮 tryOne 消耗的虚拟总时长
        //   [5] status 调用总次数（轮次代理量）
        return new Object[]{
                fv.get(a), fg.get(a), fb.get(a),
                sh.successAt, clock.uptimeMillis() - ScriptedShell.V0, sh.statusCalls };
    }

    /** 兼容入口：中间态用默认的「未连接」。 */
    private static Object[] runCase(int switchAt, long timeoutMs) throws Exception {
        return runCase(switchAt, timeoutMs, null);
    }

    /** 慢速 AP 现场：目标还在握手，连接并未终止。 */
    private static String associatingFrame() {
        return "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /0.0.0.0, Supplicant state: FOUR_WAY_HANDSHAKE, Net ID: 613\n";
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 延迟成功测试（失败 T+3s；成功后到） ===");
        // ── B2：先把「这个测试到底测的是什么时间」讲清楚 ────────────────────
        //  · 时间体系 = **TestEngineClock 虚拟时间**。scale=1，sleep 多少毫秒就推进多少。
        //  · realBlockCap = 0 → **完全不真实阻塞**：本测试不产生任何真实等待。
        //    被等待方（系统回调）在这里没有机会跑；之所以可以接受，是因为
        //    ShellChannel 是**脚本化的**（FakeShell 注入），没有真实对端。
        //  · switchAt 是「第几次 status 调用起换成成功帧」，**不是秒数**。
        //    虚拟到达时刻由本测试实际记录并打印（见下表 successAt 列）。
        //  · 生产对照：basePerItem 默认 10_000ms（连续无证据时自适应降到 6_000ms），
        //    另有「目标出现在 status」触发的 +4s 看门狗延长 → 生产真实观察上限
        //    约 10~14 秒。因此 **T+15s / T+19s 两组在生产默认窗口下不可达**，
        //    它们是「用户把单条时限调到 20 秒」时的行为验证，不是生产默认行为。
        System.out.println("  · 时间体系: TestEngineClock 虚拟时间（scale=1, realBlockCap=0）");
        System.out.println("  · 真实等待: 无（脚本化 ShellChannel，无真实对端）");
        System.out.println("  · 生产默认窗口: basePerItem=10000ms，自适应缩窗 6000ms，看门狗 +4000ms");

        // 先标定：一轮循环的虚拟时间推进量（用不同 switchAt 观察）
        int[] switches = { 12, 50, 60, 96, 232 };
        System.out.println("  -- 标定：不同 switchAt 下的结果（timeout=10s） --");
        for (int sw : switches) {
            Object[] r = runCase(sw, 10_000L);
            long at = (Long) r[3];
            info("switchAt=" + sw + " → verdict=" + r[0] + " grade=" + r[1] + " by=" + r[2]
                    + " | 成功帧虚拟到达=" + (at < 0 ? "从未" : at + "ms")
                    + "（第 " + r[5] + " 次 status 调用）"
                    + " | 总虚拟时长=" + r[4] + "ms");
        }

        System.out.println();
        System.out.println("=== 四组目标场景（每条时限 20s，模拟用户把窗口调大） ===");
        // 用 switchAt 把成功帧放到不同的虚拟时刻；真实到达时刻由脚本执行时记录。
        int[][] cases = { {96, 4_100}, {120, 6_000}, {232, 15_000}, {282, 19_000} };
        for (int[] c : cases) {
            Object[] r = runCase(c[0], 20_000L);
            String v = String.valueOf(r[0]);
            long at = (Long) r[3];
            info("[期望 T+" + c[1] + "ms] switchAt=" + c[0]
                    + " → 成功帧虚拟到达=" + (at < 0 ? "从未" : at + "ms")
                    + "（第 " + r[5] + " 次 status 调用，总虚拟 " + r[4] + "ms）"
                    + " → verdict=" + v + " / " + r[1] + " / " + r[2]);
            ok("[T+" + c[1] + "ms, 窗口 20s] 后到成功必须参与裁决 → SUCCESS",
                    "SUCCESS".equals(v), "实际=" + v);
        }

        System.out.println();
        System.out.println("=== 生产默认窗口下的同两组（关键行为保证） ===");
        // 这是 B2 新增的核心断言：**晚到成功在生产默认窗口内不可达时，
        // 绝不能被误判成 AUTH_FAIL**，只能落到可重新裁决的 INCOMPLETE/TIMEOUT。
        for (int[] c : new int[][]{ {232, 15_000}, {282, 19_000} }) {
            Object[] r = runCase(c[0], 10_000L);
            String v = String.valueOf(r[0]);
            long at = (Long) r[3];
            info("[T+" + c[1] + "ms, 生产默认窗口 10s] 成功帧虚拟到达=" + (at < 0 ? "从未" : at + "ms")
                    + " → verdict=" + v + " / " + r[1] + " / " + r[2]);
            ok("[T+" + c[1] + "ms, 窗口 10s] 窗口内未及成功 **不得** 判 AUTH_FAIL",
                    !"AUTH_FAIL".equals(v), "实际=" + v);
            ok("[T+" + c[1] + "ms, 窗口 10s] 降级为可重新裁决的 INCOMPLETE/TIMEOUT",
                    "INCOMPLETE".equals(v) || "TIMEOUT".equals(v)
                            || "UNKNOWN".equals(v) || "TERMINATED_UNKNOWN".equals(v),
                    "实际=" + v);
            ok("[T+" + c[1] + "ms, 窗口 10s] grade=NONE（不写 triedSet / 不推 cursor）",
                    String.valueOf(r[1]).equals("NONE"), "grade=" + r[1]);
        }

        System.out.println();
        System.out.println("=== 慢速 AP：窗口结束但**连接仍在进行**（最接近真机的慢成功场景） ===");
        // 真机上「正确密码但 AP 慢」时，窗口结束那一刻的 status 不是
        // `Wifi is not connected`，而是 FOUR_WAY_HANDSHAKE / ASSOCIATING。
        // 此时**必须**给 INCOMPLETE（= 可重新裁决），绝不能给失败。
        // 组 1：慢成功仍落在窗口内（1.16s）→ 必须成功
        Object[] r1 = runCase(60, 10_000L, associatingFrame());
        String v1 = String.valueOf(r1[0]);
        info("[慢成功 1.16s, 窗口 10s, 中间态=FOUR_WAY_HANDSHAKE] → verdict=" + v1
                + " / " + r1[1] + " / by=" + r1[2]
                + " | 成功帧虚拟到达=" + ((Long) r1[3] < 0 ? "从未" : r1[3] + "ms")
                + " | status 调用=" + r1[5] + " | 总虚拟=" + r1[4] + "ms");
        ok("[慢成功落在窗口内] → SUCCESS（慢 AP 不得被误判）", "SUCCESS".equals(v1), "实际=" + v1);

        // 组 2：慢成功落在窗口外（14.8s），窗口结束那一刻连接仍在握手
        Object[] r2 = runCase(232, 10_000L, associatingFrame());
        String v2 = String.valueOf(r2[0]);
        info("[慢成功 14.8s, 窗口 10s, 中间态=FOUR_WAY_HANDSHAKE] → verdict=" + v2
                + " / " + r2[1] + " / by=" + r2[2]
                + " | 成功帧虚拟到达=" + ((Long) r2[3] < 0 ? "从未" : r2[3] + "ms")
                + " | status 调用=" + r2[5] + " | 总虚拟=" + r2[4] + "ms");
        ok("[连接进行中] 窗口结束未连上 **不得** 判 AUTH_FAIL",
                !"AUTH_FAIL".equals(v2), "实际=" + v2);
        ok("[连接进行中] 必须落到 INCOMPLETE（可重新裁决，不是终局）",
                "INCOMPLETE".equals(v2), "实际=" + v2);
        ok("[连接进行中] grade=NONE（不写 triedSet / 不推 cursor）",
                String.valueOf(r2[1]).equals("NONE"), "grade=" + r2[1]);

        System.out.println();
        System.out.println("=== 延迟成功测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

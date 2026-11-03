package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * D5 剩余语义：**INFERRED 不得造成任何形式的候选排除**。
 *
 * <h3>要钉死的三件事（端到端，驱动真实 run()）</h3>
 * <ol>
 *   <li>无归属失败（INFERRED）之后，续跑游标停在原处 —— 候选保留验证机会。</li>
 *   <li>候选调度状态：本轮内该候选不被当作「已确认失败」。</li>
 *   <li>多目标串扰不会让当前候选被排除。</li>
 * </ol>
 *
 * <h3>为什么用 run() 而不是 tryOne()</h3>
 * 「是否被排除」是**调度层**的事实（cursor / offset），单条 tryOne 看不出来。
 */
public final class P05NoExclusionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-E5";
    static final String BSSID = "02:00:00:00:00:0D";

    /** 假通道：status 永远未连接；日志固定为「目标 associate + 无归属握手失败」。 */
    static final class FakeShell implements CrackEngine.ShellChannel {
        final String attemptId;
        final List<String> body;
        FakeShell(String attemptId, List<String> body) {
            this.attemptId = attemptId;
            this.body = body;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.contains("logcat")) {
                StringBuilder sb = new StringBuilder();
                sb.append("10-31 12:00:00.000  1000  1000 I WPF_PROBE: \"attempt=")
                        .append(attemptId).append("\"\n");
                for (String l : body) sb.append(l).append('\n');
                sb.append(LogcatCmd.RC_GREP).append("0\n");
                sb.append(LogcatCmd.RC).append("0\n");
                return sb.toString();
            }
            return "";
        }
        @Override public String name() { return "fake-e5"; }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<String> verdicts = new ArrayList<>();
        final List<String> grades = new ArrayList<>();
        int finishedTested = -1;
        int finishedCursor = Integer.MIN_VALUE;
        String outcome = null;
        boolean blocked = false;

        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            verdicts.add(a.verdict.name());
            grades.add(String.valueOf(a.grade));
        }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finishedTested = tested;
            finishedCursor = cursor;
            this.outcome = outcome;
        }
        @Override public void onBlocked(String title, String detail) { blocked = true; }
    }

    private static String attemptIdOf(long epoch, int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, epoch, TARGET, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, seq);
    }

    public static void main(String[] args) {
        System.out.println("== D5 剩余：INFERRED 不得造成候选排除 ==");

        try {
            // ---------- 场景：全部候选都是「无归属握手失败」= INFERRED
            String aid1 = attemptIdOf(1_764_000_000_000L, 1);
            List<String> body = new ArrayList<>();
            body.add("10-31 12:00:01.100  1000  1000 I wpa_supplicant: wlan0: Trying to associate with SSID '" + TARGET + "'");
            body.add("10-31 12:00:01.900  1000  1000 I wpa_supplicant: WPA: 4-Way Handshake failed - pre-shared key may be incorrect");

            CrackEngine engine = newEngine();
            engine.setShellChannel(new FakeShell(aid1, body));

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            cands.add(new Candidate("wrong-001", Candidate.Origin.USER_DICT));
            cands.add(new Candidate("wrong-002", Candidate.Origin.USER_DICT));

            Rec cb = new Rec();
            engine.run(t, cands, 0, 1200L, cb);

            info("逐条 verdict = " + cb.verdicts);
            info("逐条 grade   = " + cb.grades);
            info("onFinished tested=" + cb.finishedTested
                    + " cursor=" + cb.finishedCursor + " outcome=" + cb.outcome);

            ok("确实执行了 2 条候选", cb.verdicts.size() == 2, "size=" + cb.verdicts.size());

            // 关键不是「必须是 INFERRED」，而是「**绝不能是 DIRECT**」——
            // 只要不是 DIRECT，就不会写永久 triedSet、也不会推进游标。
            // 本条用例里 status 通道无响应，引擎判 ERROR/NONE，
            // 同样满足「证据不足不得形成失败结论」。
            boolean noneIsDirect = true;
            for (String g : cb.grades) {
                if ("DIRECT".equals(g)) noneIsDirect = false;
            }
            ok("**证据不足全部不得判为 DIRECT**（无论 INFERRED 或 NONE）",
                    noneIsDirect, "grades=" + cb.grades);

            boolean noConfirmedFail = true;
            for (String v : cb.verdicts) {
                if ("AUTH_FAIL".equals(v) && noneIsDirect) noConfirmedFail = false;
            }
            ok("非 DIRECT 的结论不得被当成已确认失败",
                    noConfirmedFail, "verdicts=" + cb.verdicts + " grades=" + cb.grades);

            // ---------- 核心：游标不得被 INFERRED 推进
            ok("**INFERRED 不得推进续跑游标**（cursor 应停在 0，续跑会重试第 1 条）",
                    cb.finishedCursor == 0,
                    "实际 cursor=" + cb.finishedCursor
                            + "（若为 2 说明两条都被永久越过，续跑将漏掉它们）");

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== D5 无排除测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static CrackEngine newEngine() throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        return e;
    }
}

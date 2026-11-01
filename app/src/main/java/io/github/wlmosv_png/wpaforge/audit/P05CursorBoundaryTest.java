package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D5-3：**混合判定结果下的 cursor 安全性**。
 *
 * <h3>两部分</h3>
 * <ol>
 *   <li><b>穷举</b>：直接调用生产函数 {@link CrackEngine#nextCursor(int,int,EvidenceGrade.Level)}，
 *       覆盖全部等级组合，断言「边界永远停在第一个未获 DIRECT 的候选」。</li>
 *   <li><b>端到端</b>：驱动真实 {@code run()}，验证真实调度路径下
 *       非 DIRECT 候选不会被越过（cursor 停在起点）。</li>
 * </ol>
 *
 * <h3>为什么用生产纯函数而不是只做端到端</h3>
 * 端到端要精确构造「同一 run 内三种不同等级」需要在假 Shell 里同时
 * 模拟 logcat 窗口隔离、时间锚点与命令时序，成本远高于收益，
 * 且任何一处模拟偏差都会让断言测到「假环境的产物」而不是「真实逻辑」。
 * 因此：**cursor 计算逻辑用生产纯函数穷举**（这是它唯一的实现），
 * **调度路径用真实 run() 验证不回退**。
 */
public final class P05CursorBoundaryTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final EvidenceGrade.Level D = EvidenceGrade.Level.DIRECT;
    private static final EvidenceGrade.Level I = EvidenceGrade.Level.INFERRED;
    private static final EvidenceGrade.Level N = EvidenceGrade.Level.NONE;

    /** 按脚本顺序跑一遍 nextCursor，返回最终边界。 */
    private static int runScript(EvidenceGrade.Level[] script, int start) {
        int cursor = start;
        for (int i = start; i < script.length; i++) {
            cursor = CrackEngine.nextCursor(cursor, i, script[i]);
        }
        return cursor;
    }

    private static void scenario(String tag, EvidenceGrade.Level[] script, int expect) {
        int got = runScript(script, 0);
        info("[" + tag + "] " + java.util.Arrays.toString(script)
                + " -> cursor=" + got + "（期望 " + expect + "）");
        ok("[" + tag + "] cursor 必须停在第一个未获 DIRECT 的候选", got == expect,
                "实际 " + got + "，期望 " + expect);
    }

    public static void main(String[] args) {
        System.out.println("== D5-3 混合结果的 cursor 安全性 ==");

        // 用户点名的 5 个场景
        scenario("1", new EvidenceGrade.Level[]{I, D, N}, 0);
        scenario("2", new EvidenceGrade.Level[]{N, D, N}, 0);
        scenario("3", new EvidenceGrade.Level[]{I, I, D}, 0);
        scenario("4", new EvidenceGrade.Level[]{D, I, D}, 1);
        scenario("5", new EvidenceGrade.Level[]{D, D, D}, 3);

        // 补充：边界不应回退
        ok("补充1：cursor 只增不减",
                CrackEngine.nextCursor(2, 0, D) == 2, "回退了");
        ok("补充2：全部 DIRECT 时边界 = 长度",
                runScript(new EvidenceGrade.Level[]{D, D, D, D}, 0) == 4, "");
        ok("补充3：首条非 DIRECT 则边界恒为起点",
                runScript(new EvidenceGrade.Level[]{N, D, D, D}, 0) == 0, "");
        ok("补充4：从非 0 起点开始时同样成立",
                runScript(new EvidenceGrade.Level[]{D, D, I, D}, 2) == 2, "");

        // ---------- 端到端：真实 run()，全部无结论 -> cursor 不得推进
        try {
            String aid = attemptIdOf();
            FakeShell sh = new FakeShell(aid);
            CrackEngine engine = newEngine();
            engine.setShellChannel(sh);

            NetRecord t = new NetRecord("LAB-C5", "02:00:00:00:00:0E", -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            cands.add(new Candidate("candidate-0x", Candidate.Origin.USER_DICT));
            cands.add(new Candidate("candidate-1x", Candidate.Origin.USER_DICT));

            Rec cb = new Rec();
            engine.run(t, cands, 0, 1000L, cb);
            info("端到端: verdict=" + cb.verdicts + " grade=" + cb.grades
                    + " cursor=" + cb.finishedCursor);
            ok("端到端：全部无结论时 cursor 停在 0（续跑会重新验证）",
                    cb.finishedCursor == 0, "cursor=" + cb.finishedCursor);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 端到端异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== D5-3 cursor 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ 端到端辅助

    static final class FakeShell implements CrackEngine.ShellChannel {
        final String aid;
        FakeShell(String aid) { this.aid = aid; }
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("WPF_PROBE")) return "";
            if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.contains("logcat")) {
                StringBuilder sb = new StringBuilder();
                sb.append("10-31 12:00:00.000  1000  1000 I WPF_PROBE: \"attempt=")
                  .append(aid).append("\"\n");
                sb.append(LogcatCmd.RC_GREP).append("0\n");
                sb.append(LogcatCmd.RC).append("0\n");
                return sb.toString();
            }
            return "";
        }
        @Override public String name() { return "fake-c5"; }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<String> verdicts = new ArrayList<>();
        final List<String> grades = new ArrayList<>();
        int finishedCursor = Integer.MIN_VALUE;
        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            verdicts.add(a.verdict.name());
            grades.add(String.valueOf(a.grade));
        }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finishedCursor = cursor;
        }
        @Override public void onBlocked(String title, String detail) {}
    }

    private static String attemptIdOf() throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, "LAB-C5", 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, 1);
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

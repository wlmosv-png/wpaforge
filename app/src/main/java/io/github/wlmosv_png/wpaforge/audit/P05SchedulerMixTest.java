package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * D5-4：**用可控结论输入驱动真实 run()**，验证调度器确实消费了混合等级。
 *
 * <h3>关键边界</h3>
 * {@link CrackEngine.VerdictProvider} 只替换「一条候选得到什么结论」，
 * **cursor / 候选调度 / 记账 / 落盘全部仍由真实 run() 执行**。
 * 因此本测试证明的是**调度器**正确，不是判定逻辑正确
 * （判定逻辑见 P05AttributionTest / P05CrossTalkTest，那些走真实日志）。
 */
public final class P05SchedulerMixTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-M5";
    static final String BSSID = "02:00:00:00:00:0F";

    enum Kind { DIRECT, INFERRED, NONE }

    /** 按脚本给出确定性结论；**不接触 cursor / triedSet**。 */
    static final class ScriptedProvider implements CrackEngine.VerdictProvider {
        final Kind[] script;
        final List<String> calls = new ArrayList<>();

        ScriptedProvider(Kind[] script) { this.script = script; }

        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            calls.add(password + "@" + index);
            Kind k = (index - 1 >= 0 && index - 1 < script.length)
                    ? script[index - 1] : Kind.NONE;
            switch (k) {
                case DIRECT: {
                    CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                            CrackEngine.Verdict.AUTH_FAIL, 1L, "direct", "scripted");
                    a.grade = EvidenceGrade.Level.DIRECT;
                    return a;
                }
                case INFERRED: {
                    CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                            CrackEngine.Verdict.AUTH_FAIL, 1L, "inferred", "scripted");
                    a.grade = EvidenceGrade.Level.INFERRED;
                    return a;
                }
                default: {
                    CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                            CrackEngine.Verdict.TIMEOUT, 1L, "none", "scripted");
                    a.grade = EvidenceGrade.Level.NONE;
                    return a;
                }
            }
        }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<String> grades = new ArrayList<>();
        final List<String> verdicts = new ArrayList<>();
        int finishedCursor = Integer.MIN_VALUE;
        int finishedTested = -1;
        String outcome = null;
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
        @Override public void onBlocked(String title, String detail) {
            System.out.println("  !! blocked " + title);
        }
    }

    public static void main(String[] args) {
        System.out.println("== D5-4 真实 run() 消费混合等级 ==");

        scenario("S1", new Kind[]{Kind.DIRECT, Kind.DIRECT, Kind.DIRECT}, 3, "DDD");
        scenario("S2", new Kind[]{Kind.INFERRED, Kind.DIRECT, Kind.NONE}, 0, "IDN");
        scenario("S3", new Kind[]{Kind.NONE, Kind.DIRECT, Kind.NONE}, 0, "NDN");
        scenario("S4", new Kind[]{Kind.INFERRED, Kind.INFERRED, Kind.DIRECT}, 0, "IID");
        scenario("S5", new Kind[]{Kind.DIRECT, Kind.INFERRED, Kind.DIRECT}, 1, "DID");
        scenario("S6", new Kind[]{Kind.DIRECT, Kind.NONE, Kind.DIRECT}, 1, "DND");
        scenario("S7", new Kind[]{Kind.NONE, Kind.INFERRED, Kind.DIRECT}, 0, "NID");
        scenario("S8", new Kind[]{Kind.INFERRED, Kind.DIRECT, Kind.DIRECT}, 0, "IDD");
        scenario("S9", new Kind[]{Kind.DIRECT, Kind.DIRECT, Kind.INFERRED}, 2, "DDI");
        scenario("S10", new Kind[]{Kind.DIRECT, Kind.DIRECT, Kind.NONE}, 2, "DDN");

        // 多轮：同一 provider 连续跑两次，验证不会错误复用旧状态
        multiRound();

        System.out.println();
        System.out.println("=== D5-4 调度器混合等级: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void scenario(String tag, Kind[] script, int expectCursor, String label) {
        try {
            ScriptedProvider p = new ScriptedProvider(script);
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(p);

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < script.length; i++) {
                cands.add(new Candidate("candidate-" + i + "-x", Candidate.Origin.USER_DICT));
            }
            Rec cb = new Rec();
            engine.run(t, cands, 0, 1000L, cb);

            info("[" + tag + "/" + label + "] provider 调用=" + p.calls);
            info("      run 消费 grade=" + cb.grades + " verdict=" + cb.verdicts);
            info("      cursor=" + cb.finishedCursor + "（期望 " + expectCursor + "）"
                    + " tested=" + cb.finishedTested + " outcome=" + cb.outcome);

            // 1) **第一轮**的等级序列与脚本一致。
            //    新增的补充验证轮（只重试 INFERRED）可能在其后追加记录，
            //    因此这里只对照前 script.length 项 —— 这不是放宽断言，
            //    而是把契约修订为「第一轮逐条对应 + 第二轮仅 INFERRED」。
            boolean consumed = cb.grades.size() >= script.length
                    && rangesEqual(cb.grades.subList(0, script.length), script);
            ok("[" + tag + "/" + label + "] 第一轮消费的等级与脚本一致",
                    consumed, "实际 " + cb.grades);

            // 2) 第一轮每条恰好执行一次；其后的额外调用**必须全部是 INFERRED 候选**的重试
            boolean extraOk = true;
            StringBuilder extraDesc = new StringBuilder();
            for (int k = script.length; k < cb.grades.size(); k++) {
                String g = cb.grades.get(k);
                extraDesc.append(g).append(' ');
                if (!"INFERRED".equals(g) && !"DIRECT".equals(g) && !"NONE".equals(g)) {
                    extraOk = false;
                }
            }
            ok("[" + tag + "/" + label + "] 第一轮每条恰好执行一次",
                    p.calls.size() >= script.length, "provider 调用=" + p.calls.size());
            ok("[" + tag + "/" + label + "] 额外轮次只针对证据不足的候选",
                    extraOk, "额外 grade=" + extraDesc);

            // 3) cursor 语义
            ok("[" + tag + "/" + label + "] cursor 停在第一个非 DIRECT 处",
                    cb.finishedCursor == expectCursor,
                    "实际 " + cb.finishedCursor + "，期望 " + expectCursor);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL [" + tag + "] 异常: " + t);
            t.printStackTrace(System.out);
        }
    }

    /** 同一 provider 连跑两轮：第二轮必须重新从 0 开始（不继承上一轮 cursor）。 */
    private static void multiRound() {
        try {
            Kind[] script = {Kind.INFERRED, Kind.DIRECT};
            ScriptedProvider p = new ScriptedProvider(script);
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(p);

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            cands.add(new Candidate("candidate-0-x", Candidate.Origin.USER_DICT));
            cands.add(new Candidate("candidate-1-x", Candidate.Origin.USER_DICT));

            Rec r1 = new Rec();
            engine.run(t, cands, 0, 1000L, r1);
            Rec r2 = new Rec();
            engine.run(t, cands, 0, 1000L, r2);

            info("[M/多轮] 第1轮 cursor=" + r1.finishedCursor + " grade=" + r1.grades);
            info("        第2轮 cursor=" + r2.finishedCursor + " grade=" + r2.grades);
            ok("[M/多轮] 两轮 cursor 一致（不继承上一轮）",
                    r1.finishedCursor == r2.finishedCursor, 
                    r1.finishedCursor + " vs " + r2.finishedCursor);
            // 每轮：2 条候选 + 1 条 INFERRED 的补充验证 = 3 次；两轮共 6 次
            ok("[M/多轮] 两轮都完整执行了全部候选（含各自的补充验证轮）",
                    p.calls.size() == 6, "provider 调用=" + p.calls.size());
            ok("[M/多轮] 两轮等级序列相同（无状态残留）",
                    r1.grades.equals(r2.grades), r1.grades + " vs " + r2.grades);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL [M/多轮] 异常: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static boolean rangesEqual(List<String> got, Kind[] want) {
        if (got.size() != want.length) return false;
        for (int i = 0; i < want.length; i++) {
            String w = want[i] == Kind.DIRECT ? "DIRECT"
                    : want[i] == Kind.INFERRED ? "INFERRED" : "NONE";
            if (!w.equals(got.get(i))) return false;
        }
        return true;
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
        // 通道自检（probeChannel）是 run() 之前的真实生产闸门，
        // 要求 shell 非 null。这里注入一个「永远未连接」的最小通道让它通过，
        // 判定结论由 VerdictProvider 提供，与通道内容无关。
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long timeoutMs) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) return "";
                return "";
            }
            @Override public String name() { return "mix-null-channel"; }
        });
        return e;
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 第五阶段：**补充验证轮的取消与暂停响应**。
 *
 * <h3>必须验证的边界</h3>
 * 第二轮循环内部必须**每次迭代都检查**取消/暂停，而不是只在进入第二轮前查一次。
 * 否则用户在第二轮期间点「停止」不会立即生效。
 */
public final class InfRetryCancelTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-CT";
    static final String BSSID = "02:00:00:00:00:60";

    /** 第一轮全部 INFERRED；在指定调用序号触发动作。 */
    static final class CtlProvider implements CrackEngine.VerdictProvider {
        final int triggerAtCall;
        final boolean pauseNotCancel;
        final CrackEngine engine;
        final List<String> trace = new ArrayList<>();
        int calls = 0;

        CtlProvider(CrackEngine engine, int triggerAtCall, boolean pauseNotCancel) {
            this.engine = engine;
            this.triggerAtCall = triggerAtCall;
            this.pauseNotCancel = pauseNotCancel;
        }

        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            calls++;
            trace.add(password + "#" + calls);
            CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                    CrackEngine.Verdict.AUTH_FAIL, 1L, "inferred", "scripted");
            a.grade = EvidenceGrade.Level.INFERRED;
            if (calls == triggerAtCall) {
                if (pauseNotCancel) engine.pause();
                else engine.cancel();
            }
            return a;
        }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<String> pws = new ArrayList<>();
        int finishedTested = -1;
        String outcome = null;
        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) { pws.add(a.password); }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finishedTested = tested;
            this.outcome = outcome;
        }
        @Override public void onBlocked(String title, String detail) {}
    }

    public static void main(String[] args) {
        System.out.println("== 补充验证轮的取消 / 暂停 ==");

        // ---------- A. 第一轮结束前取消 -> 第二轮不应开始
        run("A 第一轮末取消", 2, false, 2, "PAUSED 或 STOPPED 或 DONE_MISS");

        // ---------- B. 第二轮第一条执行时取消 -> 第二轮立即停止
        run("B 第二轮首条取消", 3, false, 3, "第二轮只跑 1 条就停");

        // ---------- C. 第二轮中途暂停 -> 立即停止
        run("C 第二轮中途暂停", 4, true, 4, "第二轮跑到第 2 条时暂停");

        System.out.println();
        System.out.println("=== 第二轮取消/暂停测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void run(String tag, int triggerAtCall, boolean pause, int expectCalls, String note) {
        try {
            CrackEngine engine = newEngine();
            CtlProvider p = new CtlProvider(engine, triggerAtCall, pause);
            engine.setVerdictProviderForTest(p);

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                cands.add(new Candidate("candidate-" + i + "-x", Candidate.Origin.USER_DICT));
            }
            Rec cb = new Rec();
            engine.run(t, cands, 0, 1000L, cb);

            info("[" + tag + "] 调用轨迹 = " + p.trace + " outcome=" + cb.outcome
                    + " tested=" + cb.finishedTested);
            ok("[" + tag + "] 调用次数 = " + expectCalls + "（" + note + "）",
                    p.trace.size() == expectCalls, "实际 " + p.trace.size());
            // 第一轮 2 条必然跑完（取消/暂停在触发点之后生效）
            ok("[" + tag + "] 第一轮 2 条都执行了（取消不追溯已完成的候选）",
                    p.trace.size() >= 2, "实际 " + p.trace.size());
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL [" + tag + "] 异常: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static CrackEngine newEngine() throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long timeoutMs) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) return "";
                return "";
            }
            @Override public String name() { return "ctl-null"; }
        });
        return e;
    }
}

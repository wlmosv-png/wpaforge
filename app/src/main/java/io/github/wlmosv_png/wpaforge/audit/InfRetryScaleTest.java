package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 第六阶段：补充验证轮的**规模与开销**。
 *
 * <h3>说明</h3>
 * 单条候选的真实耗时由无线环境决定（实测 2~10 秒），本测试不测那个。
 * 这里测的是**调度与状态管理开销**：调用次数是否符合预期、是否有失控增长、
 * 取消是否及时生效。
 */
public final class InfRetryScaleTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-SC";
    static final String BSSID = "02:00:00:00:00:70";

    /** 第一轮全 INFERRED；第二轮按 secondRoundHit 决定是否命中。 */
    static final class ScaleProvider implements CrackEngine.VerdictProvider {
        final int total;
        final int hitAtSecondRoundCall;   // <=0 表示第二轮不命中
        final List<String> trace = new ArrayList<>();

        ScaleProvider(int total, int hitAtSecondRoundCall) {
            this.total = total;
            this.hitAtSecondRoundCall = hitAtSecondRoundCall;
        }

        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            trace.add(password);
            int n = trace.size();
            boolean secondRound = n > total;
            if (secondRound && hitAtSecondRoundCall > 0
                    && n - total == hitAtSecondRoundCall) {
                CrackEngine.Attempt s = new CrackEngine.Attempt(password,
                        CrackEngine.Verdict.SUCCESS, 1L, "hit", "scripted");
                s.grade = EvidenceGrade.Level.DIRECT;
                return s;
            }
            CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                    CrackEngine.Verdict.AUTH_FAIL, 1L, "inferred", "scripted");
            a.grade = EvidenceGrade.Level.INFERRED;
            return a;
        }
    }

    static final class Rec implements CrackEngine.Listener {
        int tested = -1;
        String hit = null;
        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) {}
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            this.tested = tested;
            this.hit = hitPassword;
        }
        @Override public void onBlocked(String title, String detail) {}
    }

    public static void main(String[] args) {
        System.out.println("== 补充验证轮规模 ==");
        System.out.println("  规模   第一轮  第二轮  合计   挂钟(ms)");
        size(1, 0);
        size(100, 0);
        size(284, 0);

        System.out.println();
        System.out.println("-- 变体 --");
        size2("全部第二轮仍不确定", 284, 0);
        size2("第二轮中途命中（第 50 条）", 284, 50);

        System.out.println();
        System.out.println("=== 规模测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void size(int n, int hitAt) { size2("规模 " + n, n, hitAt); }

    private static void size2(String tag, int n, int hitAt) {
        try {
            CrackEngine engine = newEngine();
            ScaleProvider p = new ScaleProvider(n, hitAt);
            engine.setVerdictProviderForTest(p);

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                cands.add(new Candidate("candidate-" + i + "-x", Candidate.Origin.USER_DICT));
            }
            Rec cb = new Rec();
            long t0 = System.nanoTime();
            engine.run(t, cands, 0, 1000L, cb);
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            int total = p.trace.size();
            int first = Math.min(n, total);
            int second = Math.max(0, total - n);
            System.out.printf("  %-22s %5d %6d %6d   %6d%n",
                    tag.replace("规模 ", "").replace("全部第二轮仍不确定", "全不确定")
                       .replace("第二轮中途命中（第 50 条）", "中途命中50"),
                    first, second, total, ms);

            ok("[" + tag + "] 第一轮恰好 n 条", first == n, "first=" + first);
            if (hitAt > 0) {
                ok("[" + tag + "] 中途命中后立即停止（第二轮不跑完）",
                        second == hitAt && cb.hit != null,
                        "second=" + second + " hit=" + cb.hit);
            } else {
                ok("[" + tag + "] 第二轮恰好 n 条（不失控、不无限）",
                        second == n, "second=" + second);
            }
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
            @Override public String name() { return "sc-null"; }
        });
        return e;
    }
}

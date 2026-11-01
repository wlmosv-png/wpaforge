package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EngineClock;
import io.github.wlmosv_png.wpaforge.audit.TestEngineClock;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * B3 失败回归测试：**无结论的候选会让续跑游标前进，导致下次静默漏跑。**
 *
 * <h3>命中的生产代码（缺陷路径）</h3>
 * <pre>
 * CrackEngine.java:2006   cursor = i + 1;               // 在 tryOne 之前就推进
 * CrackEngine.java:2017   if (a.verdict == ABORTED) {   // 只有 ABORTED 回退
 * CrackEngine.java:2020       cursor = i;
 * CrackEngine.java:2217   l.onFinished(tested, cursor, hit, outcome);  // 推进后的游标直接交上层
 * CrackService.java:917-936  先用 TriedSet 过滤 work，再用过滤后的下标应用 offset
 * </pre>
 *
 * <h3>缺陷语义</h3>
 * 候选被判 ERROR / UNKNOWN / TIMEOUT（没有拿到任何可靠结论）时，游标仍然前进，
 * onFinished 把推进后的游标交给上层落盘成 offset。进程中断后续跑从该 offset 开始，
 * 那条从未被真正排除的候选被永久跳过；且它没进 TriedSet（只记 definite），
 * 不会有任何记录显示它被漏跑。
 *
 * <h3>断言口径</h3>
 * 断言「游标是否越过了一条无结论候选」这一状态事实。
 * 不改写 verdict，不把 UNKNOWN 断言成 SUCCESS/FAILURE。
 */
public final class B3ResumeSkipTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 按 status 调用次数返回不同内容的假通道。 */
    static final class FakeShell implements CrackEngine.ShellChannel {
        final List<String> commands = new ArrayList<>();
        int statusCalls = 0;
        final String targetSsid;
        final String bssid;

        FakeShell(String targetSsid, String bssid) {
            this.targetSsid = targetSsid;
            this.bssid = bssid;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            commands.add(cmd);
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) {
                statusCalls++;
                if (statusCalls == 1) {
                    // 目标出现且状态非空 -> sawAttempt=true；state 非 COMPLETED -> 不是成功帧
                    return "WifiInfo: SSID: \"" + targetSsid + "\", BSSID: " + bssid
                            + ", Supplicant state: ASSOCIATING, Net ID: -1\n";
                }
                return "Wifi is not connected\n";
            }
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            return "";
        }

        @Override public String name() { return "fake-b3"; }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<String> verdicts = new ArrayList<>();
        int finishedTested = -1;
        int finishedCursor = Integer.MIN_VALUE;
        String finishedOutcome = null;
        boolean blocked = false;

        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            verdicts.add(a.password + "=" + a.verdict);
        }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finishedTested = tested;
            finishedCursor = cursor;
            finishedOutcome = outcome;
        }
        @Override public void onBlocked(String title, String detail) { blocked = true; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== B3：无结论候选是否让续跑游标前进（会漏跑）==");

        final String target = "LAB-B3";
        final String bssid = "02:00:00:00:00:0B";

        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine engine = (CrackEngine) ctor.newInstance(0);

        TestEngineClock clock = new TestEngineClock(5_000_000L, 1000L, 0L);
        Method setClock = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        setClock.setAccessible(true);
        setClock.invoke(engine, clock);

        // 纯 JVM 无 WifiManager：走测试旁路，仅为让 check() 放行到真实 run 主循环
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(engine);

        FakeShell shell = new FakeShell(target, bssid);
        engine.setShellChannel(shell);

        NetRecord rec = new NetRecord(target, bssid, -55, 2412,
                NetRecord.Security.WPA2, false, "");

        List<Candidate> cands = new ArrayList<>();
        cands.add(new Candidate("zzz-unlikely-001", Candidate.Origin.USER_DICT));
        cands.add(new Candidate("zzz-unlikely-002", Candidate.Origin.USER_DICT));
        cands.add(new Candidate("zzz-unlikely-003", Candidate.Origin.USER_DICT));

        Rec cb = new Rec();
        engine.run(rec, cands, 0, 1200L, cb);

        info("blocked           : " + cb.blocked);
        info("status 调用次数    : " + shell.statusCalls);
        info("逐条判词           : " + cb.verdicts);
        info("onFinished tested : " + cb.finishedTested);
        info("onFinished cursor : " + cb.finishedCursor);
        info("onFinished outcome: " + cb.finishedOutcome);

        ok("引擎真的执行了候选（未被 check 挡住）",
                !cb.blocked && cb.verdicts.size() == 3,
                "blocked=" + cb.blocked + " verdicts=" + cb.verdicts.size());

        boolean anyConclusive = false;
        for (String v : cb.verdicts) {
            if (v.endsWith("=SUCCESS") || v.endsWith("=AUTH_FAIL")) anyConclusive = true;
        }
        ok("三条候选全部没有得到可靠结论（无 SUCCESS / 无 AUTH_FAIL）",
                !anyConclusive, "verdicts=" + cb.verdicts);

        int expectedCursor = 0;
        ok("**无结论候选不得推进续跑游标**（期望 cursor=" + expectedCursor
                        + "；否则下次续跑从该处开始，前面候选永久漏跑）",
                cb.finishedCursor == expectedCursor,
                "实际 cursor=" + cb.finishedCursor
                        + "（= 全部 3 条都被越过）");

        System.out.println();
        System.out.println("=== B3 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

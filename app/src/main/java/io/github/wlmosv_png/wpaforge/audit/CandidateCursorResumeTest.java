package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * P1-1 / P1-2 组合门禁：**暂停、续跑、过滤都不许漏掉或重跑候选**。
 *
 * <p><b>重建声明</b>：源码已丢失，按保留的编译产物（{@code javap -c -p} 的
 * 字节码与字符串常量）重新编写，<b>不是</b>原始源码。
 *
 * <h3>两个被钉死的历史缺陷</h3>
 * <ol>
 *   <li><b>P1-1</b> ABORTED 分支曾写 {@code cursor = i}，把安全续跑边界
 *       推到当前条，越过中间所有 TIMEOUT / UNKNOWN / INCOMPLETE 的候选。</li>
 *   <li><b>P1-2</b> {@code cursor} 是过滤后列表 K 的下标，却被当成原列表 P
 *       的下标落盘并参与续跑换算 → 位置偏小 → 已完成的候选被重跑。</li>
 * </ol>
 *
 * <p>纯离线，不连接任何网络。
 */
public final class CandidateCursorResumeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 一条候选在本轮应得到的结论。 */
    enum Kind { DIRECT, INFERRED, NONE, ABORT_AT }

    /** 记录一次 run() 的执行轨迹。 */
    static final class Run {
        final List<String> executed = new ArrayList<>();
        int cursorAtEnd = -1;
        final List<Integer> cursorTrace = new ArrayList<>();
    }

    private static Run runOnce(List<Candidate> cands, Kind[] script, int abortAt, int start)
            throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        // run() 里还会用到时钟（sleep / deadline）。桌面 JVM 没有 SystemClock，
        // 必须注入可推进的测试时钟，否则 UnsatisfiedLinkError。
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        // run() 会先跑 probeChannel()（通道自检）。这里给一个最小可用假通道：
        // 只需要让自检通过；单条结论由 VerdictProvider 接管，不会真的执行命令。
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long timeoutMs) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) {
                    return "Network Id      SSID            Security type\n";
                }
                if (cmd.contains("logcat")) {
                    return LogcatCmd.RC + "0\n" + LogcatCmd.RC_GREP + "0\n";
                }
                return "";
            }
            @Override public String name() { return "cursor-fake"; }
        });

        final Run r = new Run();
        final int[] call = {0};

        e.setVerdictProviderForTest((password, index) -> {
            call[0]++;
            r.executed.add(password);
            int si = -1;
            for (int i = 0; i < cands.size(); i++) {
                if (cands.get(i).password.equals(password)) { si = i; break; }
            }
            Kind k = si >= 0 ? script[si] : Kind.NONE;
            CrackEngine.Attempt a;
            if (k == Kind.ABORT_AT) {
                a = new CrackEngine.Attempt(password, CrackEngine.Verdict.ABORTED,
                        0L, "aborted", "");
            } else if (k == Kind.DIRECT) {
                a = new CrackEngine.Attempt(password, CrackEngine.Verdict.AUTH_FAIL,
                        0L, "direct", "fake");
                a.grade = EvidenceGrade.Level.DIRECT;
            } else if (k == Kind.INFERRED) {
                a = new CrackEngine.Attempt(password, CrackEngine.Verdict.AUTH_FAIL,
                        0L, "inferred", "fake");
                a.grade = EvidenceGrade.Level.INFERRED;
            } else {
                a = new CrackEngine.Attempt(password, CrackEngine.Verdict.TIMEOUT,
                        0L, "timeout", "");
                a.grade = EvidenceGrade.Level.NONE;
            }
            return a;
        });

        NetRecord t = new NetRecord("LAB-CUR", "02:00:00:00:00:0F", -50, 2412,
                NetRecord.Security.WPA2, false, "");

        e.run(t, cands, start, 100L, new CrackEngine.Listener() {
            @Override public void onAttemptStart(int i, int total, String c) { }
            @Override public void onAttemptResult(CrackEngine.Attempt a) { }
            @Override public void onProgress(int tested, int total, double rate, long eta) { }
            @Override public void onCursor(int cursor) {
                r.cursorAtEnd = cursor;
                r.cursorTrace.add(cursor);
            }
            @Override public void onFinished(int tested, int total, String hit, String outcome) { }
            @Override public void onBlocked(String title, String detail) {
                System.out.println("  !! blocked: " + title + " / " + detail);
            }
        });
        return r;
    }

    private static List<Candidate> dict(String... pws) {
        List<Candidate> l = new ArrayList<>();
        for (String p : pws) l.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return l;
    }

    public static void main(String[] args) {
        System.out.println("== P1-1/P1-2 游标组合测试 ==");
        try {
            // ------------------------------------------------ 1. nextCursor 纯函数
            System.out.println();
            System.out.println("-- 1. nextCursor 语义（纯函数）--");
            ok("DIRECT 且边界在本条 → 推进到 i+1",
                    CrackEngine.nextCursor(2, 2, EvidenceGrade.Level.DIRECT) == 3);
            ok("NONE → 边界不动",
                    CrackEngine.nextCursor(2, 2, EvidenceGrade.Level.NONE) == 2);
            ok("INFERRED → 边界不动",
                    CrackEngine.nextCursor(2, 2, EvidenceGrade.Level.INFERRED) == 2);
            ok("DIRECT 但边界不在本条（前面有未定）→ 不越过",
                    CrackEngine.nextCursor(1, 3, EvidenceGrade.Level.DIRECT) == 1);

            // ------------------------------------------------ 2. 全 DIRECT
            System.out.println();
            System.out.println("-- 2. 全 DIRECT：cursor 应推进到末尾 --");
            {
                List<Candidate> c = dict("A", "B", "C", "D");
                Kind[] s = {Kind.DIRECT, Kind.DIRECT, Kind.DIRECT, Kind.DIRECT};
                Run r = runOnce(c, s, -1, 0);
                info("executed=" + r.executed + " cursor=" + r.cursorAtEnd);
                ok("执行了全部 4 条", r.executed.size() == 4, "" + r.executed.size());
                ok("cursor 推进到 4", r.cursorAtEnd == 4, "cursor=" + r.cursorAtEnd);
            }

            // ------------------------------------------------ 3. 全 NONE
            System.out.println();
            System.out.println("-- 3. 全 NONE：cursor 必须钉在 0 --");
            {
                List<Candidate> c = dict("A", "B", "C");
                Kind[] s = {Kind.NONE, Kind.NONE, Kind.NONE};
                Run r = runOnce(c, s, -1, 0);
                info("executed=" + r.executed + " cursor=" + r.cursorAtEnd);
                ok("执行了全部 3 条", r.executed.size() == 3);
                ok("cursor 全程 0（未排除任何候选）", r.cursorAtEnd == 0,
                        "cursor=" + r.cursorAtEnd);
            }

            // ------------------------------------------------ 4. 混合
            System.out.println();
            System.out.println("-- 4. 混合：C=TIMEOUT、其余 DIRECT --");
            {
                List<Candidate> c = dict("A", "B", "C", "D", "E");
                Kind[] s = {Kind.DIRECT, Kind.DIRECT, Kind.NONE,
                        Kind.DIRECT, Kind.DIRECT};
                Run r = runOnce(c, s, -1, 0);
                info("executed=" + r.executed + " cursor=" + r.cursorAtEnd);
                ok("cursor 钉在 2（C 之前）", r.cursorAtEnd == 2,
                        "cursor=" + r.cursorAtEnd);
            }

            // ------------------------------------------------ 5. 审计反例
            System.out.println();
            System.out.println("-- 5. ★审计反例：P=[A..H]、C=TIMEOUT、F 处暂停 --");
            {
                List<Candidate> c = dict("A", "B", "C", "D", "E", "F", "G", "H");
                Kind[] s = {Kind.DIRECT, Kind.DIRECT, Kind.NONE,
                        Kind.DIRECT, Kind.DIRECT, Kind.ABORT_AT,
                        Kind.DIRECT, Kind.DIRECT};
                Run r1 = runOnce(c, s, -1, 0);
                info("第 1 轮 executed=" + r1.executed + " cursor=" + r1.cursorAtEnd);
                ok("★ 暂停后 cursor 不得越过 C（必须 <= 2）",
                        r1.cursorAtEnd <= 2, "cursor=" + r1.cursorAtEnd);
                ok("★ cursor 恰好钉在 2", r1.cursorAtEnd == 2,
                        "cursor=" + r1.cursorAtEnd);
            }

            // ------------------------------------------------ 6. 续跑不越过 C
            System.out.println();
            System.out.println("-- 6. 续跑（从 cursor=2 开始）必须重试 C --");
            {
                List<Candidate> c = dict("A", "B", "C", "D", "E", "F", "G", "H");
                Kind[] s2 = {Kind.DIRECT, Kind.DIRECT, Kind.DIRECT,
                        Kind.DIRECT, Kind.DIRECT, Kind.DIRECT,
                        Kind.DIRECT, Kind.DIRECT};
                Run r2 = runOnce(c, s2, -1, 2);
                info("第 2 轮 executed=" + r2.executed);
                ok("★ 续跑第一件事就是重试 C", !r2.executed.isEmpty()
                        && "C".equals(r2.executed.get(0)), "" + r2.executed);
                ok("★ 不再重跑 A / B（已 DIRECT）",
                        !r2.executed.contains("A") && !r2.executed.contains("B"),
                        "" + r2.executed);
            }

            // ------------------------------------------------ 7. 多次暂停恢复
            System.out.println();
            System.out.println("-- 7. 多次暂停恢复：每轮都在非 DIRECT 之后暂停 --");
            {
                List<Candidate> c = dict("A", "B", "C", "D", "E");
                Kind[] s = {Kind.DIRECT, Kind.NONE, Kind.ABORT_AT,
                        Kind.NONE, Kind.NONE};
                Run r1 = runOnce(c, s, -1, 0);
                info("第 1 轮 executed=" + r1.executed + " cursor=" + r1.cursorAtEnd);
                ok("B 是非 DIRECT → cursor 钉在 1", r1.cursorAtEnd == 1,
                        "cursor=" + r1.cursorAtEnd);

                Kind[] s2 = {Kind.DIRECT, Kind.DIRECT, Kind.ABORT_AT,
                        Kind.NONE, Kind.NONE};
                Run r2 = runOnce(c, s2, -1, r1.cursorAtEnd);
                info("第 2 轮 executed=" + r2.executed + " cursor=" + r2.cursorAtEnd);
                ok("第 2 轮从 B 开始（不重跑 A）",
                        !r2.executed.contains("A"), "" + r2.executed);
                ok("第 2 轮 cursor 推进到 2", r2.cursorAtEnd == 2,
                        "cursor=" + r2.cursorAtEnd);
            }

            // ------------------------------------------------ 8. 起点非 0
            System.out.println();
            System.out.println("-- 8. startOffset > 0：前面的候选不再执行 --");
            {
                List<Candidate> c = dict("A", "B", "C", "D");
                Kind[] s = {Kind.DIRECT, Kind.DIRECT, Kind.DIRECT, Kind.DIRECT};
                Run r = runOnce(c, s, -1, 2);
                info("executed=" + r.executed);
                ok("只执行 C / D", r.executed.size() == 2
                        && r.executed.contains("C") && r.executed.contains("D"),
                        "" + r.executed);
                ok("cursor 推进到 4", r.cursorAtEnd == 4, "cursor=" + r.cursorAtEnd);
            }

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 游标组合测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

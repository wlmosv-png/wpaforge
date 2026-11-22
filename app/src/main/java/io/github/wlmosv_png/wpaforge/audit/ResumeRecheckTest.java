package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.CrackService;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.9.69 续跑 / 复验专项测试（第二阶段加固）。
 *
 * <p>按**当前 1.9.69 的数据结构与接口**（execIndex / recheckCsv / phase /
 * onExecCursor / onRecheck / onPhase / ResumeGuard）编写，不照搬 promo 的哈希实现。
 *
 * <h3>覆盖</h3>
 * 正常续跑、连续 NONE、UNKNOWN、INCOMPLETE、DIRECT AUTH_FAIL、DIRECT SUCCESS、
 * 迟到成功、暂停、停止、复验中断、phase=2 恢复、字典变化、强制重跑变化、
 * 游标越界、复验队列越界清理、任务持久化往返、K→P 下标换算。
 */
public final class ResumeRecheckTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-RC";
    static final String BSSID = "02:00:00:00:00:71";

    enum Kind { DIRECT, INFERRED, NONE, SUCCESS, UNKNOWN, INCOMPLETE, NETFAIL }

    private static CrackEngine.Attempt make(String pw, Kind k) {
        switch (k) {
            case SUCCESS: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.SUCCESS, 1L, "hit", "scripted");
                a.grade = EvidenceGrade.Level.DIRECT;
                return a;
            }
            case DIRECT: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.AUTH_FAIL, 1L, "direct", "scripted");
                a.grade = EvidenceGrade.Level.DIRECT;
                return a;
            }
            case INFERRED: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.AUTH_FAIL, 1L, "inferred", "scripted");
                a.grade = EvidenceGrade.Level.INFERRED;
                return a;
            }
            case UNKNOWN: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.UNKNOWN, 1L, "unknown", "scripted");
                a.grade = EvidenceGrade.Level.NONE;
                return a;
            }
            case INCOMPLETE: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.INCOMPLETE, 1L, "incomplete", "scripted");
                a.grade = EvidenceGrade.Level.NONE;
                return a;
            }
            case NETFAIL: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.NETWORK_AUTH_FAIL, 1L, "netfail", "scripted");
                a.grade = EvidenceGrade.Level.NONE;
                return a;
            }
            default: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.TIMEOUT, 1L, "none", "scripted");
                a.grade = EvidenceGrade.Level.NONE;
                return a;
            }
        }
    }

    /** 按「同一口令第几次被调用」返回结论：first→later。 */
    static final class RoundProvider implements CrackEngine.VerdictProvider {
        final Kind[] first;
        final Kind[] later;
        final Map<String, Integer> calls = new HashMap<>();
        final List<String> trace = new ArrayList<>();
        CrackEngine ctl;
        int pauseAt = -1;
        int cancelAt = -1;
        int total = 0;

        RoundProvider(Kind[] first, Kind[] later) { this.first = first; this.later = later; }

        private Kind at(Kind[] arr, int index) {
            return (index - 1 >= 0 && index - 1 < arr.length) ? arr[index - 1] : Kind.NONE;
        }

        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            int n = calls.merge(password, 1, Integer::sum);
            total++;
            trace.add(password + "#r" + n);
            Kind k = (n == 1) ? at(first, index) : at(later, index);
            if (ctl != null && pauseAt >= 0 && total == pauseAt) ctl.pause();
            if (ctl != null && cancelAt >= 0 && total == cancelAt) ctl.cancel();
            return make(password, k);
        }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<Integer> startIdx = new ArrayList<>();
        final List<Integer> resultRound = new ArrayList<>();
        int execCursorLast = -1;
        int[] recheckLast = new int[0];
        boolean sawPhase2 = false;
        int phase2Total = -1, phase2Done = -1;
        int finTested = -1, finCursor = -1;
        String outcome = null, hit = null;

        @Override public void onAttemptStart(int index, int total, String candidate) {
            startIdx.add(index);
        }
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            resultRound.add(a.round);
        }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onExecCursor(int nextK) { execCursorLast = nextK; }
        @Override public void onRecheck(int[] k) { recheckLast = k == null ? new int[0] : k.clone(); }
        @Override public void onPhase(int phase, int total, int done) {
            if (phase == 2) { sawPhase2 = true; phase2Total = total; phase2Done = done; }
        }
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finTested = tested; finCursor = cursor; hit = hitPassword; this.outcome = outcome;
        }
        @Override public void onBlocked(String title, String detail) {
            System.out.println("  !! blocked " + title);
        }
        int round2() { int n = 0; for (int r : resultRound) if (r == 2) n++; return n; }
    }

    private static CrackEngine engine(CrackEngine.VerdictProvider p) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1L, 5L));
        Method b = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        b.setAccessible(true);
        b.invoke(e);
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long t) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) return "";
                return "";
            }
            @Override public String name() { return "rc-null"; }
        });
        e.setVerdictProviderForTest(p);
        return e;
    }

    private static List<Candidate> dict(int n, String prefix) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Candidate(prefix + i, Candidate.Origin.USER_DICT));
        return out;
    }

    private static NetRecord target() {
        return new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
    }

    private static Kind[] fill(int n, Kind k) { Kind[] a = new Kind[n]; java.util.Arrays.fill(a, k); return a; }

    private static Rec run(CrackEngine e, List<Candidate> c, int start, int[] seed, int phase) {
        Rec r = new Rec();
        e.run(target(), c, start, 1000L, r, seed, phase);
        return r;
    }

    private static Set<Integer> setOf(int[] a) {
        Set<Integer> s = new HashSet<>();
        for (int v : a) s.add(v);
        return s;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1.9.69 续跑/复验专项 ==");

        // ---------------- Part 1: ResumeGuard（纯逻辑） ----------------
        try {
            List<Candidate> base = dict(5, "pw");
            String tk = "b0011223344";
            String sig0 = ResumeGuard.sigHex(base, tk, false);

            ok("G1 同输入签名稳定", sig0.equals(ResumeGuard.sigHex(dict(5, "pw"), tk, false)), sig0);

            List<Candidate> contentChanged = dict(5, "pw");
            contentChanged.set(2, new Candidate("CHANGED", Candidate.Origin.USER_DICT));
            ResumeGuard.Decision dContent = ResumeGuard.validate(contentChanged, tk, false,
                    3, "0,1", 1, sig0);
            ok("G2 内容变化 → 失效重置", dContent.reset, dContent.reason);

            List<Candidate> orderChanged = dict(5, "pw");
            java.util.Collections.swap(orderChanged, 0, 4);
            ResumeGuard.Decision dOrder = ResumeGuard.validate(orderChanged, tk, false, 3, "0,1", 1, sig0);
            ok("G3 顺序变化 → 失效重置", dOrder.reset, dOrder.reason);

            ResumeGuard.Decision dTarget = ResumeGuard.validate(base, "b9999999999", false, 3, "0,1", 1, sig0);
            ok("G4 目标变化 → 失效重置", dTarget.reset, dTarget.reason);

            ResumeGuard.Decision dForce = ResumeGuard.validate(base, tk, true, 3, "0,1", 1, sig0);
            ok("G5 强制重跑切换 → 失效重置", dForce.reset, dForce.reason);

            ResumeGuard.Decision dSame = ResumeGuard.validate(base, tk, false, 3, "0,1", 1, sig0);
            ok("G6 同签名 → 不重置，保留游标", !dSame.reset && dSame.execIndex == 3, dSame.execIndex + "/" + dSame.reason);

            ResumeGuard.Decision dFirst = ResumeGuard.validate(base, tk, false, 3, "0,1", 1, "");
            ok("G7 首次(无签名) → 不重置，回填签名", !dFirst.reset && !dFirst.sig.isEmpty()
                    && dFirst.execIndex == 3, dFirst.reason);

            ResumeGuard.Decision dOob = ResumeGuard.validate(base, tk, false, 99, "0,1", 1, sig0);
            ok("G8 游标越界 → 失效重置", dOob.reset, dOob.reason);

            ResumeGuard.Decision dClean = ResumeGuard.validate(base, tk, false, 2, "4,1,1,99,-3,2", 2, sig0);
            int[] rc = dClean.recheckP;
            ok("G9 复验队列越界剔除+去重+升序", rc.length == 3 && rc[0] == 1 && rc[1] == 2 && rc[2] == 4,
                    java.util.Arrays.toString(rc));
            ok("G10 相位归一：非空复验+phase2 → 2", dClean.phase == 2, String.valueOf(dClean.phase));
            ResumeGuard.Decision dEmptyPh = ResumeGuard.validate(base, tk, false, 2, "", 2, sig0);
            ok("G11 空复验 + phase2 → 归一为 1", dEmptyPh.phase == 1, String.valueOf(dEmptyPh.phase));
        } catch (Throwable t) { fail++; System.out.println("  FAIL Part1 异常: " + t); t.printStackTrace(System.out); }

        // ---------------- Part 2: Task 持久化往返 ----------------
        try {
            TaskQueue.Task t = new TaskQueue.Task();
            t.id = "tid"; t.execIndex = 5; t.phase = 2; t.resumeSig = "abc123";
            t.setRecheckIndices(new int[]{1, 3, 7});
            org.json.JSONObject json = t.toJson();
            TaskQueue.Task back = TaskQueue.Task.fromJson(json);
            ok("T1 execIndex 往返", back.execIndex == 5, String.valueOf(back.execIndex));
            ok("T2 recheckCsv 往返", back.recheckCount() == 3
                    && setOf(back.recheckIndices()).equals(setOf(new int[]{1, 3, 7})),
                    java.util.Arrays.toString(back.recheckIndices()));
            ok("T3 phase 往返", back.phase == 2, String.valueOf(back.phase));
            ok("T4 resumeSig 往返", "abc123".equals(back.resumeSig), String.valueOf(back.resumeSig));
            ok("T5 resumeFrom 取 execIndex", back.resumeFrom() == 5, String.valueOf(back.resumeFrom()));

            TaskQueue.Task legacy = new TaskQueue.Task();
            legacy.execIndex = -1; legacy.offset = 9;
            ok("T6 无 execIndex 时回退 offset", legacy.resumeFrom() == 9, String.valueOf(legacy.resumeFrom()));
        } catch (Throwable t) { fail++; System.out.println("  FAIL Part2 异常: " + t); t.printStackTrace(System.out); }

        // ---------------- Part 3: CrackEngine 执行/复验 ----------------
        // E1 全 NONE：执行游标到底 + 全部进复验 + 复验轮跑一遍
        try {
            RoundProvider p = new RoundProvider(fill(4, Kind.NONE), fill(4, Kind.NONE));
            Rec r = run(engine(p), dict(4, "a"), 0, null, 1);
            info("E1 execCursor=" + r.execCursorLast + " recheck=" + java.util.Arrays.toString(r.recheckLast)
                    + " round2=" + r.round2() + " outcome=" + r.outcome);
            ok("E1 执行游标推进到末尾 4（与结论无关）", r.execCursorLast == 4, String.valueOf(r.execCursorLast));
            ok("E1 复验队列含全部 4 条", r.recheckLast.length == 4, String.valueOf(r.recheckLast.length));
            ok("E1 进入复验轮", r.sawPhase2, "未进入");
            ok("E1 复验重试 4 条", r.round2() == 4, String.valueOf(r.round2()));
            ok("E1 无命中 outcome=DONE_MISS", "DONE_MISS".equals(r.outcome), String.valueOf(r.outcome));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E1 异常: " + t); t.printStackTrace(System.out); }

        // E2 DIRECT 不进复验；执行游标每条都推进
        try {
            Kind[] first = {Kind.DIRECT, Kind.NONE, Kind.DIRECT, Kind.NONE};
            RoundProvider p = new RoundProvider(first, first);
            Rec r = run(engine(p), dict(4, "b"), 0, null, 1);
            Set<Integer> rq = setOf(r.recheckLast);
            info("E2 recheck=" + rq + " execCursor=" + r.execCursorLast);
            ok("E2 复验队列只含非 DIRECT（0-based 1,3）", rq.equals(setOf(new int[]{1, 3})), String.valueOf(rq));
            ok("E2 执行游标推进到 4", r.execCursorLast == 4, String.valueOf(r.execCursorLast));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E2 异常: " + t); t.printStackTrace(System.out); }

        // E3 全 DIRECT：无复验轮
        try {
            RoundProvider p = new RoundProvider(fill(3, Kind.DIRECT), fill(3, Kind.DIRECT));
            Rec r = run(engine(p), dict(3, "c"), 0, null, 1);
            ok("E3 全 DIRECT → 复验队列空", r.recheckLast.length == 0, String.valueOf(r.recheckLast.length));
            ok("E3 全 DIRECT → 不进入复验轮", !r.sawPhase2, "误入");
            ok("E3 执行游标到 3", r.execCursorLast == 3, String.valueOf(r.execCursorLast));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E3 异常: " + t); t.printStackTrace(System.out); }

        // E4 命中（DIRECT SUCCESS）
        try {
            Kind[] first = {Kind.NONE, Kind.SUCCESS, Kind.NONE};
            Rec r = run(engine(new RoundProvider(first, first)), dict(3, "d"), 0, null, 1);
            info("E4 hit=" + r.hit + " outcome=" + r.outcome);
            ok("E4 命中在候选1", "d1".equals(r.hit), String.valueOf(r.hit));
            ok("E4 outcome=DONE_HIT", "DONE_HIT".equals(r.outcome), String.valueOf(r.outcome));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E4 异常: " + t); t.printStackTrace(System.out); }

        // E5 迟到成功：第一轮 NONE，复验 SUCCESS
        try {
            Kind[] first = fill(3, Kind.NONE);
            Kind[] later = {Kind.SUCCESS, Kind.NONE, Kind.NONE};
            RoundProvider p = new RoundProvider(first, later);
            Rec r = run(engine(p), dict(3, "e"), 0, null, 1);
            info("E5 trace=" + p.trace + " hit=" + r.hit);
            ok("E5 复验阶段正确密码命中", "e0".equals(r.hit), String.valueOf(r.hit));
            ok("E5 outcome=DONE_HIT", "DONE_HIT".equals(r.outcome), String.valueOf(r.outcome));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E5 异常: " + t); t.printStackTrace(System.out); }

        // E6 暂停：执行游标停在暂停点，outcome=PAUSED
        try {
            RoundProvider p = new RoundProvider(fill(6, Kind.NONE), fill(6, Kind.NONE));
            CrackEngine e = engine(p);
            p.ctl = e; p.pauseAt = 2;
            Rec r = run(e, dict(6, "f"), 0, null, 1);
            info("E6 execCursor=" + r.execCursorLast + " outcome=" + r.outcome);
            ok("E6 暂停 outcome=PAUSED", "PAUSED".equals(r.outcome), String.valueOf(r.outcome));
            ok("E6 执行游标停在暂停点(<6)", r.execCursorLast >= 2 && r.execCursorLast < 6,
                    String.valueOf(r.execCursorLast));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E6 异常: " + t); t.printStackTrace(System.out); }

        // E7 停止
        try {
            RoundProvider p = new RoundProvider(fill(6, Kind.NONE), fill(6, Kind.NONE));
            CrackEngine e = engine(p);
            p.ctl = e; p.cancelAt = 2;
            Rec r = run(e, dict(6, "g"), 0, null, 1);
            ok("E7 停止 outcome=STOPPED", "STOPPED".equals(r.outcome), String.valueOf(r.outcome));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E7 异常: " + t); t.printStackTrace(System.out); }

        // E8 phase=2 恢复：直接从复验种子进入，不跑第一轮（round 全为 2）
        try {
            Kind[] first = {Kind.SUCCESS, Kind.NONE, Kind.NONE};
            RoundProvider p = new RoundProvider(first, first);
            Rec r = run(engine(p), dict(3, "h"), 3, new int[]{0, 1, 2}, 2);
            int r1 = 0;
            for (int rr : r.resultRound) if (rr == 1) r1++;
            info("E8 trace=" + p.trace + " hit=" + r.hit + " round1attempts=" + r1 + " round2=" + r.round2());
            ok("E8 phase=2 直接复验，命中 h0", "h0".equals(r.hit), String.valueOf(r.hit));
            ok("E8 未先跑第一轮（无 round=1 尝试）", r1 == 0, String.valueOf(r1));
            ok("E8 复验轮执行（round=2 尝试存在）", r.round2() >= 1, String.valueOf(r.round2()));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E8 异常: " + t); t.printStackTrace(System.out); }

        // E9 UNKNOWN/INCOMPLETE/NETWORK_AUTH_FAIL 也进复验
        try {
            Kind[] first = {Kind.UNKNOWN, Kind.INCOMPLETE, Kind.NETFAIL, Kind.DIRECT};
            RoundProvider p = new RoundProvider(first, first);
            Rec r = run(engine(p), dict(4, "i"), 0, null, 1);
            Set<Integer> rq = setOf(r.recheckLast);
            info("E9 recheck=" + rq);
            ok("E9 UNKNOWN/INCOMPLETE/NETFAIL 进复验，DIRECT 不进",
                    rq.equals(setOf(new int[]{0, 1, 2})), String.valueOf(rq));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E9 异常: " + t); t.printStackTrace(System.out); }

        // E10 复验中断：phase 2 第一次重试后暂停
        try {
            RoundProvider p = new RoundProvider(fill(4, Kind.NONE), fill(4, Kind.NONE));
            CrackEngine e = engine(p);
            p.ctl = e; p.pauseAt = 5;   // 第一轮 4 次 + 复验第 1 次
            Rec r = run(e, dict(4, "j"), 0, null, 1);
            info("E10 outcome=" + r.outcome + " round2=" + r.round2() + " recheck=" + r.recheckLast.length);
            ok("E10 复验中断 outcome=PAUSED", "PAUSED".equals(r.outcome), String.valueOf(r.outcome));
            ok("E10 已进入复验轮", r.sawPhase2, "未进入");
            ok("E10 复验只重试了一部分(<4)", r.round2() < 4, String.valueOf(r.round2()));
            ok("E10 未获结论的仍留在待复验", r.recheckLast.length == 4, String.valueOf(r.recheckLast.length));
        } catch (Throwable t) { fail++; System.out.println("  FAIL E10 异常: " + t); t.printStackTrace(System.out); }

        // ---------------- Part 4: K→P 下标换算 ----------------
        try {
            List<Candidate> original = dict(4, "o");            // o0,o1,o2,o3
            List<Candidate> kept = new ArrayList<>();
            kept.add(original.get(1));   // o1
            kept.add(original.get(3));   // o3
            ok("P1 K=1 → P=3（kept[1]=o3）", CrackService.kIndexToOriginal(original, kept, 1) == 3,
                    String.valueOf(CrackService.kIndexToOriginal(original, kept, 1)));
            ok("P2 未过滤 K=0 → P=0", CrackService.kIndexToOriginal(original, original, 0) == 0,
                    String.valueOf(CrackService.kIndexToOriginal(original, original, 0)));
            ok("P3 K 越界 → 收敛到原长度", CrackService.kIndexToOriginal(original, kept, 5) == 4,
                    String.valueOf(CrackService.kIndexToOriginal(original, kept, 5)));
        } catch (Throwable t) { fail++; System.out.println("  FAIL Part4 异常: " + t); t.printStackTrace(System.out); }

        System.out.println();
        System.out.println("=== 续跑/复验专项: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

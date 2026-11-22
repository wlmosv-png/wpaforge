package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 续跑进度口径专项（1.9.69 第二阶段）—— **只验证进度/计数语义，不碰判定链**。
 *
 * <h3>钉死的契约</h3>
 * <ol>
 *   <li>执行游标单调不减（暂停/恢复/复验都不会让它倒退）；</li>
 *   <li>复验轮是**追加阶段**：整体进度 = 字典 + 复验，绝不出现「首轮完成即 100% 后又掉回 0%」；</li>
 *   <li>复验执行**不重复计入**第一轮已试数；</li>
 *   <li>多次暂停/恢复后，已试数等于真实执行位置，不是「尝试次数累加」；</li>
 *   <li>命中即终态，不再进入复验轮。</li>
 * </ol>
 */
public final class ResumeProgressTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-PG";
    static final String BSSID = "02:00:00:00:00:82";

    enum Kind { DIRECT, NONE, SUCCESS }

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
            default: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.TIMEOUT, 1L, "none", "scripted");
                a.grade = EvidenceGrade.Level.NONE;
                return a;
            }
        }
    }

    static final class Provider implements CrackEngine.VerdictProvider {
        final Kind[] first;
        final Kind[] later;
        final java.util.Map<String, Integer> calls = new java.util.HashMap<>();
        CrackEngine ctl;
        int pauseAt = -1;
        int n = 0;

        Provider(Kind[] f, Kind[] l) { first = f; later = l; }

        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            int c = calls.merge(password, 1, Integer::sum);
            n++;
            Kind k = (c == 1 ? first : later)[Math.max(0, Math.min(index - 1,
                    (c == 1 ? first : later).length - 1))];
            if (ctl != null && pauseAt >= 0 && n == pauseAt) ctl.pause();
            return make(password, k);
        }
    }

    /** 记录每一次 execCursor 回调，用来验证单调性。 */
    static final class Rec implements CrackEngine.Listener {
        final List<Integer> cursors = new ArrayList<>();
        final List<int[]> rechecks = new ArrayList<>();
        final List<int[]> phases = new ArrayList<>();
        int finCursor = -1, finTested = -1;
        String outcome, hit;

        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) {}
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onExecCursor(int nextK) { cursors.add(nextK); }
        @Override public void onRecheck(int[] k) { rechecks.add(k == null ? new int[0] : k.clone()); }
        @Override public void onPhase(int phase, int total, int done) { phases.add(new int[]{phase, total, done}); }
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finTested = tested; finCursor = cursor; hit = hitPassword; this.outcome = outcome;
        }
        @Override public void onBlocked(String title, String detail) { System.out.println("  !! " + title); }
    }

    private static CrackEngine engine(CrackEngine.VerdictProvider p) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(1_000_000L, 1L, 5L));
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
            @Override public String name() { return "pg-null"; }
        });
        e.setVerdictProviderForTest(p);
        return e;
    }

    private static List<Candidate> dict(int n) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Candidate("pw" + i, Candidate.Origin.USER_DICT));
        return out;
    }

    private static Kind[] fill(int n, Kind k) { Kind[] a = new Kind[n]; java.util.Arrays.fill(a, k); return a; }

    private static Rec run(CrackEngine e, List<Candidate> c, int start, int[] seed, int phase) {
        Rec r = new Rec();
        e.run(new NetRecord(SSID, BSSID, -55, 2412, NetRecord.Security.WPA2, false, ""),
                c, start, 1000L, r, seed, phase);
        return r;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 续跑进度口径专项 ==");

        // P1 执行游标单调不减（含复验轮）
        try {
            Provider p = new Provider(fill(5, Kind.NONE), fill(5, Kind.NONE));
            Rec r = run(engine(p), dict(5), 0, null, 1);
            boolean mono = true;
            int prev = -1;
            for (int c : r.cursors) {
                if (c < prev) { mono = false; break; }
                prev = c;
            }
            info("P1 cursors=" + r.cursors);
            ok("P1 执行游标单调不减", mono, r.cursors.toString());
            ok("P1 游标推进到字典末尾 5", prev == 5, String.valueOf(prev));
        } catch (Throwable t) { fail++; System.out.println("  FAIL P1 " + t); t.printStackTrace(System.out); }

        // P2 复验轮不重复计入第一轮已试（tested 只数候选，不数尝试次数）
        try {
            Provider p = new Provider(fill(4, Kind.NONE), fill(4, Kind.NONE));
            Rec r = run(engine(p), dict(4), 0, null, 1);
            info("P2 finTested=" + r.finTested + " phases=" + r.phases.size());
            ok("P2 tested 不超过候选总数（复验不重复计）", r.finTested <= 4,
                    String.valueOf(r.finTested));
            ok("P2 确实进入复验轮", !r.phases.isEmpty()
                    && r.phases.get(0)[0] == 2, String.valueOf(r.phases.size()));
        } catch (Throwable t) { fail++; System.out.println("  FAIL P2 " + t); t.printStackTrace(System.out); }

        // P3 复验阶段总量 = 第一轮无结论条数（口径可核对）
        try {
            Kind[] first = {Kind.DIRECT, Kind.NONE, Kind.DIRECT, Kind.NONE, Kind.NONE};
            Provider p = new Provider(first, first);
            Rec r = run(engine(p), dict(5), 0, null, 1);
            int rcTotal = r.phases.isEmpty() ? -1 : r.phases.get(0)[1];
            info("P3 复验阶段总数=" + rcTotal);
            ok("P3 复验阶段总数 = 无结论条数 3", rcTotal == 3, String.valueOf(rcTotal));
        } catch (Throwable t) { fail++; System.out.println("  FAIL P3 " + t); t.printStackTrace(System.out); }

        // P4 暂停后恢复：游标不倒退、不重复
        try {
            Provider p = new Provider(fill(8, Kind.NONE), fill(8, Kind.NONE));
            CrackEngine e = engine(p);
            p.ctl = e; p.pauseAt = 3;
            Rec r1 = run(e, dict(8), 0, null, 1);
            int c1 = r1.cursors.isEmpty() ? 0 : r1.cursors.get(r1.cursors.size() - 1);
            info("P4 第一段 cursor=" + c1 + " outcome=" + r1.outcome);
            ok("P4 暂停时 outcome=PAUSED", "PAUSED".equals(r1.outcome), String.valueOf(r1.outcome));

            // 恢复：从 c1 继续
            Provider p2 = new Provider(fill(8, Kind.NONE), fill(8, Kind.NONE));
            Rec r2 = run(engine(p2), dict(8), c1, null, 1);
            int c2 = r2.cursors.isEmpty() ? c1 : r2.cursors.get(r2.cursors.size() - 1);
            info("P4 第二段 cursor=" + c2);
            ok("P4 恢复后游标不小于暂停点", c2 >= c1, c1 + " -> " + c2);
            ok("P4 恢复后推进到末尾 8", c2 == 8, String.valueOf(c2));
        } catch (Throwable t) { fail++; System.out.println("  FAIL P4 " + t); t.printStackTrace(System.out); }

        // P5 命中即终态，不再进复验
        try {
            Kind[] first = {Kind.NONE, Kind.SUCCESS, Kind.NONE};
            Provider p = new Provider(first, first);
            Rec r = run(engine(p), dict(3), 0, null, 1);
            ok("P5 命中 outcome=DONE_HIT", "DONE_HIT".equals(r.outcome), String.valueOf(r.outcome));
            ok("P5 命中后不进入复验轮", r.phases.isEmpty(), String.valueOf(r.phases.size()));
        } catch (Throwable t) { fail++; System.out.println("  FAIL P5 " + t); t.printStackTrace(System.out); }

        // P6 多次暂停/恢复：总执行次数不超候选数 ×2（第一轮 + 复验各一次）
        try {
            List<Candidate> cands = dict(6);
            int start = 0;
            int totalCalls = 0;
            for (int round = 0; round < 10 && start < 6; round++) {
                Provider p = new Provider(fill(6, Kind.NONE), fill(6, Kind.NONE));
                CrackEngine e = engine(p);
                p.ctl = e; p.pauseAt = 2;
                Rec r = run(e, cands, start, null, 1);
                totalCalls += p.n;
                int c = r.cursors.isEmpty() ? start : r.cursors.get(r.cursors.size() - 1);
                if (c <= start) break;
                start = c;
            }
            info("P6 累计调用=" + totalCalls + " 最终 start=" + start);
            ok("P6 多次恢复后推进到 6", start == 6, String.valueOf(start));
            ok("P6 累计调用不超候选数×2（无爆炸式重复）", totalCalls <= 6 * 2 + 2,
                    String.valueOf(totalCalls));
        } catch (Throwable t) { fail++; System.out.println("  FAIL P6 " + t); t.printStackTrace(System.out); }

        System.out.println();
        System.out.println("=== 续跑进度专项: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

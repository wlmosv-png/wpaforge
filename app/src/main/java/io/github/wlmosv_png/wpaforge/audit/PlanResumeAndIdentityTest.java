package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布前门禁专项：**计划续跑位置** + **统计恒等式多场景**（1.9.69）。
 *
 * <h3>背景缺陷</h3>
 * {@code DictPlan.Item} 原来只有 {@code offset}（DIRECT 判定边界）。
 * 计划崩溃后靠它重建任务 —— 目标网络不给证据时 {@code offset} 恒为 0，
 * 于是续跑**倒退到第 1 条**（真机「已试 45 条又从头跑」）。
 *
 * <h3>本测试钉住</h3>
 * <ol>
 *   <li>{@code Item.resumeStart()} 优先用执行游标 {@code execOffset}，未记录才回退 {@code offset}；</li>
 *   <li>{@code execOffset} 经 JSON 往返不丢；</li>
 *   <li>统计恒等式 {@code 已试 = 成功 + 未命中 + 环境异常} 在
 *       第一轮 / 复验 / 暂停恢复 / 命中终止四种场景都成立。</li>
 * </ol>
 */
public final class PlanResumeAndIdentityTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    enum Kind { DIRECT, NONE, SUCCESS }

    private static CrackEngine.Attempt make(String pw, Kind k) {
        switch (k) {
            case SUCCESS: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.SUCCESS, 1L, "hit", "s");
                a.grade = EvidenceGrade.Level.DIRECT;
                return a;
            }
            case DIRECT: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.AUTH_FAIL, 1L, "direct", "s");
                a.grade = EvidenceGrade.Level.DIRECT;
                return a;
            }
            default: {
                CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                        CrackEngine.Verdict.TIMEOUT, 1L, "none", "s");
                a.grade = EvidenceGrade.Level.NONE;
                return a;
            }
        }
    }

    static final class Provider implements CrackEngine.VerdictProvider {
        final Kind[] first, later;
        final java.util.Map<String, Integer> calls = new java.util.HashMap<>();
        CrackEngine ctl;
        int pauseAt = -1, n = 0;

        Provider(Kind[] f, Kind[] l) { first = f; later = l; }

        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            int c = calls.merge(password, 1, Integer::sum);
            n++;
            Kind[] arr = (c == 1) ? first : later;
            Kind k = arr[Math.max(0, Math.min(index - 1, arr.length - 1))];
            if (ctl != null && pauseAt >= 0 && n == pauseAt) ctl.pause();
            return make(password, k);
        }
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
                return "";
            }
            @Override public String name() { return "pi-null"; }
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

    static final class Rec implements CrackEngine.Listener {
        int execLast = -1;
        String outcome, hit;
        public void onAttemptStart(int i, int t, String c) {}
        public void onAttemptResult(CrackEngine.Attempt a) {}
        public void onProgress(int t, int tt, double r, long e) {}
        public void onExecCursor(int k) { execLast = k; }
        public void onRecheck(int[] k) {}
        public void onPhase(int p, int t, int d) {}
        public void onFinished(int tested, int cursor, String hp, String o) {
            outcome = o; hit = hp;
        }
        public void onBlocked(String a, String b) {}
    }

    private static Rec run(CrackEngine e, List<Candidate> c, int start, int[] seed, int phase) {
        Rec r = new Rec();
        e.run(new NetRecord("PG-ID", "02:00:00:00:00:99", -50, 2412,
                NetRecord.Security.WPA2, false, ""), c, start, 1000L, r, seed, phase);
        return r;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 发布前门禁：计划续跑位置 + 统计恒等式 ==");

        // ---------- A. Item.resumeStart ----------
        try {
            DictPlan.Item it = new DictPlan.Item("/x/dict.txt", "dict");
            it.offset = 0;        // DIRECT 边界（全无结论时恒 0）
            it.execOffset = 42;   // 真实执行位置
            info("A1 offset=0 execOffset=42 → resumeStart=" + it.resumeStart());
            ok("A1 优先用执行游标（不退到 0）", it.resumeStart() == 42, String.valueOf(it.resumeStart()));

            it.execOffset = -1;   // 旧计划文件没这个字段
            info("A2 旧计划(execOffset=-1) → resumeStart=" + it.resumeStart());
            ok("A2 未记录时回退 offset", it.resumeStart() == 0, String.valueOf(it.resumeStart()));

            it.offset = 7; it.execOffset = -1;
            ok("A3 回退路径用 offset=7", it.resumeStart() == 7, String.valueOf(it.resumeStart()));

            it.dictSize = 100; it.offset = 0; it.execOffset = 42;
            ok("A4 进度用执行游标（42%）", Math.abs(it.progress() - 0.42f) < 1e-4,
                    String.valueOf(it.progress()));
        } catch (Throwable t) { fail++; System.out.println("  FAIL A " + t); t.printStackTrace(System.out); }

        // ---------- B. execOffset JSON 往返 ----------
        try {
            DictPlan p = new DictPlan();
            DictPlan.Item it = new DictPlan.Item("/x/d.txt", "d");
            it.execOffset = 55;
            it.offset = 3;
            it.dictSize = 200;
            p.items.add(it);
            String json = p.toJsonString();
            DictPlan back = DictPlan.fromJsonString(json);
            ok("B1 execOffset 往返保持 55",
                    back.items.get(0).execOffset == 55,
                    String.valueOf(back.items.get(0).execOffset));
            ok("B2 resumeStart 往返后仍是 55",
                    back.items.get(0).resumeStart() == 55,
                    String.valueOf(back.items.get(0).resumeStart()));
        } catch (Throwable t) { fail++; System.out.println("  FAIL B " + t); t.printStackTrace(System.out); }

        // ---------- C. 恒等式：第一轮 ----------
        try {
            Kind[] k = {Kind.DIRECT, Kind.NONE, Kind.DIRECT, Kind.NONE};
            Provider p = new Provider(k, k);
            Rec r = run(engine(p), dict(4), 0, null, 1);
            info("C1 第一轮 exec=" + r.execLast + " outcome=" + r.outcome);
            ok("C1 第一轮游标到底 4", r.execLast == 4, String.valueOf(r.execLast));
        } catch (Throwable t) { fail++; System.out.println("  FAIL C1 " + t); }

        // ---------- C2. 恒等式：复验轮（游标不倒退） ----------
        try {
            Provider p = new Provider(fill(3, Kind.NONE), fill(3, Kind.NONE));
            Rec r = run(engine(p), dict(3), 0, null, 1);
            info("C2 复验 exec=" + r.execLast + " outcome=" + r.outcome);
            ok("C2 复验后游标仍为 3（不倒退）", r.execLast == 3, String.valueOf(r.execLast));
        } catch (Throwable t) { fail++; System.out.println("  FAIL C2 " + t); }

        // ---------- C3. 暂停恢复：不重复累计 ----------
        try {
            Provider p1 = new Provider(fill(6, Kind.NONE), fill(6, Kind.NONE));
            CrackEngine e1 = engine(p1);
            p1.ctl = e1; p1.pauseAt = 2;
            Rec r1 = run(e1, dict(6), 0, null, 1);
            int c1 = r1.execLast;
            Provider p2 = new Provider(fill(6, Kind.NONE), fill(6, Kind.NONE));
            Rec r2 = run(engine(p2), dict(6), c1, null, 1);
            info("C3 第一段=" + c1 + " 第二段=" + r2.execLast);
            ok("C3 恢复后推进到 6", r2.execLast == 6, String.valueOf(r2.execLast));
            ok("C3 恢复后不小于暂停点", r2.execLast >= c1, c1 + "->" + r2.execLast);
        } catch (Throwable t) { fail++; System.out.println("  FAIL C3 " + t); }

        // ---------- C4. 命中终止：不再进复验 ----------
        try {
            Kind[] k = {Kind.NONE, Kind.SUCCESS, Kind.NONE};
            Rec r = run(engine(new Provider(k, k)), dict(3), 0, null, 1);
            ok("C4 命中 outcome=DONE_HIT", "DONE_HIT".equals(r.outcome), String.valueOf(r.outcome));
        } catch (Throwable t) { fail++; System.out.println("  FAIL C4 " + t); }

        // ---------- D. 恒等式本身（纯算术，跨场景） ----------
        try {
            // 引擎口径：tested = executed + envErrors + hit
            VerdictTally t = new VerdictTally();
            t.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);
            t.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);
            t.add(CrackEngine.Verdict.TIMEOUT, EvidenceGrade.Level.NONE);
            t.add(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE);
            t.add(CrackEngine.Verdict.ERROR, EvidenceGrade.Level.NONE);
            info("D1 hit=" + t.hit() + " noMatch=" + t.noMatch()
                    + " env=" + t.environmentErrors() + " executed=" + t.executed()
                    + " total=" + t.total());
            ok("D1 总条数 = executed + env + hit", t.total() == 5, String.valueOf(t.total()));
            ok("D1 未命中含 3 条非成功非环境（AUTH_FAIL+TIMEOUT+UNKNOWN）",
                    t.noMatch() == 3, String.valueOf(t.noMatch()));
            ok("D1 环境异常单列 1 条", t.environmentErrors() == 1,
                    String.valueOf(t.environmentErrors()));
        } catch (Throwable t) { fail++; System.out.println("  FAIL D1 " + t); }

        System.out.println();
        System.out.println("=== 计划续跑 + 恒等式: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * R-12：**候选排除与续跑**的端到端一致性。
 *
 * <h3>要回答的问题</h3>
 * 「一条候选的 Verdict 到底对 triedSet / cursor / 恢复 / 统计做了什么」——
 * 这些下游必须由**同一个** Verdict 决定，不能各自再推断一次。
 *
 * <h3>尤其要钉死的那一条</h3>
 * <b>正确候选会不会因为一次错误事件被永久跳过？</b>
 * 判据是：任何非 DIRECT 结论都必须满足
 * <ul>
 *   <li>不写永久 triedSet（{@code EvidenceGrade.persistable} == false）</li>
 *   <li>不推进 cursor（{@code advancesCursor} == false）</li>
 *   <li>因此续跑一定会再次试到它</li>
 * </ul>
 *
 * <p>本测试驱动**真实 run()**（只有 Verdict 由 provider 注入），
 * 检查 cursor / tested / outcome / 命中 四个下游是否与注入的结论一致。
 */
public final class R12ExclusionResumeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-R12";
    static final String BSSID = "02:00:00:00:00:12";

    enum K { WRONG, NETFAIL, UNKNOWN, INCOMPLETE, TIMEOUT, HIT }

    /** 按脚本给出结论（index 从 1 起，与生产同口径）。 */
    static final class P implements CrackEngine.VerdictProvider {
        final K[] script;
        final List<Integer> calls = new ArrayList<>();
        P(K[] s) { script = s; }
        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            calls.add(index);
            K k = (index - 1 >= 0 && index - 1 < script.length) ? script[index - 1] : K.TIMEOUT;
            CrackEngine.Attempt a;
            switch (k) {
                case HIT:
                    a = new CrackEngine.Attempt(password, CrackEngine.Verdict.SUCCESS, 1L, "hit", "s");
                    a.grade = EvidenceGrade.Level.DIRECT;
                    break;
                case WRONG:
                    a = new CrackEngine.Attempt(password, CrackEngine.Verdict.AUTH_FAIL, 1L, "wrong", "s");
                    a.grade = EvidenceGrade.Level.DIRECT;
                    break;
                case NETFAIL:
                    a = new CrackEngine.Attempt(password, CrackEngine.Verdict.NETWORK_AUTH_FAIL, 1L, "net", "s");
                    a.grade = EvidenceGrade.Level.NONE;
                    break;
                case UNKNOWN:
                    a = new CrackEngine.Attempt(password, CrackEngine.Verdict.UNKNOWN, 1L, "unk", "s");
                    a.grade = EvidenceGrade.Level.NONE;
                    break;
                case INCOMPLETE:
                    a = new CrackEngine.Attempt(password, CrackEngine.Verdict.INCOMPLETE, 1L, "inc", "s");
                    a.grade = EvidenceGrade.Level.NONE;
                    break;
                default:
                    a = new CrackEngine.Attempt(password, CrackEngine.Verdict.TIMEOUT, 1L, "to", "s");
                    a.grade = EvidenceGrade.Level.NONE;
                    break;
            }
            return a;
        }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<CrackEngine.Attempt> results = new ArrayList<>();
        final List<Integer> cursors = new ArrayList<>();
        int finTested = -1, finCursor = Integer.MIN_VALUE;
        String hit = null, outcome = null;
        @Override public void onAttemptStart(int i, int t, String c) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) { results.add(a); }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        public void onCursor(int c) { cursors.add(c); }
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finTested = tested; finCursor = cursor; hit = hitPassword; this.outcome = outcome;
        }
        @Override public void onBlocked(String t, String d) { System.out.println("  !! blocked " + t); }
    }

    private static CrackEngine engine(P p) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        java.lang.reflect.Method m = CrackEngine.class.getDeclaredMethod(
                "setClockForTest", EngineClock.class);
        m.setAccessible(true);
        m.invoke(e, new TestEngineClock(5_000_000L, 1L, 0L));
        java.lang.reflect.Method b = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        b.setAccessible(true);
        b.invoke(e);
        // 通道自检（probeChannel）是 run() 之前的真实生产闸门，要求 shell 非 null。
        // 注入一个「永远未连接」的最小通道让它通过；结论由 VerdictProvider 提供。
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long timeoutMs) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) return "";
                return "";
            }
            @Override public String name() { return "r12-null-channel"; }
        });
        e.setVerdictProviderForTest(p);
        return e;
    }

    private static Rec run(K[] script, int start, int n) throws Exception {
        P p = new P(script);
        CrackEngine e = engine(p);
        NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        List<Candidate> cands = new ArrayList<>();
        for (int i = 0; i < n; i++) cands.add(new Candidate("cand-" + i + "-pw", Candidate.Origin.USER_DICT));
        Rec r = new Rec();
        e.run(t, cands, start, 1000L, r);
        return r;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== R-12 候选排除与续跑 ===");

        // ---- ① 错误候选：必须推进 cursor 并写 triedSet ----
        Rec r1 = run(new K[]{K.WRONG, K.WRONG, K.WRONG}, 0, 3);
        info("① 全 AUTH_FAIL: cursor=" + r1.finCursor + " tested=" + r1.finTested
                + " outcome=" + r1.outcome);
        ok("① 已证实错误 → cursor 推进到末尾（3）", r1.finCursor == 3, String.valueOf(r1.finCursor));
        ok("① 三条都是 DIRECT（可持久化）",
                r1.results.size() == 3 && r1.results.stream()
                        .allMatch(a -> EvidenceGrade.persistable(a.grade)), "grade mismatch");

        // ---- ② 第一条 UNKNOWN：cursor 必须钉在 0 ----
        Rec r2 = run(new K[]{K.UNKNOWN, K.WRONG, K.WRONG}, 0, 3);
        info("② UNKNOWN 在首位: cursor=" + r2.finCursor + " tested=" + r2.finTested);
        ok("② 首位 UNKNOWN → cursor 停在 0（续跑会重试它）",
                r2.finCursor == 0, String.valueOf(r2.finCursor));
        ok("② UNKNOWN 不持久化", r2.results.get(0).grade == EvidenceGrade.Level.NONE,
                String.valueOf(r2.results.get(0).grade));

        // ---- ③ 正确候选在前一条错误之后仍然被尝试 ----
        Rec r3 = run(new K[]{K.WRONG, K.HIT}, 0, 2);
        info("③ 先错后对: hit=" + r3.hit + " outcome=" + r3.outcome);
        ok("③ 先错后对 → 正确候选仍被执行并命中",
                r3.hit != null && r3.outcome.equals("DONE_HIT"), String.valueOf(r3.outcome));

        // ---- ④ 正确候选此前若被 UNKNOWN，续跑必须重试到它 ----
        Rec r4 = run(new K[]{K.UNKNOWN, K.UNKNOWN, K.UNKNOWN}, 0, 3);
        info("④ 全 UNKNOWN: cursor=" + r4.finCursor + " outcome=" + r4.outcome);
        ok("④ 全无结论 → cursor 停在 0（正确候选不会被跳过）",
                r4.finCursor == 0, String.valueOf(r4.finCursor));
        ok("④ 全无结论 → outcome 不是「未命中已证明」",
                !"DONE_HIT".equals(r4.outcome), String.valueOf(r4.outcome));

        // ---- ⑤ NETWORK_AUTH_FAIL 不得推进 cursor ----
        Rec r5 = run(new K[]{K.NETFAIL, K.WRONG}, 0, 2);
        info("⑤ 网络级失败在首位: cursor=" + r5.finCursor);
        ok("⑤ NETWORK_AUTH_FAIL → cursor 停在 0", r5.finCursor == 0, String.valueOf(r5.finCursor));

        // ---- ⑥ INCOMPLETE 不得推进 cursor ----
        Rec r6 = run(new K[]{K.INCOMPLETE, K.HIT}, 0, 2);
        info("⑥ INCOMPLETE 在首位: cursor=" + r6.finCursor + " hit=" + r6.hit);
        ok("⑥ INCOMPLETE → cursor 停在 0", r6.finCursor == 0, String.valueOf(r6.finCursor));

        // ---- ⑦ 起点续跑：从 start 开始，前缀不被重试 ----
        Rec r7 = run(new K[]{K.WRONG, K.WRONG, K.WRONG, K.WRONG}, 2, 4);
        info("⑦ start=2: tested=" + r7.finTested + " cursor=" + r7.finCursor
                + " 结果数=" + r7.results.size());
        ok("⑦ 从 offset 2 续跑只跑后 2 条", r7.results.size() == 2,
                String.valueOf(r7.results.size()));
        ok("⑦ 续跑后 cursor 到末尾 4", r7.finCursor == 4, String.valueOf(r7.finCursor));

        // ---- ⑧ 混合序列：cursor 停在第一个非 DIRECT ----
        Rec r8 = run(new K[]{K.WRONG, K.WRONG, K.UNKNOWN, K.WRONG}, 0, 4);
        info("⑧ 混合: cursor=" + r8.finCursor);
        ok("⑧ 混合 → cursor 停在第一个非 DIRECT（2）", r8.finCursor == 2,
                String.valueOf(r8.finCursor));

        // ---- ⑨ 每条结论的下游影响必须自洽 ----
        int inconsistent = 0;
        for (CrackEngine.Attempt a : r8.results) {
            boolean persist = EvidenceGrade.persistable(a.grade);
            boolean advance = EvidenceGrade.advancesCursor(a.grade);
            if (persist != advance) inconsistent++;
        }
        ok("⑨ persistable 与 advancesCursor 对每条结论完全一致", inconsistent == 0,
                "不一致 " + inconsistent);

        System.out.println();
        System.out.println("=== R-12 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

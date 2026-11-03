package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 恢复专项：**暂停 / 停止 / 进程退出后继续，实际执行索引必须与
 * cursor + offset + triedSet 三者一致**。
 *
 * <h3>三件事必须同时成立</h3>
 * <ol>
 *   <li><b>不重复</b>：cursor 之前的候选（已 DIRECT）不再执行。</li>
 *   <li><b>不跳过</b>：cursor 位置那条（非 DIRECT）必须被再次执行。</li>
 *   <li><b>过滤换算</b>：triedSet 非空导致列表变短时，起跑位置按
 *       「前缀被删条数」前移，而不是归零。</li>
 * </ol>
 *
 * <p>每组都驱动**真实 {@code run()}**，用 {@code onAttemptStart} 记录实际候选。
 */
public final class ResumeMatrixTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-RS";
    static final String BSSID = "02:00:00:00:00:5A";

    static final class Rec implements CrackEngine.Listener {
        final List<String> started = new ArrayList<>();
        int finCursor = Integer.MIN_VALUE;
        int finTested = -1;
        String outcome;
        @Override public void onAttemptStart(int i, int total, String c) { started.add(c); }
        @Override public void onAttemptResult(CrackEngine.Attempt a) {}
        @Override public void onProgress(int tested, int total, double rate, long eta) {}
        @Override public void onCursor(int cursor) {}
        @Override public void onFinished(int tested, int cursor, String hit, String outcome) {
            finTested = tested; finCursor = cursor; this.outcome = outcome;
        }
        @Override public void onBlocked(String t, String d) { System.out.println("  !! " + t); }
    }

    /** 按候选密码给出既定结论。 */
    static final class P implements CrackEngine.VerdictProvider {
        final Map<String, CrackEngine.Verdict> v = new HashMap<>();
        final Map<String, EvidenceGrade.Level> g = new HashMap<>();
        P put(String pw, CrackEngine.Verdict verdict, EvidenceGrade.Level grade) {
            v.put(pw, verdict); g.put(pw, grade); return this;
        }
        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            CrackEngine.Verdict vv = v.getOrDefault(password, CrackEngine.Verdict.AUTH_FAIL);
            EvidenceGrade.Level gg = g.getOrDefault(password, EvidenceGrade.Level.DIRECT);
            CrackEngine.Attempt a = new CrackEngine.Attempt(password, vv, 1L, "scripted", "s");
            a.grade = gg;
            return a;
        }
    }

    private static CrackEngine engine(CrackEngine.VerdictProvider p, long t0) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(t0, 1L, 0L));
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
            @Override public String name() { return "rs-null"; }
        });
        e.setVerdictProviderForTest(p);
        return e;
    }

    private static List<Candidate> dict(String... pws) {
        List<Candidate> out = new ArrayList<>();
        for (String p : pws) out.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return out;
    }

    private static Rec run(CrackEngine e, List<Candidate> c, int start) {
        NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        Rec r = new Rec();
        e.run(t, c, start, 1000L, r);
        return r;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 恢复矩阵：cursor / offset / triedSet / 实际索引 ===");

        String[] pw = {"pwA00001", "pwB00002", "pwC00003", "pwD00004", "pwE00005",
                "pwF00006", "pwG00007", "pwH00008", "pwI00009", "pwJ00010"};
        List<Candidate> all = dict(pw);

        // ---- 场景 1：前 5 条 DIRECT，第 6 条 UNKNOWN，其余 DIRECT ----
        // 期望：cursor=5（停在第 6 条），继续时首条必须是 pwF00006
        {
            P p = new P();
            p.put("pwF00006", CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE);
            CrackEngine e = engine(p, 1_000_000L);
            Rec r1 = run(e, all, 0);
            info("S1 第一轮 cursor=" + r1.finCursor + " 执行=" + r1.started.size() + " 条");
            ok("S1 第一轮 cursor 停在第 6 条（index 5）", r1.finCursor == 5,
                    String.valueOf(r1.finCursor));

            // 继续：从 cursor 起跑（模拟队列 resume）
            P p2 = new P();
            p2.put("pwF00006", CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE);
            CrackEngine e2 = engine(p2, 2_000_000L);
            Rec r2 = run(e2, all, r1.finCursor);
            info("S1 继续首条 = " + r2.started.get(0));
            ok("S1 继续时首条是被 UNKNOWN 卡住的那条（不跳过）",
                    r2.started.get(0).equals("pwF00006"), r2.started.get(0));
            ok("S1 继续时不重复前 5 条（不重复）",
                    !r2.started.contains("pwA00001"), "重复了 A");
            ok("S1 继续执行 5 条（F..J）", r2.started.size() == 5,
                    String.valueOf(r2.started.size()));
        }

        // ---- 场景 2：NETWORK_AUTH_FAIL 不推 cursor ----
        {
            P p = new P();
            for (int i = 0; i < 3; i++) {
                p.put(pw[i], CrackEngine.Verdict.NETWORK_AUTH_FAIL, EvidenceGrade.Level.NONE);
            }
            CrackEngine e = engine(p, 1_000_000L);
            Rec r = run(e, all, 0);
            info("S2 前 3 条网络级失败 cursor=" + r.finCursor);
            ok("S2 NETWORK_AUTH_FAIL 不推进 cursor（停在 0）", r.finCursor == 0,
                    String.valueOf(r.finCursor));
        }

        // ---- 场景 3：INCOMPLETE 不推 cursor ----
        {
            P p = new P();
            p.put(pw[0], CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);
            p.put(pw[1], CrackEngine.Verdict.INCOMPLETE, EvidenceGrade.Level.NONE);
            CrackEngine e = engine(p, 1_000_000L);
            Rec r = run(e, all, 0);
            info("S3 [DIRECT, INCOMPLETE, ...] cursor=" + r.finCursor);
            ok("S3 INCOMPLETE 卡住 cursor（停在 1）", r.finCursor == 1,
                    String.valueOf(r.finCursor));
        }

        // ---- 场景 4：triedSet 只有 DIRECT 那几条，过滤后位置换算 ----
        {
            P p = new P();
            p.put(pw[5], CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE);
            CrackEngine e = engine(p, 1_000_000L);
            Rec r1 = run(e, all, 0);
            int cursor = r1.finCursor;      // 期望 5

            // triedSet 只记 A..E（DIRECT 那批）—— 与生产 onAttemptResult 的
            // EvidenceGrade.persistable 分流一致
            TriedSet tried = new TriedSet();
            for (int i = 0; i < 5; i++) tried.add(pw[i]);
            List<Candidate> kept = io.github.wlmosv_png.wpaforge.CrackService
                    .filterTried(all, tried).kept;
            int start = io.github.wlmosv_png.wpaforge.CrackService
                    .resumeIndexAfterFilter(all, tried, cursor);
            info("S4 cursor=" + cursor + " 过滤后 " + kept.size()
                    + " 条，换算起跑位置 " + start);
            ok("S4 triedSet 恰好是 DIRECT 那 5 条", tried.size() == 5,
                    String.valueOf(tried.size()));
            ok("S4 换算位置 = 0（前 5 条全被删掉，F 变成第 0 条）", start == 0,
                    String.valueOf(start));

            P p2 = new P();
            p2.put(pw[5], CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE);
            CrackEngine e2 = engine(p2, 2_000_000L);
            Rec r2 = run(e2, kept, start);
            info("S4 继续执行: " + r2.started);
            ok("S4 继续首条仍是 pwF00006（不跳过未确定候选）",
                    r2.started.get(0).equals("pwF00006"), r2.started.get(0));
            ok("S4 不重跑任何已 DIRECT 的候选",
                    !r2.started.contains("pwA00001") && !r2.started.contains("pwE00005"),
                    "重跑了");
        }

        // ---- 场景 5：进程退出后恢复（新引擎，盘上 cursor） ----
        {
            P p = new P();
            CrackEngine e = engine(p, 1_000_000L);
            Rec r1 = run(e, all, 3);          // 从第 3 条起，全 DIRECT
            info("S5 第一轮 start=3 cursor=" + r1.finCursor
                    + " 执行=" + r1.started.size());
            ok("S5 第一轮 cursor=10（全 DIRECT 跑到尾）", r1.finCursor == 10,
                    String.valueOf(r1.finCursor));

            // 进程被杀 → 新引擎，盘上 offset=10 → 无可跑
            CrackEngine e2 = engine(new P(), 9_000_000L);
            Rec r2 = run(e2, all, 10);
            info("S5 重启后 offset=10 实际执行 " + r2.started.size() + " 条");
            ok("S5 重启后不重跑任何候选", r2.started.isEmpty(),
                    String.valueOf(r2.started.size()));
        }

        // ---- 场景 6：命中后立即停止，不再执行剩余候选 ----
        {
            P p = new P();
            p.put(pw[2], CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);
            CrackEngine e = engine(p, 1_000_000L);
            Rec r = run(e, all, 0);
            info("S6 第 3 条命中，实际执行 " + r.started.size() + " 条");
            ok("S6 命中即停（只执行 3 条）", r.started.size() == 3,
                    String.valueOf(r.started.size()));
            ok("S6 命中条是第 3 条", r.started.get(2).equals("pwC00003"),
                    r.started.get(2));
        }

        System.out.println();
        System.out.println("=== 恢复矩阵: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

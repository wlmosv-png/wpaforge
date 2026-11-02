package io.github.wlmosv_png.wpaforge.audit;






import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * P0 端到端：**停止后继续时，实际被执行的候选索引**。
 *
 * <p>{@link P0ResumeOffsetTest} 只验证纯函数；本测试驱动**真实 {@code run()}**，
 * 记录 {@code onAttemptStart} 收到的候选密码，证明续跑不是从零开始。
 *
 * <h3>三组关键场景</h3>
 * <ol>
 *   <li>跑 5 条 → 停止（cursor 停在 3）→ 继续：应从第 3 条开始，而不是第 0 条。</li>
 *   <li>有 triedSet 命中（例如命中那条已入库）→ 继续：应从换算后的位置开始。</li>
 *   <li>模拟「进程重启」：新建引擎 + 从盘上 offset 恢复 → 位置仍正确。</li>
 * </ol>
 */
public final class P0ResumeEndToEndTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-P0";
    static final String BSSID = "02:00:00:00:00:0P";

    /** 记录每条候选被执行的顺序。 */
    static final class Rec implements CrackEngine.Listener {
        final List<String> started = new ArrayList<>();
        final List<String> verdicts = new ArrayList<>();
        int finCursor = Integer.MIN_VALUE;
        int finTested = -1;
        String outcome = null;
        @Override public void onAttemptStart(int index, int total, String candidate) {
            started.add(candidate);
        }
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            verdicts.add(a.verdict.name() + "/" + a.grade);
        }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onCursor(int cursor) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finTested = tested; finCursor = cursor; this.outcome = outcome;
        }
        @Override public void onBlocked(String t, String d) { System.out.println("  !! blocked " + t); }
    }

    /** 固定结论（按候选字符串决定），模拟不同 Verdict 类型。 */
    static final class Provider implements CrackEngine.VerdictProvider {
        final java.util.Map<String, CrackEngine.Verdict> byPw;
        final EvidenceGrade.Level grade;
        Provider(java.util.Map<String, CrackEngine.Verdict> m, EvidenceGrade.Level g) {
            byPw = m; grade = g;
        }
        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            CrackEngine.Verdict v = byPw.getOrDefault(password, CrackEngine.Verdict.AUTH_FAIL);
            CrackEngine.Attempt a = new CrackEngine.Attempt(password, v, 1L, "scripted", "s");
            a.grade = grade;
            return a;
        }
    }

    private static CrackEngine engine(CrackEngine.VerdictProvider p, long startMs) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(startMs, 1L, 0L));
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
            @Override public String name() { return "p0-null"; }
        });
        e.setVerdictProviderForTest(p);
        return e;
    }

    private static List<Candidate> dict(String... pws) {
        List<Candidate> out = new ArrayList<>();
        for (String p : pws) out.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return out;
    }

    private static Rec run(CrackEngine e, List<Candidate> cands, int startOffset) {
        NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        Rec r = new Rec();
        e.run(t, cands, startOffset, 1000L, r);
        return r;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== P0 端到端：实际执行的候选索引 ===");

        List<Candidate> all = dict("pwA00001", "pwB00002", "pwC00003", "pwD00004",
                "pwE00005", "pwF00006", "pwG00007", "pwH00008");

        // ---- ① 无过滤、起始 offset=5：只有后 3 条被执行 ----
        {
            CrackEngine e = engine(new Provider(new java.util.HashMap<>(), EvidenceGrade.Level.DIRECT),
                    1_000_000L);
            Rec r = run(e, all, 5);
            info("① start=5 实际执行: " + r.started);
            ok("① 从第 5 条起只跑 3 条", r.started.size() == 3, String.valueOf(r.started.size()));
            ok("① 第一条是 pwF00006（index 5）", r.started.get(0).equals("pwF00006"),
                    r.started.get(0));
            ok("① 绝不含 pwA00001（证明不是从零开始）",
                    !r.started.contains("pwA00001"), "含 A");
        }

        // ---- ② 有过滤（triedSet 含前 2 条）：等效位置 = 5-2 = 3 ----
        {
            TriedSet tried = new TriedSet();
            tried.add("pwA00001");
            tried.add("pwB00002");
            List<Candidate> kept = io.github.wlmosv_png.wpaforge.CrackService.filterTried(all, tried).kept;
            int start = io.github.wlmosv_png.wpaforge.CrackService.resumeIndexAfterFilter(all, tried, 5);
            info("② 过滤前 " + all.size() + " 条 -> 过滤后 " + kept.size()
                    + " 条，等效起跑位置 " + start);
            ok("② 等效位置 = 3（5 减去前缀 2 条）", start == 3, String.valueOf(start));

            CrackEngine e = engine(new Provider(new java.util.HashMap<>(), EvidenceGrade.Level.DIRECT),
                    1_000_000L);
            Rec r = run(e, kept, start);
            info("② 实际执行: " + r.started);
            ok("② 首条是 pwF00006（对应原 index 5）",
                    r.started.get(0).equals("pwF00006"), r.started.get(0));
            ok("② 不重跑 pwA00001 / pwB00002",
                    !r.started.contains("pwA00001") && !r.started.contains("pwB00002"), "重跑了");
            ok("② 执行条数 = 3", r.started.size() == 3, String.valueOf(r.started.size()));
        }

        // ---- ③ 过滤条目全在 offset 之后：位置不变（5）----
        {
            TriedSet tried = new TriedSet();
            tried.add("pwG00007");
            tried.add("pwH00008");
            List<Candidate> kept = io.github.wlmosv_png.wpaforge.CrackService.filterTried(all, tried).kept;
            int start = io.github.wlmosv_png.wpaforge.CrackService.resumeIndexAfterFilter(all, tried, 5);
            info("③ 过滤后 " + kept.size() + " 条，起跑位置 " + start);
            ok("③ 位置保持 5", start == 5, String.valueOf(start));

            CrackEngine e = engine(new Provider(new java.util.HashMap<>(), EvidenceGrade.Level.DIRECT),
                    1_000_000L);
            Rec r = run(e, kept, start);
            info("③ 实际执行: " + r.started);
            ok("③ 首条是 pwF00006", r.started.get(0).equals("pwF00006"), r.started.get(0));
        }

        // ---- ④ 模拟进程重启：新引擎 + 盘上 offset ----
        {
            int savedOffset = 5;
            TriedSet tried = new TriedSet();
            tried.add("pwA00001");
            List<Candidate> kept = io.github.wlmosv_png.wpaforge.CrackService.filterTried(all, tried).kept;
            int start = io.github.wlmosv_png.wpaforge.CrackService.resumeIndexAfterFilter(all, tried, savedOffset);
            // 全新引擎实例（模拟进程重启）
            CrackEngine e2 = engine(new Provider(new java.util.HashMap<>(), EvidenceGrade.Level.DIRECT),
                    9_000_000L);
            Rec r = run(e2, kept, start);
            info("④ 重启后 start=" + start + " 执行: " + r.started);
            ok("④ 重启后首条仍是 pwF00006", r.started.get(0).equals("pwF00006"),
                    r.started.get(0));
            ok("④ 重启后不从头开始", !r.started.contains("pwA00001"), "从头开始了");
        }

        // ---- ⑤ 不同 Verdict 混合：位置语义不受结论种类影响 ----
        {
            java.util.Map<String, CrackEngine.Verdict> m = new java.util.HashMap<>();
            m.put("pwF00006", CrackEngine.Verdict.NETWORK_AUTH_FAIL);
            m.put("pwG00007", CrackEngine.Verdict.UNKNOWN);
            Provider p = new Provider(m, EvidenceGrade.Level.NONE);
            CrackEngine e = engine(p, 1_000_000L);
            Rec r = run(e, all, 5);
            info("⑤ 混合结论 start=5 执行: " + r.started + " verdict=" + r.verdicts);
            ok("⑤ 混合结论下起跑位置不变（仍是 F 开头）",
                    r.started.get(0).equals("pwF00006"), r.started.get(0));
            ok("⑤ 三条都执行了（非 DIRECT 不中断循环）", r.started.size() == 3,
                    String.valueOf(r.started.size()));
        }

        System.out.println();
        System.out.println("=== P0 端到端: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

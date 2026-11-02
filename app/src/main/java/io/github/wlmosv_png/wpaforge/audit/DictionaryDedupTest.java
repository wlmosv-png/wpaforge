package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨字典去重：**真实驱动生产过滤入口 + 真实 run()**。
 *
 * <h3>防的真实错误</h3>
 * 换字典时重叠候选被整段重跑（用户实报）；或者反过来 —— 为了省时间
 * 把「未确定」候选也跳掉，导致正确密码被推后甚至漏掉。
 *
 * <h3>本测试的两条主线</h3>
 * <ol>
 *   <li><b>确实减少重复</b>：第二本与第一本重叠的部分不再执行。</li>
 *   <li><b>不牺牲正确性</b>：默认档绝不跳过未确定候选；强制重跑能绕过过滤。</li>
 * </ol>
 */
public final class DictionaryDedupTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-DEDUP";
    static final String BSSID = "02:00:00:00:00:DD";

    static final class Rec implements CrackEngine.Listener {
        final List<String> started = new ArrayList<>();
        int finCursor = Integer.MIN_VALUE;
        String outcome;
        @Override public void onAttemptStart(int i, int t, String c) { started.add(c); }
        @Override public void onAttemptResult(CrackEngine.Attempt a) {}
        @Override public void onProgress(int t, int total, double r, long eta) {}
        @Override public void onCursor(int c) {}
        @Override public void onFinished(int t, int c, String hit, String o) {
            finCursor = c; outcome = o;
        }
        @Override public void onBlocked(String t, String d) { System.out.println("  !! " + t); }
    }

    /** 按候选给结论；未列出的默认 AUTH_FAIL/DIRECT。 */
    static final class P implements CrackEngine.VerdictProvider {
        final Map<String, CrackEngine.Verdict> v = new HashMap<>();
        final Map<String, EvidenceGrade.Level> g = new HashMap<>();
        P put(String pw, CrackEngine.Verdict verdict, EvidenceGrade.Level grade) {
            v.put(pw, verdict); g.put(pw, grade); return this;
        }
        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                    v.getOrDefault(password, CrackEngine.Verdict.AUTH_FAIL), 1L, "s", "s");
            a.grade = g.getOrDefault(password, EvidenceGrade.Level.DIRECT);
            return a;
        }
    }

    private static CrackEngine engine(CrackEngine.VerdictProvider p) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(1_000_000L, 1L, 0L));
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
            @Override public String name() { return "dedup-null"; }
        });
        e.setVerdictProviderForTest(p);
        return e;
    }

    private static List<Candidate> dict(String... pws) {
        List<Candidate> out = new ArrayList<>();
        for (String p : pws) out.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return out;
    }

    private static Rec run(List<Candidate> c, int start, CrackEngine.VerdictProvider p)
            throws Exception {
        CrackEngine e = engine(p);
        NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
        Rec r = new Rec();
        e.run(t, c, start, 1000L, r);
        return r;
    }

    /** 字典 A：8 条；字典 B：与 A 重叠 **4** 条（echo/foxtrot/golf/hotel）+ 3 条新的。 */
    static final String[] A = {"alpha111", "bravo222", "charlie3", "delta444",
            "echo5555", "foxtrot6", "golf7777", "hotel888"};
    static final String[] B = {"echo5555", "foxtrot6", "golf7777", "hotel888",
            "india999", "juliet10", "kilo1111"};

    public static void main(String[] args) throws Exception {
        System.out.println("=== 跨字典去重（生产过滤入口 + 真实 run）===");

        // ---- ① 第一本跑完，A 的 8 条全部进账本（CONFIRMED_WRONG）----
        AttemptLedger ledger = new AttemptLedger();
        {
            List<Candidate> a = dict(A);
            Rec r1 = run(a, 0, new P());      // 默认 AUTH_FAIL/DIRECT
            info("① 第一本执行 " + r1.started.size() + " 条，outcome=" + r1.outcome);
            ok("① 第一本 8 条全执行", r1.started.size() == 8, String.valueOf(r1.started.size()));

            // 模拟生产写入：persistable(DIRECT) → triedSet；同时记账本
            TriedSet tried = new TriedSet();
            for (String pw : A) {
                tried.add(pw);
                ledger.record(pw, AttemptLedger.Level.CONFIRMED_WRONG);
            }
            ok("① 账本记下 8 条已确定错误",
                    ledger.count(AttemptLedger.Level.CONFIRMED_WRONG) == 8,
                    String.valueOf(ledger.count(AttemptLedger.Level.CONFIRMED_WRONG)));

            // ---- ② 换第二本：重叠的 5 条必须被跳过 ----
            io.github.wlmosv_png.wpaforge.CrackService.FilterOutcome fo =
                    io.github.wlmosv_png.wpaforge.CrackService.filterCandidates(
                            dict(B), tried, ledger, AttemptLedger.SkipPolicy.CONSERVATIVE);
            info("② 第二本过滤：保留 " + fo.kept.size() + " 条，跳过 " + fo.totalSkipped()
                    + "（已确定 " + fo.skippedConfirmed + "）");
            ok("② 重叠 4 条被跳过", fo.skippedConfirmed == 4,
                    String.valueOf(fo.skippedConfirmed));
            ok("② 只剩 3 条新候选", fo.kept.size() == 3, String.valueOf(fo.kept.size()));

            Rec r2 = run(fo.kept, 0, new P());
            info("② 第二本实际执行: " + r2.started);
            ok("② 第二本不再跑重叠候选",
                    !r2.started.contains("echo5555") && !r2.started.contains("hotel888"),
                    "重跑了");
            ok("② 第二本跑了 3 条新候选", r2.started.size() == 3,
                    String.valueOf(r2.started.size()));
        }

        // ---- ③ 两本完全相同 → 第二本一条都不跑 ----
        {
            TriedSet tried = new TriedSet();
            AttemptLedger l2 = new AttemptLedger();
            for (String pw : A) {
                tried.add(pw);
                l2.record(pw, AttemptLedger.Level.CONFIRMED_WRONG);
            }
            io.github.wlmosv_png.wpaforge.CrackService.FilterOutcome fo =
                    io.github.wlmosv_png.wpaforge.CrackService.filterCandidates(
                            dict(A), tried, l2, AttemptLedger.SkipPolicy.CONSERVATIVE);
            ok("③ 完全相同的字典 → 零保留", fo.kept.isEmpty(), String.valueOf(fo.kept.size()));
            ok("③ 跳过 8 条", fo.totalSkipped() == 8, String.valueOf(fo.totalSkipped()));
        }

        // ---- ④ 关键正确性：**未确定**候选在默认档绝不跳过 ----
        {
            AttemptLedger l3 = new AttemptLedger();
            for (String pw : A) l3.record(pw, AttemptLedger.Level.NETWORK_TERMINATED);
            io.github.wlmosv_png.wpaforge.CrackService.FilterOutcome fo =
                    io.github.wlmosv_png.wpaforge.CrackService.filterCandidates(
                            dict(A), new TriedSet(), l3,
                            AttemptLedger.SkipPolicy.CONSERVATIVE);
            info("④ 全未确定 + 保守档 → 保留 " + fo.kept.size() + " 条");
            ok("④ 保守档不跳过未确定候选（正确性底线）",
                    fo.kept.size() == 8 && fo.totalSkipped() == 0,
                    "跳过 " + fo.totalSkipped());

            // 省时档才跳
            io.github.wlmosv_png.wpaforge.CrackService.FilterOutcome fo2 =
                    io.github.wlmosv_png.wpaforge.CrackService.filterCandidates(
                            dict(A), new TriedSet(), l3, AttemptLedger.SkipPolicy.SAVE_TIME);
            info("④ 省时档 → 保留 " + fo2.kept.size() + " 条，其中未确定跳过 "
                    + fo2.skippedUndetermined);
            ok("④ 省时档跳过未确定", fo2.skippedUndetermined == 8,
                    String.valueOf(fo2.skippedUndetermined));
            ok("④ 省时档也如实报告未确定条数（供 UI 提示风险）",
                    fo2.skippedUndetermined > 0, "未统计");
        }

        // ---- ⑤ 强制重跑：绕过过滤，但不改历史 ----
        {
            AttemptLedger l5 = new AttemptLedger();
            for (String pw : A) l5.record(pw, AttemptLedger.Level.CONFIRMED_WRONG);
            io.github.wlmosv_png.wpaforge.CrackService.FilterOutcome fo =
                    io.github.wlmosv_png.wpaforge.CrackService.filterCandidates(
                            dict(A), new TriedSet(), l5, AttemptLedger.SkipPolicy.CONSERVATIVE,
                            true /* forceRerun */);
            ok("⑤ 强制重跑 → 一条都不跳", fo.kept.size() == 8 && fo.totalSkipped() == 0,
                    "跳了 " + fo.totalSkipped());
            ok("⑤ 强制重跑不删除原始账本",
                    l5.count(AttemptLedger.Level.CONFIRMED_WRONG) == 8,
                    String.valueOf(l5.count(AttemptLedger.Level.CONFIRMED_WRONG)));
        }

        // ---- ⑥ 位置换算：跳过的候选在 offset 之前时要前移 ----
        {
            AttemptLedger l6 = new AttemptLedger();
            l6.record(A[0], AttemptLedger.Level.CONFIRMED_WRONG);
            l6.record(A[1], AttemptLedger.Level.CONFIRMED_WRONG);
            int idx = io.github.wlmosv_png.wpaforge.CrackService.resumeIndexAfterFilter(
                    dict(A), new TriedSet(), l6, AttemptLedger.SkipPolicy.CONSERVATIVE, 5);
            info("⑥ 原列表前 5 条里有 2 条被跳过 → 等效位置 " + idx);
            ok("⑥ 位置前移被跳过条数（不是归零）", idx == 3, String.valueOf(idx));
        }

        // ---- ⑦ 三本连续：第三本只跑前两本都没跑过的 ----
        {
            TriedSet tried = new TriedSet();
            AttemptLedger l7 = new AttemptLedger();
            String[] C = {"india999", "juliet10", "kilo1111", "lima1212"};

            for (String pw : A) { tried.add(pw); l7.record(pw, AttemptLedger.Level.CONFIRMED_WRONG); }
            // A 的 3 条新候选再跑一遍（B 去掉重叠后的部分）
            for (String pw : new String[]{"india999", "juliet10", "kilo1111"}) {
                tried.add(pw); l7.record(pw, AttemptLedger.Level.CONFIRMED_WRONG);
            }
            io.github.wlmosv_png.wpaforge.CrackService.FilterOutcome fo =
                    io.github.wlmosv_png.wpaforge.CrackService.filterCandidates(
                            dict(C), tried, l7, AttemptLedger.SkipPolicy.CONSERVATIVE);
            info("⑦ 第三本：保留 " + fo.kept.size() + " 条（期望 1）");
            ok("⑦ 三本连续后只剩真正没跑过的 1 条",
                    fo.kept.size() == 1 && fo.kept.get(0).password.equals("lima1212"),
                    String.valueOf(fo.kept.size()));
        }

        // ---- ⑧ 不同目标不共享账本（账本本身按 targetKey 分开，这里验哈希口径）----
        {
            String k1 = TriedSet.targetKey("AA:BB:CC:DD:EE:01", "netA");
            String k2 = TriedSet.targetKey("AA:BB:CC:DD:EE:02", "netA");
            ok("⑧ 同 SSID 不同 AP → 不同 targetKey", !k1.equals(k2), k1 + " vs " + k2);
            ok("⑧ 不同 AP → 不同账本文件名",
                    !AttemptLedger.fileNameOf(k1).equals(AttemptLedger.fileNameOf(k2)), "同名");
            ok("⑧ 账本与 triedSet 后缀分离（同名目标两套账各存各的）",
                    AttemptLedger.fileNameOf(k1).endsWith(".ledger")
                            && AttemptLedger.fileNameOf(k1).replace(".ledger", ".tried")
                                    .equals(TriedSet.targetKey("AA:BB:CC:DD:EE:01", "netA") + ".tried"),
                    "口径不一致");
        }

        System.out.println();
        System.out.println("=== 跨字典去重: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

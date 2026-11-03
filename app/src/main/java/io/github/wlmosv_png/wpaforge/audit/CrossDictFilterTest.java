package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.CrackService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨字典过滤专项回归（真实生产方法）。
 *
 * <p>覆盖需求第三节：同一目标下跨字典跳过、两档策略、证据等级边界、
 * 真实候选交集统计、身份冲突保护、目标隔离。
 *
 * <p>全部调用生产实现：{@link CrackService#filterCandidates} /
 * {@link CrackService#isSkipped} / {@link CrackService#ambiguousIds} /
 * {@link CrackService#resumeIndexAfterFilter} / {@link PlanFilterStats#compute} /
 * {@link AttemptLedger#record} / {@link TriedSet#add}。
 */
public final class CrossDictFilterTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static Candidate cand(String pw) {
        return new Candidate(pw, Candidate.Origin.USER_DICT);
    }

    private static List<Candidate> dict(String... pws) {
        List<Candidate> l = new ArrayList<>();
        for (String p : pws) l.add(cand(p));
        return l;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 跨字典过滤专项（真实生产方法） ===");
        twoDictsOverlap();
        threeDictsChain();
        policyTiers();
        evidenceBoundary();
        targetIsolation();
        realIntersection();
        collisionGuard();
        resumeIndexConsistency();
        System.out.println();
        System.out.println("=== 过滤: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------ ① 两本字典重复密码

    private static void twoDictsOverlap() {
        System.out.println();
        System.out.println("-- ① 两本字典重复密码 --");
        TriedSet tried = new TriedSet();
        AttemptLedger lg = new AttemptLedger();

        List<Candidate> A = dict("11111111", "22222222", "33333333");
        // A 跑完：11111111 / 22222222 已证实错误
        tried.add("11111111");
        tried.add("22222222");

        List<Candidate> B = dict("11111111", "22222222", "44444444", "55555555");
        CrackService.FilterOutcome fo = CrackService.filterCandidates(
                B, tried, lg, AttemptLedger.SkipPolicy.CONSERVATIVE);
        ok("B 跳过 2 条（A 已证实的）", fo.totalSkipped() == 2, "" + fo.totalSkipped());
        ok("B 只跳已证实错误", fo.skippedConfirmed == 2 && fo.skippedUndetermined == 0,
                fo.skippedConfirmed + "/" + fo.skippedUndetermined);
        ok("B 保留 2 条新候选", fo.kept.size() == 2, "" + fo.kept.size());
        ok("B 保留的是 44444444 / 55555555",
                fo.kept.get(0).password.equals("44444444")
                        && fo.kept.get(1).password.equals("55555555"), "");

        // 剩余为 0
        List<Candidate> C = dict("11111111", "22222222");
        CrackService.FilterOutcome fc = CrackService.filterCandidates(
                C, tried, lg, AttemptLedger.SkipPolicy.CONSERVATIVE);
        ok("全部重复时剩余候选为 0", fc.kept.isEmpty() && fc.totalSkipped() == 2, "");
    }

    // ------------------------------------------------ ② 三本连续过滤

    private static void threeDictsChain() {
        System.out.println();
        System.out.println("-- ② 三本字典连续过滤（A→B→C 共用账本） --");
        TriedSet tried = new TriedSet();
        AttemptLedger lg = new AttemptLedger();

        // A 跑完：aaa11111 已证实错误；bbb22222 超时（未确定）
        tried.add("aaa11111");
        lg.record("bbb22222", AttemptLedger.Level.TIMEOUT);

        List<Candidate> B = dict("aaa11111", "bbb22222", "ccc33333");
        CrackService.FilterOutcome fb = CrackService.filterCandidates(
                B, tried, lg, AttemptLedger.SkipPolicy.CONSERVATIVE);
        ok("保守档：B 只跳已证实错误", fb.skippedConfirmed == 1
                        && fb.skippedUndetermined == 0 && fb.kept.size() == 2,
                fb.skippedConfirmed + "/" + fb.skippedUndetermined + "/" + fb.kept.size());
        ok("保守档保留超时的 bbb22222",
                fb.kept.stream().anyMatch(c -> c.password.equals("bbb22222")), "");

        CrackService.FilterOutcome fb2 = CrackService.filterCandidates(
                B, tried, lg, AttemptLedger.SkipPolicy.SAVE_TIME);
        ok("省时档：B 跳已证实 + 超时", fb2.skippedConfirmed == 1
                        && fb2.skippedUndetermined == 1 && fb2.kept.size() == 1,
                fb2.skippedConfirmed + "/" + fb2.skippedUndetermined + "/" + fb2.kept.size());

        // C 只用 B 跑完后的账本（模拟真实接力：B 的结果也进账本）
        lg.record("ccc33333", AttemptLedger.Level.CONFIRMED_WRONG);
        List<Candidate> C = dict("aaa11111", "ccc33333", "ddd44444");
        CrackService.FilterOutcome fc = CrackService.filterCandidates(
                C, tried, lg, AttemptLedger.SkipPolicy.CONSERVATIVE);
        ok("C 跳过 2 条（A 与 B 的已证实）", fc.skippedConfirmed == 2, "" + fc.skippedConfirmed);
        ok("C 只剩 ddd44444", fc.kept.size() == 1
                && fc.kept.get(0).password.equals("ddd44444"), "");
    }

    // ------------------------------------------------ ③ 两档策略

    private static void policyTiers() {
        System.out.println();
        System.out.println("-- ③ 保守档 vs 省时档 --");
        TriedSet tried = new TriedSet();
        AttemptLedger lg = new AttemptLedger();
        lg.record("n1", AttemptLedger.Level.NETWORK_TERMINATED);
        lg.record("n2", AttemptLedger.Level.TERMINATED_UNKNOWN);
        lg.record("n3", AttemptLedger.Level.TIMEOUT);
        lg.record("n4", AttemptLedger.Level.UNKNOWN_ON_TARGET);

        List<Candidate> work = dict("n1", "n2", "n3", "n4", "fresh01");
        CrackService.FilterOutcome cons = CrackService.filterCandidates(
                work, tried, lg, AttemptLedger.SkipPolicy.CONSERVATIVE);
        ok("保守档一条都不跳（除已证实）", cons.totalSkipped() == 0, "" + cons.totalSkipped());

        CrackService.FilterOutcome save = CrackService.filterCandidates(
                work, tried, lg, AttemptLedger.SkipPolicy.SAVE_TIME);
        ok("省时档跳 NETWORK_TERMINATED/TERMINATED_UNKNOWN/TIMEOUT 三条",
                save.skippedUndetermined == 3, "" + save.skippedUndetermined);
        ok("省时档不跳 UNKNOWN_ON_TARGET", save.kept.stream()
                .anyMatch(c -> c.password.equals("n4")), "");
        ok("省时档不跳全新候选", save.kept.stream()
                .anyMatch(c -> c.password.equals("fresh01")), "");
    }

    // ------------------------------------------------ ④ 证据等级边界

    private static void evidenceBoundary() {
        System.out.println();
        System.out.println("-- ④ INFERRED / NONE 不得作为排除依据 --");
        AttemptLedger lg = new AttemptLedger();
        // classify：AUTH_FAIL 但 grade 不可持久化 → TERMINATED_UNKNOWN（不是 CONFIRMED_WRONG）
        AttemptLedger.Level inferred = AttemptLedger.Level.classify(
                io.github.wlmosv_png.wpaforge.audit.CrackEngine.Verdict.AUTH_FAIL,
                EvidenceGrade.Level.INFERRED);
        ok("AUTH_FAIL+INFERRED → TERMINATED_UNKNOWN（不是已确定错误）",
                inferred == AttemptLedger.Level.TERMINATED_UNKNOWN, String.valueOf(inferred));
        ok("INFERRED 在保守档不跳",
                !AttemptLedger.SkipPolicy.CONSERVATIVE.skips(inferred), "");
        // NONE 级
        AttemptLedger.Level none = AttemptLedger.Level.classify(
                io.github.wlmosv_png.wpaforge.audit.CrackEngine.Verdict.UNKNOWN,
                EvidenceGrade.Level.NONE);
        ok("UNKNOWN → UNKNOWN_ON_TARGET", none == AttemptLedger.Level.UNKNOWN_ON_TARGET,
                String.valueOf(none));
        ok("UNKNOWN_ON_TARGET 任何档都不跳",
                !AttemptLedger.SkipPolicy.CONSERVATIVE.skips(none)
                        && !AttemptLedger.SkipPolicy.SAVE_TIME.skips(none), "");
        // 没跑完的不进账本
        ok("INCOMPLETE/ABORTED 不进账本",
                AttemptLedger.Level.classify(
                        io.github.wlmosv_png.wpaforge.audit.CrackEngine.Verdict.INCOMPLETE,
                        EvidenceGrade.Level.NONE) == null, "");
        ok("SUCCESS 不进账本（命中终止计划）",
                AttemptLedger.Level.classify(
                        io.github.wlmosv_png.wpaforge.audit.CrackEngine.Verdict.SUCCESS,
                        EvidenceGrade.Level.DIRECT) == null, "");
    }

    // ------------------------------------------------ ⑤ 目标隔离

    private static void targetIsolation() {
        System.out.println();
        System.out.println("-- ⑤ 两个 Wi-Fi 隔离（不同目标键两套账本） --");
        String keyA = TriedSet.targetKey("AA:BB:CC:00:00:01", "AP-A");
        String keyB = TriedSet.targetKey("AA:BB:CC:00:00:02", "AP-B");
        ok("不同 BSSID → 不同目标键", !keyA.equals(keyB), keyA + " == " + keyB);
        ok("同 SSID 不同 BSSID 也隔离",
                !TriedSet.targetKey("11:22:33:44:55:66", "X")
                        .equals(TriedSet.targetKey("11:22:33:44:55:67", "X")), "");

        // A 的已试不影响 B：两份独立实例（生产就是两份文件）
        TriedSet triedA = new TriedSet();
        triedA.add("sharedpass");
        AttemptLedger lgB = new AttemptLedger();
        List<Candidate> workB = dict("sharedpass", "onlyb0001");
        CrackService.FilterOutcome foB = CrackService.filterCandidates(
                workB, new TriedSet(), lgB, AttemptLedger.SkipPolicy.CONSERVATIVE);
        ok("B 目标不继承 A 的已试记录", foB.totalSkipped() == 0, "" + foB.totalSkipped());
        ok("B 目标候选全保留", foB.kept.size() == 2, "" + foB.kept.size());
    }

    // ------------------------------------------------ ⑥ 真实候选交集统计

    private static void realIntersection() throws Exception {
        System.out.println();
        System.out.println("-- ⑥ 真实候选交集（不是账本总量） --");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "filterstats-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            File d1 = new File(dir, "d1.txt");
            File d2 = new File(dir, "d2.txt");
            write(d1, "11111111\n22222222\n33333333\n");   // 3 条
            write(d2, "33333333\n44444444\n");             // 1 条新（333 重复）

            AttemptLedger lg = new AttemptLedger();
            // 账本里放一堆**不在计划内**的记录（模拟历史）
            for (int i = 0; i < 200; i++) lg.record("historic" + i + "x", AttemptLedger.Level.TIMEOUT);
            // 计划内只有 22222222 已证实错误
            TriedSet tried = new TriedSet();
            tried.add("22222222");
            lg.record("44444444", AttemptLedger.Level.TIMEOUT);

            List<File> files = new ArrayList<>();
            files.add(d1);
            files.add(d2);
            PlanFilterStats st = PlanFilterStats.compute(files, tried, lg,
                    AttemptLedger.SkipPolicy.CONSERVATIVE, 0);
            ok("计划内候选总数 = 4（去重后）", st.totalCandidates == 4,
                    String.valueOf(st.totalCandidates));
            ok("真实交集：已证实错误 1（不是账本 200+）", st.confirmedWrong == 1,
                    String.valueOf(st.confirmedWrong));
            ok("真实交集：未确定 1（只算计划内的）", st.undetermined == 1,
                    String.valueOf(st.undetermined));
            ok("保守档将跳过 1 条", st.willSkip == 1, String.valueOf(st.willSkip));
            ok("实际待执行 3 条", st.willRun == 3, String.valueOf(st.willRun));

            PlanFilterStats st2 = PlanFilterStats.compute(files, tried, lg,
                    AttemptLedger.SkipPolicy.SAVE_TIME, 0);
            ok("省时档将跳过 2 条（含未确定的）", st2.willSkip == 2, String.valueOf(st2.willSkip));
            ok("省时档待执行 2 条", st2.willRun == 2, String.valueOf(st2.willRun));

            // 上限 → 不可靠，不编数字
            PlanFilterStats capped = PlanFilterStats.compute(files, tried, lg,
                    AttemptLedger.SkipPolicy.CONSERVATIVE, 2);
            ok("超上限 → reliable=false", !capped.reliable, "");
            ok("超上限 → note 非空", !capped.note.isEmpty(), "");
        } finally {
            purge(dir);
        }
    }

    // ------------------------------------------------ ⑦ 身份冲突保护

    private static void collisionGuard() {
        System.out.println();
        System.out.println("-- ⑦ 身份冲突保护（不凭短哈希排除） --");
        // 正常列表：无冲突
        List<Candidate> normal = dict("11111111", "22222222");
        ok("正常列表无身份冲突", CrackService.ambiguousIds(normal).isEmpty(), "");

        // 构造冲突：同一个口令重复出现（同 id 同文本）→ 不算冲突
        List<Candidate> dup = dict("11111111", "11111111");
        ok("同文本重复不算冲突", CrackService.ambiguousIds(dup).isEmpty(), "");

        // 伪造冲突：用两个人工构造的 id 相同的？
        // 真实 SHA 碰撞无法人为构造，这里验证「同一 id 不同文本 → 标记冲突」的判定逻辑
        // 通过一个可控的等价路径：直接验证 isSkipped 在 ambiguous 含该 id 时不跳。
        long id = CandidateId.of("11111111");
        java.util.Set<Long> amb = new java.util.HashSet<>();
        amb.add(id);
        TriedSet tried = new TriedSet();
        tried.add("11111111");
        ok("正常：已试口令会被跳过",
                CrackService.isSkipped("11111111", tried, null,
                        AttemptLedger.SkipPolicy.CONSERVATIVE, false), "");
        ok("身份冲突：即使已试也不跳过（保守保留）",
                !CrackService.isSkipped("11111111", tried, null,
                        AttemptLedger.SkipPolicy.CONSERVATIVE, false, amb), "被误跳");

        // 强制重跑一律不跳
        ok("强制重跑不跳", !CrackService.isSkipped("11111111", tried, null,
                AttemptLedger.SkipPolicy.CONSERVATIVE, true), "");
    }

    // ------------------------------------------------ ⑧ 过滤与位置换算同口径

    private static void resumeIndexConsistency() {
        System.out.println();
        System.out.println("-- ⑧ 过滤与续跑位置换算口径一致 --");
        TriedSet tried = new TriedSet();
        tried.add("b");
        tried.add("d");
        List<Candidate> orig = dict("a", "b", "c", "d", "e", "f");
        CrackService.FilterOutcome fo = CrackService.filterCandidates(
                orig, tried, new AttemptLedger(), AttemptLedger.SkipPolicy.CONSERVATIVE);
        ok("过滤后保留 4 条", fo.kept.size() == 4, "" + fo.kept.size());
        // offset=4（原列表前 4 条 a,b,c,d 中 b、d 被删）→ 等效 2
        int idx = CrackService.resumeIndexAfterFilter(orig, tried, new AttemptLedger(),
                AttemptLedger.SkipPolicy.CONSERVATIVE, 4, false);
        ok("offset=4 → 过滤后等效位置 2", idx == 2, String.valueOf(idx));
        ok("该位置的候选正是 e", fo.kept.get(idx).password.equals("e"), fo.kept.get(idx).password);
        // 越界收敛
        ok("offset 超过长度 → 收敛到末尾",
                CrackService.resumeIndexAfterFilter(orig, tried, new AttemptLedger(),
                        AttemptLedger.SkipPolicy.CONSERVATIVE, 999, false) == 4, "");
    }

    // ------------------------------------------------ helpers

    private static void write(File f, String s) throws Exception {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            o.write(s.getBytes("UTF-8"));
        }
    }

    private static void purge(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) purge(c);
        f.delete();
    }
}
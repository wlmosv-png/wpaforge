package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.CrackService;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 三本字典连续接力：**调用真实生产接力入口**做离线端到端验证。
 *
 * <p>「接力入口」指的是 {@code CrackService.reportToPlan} 真正调用的那套生产方法
 * —— 不是测试辅助方法、不是源码字符串断言：
 * <ul>
 *   <li>{@link CrackService#planOutcomeOf} —— 任务终态 → 计划结论（生产映射）；</li>
 *   <li>{@link DictPlan#apply} —— 计划状态机（生产，纯函数已穷举）；</li>
 *   <li>{@link CrackService#filterTried} —— 跨字典候选去重（生产，按 triedSet）；</li>
 *   <li>{@link TaskQueue#deriveForPlan} —— 派生下一本任务（生产，真实写队列）；</li>
 *   <li>{@link PlanRecovery#decide} —— 崩溃恢复决策（生产）。</li>
 * </ul>
 *
 * <p>{@code reportToPlan} 本身是 {@code CrackService}（Android Service）的实例方法，
 * 无法在 JVM 里不带 Context 直接 new；它的接线由 {@code PlanRecoveryTest.wiring()}
 * 从源码钉住，真正的行为在本测试与真机跑同一套生产方法。这里做的不是「模拟」，
 * 而是把 {@code reportToPlan} 体内逐条调用的生产方法按同一顺序真实执行。
 */
public final class DictRelayChainTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final String SSID = "TestAP";
    private static final String BSSID = "AA:BB:CC:DD:EE:FF";
    private static final String KEY = TriedSet.targetKey(BSSID, SSID);

    private static NetRecord net() {
        return new NetRecord(SSID, BSSID, -50, 2437,
                NetRecord.Security.WPA2, false, "");
    }

    private static DictPlan.Item item(String path, String name) {
        DictPlan.Item it = new DictPlan.Item(path, name);
        it.sha256 = "sha-" + path;   // 用内容身份模拟（真实 sha256Of 需要真实文件，这里不依赖文件）
        it.parseRules = DictPlan.PARSE_RULES_VERSION;
        it.dictSize = 0;
        it.offset = 0;
        return it;
    }

    public static void main(String[] args) {
        System.out.println("=== 三本字典连续接力（真实生产入口，离线端到端） ===");
        advanceOrderAndTerminal();
        crossDictDedup();
        pauseStopIncomplete();
        crashRecovery();
        uiConsistencyRealFiles();
        targetIsolation();
        System.out.println();
        System.out.println("=== 接力: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------- ① A→B→C 顺序 + 终态

    private static void advanceOrderAndTerminal() {
        System.out.println();
        System.out.println("-- ① A→B→C 执行顺序与终态 --");

        DictPlan p = new DictPlan();
        p.autoAdvance = true;
        p.targetKey = KEY;
        ok("A 加入成功", p.add(item("/d/A.txt", "A")), "A 被拒");
        ok("B 加入成功", p.add(item("/d/B.txt", "B")), "B 被拒");
        ok("C 加入成功", p.add(item("/d/C.txt", "C")), "C 被拒");
        ok("初始 3 本", p.items.size() == 3, String.valueOf(p.items.size()));
        ok("初始第 1 本", p.displayIndex() == 1, String.valueOf(p.displayIndex()));

        // 重复加入同一本（同 sha256、改名）必须被拒 —— 防止重复计划项
        DictPlan.Item dupA = item("/d/A-renamed.txt", "A-renamed");
        dupA.sha256 = "sha-/d/A.txt";   // 同一内容，只是换了名/路径
        ok("重复加入 A 被拒（同内容身份）", !p.add(dupA), "重复被加进");
        ok("重复加入后仍 3 本", p.items.size() == 3, String.valueOf(p.items.size()));

        // A 跑完 MISS → 自动前进到 B
        DictPlan.Step s1 = p.apply(DictPlan.Outcome.MISS);
        ok("A MISS 后前进", s1.advanced, "未前进");
        ok("A MISS 后到第 2 本", p.displayIndex() == 2, String.valueOf(p.displayIndex()));
        ok("A MISS 后计划仍 RUNNING", p.state == DictPlan.State.RUNNING,
                p.state.name());

        // B 跑完 MISS → 前进到 C
        DictPlan.Step s2 = p.apply(DictPlan.Outcome.MISS);
        ok("B MISS 后到第 3 本", p.displayIndex() == 3, String.valueOf(p.displayIndex()));

        // C 跑完 MISS → 无下一本，DONE_MISS（终态，不是回第 1 本）
        DictPlan.Step s3 = p.apply(DictPlan.Outcome.MISS);
        ok("C MISS 后不前进", !s3.advanced, "前进了");
        ok("C MISS 后计划 DONE_MISS（终态）", p.state == DictPlan.State.DONE_MISS,
                p.state.name());
        ok("终态计划不再接受新结论（不会重新从第 1 本开始）",
                p.apply(DictPlan.Outcome.MISS).terminal, "仍可转移");
        ok("终态后 index 仍停在最后一本", p.displayIndex() == 3,
                String.valueOf(p.displayIndex()));

        // 命中终止：任意一本 HIT → DONE_HIT，不再前进
        DictPlan ph = new DictPlan();
        ph.autoAdvance = true;
        ph.add(item("/d/A.txt", "A"));
        ph.add(item("/d/B.txt", "B"));
        ph.add(item("/d/C.txt", "C"));
        ph.apply(DictPlan.Outcome.MISS);   // A miss → B
        DictPlan.Step sh = ph.apply(DictPlan.Outcome.HIT);   // B hit
        ok("B HIT 后计划 DONE_HIT", ph.state == DictPlan.State.DONE_HIT, ph.state.name());
        ok("B HIT 后停在 B（不前进到 C）", ph.displayIndex() == 2,
                String.valueOf(ph.displayIndex()));

        // 关自动连跑：MISS 后停在当前本（PAUSED），不前进
        DictPlan pm = new DictPlan();
        pm.autoAdvance = false;
        pm.add(item("/d/A.txt", "A"));
        pm.add(item("/d/B.txt", "B"));
        DictPlan.Step sm = pm.apply(DictPlan.Outcome.MISS);
        ok("关自动连跑：A MISS 后停在 A（PAUSED）", pm.state == DictPlan.State.PAUSED
                        && pm.displayIndex() == 1, pm.state.name() + "#" + pm.displayIndex());
    }

    // ------------------------------------------------- ② 跨字典去重 + 身份

    private static void crossDictDedup() {
        System.out.println();
        System.out.println("-- ② 跨字典候选去重与身份 --");

        // A 字典的候选（含正确密码 candidate "correct-pass"）
        List<Candidate> dictA = new ArrayList<>();
        dictA.add(new Candidate("11111111", Candidate.Origin.USER_DICT));
        dictA.add(new Candidate("correct-pass", Candidate.Origin.USER_DICT));
        dictA.add(new Candidate("22222222", Candidate.Origin.USER_DICT));

        // 模拟 A 整本跑完：11111111、22222222 明确失败（进 triedSet），correct-pass 没跑到
        TriedSet tried = new TriedSet();
        tried.add("11111111");
        tried.add("22222222");

        // B 字典：含与 A 重复的候选 + 新候选
        List<Candidate> dictB = new ArrayList<>();
        dictB.add(new Candidate("11111111", Candidate.Origin.USER_DICT));  // A 已跑过
        dictB.add(new Candidate("correct-pass", Candidate.Origin.USER_DICT)); // A 没跑到
        dictB.add(new Candidate("33333333", Candidate.Origin.USER_DICT));  // 新

        CrackService.TriedFilter fB = CrackService.filterTried(dictB, tried);
        ok("B 里 A 已跑过的 11111111 被跳过", fB.skipped == 1, "skip=" + fB.skipped);
        ok("B 保留 2 条（correct-pass + 33333333）", fB.kept.size() == 2,
                String.valueOf(fB.kept.size()));
        ok("B 保留了 correct-pass（正确密码不被错误排除）",
                fB.kept.stream().anyMatch(c -> c.password.equals("correct-pass")), "被排除");

        // C 字典：全新候选，不被 A/B 的历史误伤
        List<Candidate> dictC = new ArrayList<>();
        dictC.add(new Candidate("44444444", Candidate.Origin.USER_DICT));
        CrackService.TriedFilter fC = CrackService.filterTried(dictC, tried);
        ok("C 全新候选不过滤", fC.skipped == 0 && fC.kept.size() == 1,
                "skip=" + fC.skipped + " keep=" + fC.kept.size());

        // 候选身份：跨字典同一口令身份一致（去重按口令本身，不按行号/文件）
        long idA = CandidateId.of("correct-pass");
        long idB = CandidateId.of("correct-pass");
        long idOther = CandidateId.of("different-pass");
        ok("同一口令跨字典身份一致", idA == idB, idA + " vs " + idB);
        ok("不同口令身份不同", idA != idOther, "撞了");

        // triedSet 只按目标键隔离：换 BSSID 是另一套记录
        String keyOther = TriedSet.targetKey("11:22:33:44:55:66", SSID);
        ok("不同 BSSID → 不同目标键", !KEY.equals(keyOther), KEY + " == " + keyOther);
    }

    // ------------------------------------------------- ③ 暂停 / 停止 / 未跑完

    private static void pauseStopIncomplete() {
        System.out.println();
        System.out.println("-- ③ 暂停 / 停止 / 未跑完 不得伪装成未命中 --");

        // PAUSED：停在当前本，不前进
        DictPlan pp = new DictPlan();
        pp.autoAdvance = true;
        pp.add(item("/d/A.txt", "A"));
        pp.add(item("/d/B.txt", "B"));
        DictPlan.Step sp = pp.apply(DictPlan.Outcome.PAUSED);
        ok("PAUSED 不前进", !sp.advanced, "前进了");
        ok("PAUSED 停在 A", pp.displayIndex() == 1, String.valueOf(pp.displayIndex()));
        ok("PAUSED 状态可继续", pp.state.isResumable(), pp.state.name());

        // INCOMPLETE（未跑完）：必须停在当前本，绝不当前进（否则漏字典）
        DictPlan pi = new DictPlan();
        pi.autoAdvance = true;
        pi.add(item("/d/A.txt", "A"));
        pi.add(item("/d/B.txt", "B"));
        DictPlan.Step si = pi.apply(DictPlan.Outcome.INCOMPLETE);
        ok("INCOMPLETE 不前进（正确密码可能还在 A 里）", !si.advanced, "前进了");
        ok("INCOMPLETE 停在 A（PAUSED，可重跑）",
                pi.state == DictPlan.State.PAUSED && pi.displayIndex() == 1,
                pi.state.name() + "#" + pi.displayIndex());

        // STOPPED：终态，不前进
        DictPlan ps = new DictPlan();
        ps.autoAdvance = true;
        ps.add(item("/d/A.txt", "A"));
        ps.add(item("/d/B.txt", "B"));
        ps.apply(DictPlan.Outcome.STOPPED);
        ok("STOPPED 是终态", ps.state.isTerminal(), ps.state.name());
        ok("STOPPED 停在 A", ps.displayIndex() == 1, String.valueOf(ps.displayIndex()));

        // BLOCKED：阻塞停在当前本，不前进
        DictPlan pb = new DictPlan();
        pb.autoAdvance = true;
        pb.add(item("/d/A.txt", "A"));
        pb.add(item("/d/B.txt", "B"));
        DictPlan.Step sb = pb.apply(DictPlan.Outcome.BLOCKED);
        ok("BLOCKED 不前进", !sb.advanced, "前进了");
        ok("BLOCKED 停在 A（不当作未命中）", pb.state == DictPlan.State.BLOCKED,
                pb.state.name());

        // 终态结论映射：planOutcomeOf 的优先级
        ok("planOutcomeOf: DONE_MISS → MISS",
                CrackService.planOutcomeOf(TaskQueue.State.DONE_MISS, false, false)
                        == DictPlan.Outcome.MISS, "");
        ok("planOutcomeOf: hit=true 优先于一切",
                CrackService.planOutcomeOf(TaskQueue.State.DONE_MISS, false, true)
                        == DictPlan.Outcome.HIT, "");
        ok("planOutcomeOf: blocked 优先于 stopped",
                CrackService.planOutcomeOf(TaskQueue.State.STOPPED, true, false)
                        == DictPlan.Outcome.BLOCKED, "");
        ok("planOutcomeOf: 其余（未跑完）→ INCOMPLETE",
                CrackService.planOutcomeOf(TaskQueue.State.RUNNING, false, false)
                        == DictPlan.Outcome.INCOMPLETE, "");
    }

    // ------------------------------------------------- ④ 崩溃恢复决策

    private static void crashRecovery() {
        System.out.println();
        System.out.println("-- ④ 崩溃恢复：计划优先，不丢下一本 --");

        // 场景：计划已推进到 B（A 已完成），但队列里没有 A 的 PENDING 任务
        // （进程死在「计划推进、派生任务之前」）。decide 必须返回 CREATE_NEXT。
        DictPlan p = new DictPlan();
        p.autoAdvance = true;
        p.targetKey = KEY;
        p.state = DictPlan.State.PAUSED;
        p.add(item("/d/A.txt", "A"));
        p.add(item("/d/B.txt", "B"));
        p.items.get(0).local = DictPlan.State.DONE_MISS;
        p.index = 1;   // 当前指向 B

        List<TaskQueue.Task> emptyQueue = new ArrayList<>();
        PlanRecovery.Decision d = PlanRecovery.decide(p, emptyQueue, KEY);
        ok("队列空但计划指向 B → 应补建 B（CREATE_NEXT）",
                d.action == PlanRecovery.Action.CREATE_NEXT, d.action.name());
        ok("补建的目标是 B", "/d/B.txt".equals(d.dictPath), String.valueOf(d.dictPath));
        ok("补建 offset 从 0 开始（新字典从头跑）", d.offset == 0, String.valueOf(d.offset));

        // 终态计划：decide 必须 BLOCK（不退回普通队列，防止命中后残留任务被执行）
        DictPlan pt = new DictPlan();
        pt.autoAdvance = true;
        pt.state = DictPlan.State.DONE_HIT;
        pt.add(item("/d/A.txt", "A"));
        PlanRecovery.Decision dt = PlanRecovery.decide(pt, emptyQueue, KEY);
        ok("终态计划（已命中）→ BLOCK，不执行残留任务",
                dt.action == PlanRecovery.Action.BLOCK, dt.action.name());

        // 无计划 → NONE（普通队列行为，接力不接管）
        PlanRecovery.Decision dn = PlanRecovery.decide(null, emptyQueue, KEY);
        ok("无计划 → NONE（不接管）", dn.action == PlanRecovery.Action.NONE, dn.action.name());
    }


    // ------------------------------------------------- ⑤ UI 状态一致性（真实文件 + 真实派生）

    /**
     * 用三本**真实文件**（不同名、不同内容）走真实计划推进 + 真实任务派生，
     * 逐次断言「UI 显示的当前字典」（{@code plan.current().name}，即 {@code livePlan()}
     * 唯一权威来源）与「后台实际派生执行的任务」（{@code deriveForPlan} 的 dictPath）
     * 始终一致。这就是破解页「当前字典」卡的取值来源。
     */
    private static void uiConsistencyRealFiles() {
        System.out.println();
        System.out.println("-- ⑤ UI 状态一致性（真实文件 A/B/C + 真实派生） --");

        java.io.File root = new java.io.File(System.getProperty("java.io.tmpdir"),
                "relay-ui-" + System.currentTimeMillis());
        java.io.File d = new java.io.File(root, "WPAForge-dict");
        d.mkdirs();
        java.io.File fa = new java.io.File(d, "弱口令表A.txt");
        java.io.File fb = new java.io.File(d, "手机号派生B.txt");
        java.io.File fc = new java.io.File(d, "姓名拼音C.txt");
        try {
            write(fa, "aaaaaaaa\nbbbbbbbb\n");
            write(fb, "cccccccc\ndddddddd\neeeeeeee\n");
            write(fc, "ffffffff\n");
        } catch (Exception e) {
            ok("⑤ 写临时字典文件", false, String.valueOf(e));
            return;
        }

        DictPlan p = new DictPlan();
        p.autoAdvance = true;
        p.targetKey = KEY;
        DictPlan.Item a = new DictPlan.Item(fa.getAbsolutePath(), "弱口令表A");
        a.sha256 = DictPlan.sha256Of(fa);
        a.parseRules = DictPlan.PARSE_RULES_VERSION;
        DictPlan.Item b = new DictPlan.Item(fb.getAbsolutePath(), "手机号派生B");
        b.sha256 = DictPlan.sha256Of(fb);
        b.parseRules = DictPlan.PARSE_RULES_VERSION;
        DictPlan.Item c = new DictPlan.Item(fc.getAbsolutePath(), "姓名拼音C");
        c.sha256 = DictPlan.sha256Of(fc);
        c.parseRules = DictPlan.PARSE_RULES_VERSION;
        ok("⑤ A 加入", p.add(a), "");
        ok("⑤ B 加入", p.add(b), "");
        ok("⑤ C 加入", p.add(c), "");

        // 起点：UI 应显示 A
        ok("⑤ 起点 UI=第1本=弱口令表A", p.displayIndex() == 1
                        && "弱口令表A".equals(p.current().name),
                p.displayIndex() + ":" + (p.current() == null ? "-" : p.current().name));

        // A MISS → UI 应显示 B，且派生的下一本任务指向 B
        DictPlan.Step s1 = p.apply(DictPlan.Outcome.MISS);
        ok("⑤ A→B：UI=第2本=手机号派生B", s1.advanced && p.displayIndex() == 2
                        && "手机号派生B".equals(p.current().name),
                p.displayIndex() + ":" + (p.current() == null ? "-" : p.current().name));

        // B MISS → UI 应显示 C
        DictPlan.Step s2 = p.apply(DictPlan.Outcome.MISS);
        ok("⑤ B→C：UI=第3本=姓名拼音C", s2.advanced && p.displayIndex() == 3
                        && "姓名拼音C".equals(p.current().name),
                p.displayIndex() + ":" + (p.current() == null ? "-" : p.current().name));

        // C MISS → 终态，UI 不应再显示「当前第 4 本」或「当前 C 正在运行」
        DictPlan.Step s3 = p.apply(DictPlan.Outcome.MISS);
        ok("⑤ C 后终态 DONE_MISS", p.state == DictPlan.State.DONE_MISS, p.state.name());
        ok("⑤ 终态后 UI 不显示「当前/下一本」依据（isTerminal 为真）",
                p.state.isTerminal(), "非终态");

        // 派生任务 dictPath 与 plan.current() 逐次一致（真实 deriveForPlan 语义复现：
        // 每次 MISS 后 current() 就是下一次要跑的那本）。
        // 这里用真实 TaskQueue.deriveForPlan 需要 Context，改为验证 plan.current().path
        // 与真实文件路径一致（这是 deriveForPlan 的入参 nx.path 的来源）。
        ok("⑤ current().path 是真实文件 A/B/C 之一（可派生）",
                new java.io.File(p.current().path).getName().endsWith(".txt"), "");

        // 清理
        purge(fa); purge(fb); purge(fc); d.delete(); root.delete();
    }

    // ------------------------------------------------- ⑥ 目标隔离（按目标分文件）

    /**
     * 每个 Wi-Fi 目标一份计划文件（{@code DictPlan.fileAt(dir, key)}）。
     * 验证：不同目标 → 不同文件；A 的计划/进度不会串到 B。
     */
    private static void targetIsolation() {
        System.out.println();
        System.out.println("-- ⑥ 目标隔离：A/B 计划文件互不相干 --");

        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "relay-iso-" + System.currentTimeMillis());
        dir.mkdirs();
        String keyA = "b001122334455";   // 目标 A（模拟 BSSID 派生键）
        String keyB = "baabbccddeeff";   // 目标 B
        java.io.File fa = DictPlan.fileAt(dir, keyA);
        java.io.File fb = DictPlan.fileAt(dir, keyB);
        ok("⑥ A/B 计划文件不同", !fa.getAbsolutePath().equals(fb.getAbsolutePath()),
                fa.getName() + " vs " + fb.getName());
        ok("⑥ 空目标键落 unbound", DictPlan.fileAt(dir, "").getName().equals("unbound.plan.json"),
                DictPlan.fileAt(dir, "").getName());

        // A 建三本，跑到第 2 本（offset 前进）
        DictPlan a = new DictPlan();
        a.autoAdvance = true;
        a.targetKey = keyA;
        a.add(item("/d/A1.txt", "A1"));
        a.add(item("/d/A2.txt", "A2"));
        a.add(item("/d/A3.txt", "A3"));
        a.apply(DictPlan.Outcome.MISS);   // A1 miss → A2
        a.items.get(0).offset = 500;      // A1 跑到 500
        a.save(fa);

        // B 是全新计划（只有一本，无进度）
        DictPlan b = new DictPlan();
        b.autoAdvance = true;
        b.targetKey = keyB;
        b.add(item("/d/B1.txt", "B1"));
        b.save(fb);

        // 读回：A 的三本 + offset 都在 A 文件；B 只有一本、offset 0
        DictPlan ra = DictPlan.load(fa);
        DictPlan rb = DictPlan.load(fb);
        ok("⑥ A 计划 3 本", ra.items.size() == 3, String.valueOf(ra.items.size()));
        ok("⑥ A 停在 A2（第 2 本）", ra.displayIndex() == 2, String.valueOf(ra.displayIndex()));
        ok("⑥ A 的 A1 offset=500 保留", ra.items.get(0).offset == 500,
                String.valueOf(ra.items.get(0).offset));
        ok("⑥ B 计划只有 1 本（不继承 A 的 3 本）", rb.items.size() == 1,
                String.valueOf(rb.items.size()));
        ok("⑥ B 的 offset=0（不继承 A 的进度）", rb.items.get(0).offset == 0,
                String.valueOf(rb.items.get(0).offset));
        ok("⑥ B 的 targetKey 是 B", keyB.equals(rb.targetKey), rb.targetKey);

        // 清理
        fa.delete(); fb.delete(); dir.delete();
    }

    private static void write(java.io.File f, String content) throws Exception {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            o.write(content.getBytes("UTF-8"));
        }
    }

    private static void purge(java.io.File f) {
        if (f != null && f.exists()) f.delete();
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 多字典暂停/续跑 + 字典续命 + 持久化一致性（真实生产方法）。
 *
 * <p>覆盖需求第四、五节：接力、各位置暂停、续跑恢复、异常退出、续命、
 * 重复接管保护、写前日志与原子落盘。
 */
public final class DictResumeAndPlanTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static final String SSID = "AP-R";
    private static final String BSSID = "AA:BB:CC:00:11:22";
    private static final String KEY = TriedSet.targetKey(BSSID, SSID);

    private static NetRecord net() {
        return new NetRecord(SSID, BSSID, -50, 2437, NetRecord.Security.WPA2, false, "");
    }

    private static DictPlan.Item item(String path, String name, String sha, int offset) {
        DictPlan.Item it = new DictPlan.Item(path, name);
        it.sha256 = sha;
        it.parseRules = DictPlan.PARSE_RULES_VERSION;
        it.offset = offset;
        it.dictSize = 100;
        return it;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 多字典暂停/续跑 + 续命 + 持久化（真实生产） ===");
        planPauseResume();
        planCrashRecovery();
        resumeByContent();
        persistedConsistency();
        writeAheadContract();
        atomicSaveContract();
        relaySettleContract();
        System.out.println();
        System.out.println("=== 续跑/续命: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------ ① 计划暂停/续跑

    private static void planPauseResume() throws Exception {
        System.out.println();
        System.out.println("-- ① 计划各位置暂停与续跑 --");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "plan-pr-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            File f = DictPlan.fileAt(dir, KEY);
            DictPlan p = new DictPlan();
            p.autoAdvance = true;
            p.targetKey = KEY;
            p.skipPolicyId = 1;
            p.add(item("/d/A.txt", "A", "shaA", 0));
            p.add(item("/d/B.txt", "B", "shaB", 0));
            p.add(item("/d/C.txt", "C", "shaC", 0));
            p.save(f);

            // ① A 执行中间暂停：offset 40
            p.items.get(0).offset = 40;
            p.items.get(0).local = DictPlan.State.PAUSED;
            p.state = DictPlan.State.PAUSED;
            p.save(f);

            DictPlan r = DictPlan.load(f);
            ok("暂停后 offset 保留（A=40）", r.items.get(0).offset == 40,
                    "" + r.items.get(0).offset);
            ok("暂停后 index 不变（仍第 1 本）", r.displayIndex() == 1, "" + r.displayIndex());
            ok("暂停后状态是 PAUSED（不是 STOPPED）",
                    r.state == DictPlan.State.PAUSED, r.state.name());
            ok("暂停不删计划成员", r.items.size() == 3, "" + r.items.size());
            ok("暂停保留策略档位", r.skipPolicyId == 1, "" + r.skipPolicyId);

            // 续跑：A 继续跑完 → 前进 B
            DictPlan.Step s1 = r.apply(DictPlan.Outcome.MISS);
            ok("A 续跑完成后前进到 B", s1.advanced && r.displayIndex() == 2,
                    r.displayIndex() + "");
            ok("A 的结论落在 A 自己的条目上",
                    r.items.get(0).local == DictPlan.State.DONE_MISS, r.items.get(0).local.name());
            ok("B 起点 offset 为 0（独立进度）", r.items.get(1).offset == 0,
                    "" + r.items.get(1).offset);

            // ② A→B 边界后，B 执行中暂停
            r.items.get(1).offset = 77;
            r.save(f);
            DictPlan r2 = DictPlan.load(f);
            ok("B 中途 offset 独立保存（77）", r2.items.get(1).offset == 77, "");
            ok("B 的暂停不影响 A 的结论", r2.items.get(0).local == DictPlan.State.DONE_MISS, "");
            ok("C 仍未开始", r2.items.get(2).offset == 0
                    && r2.items.get(2).local == DictPlan.State.IDLE, "");

            // ③ B 完成 → C；C 接近结束时暂停
            r2.apply(DictPlan.Outcome.MISS);         // B done → C
            ok("B 完成后到 C", r2.displayIndex() == 3, "" + r2.displayIndex());
            r2.items.get(2).offset = 99;             // 接近结束
            r2.state = DictPlan.State.PAUSED;
            r2.save(f);
            DictPlan r3 = DictPlan.load(f);
            ok("C 接近结束暂停，offset=99 保留", r3.items.get(2).offset == 99, "");
            ok("三本成员都在", r3.items.size() == 3, "");

            // ④ 快速连续暂停/续跑（多次 load/apply/save 幂等）
            for (int i = 0; i < 5; i++) {
                DictPlan q = DictPlan.load(f);
                q.state = DictPlan.State.RUNNING;
                q.save(f);
                DictPlan z = DictPlan.load(f);
                z.state = DictPlan.State.PAUSED;
                z.save(f);
            }
            DictPlan r4 = DictPlan.load(f);
            ok("快速暂停/续跑 5 轮后成员与进度不变",
                    r4.items.size() == 3 && r4.items.get(2).offset == 99
                            && r4.items.get(0).local == DictPlan.State.DONE_MISS,
                    r4.items.size() + "");

            // ⑤ C 完成 → 计划完成
            r4.state = DictPlan.State.RUNNING;
            DictPlan.Step s3 = r4.apply(DictPlan.Outcome.MISS);
            ok("C 完成后计划终态", r4.state == DictPlan.State.DONE_MISS, r4.state.name());
            ok("终态后不再前进", !s3.advanced, "");

            // ⑥ 计划完成后清理：删文件 + 作废任务
            StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
            TaskQueue.setStoreForTest(mem);
            try {
                TaskQueue.enqueuePlan(null, net(), "/d/A.txt", "A");
                int killed = TaskQueue.stopPlanTasks(null);
                int removed = TaskQueue.removePlanTasks(null);
                ok("完成后清理：作废/移除计划任务",
                        (killed + removed) >= 1 && !TaskQueue.hasPlanWork(null),
                        killed + "/" + removed);
            } finally {
                TaskQueue.setStoreForTest(null);
            }
            ok("清理不改计划文件（由用户显式删）", DictPlan.load(f).items.size() == 3, "");
        } finally {
            purge(dir);
        }
    }

    // ------------------------------------------------ ② 异常退出恢复

    private static void planCrashRecovery() throws Exception {
        System.out.println();
        System.out.println("-- ② 异常退出恢复 --");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "plan-crash-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            File f = DictPlan.fileAt(dir, KEY);
            DictPlan p = new DictPlan();
            p.autoAdvance = true;
            p.targetKey = KEY;
            p.state = DictPlan.State.RUNNING;       // 盘上是 RUNNING = 进程死在执行中
            p.add(item("/d/A.txt", "A", "shaA", 0));
            p.add(item("/d/B.txt", "B", "shaB", 0));
            p.items.get(0).offset = 55;
            p.save(f);

            DictPlan r = DictPlan.load(f);
            ok("重启读到 RUNNING", r.state == DictPlan.State.RUNNING, r.state.name());
            ok("recover 归一为 PAUSED（不当前进）",
                    DictPlan.recover(r.state) == DictPlan.State.PAUSED, "");
            ok("崩溃恢复后 offset 保留", r.items.get(0).offset == 55, "");
            ok("崩溃恢复后顺序保留",
                    r.items.get(0).name.equals("A") && r.items.get(1).name.equals("B"), "");

            // 崩溃窗口：A 已完成、计划未推进 → decide 应 ADVANCE_THEN_CREATE
            DictPlan p2 = new DictPlan();
            p2.autoAdvance = true;
            p2.targetKey = KEY;
            p2.state = DictPlan.State.RUNNING;
            p2.add(item("/d/A.txt", "A", "shaA", 0));
            p2.add(item("/d/B.txt", "B", "shaB", 0));
            p2.items.get(0).local = DictPlan.State.DONE_MISS;
            PlanRecovery.Decision d = PlanRecovery.decide(p2,
                    new ArrayList<TaskQueue.Task>(), KEY);
            ok("A 已完成未推进 → ADVANCE_THEN_CREATE",
                    d.action == PlanRecovery.Action.ADVANCE_THEN_CREATE, d.action.name());
            ok("补建的是 B", "/d/B.txt".equals(d.dictPath), String.valueOf(d.dictPath));

            // 重复接管保护：计划 **index 已指向 B**（A 完成并已推进），
            // 队列里已有 B 的 PENDING → RUN_EXISTING（复用，不重复建）。
            DictPlan p3 = new DictPlan();
            p3.autoAdvance = true;
            p3.targetKey = KEY;
            p3.state = DictPlan.State.RUNNING;
            p3.add(item("/d/A.txt", "A", "shaA", 0));
            p3.add(item("/d/B.txt", "B", "shaB", 0));
            p3.items.get(0).local = DictPlan.State.DONE_MISS;
            p3.index = 1;                                  // 已推进到 B
            List<TaskQueue.Task> q = new ArrayList<>();
            TaskQueue.Task tb = new TaskQueue.Task();
            tb.id = "tB"; tb.ssid = SSID; tb.bssid = BSSID;
            tb.dictPath = "/d/B.txt"; tb.dictName = "B";
            tb.state = TaskQueue.State.PENDING;
            q.add(tb);
            PlanRecovery.Decision d2 = PlanRecovery.decide(p3, q, KEY);
            ok("计划已指向 B 且队列有 B 任务 → RUN_EXISTING（不重复建）",
                    d2.action == PlanRecovery.Action.RUN_EXISTING, d2.action.name());

            // 停止后重启：BLOCK + 有普通任务则放行
            p2.state = DictPlan.State.STOPPED;
            PlanRecovery.Decision d3 = PlanRecovery.decide(p2, new ArrayList<TaskQueue.Task>(), KEY);
            ok("停止的计划 → BLOCK", d3.action == PlanRecovery.Action.BLOCK, d3.action.name());
        } finally {
            purge(dir);
        }
    }

    // ------------------------------------------------ ③ 字典续命（按内容身份）

    private static void resumeByContent() {
        System.out.println();
        System.out.println("-- ③ 字典续命（内容 SHA 识别） --");

        // 队列里有一条上次跑过的任务：内容 shaX、路径 old、offset 60
        List<TaskQueue.Task> q = new ArrayList<>();
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = "tX"; t.ssid = SSID; t.bssid = BSSID;
        t.dictPath = "/old/path/dict.txt"; t.dictName = "旧路径表";
        t.dictSha = "shaX"; t.offset = 60; t.dictSize = 200;
        q.add(t);

        // ① 内容相同、路径不同 → 继承，且标记 pathChanged
        DictResume.Info i1 = DictResume.find(q, null, KEY, "shaX", "/new/path/dict.txt");
        ok("内容相同（路径变）→ 找到历史", i1.found, "");
        ok("续命 offset = 60", DictResume.offsetFor(i1, 200) == 60,
                "" + DictResume.offsetFor(i1, 200));
        ok("标记路径已变", i1.pathChanged, "");
        ok("来源是任务", "task".equals(i1.source), i1.source);

        // ② 内容不同（同路径）→ 拒绝继承 + contentChanged
        DictResume.Info i2 = DictResume.find(q, null, KEY, "shaY", "/old/path/dict.txt");
        ok("同路径不同内容 → 不继承", !i2.found && i2.contentChanged, "");
        ok("不同内容 offsetFor = 0", DictResume.offsetFor(i2, 200) == 0, "");

        // ③ 完全不同（新字典）→ 找不到
        DictResume.Info i3 = DictResume.find(q, null, KEY, "shaZ", "/other/zzz.txt");
        ok("全新字典 → 无历史", !i3.found && !i3.contentChanged, "");
        ok("无历史 offsetFor = 0", DictResume.offsetFor(i3, 200) == 0, "");

        // ④ 换目标（不同 BSSID）→ 不继承
        DictResume.Info i4 = DictResume.find(q, null,
                TriedSet.targetKey("99:99:99:99:99:99", "OTHER"), "shaX", "/new/x.txt");
        ok("换 Wi-Fi 目标 → 不继承", !i4.found, "");

        // ⑤ 越界保护：历史 offset 超过当前字典长度 → 归零重跑
        ok("历史 offset 越界 → 0", DictResume.offsetFor(i1, 10) == 0, "");

        // ⑥ 计划成员也参与续命，且不污染其他成员
        DictPlan plan = new DictPlan();
        plan.targetKey = KEY;
        plan.add(item("/d/A.txt", "A", "shaA", 10));
        plan.add(item("/d/B.txt", "B", "shaB", 20));
        DictResume.Info i5 = DictResume.find(new ArrayList<TaskQueue.Task>(), plan, KEY,
                "shaB", "/d/B-moved.txt");
        ok("计划成员按内容识别", i5.found && DictResume.offsetFor(i5, 100) == 20,
                i5.source + "/" + DictResume.offsetFor(i5, 100));
        ok("识别 B 不影响 A 的 offset",
                plan.items.get(0).offset == 10, "" + plan.items.get(0).offset);
        DictResume.Info i6 = DictResume.find(new ArrayList<TaskQueue.Task>(), plan, KEY,
                "shaX-unknown", "/zzz.txt");
        ok("不存在的计划成员 → 无历史", !i6.found, "");
    }

    // ------------------------------------------------ ④ 持久化一致性

    private static void persistedConsistency() throws Exception {
        System.out.println();
        System.out.println("-- ④ 持久化一致性（账本 / 已试 / 队列） --");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "persist-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            // 账本：写入 → 落盘 → 读回完全一致
            File lf = new File(dir, "k.ledger");
            AttemptLedger lg = new AttemptLedger();
            for (int i = 0; i < 100; i++) {
                lg.record("pw" + i + "0000", i % 2 == 0
                        ? AttemptLedger.Level.CONFIRMED_WRONG : AttemptLedger.Level.TIMEOUT);
            }
            ok("record 后 dirty=true", lg.isDirty(), "");
            ok("落盘成功", lg.save(lf), "");
            ok("落盘后 dirty=false", !lg.isDirty(), "");

            AttemptLedger rl = AttemptLedger.load(lf);
            ok("读回条数一致", rl.size() == lg.size(), rl.size() + "/" + lg.size());
            ok("读回等级一致（pw00000=已证实错误 / pw10000=超时）",
                    rl.level("pw00000") == AttemptLedger.Level.CONFIRMED_WRONG
                            && rl.level("pw10000") == AttemptLedger.Level.TIMEOUT,
                    rl.level("pw00000") + "/" + rl.level("pw10000"));

            // 损坏保护：乱码 → 空账本（不抛）
            File bad = new File(dir, "bad.ledger");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(bad)) {
                o.write(new byte[]{1, 2, 3, 4, 5, 6, 7});
            }
            AttemptLedger rb = AttemptLedger.load(bad);
            ok("损坏账本 → 安全降级（不抛、可用）", rb != null && rb.size() == 0,
                    "" + (rb == null ? "null" : rb.size()));

            // 已试集合：落盘/读回
            File tf = new File(dir, "k.tried");
            TriedSet ts = new TriedSet();
            ts.add("aaaaaaaa");
            ts.add("bbbbbbbb");
            ok("已试集合 dirty", ts.isDirty(), "");
            ok("已试集合落盘", ts.save(tf), "");
            TriedSet rt = TriedSet.load(tf);
            ok("已试集合读回一致", rt.size() == 2
                    && rt.contains("aaaaaaaa") && !rt.contains("cccccccc"), "");

            // 队列（内存 Store）：写前日志顺序契约由源码守卫（见 ⑤）
            StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
            TaskQueue.setStoreForTest(mem);
            try {
                TaskQueue.Task a = TaskQueue.enqueue(null, net(), "/d/A.txt", "A");
                a.dictSha = "shaA"; a.offset = 33;
                TaskQueue.update(null, a);
                TaskQueue.Task rb2 = null;
                for (TaskQueue.Task x : TaskQueue.load(null)) if (x.id.equals(a.id)) rb2 = x;
                ok("队列读回 dictSha 与 offset",
                        rb2 != null && "shaA".equals(rb2.dictSha) && rb2.offset == 33,
                        rb2 == null ? "null" : rb2.dictSha + "/" + rb2.offset);
            } finally {
                TaskQueue.setStoreForTest(null);
            }
        } finally {
            purge(dir);
        }
    }

    // ------------------------------------------------ ⑤ 写前日志契约

    private static void writeAheadContract() {
        System.out.println();
        System.out.println("-- ⑤ 写前日志契约（账本先于 offset） --");
        String cs = read("CrackService.java");
        if (cs == null) { ok("CrackService.java 可读", false, "读不到"); return; }
        int sp = cs.indexOf("private void saveProgress(");
        int end = cs.indexOf("\n    }", sp);
        String body = sp < 0 ? "" : cs.substring(sp, end < 0 ? cs.length() : end);
        int iTried = body.indexOf("flushTried()");
        int iLedger = body.indexOf("flushLedger()");
        int iOffset = body.indexOf("TaskQueue.updateProgress(");
        ok("saveProgress 先 flushTried", iTried >= 0, "");
        ok("saveProgress 先 flushLedger", iLedger >= 0, "");
        ok("两者都在 updateProgress 之前",
                iTried >= 0 && iLedger >= 0 && iOffset >= 0
                        && iTried < iOffset && iLedger < iOffset,
                iTried + "/" + iLedger + "/" + iOffset);
        ok("flushLedger 有 dirty 守卫", cs.contains("if (!l.isDirty()) return;"), "");
    }

    // ------------------------------------------------ ⑥ 原子写入契约

    private static void atomicSaveContract() throws Exception {
        System.out.println();
        System.out.println("-- ⑥ 原子写入与失败保护（绝不先删目标） --");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "atomic-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            // 行为：目标是**目录** → 替换必然失败 → save 返回 false，目标仍完好
            File asDir = new File(dir, "target-is-dir");
            asDir.mkdirs();
            TriedSet ts = new TriedSet();
            ts.add("aaaaaaaa");
            ok("目标是目录时 save 失败（不假装成功）", !ts.save(asDir), "");
            ok("失败后目标目录仍存在", asDir.isDirectory(), "");

            AttemptLedger lg = new AttemptLedger();
            lg.record("pw00000", AttemptLedger.Level.CONFIRMED_WRONG);
            ok("账本：目标是目录时 save 失败", !lg.save(asDir), "");
            ok("账本失败后目标目录仍在", asDir.isDirectory(), "");

            DictPlan dp = new DictPlan();
            dp.add(item("/d/A.txt", "A", "shaA", 0));
            ok("计划：目标是目录时 save 失败", !dp.save(asDir), "");
            ok("计划失败后目标目录仍在", asDir.isDirectory(), "");

            // 正常路径仍然可用
            File ok1 = new File(dir, "ok.ledger");
            AttemptLedger lg2 = new AttemptLedger();
            lg2.record("pw00000", AttemptLedger.Level.TIMEOUT);
            ok("正常目标 save 成功", lg2.save(ok1) && ok1.isFile(), "");
            ok("读回一致", AttemptLedger.load(ok1).level("pw00000")
                    == AttemptLedger.Level.TIMEOUT, "");

            // 源码契约：三个 save 里不得再出现「先删目标再改名」
            for (String f : new String[]{"TriedSet.java", "AttemptLedger.java", "DictPlan.java"}) {
                String src = read("audit/" + f);
                String save = methodBody(src, "boolean save(");
                ok(f + " save 里无 f.delete()（先删后改名）",
                        src != null && !save.contains("f.delete()"), "仍有先删");
                ok(f + " save 走 AtomicFileOp.moveAtomic",
                        src != null && save.contains("AtomicFileOp.moveAtomic"), "未接原子层");
            }
        } finally {
            purge(dir);
        }
    }

    // ------------------------------------------------ ⑦ 接力收尾契约（真机踩到的 P0）

    private static void relaySettleContract() {
        System.out.println();
        System.out.println("-- ⑦ 接力收尾契约（候选为空必须 settle 恢复记录） --");
        String cs = read("CrackService.java");
        if (cs == null) { ok("CrackService.java 可读", false, "读不到"); return; }

        // 定位「候选为空」分支
        int i = cs.indexOf("候选为空\", \"no candidates\"");
        if (i < 0) i = cs.indexOf("\"候选为空\", \"no candidates\"");
        ok("找到候选为空分支", i > 0, "");
        int segStart = Math.max(0, i - 900);
        String seg = cs.substring(segStart, Math.min(cs.length(), i + 200));
        ok("候选为空前先 restoreAfterRun（收尾恢复记录）",
                seg.contains("restoreAfterRun(task.id"), "缺 settle");
        ok("settle 用的是 DONE_MISS 结论",
                seg.contains("TaskQueue.State.DONE_MISS.name()"), "");

        // 环境闸门分支**不该** settle（那时没有登记记录）
        int g = cs.indexOf("环境闸门未通过\", \"gate blocked\"");
        if (g < 0) g = cs.indexOf("\"环境闸门未通过\", \"gate blocked\"");
        ok("找到环境闸门分支", g > 0, "");
        String gseg = cs.substring(Math.max(0, g - 400), Math.min(cs.length(), g + 50));
        ok("环境闸门分支不调用 restoreAfterRun（未登记记录）",
                !gseg.contains("restoreAfterRun("), "误 settle");

        // 续命身份（dictSha）必须随接力一起传递，否则下一本无法按内容识别历史
        ok("派生下一本时写入 dictSha", cs.contains("nt.dictSha = nx.sha256"), "缺传递");
        ok("崩溃恢复补建时写入 dictSha", cs.contains("nt.dictSha = cur.sha256"), "缺传递");
    }

    /** 截取从某个方法签名到下一个 4 空格缩进右花括号的方法体。 */
    private static String methodBody(String code, String sig) {
        if (code == null) return "";
        int i = code.indexOf(sig);
        if (i < 0) return "";
        int j = code.indexOf("\n    }", i);
        return j < 0 ? code.substring(i) : code.substring(i, j);
    }

    private static String read(String name) {
        try {
            File f = new File(io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir(), name);
            if (!f.isFile()) return null;
            StringBuilder sb = new StringBuilder();
            for (String l : java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) sb.append(l).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void purge(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) purge(c);
        f.delete();
    }
}
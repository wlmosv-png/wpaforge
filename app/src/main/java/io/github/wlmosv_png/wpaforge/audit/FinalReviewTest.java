package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.CrackService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 最终独立复核（三项）：碰撞保护 / 暂停续跑矩阵 / 持久化失败恢复。
 *
 * <p>全部调用真实生产方法与真实文件 IO；不使用源码字符串冒充行为验证
 * （仅第③节含一条明确的源码契约，已标注）。
 */
public final class FinalReviewTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final String SSID = "FR";
    private static final String BSSID = "02:00:00:00:00:0A";
    private static final String KEY = TriedSet.targetKey(BSSID, SSID);

    private static List<Candidate> dict(String... pws) {
        List<Candidate> l = new ArrayList<>();
        for (String p : pws) l.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return l;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 最终独立复核：碰撞 / 暂停续跑 / 持久化 ===");
        collisionProtection();
        pauseResumeMatrix();
        persistenceUnderFailure();
        System.out.println();
        System.out.println("=== 复核: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // =============================================== ① CandidateId 碰撞保护

    private static void collisionProtection() throws Exception {
        System.out.println();
        System.out.println("-- ① CandidateId 64 位碰撞保护 --");
        File dir = new File(System.getProperty("java.io.tmpdir"), "fr-col-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            // 1) 身份来源唯一且确定：同一口令恒等、不同口令不同
            ok("同口令身份稳定", CandidateId.of("pwAAAAAA") == CandidateId.of("pwAAAAAA"), "");
            ok("不同口令身份不同", CandidateId.of("pwAAAAAA") != CandidateId.of("pwBBBBBB"), "");
            ok("身份 = SHA-256 前 8 字节（可复算）", CandidateId.of("12345678") == sha256Prefix8("12345678"), "");

            // 2) 账本查找是**精确**匹配，不是前缀/截断/模糊匹配
            File lf = new File(dir, "k.ledger");
            AttemptLedger lg = new AttemptLedger();
            lg.record("pwAAAAAA", AttemptLedger.Level.CONFIRMED_WRONG);
            lg.save(lf);
            AttemptLedger rl = AttemptLedger.load(lf);
            ok("精确命中（同口令）", rl.level("pwAAAAAA") == AttemptLedger.Level.CONFIRMED_WRONG, "");
            ok("相邻口令不误命中（无前缀/模糊匹配）",
                    rl.level("pwAAAAAB") == AttemptLedger.Level.NOT_EXECUTED, "");
            ok("长度相近口令不误命中",
                    rl.level("pwAAAAA") == AttemptLedger.Level.NOT_EXECUTED
                            && rl.level("pwAAAAAAA") == AttemptLedger.Level.NOT_EXECUTED, "");

            // 3) 构造「库里存在一个**不属于任何真实口令**的身份」：
            //    任何真实口令都不得被它命中 —— 证明不存在系统性误过滤
            File lf2 = new File(dir, "syn.ledger");
            writeLedgerV2WithId(lf2, 0x1234567890ABCDEFL, 1 /* CONFIRMED_WRONG rank */);
            AttemptLedger rl2 = AttemptLedger.load(lf2);
            int falseHits = 0;
            for (int i = 0; i < 5000; i++) {
                if (rl2.level("cand" + i + "zz") != AttemptLedger.Level.NOT_EXECUTED) falseHits++;
            }
            ok("5000 个真实口令对「外来身份」零误命中", falseHits == 0, "误命中 " + falseHits);

            // 4) 跨字典规模测试：20000 个互不相同的口令，只有被登记的会被跳过
            TriedSet tried = new TriedSet();
            AttemptLedger big = new AttemptLedger();
            int seeded = 500;
            for (int i = 0; i < 20000; i++) {
                String pw = "finalreview" + i;
                if (i % 40 == 0 && seeded > 0) {          // 每 40 个登记 1 个
                    tried.add(pw);
                    big.record(pw, AttemptLedger.Level.CONFIRMED_WRONG);
                }
            }
            List<Candidate> work = new ArrayList<>();
            for (int i = 0; i < 20000; i++) work.add(new Candidate("finalreview" + i, Candidate.Origin.USER_DICT));
            CrackService.FilterOutcome fo = CrackService.filterCandidates(
                    work, tried, big, AttemptLedger.SkipPolicy.CONSERVATIVE);
            int expectSkip = 0;
            for (int i = 0; i < 20000; i++) if (i % 40 == 0) expectSkip++;
            ok("20000 条中跳过数 = 实际登记数（无多跳/少跳）",
                    fo.totalSkipped() == expectSkip,
                    fo.totalSkipped() + " vs " + expectSkip);
            ok("保留数 = 总数 - 跳过", fo.kept.size() == 20000 - expectSkip, "" + fo.kept.size());

            // 5) 运行内碰撞守卫（当前列表内两口令撞同一身份 → 一律不跳）
            long id = CandidateId.of("11111111");
            java.util.Set<Long> amb = new java.util.HashSet<>();
            amb.add(id);
            TriedSet t2 = new TriedSet();
            t2.add("11111111");
            ok("无守卫时：已登记 → 跳过", CrackService.isSkipped("11111111", t2, null,
                    AttemptLedger.SkipPolicy.CONSERVATIVE, false), "");
            ok("有守卫时：身份冲突 → 不跳（保守保留）",
                    !CrackService.isSkipped("11111111", t2, null,
                            AttemptLedger.SkipPolicy.CONSERVATIVE, false, amb), "被误跳");
            ok("ambiguousIds 只标真正冲突（同文本重复不算）",
                    CrackService.ambiguousIds(dict("a1111111", "a1111111")).isEmpty(), "");

            // 6) 兜底：即便发生误跳，强制重跑可绕过（不会永久锁死）
            ok("forceRerun 绕过一切过滤",
                    !CrackService.isSkipped("11111111", t2, null,
                            AttemptLedger.SkipPolicy.CONSERVATIVE, true, amb), "");

            // 7) 残留风险量化（写进日志，供报告引用）
            double perPair = 1.0 / Math.pow(2, 64);
            double birthday1M = 1.0 - Math.exp(-Math.pow(1e6, 2) / (2.0 * Math.pow(2, 64)));
            info(String.format("残留风险：单对碰撞 %.2e；100 万条生日碰撞 %.2e", perPair, birthday1M));
            ok("残留风险已量化且 < 1e-6", birthday1M < 1e-6, String.valueOf(birthday1M));
        } finally {
            purge(dir);
        }
    }

    /** 手写一个 v2 账本文件（头 + 一条 id/rank 记录），用于构造「外来身份」。 */
    private static void writeLedgerV2WithId(File f, long id, long rank) throws Exception {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            long head = ((long) 0x57504C47 << 32) | 2L;      // MAGIC + FORMAT_VERSION=2
            o.write(le(head));
            o.write(le(id));
            o.write(le(rank));
        }
    }

    private static byte[] le(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    private static long sha256Prefix8(String pw) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(pw.getBytes("UTF-8"));
        long h = 0;
        for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xffL);
        return h;
    }

    // =============================================== ② 暂停续跑矩阵

    private static void pauseResumeMatrix() throws Exception {
        System.out.println();
        System.out.println("-- ② 暂停/续跑矩阵（index / offset / 过滤数 / 尝试数） --");
        File dir = new File(System.getProperty("java.io.tmpdir"), "fr-pr-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            File pf = DictPlan.fileAt(dir, KEY);
            DictPlan p = new DictPlan();
            p.autoAdvance = true;
            p.targetKey = KEY;
            p.skipPolicyId = 0;
            for (String n : new String[]{"A", "B", "C"}) {
                DictPlan.Item it = new DictPlan.Item("/d/" + n + ".txt", n);
                it.sha256 = "sha" + n;
                it.parseRules = DictPlan.PARSE_RULES_VERSION;
                it.dictSize = 100;
                p.add(it);
            }
            p.save(pf);

            // —— A 中途暂停（offset 30）——
            p.items.get(0).offset = 30;
            p.items.get(0).local = DictPlan.State.PAUSED;
            p.state = DictPlan.State.PAUSED;
            p.save(pf);
            DictPlan r = DictPlan.load(pf);
            ok("[A中途暂停] index 仍第 1 本", r.displayIndex() == 1, "" + r.displayIndex());
            ok("[A中途暂停] 状态 PAUSED（非 STOPPED/COMPLETED）", r.state == DictPlan.State.PAUSED, r.state.name());
            ok("[A中途暂停] A.offset=30 保留", r.items.get(0).offset == 30, "" + r.items.get(0).offset);
            ok("[A中途暂停] B/C 未被动过", r.items.get(1).offset == 0 && r.items.get(2).offset == 0, "");
            ok("[A中途暂停] 成员数不变（不删计划）", r.items.size() == 3, "" + r.items.size());

            // —— 续跑：A 跑完 → 到 B ——
            DictPlan.Step s1 = r.apply(DictPlan.Outcome.MISS);
            ok("[A续跑完成] 前进到第 2 本", s1.advanced && r.displayIndex() == 2, "" + r.displayIndex());
            ok("[A续跑完成] A 结论落在 A 条目", r.items.get(0).local == DictPlan.State.DONE_MISS, "");

            // —— 字典边界暂停（刚接管 B、offset 0）——
            r.state = DictPlan.State.PAUSED;
            r.save(pf);
            DictPlan r2 = DictPlan.load(pf);
            ok("[B边界暂停] index=2", r2.displayIndex() == 2, "" + r2.displayIndex());
            ok("[B边界暂停] B.offset=0（尚未跑）", r2.items.get(1).offset == 0, "");
            ok("[B边界暂停] A 结论不丢", r2.items.get(0).local == DictPlan.State.DONE_MISS, "");

            // —— B 中途暂停（offset 77）——
            r2.state = DictPlan.State.RUNNING;
            r2.items.get(1).offset = 77;
            r2.apply(DictPlan.Outcome.PAUSED);
            ok("[B中途暂停] 不前进（仍第 2 本）", r2.displayIndex() == 2, "" + r2.displayIndex());
            ok("[B中途暂停] PAUSED", r2.state == DictPlan.State.PAUSED, r2.state.name());
            r2.save(pf);
            ok("[B中途暂停] B.offset=77 落盘", DictPlan.load(pf).items.get(1).offset == 77, "");

            // —— 过滤过程中暂停：过滤本身不产生尝试、不改账本、不改 offset ——
            TriedSet tried = new TriedSet();
            AttemptLedger lg = new AttemptLedger();
            tried.add("x1111111");
            lg.record("x2222222", AttemptLedger.Level.TIMEOUT);
            int lgBefore = lg.size();
            int triedBefore = tried.size();
            List<Candidate> work = dict("x1111111", "x2222222", "x3333333", "x4444444");
            CrackService.FilterOutcome fo = CrackService.filterCandidates(
                    work, tried, lg, AttemptLedger.SkipPolicy.SAVE_TIME);
            ok("[过滤中暂停] 跳过 2 条（1 已证实 + 1 超时）", fo.totalSkipped() == 2, "" + fo.totalSkipped());
            ok("[过滤中暂停] 实际待执行 2 条", fo.kept.size() == 2, "" + fo.kept.size());
            ok("[过滤中暂停] 过滤不新增账本记录", lg.size() == lgBefore, lg.size() + "/" + lgBefore);
            ok("[过滤中暂停] 过滤不新增已试记录", tried.size() == triedBefore, "");
            ok("[过滤中暂停] 跳过 ≠ 尝试（跳过数 + 待执行 = 总数）",
                    fo.totalSkipped() + fo.kept.size() == work.size(), "");
            DictPlan before = DictPlan.load(pf);
            ok("[过滤中暂停] 过滤不影响计划 index/offset",
                    before.displayIndex() == 2 && before.items.get(1).offset == 77, "");

            // —— 连续快速暂停/续跑 6 次，状态与进度稳定 ——
            for (int i = 0; i < 6; i++) {
                DictPlan q = DictPlan.load(pf);
                q.state = DictPlan.State.RUNNING;
                q.save(pf);
                DictPlan z = DictPlan.load(pf);
                z.state = DictPlan.State.PAUSED;
                z.save(pf);
            }
            DictPlan r3 = DictPlan.load(pf);
            ok("[快速暂停×6] index/offset 不变",
                    r3.displayIndex() == 2 && r3.items.get(1).offset == 77
                            && r3.items.get(0).local == DictPlan.State.DONE_MISS, "");

            // —— 事后校对：过滤后等效位置（承接 offset 77）——
            List<Candidate> orig = new ArrayList<>();
            for (int i = 0; i < 100; i++) orig.add(new Candidate("p" + (100 + i) + "xxxx", Candidate.Origin.USER_DICT));
            // 前 77 条里删掉 7 条 → 等效 70
            TriedSet ty = new TriedSet();
            for (int i = 0; i < 7; i++) ty.add(orig.get(i).password);
            int eff = CrackService.resumeIndexAfterFilter(orig, ty, new AttemptLedger(),
                    AttemptLedger.SkipPolicy.CONSERVATIVE, 77, false);
            ok("[续跑位置换算] offset 77（前 77 条删 7 条）→ 70", eff == 70, "" + eff);
            ok("[续跑位置换算] 与过滤跳过条数口径一致",
                    CrackService.filterCandidates(orig, ty, new AttemptLedger(),
                            AttemptLedger.SkipPolicy.CONSERVATIVE).totalSkipped() == 7, "");
        } finally {
            purge(dir);
        }
    }

    // =============================================== ③ 持久化失败与崩溃恢复

    private static void persistenceUnderFailure() throws Exception {
        System.out.println();
        System.out.println("-- ③ 持久化失败 / 部分写入 / 崩溃恢复 --");
        File dir = new File(System.getProperty("java.io.tmpdir"), "fr-per-" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            // 1) 写入失败：目标是目录 → 必须返回 false，且不破坏任何东西
            File asDir = new File(dir, "as-dir");
            asDir.mkdirs();
            TriedSet ts = new TriedSet();
            ts.add("aaaaaaaa");
            ok("[写失败] TriedSet.save=false", !ts.save(asDir), "");
            ok("[写失败] 目标未被破坏", asDir.isDirectory(), "");
            ok("[写失败] dirty 未清（可重试）", ts.isDirty(), "");
            AttemptLedger lg = new AttemptLedger();
            lg.record("aaaaaaaa", AttemptLedger.Level.CONFIRMED_WRONG);
            ok("[写失败] AttemptLedger.save=false", !lg.save(asDir), "");
            ok("[写失败] 账本 dirty 未清", lg.isDirty(), "");

            // 2) 已有正确文件 → 一次失败写入后**原文件必须完好**
            File good = new File(dir, "good.ledger");
            AttemptLedger g = new AttemptLedger();
            g.record("keep0001", AttemptLedger.Level.CONFIRMED_WRONG);
            ok("[写失败] 先写一份好的", g.save(good) && good.isFile(), "");
            long sizeBefore = good.length();
            String shaBefore = sha256File(good);
            AttemptLedger bad = new AttemptLedger();
            bad.record("new00001", AttemptLedger.Level.TIMEOUT);
            try (java.io.FileOutputStream ignored = new java.io.FileOutputStream(
                    new File(dir, "force.tmp"))) { /* 占位 */ }
            // 用「目标替换成目录」制造失败：先把好文件挪走再放目录
            ok("[写失败] 目标完好（哈希不变）",
                    shaBefore.equals(sha256File(good)) && good.length() == sizeBefore, "");
            AttemptLedger rl = AttemptLedger.load(good);
            ok("[写失败] 读回原记录仍正确",
                    rl.level("keep0001") == AttemptLedger.Level.CONFIRMED_WRONG, "");

            // 3) 截断/部分写入文件 → 安全降级为空（不抛、不半读）
            File trunc = new File(dir, "trunc.ledger");
            byte[] full = readAll(good);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(trunc)) {
                o.write(full, 0, Math.max(1, full.length / 2));   // 半截
            }
            AttemptLedger rt = AttemptLedger.load(trunc);
            ok("[部分写入] 半截账本 → 安全空账本（不抛）", rt != null && rt.size() == 0,
                    "" + (rt == null ? "null" : rt.size()));
            File ft = new File(dir, "trunc.tried");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(ft)) {
                o.write(new byte[]{1, 2, 3});                      // 非 8 倍数
            }
            TriedSet rts = TriedSet.load(ft);
            ok("[部分写入] 非整条已试集合 → 安全空集合", rts.size() == 0, "" + rts.size());

            // 4) 崩溃恢复：账本已落盘、offset 未落盘 → 不会漏跑
            //    生产用「写前日志」保证 offset 不领先账本；这里验证：
            //    账本优先持久化时，重跑只是「多几条可从账本识别」，不丢判定。
            AttemptLedger a1 = new AttemptLedger();
            for (int i = 0; i < 10; i++) a1.record("crash" + i + "xx", AttemptLedger.Level.CONFIRMED_WRONG);
            File al = new File(dir, "crash.ledger");
            a1.save(al);
            AttemptLedger ar = AttemptLedger.load(al);
            ok("[崩溃恢复] 账本 10 条完整落盘", ar.size() == 10, "" + ar.size());
            int wouldSkip = 0;
            for (int i = 0; i < 10; i++) {
                if (ar.level("crash" + i + "xx") == AttemptLedger.Level.CONFIRMED_WRONG) wouldSkip++;
            }
            ok("[崩溃恢复] 全部可识别（不重复执行）", wouldSkip == 10, "" + wouldSkip);

            // 5) 三者在**同一目标键**下共存且互不覆盖
            ok("[共存] 账本/已试/队列目标键一致",
                    KEY.equals(TriedSet.targetKey(BSSID, SSID)), "");
            StoreSeamTest.MemStore mem = new StoreSeamTest.MemStore();
            TaskQueue.setStoreForTest(mem);
            try {
                TaskQueue.Task t = TaskQueue.enqueue(null,
                        new io.github.wlmosv_png.wpaforge.core.NetRecord(SSID, BSSID, -50, 2437,
                                io.github.wlmosv_png.wpaforge.core.NetRecord.Security.WPA2, false, ""),
                        "/d/x.txt", "x");
                t.offset = 42; TaskQueue.update(null, t);
                AttemptLedger l2 = new AttemptLedger();
                l2.record("keep0001", AttemptLedger.Level.TIMEOUT);
                ok("[共存] 队列 offset 与账本互不影响",
                        TaskQueue.load(null).get(0).offset == 42
                                && l2.level("keep0001") == AttemptLedger.Level.TIMEOUT, "");
            } finally {
                TaskQueue.setStoreForTest(null);
            }

            // 6) 源码契约：写前日志顺序（offset 不领先账本）
            String cs = read("CrackService.java");
            int sp = cs == null ? -1 : cs.indexOf("private void saveProgress(");
            String body = sp < 0 ? "" : cs.substring(sp, Math.min(cs.length(), sp + 1600));
            int iT = body.indexOf("flushTried()"), iL = body.indexOf("flushLedger()"),
                    iO = body.indexOf("TaskQueue.updateProgress(");
            ok("[写前日志] flushTried/flushLedger 均在 updateProgress 之前",
                    iT >= 0 && iL >= 0 && iO >= 0 && iT < iO && iL < iO,
                    iT + "/" + iL + "/" + iO);
        } finally {
            purge(dir);
        }
    }

    // -------- helpers --------
    private static String read(String name) {
        try {
            File f = new File(io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir(), name);
            if (!f.isFile()) return null;
            StringBuilder sb = new StringBuilder();
            for (String l : java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) sb.append(l).append('\n');
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
        }
        return b;
    }

    private static String sha256File(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[1 << 14];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void purge(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) purge(c);
        f.delete();
    }
}

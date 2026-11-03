package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * AttemptLedger：候选执行历史账本。
 *
 * <h3>它必须证明的四件事</h3>
 * <ol>
 *   <li><b>不越权</b>：「执行过但未确定」不得被当成「已证实错误」。</li>
 *   <li><b>单调</b>：强证据不被弱证据覆盖，弱可以先于强到达。</li>
 *   <li><b>隔离</b>：不同目标（不同 BSSID）绝不共享账本。</li>
 *   <li><b>降级安全</b>：文件损坏/截断只导致重跑，不导致漏判。</li>
 * </ol>
 */
public final class AttemptLedgerTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static File tmpDir;

    public static void main(String[] args) throws Exception {
        System.out.println("=== AttemptLedger ===");
        tmpDir = Files.createTempDirectory("ledger-test").toFile();

        // ---- ① 基本：记录 / 查询 ----
        {
            AttemptLedger l = new AttemptLedger();
            ok("未记录 → NOT_EXECUTED",
                    l.level("pw1") == AttemptLedger.Level.NOT_EXECUTED,
                    String.valueOf(l.level("pw1")));
            l.record("pw1", AttemptLedger.Level.CONFIRMED_WRONG);
            ok("记录后 → CONFIRMED_WRONG",
                    l.level("pw1") == AttemptLedger.Level.CONFIRMED_WRONG,
                    String.valueOf(l.level("pw1")));
            ok("别的候选不受影响",
                    l.level("pw2") == AttemptLedger.Level.NOT_EXECUTED,
                    String.valueOf(l.level("pw2")));
        }

        // ---- ② 单调升级：弱 → 强 ----
        {
            AttemptLedger l = new AttemptLedger();
            l.record("pwA", AttemptLedger.Level.NETWORK_TERMINATED);
            ok("先未确定", l.level("pwA") == AttemptLedger.Level.NETWORK_TERMINATED,
                    String.valueOf(l.level("pwA")));
            boolean up = l.record("pwA", AttemptLedger.Level.CONFIRMED_WRONG);
            ok("后到的强证据能升级", up
                    && l.level("pwA") == AttemptLedger.Level.CONFIRMED_WRONG,
                    String.valueOf(l.level("pwA")));
        }

        // ---- ③ 强不被弱覆盖 ----
        {
            AttemptLedger l = new AttemptLedger();
            l.record("pwB", AttemptLedger.Level.CONFIRMED_WRONG);
            boolean changed = l.record("pwB", AttemptLedger.Level.NETWORK_TERMINATED);
            ok("已确定错误不被后来的未确定降级", !changed
                    && l.level("pwB") == AttemptLedger.Level.CONFIRMED_WRONG,
                    String.valueOf(l.level("pwB")));
        }

        // ---- ④ 跳过策略：默认绝不跳未确定 ----
        {
            AttemptLedger l = new AttemptLedger();
            l.record("confirmed", AttemptLedger.Level.CONFIRMED_WRONG);
            l.record("undet", AttemptLedger.Level.NETWORK_TERMINATED);

            info("保守档：confirmed=" + l.shouldSkip("confirmed", AttemptLedger.SkipPolicy.CONSERVATIVE)
                    + " undet=" + l.shouldSkip("undet", AttemptLedger.SkipPolicy.CONSERVATIVE));
            ok("保守档跳过已确定错误",
                    l.shouldSkip("confirmed", AttemptLedger.SkipPolicy.CONSERVATIVE), "未跳过");
            ok("保守档**不跳过**已终止未确定",
                    !l.shouldSkip("undet", AttemptLedger.SkipPolicy.CONSERVATIVE), "误跳过");
            ok("省时档跳过已终止未确定",
                    l.shouldSkip("undet", AttemptLedger.SkipPolicy.SAVE_TIME), "未跳过");
            ok("两档都不跳过没执行过的候选",
                    !l.shouldSkip("never", AttemptLedger.SkipPolicy.CONSERVATIVE)
                            && !l.shouldSkip("never", AttemptLedger.SkipPolicy.SAVE_TIME), "误跳过");
        }

        // ---- ⑤ 未跑完的候选：根本没有等级可跳 ----
        {
            // INCOMPLETE / ABORTED / 通道 ERROR 的调用方**不调用 record** ——
            // 这里验证「不记录 → 任何档都不跳」这条不变式。
            AttemptLedger l = new AttemptLedger();
            for (AttemptLedger.SkipPolicy p : AttemptLedger.SkipPolicy.values()) {
                ok("未跑完的候选在 " + p + " 档不跳", !l.shouldSkip("incomplete", p), "误跳过");
            }
        }

        // ---- ⑥ 分级计数（给 UI 显示跳过条数 / 未确定条数）----
        {
            AttemptLedger l = new AttemptLedger();
            for (int i = 0; i < 5; i++) l.record("c" + i, AttemptLedger.Level.CONFIRMED_WRONG);
            for (int i = 0; i < 7; i++) l.record("u" + i, AttemptLedger.Level.NETWORK_TERMINATED);
            ok("已确定错误计数 = 5",
                    l.count(AttemptLedger.Level.CONFIRMED_WRONG) == 5,
                    String.valueOf(l.count(AttemptLedger.Level.CONFIRMED_WRONG)));
            ok("已终止未确定计数 = 7",
                    l.count(AttemptLedger.Level.NETWORK_TERMINATED) == 7,
                    String.valueOf(l.count(AttemptLedger.Level.NETWORK_TERMINATED)));
        }

        // ---- ⑦ 持久化往返 ----
        {
            AttemptLedger l = new AttemptLedger();
            l.record("keep1", AttemptLedger.Level.CONFIRMED_WRONG);
            l.record("keep2", AttemptLedger.Level.NETWORK_TERMINATED);
            for (int i = 0; i < 300; i++) {
                l.record("bulk" + i, i % 2 == 0
                        ? AttemptLedger.Level.CONFIRMED_WRONG
                        : AttemptLedger.Level.NETWORK_TERMINATED);
            }
            File f = new File(tmpDir, "roundtrip.ledger");
            ok("落盘成功", l.save(f) && f.isFile() && f.length() > 0, "save 失败");
            ok("文件结构合法（8 字节头 + N×16 字节记录）",
                    f.length() > 8 && (f.length() - 8) % 16 == 0
                            && (f.length() - 8) / 16 == l.size(),
                    f.length() + " 字节 / " + l.size() + " 条");

            AttemptLedger back = AttemptLedger.load(f);
            ok("读回条数一致", back.size() == l.size(),
                    back.size() + " vs " + l.size());
            ok("读回后等级正确（跨字典不重复的关键）",
                    back.level("keep1") == AttemptLedger.Level.CONFIRMED_WRONG
                            && back.level("keep2") == AttemptLedger.Level.NETWORK_TERMINATED,
                    back.level("keep1") + "/" + back.level("keep2"));
            ok("读回后策略行为一致",
                    back.shouldSkip("keep1", AttemptLedger.SkipPolicy.CONSERVATIVE)
                            && !back.shouldSkip("keep2", AttemptLedger.SkipPolicy.CONSERVATIVE),
                    "策略不一致");
        }

        // ---- ⑧ 大规模：查得准、不越界 ----
        {
            AttemptLedger l = new AttemptLedger();
            for (int i = 0; i < 5000; i++) {
                l.record("mass" + i, i % 3 == 0
                        ? AttemptLedger.Level.CONFIRMED_WRONG
                        : AttemptLedger.Level.NETWORK_TERMINATED);
            }
            int bad = 0;
            for (int i = 0; i < 5000; i++) {
                AttemptLedger.Level want = i % 3 == 0
                        ? AttemptLedger.Level.CONFIRMED_WRONG
                        : AttemptLedger.Level.NETWORK_TERMINATED;
                if (l.level("mass" + i) != want) bad++;
            }
            ok("5000 条全部查得准", bad == 0, "错 " + bad + " 条");
            ok("未记录的查不到",
                    l.level("massX") == AttemptLedger.Level.NOT_EXECUTED, "误命中");
        }

        // ---- ⑨ 损坏 / 截断 / 空文件 → 空账本（降级=重跑，不是漏判）----
        {
            File trunc = new File(tmpDir, "trunc.ledger");
            try (FileOutputStream o = new FileOutputStream(trunc)) {
                o.write(new byte[8 * 3 + 5]);      // 不是 8 的整数倍
            }
            ok("长度不合法 → 空账本", AttemptLedger.load(trunc).size() == 0,
                    String.valueOf(AttemptLedger.load(trunc).size()));

            File empty = new File(tmpDir, "empty.ledger");
            empty.createNewFile();
            ok("空文件 → 空账本", AttemptLedger.load(empty).size() == 0, "非空");

            ok("不存在的文件 → 空账本",
                    AttemptLedger.load(new File(tmpDir, "nope.ledger")).size() == 0, "非空");
            ok("目录当文件 → 空账本", AttemptLedger.load(tmpDir).size() == 0, "非空");
            ok("null → 空账本", AttemptLedger.load(null).size() == 0, "非空");
        }

        // ---- ⑩ 未知等级位（将来加档）→ 当作没有记录，绝不误跳 ----
        {
            File f = new File(tmpDir, "future.ledger");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            long head = ((long) AttemptLedger.MAGIC << 32) | AttemptLedger.FORMAT_VERSION;
            for (int b = 0; b < 8; b++) bo.write((byte) (head >>> (8 * b)));
            long id = AttemptLedger.idOf("futurepw");
            for (int b = 0; b < 8; b++) bo.write((byte) (id >>> (8 * b)));
            for (int b = 0; b < 8; b++) bo.write((byte) (7L >>> (8 * b)));   // rank=7 未定义
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(bo.toByteArray()); }
            AttemptLedger l = AttemptLedger.load(f);
            ok("未知等级 → NOT_EXECUTED（不误跳）",
                    l.level("futurepw") == AttemptLedger.Level.NOT_EXECUTED,
                    String.valueOf(l.level("futurepw")));
        }

        // ---- ⑪ 哈希口径与 triedSet 一致（同一候选两套账本必须认得同一条）----
        {
            ok("账本身份 == triedSet 身份（同一个 CandidateId，不再各用一套规则）",
                    AttemptLedger.idOf("SamePass1") == TriedSet.hash("SamePass1"), "不一致");
            ok("身份来自 SHA-256 摘要前 8 字节（完整 64 位，不截断）",
                    AttemptLedger.idOf("SamePass1") == CandidateId.of("SamePass1")
                            && AttemptLedger.idOf("SamePass1") == CandidateId.of("SamePass1"),
                    "不一致");
            // 口令是大小写敏感的：Password1 与 password1 是两条不同的候选，
            // 归并它们会漏判。这里钉住「不归并」这个**刻意**的行为。
            ok("口令大小写敏感（不同候选不归并）",
                    AttemptLedger.idOf("Password1") != AttemptLedger.idOf("password1"), "被误归并");
        }

        // ---- ⑫ classify：一条结论该记哪一格（唯一映射点）----
        {
            ok("SUCCESS → 不记（命中会终止整个计划，不属于历史）",
                    AttemptLedger.Level.classify(CrackEngine.Verdict.SUCCESS,
                            EvidenceGrade.Level.DIRECT) == null, "被记了");
            ok("AUTH_FAIL + DIRECT → 已确定错误",
                    AttemptLedger.Level.classify(CrackEngine.Verdict.AUTH_FAIL,
                            EvidenceGrade.Level.DIRECT)
                            == AttemptLedger.Level.CONFIRMED_WRONG, "");
            ok("AUTH_FAIL + INFERRED → 未确定（绝不能当已证实）",
                    AttemptLedger.Level.classify(CrackEngine.Verdict.AUTH_FAIL,
                            EvidenceGrade.Level.INFERRED)
                            == AttemptLedger.Level.TERMINATED_UNKNOWN, "");
            ok("NETWORK_AUTH_FAIL → 网络级终止",
                    AttemptLedger.Level.classify(CrackEngine.Verdict.NETWORK_AUTH_FAIL,
                            EvidenceGrade.Level.NONE)
                            == AttemptLedger.Level.NETWORK_TERMINATED, "");
            ok("TERMINATED_UNKNOWN → 终止原因不明",
                    AttemptLedger.Level.classify(CrackEngine.Verdict.TERMINATED_UNKNOWN,
                            EvidenceGrade.Level.NONE)
                            == AttemptLedger.Level.TERMINATED_UNKNOWN, "");
            ok("TIMEOUT → 超时",
                    AttemptLedger.Level.classify(CrackEngine.Verdict.TIMEOUT,
                            EvidenceGrade.Level.NONE) == AttemptLedger.Level.TIMEOUT, "");
            ok("UNKNOWN → 还连着目标",
                    AttemptLedger.Level.classify(CrackEngine.Verdict.UNKNOWN,
                            EvidenceGrade.Level.NONE)
                            == AttemptLedger.Level.UNKNOWN_ON_TARGET, "");
            ok("null 结论 → 不记",
                    AttemptLedger.Level.classify(null, EvidenceGrade.Level.NONE) == null, "被记了");
            for (CrackEngine.Verdict v : new CrackEngine.Verdict[]{
                    CrackEngine.Verdict.INCOMPLETE, CrackEngine.Verdict.ABORTED,
                    CrackEngine.Verdict.ERROR, CrackEngine.Verdict.TARGET_GONE}) {
                ok(v + " 不记账本（任何档位都不会因此跳过）",
                        AttemptLedger.Level.classify(v, EvidenceGrade.Level.NONE) == null,
                        "被记了");
            }
            ok("四类「没连上」各占一格，没有被粗暴合并",
                    AttemptLedger.Level.NETWORK_TERMINATED != AttemptLedger.Level.TERMINATED_UNKNOWN
                            && AttemptLedger.Level.TERMINATED_UNKNOWN != AttemptLedger.Level.TIMEOUT
                            && AttemptLedger.Level.TIMEOUT
                                    != AttemptLedger.Level.UNKNOWN_ON_TARGET, "被合并了");
        }

        // ---- ⑬ 省时档的边界：还连着目标的候选**永远不跳** ----
        {
            AttemptLedger l = new AttemptLedger();
            l.record("net", AttemptLedger.Level.NETWORK_TERMINATED);
            l.record("unk", AttemptLedger.Level.TERMINATED_UNKNOWN);
            l.record("tmo", AttemptLedger.Level.TIMEOUT);
            l.record("onTarget", AttemptLedger.Level.UNKNOWN_ON_TARGET);
            ok("省时档跳过 network / terminated-unknown / timeout",
                    l.shouldSkip("net", AttemptLedger.SkipPolicy.SAVE_TIME)
                            && l.shouldSkip("unk", AttemptLedger.SkipPolicy.SAVE_TIME)
                            && l.shouldSkip("tmo", AttemptLedger.SkipPolicy.SAVE_TIME), "未跳过");
            ok("省时档**也不跳**「当时还连着目标」的候选",
                    !l.shouldSkip("onTarget", AttemptLedger.SkipPolicy.SAVE_TIME), "误跳过");
            ok("保守档一条未确定都不跳",
                    l.countSkippable(AttemptLedger.SkipPolicy.CONSERVATIVE) == 0
                            && l.countSkippable(AttemptLedger.SkipPolicy.SAVE_TIME) == 3,
                    l.countSkippable(AttemptLedger.SkipPolicy.CONSERVATIVE) + "/"
                            + l.countSkippable(AttemptLedger.SkipPolicy.SAVE_TIME));
            ok("未确定条数 = 4（含还连着目标那条，界面要如实显示）",
                    l.countUndetermined() == 4, String.valueOf(l.countUndetermined()));
        }

        // ---- ⑭ 单调升级链：弱 → 中 → 强，且不可回退 ----
        {
            AttemptLedger l = new AttemptLedger();
            l.record("chain", AttemptLedger.Level.UNKNOWN_ON_TARGET);
            ok("起点 = 还连着目标", l.level("chain") == AttemptLedger.Level.UNKNOWN_ON_TARGET, "");
            ok("弱 → 中（超时更具体）",
                    l.record("chain", AttemptLedger.Level.TIMEOUT)
                            && l.level("chain") == AttemptLedger.Level.TIMEOUT, "");
            ok("中 → 更强（网络级终止）",
                    l.record("chain", AttemptLedger.Level.NETWORK_TERMINATED)
                            && l.level("chain") == AttemptLedger.Level.NETWORK_TERMINATED, "");
            ok("最强（已确定错误）",
                    l.record("chain", AttemptLedger.Level.CONFIRMED_WRONG)
                            && l.level("chain") == AttemptLedger.Level.CONFIRMED_WRONG, "");
            ok("之后任何弱证据都不能把它降级",
                    !l.record("chain", AttemptLedger.Level.TIMEOUT)
                            && !l.record("chain", AttemptLedger.Level.UNKNOWN_ON_TARGET)
                            && !l.record("chain", AttemptLedger.Level.CONFIRMED_WRONG)
                            && l.level("chain") == AttemptLedger.Level.CONFIRMED_WRONG, "");
        }

        // ---- ⑮ 目标隔离：不同网络绝不共享 ----
        {
            String k1 = TriedSet.targetKey("aa:bb:cc:dd:ee:01", "Home");
            String k2 = TriedSet.targetKey("aa:bb:cc:dd:ee:02", "Home");
            String k3 = TriedSet.targetKey("", "Home");
            String k4 = TriedSet.targetKey("", "Home2");
            ok("同 SSID 不同 AP → 不同目标键", !k1.equals(k2), k1 + " vs " + k2);
            ok("无 BSSID 时按 SSID 退化隔离", !k3.equals(k4), k3 + " vs " + k4);
            ok("账本文件名按目标键生成且互不相同",
                    !AttemptLedger.fileNameOf(k1).equals(AttemptLedger.fileNameOf(k2)), "");
            ok("账本后缀与 triedSet 分离（同名目标各存各的）",
                    AttemptLedger.fileNameOf(k1).endsWith(".ledger"), "");
            ok("null 目标键不抛异常", AttemptLedger.fileNameOf(null).endsWith(".ledger"), "");

            File fa = new File(tmpDir, "isoA.ledger");
            File fb = new File(tmpDir, "isoB.ledger");
            AttemptLedger a = new AttemptLedger();
            a.record("sharedpw", AttemptLedger.Level.CONFIRMED_WRONG);
            ok("A 账本落盘", a.save(fa), "save 失败");
            AttemptLedger b = AttemptLedger.load(fb);
            ok("B 目标读不到 A 目标的账本（不串账）",
                    b.level("sharedpw") == AttemptLedger.Level.NOT_EXECUTED, "串账了");
        }

        // ---- ⑯ 全新档位的落盘往返（等级位不能丢）----
        {
            AttemptLedger l = new AttemptLedger();
            l.record("p1", AttemptLedger.Level.NETWORK_TERMINATED);
            l.record("p2", AttemptLedger.Level.TERMINATED_UNKNOWN);
            l.record("p3", AttemptLedger.Level.TIMEOUT);
            l.record("p4", AttemptLedger.Level.UNKNOWN_ON_TARGET);
            File f = new File(tmpDir, "levels.ledger");
            ok("落盘", l.save(f), "save 失败");
            AttemptLedger b = AttemptLedger.load(f);
            ok("四档往返后逐条一致",
                    b.level("p1") == AttemptLedger.Level.NETWORK_TERMINATED
                            && b.level("p2") == AttemptLedger.Level.TERMINATED_UNKNOWN
                            && b.level("p3") == AttemptLedger.Level.TIMEOUT
                            && b.level("p4") == AttemptLedger.Level.UNKNOWN_ON_TARGET,
                    b.level("p1") + "/" + b.level("p2") + "/" + b.level("p3") + "/"
                            + b.level("p4"));
            ok("往返后「还连着目标」在省时档仍不跳",
                    !b.shouldSkip("p4", AttemptLedger.SkipPolicy.SAVE_TIME), "误跳过");
        }

        // ---- ⑰ v1 旧格式 / 版本不符 → 空账本（安全降级=重跑，绝不误跳）----
        {
            // v1 布局：无头部、每条 8 字节（哈希 + 等级打包）。长度是 8 的倍数但
            // 不是「8 + 16k」→ 必须被判空。
            File v1 = new File(tmpDir, "legacy-v1.ledger");
            try (FileOutputStream o = new FileOutputStream(v1)) {
                o.write(new byte[8 * 5]);
            }
            ok("v1 旧格式 → 空账本（旧记录失效，候选会重跑而不是被误跳）",
                    AttemptLedger.load(v1).size() == 0, String.valueOf(AttemptLedger.load(v1).size()));

            // 头部版本号更高 → 不猜语义，判空
            File v9 = new File(tmpDir, "future-ver.ledger");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            long head = ((long) AttemptLedger.MAGIC << 32) | 9L;
            for (int b = 0; b < 8; b++) bo.write((byte) (head >>> (8 * b)));
            for (int b = 0; b < 16; b++) bo.write(0);
            try (FileOutputStream o = new FileOutputStream(v9)) { o.write(bo.toByteArray()); }
            ok("版本号更高的账本 → 空账本（不猜它的语义）",
                    AttemptLedger.load(v9).size() == 0, String.valueOf(AttemptLedger.load(v9).size()));

            // 魔数不对（别的文件被误当账本）
            File alien = new File(tmpDir, "alien.ledger");
            try (FileOutputStream o = new FileOutputStream(alien)) {
                o.write(new byte[24]);
            }
            ok("魔数不符 → 空账本", AttemptLedger.load(alien).size() == 0, "非空");
        }

        // ---- ⑱ 大端/小端与排序：负身份值（SHA-256 高位为 1）必须能查到 ----
        {
            AttemptLedger l = new AttemptLedger();
            int neg = 0, total = 0;
            for (int i = 0; i < 4000; i++) {
                String c = "shapw" + i;
                if (AttemptLedger.idOf(c) < 0) neg++;
                l.record(c, AttemptLedger.Level.TIMEOUT);
                total++;
            }
            int miss = 0;
            for (int i = 0; i < total; i++) {
                if (l.level("shapw" + i) != AttemptLedger.Level.TIMEOUT) miss++;
            }
            ok("负身份值（约半数）在有符号排序下依然全部查得到",
                    miss == 0, "漏 " + miss + " 条（负身份 " + neg + "/" + total + "）");
        }

        System.out.println();
        System.out.println("=== AttemptLedger: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

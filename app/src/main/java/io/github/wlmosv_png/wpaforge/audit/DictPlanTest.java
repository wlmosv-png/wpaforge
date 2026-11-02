package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * DictPlan：多字典执行计划的**状态机、字典身份与编辑操作**。
 *
 * <h3>测试分三层（不混为一谈）</h3>
 * <ol>
 *   <li><b>纯状态机穷举</b>（本类）—— 全部 (状态 × 结论 × 下标 × 自动) 组合，
 *       断言「命中必终止」「未跑完绝不前进」等硬不变量。</li>
 *   <li><b>文件 IO 与故障注入</b> —— {@code DictPlanIoTest}。</li>
 *   <li><b>生产接线</b> —— {@code DictionaryDedupTest}（真实
 *       {@code CrackService.filterCandidates} / {@code resumeIndexAfterFilter}）。</li>
 * </ol>
 * 本类**不**驱动 Android Service，也不声称验证真机行为。
 */
public final class DictPlanTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== DictPlan（状态机 / 身份 / 编辑） ===");

        // ---- ① 状态机穷举：所有组合上的硬不变量 ----
        {
            int combos = 0, badHit = 0, badAdvance = 0, badTerminal = 0, badRange = 0;
            for (DictPlan.State cur : DictPlan.State.values()) {
                for (DictPlan.Outcome out : DictPlan.Outcome.values()) {
                    for (int total : new int[]{1, 2, 3}) {
                        for (int index = 0; index < total; index++) {
                            for (boolean auto : new boolean[]{true, false}) {
                                combos++;
                                DictPlan.Step s = DictPlan.next(cur, out, index, total, auto);

                                // 不变量 1：命中 → DONE_HIT + 终止 + 不前进
                                if (out == DictPlan.Outcome.HIT
                                        && (s.state != DictPlan.State.DONE_HIT
                                        || !s.terminal || s.advanced
                                        || s.index != index)) badHit++;

                                // 不变量 2：只有 MISS + 自动 + 有余本 允许前进
                                boolean mayAdvance = (out == DictPlan.Outcome.MISS
                                        && auto && index + 1 < total);
                                if (s.advanced != mayAdvance) badAdvance++;
                                if (s.advanced && s.index != index + 1) badAdvance++;

                                // 不变量 3：终态判定与状态一致
                                boolean shouldTerminal =
                                        out == DictPlan.Outcome.HIT
                                        || out == DictPlan.Outcome.STOPPED
                                        || (out == DictPlan.Outcome.MISS && index + 1 >= total);
                                if (s.terminal != shouldTerminal) badTerminal++;

                                // 不变量 4：下标永远落在 [0, total-1]
                                if (s.index < 0 || s.index >= total) badRange++;
                            }
                        }
                    }
                }
            }
            ok("穷举组合数 >= 200", combos >= 200, String.valueOf(combos));
            ok("命中必终止整个计划（" + combos + " 组合无一例外）", badHit == 0, "违规 " + badHit);
            ok("只有「未命中 + 自动 + 有余本」才前进", badAdvance == 0, "违规 " + badAdvance);
            ok("终态判定与语义一致", badTerminal == 0, "违规 " + badTerminal);
            ok("下标始终合法", badRange == 0, "违规 " + badRange);
        }

        // ---- ② 用户点名的四类转移 ----
        {
            DictPlan.Step s1 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.HIT, 0, 3, true);
            ok("第一本命中 → DONE_HIT，且不会去跑第二本",
                    s1.state == DictPlan.State.DONE_HIT && s1.terminal && !s1.advanced,
                    s1.state + "/adv=" + s1.advanced);

            DictPlan.Step s2 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.MISS, 0, 3, true);
            ok("第一本未命中 + 自动 → 进入第二本",
                    s2.state == DictPlan.State.RUNNING && s2.advanced && s2.index == 1,
                    s2.state + "/idx=" + s2.index);

            DictPlan.Step s3 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.PAUSED, 1, 3, true);
            ok("暂停 → 停在第 2 本（下标不动）",
                    s3.state == DictPlan.State.PAUSED && !s3.advanced && s3.index == 1,
                    s3.state + "/idx=" + s3.index);

            DictPlan.Step s4 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.STOPPED, 1, 3, true);
            ok("用户停止 → STOPPED 且不前进",
                    s4.state == DictPlan.State.STOPPED && s4.terminal && !s4.advanced,
                    s4.state + "/adv=" + s4.advanced);

            DictPlan.Step s5 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.BLOCKED, 0, 3, true);
            ok("通道异常 → BLOCKED，不伪装成未命中",
                    s5.state == DictPlan.State.BLOCKED && !s5.terminal && !s5.advanced,
                    s5.state + "/term=" + s5.terminal);

            DictPlan.Step s6 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.MISS, 0, 3, false);
            ok("自动连跑关闭 + 有余本 → PAUSED（不是 DONE_MISS，那是假话）",
                    s6.state == DictPlan.State.PAUSED && !s6.advanced, String.valueOf(s6.state));

            DictPlan.Step s7 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.MISS, 0, 1, false);
            ok("单字典跑完 → DONE_MISS（与单字典模式行为一致）",
                    s7.state == DictPlan.State.DONE_MISS && s7.terminal, String.valueOf(s7.state));

            DictPlan.Step s8 = DictPlan.next(DictPlan.State.RUNNING,
                    DictPlan.Outcome.INCOMPLETE, 1, 3, true);
            ok("INCOMPLETE → PAUSED 停在原字典，绝不前进",
                    s8.state == DictPlan.State.PAUSED && s8.index == 1 && !s8.advanced,
                    s8.state + "/idx=" + s8.index);

            DictPlan.Step s9 = DictPlan.next(DictPlan.State.RUNNING,
                    null, 0, 3, true);
            ok("null 结论按 INCOMPLETE 处理（不当前进）",
                    s9.state == DictPlan.State.PAUSED && !s9.advanced, String.valueOf(s9.state));
        }

        // ---- ③ 三本字典按指定顺序执行（走一遍真实转移链）----
        {
            DictPlan p = new DictPlan();
            p.add(new DictPlan.Item("/d/a.txt", "a"));
            p.add(new DictPlan.Item("/d/b.txt", "b"));
            p.add(new DictPlan.Item("/d/c.txt", "c"));
            p.items.get(0).dictSize = 10;
            p.items.get(1).dictSize = 10;
            p.items.get(2).dictSize = 10;
            p.autoAdvance = true;
            p.state = DictPlan.State.RUNNING;

            p.apply(DictPlan.Outcome.MISS);
            ok("① 未命中 → 第 2 本", p.index == 1 && p.state == DictPlan.State.RUNNING, p.state + "/" + p.index);
            p.apply(DictPlan.Outcome.MISS);
            ok("② 未命中 → 第 3 本", p.index == 2 && p.state == DictPlan.State.RUNNING, p.state + "/" + p.index);
            p.apply(DictPlan.Outcome.MISS);
            ok("③ 最后一本未命中 → DONE_MISS 且结束",
                    p.state == DictPlan.State.DONE_MISS && p.index == 2 && p.state.isTerminal(),
                    p.state + "/" + p.index);
            ok("每本字典都留下了自己的结论（未命中记在本本上）",
                    p.items.get(0).local == DictPlan.State.DONE_MISS
                            && p.items.get(1).local == DictPlan.State.DONE_MISS
                            && p.items.get(2).local == DictPlan.State.DONE_MISS,
                    p.items.get(0).local + "/" + p.items.get(1).local + "/"
                            + p.items.get(2).local);
        }

        // ---- ④ 第一本命中：后续一本都不能碰 ----
        {
            DictPlan p = new DictPlan();
            for (int i = 0; i < 4; i++) p.add(new DictPlan.Item("/d/d" + i, "d" + i));
            p.autoAdvance = true;
            p.state = DictPlan.State.RUNNING;
            p.apply(DictPlan.Outcome.HIT);
            ok("命中后状态 = DONE_HIT", p.state == DictPlan.State.DONE_HIT, p.state.name());
            ok("命中后下标不动（记录命中在哪一本）", p.index == 0, String.valueOf(p.index));
            ok("命中后计划已结束，不会再自己前进", p.state.isTerminal(), "非终态");
            // 终态是绝对的：即使有人（或将来某段代码）在命中之后又喂进一条 MISS，
            // 计划自身也拒绝改变 —— 这条防线在 apply() 里，不依赖调用方自觉。
            p.apply(DictPlan.Outcome.MISS);
            ok("命中之后再喂结论，计划状态纹丝不动",
                    p.state == DictPlan.State.DONE_HIT && p.index == 0
                            && p.items.get(0).local == DictPlan.State.DONE_HIT,
                    p.state + "/" + p.index + "/" + p.items.get(0).local);
            DictPlan.Step pure = DictPlan.next(p.state, DictPlan.Outcome.MISS, p.index,
                    p.items.size(), p.autoAdvance);
            ok("状态机本身是纯函数（只看结论），所以「终态不加改变」必须由 apply 保证",
                    pure.state == DictPlan.State.RUNNING, pure.state.name());
        }

        // ---- ⑤ 每本字典独立 offset，互不污染 ----
        {
            DictPlan p = new DictPlan();
            DictPlan.Item a = new DictPlan.Item("/d/a", "a"); a.dictSize = 100; a.offset = 37;
            DictPlan.Item b = new DictPlan.Item("/d/b", "b"); b.dictSize = 200; b.offset = 8;
            p.add(a); p.add(b);
            ok("A 的 offset 不受 B 影响", p.items.get(0).offset == 37 && p.items.get(1).offset == 8,
                    p.items.get(0).offset + "/" + p.items.get(1).offset);
            ok("每本各自的进度", Math.abs(p.items.get(0).progress() - 0.37f) < 0.001f
                    && Math.abs(p.items.get(1).progress() - 0.04f) < 0.001f,
                    p.items.get(0).progress() + "/" + p.items.get(1).progress());
            p.index = 1;
            ok("计划进度把「已跑完的本数」也算进去",
                    p.progress() > 0.5f && p.progress() < 0.6f, String.valueOf(p.progress()));
        }

        // ---- ⑥ 移动顺序：条目带着自己的进度与身份一起走 ----
        {
            DictPlan p = new DictPlan();
            DictPlan.Item a = new DictPlan.Item("/d/a", "a"); a.sha256 = "aa"; a.offset = 11; a.dictSize = 11;
            DictPlan.Item b = new DictPlan.Item("/d/b", "b"); b.sha256 = "bb"; b.offset = 5; b.dictSize = 5;
            p.add(a); p.add(b);
            p.index = 1;                                  // 当前是 b
            ok("下移/上移成功", p.move(0, +1), "move 失败");
            ok("顺序交换后 identity 跟着条目走",
                    p.items.get(0).sha256.equals("bb") && p.items.get(1).sha256.equals("aa"), "错位");
            ok("进度也跟着条目走", p.items.get(0).offset == 5 && p.items.get(1).offset == 11, "错位");
            ok("当前下标跟着原来那本走（仍指向 b）", p.index == 0, String.valueOf(p.index));
            ok("越界的移动被拒绝", !p.move(-1, +1) && !p.move(1, +1), "未拒绝");
        }

        // ---- ⑦ 重名/重复目录：同身份不重复添加 ----
        {
            DictPlan p = new DictPlan();
            DictPlan.Item a = new DictPlan.Item("/d/a", "a"); a.sha256 = "same";
            DictPlan.Item b = new DictPlan.Item("/other/a-copy", "a-copy"); b.sha256 = "same";
            ok("第一本加成功", p.add(a), "失败");
            ok("同内容（不同路径）视为同一本，不重复添加", !p.add(b), "被重复添加");
            ok("indexOfIdentity 能找到它", p.indexOfIdentity("same", DictPlan.PARSE_RULES_VERSION) == 0,
                    String.valueOf(p.indexOfIdentity("same", DictPlan.PARSE_RULES_VERSION)));
            ok("解析规则版本不同 → 视为不同字典",
                    p.indexOfIdentity("same", DictPlan.PARSE_RULES_VERSION + 1) == -1, "误判为同一本");
        }

        // ---- ⑧ 删除与下标收敛 ----
        {
            DictPlan p = new DictPlan();
            for (int i = 0; i < 3; i++) p.add(new DictPlan.Item("/d/x" + i, "x" + i));
            p.index = 2;
            ok("删掉最后一本", p.removeAt(2), "失败");
            ok("下标自动收敛到合法范围", p.index == 1, String.valueOf(p.index));
            ok("删空后下标归 0 且 current 为 null",
                    p.removeAt(1) && p.removeAt(0) && p.items.isEmpty()
                            && p.index == 0 && p.current() == null, "异常");
            ok("空计划进度为 0", p.progress() == 0f, String.valueOf(p.progress()));
        }

        // ---- ⑨ 身份判定：内容 SHA-256 的三条性质 ----
        {
            File dir = java.nio.file.Files.createTempDirectory("dictplan-id").toFile();
            File f1 = new File(dir, "one.txt");
            File f2 = new File(dir, "two.txt");
            File renamed = new File(dir, "renamed.txt");
            write(f1, "password1\npassword2\npassword3\n");
            write(f2, "password1\npassword2\npasswordX\n");      // 等长，内容不同
            write(renamed, "password1\npassword2\npassword3\n");

            String s1 = DictPlan.sha256Of(f1);
            String s2 = DictPlan.sha256Of(f2);
            String sr = DictPlan.sha256Of(renamed);

            ok("SHA-256 是 64 位小写十六进制",
                    s1.length() == 64 && s1.matches("[0-9a-f]{64}"), s1);
            ok("等长但内容不同 → 不同身份（这就是不用「大小+首行」的理由）",
                    !s1.equals(s2), s1 + " vs " + s2);
            ok("移动/重命名（同内容）→ 身份不变 → 进度可沿用",
                    s1.equals(sr) && DictPlan.sameIdentity(s1, DictPlan.PARSE_RULES_VERSION,
                            sr, DictPlan.PARSE_RULES_VERSION), "身份变了");
            ok("拿不到文件 → 空 SHA，且一律判为不同（宁可重跑）",
                    DictPlan.sha256Of(new File(dir, "nope.txt")).isEmpty()
                            && !DictPlan.sameIdentity("", DictPlan.PARSE_RULES_VERSION,
                                    s1, DictPlan.PARSE_RULES_VERSION), "误判为相同");
            ok("解析规则版本变化 → 判为不同", !DictPlan.sameIdentity(s1,
                    DictPlan.PARSE_RULES_VERSION, s1, DictPlan.PARSE_RULES_VERSION + 1), "误判为相同");
            ok("null 不抛异常", DictPlan.sha256Of(null).isEmpty(), "抛了");
        }

        // ---- ⑩ 恢复语义：盘上 RUNNING = 上个进程死在执行中 ----
        {
            ok("RUNNING → PAUSED（停在本本，不前进不回退）",
                    DictPlan.recover(DictPlan.State.RUNNING) == DictPlan.State.PAUSED, "");
            ok("PAUSED 保持 PAUSED",
                    DictPlan.recover(DictPlan.State.PAUSED) == DictPlan.State.PAUSED, "");
            ok("BLOCKED 保持 BLOCKED（阻塞原因不因重启而消失）",
                    DictPlan.recover(DictPlan.State.BLOCKED) == DictPlan.State.BLOCKED, "");
            ok("DONE_HIT 保持终态",
                    DictPlan.recover(DictPlan.State.DONE_HIT) == DictPlan.State.DONE_HIT, "");
            ok("null → IDLE", DictPlan.recover(null) == DictPlan.State.IDLE, "");
            ok("可继续态定义正确",
                    DictPlan.State.PAUSED.isResumable() && DictPlan.State.BLOCKED.isResumable()
                            && !DictPlan.State.DONE_HIT.isResumable()
                            && !DictPlan.State.STOPPED.isResumable(), "语义错");
        }

        // ---- ⑪ 上限保护 ----
        {
            DictPlan p = new DictPlan();
            int added = 0;
            for (int i = 0; i < DictPlan.MAX_ITEMS + 10; i++) {
                DictPlan.Item it = new DictPlan.Item("/d/z" + i, "z" + i);
                it.sha256 = "h" + i;
                if (p.add(it)) added++;
            }
            ok("不会超过上限（" + DictPlan.MAX_ITEMS + " 本）", added == DictPlan.MAX_ITEMS,
                    String.valueOf(added));
        }

        System.out.println();
        System.out.println("=== DictPlan: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    static void write(File f, String s) throws Exception {
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }
}

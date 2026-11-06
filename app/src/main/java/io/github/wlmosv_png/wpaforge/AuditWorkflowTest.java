package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AuditWorkflow;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictSnapshot;
import io.github.wlmosv_png.wpaforge.audit.OfflineVerdict;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计工作台的状态机：五步状态、阻断原因、总览行、冻结语义。
 *
 * <h3>要钉死的事情</h3>
 * <ul>
 *   <li>「为什么不能开始」永远有确定答案，界面不用自己猜；</li>
 *   <li>材料没导入 / 没选条目 / 材料不完整 / 字典为空 / 字典脱节，五种都要能挡；</li>
 *   <li>冻结后外界改字典不影响这一跑，{@code endRun} 必须能释放；</li>
 *   <li>总览没有数据时说的是"没数据"，不是编一个安全评分。</li>
 * </ul>
 */
public class AuditWorkflowTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    private static List<Candidate> list(String... pws) {
        List<Candidate> d = new ArrayList<Candidate>();
        for (String p : pws) d.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return d;
    }

    public static void main(String[] args) {
        System.out.println("== 初始状态：什么都不做时的答案 ==");
        AuditWorkflow w = new AuditWorkflow();
        ok("不能开始", !w.canStart());
        ok("阻断原因不是空串", !w.blocker().isEmpty());
        ok("没有字典", w.dictionary().empty());
        eq("候选 0", w.dictionaryCount(), 0);
        eq("第一步是 READY", w.stateOf(AuditWorkflow.Step.MATERIAL, false),
                AuditWorkflow.StepState.READY);
        eq("第二步是 TODO", w.stateOf(AuditWorkflow.Step.DIAGNOSE, false),
                AuditWorkflow.StepState.TODO);
        eq("第三步是 TODO", w.stateOf(AuditWorkflow.Step.DICT, false),
                AuditWorkflow.StepState.TODO);
        eq("第四步是 TODO", w.stateOf(AuditWorkflow.Step.RUN, false),
                AuditWorkflow.StepState.TODO);
        eq("第五步是 TODO", w.stateOf(AuditWorkflow.Step.RESULT, false),
                AuditWorkflow.StepState.TODO);
        ok("beginRun 在没有材料时返回 null", w.beginRun() == null);

        System.out.println("== 只有字典、没有材料：仍然不能开始 ==");
        AuditWorkflow w2 = new AuditWorkflow();
        ok("设字典成功", w2.setDictionary("A", DictSnapshot.SRC_FILE, "/tmp/a.txt",
                list("p1", "p2")));
        ok("有字典仍不能开始（缺材料）", !w2.canStart());
        eq("材料还没进来 -> 第三步仍是 TODO（步骤按顺序推进）",
                w2.stateOf(AuditWorkflow.Step.DICT, false),
                AuditWorkflow.StepState.TODO);

        System.out.println("== 空字典不能覆盖已有字典 ==");
        ok("空列表被拒绝", !w2.setDictionary("空", DictSnapshot.SRC_FILE, "", list()));
        eq("字典没被清掉", w2.dictionary().name, "A");
        eq("条数没被清掉", w2.dictionaryCount(), 2);

        System.out.println("== 材料导入 + 选中 + 诊断 ==");
        AuditWorkflow w3 = new AuditWorkflow();
        w3.onMaterialOk("cap.hc22000", 2048, hash64(), 2, 1, 1);
        ok("材料已导入", w3.materialImported());
        eq("记录数", w3.materialRecords(), 2);
        eq("PMKID", w3.materialPmkid(), 1);
        eq("EAPOL", w3.materialEapol(), 1);
        eq("步骤① DONE", w3.stateOf(AuditWorkflow.Step.MATERIAL, false),
                AuditWorkflow.StepState.DONE);
        eq("步骤② READY（还没选条目）", w3.stateOf(AuditWorkflow.Step.DIAGNOSE, false),
                AuditWorkflow.StepState.READY);
        ok("多条目时必须先选一条", !w3.canStart());

        w3.selectEntry(1, false, "MIC 字段未置零");
        eq("步骤② BLOCKED（选中的不完整）", w3.stateOf(AuditWorkflow.Step.DIAGNOSE, false),
                AuditWorkflow.StepState.BLOCKED);
        ok("选中的材料不完整 -> 仍不能开始", !w3.canStart());
        ok("阻断原因带上具体缺失项", w3.blocker().contains("MIC 字段未置零"));

        w3.selectEntry(0, true, "");
        eq("步骤② DONE", w3.stateOf(AuditWorkflow.Step.DIAGNOSE, false),
                AuditWorkflow.StepState.DONE);
        ok("材料齐了但字典还空 -> 不能开始", !w3.canStart());
        eq("步骤③ READY", w3.stateOf(AuditWorkflow.Step.DICT, false),
                AuditWorkflow.StepState.READY);

        System.out.println("== 越界选中必须被拒（不给脏状态）==");
        w3.selectEntry(9, true, "");
        eq("越界 -> 选中清零", w3.selectedIndex(), -1);
        ok("越界后不能开始", !w3.canStart());
        w3.selectEntry(-1, true, "");
        eq("负数 -> 同样清零", w3.selectedIndex(), -1);

        System.out.println("== 条件齐了：可以开始 ==");
        w3.selectEntry(0, true, "");
        ok("设字典", w3.setDictionary("B", DictSnapshot.SRC_PROFILE, "/tmp/b.txt",
                list("hashcat!", "x")));
        ok("可以开始", w3.canStart());
        eq("阻断原因为空", w3.blocker(), "");
        eq("步骤④ READY", w3.stateOf(AuditWorkflow.Step.RUN, false),
                AuditWorkflow.StepState.READY);

        System.out.println("== 冻结：本次任务与界面脱钩 ==");
        List<Candidate> uiDict = list("hashcat!", "x");
        w3.setDictionary("B", DictSnapshot.SRC_PROFILE, "/tmp/b.txt", uiDict);
        AuditWorkflow.Frozen f = w3.beginRun();
        ok("冻结成功", f != null);
        ok("正在运行", w3.running());
        eq("冻结条数 = 界面条数", f.candidateCount(), 2);
        eq("冻结的字典名", f.snap.name, "B");
        ok("冻结包自洽（快照与副本一致）", f.snap.matches(f.dict));
        eq("步骤④ ACTIVE", w3.stateOf(AuditWorkflow.Step.RUN, false),
                AuditWorkflow.StepState.ACTIVE);
        ok("运行中不能再开一个", w3.beginRun() == null);
        ok("运行中阻断原因指向'已经在跑'", !w3.blocker().isEmpty());

        // 界面上换字典 —— 冻结包不受影响
        uiDict.add(new Candidate("late", Candidate.Origin.USER_DICT));
        w3.setDictionary("C", DictSnapshot.SRC_FILE, "/tmp/c.txt", list("z"));
        eq("界面字典已换", w3.dictionary().name, "C");
        eq("冻结包条数没变", f.candidateCount(), 2);
        ok("冻结包内容没变", f.snap.matches(list("hashcat!", "x")));
        ok("运行中换字典不会让任务换字典（结构性保证）",
                !f.snap.matches(w3.dictionaryList()));

        w3.endRun();
        ok("结束之后不再运行", !w3.running());
        ok("结束后可以再次开始", w3.canStart());

        System.out.println("== 界面字典被改坏 -> 开跑前被拦下 ==");
        AuditWorkflow w4 = new AuditWorkflow();
        w4.onMaterialOk("m", 10, hash64(), 1, 1, 0);
        w4.selectEntry(0, true, "");
        List<Candidate> shared = list("one", "two");
        w4.setDictionary("D", DictSnapshot.SRC_FILE, "/tmp/d.txt", shared);
        ok("正常可以开始", w4.canStart());
        shared.add(new Candidate("three", Candidate.Origin.USER_DICT));   // 快照之后又改了
        ok("快照与列表脱节 -> 被拦下", !w4.canStart());
        ok("阻断原因说明脱节", !w4.blocker().isEmpty());
        ok("beginRun 也拒绝", w4.beginRun() == null);

        System.out.println("== 材料失败：状态要跟着走，不能停在旧结果 ==");
        AuditWorkflow w5 = new AuditWorkflow();
        w5.onMaterialOk("good", 10, hash64(), 1, 1, 0);
        w5.selectEntry(0, true, "");
        w5.setDictionary("E", DictSnapshot.SRC_FILE, "/tmp/e.txt", list("k"));
        ok("先可以开始", w5.canStart());
        w5.onMaterialProblem("bad.pcap", "不是合法的 22000 记录");
        eq("步骤① BLOCKED", w5.stateOf(AuditWorkflow.Step.MATERIAL, false),
                AuditWorkflow.StepState.BLOCKED);
        ok("不再可以开始", !w5.canStart());
        eq("记录数清零", w5.materialRecords(), 0);
        eq("选中清零", w5.selectedIndex(), -1);
        ok("字典仍在（材料坏不该顺手清字典）", !w5.dictionary().empty());

        System.out.println("== 总览：五条、空状态、异常突出 ==");
        AuditWorkflow w6 = new AuditWorkflow();
        List<AuditWorkflow.Row> rows0 = w6.overview(OfflineVerdict.none(), 0);
        eq("五条", rows0.size(), 5);
        eq("第 1 条是当前状态", rows0.get(0).label, L.s("当前状态", "State"));
        eq("无数据时是 DIM（不编数字）", rows0.get(0).tone, AuditWorkflow.Tone.DIM);
        eq("字典行写明未选择", rows0.get(1).tone, AuditWorkflow.Tone.DIM);
        eq("材料行写明未导入", rows0.get(2).tone, AuditWorkflow.Tone.DIM);
        eq("最近结果写明未跑过", rows0.get(3).tone, AuditWorkflow.Tone.DIM);
        eq("待处理写明无", rows0.get(4).tone, AuditWorkflow.Tone.DIM);

        w6.onMaterialOk("m.hc22000", 100, hash64(), 1, 0, 1);
        w6.selectEntry(0, true, "");
        w6.setDictionary("F", DictSnapshot.SRC_PROFILE, "/tmp/f.txt", list("k"));
        List<AuditWorkflow.Row> rows1 = w6.overview(OfflineVerdict.none(), 0);
        eq("条件齐 -> OK", rows1.get(0).tone, AuditWorkflow.Tone.OK);
        ok("字典行带条数", rows1.get(1).value.contains(L.s("1 条", "1 entries")));
        ok("字典行带指纹", rows1.get(1).value.contains(w6.dictionary().fingerprint()));

        OfflineVerdict hit = new OfflineVerdict(OfflineVerdict.State.HIT, "m", "PMKID",
                "s", "aa", "F", 1, 1, 5, 1L, "pw", "");
        eq("命中 -> OK 色", w6.overview(hit, 0).get(3).tone, AuditWorkflow.Tone.OK);
        OfflineVerdict miss = new OfflineVerdict(OfflineVerdict.State.MISS, "m", "PMKID",
                "s", "aa", "F", 4, 4, 5, 1L, "", "");
        eq("未命中 -> WARN 色", w6.overview(miss, 0).get(3).tone, AuditWorkflow.Tone.WARN);
        OfflineVerdict cac = new OfflineVerdict(OfflineVerdict.State.CANCELLED, "m", "PMKID",
                "s", "aa", "F", 4, 2, 5, 1L, "", "");
        eq("取消 -> NORMAL 色", w6.overview(cac, 0).get(3).tone, AuditWorkflow.Tone.NORMAL);
        ok("取消在总览里明确写出「用户已取消」",
                w6.overview(cac, 0).get(3).value.contains(L.s("用户已取消", "cancelled by user")));

        eq("有待处理项 -> WARN", w6.overview(OfflineVerdict.none(), 3).get(4).tone,
                AuditWorkflow.Tone.WARN);
        w6.onMaterialProblem("bad", "文件为空");
        eq("材料异常 -> 第 1 条 DANGER", w6.overview(OfflineVerdict.none(), 0).get(0).tone,
                AuditWorkflow.Tone.DANGER);
        eq("材料异常 -> 待处理 DANGER", w6.overview(OfflineVerdict.none(), 0).get(4).tone,
                AuditWorkflow.Tone.DANGER);

        System.out.println("== 步骤条：五步标题与副标题都在 ==");
        AuditWorkflow w7 = new AuditWorkflow();
        AuditWorkflow.StepInfo[] steps = w7.steps(false);
        eq("五步", steps.length, 5);
        eq("序号 1..5", steps[0].ordinal + steps[4].ordinal, 6);
        eq("① 标题", steps[0].title,
                L.s("导入 Wi-Fi 握手材料", "Import Wi-Fi handshake material"));
        eq("② 标题", steps[1].title,
                L.s("检查材料是否可以使用", "Check whether the material works"));
        eq("③ 标题", steps[2].title,
                L.s("选择候选口令字典", "Pick a candidate dictionary"));
        eq("④ 标题", steps[3].title,
                L.s("开始离线校验", "Run the offline check"));
        eq("⑤ 标题", steps[4].title,
                L.s("离线校验结果", "Offline check result"));
        ok("每步都有副标题（不是空字符串）",
                !steps[0].detail.isEmpty() && !steps[1].detail.isEmpty()
                        && !steps[2].detail.isEmpty() && !steps[3].detail.isEmpty()
                        && !steps[4].detail.isEmpty());
        ok("④ 的副标题直接给出为什么不能开始", steps[3].detail.equals(w7.blocker()));

        System.out.println("== resetMaterial 清得干净 ==");
        AuditWorkflow w8 = new AuditWorkflow();
        w8.onMaterialOk("m", 10, hash64(), 3, 2, 1);
        w8.selectEntry(1, true, "");
        w8.resetMaterial();
        eq("记录数 0", w8.materialRecords(), 0);
        eq("选中 -1", w8.selectedIndex(), -1);
        eq("文件名空", w8.materialName(), "");
        eq("问题空", w8.materialProblem(), "");

        System.out.println("== clearDictionary ==");
        AuditWorkflow w9 = new AuditWorkflow();
        w9.setDictionary("G", DictSnapshot.SRC_FILE, "/tmp/g.txt", list("a"));
        w9.clearDictionary();
        ok("清空后没有字典", w9.dictionary().empty());
        eq("候选 0", w9.dictionaryCount(), 0);
        ok("清空后 blocker 指向字典", !w9.canStart());

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    private static String hash64() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append('a');
        return sb.toString();
    }
}

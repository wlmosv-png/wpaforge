package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.ui.VerdictView;

import java.util.HashSet;
import java.util.Set;

/**
 * Verdict → UI 映射测试（U1）。
 *
 * <h3>它拦的是什么</h3>
 * 修复前界面只拿到 {@code boolean hit}，非成功一律显示 ✗ ——
 * AUTH_FAIL / NETWORK_AUTH_FAIL / TIMEOUT / INCOMPLETE / TERMINATED_UNKNOWN /
 * ERROR 六种情况长得一模一样。用户会得出「这批密码都错了」的错误结论。
 *
 * <p>本测试把「每种 Verdict 在界面上必须可区分」变成可执行契约：
 * 符号唯一、标签唯一、说明非空，且「是否算已证实错误」只有 AUTH_FAIL 为真。
 */
public final class VerdictViewTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) {
        System.out.println("=== Verdict -> UI 统一映射（U1）===");

        CrackEngine.Verdict[] all = CrackEngine.Verdict.values();
        info("枚举共 " + all.length + " 个 verdict");

        // ---------------------------------------------------------- 1) 可区分性
        Set<String> syms = new HashSet<>();
        int emptyDesc = 0, dupSym = 0;
        System.out.println();
        System.out.println("-- 1) 每个 verdict 在界面上必须可区分 --");
        for (CrackEngine.Verdict v : all) {
            String sym = VerdictView.symbol(v);
            String lbl = VerdictView.label(v);
            String desc = VerdictView.description(v);
            info(String.format("%-20s 符号=%-4s 标签=%-10s 说明=%s",
                    v, sym, lbl, desc.isEmpty() ? "(空)" : desc.length() > 28
                            ? desc.substring(0, 28) + "…" : desc));
            if (desc == null || desc.isEmpty()) emptyDesc++;
            // UNKNOWN 与 TERMINATED_UNKNOWN 允许同符号（都是"?"），但标签必须不同
            if (!syms.add(sym + "|" + lbl)) dupSym++;
        }
        ok("每个 verdict 的 (符号+标签) 组合都唯一（界面可区分）", dupSym == 0,
                "重复=" + dupSym);
        ok("每个 verdict 都有非空说明（用户知道该做什么）", emptyDesc == 0,
                "空=" + emptyDesc);

        // ---------------------------------------------------------- 2) 关键语义
        System.out.println();
        System.out.println("-- 2) 「已证实错误」只能来自候选级失败 --");
        int wrongCount = 0;
        for (CrackEngine.Verdict v : all) {
            if (VerdictView.countsAsProvenWrong(v)) {
                wrongCount++;
                ok("countsAsProvenWrong 为真的只有 AUTH_FAIL（实际=" + v + "）",
                        v == CrackEngine.Verdict.AUTH_FAIL, v.toString());
            }
        }
        ok("恰好 1 个 verdict 计入「已证实错误密码」", wrongCount == 1, "count=" + wrongCount);

        ok("NETWORK_AUTH_FAIL **不**计入已证实错误（网络级事件）",
                !VerdictView.countsAsProvenWrong(CrackEngine.Verdict.NETWORK_AUTH_FAIL), "");
        ok("INCOMPLETE **不**计入已证实错误（连接还在跑）",
                !VerdictView.countsAsProvenWrong(CrackEngine.Verdict.INCOMPLETE), "");
        ok("TERMINATED_UNKNOWN **不**计入已证实错误",
                !VerdictView.countsAsProvenWrong(CrackEngine.Verdict.TERMINATED_UNKNOWN), "");
        ok("UNKNOWN **不**计入已证实错误",
                !VerdictView.countsAsProvenWrong(CrackEngine.Verdict.UNKNOWN), "");

        // 说明文案必须明确否认「密码错误」
        String net = VerdictView.description(CrackEngine.Verdict.NETWORK_AUTH_FAIL);
        ok("NETWORK_AUTH_FAIL 的说明明确写「不代表这条密码错了」",
                net.contains("不代表这条密码错了"), net);
        String inc = VerdictView.description(CrackEngine.Verdict.INCOMPLETE);
        ok("INCOMPLETE 的说明明确写「稍后可能成功」",
                inc.contains("稍后可能成功"), inc);
        String term = VerdictView.description(CrackEngine.Verdict.TERMINATED_UNKNOWN);
        ok("TERMINATED_UNKNOWN 的说明明确写「不等于密码错误」",
                term.contains("不等于密码错误"), term);

        // ---------------------------------------------------------- 3) 与引擎口径一致
        System.out.println();
        System.out.println("-- 3) 与引擎的持久化/游标口径一致 --");
        // 注意不能拿 EvidenceGrade.defaultOf(v) 比 —— 那是**保守默认**
        // （AUTH_FAIL -> INFERRED），而 Adjudicator **实际产出** AUTH_FAIL 时
        // grade 恒为 DIRECT（只有 AT3 才会给 AUTH_FAIL）。比默认值会误报。
        // 正确比法：该 verdict 在实际产出时的等级。
        int mismatch = 0;
        for (CrackEngine.Verdict v : all) {
            EvidenceGrade.Level actual;
            switch (v) {
                case SUCCESS:
                case AUTH_FAIL:
                    actual = EvidenceGrade.Level.DIRECT;   // Adjudicator 规则 1/3
                    break;
                default:
                    actual = EvidenceGrade.Level.NONE;     // Adjudicator 规则 2/4~9
                    break;
            }
            boolean ui = VerdictView.advancesCursor(v);
            boolean eng = EvidenceGrade.advancesCursor(actual);
            if (ui != eng) {
                mismatch++;
                info("  不一致: " + v + " ui=" + ui + " 实际等级=" + actual);
            }
        }
        ok("UI 的「推进游标」与**实际产出等级**完全一致",
                mismatch == 0, "不一致=" + mismatch);
        ok("AUTH_FAIL 的实际产出等级是 DIRECT（不是默认的 INFERRED）",
                EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT)
                        && !EvidenceGrade.persistable(EvidenceGrade.defaultOf(
                                CrackEngine.Verdict.AUTH_FAIL)), "");

        ok("SUCCESS 推进游标", VerdictView.advancesCursor(CrackEngine.Verdict.SUCCESS), "");
        ok("AUTH_FAIL 推进游标", VerdictView.advancesCursor(CrackEngine.Verdict.AUTH_FAIL), "");
        ok("NETWORK_AUTH_FAIL **不**推进游标",
                !VerdictView.advancesCursor(CrackEngine.Verdict.NETWORK_AUTH_FAIL), "");
        ok("INCOMPLETE **不**推进游标",
                !VerdictView.advancesCursor(CrackEngine.Verdict.INCOMPLETE), "");

        // ---------------------------------------------------------- 4) 重试与注意力
        System.out.println();
        System.out.println("-- 4) 重试与「需要用户注意」--");
        ok("AUTH_FAIL 不值得重试（已是候选级结论）",
                !VerdictView.retryable(CrackEngine.Verdict.AUTH_FAIL), "");
        ok("NETWORK_AUTH_FAIL **值得**重试（绑不到本条候选）",
                VerdictView.retryable(CrackEngine.Verdict.NETWORK_AUTH_FAIL), "");
        ok("INCOMPLETE **值得**重试", VerdictView.retryable(CrackEngine.Verdict.INCOMPLETE), "");
        ok("INCOMPLETE 需要用户注意", VerdictView.needsAttention(CrackEngine.Verdict.INCOMPLETE), "");
        ok("NETWORK_AUTH_FAIL 需要用户注意",
                VerdictView.needsAttention(CrackEngine.Verdict.NETWORK_AUTH_FAIL), "");
        ok("SUCCESS 不需要注意", !VerdictView.needsAttention(CrackEngine.Verdict.SUCCESS), "");

        // ---------------------------------------------------------- 5) 源码守卫
        System.out.println();
        System.out.println("-- 5) 源码守卫：页面不得自行解释 Verdict --");
        try {
            String root = TestPaths.root();
            String svc = readOrEmpty(root, "app/src/main/java/io/github/wlmosv_png/"
                    + "wpaforge/CrackService.java");
            String act = readOrEmpty(root, "app/src/main/java/io/github/wlmosv_png/"
                    + "wpaforge/MainActivity.java");
            ok("CrackService 把 Verdict 传给 UI 回调",
                    svc.contains("a.verdict);"), "未接线");
            ok("MainActivity 覆盖了带 Verdict 的回调（@Override）",
                    act.contains("CrackEngine.Verdict v"), "未覆盖");
            ok("MainActivity 用 VerdictView 决定符号/标签/说明",
                    act.contains("VerdictView.symbol") && act.contains("VerdictView.label")
                            && act.contains("VerdictView.description"), "未使用统一映射");
            ok("旧的「非成功一律 ✗」分支已消失",
                    !act.contains("appendStreamDetail(\"✗  \""), "旧分支仍在");

            // U2：运行状态必须只有一个来源。
            // 真机实测过「顶部：已暂停」+「日志区：准备就绪」同屏矛盾，
            // 根因就是两处各自推断状态。
            ok("MainActivity 抽出唯一的队列状态方法 currentQueueState()",
                    act.contains("private TaskQueue.State currentQueueState()"), "未抽出");
            int uses = 0, at = 0;
            while ((at = act.indexOf("currentQueueState()", at)) >= 0) { uses++; at++; }
            ok("currentQueueState() 至少被两处消费（进度卡 + 空状态提示）",
                    uses >= 3, "调用点=" + uses);   // 声明 1 + 使用 >=2
            ok("renderProgressCardState 不再自己遍历 queueCache 推断状态",
                    !act.contains("if (t.state == TaskQueue.State.RUNNING || "
                            + "t.state == TaskQueue.State.PENDING) {\n                st = t.state;"),
                    "仍在自行推断");
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 源码守卫异常: " + t);
        }

        System.out.println();
        System.out.println("=== Verdict->UI 映射测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String readOrEmpty(String root, String rel) {
        try {
            java.io.File f = new java.io.File(root, rel);
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
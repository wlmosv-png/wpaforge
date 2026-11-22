package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.TestPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 多字典计划**残留状态**专项（1.9.69 真机事故）。
 *
 * <h3>事故（用户截图，2026-11-22）</h3>
 * <pre>
 *   计划第 2/2 本 · 已命中 · 当前「tt」0/7     ← 上一轮多字典计划的残留
 *   单字典 · 过滤 保守 · 待执行 336            ← 同一屏的当前单字典运行
 * </pre>
 * 两条互相矛盾：用户这次根本没开多字典，界面却挂着旧计划。
 *
 * <h3>根因（两处）</h3>
 * <ol>
 *   <li>{@code loadPlanForRun()} 在「计划已终态 / 目标不符 / 异常」三个不接管分支里
 *       把 {@code p.summary()} 写进静态字段 {@code LAST_PLAN_SUMMARY}，
 *       而 UI 只要该字段非空就渲染 → 单字典运行也会显示旧计划行；</li>
 *   <li>终态分支没有作废**残留的计划 PENDING 任务**，它们会躺在队列里
 *       被下次「开始」当普通任务跑掉（审计点名场景）。</li>
 * </ol>
 *
 * <h3>本测试钉住的契约</h3>
 * <ul>
 *   <li>三个「不接管」分支都必须把 {@code LAST_PLAN_SUMMARY} 清空；</li>
 *   <li>终态分支必须调用 {@code TaskQueue.stopPlanTasks(...)} 作废残留；</li>
 *   <li>UI 计划行渲染必须以 {@code usePlanMode} 为前提（单字典一律不显示）。</li>
 * </ul>
 */
public final class PlanResidueTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String read(String rel) throws IOException {
        java.nio.file.Path p = Paths.get(TestPaths.under("app/src/main/java/" + rel));
        if (!Files.isRegularFile(p)) return null;
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 取 {from, to} 之间的片段。 */
    private static String slice(String s, String from, String to) {
        if (s == null) return "";
        int i = s.indexOf(from);
        if (i < 0) return "";
        int j = s.indexOf(to, i + from.length());
        if (j < 0) j = s.length();
        return s.substring(i, j);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 多字典计划残留状态专项 ==");

        String cs = read("io/github/wlmosv_png/wpaforge/CrackService.java");
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");
        String tq = read("io/github/wlmosv_png/wpaforge/audit/TaskQueue.java");
        ok("能读到 CrackService", cs != null, "缺失");
        ok("能读到 MainActivity", ma != null, "缺失");
        ok("能读到 TaskQueue", tq != null, "缺失");
        if (cs == null || ma == null || tq == null) { System.out.println("=== " + pass + " 通过 / " + (++fail) + " 失败 ==="); System.exit(1); }

        // ---- ① 终态分支：清空 summary + 作废残留 ----
        String terminal = slice(cs, "if (p.state.isTerminal()) {", "// 上个进程死在执行中");
        info("终态分支长度=" + terminal.length());
        ok("① 终态分支不接管（plan = null）", terminal.contains("plan = null;"), "缺失");
        ok("① 终态分支清空 LAST_PLAN_SUMMARY（不再把 summary 留在界面）",
                terminal.contains("LAST_PLAN_SUMMARY = \"\";"), "仍写入残留");
        ok("① 终态分支作废残留计划任务（stopPlanTasks）",
                terminal.contains("TaskQueue.stopPlanTasks(this)"), "未作废");
        ok("① 终态分支不再把 p.summary() 写进静态字段",
                !terminal.contains("LAST_PLAN_SUMMARY = p.summary();"), "旧写法残留");

        // ---- ② 目标不符分支：清空 summary ----
        String mismatch = slice(cs, "if (!p.targetKey.equals(key)) {", "planFileKey = key;");
        ok("② 目标不符分支清空 LAST_PLAN_SUMMARY",
                mismatch.contains("LAST_PLAN_SUMMARY = \"\";"), "仍残留");

        // ---- ③ 异常分支：清空 summary ----
        String caught = slice(cs, "failed to read the plan (falling back to a plain queue)", "}\n    }");
        ok("③ 读取异常分支清空 LAST_PLAN_SUMMARY",
                caught.contains("LAST_PLAN_SUMMARY = \"\";"), "仍残留");

        // ---- ④ UI：单字典一律不显示计划行 ----
        String planLine = slice(ma, "private void refreshPlanLine() {", "runMetaView.setText(mb.toString());");
        ok("④ UI 计划行有「单字典清空」保护",
                planLine.contains("if (!usePlanMode) {") && planLine.contains("pb.setLength(0);"),
                "缺保护");
        ok("④ 结构化计划段以 usePlanMode 为前提",
                planLine.contains("if (usePlanMode && livePlan() != null) {"), "条件不足");

        // ---- ⑤ TaskQueue.stopPlanTasks 只动计划派生的 PENDING ----
        String stop = slice(tq, "public static int stopPlanTasks(Context ctx) {", "return n;");
        ok("⑤ stopPlanTasks 只作废 fromPlan 任务", stop.contains("t.fromPlan"), "范围过宽");
        ok("⑤ stopPlanTasks 只作废 PENDING", stop.contains("State.PENDING"), "误伤其它状态");

        // ---- ⑥ 行为验证：终态计划不再被接管（纯逻辑） ----
        try {
            DictPlan p = new DictPlan();
            p.items.add(new DictPlan.Item());
            p.state = DictPlan.State.DONE_HIT;
            ok("⑥ 终态计划 isTerminal 为真", p.state.isTerminal(), String.valueOf(p.state));
            DictPlan p2 = new DictPlan();
            p2.items.add(new DictPlan.Item());
            p2.state = DictPlan.State.PAUSED;
            ok("⑥ 暂停计划不是终态（可继续）", !p2.state.isTerminal(), String.valueOf(p2.state));
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL ⑥ 异常: " + t);
        }

        System.out.println();
        System.out.println("=== 多字典残留专项: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * 「破解页上上下下只许说同一个数」的结构回归测试（1.9.69）。
 *
 * <h3>用户截图（2026-11-22 09:57）暴露的两件事</h3>
 * <pre>
 *   收尾完成 · 原来的网络已写回（已还原 …）      ← 上一轮的结算卡，还挂着
 *   正在运行 1.8%
 *   已执行 6  /  总数 332                        ← 本轮引擎口径 + 计划口径
 *   最后尝试 00000000
 *   …
 *   2.4G-3  进行中
 *   已试 44 / 336   ·  本轮 44 条                ← 任务累计口径 + 字典口径
 * </pre>
 *
 * <ol>
 *   <li><b>旧结算卡不收</b>：收卡逻辑只在「出现另一条 RUNNING 任务」时触发，
 *       同一任务点「继续」续跑时 taskId 不变 → 永远不收。</li>
 *   <li><b>两套计数</b>：进度卡用引擎 {@code onProgress} 的绝对位置（本轮），
 *       任务卡用 {@code Task.completedCount()}（续跑边界 + 历次），分母也不同
 *       （计划剩余 332 / 字典总数 336）。两个数字都是真的，但同屏并列就是自相矛盾。</li>
 * </ol>
 *
 * <h3>这里钉住的契约</h3>
 * <ol>
 *   <li>{@code Task.completedCount()} 必须把 {@code liveRoundTested} 算进去
 *       （否则跑动中任务卡不动，只有进度卡在跳）；</li>
 *   <li>服务端在进度回调里写 {@code liveRoundTested}，收尾时清零（不许重复计数）；</li>
 *   <li>界面只从 {@code runningTaskForProgress()} 取任务，{@code done/denom}
 *       一处算完给三处用，分母固定 {@code dictSize}；</li>
 *   <li>候选一开始就必须收掉旧结算卡；</li>
 *   <li>统计区的合计行必须写明「本轮」，与「已试」的累计口径区分开。</li>
 * </ol>
 */
public final class CrackCountUnifyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String read(String rel) throws IOException {
        String root = TestPaths.javaSrcRoot();
        if (root == null || root.isEmpty()) throw new IOException("no root");
        return new String(Files.readAllBytes(Paths.get(root + rel)), StandardCharsets.UTF_8);
    }

    private static String slice(String s, String from, String to) {
        if (s == null) return "";
        int i = s.indexOf(from);
        if (i < 0) return "";
        int j = s.indexOf(to, i + from.length());
        if (j < 0) j = s.length();
        return s.substring(i, j);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 破解页计数口径统一 + 结算卡收尾（1.9.69）===");

        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");
        String svc = read("io/github/wlmosv_png/wpaforge/CrackService.java");
        String queue = read("io/github/wlmosv_png/wpaforge/audit/TaskQueue.java");

        // ---- ① 任务累计口径把「本轮实时值」算进去 ----
        String cc = slice(queue, "public int completedCount() {", "public float displayProgress()");
        ok("① completedCount() 纳入 liveRoundTested（跑动中任务卡不再冻住）",
                cc.contains("liveRoundTested"), "未纳入");
        ok("① 取两者较大值，不做加法（避免重复计数当前轮）",
                cc.contains("Math.max((long) offset"), "仍是加法");
        ok("① liveRoundTested 不落盘（只用于显示）",
                slice(queue, "本轮实时的绝对位置", "public int liveRoundTested;")
                        .contains("不落盘"), "字段注释未声明不落盘");
        info("字段声明在 Task 上，序列化只写 testedThisRun（见 put/optInt \"tested\"）");

        // ---- ② 服务：进度写实时值、收尾清零 ----
        ok("② 进度回调写入 ft.liveRoundTested",
                svc.contains("ft.liveRoundTested = tested"), "未写入");
        ok("② 收尾时清零，避免与 testedThisRun 重复",
                svc.contains("ft.liveRoundTested = 0;"), "未清零");

        // ---- ③ 界面：单一来源 + 固定分母 ----
        ok("③ 存在唯一取任务的入口 runningTaskForProgress()",
                ma.contains("private io.github.wlmosv_png.wpaforge.audit.TaskQueue.Task"
                        + " runningTaskForProgress()"), "缺失");
        String prog = slice(ma, "public void onProgress(int tested, int total, double rate, long etaMs) {",
                "public void onTaskDone(");
        ok("③ onProgress 用 done/denom 而不是 tested/total 显示",
                prog.contains("final int done =") && prog.contains("final int denom =")
                        && prog.contains("thousands(done)") && prog.contains("thousands(denom)"),
                "仍用回调原值");
        ok("③ 分母固定字典总条数（不再出现计划剩余口径）",
                prog.contains("liveTask.dictSize > 0) ? liveTask.dictSize : total"), "分母未统一");
        ok("③ 进度条百分比与文字同源",
                prog.contains("percentLabel(done, denom)") && !prog.contains("percentLabel(tested, total)"),
                "百分比仍用旧口径");
        ok("③ 进度卡文案不再写「已执行 / 总数」",
                !prog.contains("已执行 %s") && prog.contains("已试 %s"), "旧文案回归");
        // 1.9.69 真机事故：暂停后 offset 仍是 0（本轮全是「无结论」，cursor 不动），
        // 卡片却写 52 —— 续跑实际从头开始。所以「已试」旁必须同时露出「已排除」。
        ok("③ 进度卡同时显示「已排除」（续跑真正会跳过的量）",
                prog.contains("已排除 %s 条"), "只写已试会骗人");
        ok("③ 首帧进度条也用 done0/denom0（不再是 15.5% 与 1.2% 打架）",
                ma.contains("float p = denom0 > 0 ? (float) done0 / denom0 : 0f;")
                        && ma.contains("percentLabel(done0, denom0)"), "首帧仍用旧口径");

        // ---- ④ 结算卡：新一轮一开口就收 ----
        String att = slice(ma, "public void onAttempt(int index, int total, String candidate) {",
                "public void onProgress(");
        int hideAt = att.indexOf("hideResultCard();");
        int candAt = att.indexOf("curCandIndex = index;");
        ok("④ 候选开始时收掉上一轮结算卡，且排在更新候选之前",
                hideAt > 0 && candAt > hideAt, "收卡缺失或顺序不对");
        ok("④ 收卡条件只看「结算卡正显示」（不依赖 taskId 变化，续跑同样生效）",
                slice(att, "ui.post(() -> {", "curCandIndex = index;")
                        .contains("resultCard.getVisibility() == View.VISIBLE"), "收卡条件不对");

        // ---- ⑤ 统计区标注「本轮」，与累计口径分开 ----
        ok("⑤ 合计行明确写「本轮已判定」",
                ma.contains("L.f(\"本轮已判定 %s 条%s\""), "口径未标注");
        ok("⑤ 任务卡把「本轮 N 条」改成「本次累计 N 条」（testedThisRun 是跨续跑累计）",
                ma.contains("本次累计 %d 条") && !ma.contains("· 本轮 %d 条"), "旧措辞仍在");

        System.out.println("------------------------------------------");
        System.out.println("CrackCountUnifyTest pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}

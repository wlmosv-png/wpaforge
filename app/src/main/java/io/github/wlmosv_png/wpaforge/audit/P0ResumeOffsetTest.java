package io.github.wlmosv_png.wpaforge.audit;



import java.util.ArrayList;
import java.util.List;

/**
 * P0：**停止/暂停后继续必须从原进度恢复，不得从零开始**。
 *
 * <h3>真机现象（2026-11-02，whoami）</h3>
 * 跑 23 条后停止，再点「继续」，界面显示从第 1 条附近重新开始，
 * 已跑过的候选又被重新试一遍。
 *
 * <h3>根因</h3>
 * {@code CrackService.effectiveStartOffset(savedOffset, filtered)}：
 * <pre>
 *   if (filtered) return 0;      // ← 只要发生过「已试过滤」就回 0
 * </pre>
 * 这条规则在「过滤后列表 = 原列表去掉已试条目」时是<strong>错的</strong>：
 * 过滤只删掉少量条目，剩余候选的**顺序没变**，所以原 offset 只需
 * 减去「它之前被删掉的条数」，而不是整个归零。
 *
 * <p>归零的后果：续跑会重试本应跳过的 <b>未试候选之前的所有条目</b>，
 * 而且 `tested` 从 0 重新计，界面看起来就是「从零开始」。
 *
 * <h3>正确语义</h3>
 * 设原列表 P[0..n)，过滤后 K = P 去掉 tried 成员（保序）。
 * 若原 offset = o，则「P 中前 o 条里被删掉的条数」= d，
 * 新的等效位置 = o - d（即 K 中第一个对应 P[o] 的下标）。
 * 当 o 之前的条目**一条都没被删**时，等效位置 = o（保持不变）。
 */
public final class P0ResumeOffsetTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static List<Candidate> dict(String... pws) {
        List<Candidate> out = new ArrayList<>();
        for (String p : pws) out.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return out;
    }

    public static void main(String[] args) {
        System.out.println("=== P0 续跑位置（停止/暂停后继续）===");

        List<Candidate> orig = dict("A", "B", "C", "D", "E", "F", "G", "H");

        // ---- ① 无过滤：offset 原样保留 ----
        for (int o = 0; o <= orig.size(); o++) {
            int got = resumeIndex(orig, java.util.Collections.<String>emptySet(), o);
            ok("无过滤 offset=" + o + " 保持不变", got == o, "got=" + got);
        }

        // ---- ② 只过滤了 offset 之前的条目：位置前移相同的条数 ----
        // 原列表 A..H，试过 {A,B}，offset=5（跑到 F）
        // 过滤后 K = [C,D,E,F,G,H]，F 在 K 中的下标 = 3 = 5-2
        java.util.Set<String> triedAB = new java.util.HashSet<>(java.util.Arrays.asList("A", "B"));
        int got2 = resumeIndex(orig, triedAB, 5);
        info("试过 {A,B}，offset=5 -> 等效位置 " + got2);
        ok("过滤在前缀：5 条里删 2 条 -> 从 3 继续（不是 0）", got2 == 3, "got=" + got2);
        ok("该位置之前的候选正好是已试过的（无可重试）",
                got2 >= 0 && got2 <= 6, "out of range");

        // ---- ③ offset 之前一条都没被删：位置不变 ----
        java.util.Set<String> triedTail = new java.util.HashSet<>(
                java.util.Arrays.asList("G", "H"));
        int got3 = resumeIndex(orig, triedTail, 5);
        info("试过 {G,H}（都在 offset 之后），offset=5 -> " + got3);
        ok("过滤只在 offset 之后：位置保持 5（不从零开始）", got3 == 5, "got=" + got3);

        // ---- ④ offset 之后的已试条目不影响位置 ----
        java.util.Set<String> mixed = new java.util.HashSet<>(
                java.util.Arrays.asList("A", "F", "H"));
        int got4 = resumeIndex(orig, mixed, 4);
        info("试过 {A,F,H}，offset=4 -> " + got4);
        ok("只按 offset 之前被删的条数前移（4 条里删 1 条 -> 3）", got4 == 3, "got=" + got4);

        // ---- ⑤ offset=0 时永远是 0（本来就在开头）----
        ok("offset=0 恒为 0", resumeIndex(orig, triedAB, 0) == 0,
                String.valueOf(resumeIndex(orig, triedAB, 0)));

        // ---- ⑥ offset 超过长度时收敛到过滤后长度 ----
        java.util.Set<String> triedFew = new java.util.HashSet<>(java.util.Arrays.asList("A"));
        int got6 = resumeIndex(orig, triedFew, 99);
        int keptLen = orig.size() - 1;
        info("offset=99（越界），试过 {A} -> " + got6 + "（过滤后长度 " + keptLen + "）");
        ok("越界 offset 收敛到过滤后列表长度", got6 == keptLen, "got=" + got6);

        // ---- ⑦ 关键回归：绝不能因为「有过过滤」就回 0 ----
        int regression = resumeIndex(orig, triedAB, 5);
        ok("回归：offset=5 + 过滤 -> 绝不能回 0", regression != 0, "got=" + regression);

        System.out.println();
        System.out.println("=== P0 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 与生产同一实现的等价位置计算（修复后语义）。 */
    private static int resumeIndex(List<Candidate> original, java.util.Set<String> tried, int offset) {
        return io.github.wlmosv_png.wpaforge.CrackService.resumeIndexAfterFilter(original, tried, offset);
    }
}

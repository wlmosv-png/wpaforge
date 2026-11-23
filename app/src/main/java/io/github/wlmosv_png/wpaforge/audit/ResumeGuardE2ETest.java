package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * ResumeGuard **端到端失效验证**（1.9.69 发布前门禁）。
 *
 * <p>与 {@code ResumeRecheckTest} 的 G1–G11 不同：那里验的是「签名算得对」，
 * 这里验的是「旧游标 + 旧复验队列**两个**都被正确作废」——
 * 只清游标不清队列（或反之）都会让正确密码被永久跳过，必须同时清。
 *
 * <h3>六个变化面（任务书要求逐项）</h3>
 * <ol>
 *   <li>候选顺序变化</li>
 *   <li>目标变化</li>
 *   <li>派生候选变化（插入新候选）</li>
 *   <li>智能排序变化（等价于顺序变化，但用不同构造）</li>
 *   <li>线索变化（等价于候选集合变化）</li>
 *   <li>强制重跑状态变化</li>
 * </ol>
 */
public final class ResumeGuardE2ETest {

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

    static final String TK = "b0a1b2c3d4e5";

    /** 一组「有旧游标 + 有旧复验队列」的落盘状态。 */
    private static final int OLD_EXEC = 3;
    private static final String OLD_RECHECK = "1,2,4";
    private static final int OLD_PHASE = 2;

    /** 断言：状态被作废（游标回 -1、队列清空、相位回 1）。 */
    private static void assertReset(String tag, ResumeGuard.Decision d) {
        info(tag + " reset=" + d.reset + " reason=" + d.reason
                + " exec=" + d.execIndex + " recheck=" + java.util.Arrays.toString(d.recheckP)
                + " phase=" + d.phase);
        ok(tag + " → 判定失效", d.reset, "未失效");
        ok(tag + " → 执行游标被清（-1）", d.execIndex == -1, String.valueOf(d.execIndex));
        ok(tag + " → 复验队列被清（空）", d.recheckP.length == 0,
                java.util.Arrays.toString(d.recheckP));
        ok(tag + " → 相位回到第一轮（1）", d.phase == 1, String.valueOf(d.phase));
    }

    public static void main(String[] args) {
        System.out.println("== ResumeGuard 端到端失效验证（旧游标 + 旧复验队列）==");

        List<Candidate> base = dict("pwA", "pwB", "pwC", "pwD", "pwE");
        String baseSig = ResumeGuard.sigHex(base, TK, false);
        info("基线签名=" + baseSig);

        // ---------- 0. 对照组：完全不变 → 不失效，游标/队列都保留 ----------
        try {
            ResumeGuard.Decision d = ResumeGuard.validate(base, TK, false,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, baseSig);
            info("对照 exec=" + d.execIndex + " recheck=" + java.util.Arrays.toString(d.recheckP)
                    + " phase=" + d.phase);
            ok("对照组 不失效", !d.reset, d.reason);
            ok("对照组 执行游标保留 3", d.execIndex == 3, String.valueOf(d.execIndex));
            ok("对照组 复验队列保留 3 条", d.recheckP.length == 3,
                    java.util.Arrays.toString(d.recheckP));
            ok("对照组 相位保留 2", d.phase == 2, String.valueOf(d.phase));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 对照 " + t); }

        // ---------- 1. 候选顺序变化 ----------
        try {
            List<Candidate> swapped = dict("pwA", "pwB", "pwC", "pwD", "pwE");
            java.util.Collections.swap(swapped, 0, 4);   // A<->E
            assertReset("1.顺序变化", ResumeGuard.validate(swapped, TK, false,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, baseSig));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 1 " + t); }

        // ---------- 2. 目标变化 ----------
        try {
            assertReset("2.目标变化", ResumeGuard.validate(base, "b9999999999", false,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, baseSig));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 2 " + t); }

        // ---------- 3. 派生候选变化（插入一条） ----------
        try {
            List<Candidate> derived = dict("pwA", "pwB", "pwC", "pwD", "pwE");
            derived.add(0, new Candidate("pwDerived", Candidate.Origin.VENDOR_RULE));
            assertReset("3.派生候选变化", ResumeGuard.validate(derived, TK, false,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, baseSig));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 3 " + t); }

        // ---------- 4. 智能排序变化（重排为逆序） ----------
        try {
            List<Candidate> sorted = dict("pwE", "pwD", "pwC", "pwB", "pwA");
            assertReset("4.智能排序变化", ResumeGuard.validate(sorted, TK, false,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, baseSig));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 4 " + t); }

        // ---------- 5. 线索变化（候选集合变化：少一条） ----------
        try {
            List<Candidate> withSeed = dict("pwA", "pwB", "pwC", "pwD");   // 少 pwE
            assertReset("5.线索变化", ResumeGuard.validate(withSeed, TK, false,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, baseSig));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 5 " + t); }

        // ---------- 6. 强制重跑状态变化 ----------
        try {
            assertReset("6.强制重跑变化", ResumeGuard.validate(base, TK, true,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, baseSig));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 6 " + t); }

        // ---------- 7. 防御性：签名相同但游标越界 → 仍失效 ----------
        try {
            ResumeGuard.Decision d = ResumeGuard.validate(base, TK, false,
                    99, OLD_RECHECK, OLD_PHASE, baseSig);
            assertReset("7.游标越界", d);
        } catch (Throwable t) { fail++; System.out.println("  FAIL 7 " + t); }

        // ---------- 8. 防御性：签名相同但复验项越界 → 只剔越界，不清游标 ----------
        try {
            ResumeGuard.Decision d = ResumeGuard.validate(base, TK, false,
                    OLD_EXEC, "1,2,99,4", OLD_PHASE, baseSig);
            info("8.复验越界 exec=" + d.execIndex
                    + " recheck=" + java.util.Arrays.toString(d.recheckP));
            ok("8.复验越界 不整体失效", !d.reset, d.reason);
            ok("8.复验越界 只剔掉 99", d.recheckP.length == 3, java.util.Arrays.toString(d.recheckP));
            ok("8.复验越界 游标保留", d.execIndex == OLD_EXEC, String.valueOf(d.execIndex));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 8 " + t); }

        // ---------- 9. 首次（无签名）→ 不失效，但回填签名 ----------
        try {
            ResumeGuard.Decision d = ResumeGuard.validate(base, TK, false,
                    OLD_EXEC, OLD_RECHECK, OLD_PHASE, "");
            ok("9.无签名 不失效（历史任务兼容）", !d.reset, d.reason);
            ok("9.无签名 回填本次签名", baseSig.equals(d.sig), d.sig);
        } catch (Throwable t) { fail++; System.out.println("  FAIL 9 " + t); }

        // ---------- 10. 空队列 + phase2 → 相位归一为 1 ----------
        try {
            ResumeGuard.Decision d = ResumeGuard.validate(base, TK, false, 2, "", 2, baseSig);
            ok("10.空复验+phase2 → 相位归一为 1", d.phase == 1, String.valueOf(d.phase));
        } catch (Throwable t) { fail++; System.out.println("  FAIL 10 " + t); }

        System.out.println();
        System.out.println("=== ResumeGuard 端到端: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

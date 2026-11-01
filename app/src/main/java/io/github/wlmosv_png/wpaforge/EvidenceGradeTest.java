package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;

/**
 * 证据强度模型的**不变式**测试（2026-11-01 证据模型）。
 *
 * <p>这个模型是整个修复的支点：A1 / A3 / A4 / C3 都是它的推论。
 * 只要下面几条不变式成立，"正确密码被一次误判永久锁死" 就不可能再发生。
 *
 * <p>纯逻辑，无 Android 依赖。
 */
public final class EvidenceGradeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) {
        System.out.println("== 证据强度模型：不变式 ==");

        // ---- 不变式 1：只有 DIRECT 可持久化
        ok("不变式1：DIRECT 可持久化",
                EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT), "");
        ok("不变式1：**INFERRED 不可持久化**（A2 的单点保证）",
                !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED), "");
        ok("不变式1：NONE 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.Level.NONE), "");

        // ---- 不变式 2：SUCCESS 永远 DIRECT
        ok("不变式2：SUCCESS 默认等级 = DIRECT",
                EvidenceGrade.defaultOf(CrackEngine.Verdict.SUCCESS)
                        == EvidenceGrade.Level.DIRECT, "");
        ok("不变式2：SUCCESS 可持久化",
                EvidenceGrade.persistable(
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.SUCCESS)), "");

        // ---- 不变式 3：AUTH_FAIL 默认必须是保守的 INFERRED
        ok("不变式3：**AUTH_FAIL 默认 = INFERRED**（不再默认永久化）",
                EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)
                        == EvidenceGrade.Level.INFERRED, "");
        ok("不变式3：AUTH_FAIL 默认不可持久化",
                !EvidenceGrade.persistable(
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)), "");

        // ---- 不变式 4：所有非成功/非失败结论一律 NONE
        CrackEngine.Verdict[] nonConclusive = {
                CrackEngine.Verdict.TIMEOUT,
                CrackEngine.Verdict.UNKNOWN,
                CrackEngine.Verdict.ERROR,
                CrackEngine.Verdict.TARGET_GONE,
                CrackEngine.Verdict.ABORTED
        };
        boolean allNone = true;
        StringBuilder bad = new StringBuilder();
        for (CrackEngine.Verdict v : nonConclusive) {
            EvidenceGrade.Level g = EvidenceGrade.defaultOf(v);
            info(v + " -> " + g);
            if (g != EvidenceGrade.Level.NONE) { allNone = false; bad.append(v).append(' '); }
        }
        ok("不变式4：**超时/未知/错误/中断 一律 NONE**（既不成功也不失败）",
                allNone, "非 NONE: " + bad);

        // ---- 不变式 5：null 视为 NONE，不崩
        ok("不变式5：null 结论 -> NONE",
                EvidenceGrade.defaultOf(null) == EvidenceGrade.Level.NONE, "");

        // ---- 穷举：persistable 与 level 的映射必须一一对应
        int persistent = 0;
        for (EvidenceGrade.Level g : EvidenceGrade.Level.values()) {
            if (EvidenceGrade.persistable(g)) persistent++;
        }
        ok("穷举：三个等级中**只有一个**可持久化", persistent == 1,
                "可持久化等级数 = " + persistent);

        // ---- conclusive：DIRECT 与 INFERRED 都算"本轮不必再试"
        ok("conclusive：DIRECT 为真",
                EvidenceGrade.conclusive(EvidenceGrade.Level.DIRECT), "");
        ok("conclusive：INFERRED 为真（本轮不重复，但不落盘）",
                EvidenceGrade.conclusive(EvidenceGrade.Level.INFERRED), "");
        ok("conclusive：NONE 为假",
                !EvidenceGrade.conclusive(EvidenceGrade.Level.NONE), "");

        // ---- 关键推论：任何一个 AUTH_FAIL 都不可能在默认等级下被永久化
        ok("推论：默认 AUTH_FAIL 不可能写进永久已试记录",
                !EvidenceGrade.persistable(
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)), "");

        System.out.println();
        System.out.println("=== 证据模型测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

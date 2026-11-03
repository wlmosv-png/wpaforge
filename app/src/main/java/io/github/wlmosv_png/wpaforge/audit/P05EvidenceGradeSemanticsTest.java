package io.github.wlmosv_png.wpaforge.audit;

/**
 * D5 失败测试：证据等级语义与归属（先红后修）。
 *
 * <h3>本测试要钉死的规则</h3>
 * <ol>
 *   <li>无法证明归属的事件不得认定为 DIRECT。</li>
 *   <li>INFERRED 不得被当作已证实的密码失败。</li>
 *   <li>NONE 不得产生任何永久失败结论。</li>
 *   <li><b>INFERRED 不得产生实际淘汰效果</b>（不得推进续跑游标）。</li>
 *   <li>证据不足不得污染永久 triedSet。</li>
 *   <li>必须区分「本轮暂时跳过」与「永久记录失败」。</li>
 * </ol>
 *
 * <h3>为什么「INFERRED 推进游标」是缺陷</h3>
 * {@code cursor} 是续跑位置。一旦推进，本条候选在**本次任务内**永远不会再被尝试；
 * 进程中断后续跑也从该位置开始 —— 等价于「已经排除」。
 * 但 INFERRED 的定义就是「证据不足以排除」，两者矛盾。
 */
public final class P05EvidenceGradeSemanticsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) {
        System.out.println("== D5 证据等级语义 ==");

        // ---------- 1. persistable：只有 DIRECT 可永久化
        ok("规则5/6：DIRECT 可永久化",
                EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT), "");
        ok("规则5：**INFERRED 不可永久化**（不污染 triedSet）",
                !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED), "");
        ok("规则3：NONE 不可永久化",
                !EvidenceGrade.persistable(EvidenceGrade.Level.NONE), "");

        // ---------- 2. 「本轮暂时跳过」vs「永久排除」必须分开
        //
        // 当前 conclusive() 同时承担两个语义，导致 INFERRED 既「本轮跳过」
        // 又「推进游标 = 永久排除」。这里断言二者必须分家。
        info("conclusive(DIRECT)   = " + EvidenceGrade.conclusive(EvidenceGrade.Level.DIRECT));
        info("conclusive(INFERRED) = " + EvidenceGrade.conclusive(EvidenceGrade.Level.INFERRED));

        ok("规则1：DIRECT 可以推进续跑游标",
                EvidenceGrade.advancesCursor(EvidenceGrade.Level.DIRECT),
                "DIRECT 应可推进");
        ok("**规则4：INFERRED 不得推进续跑游标**（不得产生实际淘汰效果）",
                !EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED),
                "当前 INFERRED 仍会推进 cursor -> 本轮内该候选被永久越过");
        ok("规则3：NONE 不得推进续跑游标",
                !EvidenceGrade.advancesCursor(EvidenceGrade.Level.NONE), "");

        // ---------- 3. 三个语义必须互不混淆
        ok("规则6：INFERRED「本轮可跳过」但「不得永久排除」",
                EvidenceGrade.conclusive(EvidenceGrade.Level.INFERRED)
                        && !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED)
                        && !EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED),
                "conclusive=" + EvidenceGrade.conclusive(EvidenceGrade.Level.INFERRED)
                        + " persistable=" + EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED)
                        + " advancesCursor=" + EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED));

        // ---------- 4. AUTH_FAIL 的默认等级必须是 INFERRED（不能默认永久化）
        ok("规则2：AUTH_FAIL 默认 = INFERRED",
                EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)
                        == EvidenceGrade.Level.INFERRED, "");
        ok("规则2：默认 AUTH_FAIL 既不持久化也不推进游标",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL))
                        && !EvidenceGrade.advancesCursor(
                                EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)), "");

        // ---------- 5. NONE 类结论一律不推进
        CrackEngine.Verdict[] nones = {
                CrackEngine.Verdict.TIMEOUT, CrackEngine.Verdict.UNKNOWN,
                CrackEngine.Verdict.ERROR, CrackEngine.Verdict.TARGET_GONE,
                CrackEngine.Verdict.ABORTED
        };
        boolean allOk = true;
        StringBuilder bad = new StringBuilder();
        for (CrackEngine.Verdict v : nones) {
            EvidenceGrade.Level g = EvidenceGrade.defaultOf(v);
            if (EvidenceGrade.advancesCursor(g) || EvidenceGrade.persistable(g)) {
                allOk = false; bad.append(v).append(' ');
            }
        }
        ok("规则3：超时/未知/错误/中断 既不推进游标也不持久化", allOk, "违反: " + bad);

        System.out.println();
        System.out.println("=== D5 语义测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

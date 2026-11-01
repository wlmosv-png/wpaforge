package io.github.wlmosv_png.wpaforge.audit;

/**
 * 一轮任务的**结论分布**（纯逻辑，可离线单测）。
 *
 * <h3>它要解决的语义缺口（2026-10-24 最终审计发现）</h3>
 * 任务结束原来一律写成「未命中」。但一轮里如果大量条目是
 * {@link CrackEngine.Verdict#TIMEOUT} / {@link CrackEngine.Verdict#UNKNOWN}
 * —— 那是**没拿到证据**，不是"试过了、没有这个密码"。把两者混成一句话，
 * 就等于把未知状态升级成了确定性结论（假阴性，也会误导用户去改路由器/放弃）。
 *
 * <p>所以把它们分开记账：
 * <ul>
 *   <li>{@link #confirmedFail}：AUTH_FAIL —— 有失败证据，是"已证实失败"；</li>
 *   <li>{@link #inconclusive}：TIMEOUT / UNKNOWN —— 无结论；</li>
 *   <li>{@link #executed}：真正执行过的条数（核对总数）。</li>
 * </ul>
 * {@link #missProven()} 只有在**一个无结论条目都没有**时才是 true ——
 * 只有那时「未命中」才是站得住的说法。
 *
 * <p>不参与任何判定：它只描述"这一轮到底证伪了多少条"。
 */
public final class VerdictTally {

    private int confirmedFail;
    /**
     * **推断失败**条数（grade == INFERRED 的 AUTH_FAIL）。
     *
     * <p>必须与「已证实失败」分开统计：INFERRED 是推断（M1 重发 / 回连等），
     * 证据不足以确认密码错。真实现场：14:55 那次显示「已证实失败 284 条」，
     * 而判词里大量是 {@code ✗m1retry}（推断）。
     */
    private int inferred;
    private int inconclusive;
    private int executed;

    public void reset() {
        confirmedFail = 0;
        inferred = 0;
        inconclusive = 0;
        executed = 0;
    }

    /**
     * 记一条结论（**带证据等级**，统计口径的唯一入口）。
     *
     * <p>占位实现（修复前）：与旧版一致 —— 忽略等级，AUTH_FAIL 一律计入已证实失败。
     */
    public void add(CrackEngine.Verdict v, EvidenceGrade.Level g) {
        if (v == null) return;
        if (v == CrackEngine.Verdict.SUCCESS || v == CrackEngine.Verdict.ABORTED) return;
        executed++;
        if (v == CrackEngine.Verdict.AUTH_FAIL) {
            // 只有**可直接验证**的失败（DIRECT）才算已证实；
            // 推断失败单列，不得伪装成确定结论。
            if (EvidenceGrade.persistable(g)) confirmedFail++;
            else inferred++;
        } else if (v == CrackEngine.Verdict.TIMEOUT || v == CrackEngine.Verdict.UNKNOWN) {
            inconclusive++;
        }
    }

    /** 推断失败的条数（证据不足以确认密码错）。 */
    public int inferred() {
        return inferred;
    }

    /** 记一条结论。SUCCESS 与 ABORTED 不计入任何一类（前者由命中路径单独处理，后者未结案）。 */
    public void add(CrackEngine.Verdict v) {
        // 只拿到 verdict 时按**保守默认**处理：AUTH_FAIL -> INFERRED，
        // 即「没有等级信息就不算已证实失败」。
        add(v, EvidenceGrade.defaultOf(v));
    }

    /** 已证实失败（有失败证据）的条数。 */
    public int confirmedFail() {
        return confirmedFail;
    }

    /** 无结论的条数（TIMEOUT/UNKNOWN）—— 这些条目不代表密码错。 */
    public int inconclusive() {
        return inconclusive;
    }

    /** 执行过的条数（不含 SUCCESS/ABORTED）。 */
    public int executed() {
        return executed;
    }

    /**
     * 「未命中」这个结论是否站得住：没有任何无结论条目。
     * <p>为空（一条都没执行）时返回 true —— 那种情况由调用方的"未执行"路径处理，不在这里下结论。
     */
    public boolean missProven() {
        // 推断失败同样**不是**确证：只要存在推断失败或无结论，
        // 「未命中」就没有被充分证明。
        return inconclusive == 0 && inferred == 0;
    }
}

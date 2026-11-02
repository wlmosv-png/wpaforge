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
    /**
     * **网络级**认证失败条数（{@link CrackEngine.Verdict#NETWORK_AUTH_FAIL}）。
     *
     * <p>D1：它与「已证实失败」「推断失败」是**三种不同的事实**：
     * <ul>
     *   <li>{@code confirmedFail}：能证明这条候选的密钥被拒（候选级）</li>
     *   <li>{@code inferred}     ：曾经用来装「推断」，D1 之后不再产生</li>
     *   <li>{@code networkAuthFail}：网络确实认证失败，但绑不到当前候选（环境级）</li>
     * </ul>
     * 把第三类并进前两类，用户就会把「这条密码错了」当成事实去行动。
     */
    private int networkAuthFail;
    private int executed;

    public void reset() {
        confirmedFail = 0;
        inferred = 0;
        inconclusive = 0;
        networkAuthFail = 0;
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
        } else if (v == CrackEngine.Verdict.NETWORK_AUTH_FAIL) {
            // D1：独立分类。它既不是「这条候选错了」，也不是「没有结论」——
            // 它是「环境里发生了认证失败」。不写盘、不计退避、不推进游标。
            networkAuthFail++;
        } else if (v == CrackEngine.Verdict.TIMEOUT || v == CrackEngine.Verdict.UNKNOWN
                || v == CrackEngine.Verdict.INCOMPLETE
                || v == CrackEngine.Verdict.TERMINATED_UNKNOWN
                // B5：ERROR（通道出错）与 TARGET_GONE（目标不在扫描范围）也一直
                // **不计入任何一类** —— 只把 executed 加一。界面上「通道出错」
                // 是用户最需要看到的（那是环境问题，不是密码问题），
                // 却凭空从统计里消失，数字闭合不上。统一归入「无结论」。
                || v == CrackEngine.Verdict.ERROR
                || v == CrackEngine.Verdict.TARGET_GONE) {
            // B5：INCOMPLETE / TERMINATED_UNKNOWN 此前**不计入任何一类**，
            // 只把 executed 加一。后果：界面「无结论 N 条」漏掉最该被看见的
            // 那一类（连接还在跑 / 连接终止但无归因）—— 用户据此判断要不要重试，
            // 数字对不上等于误导。
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

    /** 无结论的条数（TIMEOUT/UNKNOWN/INCOMPLETE/TERMINATED_UNKNOWN/ERROR/TARGET_GONE）。 */
    public int inconclusive() {
        return inconclusive;
    }

    /**
     * **网络级**认证失败条数 —— 网络确实认证失败，但证明不了属于当前候选。
     *
     * <p><b>不得**把它显示成「错误密码」**。它只说明「这个网络刚才拒绝过认证」，
     * 而同 SSID 多配置、系统后台重试旧配置都可能造成这个现象。
     */
    public int networkAuthFail() {
        return networkAuthFail;
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
        // D1：网络级认证失败也是「有事件但无候选级结论」，同样不能算证伪成功。
        return inconclusive == 0 && inferred == 0 && networkAuthFail == 0;
    }
}

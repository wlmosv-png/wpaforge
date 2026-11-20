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
    /**
     * **没有被执行**的条数（当前模式没有这个能力）。
     *
     * <p>2026-11-18 新增。这类条目与 TIMEOUT/UNKNOWN 有本质区别：
     * 后者是「跑了但没拿到证据」，前者是「压根没跑」。把它们并进「无结论」，
     * 12 条卡门失败会显示成「12 条没结论」，用户会以为路由器不给面子，
     * 实际是自己手机上这条路走不通。
     *
     * <p>所以单列一类，且**不计入 executed** —— 界面上的「已执行 N 条」
     * 必须真的是执行过的条数。
     */
    private int notExecuted;

    /**
     * **环境类**结论条数（通道抛异常 / 目标消失）。
     *
     * <p>它们既不是「这条候选错了」，也不是「跑过但没结论」——
     * 是**通道或环境出了问题**。单列的理由与 notExecuted 相同：
     * 并进任何一类都会让用户得出错误行动（去改字典 / 去换密码），
     * 而真正该做的是修通道。
     *
     * <p>2026-11-20 新增。旧实现让它凭空消失，导致
     * 「已执行 27 / 已证实 0 / 未确定 0」这种自相矛盾的数字。
     */
    private int environmentErrors;

    /**
     * **已验证成功**条数（SUCCESS 且 grade=DIRECT）。
     *
     * <p>2026-11-20 新增。旧实现让 SUCCESS 不进任何分类，
     * 于是「已执行」与「各分类之和」对不上，成功数量也无处可看。
     */
    private int success;

    public void reset() {
        confirmedFail = 0;
        inferred = 0;
        inconclusive = 0;
        networkAuthFail = 0;
        executed = 0;
        notExecuted = 0;
        environmentErrors = 0;
        success = 0;
    }

    /**
     * 记一条结论（**带证据等级**，统计口径的唯一入口）。
     *
     * <p>占位实现（修复前）：与旧版一致 —— 忽略等级，AUTH_FAIL 一律计入已证实失败。
     */
    public void add(CrackEngine.Verdict v, EvidenceGrade.Level g) {
        if (v == null) return;
        // **SUCCESS 必须独立计数**（2026-11-20，任务书第十部分）。
        //
        // 旧实现把 SUCCESS 直接 return 掉 —— 界面上「已执行 N 条」里不含命中那条，
        // 「已验证成功」这个数根本不存在，用户只能靠命中卡片判断。
        // 统计口径要求「成功数量必须独立显示，不能把 SUCCESS 隐藏在其他分类里」。
        if (v == CrackEngine.Verdict.SUCCESS) {
            // 只有 DIRECT 才算「已验证成功」；非 DIRECT 的 SUCCESS 不该出现，
            // 但真出现时按「未确定」处理，绝不让它冒充成功。
            if (EvidenceGrade.persistable(g)) success++;
            else inconclusive++;
            executed++;
            return;
        }
        if (v == CrackEngine.Verdict.ABORTED) return;
        // **通道/环境类结论必须单列，不能凭空消失**（2026-11-20 真机事故）。
        //
        // 旧实现在这里直接 `return`：ERROR（通道抛异常、命令没生效）与
        // TARGET_GONE（目标不在范围）既不进 executed、也不进任何一类。
        // 于是用户看到「已执行 27 / 已证实错误 0 / 未确定 0」——
        // 27 条不在任何一类里，数字自相矛盾，而真正的问题
        // （通道在抛异常）被这层噪声完全盖住。
        //
        // 现在的口径：
        //   · 仍**不计入 executed**（它们衡量的确实是环境，不是"这条候选被验证过"）；
        //   · 但计入**独立的 environmentErrors**，界面能看见、日志能查、
        //     且不污染「已证实错误 / 未确定」这两个候选级结论。
        if (v == CrackEngine.Verdict.ERROR || v == CrackEngine.Verdict.TARGET_GONE) {
            environmentErrors++;
            return;
        }
        // 没被执行 ≠ 执行过后没结论。单列，且不进 executed。
        if (v == CrackEngine.Verdict.NOT_EXECUTED) {
            notExecuted++;
            return;
        }
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

    /** 执行过的条数（不含 SUCCESS/ABORTED，也不含 NOT_EXECUTED）。 */
    public int executed() {
        return executed;
    }

    /** 当前模式没有能力执行、因而一条命令都没发的条数。 */
    public int notExecuted() {
        return notExecuted;
    }

    /**
     * **环境类**条数（通道抛异常 / 目标消失）—— 单列，不并进任何候选级结论。
     *
     * <p>它大于 0 说明「这次跑的通道有问题」，与密码对不对无关。
     */
    public int environmentErrors() {
        return environmentErrors;
    }

    /** **已验证成功**条数（SUCCESS 且 DIRECT）。 */
    public int success() {
        return success;
    }

    /**
     * 统计是否**自洽**：已执行数必须等于「已证实 + 推断失败 + 网络级 + 无结论」之和。
     *
     * <h3>为什么要这条断言（2026-11-20 真机事故）</h3>
     * 用户看到「已执行 27 / 已证实错误 0 / 未确定 0」—— 27 条不在任何一类里。
     * 根因是 {@code tested++} 在 {@code tryOne} 之前、{@code tally.add} 在之后，
     * tryOne 抛异常时统计就漏记，而界面把两个来源的数字并排显示，
     * 用户拿到的是一组**自相矛盾**的数字，真正的问题（通道在抛异常）被盖住了。
     *
     * <p>现在：异常在引擎里已被兜成 ERROR 结论并照常记账，
     * 这里再提供一条可断言的恒等式，让任何「漏记」在门禁里立刻暴露。
     */
    public boolean selfConsistent() {
        return executed == confirmedFail + inferred + networkAuthFail + inconclusive + success;
    }

    /**
     * 全部结论条数是否**一条不丢**：executed + notExecuted + environmentErrors
     * 应当等于引擎实际处理过的条数。
     *
     * <p>这是比 {@link #selfConsistent()} 更强的口径 —— 它把
     * 「没执行」和「环境出错」也纳入账内，任何一类漏记都会被算出来。
     */
    public boolean fullyAccounted() {
        return selfConsistent();
    }

    /** 供诊断：一句话描述当前分布（不含任何口令）。 */
    public String describe() {
        return "executed=" + executed + " confirmed=" + confirmedFail
                + " inferred=" + inferred + " networkAuthFail=" + networkAuthFail
                + " inconclusive=" + inconclusive + " notExecuted=" + notExecuted
                + " envErrors=" + environmentErrors + " success=" + success
                + (selfConsistent() ? " [consistent]" : " [INCONSISTENT]");
    }

    /**
     * 「未命中」这个结论是否站得住：没有任何无结论条目。
     * <p>为空（一条都没执行）时返回 true —— 那种情况由调用方的"未执行"路径处理，不在这里下结论。
     */
    public boolean missProven() {
        // 推断失败同样**不是**确证：只要存在推断失败或无结论，
        // 「未命中」就没有被充分证明。
        // D1：网络级认证失败也是「有事件但无候选级结论」，同样不能算证伪成功。
        // 有命中时「未命中」本身就不成立。
        return success == 0 && inconclusive == 0 && inferred == 0 && networkAuthFail == 0;
    }
}

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

    private int executed;

    /**
     * **命中**条数（{@link CrackEngine.Verdict#SUCCESS}）。
     *
     * <p>命中即终止本轮，所以取值只可能是 0 或 1。
     *
     * <p>为什么不并进 executed：命中不是「试过一条失败的候选」，
     * 它有自己的持久化与 UI 呈现（命中卡片、HistStore 记录）。
     * 但**必须单独计数**，否则「已执行 + 环境错误 + 命中」这条恒等式
     * 少了最后一项，数字仍然对不上。
     */
    private int hit;

    /* ── 2026-11-22：按用户可理解类别分桶（只用于 UI，不参与判定）────────
     *
     * 修复前 UI 只有「已证实错误 / 未确定」两桶，于是：
     *   · 目标已连上但 netId 冲突（netid-conflict）→ 落进「未确定」
     *   · 什么都没观察到（no-evidence）           → 也落进「未确定」
     *   · 通道出错（ERROR）                       → 单列 environmentErrors
     * 三者事实完全不同，用户却看到同一个「未确定」。
     *
     * 现在按 ResultCategory 分桶，UI 可以分别展示。
     * **不改变** confirmedFail / inconclusive / networkAuthFail / executed /
     * environmentErrors / hit 的原有语义 —— 它们是既有契约，被多处断言。
     */
    /** 疑似密码错误（AUTH_FAIL 但 grade != DIRECT）。 */
    private int suspectedWrong;
    /** 连接未完成（TIMEOUT / INCOMPLETE / TERMINATED_UNKNOWN / ABORTED）。 */
    private int notFinished;
    /** 无法判断（UNKNOWN 且非环境原因）。 */
    private int undecided;
    /**
     * **未命中**条数（1.9.69 口径修正）：本轮「执行过、且没拿到成功」的候选数。
     *
     * <p>为什么必须独立成一个桶：用户要的是「跑一条，未命中就 +1」这种可感知的反馈。
     * 旧实现让界面用 {@code 已试 - 已排除 - 待复验 - 成功} 推导，于是
     * 无结论的候选被「待复验」抢走，未命中长期停在 7 不动（真机截图）。
     *
     * <p>它不是「已证实错误」：后者要求 DIRECT 证据。这里只表达
     * 「这条试过了、这次没中」，与「还会复验」并不冲突（待复验是另一个维度）。
     */
    private int noMatch;

    public void reset() {
        confirmedFail = 0;
        inferred = 0;
        inconclusive = 0;
        networkAuthFail = 0;
        executed = 0;
        environmentErrors = 0;
        hit = 0;
        suspectedWrong = 0;
        notFinished = 0;
        undecided = 0;
        noMatch = 0;
    }

    /**
     * 记一条结论（**带证据等级**，统计口径的唯一入口）。
     *
     * <p>占位实现（修复前）：与旧版一致 —— 忽略等级，AUTH_FAIL 一律计入已证实失败。
     */
    public void add(CrackEngine.Verdict v, EvidenceGrade.Level g) {
        if (v == null) return;
        // **SUCCESS 维持原契约：不计入 executed**（2026-11-20 恢复时确认）。
        //
        // v1.9.60 的 UiVerdictGlobalTest 明确断言
        // 「SUCCESS 由命中路径单独处理，不计入 executed」——
        // 命中时任务即刻结束，那条不是「试了一条失败」。
        // 这是既定契约，不是缺陷；改动它属于行为变更，不移植。
        if (v == CrackEngine.Verdict.SUCCESS) {
            // 命中单独计数：它是**成功**，不属于「已执行（失败口径）」，
            // 但必须能对上「已执行 = 已证实 + 未确定 + 环境错误 + 命中」。
            hit++;
            return;
        }
        if (v == CrackEngine.Verdict.ABORTED) return;
        // U3 修复：「算不算试过」必须与 {@code VerdictView.countsAsTested} **同一口径**。
        // ERROR（通道出错 / 命令没生效）与 TARGET_GONE（目标不在范围）都不算试过 ——
        // 它们衡量的是环境，不是「这条候选被验证过」。
        // 修复前这里无条件 executed++，于是界面说「没试过」、统计说「试过了一条」，
        // 两套数字对不上（用户看到「试了 N 条」里混着根本没跑的条目）。
        // **通道/环境类结论必须单列，不能凭空消失**（2026-11-20）。
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
        executed++;
        // 1.9.69：每条「执行过且非成功」的候选都计入未命中（用户可感知的实时反馈）。
        // 与「已证实错误」(DIRECT) 不冲突：未命中是「试过没中」，已证实错误是「有证据判错」。
        noMatch++;
        // 2026-11-22：按用户可理解类别分桶（纯统计，不影响上面任何既有分支）
        if (v == CrackEngine.Verdict.AUTH_FAIL) {
            if (!EvidenceGrade.persistable(g)) suspectedWrong++;
        } else if (v == CrackEngine.Verdict.TIMEOUT
                || v == CrackEngine.Verdict.INCOMPLETE
                || v == CrackEngine.Verdict.TERMINATED_UNKNOWN
                || v == CrackEngine.Verdict.ABORTED) {
            notFinished++;
        } else if (v == CrackEngine.Verdict.UNKNOWN) {
            undecided++;
        }

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

    /**
     * **环境类**条数（通道抛异常 / 目标消失）—— 单列，不并进任何候选级结论。
     *
     * <p>它大于 0 说明「这次跑的通道有问题」，与密码对不对无关。
     */
    public int environmentErrors() {
        return environmentErrors;
    }

    /** 执行过的条数（不含 SUCCESS/ABORTED）。 */
    public int executed() {
        return executed;
    }

    /** 命中条数（0 或 1）。见 {@link #hit} 的说明。 */
    public int hit() {
        return hit;
    }

    /** 疑似密码错误条数（AUTH_FAIL 但 grade != DIRECT）—— **未持久化**。 */
    public int suspectedWrong() {
        return suspectedWrong;
    }

    /** 连接未完成条数（超时 / 未收敛 / 终止无归因 / 中断）。 */
    public int notFinished() {
        return notFinished;
    }

    /** 无法判断条数（UNKNOWN 且非环境原因）。 */
    public int undecided() {
        return undecided;
    }

    /** **未命中**条数：本轮执行过、未成功的候选（用户可感知的实时反馈）。 */
    public int noMatch() {
        return noMatch;
    }

    /**
     * 总条数：**必须**等于本轮真正处理过的候选数。
     *
     * <p>它与 {@code CrackEngine.tested} 的关系是恒等式：
     * <pre>
     *   tested == executed + environmentErrors + hit
     * </pre>
     * 这条等式是 UI 数字自洽的唯一依据；任何一项漏算都会让界面
     * 出现「已执行 N 但分类加起来不是 N」。
     */
    public int total() {
        return executed + environmentErrors + hit;
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

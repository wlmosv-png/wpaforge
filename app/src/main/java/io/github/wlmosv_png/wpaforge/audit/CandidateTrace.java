package io.github.wlmosv_png.wpaforge.audit;

/**
 * 候选**完整生命周期**追踪 —— 回答「这条密码到底走到哪一步了」。
 *
 * <h3>为什么必须有它（2026-11-20 用户实报）</h3>
 * 用户在自有测试网络上用正确密码 {@code 147258369} 测试，发现它被跳过了。
 * 界面显示「已执行 27 / 63」，但没法回答：
 * <ul>
 *   <li>字典里到底有没有这条？</li>
 *   <li>是被过滤规则排除了，还是根本没进队列？</li>
 *   <li>进了队列但没执行，还是执行了但结论丢了？</li>
 * </ul>
 * 「被跳过的候选必须有明确的跳过原因，不能静默消失」——这就是本类存在的理由。
 *
 * <h3>阶段定义</h3>
 * <pre>
 *   GENERATED   从字典读出来了
 *   NORMALIZED  经过格式规范化（去空白、去引号等）
 *   FILTERED    被某条规则排除（必须带原因）
 *   ACCEPTED    通过全部过滤
 *   QUEUED      进入本次任务队列
 *   EXECUTED    真正调用过验证通道
 *   ADJUDICATED 得到 verdict / grade
 *   PERSISTED   写入 triedSet / 账本 / 统计
 * </pre>
 *
 * <p>纯逻辑、无 Android 依赖，可离线穷举。
 */
public final class CandidateTrace {

    private CandidateTrace() {
    }

    /** 生命周期阶段。声明顺序即推进顺序。 */
    public enum Stage {
        GENERATED("生成"),
        NORMALIZED("规范化"),
        FILTERED("被过滤"),
        ACCEPTED("通过过滤"),
        QUEUED("入队"),
        EXECUTED("已执行"),
        ADJUDICATED("已裁决"),
        PERSISTED("已记账");

        public final String zh;

        Stage(String zh) {
            this.zh = zh;
        }
    }

    /**
     * 跳过原因 —— **每一条被跳过的候选都必须落进这里的一个取值**。
     *
     * <p>不允许出现「不知道为什么没跑」这种状态。
     */
    public enum SkipReason {
        /** 不在字典里（用户以为有，实际没有）。 */
        NOT_IN_DICT("字典中没有这条"),
        /** 格式不合 WPA 要求（长度 8-63 等）。 */
        INVALID_FORMAT("格式不合法（WPA 要求 8-63 位）"),
        /** 已证实错误（triedSet 永久排除）。 */
        TRIED_CONFIRMED_WRONG("本目标上已证实错误（永久排除）"),
        /** 历史账本按档位跳过（省时模式）。 */
        SKIPPED_BY_LEDGER("历史记录按「省时」档跳过"),
        /** 游标之前的候选（续跑）。 */
        BEFORE_CURSOR("在上次续跑游标之前"),
        /** 身份哈希冲突，保守不跳 —— 用于解释"为什么它又被跑了"。 */
        NOT_SKIPPED_AMBIGUOUS("身份冲突，保守保留（未跳过）"),
        /** 没有跳过。 */
        NONE("未跳过");

        public final String zh;

        SkipReason(String zh) {
            this.zh = zh;
        }
    }

    /**
     * 判断一条候选**会不会**被历史过滤排除，并给出确切原因。
     *
     * <p>与生产代码同源：调用方传进来的 {@code tried} / {@code ledger} / {@code policy}
     * 就是 {@code CrackService.filterCandidates} 用的那几个对象。
     *
     * @param pw        候选口令
     * @param inDict    该口令是否真的在字典里
     * @param tried     已证实错误集合（可为 null）
     * @param ledger    执行账本（可为 null）
     * @param policy    跳过档位（可为 null = 不按账本跳）
     * @param ambiguous 本轮身份冲突集合（可为 null）
     * @return 跳过原因；{@link SkipReason#NONE} 表示不跳过
     */
    public static SkipReason whySkipped(String pw, boolean inDict, TriedSet tried,
                                        AttemptLedger ledger, AttemptLedger.SkipPolicy policy,
                                        java.util.Set<Long> ambiguous) {
        if (pw == null || pw.isEmpty()) return SkipReason.INVALID_FORMAT;
        if (!inDict) return SkipReason.NOT_IN_DICT;
        // 身份冲突 → 保守保留（这是「为什么它没被跳过」的解释，不是跳过原因）
        if (ambiguous != null && !ambiguous.isEmpty()
                && ambiguous.contains(CandidateId.of(pw))) {
            return SkipReason.NONE;
        }
        if (tried != null && tried.contains(pw)) return SkipReason.TRIED_CONFIRMED_WRONG;
        if (ledger != null && policy != null && policy.skips(ledger.level(pw))) {
            return SkipReason.SKIPPED_BY_LEDGER;
        }
        return SkipReason.NONE;
    }

    /**
     * 给定阶段与跳过原因，生成一句人话（不含口令明文）。
     *
     * <p>日志与界面都用它 —— 保证「被跳过」这件事在任何地方都是可读的，
     * 而不是只在某段堆栈里。
     */
    public static String describe(Stage stage, SkipReason reason, int dictIndex, String pwTag) {
        StringBuilder sb = new StringBuilder();
        sb.append("候选#").append(dictIndex);
        if (pwTag != null && !pwTag.isEmpty()) sb.append("(").append(pwTag).append(")");
        sb.append(" → ").append(stage == null ? "?" : stage.zh);
        if (reason != null && reason != SkipReason.NONE) {
            sb.append(" ｜ 跳过原因: ").append(reason.zh);
        }
        return sb.toString();
    }
}

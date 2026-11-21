package io.github.wlmosv_png.wpaforge.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 破解页**统计区**的纯布局/分类模型（1.9.66 重设计，取代 1.9.65 版本）。
 *
 * <h3>四项核心统计（互斥、与 VerdictTally 口径一致）<\/h3>
 * <pre>
 *   已确认成功 = hit                                   （SUCCESS / DIRECT）
 *   已证实错误 = confirmedFail                         （AUTH_FAIL / DIRECT）
 *   连接未完成 = notFinished                           （TIMEOUT/INCOMPLETE/TERMINATED_UNKNOWN/ABORTED）
 *   尚无有效判定 = suspected + networkFail + undecided + envErrors
 * <\/pre>
 * 恒等式（本类用 {@link #consistent4} 校验）：
 * <pre>
 *   hit + confirmedFail + notFinished + 尚无有效判定 == total
 *   executed == confirmedFail + suspected + notFinished + undecided + networkFail
 *   total == executed + envErrors + hit
 * <\/pre>
 * 「未确定细分」独立呈现：疑似密码错误 / 网络认证失败 / 无法判断 / 环境异常，
 * 四者之和 == 尚无有效判定。
 *
 * <h3>不改口径<\/h3>
 * 所有数字来自 {@code VerdictTally}，本类只做「分桶/排版/一致性校验」，不重算结论。
 */
public final class CrackStatsModel {

    private CrackStatsModel() {}

    /** 宽屏阈值（dp）：达到它才用 1 行 4 列，否则 2×2。 */
    public static final int WIDE_DP = 600;

    /** 统计网格列数：窄屏 2（2×2），宽屏(≥{@link #WIDE_DP}dp) 4（1×4）。 */
    public static int gridColumns(int widthDp) {
        return widthDp >= WIDE_DP ? 4 : 2;
    }

    /** 网格行数（4 项）。 */
    public static int gridRows(int widthDp) {
        int cols = gridColumns(widthDp);
        return (4 + cols - 1) / cols;
    }

    /** 核心统计项的指标下标。 */
    public enum Core {
        /** 已确认成功（命中）。 */
        VERIFIED_OK,
        /** 已证实密码错误（AUTH_FAIL + DIRECT）。 */
        PROVEN_WRONG,
        /** 连接未完成。 */
        NOT_FINISHED,
        /** 尚无有效判定（疑似 + 网络级 + 无法判断 + 环境异常）。 */
        NO_VALID_VERDICT
    }

    /** 核心统计项的中文标签（顺序与 {@link Core} 一致）。 */
    public static String coreLabel(Core c, boolean zh) {
        switch (c) {
            case VERIFIED_OK:     return zh ? "已确认成功" : "VERIFIED";
            case PROVEN_WRONG:    return zh ? "已证实错误" : "PROVEN WRONG";
            case NOT_FINISHED:    return zh ? "连接未完成" : "NOT FINISHED";
            case NO_VALID_VERDICT:
            default:              return zh ? "未命中" : "NO MATCH";
        }
    }

    /** 尚无有效判定 = 疑似 + 网络级 + 无法判断 + 环境异常。 */
    public static int noValidVerdict(int suspected, int networkFail, int undecided, int envErrors) {
        return suspected + networkFail + undecided + envErrors;
    }

    /**
     * 四项核心统计的计数值，顺序 {@link Core}。
     *
     * @param hit         已确认成功
     * @param confirmed   已证实错误
     * @param notFinished 连接未完成
     * @param noVerdict   尚无有效判定（= {@link #noValidVerdict}）
     */
    public static int[] coreCounts(int hit, int confirmed, int notFinished, int noVerdict) {
        return new int[] { hit, confirmed, notFinished, noVerdict };
    }

    /**
     * 四项之和是否等于总数（统计总数必须与实际分类总数一致）。
     *
     * @param total 本轮处理过的候选总数（= VerdictTally.total()）
     */
    public static boolean consistent4(int hit, int confirmed, int notFinished,
                                      int noVerdict, int total) {
        return hit + confirmed + notFinished + noVerdict == total;
    }

    /** 一细分项。 */
    public enum Cat {
        /** 疑似密码错误（有认证失败证据但归因不足，未持久化）。 */
        SUSPECTED_WRONG,
        /** 网络认证失败（网络确实拒绝，但绑不到本条候选）。 */
        NETWORK_FAIL,
        /** 无法判断（证据不足）。 */
        UNDECIDED,
        /** 环境异常（通道 / 命令 / 采集问题）。 */
        ENV_ERROR
    }

    /** 细分项（类别 + 计数）。 */
    public static final class Item {
        public final Cat cat;
        public final int count;
        Item(Cat cat, int count) { this.cat = cat; this.count = count; }
        @Override public String toString() { return cat + "=" + count; }
    }

    private static void add(List<Item> out, Cat cat, int n) {
        if (n > 0) out.add(new Item(cat, n));
    }

    /**
     * 「尚无有效判定」的细分（固定顺序，零值隐藏）：
     * 疑似密码错误 → 网络认证失败 → 无法判断 → 环境异常。
     *
     * <p>四者之和必须等于 {@link #noValidVerdict}；这是「分类与总数一致」的依据。
     */
    public static List<Item> breakdown(int suspected, int networkFail, int undecided,
                                       int envErrors) {
        List<Item> out = new ArrayList<>();
        add(out, Cat.SUSPECTED_WRONG, suspected);
        add(out, Cat.NETWORK_FAIL, networkFail);
        add(out, Cat.UNDECIDED, undecided);
        add(out, Cat.ENV_ERROR, envErrors);
        return out;
    }

    /** 细分总数（非零项计数之和）。 */
    public static int breakdownTotal(List<Item> items) {
        int n = 0;
        if (items != null) for (Item it : items) n += it.count;
        return n;
    }

    /**
     * 细分项中文标签（1.9.67 用户表达层）。
     *
     * <p>「网络认证失败 / 无法判断」这类词会让普通用户以为软件出错。
     * 这里改成「需要你处理的」与「可以忽略的」两分：
     * <ul>
     *   <li>环境异常 —— 真的需要用户去修（唯一需要处理的项）；<\/li>
     *   <li>其余全部归到「未连上」语义，技术真相留在详情与审计页。<\/li>
     * <\/ul>
     */
    public static String catLabelZh(Cat c) {
        switch (c) {
            case SUSPECTED_WRONG: return "疑似密码错误";
            case NETWORK_FAIL:    return "未连上（网络拒绝）";
            case UNDECIDED:       return "未连上（无证据）";
            case ENV_ERROR:       return "环境异常";
            default:              return "";
        }
    }

    /** 细分项英文标签。 */
    public static String catLabelEn(Cat c) {
        switch (c) {
            case SUSPECTED_WRONG: return "suspected wrong";
            case NETWORK_FAIL:    return "not connected (rejected)";
            case UNDECIDED:       return "not connected (no evidence)";
            case ENV_ERROR:       return "env error";
            default:              return "";
        }
    }

    /** 细分渲染成一行（零项不出现）。 */
    public static String renderBreakdown(int suspected, int networkFail, int undecided,
                                         int envErrors) {
        List<Item> items = breakdown(suspected, networkFail, undecided, envErrors);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append("   ");
            Item it = items.get(i);
            sb.append(catLabelZh(it.cat)).append(' ').append(it.count);
        }
        return sb.toString();
    }
}

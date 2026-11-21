package io.github.wlmosv_png.wpaforge.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 破解页统计区的**纯布局/分类模型**（1.9.65 新增）。
 *
 * <h3>为什么抽出来<\/h3>
 * 1.9.64 的统计区是「四列横排 + 把「未确定」的细分（未完／未判／环境）
 * 硬塞进数字格」——窄屏下数字、标签、副标题互相挤压，混成一团。
 * 1.9.65 改为「2×2 网格 + 独立细分区」，本类把「放几列」「细分怎么列」
 * 抽成纯逻辑，既能被 MainActivity 复用，也能在桌面 JVM 上穷举测试。
 *
 * <h3>不改判定<\/h3>
 * 本类只做展示模型：不读引擎、不改统计口径、不伪造数字。
 */
public final class CrackStatsModel {

    private CrackStatsModel() {}

    /** 宽屏阈值（dp）：达到它才用 1 行 4 列，否则 2×2。 */
    public static final int WIDE_DP = 600;

    /**
     * 统计网格列数：窄屏 2（2×2 网格），宽屏(≥{@link #WIDE_DP}dp) 4（1×4）。
     *
     * <p>「小屏幕优先保证可读性，不要强行维持四列」——这是本方法的全部职责。
     */
    public static int gridColumns(int widthDp) {
        return widthDp >= WIDE_DP ? 4 : 2;
    }

    /** 网格行数（用于断言 2×2 / 1×4）。 */
    public static int gridRows(int widthDp) {
        int cols = gridColumns(widthDp);
        return (4 + cols - 1) / cols;
    }

    /** 「未确定」核心三类之和是否等于 inconclusive（{@link io.github.wlmosv_png.wpaforge.audit.VerdictTally} 口径）。 */
    public static boolean undeterminedConsistent(int networkFail, int notFinished,
                                                 int undecided, int inconclusive) {
        return networkFail + notFinished + undecided == inconclusive;
    }

    /** 用户可理解的未确定细分类别。 */
    public enum Cat {
        /** 疑似密码错误（有认证失败证据但归因不足，未持久化）。 */
        SUSPECTED_WRONG,
        /** 网络认证失败（网络确实拒绝，但绑不到本条候选）。 */
        NETWORK_FAIL,
        /** 连接未完成（超时 / 未收敛 / 终止无归因 / 中断）。 */
        NOT_FINISHED,
        /** 环境异常（通道 / 命令 / 采集问题）。 */
        ENV_ERROR,
        /** 无法判断（证据不足）。 */
        UNDECIDED
    }

    /** 一条细分项（类别 + 计数）。 */
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
     * 按**固定顺序**列出非零细分项（零值隐藏）。
     *
     * <p>顺序：疑似密码错误 → 网络认证失败 → 连接未完成 → 环境异常 → 无法判断。
     * 零值不出现，避免「0」堆一行噪声。
     */
    public static List<Item> breakdown(int suspectedWrong, int networkFail, int notFinished,
                                       int envErrors, int undecided) {
        List<Item> out = new ArrayList<>();
        add(out, Cat.SUSPECTED_WRONG, suspectedWrong);
        add(out, Cat.NETWORK_FAIL, networkFail);
        add(out, Cat.NOT_FINISHED, notFinished);
        add(out, Cat.ENV_ERROR, envErrors);
        add(out, Cat.UNDECIDED, undecided);
        return out;
    }

    /** 细分项的细分总数（非零项计数之和，用于与预期核对）。 */
    public static int breakdownTotal(List<Item> items) {
        int n = 0;
        if (items != null) for (Item it : items) n += it.count;
        return n;
    }

    /** 中文标签。 */
    public static String labelZh(Cat c) {
        switch (c) {
            case SUSPECTED_WRONG: return "疑似密码错误";
            case NETWORK_FAIL:    return "网络认证失败";
            case NOT_FINISHED:    return "连接未完成";
            case ENV_ERROR:       return "环境异常";
            case UNDECIDED:       return "无法判断";
            default:              return "";
        }
    }

    /** 英文标签。 */
    public static String labelEn(Cat c) {
        switch (c) {
            case SUSPECTED_WRONG: return "suspected wrong";
            case NETWORK_FAIL:    return "network auth fail";
            case NOT_FINISHED:    return "not finished";
            case ENV_ERROR:       return "env error";
            case UNDECIDED:       return "undecided";
            default:              return "";
        }
    }

    /** 渲染成一行细分文案（零项不出现）。 */
    public static String render(int suspectedWrong, int networkFail, int notFinished,
                                int envErrors, int undecided) {
        List<Item> items = breakdown(suspectedWrong, networkFail, notFinished, envErrors, undecided);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append("   ");
            Item it = items.get(i);
            sb.append(labelZh(it.cat)).append(' ').append(it.count);
        }
        return sb.toString();
    }
}

package io.github.wlmosv_png.wpaforge.scan;

/**
 * 信道 → 圆盘方位的映射（纯逻辑，可离线单测）。
 *
 * <h3>为什么要做这件事</h3>
 * 观测页的雷达原来把方位角当装饰：点沿圆周"均匀铺开"，顺序来自按信号排序的列表。
 * 结果有两个问题：
 * <ol>
 *   <li><b>读不出信息</b>：23 个网络谁是谁、哪条信道挤，全靠猜；</li>
 *   <li><b>位置不稳定</b>：信号一抖，列表顺序变，同档的点就会互相换位；
 *       新来一个 AP，其余点全体挪一格。观测页是靠位置认设备的，位置会跳就等于没法认。</li>
 * </ol>
 *
 * <p>现在把圆盘当成**频谱环**：方位角＝信道。
 * <ul>
 *   <li>上半圆（180°→360°，即左侧经正上方到右侧）＝ <b>2.4G</b> 的 14 个信道，
 *       每信道 180/14 ≈ 12.86°；</li>
 *   <li>下半圆（0°→180°）＝ <b>5G</b>，按 UNII 分 4 组（36–48 / 52–64 / 100–144 / 149–165），
 *       每组 45°。</li>
 * </ul>
 *
 * <p>于是：同一扇区里点越多 = 这条信道越挤（和 {@link ChannelAnalyzer} 的结论互相印证），
 * 而只要信道不变，方位就不变 —— 新 AP 只会在自己的扇区里插空，不会重排全场。
 *
 * <p>角度按 Android Canvas 约定：0° 指向正右方，顺时针为正（90° 正下方）。
 */
public final class ChannelMap {

    /** 2.4G 信道数（1..14） */
    public static final int CH_24_COUNT = 14;
    /** 5G 分组数（UNII 四段） */
    public static final int GROUP_5_COUNT = 4;
    /** 半圆 = 一个频段的跨度 */
    public static final float HALF = 180f;

    private ChannelMap() {}

    /** 2.4G 每个信道的扇区宽度（度） */
    public static float slotWidth(boolean is5G) {
        return is5G ? HALF / GROUP_5_COUNT : HALF / CH_24_COUNT;
    }

    /**
     * 5G 信道归到哪一组。
     * <pre>
     *   0: 36–48（UNII-1）      1: 52–64（UNII-2A）
     *   2: 100–144（UNII-2C）   3: 149–165（UNII-3）
     * </pre>
     * 中国可用的是 36–48 / 149–165，中间两段在国行设备上通常扫不到 ——
     * 但映射照样按标准给，免得换个区域就画错。
     */
    public static int groupOf5(int channel) {
        if (channel >= 36 && channel <= 48) return 0;
        if (channel >= 52 && channel <= 64) return 1;
        if (channel >= 100 && channel <= 144) return 2;
        if (channel >= 149 && channel <= 165) return 3;
        // 6GHz 或未知：塞进最后一组，绝不抛、不丢点
        return 3;
    }

    /** 扇区序号；2.4G 用信道-1（0..13），5G 用 UNII 组号（0..3） */
    public static int slotIndex(boolean is5G, int channel) {
        if (is5G) return groupOf5(channel);
        int i = channel - 1;
        return Math.max(0, Math.min(CH_24_COUNT - 1, i));
    }

    /** 扇区起始角（度） */
    public static float slotStart(boolean is5G, int channel) {
        float w = slotWidth(is5G);
        int idx = slotIndex(is5G, channel);
        return is5G ? (idx * w) : (HALF + idx * w);
    }

    /** 扇区中心角（度）—— 单点时落在正中间 */
    public static float slotCenter(boolean is5G, int channel) {
        return slotStart(is5G, channel) + slotWidth(is5G) / 2f;
    }

    /** 频段名（界面图例用） */
    public static String bandLabel(boolean is5G) {
        return is5G ? "5G" : "2.4G";
    }

    /** 扇区标签：2.4G 给信道号，5G 给组名 */
    public static String slotLabel(boolean is5G, int channel) {
        if (is5G) {
            switch (groupOf5(channel)) {
                case 0: return "36-48";
                case 1: return "52-64";
                case 2: return "100-144";
                default: return "149-165";
            }
        }
        return "ch " + Math.max(1, Math.min(CH_24_COUNT, channel));
    }

    /**
     * 2.4G 里**只有 1 / 6 / 11 值得标**：它们是唯一互不重叠的三条。
     * 14 个信道全标上去只会糊成一片，而这恰恰是用户真正要记的三个数。
     */
    public static boolean isKey24Channel(int channel) {
        return channel == 1 || channel == 6 || channel == 11;
    }

    /** 是否有明确信道（扫不到信道信息时别硬编） */
    public static boolean known(boolean is5G, int channel) {
        if (is5G) return channel >= 36;
        return channel >= 1 && channel <= CH_24_COUNT;
    }
}

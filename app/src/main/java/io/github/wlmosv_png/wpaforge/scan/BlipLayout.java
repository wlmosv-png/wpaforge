package io.github.wlmosv_png.wpaforge.scan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 雷达落点几何（纯逻辑，可离线单测）。
 *
 * <p>从 {@code RadarSweep.onDraw} 里抽出来的唯一动机：**这段几何是这次改版里最容易错、
 * 又最难肉眼验证的部分**（点在不在盘里、扇区里会不会叠、刷新会不会乱跳）。
 * 抽成纯逻辑之后可以用断言一次钉死，而不是靠截图碰运气。
 *
 * <p>规则：
 * <ol>
 *   <li><b>方位角＝信道</b>（见 {@link ChannelMap}），所以位置只由信道决定；</li>
 *   <li>同一「扇区 + 档位」的点组内，按 <b>BSSID 排序</b>后在扇区内均匀铺开 ——
 *       排序键里**没有信号强度**，所以信号抖动不会让点换位；</li>
 *   <li>组内点太多时向内/向外拆子环（最多 3 道），点径同时收缩，
 *       既不跑到别的档位带里，也不互相粘连。</li>
 * </ol>
 */
public final class BlipLayout {

    /** 输入：一个 AP 在盘上的“身份 + 位置依据” */
    public static final class In {
        public final String key;
        public final int channel;
        public final boolean is5G;
        public final int level;
        /** 调用方自己的下标 —— 输出按输入顺序对齐，靠它取回原始对象（别用身份去反查） */
        public final int tag;

        public In(String key, int channel, boolean is5G, int level) {
            this(key, channel, is5G, level, 0);
        }

        public In(String key, int channel, boolean is5G, int level, int tag) {
            this.key = key == null ? "" : key;
            this.channel = channel;
            this.is5G = is5G;
            this.level = level;
            this.tag = tag;
        }
    }

    /** 输出：画一个点需要的全部数据 */
    public static final class Pos {
        public final In src;
        /** 方位角（度，0=正右，顺时针） */
        public final float angleDeg;
        /** 半径比例（相对可用半径） */
        public final float radiusRatio;
        /** 点半径（像素） */
        public final float dotR;
        /**
         * 汇聚标记：这一格塞不下这么多点了，改成画一个「×N」的标。
         *
         * <p>为什么不做成"点更小一点硬塞"：几十个点叠在一起既看不出数量、
         * 也看不出谁是谁，还不如直接告诉你「这条信道这一档有 N 台」——
         * 而"这条信道很挤"本身就是用户要的结论。
         */
        public final int count;

        Pos(In src, float angleDeg, float radiusRatio, float dotR, int count) {
            this.src = src;
            this.angleDeg = angleDeg;
            this.radiusRatio = radiusRatio;
            this.dotR = dotR;
            this.count = count;
        }

        public boolean aggregated() {
            return count > 0;
        }
    }

    /** 同一道环内两点之间至少保留的点径倍数（防稠密） */
    public static final float DOT_GAP = 2.6f;
    /** 同一扇区内点太多时的子环偏移（半径比例） */
    public static final float ROW_OFFSET = 0.042f;
    /** 子环最多拆几道（再多会串到邻档的带上） */
    public static final int MAX_ROWS = 4;
    /** 半径比例的安全范围（不许跑出盘，也不许压到中心文字） */
    public static final float MIN_RATIO = 0.30f, MAX_RATIO = 1.0f;
    /** 点径下限（dp）—— 挤到极限也得看得见，但**不许为了好看而互相盖住** */
    public static final float MIN_DOT_DP = 1.4f;

    private BlipLayout() {}

    /**
     * @param radiusPx 可用半径（像素）
     * @param density  屏幕密度（dp→px）
     * @param ring     五道环的半径比例（下标＝档位）
     * @param dotDp    五档点径（dp，下标＝档位）
     */
    public static List<Pos> layout(List<In> items, float radiusPx, float density,
                                   float[] ring, float[] dotDp) {
        List<Pos> out = new ArrayList<>();
        if (items == null || items.isEmpty() || radiusPx <= 0 || ring == null || dotDp == null) {
            return out;
        }

        List<In> sorted = new ArrayList<>(items);
        // 排序键：(频段+扇区, 档位, BSSID)。**故意不按信号排** —— 信号抖动不该让点换位
        sorted.sort((a, b) -> {
            int sa = ChannelMap.slotIndex(a.is5G, a.channel) + (a.is5G ? 100 : 0);
            int sb = ChannelMap.slotIndex(b.is5G, b.channel) + (b.is5G ? 100 : 0);
            int c = Integer.compare(sa, sb);
            if (c != 0) return c;
            c = Integer.compare(lv(b), lv(a));
            if (c != 0) return c;
            return a.key.compareToIgnoreCase(b.key);
        });

        int i = 0;
        while (i < sorted.size()) {
            In first = sorted.get(i);
            int slot = ChannelMap.slotIndex(first.is5G, first.channel);
            int level = lv(first);
            int j = i + 1;
            while (j < sorted.size()
                    && sorted.get(j).is5G == first.is5G
                    && ChannelMap.slotIndex(sorted.get(j).is5G, sorted.get(j).channel) == slot
                    && lv(sorted.get(j)) == level) {
                j++;
            }
            place(out, sorted, i, j, first.is5G, first.channel, level,
                    radiusPx, density, ring, dotDp);
            i = j;
        }
        return out;
    }

    private static void place(List<Pos> out, List<In> sorted, int from, int to,
                              boolean is5G, int channel, int level,
                              float radiusPx, float density, float[] ring, float[] dotDp) {
        int n = to - from;
        float widthDeg = ChannelMap.slotWidth(is5G);
        float startDeg = ChannelMap.slotStart(is5G, channel);
        float ringR = ring[level];

        // 扇区在该环上的弧长 → 一层能放几个点（按点径 + 间隔算）
        float arc = (float) (radiusPx * ringR * Math.toRadians(widthDeg));
        float minDot = MIN_DOT_DP * density;

        // 一层能放几个：点径下限 × 3（点 + 空隙 + 点）
        int perRow = Math.max(1, (int) (arc / (minDot * 3f)));
        int capacity = perRow * MAX_ROWS;
        if (n > capacity) {
            // 塞不下 → 汇聚成一个计数标，别硬挤
            out.add(new Pos(sorted.get(from), ChannelMap.slotCenter(is5G, channel),
                    ringR, minDot, n));
            return;
        }
        int rows = Math.max(1, Math.min(MAX_ROWS, (int) Math.ceil(n / (float) perRow)));
        int cols = (int) Math.ceil(n / (float) rows);

        // 点径必须服从**角向间距**：相邻两点之间要放得下两个半径。
        // 旧式子的系数是 1.4（比间距还大），所以"明明算过容量"点还是叠在一起
        // —— 用户截图上那处叠着的三四个点就是这么来的。
        float spacing = arc / (cols + 1f);
        float dotR = Math.min(dotDp[level] * density, spacing / 2f * 0.85f);
        dotR = Math.max(minDot, dotR);

        for (int k = 0; k < n; k++) {
            int row = k / cols;
            int col = k % cols;
            float angle = startDeg + widthDeg * (col + 1) / (cols + 1f);
            float rr = ringR + (row - (rows - 1) / 2f) * ROW_OFFSET;
            rr = Math.max(MIN_RATIO, Math.min(MAX_RATIO, rr));
            out.add(new Pos(sorted.get(from + k), angle, rr, dotR, 0));
        }
    }

    private static int lv(In in) {
        return Math.max(0, Math.min(4, in.level));
    }

    /** 调试/测试用：把一批点的方位排序后打印（顺序稳定才说明位置稳） */
    public static String dump(List<Pos> ps) {
        StringBuilder sb = new StringBuilder();
        List<Pos> c = new ArrayList<>(ps);
        c.sort((a, b) -> {
            int x = Integer.compare(ChannelMap.slotIndex(a.src.is5G, a.src.channel),
                    ChannelMap.slotIndex(b.src.is5G, b.src.channel));
            return x != 0 ? x : a.src.key.compareToIgnoreCase(b.src.key);
        });
        for (Pos p : c) {
            sb.append(String.format(java.util.Locale.ROOT, "%s ch%d L%d -> %.1f°/%.3f%n",
                    p.src.key, p.src.channel, p.src.level, p.angleDeg, p.radiusRatio));
        }
        return sb.toString();
    }

    /** 便捷构造一组输入 */
    public static List<In> of(String[] keys, int[] channels, boolean[] is5g, int[] levels) {
        List<In> out = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            out.add(new In(keys[i], channels[i], is5g[i], levels[i]));
        }
        return out;
    }

    /** 把一组输入打平成数组（测试里给 Arrays.asList 用） */
    public static In[] arr(In... items) {
        return Arrays.copyOf(items, items.length);
    }
}

package io.github.wlmosv_png.wpaforge.scan;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 信道分析。纯计算，输入是扫描结果，输出是各信道的占用与推荐。
 */
public final class ChannelAnalyzer {

    /** 单信道统计 */
    public static final class ChannelStat {
        public final int channel;
        public final String band;
        public final int apCount;
        public final int strongestRssi;
        public final int loadScore;   // 越大越拥挤

        ChannelStat(int channel, String band, int apCount, int strongestRssi, int loadScore) {
            this.channel = channel;
            this.band = band;
            this.apCount = apCount;
            this.strongestRssi = strongestRssi;
            this.loadScore = loadScore;
        }
    }

    public static final class Report {
        public final List<ChannelStat> stats24;
        public final List<ChannelStat> stats5;
        public final int recommended24;
        public final int recommended5;

        Report(List<ChannelStat> s24, List<ChannelStat> s5, int r24, int r5) {
            this.stats24 = s24;
            this.stats5 = s5;
            this.recommended24 = r24;
            this.recommended5 = r5;
        }
    }

    /** 2.4G 不重叠信道 */
    private static final int[] NON_OVERLAP_24 = {1, 6, 11, 13};

    private ChannelAnalyzer() {}

    public static Report analyze(List<NetRecord> records) {
        // 全信道桶
        int[] count = new int[240];
        int[] rssiSum = new int[240];
        for (NetRecord r : records) {
            if (r.channel <= 0 || r.channel >= count.length) continue;
            count[r.channel]++;
            // 信号越强，占用贡献越大（把 dBm 转成正的权重）
            int weight = Math.max(0, 100 + r.rssi); // -100dBm -> 0, -40dBm -> 60
            rssiSum[r.channel] += weight;
        }

        List<ChannelStat> s24 = new ArrayList<>();
        List<ChannelStat> s5 = new ArrayList<>();
        for (int ch = 1; ch < count.length; ch++) {
            if (count[ch] == 0) continue;
            String band = NetRecord.bandLabel(ch);
            ChannelStat st = new ChannelStat(ch, band, count[ch], rssiSum[ch], rssiSum[ch]);
            if ("2.4G".equals(band)) s24.add(st);
            else if ("5G".equals(band)) s5.add(st);
        }

        return new Report(s24, s5, recommend(s24, NON_OVERLAP_24), recommend(s5, null));
    }

    /**
     * 推荐信道：优先在候选集里挑最空的；没有候选集就全局挑最空。
     * 返回 -1 表示无数据。
     */
    private static int recommend(List<ChannelStat> stats, int[] preferred) {
        if (stats.isEmpty()) return -1;
        int bestCh = -1;
        int bestLoad = Integer.MAX_VALUE;
        for (ChannelStat st : stats) {
            boolean ok = preferred == null;
            if (preferred != null) {
                for (int p : preferred) if (p == st.channel) { ok = true; break; }
            }
            if (!ok) continue;
            if (st.loadScore < bestLoad) {
                bestLoad = st.loadScore;
                bestCh = st.channel;
            }
        }
        // 候选集里一个都没有（比如 5G 之外的情况），退回全局
        if (bestCh == -1 && preferred != null) {
            return recommend(stats, null);
        }
        return bestCh;
    }
}

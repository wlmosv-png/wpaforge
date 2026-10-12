package io.github.wlmosv_png.wpaforge.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 同一 SSID 的多 BSSID 聚合。
 *
 * <h3>为什么需要</h3>
 * 现在列表里，一个 SSID 只留信号最强的一条（Scanner 里那段
 * 「同名 SSID 只保留信号最强的一条」）。这在单 AP 环境没问题，
 * 但在**宿舍楼 / 办公室 Mesh** 这种场景就丢信息了：
 *
 *   B214      ch1   -39dBm
 *   B212      ch5   -41dBm
 *   2.4G-3    ch5   -38dBm
 *
 * 这是三个不同的 SSID，看起来正常。但真实场景里更常见的是：
 *
 *   CMCC-密码   ch1   -84dBm
 *   CMCC-密码   ch36  -55dBm
 *   CMCC-密码   ch149 -61dBm
 *
 * 三个 BSSID 一个 SSID。旧逻辑只留 -55 那条，用户看不到
 * 「这网其实有三个 AP，5G 那条特别强」——而这恰恰是决定
 * 「我该连哪个」的关键信息。
 *
 * <h3>聚合规则</h3>
 * 按 SSID 分组（隐藏网络按 BSSID 各自独立，因为无法确认它们同源）。
 * 组内按 RSSI 降序，最强的当代表。组本身按代表信号降序。
 * 组内不同信道数 >= 2 时标记为「多 AP」，界面上显示 AP 个数。
 */
public final class SsidGroup {

    /** 一个 SSID 下的一组 AP */
    public static final class Group {
        public final String ssid;
        public final List<NetRecord> members;   // 已按 RSSI 降序
        public final NetRecord best;

        Group(String ssid, List<NetRecord> members) {
            this.ssid = ssid;
            this.members = members;
            this.best = members.get(0);
        }

        public int size() {
            return members.size();
        }

        /** 是否多 AP（Mesh / 多基站） */
        public boolean isMesh() {
            return members.size() > 1;
        }

        /** 组内涉及的信道数（去重） */
        public int distinctChannels() {
            List<Integer> seen = new ArrayList<>();
            for (NetRecord r : members) {
                if (!seen.contains(r.channel)) seen.add(r.channel);
            }
            return seen.size();
        }

        /** 组内最差信号，用来体现「走到另一头会掉多少」 */
        public int worstRssi() {
            int w = members.get(0).rssi;
            for (NetRecord r : members) {
                if (r.rssi < w) w = r.rssi;
            }
            return w;
        }
    }

    private SsidGroup() {}

    /**
     * 把平铺的记录聚合成组。返回值已按代表信号从强到弱排序。
     *
     * 隐藏网络（SSID 为空）不聚合：没法确认两个空 SSID 是不是同一个网，
     * 合错了比不合更误导。
     */
    public static List<Group> group(List<NetRecord> records) {
        if (records == null || records.isEmpty()) return new ArrayList<>();

        Map<String, List<NetRecord>> bySsid = new LinkedHashMap<>();
        for (NetRecord r : records) {
            if (r == null) continue;
            // 隐藏网络用 BSSID 当 key，保证每个自成一"组"（组内只有它自己）
            String key = r.isHidden() ? ("\u0000" + r.bssid) : r.ssid;
            List<NetRecord> bucket = bySsid.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                bySsid.put(key, bucket);
            }
            bucket.add(r);
        }

        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<NetRecord>> e : bySsid.entrySet()) {
            List<NetRecord> bucket = e.getValue();
            Collections.sort(bucket, (a, b) -> Integer.compare(b.rssi, a.rssi));
            out.add(new Group(bucket.get(0).displayName(), bucket));
        }
        Collections.sort(out, (a, b) -> Integer.compare(b.best.rssi, a.best.rssi));
        return out;
    }
}

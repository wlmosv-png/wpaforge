package io.github.wlmosv_png.wpaforge.core;

import io.github.wlmosv_png.wpaforge.L;

/**
 * 一条周边网络记录。不可变，构造后只读。
 * 字段全部来自 WifiManager / ScanResult 的公开 API。
 */
public final class NetRecord {

    public enum Security { OPEN, WEP, WPA, WPA2, WPA3, OWE, WPA2_WPA3, UNKNOWN }

    public final String ssid;        // 可能为空（隐藏网络）
    public final String bssid;       // MAC，大写冒号分隔
    public final int rssi;           // dBm，负数，越接近 0 越强
    public final int frequencyMhz;   // 2412 / 5180 ...
    public final int channel;        // 1..14 / 36..177
    public final boolean is5Ghz;
    public final Security security;
    public final boolean wpsSupported;
    public final String capabilities; // 原始字段，用于排查

    public NetRecord(String ssid, String bssid, int rssi, int frequencyMhz,
                     Security security, boolean wpsSupported, String capabilities) {
        this.ssid = ssid == null ? "" : ssid;
        this.bssid = bssid == null ? "" : bssid.toUpperCase();
        this.rssi = rssi;
        this.frequencyMhz = frequencyMhz;
        this.channel = freqToChannel(frequencyMhz);
        this.is5Ghz = frequencyMhz >= 5000;
        this.security = security == null ? Security.UNKNOWN : security;
        this.wpsSupported = wpsSupported;
        this.capabilities = capabilities == null ? "" : capabilities;
    }

    /** 隐藏 SSID 判定 */
    public boolean isHidden() {
        return ssid.isEmpty();
    }

    /** 展示名：隐藏网络给个占位 */
    public String displayName() {
        return isHidden() ? L.s("(隐藏网络)", "(hidden network)") : ssid;
    }

    /**
     * RSSI -> 0..4 的信号等级，用于画柱状图。
     * 阈值取自通用经验值，不是厂商私有数据。
     */
    public int signalLevel() {
        if (rssi >= -50) return 4;
        if (rssi >= -60) return 3;
        if (rssi >= -70) return 2;
        if (rssi >= -80) return 1;
        return 0;
    }

    /** 粗略距离估算（自由空间路径损耗，仅供可视化，不精确） */
    public double estimatedDistanceMeters() {
        // FSPL: FSPL(dB) = 20log10(d) + 20log10(f) - 27.55
        // d = 10^((FSPL - 20log10(f) + 27.55) / 20)
        double freqGhz = frequencyMhz / 1000.0;
        if (freqGhz <= 0) return -1;
        // 参考发射功率按 -40dBm@1m 的常见经验取值
        double fspl = Math.abs(rssi - (-40.0));
        double d = Math.pow(10, (fspl - 20 * Math.log10(freqGhz) + 27.55) / 20.0);
        return Math.round(d * 10) / 10.0;
    }

    /** 从 capabilities 串解析加密类型，按规范顺序判定 */
    public static Security parseSecurity(String caps) {
        if (caps == null || caps.isEmpty()) return Security.UNKNOWN;
        String c = caps.toUpperCase();
        if (c.contains("SAE")) {
            return c.contains("PSK") ? Security.WPA2_WPA3 : Security.WPA3;
        }
        if (c.contains("OWE")) return Security.OWE;
        if (c.contains("WPA2")) return Security.WPA2;
        if (c.contains("WPA3")) return Security.WPA3;
        if (c.contains("WPA")) return Security.WPA;
        if (c.contains("WEP")) return Security.WEP;
        return Security.OPEN;
    }

    /** 从 capabilities 串检测 WPS 能力标识 */
    public static boolean parseWps(String caps) {
        return caps != null && caps.toUpperCase().contains("WPS");
    }

    /** 频率 -> 信道号（公开的 IEEE 信道规划） */
    public static int freqToChannel(int mhz) {
        if (mhz == 2484) return 14;
        if (mhz >= 2412 && mhz <= 2472) return (mhz - 2407) / 5;
        if (mhz >= 5150 && mhz <= 5895) return (mhz - 5000) / 5;
        if (mhz >= 5955 && mhz <= 7115) return (mhz - 5950) / 5;
        return -1;
    }

    /** 信道 -> 频段标签 */
    public static String bandLabel(int channel) {
        if (channel >= 1 && channel <= 14) return "2.4G";
        if (channel >= 32 && channel <= 177) return "5G";
        if (channel >= 1 + 200) return "6G";
        return "?";
    }

    @Override
    public String toString() {
        return displayName() + " [" + bssid + "] " + rssi + "dBm ch" + channel
                + " " + security + (wpsSupported ? " +WPS" : "");
    }
}

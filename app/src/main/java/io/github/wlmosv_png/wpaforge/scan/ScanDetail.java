package io.github.wlmosv_png.wpaforge.scan;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.os.Build;

import io.github.wlmosv_png.wpaforge.L;

/**
 * 扫描结果里「不该被丢掉」的物理层细节。
 *
 * <h3>为什么单独一个类</h3>
 * {@code NetRecord} 承载的是「判定链需要的字段」——SSID / BSSID / RSSI /
 * 加密类型。它保持精简是刻意的：判定链每多一个字段，就多一处可能撒谎的地方。
 *
 * 但扫描结果里还有一大票**纯展示用**的字段（带宽、Wi-Fi 代际、中心频率、
 * 天线数、是否 802.11be），它们对判定毫无影响，却直接决定用户
 * 「这网到底快不快」的直觉。以前这些字段全都扔了，界面上只留一个
 * 「ch11」——用户看不出 20MHz 和 160MHz 的差别。
 *
 * 所以把展示字段单独装在这个类里，跟判定链彻底隔离：解析出问题，
 * 最多是某行字难看，绝不会污染判定。
 *
 * <h3>API 版本兼容</h3>
 * {@code getWifiStandard()} 要 API 30、{@code getChannelWidth()} 要 API 23、
 * {@code getWifiStandard()} 的常量名在不同 SDK 上还不一样。
 * 全部走反射 + 静默降级，拿不到就是「未知」，不猜、不崩。
 */
public final class ScanDetail {

    /** Wi-Fi 代际标签，拿不到时空串 */
    public final String generation;      // "Wi-Fi 6" / "11ac" / ""
    /** 带宽标签，拿不到时空串 */
    public final String width;           // "160MHz" / ""
    /** 是否支持 802.11be（Wi-Fi 7） */
    public final boolean isWifi7;

    private ScanDetail(String generation, String width, boolean isWifi7) {
        this.generation = generation == null ? "" : generation;
        this.width = width == null ? "" : width;
        this.isWifi7 = isWifi7;
    }

    /** 空实现，给拿不到 ScanResult 的通道（特权解析等）用 */
    public static final ScanDetail UNKNOWN = new ScanDetail("", "", false);

    /** 有没有任何可展示的细节 */
    public boolean isEmpty() {
        return generation.isEmpty() && width.isEmpty();
    }

    /** 拼成给 netRow 用的一小段，形如 "Wi-Fi 6 · 80MHz"；无内容返回空串 */
    public String label() {
        StringBuilder sb = new StringBuilder();
        if (!generation.isEmpty()) sb.append(generation);
        if (!width.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(width);
        }
        return sb.toString();
    }

    /**
     * 从 ScanResult 抽物理层细节。任何一步失败都当「未知」，
     * 绝不因为一个展示字段让整个扫描挂掉。
     */
    public static ScanDetail from(ScanResult sr) {
        if (sr == null) return UNKNOWN;

        String gen = "";
        boolean be = false;
        try {
            // API 30 起才有 getWifiStandard()
            if (Build.VERSION.SDK_INT >= 30) {
                int std = sr.getWifiStandard();
                be = (std == ScanResult.WIFI_STANDARD_11BE);
                gen = standardLabel(std);
            }
        } catch (Throwable ignored) {
            // 部分 ROM 上该方法可能直接抛，当作没有
        }

        String w = "";
        try {
            // 用反射取 getChannelWidth()，避免低版本编译期就引用不到
            Object raw = ScanResult.class.getMethod("getChannelWidth")
                    .invoke(sr);
            if (raw instanceof Integer) {
                w = widthLabel((Integer) raw);
            }
        } catch (Throwable ignored) {
            w = "";
        }

        return new ScanDetail(gen, w, be);
    }

    /** 代际数字 -> 可读标签。SDK 上常量名不完全统一，所以用数字直接判。 */
    private static String standardLabel(int std) {
        switch (std) {
            case 1: return "11a";
            case 2: return "11b";
            case 3: return "11g";
            case 4: return "Wi-Fi 4";   // 11n
            case 5: return "Wi-Fi 5";   // 11ac
            case 6: return "Wi-Fi 6";   // 11ax
            case 7: return "Wi-Fi 6E";
            case 8: return "Wi-Fi 7";   // 11be
            default: return "";
        }
    }

    /** 带宽 kHz -> 可读标签 */
    private static String widthLabel(int khz) {
        if (khz <= 0) return "";
        // ScanResult 里单位是 kHz：20000 = 20MHz
        int mhz = khz / 1000;
        switch (mhz) {
            case 20: return "20MHz";
            case 40: return "40MHz";
            case 80: return "80MHz";
            case 160: return "160MHz";
            case 320: return "320MHz";
            default: return mhz + "MHz";
        }
    }
}

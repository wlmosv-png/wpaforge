package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.scan.ChannelMap;

/**
 * 信道 → 圆盘方位映射自检。
 *
 * <p>这是观测页雷达「能读」的地基：方位角＝信道，所以映射必须满足
 * <ol>
 *   <li><b>同信道同方位</b>（位置稳定，刷新不乱跳）；</li>
 *   <li><b>扇区连续、不重叠</b>（相邻扇区首尾相接，铺满整圈）；</li>
 *   <li><b>上半圆 2.4G / 下半圆 5G</b>（两段的角区间不交叉）；</li>
 *   <li><b>未知信道不丢点</b>（落到合法扇区，绝不抛异常）。</li>
 * </ol>
 */
public class ChannelMapTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== 扇区宽度 ==");
        float w24 = ChannelMap.slotWidth(false), w5 = ChannelMap.slotWidth(true);
        ok("2.4G 每个信道 180/14 度", Math.abs(w24 - 180f / 14) < 0.001, "" + w24);
        ok("5G 每组 45 度", Math.abs(w5 - 45f) < 0.001, "" + w5);
        ok("14 个信道刚好铺满半圆", Math.abs(w24 * ChannelMap.CH_24_COUNT - 180f) < 0.001, "");
        ok("4 组刚好铺满半圆", Math.abs(w5 * ChannelMap.GROUP_5_COUNT - 180f) < 0.001, "");

        System.out.println("== 2.4G 扇区 ==");
        ok("ch1 从 180 度起（上半圆起点）", Math.abs(ChannelMap.slotStart(false, 1) - 180f) < 0.001, "");
        ok("ch1 序号 0", ChannelMap.slotIndex(false, 1) == 0, "");
        ok("ch6 序号 5", ChannelMap.slotIndex(false, 6) == 5, "");
        ok("ch11 序号 10", ChannelMap.slotIndex(false, 11) == 10, "");
        ok("ch14 序号 13", ChannelMap.slotIndex(false, 14) == 13, "");
        ok("ch14 起点 + 宽 = 360", Math.abs(ChannelMap.slotStart(false, 14) + w24 - 360f) < 0.001, "");
        boolean cont = true;
        for (int ch = 1; ch < 14; ch++) {
            float end = ChannelMap.slotStart(false, ch) + w24;
            if (Math.abs(end - ChannelMap.slotStart(false, ch + 1)) > 0.001) cont = false;
        }
        ok("相邻信道首尾相接（连续、不重叠）", cont, "");
        boolean inHalf = true;
        for (int ch = 1; ch <= 14; ch++) {
            float st = ChannelMap.slotStart(false, ch);
            if (st < 180f || st >= 360f) inHalf = false;
        }
        ok("2.4G 全部落在上半圆 [180,360)", inHalf, "");
        ok("中心角在扇区内", ChannelMap.slotCenter(false, 6)
                > ChannelMap.slotStart(false, 6)
                && ChannelMap.slotCenter(false, 6)
                < ChannelMap.slotStart(false, 6) + w24, "");

        System.out.println("== 5G 分组 ==");
        ok("36-48 → 组 0", ChannelMap.groupOf5(36) == 0 && ChannelMap.groupOf5(48) == 0, "");
        ok("52-64 → 组 1", ChannelMap.groupOf5(52) == 1 && ChannelMap.groupOf5(64) == 1, "");
        ok("100-144 → 组 2", ChannelMap.groupOf5(100) == 2 && ChannelMap.groupOf5(144) == 2, "");
        ok("149-165 → 组 3", ChannelMap.groupOf5(149) == 3 && ChannelMap.groupOf5(165) == 3, "");
        ok("149 组从 135 度起", Math.abs(ChannelMap.slotStart(true, 149) - 135f) < 0.001,
                "" + ChannelMap.slotStart(true, 149));
        boolean lower = true;
        for (int ch : new int[]{36, 40, 44, 48, 52, 60, 64, 100, 120, 144, 149, 157, 165}) {
            float st = ChannelMap.slotStart(true, ch);
            if (st < 0f || st >= 180f) lower = false;
        }
        ok("5G 全部落在下半圆 [0,180)", lower, "");
        ok("6GHz / 未知 5G 信道不抛异常且落在合法扇区",
                ChannelMap.slotStart(true, 37) >= 0f && ChannelMap.slotStart(true, 5975) < 180f, "");

        System.out.println("== 未知/越界 ==");
        ok("信道 0 被夹到第一个扇区", ChannelMap.slotIndex(false, 0) == 0, "");
        ok("信道 99 被夹到最后一个扇区",
                ChannelMap.slotIndex(false, 99) == ChannelMap.CH_24_COUNT - 1, "");
        ok("信道 -5 被夹到第一个扇区", ChannelMap.slotIndex(false, -5) == 0, "");
        ok("负数信道不会算出负角", ChannelMap.slotStart(false, -5) >= 180f, "");
        ok("未知信道判定", !ChannelMap.known(false, 0) && !ChannelMap.known(true, 0), "");
        ok("已知信道判定", ChannelMap.known(false, 6) && ChannelMap.known(true, 149), "");

        System.out.println("== 稳定性（位置不乱跳的前提）==");
        ok("同信道多次调用结果一致",
                ChannelMap.slotStart(false, 6) == ChannelMap.slotStart(false, 6)
                        && ChannelMap.slotStart(true, 149) == ChannelMap.slotStart(true, 149), "");
        ok("不同信道方位不同",
                ChannelMap.slotCenter(false, 1) != ChannelMap.slotCenter(false, 6), "");

        System.out.println("== 标签 ==");
        ok("1/6/11 是重点信道",
                ChannelMap.isKey24Channel(1) && ChannelMap.isKey24Channel(6)
                        && ChannelMap.isKey24Channel(11), "");
        ok("其余信道不重点标", !ChannelMap.isKey24Channel(2) && !ChannelMap.isKey24Channel(13), "");
        ok("2.4G 标签带信道号", ChannelMap.slotLabel(false, 6).contains("6"),
                ChannelMap.slotLabel(false, 6));
        ok("5G 标签是组范围", ChannelMap.slotLabel(true, 44).equals("36-48"),
                ChannelMap.slotLabel(true, 44));
        ok("频段名", ChannelMap.bandLabel(false).equals("2.4G")
                && ChannelMap.bandLabel(true).equals("5G"), "");

        System.out.println();
        System.out.println("=== 信道映射测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

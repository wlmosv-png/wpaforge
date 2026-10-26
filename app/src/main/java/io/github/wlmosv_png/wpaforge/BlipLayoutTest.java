package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.scan.BlipLayout;
import io.github.wlmosv_png.wpaforge.scan.ChannelMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 雷达落点几何自检（纯逻辑，不需要真机）。
 *
 * <p>钉四件事：
 * <ol>
 *   <li><b>不出盘</b>：角度在 [0,360)，半径比例在 [0.30,1.0] 内；</li>
 *   <li><b>回自己扇区</b>：每个点落在自己信道的扇区区间里；</li>
 *   <li><b>不重合</b>：同一扇区 + 同一档位里，没有两个点位置完全相同；</li>
 *   <li><b>不乱跳</b>：把输入顺序打乱，同一个 BSSID 的方位角必须一模一样
 *       （这条是「观测页靠位置认设备」的硬约束）。</li>
 * </ol>
 */
public class BlipLayoutTest {

    private static int pass = 0, fail = 0;

    private static final float[] RING = {0.97f, 0.855f, 0.74f, 0.625f, 0.51f};
    private static final float[] DOT_DP = {3.0f, 3.6f, 4.2f, 4.8f, 5.4f};
    private static final float RADIUS = 300f;   // px
    private static final float DENSITY = 2.75f; // 常见密度

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static List<BlipLayout.In> mixed() {
        List<BlipLayout.In> in = new ArrayList<>();
        String[] keys = {"AA:01", "AA:02", "AA:03", "BB:01", "BB:02", "CC:01", "DD:01"};
        int[] ch = {1, 6, 6, 11, 11, 36, 149};
        boolean[] g5 = {false, false, false, false, false, true, true};
        int[] lv = {4, 3, 2, 1, 0, 4, 3};
        for (int i = 0; i < keys.length; i++) {
            in.add(new BlipLayout.In(keys[i], ch[i], g5[i], lv[i], i));
        }
        return in;
    }

    private static List<BlipLayout.Pos> run(List<BlipLayout.In> in) {
        return BlipLayout.layout(in, RADIUS, DENSITY, RING, DOT_DP);
    }

    public static void main(String[] a) {
        System.out.println("== 基本几何 ==");
        List<BlipLayout.Pos> ps = run(mixed());
        ok("每个输入都有落点", ps.size() == 7, "" + ps.size());
        boolean angleOk = true, ratioOk = true, dotOk = true;
        for (BlipLayout.Pos p : ps) {
            if (p.angleDeg < 0 || p.angleDeg >= 360) angleOk = false;
            if (p.radiusRatio < BlipLayout.MIN_RATIO || p.radiusRatio > BlipLayout.MAX_RATIO) ratioOk = false;
            if (p.dotR <= 0) dotOk = false;
        }
        ok("角度都在 [0,360)", angleOk, "");
        ok("半径比例在 [0.30,1.0]", ratioOk, "");
        ok("点径为正", dotOk, "");

        System.out.println("== 落回自己的扇区 ==");
        boolean inSector = true;
        for (BlipLayout.Pos p : ps) {
            float st = ChannelMap.slotStart(p.src.is5G, p.src.channel);
            float w = ChannelMap.slotWidth(p.src.is5G);
            if (p.angleDeg <= st || p.angleDeg >= st + w) inSector = false;
        }
        ok("每个点在自己信道的扇区里", inSector, "");
        BlipLayout.Pos p149 = null;
        for (BlipLayout.Pos p : ps) if (p.src.channel == 149) p149 = p;
        ok("5G 149 落在下半圆", p149 != null && p149.angleDeg < 180f,
                p149 == null ? "null" : "" + p149.angleDeg);
        BlipLayout.Pos p1 = null;
        for (BlipLayout.Pos p : ps) if (p.src.channel == 1) p1 = p;
        ok("2.4G ch1 落在上半圆起点附近", p1 != null && p1.angleDeg > 180f && p1.angleDeg < 205f,
                p1 == null ? "null" : "" + p1.angleDeg);

        System.out.println("== 同扇区不重合 ==");
        List<BlipLayout.In> crowd = new ArrayList<>();
        for (int i = 0; i < 12; i++) crowd.add(new BlipLayout.In("K" + i, 6, false, 3, i));
        List<BlipLayout.Pos> cp = run(crowd);
        boolean dup = false;
        for (int i = 0; i < cp.size(); i++) {
            for (int j = i + 1; j < cp.size(); j++) {
                if (cp.get(i).aggregated() || cp.get(j).aggregated()) continue;
                if (Math.abs(cp.get(i).angleDeg - cp.get(j).angleDeg) < 0.001f
                        && Math.abs(cp.get(i).radiusRatio - cp.get(j).radiusRatio) < 0.001f) {
                    dup = true;
                }
            }
        }
        ok("12 台挤在同一信道也不重合（拆子环）", !dup, "");
        // 新硬的约束：任意两点的**像素距离**必须容得下两个半径
        List<BlipLayout.In> crowd2 = new ArrayList<>();
        for (int i = 0; i < 40; i++) crowd2.add(new BlipLayout.In("C" + i, 6, false, 4, i));
        List<BlipLayout.Pos> cp2 = run(crowd2);
        float worst = Float.MAX_VALUE;
        for (int i = 0; i < cp2.size(); i++) {
            for (int j = i + 1; j < cp2.size(); j++) {
                double a1 = Math.toRadians(cp2.get(i).angleDeg), a2 = Math.toRadians(cp2.get(j).angleDeg);
                float r1 = cp2.get(i).radiusRatio * RADIUS, r2 = cp2.get(j).radiusRatio * RADIUS;
                float dx = (float) (Math.cos(a1) * r1 - Math.cos(a2) * r2);
                float dy = (float) (Math.sin(a1) * r1 - Math.sin(a2) * r2);
                float d = (float) Math.hypot(dx, dy);
                float need = cp2.get(i).dotR + cp2.get(j).dotR;
                if (d - need < worst) worst = d - need;
            }
        }
        ok("40 台同信道同档：不硬挤，改成汇聚计数标", cp2.size() == 1 && cp2.get(0).aggregated()
                && cp2.get(0).count == 40, cp2.toString());
        ok("汇聚标落在该扇区中心", cp2.size() == 1
                && Math.abs(cp2.get(0).angleDeg - ChannelMap.slotCenter(false, 6)) < 0.01f, "");
        ok("汇聚标在正确档位环上", cp2.size() == 1
                && Math.abs(cp2.get(0).radiusRatio - RING[4]) < 0.001f, "");

        // 塞得下的拥挤场景：任意两点的像素距离必须容得下两个半径
        List<BlipLayout.In> crowd3 = new ArrayList<>();
        for (int i = 0; i < 9; i++) crowd3.add(new BlipLayout.In("D" + i, 6, false, 2, i));
        List<BlipLayout.Pos> cp3 = run(crowd3);
        float worst3 = Float.MAX_VALUE;
        for (int i = 0; i < cp3.size(); i++) {
            for (int j = i + 1; j < cp3.size(); j++) {
                double a1 = Math.toRadians(cp3.get(i).angleDeg), a2 = Math.toRadians(cp3.get(j).angleDeg);
                float r1 = cp3.get(i).radiusRatio * RADIUS, r2 = cp3.get(j).radiusRatio * RADIUS;
                float dx = (float) (Math.cos(a1) * r1 - Math.cos(a2) * r2);
                float dy = (float) (Math.sin(a1) * r1 - Math.sin(a2) * r2);
                float d = (float) Math.hypot(dx, dy);
                float need = cp3.get(i).dotR + cp3.get(j).dotR;
                if (d - need < worst3) worst3 = d - need;
            }
        }
        ok("9 台同信道同档：任意两点不重叠（含像素距离）", worst3 > -0.3f
                && cp3.stream().allMatch(p -> !p.aggregated()), "最坏余量 " + worst3 + "px");
        boolean smallOk = true;
        for (BlipLayout.Pos p : cp3) if (p.dotR < BlipLayout.MIN_DOT_DP * DENSITY - 0.01f) smallOk = false;
        ok("点径不低于下限", smallOk, "");
        boolean layered = false;
        for (BlipLayout.Pos p : cp) if (Math.abs(p.radiusRatio - RING[3]) > 0.001f) layered = true;
        ok("挤的时候确实拆了子环", layered, "");
        boolean inBand = true;
        for (BlipLayout.Pos p : cp) {
            if (Math.abs(p.radiusRatio - RING[3]) > 0.001f
                    && Math.abs(p.radiusRatio - RING[3]) > BlipLayout.ROW_OFFSET * 1.6f) inBand = false;
        }
        ok("子环没有跑出本档位的带", inBand, "");

        System.out.println("== 位置稳定性（硬约束）==");
        List<BlipLayout.In> shuffled = new ArrayList<>(mixed());
        for (int i = 0; i < 5; i++) Collections.shuffle(shuffled);
        List<BlipLayout.Pos> ps2 = run(shuffled);
        String d1 = BlipLayout.dump(ps), d2 = BlipLayout.dump(ps2);
        ok("打乱输入顺序后方位完全一致（靠位置认设备的前提）", d1.equals(d2),
                d1 + "\\n---\\n" + d2);

        System.out.println("== 大规模 ==");
        List<BlipLayout.In> big = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            int ch = (i % 13) + 1;
            big.add(new BlipLayout.In("B" + String.format("%03d", i), ch, false, i % 5, i));
        }
        List<BlipLayout.Pos> bp = run(big);
        ok("120 台全部有落点", bp.size() == 120, "" + bp.size());
        boolean bigOk = true;
        for (BlipLayout.Pos p : bp) {
            if (p.radiusRatio < BlipLayout.MIN_RATIO || p.radiusRatio > BlipLayout.MAX_RATIO) bigOk = false;
            if (p.dotR < BlipLayout.MIN_DOT_DP * DENSITY - 0.01f) bigOk = false;
        }
        ok("120 台时仍不越界、点径不低于下限", bigOk, "");

        System.out.println("== tag 对齐 ==");
        boolean tagOk = true;
        for (BlipLayout.Pos p : ps) if (p.src.tag < 0 || p.src.tag > 6) tagOk = false;
        ok("输出带得回原始下标", tagOk, "");

        System.out.println("== 容错 ==");
        ok("空输入返回空表", run(new ArrayList<>()).isEmpty(), "");
        ok("null 输入不炸", BlipLayout.layout(null, RADIUS, DENSITY, RING, DOT_DP).isEmpty(), "");
        ok("半径为 0 返回空表", BlipLayout.layout(mixed(), 0, DENSITY, RING, DOT_DP).isEmpty(), "");
        ok("信道未知也能落点",
                run(Collections.singletonList(new BlipLayout.In("X", 0, false, 2, 0))).size() == 1, "");

        System.out.println();
        System.out.println("=== 雷达几何测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

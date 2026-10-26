package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.scan.BlipLayout;
import io.github.wlmosv_png.wpaforge.scan.RadarAnimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 雷达过渡动画自检（纯逻辑）。
 *
 * 盯四件事：
 *  1. 新点**淡入**（不是直接出现），且位置从略靠内"长"到目标；
 *  2. 老点位置变化是**连续**的（相邻帧位移有界），不是瞬移；
 *  3. 消失的点**淡出后**才移除，不是立刻消失；
 *  4. 全部到位后 busy() 变 false —— 静止不出帧（省电）；
 *     且切后台回来（超大 dt）不会一步跳到位。
 */
public class RadarAnimatorTest {

    private static int pass = 0, fail = 0;

    private static final float[] RING = {0.97f, 0.855f, 0.74f, 0.625f, 0.51f};
    private static final float[] DOT_DP = {3.0f, 3.6f, 4.2f, 4.8f, 5.4f};

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static List<BlipLayout.In> items(String[][] rows) {
        List<BlipLayout.In> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(new BlipLayout.In(rows[i][0], Integer.parseInt(rows[i][1]),
                    Boolean.parseBoolean(rows[i][2]), Integer.parseInt(rows[i][3]), true, i));
        }
        return out;
    }

    private static RadarAnimator anim(List<BlipLayout.In> in) {
        RadarAnimator a = new RadarAnimator();
        a.setTargets(in, 300f, 2.75f, RING, DOT_DP);
        return a;
    }

    private static void run(RadarAnimator a, int frames, float dt) {
        for (int i = 0; i < frames; i++) a.advance(dt);
    }

    public static void main(String[] x) {
        System.out.println("== 淡入 ==");
        RadarAnimator a = anim(items(new String[][]{{"AA", "6", "false", "4"}}));
        ok("刚设目标时 alpha 从 0 开始", a.states().get(0).alpha < 0.01f,
                "" + a.states().get(0).alpha);
        run(a, 3, 16f);
        float mid = a.states().get(0).alpha;
        ok("几帧后 alpha 在 0 与 1 之间（是渐入不是闪现）", mid > 0.05f && mid < 0.95f, "" + mid);
        run(a, 60, 16f);
        ok("最终 alpha 到 1", a.states().get(0).alpha > 0.98f, "" + a.states().get(0).alpha);
        ok("最终位置＝目标位置", Math.abs(a.states().get(0).ratio - RING[4]) < 0.005f,
                "" + a.states().get(0).ratio);
        ok("到位后 busy()＝false（静止不出帧）", !a.busy(), a.dump());

        System.out.println("== 位置连续（不瞬移）==");
        RadarAnimator b = anim(items(new String[][]{{"AA", "6", "false", "0"}}));
        run(b, 60, 16f);
        float before = b.states().get(0).ratio;
        // 同一台 AP 信号变强（档位 0 → 4），半径目标从 0.97 变到 0.51
        b.setTargets(items(new String[][]{{"AA", "6", "false", "4"}}), 300f, 2.75f, RING, DOT_DP);
        b.advance(16f);
        float after1 = b.states().get(0).ratio;
        ok("单帧位移有界（16ms 内不跨越整条半径）", Math.abs(after1 - before) < 0.25f,
                before + " -> " + after1);
        ok("过渡期间 busy()＝true", b.busy(), "");
        run(b, 80, 16f);
        ok("过渡结束后落到新档位环", Math.abs(b.states().get(0).ratio - RING[4]) < 0.005f,
                "" + b.states().get(0).ratio);
        ok("同一个 key 全程只有一个点（没有重复/分身）", b.size() == 1, "" + b.size());

        System.out.println("== 淡出（消失不是立刻）==");
        RadarAnimator c = anim(items(new String[][]{
                {"AA", "6", "false", "4"}, {"BB", "1", "false", "3"}}));
        run(c, 60, 16f);
        c.setTargets(items(new String[][]{{"AA", "6", "false", "4"}}), 300f, 2.75f, RING, DOT_DP);
        ok("消失的点还在（等淡出）", c.size() == 2, "" + c.size());
        c.advance(16f);
        RadarAnimator.State gone = null;
        for (RadarAnimator.State st : c.states()) if (st.key.equals("BB")) gone = st;
        ok("消失点的 alpha 在下降", gone != null && gone.alpha < 0.99f,
                gone == null ? "null" : "" + gone.alpha);
        run(c, 100, 16f);
        ok("淡出后移除", c.size() == 1 && c.states().get(0).key.equals("AA"), c.dump());

        System.out.println("== 容错与稳定 ==");
        RadarAnimator d = anim(items(new String[][]{{"AA", "6", "false", "4"}}));
        run(d, 60, 16f);
        String s1 = d.dump();
        d.setTargets(items(new String[][]{{"AA", "6", "false", "4"}}), 300f, 2.75f, RING, DOT_DP);
        run(d, 60, 16f);
        ok("同一份数据重复刷新后状态不变（不会漂）", s1.equals(d.dump()), s1 + "--" + d.dump());
        d.advance(0);
        d.advance(-5);
        ok("dt=0/-5 不炸", true, "");
        RadarAnimator e = anim(items(new String[][]{{"AA", "6", "false", "4"}}));
        e.advance(5000f);
        ok("超大 dt 不会一步到位（切后台回来的保护）",
                Math.abs(e.states().get(0).ratio - RING[4]) > 0.0001f, e.dump());
        RadarAnimator f = new RadarAnimator();
        f.setTargets(new ArrayList<>(), 300f, 2.75f, RING, DOT_DP);
        ok("空输入不炸也不留点", f.size() == 0 && !f.busy(), "");
        RadarAnimator g = new RadarAnimator();
        g.setTargets(null, 300f, 2.75f, RING, DOT_DP);
        ok("null 输入不炸", g.size() == 0, "");

        System.out.println("== 汇聚标也能过渡 ==");
        RadarAnimator h = new RadarAnimator();
        List<BlipLayout.In> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new BlipLayout.In("K" + i, 6, false, 4, true, i));
        }
        h.setTargets(many, 300f, 2.75f, RING, DOT_DP);
        run(h, 60, 16f);
        ok("40 台聚成 1 个标", h.size() == 1 && h.states().get(0).count == 40, h.dump());

        System.out.println();
        System.out.println("=== 雷达过渡动画测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}

package io.github.wlmosv_png.wpaforge.scan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 雷达落点的**过渡状态机**（纯逻辑，可离线单测）。
 *
 * <h3>为什么需要它</h3>
 * 用户的原话：「刷新的时候有个特效啥的？看着很生硬一下就变了」。
 * 原来 {@code setBlips()} 一换数组，下一帧就是新位置：点会**瞬移**，
 * 新出现的网络凭空冒出，消失的网络凭空消失 —— 看起来像画面被替换，不像仪表在更新。
 *
 * <h3>做法</h3>
 * <ol>
 *   <li><b>按 BSSID 匹配</b>：同一台 AP 在两次刷新之间是同一个点，
 *       位置变化走缓动（不是换一个点）；</li>
 *   <li><b>新增点淡入</b>：alpha 0→1，并从头稍微靠内的位置"长"出来；</li>
 *   <li><b>消失点淡出</b>：alpha→0 再移除（不是立刻消失），短暂看到它退场；</li>
 *   <li><b>静止不出帧</b>：全部到位后 {@link #busy()} 返回 false，
 *       视图就不必再刷帧（省电，也避免无意义重绘）。</li>
 * </ol>
 *
 * <p>注意：这里动的是**渲染值**，目标位置仍然由 {@link BlipLayout} 决定 ——
 * 也就是说「位置稳定」的契约没有被动画破坏，动画只是把"跳到新位置"变成"走过去"。
 */
public final class RadarAnimator {

    /** 一个点的当前状态（渲染值）与目标值 */
    public static final class State {
        public final String key;
        public int level;
        public boolean is5G;
        public int channel;
        public boolean secured;
        /** 汇聚计数（>0 表示这一格里塞不下，画成 ×N 的标） */
        public int count;

        public float angleDeg, ratio, dotR, alpha;
        float tAngle, tRatio, tDotR, tAlpha;
        boolean exiting;

        State(String key) {
            this.key = key;
        }

        public boolean visible() {
            return alpha > 0.02f;
        }
    }

    /** 缓动时间常数（毫秒）：越小越"跟手"，越大越"顺滑" */
    public static final float TAU_MS = 110f;
    /** 低于这个 alpha 视为已经退出，可以移除 */
    public static final float ALPHA_EPS = 0.02f;
    /** 位置到位判据（角度 / 半径比例） */
    public static final float ANGLE_EPS = 0.05f;
    public static final float RATIO_EPS = 0.0015f;
    /** 新点"长出来"的起始半径系数（比目标更靠内一点） */
    public static final float ENTER_RATIO = 0.88f;
    /** 单帧最大推进步长，防止切后台回来后"瞬移" */
    public static final float MAX_STEP = 0.5f;

    private final Map<String, State> map = new HashMap<>();

    /** 设置新一批目标（按 BSSID 匹配旧点，保留动画连续性） */
    public void setTargets(List<BlipLayout.In> items, float radiusPx, float density,
                           float[] ring, float[] dotDp) {
        List<BlipLayout.Pos> pos = BlipLayout.layout(items, radiusPx, density, ring, dotDp);
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (BlipLayout.Pos p : pos) {
            String key = p.src.key;
            if (key == null || key.isEmpty()) {
                // 没有身份的点（老调用方/未知）用位置当键，至少别互相覆盖
                key = "?" + p.src.channel + ":" + p.src.level + ":" + (int) p.angleDeg;
            }
            seen.add(key);
            State st = map.get(key);
            if (st == null) {
                st = new State(key);
                st.angleDeg = p.angleDeg;
                st.ratio = clampRatio(p.radiusRatio * ENTER_RATIO);
                st.dotR = 0.35f * p.dotR;      // 由小长到大
                st.alpha = 0f;
                map.put(key, st);
            }
            st.level = p.src.level;
            st.is5G = p.src.is5G;
            st.channel = p.src.channel;
            st.count = p.count;
            st.secured = p.src.secured;
            st.tAngle = p.angleDeg;
            st.tRatio = p.radiusRatio;
            st.tDotR = p.dotR;
            st.tAlpha = 1f;
            st.exiting = false;
        }

        // 没出现在新数据里的点：淡出，而不是立刻消失
        for (State st : map.values()) {
            if (seen.contains(st.key)) continue;
            st.tAlpha = 0f;
            st.exiting = true;
        }
    }

    /** 推进一帧；dtMs 为距上一帧的毫秒数 */
    public void advance(float dtMs) {
        if (dtMs <= 0) return;
        if (dtMs > 200f) dtMs = 200f;      // 切后台回来别一步到位
        float k = (float) (1.0 - Math.exp(-dtMs / TAU_MS));
        if (k > MAX_STEP) k = MAX_STEP;

        List<String> dead = new ArrayList<>();
        for (State st : map.values()) {
            st.angleDeg += (st.tAngle - st.angleDeg) * k;
            st.ratio += (st.tRatio - st.ratio) * k;
            st.dotR += (st.tDotR - st.dotR) * k;
            st.alpha += (st.tAlpha - st.alpha) * k;
            if (st.exiting && st.alpha < ALPHA_EPS) dead.add(st.key);
        }
        for (String key : dead) map.remove(key);
    }

    /** 还有动画在跑吗（视图据此决定要不要继续出帧） */
    public boolean busy() {
        for (State st : map.values()) {
            if (st.exiting) return true;
            if (Math.abs(st.tAngle - st.angleDeg) > ANGLE_EPS) return true;
            if (Math.abs(st.tRatio - st.ratio) > RATIO_EPS) return true;
            if (Math.abs(st.tDotR - st.dotR) > 0.15f) return true;
            if (st.tAlpha - st.alpha > ALPHA_EPS) return true;
        }
        return false;
    }

    /** 立即到位（首帧/恢复场景用，避免从 0 开始长） */
    public void settle() {
        for (State st : map.values()) {
            st.angleDeg = st.tAngle;
            st.ratio = st.tRatio;
            st.dotR = st.tDotR;
            st.alpha = st.tAlpha;
        }
        List<String> dead = new ArrayList<>();
        for (State st : map.values()) if (st.exiting && st.alpha < ALPHA_EPS) dead.add(st.key);
        for (String key : dead) map.remove(key);
    }

    public List<State> states() {
        return new ArrayList<>(map.values());
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }

    /** 测试/排查用：当前状态快照 */
    public String dump() {
        List<State> list = new ArrayList<>(map.values());
        list.sort((a, b) -> a.key.compareTo(b.key));
        StringBuilder sb = new StringBuilder();
        for (State st : list) {
            sb.append(String.format(java.util.Locale.ROOT,
                    "%s L%d %.1f°/%.3f a=%.2f%s%n",
                    st.key, st.level, st.angleDeg, st.ratio, st.alpha,
                    st.count > 0 ? " x" + st.count : ""));
        }
        return sb.toString();
    }

    private static float clampRatio(float v) {
        return Math.max(BlipLayout.MIN_RATIO, Math.min(BlipLayout.MAX_RATIO, v));
    }
}

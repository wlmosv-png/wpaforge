package io.github.wlmosv_png.wpaforge.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选滚动窗口 —— 破解页「正在试」那三行的数据源。
 *
 * <p>为什么单独抽出来：这是破解页唯一每条约 5 秒就要刷一次的部件，
 * 而且它要同时给出「当前在试哪条」和「刚才试过哪些」。
 * 纯逻辑，桌面 JVM 可测。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #current()} 是**最新**推入的那条（也就是正在试的）</li>
 *   <li>{@link #previous(int)} 返回它之前的那几条，**最新在前**</li>
 *   <li>null / 空串一律忽略，不占格子（否则日志里的空行会把窗口挤空）</li>
 * </ul>
 */
public final class CandidateTicker {

    private final String[] buf;
    private int count = 0;   // 已推入的有效条数（可超过 buf.length）
    private int next = 0;    // 下一个写入位置

    public CandidateTicker(int size) {
        this.buf = new String[Math.max(1, size)];
    }

    /** 推入一条候选。null / 空串忽略。 */
    public void push(String candidate) {
        if (candidate == null) return;
        String c = candidate.trim();
        if (c.isEmpty()) return;
        buf[next % buf.length] = c;
        next++;
        if (count < buf.length) count++;
    }

    /** 最新一条（正在试的）；还没推过返回 null。 */
    public String current() {
        if (count == 0) return null;
        return buf[(next - 1 + buf.length * 2) % buf.length];
    }

    /** 当前之前最多 k 条，最新在前（不含 current）。 */
    public List<String> previous(int k) {
        List<String> out = new ArrayList<>();
        int n = Math.min(k, count - 1);
        for (int i = 1; i <= n; i++) {
            String s = buf[(next - 1 - i + buf.length * 2) % buf.length];
            if (s != null) out.add(s);
        }
        return out;
    }

    public int count() {
        return count;
    }

    public void clear() {
        for (int i = 0; i < buf.length; i++) buf[i] = null;
        count = 0;
        next = 0;
    }
}

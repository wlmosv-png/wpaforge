package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 每条候选的耗时汇总（纯逻辑，可离线单测）。
 *
 * <h3>为什么需要它</h3>
 * 后台跑任务时界面没开，UI 回调是空的 —— 逐条结果**只有界面能看到**，
 * 任务日志里一条都没有。用户回头翻日志，只看到满屏「监听器诊断」，
 * 既看不出快慢，也看不出判定走了哪条路。
 *
 * <p>但按既定的「日志降噪」原则，不能每条都写一行（一本 3336 条的字典
 * 会灌 3000+ 行）。折中：攒够 {@code window} 条写**一行**汇总 ——
 * 均值 + 四段分解 + 判定归类，既有信号又不吵。
 *
 * <p>四段分解（都来自引擎的实测）：
 * <pre>
 *   前置  = 删旧配置 + 净检 + 断开确认 + 清日志缓冲 + 快照
 *   netId = 发令后等「本条新建的 netId」那一段（旧版固定等 600ms）
 *   轮询  = 主循环里 cmd wifi status 的累计耗时
 *   读日志 = 主循环里 logcat 读取 + 解析的累计耗时
 * </pre>
 * 四段之和小于总耗时 —— 差额就是 sleep/等待，那部分本来就不该出现在这里。
 */
public final class CostMeter {

    /** 一条候选的样本 */
    public static final class Sample {
        public final long totalMs, prepMs, probeMs, pollMs, logMs;
        public final int rounds;
        public final String verdict, by;

        public Sample(long totalMs, String verdict, String by,
                      long prepMs, long probeMs, long pollMs, long logMs, int rounds) {
            this.totalMs = totalMs;
            this.verdict = verdict == null ? "" : verdict;
            this.by = by == null ? "" : by;
            this.prepMs = prepMs;
            this.probeMs = probeMs;
            this.pollMs = pollMs;
            this.logMs = logMs;
            this.rounds = rounds;
        }
    }

    private final int window;
    private final List<Sample> win = new ArrayList<>();

    public CostMeter(int window) {
        this.window = window < 1 ? 1 : window;
    }

    public int window() { return window; }

    public void record(long totalMs, String verdict, String by,
                       long prepMs, long probeMs, long pollMs, long logMs, int rounds) {
        win.add(new Sample(totalMs, verdict, by, prepMs, probeMs, pollMs, logMs, rounds));
    }

    /** 攒够 window 条就给一行汇总并清空；不够返回 null（调用方据此决定不打日志）。 */
    public String takeSummary() {
        if (win.size() < window) return null;
        String line = format(win);
        win.clear();
        return line;
    }

    /** 拼一段「标签 值」（值为 0 就跳过） */
    private static void appendSeg(StringBuilder sb, String label, long v) {
        if (v == 0) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(label).append(" ").append(v);
    }

    /** 纯函数：把一批样本渲染成一行。单测直接钉它。 */
    public static String format(List<Sample> list) {
        if (list == null || list.isEmpty()) return "";

        long tSum = 0, prepSum = 0, probeSum = 0, pollSum = 0, logSum = 0, rSum = 0;
        // 判定归类：按「命中判据」分组，空判据归到 verdict 名
        List<String> keys = new ArrayList<>();
        List<Integer> cnt = new ArrayList<>();
        for (Sample s : list) {
            tSum += s.totalMs;
            prepSum += s.prepMs;
            probeSum += s.probeMs;
            pollSum += s.pollMs;
            logSum += s.logMs;
            rSum += s.rounds;
            String k = s.by.isEmpty() ? s.verdict : s.by;
            if (k.isEmpty()) k = "?";
            int at = keys.indexOf(k);
            if (at < 0) { keys.add(k); cnt.add(1); }
            else cnt.set(at, cnt.get(at) + 1);
        }
        int n = list.size();
        StringBuilder sb = new StringBuilder();
        sb.append("近 ").append(n).append(" 条 · 均 ").append(tSum / n).append("ms");
        // 值为 0 的分段不打印：status 与 logcat 合并成一条命令后，
        // 「读日志」恒为 0，写出来只会让人以为坏了。
        StringBuilder seg = new StringBuilder();
        appendSeg(seg, "前置", prepSum / n);
        appendSeg(seg, "netId", probeSum / n);
        appendSeg(seg, "轮询", pollSum / n);
        appendSeg(seg, "读日志", logSum / n);
        if (seg.length() > 0) sb.append("（").append(seg.toString()).append("）");
        sb.append("轮 ").append(rSum / n).append(" 均 ").append(rSum == 0 ? 0 : pollSum / rSum).append("ms");
        sb.append(" | ");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(keys.get(i)).append(' ').append(cnt.get(i));
        }
        return sb.toString();
    }
}

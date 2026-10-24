package io.github.wlmosv_png.wpaforge.audit;

/**
 * 速率滑窗（纯逻辑，可离线单测）。
 *
 * <h3>为什么不能只用「整轮平均」</h3>
 * 逐条尝试的耗时并不稳定：一批候选里如果多数走「系统广播认证失败」这条快速判据，
 * 每条约 2 秒；如果有几条落到「观察窗口结束、无结论」（TIMEOUT），那几条会等满窗口，
 * 于是那一批的均值能到 5 秒以上。整轮平均（{@code 累计耗时 / 已试条数}）会把开头的
 * 慢批次一直拖在后面 —— 界面显示「每条约 5.2 秒、剩 7 小时」，而眼下实际只有
 * 2.2 秒/条（用户 2026-10-24 实测反馈："实际很快，但显示 5 秒"）。
 *
 * <p>所以界面上的「每条约多少秒」「条/分」「剩余时间」改用**最近 N 条的滑窗均值**；
 * 整轮平均仍然保留（任务结束的汇总、下一次开始前的预估都用它，那个口径没问题）。
 *
 * <p>窗口未满时就用已有样本的均值，不等到攒满才给数。
 */
public final class RateWindow {

    /** 默认窗口：最近 20 条。 */
    public static final int DEFAULT_SIZE = 20;

    private final long[] buf;
    private int count;   // 有效样本数（<= buf.length）
    private int next;    // 下一个写入位置
    private long sumMs;

    public RateWindow() {
        this(DEFAULT_SIZE);
    }

    public RateWindow(int size) {
        this.buf = new long[Math.max(1, size)];
    }

    public int size() {
        return buf.length;
    }

    /** 有效样本数。 */
    public int count() {
        return count;
    }

    public boolean full() {
        return count >= buf.length;
    }

    /** 清空（新一轮任务开始时调用）。 */
    public void reset() {
        count = 0;
        next = 0;
        sumMs = 0L;
        for (int i = 0; i < buf.length; i++) buf[i] = 0L;
    }

    /** 记一条耗时（ms）。非正数忽略 —— 中断/回退那些不该算进速率。 */
    public void add(long ms) {
        if (ms <= 0L) return;
        if (count == buf.length) {
            sumMs -= buf[next];      // 淘汰最旧
        } else {
            count++;
        }
        buf[next] = ms;
        sumMs += ms;
        next = (next + 1) % buf.length;
    }

    /** 滑窗均值（ms）；没有样本返回 0。 */
    public long avgMs() {
        return count == 0 ? 0L : sumMs / count;
    }

    /**
     * 用滑窗均值估算剩余时间（ms）。
     *
     * @param remaining 剩余条数
     * @return 估算毫秒；没有样本或条数非正时返回 -1（调用方据此不显示 ETA）
     */
    public long etaMs(long remaining) {
        long per = avgMs();
        if (per <= 0L || remaining <= 0L) return -1L;
        return remaining * per;
    }

    /** 条/分；没有样本返回 0。 */
    public double perMinute() {
        long per = avgMs();
        return per <= 0L ? 0d : 60000d / per;
    }
}

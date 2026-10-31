package io.github.wlmosv_png.wpaforge.audit;

/**
 * 测试用的可控时钟（B1）。
 *
 * <h3>两条必须同时成立的约束</h3>
 * <ol>
 *   <li><b>时间可控</b>：{@link #advance(long)} 让虚拟时钟前进，不必真等。</li>
 *   <li><b>阻塞仍然发生</b>：{@link #sleep(long)} <b>不是"只推进时间就返回"</b>。
 *       它先真实地 {@code Thread.sleep(realBlockMs)} 让出 CPU（让对端线程/回调有机会跑），
 *       再按 {@link #timeScale} 推进虚拟时间。</li>
 * </ol>
 *
 * <p>为什么第 2 条不能省：{@code EngineClock.sleep} 在生产里等价于
 * {@code Thread.sleep}，它的副作用是「把当前线程让出去」。
 * 如果测试实现只推进虚拟时间，引擎会以"零延迟"空转，
 * 对被等待方（系统回调、另一线程）的时序就是假的 ——
 * 那样测出来的"时序正确"毫无意义。
 *
 * <h3>安全阀</h3>
 * 真实阻塞上限 {@link #MAX_REAL_BLOCK_MS} —— 防止被测代码里某个 1200ms 的 sleep
 * 把测试拖到分钟级。超出部分只推进虚拟时间，不再真等。
 */
public final class TestEngineClock implements EngineClock {

    /** 真实阻塞的硬上限（毫秒）。超出部分仅虚拟推进。 */
    public static final long MAX_REAL_BLOCK_MS = 5L;

    private volatile long now;
    private final long scale;
    private final long realBlockCap;

    private int sleepCalls;
    private long sleptVirtualMs;

    /** 倍率 1：1ms 真实等待 → 1ms 虚拟推进（仍受 realBlockCap 限制）。 */
    public TestEngineClock(long startMs) {
        this(startMs, 1L, MAX_REAL_BLOCK_MS);
    }

    public TestEngineClock(long startMs, long scale, long realBlockCap) {
        this.now = startMs;
        this.scale = Math.max(1L, scale);
        this.realBlockCap = Math.max(0L, realBlockCap);
    }

    @Override
    public long uptimeMillis() {
        return now;
    }

    /**
     * 等价于生产行为：先真实阻塞（让出 CPU），再推进虚拟时间。
     *
     * <p>中断处理与生产一致：**吞掉**，不恢复中断位，不抛出。
     */
    @Override
    public void sleep(long ms) {
        if (ms > 0) {
            long block = Math.min(ms, realBlockCap);
            if (block > 0) {
                try {
                    Thread.sleep(block);
                } catch (InterruptedException ignored) {
                    // 与生产实现一致：吞掉。
                }
            }
        }
        synchronized (this) {
            sleepCalls++;
            if (ms > 0) {
                long adv = ms * scale;
                now += adv;
                sleptVirtualMs += adv;
            }
        }
    }

    /** 手动推进虚拟时间（模拟"时间流逝但没人 sleep"）。 */
    public synchronized void advance(long ms) {
        if (ms > 0) now += ms;
    }

    public synchronized int sleepCalls() { return sleepCalls; }
    public synchronized long sleptVirtualMs() { return sleptVirtualMs; }
}

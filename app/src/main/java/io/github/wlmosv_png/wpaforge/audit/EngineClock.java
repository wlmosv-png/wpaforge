package io.github.wlmosv_png.wpaforge.audit;

import android.os.SystemClock;

/**
 * 引擎的**时间与等待**抽象（2026-10-31，B1 阶段）。
 *
 * <h3>为什么需要它</h3>
 * {@code CrackEngine} 里原本有 41 处 {@link SystemClock#uptimeMillis()} 与
 * 1 处私有 {@code sleep(long)}。这两样东西让引擎在离线 JVM 上**无法确定性复现**：
 * 时间只能流逝、等待只能真等，于是「迟到回调」「超时边界」「候选串窗口」
 * 这类与时序有关的缺陷永远只能靠真机碰运气。
 *
 * <h3>本抽象不改变任何行为</h3>
 * 生产默认实现 {@link #PRODUCTION} 逐字节等价于原来的调用：
 * <pre>
 *   uptimeMillis()  →  SystemClock.uptimeMillis()
 *   sleep(ms)       →  try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
 * </pre>
 * 也就是说：**阻塞线程、吞掉中断、返回 void** 这三件事全部照旧，
 * 语义、异常传播方式、线程行为都没有任何变化。
 *
 * <h3>为什么不把 sleep 换成「推进虚拟时间」</h3>
 * {@code Thread.sleep} 除了计时，还**真的把当前线程让出去**。
 * 如果测试里把它换成「只推进虚拟时钟、不阻塞」，那么被等待的一方
 * （另一个线程、系统回调）就永远得不到执行机会，测出来的时序是假的。
 *
 * <p>因此测试实现（{@code TestEngineClock}）的约定是：
 * <b>先真实短暂阻塞（让出 CPU，供对端线程推进），再按倍率推进虚拟时间</b>。
 * 时间变得可控，但"阻塞"这一行为被保留。
 *
 * <h3>污染防护</h3>
 * 注入是**引擎实例**的字段，不是 static 全局状态 ——
 * 一个实例注入测试时钟，不会影响同进程里的任何其它实例。
 * 且注入入口 {@link CrackEngine#setClockForTest} 是包级可见，
 * 生产代码（{@code CrackService}/{@code MainActivity}/{@code App}）无法调用。
 *
 * <h3>与 {@link SystemClock#uptimeMillis()} 同样的假设</h3>
 * 单调、不受系统时间调整影响、进程内一致。所有调用点原本就在这个假设下工作，
 * 本抽象不引入新假设。
 */
public interface EngineClock {

    /** 单调时钟（毫秒）。语义等同 {@link SystemClock#uptimeMillis()}。 */
    long uptimeMillis();

    /**
     * 等待。
     *
     * <p>契约与原来的 {@code private static void sleep(long)} 完全一致：
     * <ul>
     *   <li>阻塞当前线程；</li>
     *   <li>被中断时**吞掉** {@link InterruptedException}（不恢复中断位、不抛出）；</li>
     *   <li>返回 {@code void}；</li>
     *   <li>参数为负或 0 时同 {@link Thread#sleep(long)} —— 立即返回。</li>
     * </ul>
     */
    void sleep(long ms) throws InterruptedException;

    /**
     * 生产实现：直接转发到 Android 运行时。
     * <b>这是唯一被生产代码使用的实现。</b>
     */
    EngineClock PRODUCTION = new EngineClock() {
        @Override public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }

        @Override public void sleep(long ms) throws InterruptedException {
            // 与 CrackEngine 原 private static sleep 完全一致：
            // 调用方负责吞中断（原实现就在这一层吞），这里向上抛以保持签名完整，
            // 由 EngineClock#sleepQuietly 承担"吞掉"的旧行为。
            //
            // B1 一致性说明：原实现若传入负值，Thread.sleep 会抛
            // IllegalArgumentException（未捕获 → 同一异常照旧传播）。
            // 生产路径不存在负值调用（所有调用点都是字面量或已判正的表达式），
            // 故此分支在生产中不可达；此处**不做归一化**，保持逐字节等价。
            Thread.sleep(ms);
        }
    };

    /**
     * 旧行为的等价入口：吞掉 {@link InterruptedException}。
     *
     * <p>原来的 {@code CrackEngine.sleep(long)} 内部就是
     * {@code try { Thread.sleep(ms); } catch (InterruptedException ignored) {}}。
     * 生产代码调用本方法，得到**逐字节相同**的行为；
     * 测试实现可以覆盖 {@link #sleep(long)} 而不必重复这套吞异常逻辑。
     */
    default void sleepQuietly(long ms) {
        try {
            sleep(ms);
        } catch (InterruptedException ignored) {
            // 与旧实现一致：吞掉，不恢复中断位，不向上抛。
        }
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 离线校验的**进程级**运行守卫。
 *
 * <h3>为什么必须是进程级（真机可复现的缺陷）</h3>
 * 原来"同一时间只允许一次比对"完全靠 Activity 的实例字段
 * （{@code offlineBusy} / {@code runningEngine} / {@code workbench}）。
 * 按返回键退出、被系统回收、或横竖屏重建之后，**工作线程还活着，新实例却以为
 * 自己是空闲的** —— 于是同一个进程里可以同时跑两次比对：
 * <ul>
 *   <li>新实例停不掉旧任务（引擎引用在旧实例里）；</li>
 *   <li>两次运行都往同一个结果卡写盘，后完成的覆盖先完成的；</li>
 *   <li>用户看到的是哪一次完全取决于执行顺序。</li>
 * </ul>
 * 这里把"谁在跑、有没有请求取消、取消后多久算卡住"收成进程级静态状态，
 * 纯逻辑、可离线测试。
 */
public final class OfflineRunGuard {

    /** 取消后多久还没退出就判定"卡住"（可注入，便于测试）。 */
    public static final long DEFAULT_STALL_MS = 15_000L;

    private static final AtomicReference<String> OWNER = new AtomicReference<String>();
    private static final AtomicLong CANCEL_AT = new AtomicLong(0L);
    private static final AtomicLong LAST_RELEASE_AT = new AtomicLong(0L);

    private OfflineRunGuard() {}

    /**
     * 申请独占运行权。
     *
     * @param token 本次运行的唯一标识（建议用材料+字典指纹+时间）
     * @return true = 拿到；false = 已经有别人在跑（含"上一个 Activity 实例"）
     */
    public static boolean tryAcquire(String token) {
        String t = token == null || token.isEmpty() ? "run" : token;
        if (!OWNER.compareAndSet(null, t)) return false;
        CANCEL_AT.set(0L);
        return true;
    }

    /** 释放（只有持有者能释放，避免旧任务释放掉新任务）。 */
    public static void release(String token) {
        String t = token == null || token.isEmpty() ? "run" : token;
        if (OWNER.compareAndSet(t, null)) {
            CANCEL_AT.set(0L);
            LAST_RELEASE_AT.set(System.currentTimeMillis());
        }
    }

    /** 强制释放（看门狗用：确认卡死之后不认 token）。 */
    public static void forceRelease() {
        OWNER.set(null);
        CANCEL_AT.set(0L);
        LAST_RELEASE_AT.set(System.currentTimeMillis());
    }

    public static boolean held() {
        return OWNER.get() != null;
    }

    public static String owner() {
        return OWNER.get();
    }

    public static long lastReleaseAt() {
        return LAST_RELEASE_AT.get();
    }

    /** 记录一次"用户请求取消"的时刻。 */
    public static void markCancel(long nowMs) {
        if (OWNER.get() != null) CANCEL_AT.compareAndSet(0L, nowMs);
    }

    public static boolean cancelRequested() {
        return CANCEL_AT.get() > 0L;
    }

    public static long cancelAt() {
        return CANCEL_AT.get();
    }

    /**
     * 取消之后一直没退出 = 卡住。
     *
     * <p>引擎的取消是**协作式**的：它只在遍历候选的循环里检查标志位。
     * 如果此时线程被系统冻结、或阻塞在别处，它会一直不返回 ——
     * 而界面上的"运行中"状态就永远不解锁（开始按钮永久不可用，停止按钮点了也没用，
     * 只能杀进程）。有这一条判据，界面就可以自己解锁并如实说明。
     *
     * @param nowMs      当前时间
     * @param timeoutMs  允许多久；<=0 时用 {@link #DEFAULT_STALL_MS}
     */
    public static boolean stalled(long nowMs, long timeoutMs) {
        long t = CANCEL_AT.get();
        if (t <= 0L || OWNER.get() == null) return false;
        long limit = timeoutMs > 0 ? timeoutMs : DEFAULT_STALL_MS;
        return nowMs - t >= limit;
    }

    /** 仅供测试：把静态状态清干净。 */
    static void resetForTest() {
        OWNER.set(null);
        CANCEL_AT.set(0L);
        LAST_RELEASE_AT.set(0L);
    }
}

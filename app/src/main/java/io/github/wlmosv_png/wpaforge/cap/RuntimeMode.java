package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;

/**
 * 运行模式的**进程级缓存**。
 *
 * <h3>为什么要缓存</h3>
 * 模式判定要起 {@code su -c id}（约 137ms，冷启动更久）。
 * 界面每一帧、每个页面都去判一次，会让「设置页」看起来卡住，
 * 也会让用户以为是应用坏了。
 *
 * <h3>为什么必须是「进程级单例 + 显式失效」</h3>
 * 这个项目上一版的老毛病就是**多处各自维护一份状态**：
 * 首页说能用、点了才发现不能用。所以这里只有一个真相，
 * 任何页面读的都是它；只有用户在设置里改模式或主动重测时才失效。
 *
 * <p>注意：本类只缓存**模式**，能力明细由 {@link CapabilityDetector} 缓存，
 * 两者都带失效入口，不允许出现「模式变了但能力没跟着变」。
 */
public final class RuntimeMode {

    private static volatile RunMode current;

    private RuntimeMode() {
    }

    /**
     * 取当前模式。首次调用会真实探测（可能起 su，**不要在主线程首次调用**）。
     */
    public static RunMode get(Context ctx) {
        RunMode m = current;
        if (m != null) return m;
        synchronized (RuntimeMode.class) {
            m = current;
            if (m == null) {
                m = CapabilityDetector.resolve(ctx, prefMode(ctx));
                current = m;
            }
            return m;
        }
    }

    /** 只读缓存，不触发探测（界面首帧安全）。未探测过返回 null。 */
    public static RunMode peek() {
        return current;
    }

    /** 同 {@link #peek()}，语义明确地用于「只想要当前值、不想触发探测」的文案拼装。 */
    public static RunMode current() {
        return current;
    }

    /** 强制重新判定（用户改模式 / 点「重新检测」/ 打开设置页时调用）。 */
    public static RunMode refresh(Context ctx) {
        synchronized (RuntimeMode.class) {
            CapabilityDetector.invalidate();
            current = CapabilityDetector.resolve(ctx, prefMode(ctx));
            return current;
        }
    }

    /** 当前实际的 modeId（未探测时按 Root —— 与原行为一致，避免改动前后行为漂移）。 */
    public static int modeId(Context ctx) {
        RunMode m = current;
        return m == null ? prefMode(ctx) : m.modeId();
    }

    /** 用户的选择（可能是 AUTO），不是实际生效的模式。 */
    public static int prefMode(Context ctx) {
        try {
            return io.github.wlmosv_png.wpaforge.Prefs.requestedMode(ctx);
        } catch (Throwable t) {
            return RunMode.ROOT;
        }
    }

    static void setForTest(RunMode m) {
        current = m;
    }

    static void clearForTest() {
        current = null;
    }
}

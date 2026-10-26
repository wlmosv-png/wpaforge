package io.github.wlmosv_png.wpaforge.scan;

import io.github.wlmosv_png.wpaforge.L;

/**
 * 扫描节流闸门（纯逻辑，可离线单测）。
 *
 * <h3>为什么需要它</h3>
 * 用户的原话是「用户无法主动刷新网络 能不能加个按钮啥的」。
 * 加按钮不难，难的是**加一个不撒谎的按钮**：
 *
 * <ul>
 *   <li>Android 9+ 对前台应用的 {@code startScan()} 有频率限制
 *       （前台约每 30 秒一次，后台更久）；</li>
 *   <li>超频时 {@code startScan()} 返回 false —— 也就是「系统把这个请求丢了」。
 *       旧代码只是往日志里写一行，界面什么都不说，用户点了按钮没反应，
 *       只会以为软件坏了。</li>
 * </ul>
 *
 * <p>所以这里把「能不能扫、还要等多久、上次为什么没扫成」变成可读状态，
 * 界面据此禁用按钮并显示倒计时 —— 把系统的限制**如实告诉用户**，
 * 而不是让他反复点。
 */
public final class ScanGate {

    /** 前台应用的最小扫描间隔。Android 9+ 实测约 30 秒一次，取 30s 作为默认冷却 */
    public static final long MIN_INTERVAL_MS = 30_000L;
    /** 被系统拒绝后的冷却：宁可多等一会儿，也不让用户连点 */
    public static final long REJECT_COOLDOWN_MS = 30_000L;

    /** 上次请求（不论成没成）的时间戳；0 = 从没扫过 */
    private long lastRequestAt = 0;
    /** 上次请求是否被系统接受 */
    private boolean lastAccepted = true;
    /** 上次被拒绝的时刻 */
    private long lastRejectAt = 0;

    /** 记一次请求结果。accepted = startScan() 的返回值 */
    public void markRequest(long now, boolean accepted) {
        lastRequestAt = now;
        lastAccepted = accepted;
        if (!accepted) lastRejectAt = now;
    }

    /** 距下次允许扫描还有多少毫秒；0 = 现在就可以 */
    public long remainingMs(long now) {
        if (lastRequestAt <= 0) return 0;
        long wait = MIN_INTERVAL_MS - (now - lastRequestAt);
        if (!lastAccepted) {
            // 被拒之后额外多等一会儿：系统很可能还在自己的窗口里
            wait = Math.max(wait, REJECT_COOLDOWN_MS - (now - lastRejectAt));
        }
        return Math.max(0, wait);
    }

    public boolean allowed(long now) {
        return remainingMs(now) <= 0;
    }

    /** 上次请求有没有被系统接受（界面用来解释「为什么点了没反应」） */
    public boolean lastAccepted() {
        return lastAccepted;
    }

    public boolean everRequested() {
        return lastRequestAt > 0;
    }

    /** 按钮下方的状态文字；秒数向上取整，别出现「还需 0 秒」 */
    public String label(long now) {
        long ms = remainingMs(now);
        if (ms <= 0) return L.s("可以扫描", "Ready");
        long s = (ms + 999) / 1000;
        if (!lastAccepted) {
            return L.s("系统限制了扫描频率 · " + s + " 秒后再试",
                    "System throttled scanning \\u00b7 retry in " + s + "s");
        }
        return L.s("还需 " + s + " 秒", "in " + s + "s");
    }

    /** 测试与「重新进入页面」用 */
    public void reset() {
        lastRequestAt = 0;
        lastRejectAt = 0;
        lastAccepted = true;
    }
}

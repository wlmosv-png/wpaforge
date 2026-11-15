package io.github.wlmosv_png.wpaforge.wps;

/**
 * PIN 被拒 / 超时后的自动退避（2026-11-15 新增，用户建议）。
 *
 * <h3>它解决什么</h3>
 * 家用路由器对连续失败的 WPS PIN 有自己的惩罚：多数在 3~10 次失败后把 WPS
 * 锁 60 秒，少数直接进 AP Setup Locked。原实现是**一发接一发不停**，
 * 于是「被锁的 60 秒里继续发」—— 那 60 秒本来必然失败，既浪费额度，
 * 还可能把锁定期越推越长。用户提的做法是对的：**被拒或超时后就停一分钟再跑**。
 *
 * <h3>为什么不复用 {@link WpsEngine#shouldBackoff}</h3>
 * 那条链（{@code V_LOCKED}）的判据是「关联时长骤降 / beacon 报锁定」，
 * 属于**已经被锁**之后的止损；本类管的是**还没被锁就先避开**，
 * 判据是每次尝试自己的结果（被拒 / 超时）。两者互补，不互相替代。
 *
 * <h3>纯逻辑</h3>
 * 不碰线程、不碰 SharedPreferences、不碰 Android —— 可以直接穷举测试。
 * 真正「等」这件事由调用方（{@code MainActivity}）做，因为它必须能被
 * 「停止」按钮打断（见 {@code wpsCancel}），而那是 UI 线程的职责。
 */
public final class WpsPinBackoff {

    /** 默认暂停秒数：与多数家用 AP 的 WPS 锁定窗口同量级。 */
    public static final int DEFAULT_PAUSE_SEC = 60;

    /** 允许的暂停秒数范围（界面可选档见 {@link #PRESET_SEC}）。 */
    public static final int MIN_PAUSE_SEC = 5;
    public static final int MAX_PAUSE_SEC = 600;

    /** 界面上的时长档位。 */
    public static final int[] PRESET_SEC = {30, 60, 120, 300};

    /** 连击判据：连续多少次「被拒 / 超时」才暂停。默认 1 = 用户说的「每次」。 */
    public static final int DEFAULT_AFTER_N = 1;

    private final boolean enabled;
    private final int pauseSec;
    private final int afterN;

    private int consecutive;

    public WpsPinBackoff(boolean enabled, int pauseSec) {
        this(enabled, pauseSec, DEFAULT_AFTER_N);
    }

    public WpsPinBackoff(boolean enabled, int pauseSec, int afterN) {
        this.enabled = enabled;
        this.pauseSec = clampPauseSec(pauseSec);
        this.afterN = Math.max(1, afterN);
    }

    public static int clampPauseSec(int s) {
        if (s < MIN_PAUSE_SEC) return MIN_PAUSE_SEC;
        if (s > MAX_PAUSE_SEC) return MAX_PAUSE_SEC;
        return s;
    }

    /**
     * 一次尝试的结果 → 这次之后要等多少毫秒。
     *
     * @param status helper 回的 {@code RESULT=…}（见 {@link WpsController.Result#status}）
     * @return 0 = 不用等；&gt;0 = 需要等这么多毫秒
     */
    public long observe(String status) {
        if (isRejectOrTimeout(status)) {
            consecutive++;
            if (enabled && consecutive >= afterN) {
                consecutive = 0;
                return pauseSec * 1000L;
            }
            return 0;
        }
        if (isProgress(status)) {
            // AP 真的在跟我们做 WPS（出现过 km=WPS 的关联 / 半接受）——
            // 这是「有戏」的信号，不是被拒，绝不在这个节骨眼上罚停。
            consecutive = 0;
            return 0;
        }
        // SKIPPED_INVALID 之类「根本没发出去」的，不算被拒也不算进展：
        // 不累加，也不清空 —— 清空会让「被拒 3 次 → 一条非法候选 → 又从头数」
        // 这种把连击判据架空的情况出现。保持不动是最保守的选择。
        return 0;
    }

    /**
     * 这条结果算不算「被 AP 拒绝或超时」。
     *
     * <p>词汇表来自 {@link WpsHelper} 实际会打印的那些 RESULT 值。
     * 明确**排除**：
     * <ul>
     *   <li>{@code SKIPPED_INVALID} —— 校验位不合法，根本没发出去，不是 AP 拒的；</li>
     *   <li>{@code SUCCESS*} / {@code HALF_OK} / {@code M2D_THEN_MORE} —— 是进展；</li>
     * </ul>
     */
    public static boolean isRejectOrTimeout(String status) {
        if (status == null) return false;
        String s = status.trim().toUpperCase(java.util.Locale.US);
        return "REJECTED".equals(s)
                || "PIN_REJECTED".equals(s)
                || "TIMEOUT".equals(s)
                || "NO_RESPONSE".equals(s)
                || "NO_ASSOC".equals(s)
                || "LINK_LOST".equals(s)
                || "CMD_FAIL".equals(s)
                || "ERROR".equals(s);
    }

    /** 是不是「AP 在响应我们」的进展信号。 */
    public static boolean isProgress(String status) {
        if (status == null) return false;
        String s = status.trim().toUpperCase(java.util.Locale.US);
        return s.startsWith("SUCCESS") || "HALF_OK".equals(s) || "M2D_THEN_MORE".equals(s);
    }

    /** 连击计数（诊断用）。 */
    public int consecutive() {
        return consecutive;
    }

    /** 开新任务 / 换目标时清零。 */
    public void reset() {
        consecutive = 0;
    }

    public boolean enabled() {
        return enabled;
    }

    public int pauseSec() {
        return pauseSec;
    }

    /** 「等待中」那一行文案。 */
    public String waitText(int remainSec, boolean zh) {
        if (remainSec <= 0) return "";
        return zh ? "已自动暂停 " + remainSec + " 秒（上一次 PIN 被拒或超时）—— 按「停止」可随时中断"
                  : "Auto-paused " + remainSec + " s (last PIN was rejected or timed out) — press Stop to abort";
    }
}

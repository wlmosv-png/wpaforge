package io.github.wlmosv_png.wpaforge.audit;

/**
 * AP 侧退避检测。
 *
 * <p>目标 AP 对短时间内的反复关联会退避：表现是每条候选的净耗时明显变长，
 * 甚至一段时间里不再回应。此时继续按原节奏硬打，只会换来一串无效尝试 ——
 * 既浪费时间，也更容易被 AP 侧的防护记住。
 *
 * <p>判据刻意做得保守：只看最近 {@value #MIN_SAMPLES} 条，**全部**偏慢才建议退避。
 * 单条偶发变慢（信号抖动、系统忙）不触发，避免把正常的节奏打断。
 *
 * <p>纯逻辑，无 Android 依赖，可离线测试。
 */
public final class BackoffPolicy {

    /** 单条超过这个时长视为「偏慢」 */
    public static final long SLOW_MS = 6000L;
    /** 至少攒够这么多条才判断 */
    public static final int MIN_SAMPLES = 3;
    /** 冷却下限 / 上限（随机取值，避免固定节奏被 AP 侧规律识别） */
    public static final long COOL_MIN_MS = 400L;
    public static final long COOL_MAX_MS = 1500L;

    private BackoffPolicy() {}

    /** 最近若干条净耗时的环形窗口 */
    public static final class Window {
        private static final int SIZE = 6;
        private final long[] buf = new long[SIZE];
        private int n = 0;
        private int idx = 0;

        public void record(long ms) {
            buf[idx] = ms;
            idx = (idx + 1) % SIZE;
            if (n < SIZE) n++;
        }

        /** 已记录的条数（上限 SIZE） */
        public int count() {
            return n;
        }

        /** 按时间顺序返回副本（最早 -> 最新） */
        public long[] recent() {
            long[] out = new long[n];
            int start = (n < SIZE) ? 0 : idx;
            for (int i = 0; i < n; i++) out[i] = buf[(start + i) % SIZE];
            return out;
        }

        public void reset() {
            n = 0;
            idx = 0;
        }
    }

    /**
     * 这个结论算不算「AP 给了明确响应」—— 只有它才该进采样窗口。
     *
     * <p>排除掉的那些都不反映 AP 的响应速度：
     * <ul>
     *   <li>{@code TIMEOUT}：本地策略等满超时的固定值（本机 10 秒）。把它算进来，
     *       「每条都超时」会被误读成「连续限流」，于是每条都插冷却 —— 反而更慢。</li>
     *   <li>{@code UNKNOWN} / {@code ERROR}：通道异常，不是 AP 的问题。</li>
     *   <li>{@code TARGET_GONE}：目标不在扫描范围，这条本来就没意义。</li>
     *   <li>{@code ABORTED}：用户暂停/取消。</li>
     * </ul>
     */
    public static boolean countable(CrackEngine.Verdict v) {
        return v == CrackEngine.Verdict.SUCCESS || v == CrackEngine.Verdict.AUTH_FAIL;
    }

    /**
     * 建议的额外冷却时间；0 表示不需要退避。
     *
     * @param recent 按时间顺序的最近净耗时
     * @param count  recent 中有效长度
     */
    public static long suggestDelayMs(long[] recent, int count) {
        if (recent == null || count < MIN_SAMPLES) return 0;
        int from = Math.max(0, count - MIN_SAMPLES);
        int slow = 0;
        for (int i = from; i < count && i < recent.length; i++) {
            if (recent[i] >= SLOW_MS) slow++;
        }
        if (slow < MIN_SAMPLES) return 0;
        return COOL_MIN_MS + jitter(count, recent) % (COOL_MAX_MS - COOL_MIN_MS + 1);
    }

    /** 由样本决定的确定性抖动，便于测试复现 */
    private static long jitter(int count, long[] recent) {
        long h = 1125899906842597L;
        for (int i = 0; i < count && i < recent.length; i++) h = h * 31 + recent[i];
        return Math.abs(h % 1000);
    }
}

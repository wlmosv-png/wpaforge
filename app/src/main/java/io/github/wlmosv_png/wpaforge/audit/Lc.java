package io.github.wlmosv_png.wpaforge.audit;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * logcat 时间锚点（纯逻辑，无 Android 依赖）。
 *
 * <h3>为什么需要它</h3>
 * 判定链的日志归属过去只有一个机制：**每条候选前 {@code logcat -c} 清空全局缓冲**。
 * 这个做法的代价是真实存在的：
 * <ul>
 *   <li>它是**全局**副作用 —— 把整机其它组件的缓冲一起清掉，
 *       用户随后想抓任何现场都没了（审计 R3 把它记为“高风险假设”）；</li>
 *   <li>它从不校验结果 —— 清空失败时，上一条候选的失败行会留在缓冲里被算到本条头上；</li>
 *   <li>ColorOS 有日志配额，清空后常常一条都读不到，日志类判据整体失效。</li>
 * </ul>
 *
 * <h3>替代方案：时间戳切片</h3>
 * {@code logcat -d -t '<MM-DD HH:MM:SS.mmm>'} 只输出**该时刻之后**的行。
 * 于是“窗口起点”从“我清过缓冲”变成“我给出的时间锚点”，
 * 不再需要动全局缓冲，也不再依赖清空是否成功。
 *
 * <p>与 {@link AttemptMarker} 配合：标记证明“这段日志属于哪条候选”，
 * 时间锚点证明“这段日志不早于本次尝试”。两者叠加才是可靠的窗口隔离。
 *
 * <p>输出格式由本类的 {@link #anchor(long)} 统一生成，便于离线断言。
 */
public final class Lc {

    /** logcat {@code -t} 接受的时间格式（无年份）。 */
    public static final String PATTERN = "MM-dd HH:mm:ss.SSS";

    private Lc() {}

    /**
     * 把毫秒时刻转成 logcat {@code -t} 可接受的锚点串。
     *
     * @param epochMs 墙钟毫秒；{@code <= 0} 返回 {@code null}（表示“没有锚点”）
     * @return 形如 {@code 10-24 06:45:09.123}；不可用时返回 {@code null}
     */
    public static String anchor(long epochMs) {
        if (epochMs <= 0) return null;
        try {
            return new SimpleDateFormat(PATTERN, Locale.US).format(new Date(epochMs));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 是否需要为锚点退让一秒。
     *
     * <p>logcat 的 {@code -t} 是**闭区间起点**，而设备时钟与 logcat 自己的
     * 时间戳取整之间可能有几毫秒偏差：若锚点正好落在某行之后一点点，
     * 那行就会被漏掉。判定宁可多看一行（多出来的行由标记与 BSSID 归属挡掉），
     * 也不能漏看一行。所以把锚点**往前推 1 秒**。
     *
     * @return 前推后的时刻；{@code epochMs <= 0} 时原样返回
     */
    public static long backOff(long epochMs) {
        if (epochMs <= 1000L) return epochMs;
        return epochMs - 1000L;
    }

    /**
     * 构造读取命令。
     *
     * <h3>必须插在管道**之前**（否则静默失效）</h3>
     * wpa 日志的读取命令是
     * {@code logcat -d -s wpa_supplicant -s WPF_PROBE | grep -iE '<过滤式>'}。
     * 如果把 {@code -t} 直接拼到整条命令末尾，它会落进 **grep** 的参数里：
     * grep 不认识 {@code -t} → 报错退出 → 输出为空 →
     * 「本机没有 wpa 日志」这个假象会**静默**关掉整层日志判据
     * （不误判，但所有日志类证据集体失效，而且没有任何报错）。
     *
     * <p>所以这里按第一个 {@code |} 切开，把 {@code -t} 还给 logcat。
     *
     * @param base    不带时间限制的基础命令（可能含管道）
     * @param anchorMs 时间锚点；{@code <= 0} 时返回基础命令（离线/降级路径）
     * @return 可直接交给 shell 的完整命令
     */
    public static String withSince(String base, long anchorMs) {
        String a = anchor(backOff(anchorMs));
        if (a == null) return base;
        // 引号必须保留：串里有空格，不加引号会被 shell 拆成两个参数。
        String flag = "-t '" + a + "'";
        int pipe = base.indexOf('|');
        if (pipe < 0) return base + " " + flag;
        return base.substring(0, pipe).trim() + " " + flag + " " + base.substring(pipe).trim();
    }
}

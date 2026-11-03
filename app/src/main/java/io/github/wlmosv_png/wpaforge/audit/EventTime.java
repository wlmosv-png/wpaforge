package io.github.wlmosv_png.wpaforge.audit;

/**
 * 时间基准模型（C5 / S1-1）。
 *
 * <h3>为什么必须有这个类</h3>
 * 判定链上同时存在**八种**时间，它们不是同一个基准。把它们直接比较，
 * 轻则判错时序，重则把「别人的事件」算成本次候选的证据：
 *
 * <table>
 *   <tr><th>#</th><th>时间</th><th>来源 / 载体</th><th>基准</th><th>本类能否拿到</th></tr>
 *   <tr><td>1</td><td>事件实际发生时间</td><td>内核/框架内部</td>
 *       <td>—</td><td><b>不可得</b>（没有任何接口暴露）</td></tr>
 *   <tr><td>2</td><td>事件被系统记录的时间</td>
 *       <td>logcat 行首时间戳</td>
 *       <td>取决于 {@code -v} 选项</td><td>可得（{@link #parse}）</td></tr>
 *   <tr><td>3</td><td>应用采集时间</td>
 *       <td>{@code clock().uptimeMillis()}（{@code exec} 返回瞬间）</td>
 *       <td>CLOCK_MONOTONIC</td><td>可得</td></tr>
 *   <tr><td>4</td><td>应用入库时间</td>
 *       <td>队列 / 历史文件写入时刻</td>
 *       <td>墙钟</td><td>可得（不在本类）</td></tr>
 *   <tr><td>5</td><td>bootId</td>
 *       <td>{@code /proc/sys/kernel/random/boot_id}</td>
 *       <td>内核 UUID</td><td>可得（{@code CrackEngine.bootId()}）</td></tr>
 *   <tr><td>6</td><td>Attempt 创建时间</td>
 *       <td>runId 里的毫秒段</td><td>墙钟</td><td>可得</td></tr>
 *   <tr><td>7</td><td>Attempt 提交时间</td>
 *       <td>{@code connect-network} 发出时刻 {@code commandAt}</td>
 *       <td>CLOCK_MONOTONIC</td><td>可得</td></tr>
 *   <tr><td>8</td><td>Attempt 终止时间</td>
 *       <td>循环结束 / 裁决时刻</td>
 *       <td>CLOCK_MONOTONIC</td><td>可得</td></tr>
 * </table>
 *
 * <h3>本机实测（2026-11-02，OnePlus PJX110 / Android 15，可复核）</h3>
 * <pre>
 *   /proc/uptime                = 176634.66 / 176637.95 / 176639.60   ← BOOTTIME（含深睡）
 *   logcat -v monotonic 对应行   = 144946.202 / 144949.464 / 144951.093
 *   差值                        = 31688.458 / 31688.486 / 31688.507  ← 恒定
 *   ⇒ logcat -v monotonic 与 CLOCK_MONOTONIC 同频；
 *     该差值是深睡眠累计（BOOTTIME − MONOTONIC），**不是**时钟漂移。
 *   ⇒ **{@code /proc/uptime} 第一列不能当 monotonic 用**（差 8.8 小时）。
 *
 *   logcat -v epoch 1793603667.054  vs  date +%s 1793603667
 *   ⇒ 差 0.054s（就是两条命令的执行间隔）⇒ 与 CLOCK_REALTIME 同源。
 * </pre>
 *
 * <h3>使用禁令（违反即为判定链缺陷）</h3>
 * <ul>
 *   <li>不得把墙钟时间（{@link Base#WALL_CLOCK}）与单调时间（{@link Base#MONOTONIC}）
 *       直接比较 —— 用户校时、时区切换、跨重启都会让墙钟跳变。</li>
 *   <li>不得用「时间差很小」作为**强归属**证据。时间接近只提高可能性，
 *       不能把 AT1 升级为 AT3。归属只能靠 SSID/BSSID/netId 这类**内容**维度。</li>
 *   <li>不得用「最近一条事件」代表「属于本次尝试的事件」——
 *       广播/日志都可能延迟投递（{@code WifiAuthFailureWatcher} 的 generation
 *       校验就是为此存在）。</li>
 * </ul>
 *
 * <p>本类只做**解析与标定**，不做任何归属判断。
 */
public final class EventTime {

    private EventTime() {}

    /** 时间基准。 */
    public enum Base {
        /** CLOCK_MONOTONIC（不含深睡眠）。与 {@code SystemClock.uptimeMillis()} 同源。 */
        MONOTONIC,
        /** CLOCK_REALTIME（墙钟，可被用户/网络校时改变）。 */
        WALL_CLOCK,
        /** 无法解析。 */
        UNKNOWN
    }

    /** 一次日志行时间戳的解析结果。 */
    public static final class Stamp {
        public final long valueMs;
        public final Base base;

        Stamp(long valueMs, Base base) {
            this.valueMs = valueMs;
            this.base = base;
        }

        public boolean usable() { return base != Base.UNKNOWN; }

        @Override
        public String toString() {
            return base + "@" + valueMs;
        }
    }

    public static final Stamp NONE = new Stamp(0L, Base.UNKNOWN);

    /**
     * 解析 logcat 行首时间戳，**自动判定基准**。
     *
     * <p>支持的三种格式（都取自真机）：
     * <pre>
     *   threadtime(默认) : 11-02 15:14:17.659  5945  5945 I tag: msg   -> WALL_CLOCK（无年份）
     *   monotonic        : 144925.532  5945  5945 I app_process: ...    -> MONOTONIC
     *   epoch            : 1793603657.659  5945  5945 D ...             -> WALL_CLOCK
     * </pre>
     *
     * @param line 一行日志（可含前导空白）
     */
    public static Stamp parse(String line) {
        if (line == null) return NONE;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("---") || t.startsWith("--- beginning")) return NONE;

        // 形态 A：threadtime `MM-DD HH:MM:SS.mmm`（默认格式，无 -v 参数时）
        // 只在**固定位置**判定，避免把正文里恰好含 '-' 与 ':' 的行误认成时间。
        if (t.length() >= 18
                && t.charAt(2) == '-' && t.charAt(5) == ' '
                && t.charAt(8) == ':' && t.charAt(11) == ':') {
            long v = parseThreadtime(t.substring(0, 18));
            return v > 0 ? new Stamp(v, Base.WALL_CLOCK) : NONE;
        }

        // 形态 B / C：行首是纯数字 token（monotonic `144925.532` / epoch `1793603657.659`）
        // 注意不能把后续的 pid/tid 一起吃掉 —— 它们之间是**多个空格**分隔，
        // 而 token 内部不含空格。这里只取到第一个非数字/非点号为止。
        int i = 0;
        while (i < t.length()
                && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.')) {
            i++;
        }
        if (i == 0) return NONE;
        String head = t.substring(0, i);
        try {
            if (head.indexOf('.') >= 0) {
                double d = Double.parseDouble(head);
                long ms = (long) (d * 1000.0);
                // 大数值 => epoch 秒（>= 1e9）；小数值 => monotonic 秒
                return new Stamp(ms, d >= 1_000_000_000.0 ? Base.WALL_CLOCK : Base.MONOTONIC);
            }
            long v = Long.parseLong(head);
            return new Stamp(v * 1000L, Base.MONOTONIC);
        } catch (Throwable ignored) {
            return NONE;
        }
    }

    /** 解析 {@code 11-02 15:14:17.659} 成「当月内毫秒」——没有年份，只能做**同月内**相对排序。 */
    private static long parseThreadtime(String head) {
        try {
            int sp = head.indexOf(' ');
            if (sp < 0) return 0L;
            String datePart = head.substring(0, sp);          // 11-02
            String timePart = head.substring(sp + 1).trim();  // 15:14:17.659
            int dash = datePart.indexOf('-');
            int day = Integer.parseInt(datePart.substring(dash + 1));

            int c1 = timePart.indexOf(':');
            int c2 = timePart.indexOf(':', c1 + 1);
            int dot = timePart.indexOf('.');
            int hh = Integer.parseInt(timePart.substring(0, c1));
            int mm = Integer.parseInt(timePart.substring(c1 + 1, c2));
            String secPart = dot > 0 ? timePart.substring(c2 + 1, dot)
                                     : timePart.substring(c2 + 1);
            int ss = Integer.parseInt(secPart);
            int ms = 0;
            if (dot > 0) {
                String frac = timePart.substring(dot + 1);
                while (frac.length() < 3) frac = frac + "0";
                ms = Integer.parseInt(frac.substring(0, 3));
            }
            // 用「当月第几天」当量纲，避免引入不存在的年份
            long days = day;
            return ((days * 24L + hh) * 60L + mm) * 60_000L + ss * 1000L + ms;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 两个时间戳是否**可以互相比较**。
     *
     * <p>只有同基准才可比较。任一不可解析也返回 false ——
     * 「拿不准就不比」是这里唯一的正确策略。
     */
    public static boolean comparable(Stamp a, Stamp b) {
        return a != null && b != null && a.usable() && b.usable() && a.base == b.base;
    }

    /**
     * 两时间戳之差（毫秒）。**不同基准返回 {@link Long#MIN_VALUE}**，不是 0 ——
     * 0 会被误读成「同时发生」，那是更危险的错误。
     */
    public static long delta(Stamp a, Stamp b) {
        if (!comparable(a, b)) return Long.MIN_VALUE;
        return a.valueMs - b.valueMs;
    }
}
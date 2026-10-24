package io.github.wlmosv_png.wpaforge.audit;

/**
 * 日志读取的**状态判定**（纯逻辑，无 Android 依赖）。
 *
 * <h3>要修的那个缺陷</h3>
 * 原来的读取命令是 {@code logcat -d … | grep -iE '…'}。而 {@link ShellExec}
 * 只取 stdout、**完全不看退出码**，更糟的是：管道里 {@code $?} 是 **grep** 的退出码，
 * 于是三种完全不同的情况在调用方看来一模一样：
 * <ul>
 *   <li>logcat 参数非法 / 被拒绝 → 上游失败；</li>
 *   <li>logcat 正常但这段窗口里没有任何匹配行 → 真的空；</li>
 *   <li>grep 自身报错（比如过滤式写坏）→ 下游失败。</li>
 * </ul>
 * 三者都被压成「输出为空」。判定链一旦把「输出为空」当成「没有日志」，
 * 就会静默丢掉整层日志判据；更糟的是可能被当成「没有失败证据 → 密码错」。
 *
 * <h3>做法：两枚哨兵，各自绑定一侧的退出码</h3>
 * <pre>
 *   { logcat -d … 2>&1; echo "__WP_LC_RC=$?"; } | grep -iE '…|__WP_LC_RC='; echo "__WP_LC_GREP=$?"
 * </pre>
 * <ul>
 *   <li>花括号把 logcat 与它的退出码圈进**同一个管道** —— 若写成
 *       {@code logcat …; echo … | grep …}，{@code ;} 会截断管道，
 *       logcat 的输出根本进不了 grep（这是最容易写错的一处）；</li>
 *   <li>{@code 2>&1} 把 stderr 收进 stdout，于是 logcat 的报错文本也能被看见；</li>
 *   <li>grep 的退出码回显在管道**之外**，不会被过滤式影响；</li>
 *   <li>过滤式必须放行 {@code __WP_LC_RC=}，否则上游状态会被 grep 亲手滤掉。</li>
 * </ul>
 *
 * <h3>五态</h3>
 * <table>
 *   <tr><td>{@link Status#OK}</td><td>上游正常且有匹配行</td></tr>
 *   <tr><td>{@link Status#NO_MATCH}</td><td>上游正常，这段窗口确实没有匹配行（**可信的“空”**）。
 *       <br>判据是「剥掉哨兵后正文为空」，不是 grep 的退码 —— 哨兵行自己总会被过滤式放行，
 *       grep 退码因此在真机上几乎恒为 0（2026-10-24 实测）。</td></tr>
 *   <tr><td>{@link Status#UPSTREAM_FAILED}</td><td>logcat 非 0 退出（含 {@code -t} 格式错）</td></tr>
 *   <tr><td>{@link Status#GREP_FAILED}</td><td>grep 以 &gt;1 退出（过滤式或用法错误）</td></tr>
 *   <tr><td>{@link Status#INDETERMINATE}</td><td>拿不到哨兵 → 命令没跑成 / 被截断，**一律不能当“没有日志”**</td></tr>
 * </table>
 */
public final class LogcatCmd {

    /** logcat 一侧的退出码哨兵（会被 grep 过滤式放行）。 */
    public static final String RC = "__WP_LC_RC=";
    /** grep 一侧的退出码哨兵（在管道之外回显）。 */
    public static final String RC_GREP = "__WP_LC_GREP=";

    private LogcatCmd() {}

    /** 读取结果的判定档位。 */
    public enum Status {
        /** 上游正常且有匹配行。 */
        OK,
        /** 上游正常，但这段时间内确实没有匹配行。 */
        NO_MATCH,
        /** logcat 非 0 退出。 */
        UPSTREAM_FAILED,
        /** grep 自身失败（退出码 &gt;1）。 */
        GREP_FAILED,
        /** 哨兵缺失 —— 命令结果不可判。 */
        INDETERMINATE
    }

    /** 一次读取的结构化结果。 */
    public static final class Result {
        public final Status status;
        public final int logcatRc;
        public final int grepRc;
        /** 去掉两枚哨兵行之后的正文（可能为空）。 */
        public final String body;

        Result(Status status, int logcatRc, int grepRc, String body) {
            this.status = status;
            this.logcatRc = logcatRc;
            this.grepRc = grepRc;
            this.body = body == null ? "" : body;
        }

        /** 这段日志是否**可用作证据**（含可信的“空”）。 */
        public boolean logUsable() {
            return status == Status.OK || status == Status.NO_MATCH;
        }

        /** 是否有匹配行。 */
        public boolean hasLines() { return status == Status.OK; }

        /** 一行诊断（进判词/日志，不含任何口令）。 */
        public String describe() {
            return status.name().toLowerCase(java.util.Locale.ROOT)
                    + "(logcat=" + (logcatRc < 0 ? "?" : String.valueOf(logcatRc))
                    + ",grep=" + (grepRc < 0 ? "?" : String.valueOf(grepRc)) + ")";
        }

        static Result indeterminate() { return new Result(Status.INDETERMINATE, -1, -1, ""); }
    }

    /**
     * 给基础命令套上状态哨兵。
     *
     * @param base 不带状态回显的命令，可能含一个管道
     * @return 可直接交给 shell 的完整命令；{@code base} 为空时原样返回
     */
    public static String wrap(String base) {
        if (base == null || base.trim().isEmpty()) return base;
        String left;
        String right = null;
        int pipe = base.indexOf('|');
        if (pipe < 0) {
            left = base.trim();
        } else {
            left = base.substring(0, pipe).trim();
            right = base.substring(pipe).trim();
        }
        String probe = left + " 2>&1; echo \"" + RC + "$?\"";
        if (right == null) {
            // 注意：这里必须有 $? —— 少了它，grep 一侧的退码变成空串，
            // 解析器只能落到「不可判」，白丢一次可用性。
            return probe + "; echo \"" + RC_GREP + "$?\"";
        }
        // 花括号分组：logcat 的输出与它的退出码都必须在管道左侧。
        return "{ " + probe + "; } " + right + "; echo \"" + RC_GREP + "$?\"";
    }

    /** 解析回显。任何缺失都归入 {@link Status#INDETERMINATE}，绝不猜。 */
    public static Result parse(String raw) {
        if (raw == null) return Result.indeterminate();

        int grepRc = intAt(raw, lastIndexOf(raw, RC_GREP));
        int logcatRc = intAt(raw, lastIndexOf(raw, RC));

        StringBuilder body = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith(RC) || t.startsWith(RC_GREP)) continue;
            body.append(line).append('\n');
        }

        // 两枚哨兵缺一不可：任何一侧拿不到，整次读取就**不可判**。
        // 只判 logcat 一侧会让「grep 环节根本没执行」被误当成 OK（有数据）。
        if (logcatRc < 0 || grepRc < 0) {
            return new Result(Status.INDETERMINATE, logcatRc, grepRc, body.toString());
        }
        if (grepRc > 1) return new Result(Status.GREP_FAILED, logcatRc, grepRc, body.toString());
        if (logcatRc != 0) return new Result(Status.UPSTREAM_FAILED, logcatRc, grepRc, body.toString());
        // **真机实证（2026-10-24）**：过滤式必须放行 logcat 侧哨兵，
        // 于是哨兵行自己总会被 grep 匹配到 —— grep 一侧几乎恒为 0，
        // 「无匹配」根本不会以 GREP=1 的形式出现。所以这里**必须**同时看正文：
        // 剥掉哨兵之后正文为空，才是真正的「这段时间内没有匹配行」。
        // 只看退码会把空窗口报成 OK（有数据），正好丢掉最需要区分的那一档。
        boolean bodyEmpty = body.toString().trim().isEmpty();
        if (grepRc == 1 || bodyEmpty) {
            return new Result(Status.NO_MATCH, logcatRc, grepRc, body.toString());
        }
        return new Result(Status.OK, logcatRc, grepRc, body.toString());
    }

    /** 哨兵名之后的位置；找不到返回 -1。 */
    private static int lastIndexOf(String s, String needle) {
        int at = s.lastIndexOf(needle);
        return at < 0 ? -1 : at + needle.length();
    }

    /** 从 from 起解析一个十进制整数（允许前导空格）；拿不到返回 -1。 */
    private static int intAt(String s, int from) {
        if (s == null || from < 0 || from >= s.length()) return -1;
        int i = from;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        int st = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == st) return -1;
        try {
            return Integer.parseInt(s.substring(st, i));
        } catch (Throwable t) {
            return -1;
        }
    }
}

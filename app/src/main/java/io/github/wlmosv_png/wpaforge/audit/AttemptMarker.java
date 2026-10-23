package io.github.wlmosv_png.wpaforge.audit;

/**
 * 日志窗口起点标记（R6 契约）—— **纯逻辑，无 Android 依赖**。
 *
 * <h3>它要修的那个未经验证的前提</h3>
 * 现状：全部日志类判据（成功、失败、M1 计数）都建立在
 * 「命令前已 {@code logcat -c}，所以缓冲里就是本次尝试的日志」之上。
 * 而 {@code WifiStateLog.clear()} 返回 {@code void}、异常被吞、
 * 调用方不看结果 —— 这个前提从未被校验（R3 审计 S3-1/S3-2）。
 *
 * <h3>做法</h3>
 * 每条候选在清空日志**成功之后**，立刻往日志缓冲里写一行自造标记：
 * <pre>
 *   WPF_PROBE|attempt=&lt;attemptId&gt;
 * </pre>
 * 读日志时：
 * <ul>
 *   <li>找不到**属于本条候选**的标记 → 「缓冲没清空 或 读取不完整」
 *       → 该候选的日志证据一律标记为不可信，**不得据此判负**；</li>
 *   <li>在本条标记**之后**又出现别的候选标记 → 读到了别人窗口的内容
 *       → 同样不可信（{@code logcat -d} 是整缓冲 dump，很容易读过头）。</li>
 * </ul>
 * 于是「缓冲已隔离」从一个假设变成一条**可验证的存在性证据**。
 *
 * <h3>边界</h3>
 * 标记只能证明「这段日志属于哪条候选的窗口」，
 * <b>不能</b>证明「这段日志里的连接是这条候选建的」——与
 * {@link AttemptId} 同一禁令，见其类注释。
 */
public final class AttemptMarker {

    /** 标记 tag（写入时的 tag 名）。 */
    public static final String TAG = "WPF_PROBE";

    /** 标记行的字段名。 */
    public static final String FIELD = "attempt=";

    private AttemptMarker() {}

    // ------------------------------------------------------------ 构造

    /** 标记行（不含换行）。attemptId 非法时返回 {@code null}。 */
    public static String line(String attemptId) {
        if (!AttemptId.wellFormed(attemptId)) return null;
        return TAG + "|" + FIELD + attemptId;
    }

    /**
     * 写标记要执行的 shell 命令（**本阶段不执行**）。
     *
     * <p>用 toybox 的 {@code log -t <tag> <msg>}。设备上是否存在、
     * 写出的行是否会被 {@code logcat -d -s wpa_supplicant} 之外的读取方式
     * 看见，都**尚未验证**（报告里记为 NOT RUN）。
     *
     * @return 命令串；attemptId 非法返回 {@code null}
     */
    public static String writeCommand(String attemptId) {
        String l = line(attemptId);
        if (l == null) return null;
        return "log -t " + TAG + " \"" + FIELD + attemptId + "\"";
    }

    // ------------------------------------------------------------ 判定

    /** 文本里是否出现**本条候选**的标记行（逐行精确匹配）。 */
    public static boolean present(String logText, String attemptId) {
        String want = line(attemptId);
        if (want == null || logText == null || logText.isEmpty()) return false;
        for (String raw : logText.split("\n")) {
            if (raw.contains(want)) return true;
        }
        return false;
    }

    /**
     * 是否读到了**别的候选**的标记（同 run 的其它序号、或别的 run）。
     *
     * <p>只看「本条标记之后」的部分：之前的标记属于上一个窗口，读到它
     * 本身不构成污染证据。
     */
    public static boolean foreignMarkerAfter(String logText, String attemptId) {
        String want = line(attemptId);
        if (want == null || logText == null) return false;
        String[] lines = logText.split("\n");
        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(want)) { at = i; break; }
        }
        if (at < 0) return false;                     // 连自己的都没有 → 由 present 负责
        for (int i = at + 1; i < lines.length; i++) {
            String id = idIn(lines[i]);
            if (id != null && !id.equals(attemptId)) return true;
        }
        return false;
    }

    /**
     * **日志证据可信性**：本候选的窗口是否被可靠地隔离出来。
     *
     * <p>只有为 {@code true} 时，才允许用这段日志判定这条候选的结论。
     * 为 {@code false} 时，日志类判据必须放弃（归「无证据」而不是「失败」）。
     */
    public static boolean isolated(String logText, String attemptId) {
        return present(logText, attemptId) && !foreignMarkerAfter(logText, attemptId);
    }

    /** 文本里**最后一个**标记的 attemptId；没有标记返回 {@code null}。诊断用。 */
    public static String lastId(String logText) {
        if (logText == null) return null;
        String found = null;
        for (String raw : logText.split("\n")) {
            String id = idIn(raw);
            if (id != null) found = id;
        }
        return found;
    }

    /** 从一行里取出标记携带的 attemptId（形状合法才算）；没有返回 {@code null}。 */
    public static String idIn(String oneLine) {
        if (oneLine == null) return null;
        int k = oneLine.indexOf(FIELD);
        if (k < 0) return null;
        String rest = oneLine.substring(k + FIELD.length()).trim();
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c <= ' ') { end = i; break; }
        }
        String cand = rest.substring(0, end);
        return AttemptId.wellFormed(cand) ? cand : null;
    }
}

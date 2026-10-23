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

    /**
     * 取「本候选起点标记**之后**」的日志切片 —— 日志类判据必须只在切片上跑。
     *
     * <h3>为什么光有 isolated 还不够</h3>
     * {@code logcat -c} 失败时，缓冲里会留着**上一条候选**的行；这些行位于
     * 本候选标记**之前**。只判断「有没有外来标记」看不出它们，判据函数又是对
     * 整段文本做子串匹配（例如 {@code CTRL-EVENT-SSID-TEMP-DISABLED ssid="目标"}），
     * 于是陈旧失败行照样能被算到本条候选头上（审计 S3-2）。
     *
     * <p>切片把归属变成**结构**而非假设：只有标记之后的行才可能属于本次尝试。
     *
     * @return 标记之后的部分（不含标记行本身）；找不到标记或 id 非法时返回 {@code ""}
     *         —— 空切片等于「没有可用日志证据」，判据函数在其上只会得到 false。
     */
    public static String sliceAfter(String logText, String attemptId) {
        String want = line(attemptId);
        if (want == null || logText == null) return "";
        int at = 0;
        boolean found = false;
        for (String raw : logText.split("\n", -1)) {
            int len = raw.length() + 1;
            if (found) { /* 已过标记行 */ }
            else if (raw.contains(want)) { found = true; at += len; continue; }
            if (found) break;
            at += len;
        }
        if (!found) return "";
        return at <= logText.length() ? logText.substring(at) : "";
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

    // ------------------------------------------------------------ 窗口元数据

    /**
     * 一次尝试的**日志窗口元数据**（纯数据）。
     *
     * <p>把四件事分开记录，不允许合成一个布尔值后再解释：
     * <ol>
     *   <li>{@link #clearOk} 日志清理操作是否拿到帧（注意：{@code logcat -c} 成功时**无输出**，
     *       所以不能用「输出为空」当失败判据）</li>
     *   <li>{@link #markerWritten} 起点标记的写命令是否已发出（本阶段不做回读校验）</li>
     *   <li>{@link #markerVisible} 本次读取文本里是否真的出现了本候选的标记</li>
     *   <li>{@link #foreignMarker} 本候选标记之后是否出现了**别的候选**的标记</li>
     * </ol>
     *
     * <p><b>语义边界</b>：{@link #evidenceUsable()} 为真只表示「这段日志允许被进一步评估」，
     * 是任何日志类结论的**必要**条件，绝不是充分条件 —— 它既不能推出成功，也不能推出失败。
     * 为假或未知时，只能把日志证据记为「不可用」，**不得**把「没看到失败行」解释成认证失败。
     */
    public static final class Window {
        /** 本条候选的 id；非法/缺失为 {@code null}（显式无效，不会退化成某个合法 id）。 */
        public final String attemptId;
        public final boolean clearOk;
        public final boolean markerWritten;
        public final boolean markerVisible;
        public final boolean foreignMarker;
        /** 起始标记与外来标记都没有出现时为 true —— id 非法时为 false。 */
        public final boolean isolated;
        /** 短标签，进判词/诊断：{@code id-invalid} / {@code no-marker} / {@code foreign} / {@code ok}。 */
        public final String reason;

        Window(String attemptId, boolean clearOk, boolean markerWritten,
               boolean markerVisible, boolean foreignMarker) {
            this.attemptId = attemptId;
            this.clearOk = clearOk;
            this.markerWritten = markerWritten;
            this.markerVisible = markerVisible;
            this.foreignMarker = foreignMarker;
            this.isolated = markerVisible && !foreignMarker;
            if (!AttemptId.wellFormed(attemptId)) this.reason = "id-invalid";
            else if (foreignMarker) this.reason = "foreign";
            else if (!markerVisible) this.reason = "no-marker";
            else this.reason = "ok";
        }

        /** 日志证据是否可用（必要条件，非充分条件）。 */
        public boolean evidenceUsable() { return isolated; }

        /** 一行可读诊断（进 detail/日志）。 */
        public String describe() {
            return "clear=" + (clearOk ? "ok" : "no-frame")
                    + " marker=" + (markerWritten ? (markerVisible ? "visible" : "invisible")
                                                   : "not-sent")
                    + " foreign=" + (foreignMarker ? "yes" : "no")
                    + " iso=" + (isolated ? "yes" : "no");
        }
    }

    /**
     * 由「清理结果 + 标记写入结果 + 本轮实际读到的文本」算出窗口元数据。
     *
     * <p>只做记录，**不做任何判定**：调用方不得据此改变成功/失败结论（R6 阶段约束）。
     *
     * @param attemptId     本条候选的 id；null/非法 → 元数据显式标记无效
     * @param clearOk       日志清理是否拿到帧
     * @param markerWritten 起点标记的写命令是否已发出
     * @param readText      本轮实际读到的日志文本（null 视为没读到）
     */
    public static Window evaluate(String attemptId, boolean clearOk, boolean markerWritten,
                                  String readText) {
        String id = AttemptId.wellFormed(attemptId) ? attemptId : null;
        boolean visible = id != null && present(readText, id);
        boolean foreign = id != null && foreignMarkerAfter(readText, id);
        return new Window(id, clearOk, markerWritten, visible, foreign);
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

package io.github.wlmosv_png.wpaforge.audit;

/**
 * 判据 D 的**纯归属判定**：把一份 {@code dumpsys wifi} 文本，按「当前 attempt 的
 * 时间窗口 + 配置归属」翻译成「本 attempt 是否被 framework 判为密码错误」。
 *
 * <p>纯逻辑、无 Android 依赖、可离线穷举 —— 抽取出来的目的，是让
 * {@link CrackEngine#checkDumpsEvidence} 与单元测试**共用同一份归因代码**，
 * 而不是各写一份互不相同的镜像逻辑。
 *
 * <h3>归因条件（全部必须同时满足，缺一不认）<\/h3>
 * <ol>
 *   <li>窗口可用：{@link WifiEvidenceProbe#windowUsable}（两端同天且顺序正确）；<\/li>
 *   <li>窗口内存在**本次候选对应的新增配置事件**：target SSID 的 {@code uid=0}
 *       {@code ADD_OR_UPDATE}（{@link WifiEvidenceProbe#hasRootAddInWindow}）——<\/li>
 *   <li>明确的 {@code AUTHENTICATION_FAILURE / AUTH_FAILURE_WRONG_PSWD}（连接级）
 *       或等价的 {@code DISABLED_BY_WRONG_PASSWORD}（配置级）；<\/li>
 *   <li>事件时间落在窗口 {@code [fromMs, toMs]} 内；<\/li>
 *   <li>连接级还要求 BSSID 收窄：目标 BSSID 已知且事件带 BSSID 时必须相等。<\\/li>
 * <\\/ol>
 *
 * <p><b>{@code uid=0} 本身不能单独证明某条失败属于当前候选<\\/b> —— 必须同时有
 * 本次配置关联（②）。否则会读到上一条候选、或同 SSID 历史配置的失败。
 */
public final class DumpsAttribution {

    /** 归属结论。 */
    public enum Verdict {
        /** 本 attempt 被 framework 明确判为密码错误（可进入 AT3 归因）。 */
        WRONG_KEY,
        /** 没有可归因的密码错证据。 */
        NONE
    }

    /** 一次归属判定的结果。 */
    public static final class Result {
        public final Verdict verdict;
        /** {@code "config"} = 配置级（DISABLED_BY_WRONG_PASSWORD）；
         *  {@code "connevent"} = 连接级（AUTH_FAILURE_WRONG_PSWD）；{@code null} = 未命中。 */
        public final String source;
        /** 诊断摘要：有失败但原因不是密码错（例如 NEW_CONNECTION_ATTEMPT），否则 {@code null}。 */
        public final String otherFailure;

        /** 命中事件的开始时间（{@code parseStamp} 坐标）；未命中为 -1。
         *  引擎据此把「已认领的事件」排除在后续 attempt 之外，防重复消费。 */
        public final long claimedStartMs;

        Result(Verdict verdict, String source, String otherFailure) {
            this(verdict, source, otherFailure, -1L);
        }

        Result(Verdict verdict, String source, String otherFailure, long claimedStartMs) {
            this.verdict = verdict;
            this.source = source;
            this.otherFailure = otherFailure;
            this.claimedStartMs = claimedStartMs;
        }

        static Result none(String otherFailure) {
            return new Result(Verdict.NONE, null, otherFailure);
        }

        public boolean isWrongKey() {
            return verdict == Verdict.WRONG_KEY;
        }

        @Override public String toString() {
            return "attribution(" + verdict + " src=" + source + " other=" + otherFailure + ")";
        }
    }

    private DumpsAttribution() {}

    /**
     * 纯归属判定。
     *
     * @param dumpsText dumpsys wifi 原文
     * @param ssid      目标 SSID（逐字相等）
     * @param bssid     目标 BSSID（可空；为空则不做 BSSID 收窄）
     * @param fromMs    窗口起点（{@link WifiEvidenceProbe#parseStamp} 坐标系；-1 = 不限）
     * @param toMs      窗口终点（-1 = 不限）
     */
    public static Result evaluate(String dumpsText, String ssid, String bssid,
                                  long fromMs, long toMs) {
        return evaluate(dumpsText, ssid, bssid, fromMs, toMs, -1L);
    }

    /**
     * 纯归属判定（带**已认领事件排除**）。
     *
     * @param excludeStartMs 上一个 attempt 已认领事件的开始时间；同一条事件
     *                       不会被第二个 attempt 再次认领（防重复消费）。
     *                       传 -1 表示不排除。
     */
    public static Result evaluate(String dumpsText, String ssid, String bssid,
                                  long fromMs, long toMs, long excludeStartMs) {
        if (dumpsText == null || dumpsText.isEmpty()) return Result.none(null);
        if (ssid == null || ssid.isEmpty()) return Result.none(null);
        if (!WifiEvidenceProbe.windowUsable(fromMs, toMs)) return Result.none(null);

        java.util.List<WifiEvidenceProbe.ConfigEvent> cfgs =
                WifiEvidenceProbe.configEvents(dumpsText);
        java.util.List<WifiEvidenceProbe.ConnEvent> conns =
                WifiEvidenceProbe.connectionEvents(dumpsText);

        // ② 本次候选对应的新增配置事件（uid=0 的 ADD_OR_UPDATE，root 通道归属）。
        boolean rootAdd = WifiEvidenceProbe.hasRootAddInWindow(cfgs, ssid, fromMs, toMs);

        // ③ 配置级：配置被 framework 标为密码错误（要求本次 root 建的配置）。
        WifiEvidenceProbe.ConfigEvent cfgWrong = rootAdd
                ? WifiEvidenceProbe.findWrongPasswordDisable(cfgs, ssid, fromMs, toMs, true)
                : null;
        // ④ 连接级：framework 明确 WRONG_PSWD（要求本次 root 建的配置 + BSSID 收窄）。
        WifiEvidenceProbe.ConnEvent connWrong = rootAdd
                ? WifiEvidenceProbe.findWrongPsk(conns, ssid, bssid, fromMs, toMs, true)
                : null;

        // 已认领事件排除：同一条连接级事件不得被下一个 attempt 再认领。
        if (connWrong != null && excludeStartMs >= 0 && connWrong.timeMs == excludeStartMs) {
            connWrong = null;
        }
        if (cfgWrong != null || connWrong != null) {
            long claimed = connWrong != null ? connWrong.timeMs
                    : (cfgWrong != null ? cfgWrong.timeMs : -1L);
            return new Result(Verdict.WRONG_KEY,
                    cfgWrong != null ? "config" : "connevent", null, claimed);
        }
        // 诊断级：有失败但 reason 不是 WRONG_PSWD（不能判密码错，只如实记录）。
        WifiEvidenceProbe.ConnEvent connOther =
                WifiEvidenceProbe.findNonPskFailure(conns, ssid, fromMs, toMs);
        String other = connOther == null ? null
                : connOther.level2FailureCode + "/" + connOther.level2FailureReason
                  + " dur=" + connOther.durationMs + "ms";
        return Result.none(other);
    }
}

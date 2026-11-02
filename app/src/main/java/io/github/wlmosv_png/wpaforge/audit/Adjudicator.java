package io.github.wlmosv_png.wpaforge.audit;

import java.util.Collection;

/**
 * **唯一裁决器**（C3 统一裁决架构）。
 *
 * <h3>它解决的核心问题</h3>
 * 迁移前，五种证据源各自 {@code new Attempt(password, Verdict.AUTH_FAIL, ...)}：
 * {@code failover} / {@code authcast} / {@code m1retry} / {@code wpalog 支A} /
 * {@code wpalog 支B}。每处都自己判断「这条证据够不够硬」，于是同一个问题有
 * 五种答案，B1~B5 修的大部分不一致都是这个结构的必然产物。
 *
 * <p>现在：证据源只提交 {@link Evidence}，结论**只在这里**产生一次。
 *
 * <h3>裁决规则（按优先级，第一条命中即返回）</h3>
 * <table>
 *   <tr><th>#</th><th>条件</th><th>结论</th><th>能否永久排除</th></tr>
 *   <tr><td>0</td><td>前置闸门未通过（STALE / UNREADABLE / DISCONNECT_FAILED）</td>
 *       <td>UNKNOWN</td><td>否</td></tr>
 *   <tr><td>1</td><td>CONNECTED 且归属 AT3</td><td>SUCCESS / DIRECT</td><td>—</td></tr>
 *   <tr><td>2</td><td>CONNECTED 但归属 &lt; AT3</td>
 *       <td>UNKNOWN（归属不明）</td><td>否</td></tr>
 *   <tr><td>3</td><td>WRONG_KEY 且归属 AT3</td><td>AUTH_FAIL / DIRECT</td><td><b>是</b></td></tr>
 *   <tr><td>4</td><td>WRONG_KEY 且归属 AT2 / AT1</td><td>AUTH_FAIL / INFERRED</td><td>否</td></tr>
 *   <tr><td>5</td><td>NETWORK_REJECTED（无 WRONG_KEY）</td>
 *       <td>UNKNOWN（reason=network-rejected）</td><td>否</td></tr>
 *   <tr><td>6</td><td>只有 NOT_CONNECTED，生命周期 ACTIVE</td><td>INCOMPLETE</td><td>否</td></tr>
 *   <tr><td>7</td><td>只有 NOT_CONNECTED，生命周期 TERMINATED</td>
 *       <td>TERMINATED_UNKNOWN</td><td>否</td></tr>
 *   <tr><td>8</td><td>只有 NOT_CONNECTED，生命周期 UNKNOWN</td><td>UNKNOWN</td><td>否</td></tr>
 *   <tr><td>9</td><td>没有任何（已归属的）证据</td><td>TIMEOUT</td><td>否</td></tr>
 * </table>
 *
 * <h3>三条硬约束（都在本文件内可验证）</h3>
 * <ol>
 *   <li><b>未归属的证据一律不用。</b>{@link Evidence#belongsTo} 为假的证据直接丢弃
 *       —— 「无法唯一归属的事件必须保持未归属」。</li>
 *   <li><b>AP-1 永不产生 AUTH_FAIL。</b>{@link Evidence.Claim#NOT_CONNECTED}
 *       只能落到 INCOMPLETE / TERMINATED_UNKNOWN / UNKNOWN。</li>
 *   <li><b>只有 AT3 允许永久排除。</b>{@link EvidenceGrade#persistable} 只看
 *       grade，而 grade 只在这里产生。</li>
 * </ol>
 *
 * <p>本类是**纯逻辑**：不碰 Android、不读时间、不写盘。可离线穷举
 * （见 {@code AdjudicatorMatrixTest}）。
 */
public final class Adjudicator {

    private Adjudicator() {}

    /** 裁决结果。 */
    public static final class Result {
        public final CrackEngine.Verdict verdict;
        public final EvidenceGrade.Level grade;
        /** 机器可读归类码（与既有 {@code Attempt.reason} 同口径，为空表示无）。 */
        public final String reason;
        /** 采信的那条证据来源；无证据时为空。 */
        public final Evidence.Source bySource;
        /** 人为可读的依据描述（沿用既有判词风格，便于对比迁移前后）。 */
        public final String detail;

        Result(CrackEngine.Verdict verdict, EvidenceGrade.Level grade,
               String reason, Evidence.Source bySource, String detail) {
            this.verdict = verdict;
            this.grade = grade;
            this.reason = reason == null ? "" : reason;
            this.bySource = bySource;
            this.detail = detail == null ? "" : detail;
        }

        @Override
        public String toString() {
            return verdict + "/" + grade + (reason.isEmpty() ? "" : "(" + reason + ")");
        }
    }

    /**
     * 裁决一次尝试。
     *
     * @param st        尝试状态（生命周期 + 前置闸门）
     * @param evidences 本次尝试观察到的全部证据（**可含未归属的**，会被自动丢弃）
     * @return 唯一结论；永不返回 {@code null}
     */
    public static Result adjudicate(AttemptState st, Collection<Evidence> evidences) {
        AttemptState state = st == null
                ? new AttemptState("", CrackEngine.Lifecycle.UNKNOWN, AttemptState.Gate.OK, false)
                : st;

        // ---- 规则 0：前置闸门 ----
        // 旧配置没删净 / 读不到前置状态 / 上一条没断开时，status 里的「目标已连上」
        // 可能来自旧配置或上一条连接 —— 任何证据都不可信，一刀切掉。
        if (!state.gateOk()) {
            return new Result(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE,
                    "gate-" + state.gate.name().toLowerCase(java.util.Locale.ROOT),
                    null, "pre-command gate not satisfied; no evidence is trustworthy");
        }

        // ---- 只采信**属于本次尝试**的证据 ----
        boolean sawConnected = false, sawWrongKey = false, sawReject = false, sawNotConnected = false;
        Evidence.Attribution bestConnected = Evidence.Attribution.NONE;
        Evidence.Attribution bestWrongKey = Evidence.Attribution.NONE;
        Evidence.Source connSrc = null, wrongSrc = null;

        if (evidences != null) {
            for (Evidence e : evidences) {
                if (e == null || !e.belongsTo(state.attemptId)) continue;   // 未归属 -> 丢弃
                switch (e.claim) {
                    case CONNECTED:
                        sawConnected = true;
                        if (rank(e.attribution) > rank(bestConnected)) bestConnected = e.attribution;
                        if (connSrc == null) connSrc = e.source;
                        break;
                    case WRONG_KEY:
                        sawWrongKey = true;
                        if (rank(e.attribution) > rank(bestWrongKey)) bestWrongKey = e.attribution;
                        if (wrongSrc == null) wrongSrc = e.source;
                        break;
                    case NETWORK_REJECTED:
                        sawReject = true;
                        break;
                    case NOT_CONNECTED:
                    default:
                        sawNotConnected = true;
                        break;
                }
            }
        }

        // ---- 规则 1/2：成功 ----
        // 只有「本次 Attempt 建立起来的连接」才算成功。
        if (sawConnected) {
            if (rank(bestConnected) >= rank(Evidence.Attribution.AT3)) {
                return new Result(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT,
                        "connected-attributed", connSrc,
                        "handshake completed with IP, attributed to this attempt (AT3)");
            }
            // 看到「目标已连上」但绑不到本次尝试 —— 既不当成功（可能是旧配置/回连），
            // 也不当失败。如实报归属不明。
            return new Result(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE,
                    "on-target-unattributed", connSrc,
                    "target appears connected but cannot be attributed to this attempt");
        }

        // ---- 规则 3/4：明确密钥不符 ----
        if (sawWrongKey) {
            boolean permanent = rank(bestWrongKey) >= rank(Evidence.Attribution.AT3);
            return new Result(CrackEngine.Verdict.AUTH_FAIL,
                    permanent ? EvidenceGrade.Level.DIRECT : EvidenceGrade.Level.INFERRED,
                    "wrong-key", wrongSrc,
                    permanent
                            ? "explicit wrong-key evidence attributed to this attempt (AT3)"
                            : "wrong-key evidence without AT3 attribution - this round only");
        }

        // ---- 规则 5：网络拒绝（关联被拒等）----
        // 「AP 拒绝关联」不等于「密钥不符」：MAC 过滤、负载、加密方式不匹配
        // 都会产生同样的行。宁可不确定，也不要永久排除一个正确密码。
        if (sawReject) {
            return new Result(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE,
                    "network-rejected", null,
                    "association rejected by the network - not a proven wrong passphrase");
        }

        // ---- 规则 6/7/8：只证明「没连上」----
        if (sawNotConnected) {
            switch (state.lifecycle) {
                case ACTIVE:
                    return new Result(CrackEngine.Verdict.INCOMPLETE, EvidenceGrade.Level.NONE,
                            "not-connected-active", null,
                            "not connected yet, but the connection is still in progress");
                case TERMINATED:
                    return new Result(CrackEngine.Verdict.TERMINATED_UNKNOWN,
                            EvidenceGrade.Level.NONE, "not-connected-terminated", null,
                            "connection terminated without attributable evidence "
                                    + "(not a wrong passphrase)");
                default:
                    return new Result(CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE,
                            "not-connected-state-unknown", null,
                            "connection state unconfirmable; no definitive verdict");
            }
        }

        // ---- 规则 9：什么证据都没有 ----
        return new Result(CrackEngine.Verdict.TIMEOUT, EvidenceGrade.Level.NONE,
                "no-evidence", null,
                "observation window ended with no attributable evidence");
    }

    private static int rank(Evidence.Attribution a) {
        if (a == null) return 0;
        switch (a) {
            case AT3: return 3;
            case AT2: return 2;
            case AT1: return 1;
            default: return 0;
        }
    }

    /**
     * 由裁决结果构造终局 {@code Attempt}（保留既有判词口径）。
     *
     * <p>五种证据源迁移后统一走这里，不再各自 {@code new Attempt(...)}。
     */
    public static CrackEngine.Attempt toAttempt(String password, Result r,
                                                long elapsedMs, String detailSuffix) {
        return toAttempt(password, r, elapsedMs, detailSuffix, "");
    }

    /**
     * 同上，并指定判词里的 {@code confirmedBy}（"哪一个信号确认的"）。
     *
     * <p>为什么必须走构造器：{@code Attempt.confirmedBy} 是 {@code final} ——
     * 刻意设计成「结论一旦产生，依据不可被下游改写」。
     */
    public static CrackEngine.Attempt toAttempt(String password, Result r,
                                                long elapsedMs, String detailSuffix,
                                                String confirmedBy) {
        String d = r.detail;
        if (detailSuffix != null && !detailSuffix.isEmpty()) {
            d = d + " · " + detailSuffix;
        }
        CrackEngine.Attempt a = new CrackEngine.Attempt(
                password, r.verdict, elapsedMs, d,
                confirmedBy == null ? "" : confirmedBy);
        a.grade = r.grade;
        if (!r.reason.isEmpty()) a.reason = r.reason;
        return a;
    }
}
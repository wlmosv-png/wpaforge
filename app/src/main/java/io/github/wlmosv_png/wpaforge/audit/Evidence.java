package io.github.wlmosv_png.wpaforge.audit;

/**
 * 一条**证据**及其归属强度（C3 统一裁决架构）。
 *
 * <h3>为什么要把「证据」和「结论」拆开</h3>
 * 迁移前，五种来源各自 {@code new Attempt(password, Verdict.AUTH_FAIL, ...)} ——
 * 谁都能直接决定候选的命运，且每处都要自己判断「这条证据够不够硬」。
 * 结果是同一个问题在五处有五种答案（B1~B5 修的就是这些不一致）。
 *
 * <p>现在每个来源只回答两件事：
 * <ol>
 *   <li><b>你观察到了什么</b>（{@link Claim}）</li>
 *   <li><b>这份观察能绑多紧</b>（{@link Attribution}）</li>
 * </ol>
 * 「这算成功还是失败、能不能永久排除」只由 {@link Adjudicator} 回答一次。
 *
 * <h3>归属强度（本类最重要的一张表）</h3>
 * <table>
 *   <tr><th>等级</th><th>证据能证明什么</th><th>能否永久排除</th></tr>
 *   <tr><td>{@link Attribution#NONE}</td>
 *       <td>什么都证明不了（切片外的行 / 无任何维度）</td><td>否</td></tr>
 *   <tr><td>{@link Attribution#AT1}</td>
 *       <td>SSID 相同。**同 SSID 多配置时这等于没证据**</td><td>否</td></tr>
 *   <tr><td>{@link Attribution#AT2}</td>
 *       <td>SSID + BSSID 相同 —— 说明是同一个 AP</td><td>否</td></tr>
 *   <tr><td>{@link Attribution#AT3}</td>
 *       <td>SSID + BSSID + netId 与**本次新建配置**一致</td><td>是</td></tr>
 * </table>
 *
 * <p><b>AT3 的现实可达性</b>：需要「事件本身携带 netId」且「能与本次配置的
 * netId 对齐」。本条链上唯一可能携带 netId 的是 wpa_supplicant 的
 * {@code CTRL-EVENT-SSID-TEMP-DISABLED ... id=N}；它与 Android Net ID 是否
 * 同一体系**尚未证实**（残余风险 R-4）。广播（authcast）不含 netId/BSSID
 * （Android 12+ 已废弃这些 extra），因此 authcast **永远到不了 AT3**。
 *
 * <p>这条约束是刻意的：宁可少一个永久排除，不可错排除一个正确密码。
 */
public final class Evidence {

    /** 证据来自哪一层。迁移期间保留它用于排查与断言。 */
    public enum Source {
        /** 系统回连到别的网络（只证明「本次没连上」）。 */
        FAILOVER,
        /** framework 的 ERROR_AUTHENTICATING 广播。 */
        AUTHCAST,
        /** AP 重发 M1（握手没进展）。 */
        M1RETRY,
        /** 日志里带 SSID 归属的失败（TEMP-DISABLED reason=WRONG_KEY）。 */
        WPALOG_A,
        /** 日志里零字段/无归属的失败行。 */
        WPALOG_B,
        /** status 四条件同行（SSID+COMPLETED+IP+netId）。 */
        SUCCESS_STATUS,
        /** 日志里目标 BSSID 的 Key negotiation completed。 */
        SUCCESS_LOG
    }

    /**
     * 这条证据**主张**了什么。
     *
     * <p>注意 {@link #NOT_CONNECTED} 与 {@link #WRONG_KEY} 的区别 ——
     * 这正是本任务的核心：
     * <pre>
     *   NOT_CONNECTED = 本次没连上（AP-1）。连接终止 ≠ 密码错。
     *   WRONG_KEY     = 观察到「密钥不符」的直接证据（AP-3）。
     * </pre>
     */
    public enum Claim {
        /** 本次连接没有成功建立。 */
        NOT_CONNECTED,
        /** 观察到了密钥不符的直接证据。 */
        WRONG_KEY,
        /** AP/网络层面拒绝了请求（关联被拒等），不等于密钥不符。 */
        NETWORK_REJECTED,
        /** 目标已连上且拿到有效 IP。 */
        CONNECTED
    }

    /**
     * 证据归属强度。
     *
     * <p>{@link #AT3} 是**唯一**允许永久排除的等级，见类注释的表。
     */
    public enum Attribution {
        NONE, AT1, AT2, AT3
    }

    /** 采集到这条证据的时刻（{@code clock().uptimeMillis()}，单调，不与墙钟混比）。 */
    public final long observedAtMs;
    public final Source source;
    public final Claim claim;
    public final Attribution attribution;
    /**
     * 证据归属的 Attempt。
     *
     * <p>为空表示**未能归属到任何一次尝试** —— 按契约，这种证据必须保持未归属，
     * 不得被任何 Attempt 采信。
     */
    public final String attemptId;

    public Evidence(Source source, Claim claim, Attribution attribution,
                    String attemptId, long observedAtMs) {
        this.source = source;
        this.claim = claim;
        this.attribution = attribution;
        this.attemptId = attemptId;
        this.observedAtMs = observedAtMs;
    }

    /** 是否归属到指定 Attempt（空 id 永不属于任何人）。 */
    public boolean belongsTo(String id) {
        return id != null && !id.isEmpty() && id.equals(attemptId);
    }

    /**
     * 这条证据**自身**是否足以支撑「永久排除」。
     *
     * <p>注意这只是必要条件：调用方还必须确认它属于当前 Attempt。
     */
    public boolean strongEnoughToExclude() {
        return claim == Claim.WRONG_KEY && attribution == Attribution.AT3;
    }

    @Override
    public String toString() {
        return source + "/" + claim + "/" + attribution
                + (attemptId == null ? "" : "@" + attemptId);
    }
}
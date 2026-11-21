package io.github.wlmosv_png.wpaforge.audit;

/**
 * 成功判定的**唯一入口**。
 *
 * <h3>规则</h3>
 * 目标 SSID + supplicant 状态 COMPLETED + 有效 IP —— 三条同时成立即判成功。
 *
 * <h3>为什么 netId 绝不参与判定</h3>
 * {@code targetNetId} 是「命令发出后去查到的值」，时序敏感：
 * 系统还没把配置建好时它是 -1，系统换了连接之后它可能已经是别的连接的值。
 * 一旦拿它当否决条件，就会出现「密码正确、系统确实连上了目标，却因为 netId
 * 对不上而整段跳过成功判断」—— 实测事故：正确密码 12 秒全超时（漏判）。
 *
 * <p>归因本来就不需要靠它：命令之前有两道前置闸门
 * ①<em>目标配置已删净并回读校验</em> ②<em>命令前若连着目标已断开并确认</em>，
 * 所以命令之后出现的「目标 + 握手完成 + 有 IP」只可能来自本条候选。
 * netId 相符只作为**证据强度的标注**（{@code supplicant+netId}）。
 *
 * <h3>反向约束</h3>
 * 判定必须严：SSID 必须逐字相等、状态必须 COMPLETED、IP 必须不是 0.0.0.0。
 * 少了任何一条都不算成功 —— 宁可报「不确定」，也不能把错的当成对的。
 *
 * <p>纯逻辑，无 Android 依赖。
 */
public final class SuccessRule {

    private SuccessRule() {}

    /** 三条硬条件；netId 不在此列 */
    public static boolean isSuccess(String targetSsid, String statusSsid,
                                    String supplicantState, String ip) {
        if (targetSsid == null || targetSsid.isEmpty()) return false;
        if (statusSsid == null || !targetSsid.equals(statusSsid)) return false;
        if (supplicantState == null || !"COMPLETED".equalsIgnoreCase(supplicantState)) return false;
        if (ip == null || ip.isEmpty() || "0.0.0.0".equals(ip)) return false;
        return true;
    }

    /** 证据强度标注：netId 相符只是加分，不影响是否判成功 */
    public static String evidence(long statusNetId, long targetNetId) {
        return (statusNetId >= 0 && statusNetId == targetNetId)
                ? "supplicant+netId" : "supplicant";
    }

    // ------------------------------------------------------------ 成功归因评估

    /**
     * 成功判定的**完整评估**：协议层观察 + 归属证据 → 业务结论。
     *
     * <h3>为什么需要它（2026-10-24 审计 R1）</h3>
     * 三条件是**协议层事实**（系统宣告握手完成且拿到地址），但「这条连接是不是
     * 本次候选造成的」是另一件事。缺口在于：三条件成立时没有任何冲突检查。
     *
     * <h3>netId 的定位（明确写死，防止再被当成万能否决）</h3>
     * <ul>
     *   <li><b>是证据，不是必要条件</b>：缺失（{@code <0}）或为 {@code 0} 时
     *       **一律不否决**（历史事故：正确密码因取不到 netId 被整段跳过成功判断）。</li>
     *   <li><b>只有两侧都拿到有效非零 netId 且不一致</b>时，才认定为**证据冲突**
     *       → 返回 {@link CrackEngine.Verdict#UNKNOWN}，理由码 {@code netid-conflict}
     *       （即决策里的 UNKNOWN_MISMATCH 语义：非成功、也**不等于**认证失败）。</li>
     * </ul>
     *
     * <p>纯逻辑，无 Android 依赖。
     */
    public static final class Assessment {
        /** 三条件不成立 → {@code null}（不是成功帧）；冲突 → UNKNOWN；否则 SUCCESS。 */
        public final CrackEngine.Verdict verdict;
        /** 证据标签（进排障记录）。 */
        public final String evidence;
        /** 机器可读理由：{@code ok} / {@code netid-conflict}。 */
        public final String reason;

        Assessment(CrackEngine.Verdict v, String e, String r) {
            this.verdict = v; this.evidence = e; this.reason = r;
        }
    }

    /**
     * @param targetSsid   目标 SSID
     * @param statusSsid   状态帧里的 SSID
     * @param state        状态帧里的 supplicant 状态
     * @param ip           状态帧里的 IP
     * @param statusNetId  同一行的 netId（{@code <0} = 取不到）
     * @param targetNetId  本次命令建出的配置 netId（{@code <0} = 取不到）
     */
    @Deprecated
    public static Assessment assess(String targetSsid, String statusSsid, String state,
                                    String ip, long statusNetId, long targetNetId) {
        /*
         * ⚠️ **生产路径禁止调用本重载**（2026-11-21 判定链专项审计加固）。
         *
         * 它把 gateConfirmedClean 硬编码为 true —— 等于**假设**「前置闸门已确认
         * 目标旧配置被删净」。这个假设在离线契约测试里成立，但在生产里必须由
         * CrackEngine 用实测结果显式传入，否则 P0-1 的 fail-closed 保护会被绕过：
         *   · list-networks 返回错误文本却当成「已删净」
         *   · 旧配置残留 + 系统回连 → SSID+COMPLETED+IP 全真 → 假成功
         *
         * 生产调用方请用 7 参重载并传真实闸门结论。
         * 门禁：VerdictOverloadGuardTest 静态钉住「CrackEngine 不得调用 6 参版本」。
         *
         * 语义与 1.9.60 **逐字一致**（netId 缺失不否决）：
         * 实测慢 AP 上 netId 在命令后 242~324ms 才出现，早取必为 -1，
         * 硬要求相等会让正确密码整段 12 秒超时（历史事故）。
         * 本重载保留给「确实没有闸门信息」的离线场景与既有契约测试。
         */
        return assess(targetSsid, statusSsid, state, ip, statusNetId, targetNetId, true);
    }

    /**
     * 完整评估（P0-1，2026-11-21）。
     *
     * <h3>gateConfirmedClean 决定 netId 缺失能否放行</h3>
     * <ul>
     *   <li><b>true</b>：命令前已实测确认「目标的旧配置不在已保存列表里」。
     *       此时命令后出现的「目标 + COMPLETED + 有效 IP」在结构上只可能来自
     *       本条候选新建的配置 —— netId 取不到（时序问题）**不否决**，
     *       否则慢 AP 上正确密码会被整段漏判（历史事故：12 秒全超时）。</li>
     *   <li><b>false</b>：无法确认前置状态（list-networks 是错误文本 / 超时 /
     *       不可解析）。此时只剩 status 一侧的证据，旧配置残留与旧连接回连
     *       都能满足「SSID + COMPLETED + IP」—— 必须要求 netId 两侧都有效且相等，
     *       否则报 UNKNOWN（既不当成功，也不当失败）。</li>
     * </ul>
     *
     * <p><b>安全方向</b>：这个参数只会让「证据不足」的场景更严格，
     * 不会把原本的失败升级成成功。
     */
    public static Assessment assess(String targetSsid, String statusSsid, String state,
                                    String ip, long statusNetId, long targetNetId,
                                    boolean gateConfirmedClean) {
        if (!isSuccess(targetSsid, statusSsid, state, ip)) {
            return new Assessment(null, "", "not-a-success-frame");
        }
        boolean bothValid = statusNetId > 0 && targetNetId > 0;
        if (bothValid && statusNetId != targetNetId) {
            // 两侧都是有效值却不一致 → 归属存疑（可能是旧配置/同名网络的回连）。
            // 不判成功（可能误报），也不判失败（可能是慢一步的真实命中）。
            return new Assessment(CrackEngine.Verdict.UNKNOWN,
                    "supplicant-netid-conflict", "netid-conflict");
        }
        if (!gateConfirmedClean && !bothValid) {
            // 前置闸门没确认过「旧配置已删净」，且拿不到可比的 netId：
            // 「目标 + 握手完成 + 有 IP」完全可能是旧配置 / 旧连接 / 缓存凭据。
            // 归 UNKNOWN —— 不写命中历史、不触发成功副作用，但也不排除候选。
            return new Assessment(CrackEngine.Verdict.UNKNOWN,
                    "supplicant-unattributed", "netid-unverified");
        }
        return new Assessment(CrackEngine.Verdict.SUCCESS,
                bothValid ? "supplicant+netId" : "supplicant", "ok");
    }
}

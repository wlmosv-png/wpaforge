package io.github.wlmosv_png.wpaforge.cap;

import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.Evidence;

/**
 * 无 Root 在线验证的**结论映射**：系统回调 → 证据主张（纯逻辑）。
 *
 * <h3>为什么单独抽出来</h3>
 * 这条路真正碰设备的代码（{@code ConnectivityManager.requestNetwork}）在桌面 JVM
 * 上跑不起来，所以「回调来了该怎么判」必须能离线穷举。这个项目历史上最贵的 bug
 * 都是**解释**错了：把「问不到」当「没有」、把「没连上」当「密码错」。
 * 所以映射规则收在这里，由 {@code RootlessSpecifierTest} 逐格钉死。
 *
 * <h3>映射表（每一格都有理由）</h3>
 * <table>
 *   <tr><th>系统回调</th><th>主张</th><th>归属</th><th>结果等级</th></tr>
 *   <tr><td>{@code onAvailable}</td><td>CONNECTED</td><td>AT3</td><td>SUCCESS / DIRECT</td></tr>
 *   <tr><td>{@code onUnavailable}</td><td>NOT_CONNECTED</td><td>AT3</td><td>TERMINATED_UNKNOWN / NONE</td></tr>
 *   <tr><td>用户拒绝确认</td><td>（不产证据）</td><td>—</td><td>NOT_EXECUTED</td></tr>
 *   <tr><td>系统拒绝请求</td><td>（不产证据）</td><td>—</td><td>NOT_EXECUTED</td></tr>
 *   <tr><td>无答复</td><td>（不产证据）</td><td>—</td><td>NOT_EXECUTED</td></tr>
 * </table>
 *
 * <p><b>关键一条</b>：{@code onUnavailable} 永远不给 {@code WRONG_KEY}。
 * 系统只说「没能建立连接」，认证失败、超时、AP 拒绝全混在一起；
 * 把它当「口令被拒」就会升到 AUTH_FAIL/DIRECT，进而写进已试集合 ——
 * 以后有 root 时会跳过一条**根本没被证明错误**的口令。
 *
 * <p>纯逻辑、无 Android 依赖。
 */
public final class SpecifierVerdict {

    /** 结论动作：调用方按这个分支走。 */
    public static final int ACT_SUCCESS = 1;        // 走 judge(CONNECTED/AT3)
    public static final int ACT_NOT_CONNECTED = 2;  // 走 judge(NOT_CONNECTED/AT3)
    public static final int ACT_NOT_EXECUTED = 3;   // 用户拒绝 / 系统拒绝 / 无答复
    public static final int ACT_STALE_CONFIG = 4;   // 目标已被系统保存或正连着

    /** 映射结果。 */
    public static final class Decision {
        public final int action;
        /** 机器可读理由码（写日志与统计）。 */
        public final String code;
        public final Evidence.Source source;
        public final Evidence.Claim claim;
        public final Evidence.Attribution attribution;
        public final boolean netIdKnown;

        private Decision(int action, String code, Evidence.Source source,
                         Evidence.Claim claim, Evidence.Attribution attribution,
                         boolean netIdKnown) {
            this.action = action;
            this.code = code;
            this.source = source;
            this.claim = claim;
            this.attribution = attribution;
            this.netIdKnown = netIdKnown;
        }

        public boolean isSuccess() {
            return action == ACT_SUCCESS;
        }

        public boolean isNotConnected() {
            return action == ACT_NOT_CONNECTED;
        }

        public boolean isNotExecuted() {
            return action == ACT_NOT_EXECUTED;
        }

        public boolean isStaleConfig() {
            return action == ACT_STALE_CONFIG;
        }

        public String toString() {
            return action + ":" + code;
        }
    }

    private SpecifierVerdict() {
    }

    /**
     * 把一次 {@code SpecifierChannel.Outcome} 映射成结论动作。
     *
     * @param connected    系统连上了
     * @param unavailable  系统说「未能建立连接」
     * @param userRejected 用户没确认
     * @param preBlocked   系统拒绝了这个请求（权限/参数）
     * @param timedOut     我们自己的超时，系统没答复
     */
    public static Decision decide(boolean connected, boolean unavailable,
                                  boolean userRejected, boolean preBlocked, boolean timedOut) {
        // 顺序即优先级：先认「连上」，再认「用户拒绝」（这不是网络结论），
        // 再认「系统拒绝」，最后才是「没能建立连接」。
        if (connected) {
            return new Decision(ACT_SUCCESS, "available",
                    Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                    Evidence.Attribution.AT3, true);
        }
        if (userRejected) {
            return new Decision(ACT_NOT_EXECUTED, "user-rejected",
                    null, null, Evidence.Attribution.NONE, false);
        }
        if (preBlocked) {
            return new Decision(ACT_NOT_EXECUTED, "platform-refused",
                    null, null, Evidence.Attribution.NONE, false);
        }
        if (unavailable) {
            // NOT_CONNECTED + AT3：能归到本次请求，但**不主张口令被拒**。
            return new Decision(ACT_NOT_CONNECTED, "unavailable",
                    Evidence.Source.FAILOVER, Evidence.Claim.NOT_CONNECTED,
                    Evidence.Attribution.AT3, false);
        }
        if (timedOut) {
            return new Decision(ACT_NOT_EXECUTED, "no-answer",
                    null, null, Evidence.Attribution.NONE, false);
        }
        return new Decision(ACT_NOT_EXECUTED, "unknown-outcome",
                null, null, Evidence.Attribution.NONE, false);
    }

    /** 目标已被系统保存 / 正连着它时，本条作废（公开 API 删不掉旧配置）。 */
    public static Decision staleConfig() {
        return new Decision(ACT_STALE_CONFIG, "stale-config",
                null, null, Evidence.Attribution.NONE, false);
    }

    /** 这个决策要不要经过 Adjudicator（只有前两种要）。 */
    public static boolean needsAdjudication(Decision d) {
        return d != null && (d.action == ACT_SUCCESS || d.action == ACT_NOT_CONNECTED);
    }

    /** 对应要传的闸门（仅 ACT_STALE_CONFIG / ACT_NOT_EXECUTED 用得上）。 */
    public static AttemptState.Gate gateOf(Decision d) {
        if (d == null) return AttemptState.Gate.OK;
        if (d.action == ACT_STALE_CONFIG) return AttemptState.Gate.STALE_CONFIG;
        if (d.action == ACT_NOT_EXECUTED) return AttemptState.Gate.NOT_CAPABLE;
        return AttemptState.Gate.OK;
    }
}

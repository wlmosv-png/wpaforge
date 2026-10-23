package io.github.wlmosv_png.wpaforge.audit;

/**
 * 失败加速判据：系统是否已经回连到别的网络。
 *
 * <h3>为什么需要它</h3>
 * 判定「密码对不对」最直接的证据是 wpa_supplicant 的
 * {@code CTRL-EVENT-ASSOC-REJECT} / {@code 4-Way Handshake failed}。
 * 但**本机（ColorOS 15）根本不输出这些日志**（实测 {@code logcat -d -s wpa_supplicant}
 * 为 0 行，状态机日志也只匹配到无关的 ContextImpl 噪音），所以错误密码只能干等
 * 10 秒超时 —— 这就是「每条约 10 秒」的来源。
 *
 * <p>好在有一条本地信号既可靠又免费：**系统不会同时连两个网络**。
 * 它去连别的网络，就说明目标这次尝试已经结束、且没连上。
 * {@code cmd wifi status} 本来就每轮都在读（实测 18ms），零额外开销。
 *
 * <h3>为什么单独一个类</h3>
 * 它决定「一条错误密码什么时候被判负」，判断错在两边的代价都高：
 * 早了 → 正确密码被当失败跳过；晚了 → 等于没加速。所以它必须是纯逻辑、
 * 能被测试逐条钉死（见 {@code FailoverTest}）。放引擎里会因为引擎依赖 Android
 * 而在桌面 JVM 上加载不起来，等于没法测。
 *
 * <p>纯逻辑，无 Android 依赖。
 */
public final class FailoverRule {

    /**
     * 最短等待：系统从「目标失败」到「回连别的网络」要几秒，
     * 太短会把切换过程中的瞬时状态误读成回连。
     */
    public static final long MIN_WAIT_MS = 2500L;

    private FailoverRule() {}

    /**
     * **归属未知**的旧入口（保留兼容）。
     *
     * <p>它不看「本条候选是否真的被尝试过」，也不看「本轮是否已有目标的成功证据」——
     * 生产判定**不得**再调用它，请用七参版本。
     * 保留只为兼容历史调用点与既有测试；用 {@code looksLikeFailoverRaw} 的语义。
     */
    public static boolean looksLikeFailover(String targetSsid, String statusSsid,
                                            String supplicantState, boolean leftBaseline,
                                            long sinceCommandMs) {
        return looksLikeFailover(targetSsid, statusSsid, supplicantState, leftBaseline,
                sinceCommandMs, true, false);
    }

    /**
     * R3 起使用的完整入口：把**归属前置条件**显式作为输入。
     *
     * <h3>为什么必须加这两个参数（2026-10-24 审计 S2-1/S2-2/S2-3）</h3>
     * <ol>
     *   <li><b>{@code attemptExercised}</b>：本判据的结论是「目标这次没连上」——
     *       只有先证明「这次真的试过」，这句话才成立。否则「系统自己回连了别的网络」
     *       这种与本次候选无关的事件，会把一条**从未被执行的候选**判成认证失败。</li>
     *   <li><b>{@code targetNegotiatedThisRound}</b>：同一轮里若已出现「目标的握手完成」，
     *       成功证据优先 —— 不得让失败判据抢在它前面结案（证据冲突时不得猜失败）。</li>
     * </ol>
     *
     * @param leftBaseline  观测到过「至少一轮不是『连着别的网络且 COMPLETED』」。
     *                      <b>注意</b>：它不是「离开了命令前那条连接」的证明 ——
     *                      手机在命令时本就未连接（本工具常态）时它第一轮即为真，
     *                      所以它**必须**与 {@code attemptExercised} 一起用。
     * @return true = 判定为「目标这次没连上」
     */
    public static boolean looksLikeFailover(String targetSsid, String statusSsid,
                                            String supplicantState, boolean leftBaseline,
                                            long sinceCommandMs, boolean attemptExercised,
                                            boolean targetNegotiatedThisRound) {
        if (!attemptExercised) return false;                  // 没证明「试过」，就不能说「没连上」
        if (targetNegotiatedThisRound) return false;          // 已有成功证据，交给成功判据
        if (!leftBaseline) return false;                      // 还没离开原连接，可能只是没切换完
        if (sinceCommandMs < MIN_WAIT_MS) return false;        // 给握手留出时间
        if (targetSsid == null || statusSsid == null || statusSsid.isEmpty()) return false;
        if (targetSsid.equals(statusSsid)) return false;       // 还是目标，当然不算回连
        return supplicantState != null && "COMPLETED".equalsIgnoreCase(supplicantState);
    }
}

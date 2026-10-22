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
     * @param targetSsid       目标 SSID
     * @param statusSsid       本轮 status 读到的当前 SSID
     * @param supplicantState  本轮 status 读到的 supplicant 状态
     * @param leftBaseline     是否已观察到「系统离开了命令前那条连接」
     * @param sinceCommandMs   距命令发出已过多少毫秒
     * @return true = 判定为「目标这次没连上」
     */
    public static boolean looksLikeFailover(String targetSsid, String statusSsid,
                                            String supplicantState, boolean leftBaseline,
                                            long sinceCommandMs) {
        if (!leftBaseline) return false;                      // 还没离开原连接，可能只是没切换完
        if (sinceCommandMs < MIN_WAIT_MS) return false;        // 给握手留出时间
        if (targetSsid == null || statusSsid == null || statusSsid.isEmpty()) return false;
        if (targetSsid.equals(statusSsid)) return false;       // 还是目标，当然不算回连
        return supplicantState != null && "COMPLETED".equalsIgnoreCase(supplicantState);
    }
}

package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;

/**
 * **IWifiManager 直调能力的真实状态**（2026-11-20 第三阶段）。
 *
 * <h3>为什么必须有这个类</h3>
 * 改造前日志在**注入时无条件**打印：
 * <pre>
 *   已启用 IWifiManager 直调（不走 shell，避免管道超时）
 * </pre>
 * 但那一刻只是把一个匿名类挂进引擎，**一次真实调用都没发生过**。
 * 于是用户看到「已启用」，而实际 {@code getConnectionInfo} 可能返回
 * {@code no-service} / {@code no-method} / {@code invoke-failed} ——
 * 界面在说假话，排查时最容易把人带偏。
 *
 * <h3>四态（任务书第五节明确要求）</h3>
 * <ul>
 *   <li>{@link State#AVAILABLE} —— 真的调用成功并拿到有效返回值；</li>
 *   <li>{@link State#INITIALIZED_ONLY} —— 注入完成，但**尚未实测**（不能算可用）；</li>
 *   <li>{@link State#CALL_FAILED} —— 实测调用失败（附原因码）；</li>
 *   <li>{@link State#UNSUPPORTED} —— 本机没有可用接口（无 service / 无方法）。</li>
 * </ul>
 *
 * <p><b>只有 AVAILABLE 才允许在日志里说「已启用」</b>，
 * 其余三态必须如实报出原因，且**不得参与 DIRECT 判定**
 * （直调只负责「读/删」，判定仍走 {@code cmd wifi status} 四条件）。
 */
public final class WifiDirectCapability {

    /** 能力状态。 */
    public enum State {
        /** 实测成功：拿到有效返回值。 */
        AVAILABLE("可用", "available"),
        /** 注入完成但未实测 —— **不等于可用**。 */
        INITIALIZED_ONLY("已注入·未实测", "initialized-only"),
        /** 实测调用失败。 */
        CALL_FAILED("调用失败", "call-failed"),
        /** 本机不支持（无 service / 无对应方法）。 */
        UNSUPPORTED("不支持", "unsupported");

        public final String zh;
        public final String key;

        State(String zh, String key) {
            this.zh = zh;
            this.key = key;
        }

        /** 是否允许对外声称「已启用」。 */
        public boolean claimable() {
            return this == AVAILABLE;
        }

        public String label(boolean zh) {
            return zh ? this.zh : this.key;
        }
    }

    /** 一次探测的完整结论。 */
    public static final class Probe {
        public final State state;
        /** 实测时真正调到的方法名（空 = 没调到）。 */
        public final String method;
        /** 调用耗时（毫秒）。 */
        public final long elapsedMs;
        /** 失败原因码（{@code no-service} / {@code no-method} / {@code invoke-failed} …）。 */
        public final String reason;
        /** 异常类名（无异常为空）。 */
        public final String exceptionClass;
        /** 返回值的可读摘要（不含敏感信息）。 */
        public final String valueBrief;

        Probe(State state, String method, long elapsedMs, String reason,
              String exceptionClass, String valueBrief) {
            this.state = state == null ? State.UNSUPPORTED : state;
            this.method = method == null ? "" : method;
            this.elapsedMs = elapsedMs;
            this.reason = reason == null ? "" : reason;
            this.exceptionClass = exceptionClass == null ? "" : exceptionClass;
            this.valueBrief = valueBrief == null ? "" : valueBrief;
        }

        /** 只有 AVAILABLE 才算「真的能用」。 */
        public boolean usable() {
            return state.claimable();
        }

        /**
         * 一行如实描述（**不含口令与 SSID 明文**）。
         *
         * <p>格式固定，便于 grep：
         * <pre>
         *   IWifiManager = 可用 (getConnectionInfo, 18ms, value=WifiInfo)
         *   IWifiManager = 调用失败 (getConnectionInfo, invoke-failed,
         *                            SecurityException)
         * </pre>
         */
        public String describe(boolean zh) {
            StringBuilder sb = new StringBuilder("IWifiManager = ");
            sb.append(state.label(zh));
            sb.append(" (");
            boolean first = true;
            if (!method.isEmpty()) {
                sb.append(method);
                first = false;
            }
            if (elapsedMs > 0) {
                if (!first) sb.append(", ");
                sb.append(elapsedMs).append("ms");
                first = false;
            }
            if (!reason.isEmpty()) {
                if (!first) sb.append(", ");
                sb.append(reason);
                first = false;
            }
            if (!exceptionClass.isEmpty()) {
                if (!first) sb.append(", ");
                sb.append(exceptionClass);
                first = false;
            }
            if (!valueBrief.isEmpty()) {
                if (!first) sb.append(", ");
                sb.append("value=").append(valueBrief);
            }
            sb.append(")");
            return sb.toString();
        }
    }

    private WifiDirectCapability() {
    }

    /**
     * **实测**直调能力：真的调一次 {@code getConnectionInfo} 并检查返回值。
     *
     * <p>这是只读操作（读当前连接信息），不改变任何网络状态。
     *
     * @param sdk {@code Build.VERSION.SDK_INT}
     */
    public static Probe probeConnectionInfo(int sdk) {
        long t0 = System.currentTimeMillis();
        WifiServiceBridge.Outcome<Object> o = WifiServiceBridge.getConnectionInfo(sdk);
        long ms = System.currentTimeMillis() - t0;
        if (o.ok && o.value != null) {
            // 拿到非空 WifiInfo → 真的可用
            String brief = o.value.getClass().getSimpleName();
            return new Probe(State.AVAILABLE, "getConnectionInfo", ms, "", "", brief);
        }
        String reason = o.reason == null ? "unknown" : o.reason;
        String ex = o.errorClass == null ? "" : o.errorClass;
        // no-service / no-method → 本机不支持；其余 → 调用失败
        State st = ("no-service".equals(reason) || "no-method".equals(reason))
                ? State.UNSUPPORTED : State.CALL_FAILED;
        return new Probe(st, "getConnectionInfo", ms, reason, ex, "");
    }

    /**
     * **实测**列已保存网络能力（只读）。
     *
     * <p>它比 {@code getConnectionInfo} 更强：能拿到配置列表说明
     * 服务端真的把数据返回来了。
     */
    public static Probe probeListNetworks(int sdk) {
        long t0 = System.currentTimeMillis();
        WifiServiceBridge.Outcome<java.util.List<Object>> o =
                WifiServiceBridge.listConfiguredNetworks(sdk);
        long ms = System.currentTimeMillis() - t0;
        if (o.ok && o.value != null) {
            String brief = "List[" + o.value.size() + "]";
            return new Probe(State.AVAILABLE, "getConfiguredNetworks", ms, "", "", brief);
        }
        String reason = o.reason == null ? "unknown" : o.reason;
        String ex = o.errorClass == null ? "" : o.errorClass;
        State st = ("no-service".equals(reason) || "no-method".equals(reason))
                ? State.UNSUPPORTED : State.CALL_FAILED;
        return new Probe(st, "getConfiguredNetworks", ms, reason, ex, "");
    }

    /**
     * 只做「注入」时的状态：**明确不是可用**。
     *
     * <p>注入之后、实测之前，唯一诚实的说法是
     * {@link State#INITIALIZED_ONLY} —— 不能提前说「已启用」。
     */
    public static Probe initializedOnly() {
        return new Probe(State.INITIALIZED_ONLY, "", 0L, "not-yet-probed", "", "");
    }
}

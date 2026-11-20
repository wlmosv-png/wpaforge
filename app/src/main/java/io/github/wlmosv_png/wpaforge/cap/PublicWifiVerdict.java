package io.github.wlmosv_png.wpaforge.cap;

/**
 * **Android 公共 Wi-Fi API 验证的七态判定模型**（纯逻辑，无 Android 依赖）。
 *
 * <h3>为什么必须单独一个模型</h3>
 * 公开 API（{@code WifiNetworkSpecifier} + {@code ConnectivityManager.requestNetwork}）
 * 的回调语义**不足以**直接回答「这条口令对不对」：
 * <pre>
 *   onAvailable()     → 系统说「有一条到目标的连接可用了」
 *   onUnavailable()   → 系统说「没能在超时内满足请求」
 * </pre>
 * 这两条**都不包含**「是不是这条 PSK 完成的握手」。历史上把这个混淆过一次，
 * 结果是把「系统用旧配置连上」判成密码正确（假阳性）。
 *
 * <h3>七态定义（任务书第三节）</h3>
 * <table>
 *   <tr><th>状态<\/th><th>含义<\/th><th>能否进 DIRECT<\/th><\/tr>
 *   <tr><td>{@link State#REQUEST_ACCEPTED}<\/td>
 *       <td>系统接受了请求（requestNetwork 没抛异常）<\/td><td>否<\/td><\/tr>
 *   <tr><td>{@link State#USER_APPROVED}<\/td>
 *       <td>用户点了确认框（无法直接观测，只能由「随后出现可用网络」间接推断）<\/td>
 *       <td>否<\/td><\/tr>
 *   <tr><td>{@link State#NETWORK_AVAILABLE}<\/td>
 *       <td>onAvailable 触发<\/td><td>否<\/td><\/tr>
 *   <tr><td>{@link State#NETWORK_UNAVAILABLE}<\/td>
 *       <td>onUnavailable 触发<\/td><td>否<\/td><\/tr>
 *   <tr><td>{@link State#AUTHENTICATION_CONFIRMED}<\/td>
 *       <td>本次请求新建的连接 + 目标 SSID + 有效 IP，且排除旧网络<\/td>
 *       <td>**是**（唯一允许）<\/td><\/tr>
 *   <tr><td>{@link State#AUTHENTICATION_REJECTED}<\/td>
 *       <td>能证明「本次请求的这条 PSK 被拒」<\/td><td>否（且现实不可达）<\/td><\/tr>
 *   <tr><td>{@link State#UNKNOWN}<\/td>
 *       <td>证据不足<\/td><td>否<\/td><\/tr>
 * <\/table>
 *
 * <h3>为什么 AUTHENTICATION_REJECTED 现实不可达</h3>
 * 公开 API 的 {@code onUnavailable} 把「口令错」「AP 不存在」「用户拒绝」
 * 「超时」「信号太差」全混在一起，AOSP 没有向应用暴露区分依据。
 * 所以**永远不能**用它判「这条口令错了」——那会写进已试集合，
 * 以后有 root 时跳过一条根本没被证明错误的口令。
 *
 * <p>纯逻辑，可离线穷举。
 */
public final class PublicWifiVerdict {

    /** 七态。 */
    public enum State {
        /** 请求已提交（系统未拒绝）。 */
        REQUEST_ACCEPTED("请求已接受", "request-accepted"),
        /** 用户已确认（间接推断）。 */
        USER_APPROVED("用户已确认", "user-approved"),
        /** 网络可用（onAvailable）。 */
        NETWORK_AVAILABLE("网络可用", "network-available"),
        /** 网络不可用（onUnavailable）。 */
        NETWORK_UNAVAILABLE("网络不可用", "network-unavailable"),
        /** 认证已确认（唯一可进 DIRECT 的状态）。 */
        AUTHENTICATION_CONFIRMED("认证已确认", "auth-confirmed"),
        /** 认证被拒（公开 API 现实不可达）。 */
        AUTHENTICATION_REJECTED("认证被拒", "auth-rejected"),
        /** 证据不足。 */
        UNKNOWN("未确定", "unknown");

        public final String zh;
        public final String key;

        State(String zh, String key) {
            this.zh = zh;
            this.key = key;
        }

        public String label(boolean zh) {
            return zh ? this.zh : this.key;
        }

        /**
         * 是否允许进入 DIRECT 判定。
         *
         * <p>**只有 AUTHENTICATION_CONFIRMED**。其余全部否 ——
         * 包括 NETWORK_AVAILABLE（已连上但不等于本次口令完成认证）。
         */
        public boolean allowsDirect() {
            return this == AUTHENTICATION_CONFIRMED;
        }

        /** 是否是终态（不会再变）。 */
        public boolean terminal() {
            return this == AUTHENTICATION_CONFIRMED
                    || this == AUTHENTICATION_REJECTED
                    || this == NETWORK_UNAVAILABLE
                    || this == UNKNOWN;
        }
    }

    /**
     * 一次请求观测到的**原始事实**（由调用方从真实回调里填）。
     *
     * <p>这些都是「看到什么」，不含任何推断。
     */
    public static final class Facts {
        /** requestNetwork 是否没抛异常。 */
        public boolean requestAccepted;
        /** 是否收到 onAvailable。 */
        public boolean sawAvailable;
        /** 是否收到 onUnavailable。 */
        public boolean sawUnavailable;
        /** 是否收到 onLost（available 之后）。 */
        public boolean sawLostAfterAvailable;
        /** 是否在 available 之前就 lost。 */
        public boolean sawLostBeforeAvailable;
        /** 返回的 Network 是否在请求前就已存在（旧连接）。 */
        public boolean networkPreexisting;
        /** 从 NetworkCapabilities.getTransportInfo() 拿到的 SSID 是否等于目标。 */
        public boolean transportSsidMatches;
        /** 是否真的拿到了 WifiInfo（transportInfo 非空）。 */
        public boolean hasTransportInfo;
        /** LinkProperties 里是否有有效 IPv4/IPv6 地址。 */
        public boolean hasValidAddress;
        /** 请求前是否已经连着目标（旧连接污染）。 */
        public boolean preConnectedToTarget;
        /** 是否请求超时（我们自己算的）。 */
        public boolean timedOut;
        /** 是否缺少必要权限（SecurityException）。 */
        public boolean permissionDenied;
        /** 系统是否拒绝了这个网络描述（IllegalArgumentException）。 */
        public boolean specifierRejected;
        /** 连接建立后是否在稳定窗口内掉线。 */
        public boolean droppedWithinStability;
    }

    private PublicWifiVerdict() {
    }

    /**
     * 由原始事实推出状态。
     *
     * <p>顺序即优先级，每条都有理由：
     * <ol>
     *   <li>权限/描述被拒 → UNKNOWN（**不是**认证失败，也不是没连上）<\/li>
     *   <li>请求前已连着目标 → UNKNOWN（旧连接污染，无法归因）<\/li>
     *   <li>返回的网络是旧的 → UNKNOWN（同上）<\/li>
     *   <li>稳定窗口内掉线 → NETWORK_UNAVAILABLE（不足以证明口令可用）<\/li>
     *   <li>available + SSID 对 + 有 IP → **AUTHENTICATION_CONFIRMED**<\/li>
     *   <li>available 但证据不全 → NETWORK_AVAILABLE（已连上，未证实）<\/li>
     *   <li>available 前就 lost → NETWORK_UNAVAILABLE<\/li>
     *   <li>onUnavailable → NETWORK_UNAVAILABLE（**永不** REJECTED）<\/li>
     *   <li>超时 → UNKNOWN（不是失败，是没答复）<\/li>
     *   <li>其余 → UNKNOWN<\/li>
     * <\/ol>
     */
    public static State of(Facts f) {
        if (f == null) return State.UNKNOWN;

        // ① 权限或网络描述被系统拒绝 —— 这既不是「连上了」也不是「口令错」
        if (f.permissionDenied || f.specifierRejected) return State.UNKNOWN;

        // ② 请求前就连着目标：系统可能直接用旧连接满足请求，本次口令没被验证
        if (f.preConnectedToTarget) return State.UNKNOWN;

        // ③ 返回的 Network 在请求前就存在：同上，旧连接污染
        if (f.networkPreexisting) return State.UNKNOWN;

        // ④ 连上又立刻掉：不足以证明口令可用
        if (f.droppedWithinStability) return State.NETWORK_UNAVAILABLE;

        // ⑤ 唯一允许 AUTHENTICATION_CONFIRMED 的分支：
        //    新连接 + 目标 SSID + 有效地址，三个条件必须同时成立
        if (f.sawAvailable && f.hasTransportInfo && f.transportSsidMatches && f.hasValidAddress) {
            return State.AUTHENTICATION_CONFIRMED;
        }

        // ⑥ 连上了但证据不全 —— 是 NETWORK_AVAILABLE，不是成功
        if (f.sawAvailable) return State.NETWORK_AVAILABLE;

        // ⑦ available 之前就 lost = 没连上
        if (f.sawLostBeforeAvailable) return State.NETWORK_UNAVAILABLE;

        // ⑧ onUnavailable：口令错 / AP 不在 / 用户拒绝 / 信号差 全混在一起。
        //    **绝不给 AUTHENTICATION_REJECTED** —— 那会污染已试集合。
        if (f.sawUnavailable) return State.NETWORK_UNAVAILABLE;

        // ⑨ 超时：系统没给答复，不是失败
        if (f.timedOut) return State.UNKNOWN;

        // ⑩ 只提交了请求，什么都没回来
        if (f.requestAccepted) return State.REQUEST_ACCEPTED;

        return State.UNKNOWN;
    }

    /**
     * 该状态对应的证据等级。
     *
     * <p>只有 AUTHENTICATION_CONFIRMED 给 DIRECT；其余一律 NONE
     * （**不写 TriedSet、不推游标**）。
     */
    public static String gradeOf(State s) {
        return s != null && s.allowsDirect() ? "DIRECT" : "NONE";
    }

    /**
     * 该状态是否允许写 TriedSet（「这条口令已证实是错的」）。
     *
     * <p>公开 API 下**恒为 false** —— 它证明不了任何一条口令是错的。
     */
    public static boolean allowsTriedSet(State s) {
        return false;
    }

    /**
     * 人话解释（给界面与日志用，不含口令）。
     */
    public static String explain(State s, boolean zh) {
        switch (s) {
            case AUTHENTICATION_CONFIRMED:
                return zh ? "本次请求新建的连接：目标 SSID 一致且有有效地址，可归因于本次口令"
                        : "connection created by this request: target SSID + valid address";
            case NETWORK_AVAILABLE:
                return zh ? "系统报告网络可用，但证据不足以证明是本条口令完成的认证"
                        : "network reported available, but cannot attribute the handshake";
            case NETWORK_UNAVAILABLE:
                return zh ? "系统未能建立连接（口令错/AP 不在/用户拒绝/超时 不可区分，"
                        + "不等于口令错误）"
                        : "system could not establish the connection "
                        + "(wrong key / AP absent / user declined / timeout are indistinguishable)";
            case AUTHENTICATION_REJECTED:
                return zh ? "能证明本条口令被拒（公开 API 现实不可达）"
                        : "this passphrase proven rejected (unreachable via public API)";
            case REQUEST_ACCEPTED:
                return zh ? "请求已提交，等待系统答复" : "request submitted, awaiting answer";
            case USER_APPROVED:
                return zh ? "用户已确认（由后续可用网络间接推断）"
                        : "user approved (inferred from a later available network)";
            case UNKNOWN:
            default:
                return zh ? "证据不足，保持未确定（不写已试集合）"
                        : "insufficient evidence; stays unknown (no tried-set write)";
        }
    }
}

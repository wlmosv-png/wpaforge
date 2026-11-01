package io.github.wlmosv_png.wpaforge.audit;

/**
 * 证据强度等级 —— 判定结论的**可信度**，与业务结论（{@link CrackEngine.Verdict}）正交。
 *
 * <h3>为什么需要它（2026-11-01 审计）</h3>
 * 旧模型只有 {@code AUTH_FAIL} 一个失败值，把两类性质完全不同的东西压在一起：
 * <ul>
 *   <li>「AP 明确拒绝」——wpa_supplicant 打出 {@code TEMP-DISABLED reason=WRONG_KEY}，
 *       带 SSID 归属、带失败前导行。这是**协议层的直接否认**。</li>
 *   <li>「系统广播认证失败」——framework 级信号，但归属只能靠「当时的连接信息」猜。</li>
 *   <li>「M1 被重发」——只说明「握手没进展」，丢包、AP 慢、上次残留都能造成。</li>
 *   <li>「系统回连别的网」——只说明「这次尝试结束了」，不能证明密码错。</li>
 * </ul>
 * 四者都被写成同一个 {@code AUTH_FAIL}，于是下游只能一律当成「已证实失败」永久记账。
 * 一次误判 = 正确密码永久被跳过。
 *
 * <h3>模型</h3>
 * <pre>
 *   DIRECT     有归属的直接否认       → 可作为永久结论
 *   INFERRED   间接推断（可被反驳）   → 只在本轮内存有效，不得持久化
 *   NONE       无结论                 → 既非成功也非失败，下次重试
 * </pre>
 *
 * <p><b>不变式</b>：
 * <ol>
 *   <li>{@link #persistable} 为真**当且仅当**等级是 {@link #DIRECT}。</li>
 *   <li>{@link CrackEngine.Verdict#SUCCESS} 永远 {@link #DIRECT}（握手完成是同源四条件）。</li>
 *   <li>非 success 且非 AUTH_FAIL 的结论，等级一律 {@link #NONE}。</li>
 * </ol>
 *
 * <p>纯逻辑，无 Android 依赖，可离线穷举。
 */
public final class EvidenceGrade {

    /** 证据强度。声明顺序即强度顺序（越早越强）。 */
    public enum Level {
        /** 有归属的直接否认：可以据此把这个候选排除在后续运行之外。 */
        DIRECT,
        /** 间接推断：本轮不再重复，但**不得**写入永久记录。 */
        INFERRED,
        /** 无结论：什么都没证明。 */
        NONE
    }

    private EvidenceGrade() {}

    /**
     * 这个等级**是否允许**写入永久已试记录。
     *
     * <p>唯一为真的等级是 {@link Level#DIRECT}。这条是「正确密码不会被一次误判永久锁死」
     * 的单点保证 —— 所有持久化路径都必须经过它。
     */
    public static boolean persistable(Level g) {
        return g == Level.DIRECT;
    }

    /**
     * 由业务结论推出默认等级（无归属信息时的保守取值）。
     *
     * <p>注意：{@code AUTH_FAIL} 的默认值是 {@link Level#INFERRED} —— **宁可保守**。
     * 只有调用方明确知道「这条失败带归属证据」时，才应显式传 {@link Level#DIRECT}。
     *
     * @param v 业务结论；{@code null} 视为无结论
     */
    public static Level defaultOf(CrackEngine.Verdict v) {
        if (v == null) return Level.NONE;
        switch (v) {
            case SUCCESS:
                // 四条件同源于同一行 WifiInfo，归属由前置闸门保证
                return Level.DIRECT;
            case AUTH_FAIL:
                // 保守默认：可能是 m1retry / failover 这类推断
                return Level.INFERRED;
            case TIMEOUT:
            case UNKNOWN:
            case ERROR:
            case TARGET_GONE:
            case ABORTED:
            default:
                return Level.NONE;
        }
    }

    /**
     * 该等级是否足以构成「这条候选可以被排除」的结论。
     *
     * <p>为真时表示「本轮不必再试」；{@link #persistable} 为真时才表示「以后也不必再试」。
     */
    public static boolean conclusive(Level g) {
        return g == Level.DIRECT || g == Level.INFERRED;
    }
}

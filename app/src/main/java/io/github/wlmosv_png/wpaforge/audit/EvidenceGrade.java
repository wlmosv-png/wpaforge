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
     * 该等级是否构成「这条候选已有结论」（语义描述用）。
     *
     * <p><b>注意：生产代码已不再用它做任何排除判断。</b>
     * D5 系列修复后，「能否排除」被拆成两个更精确的问题：
     * <ul>
     *   <li>{@link #persistable} —— 能否写永久记录</li>
     *   <li>{@link #advancesCursor} —— 能否推进续跑游标</li>
     * </ul>
     * 本方法保留是因为它准确描述了「DIRECT/INFERRED 都算有结论，NONE 不算」，
     * 供测试与未来语义使用；**不要**拿它当作排除依据。
     */
    public static boolean conclusive(Level g) {
        return g == Level.DIRECT || g == Level.INFERRED;
    }

    /**
     * 该等级是否允许**推进续跑游标**（即：这条候选在本轮任务内被永久越过）。
     *
     * <h3>D5 修复：只有 DIRECT 推进</h3>
     * 游标一旦推进，本条候选在本次任务内不再被尝试；进程中断后续跑也从该位置开始
     * —— 这在语义上等价于「已排除」。而 {@link Level#INFERRED} 的定义恰恰是
     * 「证据不足以排除」，两者矛盾。
     *
     * <p>INFERRED 在当前实现里**不参与任何排除逻辑**：
     * 不写磁盘、不推进游标、也不进入任何「本轮已排除」集合
     * （历史上曾有 {@code inferredThisRun}，因与语义冲突已整体删除）。
     * 它现在只用于日志/界面标注「这是推断，不是已证实」。
     */
    public static boolean advancesCursor(Level g) {
        return g == Level.DIRECT;
    }
}

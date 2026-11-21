package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;

/**
 * **用户可理解的结果分类**（2026-11-22 新增）。
 *
 * <h3>为什么需要它</h3>
 * 修复前 UI 只有 10 种 Verdict 标签，但用户在真机上看到的是
 * 「5 条不确定」—— 而底层这 5 条可能是完全不同的情况：
 * <ul>
 *   <li>{@code netid-conflict}：目标已连上、拿到 IP、握手完成，
 *       但 netId 与本次配置不符（多 AP mesh 场景常见）——**可能密码是对的**；</li>
 *   <li>{@code status-unclear}：结束时 status 显示目标但没判定成功；</li>
 *   <li>{@code no-evidence}：等满时限、什么都没观察到；</li>
 *   <li>{@code not-attempted}：命令根本没生效。</li>
 * </ul>
 * 这四种事实天差地别，却共用「不确定」一个词。
 *
 * <h3>本类的边界（重要）</h3>
 * <ul>
 *   <li><b>不改变判定</b>：只把已有的 {@code Verdict + grade + reason}
 *       翻译成用户能懂的类别。判定逻辑一行不动。</li>
 *   <li><b>不改变持久化</b>：写 TriedSet 仍只看
 *       {@code EvidenceGrade.persistable(grade)}，与本类无关。</li>
 *   <li><b>不伪造结论</b>：{@link #SUSPECTED_WRONG} 明确写「尚未证实」，
 *       绝不暗示已证实。</li>
 * </ul>
 *
 * <p>纯逻辑，无 Android 依赖，可离线穷举。
 */
public final class ResultCategory {

    /** 用户可理解的 7 类结果。 */
    public enum Kind {
        /** 密码正确（SUCCESS + DIRECT）。 */
        VERIFIED_OK,
        /** 已证实密码错误（AUTH_FAIL + DIRECT）。 */
        PROVEN_WRONG,
        /** 疑似密码错误（有认证失败证据，但归因不足）。 */
        SUSPECTED_WRONG,
        /** 网络认证失败（网络确实拒绝，但绑不到本条候选）。 */
        NETWORK_FAIL,
        /** 连接未完成（超时 / 中断 / 仍在进行）。 */
        NOT_FINISHED,
        /** 环境异常（通道 / 命令 / 日志采集出问题）。 */
        ENV_ERROR,
        /** 无法判断（证据不足）。 */
        UNDECIDED
    }

    private ResultCategory() {}

    /**
     * 由 {@code Verdict + grade + reason} 推出用户可理解的类别。
     *
     * @param v     业务结论；{@code null} = 还没结论
     * @param g     证据等级；{@code null} = 未知
     * @param reason 机器可读原因码（{@code Attempt.reason}）
     */
    public static Kind of(CrackEngine.Verdict v, EvidenceGrade.Level g, String reason) {
        if (v == null) return Kind.UNDECIDED;
        switch (v) {
            case SUCCESS:
                // 成功必须是 DIRECT（defaultOf(SUCCESS)=DIRECT）。
                return g == EvidenceGrade.Level.DIRECT ? Kind.VERIFIED_OK : Kind.UNDECIDED;

            case AUTH_FAIL:
                // 只有 DIRECT 才叫「已证实」。
                return g == EvidenceGrade.Level.DIRECT ? Kind.PROVEN_WRONG : Kind.SUSPECTED_WRONG;

            case NETWORK_AUTH_FAIL:
                return Kind.NETWORK_FAIL;

            case TIMEOUT:
            case INCOMPLETE:
            case TERMINATED_UNKNOWN:
                return Kind.NOT_FINISHED;

            case ERROR:
            case TARGET_GONE:
                return Kind.ENV_ERROR;

            case ABORTED:
                return Kind.NOT_FINISHED;

            case UNKNOWN:
                // UNKNOWN 有两种本质：
                //   · netid-conflict / status-unclear / on-target-unattributed
                //     = 目标**曾经连上过**，只是归属存疑 → 归「无法判断」
                //     但要靠 reason 让 UI 说出「已连上但归因存疑」
                //   · not-attempted = 命令没生效 → 环境异常
                if (isEnvReason(reason)) return Kind.ENV_ERROR;
                return Kind.UNDECIDED;

            default:
                return Kind.UNDECIDED;
        }
    }

    /** 这个 reason 是否属于「环境/通道问题」而非「证据不足」。 */
    public static boolean isEnvReason(String reason) {
        if (reason == null) return false;
        return reason.startsWith("gate-")
                || "not-attempted".equals(reason)
                || "channel-no-response".equals(reason);
    }

    /**
     * 这条 UNKNOWN 是否意味着「目标其实连上过」。
     *
     * <h3>2026-11-22 修正：netid-conflict 被移出本集合</h3>
     * 修复前它返回 true，UI 因此提示
     * 「目标曾连上并取得 IP，但无法确认是这条口令造成的 —— 请人工复核」。
     *
     * <p>真机证伪（PJX110 / ColorOS 15）：{@code 2.4G-3} 连续 14 次尝试
     * 全部是 {@code AUTHENTICATION_FAILURE / AUTH_FAILURE_WRONG_PSWD}，
     * **一次都没连上过**（{@code connectionResult} 恒为 0），
     * 而 1.9.63 仍把它标成 {@code netid-conflict} 并提示人工复核 ——
     * 那是在让用户去复核一个不存在的命中。
     *
     * <p>{@code netid-conflict} 的真实含义是「观察到了 netId 与本次配置不符」，
     * 它**不蕴含**「连上过」。真正的「连上过」只有
     * {@code status-unclear} 与 {@code on-target-unattributed} 能证明
     * （两者都由「status 显示目标」触发）。
     */
    public static boolean targetMayHaveConnected(String reason) {
        if (reason == null) return false;
        return "status-unclear".equals(reason)
                || "on-target-unattributed".equals(reason);
    }

    /**
     * 这条 UNKNOWN 是否属于「系统记录了失败，但原因不是密钥错误」。
     *
     * <p>真机实例（whoami，2026-11-22）：framework 的
     * {@code mConnectionEvents} 记的是
     * {@code NEW_CONNECTION_ATTEMPT / FAILURE_REASON_UNKNOWN}，
     * 连续 10 次，每次 duration 3.1~10.7 秒。
     *
     * <p>含义：**系统确实试过、也确实没连上**，但没有密钥不符的证据。
     * 这与「什么都没观察到」是两种不同的事实，UI 必须分开说 ——
     * 否则用户会以为是应用没跑，而实际是 AP 没给结论。
     */
    public static boolean systemRecordedFailure(String reason) {
        if (reason == null) return false;
        return "not-connected-terminated".equals(reason)
                || "no-evidence".equals(reason)
                || "wrong-key-active".equals(reason)
                || "wrong-key-state-unknown".equals(reason);
    }

    /** 中文短标签。 */
    public static String label(Kind k, boolean zh) {
        if (k == null) return zh ? "无结论" : "no verdict";
        switch (k) {
            case VERIFIED_OK:     return zh ? "密码正确" : "correct";
            case PROVEN_WRONG:    return zh ? "已证实密码错误" : "proven wrong";
            case SUSPECTED_WRONG: return zh ? "疑似密码错误" : "suspected wrong";
            case NETWORK_FAIL:    return zh ? "网络认证失败" : "network auth failed";
            case NOT_FINISHED:    return zh ? "连接未完成" : "not finished";
            case ENV_ERROR:       return zh ? "环境异常" : "environment error";
            case UNDECIDED:
            default:              return zh ? "无法判断" : "undecided";
        }
    }

    /** 一句话说明（告诉用户「这代表什么、要不要重试」）。 */
    public static String hint(Kind k, String reason, boolean zh) {
        if (k == null) return "";
        switch (k) {
            case VERIFIED_OK:
                return zh ? "这条口令被证明是正确的" : "this passphrase was proven correct";
            case PROVEN_WRONG:
                return zh ? "这条口令被证明是错的，已记录，下次不会重复尝试"
                        : "this passphrase was proven wrong; recorded, will not be retried";
            case SUSPECTED_WRONG:
                return zh ? "有认证失败迹象，但无法证明是这条口令导致 —— 未记录，下次会重试"
                        : "auth-failure signs seen, but not attributable to this candidate "
                                + "- not recorded, will be retried";
            case NETWORK_FAIL:
                return zh ? "网络确实拒绝过认证，但绑不到这条口令 —— 未记录，下次会重试"
                        : "the network did reject auth, but it cannot be tied to this "
                                + "candidate - not recorded, will be retried";
            case NOT_FINISHED:
                return zh ? "连接还没跑完或已中断 —— 未记录，下次会重试"
                        : "connection did not finish or was interrupted "
                                + "- not recorded, will be retried";
            case ENV_ERROR:
                return zh ? "通道或环境出错，与口令无关 —— 请检查授权/权限后重试"
                        : "channel or environment error, unrelated to the passphrase "
                                + "- check permissions and retry";
            case UNDECIDED:
                if (targetMayHaveConnected(reason)) {
                    return zh ? "目标曾连上并取得 IP，但无法确认是这条口令造成的 —— 请人工复核"
                            : "the target did connect with an IP, but it cannot be "
                                    + "confirmed as caused by this candidate - review manually";
                }
                if ("netid-conflict".equals(reason)) {
                    // 2026-11-22：不再说「曾连上」。真机实测这条 reason 对应的
                    // 是「netId 与本次配置不符」，与「连上过」无关。
                    return zh ? "连接归属存疑（netId 与本次配置不符）—— 未记录，下次会重试"
                            : "connection attribution is doubtful (netId differs from "
                                    + "this attempt's config) - not recorded, will be retried";
                }
                if ("status-unclear".equals(reason)) {
                    return zh ? "通道正常但结束时没拿到足够证据 —— 未记录，下次会重试"
                            : "channel was alive but evidence was insufficient at the end "
                                    + "- not recorded, will be retried";
                }
                if (systemRecordedFailure(reason)) {
                    return zh ? "系统记录到本次连接失败，但原因不是「密钥错误」"
                                    + "（例如关联超时 / AP 无响应）—— 未记录，下次会重试"
                            : "the system recorded a failed attempt, but the reason is not "
                                    + "a wrong key (e.g. association timeout / AP unresponsive) "
                                    + "- not recorded, will be retried";
                }
                return zh ? "没拿到可归因的证据 —— 未记录，下次会重试"
                        : "no attributable evidence - not recorded, will be retried";
            default:
                return "";
        }
    }
}

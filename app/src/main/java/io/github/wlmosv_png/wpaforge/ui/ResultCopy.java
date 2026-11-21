package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;

/**
 * 结果→文案/语义色 的**唯一映射**（1.9.66 破解页重设计）。纯逻辑，可离线穷举。
 *
 * <h3>职责边界（重要）<\/h3>
 * <ul>
 *   <li><b>只做展示翻译<\/b>：分类（{@link ResultCategory.Kind}）→ 主标签 / 语义色调 /
 *       一句事实 / 详情行。**不推导 Verdict、不改判定、不改持久化**。<\/li>
 *   <li>Verdict 仍是后端唯一结论；本类只把既有数据换个说法。<\/li>
 * <\/ul>
 *
 * <h3>为什么单独抽出来<\/h3>
 * 1.9.65 的文案散落在 {@code VerdictView} 与 {@code ResultCategory.hint} 两处，
 * 颜色又按 Verdict 硬编码在 MainActivity 里 —— 同一个「尚无有效判定」在列表、
 * 统计、状态行三处措辞/颜色可能不一致。这里收成一处，三处共用，且可单测。
 */
public final class ResultCopy {

    /** 语义色调（UI 再映射成具体颜色；本类不依赖 Android）。 */
    public enum Tone {
        /** 成功（绿）。 */
        OK,
        /** 已证实错误（红）。 */
        PROVEN,
        /** 疑似错误（橙）。 */
        SUSPECTED,
        /** 网络级认证失败（警示橙）。 */
        WARN,
        /** 连接未完成 / 尚无有效判定（中性灰）。 */
        NEUTRAL,
        /** 环境异常（紫灰）。 */
        ENV
    }

    private ResultCopy() {}

    /** 主标签（用户可理解；统计格与攻击流主行同源）。 */
    public static String primaryLabel(ResultCategory.Kind k, boolean zh) {
        if (k == null) return zh ? "尚无有效判定" : "no verdict";
        switch (k) {
            case VERIFIED_OK:     return zh ? "已确认成功" : "verified";
            case PROVEN_WRONG:    return zh ? "已证实密码错误" : "proven wrong";
            case SUSPECTED_WRONG: return zh ? "疑似密码错误" : "suspected wrong";
            case NETWORK_FAIL:    return zh ? "网络认证失败" : "network auth failed";
            case NOT_FINISHED:    return zh ? "连接未完成" : "not finished";
            case ENV_ERROR:       return zh ? "环境异常" : "environment error";
            case UNDECIDED:
            default:              return zh ? "尚无有效判定" : "no valid verdict";
        }
    }

    /** 语义色调。 */
    public static Tone toneOf(ResultCategory.Kind k) {
        if (k == null) return Tone.NEUTRAL;
        switch (k) {
            case VERIFIED_OK:     return Tone.OK;
            case PROVEN_WRONG:    return Tone.PROVEN;
            case SUSPECTED_WRONG: return Tone.SUSPECTED;
            case NETWORK_FAIL:    return Tone.WARN;
            case NOT_FINISHED:    return Tone.NEUTRAL;
            case ENV_ERROR:       return Tone.ENV;
            case UNDECIDED:
            default:              return Tone.NEUTRAL;
        }
    }

    /**
     * 一句简短、准确的事实说明。
     *
     * <p>必须回答三件事：① 观察到了什么 ② 为什么不能更明确 ③ 是否记录/是否跳过。
     * **不得重复主标签的措辞**（否则就是「主行和副行说同一件事」）。
     */
    public static String shortFact(ResultCategory.Kind k, String reason, boolean zh) {
        if (k == null) return zh ? "本次未形成结论。" : "No conclusion for this attempt.";
        switch (k) {
            case VERIFIED_OK:
                return zh ? "已连上并取得 IP —— 这条口令正确。"
                          : "Connected and obtained an IP - this passphrase is correct.";
            case PROVEN_WRONG:
                return zh ? "系统观察到归属于本次尝试的错误密钥 —— 已记录，不再重试。"
                          : "Wrong-key proven for this attempt - recorded, will not retry.";
            case SUSPECTED_WRONG:
                return zh ? "有认证失败迹象但不足以确认 —— 未记录，下次会重试。"
                          : "Auth-failure signs but unconfirmed - not recorded, will retry.";
            case NETWORK_FAIL:
                return zh ? "网络拒绝过认证，但绑不到这条口令 —— 未记录，下次会重试。"
                          : "Network rejected auth but not attributable - not recorded, will retry.";
            case NOT_FINISHED:
                return zh ? "连接提前结束或仍在进行，未形成完整判定 —— 未记录，下次会重试。"
                          : "Connection ended/ongoing without a verdict - not recorded, will retry.";
            case ENV_ERROR:
                return zh ? "通道或权限问题，与口令无关 —— 请检查后重试。"
                          : "Channel/permission issue, unrelated to the passphrase - check and retry.";
            case UNDECIDED:
            default:
                if (ResultCategory.targetMayHaveConnected(reason)) {
                    return zh ? "目标曾连上并取得 IP，但无法确认是这条口令 —— 未记录，请复核。"
                              : "Target connected with an IP but not attributable - not recorded, review.";
                }
                if ("netid-conflict".equals(reason)) {
                    return zh ? "连接归属存疑（netId 与本次配置不符）—— 未记录，下次会重试。"
                              : "Attribution doubtful (netId mismatch) - not recorded, will retry.";
                }
                if ("status-unclear".equals(reason)) {
                    return zh ? "通道正常但结束时证据不足 —— 未记录，下次会重试。"
                              : "Channel ok but evidence insufficient at end - not recorded, will retry.";
                }
                if (ResultCategory.systemRecordedFailure(reason)) {
                    return zh ? "系统记录到本次连接失败，但原因不是密钥错误 —— 未记录，下次会重试。"
                              : "System recorded a failed attempt, reason not a wrong key - not recorded, retry.";
                }
                return zh ? "未观察到可归属于本次尝试的证据 —— 未记录，下次会重试。"
                          : "No attributable evidence - not recorded, will retry.";
        }
    }

    /** 详情行：把后端给出的技术字段拼成可追溯的一行（展开时显示）。 */
    public static String detailLine(String attemptId, CrackEngine.Verdict v,
                                    EvidenceGrade.Level g, String reason, String source,
                                    boolean zh) {
        String verdict = v == null ? "—" : v.name();
        String grade = g == null ? "—" : g.name();
        String tried = EvidenceGrade.persistable(g)
                ? (zh ? "写入" : "yes") : (zh ? "不写" : "no");
        String cursor = EvidenceGrade.advancesCursor(g)
                ? (zh ? "推进" : "advance") : (zh ? "不推进" : "hold");
        return (attemptId == null || attemptId.isEmpty() ? "attempt=—" : "attempt=" + attemptId)
                + " · verdict=" + verdict
                + " · grade=" + grade
                + " · reason=" + (reason == null || reason.isEmpty() ? "—" : reason)
                + " · src=" + (source == null || source.isEmpty() ? "—" : source)
                + " · triedSet=" + tried
                + " · cursor=" + cursor;
    }
}

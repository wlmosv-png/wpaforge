package io.github.wlmosv_png.wpaforge.ui;

/**
 * **用户表达层**：把内部判定翻译成普通用户能理解的说法（1.9.67）。纯逻辑，可离线穷举。
 *
 * <h3>为什么需要它<\/h3>
 * WPAForge 是逐条验证字典候选的工具：**候选连不上、换下一条，是正常工作过程**。
 * 但 1.9.66 的攻击流把每条未命中都打成「尚无有效判定 / 连接未完成」这类技术措辞，
 * 用户看到的是「软件每试一次就出一次问题」，而不是「正在逐条尝试」。
 *
 * <h3>本类只做翻译，绝不改判定<\/h3>
 * <ul>
 *   <li>**不**把 UNKNOWN / INCOMPLETE 改成 AUTH_FAIL；<\/li>
 *   <li>**不**影响 TriedSet / Cursor（仍只看 {@link io.github.wlmosv_png.wpaforge.audit.EvidenceGrade}）；<\/li>
 *   <li>**不**删除任何技术字段 —— 详情行仍展示 verdict / grade / reason / src / attemptId。<\/li>
 * <\/ul>
 * 内部审计口径（{@link ResultCategory}）与技术细节原样保留，只是**默认不摆在主界面上**。
 */
public final class UserOutcome {

    /** 用户可见的结果表达（互斥）。 */
    public enum Kind {
        /** 正在尝试候选。 */
        RUNNING,
        /** 候选未成功，继续下一条 —— **中性**，不代表已证明密码错。 */
        MISS,
        /** 找到正确密码。 */
        HIT,
        /** 已证实密码错误（DIRECT）—— 仍是「未命中」的一种，但值得单列。 */
        PROVEN_WRONG,
        /** 用户主动暂停。 */
        PAUSED,
        /** 字典执行完毕，未找到密码。 */
        NOT_FOUND,
        /** 真实的执行故障（Wi-Fi 服务 / 权限 / Shell / 通道）。 */
        ENV_ERROR,
        /** 任务被停止或取消。 */
        STOPPED,
        /** 尚未开始。 */
        IDLE
    }

    private UserOutcome() {}

    /** 用户主文案。 */
    public static String label(Kind k, boolean zh) {
        if (k == null) return zh ? "未开始" : "idle";
        switch (k) {
            case RUNNING:      return zh ? "正在运行" : "running";
            case MISS:         return zh ? "未命中" : "no match";
            case HIT:          return zh ? "破解成功" : "cracked";
            case PROVEN_WRONG: return zh ? "已证实密码错误" : "proven wrong";
            case PAUSED:       return zh ? "已暂停" : "paused";
            case NOT_FOUND:    return zh ? "未找到匹配密码" : "no matching password";
            case ENV_ERROR:    return zh ? "环境异常" : "environment error";
            case STOPPED:      return zh ? "已停止" : "stopped";
            case IDLE:
            default:           return zh ? "未开始" : "idle";
        }
    }

    /**
     * 内部类别 → 用户表达。
     *
     * <p>核心：**UNKNOWN / NOT_FINISHED / NETWORK_FAIL / SUSPECTED_WRONG 一律叫「未命中」**。
     * 它们的事实差别保留在详情行与审计页，不在主界面制造「故障」印象。
     */
    public static Kind of(ResultCategory.Kind k) {
        if (k == null) return Kind.MISS;
        switch (k) {
            case VERIFIED_OK:     return Kind.HIT;
            case PROVEN_WRONG:    return Kind.PROVEN_WRONG;
            case ENV_ERROR:       return Kind.ENV_ERROR;
            case SUSPECTED_WRONG:
            case NETWORK_FAIL:
            case NOT_FINISHED:
            case UNDECIDED:
            default:              return Kind.MISS;
        }
    }

    /**
     * 是否属于「需要用户处理的真实故障」。
     *
     * <p>只有这一类才在攻击流里用醒目色 + 明确措辞；普通未命中一律中性。
     */
    public static boolean needsAttention(Kind k) {
        return k == Kind.ENV_ERROR;
    }

    /** 一句事实（不重复主标签措辞）。 */
    public static String fact(Kind k, boolean zh) {
        if (k == null) return "";
        switch (k) {
            case RUNNING:
                return zh ? "正在逐条验证字典中的候选。"
                          : "Verifying dictionary candidates one by one.";
            case MISS:
                return zh ? "这条没有连上，继续尝试下一条。"
                          : "This one did not connect; moving to the next.";
            case HIT:
                return zh ? "已连上并取得 IP —— 这条口令正确。"
                          : "Connected and obtained an IP - this passphrase is correct.";
            case PROVEN_WRONG:
                return zh ? "系统观察到归属于本次尝试的错误密钥，已记录，不再重试。"
                          : "Wrong key proven for this attempt; recorded, will not retry.";
            case PAUSED:
                return zh ? "进度已保留，可以随时继续。"
                          : "Progress kept; can resume anytime.";
            case NOT_FOUND:
                return zh ? "字典已全部执行完，没有找到能连上的口令。"
                          : "The whole dictionary ran through without a match.";
            case ENV_ERROR:
                return zh ? "Wi-Fi 服务或权限不可用，与口令无关 —— 请检查后重试。"
                          : "Wi-Fi service or permission unavailable, unrelated to passphrases.";
            case STOPPED:
                return zh ? "任务已被停止，进度保留在停止点。"
                          : "Task stopped; progress kept at the stop point.";
            case IDLE:
            default:
                return zh ? "选好目标和字典后即可开始。"
                          : "Pick a target and a dictionary to start.";
        }
    }

    /**
     * 字典跑完时的收尾表达。
     *
     * @param hit       是否命中
     * @param proven    已证实错误条数（DIRECT）
     * @param missTotal 其余未命中条数
     */
    public static String finishSummary(boolean hit, int proven, int missTotal, boolean zh) {
        if (hit) return label(Kind.HIT, zh);
        if (proven > 0) {
            return zh ? ("未找到匹配密码（已证实 " + proven + " 条错误）")
                      : ("no matching password (" + proven + " proven wrong)");
        }
        return label(Kind.NOT_FOUND, zh);
    }
}

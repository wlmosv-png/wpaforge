package io.github.wlmosv_png.wpaforge.audit;

/**
 * 「能不能开始一次破坏性运行」的闸门（纯逻辑，无 Android 依赖）。
 *
 * <h3>为什么必须有它（F3 / F6）</h3>
 * 引擎为了保证归因，会删掉目标的已保存配置再用候选口令加回来。
 * 如果**原始配置没能安全记下来**，跑完之后就再也还原不回去了。
 * 旧实现只在快照/落盘失败时写一行日志就继续跑 —— 这等于在
 * 「恢复能力已经失效」的状态下照样做破坏性操作，而用户看不到。
 *
 * <p>另一个更隐蔽的风险：盘上如果还留着**上一个任务**的未完成恢复记录，
 * 而本任务的记录又落盘失败，收尾时就会拿**旧记录**去恢复**另一个目标**。
 * 所以「盘上还有别人的未完成记录」也必须挡住。
 *
 * <h3>原则：fail closed</h3>
 * 任何一项不成立 → 不进入破坏性操作。宁可这一轮不跑（任务保持可续跑），
 * 也不能在无法还原的前提下动用户的网络配置。
 */
public final class RestoreGuard {

    /** 挡下来的原因（机器可读，进日志与界面）。 */
    public enum Reason {
        /** 盘上还有**别的任务**未完成的恢复记录 —— 必须先把它处理掉。 */
        OTHER_PENDING,
        /** 读不到已保存网络列表 —— 连目标当前有没有配置都不知道。 */
        CHANNEL_UNREADABLE,
        /** 目标是已保存网络，但读不到配置文件 —— 拿不到原口令。 */
        CONFIG_UNREADABLE,
        /** 目标是需要口令的网络，却没有读到任何口令。 */
        PSK_UNREADABLE,
        /** 读到了口令但加密失败 —— 不许退回明文，也不许假装能还原。 */
        SEAL_FAILED,
        /** 恢复记录写不进盘 —— 进程被杀就没有补做依据。 */
        JOURNAL_UNWRITABLE
    }

    /** 闸门需要的事实（由调用方采集，全部是布尔/计数，不含口令）。 */
    public static final class Facts {
        /** 盘上存在别的任务的未完成恢复记录。 */
        public final boolean otherPending;
        /** `cmd wifi list-networks` 是否读到。 */
        public final boolean channelOk;
        /** WifiConfigStore 是否读到。 */
        public final boolean configReadable;
        /** 目标原本是否是**已保存**网络（netId ≥ 0）。 */
        public final boolean targetWasSaved;
        /** 目标是否需要口令（开放网络不需要）。 */
        public final boolean pskRequired;
        /** 是否读到了原口令。 */
        public final boolean hadPsk;
        /** 原口令是否已成功加密（未加密 = 不能落盘）。 */
        public final boolean sealedOk;
        /** 恢复记录是否已成功落盘。 */
        public final boolean journalSaved;

        public Facts(boolean otherPending, boolean channelOk, boolean configReadable,
                     boolean targetWasSaved, boolean pskRequired, boolean hadPsk,
                     boolean sealedOk, boolean journalSaved) {
            this.otherPending = otherPending;
            this.channelOk = channelOk;
            this.configReadable = configReadable;
            this.targetWasSaved = targetWasSaved;
            this.pskRequired = pskRequired;
            this.hadPsk = hadPsk;
            this.sealedOk = sealedOk;
            this.journalSaved = journalSaved;
        }

        /** 全绿的构造（测试与「目标本来就没保存」的常见场景用）。 */
        public static Facts ok(boolean targetWasSaved, boolean pskRequired,
                               boolean hadPsk, boolean sealedOk, boolean journalSaved) {
            return new Facts(false, true, true, targetWasSaved, pskRequired,
                    hadPsk, sealedOk, journalSaved);
        }
    }

    /** 判定结果。 */
    public static final class Decision {
        public final boolean proceed;
        public final Reason reason;
        public final String detail;

        public Decision(boolean proceed, Reason reason, String detail) {
            this.proceed = proceed;
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
        }

        /** 被挡下时是否**已经**动过系统状态（用于决定要不要收尾恢复）。 */
        public boolean touchedSystem() { return false; }

        public String code() { return reason == null ? "OK" : reason.name(); }
    }

    private RestoreGuard() {}

    private static final Decision GO = new Decision(true, null, "");

    /**
     * 决策。**顺序即优先级**：先把「别人的未完成恢复」挡住，
     * 再看通道/配置能否读到，最后看记录是否写得进去。
     */
    public static Decision decide(Facts f) {
        if (f == null) {
            return new Decision(false, Reason.JOURNAL_UNWRITABLE, "缺少闸门事实");
        }
        if (f.otherPending) {
            return new Decision(false, Reason.OTHER_PENDING,
                    "盘上还有另一个任务未完成的配置恢复：必须先处理它，"
                            + "否则本次收尾可能拿旧记录去恢复另一个目标");
        }
        if (!f.channelOk) {
            return new Decision(false, Reason.CHANNEL_UNREADABLE,
                    "读不到已保存网络列表，无法确认目标的原始配置");
        }
        if (f.targetWasSaved && !f.configReadable) {
            return new Decision(false, Reason.CONFIG_UNREADABLE,
                    "目标是已保存网络，但读不到 WiFi 配置：拿不到原口令，跑完无法还原");
        }
        if (f.pskRequired && !f.hadPsk) {
            return new Decision(false, Reason.PSK_UNREADABLE,
                    "目标需要口令，但没有读到任何原口令");
        }
        if (f.hadPsk && !f.sealedOk) {
            return new Decision(false, Reason.SEAL_FAILED,
                    "原口令加密失败（密钥不可用）：不许落明文，也无法保证能还原");
        }
        if (!f.journalSaved) {
            return new Decision(false, Reason.JOURNAL_UNWRITABLE,
                    "恢复记录写不进盘：进程被杀后没有补做依据");
        }
        return GO;
    }

    /** 供日志/界面用的一行结论。 */
    public static String describe(Decision d) {
        if (d == null) return "未判定";
        return d.proceed ? "放行" : ("已阻止（" + d.code() + "）：" + d.detail);
    }
}

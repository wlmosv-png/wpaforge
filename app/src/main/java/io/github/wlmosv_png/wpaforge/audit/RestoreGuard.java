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
 * <p>另一个更隐蔽的风险：本任务的记录落盘失败时，收尾可能拿**别的目标**的旧记录
 * 去执行恢复。这一条由 {@link RestoreGuard.Reason#OTHER_PENDING}（同目标未解决）
 * 与 {@link RestoreRunner#checkNewTask} 里的 taskId 归属校验共同封堵。
 *
 * <h3>原则：fail closed</h3>
 * 任何一项不成立 → 不进入破坏性操作。宁可这一轮不跑（任务保持可续跑），
 * 也不能在无法还原的前提下动用户的网络配置。
 *
 * <h3>1.9.28 的判据收窄</h3>
 * 1.9.27 把「盘上有**任何**不属于本任务的未完成记录」都算作冲突，
 * 于是一条“配置已写回、只是当时没连上”的失败记录会永久挡掉所有新任务。
 * 现在冲突只按**同一目标**判（见 {@link RestoreRunner#blocksTask}），
 * 并新增两条明确的拦截理由：记录不可解析、待处理记录堆积到上限。
 */
public final class RestoreGuard {

    /** 挡下来的原因（机器可读，进日志与界面）。 */
    public enum Reason {
        /**
         * **同一目标**还有未解决的恢复记录 —— 必须先处理它。
         *
         * <p>为什么同目标必须挡：新任务的快照会把这个目标当前的配置当作“原配置”，
         * 而它可能正是上一次留下的候选口令 —— 直接往下跑就会把污染固化成“原配置”。
         */
        OTHER_PENDING,
        /** 盘上有记录但解析不出来（损坏 / 更高版本）→ 未知，不当作“没有”。 */
        JOURNAL_UNREADABLE,
        /** 待处理记录堆积到上限 —— 要求人工清理，避免无限累积。 */
        TOO_MANY_PENDING,
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
        /** 盘上存在**同一目标**未解决的恢复记录。 */
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

        // ── 1.9.28 ──────────────────────────────────────────────────────
        /** 盘上有记录但解析不出来。 */
        public final boolean journalUnreadable;
        /** 盘上待处理记录条数（用于判断是否堆积）。 */
        public final int pendingCount;

        public Facts(boolean otherPending, boolean channelOk, boolean configReadable,
                     boolean targetWasSaved, boolean pskRequired, boolean hadPsk,
                     boolean sealedOk, boolean journalSaved) {
            this(otherPending, channelOk, configReadable, targetWasSaved, pskRequired,
                    hadPsk, sealedOk, journalSaved, false, 0);
        }

        public Facts(boolean otherPending, boolean channelOk, boolean configReadable,
                     boolean targetWasSaved, boolean pskRequired, boolean hadPsk,
                     boolean sealedOk, boolean journalSaved,
                     boolean journalUnreadable, int pendingCount) {
            this.otherPending = otherPending;
            this.channelOk = channelOk;
            this.configReadable = configReadable;
            this.targetWasSaved = targetWasSaved;
            this.pskRequired = pskRequired;
            this.hadPsk = hadPsk;
            this.sealedOk = sealedOk;
            this.journalSaved = journalSaved;
            this.journalUnreadable = journalUnreadable;
            this.pendingCount = pendingCount;
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

        /** 被挡下时是否需要人工处理（而不是重试即可）。 */
        public boolean needsHuman() {
            return reason == Reason.TOO_MANY_PENDING || reason == Reason.JOURNAL_UNREADABLE
                    || reason == Reason.SEAL_FAILED || reason == Reason.CONFIG_UNREADABLE
                    || reason == Reason.PSK_UNREADABLE;
        }

        public String code() { return reason == null ? "OK" : reason.name(); }
    }

    private RestoreGuard() {}

    private static final Decision GO = new Decision(true, null, "");

    /**
     * 决策。**顺序即优先级**：先处理“未知/冲突/堆积”，
     * 再看通道/配置能否读到，最后看记录是否写得进去。
     */
    public static Decision decide(Facts f) {
        if (f == null) {
            return new Decision(false, Reason.JOURNAL_UNWRITABLE, "缺少闸门事实");
        }
        if (f.journalUnreadable) {
            return new Decision(false, Reason.JOURNAL_UNREADABLE,
                    "盘上的恢复记录无法解析（损坏或来自更高版本）："
                            + "无法确认是否还有目标未收尾，按未知处理。"
                            + "可在破解页的恢复状态区备份并清理它");
        }
        if (f.otherPending) {
            return new Decision(false, Reason.OTHER_PENDING,
                    "同一目标的配置恢复还没处理完：必须先处理它，"
                            + "否则本次可能把上一次留下的候选口令当成原配置");
        }
        if (f.pendingCount >= RestoreJournal.MAX_PENDING) {
            return new Decision(false, Reason.TOO_MANY_PENDING,
                    "盘上堆积了 " + f.pendingCount + " 条未处理的恢复记录（上限 "
                            + RestoreJournal.MAX_PENDING + "）");
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

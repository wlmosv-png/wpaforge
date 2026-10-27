package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 恢复策略（纯逻辑，无 Android 依赖）—— 回答三个问题：
 * <ol>
 *   <li>这条记录**需不需要**动手？（{@link #steps}）</li>
 *   <li>做完之后**算不算成功**？失败是**哪一步**败的？（{@link #judge}）</li>
 *   <li>无论成功失败，**有没有把错误候选口令留在系统里**？（{@link Outcome#noWrongLeft}）</li>
 * </ol>
 *
 * <h3>1.9.28 的关键订正：配置恢复 ≠ 连接成功</h3>
 * 旧版把两件事压成一个 {@code restored}：
 * <ul>
 *   <li><b>配置层面</b>：原口令是否写回系统并被**独立回读**确认；</li>
 *   <li><b>连接层面</b>：此刻是否连着目标、是否拿到有效 IP。</li>
 * </ul>
 * 于是一个必然失败的场景：目标**不在覆盖范围内**（或用户此刻连着别的网）时，
 * 连接观察永远不成立 → 记录被判 {@code VERIFY-FAILED} → 详见
 * {@link RestoreJournal} 里记的那次真实故障。
 *
 * <p>现在：
 * <ul>
 *   <li>{@code restored} 只由**写回确认**决定（{@link Outcome#writeBackConfirmed}）；</li>
 *   <li>连接与 IP 只作为观察值记录（{@link Outcome#connObserved} /
 *       {@link Outcome#ipObserved}），**不参与成败**；</li>
 *   <li>写回确认了但没连上 → 成功终态，码为 {@link #WROTE_BACK}；</li>
 *   <li>写回**没确认**（命令没发出 / 列表里看不到目标）→ 真失败，
 *       码为 {@link #READD_FAILED} / {@link #READD_NOT_WRITTEN}，并标
 *       {@link Outcome#residualRisk}=true（可能还留着候选口令）。</li>
 * </ul>
 *
 * <h3>两条硬约束（不变）</h3>
 * <ul>
 *   <li><b>不许遗留错误口令</b>：只要不能确认“已还原成原配置”，
 *       就必须把目标从已保存列表里删干净。删掉是**可见**的故障（用户会重连），
 *       留着错误口令是**静默**的故障（用户以为存好了，其实连不上）。</li>
 *   <li><b>不许伪报成功</b>：没有独立回读就不算写回成功。
 *       {@code cmd wifi connect-network} 是**哑命令**（成功失败都无输出、退出码都是 0）。
 *       注意：我们能验证的只有「配置是否存在」这一级 —— 读不回口令本身，
 *       所以“写回确认”的严格含义见 {@link #judge} 的注释。</li>
 * </ul>
 */
public final class RestorePlan {

    /** 恢复步骤（顺序即执行顺序）。 */
    public enum Step {
        /** 删掉候选留下的配置。 */
        REMOVE_WRONG_CONFIG,
        /** 用**原口令**把目标加回去。 */
        READD_ORIGINAL,
        /** 读回状态确认。 */
        VERIFY,
        /**
         * 命中收尾：**保留**目标当前那条配置（它就是刚验证过的正确口令），
         * 全程不做任何删除。见 {@link #steps(RestoreJournal.Entry, boolean)}。
         */
        KEEP_HIT_CONFIG
    }

    /** 结果码（机器可读，进日志与队列）。 */
    public static final String OK = "OK";
    public static final String NO_RECORD = "NO-RECORD";
    public static final String NO_SECRET = "NO-SECRET";
    public static final String REMOVE_FAILED = "REMOVE-FAILED";
    public static final String READD_FAILED = "READD-FAILED";
    /**
     * 重建命令发出了，但**回读已保存列表里没有目标** —— 写回没成功。
     *
     * <p>与 {@link #WROTE_BACK} 严格区分：后者是「配置写回去了、只是没连上」，
     * 前者是「配置根本没写回去」。把前者当后者报，会把「配置丢了」说成「网络没连上」。
     */
    public static final String READD_NOT_WRITTEN = "READD-NOT-WRITTEN";
    /**
     * **写回已确认，但没有观察到连接**（目标不在覆盖范围内 / 当前连着别的网 /
     * 观察窗口内没等到）。
     *
     * <p>这是一个**成功终态**：恢复的目标是“把原配置放回系统”，不是“此刻必须连上”。
     * 1.9.27 及以前把它报成 {@code VERIFY-FAILED} 并判失败，是那次永久自锁的根因。
     */
    public static final String WROTE_BACK = "WROTE-BACK";
    /** 原本就没有已保存配置、且已确认无候选残留 —— 无需还原。 */
    public static final String NOTHING_TO_DO = "NOTHING-TO-DO";
    /**
     * **未能确认**（审计修复）：读不到已保存网络列表，因此既不能确认写回成功，
     * 也不能确认“没有候选残留”。
     *
     * <p>为什么要单独一个码：把“读不到”当成“列表里没有目标”，就等于把
     * 未确认说成没有残留 —— 那是 fail-open。此码一律：不算成功、不删记录、
     * 仍算可重试失败、并把残留风险保守标为 true。
     */
    public static final String UNVERIFIED = "UNVERIFIED";
    /**
     * 没有可执行的恢复步骤（记录已是终态）。
     *
     * <p>它**不是**成功，也不是失败：什么都没做，所以什么都不声明。
     */
    public static final String NO_STEPS = "NO-STEPS";
    /**
     * @deprecated 1.9.28 起不再产生。连接观察不再决定恢复成败；
     *             写回失败请见 {@link #READD_FAILED} / {@link #READD_NOT_WRITTEN}。
     *             常量保留仅为读取/迁移旧记录。
     */
    @Deprecated
    public static final String VERIFY_FAILED = "VERIFY-FAILED";

    /** 命中口令已**保留**为目标配置（回读确认配置在已保存列表里）。 */
    public static final String KEPT = "KEPT";

    /**
     * 保留条件不成立（回读不到目标配置）—— 调用方必须退回标准恢复。
     * 与 {@link #KEPT} 严格区分：前者是「没留下东西」，后者是「正确口令留住了」。
     */
    public static final String KEEP_NOT_WRITTEN = "KEEP-NOT-WRITTEN";

    /**
     * 重建原配置这一步的结果。
     *
     * <p>哑命令（{@code cmd wifi connect-network}）成功失败都无输出、退出码都是 0，
     * 所以**不能凭「命令执行返回」认定已写回**。必须再读一次已保存列表：
     * <ul>
     *   <li>{@link #issued}：命令是否真的发出（拿到帧）；</li>
     *   <li>{@link #configPresent}：回读列表里是否真的出现了目标配置。</li>
     * </ul>
     */
    public static final class ReaddResult {
        public final boolean issued;
        public final boolean configPresent;
        public final String detail;

        ReaddResult(boolean issued, boolean configPresent, String detail) {
            this.issued = issued;
            this.configPresent = configPresent;
            this.detail = detail == null ? "" : detail;
        }

        public static ReaddResult notAttempted() {
            return new ReaddResult(false, false, "未尝试重建");
        }

        public static ReaddResult notIssued() {
            return new ReaddResult(false, false, "重建命令未能发出");
        }

        public static ReaddResult issuedButMissing() {
            return new ReaddResult(true, false, "命令已发出但列表里未见目标");
        }

        public static ReaddResult written() {
            return new ReaddResult(true, true, "已写回且回读可见");
        }

        /** 是否**确认**已写回（两个条件都要成立）。 */
        public boolean confirmed() { return issued && configPresent; }
    }

    private RestorePlan() {}

    /** 需要执行的步骤。 */
    public static List<Step> steps(RestoreJournal.Entry e) {
        return steps(e, false);
    }

    /**
     * 需要执行的步骤（区分「命中保留」与「标准恢复」）。
     *
     * <p><b>keepHit=true（命中收尾）</b>：系统里留下的那条配置就是**刚验证过的正确口令**
     * —— 它正是用户要的结果，所以这里一步删除都不做，只读回确认"配置确实在已保存列表里"。
     *
     * <p><b>keepHit=false（未命中 / 暂停 / 取消 / 异常 / 启动补做）</b>：
     * 候选留下的口令是错的（或状态未知），必须先删干净。
     *
     * @param e       记录；{@code null} 或已终结 → 空列表
     * @param keepHit 本轮是否命中
     */
    public static List<Step> steps(RestoreJournal.Entry e, boolean keepHit) {
        if (e == null || e.settled()) return Collections.emptyList();
        List<Step> out = new ArrayList<>(3);
        if (keepHit) {
            out.add(Step.KEEP_HIT_CONFIG);
            out.add(Step.VERIFY);
            return out;
        }
        // 第一步永远是删 —— 无论后面能不能加回去，
        // “错误候选口令不许留在系统里”这条都必须先满足。
        out.add(Step.REMOVE_WRONG_CONFIG);
        if (e.canReadd()) out.add(Step.READD_ORIGINAL);
        out.add(Step.VERIFY);
        return out;
    }

    /** 本轮要不要走「命中保留」这条路（纯判断，与 shell 无关）。 */
    public static boolean keepsHitConfig(RestoreJournal.Entry e, boolean keepHit) {
        if (!keepHit || e == null || e.settled()) return false;
        return steps(e, true).contains(Step.KEEP_HIT_CONFIG);
    }

    /** 读回验证的输入快照（由 shell 侧采集，纯数据）。 */
    public static final class Verify {
        /** 目标是否**仍**在已保存网络列表里。 */
        public final boolean listPresent;
        /**
         * **列表本身读到了吗**（审计修复）。
         *
         * <p>为什么要单独一个字段：{@code cmd wifi list-networks} 读失败时，
         * “列表里没有目标”和“压根没读到列表”都会表现成 {@code listPresent=false}。
         * 把后者当成前者就是 fail-open —— 会把“未确认”说成“没有残留”，
         * 甚至说成“无需还原”。判定必须能区分这两件事。
         */
        public final boolean listReadable;
        /** {@code cmd wifi status} 读到的 SSID（可为 null）。 */
        public final String statusSsid;
        /** supplicant 状态（可为 null）。 */
        public final String supplicantState;
        /** 读到的 IP（可为 null）。 */
        public final String ip;

        /**
         * 四参构造：**默认认为“列表读到了”**（{@code listReadable=true}）。
         *
         * <p>为什么这样定：1.9.28 之前所有调用点用 {@code listPresent=false} 表达的
         * 都是“列表读到了、里面没有目标”；“压根没读到列表”这个情形以前根本
         * 无法表达（那正是 fail-open 的来源）。要让“读不到”显式化，就必须让四参
         * 保持旧语义，而把“读不到”留给五参构造与 {@link #unknown()}。
         */
        public Verify(boolean listPresent, String statusSsid,
                      String supplicantState, String ip) {
            this(listPresent, statusSsid, supplicantState, ip, true);
        }

        public Verify(boolean listPresent, String statusSsid,
                      String supplicantState, String ip, boolean listReadable) {
            this.listPresent = listPresent;
            this.statusSsid = statusSsid;
            this.supplicantState = supplicantState;
            this.ip = ip;
            this.listReadable = listReadable;
        }

        /** 采不到（通道断、命令超时）→ 全部未知，一律按“没验证”处理。 */
        public static Verify unknown() { return new Verify(false, null, null, null, false); }

        /** 列表是否**没读到**（读失败 / 命令超时）。 */
        public boolean listUnreadable() { return !listReadable; }

        /** 是否读到了可用信息（读不到就不能判成功）。 */
        public boolean readable() { return statusSsid != null || listPresent; }

        /** 目标此刻是否是“已连上”的状态（仅用于观察字段）。 */
        public boolean connectedTo(String ssid) {
            if (ssid == null || ssid.isEmpty()) return false;
            return ssid.equals(statusSsid)
                    && supplicantState != null
                    && "COMPLETED".equalsIgnoreCase(supplicantState)
                    && isIpValid(ip);
        }

        public String describe() {
            return "list=" + (listReadable ? (listPresent ? "yes" : "no") : "UNREADABLE")
                    + " ssid=" + (statusSsid == null ? "<null>" : statusSsid)
                    + " state=" + (supplicantState == null ? "<null>" : supplicantState)
                    + " ip=" + (ip == null ? "<null>" : ip);
        }
    }

    /** 恢复结论。 */
    public static final class Outcome {
        /** 命中口令是否已被**保留**为目标配置（回读确认在已保存列表里）。 */
        public final boolean kept;
        /** 原配置是否已还原**并读回验证**（= 写回确认，不含连接观察）。 */
        public final boolean restored;
        /** 是否已确认“系统里没有留下错误候选口令”。 */
        public final boolean noWrongLeft;

        // ── 1.9.28：把四件事分别记录（用户明确要求，不得混为一个状态） ──
        /** ① 原配置是否已成功写回系统（命令已发出**且**回读可见目标）。 */
        public final boolean writeBackConfirmed;
        /** ③ 当前是否已连接目标网络（**仅观察**）。 */
        public final boolean connObserved;
        /** ④ 是否获得有效 IP（**仅观察**）。 */
        public final boolean ipObserved;
        /** 是否可能仍有候选残留（错误口令还留在系统里）→ 需要针对性处理。 */
        public final boolean residualRisk;
        /** 失败是否**可重试**（通道类/命令类失败可重试；口令不可读这类不可）。 */
        public final boolean retryable;
        /**
         * 本次是否**真的发出了写回命令**（{@code cmd wifi connect-network}）。
         *
         * <p>审计要求把「发起恢复次数」「实际写回次数」「验证失败次数」分开记：
         * 一条记录可能被尝试了很多次，但每次都因为拿不到原口令密文而**根本没写回**。
         * 用这个标志就能把它们区分开，见 {@link RestoreJournal.Entry#writeBackIssuedCount}。
         */
        public final boolean readdIssued;

        public final String code;
        public final Step failedAt;
        public final String detail;

        Outcome(boolean restored, boolean noWrongLeft, String code, Step failedAt, String detail) {
            this(false, restored, noWrongLeft, restored, false, false,
                    !noWrongLeft, false, code, failedAt, detail);
        }

        Outcome(boolean kept, boolean restored, boolean noWrongLeft,
                String code, Step failedAt, String detail) {
            this(kept, restored, noWrongLeft, restored, false, false,
                    !noWrongLeft, false, code, failedAt, detail);
        }

        Outcome(boolean kept, boolean restored, boolean noWrongLeft,
                boolean writeBackConfirmed, boolean connObserved, boolean ipObserved,
                boolean residualRisk, boolean retryable,
                String code, Step failedAt, String detail) {
            this(kept, restored, noWrongLeft, writeBackConfirmed, connObserved, ipObserved,
                    residualRisk, retryable, false, code, failedAt, detail);
        }

        Outcome(boolean kept, boolean restored, boolean noWrongLeft,
                boolean writeBackConfirmed, boolean connObserved, boolean ipObserved,
                boolean residualRisk, boolean retryable, boolean readdIssued,
                String code, Step failedAt, String detail) {
            this.kept = kept;
            this.restored = restored;
            this.noWrongLeft = noWrongLeft;
            this.writeBackConfirmed = writeBackConfirmed;
            this.connObserved = connObserved;
            this.ipObserved = ipObserved;
            this.residualRisk = residualRisk;
            this.retryable = retryable;
            this.readdIssued = readdIssued;
            this.code = code;
            this.failedAt = failedAt;
            this.detail = detail == null ? "" : detail;
        }

        /** 终态是否已妥善处理（原配置还原了，或正确口令按命中策略留住了）。 */
        public boolean resolved() { return restored || kept; }

        /** 能不能对用户说“你的网络配置已经处理好了”。 */
        public boolean acceptable() { return resolved(); }

        /** 供日志/界面用的一行结论。 */
        public String summary() {
            if (kept) return "已保留命中口令：" + code + (detail.isEmpty() ? "" : "（" + detail + "）");
            if (restored) {
                String tail = connObserved ? "，此刻已连上" : "，此刻未观察到连上（不影响配置已还原）";
                return "已还原：" + code + tail;
            }
            if (noWrongLeft) return "未能还原原配置，但已清除候选残留：" + code
                    + (detail.isEmpty() ? "" : "（" + detail + "）");
            return "恢复失败：" + code + (detail.isEmpty() ? "" : "（" + detail + "）");
        }
    }

    /** IP 有效性（与 {@link SuccessRule} 同一口径）。 */
    public static boolean isIpValid(String ip) {
        return ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip);
    }

    /**
     * 判定恢复结果。
     *
     * <h3>“写回确认”的严格含义（必须要说清的能力边界）</h3>
     * 我们能用的只有两条系统通道：{@code cmd wifi list-networks}（配置是否存在、
     * netId 是多少）与 {@code cmd wifi status}（当前连接状态）。**读不回配置里的口令**，
     * 因此“写回确认”能证明的是：<b>我们发出的原口令被系统接受，且目标重新出现在
     * 已保存列表里</b>；它不能证明“以后每次都能连上”。这就是为什么连接观察必须
     * 单独记录、且不得反过来把配置恢复判成失败。
     *
     * @param e 记录；{@code null} → {@link #NO_RECORD}
     * @param v 读回快照
     * @param removeStageOk {@link Step#REMOVE_WRONG_CONFIG} 这一步是否执行成功
     * @param readd {@link Step#READD_ORIGINAL} 这一步的结果（命令是否发出 + 是否回读可见）
     */
    public static Outcome judge(RestoreJournal.Entry e, Verify v,
                                boolean removeStageOk, ReaddResult readd) {
        if (e == null) {
            return new Outcome(false, false, false, NO_RECORD, null,
                    "没有恢复记录，无法判断原配置状态");
        }
        if (v == null) v = Verify.unknown();

        if (!removeStageOk) {
            // 连“删掉错误候选口令”都没做到 —— 这是最坏的一种：既没还原、又留了错口令。
            return new Outcome(false, false, false, false, false, false,
                    true, true, REMOVE_FAILED, Step.REMOVE_WRONG_CONFIG,
                    "无法清除候选配置：" + v.describe());
        }

        // ── 审计修复：读不到列表就不许下结论 ──────────────────────────────
        // 走到这里说明 removeOk=true，即“删除那一步自认为成功”——但如果列表
        // 从头就读不到，连“删除是否成功”都无法确认。此时：
        //   · 不能判成功（写回没被回读确认）；
        //   · 不能判“没有残留”（可能还留着候选口令）；
        //   · 更不能判“无需还原”（那会把未确认的脏状态当成干净）。
        // 唯一正确的处理是：保留记录、标为可重试失败、保守标记残留风险。
        // 例外：写回命令**自带回读确认**（readd.configPresent）时，事实已经成立。
        if (!v.listReadable && !(readd != null && readd.configPresent)) {
            return new Outcome(false, false, false, false, false, false,
                    true, true, readd != null && readd.issued, UNVERIFIED, Step.VERIFY,
                    "读不到已保存网络列表，无法确认目标配置状态：" + v.describe());
        }

        // 原本就没有已保存配置（或快照失败拿不到口令）：
        // 这时“正确的结果”是目标**不在**列表里，而不是把它加回去。
        if (!e.hadPsk) {
            boolean absent = !v.listPresent;
            return new Outcome(false, absent, absent, false, false, false,
                    !absent, !absent, absent ? NOTHING_TO_DO : READD_FAILED, Step.VERIFY,
                    absent ? "原本就无需还原，候选残留已清除"
                           : "原本没有已保存配置，但目标仍在列表中：" + v.describe());
        }

        if (!e.canReadd()) {
            // 有原配置，但拿不到安全的原口令（Keystore 不可用等）。
            // 不许假装能恢复：如实报 NO-SECRET，已保证不遗留错误口令。
            boolean absent = !v.listPresent;
            return new Outcome(false, false, absent, false, false, false,
                    !absent, false, NO_SECRET, Step.READD_ORIGINAL,
                    "原口令未能安全保存，无法自动还原；"
                            + (absent ? "已清除候选残留，请手动重连该网络"
                                      : "候选残留也未能清除：" + v.describe()));
        }

        if (readd == null || !readd.issued) {
            // 重建命令没发出去：系统里可能还留着候选口令（目标还在列表里就是残留）
            // → 标出残留风险，靠 cleanup 兜底。
            return new Outcome(false, false, !v.listPresent, false, false, false,
                    v.listPresent, true, READD_FAILED, Step.READD_ORIGINAL,
                    "重建原配置的命令未能发出：" + v.describe());
        }

        // 写回是否成立：命令发出 **且** 回读看得到（readd 当时的回读或本次 verify 的列表）。
        // 两个来源任一成立即可 —— 前者可能因一次读失败而未知，后者是同一事实的再次观察。
        if (!readd.configPresent && !v.listPresent) {
            // 到这里可以确定：列表里**没有**目标，所以不存在候选残留（noWrongLeft=true）——
            // 但这仍然不是成功：用户的原配置没能写回去（可见故障，可重试一次）。
            // readdIssued=true：写回命令确实发出过（与“压根没写回”严格区分）。
            return new Outcome(false, false, true, false, false, false,
                    false, true, readd.issued, READD_NOT_WRITTEN, Step.READD_ORIGINAL,
                    "重建命令已发出，但列表中未出现目标配置（写回未成功）：" + v.describe());
        }

        // ── 写回已确认：配置层面恢复完成 ──────────────────────────────────
        // 连接与 IP 只作为**观察值**：目标不在覆盖范围内、当前连着别的网、
        // 观察窗口内没等到 —— 都不构成恢复失败。
        boolean conn = v.connectedTo(e.ssid);
        boolean ipOk = isIpValid(v.ip);
        return new Outcome(false, true, true, true, conn, ipOk,
                false, false, true, conn ? OK : WROTE_BACK, null,
                conn ? "已还原并观察到连上" : "原配置已写回并回读确认（此刻未观察到连上）");
    }

    /** 兼容旧签名（无 readd 信息 → 按“没发出”处理，最保守）。 */
    public static Outcome judge(RestoreJournal.Entry e, Verify v, boolean removeStageOk) {
        return judge(e, v, removeStageOk, ReaddResult.notIssued());
    }

    /**
     * 判定「命中保留」的结果。**纯判断，不动系统**。
     *
     * <p>唯一的成功判据：回读确认目标配置**在已保存列表里** ——
     * 那正是命中口令写进去的那条配置。读不到就不算保留成立，
     * 调用方必须退回 {@link #steps(RestoreJournal.Entry)} 的标准恢复
     * （宁可回到「删干净 + 手动重连」的可见故障，也不留一个说不清的中间态）。
     */
    public static Outcome judgeKept(RestoreJournal.Entry e, Verify v) {
        if (e == null) {
            return new Outcome(false, false, false, NO_RECORD, null,
                    "没有恢复记录，无法判断保留状态");
        }
        if (v == null) v = Verify.unknown();
        if (!v.listReadable) {
            // 审计修复：读不到列表就**不能**说“保留不成立”，更不能说“没有残留”——
            // 那两种情况都会导致后面的动作基于未确认的事实。这里保守报未确认。
            return new Outcome(false, false, false, false, false, false,
                    true, true, false, UNVERIFIED, Step.KEEP_HIT_CONFIG,
                    "读不到已保存网络列表，无法确认保留是否成立：" + v.describe());
        }
        if (!v.listPresent) {
            // 列表读到了、里面没有目标 ⇒ 没有候选残留（noWrongLeft=true）：
            // 保留不成立要退回标准恢复，但“没有残留”这一点是确定的。
            return new Outcome(false, false, true, false, false, false,
                    false, false, false, KEEP_NOT_WRITTEN, Step.KEEP_HIT_CONFIG,
                    "回读不到目标配置，保留不成立（退回标准恢复）：" + v.describe());
        }
        boolean up = v.connectedTo(e.ssid);
        boolean ipOk = isIpValid(v.ip);
        Outcome o = new Outcome(true, false, true, true, up, ipOk,
                false, false, false, KEPT, null,
                up ? "此刻正连着目标，配置留着，以后会自动重连"
                   : "目标配置在已保存列表里，系统会按策略自动连");
        return o;
    }

    /**
     * 失败之后要不要再补一次“删除残留”（清不掉就只剩可见故障，好过静默故障）。
     *
     * <p>1.9.28 起判据收紧为 {@link Outcome#residualRisk}：
     * 只有“可能还留着候选口令”才值得再删一次。已处置（还原 / 命中保留 /
     * 写回确认但没连上）一律不再补删 —— 否则会把刚写回的正确配置删掉。
     */
    public static boolean needsCleanupRetry(Outcome o) {
        return o != null && o.residualRisk;
    }
}

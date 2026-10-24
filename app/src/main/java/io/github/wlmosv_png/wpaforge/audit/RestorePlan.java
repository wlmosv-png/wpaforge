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
 * <h3>两条硬约束</h3>
 * <ul>
 *   <li><b>不许遗留错误口令</b>：只要不能确认“已还原成原配置”，
 *       就必须把目标从已保存列表里删干净。删掉是**可见**的故障（用户会重连），
 *       留着错误口令是**静默**的故障（用户以为存好了，其实连不上）。</li>
 *   <li><b>不许伪报成功</b>：没有读回验证就不算恢复。
 *       {@code cmd wifi connect-network} 是**哑命令**（成功失败都无输出、退出码都是 0），
 *       所以唯一可信的判据是“读回状态确实连上了目标”。</li>
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
        VERIFY
    }

    /** 结果码（机器可读，进日志与队列）。 */
    public static final String OK = "OK";
    public static final String NO_RECORD = "NO-RECORD";
    public static final String NO_SECRET = "NO-SECRET";
    public static final String REMOVE_FAILED = "REMOVE-FAILED";
    public static final String VERIFY_FAILED = "VERIFY-FAILED";
    public static final String READD_FAILED = "READD-FAILED";
    /**
     * 重建命令发出了，但**回读已保存列表里没有目标** —— 写回没成功。
     *
     * <p>与 {@link #VERIFY_FAILED} 严格区分：后者是「配置写回去了、只是没连上」，
     * 前者是「配置根本没写回去」。把前者当后者报，会把「配置丢了」说成「网络没连上」。
     */
    public static final String READD_NOT_WRITTEN = "READD-NOT-WRITTEN";

    /**
     * 重建原配置这一步的结果（F5）。
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

    /**
     * 需要执行的步骤。
     *
     * @param e 记录；{@code null} 或已恢复 → 空列表
     */
    public static List<Step> steps(RestoreJournal.Entry e) {
        if (e == null || e.restored()) return Collections.emptyList();
        List<Step> out = new ArrayList<>(3);
        // 第一步永远是删 —— 无论后面能不能加回去，
        // “错误候选口令不许留在系统里”这条都必须先满足。
        out.add(Step.REMOVE_WRONG_CONFIG);
        if (e.canReadd()) out.add(Step.READD_ORIGINAL);
        out.add(Step.VERIFY);
        return out;
    }

    /** 读回验证的输入快照（由 shell 侧采集，纯数据）。 */
    public static final class Verify {
        /** 目标是否**仍**在已保存网络列表里。 */
        public final boolean listPresent;
        /** {@code cmd wifi status} 读到的 SSID（可为 null）。 */
        public final String statusSsid;
        /** supplicant 状态（可为 null）。 */
        public final String supplicantState;
        /** 读到的 IP（可为 null）。 */
        public final String ip;

        public Verify(boolean listPresent, String statusSsid,
                      String supplicantState, String ip) {
            this.listPresent = listPresent;
            this.statusSsid = statusSsid;
            this.supplicantState = supplicantState;
            this.ip = ip;
        }

        /** 采不到（通道断、命令超时）→ 全部未知，一律按“没验证”处理。 */
        public static Verify unknown() { return new Verify(false, null, null, null); }

        /** 是否读到了可用信息（读不到就不能判成功）。 */
        public boolean readable() { return statusSsid != null || listPresent; }

        /** 目标此刻是否是“已连上”的状态。 */
        public boolean connectedTo(String ssid) {
            if (ssid == null || ssid.isEmpty()) return false;
            return ssid.equals(statusSsid)
                    && supplicantState != null
                    && "COMPLETED".equalsIgnoreCase(supplicantState)
                    && isIpValid(ip);
        }

        public String describe() {
            return "list=" + (listPresent ? "yes" : "no")
                    + " ssid=" + (statusSsid == null ? "<null>" : statusSsid)
                    + " state=" + (supplicantState == null ? "<null>" : supplicantState)
                    + " ip=" + (ip == null ? "<null>" : ip);
        }
    }

    /** 恢复结论。 */
    public static final class Outcome {
        /** 原配置是否已还原**并读回验证**。 */
        public final boolean restored;
        /** 是否已确认“系统里没有留下错误候选口令”。 */
        public final boolean noWrongLeft;
        public final String code;
        public final Step failedAt;
        public final String detail;

        Outcome(boolean restored, boolean noWrongLeft, String code, Step failedAt, String detail) {
            this.restored = restored;
            this.noWrongLeft = noWrongLeft;
            this.code = code;
            this.failedAt = failedAt;
            this.detail = detail == null ? "" : detail;
        }

        /** 能不能对用户说“你的网络配置已经恢复”。 */
        public boolean acceptable() { return restored; }

        /** 供日志/界面用的一行结论。 */
        public String summary() {
            if (restored) return "已恢复：" + code;
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
     * @param e 记录；{@code null} → {@link #NO_RECORD}
     * @param v 读回快照
     * @param removeStageOk {@link Step#REMOVE_WRONG_CONFIG} 这一步是否执行成功
     * @param readd {@link Step#READD_ORIGINAL} 这一步的结果（命令是否发出 + 是否回读可见）
     */
    public static Outcome judge(RestoreJournal.Entry e, Verify v,
                                boolean removeStageOk, ReaddResult readd) {
        if (e == null) {
            return new Outcome(false, false, NO_RECORD, null, "没有恢复记录，无法判断原配置状态");
        }
        if (v == null) v = Verify.unknown();

        if (!removeStageOk) {
            // 连“删掉错误候选口令”都没做到 —— 这是最坏的一种：既没还原、又留了错口令。
            return new Outcome(false, false, REMOVE_FAILED, Step.REMOVE_WRONG_CONFIG,
                    "无法清除候选配置：" + v.describe());
        }

        // 原本就没有已保存配置（或快照失败拿不到口令）：
        // 这时“正确的结果”是目标**不在**列表里，而不是把它加回去。
        if (!e.hadPsk) {
            boolean absent = !v.listPresent;
            return new Outcome(absent, absent, absent ? OK : READD_FAILED, Step.VERIFY,
                    absent ? "原本就无需还原，候选残留已清除"
                           : "原本没有已保存配置，但目标仍在列表中：" + v.describe());
        }

        if (!e.canReadd()) {
            // 有原配置，但拿不到安全的原口令（Keystore 不可用等）。
            // 不许假装能恢复：如实报 NO-SECRET，但已保证不遗留错误口令。
            boolean absent = !v.listPresent;
            return new Outcome(false, absent, NO_SECRET, Step.READD_ORIGINAL,
                    "原口令未能安全保存，无法自动还原；"
                            + (absent ? "已清除候选残留，请手动重连该网络"
                                      : "候选残留也未能清除：" + v.describe()));
        }

        if (readd == null || !readd.issued) {
            // 重建命令没发出去：系统里可能还留着候选口令 → 靠 cleanup 兜底。
            return new Outcome(false, !v.listPresent, READD_FAILED, Step.READD_ORIGINAL,
                    "重建原配置的命令未能发出：" + v.describe());
        }
        // 写回是否成立：命令发出 **且** 回读看得到（readd 当时的回读或本次 verify 的列表）。
        // 两个来源任一成立即可 —— 前者可能因一次读失败而未知，后者是同一事实的再次观察。
        if (!readd.configPresent && !v.listPresent) {
            return new Outcome(false, !v.listPresent, READD_NOT_WRITTEN, Step.READD_ORIGINAL,
                    "重建命令已发出，但列表中未出现目标配置（写回未成功）：" + v.describe());
        }

        // 已经把**原口令**写回系统：这一步起“系统里的口令”已经不是候选口令了。
        // 但“恢复成功”仍要求读回确认 —— 哑命令没有回执，只能看状态。
        if (v.connectedTo(e.ssid)) {
            return new Outcome(true, true, OK, null, "已还原并读回验证通过");
        }
        return new Outcome(false, true, VERIFY_FAILED, Step.VERIFY,
                "原配置已写回但未观察到连上：" + v.describe());
    }

    /** 兼容旧签名（无 readd 信息 → 按“没发出”处理，最保守）。 */
    public static Outcome judge(RestoreJournal.Entry e, Verify v, boolean removeStageOk) {
        return judge(e, v, removeStageOk, ReaddResult.notIssued());
    }

    /** 失败之后要不要再补一次“删除残留”（清不掉就只剩可见故障，好过静默故障）。 */
    public static boolean needsCleanupRetry(Outcome o) {
        return o != null && !o.restored && !o.noWrongLeft;
    }
}

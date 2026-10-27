package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.wlmosv_png.wpaforge.L;

/**
 * 恢复记录的**管理门面**（界面用）：查看、重试、备份并清理。
 *
 * <h3>为什么单独一个类</h3>
 * 恢复链的判定全是纯逻辑（{@link RestorePlan} / {@link RestoreJournal} /
 * {@link RestoreRunner}），但它们不碰 Android 的存储与界面。
 * 界面需要的三件事 —— 列出现状、同步重试一次、备份后清理 ——
 * 集中在这里，界面只调这三个入口，避免把 shell 逻辑写进 Activity。
 *
 * <h3>清理的安全纪律</h3>
 * <ul>
 *   <li>清理**前**先做只读安全检查（{@link ConfigRestorer#inspect}），
 *       把目标当前是否还在已保存列表里、是否正连着，如实告诉用户；</li>
 *   <li>清理**前**先备份 {@code restore.json}（带时间戳），可回溯；</li>
 *   <li>清理只删“恢复记录”，**不动系统里的网络配置** ——
 *       因此它**不等于**“恢复原 Wi-Fi 配置”，界面文案必须说清这一点。</li>
 * </ul>
 */
public final class RestoreAdmin {

    private RestoreAdmin() {}

    /** 一行现状（界面直接渲染）。 */
    public static final class Row {
        public final RestoreJournal.Entry entry;
        /** 还能自动重试几次。 */
        public final int remaining;
        /** 阶段标签（人读）。 */
        public final String stageLabel;
        /** 最近一次尝试时间（人读）。 */
        public final String lastAttemptText;
        /** 这条记录是否仍需人工处置（而不是只等重试）。 */
        public final boolean needsHuman;

        Row(RestoreJournal.Entry e) {
            this.entry = e;
            this.remaining = e.remainingAttempts();
            this.stageLabel = e.stageLabel();
            this.lastAttemptText = e.lastAttemptAt <= 0 ? L.s("未尝试过", "never tried")
                    : new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                        .format(new Date(e.lastAttemptAt));
            this.needsHuman = RestoreJournal.STAGE_ARCHIVED.equals(e.stage) || e.broken()
                    || (e.residualRisk && this.remaining <= 0);
        }

        /** 一行摘要：目标 · 阶段 · 重试。 */
        public String line() {
            StringBuilder sb = new StringBuilder();
            sb.append(entry.ssid == null || entry.ssid.isEmpty()
                    ? L.s("(未知目标)", "(unknown target)") : entry.ssid);
            sb.append(" · ").append(stageLabel);
            sb.append(" · ").append(L.s("已试 ", "tried ")).append(entry.attempts)
                    .append("/").append(RestoreJournal.MAX_ATTEMPTS);
            if (remaining > 0) {
                sb.append(L.s("，还可重试 ", ", ")).append(remaining).append(L.s(" 次", " left"));
            }
            return sb.toString();
        }

        /** 失败原因（可为空）。 */
        public String reason() {
            if (entry.failDetail != null && !entry.failDetail.isEmpty()) return entry.failDetail;
            if (RestoreJournal.STAGE_ARMED.equals(entry.stage)) {
                return L.s("等待收尾（进程被杀时会在这里停下）",
                        "waiting for settle (process was killed)");
            }
            return "";
        }
    }

    /** 列出全部记录（含已归档，便于追溯）。 */
    public static List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (RestoreJournal.Entry e : RestoreJournal.loadAll()) {
            if (e == null) continue;
            out.add(new Row(e));
        }
        return out;
    }

    /** 当前需要处理的条数（0 = 一切正常）。 */
    public static int pendingCount() {
        return RestoreJournal.pendingCount();
    }

    /** 空态/汇总文案。 */
    public static String summary() {
        List<RestoreJournal.Entry> all = RestoreJournal.loadAll();
        if (all.isEmpty()) {
            return L.s("没有待处理的恢复记录", "no pending restore records");
        }
        int pending = 0;
        for (RestoreJournal.Entry e : all) if (e.needsRetry() || e.blocking()) pending++;
        if (pending == 0) {
            return L.f("有 %d 条历史记录（已归档，不影响新任务）",
                    "%d archived record(s), they do not block new tasks", all.size());
        }
        return L.f("有 %d 条待处理记录", "%d pending record(s)", pending);
    }

    /** 只读安全检查（清理前必须调用）。 */
    public static ConfigRestorer.Safety safety(RestoreJournal.Entry e) {
        return ConfigRestorer.inspect(e == null ? "" : e.ssid);
    }

    /** 重试结果。 */
    public static final class RetryResult {
        public final boolean ok;
        public final String message;
        public final RestorePlan.Outcome outcome;

        RetryResult(boolean ok, String message, RestorePlan.Outcome o) {
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.outcome = o;
        }
    }

    /**
     * 手动重试一条记录（**同步**，会执行 shell；界面请放到后台线程）。
     *
     * <p>走的是与任务收尾同一条通道（{@link RestoreRunner} 的全局锁），
     * 因此不会与启动补做并发操作同一份配置。
     */
    public static RetryResult retryNow(RestoreJournal.Entry e) {
        if (e == null) {
            return new RetryResult(false, L.s("没有可重试的记录", "nothing to retry"), null);
        }
        if (e.broken()) {
            return new RetryResult(false,
                    L.s("这条记录无法解析（损坏或版本不兼容），没有可重试的目标；"
                            + "请用「清理记录」处理",
                        "This record cannot be parsed; use Discard instead"), null);
        }
        // 归属校验只在有 taskId 时才有意义：旧格式记录（taskId 为空）直接走恢复，
        // 否则用户点了「重试」却永远得到“跳过”。
        RestorePlan.Outcome o = (e.taskId == null || e.taskId.isEmpty())
                ? ConfigRestorer.restore(e, false)
                : RestoreRunner.runExclusiveForTask(e, e.taskId,
                        cur -> ConfigRestorer.restore(cur, false));
        if (o == null) {
            return new RetryResult(false,
                    L.s("同目标还有另一条未处理的恢复记录，本次未执行",
                        "another unresolved record for the same target; skipped"), null);
        }
        return new RetryResult(o.resolved(), o.summary(), o);
    }

    /** 清理结果。 */
    public static final class DiscardResult {
        public final boolean removed;
        public final String backupPath;
        public final String message;

        DiscardResult(boolean removed, String backupPath, String message) {
            this.removed = removed;
            this.backupPath = backupPath;
            this.message = message == null ? "" : message;
        }
    }

    /**
     * 备份并清理一条记录。
     *
     * <p>**不是**“恢复原 Wi-Fi 配置”：它只删掉本地这条恢复记录，
     * 系统里的网络配置原样不动。返回值里的 message 已经把这件事写给用户看。
     */
    public static DiscardResult discard(Context ctx, RestoreJournal.Entry e) {
        if (e == null) {
            return new DiscardResult(false, null, L.s("没有可清理的记录", "nothing to discard"));
        }
        ConfigRestorer.Safety safe = safety(e);
        String backup = RestoreStoreFile.backup(ctx);
        e.abandoned = true;
        // 损坏记录没有可用的 ssid/taskId，按 key 删不掉 → 走“剔除损坏条目”那条路。
        boolean removed = e.broken()
                ? RestoreJournal.removeBroken()
                : RestoreRunner.removeEntry(e);
        if (!removed) {
            // 删不掉就退一步：写成归档态（不再阻塞、不再自动重试），
            // 至少不会留下一个永远“待处理”的坑。
            e.stage = RestoreJournal.STAGE_ARCHIVED;
            e.abandoned = true;
            RestoreJournal.save(e);
        }
        StringBuilder msg = new StringBuilder();
        msg.append(removed ? L.s("已清理记录", "record discarded")
                           : L.s("未能删除记录，已归档（不再自动重试）",
                                 "could not delete; archived instead"));
        if (backup != null) {
            msg.append(L.s("；备份：", "; backup: ")).append(backup);
        } else {
            msg.append(L.s("；没有可备份的内容", "; nothing to back up"));
        }
        msg.append(L.s("。注意：清理记录 ≠ 恢复原 Wi-Fi 配置 —— ", 
                ". Note: discarding a record does NOT restore your Wi-Fi config - "))
           .append(safe.describe());
        if (safe.configPresent) {
            msg.append(L.s("；如需还原，请用「重试恢复」或手动重连该网络",
                    "; use Retry, or reconnect manually"));
        }
        return new DiscardResult(removed, backup, msg.toString());
    }

    /** 备份当前记录文件（不清理）。 */
    public static String backupNow(Context ctx) {
        String p = RestoreStoreFile.backup(ctx);
        return p == null ? L.s("没有可备份的记录文件", "no record file to back up") : p;
    }
}

package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import io.github.wlmosv_png.wpaforge.L;

/**
 * 配置恢复的**唯一执行通道**。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>把「启动补做」与「任务收尾恢复」串行化 —— 同一时刻只有一条恢复流程
 *       能动系统里的网络配置与 journal；</li>
 *   <li>决定**新任务能不能开**（{@link #checkNewTask}）：这是 1.9.28 重写的重点；</li>
 *   <li>把结论写回记录（{@link #applyStage}）：终态清理、失败留痕、到顶归档。</li>
 * </ol>
 *
 * <h3>1.9.28 根因修复：从「一票否决」到「按风险定向拦截」</h3>
 * 1.9.27 的判据是 {@code otherTaskPending = 盘上存在不属于本任务的未完成记录},
 * 而“未完成”= 不是 {@code RESTORED}/{@code KEPT}。于是：
 * <ul>
 *   <li>一条 {@code FAILED} 记录（典型成因：原配置已写回，但目标不在覆盖范围内、
 *       连接观察永远不成立）会**永久**挡掉所有新任务；</li>
 *   <li>每次启动还会再补做一次、再失败一次 —— 自锁循环。</li>
 * </ul>
 *
 * <p>现在记录按 {@code taskId} 分槽（见 {@link RestoreJournal}），新任务写自己的
 * 槽、不覆盖别人的，于是“跨任务必须一票否决”这个前提本身就不成立了。剩下的、
 * **真正**需要拦住新任务的只有三种情况：
 * <ol>
 *   <li><b>同目标仍有未解决风险</b>：同一 SSID 已有一条未收尾/有残留风险的记录
 *       —— 这时新任务快照到的“原配置”可能已经被污染，必须先把那条处理掉；</li>
 *   <li><b>盘上记录读不出来</b>：内容损坏 / 更高版本写的 → 未知，fail closed；</li>
 *   <li><b>待处理记录堆积到上限</b>：说明长期无人处置，要求人工清理，
 *       避免无限累积（{@link RestoreJournal#MAX_PENDING}）。</li>
 * </ol>
 * 这三种之外一律放行 —— 新任务的破坏性操作**只**影响它自己的目标。
 */
public final class RestoreRunner {

    private static final String TAG = "WPAForgeRestore";

    /** 一次恢复要做的事；拿到的是**锁内读到的最新记录**。 */
    public interface Job {
        RestorePlan.Outcome run(RestoreJournal.Entry current);
    }

    /** 一把锁管住所有恢复动作。fair=true 让排队顺序可预期（便于测试断言）。 */
    private static final ReentrantLock LOCK = new ReentrantLock(true);
    /** 单飞：同一时间最多一个补做线程在跑/排队。 */
    private static final AtomicBoolean QUEUED = new AtomicBoolean(false);

    private static volatile String lastSummary = "未执行";
    private static volatile String lastWhere = "";
    private static volatile String lastBlockReason = "";

    private RestoreRunner() {}

    // ---------------------------------------------------------------- 纯判断

    /**
     * 这条记录**还需不需要动手**（待收尾 / 可重试失败 → 需要）。
     *
     * <p>注意与 {@link #blocksTask} 的区别：这里回答“有没有待办”，
     * 不回答“要不要挡住别人”。
     */
    public static boolean shouldRecover(RestoreJournal.Entry e) {
        return e != null && e.needsRetry();
    }

    /** 这条记录是不是**指定任务**的。taskId 缺失的历史记录一律判否（不猜）。 */
    public static boolean forTask(RestoreJournal.Entry e, String taskId) {
        if (e == null || taskId == null || taskId.isEmpty()) return false;
        return taskId.equals(e.taskId);
    }

    /**
     * 盘上那条记录会不会挡住**指定任务**。
     *
     * <p>1.9.28 起判据是「同目标 + 未解决风险」，不再看记录属于谁：
     * 别人的失败记录与本次目标无关，不构成理由去禁止一次只作用于本目标的破坏性操作。
     *
     * @deprecated 用 {@link #checkNewTask(String, String)}（含损坏/堆积判定）。
     */
    @Deprecated
    public static boolean otherTaskPending(RestoreJournal.Entry e, String taskId) {
        if (e == null) return false;
        if (forTask(e, taskId)) return false;
        return e.blocking();
    }

    /** 同目标 + 未解决风险 → 必须先把那条记录处理掉。 */
    public static boolean blocksTask(RestoreJournal.Entry e, String taskId, String ssid) {
        if (e == null) return false;
        if (forTask(e, taskId)) return false;      // 自己的记录：正常流程
        if (e.broken()) return true;               // 未知记录：fail closed
        return e.hasUnresolvedRisk() && e.sameTarget(ssid);
    }

    /** {@link #checkNewTask} 的结论。 */
    public static final class Block {
        public final boolean blocked;
        public final RestoreGuard.Reason reason;
        public final String detail;
        public final RestoreJournal.Entry entry;
        /** 不阻塞时的提示（仍然要告诉用户盘上还有别的待办）。 */
        public final String note;

        Block(boolean blocked, RestoreGuard.Reason reason, String detail,
              RestoreJournal.Entry entry, String note) {
            this.blocked = blocked;
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
            this.entry = entry;
            this.note = note == null ? "" : note;
        }
    }

    /**
     * 决定这一次新任务能不能进入破坏性操作。
     *
     * <p>三种拦法（顺序即优先级）：
     * <ol>
     *   <li>盘上有内容但解析不出来 → {@code JOURNAL_UNREADABLE}（fail closed）；</li>
     *   <li>同一目标已有未解决风险的记录 → {@code OTHER_PENDING}；</li>
     *   <li>待处理记录堆积超过上限 → {@code TOO_MANY_PENDING}。</li>
     * </ol>
     * 其余情况放行；若盘上还有**别的目标**的待办，会在 {@link Block#note} 里说明。
     */
    public static Block checkNewTask(String taskId, String ssid) {
        List<RestoreJournal.Entry> all = RestoreJournal.loadAll();
        if (RestoreJournal.pendingUnknown()) {
            return new Block(true, RestoreGuard.Reason.JOURNAL_UNREADABLE,
                    "盘上的恢复记录无法解析（内容损坏或来自更高版本）："
                            + "无法确认是否还有别的目标未收尾，按未知处理",
                    null, "");
        }
        List<RestoreJournal.Entry> pending = new ArrayList<>();
        for (RestoreJournal.Entry e : all) {
            if (e == null) continue;
            if (e.needsRetry() || e.hasUnresolvedRisk()) pending.add(e);
        }
        for (RestoreJournal.Entry e : pending) {
            if (blocksTask(e, taskId, ssid)) {
                return new Block(true, RestoreGuard.Reason.OTHER_PENDING,
                        "目标「" + e.ssid + "」已有一条未处理的配置恢复记录"
                                + "（task=" + (e.taskId == null || e.taskId.isEmpty() ? "?" : e.taskId)
                                + "，阶段=" + e.stageLabel()
                                + "，已试 " + e.attempts + "/" + RestoreJournal.MAX_ATTEMPTS
                                + "）：先处理它再跑同一目标，否则本次可能把候选口令当成原配置",
                        e, "");
            }
        }
        if (pending.size() >= RestoreJournal.MAX_PENDING) {
            return new Block(true, RestoreGuard.Reason.TOO_MANY_PENDING,
                    "盘上堆积了 " + pending.size() + " 条未处理的恢复记录（上限 "
                            + RestoreJournal.MAX_PENDING + "）：请在恢复状态区清理后再开新任务",
                    pending.get(0), "");
        }
        String note = "";
        if (!pending.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (RestoreJournal.Entry e : pending) {
                if (sb.length() > 0) sb.append("、");
                sb.append(e.ssid == null || e.ssid.isEmpty() ? "(未知目标)" : e.ssid);
            }
            note = "盘上另有 " + pending.size() + " 条待处理恢复记录（" + sb
                    + "），不影响本次目标；可在恢复状态区处理";
        }
        lastBlockReason = "";
        return new Block(false, null, "", null, note);
    }

    /** 最近一次被拦的原因（供日志与界面显示）。 */
    public static String lastBlockReason() { return lastBlockReason; }

    /**
     * 被闸门挡下后的**人读**提示：为什么停、到哪处理、要不要人工。
     *
     * <p>P1 要求：不得把定向拦截伪装成普通暂停 —— 所以这条提示必须说清
     * “是等恢复、可重试，还是需要人工处理”。
     */
    public static String blockHelp(RestoreGuard.Decision d) {
        if (d == null || d.proceed) return "";
        RestoreGuard.Reason r = d.reason;
        if (r == null) return "";
        switch (r) {
            case OTHER_PENDING:
                return L.s("破解页顶部的「恢复状态」里有这个目标的一条未处理记录："
                                + "点「重试恢复」或「清理记录」后再继续（进度已保留）",
                        "The Restore section has an unresolved record for this target - "
                                + "retry or discard it, then resume (progress is kept)");
            case JOURNAL_UNREADABLE:
                return L.s("盘上的恢复记录无法解析：到破解页「恢复状态」备份并清理后再继续",
                        "The restore file is unreadable - back it up and clear it "
                                + "in the Restore section, then resume");
            case TOO_MANY_PENDING:
                return L.s("待处理的恢复记录过多：到破解页「恢复状态」逐条处理后再继续",
                        "Too many pending restore records - handle them in the Restore section");
            case CHANNEL_UNREADABLE:
                return L.s("读不到已保存网络列表：确认 root 通道可用后重试即可",
                        "Cannot read the saved-network list - check the root channel and retry");
            case CONFIG_UNREADABLE:
                return L.s("读不到该目标的 WiFi 配置（拿不到原口令）：本次不跑，先确认 root 通道",
                        "Cannot read the target's WiFi config (no original passphrase)");
            case PSK_UNREADABLE:
                return L.s("目标是加密网络但读不到原口令：本次不跑（跑完无法还原）",
                        "Encrypted target but no passphrase readable - refusing to run");
            case SEAL_FAILED:
                return L.s("原口令加密失败（Keystore 不可用）：不允许落明文，本次不跑",
                        "Passphrase sealing failed - refusing to run");
            default:
                return L.s("恢复记录写不进盘：本次不跑（进程被杀后无法补做）",
                        "Restore record not writable - refusing to run");
        }
    }

    /** 当前是否有恢复在跑。 */
    public static boolean busy() {
        return QUEUED.get() || LOCK.isLocked();
    }

    /** 最近一次恢复的结论（供日志/界面读取）。 */
    public static String lastSummary() { return lastSummary; }

    /** 最近一次恢复的触发方（app-start / service-start / task-end / manual）。 */
    public static String lastWhere() { return lastWhere; }

    // ---------------------------------------------------------------- 串行执行

    /**
     * 收尾恢复：只处理**当前任务自己的内存记录**。
     *
     * @param mine   当前任务的内存记录（权威来源）
     * @param taskId 当前任务 id
     * @return 结论；记录不属于本任务 / 未写盘时返回 {@code null}
     */
    public static RestorePlan.Outcome runExclusiveForTask(RestoreJournal.Entry mine,
                                                          String taskId, Job job) {
        if (mine == null || !forTask(mine, taskId) || job == null) return null;
        LOCK.lock();
        try {
            // 锁内再看一眼盘：若盘上那条**同目标**的记录不属本任务且仍有风险，
            // 就不要动目标配置 —— 那可能是另一次未完成的恢复，动了会互相破坏。
            RestoreJournal.Entry disk = RestoreJournal.load();
            if (disk != null && !forTask(disk, taskId)
                    && disk.hasUnresolvedRisk() && disk.sameTarget(mine.ssid)) {
                lastSummary = "同目标存在另一条未解决的恢复记录，本次不做收尾恢复";
                lastWhere = "task-end-skipped";
                return null;
            }
            RestorePlan.Outcome o = job.run(mine);
            applyStage(mine, o);
            lastWhere = "task-end";
            lastSummary = o == null ? "无结论" : o.summary();
            return o;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 启动补做（异步、单飞）。
     *
     * <h3>1.9.28 的三条纪律</h3>
     * <ol>
     *   <li><b>一次只补一条</b>：避免同时动多个目标的配置；</li>
     *   <li><b>冷却</b>：距上次尝试不足 {@link RestoreJournal#RETRY_COOLDOWN_MS} 就跳过
     *       —— 反复启动应用不会把同一条记录刷成死循环；</li>
     *   <li><b>到顶即归档</b>：{@code attempts >= MAX_ATTEMPTS} 时不再尝试，
     *       直接写终态 {@code ARCHIVED}，把“永远失败”变成“明确放弃 + 可人工处理”。</li>
     * </ol>
     *
     * @param caller 触发方标识（只进日志）
     */
    public static void requestStartupRecovery(String caller) {
        final String where = caller == null ? "unknown" : caller;
        if (!QUEUED.compareAndSet(false, true)) {
            lastSummary = "已有恢复在进行/排队，跳过（来自 " + where + "）";
            lastWhere = where + "-skipped";
            return;
        }
        Thread t = new Thread(() -> {
            try {
                LOCK.lock();
                try {
                    long now = System.currentTimeMillis();
                    List<RestoreJournal.Entry> all = RestoreJournal.loadAll();
                    RestoreJournal.Entry target = null;
                    int waiting = 0;
                    for (RestoreJournal.Entry e : all) {
                        if (e == null || !e.needsRetry()) continue;
                        if (e.attempts >= RestoreJournal.MAX_ATTEMPTS) {
                            archive(e, "自动重试已达上限 " + RestoreJournal.MAX_ATTEMPTS + " 次");
                            continue;
                        }
                        if (!e.cooldownPassed(now)) { waiting++; continue; }
                        target = e;
                        break;
                    }
                    if (target == null) {
                        lastWhere = where;
                        lastSummary = waiting > 0
                                ? ("有待办但仍在冷却期（" + waiting + " 条），本次不重试")
                                : "无待办";
                        return;
                    }
                    RestorePlan.Outcome o = ConfigRestorer.restore(target, false);
                    applyStage(target, o);
                    lastWhere = where;
                    lastSummary = o == null ? "无结论" : o.summary();
                    android.util.Log.i(TAG, "recovery(" + where + "): " + lastSummary);
                } finally {
                    LOCK.unlock();
                }
            } catch (Throwable t2) {
                lastWhere = where;
                lastSummary = "补做失败：" + t2.getClass().getSimpleName();
                android.util.Log.w(TAG, "recovery failed(" + where + "): " + t2);
            } finally {
                QUEUED.set(false);
            }
        }, "wpaforge-restore");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把结论写回记录（1.9.28 的生命周期）。
     *
     * <ul>
     *   <li><b>成功</b>（写回确认 / 命中保留 / 无需还原）→ 终态：能删就删记录，
     *       删不掉就写终态落盘（绝不留 {@code ARMED}，否则下次补做会把正确配置删掉）；</li>
     *   <li><b>可重试失败</b>且未到上限 → {@code FAILED}（留痕、可重试）；</li>
     *   <li><b>不可重试 / 到上限</b> → {@code ARCHIVED}（终态：不再自动重试，
     *       也不再阻塞跨目标任务）。</li>
     * </ul>
     *
     * <p>每次调用都会记录 {@code attempts} 与 {@code lastAttemptAt}，
     * 观察值（连上没、有没有 IP）一并落盘，便于事后追问。
     */
    public static void applyStage(RestoreJournal.Entry e, RestorePlan.Outcome o) {
        if (e == null || o == null) return;
        long now = System.currentTimeMillis();
        e.finishedAt = now;
        e.writeBackConfirmed = o.writeBackConfirmed;
        e.connObserved = o.connObserved;
        e.ipObserved = o.ipObserved;
        e.residualRisk = o.residualRisk;
        e.noteAttempt(now);
        if (o.resolved()) {
            e.stage = o.kept ? RestoreJournal.STAGE_KEPT
                    : (RestorePlan.NOTHING_TO_DO.equals(o.code)
                        ? RestoreJournal.STAGE_RESOLVED : RestoreJournal.STAGE_RESTORED);
            e.failStep = "";
            e.failDetail = "";
            // 清记录失败时退而求其次：把它写成**终态**再落盘，
            // 否则盘上留着的还是 ARMED，下次启动的补做会把刚写回的正确配置删掉。
            if (!removeEntry(e)) RestoreJournal.save(e);
        } else if (!o.retryable || e.attempts >= RestoreJournal.MAX_ATTEMPTS) {
            e.failStep = o.failedAt == null ? "" : o.failedAt.name();
            e.failDetail = o.code + " · " + o.detail
                    + " · 已归档（不再自动重试；残留风险："
                    + (o.residualRisk ? "可能有" : "无") + "）";
            e.stage = RestoreJournal.STAGE_ARCHIVED;
            RestoreJournal.save(e);
        } else {
            e.stage = RestoreJournal.STAGE_FAILED;
            e.failStep = o.failedAt == null ? "" : o.failedAt.name();
            e.failDetail = o.code + " · " + o.detail
                    + "（还可自动重试 " + e.remainingAttempts() + " 次）";
            RestoreJournal.save(e);
        }
    }

    /** 归档一条记录（不再自动重试），并落盘。 */
    public static void archive(RestoreJournal.Entry e, String why) {
        if (e == null) return;
        e.stage = RestoreJournal.STAGE_ARCHIVED;
        e.failDetail = (e.failDetail == null ? "" : e.failDetail)
                + " · " + (why == null ? "已归档" : why);
        RestoreJournal.save(e);
    }

    /** 删掉一条记录（taskId 优先，其次 ssid）。 */
    static boolean removeEntry(RestoreJournal.Entry e) {
        if (e == null) return false;
        String key = (e.taskId != null && !e.taskId.isEmpty()) ? e.taskId : e.ssid;
        return RestoreJournal.remove(key);
    }
}

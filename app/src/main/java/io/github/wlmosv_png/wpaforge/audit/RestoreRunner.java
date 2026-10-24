package io.github.wlmosv_png.wpaforge.audit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 配置恢复的**唯一执行通道**（F1 / F2）。
 *
 * <h3>要解决的三个问题</h3>
 * <ol>
 *   <li><b>补做入口太窄</b>：{@code recoverRestore()} 原来只在
 *       {@code CrackService.onCreate} 里调用。用户只打开 App 不再起服务，
 *       未完成的恢复就**永远不会补做**。</li>
 *   <li><b>可能并发操作同一份 journal</b>：启动补做与任务收尾恢复
 *       分别在两条线程上，都能改同一条记录、都能动同一个目标配置。</li>
 *   <li><b>阻塞主线程</b>：恢复里有最多十几秒的 shell 轮询，
 *       放在 {@code Service.onCreate} 里会 ANR。</li>
 * </ol>
 *
 * <h3>做法</h3>
 * <ul>
 *   <li><b>一把全局可重入锁</b>（fair）：不论是启动补做还是任务收尾，
 *       进 {@link #runExclusive} 之前都必须拿到它 —— 同一时刻只有一条恢复流程
 *       能读/写 journal、能执行删除与重建。</li>
 *   <li><b>单飞标记</b>：{@link #requestStartupRecovery} 用 CAS 保证同一时间
 *       最多一个排队中的补做线程（重复启动不会叠出一堆线程）。</li>
 *   <li><b>异步执行</b>：补做一律在工作线程上跑，调用方（Application /
 *       Service 的 {@code onCreate}）立即返回。</li>
 *   <li><b>任务归属校验</b>：收尾恢复只认**当前任务自己的记录**；
 *       盘上那条若不属本任务，就地放弃（留给启动补做），
 *       —— 这正是「拿旧 FAILED 记录去恢复另一个目标」的封堵点。</li>
 * </ul>
 *
 * <p>本类里凡是纯判断（{@link #shouldRecover}、{@link #forTask}、
 * {@link #otherTaskPending}）都不依赖 Android，可离线断言。
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

    private RestoreRunner() {}

    // ---------------------------------------------------------------- 纯判断

    /** 这条记录是否需要动手（不存在 / 已恢复 → 不需要）。 */
    public static boolean shouldRecover(RestoreJournal.Entry e) {
        return e != null && !e.restored();
    }

    /** 这条记录是不是**指定任务**的。taskId 缺失的历史记录一律判否（不猜）。 */
    public static boolean forTask(RestoreJournal.Entry e, String taskId) {
        if (e == null || taskId == null || taskId.isEmpty()) return false;
        return taskId.equals(e.taskId);
    }

    /** 是否需要挡住新任务：盘上有记录，且**不属于**指定任务。 */
    public static boolean otherTaskPending(RestoreJournal.Entry e, String taskId) {
        return shouldRecover(e) && !forTask(e, taskId);
    }

    /** 当前是否有恢复在跑。 */
    public static boolean busy() {
        return QUEUED.get() || LOCK.isLocked();
    }

    /** 最近一次恢复的结论（供日志/界面读取）。 */
    public static String lastSummary() { return lastSummary; }

    /** 最近一次恢复的触发方（app-start / service-start / task-end）。 */
    public static String lastWhere() { return lastWhere; }

    // ---------------------------------------------------------------- 串行执行

    /**
     * 收尾恢复：只处理**当前任务自己的内存记录**。
     *
     * @param mine   当前任务的内存记录（权威来源）
     * @param taskId 当前任务 id
     * @return 结论；不进锁（记录不属于本任务 / 盘上记录属于别人）时返回 {@code null}
     */
    public static RestorePlan.Outcome runExclusiveForTask(RestoreJournal.Entry mine,
                                                          String taskId, Job job) {
        if (mine == null || !forTask(mine, taskId) || job == null) return null;
        LOCK.lock();
        try {
            // 锁内再看一眼盘：若盘上那条不属于本任务，就**不要动目标配置** ——
            // 它可能是另一个目标的未完成恢复，动了就会用错的口令覆盖。
            RestoreJournal.Entry disk = RestoreJournal.load();
            if (disk != null && !forTask(disk, taskId)) {
                lastSummary = "盘上记录属于另一个任务，本次不做收尾恢复（留给启动补做）";
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
                    RestoreJournal.Entry e = RestoreJournal.load();
                    if (!shouldRecover(e)) {
                        lastWhere = where;
                        lastSummary = "无待办";
                        return;
                    }
                    RestorePlan.Outcome o = ConfigRestorer.restore(e);
                    applyStage(e, o);
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

    /** 把结论写回记录：成功清记录，失败留痕（可被下次启动重试）。 */
    static void applyStage(RestoreJournal.Entry e, RestorePlan.Outcome o) {
        if (e == null || o == null) return;
        e.finishedAt = System.currentTimeMillis();
        if (o.restored) {
            e.stage = RestoreJournal.STAGE_RESTORED;
            e.failStep = "";
            e.failDetail = "";
            RestoreJournal.clear();
        } else {
            e.stage = RestoreJournal.STAGE_FAILED;
            e.failStep = o.failedAt == null ? "" : o.failedAt.name();
            e.failDetail = o.code + " · " + o.detail;
            RestoreJournal.save(e);
        }
    }
}

package io.github.wlmosv_png.wpaforge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.content.pm.ServiceInfo;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.CnHot;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;
import io.github.wlmosv_png.wpaforge.audit.DictStore;
import io.github.wlmosv_png.wpaforge.audit.RootProber;
import io.github.wlmosv_png.wpaforge.audit.SeedModel;
import io.github.wlmosv_png.wpaforge.audit.ShellSession;
import io.github.wlmosv_png.wpaforge.audit.SmartSorter;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
import io.github.wlmosv_png.wpaforge.audit.TriedSet;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 破解任务的前台服务。
 *
 * 解决的问题：
 *  1. 后台运行 —— 前台服务不会被系统随手回收，切应用/锁屏都继续跑
 *  2. 多目标队列 —— 依次执行队列里的任务
 *  3. 断点续跑 —— 每个任务记住 offset，中断后从那里继续
 *
 * 通知栏显示当前目标、进度、剩余时间，可以点通知回到应用。
 */
public class CrackService extends Service {

    public static final String ACTION_START = "io.github.wlmosv_png.wpaforge.START";
    public static final String ACTION_PAUSE = "io.github.wlmosv_png.wpaforge.PAUSE";
    public static final String ACTION_STOP = "io.github.wlmosv_png.wpaforge.STOP";

    private static final String CHANNEL = "wpaforge_crack";
    private static final int NOTI_ID = 1001;

    /** 全局单例引用，UI 直接绑定 */
    private static CrackService instance;

    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    public static CrackService get() {
        return instance;
    }

    /** 状态回调给 UI */
    public interface UiCallback {
        void onQueueChanged(List<TaskQueue.Task> queue);
        void onAttempt(int index, int total, String candidate);
        void onAttemptResult(String candidate, boolean hit, long ms, String detail);
        void onProgress(int tested, int total, double rate, long etaMs);
        void onTaskDone(TaskQueue.Task task, String hitPassword, String outcome);
        void onLog(String line);
        /**
         * 收尾（把用户原来的网络配置写回去）结束。
         *
         * <p>用 default 实现：这是给界面加的一条**补充信息**，
         * 老实现类不必为了它改代码。
         *
         * @param summary 人类可读的结论，例：{@code 已恢复 : OK}
         */
        default void onRestoreDone(String summary) { }
    }

    private final List<UiCallback> callbacks = new ArrayList<>();
    private volatile boolean running = false;
    private Thread worker;
    /** 统一引擎实例（取消/暂停走它） */
    private volatile CrackEngine engineRef;
    private TaskQueue.Task currentTask;
    /** 连续 exec 超时计数（通道熔断用） */
    private int execTimeouts = 0;
    private static final int EXEC_TIMEOUT_LIMIT = 5;
    private volatile boolean whitelistEnsured = false;
    /** 保活悬浮窗（1×1 透明） */
    private android.view.View keepAliveOverlay;
    private android.view.WindowManager keepAliveWm;
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    /**
     * 保活静音音轨。
     *
     * <p>为什么需要它（2026-10-23 实测定论）：ColorOS 15 会冻结「没有可见窗口」
     * 的后台进程 —— 实测后台时进程 CPU 10 秒消耗 0 ticks，su 调用随之超时，
     * 任务停滞。前台服务 + WakeLock + 待机桶 EXEMPTED 全都拦不住。
     *
     * <p>而**正在播放媒体**的进程属于另一个待遇档位，系统不冻它。
     * 这里持续写入全 0（完全无声）的 PCM，不申请音频焦点，不会影响其它 App 出声。
     */
    private android.media.AudioTrack silentTrack;
    private Thread silentThread;
    private volatile boolean silentRunning = false;
    private List<Candidate> currentDict = new ArrayList<>();
    private long taskStartAt;
    /** 上次把 offset 写盘的时刻（节流用） */
    private long lastOffsetSaveAt = 0L;
    /** 上次写盘时的 offset（节流用，避免无变化也写） */
    private int lastSavedOffset = -1;

    public class LocalBinder extends Binder {
        public CrackService service() { return CrackService.this; }
    }

    private final LocalBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        L.init(this);
        instance = this;
        // R11：先装好两个基础设施，后面所有恢复动作才有落脚点。
        //   SecretBox         —— 恢复记录里的原口令靠它加密（装不上就拒绝落明文）
        //   RestoreJournal    —— 恢复记录载体（进程被杀后靠它补做）
        try {
            io.github.wlmosv_png.wpaforge.audit.SecretBox.install(
                    io.github.wlmosv_png.wpaforge.audit.SecretBox.device(this));
        } catch (Throwable ignored) {}
        try {
            io.github.wlmosv_png.wpaforge.audit.RestoreJournal.install(
                    io.github.wlmosv_png.wpaforge.audit.RestoreStoreFile.file(this));
        } catch (Throwable ignored) {}
        createChannel();
        recoverInterrupted();
        // F1/F2：补做未完成的恢复一律**异步 + 单飞**（RestoreRunner 内部一把锁），
        // 这里不再同步跑 —— 原来在 onCreate（主线程）里最多阻塞十几秒会 ANR。
        io.github.wlmosv_png.wpaforge.audit.RestoreRunner.requestStartupRecovery("service-start");
    }

    // ──────────────────────────────── R11：配置恢复 ────────────────────────────────

    /**
     * 本次任务的恢复记录（**内存副本 = 权威来源**）。
     *
     * <p>为什么内存这份是权威：盘上那份可能因为写入失败而停留在**上一个任务**的内容。
     * 收尾恢复只认这一份，并且要核对 `taskId` 属于本任务（F3）。
     */
    private volatile io.github.wlmosv_png.wpaforge.audit.RestoreJournal.Entry restoreEntry;

    /**
     * 在任何破坏性动作之前：快照原配置 → 加密 → 落盘 → 过 fail-closed 闸门。
     *
     * <p>顺序不能变：引擎第一条候选就会 `cmd wifi forget-network`，
     * 删掉之后原口令再也拿不回来，所以**快照必须在删除之前**，
     * 而**持久化必须在动手之前**（进程被杀时才补得回来）。
     *
     * @return 闸门结论；{@code proceed=false} 时调用方**不得**进入引擎
     */
    private io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Decision armRestore(TaskQueue.Task task) {
        io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.Snapshot snap = null;
        boolean journalSaved = false;
        try {
            if (task == null || task.ssid == null || task.ssid.isEmpty()) {
                return new io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Decision(
                        false, io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Reason
                        .CHANNEL_UNREADABLE, L.s("任务缺少目标 SSID", "task has no target SSID"));
            }
            // 1) 冲突 / 记录不可解析 / 记录堆积（1.9.28）。
            //    判据只按「同一目标 + 未解决风险」—— 别人的失败记录不再一票否决：
            //    记录已按 taskId 分槽，本次写盘不会覆盖任何别人的记录。
            io.github.wlmosv_png.wpaforge.audit.RestoreRunner.Block blk =
                    io.github.wlmosv_png.wpaforge.audit.RestoreRunner
                            .checkNewTask(task.id, task.ssid);
            if (!blk.note.isEmpty()) log(L.s("提示: ", "note: ") + blk.note);
            if (blk.blocked) {
                // 被挡下：不快照、不写盘（写下去会覆盖/污染盘上那份别人的记录）。
                return new io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Decision(
                        false, blk.reason, blk.detail);
            }

            String sec = io.github.wlmosv_png.wpaforge.audit.ConfigRestorer
                    .securityArg(task.toNetRecord().security);
            String lineage = (task.lastRunId == null || task.lastRunId.isEmpty())
                    ? task.id : task.lastRunId;
            snap = io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.snapshot(
                    task.id, task.ssid, task.bssid, sec, lineage);
            journalSaved = io.github.wlmosv_png.wpaforge.audit.RestoreJournal.save(snap.entry);
            restoreEntry = snap.entry;
            log(L.s("配置恢复登记: ", "Restore journal: ")
                    + io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.describe(snap.entry)
                    + L.f(" · task=%s · 落盘=%s", " · task=%s · persisted=%s",
                          task.id, journalSaved ? "yes" : "NO"));
        } catch (Throwable t) {
            log(L.s("配置恢复登记异常: ", "restore journal threw: ") + t);
        }

        if (snap == null) {
            return new io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Decision(
                    false, io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Reason
                    .JOURNAL_UNWRITABLE, L.s("快照未能采集", "snapshot failed"));
        }
        return io.github.wlmosv_png.wpaforge.audit.RestoreGuard.decide(
                snap.facts(false, journalSaved,
                        io.github.wlmosv_png.wpaforge.audit.RestoreJournal.pendingUnknown(),
                        io.github.wlmosv_png.wpaforge.audit.RestoreJournal.pendingCount()));
    }

    /**
     * 收尾恢复。**每一个终态都必须走到这里**：命中、未命中、暂停、取消、异常。
     *
     * <p>同步执行（不另起线程）：它必须在服务真正停下来之前完成，
     * 否则 {@code stopSelf} 会把这个线程一起结束掉。
     * 与「启动补做」通过 {@code RestoreRunner} 的同一把锁串行化（F1）。
     *
     * @param taskId 当前任务 id —— 记录归属不符时**不动**目标配置
     */
    /**
     * 收尾恢复。**每一个终态都必须走到这里**：命中、未命中、暂停、取消、异常。
     *
     * <p>两条路（1.9.25 起）：
     * <ul>
     *   <li><b>命中</b>（{@code hit=true}）→ 系统里那条配置就是**正确口令**，
     *       走「保留」：一步删除都不做，手机会保持连着、以后自动重连；</li>
     *   <li><b>其余终态</b> → 标准恢复：删掉候选残留，再用原口令把目标加回去。</li>
     * </ul>
     * 保留条件不成立（回读不到目标配置）时自动退回标准恢复，不会留下中间态。
     *
     * @param hit 本轮是否命中 —— 由任务状态给出，**判定链本身不参与**
     */
    private void restoreAfterRun(String taskId, String outcome, boolean hit) {
        try {
            io.github.wlmosv_png.wpaforge.audit.RestoreJournal.Entry mine = restoreEntry;
            if (mine == null) return;
            log(hit
                    ? L.s("收尾保留（命中）：那条配置就是正确口令，不删除…",
                          "keeping hit config (it is the correct passphrase), no deletion…")
                    : L.s("收尾恢复（", "restoring after ") + outcome + L.s("）…", ")…"));
            io.github.wlmosv_png.wpaforge.audit.RestorePlan.Outcome o =
                    io.github.wlmosv_png.wpaforge.audit.RestoreRunner.runExclusiveForTask(
                            mine, taskId,
                            e2 -> io.github.wlmosv_png.wpaforge.audit.ConfigRestorer.settle(hit, e2));
            if (o == null) {
                String why = io.github.wlmosv_png.wpaforge.audit.RestoreRunner.lastSummary();
                log(L.s("本次未执行收尾恢复: ", "no restore this time: ") + why);
                for (UiCallback cb : callbacks) cb.onRestoreDone(why);
                return;
            }
            restoreEntry = mine;
            log(L.s("配置收尾结论: ", "Config settle result: ") + o.summary());
            for (UiCallback cb : callbacks) cb.onRestoreDone(o.summary());
        } catch (Throwable t) {
            log(L.s("配置恢复异常（不影响任务本身）: ",
                    "restore threw (task itself unaffected): ") + t);
        }
    }

    /**
     * R7：启动时把盘上残留的 {@code RUNNING} 归一为可恢复状态。
     *
     * <p>{@code RUNNING} 只由正在跑的服务写入，进程被杀后没人再改它 ——
     * 界面会永久显示「进行中」而实际没有线程（审计 R7）。这里：
     * RUNNING → PAUSED + interrupted=true，offset 原样保留（可续跑），
     * 并明确告知用户「上一轮的实时证据不继承，续跑会重新验证」。
     */
    private void recoverInterrupted() {
        try {
            java.util.List<TaskQueue.Task> all = TaskQueue.load(this);
            int n = TaskQueue.normalizeInterrupted(all);
            if (n > 0) {
                TaskQueue.save(this, all);
                log(L.f("检测到 %d 个上次未跑完的任务：已标记为「被中断」并置为可续跑"
                                + "（续跑会重新验证，不沿用上一轮的实时证据）",
                        "%d task(s) from a previous run were interrupted: marked as "
                                + "interrupted and made resumable (evidence is re-established "
                                + "on resume, not inherited)", n));
                notifyQueue();
            }
        } catch (Throwable t) {
            log(L.s("中断状态归一失败（不影响其它功能）",
                    "failed to normalise interrupted state (harmless)") + ": " + t);
        }
    }

    /** 是否成功进入前台模式 */
    private volatile boolean foregroundOk = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            pause();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }

        // Android 12+ 禁止从后台启动前台服务。
        // 从 Activity 里点按钮触发时是没问题的；但被系统重启、
        // 或从后台拉起时会抛 ForegroundServiceStartNotAllowedException。
        // 这里捕获它并降级为普通后台服务 —— 功能仍可用，只是不如
        // 前台服务稳（系统可能更早回收）。
        try {
            Notification pre = buildNotification(L.s("准备中", "Preparing"),
                    L.s("正在初始化…", "Initialising…"), 0, 0);
            if (Build.VERSION.SDK_INT >= 29) {
                // 必须与 manifest 的 foregroundServiceType 一致；
                // mediaPlayback 是为了让系统把本进程当「正在放媒体」，避免后台被冻结。
                startForeground(NOTI_ID, pre,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTI_ID, pre);
            }
            foregroundOk = true;
        } catch (Exception e) {
            foregroundOk = false;
            android.util.Log.w("WPAForgeSvc", L.s("无法进入前台模式，降级运行: ", "Cannot enter foreground mode, degraded: ") + e);
            // 仍然给出通知（如果允许），至少让用户看得到
            try {
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(NOTI_ID, buildNotification(L.s("准备中", "Preparing"), L.s("正在初始化…", "Initialising…"), 0, 0));
                }
            } catch (Exception ignored) {}
        }

        startQueue();
        // 被系统杀掉后不要自动重启（避免意外消耗），由用户手动再开
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
        instance = null;
        running = false;
        if (worker != null) worker.interrupt();
    }

    // ---------------- 对外接口 ----------------

    public void addCallback(UiCallback cb) {
        if (cb != null && !callbacks.contains(cb)) callbacks.add(cb);
    }

    public void removeCallback(UiCallback cb) {
        callbacks.remove(cb);
    }

    /**
     * 是否正在破解。
     *
     * 以**队列状态**为唯一真相，不看 running 标志 ——
     * running 只表示"工作线程存在"，停止后要等线程自然退出才变 false，
     * 拿它判断会让 UI 状态滞后，也会让停止按钮失效。
     */
    public boolean isWorking() {
        for (TaskQueue.Task t : TaskQueue.load(this)) {
            if (t.state == TaskQueue.State.RUNNING) return true;
        }
        return false;
    }

    public TaskQueue.Task current() {
        return currentTask;
    }

    /** 本目标的"已试候选"集合（跑的时候往里记，下次不重复） */
    private volatile TriedSet triedSet;
    /**
     * 载入 triedSet 时用的目标键。
     *
     * <p>落盘必须用它，**不能用"界面上当前的目标"** —— 用户可以排了 B209 的队、
     * 再切到 B211 看，这样写就会把 B209 的记录写到 B211 档案里，
     * 直接违反"跨目标绝不共享"这条不变式。
     */
    private volatile String triedKey;
    private int triedSinceFlush = 0;

    /**
     * 服务**正在使用**的那份候选列表（只读用途：界面回前台后重建显示）。
     *
     * <p>为什么由服务给而不是界面自己读盘：界面与引擎必须是同一份数据来源 ——
     * 界面自己再读一遍盘，就可能出现「界面显示 1771 条、引擎实际跑的是另一份」
     * 这种对不上的情况。这里直接给出引擎正在用的引用。
     */
    public java.util.List<Candidate> dictForDisplay() {
        return currentDict;
    }

    /** 排队一个目标 */
    public TaskQueue.Task enqueue(NetRecord target, String dictPath, String dictName) {
        TaskQueue.Task t = TaskQueue.enqueue(this, target, dictPath, dictName);
        notifyQueue();
        return t;
    }

    /** 开始/继续执行队列 */
    /**
     * 用户在 worker 仍活着时点过「继续」—— 收尾时补一次启动。
     *
     * <p>场景：点「暂停」后马上又点「继续」（或通知栏按钮连按）。那一刻
     * 引擎正在收尾、worker 线程还没退出，{@link #startQueue()} 会被
     * 「已有任务在运行」挡回去并**静默失效** —— 任务停在 PENDING，
     * 没有任何线程会去跑它，用户看到的就是「点了继续没反应」。
     */
    private volatile boolean restartWanted = false;

    /** 开始/继续执行队列 */
    public void startQueue() {
        if (worker != null && worker.isAlive()) {
            if (TaskQueue.hasPending(this)) {
                // 队列里有 PENDING（＝有人点过继续）：记一笔，收尾时接上。
                restartWanted = true;
                log(L.s("上一轮正在收尾，「继续」已记下，收尾后自动开始",
                        "Previous run is wrapping up; resume queued"));
            } else {
                log(L.s("已有任务在运行", "A task is already running"));
            }
            return;
        }
        if (!TaskQueue.hasWork(this)) {
            log(L.s("队列里没有待办任务", "No pending tasks in the queue"));
            stopSelfSafe();
            return;
        }
        stopRequested = false;
        running = true;
        worker = new Thread(this::runQueue, "wpaforge-crack");
        worker.start();
    }

    /** 暂停：当前任务保留进度，队列原地等待 */
    public void pause() {
        flushTried();   // 暂停时把"已试"落盘，避免被系统回收后重跑一截
        // ★ 最关键的一步：把「暂停」真正送到引擎。
        //
        // 历史事故（2026-10-23 实机复现）：这里以前**只**通知免-root
        // 执行器（那两个引用在 Root 模式下都是 null），于是 pause() 退化成
        // 纯 UI 动作：队列标成 PAUSED、日志写「正在暂停…」，引擎毫不知情、
        // 继续逐条往下跑。当天实测：点暂停后 offset 25 秒里从 82 涨到 88。
        CrackEngine eng = engineRef;
        if (eng != null) eng.pause();
        // 立刻把当前任务标 PAUSED，UI 马上能看到
        TaskQueue.Task cur = currentTask;
        if (cur != null && cur.state == TaskQueue.State.RUNNING) {
            cur.state = TaskQueue.State.PAUSED;
            TaskQueue.update(this, cur);
            notifyQueue();
        }
        log(L.s("正在暂停…（进度会保留）", "Pausing… (progress kept)"));
    }

    /** 用户点过停止 */
    private volatile boolean stopRequested = false;

    /**
     * 立即停止。
     *
     * 做法：把正在跑的任务直接标记为 STOPPED（队列是唯一真相，
     * 改完 UI 立刻能看到），同时通知工作线程退出。
     * 不动 running 标志 —— 那会和线程状态打架。
     */
    public void stopAll() {
        stopRequested = true;

        CrackEngine eng = engineRef;
        if (eng != null) eng.cancel();

        // 当前任务立刻标为已停止
        TaskQueue.Task cur = currentTask;
        if (cur != null && cur.state == TaskQueue.State.RUNNING) {
            cur.state = TaskQueue.State.STOPPED;
            TaskQueue.update(this, cur);
        }
        // 排队中的一并作废（点停止 = 不想跑了）
        List<TaskQueue.Task> all = TaskQueue.load(this);
        boolean changed = false;
        for (TaskQueue.Task t : all) {
            if (t.state == TaskQueue.State.PENDING) {
                t.state = TaskQueue.State.STOPPED;
                changed = true;
            }
        }
        if (changed) TaskQueue.save(this, all);

        log(L.s("正在停止…", "Stopping…"));
        notifyQueue();
    }

    // ---------------- 队列执行 ----------------

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "wpaforge:crack");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(12 * 3600 * 1000L); // 最多保活 12 小时
            }
        } catch (Throwable ignored) {}

        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wpaforge:wifilock");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {}

        startSilentAudio();
    }

    /**
     * 启动静音音轨（保活最后一层）。
     *
     * <p>实测（2026-10-23）：ColorOS 15 会把「没有可见窗口」的后台进程冻死 ——
     * 后台时进程 CPU 10 秒消耗 0 ticks，su 调用随之超时、任务停滞。
     * 前台服务 + WakeLock + 待机桶 EXEMPTED 全都拦不住；而本机的
     * 1x1 悬浮窗 addView 一直抛异常（不可用）。
     *
     * <p>持续播放**无声** PCM 让进程被系统归入「媒体播放」档位，从而不被冻结。
     * 不申请音频焦点，不会打断其它 App 的播放。
     *
     * <p>任何一步失败都静默降级 —— 它只是加分项，绝不能影响主流程。
     */
    private void startSilentAudio() {
        if (silentRunning) return;
        try {
            if (silentTrack == null) {
                int sr = 8000;
                int minBuf = android.media.AudioTrack.getMinBufferSize(sr,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) minBuf = 4096;
                silentTrack = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new android.media.AudioFormat.Builder()
                                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sr)
                                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(minBuf)
                        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                        .build();
            }
            silentTrack.play();
            silentRunning = true;
            final short[] buf = new short[400];
            silentThread = new Thread(() -> {
                while (silentRunning) {
                    try {
                        silentTrack.write(buf, 0, buf.length);
                    } catch (Throwable t) {
                        break;
                    }
                }
            }, "wpaforge-silent-audio");
            silentThread.setDaemon(true);
            silentThread.start();
            log(L.s("已启用静音音频保活（防止后台被系统冻结）",
                    "silent-audio keep-alive enabled (prevents background freeze)"));
        } catch (Throwable t) {
            silentRunning = false;
            log(L.s("静音音频保活不可用: ", "silent-audio keep-alive unavailable: ")
                    + t.getClass().getSimpleName());
        }
    }

    private void stopSilentAudio() {
        silentRunning = false;
        try {
            if (silentThread != null) {
                silentThread.interrupt();
                silentThread = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (silentTrack != null) {
                if (silentTrack.getPlayState() == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    silentTrack.stop();
                }
                silentTrack.release();
                silentTrack = null;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 显示一个 1×1 的透明悬浮窗，仅用于保活。
     *
     * <p>为什么需要它：实测（OnePlus PJX110 / ColorOS 15）后台应用会被系统冻结 ——
     * 30 秒内 CPU 实际运行 0 ms，连待机桶已是 EXEMPTED、WakeLock 持有中、
     * 前台服务在跑都拦不住。而带**可见窗口**的进程属于另一个待遇档位，
     * 系统通常不冻它。
     *
     * <p>权限用 root 直接授（`appops set <pkg> SYSTEM_ALERT_WINDOW allow`），
     * 不让用户去设置里翻。
     */
    private void showKeepAliveOverlay() {
        try {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                        new String[]{"su", "-c", "appops set " + getPackageName()
                                + " SYSTEM_ALERT_WINDOW allow"}, 6000);
            }
            if (keepAliveOverlay != null) return;
            if (keepAliveWm == null) {
                keepAliveWm = (android.view.WindowManager)
                        getSystemService(android.content.Context.WINDOW_SERVICE);
            }
            // ★ 关键修复（2026-10-23 实测定论）★
            // 本方法从工作线程 wpaforge-crack 调用，而 WindowManager.addView()
            // 内部会 new ViewRootImpl -> new Handler，要求线程已 Looper.prepare()。
            // 工作线程没有 Looper，因此稳定抛：
            //   java.lang.RuntimeException: Can't create handler inside thread
            //   Thread[wpaforge-crack,5,main] that has not called Looper.prepare()
            // 异常被 catch(Throwable) 吞成一行「悬浮窗不可用: RuntimeException」——
            // 于是保活悬浮窗从上线起一次都没成功，后台无可见窗口 -> ColorOS 冻死进程
            // -> su 调用超时 -> 任务停滞（用户现象：切出去就不跑了）。
            // 正解：addView 一律 post 到主线程执行。
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                try {
                    if (keepAliveOverlay != null) return;
                    if (keepAliveWm == null) return;
                    keepAliveOverlay = new android.view.View(CrackService.this);
                    android.view.WindowManager.LayoutParams lp =
                            new android.view.WindowManager.LayoutParams(
                                    1, 1,
                                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                    android.graphics.PixelFormat.TRANSPARENT);
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    lp.x = 0;
                    lp.y = 0;
                    keepAliveWm.addView(keepAliveOverlay, lp);
                    log(L.s("已显示保活悬浮窗（1×1 透明，防止后台被系统冻结）",
                            "keep-alive overlay shown (1x1 transparent)"));
                } catch (Throwable t) {
                    log(L.s("悬浮窗不可用: ", "overlay unavailable: ")
                            + android.util.Log.getStackTraceString(t));
                }
            });
        } catch (Throwable t) {
            log(L.s("悬浮窗不可用: ", "overlay unavailable: ")
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private void hideKeepAliveOverlay() {
        try {
            // 与 addView 同理：removeView 也必须在有 Looper 的线程。
            final android.view.View v = keepAliveOverlay;
            final android.view.WindowManager wm = keepAliveWm;
            if (wm != null && v != null) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> { try { wm.removeView(v); } catch (Throwable ignored) {} });
            }
        } catch (Throwable ignored) {
        } finally {
            keepAliveOverlay = null;
        }
    }

    private void releaseLocks() {
        hideKeepAliveOverlay();
        stopSilentAudio();
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 确保系统侧白名单包含本应用。
     *
     * <p>实测（OnePlus PJX110 / ColorOS 15）两个坑：
     * <ul>
     *   <li>不在**电池优化白名单** → 进入 Doze 后被限制</li>
     *   <li>不在 ColorOS 的**深度睡眠白名单**（{@code lspdoze.deepsleep.whitelist}）
     *       → 关屏后系统会强制断开该 UID 的网络并拦截网络操作
     *       （{@code force_uid_disconnect=1}、{@code intercept_disable_network=1}）</li>
     * </ul>
     * 两项都能用 root 直接写，不需要用户去系统设置里翻。
     */
    private void ensureSystemWhitelist() {
        if (whitelistEnsured) return;
        whitelistEnsured = true;
        final String pkg = getPackageName();
        new Thread(() -> {
            try {
                io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                        new String[]{"su", "-c", "dumpsys deviceidle whitelist +" + pkg}, 8000);
            } catch (Throwable ignored) {}
            try {
                String cur = io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                        new String[]{"su", "-c",
                                "settings get global lspdoze.deepsleep.whitelist"}, 6000);
                if (cur != null && !cur.contains(pkg)) {
                    String merged = cur.trim().isEmpty() ? pkg : cur.trim() + "," + pkg;
                    io.github.wlmosv_png.wpaforge.audit.RootProber.exec(this,
                            new String[]{"su", "-c",
                                    "settings put global lspdoze.deepsleep.whitelist '" + merged + "'"},
                            6000);
                    log(L.s("已加入系统深度睡眠白名单（关屏后不再被断网）",
                            "added to system deep-sleep whitelist"));
                }
            } catch (Throwable ignored) {}
        }, "wpaforge-whitelist").start();
    }

    private void runQueue() {
        acquireLocks();
        ensureSystemWhitelist();
        showKeepAliveOverlay();
        try {
            while (!stopRequested) {
                if (Thread.currentThread().isInterrupted()) break;
                TaskQueue.Task task = TaskQueue.nextPending(this);
                if (task == null) break;
                if (task.state == TaskQueue.State.PAUSED) {
                    log(L.f("任务「%s」处于暂停，跳过", "Task “%s” is paused, skipping", task.ssid));
                    break;
                }
                executeTask(task);
                if (stopRequested) break;
            }
        } catch (Exception e) {
            log(L.f("队列执行异常: %s", "Queue error: %s", String.valueOf(e)));
        } finally {
            releaseLocks();
            currentTask = null;
            notifyQueue();
            // 收尾前补一次：用户在 worker 还活着时点过「继续」，
            // 那一次 startQueue 被上面的分支挡下了。不补这一下，
            // 任务会永远停在 PENDING（用户看到「点了继续没反应」）。
            boolean wantRestart = restartWanted;
            restartWanted = false;   // 一次性：判定过就清，免得日后误触发
            if (!stopRequested && wantRestart && TaskQueue.hasPending(this)) {
                log(L.s("接上「继续」请求，重新开始队列",
                        "Picking up the resume request, restarting the queue"));
                worker = new Thread(this::runQueue, "wpaforge-crack");
                worker.start();
                return;
            }
            running = false;
            log(L.s("队列已结束", "Queue finished"));
            stopSelfSafe();
        }
    }

    private void executeTask(TaskQueue.Task task) {
        currentTask = task;
        task.state = TaskQueue.State.RUNNING;
        TaskQueue.update(this, task);
        notifyQueue();

        log(L.s("===== 开始任务: ", "===== Starting task: ") + task.ssid + " =====");

        // P0（F3/F6）：**任何破坏性动作之前**先做快照 + 加密 + 持久化，并过 fail-closed 闸门。
        // 快照拿不到 / 配置读不到 / 口令加密失败 / 记录写不进盘 / 盘上还有别的未完成恢复
        // —— 任一成立都不进入破坏性操作。任务置 PAUSED（可续跑，不丢进度）。
        io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Decision gate = armRestore(task);
        if (!gate.proceed) {
            log(L.s("已阻止本次运行: ", "run blocked: ")
                    + io.github.wlmosv_png.wpaforge.audit.RestoreGuard.describe(gate));
            // P1：把「阻塞原因 + 下一步」写给用户 —— 不把定向拦截伪装成普通暂停。
            log(io.github.wlmosv_png.wpaforge.audit.RestoreRunner.blockHelp(gate));
            task.state = TaskQueue.State.PAUSED;
            TaskQueue.update(this, task);
            notifyQueue();
            return;
        }

        // ── R7：运行标识与续跑血缘 ────────────────────────────────────────────
        // 续跑时**开新 run**（绝不复用旧 id）：旧 run 只作为血缘保留在记录里，
        // 旧证据（日志窗口、连接状态）一律不继承 —— 由新 run + 每条候选自己的
        // 窗口标记重新确立。这样「恢复」不会假装已丢失的实时证据仍然有效。
        try {
            io.github.wlmosv_png.wpaforge.audit.AttemptId.ResumeTicket tk =
                    io.github.wlmosv_png.wpaforge.audit.AttemptId.adoptForResume(
                    task.lastRunId, task.offset, System.currentTimeMillis(),
                    task.ssid, 0);
            if (tk.newRunId != null && engineRef != null) {
                engineRef.presetRunId(tk.newRunId);
                task.lastRunId = tk.newRunId;
                task.interrupted = false;      // 已开始新的一轮，中断标记清掉
                TaskQueue.update(this, task);
                if (tk.previousRunId != null) {
                    log(L.f("续跑：自第 %d 条继续；上一轮运行标识 %s，本轮 %s（不继承其证据）",
                            "resume from #%d; previous run %s, current run %s "
                                    + "(evidence is not inherited)",
                            tk.resumeFromOffset, tk.previousRunId, tk.newRunId));
                } else {
                    log(L.f("本轮运行标识: %s", "run id: %s", tk.newRunId));
                }
            }
        } catch (Throwable t) {
            log(L.s("运行标识生成失败（不影响判定，但这次没有可追溯标识）",
                    "failed to create a run id (verdicts unaffected, but this run "
                            + "has no traceable id)") + ": " + t);
        }

        // 准备候选。按任务入队时记录的开关决定，不再无条件叠加。
        // 续跑时必须用完全相同的规则重建列表，否则 offset 会对不上。
        {
            List<Candidate> base;
            if (task.dictPath != null && !task.dictPath.isEmpty()) {
                base = DictStore.load(new java.io.File(task.dictPath), 0);
                log(L.f("加载字典 %s -> %d 条", "Loaded dictionary %s -> %d entries",
                        task.dictName, base.size()));
            } else {
                // **重点**：dictPath 为空有两种情况
                //   a) 任务是用「内置生成器」建的 —— 候选只在内存里，从没落盘
                //   b) 真的是空任务
                //
                // 旧代码一律当 (b) 处理，于是「内置生成器 1771 条」的任务
                // 一进 executeTask 就 base 为空 → 直接走「候选为空，跳过」。
                // 表现就是：三种模式点开始都秒结束，看起来像模式坏的，
                // 其实是候选根本没被重建出来。
                //
                // 这里按入队时记录的名字/条数判断，并用完全相同的参数重建 ——
                // 必须与 startConnect() 里生成时用的参数一致，否则 offset 对不上。
                if (task.dictSize > 0) {
                    base = DictGenerator.quick(task.dictSize).build();
                    log(L.f("重建内置字典 %s -> %d 条",
                            "Rebuilt built-in dictionary %s -> %d entries",
                            task.dictName, base.size()));
                } else {
                    base = new ArrayList<>();
                }
            }

            List<Candidate> work = base;

            // 线索：**必须与入队时完全一致**，否则续跑的 offset 会对不上。
            // 线索存在任务里（seedsJson），不是从界面现读 —— 界面可能已经改了。
            final SeedModel seeds = SeedModel.decode(task.seedsJson);
            final List<String> cnCommon = CnHot.load(this);
            if (!seeds.isEmpty()) {
                log(L.f("线索: 已启用 %d 项（%s）", "Seeds: %d item(s) (%s)",
                        seeds.count(), seeds.masked()));
            }

            if (task.useTargetDict) {
                // 预算与界面一致：候选只出"密度最高的一小撮"，不铺长尾
                TargetDictBuilder.Result dr = TargetDictBuilder.build(task.toNetRecord(),
                        Prefs.deriveBudget(this),
                        io.github.wlmosv_png.wpaforge.scan.OuiLookup.vendor(this, task.bssid),
                        seeds, cnCommon);
                if (!dr.candidates.isEmpty()) {
                    List<Candidate> merged = new ArrayList<>();
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                    for (Candidate c : dr.candidates) if (seen.add(c.password)) merged.add(c);
                    for (Candidate c : base) if (seen.add(c.password)) merged.add(c);
                    work = merged;
                    log(L.f("目标派生: 已启用，插入 %d 条候选",
                            "Target derivation: on, %d inserted", dr.candidates.size()));
                }
            } else {
                log(L.s("目标派生: 已关闭", "Target derivation: off"));
            }

            if (task.useSmartSort) {
                work = SmartSorter.prioritize(task.toNetRecord(), work, 0, seeds, cnCommon);
                log(L.s("智能排序: 已启用", "Smart ranking: on"));
            } else {
                log(L.s("智能排序: 已关闭", "Smart ranking: off"));
            }

            /* ── 跳过本目标已经试过的候选 ────────────────────────────────
             * 这是"换字典不再重复劳动"的落点。判据是**候选的哈希**，不是 offset ——
             * offset 只对同一份字典有效，换字典/合并字典就作废，而哈希集合照常生效。
             *
             * 只在本目标内生效：同一个候选在别的网失败，不代表这个网也失败。 */
            {
                triedKey = TriedSet.targetKey(task.bssid, task.ssid);
                triedSet = TriedSet.load(TriedSet.fileFor(this, triedKey));
                triedSinceFlush = 0;
                if (triedSet.size() > 0) {
                    List<Candidate> keep = new ArrayList<>(work.size());
                    int skipped = 0;
                    for (Candidate c : work) {
                        if (triedSet.contains(c.password)) { skipped++; continue; }
                        keep.add(c);
                    }
                    if (skipped > 0) {
                        log(L.f("已跳过 %d 条本目标试过的候选（本目标累计 %d 条）",
                                "Skipped %d already-tried candidates on this target (%d total)",
                                skipped, triedSet.size()));
                    }
                    work = keep;
                }
                if (triedSet.isOverflow()) {
                    log(L.s("提示: 本目标的已试记录已达上限，更早的记录不再计入。",
                            "Note: tried-record limit reached for this target."));
                }
            }

            currentDict = work;
            if (task.dictSize <= 0) {
                task.dictSize = currentDict.size();
                TaskQueue.update(this, task);
            }
        }

        if (currentDict.isEmpty()) {
            if (triedSet != null && triedSet.size() > 0) {
                log(L.s("这本字典的候选在本目标上都试过了 —— 换一本字典，或到字典页清空已试记录。",
                        "Every candidate here has already been tried on this target \u2014 "
                                + "use another dictionary or clear the tried record."));
            } else {
                log(L.s("候选为空，跳过", "Empty candidate set, skipping"));
            }
            task.state = TaskQueue.State.DONE_MISS;
            TaskQueue.update(this, task);
            notifyQueue();
            return;
        }

        taskStartAt = System.currentTimeMillis();
        lastOffsetSaveAt = 0L;
        lastSavedOffset = -1;
        final int startOffset = task.offset;
        if (startOffset > 0) {
            log(L.s("从第 ", "Resuming from ") + startOffset + L.s(" 条继续", " entries"));
        }

        final TaskQueue.Task ft = task;

        // **三种模式统一走 CrackEngine**
        //
        // 之前每条通道各有一套逻辑、各有一套判定，结果同一个 bug
        // 要在两三个地方修，还修不全（双重 onFinished、配置复用假阳性
        // 都同时存在于两条通道）。现在通道只负责「把一条候选发出去」，
        // 判定全部由引擎统一做。
        runViaEngine(task, startOffset);
    }

    /**
     * 统一引擎入口。
     *
     * 只有 Root 通道：su + cmd wifi。
     * 判定全在引擎里，不分正两份。
     */
    private void runViaEngine(final TaskQueue.Task ft, int startOffset) {
        logFile = null;   // 每次任务新建一个日志文件
        final CrackEngine engine = new CrackEngine(this, ft.crackMode);
        engineRef = engine;

        {
        // 常驻会话（2026-10-23 实测）：App 里每起一次 su 要 ~137ms，
        // 一条候选约 28 次，光起进程就 3.8 秒 —— 这是「每条 5 秒」的真正大头。
        // 改成一个常驻 `su -c sh` 会话里连打命令，实测 88ms/次 → 20ms/次。
        // 会话不可用就退回一次性 su：功能不丢，只是慢回去。
        final ShellSession sess = new ShellSession();
        final int[] sessFails = {0};
        final boolean[] sessOff = {false};
        engine.setShellChannel(new CrackEngine.ShellChannel() {
            @Override
            public String exec(String cmd, long timeoutMs) {
                String out = sessOff[0] ? null : sess.exec(cmd, timeoutMs);
                if (out == null) {
                    // 这一帧没拿到（超时 / 会话死了）。连续几次就彻底不再试，
                    // 免得每轮白付一次启动成本。
                    if (!sessOff[0] && ++sessFails[0] >= 3) {
                        sessOff[0] = true;
                        log(L.s("常驻 shell 会话不可用，已退回逐条 su（会慢一些）",
                                "persistent shell unavailable; falling back to per-call su"));
                    }
                    out = RootProber.exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                } else {
                    sessFails[0] = 0;
                }
                // ---- 通道熔断 ----
                // 连续多次命令超时，说明通道已经不可用（进程被系统限制、su 被反复卡住……）。
                // 这时继续跑只会让每条候选都耗在超时上 —— 界面看着像「在跑」，
                // 实际一步没动。与其无声卡住，不如停下来把情况讲清楚。
                {
                    if (RootProber.lastTimedOut()) {
                        if (++execTimeouts >= EXEC_TIMEOUT_LIMIT) {
                            execTimeouts = 0;
                            log(L.s(
                                    "连续多次命令超时 —— 通道已被系统限制，任务已暂停。"
                                    + "回到应用界面后点继续即可（前台运行时不受此限制）。",
                                    "Repeated command timeouts - channel throttled. Task paused; "
                                    + "reopen the app and press resume."));
                            stopAll();
                        }
                    } else if (out != null) {
                        execTimeouts = 0;
                    }
                }
                return out;
            }
            @Override
            public void close() {
                sess.close();
            }
            // F4：这里原来有一份「删净旧配置」实现（内含 cmd wifi forget-network）。
            // 自 verifyHit() 删除后它已无调用者，且引擎判定用的是自带的
            // CrackEngine.forgetTargetByShell / removeTargetConfig —— 同一个动作
            // 两处实现只会带来不一致。接口方法已降级为 @Deprecated 空默认实现。
            @Override
            public String name() { return "Root"; }
        });
        }

        log(L.s("引擎模式: Root", "Engine mode: Root"));

        engine.run(ft.toNetRecord(), currentDict, startOffset, 0L, new CrackEngine.Listener() {
            @Override
            public void onAttemptStart(int index, int total, String candidate) {
                for (UiCallback cb : callbacks) cb.onAttempt(index, total, candidate);
                updateNotification(L.f("正在破解 %s", "Cracking %s", ft.ssid),
                        // 通知栏是最暴露的面（锁屏可见），候选一律脱敏
                        io.github.wlmosv_png.wpaforge.ui.Redact.pw(candidate)
                                + "   " + index + "/" + total, index, total);
            }

            @Override
            public void onAttemptResult(CrackEngine.Attempt a) {
                // 心跳：回到前台时用它判断「后台期间是不是被系统挂起过」
                Prefs.beat(CrackService.this, System.currentTimeMillis());

                /* 记入"本目标已试"。
                 * **只记有明确结论的**（密码错误 / 命中）——
                 * 超时、无结论、通道出错都不记：那些下次还应该再试一次，
                 * 记进去等于把没真正验证过的候选当成"已排除"。 */
                if (triedSet != null && a.password != null && !a.password.isEmpty()) {
                    boolean definite = a.isSuccess()
                            || a.verdict == CrackEngine.Verdict.AUTH_FAIL;
                    if (definite && triedSet.add(a.password) && ++triedSinceFlush >= 32) {
                        triedSinceFlush = 0;
                        flushTried();
                    }
                }
                // 不要再覆盖引擎给出的 detail。
                //
                // 旧代码把 UNKNOWN 一律改写成「无法确认（当前连着目标，但可能是原有连接）」，
                // 抹掉了引擎辛苦区分出来的真实原因（通道没响应 / 未见尝试 / 证据不足），
                // 用户因此永远看不到问题出在哪。现在原样透传。
                String detail = a.detail;
                if (a.isSuccess() && a.confirmedBy != null && !a.confirmedBy.isEmpty()) {
                    // 命中时补上判定依据，便于自证：wifistate / status / watcher / wifiinfo
                    detail = L.s(detail + "（依据: ", detail + " (via: ") + a.confirmedBy + "）";
                }
                for (UiCallback cb : callbacks) {
                    cb.onAttemptResult(a.password, a.isSuccess(), a.elapsedMs, detail);
                }
            }

            @Override
            public void onProgress(int tested, int total, double rate, long etaMs) {
                // 断点续跑的关键：把「已试到哪」定期写盘。
                // 引擎每条都会回调这里，tested 即当前游标位置。
                saveProgress(ft, tested, false);
                for (UiCallback cb : callbacks) cb.onProgress(tested, total, rate, etaMs);
            }

            @Override
            public void onLog(String line) { log(line); }

            @Override
            public void onFinished(int tested, int offset, String hitPassword, String outcome) {
                engineRef = null;
                finishTask(ft, outcome, tested, offset, hitPassword);
            }

            @Override
            public void onBlocked(String title, String detail) {
                log(L.f("无法开始: %s", "Cannot start: %s", title));
                for (UiCallback cb : callbacks) cb.onLog(title + "\n" + detail);
                engineRef = null;
                ft.state = TaskQueue.State.STOPPED;
                TaskQueue.update(CrackService.this, ft);
                restoreAfterRun(ft.id, "BLOCKED", false);
                running = false;
                notifyQueue();
                stopSelfSafe();
            }
        });
    }

    /**
     * 本次任务的实时速率（毫秒/条）；没有任务在跑时返回 0。
     *
     * <p>为什么不能用 Prefs 的历史值：那是跨任务累计的指数滑动平均，
     * 新任务的真实速度会被历史样本压住。用户看到「每条约 1.2 秒」而实际每条 10 秒，
     * 就是这么来的 —— 状态行必须显示**本次**的实测值。
     */
    public long currentRateMs() {
        try {
            CrackEngine e = engineRef;
            if (e == null || !running) return 0;
            int n = e.testedCount();
            // 优先「最近 20 条」的滑窗均值：它才是此刻的真实节奏。
            // （整轮平均会被开头的慢批次拖住 —— 用户实测：显示 5.2 秒，实际 2.2 秒。）
            long recent = e.liveRecentAvgMs();
            if (recent > 0) return recent;
            // 窗口还空（刚开始）：退回整轮平均。
            // 必须用**运行中**的累计值：lastPureTryMs 只在整轮循环结束后才赋值，
            // 任务进行中它恒为 0，于是界面永远显示上一个任务的历史数字。
            long pure = e.livePureTryMs();
            if (n > 0 && pure > 0) return pure / n;
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * 记录速率时该用哪个耗时。
     *
     * 优先用引擎报的净尝试耗时（只含逐条尝试）；拿不到才退回墙钟。
     * 墙钟会把 WiFi 重置、基线断开、通道自检都摊进来，
     * 导致 perItem 被高估、开始前的预估偏慢。
     */
    private long rateSampleMs(int tested) {
        try {
            CrackEngine e = engineRef;
            if (e != null) {
                long pure = e.lastPureTryMs();
                if (pure > 0) return pure;
            }
        } catch (Throwable ignored) {}
        return System.currentTimeMillis() - taskStartAt;
    }

    /**
     * 把当前进度写盘（断点续跑的依据）。
     *
     * 为什么需要：offset 以前只在任务开始与结束时写。跑到第 3000 条
     * 被系统回收，磁盘上记的还是 0 —— 用户看到的就是「进度没记录」。
     *
     * 为什么节流：每条都全量重写 JSON 会产生密集 IO。
     * 按「≥2 秒 或 ≥20 条」落一次，可靠性与开销兼顾。
     * 命中时由 finishTask 立即落盘，不走这里。
     */
    /** 把"已试集合"落盘。失败只记一行日志 —— 丢了最多重跑，不会出错。 */
    private void flushTried() {
        TriedSet t = triedSet;
        String key = triedKey;
        if (t == null || key == null || key.isEmpty() || !t.isDirty()) return;
        t.save(TriedSet.fileFor(this, key));
    }

    private void saveProgress(TaskQueue.Task ft, int offset, boolean force) {
        if (ft == null || offset <= 0) return;
        long now = System.currentTimeMillis();
        boolean changed = offset != lastSavedOffset;
        boolean due = (now - lastOffsetSaveAt >= 2000L)
                || (offset - lastSavedOffset >= 20);
        if (!force && (!changed || !due)) return;
        ft.offset = offset;
        ft.elapsedMs = now - taskStartAt;
        // 只写进度字段：盘上的 state 才是用户最新的操作。
        // 旧写法拿内存快照整对象回写，会把用户点的「继续」（PENDING）
        // 又改回 PAUSED —— 就是「点了继续没反应」的最后一层原因。
        TaskQueue.updateProgress(this, ft.id, ft.offset, ft.elapsedMs);
        lastOffsetSaveAt = now;
        lastSavedOffset = offset;
    }

    private void finishTask(TaskQueue.Task ft, String outcomeName,
                            int tested, int offset, String hitPassword) {
        flushTried();
        ft.offset = offset;
        ft.testedThisRun += tested;
        ft.elapsedMs += System.currentTimeMillis() - taskStartAt;

        if (hitPassword != null && !hitPassword.isEmpty()) {
            ft.setHitPassword(hitPassword);   // R12：标签/密文一并生成，明文不落盘
            ft.state = TaskQueue.State.DONE_HIT;
            log(L.f("任务结束: %s 已命中（试了 %d 条）",
                    "Task finished: %s hit (%d tried)", ft.ssid, tested));
            // 记录实测速度
            if (tested > 0) {
                Prefs.recordRate(this, rateSampleMs(tested), tested);
            }
        } else if ("PAUSED".equals(outcomeName)) {
            // 以**盘上状态**为准：引擎收尾要花十几到几百毫秒，
            // 用户可能就在这个窗口里点了「继续」（盘上状态已改成 PENDING）。
            // 这时直接写回 PAUSED 就把那次点击吞了：界面又变回「已暂停」，
            // 用户看到的是「点了继续没反应」（必须再点一次）。
            // 实测：2026-10-23 08:49:20 暂停+继续连按，日志已打「「继续」已记下」，
            // 队列却仍停在 173/PAUSED —— 就是这里写回去的。
            TaskQueue.Task onDisk = null;
            for (TaskQueue.Task t : TaskQueue.load(this)) {
                if (t.id.equals(ft.id)) { onDisk = t; break; }
            }
            if (onDisk != null && onDisk.state == TaskQueue.State.PENDING) {
                ft.state = TaskQueue.State.PENDING;
                log(L.s("暂停收尾期间已点过「继续」，保持待运行",
                        "resume was clicked while pausing; keeping it queued"));
            } else {
                ft.state = TaskQueue.State.PAUSED;
                log(L.f("已暂停，进度 %d/%d", "Paused at %d/%d", offset, ft.dictSize));
            }
        } else if ("STOPPED".equals(outcomeName)) {
            ft.state = TaskQueue.State.STOPPED;
            log(L.f("已停止，进度 %d/%d", "Stopped at %d/%d", offset, ft.dictSize));
        } else {
            ft.state = TaskQueue.State.DONE_MISS;
            // 把本轮结论分布记到任务上：**「未命中」只有在其余条目都有失败证据时才成立**。
            // 无结论（TIMEOUT/UNKNOWN）条目并不证明密码错 —— 它们只是没拿到证据。
            io.github.wlmosv_png.wpaforge.audit.CrackEngine eng = engineRef;
            ft.missConfirmedFail = eng == null ? 0 : eng.confirmedFailCount();
            ft.missInconclusive = eng == null ? 0 : eng.inconclusiveCount();
            if (ft.missInconclusive > 0) {
                log(L.f("任务完成：未命中，但其中 %d 条无结论（未能证实密码错；建议核对环境后重跑）",
                        "Task complete: no match, but %d attempts were inconclusive "
                                + "(not proof of a wrong passphrase)",
                        ft.missInconclusive));
            } else {
                log(L.f("任务完成，未命中（试了 %d 条，均有失败证据）",
                        "Task complete, no match (%d tried, all with failure evidence)", tested));
            }
            if (tested > 0) {
                Prefs.recordRate(this, rateSampleMs(tested), tested);
            }
        }

        TaskQueue.update(this, ft);
        // R11：**所有终态**（命中 / 未命中 / 暂停 / 取消）都要走收尾。
        // 命中时系统里留下的就是**正确口令** —— 保留它（不删），手机保持连着、
        // 以后自动重连；其余情况则把候选留下的错误口令换成用户原来的配置。
        restoreAfterRun(ft.id, ft.state.name(), ft.state == TaskQueue.State.DONE_HIT);
        for (UiCallback cb : callbacks) cb.onTaskDone(ft, hitPassword, ft.state.name());
        notifyQueue();

        if (ft.state == TaskQueue.State.STOPPED) running = false;
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        L.s("破解进度", "Crack progress"), NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(L.s("显示破解任务的实时进度", "Shows live progress of crack tasks"));
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String title, String text, int idx, int total) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pausePi = PendingIntent.getService(this, 1,
                new Intent(this, CrackService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, CrackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "暂停", pausePi).build())
                .addAction(new Notification.Action.Builder(null, L.s("停止", "Stop"), stopPi).build());

        if (total > 0) {
            b.setProgress(total, idx, false);
        }
        return b.build();
    }

    private void updateNotification(String title, String text, int idx, int total) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification n = buildNotification(title, text, idx, total);
            if (foregroundOk) {
                nm.notify(NOTI_ID, n);
            } else {
                // 非前台模式下也更新，但不用 startForeground
                nm.notify(NOTI_ID, n);
            }
        } catch (Exception e) {
            android.util.Log.w("WPAForgeSvc", L.s("更新通知失败: ", "Failed to update notification: ") + e);
        }
    }

    // ---------------- 工具 ----------------

    private void log(String line) {
        android.util.Log.i("WPAForgeSvc", line);
        for (UiCallback cb : callbacks) cb.onLog(line);
        appendFileLog(line);
    }

    /**
     * 把日志同时写一份到文件，便于用户报障时直接发出来。
     *
     * 路径：/sdcard/Download/WPAForge-log-YYYYMMDD-HHMMSS.txt
     * 每次任务开始时新建一个文件（文件名由 {@link #ensureFileLog()} 生成）。
     * 写失败静默 —— 日志只是辅助，绝不能因为它影响主流程。
     */
    private java.io.File logFile = null;

    private void ensureFileLog() {
        if (logFile != null) return;
        try {
            // 用应用自己的外部私有目录：无需任何存储权限，root/文件管理器也能拿到。
            // 公共 Download 目录在 Android 10+ 需要 MANAGE_EXTERNAL_STORAGE，太重。
            java.io.File dir = CrackService.this.getExternalFilesDir("logs");
            if (dir == null) dir = CrackService.this.getFilesDir();
            if (dir != null && !dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            logFile = new java.io.File(dir, "WPAForge-log-" + ts + ".txt");
        } catch (Throwable ignored) {}
    }

    /**
     * 把日志复制到公共 Download 目录，方便用户直接取出发送。
     * 应用私有目录普通文件管理器不好进，这里主动导一份。
     */
    public java.io.File exportLogToDownload() {
        try {
            ensureFileLog();
            if (logFile == null || !logFile.exists()) return null;
            java.io.File dir = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            java.io.File out = new java.io.File(dir, logFile.getName());
            java.io.FileInputStream in = new java.io.FileInputStream(logFile);
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.close();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void appendFileLog(String line) {
        try {
            ensureFileLog();
            if (logFile == null) return;
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.FileWriter w = new java.io.FileWriter(logFile, true);
            w.write("[" + ts + "] " + line + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    private void notifyQueue() {
        List<TaskQueue.Task> q = TaskQueue.load(this);
        for (UiCallback cb : callbacks) cb.onQueueChanged(q);
    }

    private void stopSelfSafe() {
        try {
            // 先把通知撤掉再退出前台，否则会残留一条"正在初始化…"
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
            if (foregroundOk) stopForeground(true);
        } catch (Exception ignored) {}
        foregroundOk = false;
        stopSelf();
    }

    /** 启动服务的便捷方法 */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, CrackService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}

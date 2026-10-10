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
import io.github.wlmosv_png.wpaforge.audit.DictStore;
import io.github.wlmosv_png.wpaforge.audit.RootProber;
import io.github.wlmosv_png.wpaforge.audit.SmartSorter;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
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
    }

    private final List<UiCallback> callbacks = new ArrayList<>();
    private volatile boolean running = false;
    private Thread worker;
    private RootProber prober;
    private TaskQueue.Task currentTask;
    private List<Candidate> currentDict = new ArrayList<>();
    private long taskStartAt;

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
        createChannel();
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
            startForeground(NOTI_ID, buildNotification(L.s("准备中", "Preparing"), L.s("正在初始化…", "Initialising…"), 0, 0));
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
        super.onDestroy();
        instance = null;
        running = false;
        if (prober != null) prober.cancel();
        if (worker != null) worker.interrupt();
    }

    // ---------------- 对外接口 ----------------

    public void addCallback(UiCallback cb) {
        if (cb != null && !callbacks.contains(cb)) callbacks.add(cb);
    }

    public void removeCallback(UiCallback cb) {
        callbacks.remove(cb);
    }

    public boolean isWorking() {
        return running;
    }

    public TaskQueue.Task current() {
        return currentTask;
    }

    /** 排队一个目标 */
    public TaskQueue.Task enqueue(NetRecord target, String dictPath, String dictName) {
        TaskQueue.Task t = TaskQueue.enqueue(this, target, dictPath, dictName);
        notifyQueue();
        return t;
    }

    /** 开始/继续执行队列 */
    public void startQueue() {
        if (running) return;
        if (!TaskQueue.hasWork(this)) {
            log(L.s("队列里没有待办任务", "No pending tasks in the queue"));
            stopSelfSafe();
            return;
        }
        running = true;
        worker = new Thread(this::runQueue, "wpaforge-crack");
        worker.start();
    }

    /** 暂停：当前任务保留进度，队列原地等待 */
    public void pause() {
        if (prober != null) prober.pause();
        log("正在暂停…");
    }

    /** 全部停止：当前任务标记为已停止，队列清空执行 */
    public void stopAll() {
        if (prober != null) prober.cancel();
        log(L.s("正在停止…", "Stopping…"));
    }

    // ---------------- 队列执行 ----------------

    private void runQueue() {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) break;
                TaskQueue.Task task = TaskQueue.nextPending(this);
                if (task == null) break;
                if (task.state == TaskQueue.State.PAUSED) {
                    // 暂停状态的任务不自动跑，等用户点继续
                    log(L.s("任务「", "Task “") + task.ssid + L.s("」处于暂停，跳过", "” is paused, skipping"));
                    break;
                }
                executeTask(task);
                if (!running) break;
            }
        } catch (Exception e) {
            log(L.s("队列执行异常: ", "Queue execution error: ") + e);
        } finally {
            running = false;
            currentTask = null;
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

        // 准备候选
        if (task.dictSize <= 0) {
            List<Candidate> base;
            if (task.dictPath != null && !task.dictPath.isEmpty()) {
                java.io.File f = new java.io.File(task.dictPath);
                base = DictStore.load(f, 0);
                log(L.s("加载字典 ", "Loading dictionary ") + task.dictName + " -> " + base.size() + L.s(" 条", " entries"));
            } else {
                base = new ArrayList<>();
            }
            // 目标派生插到最前
            TargetDictBuilder.Result dr = TargetDictBuilder.build(task.toNetRecord(), 800);
            List<Candidate> merged = new ArrayList<>();
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (Candidate c : dr.candidates) if (seen.add(c.password)) merged.add(c);
            for (Candidate c : base) if (seen.add(c.password)) merged.add(c);
            // 智能排序
            currentDict = SmartSorter.prioritize(task.toNetRecord(), merged, 0);
            task.dictSize = currentDict.size();
            TaskQueue.update(this, task);
        } else {
            // 续跑：重建候选列表（保证顺序一致）
            List<Candidate> base;
            if (task.dictPath != null && !task.dictPath.isEmpty()) {
                base = DictStore.load(new java.io.File(task.dictPath), 0);
            } else {
                base = new ArrayList<>();
            }
            TargetDictBuilder.Result dr = TargetDictBuilder.build(task.toNetRecord(), 800);
            List<Candidate> merged = new ArrayList<>();
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (Candidate c : dr.candidates) if (seen.add(c.password)) merged.add(c);
            for (Candidate c : base) if (seen.add(c.password)) merged.add(c);
            currentDict = SmartSorter.prioritize(task.toNetRecord(), merged, 0);
        }

        if (currentDict.isEmpty()) {
            log(L.s("候选为空，跳过", "Empty candidate set, skipping"));
            task.state = TaskQueue.State.DONE_MISS;
            TaskQueue.update(this, task);
            notifyQueue();
            return;
        }

        taskStartAt = System.currentTimeMillis();
        final int startOffset = task.offset;
        if (startOffset > 0) {
            log(L.s("从第 ", "Resuming from ") + startOffset + L.s(" 条继续", " entries"));
        }

        prober = new RootProber(this);
        final TaskQueue.Task ft = task;

        prober.run(task.toNetRecord(), currentDict, startOffset, new RootProber.Listener() {
            @Override
            public void onAttemptStart(int index, int total, String candidate) {
                for (UiCallback cb : callbacks) cb.onAttempt(index, total, candidate);
                updateNotification("正在破解 " + ft.ssid, candidate
                        + "   " + index + "/" + total, index, total);
            }

            @Override
            public void onAttemptResult(RootProber.Attempt a) {
                boolean hit = a.verdict == RootProber.Verdict.SUCCESS;
                for (UiCallback cb : callbacks) {
                    cb.onAttemptResult(a.candidate, hit, a.elapsedMs, a.detail);
                }
            }

            @Override
            public void onProgress(int tested, int total, double rate, long etaMs) {
                for (UiCallback cb : callbacks) cb.onProgress(tested, total, rate, etaMs);
            }

            @Override
            public void onFinished(RootProber.Outcome outcome, int tested, int offset,
                                   String hitPassword) {
                ft.offset = offset;
                ft.testedThisRun += tested;
                ft.elapsedMs += System.currentTimeMillis() - taskStartAt;

                if (hitPassword != null && !hitPassword.isEmpty()) {
                    ft.hitPassword = hitPassword;
                    ft.state = TaskQueue.State.DONE_HIT;
                    log(L.s("★ 命中！", "★ HIT!") + ft.ssid + " = " + hitPassword);
                } else if (outcome == RootProber.Outcome.PAUSED) {
                    ft.state = TaskQueue.State.PAUSED;
                    log(L.s("已暂停，进度 ", "Paused at progress ") + offset + "/" + ft.dictSize);
                } else if (outcome == RootProber.Outcome.STOPPED) {
                    ft.state = TaskQueue.State.STOPPED;
                    log(L.s("已停止，进度 ", "Stopped at progress ") + offset + "/" + ft.dictSize);
                } else {
                    ft.state = TaskQueue.State.DONE_MISS;
                    log(L.s("任务完成，未命中（试了 ", "Task complete, no match (tried ") + tested + L.s(" 条）", " entries)"));
                }

                TaskQueue.update(CrackService.this, ft);
                for (UiCallback cb : callbacks) {
                    cb.onTaskDone(ft, hitPassword, ft.state.name());
                }
                notifyQueue();

                // 命中或停止则不再继续队列
                if (ft.state == TaskQueue.State.STOPPED) running = false;
            }

            @Override
            public void onHit(String ssid, String password) {
                // 已在 onFinished 里处理
            }

            @Override
            public void onBlocked(String reason) {
                log(L.s("无法开始: ", "Cannot start: ") + reason);
                ft.state = TaskQueue.State.STOPPED;
                TaskQueue.update(CrackService.this, ft);
                running = false;
            }
        });

        // 暂停时整个队列也跟着停
        if (task.state == TaskQueue.State.PAUSED) {
            log(L.s("队列已暂停", "Queue paused"));
            running = false;
        }
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
    }

    private void notifyQueue() {
        List<TaskQueue.Task> q = TaskQueue.load(this);
        for (UiCallback cb : callbacks) cb.onQueueChanged(q);
    }

    private void stopSelfSafe() {
        try {
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

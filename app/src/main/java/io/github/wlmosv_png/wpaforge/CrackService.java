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
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;
import io.github.wlmosv_png.wpaforge.audit.DictStore;
import io.github.wlmosv_png.wpaforge.audit.RootProber;
import io.github.wlmosv_png.wpaforge.audit.ShizukuRunner;
import io.github.wlmosv_png.wpaforge.audit.SpecifierProber;
import io.github.wlmosv_png.wpaforge.audit.SuggestionProber;
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
    /**
     * 免 root 执行器。**故意声明为 Object** —— SuggestionProber 引用了
     * API 30 才有的 WifiNetworkSuggestion，如果这里用具体类型，服务类
     * 一被加载就会去解析它，在低版本设备上直接 NoClassDefFoundError。
     * 用 Object 装，只在真的要跑免 root 模式时才强转（那时已确认版本）。
     */
    private Object sugProberRef;
    /** 统一引擎实例（取消/暂停走它） */
    private volatile CrackEngine engineRef;
    private TaskQueue.Task currentTask;
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

    /** 排队一个目标 */
    public TaskQueue.Task enqueue(NetRecord target, String dictPath, String dictName) {
        TaskQueue.Task t = TaskQueue.enqueue(this, target, dictPath, dictName);
        notifyQueue();
        return t;
    }

    /** 开始/继续执行队列 */
    public void startQueue() {
        if (worker != null && worker.isAlive()) {
            log(L.s("已有任务在运行", "A task is already running"));
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
        if (prober != null) prober.pause();
        safeInvoke("pause");
        // 立刻把当前任务标 PAUSED，UI 马上能看到
        TaskQueue.Task cur = currentTask;
        if (cur != null && cur.state == TaskQueue.State.RUNNING) {
            cur.state = TaskQueue.State.PAUSED;
            TaskQueue.update(this, cur);
            notifyQueue();
        }
        log(L.s("正在暂停…（进度会保留）", "Pausing… (progress kept)"));
    }

    /** 安全调用免 root 执行器的方法（它可能是 null，也可能类型不可用） */
    private void safeInvoke(String method) {
        Object o = sugProberRef;
        if (o == null) return;
        try {
            o.getClass().getMethod(method).invoke(o);
        } catch (Throwable ignored) {}
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

        if (prober != null) prober.cancel();
        safeInvoke("cancel");
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

    private void runQueue() {
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
            running = false;
            currentTask = null;
            log(L.s("队列已结束", "Queue finished"));
            notifyQueue();
            stopSelfSafe();
        }
    }

    private void executeTask(TaskQueue.Task task) {
        currentTask = task;
        task.state = TaskQueue.State.RUNNING;
        TaskQueue.update(this, task);
        notifyQueue();

        log(L.s("===== 开始任务: ", "===== Starting task: ") + task.ssid + " =====");

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
                    base = new DictGenerator(task.dictSize)
                            .classic().numericPatterns().phoneSegments()
                            .wordVariants().wordDigits()
                            .build();
                    log(L.f("重建内置字典 %s -> %d 条",
                            "Rebuilt built-in dictionary %s -> %d entries",
                            task.dictName, base.size()));
                } else {
                    base = new ArrayList<>();
                }
            }

            List<Candidate> work = base;

            if (task.useTargetDict) {
                TargetDictBuilder.Result dr = TargetDictBuilder.build(task.toNetRecord(), 800);
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
                work = SmartSorter.prioritize(task.toNetRecord(), work, 0);
                log(L.s("智能排序: 已启用", "Smart ranking: on"));
            } else {
                log(L.s("智能排序: 已关闭", "Smart ranking: off"));
            }

            currentDict = work;
            if (task.dictSize <= 0) {
                task.dictSize = currentDict.size();
                TaskQueue.update(this, task);
            }
        }

        if (currentDict.isEmpty()) {
            log(L.s("候选为空，跳过", "Empty candidate set, skipping"));
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
     * Shizuku / Root 走 shell 通道，免 Root 走系统精确连接。
     * 判定全在引擎里，不分正两份。
     */
    private void runViaEngine(final TaskQueue.Task ft, int startOffset) {
        logFile = null;   // 每次任务新建一个日志文件
        final CrackEngine engine = new CrackEngine(this, ft.crackMode);
        engineRef = engine;

        if (ft.crackMode != Prefs.MODE_NO_ROOT) {
            final boolean useShizuku = ft.crackMode == Prefs.MODE_SHIZUKU;
            engine.setShellChannel(new CrackEngine.ShellChannel() {
                @Override
                public String exec(String cmd, long timeoutMs) {
                    if (useShizuku) return ShizukuRunner.exec(cmd, timeoutMs);
                    return RootProber.exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                }
                @Override
                public void forgetTarget(String ssid) {
                    // 删净旧配置 + 回读校验
                    //
                    // 解析统一走 CrackEngine.parseNetIdLine：**不能**再用
                    // split("\s+") 取 parts[1] 当 SSID —— SSID 含空格
                    // （"My Net 5G"）会匹配失败，配置删不掉，
                    // 系统随后拿已保存的旧密码连上，判定就成了假阳性。
                    for (int round = 0; round < 3; round++) {
                        String list = exec("cmd wifi list-networks", 4000);
                        if (list == null) return;
                        long leftover = -1;
                        for (String line : list.split("\n")) {
                            Long id = io.github.wlmosv_png.wpaforge.audit.CrackEngine
                                    .parseNetIdLine(line, ssid);
                            if (id != null) { leftover = id; break; }
                        }
                        if (leftover < 0) {
                            log(L.f("配置已清干净: %s", "config cleared: %s", ssid));
                            return;
                        }
                        exec("cmd wifi forget-network " + leftover, 4000);
                        log(L.f("清除旧配置: %s netId=%s",
                                "forgot stale config: %s netId=%s", ssid, leftover));
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                    log(L.s("警告: 旧配置可能未删净，结果可能不准",
                            "Warning: stale config may remain; results may be unreliable"));
                }
                @Override
                public String name() { return useShizuku ? "Shizuku" : "Root"; }
            });
        }

        log(L.f("引擎模式: %s", "Engine mode: %s",
                ft.crackMode == Prefs.MODE_NO_ROOT ? L.s("免 Root", "root-free")
                        : (ft.crackMode == Prefs.MODE_SHIZUKU ? "Shizuku" : "Root")));

        engine.run(ft.toNetRecord(), currentDict, startOffset, 0L, new CrackEngine.Listener() {
            @Override
            public void onAttemptStart(int index, int total, String candidate) {
                for (UiCallback cb : callbacks) cb.onAttempt(index, total, candidate);
                updateNotification(L.f("正在破解 %s", "Cracking %s", ft.ssid),
                        candidate + "   " + index + "/" + total, index, total);
            }

            @Override
            public void onAttemptResult(CrackEngine.Attempt a) {
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
                running = false;
                notifyQueue();
                stopSelfSafe();
            }
        });
    }

    /** 免 root 模式执行。低版本设备上会直接拒绝，不加载任何 API 30 的类。 */
    private void runNoRoot(final TaskQueue.Task ft, int startOffset) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            log(L.s("免 Root 模式需要 Android 11 或更高版本",
                    "Root-free mode requires Android 11 or higher"));
            ft.state = TaskQueue.State.STOPPED;
            TaskQueue.update(this, ft);
            running = false;
            notifyQueue();
            stopSelfSafe();
            return;
        }
        try {
            runNoRootImpl(ft, startOffset);
        } catch (Throwable t) {
            // 类加载失败等各种情况都不该让应用挂掉
            log(L.f("免 Root 模式不可用: %s", "Root-free mode unavailable: %s",
                    t.getClass().getSimpleName()));
            ft.state = TaskQueue.State.STOPPED;
            TaskQueue.update(this, ft);
            running = false;
            notifyQueue();
            stopSelfSafe();
        }
    }

    /**
     * 真正用到 SuggestionProber 的地方。
     * 只有版本 >= 30 才会走到这里，JVM 才会去解析 WifiNetworkSuggestion。
     */
    @android.annotation.SuppressLint("NewApi")
    private void runNoRootImpl(final TaskQueue.Task ft, int startOffset) {
        // **免 Root 改走 WifiNetworkSpecifier + requestNetwork**
        //
        // 旧的 WifiNetworkSuggestion 是「白名单」：提交后由系统自己挑时间连，
        // 应用根本不知道它试没试过 —— 判定只能靠猜，必然误判。
        // requestNetwork 则是「指定这一个 SSID + 这一个密码，立刻去连」，
        // 成功失败由 onAvailable / onUnavailable 明确回调。
        final SpecifierProber spec = new SpecifierProber(this);
        sugProberRef = spec;
        log(L.s("模式: 免 Root（系统精确连接）", "Mode: root-free (precise request)"));

        spec.run(ft.toNetRecord(), currentDict, startOffset, 0L, new SpecifierProber.Listener() {
            @Override
            public void onAttemptStart(int index, int total, String candidate) {
                for (UiCallback cb : callbacks) cb.onAttempt(index, total, candidate);
                updateNotification(L.f("正在破解 %s", "Cracking %s", ft.ssid),
                        candidate + "   " + index + "/" + total, index, total);
            }

            @Override
            public void onAttemptResult(SpecifierProber.Result r) {
                boolean hit = r.isSuccess();
                for (UiCallback cb : callbacks) {
                    cb.onAttemptResult(r.password, hit, r.elapsedMs, r.detail);
                }
            }

            @Override
            public void onProgress(int tested, int total, double rate, long etaMs) {
                for (UiCallback cb : callbacks) cb.onProgress(tested, total, rate, etaMs);
            }

            @Override
            public void onLog(String line) {
                log(line);
            }

            @Override
            public void onFinished(int tested, int offset, String hitPassword) {
                sugProberRef = null;
                String outcome;
                if (hitPassword != null && !hitPassword.isEmpty()) {
                    outcome = "NORMAL";
                } else if (spec.isPaused()) {
                    outcome = "PAUSED";
                } else if (ft.state == TaskQueue.State.STOPPED) {
                    outcome = "STOPPED";
                } else {
                    outcome = "NORMAL";
                }
                finishTask(ft, outcome, tested, offset, hitPassword);
            }

            @Override
            public void onBlocked(String title, String detail) {
                log(L.f("无法开始: %s", "Cannot start: %s", title));
                for (UiCallback cb : callbacks) cb.onLog(title + "\n" + detail);
                sugProberRef = null;
                ft.state = TaskQueue.State.STOPPED;
                TaskQueue.update(CrackService.this, ft);
                running = false;
                notifyQueue();
                stopSelfSafe();
            }
        });
    }

    /** 统一收尾 */
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
    private void saveProgress(TaskQueue.Task ft, int offset, boolean force) {
        if (ft == null || offset <= 0) return;
        long now = System.currentTimeMillis();
        boolean changed = offset != lastSavedOffset;
        boolean due = (now - lastOffsetSaveAt >= 2000L)
                || (offset - lastSavedOffset >= 20);
        if (!force && (!changed || !due)) return;
        ft.offset = offset;
        ft.elapsedMs = now - taskStartAt;
        TaskQueue.update(this, ft);
        lastOffsetSaveAt = now;
        lastSavedOffset = offset;
    }

    private void finishTask(TaskQueue.Task ft, String outcomeName,
                            int tested, int offset, String hitPassword) {
        ft.offset = offset;
        ft.testedThisRun += tested;
        ft.elapsedMs += System.currentTimeMillis() - taskStartAt;

        if (hitPassword != null && !hitPassword.isEmpty()) {
            ft.hitPassword = hitPassword;
            ft.state = TaskQueue.State.DONE_HIT;
            log(L.f("任务结束: %s 已命中（试了 %d 条）",
                    "Task finished: %s hit (%d tried)", ft.ssid, tested));
            // 记录实测速度
            if (tested > 0) {
                Prefs.recordRate(this, rateSampleMs(tested), tested);
            }
        } else if ("PAUSED".equals(outcomeName)) {
            ft.state = TaskQueue.State.PAUSED;
            log(L.f("已暂停，进度 %d/%d", "Paused at %d/%d", offset, ft.dictSize));
        } else if ("STOPPED".equals(outcomeName)) {
            ft.state = TaskQueue.State.STOPPED;
            log(L.f("已停止，进度 %d/%d", "Stopped at %d/%d", offset, ft.dictSize));
        } else {
            ft.state = TaskQueue.State.DONE_MISS;
            log(L.f("任务完成，未命中（试了 %d 条）",
                    "Task complete, no match (%d tried)", tested));
            if (tested > 0) {
                Prefs.recordRate(this, rateSampleMs(tested), tested);
            }
        }

        TaskQueue.update(this, ft);
        for (UiCallback cb : callbacks) cb.onTaskDone(ft, hitPassword, ft.state.name());
        notifyQueue();

        if (ft.state == TaskQueue.State.STOPPED) running = false;
    }

    /** 走 shell 通道执行（Root 或 Shizuku） */
    private void runWithRoot(final TaskQueue.Task ft, int startOffset) {
        boolean shizuku = ft.crackMode == Prefs.MODE_SHIZUKU;
        prober = new RootProber(this, shizuku);
        log(shizuku
                ? L.s("模式: Shizuku（以 shell 身份执行 cmd wifi）",
                      "Mode: Shizuku (cmd wifi as shell user)")
                : L.s("模式: Root（通过 su 执行 cmd wifi）",
                      "Mode: root (cmd wifi via su)"));

        prober.run(ft.toNetRecord(), currentDict, startOffset, new RootProber.Listener() {
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
                    log(L.f("任务结束: %s 已命中（试了 %d 条）",
                            "Task finished: %s hit (%d tried)", ft.ssid, tested));
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

            @Override
            public void onLog(String line) {
                log(line);
            }
        });

        // 暂停时整个队列也跟着停
        if (ft.state == TaskQueue.State.PAUSED) {
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

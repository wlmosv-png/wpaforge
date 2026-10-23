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
import io.github.wlmosv_png.wpaforge.audit.ShellSession;
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
        if (prober != null) prober.pause();
        safeInvoke("pause");
        // ★ 最关键的一步：把「暂停」真正送到引擎。
        //
        // 历史事故（2026-10-23 实机复现）：这里以前**只**通知免-root
        // 执行器（prober.pause() 与反射调 sugProberRef），而 Root / Shizuku 模式下
        // 那两个引用都是 null —— 于是 pause() 退化成纯 UI 动作：队列标成
        // PAUSED、日志写「正在暂停…」，引擎毫不知情、继续逐条往下跑。
        //
        // 当天实测：08:38:21 点暂停，日志仍每 2~6 秒一条推进到 08:40:33，
        // 队列 offset 25 秒里从 82 涨到 88 —— 界面「已暂停」，实际全速。
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
                TargetDictBuilder.Result dr = TargetDictBuilder.build(task.toNetRecord(), 800,
                        io.github.wlmosv_png.wpaforge.scan.OuiLookup.vendor(this, task.bssid));
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
                    String out;
                    if (useShizuku) {
                        out = ShizukuRunner.exec(cmd, timeoutMs);
                    } else {
                        out = sessOff[0] ? null : sess.exec(cmd, timeoutMs);
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
                    }
                    // ---- 通道熔断 ----
                    // 连续多次命令超时，说明通道已经不可用（进程被系统限制、su 被反复卡住……）。
                    // 这时继续跑只会让每条候选都耗在超时上 —— 界面看着像「在跑」，
                    // 实际一步没动。与其无声卡住，不如停下来把情况讲清楚。
                    if (!useShizuku) {
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
                // 心跳：回到前台时用它判断「后台期间是不是被系统挂起过」
                Prefs.beat(CrackService.this, System.currentTimeMillis());
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

    /**
     * 真正用到 SuggestionProber 的地方。
     * 只有版本 >= 30 才会走到这里，JVM 才会去解析 WifiNetworkSuggestion。
     */
    @android.annotation.SuppressLint("NewApi")
    /** 统一收尾 */
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

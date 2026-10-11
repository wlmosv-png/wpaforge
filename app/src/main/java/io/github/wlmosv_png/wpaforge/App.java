package io.github.wlmosv_png.wpaforge;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用入口。
 *
 * 这里做两件防护，目标都是**让应用在任何环境都能打开**：
 *
 *   1. 关闭 Shizuku 的自动 Sui 初始化
 *      ShizukuProvider 默认会尝试初始化 Sui，设备上没装/版本不匹配时
 *      可能抛异常。我们的 Shizuku 支持不依赖 Sui，关掉更安全。
 *
 *   2. 装一个全局未捕获异常处理器
 *      即使真的崩了，也把堆栈写进文件，用户能拿来反馈，
 *      不用非得连电脑抓 logcat。
 */
public class App extends Application {

    private static final String TAG = "WPAForge";

    /**
     * 日志子目录名。
     *
     * 放在应用自己的外部目录下（getExternalFilesDir），因为
     * targetSdk 30+ 的 scoped storage 下应用无权直接写 /sdcard/Download ——
     * 实测对那个路径 open 会返回 Permission denied。
     * 应用私有外部目录则任何 ROM 上都必然可写，用户也能在
     * 文件管理器里找到（Android/data/<包名>/files/）。
     */
    private static final String LOG_SUBDIR = "logs";

    @Override
    public void onCreate() {
        super.onCreate();

        disableSuiSafely();
        registerShizukuEarly();
        probeShizukuChannel();
        // 后台自动探测一次可用模式（没设过的话就按探测结果落盘），
        // 避免默认值把「没 root 的手机」定成 Root 模式
        Prefs.warmUp(this);

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashLog(throwable);
            if (prev != null) prev.uncaughtException(thread, throwable);
        });

        // 启动自证：把环境信息写一份，用户不接电脑也能反馈
        writeStartupLog();
    }

    /**
     * 写启动日志。
     *
     * 目的很明确：**让应用自己把环境说清楚**。
     * 之前排查"列表空"只能靠连电脑抓 logcat，用户拿不到日志就没法定位。
     * 现在每次启动都会覆盖写一份，里面包含权限、WiFi 状态、特权通道可用性。
     */
    private void writeStartupLog() {
        new Thread(() -> {
            try {
                File dir = logDir();
                File f = new File(dir, "startup-last.txt");

                StringBuilder sb = new StringBuilder();
                sb.append("WPAForge 启动自检\n");
                sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.US).format(new Date())).append('\n');
                sb.append("设备: ").append(Build.MANUFACTURER).append(' ')
                        .append(Build.MODEL).append('\n');
                sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("应用: ").append(getPackageName()).append('\n');
                try {
                    sb.append("版本: ").append(getPackageManager()
                            .getPackageInfo(getPackageName(), 0).versionName).append('\n');
                } catch (Throwable ignored) {}
                sb.append('\n');

                // 权限
                sb.append("-- 权限 --\n");
                for (String p : new String[]{
                        "android.permission.NEARBY_WIFI_DEVICES",
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_WIFI_STATE",
                        "android.permission.CHANGE_WIFI_STATE",
                        "android.permission.POST_NOTIFICATIONS"}) {
                    int r = checkSelfPermission(p);
                    sb.append(r == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ? "  [有] " : "  [缺] ").append(p).append('\n');
                }
                sb.append('\n');

                // WiFi 与扫描
                sb.append("-- 网络 --\n");
                try {
                    android.net.wifi.WifiManager wm =
                            (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
                    sb.append("  WiFi 开关: ").append(wm != null && wm.isWifiEnabled()
                            ? "开" : "关").append('\n');
                    if (wm != null) {
                        int n = -1;
                        try {
                            java.util.List<?> res = wm.getScanResults();
                            n = res == null ? -1 : res.size();
                        } catch (Throwable t) {
                            sb.append("  getScanResults 抛异常: ")
                                    .append(t.getClass().getSimpleName()).append('\n');
                        }
                        sb.append("  getScanResults 条数: ").append(n).append('\n');
                    } else {
                        sb.append("  WifiManager 不可用\n");
                    }
                } catch (Throwable t) {
                    sb.append("  WiFi 查询失败: ").append(t.getClass().getSimpleName()).append('\n');
                }
                sb.append('\n');

                // 特权通道
                sb.append("-- 特权通道 --\n");
                try {
                    boolean shizukuAlive = io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isAvailable();
                    boolean shizukuGranted = shizukuAlive
                            && io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isGranted();
                    sb.append("  Shizuku 服务: ").append(shizukuAlive ? "在" : "不在（没跑或没装）").append('\n');
                    if (shizukuAlive) {
                        sb.append("  Shizuku 授权: ").append(shizukuGranted ? "已授权" : "未授权").append('\n');
                        sb.append("  Shizuku 版本: ")
                                .append(io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.version()).append('\n');
                        sb.append("  Shizuku 通道实测: ").append(shizukuProbeResult).append('\n');
                        sb.append("  Wi-Fi Binder 通道: ").append(wifiApiProbeResult).append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("  Shizuku 查询失败: ").append(t.getClass().getSimpleName()).append('\n');
                }
                try {
                    boolean hasPriv = io.github.wlmosv_png.wpaforge.scan.PrivScanReader.hasPrivilegedChannel();
                    sb.append("  特权扫描通道可用: ").append(hasPriv ? "是" : "否").append('\n');
                } catch (Throwable t) {
                    sb.append("  特权通道查询失败: ").append(t.getClass().getSimpleName()).append('\n');
                }

                // 引擎通道自检（含判定信号可用性）
                //
                // 用户反复遇到「密码对却识别不到」，而线上看不到任何证据。
                // 现在每次启动都把它写进这份文件，报障时直接发文件就行。
                try {
                    sb.append('\n').append("-- 引擎通道自检 --\n");
                    int mode = io.github.wlmosv_png.wpaforge.Prefs.mode(App.this);
                    sb.append("  当前模式: ")
                            .append(mode == io.github.wlmosv_png.wpaforge.Prefs.MODE_NO_ROOT
                                    ? "免 Root"
                                    : (mode == io.github.wlmosv_png.wpaforge.Prefs.MODE_SHIZUKU
                                            ? "Shizuku" : "Root"))
                            .append('\n');
                    io.github.wlmosv_png.wpaforge.audit.CrackEngine eng =
                            new io.github.wlmosv_png.wpaforge.audit.CrackEngine(App.this, mode);
                    if (mode != io.github.wlmosv_png.wpaforge.Prefs.MODE_NO_ROOT) {
                        final boolean useShizuku =
                                mode == io.github.wlmosv_png.wpaforge.Prefs.MODE_SHIZUKU;
                        eng.setShellChannel(new io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel() {
                            @Override
                            public String exec(String cmd, long timeoutMs) {
                                if (useShizuku) {
                                    return io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.exec(cmd, timeoutMs);
                                }
                                return io.github.wlmosv_png.wpaforge.audit.RootProber
                                        .exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
                            }
                            @Override public void forgetTarget(String ssid) { }
                            @Override public String name() { return useShizuku ? "Shizuku" : "Root"; }
                        });
                    }
                    io.github.wlmosv_png.wpaforge.audit.CrackEngine.ChannelProbe cp = eng.probeChannel();
                    // 拼成一个字符串再一次性追加。
                    // 之前逐行 append 出现过 steps 全部空白的情况，
                    // 排查成本太高，改成先 join 再写。
                    StringBuilder ps = new StringBuilder();
                    ps.append("  结论: ")
                      .append(cp.ok ? "已接入" : "未接入")
                      .append(" (").append(cp.summary).append(')').append('\n');
                    ps.append("  检查项 ").append(cp.steps.size()).append(" 条:").append('\n');
                    for (int i = 0; i < cp.steps.size(); i++) {
                        ps.append("    [").append(i + 1).append("] ")
                          .append(cp.steps.get(i)).append('\n');
                    }
                    if (!cp.detail.isEmpty()) {
                        ps.append("  详情:").append('\n');
                        ps.append(cp.detail).append('\n');
                    }
                    sb.append(ps);
                } catch (Throwable t) {
                    sb.append("  引擎自检异常: ")
                            .append(t.getClass().getSimpleName()).append('\n');
                }

                try (FileWriter w = new FileWriter(safeTruncate(f))) {
                    w.write(sb.toString());
                }
                Log.i(TAG, "启动自检已写入 " + f.getAbsolutePath());
            } catch (Throwable t) {
                // 不能再静默吞掉了 —— 之前就是这样导致"文件没出来但不知道为啥"。
                // 把异常本身也尽量落盘。
                Log.e(TAG, "启动自检写入失败", t);
                try {
                    File dir = logDir();
                    try (FileWriter w = new FileWriter(safeTruncate(new File(dir, "startup-error.txt")))) {
                        w.write("启动自检写入失败\n");
                        w.write(t.toString() + "\n\n");
                        java.io.StringWriter sw = new java.io.StringWriter();
                        t.printStackTrace(new java.io.PrintWriter(sw));
                        w.write(sw.toString());
                    }
                } catch (Throwable ignored) {}
            }
        }, "wpaforge-startup-log").start();
    }

    /**
     * 关掉 Shizuku 的自动 Sui 初始化。
     *
     * 用 try-catch 包住：如果 Shizuku 的类不可用（没集成、被裁剪），
     * 也不该影响应用启动。低版本 Android 上跳过。
     */
    /**
     * 尽早在进程启动时注册 Shizuku 的 binder 监听。
     *
     * 为什么必须在 Application 里做：
     *   Shizuku 的 binder 是**异步推**给客户端的。如果等到用户点进
     *   设置页才注册，那么「服务已经跑起来、binder 也已经推过」的
     *   情况就完全错过了 —— pingBinder() 永远 false，界面永远显示
     *   「服务未运行」，哪怕用户在 Shizuku 里看到开关是开的。
     *
     *   sticky 版本在注册时会立刻检查一次当前 binder 状态，
     *   所以早注册等于不会丢事件。
     */
    private void registerShizukuEarly() {
        try {
            io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.registerListeners();
        } catch (Throwable t) {
            Log.i(TAG, "Shizuku 监听注册跳过: " + t.getClass().getSimpleName());
        }
    }

    /** 自测结果（写进启动自检，用户不用连电脑就能看到） */
    private static volatile String shizukuProbeResult = "尚未检测";

    /** Wi-Fi Binder 通道自测结果 */
    private static volatile String wifiApiProbeResult = "尚未检测";

    public static String wifiApiProbe() {
        return wifiApiProbeResult;
    }

    public static String shizukuProbe() {
        return shizukuProbeResult;
    }

    /**
     * 等 binder 就绪后真跑一条 cmd wifi，确认通道可用。
     *
     * 为什么要自测：
     *   “能 ping 到 binder”与“能执行命令”是两回事。
     *   之前只看 pingBinder()，结果 binder 就绪了但命令还是可能跑不通，
     *   用户只能在点开始后才发现。现在启动就把结果落盘。
     */
    private void probeShizukuChannel() {
        new Thread(() -> {
            try {
                // 等一等，binder 是异步推过来的
                for (int i = 0; i < 40; i++) {
                    if (io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isAvailable()) break;
                    Thread.sleep(250);
                }
                if (!io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isAvailable()) {
                    shizukuProbeResult = "服务未就绪（binder 拿不到）";
                    return;
                }
                if (!io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.isGranted()) {
                    shizukuProbeResult = "服务在，但未授权本应用";
                    return;
                }
                String out = io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.exec("cmd wifi help", 8000);
                if (out == null) {
                    shizukuProbeResult = "已授权，但命令执行失败";
                } else if (out.contains("connect-network")) {
                    shizukuProbeResult = "可用（cmd wifi connect-network 已确认）";
                } else if (out.trim().isEmpty()) {
                    shizukuProbeResult = "命令返回空";
                } else {
                    shizukuProbeResult = "部分可用（cmd wifi 有响应，但未见 connect-network）";
                }

                // **关键自测**：Binder 直调 addOrUpdateNetwork 能不能走通。
                // 这是修「任何密码都判成功」的核心路径，
                // 不跑通就不能发包。
                try {
                    io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.WifiApiProbe pr =
                            io.github.wlmosv_png.wpaforge.audit.ShizukuRunner.probeWifiApi();
                    wifiApiProbeResult = pr.message;
                } catch (Throwable t) {
                    wifiApiProbeResult = "异常: " + t.getClass().getSimpleName();
                }
            } catch (Throwable t) {
                shizukuProbeResult = "自测异常: " + t.getClass().getSimpleName();
            }
            writeStartupLog();
        }, "wpaforge-shizuku-probe").start();
    }

    private void disableSuiSafely() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> c = Class.forName("rikka.shizuku.ShizukuProvider");
            c.getMethod("disableAutomaticSuiInitialization").invoke(null);
            Log.i(TAG, "已关闭 Shizuku 自动 Sui 初始化");
        } catch (Throwable t) {
            // 正常情况：没集成 Shizuku，或类被裁剪
            Log.i(TAG, "跳过 Sui 初始化设置: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 返回一个可以安全写入的文件。
     *
     * ColorOS 的存储层上，直接对**已存在**的文件 new FileWriter(f)
     * 会返回 `open failed: EEXIST (File exists)` 而失败 ——
     * 不是权限问题，是这一层的实现差异。
     * 所以覆盖写之前必须先把旧文件删掉。
     */
    private static File safeTruncate(File f) {
        try {
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
        return f;
    }

    /** 日志目录（应用私有外部目录下的 logs/），保证可写 */
    private File logDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File dir = new File(base, LOG_SUBDIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 把崩溃堆栈写到外部目录，方便用户反馈 */
    private void writeCrashLog(Throwable t) {
        try {
            File dir = logDir();
            File f = new File(dir, "crash-" + System.currentTimeMillis() + ".txt");

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.US).format(new Date()));
            pw.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL);
            pw.println("系统: Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println();
            t.printStackTrace(pw);
            pw.flush();

            try (FileWriter w = new FileWriter(f)) {
                w.write(sw.toString());
            }
            Log.e(TAG, "崩溃日志已写入 " + f.getAbsolutePath());
        } catch (Throwable ignored) {
            // 写日志本身失败就算了，不能让处理崩溃的过程再崩一次
        }
    }
}

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

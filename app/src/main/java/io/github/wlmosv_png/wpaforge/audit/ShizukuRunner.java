package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 执行通道。
 *
 * Shizuku 让普通应用以 **shell（uid 2000）** 身份执行命令 ——
 * 这正是 cmd wifi 需要的权限级别。所以它跑的和 root 模式
 * 是同一条命令、同样的速度，但**不需要 root**（只需 ADB 激活一次）。
 *
 * ============================ 集成要求 ============================
 * 缺任何一样都会失败，而且应用**不会出现在 Shizuku 的授权列表里**：
 *
 *   1. AndroidManifest 声明
 *        <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
 *   2. application 内加
 *        <meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />
 *   3. application 内注册 provider
 *        <provider android:name="rikka.shizuku.ShizukuProvider"
 *                  android:authorities="${applicationId}.shizuku" ... />
 *      —— Shizuku 的 binder 是通过这个 ContentProvider 递给客户端的，
 *         不是普通 Service。没有它，getBinder() 永远拿不到东西。
 *   4. 依赖 app/libs/shizuku-{api,provider}.jar
 *
 * ============================ 关于 newProcess =====================
 * Shizuku 把 `newProcess(String[], String[], String)` 标成了 **private**，
 * 且注释写明将在 API 14 移除。所以只能用反射 + setAccessible。
 * （同类项目也是这么做的。）
 *
 * 早期版本我写错了签名（参数个数、checkSelfPermission 带参），
 * 导致反射必然抛 NoSuchMethodException，表现为"Shizuku 不可用"。
 */
public final class ShizukuRunner {

    private static final String TAG = "WPAForgeShizuku";

    private ShizukuRunner() {}

    // ---------------- 基础状态 ----------------

    /** 服务在不在（能连上 binder 就算在） */
    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 已知包名（不同版本/分支可能不同） */
    private static final String[] PKGS = {
            "moe.shizuku.privileged.api",
            "moe.shizuku.manager",
            "rikka.shizuku",
    };

    /**
     * Shizuku 是否已安装。
     *
     * Android 11+ 有包可见性限制，必须在 manifest 里用 &lt;queries&gt;
     * 声明包名才能查到 —— 否则永远返回 false，用户会看到
     * "明明装了却让我去下载"。
     */
    public static boolean isInstalled(Context ctx) {
        for (String pkg : PKGS) {
            try {
                ctx.getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (Exception ignored) {}
        }
        return isAvailable();   // 兜底：服务能连上说明肯定装了
    }

    /** 是否已授权（checkSelfPermission 是无参的） */
    public static boolean isGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 服务版本号 */
    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable e) {
            return -1;
        }
    }

    public static boolean isPreV11() {
        try {
            return Shizuku.isPreV11();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 申请授权（会弹 Shizuku 的授权框） */
    public static boolean requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
            return true;
        } catch (Throwable e) {
            android.util.Log.w(TAG, "requestPermission 失败: " + e);
            return false;
        }
    }

    // ======================= 监听器 =======================
    //
    // 这是之前最大的坑：**Shizuku 的 binder 是异步推给客户端的**。
    // 应用启动那一刻如果 Shizuku 服务还没起来（或刚重启过），
    // pingBinder() 会一直返回 false，而它**不会自己重试** ——
    // 于是界面上永远显示「服务未运行」，哪怕用户在 Shizuku 里
    // 明明看到 WPAForge 的开关是打开的。
    //
    // 正解是注册 sticky 监听：注册时若 binder 已经就绪会立即回调，
    // 之后每次服务重启也会再回调一次。

    private static boolean listenersRegistered = false;

    /** 外部（UI）挂的回调：binder 就绪 / 掉线时会被通知 */
    public interface StateListener {
        void onBinderReady();
        void onBinderDead();
    }

    private static final java.util.List<StateListener> stateListeners = new java.util.ArrayList<>();

    public static void addStateListener(StateListener l) {
        if (l != null && !stateListeners.contains(l)) stateListeners.add(l);
    }

    public static void removeStateListener(StateListener l) {
        stateListeners.remove(l);
    }

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED =
            () -> {
                android.util.Log.i(TAG, "binder 已就绪");
                for (StateListener l : new java.util.ArrayList<>(stateListeners)) {
                    try { l.onBinderReady(); } catch (Throwable ignored) {}
                }
            };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD =
            () -> {
                android.util.Log.i(TAG, "binder 已断开");
                for (StateListener l : new java.util.ArrayList<>(stateListeners)) {
                    try { l.onBinderDead(); } catch (Throwable ignored) {}
                }
            };

    private static final Shizuku.OnRequestPermissionResultListener PERM_RESULT =
            (requestCode, grantResult) -> {
                android.util.Log.i(TAG, "授权结果 req=" + requestCode + " grant=" + grantResult);
                for (StateListener l : new java.util.ArrayList<>(stateListeners)) {
                    try { l.onBinderReady(); } catch (Throwable ignored) {}
                }
            };

    /**
     * 注册三个监听器。幂等，重复调用不会重复注册。
     *
     * 必须尽早调用（Application.onCreate 或 Activity.onCreate）。
     * 用 sticky 版本：binder 已经就绪时会**立即**触发一次回调，
     * 这样即使用户是先启动 Shizuku 再打开本应用，也能第一时间反映出来。
     */
    public static void registerListeners() {
        if (listenersRegistered) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED);
            Shizuku.addBinderDeadListener(BINDER_DEAD);
            // 授权结果走这个 listener，**不是** Activity.onRequestPermissionsResult
            Shizuku.addRequestPermissionResultListener(PERM_RESULT);
            listenersRegistered = true;
            android.util.Log.i(TAG, "监听器已注册");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "registerListeners 失败: " + t);
        }
    }

    // ---------------- 执行 ----------------

    /** 缓存反射到的方法，避免每次调用都查找 */
    private static Method cachedNewProcess;

    /**
     * 反射拿到 private static 的 newProcess。
     * 真实签名：newProcess(String[] cmd, String[] env, String dir)
     */
    private static Method newProcessMethod() throws Exception {
        if (cachedNewProcess != null) return cachedNewProcess;
        Class<?> c = Shizuku.class;
        Method m = c.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);          // 它是 private
        cachedNewProcess = m;
        return m;
    }

    /**
     * 以 shell 身份执行命令，返回 stdout。失败返回 null。
     *
     * @param timeoutMs 读取输出的时间上限（读完后会用 destroy 收尾）
     */
    public static String exec(String cmd, long timeoutMs) {
        Process proc = null;
        try {
            if (!isAvailable()) {
                android.util.Log.w(TAG, "服务未运行");
                return null;
            }

            Method np = newProcessMethod();
            Object o = np.invoke(null, new String[]{"sh", "-c", cmd}, null, "/");
            if (!(o instanceof Process)) {
                android.util.Log.w(TAG, "newProcess 返回类型异常: "
                        + (o == null ? "null" : o.getClass().getName()));
                return null;
            }
            proc = (Process) o;

            InputStream in = proc.getInputStream();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                        if (System.currentTimeMillis() > deadline) break;
                    }
                }
            }

            // 读完输出再等退出（可能被外部打断，忽略）
            try {
                proc.waitFor(200, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}

            return sb.toString();
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            android.util.Log.w(TAG, "exec 失败 (" + cmd + "): "
                    + cause.getClass().getSimpleName() + " " + cause.getMessage());
            return null;
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
        }
    }
}

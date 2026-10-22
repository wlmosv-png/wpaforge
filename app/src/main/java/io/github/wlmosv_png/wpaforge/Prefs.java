package io.github.wlmosv_png.wpaforge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 破解模式与速度统计的持久化。
 *
 * 拆出来单独放，避免都堆在 L 里（L 只管文案）。
 */
public final class Prefs {

    private static final String NAME = "wpaforge_prefs";

    /** 破解模式 */
    public static final int MODE_ROOT = 0;         // su + cmd wifi
    public static final int MODE_NO_ROOT = 1;      // 系统 API（手动确认）
    public static final int MODE_SHIZUKU = 2;      // Shizuku + cmd wifi（免 root，快）

    private static final String K_MODE = "crack_mode";
    private static final String K_PIN = "app_pin_sha256";   // 空表示未设 PIN
    private static final String K_RATE = "avg_rate_ms";     // 平均每条耗时（毫秒）
    private static final String K_RATE_N = "rate_samples";  // 样本数
    private static final String K_PIN_BSSID = "pin_bssid";   // 连接锁定到选中的那个 AP
    private static final String K_MAC_ROTATE = "mac_rotate";  // 每次尝试换随机 MAC
    private static final String K_LAST_BEAT = "last_beat_ms";  // 引擎心跳（检测被系统挂起）

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------------- 引擎心跳 ----------------

    /**
     * 引擎每条候选都会调一次，用来判断「切到后台期间是不是被系统挂起过」。
     * 回到前台时如果发现心跳间隔远大于正常值，就说明中间被冻过/卡过。
     */
    public static void beat(Context ctx, long nowMs) {
        sp(ctx).edit().putLong(K_LAST_BEAT, nowMs).apply();
    }

    public static long lastBeat(Context ctx) {
        return sp(ctx).getLong(K_LAST_BEAT, 0L);
    }

    // ---------------- 在线连接选项 ----------------

    /**
     * 是否把连接锁定到目标那一个 AP（命令行加 -b <bssid>）。
     *
     * 同名 SSID 有多个 AP 时，不锁定就等于把选择权交给系统 —— 它可能挑到
     * 信号最差的那个。默认关闭，保持与旧版本一致的行为。
     */
    public static boolean pinBssid(Context ctx) {
        return sp(ctx).getBoolean(K_PIN_BSSID, false);
    }

    public static void setPinBssid(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(K_PIN_BSSID, v).apply();
    }

    /**
     * 是否每次尝试都用新的随机 MAC（命令行加 -r non_persistent）。
     *
     * 用于缓解目标 AP 侧的限流/黑名单。默认关闭：目标若做了 MAC 白名单，
     * 开着反而连不上。
     */
    public static boolean macRotate(Context ctx) {
        return sp(ctx).getBoolean(K_MAC_ROTATE, false);
    }

    public static void setMacRotate(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(K_MAC_ROTATE, v).apply();
    }

    // ---------------- 破解模式 ----------------

    /** -1 表示「还没设过」，交给自动探测 */
    private static final int MODE_UNSET = -1;

    /** 自动探测结果缓存。0/1/2 为已定；MODE_UNSET 表示还没探测。 */
    private static int autoCache = MODE_UNSET;

    public static int mode(Context ctx) {
        int v = sp(ctx).getInt(K_MODE, MODE_UNSET);
        if (v >= 0) return v;
        return autoMode(ctx);
    }

    /**
     * 自动挑一个**当前设备真正可用**的模式。
     *
     * 为什么需要它：以前默认值是 Root 模式。没 root 的手机（比如用 Shizuku
     * 或者干脆免 Root 的那台）一装上就是「Root 模式」，用户点开始必然失败，
     * 还会以为「密码不对」——这就是最典型的低级错误。
     *
     * 顺序：Root 可用 → Root；否则 Shizuku 就绪 → Shizuku；否则免 Root。
     */
    public static int autoMode(Context ctx) {
        if (autoCache != MODE_UNSET) return autoCache;
        int pick;
        try {
            if (io.github.wlmosv_png.wpaforge.audit.RootProber.check(ctx).ok) {
                pick = MODE_ROOT;
            } else if (io.github.wlmosv_png.wpaforge.audit.RootProber.checkShizuku(ctx).ok) {
                pick = MODE_SHIZUKU;
            } else {
                pick = MODE_NO_ROOT;
            }
        } catch (Throwable t) {
            pick = MODE_NO_ROOT;
        }
        autoCache = pick;
        return pick;
    }

    /** 后台探测一次并落盘，让后续读取不再卡。 */
    public static void warmUp(final Context ctx) {
        new Thread(() -> {
            int m = autoMode(ctx);
            if (sp(ctx).getInt(K_MODE, MODE_UNSET) < 0) {
                sp(ctx).edit().putInt(K_MODE, m).apply();
            }
            android.util.Log.i("WPAForgePrefs", "auto mode = " + m);
        }, "wpaforge-modeprobe").start();
    }

    /** 用户是否手动指定过模式（用于界面提示） */
    public static boolean modeIsManual(Context ctx) {
        return sp(ctx).getInt(K_MODE, MODE_UNSET) >= 0;
    }

    // ---------------- 应用 PIN 锁 ----------------

    /** 已设置的 PIN 的 SHA-256；空串 = 未设置 */
    public static String pinHash(Context ctx) {
        return sp(ctx).getString(K_PIN, "");
    }

    public static boolean pinEnabled(Context ctx) {
        return !pinHash(ctx).isEmpty();
    }

    /** 设置或修改 PIN（明文进来，存 SHA-256） */
    public static void setPin(Context ctx, String plain) {
        String h = plain == null ? "" : sha256(plain.trim());
        sp(ctx).edit().putString(K_PIN, h).apply();
    }

    /** 清除 PIN */
    public static void clearPin(Context ctx) {
        sp(ctx).edit().remove(K_PIN).apply();
    }

    /** 校验 PIN */
    public static boolean checkPin(Context ctx, String plain) {
        String h = pinHash(ctx);
        return h != null && !h.isEmpty() && h.equals(sha256(plain == null ? "" : plain.trim()));
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setMode(Context ctx, int mode) {
        sp(ctx).edit().putInt(K_MODE, mode).apply();
    }

    public static String modeLabel(Context ctx) {
        switch (mode(ctx)) {
            case MODE_NO_ROOT: return L.s("普通模式", "Normal mode");
            case MODE_SHIZUKU: return L.s("Shizuku 模式", "Shizuku mode");
            default: return L.s("Root 模式", "Root mode");
        }
    }

    /** 该模式是否通过 shell 通道执行（root 与 shizuku 都是） */
    public static boolean usesShell(int mode) {
        return mode == MODE_ROOT || mode == MODE_SHIZUKU;
    }

    // ---------------- 速度统计 ----------------

    /**
     * 记录本次实测速度，滚动平均。
     * 用于「开始前预估耗时」，比固定假设 1 秒/条准。
     */
    public static void recordRate(Context ctx, long totalMs, int count) {
        if (count <= 0 || totalMs <= 0) return;
        long perItem = totalMs / count;
        // 过滤异常样本。
        //
        // 上限从 60000 收到 20000：真机实测单条成本在 0.6~5 秒量级
        // （forget+list 209ms + status 100ms + connect 87ms + logcat 239ms，
        // 密码错时系统再花几秒放弃）。超过 20 秒的样本基本是
        // 「等满超时兜底结束」的那几条，不代表真实速度，不该进平均。
        // 之前 60000 的上限把这类样本全放进来了，均值被抬到 8 秒以上，
        // 用户看到「每条约 8.8 秒」与体感不符。
        if (perItem < 200 || perItem > 20000) return;

        SharedPreferences p = sp(ctx);
        long old = p.getLong(K_RATE, 0);
        int n = p.getInt(K_RATE_N, 0);
        long merged;
        if (old <= 0 || n <= 0) {
            merged = perItem;
        } else {
            // 指数滑动平均，新旧各半。
            // 原来 0.6/0.4 偏向旧值：一个偏高的历史值要好几轮才被拉回来。
            // 改成 0.5/0.5，让真值更快纠偏。
            merged = (long) (old * 0.5 + perItem * 0.5);
        }
        p.edit().putLong(K_RATE, merged).putInt(K_RATE_N, Math.min(n + 1, 50)).apply();
    }

    /** 平均每条耗时（毫秒）。没有样本时给模式默认值。 */
    public static long avgMsPerItem(Context ctx) {
        long v = sp(ctx).getLong(K_RATE, 0);
        if (v > 0) return v;
        return mode(ctx) == MODE_NO_ROOT ? 8000L : 1000L;
    }

    public static String avgRateLabel(Context ctx) {
        long ms = avgMsPerItem(ctx);
        return String.format(java.util.Locale.US, "%.1f", 1000.0 / ms);
    }

    /** 依据平均速度估算一批候选要跑多久 */
    public static String estimate(Context ctx, int count) {
        long ms = avgMsPerItem(ctx) * count;
        long sec = ms / 1000;
        if (sec < 60) return L.f("%d 秒", "%ds", sec);
        if (sec < 3600) return L.f("%d 分钟", "%d min", sec / 60);
        if (sec < 86400) return L.f("%d 小时 %d 分钟", "%dh %dm", sec / 3600, (sec % 3600) / 60);
        return L.f("%d 天 %d 小时", "%dd %dh", sec / 86400, (sec % 86400) / 3600);
    }

    /** 有没有实测样本（决定文案是"预估"还是"实测"） */
    public static boolean hasRateSample(Context ctx) {
        return sp(ctx).getLong(K_RATE, 0) > 0;
    }
}

package io.github.wlmosv_png.wpaforge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 版本号的唯一来源。
 *
 * 为什么要有这个类：
 *   以前版本号是硬编码在 AboutActivity 与设置页里的字符串
 *   （"WPAForge 0.5.0 (5)"），而出包时只改了 aapt2 的参数。
 *   结果就是「装的是 0.5.3，界面里写着 0.5.0」——用户看到的第一行
 *   就是错的，报 bug 时给的信息也是错的。
 *
 *   现在一律从 PackageManager 读真实安装信息，不再有任何手写常量。
 *   以后升版本只需要改 1-aapt2.sh 的两个环境变量，界面自动跟上。
 *
 * 读取失败不提造数值：明确显示 "?"，宁可丑也不要撒谎。
 */
public final class Ver {

    private Ver() {}

    /** 形如 "0.5.3 (8)"；拿不到时返回 "?" */
    public static String full(Context ctx) {
        String name = name(ctx);
        long code = code(ctx);
        if (name == null) return "?";
        return code < 0 ? name : name + " (" + code + ")";
    }

    /** 形如 "WPAForge 0.5.3 (8)" */
    public static String labeled(Context ctx) {
        return "WPAForge " + full(ctx);
    }

    /** versionName；拿不到返回 null */
    public static String name(Context ctx) {
        PackageInfo pi = info(ctx);
        return pi == null ? null : pi.versionName;
    }

    /** versionCode；拿不到返回 -1 */
    public static long code(Context ctx) {
        PackageInfo pi = info(ctx);
        if (pi == null) return -1;
        if (Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
        return pi.versionCode;
    }

    private static PackageInfo info(Context ctx) {
        if (ctx == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageInfo(pkg,
                        PackageManager.PackageInfoFlags.of(0));
            }
            return pm.getPackageInfo(pkg, 0);
        } catch (Throwable t) {
            return null;
        }
    }
}

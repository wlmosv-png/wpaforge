package io.github.wlmosv_png.wpaforge.shizuku;

import android.os.Bundle;
import android.util.Log;

import rikka.shizuku.ShizukuProvider;

/**
 * 容错版 ShizukuProvider。
 *
 * 为什么需要它：
 *   ShizukuProvider 是 application 级组件，**进程一启动就会被创建**。
 *   而它的 onCreate 里会尝试初始化 Sui —— 如果设备上 Sui 环境异常
 *   （没装、版本不匹配、Shizuku 服务没跑），初始化抛出的异常会沿着
 *   组件创建流程冒泡，**直接导致应用启动崩溃（闪退）**。
 *
 *   用户只关心"能不能打开"，不该因为一个可选功能的初始化失败就打不开。
 *
 * 做法：
 *   继承官方 ShizukuProvider，把所有可能抛异常的回调都包一层 try-catch。
 *   失败时记录日志并返回安全值，让应用照常启动 —— 只是 Shizuku 模式不可用。
 */
public class SafeShizukuProvider extends ShizukuProvider {

    private static final String TAG = "WPAForgeShizukuProvider";

    @Override
    public boolean onCreate() {
        try {
            return super.onCreate();
        } catch (Throwable t) {
            // 不让它冒泡 —— 冒泡就等于应用打不开
            Log.e(TAG, "ShizukuProvider.onCreate 失败，应用将以降级模式启动", t);
            return true;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            return super.call(method, arg, extras);
        } catch (Throwable t) {
            Log.e(TAG, "ShizukuProvider.call 失败 (" + method + ")", t);
            return null;
        }
    }
}

package io.github.wlmosv_png.wpaforge;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.wlmosv_png.wpaforge.audit.OfflineVerdict;

/**
 * 最近一次离线校验结果的持久化。
 *
 * <p>要求是「页面切换、应用重新打开后仍在」，所以放 SharedPreferences 而不是
 * 内存字段。沿用 {@link Prefs} 的同一个 SP 文件，避免再开一份。
 *
 * <p>读失败一律退回 {@link OfflineVerdict#none()} —— 结果卡宁可空着，
 * 也不能显示一条半截的数据。
 */
public final class OfflineRunStore {

    private static final String NAME = "wpaforge_prefs";
    private static final String KEY = "offline_last_verdict";

    private OfflineRunStore() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 写入；内容里若含密码，也仅存在本机私有 SP，不外传。 */
    public static void save(Context ctx, OfflineVerdict v) {
        if (ctx == null || v == null) return;
        try {
            sp(ctx).edit().putString(KEY, v.encode()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 读取；没有或解不开时返回 none()。 */
    public static OfflineVerdict load(Context ctx) {
        if (ctx == null) return OfflineVerdict.none();
        try {
            return OfflineVerdict.decode(sp(ctx).getString(KEY, ""));
        } catch (Throwable t) {
            return OfflineVerdict.none();
        }
    }

    /** 抹掉。**只影响结果卡，不动日志与词典。** */
    public static void clear(Context ctx) {
        if (ctx == null) return;
        try {
            sp(ctx).edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }
}

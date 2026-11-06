package io.github.wlmosv_png.wpaforge;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.wlmosv_png.wpaforge.audit.InflightRun;
import io.github.wlmosv_png.wpaforge.audit.OfflineVerdict;

/**
 * 最近一次离线校验结果的持久化。
 *
 * <p>要求是「页面切换、应用重新打开后仍在」，所以放 SharedPreferences 而不是
 * 内存字段。沿用 {@link Prefs} 的同一个 SP 文件，避免再开一份。
 *
 * <p>读失败一律退回 {@link OfflineVerdict#none()} —— 结果卡宁可空着，
 * 也不能显示一条半截的数据。
 *
 * <h3>本轮审计补的三件事</h3>
 * <ol>
 *   <li><b>写盘要能被发现失败</b>：改用 {@code commit()} 并把结果返回给调用方。
 *       原来用 {@code apply()} + {@code catch(Throwable ignored)} —— 磁盘满或进程
 *       在异步落盘前被杀时，界面显示得好好的，重开却变成「还没跑过」，
 *       而用户拿不到任何解释。</li>
 *   <li><b>读失败要能和「没跑过」区分</b>：{@link #lastDecodeFailed} 让界面能说
 *       「上次的结果记录损坏了」，而不是谎称「你从来没跑过」。</li>
 *   <li><b>运行中要有痕迹</b>：{@link #saveInflight} / {@link #loadInflight} ——
 *       进程被杀后重开，能明确说「上次那次没跑完」，而不是拿着上一次的结果卡
 *       冒充这一次。</li>
 * </ol>
 */
public final class OfflineRunStore {

    private static final String NAME = "wpaforge_prefs";
    private static final String KEY = "offline_last_verdict";
    private static final String KEY_INFLIGHT = "offline_inflight";

    private OfflineRunStore() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 写入；返回 false 表示**没有落盘成功**（调用方应提示用户）。 */
    public static boolean save(Context ctx, OfflineVerdict v) {
        if (ctx == null || v == null) return false;
        try {
            return sp(ctx).edit().putString(KEY, v.encode()).commit();
        } catch (Throwable t) {
            return false;
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

    /**
     * 盘上**存过内容、但解不开**？（用于把「数据损坏」和「从没跑过」分开说）
     */
    public static boolean lastDecodeFailed(Context ctx) {
        if (ctx == null) return false;
        try {
            String raw = sp(ctx).getString(KEY, "");
            return raw != null && !raw.isEmpty() && !OfflineVerdict.decode(raw).hasResult();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 抹掉结果卡。**只影响结果卡，不动日志与词典。** */
    public static boolean clear(Context ctx) {
        if (ctx == null) return false;
        try {
            return sp(ctx).edit().remove(KEY).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 运行中痕迹

    /** 开跑时落一条最小痕迹（材料 / 字典 / 开始时间）。 */
    public static boolean saveInflight(Context ctx, InflightRun r) {
        if (ctx == null || r == null) return false;
        try {
            return sp(ctx).edit().putString(KEY_INFLIGHT, r.encode()).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上次的运行痕迹；没有被中断过的运行或痕迹坏了都返回 null。 */
    public static InflightRun loadInflight(Context ctx) {
        if (ctx == null) return null;
        try {
            return InflightRun.decode(sp(ctx).getString(KEY_INFLIGHT, ""));
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean clearInflight(Context ctx) {
        if (ctx == null) return false;
        try {
            return sp(ctx).edit().remove(KEY_INFLIGHT).commit();
        } catch (Throwable t) {
            return false;
        }
    }
}

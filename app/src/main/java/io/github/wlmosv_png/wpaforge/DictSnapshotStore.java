package io.github.wlmosv_png.wpaforge;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.wlmosv_png.wpaforge.audit.DictSnapshot;

/**
 * 当前字典快照的持久化。
 *
 * <h3>为什么需要它</h3>
 * 应用重开之后，审计页原本只会说「当前字典: 未选择」—— 即便用户上一秒刚派生了一本。
 * 结果卡记得上次跑了什么（{@code OfflineRunStore}），当前字典却不记得，
 * 用户没法回答「我现在到底用的是哪本字典」。
 *
 * <p>这里只持久化**快照**（名字 / 来源 / 路径 / 两个指纹 / 条数 / 时间），
 * 不持久化候选本身 —— 候选从文件重读并按 {@link DictSnapshot#fileSha256} 校验；
 * 校验不过就明确报「已失效」，绝不用一份来路不明的内容顶替。
 *
 * <p>沿用 {@link Prefs} 的同一个 SP 文件。读失败一律退回
 * {@link DictSnapshot#none()}。
 */
public final class DictSnapshotStore {

    private static final String NAME = "wpaforge_prefs";
    private static final String KEY = "dict_current_snapshot";

    private DictSnapshotStore() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void save(Context ctx, DictSnapshot d) {
        if (ctx == null || d == null) return;
        try {
            if (!d.present()) {
                sp(ctx).edit().remove(KEY).apply();
                return;
            }
            sp(ctx).edit().putString(KEY, d.encode()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static DictSnapshot load(Context ctx) {
        if (ctx == null) return DictSnapshot.none();
        try {
            return DictSnapshot.decode(sp(ctx).getString(KEY, ""));
        } catch (Throwable t) {
            return DictSnapshot.none();
        }
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        try {
            sp(ctx).edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }
}

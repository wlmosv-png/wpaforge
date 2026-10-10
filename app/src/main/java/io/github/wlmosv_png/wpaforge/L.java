package io.github.wlmosv_png.wpaforge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 文案层。
 *
 * 用法：
 *   L.s("取消", "Cancel")                     —— 二选一
 *   L.f("共 %d 个字典", "%d dictionaries", n)  —— 带参数
 *
 * 语言来源优先级：
 *   1. 用户在设置里手动选的
 *   2. 系统语言（zh 开头用中文，其余英文）
 *
 * 用双参数内联而不是 key 映射表，是为了让译文跟原文贴在一起，
 * 改文案时不会出现"改了中文忘了改英文"。
 */
public final class L {

    public static final int AUTO = 0;
    public static final int ZH = 1;
    public static final int EN = 2;

    private static final String PREF = "wpaforge_prefs";
    private static final String KEY = "language";

    /** 当前生效语言：true = 中文 */
    private static boolean zh = true;
    /** 用户设置（AUTO / ZH / EN） */
    private static int mode = AUTO;

    private L() {}

    /** 从持久化读取设置。应在 Application/Activity 启动时调用一次。 */
    public static void init(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mode = sp.getInt(KEY, AUTO);
        zh = resolve(ctx, mode);
    }

    private static boolean resolve(Context ctx, int m) {
        if (m == ZH) return true;
        if (m == EN) return false;
        // AUTO：跟随系统
        String lang;
        try {
            lang = Locale.getDefault().getLanguage();
        } catch (Exception e) {
            lang = "en";
        }
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    /** 切换语言 */
    public static void setMode(Context ctx, int m) {
        mode = m;
        zh = resolve(ctx, m);
        ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY, m).apply();
    }

    public static int getMode() {
        return mode;
    }

    public static boolean isZh() {
        return zh;
    }

    public static String modeLabel(Context ctx) {
        switch (mode) {
            case ZH: return zh ? "中文" : "Chinese";
            case EN: return zh ? "English" : "English";
            default: return zh ? "跟随系统" : "Follow system";
        }
    }

    // ---------------- 取词 ----------------

    /** 二选一 */
    public static String s(String cn, String en) {
        return zh ? cn : en;
    }

    /** 带参数：n = 本次格式化里 %s/%d 的个数 */
    public static String f(String cn, String en, Object... args) {
        String tpl = zh ? cn : en;
        try {
            return String.format(tpl, args);
        } catch (Exception e) {
            return tpl;
        }
    }

    /** 纯数字（不带格式说明符）的拼接，按参数个数自动补 %s */
    public static String j(String cn, String en, Object... args) {
        String tpl = zh ? cn : en;
        if (args == null || args.length == 0) return tpl;
        StringBuilder sb = new StringBuilder();
        int ai = 0;
        for (int i = 0; i < tpl.length(); i++) {
            char c = tpl.charAt(i);
            if (c == '\u0001' && ai < args.length) {
                sb.append(args[ai++]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

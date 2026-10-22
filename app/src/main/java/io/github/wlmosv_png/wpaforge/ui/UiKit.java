package io.github.wlmosv_png.wpaforge.ui;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * 控件外观的统一实现。
 *
 * <h3>为什么要有这个类</h3>
 * 之前每个界面各写各的圆角背景，结果两件事同时发生：
 * <ol>
 *   <li><b>按下去完全没反馈</b> —— 到处都调 {@code setStateListAnimator(null)}
 *       关掉系统动画，背景又只给了一个静态 {@code GradientDrawable}，
 *       于是按下时什么都没变。用户的原话是「感觉按压都没有反馈，不太舒服」。</li>
 *   <li><b>参数不一致</b> —— 圆角、内边距、字重各处不同，看着像几个人的作品。</li>
 * </ol>
 *
 * 这里统一成：水波纹反馈（{@link RippleDrawable}）+ 禁用态压暗
 * + 一致的圆角与尺寸。各界面的 {@code roundBg()} 只需转调这里的方法，
 * 全部调用点自动获得反馈 —— 不必逐个按钮去改。
 *
 * <p>纯 UI 工具，无状态。
 */
public final class UiKit {

    private UiKit() {}

    /** 静态圆角（给非交互元素用：卡片、输入框底、分隔块） */
    public static Drawable round(int color, float radiusPx) {
        return roundRect(color, radiusPx);
    }

    /**
     * 可点背景：按下有水波纹，禁用时整体压暗。
     *
     * @param color    常态底色
     * @param radiusPx 圆角（像素）
     */
    public static Drawable ripple(int color, float radiusPx) {
        RippleDrawable rd = new RippleDrawable(
                ColorStateList.valueOf(rippleColorOf(color)),
                roundRect(color, radiusPx), null);

        StateListDrawable sld = new StateListDrawable();
        // 禁用态放最前：不可点时明显变暗，一眼看得出
        sld.addState(new int[]{-android.R.attr.state_enabled}, roundRect(dim(color), radiusPx));
        sld.addState(new int[]{android.R.attr.state_enabled}, rd);
        return sld;
    }

    /** 可点背景 + 描边（次操作按钮用） */
    public static Drawable rippleStroked(int fill, int edge, float radiusPx, float strokePx) {
        RippleDrawable rd = new RippleDrawable(
                ColorStateList.valueOf(rippleColorOf(fill)),
                roundRectStroked(fill, edge, radiusPx, strokePx), null);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{-android.R.attr.state_enabled},
                roundRectStroked(dim(fill), edge, radiusPx, strokePx));
        sld.addState(new int[]{android.R.attr.state_enabled}, rd);
        return sld;
    }

    /**
     * 让按钮按下时给一次轻微触觉反馈。
     *
     * <p>用 {@code KEYBOARD_TAP}（最轻的一档），不是长振 —— 目的是"手感"，
     * 不是打断用户。返回 false 表示不消费事件，正常点击流程照走。
     */
    public static void enableTapHaptic(View v) {
        if (v == null) return;
        v.setHapticFeedbackEnabled(true);
        v.setOnTouchListener((view, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            return false;
        });
    }

    /**
     * 依底色挑一个对比够的水波纹颜色。
     *
     * <p>浅底（琥珀主色）用深波纹、深底用浅波纹 —— 否则波纹和底色糊在一起，
     * 等于还是没反馈。
     */
    public static int rippleColorOf(int bg) {
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum > 140 ? 0x33000000 : 0x33FFFFFF;
    }

    /** 同色系压暗（禁用态） */
    private static int dim(int color) {
        int a = color >>> 24;
        if (a == 0) a = 0xFF;
        int r = ((color >> 16) & 0xFF) * 55 / 100;
        int g = ((color >> 8) & 0xFF) * 55 / 100;
        int b = (color & 0xFF) * 55 / 100;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static GradientDrawable roundRect(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static GradientDrawable roundRectStroked(int fill, int edge,
                                                     float radiusPx, float strokePx) {
        GradientDrawable g = roundRect(fill, radiusPx);
        if (strokePx > 0) g.setStroke(Math.max(1, Math.round(strokePx)), edge);
        return g;
    }
}

package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.L;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 圆角自定义弹窗。替代系统 AlertDialog，跟应用整体视觉一致。
 *
 * 用法：
 *   NetDialog d = new NetDialog(ctx, "标题", "正文");
 *   d.addField(L.s("前缀", "Prefix"), "");
 *   d.setPositive(L.s("创建", "Create"), v -> {...});
 *   d.show();
 */
public final class NetDialog {

    private static final int SURFACE = AmberTheme.CARD;
    private static final int ACCENT  = AmberTheme.ACCENT;
    private static final int TEXT    = AmberTheme.TEXT;
    private static final int TEXT_DIM= AmberTheme.TEXT_DIM;
    private static final int DANGER  = AmberTheme.DANGER;
    private static final int FIELD_BG= AmberTheme.SUNKEN;

    private final Context ctx;
    private final Dialog dialog;
    private final LinearLayout body;
    private final LinearLayout rowButtons;
    private final float dp;

    private Button positiveBtn;
    private Button negativeBtn;

    public NetDialog(Context ctx, String title, String message) {
        this.ctx = ctx;
        this.dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(io.github.wlmosv_png.wpaforge.ui.UiKit.roundStroked(
                SURFACE, AmberTheme.EDGE,
                px(AmberTheme.RADIUS_CARD), Math.max(1f, dp * 0.8f)));
        root.setPadding(px(22), px(22), px(22), px(16));

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(17);
            t.setTextColor(TEXT);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            root.addView(t);
        }

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        // 只有**内容区**滚动：标题与按钮都固定在弹窗里。
        //
        // 之前是整个 root 一起塞进 ScrollView，而多行输入框会吃掉滑动手势，
        // 于是内容一高，底部的按钮就够不到了 —— 用户的原话是
        // 「没办法正常编辑，都没有保存按钮」。按钮其实一直在，只是被顶出去了。
        ScrollView bodyScroll = new ScrollView(ctx);
        bodyScroll.setClipToPadding(false);
        bodyScroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = px(12);
        root.addView(bodyScroll, blp);

        if (message != null && !message.isEmpty()) {
            body.addView(textBlock(message));
        }

        rowButtons = new LinearLayout(ctx);
        rowButtons.setOrientation(LinearLayout.HORIZONTAL);
        rowButtons.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(16);
        root.addView(rowButtons, rlp);

        // 不再整体再包一层 ScrollView：按钮已经由 bodyScroll 之外的 root 固定住了，
        // 再包一层会出现双重滚动，手势归属也会打架。
        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            lp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f);
            w.setAttributes(lp);
        }
        dialog.setCanceledOnTouchOutside(true);
    }

    /** 通用静态构造：立刻显示一个信息弹窗 */
    public static NetDialog info(Context ctx, String title, String message) {
        NetDialog d = new NetDialog(ctx, title, message);
        d.setPositive(L.s("知道了", "Got it"), null);
        d.show();
        return d;
    }

    /** 可滚动的大文本块 */
    public static NetDialog textInfo(Context ctx, String title, String body) {
        NetDialog d = new NetDialog(ctx, title, null);
        TextView tv = new TextView(ctx);
        tv.setText(body);
        tv.setTextSize(12);
        tv.setTextColor(TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(4 * d.dp, 1f);

        ScrollView sv = new ScrollView(ctx);
        sv.setBackground(d.round(FIELD_BG, AmberTheme.RADIUS_CHIP));
        sv.setPadding(d.px(14), d.px(14), d.px(14), d.px(14));
        sv.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, d.px(320));
        d.body.addView(sv, lp);
        d.setPositive(L.s("关闭", "Close"), null);
        d.show();
        return d;
    }

    /** 单行文本块 */
    private TextView textBlock(String msg) {
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextSize(13);
        tv.setTextColor(TEXT_DIM);
        tv.setLineSpacing(px(5), 1f);
        return tv;
    }

    /** 加一个输入框，返回它便于取值 */
    public EditText addField(String hint, String defaultValue) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        if (defaultValue != null) et.setText(defaultValue);
        et.setTextColor(TEXT);
        et.setHintTextColor(TEXT_DIM);
        et.setTextSize(13.5f);
        et.setSingleLine(true);
        et.setBackground(round(FIELD_BG, AmberTheme.RADIUS_CHIP));
        et.setPadding(px(14), px(13), px(14), px(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(9);
        body.addView(et, lp);
        return et;
    }

    /** 加一个多行输入框 */
    public EditText addTextArea(String hint, String defaultValue, int minLines) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        if (defaultValue != null) et.setText(defaultValue);
        et.setTextColor(TEXT);
        et.setHintTextColor(TEXT_DIM);
        et.setTextSize(12.5f);
        et.setTypeface(Typeface.MONOSPACE);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(minLines);
        // 上限 10 行：再多就让它自己内部滚，否则弹窗被撑满、按钮区被挤掉
        et.setMaxLines(Math.max(minLines, 10));
        et.setVerticalScrollBarEnabled(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setBackground(round(FIELD_BG, AmberTheme.RADIUS_CHIP));
        et.setPadding(px(14), px(13), px(14), px(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(9);
        body.addView(et, lp);
        return et;
    }

    /** 加一段小字说明 */
    public void addHint(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(TEXT_DIM);
        tv.setLineSpacing(px(4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(10);
        body.addView(tv, lp);
    }

    /** 加一个自定义视图 */
    public void addView(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(10);
        body.addView(v, lp);
    }

    public interface OnClick {
        void onClick(NetDialog d);
    }

    public NetDialog setPositive(String label, OnClick cb) {
        positiveBtn = button(label, AmberTheme.BTN_PRIMARY);
        positiveBtn.setOnClickListener(v -> {
            if (cb != null) cb.onClick(this);
            dialog.dismiss();
        });
        rowButtons.addView(positiveBtn);
        return this;
    }

    /** 不自动关闭的确定按钮（用于校验失败时留在弹窗里） */
    public NetDialog setPositiveKeepOpen(String label, OnClick cb) {
        positiveBtn = button(label, AmberTheme.BTN_PRIMARY);
        positiveBtn.setOnClickListener(v -> {
            if (cb != null) cb.onClick(this);
        });
        rowButtons.addView(positiveBtn);
        return this;
    }

    public NetDialog setNegative(String label, OnClick cb) {
        negativeBtn = button(label, AmberTheme.BTN_NEUTRAL);
        negativeBtn.setOnClickListener(v -> {
            if (cb != null) cb.onClick(this);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) negativeBtn.getLayoutParams();
        lp.rightMargin = px(10);
        negativeBtn.setLayoutParams(lp);
        rowButtons.addView(negativeBtn, 0);
        return this;
    }

    public NetDialog setDanger(String label, OnClick cb) {
        Button b = button(label, AmberTheme.BTN_DANGER);
        b.setOnClickListener(v -> {
            if (cb != null) cb.onClick(this);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.rightMargin = px(10);
        b.setLayoutParams(lp);
        rowButtons.addView(b, 0);
        return this;
    }

    /** 加一个列表项（用于选择） */
    public TextView addItem(String text, OnClick cb) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTextColor(TEXT);
        t.setPadding(px(16), px(15), px(16), px(15));
        t.setBackground(round(FIELD_BG, AmberTheme.RADIUS_CHIP));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(7);
        if (cb != null) {
            t.setOnClickListener(v -> {
                cb.onClick(this);
                dialog.dismiss();
            });
        }
        body.addView(t, lp);
        return t;
    }

    /**
     * 建按钮 —— 只选**语义**，外观由 {@link AmberTheme#action} 一处定义。
     *
     * <p>改前这里自己拼底色/字色/圆角：危险操作是**实心红**，而页面上的危险按钮
     * 是「暗底 + 红字 + 极淡红描边」。同一个「清空记录」在两处长得不一样。
     */
    private Button button(String label, int kind) {
        Button b = AmberTheme.action(ctx, dp, label, kind);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /**
     * 圆角背景 —— 统一走 UiKit：可点元素自带水波纹，禁用态压暗。
     * 之前这里返回静态 GradientDrawable，按下没有任何反馈。
     */
    private Drawable round(int color, float radiusDp) {
        return UiKit.ripple(color, radiusDp * dp);
    }

    private int px(float v) { return Math.round(v * dp); }

    public Dialog raw() { return dialog; }

    public void show() { dialog.show(); }

    public void dismiss() { dialog.dismiss(); }
}

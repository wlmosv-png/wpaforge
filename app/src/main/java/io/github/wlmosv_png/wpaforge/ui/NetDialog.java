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

    private static final int SURFACE = Color.parseColor("#1B1B24");
    private static final int ACCENT  = Color.parseColor("#FFB300");
    private static final int TEXT    = Color.parseColor("#E8E8EE");
    private static final int TEXT_DIM= Color.parseColor("#8A8A94");
    private static final int DANGER  = Color.parseColor("#FF6B6B");
    private static final int FIELD_BG= Color.parseColor("#12121A");

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
        root.setBackground(round(SURFACE, 24));
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
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = px(12);
        root.addView(body, blp);

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

        // 滚动包裹（内容多时可滚）
        ScrollView sc = new ScrollView(ctx);
        sc.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sc);
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
        sv.setBackground(d.round(FIELD_BG, 14));
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
        et.setBackground(round(FIELD_BG, 14));
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
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setBackground(round(FIELD_BG, 14));
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
        positiveBtn = button(label, ACCENT, true);
        positiveBtn.setOnClickListener(v -> {
            if (cb != null) cb.onClick(this);
            dialog.dismiss();
        });
        rowButtons.addView(positiveBtn);
        return this;
    }

    /** 不自动关闭的确定按钮（用于校验失败时留在弹窗里） */
    public NetDialog setPositiveKeepOpen(String label, OnClick cb) {
        positiveBtn = button(label, ACCENT, true);
        positiveBtn.setOnClickListener(v -> {
            if (cb != null) cb.onClick(this);
        });
        rowButtons.addView(positiveBtn);
        return this;
    }

    public NetDialog setNegative(String label, OnClick cb) {
        negativeBtn = button(label, Color.parseColor("#2A2A36"), false);
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
        Button b = button(label, DANGER, true);
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
        t.setBackground(round(FIELD_BG, 14));
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

    private Button button(String label, int bg, boolean primary) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary && bg != DANGER
                ? Color.parseColor("#14141A") : Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(20), px(11), px(20), px(11));
        b.setBackground(round(bg, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private Drawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    private int px(float v) { return Math.round(v * dp); }

    public Dialog raw() { return dialog; }

    public void show() { dialog.show(); }

    public void dismiss() { dialog.dismiss(); }
}

package io.github.wlmosv_png.wpaforge.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「Obsidian Amber」主题（纯外观常量 + 两个建视图的小工具）。
 *
 * <h3>为什么单独一个类</h3>
 * 这一版之前，颜色与尺寸散在各页里各写各的（{@code Color.parseColor("#14141A")}
 * 在 MainActivity 里出现了几十次）。改一处配色要全文搜替，而且同一个语义
 * （「凹陷的日志底」「卡片底」「次级表面」）在不同页里会写成不同的值。
 * 这里把「近黑 + 琥珀」这一套收敛成一份可引用的常量表。
 *
 * <h3>取舍</h3>
 * <ul>
 *   <li><b>不改品牌色</b>：主强调仍是琥珀（{@link #ACCENT}），背景仍是近黑，
 *       只是把原来的 {@code #0E0E11 / #FFB300} 微调到
 *       {@code #101014 / #FFB800}，对比度更稳、更接近「终端仪表盘」的观感。</li>
 *   <li><b>语义色沿用项目既有值</b>：成功绿、错误红的色相不变，只统一了
 *       「同色系的暗底」（{@link #OK_BG} / {@link #DANGER_BG} / {@link #ACCENT_BG}），
 *       避免三处各写一个十六进制。</li>
 *   <li><b>只放外观</b>：本类不含任何业务数据、不读任务状态、不发命令。
 *       所有数字仍由 {@link CrackHud} 从业务回调给的原始值格式化。</li>
 * </ul>
 */
public final class AmberTheme {

    private AmberTheme() {}

    // ---------------------------------------------------------------- 背景与表面

    /** 页面背景。 */
    public static final int BG = 0xFF0B0B0F;
    /** 卡片背景（比页面亮一档，把「一块内容」托起来）。 */
    public static final int CARD = 0xFF1C1C24;
    /** 次级表面（卡片里的分区、未选中的胶囊）。 */
    public static final int CARD_2 = 0xFF262630;
    /** 抬起的次级表面（列表项、工具条上的小胶囊底）。 */
    public static final int CARD_3 = 0xFF232330;
    /** 凹陷面（日志区、输入框底）。比页面还暗，形成「凹进去」的层次。 */
    public static final int SUNKEN = 0xFF131318;
    /** 卡片描边：极低对比，只在近黑底上勾一条边。 */
    public static final int EDGE = 0x24FFFFFF;
    /** 卡内分隔线。 */
    public static final int LINE = 0x14FFFFFF;

    // ---------------------------------------------------------------- 文字

    /** 主文字。 */
    public static final int TEXT = 0xFFF5F5F7;
    /** 次级文字（标签、单位、辅助信息）。 */
    public static final int TEXT_DIM = 0xFFA5A5B1;
    /** 三级文字（弱提示、占位）。 */
    public static final int TEXT_FAINT = 0xFF6E6E7A;

    // ---------------------------------------------------------------- 强调与语义

    /** 主强调（琥珀）。 */
    public static final int ACCENT = 0xFFFFB800;
    /** 琥珀底上的文字色（必须用深色，白字对比度不够）。 */
    public static final int ON_ACCENT = 0xFF14141A;
    /** 琥珀的暗/中两档（进度条分层、未激活的琥珀元素）。 */
    public static final int ACCENT_DIM = 0xFF7A5300;
    public static final int ACCENT_MID = 0xFFC68A00;
    /** 5GHz 专属的亮琥珀（与 2.4GHz 的 {@link #ACCENT} 拉开区分）。 */
    public static final int ACCENT_5G = 0xFFFFD54F;
    /** 更淡的琥珀（柱状图顶端、次级强调）。 */
    public static final int ACCENT_SOFT = 0xFFFFE082;
    /** 琥珀光晕 / 描边（雷达扫描、发光边框）。 */
    public static final int ACCENT_GLOW = 0x44FFB800;
    public static final int ACCENT_EDGE = 0x66FFB800;
    /** 琥珀色的极暗底（选中胶囊、命中卡里的按钮底）。 */
    public static final int ACCENT_BG = 0xFF2A2410;
    /** 极淡白：图表网格线与轨道。 */
    public static final int GRID = 0x22FFFFFF;

    /** 成功。 */
    public static final int OK = 0xFF4CAF50;
    /** 成功的暗/中两档（成功态进度条分层）。 */
    public static final int OK_DIM = 0xFF2E7D32;
    public static final int OK_MID = 0xFF388E3C;
    /** 成功的暗底。 */
    public static final int OK_BG = 0xFF12251A;
    /** 错误：克制一档的红，不用纯红。 */
    public static final int DANGER = 0xFFFF5A5F;
    /** 错误的暗底。 */
    public static final int DANGER_BG = 0xFF2A2020;
    /** 中性操作底（暂停）。 */
    public static final int NEUTRAL_BG = 0xFF2A2A36;
    /** 等待 / 暂停状态的暖暗底（与琥珀同色系，区别于「出错」的红底）。 */
    public static final int WARN_BG = 0xFF2A2318;
    /** 破坏性操作按钮的描边（与 DANGER 同色相，克制不刺眼）。 */
    public static final int DANGER_SOFT_BG = 0xFF241A1A;
    public static final int DANGER_EDGE = 0x44FF5A5F;
    /** 工具条上的极淡分隔线（比 {@link #LINE} 亮一档，用于可点元素之间）。 */
    public static final int HAIRLINE = 0x26FFFFFF;
    /** 卡片的危险态描边（例如目标配置无法还原时的提示卡）。 */
    public static final int EDGE_DANGER = 0x33FF5A5F;

    // ---------------------------------------------------------------- 终端区

    /** 遮罩底：盖住整页（PIN 门、忙碌态），近黑但半透明，仍能看见下面的内容。 */
    public static final int SCRIM = 0xF0101014;

    /** 终端 / 日志区底色：比 {@link #SUNKEN} 还暗，形成「屏幕里的屏幕」。 */
    public static final int TERM_BG = 0xFF08080C;
    /** 终端正文（等宽）。 */
    public static final int TERM_FG = 0xFFC9C9D4;
    /** 终端弱文字（时间戳、序号）。 */
    public static final int TERM_DIM = 0xFF6E6E7A;
    /** 次级操作底 + 描边。 */
    public static final int BTN_2ND_BG = 0xFF212129;
    public static final int BTN_2ND_EDGE = 0x33FFFFFF;

    // ---------------------------------------------------------------- 尺寸（dp）

    public static final float RADIUS_CARD = 16f;
    /** 胶囊 / 小标签 / 筛选片：比卡片小一档。 */
    public static final float RADIUS_CHIP = 12f;
    /** 按钮（主操作是长圆角，与胶囊区分开）。 */
    public static final float RADIUS_BTN = 24f;
    /** 顶部页签：与胶囊同档，避免三种圆角并存。 */
    public static final float RADIUS_TAB = 12f;
    /** 卡片之间的间距。 */
    public static final int GAP_CARD = 12;
    /** 卡片内边距。 */
    public static final int PAD_H = 16;
    public static final int PAD_V = 14;

    // ---------------------------------------------------------------- 字号（sp）

    /** 页面上最大的数字（完成度百分比）。 */
    public static final float FS_HERO = 30f;
    /** 统计数字（已完成 / 总数）。 */
    public static final float FS_STAT = 19f;
    /** 当前状态。 */
    public static final float FS_STATE = 15f;
    /** 卡片标题 / 名称。 */
    public static final float FS_TITLE = 16f;
    public static final float FS_BODY = 12.5f;
    /** 小标签（全大写那种）。 */
    public static final float FS_LABEL = 10.5f;

    // ---------------------------------------------------------------- 建视图小工具

    /** 一张标准卡片：竖排、圆角、统一内边距。
     *  @param density 屏幕密度（调用方传它自己的 dp 倍率），换算一律四舍五入到像素 */
    public static LinearLayout card(Context c, float density, float radiusDp) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(UiKit.roundStroked(CARD, EDGE, radiusDp * density, Math.max(1f, density * 0.8f)));
        int ph = Math.round(PAD_H * density), pv = Math.round(PAD_V * density);
        v.setPadding(ph, pv, ph, pv);
        return v;
    }

    /** 小标签：全大写感、字距拉开、弱色。 */
    public static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(FS_LABEL);
        t.setTextColor(TEXT_DIM);
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** 统计数字：等宽 + 大字号（让数字自己能对齐、能比较）。 */
    public static TextView stat(Context c) {
        TextView t = new TextView(c);
        t.setTextSize(FS_STAT);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setSingleLine(true);
        return t;
    }

    /** 一列统计（小标签在上、大数字在下），weight 交给调用方。 */
    public static LinearLayout statColumn(Context c, float density, String labelText,
                                          TextView value) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label(c, labelText));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(3 * density);
        col.addView(value, lp);
        return col;
    }

    /** 居中一行提示（空状态、占位）。 */
    public static TextView centeredHint(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(FS_BODY);
        t.setTextColor(TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 卡片/列表行的统一底色：卡片色 + 一条极淡描边（分组靠它立住）。 */
    public static Drawable rowBg(Context c, float density, float radiusDp) {
        return UiKit.roundStroked(CARD, EDGE, radiusDp * density, Math.max(1f, density * 0.8f));
    }

    /** 列表行：横向、卡片底、统一内边距（各页共用，行高才不会一处一个样）。 */
    public static LinearLayout row(Context c, float density) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setBackground(rowBg(c, density, RADIUS_CARD));
        int ph = Math.round(14 * density), pv = Math.round(13 * density);
        v.setPadding(ph, pv, ph, pv);
        return v;
    }

    /** 卡片内的一行分隔线（左右不缩进，宽度由父容器决定）。 */
    public static View divider(Context c, float density) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density))));
        return v;
    }

    /** 小节标题：中等字重 + 次级文字色，用于卡片内分组。 */
    public static TextView sectionTitle(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(FS_BODY);
        t.setTextColor(TEXT_DIM);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.02f);
        return t;
    }

    /**
     * 空状态块：一条说明 + 一句下一步。
     *
     * <p>「空」和「暂停」必须一眼能分开 —— 空状态说的是「还没有数据」，
     * 暂停状态说的是「有数据、只是没在跑」。混用同一个文案会让人以为任务丢了。
     */
    public static LinearLayout emptyBox(Context c, float density, String title, String hint) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(UiKit.round(CARD_2, Math.round(12 * density)));
        int p = Math.round(14 * density);
        v.setPadding(p, p, p, p);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(FS_BODY);
        t.setTextColor(TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        v.addView(t);
        if (hint != null && !hint.isEmpty()) {
            TextView h = new TextView(c);
            h.setText(hint);
            h.setTextSize(FS_LABEL);
            h.setTextColor(TEXT_FAINT);
            h.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(6 * density);
            v.addView(h, lp);
        }
        return v;
    }

    // ---------------------------------------------------------------- 按钮语义

    /** 主操作（实心琥珀 + 深色字）。 */
    public static final int BTN_PRIMARY = 0;
    /** 次操作（暗底 + 极淡描边）。 */
    public static final int BTN_SECONDARY = 1;
    /** 中性（暂停 / 收起）。 */
    public static final int BTN_NEUTRAL = 2;
    /** 危险（停止 / 删除，克制红）。 */
    public static final int BTN_DANGER = 3;

    /**
     * 统一按钮：高度、圆角、字重、按下反馈、禁用压暗一次定义。
     *
     * <p>之前每个页面的按钮各自设尺寸与字色，同一个「停止」在破解页和队列页
     * 长得不一样。四种语义只有这四种外观，调用方只选语义，不选颜色。
     *
     * @param kind {@link #BTN_PRIMARY} / {@link #BTN_SECONDARY} / {@link #BTN_NEUTRAL} / {@link #BTN_DANGER}
     */
    public static Button action(Context c, float density, String label, int kind) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(FS_STATE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Math.round(14 * density), Math.round(11 * density),
                Math.round(14 * density), Math.round(11 * density));
        float r = Math.round(12 * density);
        switch (kind) {
            case BTN_PRIMARY:
                b.setTextColor(ON_ACCENT);
                b.setBackground(UiKit.ripple(ACCENT, r));
                break;
            case BTN_DANGER:
                b.setTextColor(DANGER);
                b.setBackground(UiKit.rippleStroked(DANGER_SOFT_BG, DANGER_EDGE, r, 0.8f * density));
                break;
            case BTN_NEUTRAL:
                b.setTextColor(TEXT);
                b.setBackground(UiKit.ripple(NEUTRAL_BG, r));
                break;
            default:
                b.setTextColor(TEXT);
                b.setBackground(UiKit.rippleStroked(BTN_2ND_BG, BTN_2ND_EDGE, r, 0.8f * density));
                break;
        }
        UiKit.enableTapHaptic(b);
        return b;
    }

    // ---------------------------------------------------------------- 对话框

    /** 对话框内容面板：统一底色、圆角与内边距（标题/正文由调用方往里面加）。 */
    public static LinearLayout dialogPanel(Context c, float density) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(UiKit.round(CARD, Math.round(RADIUS_CARD * density)));
        int ph = Math.round(18 * density), pv = Math.round(16 * density);
        v.setPadding(ph, pv, ph, pv);
        return v;
    }

    /** 对话框标题：16sp 主文字 + 中等字重。 */
    public static TextView dialogTitle(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(FS_TITLE);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** 对话框说明：12.5sp 次级文字，长文本可换行。 */
    public static TextView dialogNote(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(FS_BODY);
        t.setTextColor(TEXT_DIM);
        t.setLineSpacing(Math.round(4 * c.getResources().getDisplayMetrics().density), 1f);
        return t;
    }

}

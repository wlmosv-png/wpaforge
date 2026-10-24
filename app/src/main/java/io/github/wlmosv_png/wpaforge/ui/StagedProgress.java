package io.github.wlmosv_png.wpaforge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 三层分段进度条。
 *
 * 为什么不是一条进度条：
 *   破解是「整个字典 → 当前批次 → 当前候选」三层的嵌套过程。
 *   一条进度条只能表达最外层，用户看到 37% 时并不知道
 *   「是字典走了 37%，还是某个批次里的 37%」——尤其是暂停恢复、
 *   智能排序让候选顺序变化之后，单条进度条的语义会漂。
 *
 *   所以这里把三层**同时**画在一根轨道上：
 *     外层（长条）  整个字典的完成度
 *     中层（亮条）  当前批次的完成度
 *     内层（点）    当前正在试的候选位置
 *
 * 三层用同一套色系、不同亮度，视觉上是「一段包含一段」的关系，
 * 不需要图例就能读懂。
 *
 * 数值全部可空：拿不到层就画 0，不猜。
 */
public class StagedProgress extends View {

    /** 一层的进度（0..1）与标签 */
    public static final class Stage {
        final float value;
        final String label;
        public Stage(float value, String label) {
            this.value = Math.max(0f, Math.min(1f, value));
            this.label = label == null ? "" : label;
        }
    }

    private final List<Stage> stages = new ArrayList<>();
    private String headline = "";
    private String footnote = "";

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] pFill = new Paint[3];
    private final Paint pHead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFoot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStage = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTick = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int accent = AmberTheme.ACCENT;
    private int okGreen = AmberTheme.OK;
    private int textMain = AmberTheme.TEXT;
    private int textDim = AmberTheme.TEXT_DIM;
    private int track = AmberTheme.SUNKEN;
    /* 分层的暗/中两档也做成字段：主题化时一并替换，避免同一套颜色两处各写一遍 */
    private int accentDim = AmberTheme.ACCENT_DIM;
    private int accentMid = AmberTheme.ACCENT_MID;
    private int okDim = AmberTheme.OK_DIM;
    private int okMid = AmberTheme.OK_MID;

    private boolean done = false;

    /* ---------- 动画状态 ---------- */
    /** 当前**显示**的进度（0..1），逐帧向 stages 里的目标值逼近 */
    private final float[] shown = new float[]{0f, 0f, 0f};
    /** 流光位置（0..1 从左到右循环） */
    private float sweep = 0f;
    /** 动画是否在跑（不可见就停，不空转） */
    private boolean animating = false;
    /** 流光用的 Paint */
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 每帧把显示值向目标值靠近的系数（越小越慢越顺） */
    private static final float EASE = 0.18f;
    /** 流光扫过一整条轨道要多少帧 */
    private static final float SWEEP_STEP = 0.012f;

    /** 自驱帧循环 */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!animating) return;
            boolean moving = false;

            // 显示值指数逼近目标值
            for (int i = 0; i < 3; i++) {
                float target = i < stages.size() ? stages.get(i).value : 0f;
                float d = target - shown[i];
                if (Math.abs(d) > 0.002f) {
                    shown[i] += d * EASE;
                    moving = true;
                } else {
                    shown[i] = target;
                }
            }

            // 流光推进（只在未完成时循环）
            if (!done) {
                sweep += SWEEP_STEP;
                if (sweep > 1f) sweep = 0f;
                moving = true;
            }

            invalidate();
            // 值已到位且已完成 → 停，避免无意义重绘
            if (!moving) { animating = false; return; }
            postOnAnimation(this);
        }
    };

    private void ensureAnimating() {
        if (animating) return;
        if (getWindowVisibility() != VISIBLE || !isShown()) return;
        animating = true;
        postOnAnimation(tick);
    }

    @Override
    protected void onVisibilityChanged(View changed, int vis) {
        super.onVisibilityChanged(changed, vis);
        if (vis == VISIBLE) ensureAnimating();
        else animating = false;   // 不可见立刻停，不空转耗电
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureAnimating();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animating = false;
    }

    public StagedProgress(Context c) { this(c, null); }

    public StagedProgress(Context c, AttributeSet a) {
        super(c, a);
        // 三层亮度：外暗 → 内亮，视觉上「一层套一层」
        int[] colors = {
                AmberTheme.ACCENT_DIM,   // 外层 深琥珀
                AmberTheme.ACCENT_MID,   // 中层 中琥珀
                AmberTheme.ACCENT    // 内层 亮琥珀（主色）
        };
        for (int i = 0; i < pFill.length; i++) {
            pFill[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            pFill[i].setColor(colors[i]);
        }
        pHead.setTextAlign(Paint.Align.LEFT);
        pFoot.setTextAlign(Paint.Align.LEFT);
        pStage.setTextAlign(Paint.Align.LEFT);
        pTick.setColor(Color.WHITE);
        pTrack.setColor(track);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 设置三层。列表顺序 = 外层 → 内层。多余三层会被忽略。 */
    public void setStages(List<Stage> list) {
        stages.clear();
        if (list != null) {
            for (int i = 0; i < list.size() && i < 3; i++) {
                if (list.get(i) != null) stages.add(list.get(i));
            }
        }
        // requestLayout 与 invalidate 缺一不可：
        //   · onMeasure 按 stages.size() 算高度（每段 dp(9)+dp(6)）
        //   · invalidate 只重绘，不会重新测量
        // 只调 invalidate 的话，高度会永远停在首次测量的结果 ——
        // 首次进来 stages 是空的（高度仅 dp(20)），之后加了 3 段也画不全，
        // 表现就是「三段进度条只剩一条细线」。
        requestLayout();
        ensureAnimating();
        invalidate();
    }

    /** 顶部一行粗体说明 + 底部一行补充 */
    public void setText(String headline, String footnote) {
        boolean hChanged = !this.headline.equals(headline == null ? "" : headline);
        this.headline = headline == null ? "" : headline;
        this.footnote = footnote == null ? "" : footnote;
        // headline / footnote 的有无也会影响高度（各 dp(20) / dp(16)），
        // 所以同样要 requestLayout。只有内容变化时才申请，避免每帧重排。
        if (hChanged) requestLayout();
        invalidate();
    }

    /** 命中后整体转绿 */
    public void setDone(boolean d) {
        this.done = d;
        for (Paint p : pFill) p.setColor(d ? okGreen : accent);
        if (d) {
            pFill[0].setColor(okDim);
            pFill[1].setColor(okMid);
            pFill[2].setColor(okGreen);
        } else {
            pFill[0].setColor(accentDim);
            pFill[1].setColor(accentMid);
            pFill[2].setColor(accent);
        }
        invalidate();
    }

    /**
     * 覆盖配色（供主题统一调用）。传 0 的位保持原值。
     *
     * <p>默认值就是本类一直以来的琥珀 + 近黑一套 —— 重构前它的颜色是写死在
     * 构造函数里的，主题化之后由调用方传入 {@code ui.AmberTheme} 的值，
     * 这样「进度条的主色」和「页面主色」不会再各写一个十六进制。
     */
    public void setPalette(int accentColor, int dim, int mid,
                           int main, int dimText, int trackColor,
                           int okColor, int okDimColor, int okMidColor) {
        if (accentColor != 0) this.accent = accentColor;
        if (dim != 0) this.accentDim = dim;
        if (mid != 0) this.accentMid = mid;
        if (main != 0) this.textMain = main;
        if (dimText != 0) this.textDim = dimText;
        if (trackColor != 0) this.track = trackColor;
        if (okColor != 0) this.okGreen = okColor;
        if (okDimColor != 0) this.okDim = okDimColor;
        if (okMidColor != 0) this.okMid = okMidColor;
        pTrack.setColor(this.track);
        pHead.setColor(this.textMain);
        pFoot.setColor(this.textDim);
        setDone(done);        // 依当前 done 状态重算三段填充
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth();
        if (w == 0) return;

        // 空状态：没有分段也没有文字就什么都不画。
        // 否则会在界面上留下一条空轨道，看起来像组件坏了。
        if (stages.isEmpty() && headline.isEmpty() && footnote.isEmpty()) {
            return;
        }

        float y = 0;

        // ---- 顶行说明：方案 A 之后这里是「还剩多久」，全页的主视觉，
        //      所以字号从 dp(13) 提到 dp(18) 并用 medium 字重。
        if (!headline.isEmpty()) {
            pHead.setTextSize(dp(18));
            pHead.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            pHead.setColor(done ? okGreen : textMain);
            cv.drawText(headline, 0, y + dp(18), pHead);
            y += dp(26);
        }

        // ---- 三层轨道 ----
        final float barH = dp(9);
        final float gap = dp(6);
        for (int i = 0; i < stages.size(); i++) {
            Stage st = stages.get(i);
            float top = y;
            float radius = barH / 2f;

            // 轨道底
            RectF r = new RectF(0, top, w, top + barH);
            cv.drawRoundRect(r, radius, radius, pTrack);

            // 填充：用动画后的显示值，而不是直接跳目标值
            float v = i < 3 ? shown[i] : st.value;
            if (v > 0.001f) {
                float fw = Math.max(barH, w * v);
                RectF f = new RectF(0, top, fw, top + barH);
                cv.drawRoundRect(f, radius, radius, pFill[Math.min(i, pFill.length - 1)]);

                // 流光：在内层条上叠一道更亮的窄带，持续左→右扫。
                // 表达「正在推进」—— 静止的进度条看起来像卡住了。
                // 只画在最外层（i==0）那条上，避免三条各扫一道显得杂乱。
                if (i == 0 && !done && v > 0.02f) {
                    float glowW = Math.max(dp(28), w * 0.10f);
                    float cx = (w + glowW) * sweep - glowW * 0.5f;
                    float l = Math.max(0, cx - glowW * 0.5f);
                    float r2 = Math.min(fw, cx + glowW * 0.5f);
                    if (r2 > l) {
                        // 呼吸：透明度做正弦脉动，避免死板
                        double phase = (System.currentTimeMillis() % 1600) / 1600.0;
                        int a = (int) (46 + 40 * Math.sin(phase * Math.PI * 2));
                        pGlow.setColor(Color.argb(Math.max(0, Math.min(120, a)), 255, 236, 179));
                        RectF g = new RectF(l, top, r2, top + barH);
                        cv.drawRoundRect(g, radius, radius, pGlow);
                    }
                }
            }

            // 行尾标签（第几层 / 百分比）
            if (!st.label.isEmpty()) {
                pStage.setTextSize(dp(10.5f));
                pStage.setColor(textDim);
                float lw = pStage.measureText(st.label);
                cv.drawText(st.label, w - lw, top + barH - dp(0.5f), pStage);
            }

            y += barH + gap;
        }

        // ---- 底行补充 ----
        if (!footnote.isEmpty()) {
            pFoot.setTextSize(dp(11));
            pFoot.setColor(textDim);
            cv.drawText(footnote, 0, y + dp(13), pFoot);
            y += dp(16);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (stages.isEmpty() && headline.isEmpty() && footnote.isEmpty()) {
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
            return;
        }
        float h = 0;
        if (!headline.isEmpty()) h += dp(26);
        h += stages.size() * (dp(9) + dp(6));
        if (!footnote.isEmpty()) h += dp(16);
        if (h < dp(20)) h = dp(20);
        super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(Math.round(h), MeasureSpec.EXACTLY));
    }
}

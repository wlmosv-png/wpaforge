package io.github.wlmosv_png.wpaforge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * 作者卡片。程序化绘制的视觉签名 —— 不依赖任何外部图片资源。
 *
 * 构成：同心圆环徽记 + 字母缩写 + 名字 + 副标题 + 一条琥珀色描边。
 */
public class AuthorCard extends View {

    private static final int ACCENT = AmberTheme.ACCENT;
    private static final int ACCENT_DIM = AmberTheme.ACCENT_EDGE;
    private static final int TEXT = AmberTheme.TEXT;
    private static final int TEXT_DIM = AmberTheme.TEXT_DIM;
    private static final int SURFACE = AmberTheme.CARD;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String name = "wlmosv";
    private String subtitle = "Android / Xposed / 逆向";
    private String initial = "W";
    /** 头像位图。为空时用程序绘制的字母徽记。 */
    private android.graphics.Bitmap avatar;
    private final Paint pAvatar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAvatarRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AuthorCard(Context c) { this(c, null); }

    public AuthorCard(Context c, AttributeSet a) {
        super(c, a);
        pText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pText.setTextAlign(Paint.Align.LEFT);
        pSub.setTextAlign(Paint.Align.LEFT);
        pMark.setTextAlign(Paint.Align.CENTER);
        pMark.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        pAvatarRing.setStyle(Paint.Style.STROKE);
        pAvatarRing.setStrokeWidth(dp(2f));
        pAvatarRing.setColor(ACCENT);
    }

    /** 设置头像位图（可传 null 回退到字母徽记） */
    public void setAvatar(android.graphics.Bitmap bmp) {
        this.avatar = bmp;
        invalidate();
    }

    public void set(String name, String subtitle, String initial) {
        this.name = name;
        this.subtitle = subtitle;
        this.initial = initial;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(Math.round(dp(118)), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float pad = dp(1.5f);
        RectF whole = new RectF(pad, pad, w - pad, h - pad);

        // 卡片底：竖向渐变，上浅下深
        pBg.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{AmberTheme.CARD_2, SURFACE},
                null, Shader.TileMode.CLAMP));
        cv.drawRoundRect(whole, dp(20), dp(20), pBg);
        pBg.setShader(null);

        // 琥珀描边
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1.4f));
        pRing.setColor(ACCENT_DIM);
        cv.drawRoundRect(whole, dp(20), dp(20), pRing);
        pRing.setStyle(Paint.Style.FILL);

        // 左侧头像区
        float cx = dp(56), cy = h / 2f;
        float r = dp(30);

        if (avatar != null && !avatar.isRecycled()) {
            // 真实头像：居中裁圆形贴上去
            cv.save();
            Path clip = new Path();
            clip.addCircle(cx, cy, r - dp(2.5f), Path.Direction.CW);
            cv.clipPath(clip);
            RectF dst = new RectF(cx - r, cy - r, cx + r, cy + r);
            cv.drawBitmap(avatar, null, dst, pAvatar);
            cv.restore();

            // 外圈琥珀描边
            cv.drawCircle(cx, cy, r - dp(1.2f), pAvatarRing);
        } else {
            // 回退：同心环 + 字母
            pFill.setColor(AmberTheme.NEUTRAL_BG);
            cv.drawCircle(cx, cy, r, pFill);

            pRing.setStyle(Paint.Style.STROKE);
            pRing.setStrokeWidth(dp(1.6f));
            pRing.setColor(ACCENT);
            cv.drawCircle(cx, cy, r - dp(2), pRing);
            pRing.setColor(ACCENT_DIM);
            cv.drawCircle(cx, cy, r - dp(8), pRing);
            pRing.setStyle(Paint.Style.FILL);

            pMark.setTextSize(dp(22));
            pMark.setColor(ACCENT);
            Paint.FontMetrics fm = pMark.getFontMetrics();
            cv.drawText(initial, cx, cy - (fm.ascent + fm.descent) / 2f, pMark);
        }

        // 右侧文字
        float tx = cx + r + dp(18);

        pText.setTextSize(dp(19));
        pText.setColor(TEXT);
        Paint.FontMetrics nfm = pText.getFontMetrics();
        float nameY = cy - dp(10);
        cv.drawText(name, tx, nameY, pText);

        pSub.setTextSize(dp(11.5f));
        pSub.setColor(TEXT_DIM);
        cv.drawText(subtitle, tx, nameY + dp(20), pSub);

        // 底部一条琥珀短线，作为视觉落点
        pFill.setColor(ACCENT);
        RectF line = new RectF(tx, nameY + dp(28), tx + dp(36), nameY + dp(30.5f));
        cv.drawRoundRect(line, dp(1.5f), dp(1.5f), pFill);
    }
}

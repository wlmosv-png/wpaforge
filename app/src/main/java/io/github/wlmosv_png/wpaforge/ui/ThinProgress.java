package io.github.wlmosv_png.wpaforge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 圆润进度条。胶囊形轨道 + 琥珀填充 + 进度文字。
 */
public class ThinProgress extends View {

    private float progress = 0f;      // 0..1
    private String leftText = "";
    private String rightText = "";
    private String subText = "";

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLeft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int accent = Color.parseColor("#FFB300");
    private int track = Color.parseColor("#242430");
    private int textColor = Color.parseColor("#CFCFD6");
    private int subColor = Color.parseColor("#8A8A94");

    public ThinProgress(Context c) { this(c, null); }

    public ThinProgress(Context c, AttributeSet a) {
        super(c, a);
        pLeft.setTextAlign(Paint.Align.LEFT);
        pRight.setTextAlign(Paint.Align.RIGHT);
        pSub.setTextAlign(Paint.Align.LEFT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setProgress(float p) {
        this.progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    /** @param left 左侧说明 @param right 右侧百分比 */
    public void setText(String left, String right) {
        this.leftText = left == null ? "" : left;
        this.rightText = right == null ? "" : right;
        invalidate();
    }

    public void setSubText(String s) {
        this.subText = s == null ? "" : s;
        invalidate();
    }

    public void setAccent(int color) {
        this.accent = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth();
        if (w == 0) return;

        float barH = dp(10);
        float top = dp(22);
        float radius = barH / 2f;

        // 轨道
        RectF r = new RectF(0, top, w, top + barH);
        pTrack.setColor(track);
        cv.drawRoundRect(r, radius, radius, pTrack);

        // 填充
        if (progress > 0.001f) {
            float fw = Math.max(barH, w * progress);
            RectF f = new RectF(0, top, fw, top + barH);
            pFill.setColor(accent);
            cv.drawRoundRect(f, radius, radius, pFill);
        }

        // 顶部两行文字
        pLeft.setTextSize(dp(12));
        pLeft.setColor(textColor);
        cv.drawText(leftText, 0, dp(14), pLeft);

        pRight.setTextSize(dp(12));
        pRight.setColor(accent);
        cv.drawText(String.format("%.0f%%", progress * 100), w, dp(14), pRight);

        // 底部补充
        if (!subText.isEmpty()) {
            pSub.setTextSize(dp(11));
            pSub.setColor(subColor);
            cv.drawText(subText, 0, top + barH + dp(15), pSub);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int h = Math.round(dp(subText.isEmpty() ? 48 : 64));
        super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}

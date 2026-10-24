package io.github.wlmosv_png.wpaforge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 雷达扫掠视图。同心圆 + 旋转扫描线 + 命中点。
 * 这是主界面的视觉核心，形态上和任何"卡片+进度条"的界面都不像。
 */
public class RadarSweep extends View {

    public static final class Blip {
        public final float angleDeg;   // 0..360
        public final float distance;  // 0..1
        public final boolean strong;
        public final boolean locked;
        public Blip(float angleDeg, float distance, boolean strong, boolean locked) {
            this.angleDeg = angleDeg;
            this.distance = distance;
            this.strong = strong;
            this.locked = locked;
        }
    }

    private Blip[] blips = new Blip[0];
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBlip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int accent = AmberTheme.ACCENT;
    private float sweepAngle = 0f;
    private boolean spinning = false;
    private long lastFrame = 0;
    private String centerText = "";
    private String subText = "";

    public RadarSweep(Context c) { this(c, null); }

    public RadarSweep(Context c, AttributeSet a) {
        super(c, a);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1.2f));
        pRing.setColor(AmberTheme.BTN_2ND_EDGE);

        pText.setTextAlign(Paint.Align.CENTER);
        pText.setColor(AmberTheme.TEXT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setBlips(Blip[] b) {
        this.blips = b == null ? new Blip[0] : b;
        invalidate();
    }

    public void setCenterText(String t, String sub) {
        this.centerText = t == null ? "" : t;
        this.subText = sub == null ? "" : sub;
        invalidate();
    }

    public void startSpin() {
        boolean wasStopped = !spinning;
        spinning = true;
        // 关键：即使已经是 spinning，也要重置时间基准并重新投递动画回调。
        // 从后台切回来时 View 的 invalidate 链条会断，不重投就永远停住。
        lastFrame = SystemClock.uptimeMillis();
        if (wasStopped || !isAttachedToWindow()) {
            postInvalidateOnAnimation();
        } else {
            // 已经附着但可能动画停了，强制再投一次
            postInvalidateOnAnimation();
        }
    }

    public void stopSpin() {
        spinning = false;
        // 移除待处理的动画回调，避免后台空转
        removeCallbacks(spinTick);
    }

    /** 自定义一帧推进，便于在可见性变化时重新接管 */
    private final Runnable spinTick = this::postInvalidateOnAnimation;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (spinning) {
            lastFrame = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && spinning) {
            // 回到前台：重置时间基准，继续转
            lastFrame = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(14);

        // 同心圆环
        for (int i = 1; i <= 4; i++) {
            cv.drawCircle(cx, cy, radius * i / 4f, pRing);
        }
        // 十字准线
        cv.drawLine(cx - radius, cy, cx + radius, cy, pRing);
        cv.drawLine(cx, cy - radius, cx, cy + radius, pRing);

        // 扫描扇形：一段渐变弧 + 拖尾线
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        pSweep.setShader(new LinearGradient(cx, cy, cx + radius, cy,
                new int[]{Color.TRANSPARENT, AmberTheme.ACCENT_GLOW},
                null, Shader.TileMode.CLAMP));
        cv.drawArc(oval, sweepAngle - 40, 40, true, pSweep);
        pSweep.setShader(null);

        // 扫描线
        double rad = Math.toRadians(sweepAngle);
        float ex = cx + (float) Math.cos(rad) * radius;
        float ey = cy + (float) Math.sin(rad) * radius;
        pSweep.setColor(accent);
        pSweep.setStrokeWidth(dp(2f));
        cv.drawLine(cx, cy, ex, ey, pSweep);

        // 命中点
        for (Blip b : blips) {
            double a = Math.toRadians(b.angleDeg);
            float d = radius * Math.max(0.05f, Math.min(1f, b.distance));
            float bx = cx + (float) Math.cos(a) * d;
            float by = cy + (float) Math.sin(a) * d;

            // 越新扫到的越亮：用角度差做淡出
            float diff = Math.abs(((sweepAngle - b.angleDeg) % 360 + 360) % 360);
            float alpha = diff < 60 ? 1f : Math.max(0.25f, 1f - (diff - 60) / 300f);

            pBlip.setColor(b.locked ? accent : AmberTheme.DANGER);
            pBlip.setAlpha((int) (255 * alpha));
            cv.drawCircle(bx, by, b.strong ? dp(5f) : dp(3.2f), pBlip);
        }

        // 中心文字
        if (!centerText.isEmpty()) {
            pText.setTextSize(dp(26));
            pText.setColor(AmberTheme.TEXT);
            cv.drawText(centerText, cx, cy - dp(2), pText);
            pText.setTextSize(dp(11));
            pText.setColor(AmberTheme.TEXT_DIM);
            cv.drawText(subText, cx, cy + dp(16), pText);
        }

        if (spinning) {
            long now = SystemClock.uptimeMillis();
            float dt = (now - lastFrame) / 1000f;
            lastFrame = now;
            sweepAngle = (sweepAngle + dt * 70f) % 360f; // 约 5.1 秒一圈
            postInvalidateOnAnimation();
        }
    }
}

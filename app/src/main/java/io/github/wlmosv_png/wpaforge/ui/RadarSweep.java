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
 * 雷达扫掠视图。同心圆 + 旋转扫描线 + 每个 AP 一个跳动点。
 *
 * <h3>每个点就是一台 AP（2026-10-24 用户要求后重做）</h3>
 * <ul>
 *   <li><b>一 AP 一点</b>：调用方按「可见网络」逐个构造，不做采样、不合并；</li>
 *   <li><b>越强越靠里</b>：信号强度归一化后映射到「离圆心越近」——
 *       圆心＝贴着你的那台 AP，外圈＝几乎听不见的；</li>
 *   <li><b>越强越大、越亮</b>：档位色 {@link AmberTheme#signalColor}，
 *       半径按档位递增，最强的还带一圈光晕；</li>
 *   <li><b>会跳动</b>：每个点有自己的相位，半径与透明度随时间轻微起伏 ——
 *       画面是活的，而不是一张静态散点图。</li>
 * </ul>
 *
 * <p>跳动与扫描是两件事：扫描线只在 {@link #startSpin()} 后转；
 * 落点只要还在前台就会一直轻轻起伏（{@link #stopSpin()} 不会让点僵住）。
 */
public class RadarSweep extends View {

    /**
     * 一个落点。
     *
     * @param angleDeg 方位角（0..360），由调用方给定，决定它在圆周上的方向
     * @param distance 离圆心的比例（0..1）；**0.30 以下会被夹住**，避免压住中心数字
     * @param level    信号档位 0..4（0 最弱）
     * @param secured  是否加密（未加密的点描一圈红边）
     * @param phase    跳动相位 0..1：调用方按索引给，保证各点不同步
     */
    public static final class Blip {
        public final float angleDeg;
        public final float distance;
        public final int level;
        public final boolean secured;
        public final float phase;

        public Blip(float angleDeg, float distance, int level, boolean secured, float phase) {
            this.angleDeg = angleDeg;
            this.distance = distance;
            this.level = level;
            this.secured = secured;
            this.phase = phase;
        }
    }

    /** 落点允许的最近半径比例：再往里就会盖住中心的大数字。 */
    private static final float MIN_DIST = 0.30f;

    private Blip[] blips = new Blip[0];
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBlip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float sweepAngle = 0f;
    private boolean spinning = false;
    /** 只要有点就保持轻微跳动（不依赖扫描是否在转）。 */
    private boolean pulsing = false;
    private long lastFrame = 0;
    private String centerText = "";
    private String subText = "";

    public RadarSweep(Context c) { this(c, null); }

    public RadarSweep(Context c, AttributeSet a) {
        super(c, a);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1.2f));
        pRing.setColor(AmberTheme.BTN_2ND_EDGE);

        pEdge.setStyle(Paint.Style.STROKE);
        pEdge.setStrokeWidth(dp(1.2f));
        pEdge.setColor(AmberTheme.DANGER);

        pText.setTextAlign(Paint.Align.CENTER);
        pText.setColor(AmberTheme.TEXT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setBlips(Blip[] b) {
        this.blips = b == null ? new Blip[0] : b;
        pulsing = blips.length > 0;
        lastFrame = SystemClock.uptimeMillis();
        kick();
    }

    public void setCenterText(String t, String sub) {
        this.centerText = t == null ? "" : t;
        this.subText = sub == null ? "" : sub;
        invalidate();
    }

    public void startSpin() {
        spinning = true;
        lastFrame = SystemClock.uptimeMillis();
        kick();
    }

    public void stopSpin() {
        spinning = false;
        kick();
    }

    /** 是否需要继续出帧（转 or 跳）。 */
    private void kick() {
        if (spinning || pulsing) postInvalidateOnAnimation();
        else invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrame = SystemClock.uptimeMillis();
        kick();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 离开窗口就不要再排队出帧了（后台空转是耗电大户）
        removeCallbacks(tick);
    }

    private final Runnable tick = this::postInvalidateOnAnimation;

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            lastFrame = SystemClock.uptimeMillis();
            kick();
        } else {
            removeCallbacks(tick);
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : (now - lastFrame) / 1000f;
        lastFrame = now;
        if (dt > 0.25f) dt = 0.25f;          // 切回前台时不要跳一大步
        float t = now / 1000f;

        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(14);

        // 同心圆环 + 十字准线
        for (int i = 1; i <= 4; i++) {
            cv.drawCircle(cx, cy, radius * i / 4f, pRing);
        }
        cv.drawLine(cx - radius, cy, cx + radius, cy, pRing);
        cv.drawLine(cx, cy - radius, cx, cy + radius, pRing);

        // 扫描扇形 + 扫描线（只在扫描进行时转）
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        pSweep.setShader(new LinearGradient(cx, cy, cx + radius, cy,
                new int[]{Color.TRANSPARENT, AmberTheme.ACCENT_GLOW},
                null, Shader.TileMode.CLAMP));
        cv.drawArc(oval, sweepAngle - 40, 40, true, pSweep);
        pSweep.setShader(null);

        double rad = Math.toRadians(sweepAngle);
        float ex = cx + (float) Math.cos(rad) * radius;
        float ey = cy + (float) Math.sin(rad) * radius;
        pSweep.setColor(AmberTheme.ACCENT);
        pSweep.setStrokeWidth(dp(2f));
        cv.drawLine(cx, cy, ex, ey, pSweep);

        // 落点：一 AP 一点，越强越靠里、越大、越亮，并随时间跳动
        for (Blip b : blips) {
            double a = Math.toRadians(b.angleDeg);
            float d = radius * Math.max(MIN_DIST, Math.min(1f, b.distance));
            float bx = cx + (float) Math.cos(a) * d;
            float by = cy + (float) Math.sin(a) * d;

            // 跳动：半径 ±18%、透明度 ±15%，各点相位不同 → 整片"活着"
            float pulse = (float) Math.sin((t * 1.6f + b.phase) * 2 * Math.PI);
            float baseR = dp(2.4f + b.level * 0.95f);
            float r = Math.max(dp(1.8f), baseR * (1f + 0.18f * pulse));

            int color = AmberTheme.signalColor(b.level);
            int alpha = (int) (255 * Math.min(1f, 0.86f + 0.14f * pulse));

            // 强信号：外圈光晕
            if (b.level >= 3) {
                pBlip.setColor(color);
                pBlip.setAlpha((int) (alpha * 0.28f));
                cv.drawCircle(bx, by, r * 2.1f, pBlip);
            }

            pBlip.setColor(color);
            pBlip.setAlpha(alpha);
            cv.drawCircle(bx, by, r, pBlip);

            // 未加密：描一圈红边（与列表里的红胶囊同一套语义）
            if (!b.secured) {
                pEdge.setAlpha((int) (alpha * 0.9f));
                cv.drawCircle(bx, by, r + dp(1.6f), pEdge);
            }
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

        if (spinning) sweepAngle = (sweepAngle + dt * 70f) % 360f;   // 约 5.1 秒一圈
        if (spinning || pulsing) postInvalidateOnAnimation();
    }
}

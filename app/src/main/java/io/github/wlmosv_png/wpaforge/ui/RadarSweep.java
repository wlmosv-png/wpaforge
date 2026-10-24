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
 * 信号雷达 —— 极坐标刻度盘（2026-10-24 第二次重做）。
 *
 * <h3>上一版为什么"怪异"（用户反馈）</h3>
 * <ol>
 *   <li><b>点全挤在圆心</b>：半径按 {@code 1 - (rssi+100)/70} 连续映射，而实测网络
 *       集中在 -40~-70dBm，算出来几乎都贴到内圈下限，外圈完全空着 ——
 *       看起来就是"一团黄球堆在中间"；</li>
 *   <li><b>光晕叠成一坨</b>：每个强点画 2.1 倍半径的半透明大圆，几十个一叠，
 *       糊成一片黄雾；</li>
 *   <li><b>乱跳</b>：每个点各自按相位做正弦抖动，像抽筋，不像仪表；</li>
 *   <li><b>扫描线僵在半空</b>：扫描结束后指针还冻在某个角度，像坏掉的表针。</li>
 * </ol>
 *
 * <h3>现在的做法：档位即刻度，点沿环均匀铺开</h3>
 * <ul>
 *   <li><b>五道信号环</b>（≥-50 / -60 / -70 / -80 / ≤-90 dBm，越强越靠里），
 *       环上标出 dBm 刻度 —— "越强越靠里"这件事从此**可读**，而不只是好看；</li>
 *   <li><b>一 AP 一点</b>，落在自己档位的那道环上；同一道环内的点沿圆周**均匀铺开**，
 *       所以永远不会重叠、也不会堆在圆心；</li>
 *   <li><b>不抖</b>：点的位置与大小固定（大小按档位 3.0→5.4dp）。唯一的"活"来自扫描 ——
 *       被光束扫过的点会亮起来，随后按角度差缓慢回落，像真雷达的余辉；</li>
 *   <li><b>不转的时候就是一张干净的刻度盘</b>：扫描结束不画指针，也不后台出帧（省电）。</li>
 * </ul>
 *
 * <p>未加密的点套一圈细红边（与列表里的红胶囊同一套语义）；最强档再加一层很淡的光晕。
 */
public class RadarSweep extends View {

    /**
     * 一个落点。
     *
     * @param angleDeg 建议方位角：仅用于同档位内的排序（保证刷新时位置稳定），
     *                 实际方位由视图在该环上均匀分配，避免重叠
     * @param level    信号档位 0..4（0 最弱）
     * @param secured  是否加密（未加密套红边）
     */
    public static final class Blip {
        public final float angleDeg;
        public final int level;
        public final boolean secured;

        public Blip(float angleDeg, int level, boolean secured) {
            this.angleDeg = angleDeg;
            this.level = level;
            this.secured = secured;
        }
    }

    /** 五道信号环的半径比例（下标＝档位，越大越靠里）。 */
    private static final float[] RING = {0.98f, 0.835f, 0.69f, 0.545f, 0.40f};
    /** 环刻度文字（与该环的档位一一对应）。 */
    private static final String[] RING_LABEL = {"\u2264-90", "-80", "-70", "-60", "\u2265-50"};
    /** 每档的点半径（dp，下标＝档位）。 */
    private static final float[] DOT_DP = {3.0f, 3.6f, 4.2f, 4.8f, 5.4f};
    /** 被光束扫过之后的余辉：从亮到暗的最低值。 */
    private static final float GLOW_MIN = 0.55f;

    private Blip[] blips = new Blip[0];
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDrum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pScale = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float sweepAngle = 0f;
    private boolean spinning = false;
    private long lastFrame = 0;
    private String centerText = "";
    private String subText = "";

    public RadarSweep(Context c) { this(c, null); }

    public RadarSweep(Context c, AttributeSet a) {
        super(c, a);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1f));
        pRing.setColor(AmberTheme.EDGE);

        pAxis.setStyle(Paint.Style.STROKE);
        pAxis.setStrokeWidth(dp(1f));
        pAxis.setColor(AmberTheme.GRID);

        pEdge.setStyle(Paint.Style.STROKE);
        pEdge.setStrokeWidth(dp(1.1f));
        pEdge.setColor(AmberTheme.DANGER);

        pText.setTextAlign(Paint.Align.CENTER);
        pText.setColor(AmberTheme.TEXT);

        pScale.setTextAlign(Paint.Align.LEFT);
        pScale.setTextSize(dp(9f));
        pScale.setColor(AmberTheme.TEXT_FAINT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setBlips(Blip[] b) {
        this.blips = b == null ? new Blip[0] : b;
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

    /** 停止扫描：指针与光晕一并收起，回到静态刻度盘（不再出帧）。 */
    public void stopSpin() {
        spinning = false;
        kick();
    }

    private void kick() {
        if (spinning) postInvalidateOnAnimation();
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
        if (dt > 0.2f) dt = 0.2f;

        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(16);

        // ── 刻度盘：五道信号环 + 细化十字轴 + 环上的 dBm 刻度
        for (int i = 0; i < RING.length; i++) {
            float rr = radius * RING[i];
            cv.drawCircle(cx, cy, rr, pRing);
            // 刻度字贴在该环的左下 45°，避开中心的数字
            float a = (float) Math.toRadians(135);
            float tx = cx + (float) Math.cos(a) * rr;
            float ty = cy + (float) Math.sin(a) * rr;
            cv.drawText(RING_LABEL[i], tx + dp(4), ty - dp(3), pScale);
        }
        for (int i = 1; i <= 3; i++) {   // 细十字轴（比环更淡）
            float rr = radius * i / 3.4f;
            float d = rr * 0.42f;
            cv.drawLine(cx - d, cy, cx + d, cy, pAxis);
            cv.drawLine(cx, cy - d, cx, cy + d, pAxis);
        }

        // ── 扫描：只在扫描时画（停下就是一张干净的盘）
        if (spinning) {
            RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            pSweep.setShader(new LinearGradient(cx, cy, cx + radius, cy,
                    new int[]{Color.TRANSPARENT, AmberTheme.ACCENT_GLOW}, null,
                    Shader.TileMode.CLAMP));
            cv.drawArc(oval, sweepAngle - 46, 46, true, pSweep);
            pSweep.setShader(null);

            double rad = Math.toRadians(sweepAngle);
            float ex = cx + (float) Math.cos(rad) * radius;
            float ey = cy + (float) Math.sin(rad) * radius;
            pDrum.setColor(AmberTheme.ACCENT);
            pDrum.setStrokeWidth(dp(1.6f));
            cv.drawLine(cx, cy, ex, ey, pDrum);
        }

        // ── 落点：按档位分桶，桶内沿环均匀铺开（永不重叠、不挤中心）
        int[] bucketSize = new int[RING.length];
        for (Blip b : blips) bucketSize[lv(b)]++;
        int[] used = new int[RING.length];
        for (Blip b : blips) {
            int level = lv(b);
            int idx = used[level]++;
            int n = Math.max(1, bucketSize[level]);
            // 每档整体偏转一点，避免不同环上的点排成同一根辐条
            float angle = (360f * idx / n) + level * 26f;

            double a = Math.toRadians(angle);
            float rr = radius * RING[level];
            float bx = cx + (float) Math.cos(a) * rr;
            float by = cy + (float) Math.sin(a) * rr;

            // 余辉：被光束扫过最亮，随后按角度差缓慢回落（只在扫描时变化）
            float alpha = 0.92f;
            if (spinning) {
                float diff = ((sweepAngle - angle) % 360f + 360f) % 360f;
                alpha = diff <= 26f ? 1f
                        : GLOW_MIN + (1f - GLOW_MIN) * Math.max(0f, 1f - (diff - 26f) / 220f);
            }

            int color = AmberTheme.signalColor(level);
            if (level >= 4) {   // 最强档：一层很淡的光晕（不再是 2 倍半径的糊圈）
                pDot.setColor(color);
                pDot.setAlpha((int) (alpha * 60));
                cv.drawCircle(bx, by, dp(DOT_DP[level]) * 1.9f, pDot);
            }
            pDot.setColor(color);
            pDot.setAlpha((int) (alpha * 255));
            cv.drawCircle(bx, by, dp(DOT_DP[level]), pDot);

            if (!b.secured) {   // 未加密：细红边（与列表红胶囊同一语义）
                pEdge.setAlpha((int) (alpha * 220));
                cv.drawCircle(bx, by, dp(DOT_DP[level]) + dp(1.5f), pEdge);
            }
        }

        // ── 中心：大数字 + 副文字
        if (!centerText.isEmpty()) {
            pText.setTextSize(dp(26));
            pText.setColor(AmberTheme.TEXT);
            cv.drawText(centerText, cx, cy - dp(2), pText);
            pText.setTextSize(dp(11));
            pText.setColor(AmberTheme.TEXT_DIM);
            cv.drawText(subText, cx, cy + dp(16), pText);
        }

        if (spinning) {
            sweepAngle = (sweepAngle + dt * 62f) % 360f;   // 约 5.8 秒一圈
            postInvalidateOnAnimation();
        }
    }

    private static int lv(Blip b) {
        return Math.max(0, Math.min(4, b.level));
    }
}

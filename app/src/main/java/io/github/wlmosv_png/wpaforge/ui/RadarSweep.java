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

    /**
     * 五道信号环的半径比例（下标＝档位，越大越靠里）。
     *
     * <p>最内环从 0.40 外移到 0.51：原来最强档的点紧贴中心的大数字，
     * 加上刻度字也在那儿，看起来就是"中间一团"。现在中心留出一圈干净的空，
     * 强信号的那道环在中层，读数与点都不打架。
     */
    private static final float[] RING = {0.97f, 0.855f, 0.74f, 0.625f, 0.51f};
    /** 环刻度文字（与该环的档位一一对应）。 */
    private static final String[] RING_LABEL = {"\u2264-90", "-80", "-70", "-60", "\u2265-50"};
    /** 每档的点半径（dp，下标＝档位）。 */
    private static final float[] DOT_DP = {3.0f, 3.6f, 4.2f, 4.8f, 5.4f};
    /** 被光束扫过之后的余辉：从亮到暗的最低值。 */
    private static final float GLOW_MIN = 0.55f;
    /** 同一道环内两点之间至少保留的点径倍数（防稠密用）。 */
    private static final float DOT_GAP = 2.7f;
    /** 同档超过单环容量时的分层偏移（半径比例）。 */
    private static final float LAYER_OFFSET = 0.075f;

    private Blip[] blips = new Blip[0];
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDrum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pScale = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 刻度字底下的色块（与卡片同色）。 */
    private final Paint pPatch = new Paint(Paint.ANTI_ALIAS_FLAG);

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
            // 刻度做成**纵轴上的刻度尺**（上半天、写在纵轴右侧）：
            // 斜排会跟中心的数字、以及点挤在一起；竖排一眼就能读出"哪道环是多少 dBm"。
            // 底下垫一块与卡片同色的底片，点多的时候刻度也不会被点压住。
            float tx = cx + dp(5);
            float ty = cy - rr + dp(3);
            float tw = pScale.measureText(RING_LABEL[i]);
            pPatch.setColor(AmberTheme.CARD);
            pPatch.setAlpha(238);
            RectF patch = new RectF(tx - dp(3), ty - dp(9), tx + tw + dp(3), ty + dp(4));
            cv.drawRoundRect(patch, dp(3), dp(3), pPatch);
            cv.drawText(RING_LABEL[i], tx, ty, pScale);
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

        // ── 落点：按档位分桶，桶内沿环均匀铺开。
        //
        // 稠密处理（WiFi 很多时）：单环容量按「环周长 / 点直径 / 间隔」算，
        // 超过容量就**自动分层**（同一档位拆成 2~3 道子环），点径再按层内点数收缩。
        // 这样 20 台和 120 台都不会糊成一圈 —— 既能一 AP 一点，也不重叠。
        int[] bucketSize = new int[RING.length];
        for (Blip b : blips) bucketSize[lv(b)]++;
        int[] layers = new int[RING.length];
        int[] perLayer = new int[RING.length];
        for (int i = 0; i < RING.length; i++) {
            int n = bucketSize[i];
            float ringLen = (float) (2 * Math.PI * radius * RING[i]);
            int cap = Math.max(1, (int) (ringLen / (dp(DOT_DP[i]) * DOT_GAP)));
            // 最多拆三道子环：再多就会串到邻档的带上，反而看不出档位
            layers[i] = Math.max(1, Math.min(3, (int) Math.ceil(n / (float) cap)));
            perLayer[i] = (int) Math.ceil(n / (float) layers[i]);
        }
        int[] used = new int[RING.length];
        for (Blip b : blips) {
            int level = lv(b);
            int idx = used[level]++;
            int n = Math.max(1, perLayer[level]);
            int layer = idx / n;                       // 第几道子环
            int slot = idx % n;
            // 每档整体偏转一点，避免不同环上的点排成同一根辐条
            float angle = (360f * slot / n) + level * 26f + layer * (180f / Math.max(1, n));

            float ringR = RING[level];
            if (layers[level] > 1) {
                // 子环围绕该档环向外/向内错开；夹在 0.28~1.0 之间不越界
                float off = (layer - (layers[level] - 1) / 2f) * LAYER_OFFSET;
                ringR = Math.max(0.28f, Math.min(1f, ringR + off));
            }
            double a = Math.toRadians(angle);
            float rr = radius * ringR;
            float bx = cx + (float) Math.cos(a) * rr;
            float by = cy + (float) Math.sin(a) * rr;

            // 余辉：被光束扫过最亮，随后按角度差缓慢回落（只在扫描时变化）
            float alpha = 0.92f;
            if (spinning) {
                float diff = ((sweepAngle - angle) % 360f + 360f) % 360f;
                alpha = diff <= 26f ? 1f
                        : GLOW_MIN + (1f - GLOW_MIN) * Math.max(0f, 1f - (diff - 26f) / 220f);
            }

            // 点径：档位基准，随「本层点数」收缩 —— 挤的时候自动变小，永不粘连
            float ringLen = (float) (2 * Math.PI * radius * ringR);
            float dotR = Math.min(dp(DOT_DP[level]), ringLen / (DOT_GAP * n) / 2f * 1.5f);
            dotR = Math.max(dp(1.9f), dotR);

            int color = AmberTheme.signalColor(level);
            if (level >= 4) {
                // 最强档：一圈**细亮环**（原来那层大光晕会被看成"糊"，这里换成清晰的环）
                pEdge.setColor(AmberTheme.ACCENT_SOFT);
                pEdge.setStyle(Paint.Style.STROKE);
                pEdge.setAlpha((int) (alpha * 170));
                cv.drawCircle(bx, by, dotR + dp(1.6f), pEdge);
                pEdge.setColor(AmberTheme.DANGER);
            }
            pDot.setColor(color);
            pDot.setAlpha((int) (alpha * 255));
            cv.drawCircle(bx, by, dotR, pDot);

            if (!b.secured) {   // 未加密：细红边（与列表红胶囊同一语义）
                pEdge.setAlpha((int) (alpha * 220));
                cv.drawCircle(bx, by, dotR + dp(1.4f), pEdge);
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

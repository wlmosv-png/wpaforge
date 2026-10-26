package io.github.wlmosv_png.wpaforge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.wlmosv_png.wpaforge.scan.BlipLayout;
import io.github.wlmosv_png.wpaforge.scan.RadarAnimator;
import io.github.wlmosv_png.wpaforge.scan.ChannelMap;

/**
 * 信号雷达 —— 极坐标刻度盘（2026-10-26 第三次重做：频谱盘）。
 *
 * <h3>历史<\/h3>
 * <ul>
 *   <li><b>第一版<\/b>：点全挤圆心、光晕糊成黄雾、每点各自抖动（用户：\"一团黄球\"）；<\/li>
 *   <li><b>第二版<\/b>：档位即刻度（5 道环）、点沿圆周均匀铺开、不抖。结构对了，
 *       但**方位角仍是装饰** —— 位置来自\"按信号排序的序号\"，信号一抖点就换位，
 *       新 AP 进来其余点全体挪一格；23 个网络谁是谁也无从对应；<\/li>
 *   <li><b>本版<\/b>：方位角＝<b>信道<\/b>。圆盘变成频谱环：上半圆是 2.4G 的
 *       14 个信道，下半圆是 5G 的 4 个 UNII 组（见 {@link ChannelMap}）。<\/li>
 * <\/ul>
 *
 * <h3>这一版解决什么<\/h3>
 * <ol>
 *   <li><b>能读<\/b>：同一扇区里点越多 = 这条信道越挤。这正是用户要做的决定
 *       ——「我该把路由改到哪条」，而且与 {@link ChannelBars} \/
 *       {@code ChannelAnalyzer} 的推荐互相印证；<\/li>
 *   <li><b>位置稳定<\/b>：信道不变，方位就不变。扇区内按 BSSID 排序，新 AP 只在自己的
 *       扇区里插空，不会让全场重排；<\/li>
 *   <li><b>刻度不再被压住<\/b>：环上的 dBm 刻度挪到**最后一步**画（点之上），
 *       底片不透明 —— 原来画在点之前，用户截图里 {@code ≥-50} 那一格正被亮点盖住；<\/li>
 *   <li><b>扫描光束不再盖数据<\/b>：实心黄扇换成\"指针 + 拖尾\"，不填充任何面，
 *       余辉也调浅（原来 20% 不透明度的扇形压在右下 1/4 的数据上）。<\/li>
 * <\/ol>
 *
 * <p>频段用**填充方式**区分而不是颜色：2.4G 实心、5G 空心环。这样不占用
 * 颜色通道（颜色留给信号强度与\"未加密\"的红边），色盲用户也分得清。
 */
public class RadarSweep extends View {

    /** 一个落点。 */
    public static final class Blip {
        /** 稳定身份（BSSID）：同扇区内按它排序，保证刷新后位置不乱跳 */
        public final String key;
        /** 信道（2.4G 1..14；5G 36..177；未知填 0） */
        public final int channel;
        public final boolean is5G;
        /** 信号档位 0..4（0 最弱） */
        public final int level;
        /** 是否加密（未加密套红边） */
        public final boolean secured;

        public Blip(String key, int channel, boolean is5G, int level, boolean secured) {
            this.key = key == null ? "" : key;
            this.channel = channel;
            this.is5G = is5G;
            this.level = level;
            this.secured = secured;
        }

        /** 老调用方兼容：没有信道信息时落到 2.4G 的第 1 个扇区（不丢点、不抛异常） */
        public Blip(float angleDeg, int level, boolean secured) {
            this("", 0, false, level, secured);
        }
    }

    /**
     * 五道信号环的半径比例（下标＝档位，越大越靠里）。
     * 最强档从 0.40 外移到 0.51：中心留出一圈干净的空，读数与点都不打架。
     */
    private static final float[] RING = {0.97f, 0.855f, 0.74f, 0.625f, 0.51f};
    /**
     * 环刻度文字（与该环的档位一一对应，下标＝档位）。
     *
     * <p><b>这些字现在不画在盘上了。</b>上一版把它们排在最后一步画，
     * 结果是"点被数字压住"（用户截图）。盘面本来就窄，文字与点在同一个平面
     * 抢位置，怎么调 z 序都是输 —— 所以改成：**盘上只留信道，dBm 刻度走盘下的图例**。
     * 档位顺序仍然由这里定义，图例与测试都从 {\@link #ringLabels()} 取，
     * 不允许在别处另写一份。
     */
    private static final String[] RING_LABEL = {"\u2264-90", "-80", "-70", "-60", "\u2265-50"};

    /** 图例用：五档 dBm 文字（由内到外＝由强到弱） */
    public static String[] ringLabels() {
        return RING_LABEL.clone();
    }

    /** 图例用：七档……五档信号色的取样点（下标＝档位） */
    public static int[] ringColors() {
        int[] c = new int[RING.length];
        for (int i = 0; i < c.length; i++) c[i] = AmberTheme.signalColor(i);
        return c;
    }
    /** 每档的点半径（dp，下标＝档位）。 */
    private static final float[] DOT_DP = {3.0f, 3.6f, 4.2f, 4.8f, 5.4f};
    /** 被光束扫过之后的余辉：从亮到暗的最低值。 */
    private static final float GLOW_MIN = 0.72f;

    private Blip[] blips = new Blip[0];
    /** 过渡状态机：新点淡入、消失点淡出、位置变化走缓动 */
    private final RadarAnimator anim = new RadarAnimator();
    private boolean animating = false;
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSector = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDrum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pScale = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBand = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 刻度字底下的色块（与卡片同色）。 */
    private final Paint pPatch = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float sweepAngle = 0f;
    private boolean spinning = false;
    private long lastFrame = 0;
    private String centerText = "";
    private String subText = "";
    /** 中心数字的滚动动画：显示值向目标值靠（数字刷新时不再是"啪"地跳变） */
    private float centerShown = 0f;
    private float centerTarget = 0f;
    private boolean centerNumeric = false;

    public RadarSweep(Context c) { this(c, null); }

    public RadarSweep(Context c, AttributeSet a) {
        super(c, a);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1f));
        pRing.setColor(AmberTheme.EDGE);

        pAxis.setStyle(Paint.Style.STROKE);
        pAxis.setStrokeWidth(dp(1f));
        pAxis.setColor(AmberTheme.GRID);

        pSector.setStyle(Paint.Style.STROKE);
        pSector.setStrokeWidth(dp(0.8f));
        pSector.setColor(AmberTheme.GRID);

        pEdge.setStyle(Paint.Style.STROKE);
        pEdge.setStrokeWidth(dp(1.6f));
        pEdge.setColor(AmberTheme.DANGER);

        pText.setTextAlign(Paint.Align.CENTER);
        pText.setColor(AmberTheme.TEXT);

        pScale.setTextAlign(Paint.Align.LEFT);
        pScale.setTextSize(dp(9f));
        pScale.setColor(AmberTheme.TEXT_FAINT);

        pBand.setTextAlign(Paint.Align.CENTER);
        pBand.setTextSize(dp(9f));
        pBand.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * 刷新落点。
     *
     * <p>这里**不直接把新位置画出来** —— 交给 {@link RadarAnimator} 按 BSSID 匹配旧点，
     * 位置变化走缓动、新增淡入、消失淡出，避免"画面被整块替换"的生硬感。
     */
    public void setBlips(Blip[] b) {
        this.blips = b == null ? new Blip[0] : b;
        if (getWidth() == 0) {
            // 还没测量：没有半径可算，等 onSizeChanged/下一帧再设
            pendingTargets = true;
            return;
        }
        applyTargets();
        lastFrame = SystemClock.uptimeMillis();
        kick();
    }

    private boolean pendingTargets = false;

    private void applyTargets() {
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(16);
        if (radius <= 0) return;
        List<BlipLayout.In> ins = new ArrayList<>(blips.length);
        for (int i = 0; i < blips.length; i++) {
            Blip b = blips[i];
            ins.add(new BlipLayout.In(b.key, b.channel, b.is5G, lv(b), b.secured, i));
        }
        anim.setTargets(ins, radius,
                getResources().getDisplayMetrics().density, RING, DOT_DP);
    }

    public void setCenterText(String t, String sub) {
        this.centerText = t == null ? "" : t;
        this.subText = sub == null ? "" : sub;
        // 纯数字：让数字滚上去（首次直接到位，不从 0 数）
        int n = -1;
        try {
            n = Integer.parseInt(this.centerText.trim());
        } catch (Exception ignored) {}
        centerNumeric = n >= 0;
        if (centerNumeric) {
            if (centerShown == 0f) centerShown = n;
            centerTarget = n;
        }
        lastFrame = SystemClock.uptimeMillis();
        kick();
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
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (pendingTargets || anim.size() == 0) {
            pendingTargets = false;
            anim.clear();
            applyTargets();
            anim.settle();      // 首次布局直接到位，别从中心"长"出来
            kick();
        }
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

        if (pendingTargets) {
            pendingTargets = false;
            applyTargets();
        }
        anim.advance(dt * 1000f);
        animating = anim.busy();

        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(16);

        // ── 1. 刻度盘：五道信号环 + 细十字轴
        for (int i = 0; i < RING.length; i++) {
            cv.drawCircle(cx, cy, radius * RING[i], pRing);
        }
        for (int i = 1; i <= 3; i++) {
            float rr = radius * i / 3.4f;
            float d = rr * 0.42f;
            cv.drawLine(cx - d, cy, cx + d, cy, pAxis);
            cv.drawLine(cx, cy - d, cx, cy + d, pAxis);
        }

        // ── 2. 扇区分隔：方位角＝信道，所以这条线是「频谱的分格」
        drawSectors(cv, cx, cy, radius);

        // ── 3. 扫描：指针 + 拖尾（**不填充扇形**，别盖住数据）
        if (spinning) {
            double rad = Math.toRadians(sweepAngle);
            for (int i = 5; i >= 0; i--) {
                double a = Math.toRadians(sweepAngle - i * 3.4f);
                float al = (1f - i / 6f) * 0.45f;
                pDrum.setColor(AmberTheme.ACCENT);
                pDrum.setAlpha((int) (al * 255));
                pDrum.setStrokeWidth(dp(i == 0 ? 1.8f : 1.0f));
                cv.drawLine(cx + (float) Math.cos(a) * radius * 0.12f,
                        cy + (float) Math.sin(a) * radius * 0.12f,
                        cx + (float) Math.cos(a) * radius,
                        cy + (float) Math.sin(a) * radius, pDrum);
            }
            pDrum.setAlpha(255);
            cv.drawCircle(cx + (float) Math.cos(rad) * radius,
                    cy + (float) Math.sin(rad) * radius, dp(2.4f), pDrum);
        }

        // ── 4. 落点：**按信道扇区分组**，组内按 BSSID 排序（位置稳定）
        drawBlips(cv, cx, cy, radius);

        // ── 5. 信道标签最后画 + 底色片：盘上文字只剩这 7 个短标（1/6/11 与 4 个 5G 组），
        //      压在点上也只是压一点点，比"点被数字盖住"好读得多
        drawSectorLabels(cv, cx, cy, radius);

        // ── 6. 中心：大数字 + 副文字
        if (!centerText.isEmpty()) {
            pText.setTextSize(dp(24));
            pText.setColor(AmberTheme.TEXT);
            String big = centerText;
            if (centerNumeric) {
                float k = (float) (1.0 - Math.exp(-(dt * 1000f) / 220f));
                centerShown += (centerTarget - centerShown) * k;
                if (Math.abs(centerTarget - centerShown) < 0.5f) centerShown = centerTarget;
                big = String.valueOf(Math.round(centerShown));
            }
            cv.drawText(big, cx, cy - dp(2), pText);
            pText.setTextSize(dp(10.5f));
            pText.setColor(AmberTheme.TEXT_DIM);
            cv.drawText(subText, cx, cy + dp(15), pText);
        }

        if (spinning) {
            sweepAngle = (sweepAngle + dt * 62f) % 360f;   // 约 5.8 秒一圈
        }
        // 只有"还在转"或"还在过渡"时才继续出帧 —— 静止的盘一秒都不重绘
        boolean centerBusy = centerNumeric && Math.abs(centerTarget - centerShown) >= 0.5f;
        if (spinning || animating || centerBusy) postInvalidateOnAnimation();
    }

    /** 扇区分隔线 + 频段标签（2.4G 在上半圆，5G 在下半圆） */
    private void drawSectors(Canvas cv, float cx, float cy, float radius) {
        float w24 = ChannelMap.slotWidth(false);
        for (int i = 0; i <= ChannelMap.CH_24_COUNT; i++) {
            float a = ChannelMap.HALF + i * w24;
            boolean divider = (i == 0 || i == ChannelMap.CH_24_COUNT);
            // 只切颜色：主题色自带 alpha，再 setAlpha 会把透明度覆盖成不透明
            pSector.setColor(divider ? AmberTheme.HAIRLINE : AmberTheme.GRID);
            double r = Math.toRadians(a);
            cv.drawLine(cx + (float) Math.cos(r) * radius * 0.51f,
                    cy + (float) Math.sin(r) * radius * 0.51f,
                    cx + (float) Math.cos(r) * radius,
                    cy + (float) Math.sin(r) * radius, pSector);
        }
        float w5 = ChannelMap.slotWidth(true);
        for (int i = 0; i <= ChannelMap.GROUP_5_COUNT; i++) {
            float a = i * w5;
            double r = Math.toRadians(a);
            pSector.setColor(i == 0 || i == ChannelMap.GROUP_5_COUNT
                    ? AmberTheme.HAIRLINE : AmberTheme.GRID);
            cv.drawLine(cx + (float) Math.cos(r) * radius * 0.51f,
                    cy + (float) Math.sin(r) * radius * 0.51f,
                    cx + (float) Math.cos(r) * radius,
                    cy + (float) Math.sin(r) * radius, pSector);
        }

    }

    /** 扇区标签：2.4G 只标 1 / 6 / 11，5G 标组范围；带底色片，画在点之上 */
    private void drawSectorLabels(Canvas cv, float cx, float cy, float radius) {
        pBand.setTextSize(dp(9f));
        for (int ch = 1; ch <= ChannelMap.CH_24_COUNT; ch++) {
            if (!ChannelMap.isKey24Channel(ch)) continue;
            pBand.setColor(AmberTheme.ACCENT_MID);
            drawTinyLabel(cv, cx, cy, radius, ChannelMap.slotCenter(false, ch),
                    String.valueOf(ch), false);
        }
        for (int g = 0; g < ChannelMap.GROUP_5_COUNT; g++) {
            pBand.setColor(AmberTheme.TEXT_FAINT);
            drawTinyLabel(cv, cx, cy, radius,
                    g * ChannelMap.slotWidth(true) + ChannelMap.slotWidth(true) / 2f,
                    ChannelMap.slotLabel(true, g == 0 ? 36 : g == 1 ? 52 : g == 2 ? 100 : 149),
                    true);
        }
    }

    /** 一个带底色片的小标签，落在 radius*0.905 的位置 */
    private void drawTinyLabel(Canvas cv, float cx, float cy, float radius,
                               float angleDeg, String text, boolean wide) {
        double r = Math.toRadians(angleDeg);
        float rr = radius * 0.90f;
        float tx = cx + (float) Math.cos(r) * rr;
        float ty = cy + (float) Math.sin(r) * rr + dp(3);
        float tw = pBand.measureText(text);
        pPatch.setColor(AmberTheme.CARD);
        pPatch.setAlpha(215);
        RectF patch = new RectF(tx - tw / 2f - dp(3), ty - dp(9),
                tx + tw / 2f + dp(3), ty + dp(4));
        cv.drawRoundRect(patch, dp(3), dp(3), pPatch);
        cv.drawText(text, tx, ty, pBand);
    }

    /**
     * 落点：几何交给 {@link BlipLayout}，过渡交给 {@link RadarAnimator}（都是纯逻辑、有单测）。
     *
     * <p>视图这里只管画 —— 位置对不对、刷新顺不顺由测试保证，不靠肉眼。
     */
    private void drawBlips(Canvas cv, float cx, float cy, float radius) {
        if (anim.states().isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;

        for (RadarAnimator.State st : anim.states()) {
            if (!st.visible()) continue;
            double a = Math.toRadians(st.angleDeg);
            float bx = cx + (float) Math.cos(a) * radius * st.ratio;
            float by = cy + (float) Math.sin(a) * radius * st.ratio;

            // 余辉：被光束扫过最亮，随后按角度差缓慢回落（只在扫描时变化）
            float alpha = st.alpha * 0.94f;
            if (spinning) {
                float diff = ((sweepAngle - st.angleDeg) % 360f + 360f) % 360f;
                alpha = st.alpha * (diff <= 22f ? 1f
                        : GLOW_MIN + (1f - GLOW_MIN) * Math.max(0f, 1f - (diff - 22f) / 200f));
            }
            int alpha255 = (int) (Math.max(0f, Math.min(1f, alpha)) * 255f);

            int level = Math.max(0, Math.min(4, st.level));
            int color = AmberTheme.signalColor(level);

            if (st.count > 0) {
                // 这一格塞不下这么多点：画一个「×N」的标，比硬挤成一坨信息量大
                pDot.setColor(color);
                pDot.setAlpha(alpha255);
                cv.drawCircle(bx, by, dp(9f) * st.alpha, pDot);
                if (st.alpha > 0.5f) {
                    pText.setTextSize(dp(9f));
                    pText.setColor(AmberTheme.ON_ACCENT);
                    pText.setAlpha((int) ((st.alpha - 0.5f) * 2f * 255f));
                    cv.drawText(String.valueOf(st.count), bx, by + dp(3.2f), pText);
                    pText.setAlpha(255);
                }
                continue;
            }

            if (level >= 4) {   // 最强档：一圈细亮环
                pEdge.setColor(AmberTheme.ACCENT_SOFT);
                pEdge.setStyle(Paint.Style.STROKE);
                pEdge.setAlpha((int) (st.alpha * 170));
                cv.drawCircle(bx, by, st.dotR + dp(1.6f), pEdge);
            }

            pDot.setColor(color);
            pDot.setAlpha(alpha255);
            if (st.is5G) {
                // 5G：空心环 —— 用**填充方式**区分频段，不占用颜色通道
                pDot.setStyle(Paint.Style.STROKE);
                pDot.setStrokeWidth(dp(1.5f));
                cv.drawCircle(bx, by, st.dotR, pDot);
                pDot.setStyle(Paint.Style.FILL);
            } else {
                cv.drawCircle(bx, by, st.dotR, pDot);
            }

            if (!st.secured) {   // 未加密：红边（深底上要看得见）
                pEdge.setColor(AmberTheme.DANGER);
                pEdge.setAlpha((int) (st.alpha * 235));
                cv.drawCircle(bx, by, st.dotR + dp(1.7f), pEdge);
            }
        }
    }

    private static int lv(Blip b) {
        return Math.max(0, Math.min(4, b.level));
    }
}

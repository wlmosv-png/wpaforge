package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.L;

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
 * 信号柱状图。纯 Canvas 绘制。
 *
 * 布局要点（解决标签重叠）：
 *  - 柱子按可用宽度自动收窄，网络多时更细
 *  - 底部标签旋转 -55° 摆放，横向占用小、互不重叠
 *  - 网络超过一定数量时只画柱子不画标签，避免糊成一团
 */
public class SignalBars extends View {

    public static final class Bar {
        public final String label;
        public final int rssi;
        public final boolean is5G;
        public final boolean locked;
        public Bar(String label, int rssi, boolean is5G, boolean locked) {
            this.label = label;
            this.rssi = rssi;
            this.is5G = is5G;
            this.locked = locked;
        }
    }

    /** 标签上限。超过这个数量意味着柱子已经细到放不下文字，
     *  此时改为只标首尾几个，而不是全部不画。 */
    private static final int LABEL_LIMIT = 32;
    /** 标签最短可读宽度（dp）。低于这个值就不画该标签。 */
    private static final float MIN_LABEL_SLOT_DP = 11f;

    private List<Bar> bars = new ArrayList<>();
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLabel = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int accent, accent5g, accentDim, textMain, textDim, gridColor;

    public SignalBars(Context c) { this(c, null); }

    public SignalBars(Context c, AttributeSet a) {
        super(c, a);
        accent = Color.parseColor("#FFB300");
        accent5g = Color.parseColor("#FFD54F");
        accentDim = Color.parseColor("#7A5600");
        textMain = Color.parseColor("#E0E0E6");
        textDim = Color.parseColor("#8A8A94");
        gridColor = Color.parseColor("#22FFFFFF");

        pGrid.setStrokeWidth(2f);
        pGrid.setColor(gridColor);

        pValue.setTextSize(dp(10));
        pValue.setColor(textDim);
        pValue.setTextAlign(Paint.Align.CENTER);

        pLabel.setTextSize(dp(9.5f));
        pLabel.setColor(textDim);
        pLabel.setTextAlign(Paint.Align.RIGHT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setBars(List<Bar> list) {
        this.bars = list == null ? new ArrayList<>() : list;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float padTop = dp(20), padSide = dp(6);
        int n = bars.size();
        // 是否画标签：数量在限内，且每个槽位有足够宽度
        float slot0 = n > 0 ? (w - padSide * 2) / n : 0;
        // 标签能不能画：数量在限内，且槽位有足够宽度
        boolean labelAllowed = n > 0 && n <= LABEL_LIMIT && slot0 >= dp(MIN_LABEL_SLOT_DP);
        // 槽位窄时不是全砍，而是隔几个画一个 —— 保证仍有可读的标注
        int labelStride = 1;
        if (labelAllowed) {
            float need = dp(26);   // 一个可读标签大致需要的横向空间
            if (slot0 < need) {
                labelStride = (int) Math.ceil(need / Math.max(1f, slot0));
            }
        }
        float padBottom = labelAllowed ? dp(54) : dp(12);

        float chartH = h - padTop - padBottom;
        if (chartH <= 0) chartH = h * 0.5f;

        // 基线
        cv.drawLine(padSide, h - padBottom, w - padSide, h - padBottom, pGrid);

        if (n == 0) {
            pValue.setTextSize(dp(12));
            pValue.setColor(textDim);
            cv.drawText(L.s("暂无扫描结果", "No scan results yet"), w / 2f, h / 2f, pValue);
            return;
        }

        float slot = (w - padSide * 2) / n;
        // 柱子宽度：随数量自适应，最粗 18dp，最细 3dp
        float barW = Math.min(slot * 0.62f, dp(18));
        barW = Math.max(barW, dp(2.5f));

        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            float cx = padSide + slot * i + slot / 2f;

            float t = (b.rssi + 100f) / 70f;
            t = Math.max(0.05f, Math.min(1f, t));
            float barH = chartH * t;

            RectF r = new RectF(cx - barW / 2f, h - padBottom - barH,
                    cx + barW / 2f, h - padBottom);

            // 柱体：5G 亮黄，2.4G 琥珀，未加密的偏暗
            int base = b.is5G ? accent5g : accent;
            pBar.setColor(b.locked ? base : dim(base));
            cv.drawRoundRect(r, dp(2), dp(2), pBar);

            // 顶端高亮小段
            pTop.setColor(b.is5G ? Color.parseColor("#FFFFFF") : Color.parseColor("#FFE082"));
            RectF top = new RectF(r.left, r.top, r.right, r.top + Math.min(barH, dp(4)));
            cv.drawRoundRect(top, dp(2), dp(2), pTop);

            // 未加密：柱顶画空心警示圆
            if (!b.locked) {
                pBar.setStyle(Paint.Style.STROKE);
                pBar.setStrokeWidth(dp(1.4f));
                pBar.setColor(Color.parseColor("#FF5252"));
                cv.drawCircle(cx, r.top - dp(7), dp(3f), pBar);
                pBar.setStyle(Paint.Style.FILL);
            }

            // 顶部数值：同样抽稀，且槽位太窄就不画
            if (slot >= dp(16) && (i % Math.max(1, labelStride) == 0)) {
                pValue.setColor(textMain);
                cv.drawText(String.valueOf(b.rssi), cx, r.top - dp(13), pValue);
            }

            // 底部标签：按 stride 抽稀绘制，避免互相叠成一团噪声
            if (labelAllowed && (i % labelStride == 0)) {
                float labelBottom = h - padBottom + dp(6);
                pLabel.setTextSize(dp(9.5f));
                cv.save();
                cv.rotate(-55, cx, labelBottom);
                String lbl = b.label;
                if (lbl.length() > 10) lbl = lbl.substring(0, 10) + "…";
                pLabel.setColor(textDim);
                cv.drawText(lbl, cx + dp(1), labelBottom + dp(10), pLabel);
                cv.restore();
            }
        }
    }

    private static int dim(int c) {
        int r = (int) (((c >> 16) & 0xFF) * 0.55f);
        int g = (int) (((c >> 8) & 0xFF) * 0.55f);
        int b = (int) ((c & 0xFF) * 0.55f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

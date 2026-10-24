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

import io.github.wlmosv_png.wpaforge.scan.ChannelAnalyzer;

/**
 * 信道占用横向条。
 *
 * 为什么不用柱状图：
 *   柱状图看"哪个信道挤"要横向对比高度，还要数格子；
 *   而 WiFi Analyzer 那类工具用**横向条**，因为信道名在左、
 *   占用条向右延展，扫一眼就知道谁最长 —— 这正是"干扰信道"
 *   要表达的语义。所以这里改成横向条。
 *
 * 视觉约定：
 *   · 左列固定信道号（ch 11）
 *   · 右边横条长度 = 该信道的拥挤度（按全场最大值归一化）
 *   · 条上叠加 AP 数量文字
 *   · 最空的那个信道用绿色高亮 + 文字标注"推荐"
 *   · 2.4G / 5G 分组，各自一个推荐
 */
public class ChannelBars extends View {

    /** 一行 */
    private static final class Row {
        final int channel;
        final String band;
        final int apCount;
        final int load;
        final boolean recommended;
        Row(int channel, String band, int apCount, int load, boolean recommended) {
            this.channel = channel;
            this.band = band;
            this.apCount = apCount;
            this.load = load;
            this.recommended = recommended;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    /** 分组标题（插在行之间） */
    private final List<int[]> sectionAt = new ArrayList<>();   // {行索引, 类型} 1=2.4G 2=5G

    private final Paint pLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCount = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRec = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int accent, ok, danger, warn, textMain, textDim, track;

    private float rowH;

    public ChannelBars(Context c) { this(c, null); }

    public ChannelBars(Context c, AttributeSet a) {
        super(c, a);
        accent   = AmberTheme.ACCENT;
        ok       = AmberTheme.OK;
        danger   = AmberTheme.DANGER;
        warn     = AmberTheme.ACCENT;
        textMain = AmberTheme.TEXT;
        textDim  = AmberTheme.TEXT_DIM;
        track    = AmberTheme.GRID;

        rowH = dp(26);

        pLabel.setTextSize(dp(11));
        pLabel.setColor(textMain);
        pLabel.setTextAlign(Paint.Align.LEFT);

        pCount.setTextSize(dp(10));
        pCount.setTextAlign(Paint.Align.LEFT);

        pSection.setTextSize(dp(10));
        pSection.setColor(textDim);
        pSection.setTextAlign(Paint.Align.LEFT);
        pSection.setLetterSpacing(0.1f);

        pRec.setTextSize(dp(9.5f));
        pRec.setColor(ok);
        pRec.setTextAlign(Paint.Align.RIGHT);

        pTrack.setColor(track);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 用分析报告刷新视图 */
    public void setReport(ChannelAnalyzer.Report rep) {
        rows.clear();
        sectionAt.clear();
        if (rep == null) { invalidate(); return; }

        // 全场最大 load，用于归一化条长
        int maxLoad = 1;
        for (ChannelAnalyzer.ChannelStat st : rep.stats24) maxLoad = Math.max(maxLoad, st.loadScore);
        for (ChannelAnalyzer.ChannelStat st : rep.stats5) maxLoad = Math.max(maxLoad, st.loadScore);

        // 2.4G：所有信道都列出来（1..13），没数据的也画空条，
        // 这样"哪个信道空"是直接的视觉结论，不用心算
        sectionAt.add(new int[]{rows.size(), 1});
        for (int ch = 1; ch <= 13; ch++) {
            ChannelAnalyzer.ChannelStat st = find(rep.stats24, ch);
            rows.add(new Row(ch, "2.4G",
                    st == null ? 0 : st.apCount,
                    st == null ? 0 : st.loadScore,
                    ch == rep.recommended24));
        }

        if (!rep.stats5.isEmpty()) {
            sectionAt.add(new int[]{rows.size(), 2});
            for (ChannelAnalyzer.ChannelStat st : rep.stats5) {
                rows.add(new Row(st.channel, "5G", st.apCount, st.loadScore,
                        st.channel == rep.recommended5));
            }
        }

        // 视图高度 = 行数 * 行高 + 分组标题空间
        int sections = sectionAt.size();
        setMinimumHeight((int) (rows.size() * rowH + sections * dp(24) + dp(8)));
        this.maxLoad = maxLoad;
        requestLayout();
        invalidate();
    }

    private int maxLoad = 1;

    private static ChannelAnalyzer.ChannelStat find(
            List<ChannelAnalyzer.ChannelStat> list, int ch) {
        for (ChannelAnalyzer.ChannelStat st : list) if (st.channel == ch) return st;
        return null;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) (rows.size() * rowH + sectionAt.size() * dp(24) + dp(8));
        if (h <= 0) h = (int) dp(60);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth();
        if (w == 0) return;

        float padSide = dp(12);
        float chW = dp(34);            // 左列信道号宽度
        float countW = dp(46);         // 右侧计数宽度
        float barLeft = padSide + chW;
        float barRight = w - padSide - countW;
        float barMaxW = Math.max(dp(20), barRight - barLeft);

        float y = dp(4);
        int sectionIdx = 0;

        for (int i = 0; i < rows.size(); i++) {
            // 到这个行之前要不要插分组标题
            while (sectionIdx < sectionAt.size() && sectionAt.get(sectionIdx)[0] == i) {
                int type = sectionAt.get(sectionIdx)[1];
                String title = type == 1
                        ? L.s("2.4 GHz", "2.4 GHz")
                        : L.s("5 GHz", "5 GHz");
                cv.drawText(title, padSide, y + dp(14), pSection);
                y += dp(24);
                sectionIdx++;
            }

            Row r = rows.get(i);
            float cy = y + rowH / 2f;

            // 左：信道号
            pLabel.setColor(r.recommended ? ok : textMain);
            cv.drawText("ch " + r.channel, padSide, cy + dp(4), pLabel);

            // 中：轨道 + 实条
            float barH = dp(9);
            RectF trackR = new RectF(barLeft, cy - barH / 2f, barRight, cy + barH / 2f);
            cv.drawRoundRect(trackR, barH / 2f, barH / 2f, pTrack);

            float ratio = maxLoad <= 0 ? 0 : (float) r.load / maxLoad;
            ratio = Math.max(0f, Math.min(1f, ratio));
            if (r.apCount > 0) {
                float fillW = Math.max(dp(4), barMaxW * ratio);
                RectF fill = new RectF(barLeft, cy - barH / 2f, barLeft + fillW, cy + barH / 2f);
                // 颜色按拥挤度分档：绿(空) -> 橙 -> 红(挤)
                pBar.setColor(r.recommended ? ok
                        : ratio > 0.72f ? danger
                        : ratio > 0.42f ? warn
                        : accent);
                cv.drawRoundRect(fill, barH / 2f, barH / 2f, pBar);
            }

            // 右：AP 数 + 推荐标记
            pCount.setColor(r.apCount == 0 ? textDim : textMain);
            String cnt = r.apCount == 0
                    ? L.s("空", "clear")
                    : L.f("%d 个", "%d", r.apCount);
            cv.drawText(cnt, barRight + dp(6), cy + dp(4), pCount);

            if (r.recommended) {
                pRec.setColor(ok);
                cv.drawText(L.s("推荐", "best"), w - padSide, cy + dp(4), pRec);
            }

            y += rowH;
        }
    }
}

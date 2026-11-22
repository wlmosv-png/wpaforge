package io.github.wlmosv_png.wpaforge.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 状态图标 —— **全部 Canvas 自绘，不用 Unicode 字符、不用 emoji、不用位图**。
 *
 * <h3>为什么必须自绘（1.9.68）<\/h3>
 * 上一版状态行用 `▶`、`‖`、`✓` 这些 Unicode 符号：不同字体下字形、基线、
 * 光学大小全不一样，22sp 文字旁边挂一个 8sp 观感的小三角，
 * 看起来就是「临时拼接」。而且符号无法跟随主题色与尺寸统一缩放。
 *
 * <p>现在每个图标都在正方形画布里按短边比例绘制（u = 短边），
 * 20dp 与 40dp 下比例完全一致；颜色由 {@link #setTint(int)} 统一控制，
 * 与主状态文字同色。
 */
public final class StatusIcons extends Drawable {

    /** 状态图标种类。 */
    public enum Kind {
        /** 空心圆：未开始。 */
        CIRCLE,
        /** 实心三角（播放）：正在运行。 */
        PLAY,
        /** 两条竖杠：已暂停。 */
        PAUSE,
        /** 实心方块：已停止。 */
        STOP,
        /** 对勾：破解成功。 */
        CHECK,
        /** 叉号：已证实密码错误。 */
        CROSS,
        /** 小实心圆点：普通未命中（中性，不抢眼）。 */
        DOT,
        /** 感叹号：环境异常。 */
        WARN,
        /** 三点省略：准备中 / 收尾中。 */
        DOTS,
        /** 折叠箭头（合拢）：攻击流已收起。 */
        CHEVRON_RIGHT,
        /** 折叠箭头（张开）：攻击流已展开。 */
        CHEVRON_DOWN
    }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    public StatusIcons(Kind kind, int color) {
        this.kind = kind == null ? Kind.DOT : kind;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    /** 换色（与主状态文字同色）。 */
    public void setTint(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b == null || b.width() <= 0 || b.height() <= 0) return;
        float u = Math.min(b.width(), b.height());
        float cx = b.centerX();
        float cy = b.centerY();

        switch (kind) {
            case CIRCLE: {
                // 空心圆：环宽 = u * 0.09
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u * 0.11f);
                float r = u * 0.34f;
                canvas.drawCircle(cx, cy, r, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case PLAY: {
                // 1.9.68：三角放大到短边 0.66（原来 0.42/0.36 在 22sp 画布里太小），
                // 视觉重心右移一点补偿三角的光学中心偏左。
                path.reset();
                float h = u * 0.66f;
                float w = u * 0.56f;
                float x0 = cx - w * 0.5f + u * 0.03f;
                path.moveTo(x0, cy - h * 0.5f);
                path.lineTo(x0, cy + h * 0.5f);
                path.lineTo(x0 + w, cy);
                path.close();
                canvas.drawPath(path, paint);
                break;
            }
            case PAUSE: {
                float barW = u * 0.15f;
                float barH = u * 0.52f;
                float gap = u * 0.13f;
                canvas.drawRect(cx - gap * 0.5f - barW, cy - barH * 0.5f,
                        cx - gap * 0.5f, cy + barH * 0.5f, paint);
                canvas.drawRect(cx + gap * 0.5f, cy - barH * 0.5f,
                        cx + gap * 0.5f + barW, cy + barH * 0.5f, paint);
                break;
            }
            case STOP: {
                float s = u * 0.30f;
                oval.set(cx - s, cy - s, cx + s, cy + s);
                canvas.drawRoundRect(oval, u * 0.06f, u * 0.06f, paint);
                break;
            }
            case CHECK: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u * 0.11f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(cx - u * 0.26f, cy + u * 0.02f);
                path.lineTo(cx - u * 0.06f, cy + u * 0.22f);
                path.lineTo(cx + u * 0.28f, cy - u * 0.20f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeCap(Paint.Cap.BUTT);
                break;
            }
            case CROSS: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u * 0.11f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float d = u * 0.24f;
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeCap(Paint.Cap.BUTT);
                break;
            }
            case DOT: {
                // 小实心圆：普通未命中，刻意做小，不抢视觉
                canvas.drawCircle(cx, cy, u * 0.18f, paint);
                break;
            }
            case WARN: {
                // 圆角三角 + 挖空感叹号
                path.reset();
                float h = u * 0.40f;
                float w = u * 0.44f;
                path.moveTo(cx, cy - h * 0.55f);
                path.lineTo(cx + w * 0.55f, cy + h * 0.48f);
                path.lineTo(cx - w * 0.55f, cy + h * 0.48f);
                path.close();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u * 0.085f);
                paint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                // 感叹号
                canvas.drawRect(cx - u * 0.035f, cy - u * 0.10f,
                        cx + u * 0.035f, cy + u * 0.10f, paint);
                canvas.drawCircle(cx, cy + u * 0.20f, u * 0.038f, paint);
                break;
            }
            case CHEVRON_RIGHT: {
                // 1.9.69：箭头几何重定 —— **尺寸只由高度决定、墨迹贴住左缘**。
                //
                // 旧写法用 u = min(w,h) 且把三角画在 cx 两侧，于是 26dp 方形视图里
                // 真正的墨迹只有中间 ~11dp，左右各白扔 7dp。图标本身没错，错在
                // 「它以为自己占 26dp，其实只画了 11dp」——视图左缘与被看到的左缘
                // 差 23px（density 3 下 ≈7.7dp），真机截图里箭头明显比「正在尝试」
                // 「候选大字」那一列往右缩进去一小块。
                //
                // 现在：横向用 b.height() 定尺寸（窄高图标不能按宽度缩），
                // 路径起点 = b.left + 描边半宽 → 圆头笔帽外沿正好落在 b.left，
                // 于是「箭头墨迹左缘 == 卡片内容左缘 == 文字列左缘」。
                float uh = b.height();
                float sw = uh * 0.16f;
                float x0 = b.left + sw * 0.5f;
                float x1 = x0 + uh * 0.30f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sw);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(x0, cy - uh * 0.28f);
                path.lineTo(x1, cy);
                path.lineTo(x0, cy + uh * 0.28f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeCap(Paint.Cap.BUTT);
                break;
            }
            case CHEVRON_DOWN: {
                // 与 CHEVRON_RIGHT 同一套口径：左缘固定、宽度受视图宽度约束。
                // 展开态标题的左边缘与折叠态完全一致 → 点开/收起时标题不会横跳。
                float uh = b.height();
                float sw = uh * 0.16f;
                float dx0 = b.left + sw * 0.5f;
                float dx1 = dx0 + uh * 0.30f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sw);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(dx0, cy - uh * 0.13f);
                path.lineTo((dx0 + dx1) * 0.5f, cy + uh * 0.17f);
                path.lineTo(dx1, cy - uh * 0.13f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeCap(Paint.Cap.BUTT);
                break;
            }
            case DOTS:
            default: {
                float r = u * 0.075f;
                float gap = u * 0.16f;
                canvas.drawCircle(cx - gap, cy, r, paint);
                canvas.drawCircle(cx, cy, r, paint);
                canvas.drawCircle(cx + gap, cy, r, paint);
                break;
            }
        }
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

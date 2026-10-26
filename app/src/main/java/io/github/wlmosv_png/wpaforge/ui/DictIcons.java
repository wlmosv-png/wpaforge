package io.github.wlmosv_png.wpaforge.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 字典来源那一屏用的矢量图标 —— **全部用 Canvas 自绘，不用 emoji、不用位图**。
 *
 * 为什么自绘：
 *  · emoji 在不同 ROM 上字形、颜色、基线全不一样（同一个 🎯 在 ColorOS 和
 *    原生上能差出一个字号），而这个 App 的视觉是统一的暗底琥珀色系；
 *  · 位图要为每个 dpi 出图，还要处理换色；
 *  · 自绘只有一个类，颜色/尺寸/线宽全跟着 AmberTheme 走，永远不跑偏。
 *
 * 所有图形都在一个正方形画布里按比例绘制（u = 短边），
 * 所以同一份代码在 20dp 和 28dp 下比例完全一致。
 */
public final class DictIcons extends Drawable {

    /** 四种来源 + 右侧的箭头/勾 */
    public enum Kind {
        PROFILE,   // 画像：头像（圆头 + 肩线）
        TARGET,    // 派生：靶心（两环 + 芯）
        FILE,      // 文件：文档（圆角矩形 + 三条线）
        GENERIC,   // 通用：齿轮（圆 + 八根齿）
        ARROW,     // 右侧 ›
        CHECK,     // 右侧 ✓
        PICK,      // 多选框：空心圆（未选）
        PICKED,    // 多选框：实心圆 + 勾（已选）
        MORE       // 行内「⋯」：更多操作
    }

    private final Kind kind;
    private final int color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private DictIcons(Kind kind, int color) {
        this.kind = kind;
        this.color = color;
    }

    /**
     * 建一个图标，并按 dp 设定边界。
     *
     * @param density 屏幕密度（{@code getResources().getDisplayMetrics().density}）
     * @param sizeDp  边长（dp）
     */
    public static DictIcons sized(Kind kind, int color, float density, float sizeDp) {
        DictIcons d = new DictIcons(kind, color);
        int s = Math.max(1, Math.round(sizeDp * density));
        d.setBounds(0, 0, s, s);
        return d;
    }

    /** 换色（选中态要用强调色，避免重建对象） */
    public DictIcons recolored(int c) {
        return new DictIcons(kind, c);
    }

    @Override
    public void draw(Canvas cv) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;
        float cx = b.left + w / 2f;
        float cy = b.top + h / 2f;
        float u = Math.min(w, h);

        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, u * 0.078f));

        switch (kind) {
            case PROFILE: {
                // 头
                cv.drawCircle(cx, cy - u * 0.135f, u * 0.155f, p);
                // 肩：下半段圆弧，两端收在画布内
                RectF sh = new RectF(cx - u * 0.30f, cy + u * 0.06f,
                        cx + u * 0.30f, cy + u * 0.66f);
                cv.drawArc(sh, 180f, 180f, false, p);
                break;
            }
            case TARGET: {
                cv.drawCircle(cx, cy, u * 0.345f, p);
                cv.drawCircle(cx, cy, u * 0.175f, p);
                Paint f = new Paint(p);
                f.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy, u * 0.062f, f);
                break;
            }
            case FILE: {
                RectF r = new RectF(cx - u * 0.265f, cy - u * 0.345f,
                        cx + u * 0.265f, cy + u * 0.345f);
                cv.drawRoundRect(r, u * 0.085f, u * 0.085f, p);
                p.setStrokeWidth(Math.max(1f, u * 0.062f));
                float x0 = cx - u * 0.135f, x1 = cx + u * 0.135f;
                cv.drawLine(x0, cy - u * 0.145f, x1, cy - u * 0.145f, p);
                cv.drawLine(x0, cy, x1, cy, p);
                cv.drawLine(x0, cy + u * 0.145f, x1 - u * 0.115f, cy + u * 0.145f, p);
                break;
            }
            case GENERIC: {
                // 齿轮。要点在**厚环**：细环 + 外伸齿在 22dp 下会读成"太阳"，
                // 试过三版（细齿外伸 / 短粗齿 / 粗环），只有厚环这一版在小尺寸下认得出齿轮。
                p.setStrokeWidth(Math.max(1.6f, u * 0.17f));
                cv.drawCircle(cx, cy, u * 0.205f, p);
                p.setStrokeWidth(Math.max(1.4f, u * 0.125f));
                for (int i = 0; i < 8; i++) {
                    double ang = Math.toRadians(i * 45.0);
                    float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
                    cv.drawLine(cx + c * u * 0.235f, cy + s * u * 0.235f,
                            cx + c * u * 0.345f, cy + s * u * 0.345f, p);
                }
                break;
            }
            case ARROW: {
                cv.drawLine(cx - u * 0.13f, cy - u * 0.265f, cx + u * 0.135f, cy, p);
                cv.drawLine(cx + u * 0.135f, cy, cx - u * 0.13f, cy + u * 0.265f, p);
                break;
            }
            case CHECK: {
                cv.drawLine(cx - u * 0.30f, cy + u * 0.02f, cx - u * 0.075f, cy + u * 0.255f, p);
                cv.drawLine(cx - u * 0.075f, cy + u * 0.255f, cx + u * 0.31f, cy - u * 0.255f, p);
                break;
            }
            case MORE: {
                Paint f = new Paint(p);
                f.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy - u * 0.27f, u * 0.088f, f);
                cv.drawCircle(cx, cy, u * 0.088f, f);
                cv.drawCircle(cx, cy + u * 0.27f, u * 0.088f, f);
                break;
            }
            case PICK: {
                // 空心圆：一眼看出"这里能选"
                cv.drawCircle(cx, cy, u * 0.38f, p);
                break;
            }
            case PICKED: {
                // 实心圆 + 勾：选中态要一眼可辨，所以用填色 + 反色勾
                Paint f = new Paint(p);
                f.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy, u * 0.40f, f);
                p.setColor(AmberTheme.ON_ACCENT);   // 勾画在琥珀底上，用深色
                p.setStrokeWidth(Math.max(1.6f, u * 0.115f));
                cv.drawLine(cx - u * 0.20f, cy + u * 0.02f, cx - u * 0.05f, cy + u * 0.18f, p);
                cv.drawLine(cx - u * 0.05f, cy + u * 0.18f, cx + u * 0.21f, cy - u * 0.17f, p);
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

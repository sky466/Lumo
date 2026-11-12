package com.letvcast.player;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 主题按钮图标：浅色模式画太阳，深色模式画月亮。
 * 用代码画而不是放 PNG，任何分辨率都清楚，颜色也能跟着主题走。
 */
public class ThemeIcon extends Drawable {

    private final boolean sun;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public ThemeIcon(int color, boolean sun) {
        this.sun = sun;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float r = size * 0.24f;
        if (sun) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, size * 0.075f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float inner = r * 1.65f;
            float outer = r * 2.25f;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float x1 = (float) (cx + Math.cos(a) * inner);
                float y1 = (float) (cy + Math.sin(a) * inner);
                float x2 = (float) (cx + Math.cos(a) * outer);
                float y2 = (float) (cy + Math.sin(a) * outer);
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
        } else {
            drawMoon(canvas, cx, cy, size);
        }
    }

    /**
     * 月牙（v1.47 重画）。
     *
     * <p>做法：外圆 A 减掉内圆 B，用「外圆左半弧 + 内圆左半弧」两条弧围成封闭路径。
     * 两圆半径不等、圆心错开，交点角度用余弦定理算出来，形状比原来那条等半径细月牙
     * 饱满得多；老系统没有 {@code Path.op}，EVEN_ODD 又会把内圆右半也画出来，所以只能拼弧。
     *
     * <p>整体再按 -20° 倾斜（经典月牙朝向：肚子在左下、尖角朝右上），右上角点一颗小星。
     */
    private void drawMoon(Canvas canvas, float cx, float cy, float size) {
        float R = size * 0.335f;
        float r = R * 0.95f;
        float d = R * 0.52f;

        double a = (d * d - r * r + R * R) / (2.0 * d);          // 交点到 A 圆心的水平距离
        double h = Math.sqrt(Math.max(0, (double) R * R - a * a)); // 交点高度
        double degA1 = Math.toDegrees(Math.atan2(h, a));          // 下方交点（A 上）
        double degA2 = -degA1;                                    // 上方交点（A 上）
        double degB1 = Math.toDegrees(Math.atan2(-h, a - d));     // 下方交点（B 上）
        double degB2 = -degB1;                                    // 上方交点（B 上）
        float sweepOuter = (float) norm(degA2 - degA1);           // 顺时针经过 180°（左半）
        float sweepInner = (float) -norm(degB1 - degB2);          // 逆时针经过 180°（凹边）

        android.graphics.RectF outer = new android.graphics.RectF(cx - R, cy - R, cx + R, cy + R);
        android.graphics.RectF inner = new android.graphics.RectF(cx + d - r, cy - r, cx + d + r, cy + r);

        paint.setStyle(Paint.Style.FILL);
        canvas.save();
        canvas.rotate(-20f, cx, cy);
        path.reset();
        path.moveTo((float) (cx + a), (float) (cy + h));
        path.arcTo(outer, (float) degA1, sweepOuter, false);
        path.arcTo(inner, (float) degB1, sweepInner, false);
        path.close();
        canvas.drawPath(path, paint);

        // 右上角一颗四角星，跟月牙一起倾斜，当点缀
        float sx = cx + R * 1.02f;
        float sy = cy - R * 0.92f;
        float t = size * 0.075f;
        path.reset();
        path.moveTo(sx, sy - t);
        path.quadTo(sx + t * 0.26f, sy - t * 0.26f, sx + t, sy);
        path.quadTo(sx + t * 0.26f, sy + t * 0.26f, sx, sy + t);
        path.quadTo(sx - t * 0.26f, sy + t * 0.26f, sx - t, sy);
        path.quadTo(sx - t * 0.26f, sy - t * 0.26f, sx, sy - t);
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private static double norm(double deg) {
        double v = deg % 360.0;
        if (v < 0) v += 360.0;
        return v;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

package com.letvcast.player;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 齿轮（设置）图标：和 {@link ThemeIcon} 一样用代码画，
 * 任何分辨率都清楚，颜色跟着主题走，不用塞 PNG。
 *
 * <p>画法：一个圆环 + 8 个梯形齿，中间再挖一个浅色圆点（用 fill + 反色小圆模拟挖空，
 * 老系统没有 Path.op）。
 */
public class GearIcon extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public GearIcon(int color, int holeColor) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        holePaint.setColor(holeColor);
        holePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float rOuter = size * 0.40f;       // 齿顶
        float rBody = size * 0.30f;        // 齿轮本体
        float rHole = size * 0.115f;       // 中心孔

        int teeth = 8;
        float toothHalf = (float) (Math.PI / teeth * 0.42);
        path.reset();
        for (int i = 0; i < teeth; i++) {
            double a = Math.PI * 2 / teeth * i;
            // 齿：从本体半径出发，向外扩一个梯形
            float a1 = (float) (a - toothHalf);
            float a2 = (float) (a + toothHalf);
            float am = (float) a;
            float t1 = (float) (a - toothHalf * 0.45);
            float t2 = (float) (a + toothHalf * 0.45);
            // 齿根两角
            path.lineTo((float) (cx + Math.cos(a1) * rBody), (float) (cy + Math.sin(a1) * rBody));
            // 齿顶两角
            path.lineTo((float) (cx + Math.cos(t1) * rOuter), (float) (cy + Math.sin(t1) * rOuter));
            path.lineTo((float) (cx + Math.cos(t2) * rOuter), (float) (cy + Math.sin(t2) * rOuter));
            // 回齿根
            path.lineTo((float) (cx + Math.cos(a2) * rBody), (float) (cy + Math.sin(a2) * rBody));
            // 齿间圆弧
            double aNext = Math.PI * 2 / teeth * (i + 1);
            float a3 = (float) (aNext - toothHalf);
            path.lineTo((float) (cx + Math.cos(a3) * rBody), (float) (cy + Math.sin(a3) * rBody));
        }
        path.close();
        canvas.drawPath(path, paint);
        // 中心孔：用背景色小圆盖掉，视觉上就是挖空
        canvas.drawCircle(cx, cy, rHole, holePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        holePaint.setAlpha(alpha);
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

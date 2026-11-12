package com.letvcast.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.SeekBar;

/**
 * 自绘进度条（v1.40）。
 *
 * <p>为什么不用 {@code progressDrawable}：layer-list 里的 {@code <clip>} 在
 * Android 5.x 上不按 level 裁剪，进度 0 也会画满整条（实测确认）。电视上是 4.3、
 * 手机上是 5.x~14，一套系统 drawable 覆盖不了，所以这里干脆自己画：
 * 轨道 + 已播进度 + 圆点 + 遥控器焦点框，颜色全部取 {@code @color} 主题令牌，
 * 浅色主题是深色轨道配蓝进度，深色主题是白轨道，任何版本表现都一致。
 */
public class SeekBarLumo extends SeekBar {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float density = 1f;

    public SeekBarLumo(Context context) {
        super(context);
        init();
    }

    public SeekBarLumo(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SeekBarLumo(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        int trackColor;
        int fillColor;
        int ringColor;
        try {
            trackColor = getResources().getColor(R.color.player_track);
            fillColor = getResources().getColor(R.color.player_thumb);
            ringColor = getResources().getColor(R.color.player_btn_focus_stroke);
        } catch (Throwable t) {
            trackColor = 0x33FFFFFF;
            fillColor = 0xFF2563EB;
            ringColor = 0xFFFFFFFF;
        }
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(trackColor);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(fillColor);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2f));
        ringPaint.setColor(ringColor);
        // 系统自带的进度条和滑块都不画：轨道、进度、圆点全由 onDraw 自己来。
        // 注意必须是 null：ProgressBar 只有在进度条 drawable 为 null 时才会在 setProgress 里
        // 主动 invalidate()，否则它只对 drawable 调 setLevel()（ColorDrawable 不认 level），
        // 结果就是画面停在第一帧，进度条永远不动。
        try {
            setThumb(null);
            setThumbOffset(0);
            setProgressDrawable(null);
        } catch (Throwable ignored) {
        }
        setPadding((int) dp(10f), getPaddingTop(), (int) dp(10f), getPaddingBottom());
    }

    private float dp(float v) {
        return v * density;
    }

    /** setProgress 之后强制重绘一次（不依赖 ProgressBar 内部是否 invalidate） */
    @Override
    public synchronized void setProgress(int progress) {
        super.setProgress(progress);
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float left = getPaddingLeft();
        float right = w - getPaddingRight();
        if (right - left < dp(4f)) return;
        float thickness = Math.min(dp(6f), h * 0.5f);
        float cy = h / 2f;
        float r = thickness / 2f;

        rect.set(left, cy - r, right, cy + r);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        int max = getMax();
        float ratio = max <= 0 ? 0f : getProgress() / (float) max;
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;
        float cx = left + (right - left) * ratio;
        if (ratio > 0.001f) {
            float fillRight = Math.max(cx, left + thickness);
            rect.set(left, cy - r, Math.min(fillRight, right), cy + r);
            canvas.drawRoundRect(rect, r, r, fillPaint);
        }
        canvas.drawCircle(cx, cy, dp(7f), dotPaint);
        if (isFocused()) {
            float pad = dp(2f);
            rect.set(left - pad, cy - r - pad, right + pad, cy + r + pad);
            canvas.drawRoundRect(rect, dp(6f), dp(6f), ringPaint);
        }
    }
}

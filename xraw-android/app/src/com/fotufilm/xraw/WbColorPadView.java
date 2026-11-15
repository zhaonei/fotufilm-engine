package com.fotufilm.xraw;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 白平衡 2D 色偏盘（对齐机内）：横轴 = R（−9…+9），纵轴 = B（−9…+9）。
 *
 * 交互：拖动时按整数格吸附，每格触发一次 {@link OnShift}；双击/长按中心复位。
 * 绘制全用 GPU（Canvas 基本图元 + 硬件加速），拖动期间不分配对象。
 */
public final class WbColorPadView extends View {

    public interface OnShift {
        /** @param r −9…+9  @param b −9…+9  @param committed 是否已松手 */
        void onShift(int r, int b, boolean committed);
    }

    private static final int RANGE = 9;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint();
    private final Paint axis = new Paint();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int r = 0, b = 0;
    private OnShift listener;
    private final PointF cur = new PointF();
    private float pad;          // 内边距（给坐标轴标签留位置）
    private float step;         // 每格像素

    public WbColorPadView(Context c) {
        super(c);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        bg.setColor(0xFF151515);
        dot.setColor(0xFF333333);
        axis.setColor(0xFF3A3A3A);
        axis.setStrokeWidth(1f);
        ring.setColor(0xFFE8B44A);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        ringFill.setColor(0x2EE8B44A);
        label.setColor(0xFF6F6F6F);
        label.setTextSize(Ui.dp(c, 9));
        pad = Ui.dp(c, 18);
    }

    public void setListener(OnShift l) { this.listener = l; }

    public void setValue(int r, int b) {
        this.r = clamp(r);
        this.b = clamp(b);
        invalidate();
    }

    public int getR() { return r; }
    public int getB() { return b; }

    private static int clamp(int v) { return v < -RANGE ? -RANGE : (v > RANGE ? RANGE : v); }

    @Override protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        if (size <= 0) size = Ui.dp(getContext(), 206);
        setMeasuredDimension(size, size);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float left = pad, top = pad, right = w - pad, bottom = h - pad;
        step = (right - left) / (2f * RANGE);

        c.drawColor(0xFF151515);
        // 点阵
        for (int i = 0; i <= 2 * RANGE; i++) {
            for (int j = 0; j <= 2 * RANGE; j++) {
                c.drawCircle(left + i * step, top + j * step, Math.max(1f, Ui.dp(getContext(), 0.7f)), dot);
            }
        }
        // 中心十字
        float cx = left + RANGE * step, cy = top + RANGE * step;
        c.drawLine(left, cy, right, cy, axis);
        c.drawLine(cx, top, cx, bottom, axis);

        // 光标
        float px = cx + r * step, py = cy - b * step;
        float rad = Ui.dp(getContext(), 8);
        c.drawCircle(px, py, rad, ringFill);
        c.drawCircle(px, py, rad, ring);

        // 轴标签
        c.drawText("R−", left, bottom + Ui.dp(getContext(), 13), label);
        String rp = "R+";
        c.drawText(rp, right - label.measureText(rp), bottom + Ui.dp(getContext(), 13), label);
        c.drawText("B+", cx - label.measureText("B+") / 2, top - Ui.dp(getContext(), 4), label);
        String bm = "B−";
        c.drawText(bm, cx - label.measureText(bm) / 2, bottom + Ui.dp(getContext(), 13), label);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float left = pad, top = pad;
        float stepNow = (getWidth() - 2 * pad) / (2f * RANGE);
        float cx = left + RANGE * stepNow, cy = top + RANGE * stepNow;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                int nr = clamp(Math.round((e.getX() - cx) / stepNow));
                int nb = clamp(Math.round((cy - e.getY()) / stepNow));
                if (nr != r || nb != b) {
                    r = nr; b = nb;
                    invalidate();
                    if (listener != null) listener.onShift(r, b, false);
                    performHaptic();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (listener != null) listener.onShift(r, b, true);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void performHaptic() {
        try { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) { }
    }
}

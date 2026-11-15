package com.fotufilm.xraw;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * 支持双指缩放 / 双击 fit↔100% / 单项平移 / 长按对比的 ImageView。
 *
 * 全部变换走 {@link Matrix}（GPU 合成），缩放与平移期间不解码、不重建 Bitmap，
 * 所以拖动跟手。长按回调用于「临时看原图」。
 */
public final class ZoomImageView extends ImageView {

    public interface OnCompareHold { void onHold(boolean holding); }

    private final Matrix baseMatrix = new Matrix();
    private final Matrix userMatrix = new Matrix();
    private final float[] m = new float[9];

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OnCompareHold compareHold;

    private float minScale = 1f, maxScale = 8f;
    private float curScale = 1f;
    private float lastX, lastY;
    private boolean fitMode = true;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                applyScale(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (fitMode) zoomTo(2.5f, e.getX(), e.getY()); else resetFit();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (compareHold != null) compareHold.onHold(true);
            }
        });
        gestureDetector.setIsLongpressEnabled(true);
    }

    public void setOnCompareHold(OnCompareHold l) { this.compareHold = l; }

    @Override public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        resetFit();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        resetFit();
    }

    /** 复位为「适应屏幕」 */
    public void resetFit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0) return;
        float bw = d.getIntrinsicWidth(), bh = d.getIntrinsicHeight();
        if (bw <= 0 || bh <= 0) return;
        float s = Math.min(getWidth() / bw, getHeight() / bh);
        baseMatrix.reset();
        baseMatrix.postScale(s, s);
        baseMatrix.postTranslate((getWidth() - bw * s) / 2f, (getHeight() - bh * s) / 2f);
        userMatrix.reset();
        curScale = 1f;
        minScale = 1f;
        maxScale = 8f;
        fitMode = true;
        setImageMatrix(baseMatrix);
    }

    private void applyScale(float factor, float fx, float fy) {
        float target = clamp(curScale * factor);
        float real = target / curScale;
        if (real == 1f) return;
        userMatrix.postScale(real, real, fx, fy);
        curScale = target;
        fitMode = Math.abs(curScale - 1f) < 0.01f;
        push();
    }

    private void zoomTo(float scale, float fx, float fy) {
        float real = clamp(scale) / curScale;
        userMatrix.postScale(real, real, fx, fy);
        curScale = clamp(scale);
        fitMode = false;
        push();
    }

    private float clamp(float s) { return Math.max(minScale, Math.min(maxScale, s)); }

    private void push() {
        Matrix out = new Matrix(baseMatrix);
        out.postConcat(userMatrix);
        setImageMatrix(out);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && curScale > 1.01f) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    userMatrix.postTranslate(dx, dy);
                    lastX = e.getX(); lastY = e.getY();
                    push();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (compareHold != null) compareHold.onHold(false);
                break;
        }
        return true;
    }
}

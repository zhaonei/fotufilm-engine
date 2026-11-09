package com.fotufilm.xraw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 直方图（RGB + 亮度）。
 *
 * 与 X RAW Studio 的直方图一致：暗部在左、亮部在右；亮度为填充区域，R/G/B 为细线。
 * 纵轴用 log 压缩，否则单个尖峰会把其余通道压成一条线（阴影/黑场常见）。
 */
public final class HistogramView extends View {

    private static final int BINS = 256;
    private final int[] hr = new int[BINS];
    private final int[] hg = new int[BINS];
    private final int[] hb = new int[BINS];
    private final int[] hl = new int[BINS];
    private double maxLog = 1;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmpRect = new RectF();

    public HistogramView(Context c) {
        super(c);
        bgPaint.setColor(0xCC0C0C0D);
        bgPaint.setStyle(Paint.Style.FILL);
        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(1f);
        fillPaint.setStyle(Paint.Style.FILL);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.2f);
        setWillNotDraw(false);
    }

    /** 在主线程之外调用都可以：只做字段写入 + invalidate */
    public void setBitmap(Bitmap bm) {
        java.util.Arrays.fill(hr, 0);
        java.util.Arrays.fill(hg, 0);
        java.util.Arrays.fill(hb, 0);
        java.util.Arrays.fill(hl, 0);
        if (bm == null) { maxLog = 1; invalidate(); return; }

        int w = bm.getWidth(), h = bm.getHeight();
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 160_000.0));
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = bm.getPixel(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                hr[r]++; hg[g]++; hb[b]++;
                hl[Math.min(255, (r * 299 + g * 587 + b * 114) / 1000)]++;
            }
        }
        double max = 1;
        for (int i = 0; i < BINS; i++) {
            max = Math.max(max, Math.max(hr[i], Math.max(hg[i], Math.max(hb[i], hl[i]))));
        }
        maxLog = Math.log(1 + max);
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        tmpRect.set(0, 0, w, h);
        cv.drawRoundRect(tmpRect, 6, 6, bgPaint);

        // 网格：4 等分
        for (int i = 1; i < 4; i++) {
            float x = w * i / 4f;
            cv.drawLine(x, 4, x, h - 4, gridPaint);
        }

        // 亮度填充
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i < BINS; i++) {
            float x = w * i / (BINS - 1f);
            float y = h - (float) (Math.log(1 + hl[i]) / maxLog * (h - 6));
            path.lineTo(x, y);
        }
        path.lineTo(w, h);
        path.close();
        fillPaint.setShader(new LinearGradient(0, 0, 0, h, 0x66FFFFFF, 0x11FFFFFF, Shader.TileMode.CLAMP));
        cv.drawPath(path, fillPaint);
        fillPaint.setShader(null);

        // RGB 细线
        drawChannel(cv, w, h, hr, 0xFFFF5A5A);
        drawChannel(cv, w, h, hg, 0xFF5AFF8C);
        drawChannel(cv, w, h, hb, 0xFF5AA8FF);
    }

    private void drawChannel(Canvas cv, int w, int h, int[] hist, int color) {
        path.reset();
        for (int i = 0; i < BINS; i++) {
            float x = w * i / (BINS - 1f);
            float y = h - (float) (Math.log(1 + hist[i]) / maxLog * (h - 6));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        linePaint.setColor(color);
        linePaint.setAlpha(200);
        cv.drawPath(path, linePaint);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(Ui.dp(getContext(), 148), wSpec);
        int h = resolveSize(Ui.dp(getContext(), 86), hSpec);
        setMeasuredDimension(w, h);
    }
}

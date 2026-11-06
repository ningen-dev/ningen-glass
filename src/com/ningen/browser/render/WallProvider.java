package com.ningen.browser.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

/**
 * 壁纸快照中心。玻璃面板不重抓整棵 View 树，而是按自身在窗口中的位置，
 * 从同一张底图上采样对应区域；这样每块玻璃都是"同一片背景"的局部，
 * 视差与折射天然一致，也不用担心多层嵌套递归抓取。
 */
public final class WallProvider {

    private static final String TAG = "WallProvider";

    private static Bitmap raw;
    private static int winW = 0;
    private static int winH = 0;
    private static float coverScale = 1f;
    private static float drawLeft = 0f;
    private static float drawTop = 0f;

    private WallProvider() {}

    /**
     * 注入底图。宿主在启动时提供任意一张位图作为玻璃的采样源。
     * 本库不内置素材，也不读取 assets，壁纸完全由调用方决定。
     */
    public static void set(Bitmap b) {
        raw = b;
    }

    public static boolean ready() {
        return raw != null;
    }

    public static Bitmap raw() {
        return raw;
    }

    public static int width() {
        return winW;
    }

    public static int height() {
        return winH;
    }

    public static void setWindow(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == winW && h == winH) return;
        winW = w;
        winH = h;
        if (raw == null) return;
        float sx = w / (float) raw.getWidth();
        float sy = h / (float) raw.getHeight();
        coverScale = Math.max(sx, sy);
        float dw = raw.getWidth() * coverScale;
        float dh = raw.getHeight() * coverScale;
        drawLeft = (w - dw) * 0.5f;
        // 日出地平线压在偏上位置，给下方留出内容空间
        drawTop = (h - dh) * 0.38f;
    }

    private static volatile Bitmap live;
    private static volatile int liveGen;

    /** 视频背景推进一帧活帧（整窗尺寸缩略图），玻璃折射以它为准。 */
    public static void setLiveFrame(Bitmap b) {
        if (b == null || b.isRecycled()) return;
        live = b;
        liveGen++;
    }

    public static void clearLive() {
        live = null;
        liveGen++;
    }

    public static boolean liveActive() {
        return live != null && !live.isRecycled();
    }

    public static int liveGen() {
        return liveGen;
    }

    /** 把活帧按窗口坐标铺满画到 canvas 上（canvas 需已在窗口坐标系）。 */
    public static void drawLiveTo(Canvas c, Paint p) {
        Bitmap b = live;
        if (b == null || b.isRecycled() || winW <= 0) return;
        Rect src = new Rect(0, 0, b.getWidth(), b.getHeight());
        RectF dst = new RectF(0, 0, winW, winH);
        c.drawBitmap(b, src, dst, p);
    }

    /**
     * 采样窗口内某块矩形对应的背景，输出降采样位图。
     *
     * @param rect  面板在窗口中的位置
     * @param down  降采样系数
     * @param blurR 模糊半径
     */
    public static Bitmap sample(RectF rect, int down, int blurR, boolean wantBlur) {
        if (raw == null || winW == 0) return null;
        down = Math.max(1, down);
        int w = Math.max(1, Math.round(rect.width() / down));
        int h = Math.max(1, Math.round(rect.height() / down));
        if (w > 512 || h > 1024) return null;
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.scale(1f / down, 1f / down);
            c.translate(-rect.left, -rect.top);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setFilterBitmap(true);
            Rect src = new Rect(0, 0, raw.getWidth(), raw.getHeight());
            RectF dst = new RectF(drawLeft, drawTop,
                    drawLeft + raw.getWidth() * coverScale,
                    drawTop + raw.getHeight() * coverScale);
            c.drawBitmap(raw, src, dst, p);
            // 有视频活帧时盖在静态壁纸之上：玻璃折射到的是当前帧，不再是死图
            drawLiveTo(c, p);
            if (wantBlur && blurR > 0) {
                return Blur.box(out, Math.max(1, blurR / down), 2);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取整屏底色主调，用于渐变延展与开屏余晖。 */
    public static int averageColor() {
        if (raw == null) return 0xFF0A1416;
        try {
            Bitmap small = Blur.downscale(raw, Math.max(1, raw.getWidth() / 8));
            long r = 0, g = 0, b = 0;
            int w = small.getWidth(), h = small.getHeight();
            int[] pix = new int[w * h];
            small.getPixels(pix, 0, w, 0, 0, w, h);
            for (int px : pix) {
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
            }
            int n = w * h;
            return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        } catch (Throwable t) {
            return 0xFF0A1416;
        }
    }
}

package com.ningen.browser.render;

import android.graphics.Bitmap;

/** 分离式盒式模糊。低版本管线的磨砂底全部由它生成，成本按降采样后像素计。 */
public final class Blur {

    private Blur() {}

    public static Bitmap box(Bitmap src, int radius, int passes) {
        if (src == null || radius < 1) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 2 || h < 2) return src;

        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);

        int[] tmp = new int[w * h];
        for (int p = 0; p < passes; p++) {
            blurH(pix, tmp, w, h, radius);
            blurV(tmp, pix, w, h, radius);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        return out;
    }

    /** 降低分辨率后再模糊，用于快速获得大半径磨砂。 */
    public static Bitmap downscale(Bitmap src, int factor) {
        if (src == null || factor <= 1) return src;
        int w = Math.max(1, src.getWidth() / factor);
        int h = Math.max(1, src.getHeight() / factor);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(out).drawBitmap(
                src,
                new android.graphics.Rect(0, 0, src.getWidth(), src.getHeight()),
                new android.graphics.Rect(0, 0, w, h),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static void blurH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int px = in[row + clamp(i, w)];
                a += px >>> 24;
                rr += (px >> 16) & 0xFF;
                gg += (px >> 8) & 0xFF;
                bb += px & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((int) (a / div) << 24) | ((int) (rr / div) << 16)
                        | ((int) (gg / div) << 8) | (int) (bb / div);
                int add = in[row + clamp(x + r + 1, w)];
                int sub = in[row + clamp(x - r, w)];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void blurV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            long a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int px = in[clamp(i, h) * w + x];
                a += px >>> 24;
                rr += (px >> 16) & 0xFF;
                gg += (px >> 8) & 0xFF;
                bb += px & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((int) (a / div) << 24) | ((int) (rr / div) << 16)
                        | ((int) (gg / div) << 8) | (int) (bb / div);
                int add = in[clamp(y + r + 1, h) * w + x];
                int sub = in[clamp(y - r, h) * w + x];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }
}

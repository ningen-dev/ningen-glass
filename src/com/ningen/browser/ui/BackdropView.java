package com.ningen.browser.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import com.ningen.browser.core.Ease;
import com.ningen.browser.render.Blur;
import com.ningen.browser.render.Theme;
import com.ningen.browser.render.WallProvider;

/**
 * 全局背景：一张日出田野铺满整屏。
 * 上层是原图（宽度铺满、压在上三分之一），下层是重模糊后的同图延展到全屏，
 * 这样竖屏也不会因为原图过宽而只剩中间一条，同时保持同一片色。
 */
public class BackdropView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();

    private Bitmap sharp;
    private Bitmap soft;
    private int softW, softH;
    private float parallax;
    private float targetParallax;
    private long introAt;
    private boolean intro;
    private boolean video;

    public BackdropView(Context c) {
        super(c);
        introAt = SystemClock.uptimeMillis();
        intro = true;
        video = com.ningen.browser.render.VideoWall.on();
    }

    /**
     * 视频背景模式：本视图不再铺静态壁纸，改成只画上层的光影，
     * 让下面的 VideoWall 透出来，同时保留原有的压暗与渐隐衔接。
     */
    public void setVideoBackdrop(boolean v) {
        if (this.video != v) {
            this.video = v;
            invalidate();
        }
    }

    public void setParallax(float p) {
        this.targetParallax = Math.max(-1f, Math.min(1f, p));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        build(w, h);
    }

    private void build(int w, int h) {
        WallProvider.setWindow(w, h);
        sharp = null;
        soft = null;
        if (video || !WallProvider.ready()) return;
        try {
            Bitmap raw = WallProvider.raw();
            // 全屏软底：大幅降采样 + 模糊，避免放大后出现噪点
            Bitmap small = Blur.downscale(raw, 8);
            Bitmap blurred = Blur.box(small, 6, 2);
            soft = Bitmap.createScaledBitmap(blurred, Math.max(1, w / 6), Math.max(1, h / 6), true);
            softW = w;
            softH = h;

            // 顶部主视觉：宽度铺满
            float scale = w / (float) raw.getWidth();
            int dw = w;
            int dh = (int) (raw.getHeight() * scale);
            sharp = Bitmap.createScaledBitmap(raw, dw, Math.max(1, dh), true);
        } catch (Throwable ignored) {
            sharp = null;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0) return;

        if (!video) canvas.drawColor(Theme.INK);

        // 视差平滑
        if (Math.abs(parallax - targetParallax) > 0.001f) {
            parallax += (targetParallax - parallax) * 0.12f;
            postInvalidateOnAnimation();
        }

        // 入场：整体轻微回落
        float introP = 1f;
        if (intro) {
            float t = (SystemClock.uptimeMillis() - introAt) / 900f;
            introP = 0.92f + 0.08f * Ease.out(Ease.clamp01(t));
            if (t < 1f) postInvalidateOnAnimation();
            else intro = false;
        }

        float px = video ? 0f : parallax * Theme.dp(getContext(), 14);
        int save = canvas.save();
        float z = video ? 1f : (1f + (1f - introP) * 0.6f) * introP;
        canvas.translate(w / 2f, h * 0.30f);
        canvas.scale(z, z);
        canvas.translate(-w / 2f + px, -h * 0.30f);

        if (!video) {

        if (soft != null) {
            matrix.reset();
            matrix.setScale(w / (float) soft.getWidth(), h / (float) soft.getHeight());
            paint.setShader(null);
            paint.setAlpha(190);
            canvas.drawBitmap(soft, matrix, paint);
            paint.setAlpha(255);
        }

        if (sharp != null) {
            float top = -Theme.dp(getContext(), 8);
            paint.setAlpha(255);
            canvas.drawBitmap(sharp, px * 0.35f, top, paint);

            // 下半部渐隐到墨色，衔接内容区
            paint.setShader(new LinearGradient(0, top + sharp.getHeight() * 0.34f, 0,
                    top + sharp.getHeight() * 1.55f,
                    new int[]{0x0004090B, 0xB304090B, Theme.INK},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(-w, top, w * 2f, h * 1.2f, paint);
            paint.setShader(null);
        }
        } else {
            // 视频背景：先整体压一层薄暗，保证白色文字可读
            paint.setShader(null);
            paint.setAlpha(255);
            paint.setColor(0x2E04090B);
            canvas.drawRect(-w, -h, w * 2f, h * 2f, paint);
            // 画面中上部保留，底部渐隐到纸白，衔接浅色内容区
            paint.setShader(new LinearGradient(0, h * 0.26f, 0, h * 0.96f,
                    new int[]{0x00000000, 0x59F1F5F4, 0xFFF1F5F4},
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(-w, 0, w * 2f, h * 1.2f, paint);
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
        }

        // 顶部压暗，保证状态栏与标题可读
        paint.setShader(new LinearGradient(0, 0, 0, h * 0.28f,
                video ? 0xC604090B : 0x9904090B, 0x0004090B, Shader.TileMode.CLAMP));
        canvas.drawRect(-w, -h, w * 2f, h * 0.28f, paint);
        paint.setShader(null);

        // 青色环境光
        paint.setShader(new RadialGradient(w * 0.5f, h * 0.14f, w * 0.9f,
                new int[]{0x1A2EE6D6, 0x00000000}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(-w, -h, w * 2f, h * 2f, paint);
        paint.setShader(null);

        canvas.restoreToCount(save);
    }
}

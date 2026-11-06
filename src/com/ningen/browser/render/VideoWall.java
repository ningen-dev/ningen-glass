package com.ningen.browser.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 动态背景：由宿主指定的视频源铺满整屏、静音循环播放。
 *
 * 全屏适配采用 center-crop（等比放大到铺满整屏，溢出部分居中裁切），
 * 视频原始比例 9:16，任何屏幕比例都不会出现黑边，也不会被拉伸变形。
 * 本视图始终垫在内容最底层，上层由 BackdropView 负责压暗与渐隐衔接。
 */
public class VideoWall extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "VideoWall";
    /** 视频源，本地绝对路径或 http(s) 地址，由宿主注入；为空时不启用视频墙。 */
    private static String sourcePath = null;

    /**
     * 指定视频背景来源。宿主自行准备 mp4（放在 assets 里的需先复制到缓存目录再传路径），
     * 本库不内置任何素材。
     */
    public static void setSource(String path) {
        sourcePath = path;
    }

    /** 是否启用视频背景。 */
    private static boolean enabled = true;
    /** 跨 Activity 续播位置，避免每次切页都从头开始。 */
    private static long resumeAt = 0L;

    public static boolean on() {
        return enabled;
    }

    public static void useVideo(boolean v) {
        enabled = v;
    }

    private Bitmap grabBuf;
    private long lastGrab;
    private static final long GRAB_MS = 380L;
    private static final int LIVE_DIV = 6;
    private MediaPlayer mp;
    private Surface surf;
    private boolean prepared;
    private int videoW, videoH;
    private final Matrix tm = new Matrix();

    public VideoWall(Context c) {
        super(c);
        setOpaque(true);
        setAlpha(0f);
        setSurfaceTextureListener(this);
    }

    /** 挂到内容根布局的最底层（index 0）。 */
    public static VideoWall addTo(FrameLayout root) {
        if (!enabled) return null;
        VideoWall v = new VideoWall(root.getContext());
        root.addView(v, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    // ------------------------------------------------------------------ 播放控制

    public void play() {
        if (mp != null && prepared) {
            try {
                if (!mp.isPlaying()) mp.start();
            } catch (Throwable t) {
                Log.w(TAG, "start " + t);
            }
            return;
        }
        SurfaceTexture st = getSurfaceTexture();
        if (st != null && isAvailable()) open(st);
    }

    public void stop() {
        if (mp != null && prepared) {
            try {
                mp.pause();
                resumeAt = mp.getCurrentPosition();
            } catch (Throwable ignored) {
            }
        }
    }

    public void release() {
        close();
    }

    private void close() {
        prepared = false;
        WallProvider.clearLive();
        if (mp != null) {
            try {
                mp.reset();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
        if (surf != null) {
            try {
                surf.release();
            } catch (Throwable ignored) {
            }
            surf = null;
        }
        setAlpha(0f);
    }

    private void open(SurfaceTexture st) {
        close();
        if (sourcePath == null) {
            Log.w(TAG, "video source not set");
            return;
        }
        try {
            MediaPlayer p = new MediaPlayer();
            p.setDataSource(sourcePath);
            surf = new Surface(st);
            p.setSurface(surf);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    videoW = w;
                    videoH = h;
                    fit();
                }
            });
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    if (videoW == 0 || videoH == 0) {
                        videoW = m.getVideoWidth();
                        videoH = m.getVideoHeight();
                    }
                    fit();
                    try {
                        if (resumeAt > 0 && resumeAt < m.getDuration() - 200) {
                            m.seekTo((int) resumeAt);
                        }
                        m.start();
                    } catch (Throwable t) {
                        Log.w(TAG, "play " + t);
                    }
                    reveal();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "err " + what + "/" + extra);
                    close();
                    return true;
                }
            });
            mp = p;
            p.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "open " + t);
            close();
        }
    }

    /** 首帧出来后再淡入，避免切页时闪一下。 */
    private void reveal() {
        setAlpha(0f);
        animate().alpha(1f).setDuration(450).setStartDelay(40).start();
    }

    // ------------------------------------------------------------------ 全屏适配

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    /** center-crop：等比缩放到铺满整屏，溢出部分居中裁切。 */
    private void fit() {
        if (videoW <= 0 || videoH <= 0) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.max(w / (float) videoW, h / (float) videoH);
        float sx = videoW * s / w;
        float sy = videoH * s / h;
        tm.reset();
        tm.setScale(sx, sy, w * 0.5f, h * 0.5f);
        setTransform(tm);
    }

    // ------------------------------------------------------------------ 生命周期回调

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        open(st);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        fit();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        close();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        long now = SystemClock.uptimeMillis();
        if (now - lastGrab < GRAB_MS) return;
        lastGrab = now;
        grabLive();
    }

    /** 把当前视频画面抓成小图交给 WallProvider，玻璃便折射到活帧 */
    private void grabLive() {
        if (prepared == false || isAvailable() == false) return;
        if (getAlpha() < 0.02f) return;
        int w = getWidth();
        int h = getHeight();
        if (w < 1 || h < 1) return;
        try {
            if (grabBuf == null || grabBuf.isRecycled()
                    || grabBuf.getWidth() != w || grabBuf.getHeight() != h) {
                grabBuf = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            if (getBitmap(grabBuf) == null) return;
            int dw = Math.max(1, w / LIVE_DIV);
            int dh = Math.max(1, h / LIVE_DIV);
            Bitmap small = Bitmap.createScaledBitmap(grabBuf, dw, dh, true);
            WallProvider.setLiveFrame(small);
        } catch (Throwable t) {
            Log.w(TAG, "grab " + t);
        }
    }
}

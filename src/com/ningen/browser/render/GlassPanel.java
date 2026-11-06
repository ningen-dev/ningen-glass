package com.ningen.browser.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** 通用液态玻璃容器：自身负责背景折射，子 View 叠在上层。 */
public class GlassPanel extends FrameLayout {

    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];

    protected float corner = 24f;
    protected float frost = 0.5f;
    protected float glow = 0.5f;
    protected boolean elevated = true;
    protected boolean interactive = false;

    private Bitmap snap;
    private int snapX, snapY, snapW, snapH;
    private long lastSnap;
    private float pressT;
    private boolean pressed;

    /** 融动：0 = 收成一滴水，1 = 摊平成一片。半径与形变都靠它插值。 */
    private float morph = 1f;
    private boolean autoMorph = true;
    private boolean morphDone;

    public GlassPanel(Context c) {
        super(c);
        setWillNotDraw(false);
        setClipChildren(true);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Theme.GLASS_SHADOW);
        setLayerType(LAYER_TYPE_NONE, null);
    }

    /** 关掉自动融动（例如本身就在做位移动画的元素）。 */
    public GlassPanel noMorph() {
        autoMorph = false;
        morphDone = true;
        morph = 1f;
        return this;
    }

    /** 当前应画的圆角：SDF 半径随 morph 插值，收拢时更"圆胖"。 */
    private float radiusNow() {
        return corner * (0.30f + 0.70f * morph);
    }

    /** 从一滴水摊开成整片玻璃，带一点点过冲回弹。 */
    public void morphIn() {
        morphDone = true;
        morph = 0f;
        setPivotX(getWidth() * 0.5f);
        setPivotY(getHeight() * 0.5f);
        final long start = System.currentTimeMillis();
        final long dur = 430L;
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                float t = Math.min(1f, (System.currentTimeMillis() - start) / (float) dur);
                float e = com.ningen.browser.core.Ease.out(t);
                // 中段略微过冲（>1），落定时回到 1，像水铺开后轻轻一顿
                float over = 1f + 0.055f * (float) Math.sin(Math.PI * t) * (1f - t * 0.4f);
                morph = e * over;
                // 轻微拉伸：起手横向收一点，铺开时再回正
                float sx = 0.972f + 0.028f * morph;
                float sy = 1.018f - 0.018f * morph;
                setScaleX(sx);
                setScaleY(sy);
                invalidate();
                if (t < 1f) {
                    postOnAnimation(this);
                } else {
                    morph = 1f;
                    setScaleX(1f);
                    setScaleY(1f);
                }
            }
        });
    }

    private void maybeMorph() {
        if (!autoMorph || morphDone) return;
        if (corner < Theme.dp(getContext(), 14)) return;
        if (getWidth() <= 0) return;
        morphIn();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Tilt.get().watch(this);
        if (getVisibility() == VISIBLE) maybeMorph();
    }

    @Override
    protected void onDetachedFromWindow() {
        Tilt.get().unwatch(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityChanged(android.view.View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) maybeMorph();
    }

    public GlassPanel corner(float dp) {
        this.corner = Theme.dp(getContext(), dp);
        invalidate();
        return this;
    }

    public GlassPanel frost(float f) {
        this.frost = f;
        invalidate();
        return this;
    }

    public GlassPanel glow(float g) {
        this.glow = g;
        return this;
    }

    public GlassPanel interactive() {
        this.interactive = true;
        return this;
    }

    public GlassPanel flat() {
        this.elevated = false;
        return this;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        invalidateSnap();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) invalidateSnap();
    }

    public void invalidateSnap() {
        snap = null;
        invalidate();
    }

    private boolean needSnap() {
        if (getWidth() <= 0 || getHeight() <= 0) return false;
        if (snap != null && snapX == getLeft() && snapY == getTop()
                && snapW == getWidth() && snapH == getHeight()) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastSnap < 60) return snap != null;
        lastSnap = now;

        getLocationInWindow(loc);
        rect.set(loc[0], loc[1], loc[0] + getWidth(), loc[1] + getHeight());
        Bitmap s = WallProvider.sample(rect, 4, 26, true);
        if (s != null) {
            snap = s;
            snapX = getLeft();
            snapY = getTop();
            snapW = getWidth();
            snapH = getHeight();
        }
        return snap != null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int pad = (int) Theme.dp(getContext(), 3);
        rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
        float r = radiusNow();

        // 投影：用下层偏移的圆角矩形模拟高度
        if (elevated) {
            shadowPaint.setColor(0x4D000000);
            RectF sh = new RectF(rect);
            sh.offset(0, Theme.dp(getContext(), 6));
            canvas.drawRoundRect(sh, r, r, shadowPaint);
        }

        needSnap();
        float pressDip = pressed ? 0.6f : 1f;
        GlassSurface.draw(canvas, rect, r, snap, frost, glow * pressDip);

        // 按压时内部轻亮一层
        if (pressT > 0.01f) {
            shadowPaint.setColor(((int) (0x1A * pressT) << 24) | 0xFFFFFF);
            canvas.drawRoundRect(rect, r, r, shadowPaint);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        float r = radiusNow();
        if (r > 0f) {
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        super.dispatchDraw(canvas);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (interactive) handlePress(ev);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (interactive) handlePress(ev);
        return super.onTouchEvent(ev);
    }

    private void handlePress(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                animatePress(1f);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                animatePress(0f);
                break;
            default:
                break;
        }
    }

    private void animatePress(float to) {
        final float from = pressT;
        final long start = System.currentTimeMillis();
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                float t = Math.min(1f, (System.currentTimeMillis() - start) / 160f);
                float e = com.ningen.browser.core.Ease.out(t);
                pressT = from + (to - from) * e;
                invalidate();
                if (t < 1f) postOnAnimation(this);
            }
        });
    }

    /** 供列表项等场景复用的圆角裁剪。 */
    public static void clipRound(Canvas c, ViewGroup v, float r) {
        Path p = new Path();
        p.addRoundRect(new RectF(0, 0, v.getWidth(), v.getHeight()), r, r, Path.Direction.CW);
        c.clipPath(p);
    }

    public static Rect rectOf(android.view.View v) {
        int[] l = new int[2];
        v.getLocationInWindow(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }
}

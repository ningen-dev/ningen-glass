package com.ningen.browser.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * 自研液态玻璃底部导航栏 · v2
 *
 * v1 已有：自研 AGSL 折射/色散/边缘高光、拖拽拉伸、惯性吸附、触摸光斑、震感。
 * v2 追加五种「功效」：
 *   1) 液滴牵引  —— 拖拽时胶囊身后拖出一颗落后的液球，用平滑并集 shader 拉出液桥，
 *                   松手时液桥回缩合并，像一泡水被拉开又吸回去；
 *   2) 重力感光  —— 读重力传感器，高光方向随手机倾斜实时偏转（真·液态玻璃行为）；
 *   3) 滚动收拢  —— 页面下滑时栏体收窄压低，上滑时舒展开；
 *   4) 按压回弹  —— 触摸瞬间整体轻微下压，用低阻尼弹簧回弹；
 *   5) 点击涟漪  —— 每次切换标签，图标处散出一圈迅速衰减的水纹。
 *
 * 仍不依赖任何第三方库；API 33+ 走 GPU 着色器，以下走降级路径（以上五种在降级路径上
 * 也有对应实现，只是没有真实折射）。
 */
public class LiquidNavBar extends View {

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private static final int TAB_COUNT = 5;

    // ---- 尺寸（dp） ----
    private final float DP_BAR_H = 74f;
    private final float DP_BAR_SIDE = 16f;
    private final float DP_BAR_TOP = 8f;
    private final float DP_CORNER = 27f;
    private final float DP_BAR_PAD = 10f;

    private final float DP_PILL_H = 62f;
    private final float DP_PILL_W = 64f;
    private final float DP_PILL_CORNER = 24f;

    private final float DP_ICON = 23f;

    // 收拢后的额外内缩/压缩量
    private final float DP_COLLAPSE_SIDE = 13f;
    private final float DP_COLLAPSE_H = 9f;
    private final float DP_COLLAPSE_TOP = 3f;

    // ---- 布局结果（px，View 坐标） ----
    private float mBarLeft, mBarTop, mBarRight, mBarBottom;
    private float mPillBaseW, mPillBaseH, mPillCorner;
    private float mIconSize;
    private final float[] mTabCenter = new float[TAB_COUNT];
    private float mSlotW;

    // ---- 弹簧 ----
    private final Spring mLeft = new Spring(46f, 1.0f);
    private final Spring mRight = new Spring(44f, 1.0f);
    private final Spring mGlow = new Spring(22f, 1.0f);
    private final Spring mPress = new Spring(52f, 0.92f);   // 按压回弹
    private final Spring mGhostX = new Spring(34f, 1.0f);   // 液滴牵引
    private final Spring mGhostR = new Spring(34f, 1.0f);
    private final Spring mCollapse = new Spring(42f, 1.0f); // 收拢（滚动 / 拖拽让位）
    private final Spring mBulge = new Spring(30f, 0.96f);   // 拖拽胀大
    private float mScrollCollapse;

    private float mDragX;
    private float mVel;
    private boolean mDragging;
    private boolean mPressed;
    private int mSelected = 0;

    // ---- 重力感光 ----
    private SensorManager mSensorMgr;
    private Sensor mGravity;
    private float mLightAngle = -1.5708f;   // 当前光照角（弧度，-π/2 = 正上方）
    private float mLightTarget = -1.5708f;
    private boolean mSensorOn;

    // ---- 点击涟漪 ----
    private static final int MAX_RIPPLES = 4;
    private final float[] mRipX = new float[MAX_RIPPLES];
    private final float[] mRipY = new float[MAX_RIPPLES];
    private final float[] mRipT = new float[MAX_RIPPLES];
    private int mRipHead;

    // ---- 背景捕获 ----
    private View mHost;
    private Bitmap mCapBmp;
    private Bitmap mFrostBmp;   // 降级路径用的磨砂底图（CPU 盒式模糊，小图代价极低）
    private final Path mPillPath = new Path();
    private final Path mBridgePath = new Path();
    private final Path mGhostPath = new Path();
    private final Rect mPillSrc = new Rect();
    private final RectF mPillDst = new RectF();
    private Canvas mCapCanvas;
    private BitmapShader mCapShader;
    private boolean mNeedCapture = true;
    private int mCapGen = -1;
    private final Rect mCapRect = new Rect();
    private final float CAP_SCALE = 0.25f;

    // ---- 着色器 ----
    private Object mShader;
    private boolean mShaderTried;
    private boolean mShaderOk;

    // ---- 画笔 ----
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRipplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBarPath = new Path();
    private final Path[] mIconPaths = new Path[TAB_COUNT];
    private final RectF mTmpRect = new RectF();
    private final Matrix mTmpMtx = new Matrix();

    private OnTabSelectedListener mListener;

    private long mLastNanos;
    private boolean mTicking;

    private final Choreographer.FrameCallback mTicker = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            float dt;
            if (mLastNanos == 0L) {
                dt = 1f / 60f;
            } else {
                dt = (frameTimeNanos - mLastNanos) / 1_000_000_000f;
            }
            mLastNanos = frameTimeNanos;
            if (dt > 0.034f) dt = 0.034f;
            boolean alive = step(dt);
            invalidate();
            if (alive) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                mLastNanos = 0L;
                mTicking = false;
            }
        }
    };

    public LiquidNavBar(Context context) {
        super(context);
        setWillNotDraw(false);
        mIconPaths[0] = buildHome();
        mIconPaths[1] = buildCompass();
        mIconPaths[2] = buildNote();
        mIconPaths[3] = buildMessage();
        mIconPaths[4] = buildPerson();
        mIcon.setStyle(Paint.Style.STROKE);
        mIcon.setStrokeCap(Paint.Cap.ROUND);
        mIcon.setStrokeJoin(Paint.Join.ROUND);
        mRipplePaint.setStyle(Paint.Style.STROKE);
        mLeft.snap(0f);
        mRight.snap(0f);
        mPress.snap(1f);
        mGhostR.snap(0f);
        mCollapse.snap(0f);
        mBulge.snap(0f);
    }

    // ================= 对外接口 =================

    // ================= 与新工程主壳对接的兼容层 =================

    public interface Listener {
        void onSelect(int index, boolean fromUser);
    }

    private Listener compatListener;

    public void setListener(Listener l) {
        this.compatListener = l;
        setOnTabSelectedListener(new OnTabSelectedListener() {
            @Override
            public void onTabSelected(int index) {
                if (compatListener != null) compatListener.onSelect(index, true);
            }
        });
    }

    public int selected() {
        return getSelectedIndex();
    }

    public void setSelected(int index, boolean animate) {
        setSelectedIndex(index, animate);
    }

    public void refreshBackdrop() {
        notifyContentChanged();
    }

    public static float heightPx(Context c) {
        return dp(c, 74f) + dp(c, 12f);
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public void setHostView(View host) {
        mHost = host;
        mNeedCapture = true;
        invalidate();
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        mListener = l;
    }

    public int getSelectedIndex() {
        return mSelected;
    }

    public void setSelectedIndex(int index, boolean animate) {
        if (index < 0 || index >= TAB_COUNT) return;
        mSelected = index;
        if (!animate) {
            float c = mTabCenter[index];
            mLeft.snap(c - mPillBaseW / 2f);
            mRight.snap(c + mPillBaseW / 2f);
            mDragX = c;
            mGhostX.snap(c);
            invalidate();
        } else {
            ensureTicker();
        }
    }

    /**
     * 滚动收拢：0 = 完全展开，1 = 完全收拢。
     * 由外部滚动容器根据滚动方向与速度驱动。
     */
    public void setCollapse(float t) {
        mScrollCollapse = Math.max(0f, Math.min(1f, t));
        mCollapse.target = Math.max(mScrollCollapse, mBulge.value * 0.60f);
        ensureTicker();
    }

    /** 背景内容发生滚动/变化时调用，让玻璃重新取样 */
    public void notifyContentChanged() {
        mNeedCapture = true;
        invalidate();
    }

    // ================= 测量与布局 =================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (dp(DP_BAR_H) + dp(DP_BAR_TOP) + dp(12f));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeGeometry();
        applyGeometryShift(0f);
        float c = mTabCenter[mSelected];
        mLeft.snap(c - mPillBaseW / 2f);
        mRight.snap(c + mPillBaseW / 2f);
        mDragX = c;
        mGhostX.snap(c);
        mNeedCapture = true;
    }

    /** 按当前收拢程度重新计算所有几何量，不触碰弹簧 */
    private void computeGeometry() {
        float c = mCollapse.value;
        float side = dp(DP_BAR_SIDE + DP_COLLAPSE_SIDE * c);
        float top = dp(DP_BAR_TOP + DP_COLLAPSE_TOP * c);
        float height = dp(DP_BAR_H - DP_COLLAPSE_H * c);

        mBarLeft = side;
        mBarRight = getWidth() - side;
        mBarTop = top;
        mBarBottom = top + height;

        mPillBaseW = dp(DP_PILL_W) * (1f - 0.07f * c);
        mPillBaseH = dp(DP_PILL_H) * (1f - 0.10f * c);
        mPillCorner = dp(DP_PILL_CORNER);
        mIconSize = dp(DP_ICON) * (1f - 0.09f * c);

        float barW = mBarRight - mBarLeft;
        float pad = dp(DP_BAR_PAD) * (1f - 0.15f * c);
        mSlotW = (barW - pad * 2f) / TAB_COUNT;
        for (int i = 0; i < TAB_COUNT; i++) {
            mTabCenter[i] = mBarLeft + pad + mSlotW * (i + 0.5f);
        }
    }

    /** 几何变化时把胶囊连同弹簧一起平移，避免收拢动画里胶囊跳位 */
    private void applyGeometryShift(float dx) {
        if (dx == 0f) return;
        mLeft.value += dx;
        mLeft.target += dx;
        mRight.value += dx;
        mRight.target += dx;
        mDragX += dx;
        mGhostX.value += dx;
        mGhostX.target += dx;
    }

    // ================= 动画步进 =================

    private boolean step(float dt) {
        // 拖拽：玻璃胀大、栏体让位收缩；松手同时回弹
        mBulge.target = mDragging ? 1f : 0f;
        boolean bg = mBulge.update(dt);
        mCollapse.target = Math.max(mScrollCollapse, mBulge.value * 0.60f);

        // 收拢：几何每帧跟随，同时平移胶囊
        if (Math.abs(mCollapse.target - mCollapse.value) > 0.0005f || mCollapse.vel != 0f) {
            float oldCenter = mTabCenter[mSelected];
            boolean cAlive = mCollapse.update(dt);
            computeGeometry();
            float newCenter = mTabCenter[mSelected];
            applyGeometryShift(newCenter - oldCenter);
            mNeedCapture = true;
        }

        float baseW = mPillBaseW;
        float center = mDragging ? mDragX : mTabCenter[mSelected];
        float targetW = baseW;

        if (mDragging) {
            float stretch = Math.min(1f, Math.abs(mVel) / 8000f);
            targetW = baseW * (1f + 0.55f * stretch);
        } else {
            mVel *= (float) Math.exp(-dt * 10f);
        }
        targetW *= 1f + 0.20f * mBulge.value;
        if (mPressed) targetW *= 1.04f;

        float lag = -mVel * 0.010f;
        if (lag > baseW * 0.20f) lag = baseW * 0.20f;
        if (lag < -baseW * 0.20f) lag = -baseW * 0.20f;

        mLeft.target = center - targetW / 2f + lag;
        mRight.target = center + targetW / 2f + lag;

        boolean a = mLeft.update(dt);
        boolean b = mRight.update(dt);

        mGlow.target = (mPressed ? 1f : 0.0f);
        boolean g = mGlow.update(dt);

        mPress.target = mPressed ? 0.972f : 1f;
        boolean p = mPress.update(dt);

        // ---- 液滴牵引 ----
        float stretch = Math.min(1f, Math.abs(mVel) / 8000f);
        float ghostTargetX;
        float ghostTargetR;
        if (mDragging) {
            ghostTargetX = mDragX - Math.signum(mVel == 0f ? 1f : mVel) * baseW * (0.18f + 0.30f * stretch);
            ghostTargetR = baseW * (0.10f + 0.20f * stretch);
        } else {
            ghostTargetX = (mLeft.value + mRight.value) / 2f;
            ghostTargetR = 0f;
        }
        mGhostX.target = ghostTargetX;
        mGhostR.target = ghostTargetR;
        boolean gx = mGhostX.update(dt);
        boolean gr = mGhostR.update(dt);

        // ---- 重力感光平滑 ----
        float dl = mLightTarget - mLightAngle;
        if (Math.abs(dl) > 0.0004f) {
            mLightAngle += dl * Math.min(1f, dt * 9f);
        } else {
            mLightAngle = mLightTarget;
        }

        // ---- 涟漪推进 ----
        boolean rip = false;
        for (int i = 0; i < MAX_RIPPLES; i++) {
            if (mRipT[i] > 0f && mRipT[i] < 1f) {
                mRipT[i] += dt / 0.42f;
                if (mRipT[i] >= 1f) mRipT[i] = 0f;
                else rip = true;
            }
        }

        boolean lightMoving = Math.abs(mLightTarget - mLightAngle) > 0.0004f;

        return a || b || g || p || gx || gr || bg || rip || lightMoving || mDragging;
    }

    private void ensureTicker() {
        if (!mTicking) {
            mTicking = true;
            mLastNanos = 0L;
            Choreographer.getInstance().postFrameCallback(mTicker);
        }
    }

    // ================= 触摸 =================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (y < mBarTop - dp(6f) || y > mBarBottom + dp(6f)) return false;
                // 按下瞬间校准液滴到当前胶囊中心，避免从上一轮的残留位置飘过来
                mGhostX.snap((mLeft.value + mRight.value) / 2f);
                mGhostR.snap(0f);
                mDragging = true;
                mPressed = true;
                mVel = 0f;
                mDragX = clampX(x);
                ensureTicker();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mDragging) return true;
                float nx = clampX(x);
                mVel = mVel * 0.55f + (nx - mDragX) * 42f;
                mDragX = nx;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!mDragging) return true;
                mDragging = false;
                mPressed = false;
                int idx = nearestIndex(mDragX + mVel * 0.05f);
                if (idx != mSelected) {
                    mSelected = idx;
                    addRipple(mTabCenter[idx]);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (mListener != null) mListener.onTabSelected(idx);
                }
                ensureTicker();
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void addRipple(float cx) {
        mRipX[mRipHead] = cx;
        mRipY[mRipHead] = mBarTop + (mBarBottom - mBarTop) / 2f;
        mRipT[mRipHead] = 0.001f;
        mRipHead = (mRipHead + 1) % MAX_RIPPLES;
        ensureTicker();
    }

    private float clampX(float x) {
        float pad = dp(DP_BAR_PAD);
        float lo = mBarLeft + pad + mPillBaseW / 2f;
        float hi = mBarRight - pad - mPillBaseW / 2f;
        return Math.max(lo, Math.min(hi, x));
    }

    private int nearestIndex(float x) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < TAB_COUNT; i++) {
            float d = Math.abs(mTabCenter[i] - x);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mBarRight <= mBarLeft) computeGeometry();
        if (WallProvider.liveActive() && WallProvider.liveGen() != mCapGen) mNeedCapture = true;
        captureIfNeeded();

        float bw = mBarRight - mBarLeft;
        float bh = mBarBottom - mBarTop;
        float radius = dp(DP_CORNER) * (1f - 0.06f * mCollapse.value);

        mBarPath.reset();
        mTmpRect.set(0f, 0f, bw, bh);
        mBarPath.addRoundRect(mTmpRect, radius, radius, Path.Direction.CW);

        float pillLeft = mLeft.value;
        float pillRight = mRight.value;
        float pillCX = (pillLeft + pillRight) / 2f - mBarLeft;
        float pillCY = (mBarTop + mBarBottom) / 2f - mBarTop;

        float stretch = Math.min(1f, Math.abs(mVel) / 8000f);
        // 泡泡感强度：拖动速度为主，鼓胀与按压叠加
        float sheen = Math.min(1f, Math.abs(mVel) / 5200f * 0.85f
                + mBulge.value * 0.45f + (mPressed ? 0.12f : 0f));
        float pillW = Math.max(mPillBaseW * 0.9f, pillRight - pillLeft);
        float pillH = mPillBaseH * (1f - 0.10f * stretch) * (mPressed ? 1.03f : 1f)
                * (1f + 0.16f * mBulge.value);

        float ghostX = mGhostX.value - mBarLeft;
        float ghostR = mGhostR.value;

        canvas.save();
        // 按压整体回弹：以栏中心为锚点整体轻微下压
        float ps = mPress.value;
        if (Math.abs(ps - 1f) > 0.0008f) {
            canvas.scale(ps, ps, bw / 2f, bh / 2f);
        }
        canvas.translate(mBarLeft, mBarTop);

        // 降级底衬：即使 AGSL 不可用界面也完整
        drawFallback(canvas, bw, bh, radius, pillLeft - mBarLeft, pillRight - mBarLeft,
                pillCY, pillH, ghostX, ghostR);

        if (Build.VERSION.SDK_INT >= 33) {
            ensureShader();
            if (mShaderOk && mShader != null && mCapShader != null) {
                RuntimeShader rs = (RuntimeShader) mShader;
                rs.setInputShader("uContent", mCapShader);
                rs.setFloatUniform("uSize", bw, bh);
                rs.setFloatUniform("uCorner", radius);
                rs.setFloatUniform("uPillCenter", pillCX, pillCY);
                rs.setFloatUniform("uPillHalf", pillW / 2f, pillH / 2f);
                rs.setFloatUniform("uPillCorner", mPillCorner);
                rs.setFloatUniform("uGhost", ghostX, pillCY);
                rs.setFloatUniform("uGhostR", ghostR);
                rs.setFloatUniform("uMerge", Math.max(1f, ghostR * 0.85f));
                rs.setFloatUniform("uBulge", mBulge.value);
                rs.setFloatUniform("uBulgePos", pillCX, pillCY);
                rs.setFloatUniform("uBulgeR", pillH * 0.58f * mBulge.value);
                rs.setFloatUniform("uBlur", dp(9f) * CAP_SCALE * 4f);
                rs.setFloatUniform("uLens", dp(26f));
                rs.setFloatUniform("uChroma", 0.035f);
                rs.setFloatUniform("uLight", mLightAngle);
                rs.setFloatUniform("uGlow", mGlow.value);
                float touchLocalX = mDragging ? (mDragX - mBarLeft) : pillCX;
                rs.setFloatUniform("uTouch", touchLocalX, pillCY);
                rs.setFloatUniform("uTint", 0.97f, 0.985f, 1.0f);
                rs.setFloatUniform("uSat", 1.32f);
                rs.setFloatUniform("uShrink", 0.13f + 0.27f * mBulge.value);
                rs.setFloatUniform("uSheen", sheen);
                try {
                    mFill.setShader(rs);
                    canvas.drawRect(0f, 0f, bw, bh, mFill);
                } catch (Throwable t) {
                    mShaderOk = false;
                    mFill.setShader(null);
                } finally {
                    mFill.setShader(null);
                }
            }
        }

        // 栏内极淡的内描边，强化「玻璃厚度」
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(dp(1f));
        mStroke.setShader(new LinearGradient(0f, 0f, 0f, bh,
                new int[]{Theme.W(0x40), Theme.W(0x0A), Theme.W(0x18)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(insetPath(bw, bh, radius), mStroke);
        mStroke.setShader(null);

        // 图标
        drawIcons(canvas, bw, bh, pillCX, pillW, pillH);

        // 涟漪画在最上层
        drawRipples(canvas);

        canvas.restore();
    }

    private void drawRipples(Canvas canvas) {
        for (int i = 0; i < MAX_RIPPLES; i++) {
            float t = mRipT[i];
            if (t <= 0f) continue;
            float e = 1f - (1f - t) * (1f - t);
            float r = dp(9f) + dp(19f) * e;
            int alpha = (int) (0x8C * (1f - e) * (1f - e));
            if (alpha <= 2) continue;
            mRipplePaint.setStrokeWidth(dp(1.6f) * (1f - 0.5f * e));
            mRipplePaint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawCircle(mRipX[i] - mBarLeft, mRipY[i] - mBarTop, r, mRipplePaint);
        }
    }

    private void drawIcons(Canvas canvas, float bw, float bh, float pillCX, float pillW,
                           float pillH) {
        float size = mIconSize;
        float cy = bh / 2f;
        // 文字/图标：跟着胶囊一起放大，但只吃约 55% 的放大幅度（不与胶囊等比例），
        // 上限受底层宽度约束（绝不超过底层允许的放大），下限轻微回缩以保证收缩时不跳。
        float growH = pillH / Math.max(1f, mPillBaseH);
        float growW = pillW / Math.max(1f, mPillBaseW);
        float txt = 1f + 0.55f * (growH - 1f);
        txt = Math.min(txt, Math.max(1f, growW));
        txt = Math.max(0.94f, Math.min(1.16f, txt));
        for (int i = 0; i < TAB_COUNT; i++) {
            float cx = mTabCenter[i] - mBarLeft;
            float d = Math.abs(cx - pillCX);
            float light = 1f - Math.min(1f, d / Math.max(1f, mSlotW * 0.72f));
            int base = 0xFFFFFFFF;
            int hot = 0xFFFFFFFF;
            int color = blend(base, hot, light);
            mIcon.setColor(color);
            mIcon.setStrokeWidth(1.55f + 0.30f * light);
            canvas.save();
            // 以各自槽位中心为锚点缩放：文字随胶囊起伏但不脱离底层排布
            canvas.translate(cx, cy);
            canvas.scale(txt, txt);
            canvas.translate(-size / 2f, -size / 2f);
            canvas.scale(size / 24f, size / 24f);
            canvas.drawPath(mIconPaths[i], mIcon);
            canvas.restore();
        }
    }

    // ================= 降级路径 =================

    private void drawFallback(Canvas canvas, float bw, float bh, float radius,
                              float pillL, float pillR, float pillCY, float pillH,
                              float ghostX, float ghostR) {
        canvas.save();
        canvas.clipPath(mBarPath);

        Bitmap back = (mFrostBmp != null) ? mFrostBmp : mCapBmp;
        Rect src = null;
        RectF dst = null;
        if (back != null && mCapRect.width() > 0) {
            float s = CAP_SCALE;
            src = new Rect(
                    Math.round((mBarLeft - mCapRect.left) * s),
                    Math.round((mBarTop - mCapRect.top) * s),
                    Math.round((mBarRight - mCapRect.left) * s),
                    Math.round((mBarBottom - mCapRect.top) * s));
            dst = new RectF(0f, 0f, bw, bh);
            canvas.drawBitmap(back, src, dst, mBitmapPaint);
        }

        // 玻璃白雾
        mFill.setShader(null);
        mFill.setColor(Theme.W(0x38));
        canvas.drawRect(0f, 0f, bw, bh, mFill);

        // 受光方向随重力倾斜偏转：渐变起止点沿光矢量旋转
        float lx = (float) Math.cos(mLightAngle);
        float ly = (float) Math.sin(mLightAngle);
        float ox = lx * bh * 1.15f;
        float oy = ly * bh * 1.15f;
        mFill.setShader(new LinearGradient(bw / 2f - ox, bh / 2f - oy,
                bw / 2f + ox, bh / 2f + oy,
                new int[]{Theme.W(0x33), Theme.W(0x00), 0x10000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, bw, bh, mFill);
        mFill.setShader(null);

        // 滞后的液滴球（降级：两颗半透明圆 + 中间一段哑铃状过渡）
        if (ghostR > 1f) {
            float pillCX = (pillL + pillR) / 2f;
            mFill.setColor(Theme.W(0x2E));
            canvas.drawCircle(ghostX, pillCY, ghostR, mFill);
            float mid = (pillCX + ghostX) / 2f;
            float gap = Math.abs(ghostX - pillCX);
            float bridge = Math.max(2f, Math.min(ghostR, gap * 0.45f));
            float bl = Math.min(pillL, ghostX - ghostR * 0.6f);
            float br = Math.max(pillR, ghostX + ghostR * 0.6f);
            mTmpRect.set(bl, pillCY - bridge * 0.62f, br, pillCY + bridge * 0.62f);
            canvas.drawRoundRect(mTmpRect, bridge * 0.62f, bridge * 0.62f, mFill);
        }

        // ---- 选中胶囊：轮廓用 SDF 思路的路径并集（胶囊 ∪ 水桥 ∪ 液滴）----
        float pr = Math.min(mPillCorner, pillH / 2f);
        mPillPath.reset();
        mTmpRect.set(pillL, pillCY - pillH / 2f, pillR, pillCY + pillH / 2f);
        mPillPath.addRoundRect(mTmpRect, pr, pr, Path.Direction.CW);

        if (ghostR > 1.5f) {
            // 腰细的水桥：两段二次贝塞尔，中段收细，模拟表面张力
            float dir = (ghostX >= (pillL + pillR) / 2f) ? 1f : -1f;
            float xa = dir > 0 ? pillR : pillL;
            float ra = pillH * 0.30f;
            float gIn = ghostX - dir * ghostR * 0.86f;
            float midX = (xa + gIn) * 0.5f;
            mBridgePath.reset();
            mBridgePath.moveTo(xa, pillCY - ra);
            mBridgePath.quadTo(midX, pillCY - ra * 0.20f, gIn, pillCY - ghostR * 0.52f);
            mBridgePath.quadTo(ghostX, pillCY - ghostR, ghostX + dir * ghostR, pillCY);
            mBridgePath.quadTo(ghostX, pillCY + ghostR, gIn, pillCY + ghostR * 0.52f);
            mBridgePath.quadTo(midX, pillCY + ra * 0.20f, xa, pillCY + ra);
            mBridgePath.close();
            mPillPath.op(mBridgePath, Path.Op.UNION);

            mGhostPath.reset();
            mGhostPath.addCircle(ghostX, pillCY, ghostR, Path.Direction.CW);
            mPillPath.op(mGhostPath, Path.Op.UNION);
        }

        // 透镜：把底层内容在胶囊内局部放大，制造凸起错觉（最便宜的折射近似）
        if (back != null && src != null) {
            canvas.save();
            canvas.clipPath(mPillPath);
            float lens = 1f + 0.10f * (0.45f + 0.55f * mBulge.value);
            canvas.scale(lens, lens, (pillL + pillR) / 2f, pillCY);
            canvas.drawBitmap(back, src, dst, mBitmapPaint);
            canvas.restore();
        }

        // 玻璃白雾
        mFill.setShader(null);
        mFill.setColor(Theme.W(0x34));
        canvas.drawPath(mPillPath, mFill);

        // 边缘色散：冷暖两层极细错位描边
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(dp(1.2f));
        canvas.save();
        canvas.translate(-0.7f, -0.7f);
        mStroke.setColor(0x26B4ECFF);
        canvas.drawPath(mPillPath, mStroke);
        canvas.restore();
        canvas.save();
        canvas.translate(0.7f, 0.7f);
        mStroke.setColor(0x1EF8C8DC);
        canvas.drawPath(mPillPath, mStroke);
        canvas.restore();

        mStroke.setStrokeWidth(dp(1f));
        mStroke.setColor(Theme.W(0x5C));
        canvas.drawPath(mPillPath, mStroke);

        // 上缘高光（方向跟随重力）
        mFill.setShader(new LinearGradient(
                (pillL + pillR) / 2f - ox * 0.55f, pillCY - oy * 0.55f,
                (pillL + pillR) / 2f + ox * 0.55f, pillCY + oy * 0.55f,
                new int[]{Theme.W(0x46), Theme.W(0x00)}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(mPillPath, mFill);
        mFill.setShader(null);

        // 鼓包亮斑 + 触摸光斑
        float glow = Math.max(mGlow.value * 0.85f, mBulge.value * 0.75f);
        if (glow > 0.01f) {
            float gx = mDragging ? (mDragX - mBarLeft) : (pillL + pillR) / 2f;
            float gr2 = dp(58f) * (1f + 0.35f * mBulge.value);
            mFill.setShader(new RadialGradient(gx, pillCY, gr2,
                    new int[]{Theme.W(0x50), Theme.W(0x00)}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(gx, pillCY, gr2, mFill);
            mFill.setShader(null);
        }
        canvas.restore();
    }

    // ================= 背景捕获 =================

    private void captureIfNeeded() {
        if (!mNeedCapture) return;
        if (mHost == null || mHost.getWidth() == 0 || mHost.getHeight() == 0) return;

        int pad = (int) dp(22f);
        int[] hostLoc = new int[2];
        int[] selfLoc = new int[2];
        mHost.getLocationInWindow(hostLoc);
        getLocationInWindow(selfLoc);

        int capL = selfLoc[0] + (int) mBarLeft - pad;
        int capT = selfLoc[1] + (int) mBarTop - pad;
        int capR = selfLoc[0] + (int) mBarRight + pad;
        int capB = selfLoc[1] + (int) mBarBottom + pad;

        int w = Math.max(1, Math.round((capR - capL) * CAP_SCALE));
        int h = Math.max(1, Math.round((capB - capT) * CAP_SCALE));

        try {
            if (mCapBmp == null || mCapBmp.getWidth() != w || mCapBmp.getHeight() != h) {
                mCapBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                mCapCanvas = new Canvas(mCapBmp);
            }
            Canvas cv = mCapCanvas;
            cv.drawColor(Theme.BAR_BG, PorterDuff.Mode.SRC);
            cv.save();
            cv.scale(CAP_SCALE, CAP_SCALE);
            cv.translate(hostLoc[0] - capL, hostLoc[1] - capT);
            if (WallProvider.liveActive()) {
                WallProvider.drawLiveTo(cv, mBitmapPaint);
            }
            mHost.draw(cv);
            cv.restore();
            mCapGen = WallProvider.liveGen();

            mCapRect.set(capL, capT, capR, capB);

            if (Build.VERSION.SDK_INT < 33) buildFrost();

            mCapShader = new BitmapShader(mCapBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mTmpMtx.reset();
            mTmpMtx.setScale(1f / CAP_SCALE, 1f / CAP_SCALE);
            mTmpMtx.postTranslate(capL - selfLoc[0] - mBarLeft, capT - selfLoc[1] - mBarTop);
            mCapShader.setLocalMatrix(mTmpMtx);
            mNeedCapture = false;
        } catch (Throwable t) {
            mNeedCapture = false;
        }
    }

    /** 对 1/4 降采样小图做盒式模糊，得到磨砂玻璃底；小图（~90x60）两遍扫描开销可忽略 */
    private void buildFrost() {
        if (mCapBmp == null) return;
        try {
            int w = mCapBmp.getWidth();
            int h = mCapBmp.getHeight();
            if (w < 2 || h < 2) return;
            if (mFrostBmp == null || mFrostBmp.getWidth() != w || mFrostBmp.getHeight() != h) {
                mFrostBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            int[] px = new int[w * h];
            int[] tmp = new int[w * h];
            mCapBmp.getPixels(px, 0, w, 0, 0, w, h);
            blurPass(px, tmp, w, h, 2);
            blurPass(px, tmp, w, h, 3);
            mFrostBmp.setPixels(px, 0, w, 0, 0, w, h);
        } catch (Throwable ignored) {
        }
    }

    private static void blurPass(int[] px, int[] tmp, int w, int h, int r) {
        int div = r * 2 + 1;
        if (div <= 1) return;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = px[row + Math.min(w - 1, Math.max(0, i))];
                a += (c >>> 24) & 0xFF;
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int co = px[row + Math.min(w - 1, x + r + 1)];
                int ci = px[row + Math.max(0, x - r)];
                a += ((co >>> 24) & 0xFF) - ((ci >>> 24) & 0xFF);
                rr += ((co >> 16) & 0xFF) - ((ci >> 16) & 0xFF);
                gg += ((co >> 8) & 0xFF) - ((ci >> 8) & 0xFF);
                bb += (co & 0xFF) - (ci & 0xFF);
            }
        }
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = tmp[Math.min(h - 1, Math.max(0, i)) * w + x];
                a += (c >>> 24) & 0xFF;
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int co = tmp[Math.min(h - 1, y + r + 1) * w + x];
                int ci = tmp[Math.max(0, y - r) * w + x];
                a += ((co >>> 24) & 0xFF) - ((ci >>> 24) & 0xFF);
                rr += ((co >> 16) & 0xFF) - ((ci >> 16) & 0xFF);
                gg += ((co >> 8) & 0xFF) - ((ci >> 8) & 0xFF);
                bb += (co & 0xFF) - (ci & 0xFF);
            }
        }
    }

    private void ensureShader() {
        if (mShaderTried) return;
        mShaderTried = true;
        if (Build.VERSION.SDK_INT < 33) {
            mShaderOk = false;
            return;
        }
        try {
            mShader = new RuntimeShader(GlassShaders.BAR);
            mShaderOk = true;
        } catch (Throwable t) {
            mShaderOk = false;
        }
    }

    // ================= 生命周期 =================

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mNeedCapture = true;
        ensureTicker();
        startSensor();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSensor();
    }

    private void startSensor() {
        if (mSensorOn) return;
        try {
            Context ctx = getContext();
            mSensorMgr = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (mSensorMgr == null) return;
            mGravity = mSensorMgr.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (mGravity == null) mGravity = mSensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (mGravity == null) return;
            mSensorOn = mSensorMgr.registerListener(mLightListener, mGravity,
                    SensorManager.SENSOR_DELAY_GAME);
        } catch (Throwable ignored) {
        }
    }

    private void stopSensor() {
        if (!mSensorOn) return;
        try {
            mSensorMgr.unregisterListener(mLightListener);
        } catch (Throwable ignored) {
        }
        mSensorOn = false;
    }

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            if (e.values.length < 2) return;
            float gx = e.values[0];
            float gy = e.values[1];
            // 竖直持握时重力约 (0, -9.8)；左右倾斜让 gx 偏离 0
            // 光照角 -π/2 = 正上方，倾斜时向抬高一侧偏移
            float tilt = (float) Math.atan2(gx, Math.max(0.001f, -gy));
            tilt = Math.max(-1.05f, Math.min(1.05f, tilt));
            float target = -1.5708f + tilt * 0.72f;
            if (Math.abs(target - mLightTarget) < 0.0025f) return;
            mLightTarget = target;
            ensureTicker();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    // ================= 工具 =================

    private Path insetPath(float w, float h, float r) {
        Path p = new Path();
        RectF rf = new RectF(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f));
        p.addRoundRect(rf, r - dp(0.5f), r - dp(0.5f), Path.Direction.CW);
        return p;
    }

    private static int blend(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ================= 图标（24x24 坐标系自绘） =================

    private static Path buildHome() {
        Path p = new Path();
        p.moveTo(4.2f, 10.6f);
        p.lineTo(12f, 3.7f);
        p.lineTo(19.8f, 10.6f);
        p.lineTo(19.8f, 19.4f);
        p.lineTo(14.5f, 19.4f);
        p.lineTo(14.5f, 13.9f);
        p.lineTo(9.5f, 13.9f);
        p.lineTo(9.5f, 19.4f);
        p.lineTo(4.2f, 19.4f);
        p.close();
        return p;
    }

    private static Path buildCompass() {
        Path p = new Path();
        p.addCircle(12f, 12f, 8.3f, Path.Direction.CW);
        Path needle = new Path();
        needle.moveTo(12f, 6.9f);
        needle.lineTo(14.1f, 12f);
        needle.lineTo(12f, 17.1f);
        needle.lineTo(9.9f, 12f);
        needle.close();
        p.addPath(needle);
        return p;
    }

    private static Path buildMessage() {
        Path p = new Path();
        p.moveTo(4.6f, 6.4f);
        p.lineTo(19.4f, 6.4f);
        p.quadTo(21f, 6.4f, 21f, 8f);
        p.lineTo(21f, 14.6f);
        p.quadTo(21f, 16.2f, 19.4f, 16.2f);
        p.lineTo(11.4f, 16.2f);
        p.lineTo(7.2f, 19.6f);
        p.lineTo(7.2f, 16.2f);
        p.lineTo(4.6f, 16.2f);
        p.quadTo(3f, 16.2f, 3f, 14.6f);
        p.lineTo(3f, 8f);
        p.quadTo(3f, 6.4f, 4.6f, 6.4f);
        p.close();
        return p;
    }

    private static Path buildNote() {
        Path p = new Path();
        // 双八分音符：左侧低符头、右侧高符头，两条符干 + 顶部连杠
        p.addCircle(7.4f, 17.2f, 2.5f, Path.Direction.CW);
        p.addCircle(16.8f, 15.4f, 2.5f, Path.Direction.CW);
        p.moveTo(9.9f, 17.2f);
        p.lineTo(9.9f, 7.0f);
        p.moveTo(19.3f, 15.4f);
        p.lineTo(19.3f, 5.2f);
        p.moveTo(9.9f, 7.0f);
        p.lineTo(19.3f, 5.2f);
        return p;
    }

    private static Path buildPerson() {
        Path p = new Path();
        p.addCircle(12f, 8.5f, 3.5f, Path.Direction.CW);
        Path shoulder = new Path();
        shoulder.moveTo(5f, 19.6f);
        shoulder.quadTo(5f, 13.4f, 12f, 13.4f);
        shoulder.quadTo(19f, 13.4f, 19f, 19.6f);
        p.addPath(shoulder);
        return p;
    }

    // ================= 单自由度弹簧（临界阻尼可调） =================

    private static final class Spring {
        private final float k;
        private final float damp;
        float value;
        float vel;
        float target;

        Spring(float omega, float zeta) {
            k = omega * omega;
            damp = 2f * zeta * omega;
        }

        void snap(float v) {
            value = v;
            target = v;
            vel = 0f;
        }

        boolean update(float dt) {
            float a = k * (target - value) - damp * vel;
            vel += a * dt;
            value += vel * dt;
            if (Math.abs(vel) < 0.6f && Math.abs(target - value) < 0.05f) {
                value = target;
                vel = 0f;
                return false;
            }
            return true;
        }
    }
}

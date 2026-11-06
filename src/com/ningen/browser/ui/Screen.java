package com.ningen.browser.ui;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.WindowInsets;

/**
 * 屏幕自适应：按屏幕最短边决定整体 UI 缩放，宽屏限制内容最大宽度并居中。
 * 所有尺寸换算统一走这里，避免各页面自己拍脑袋定死 dp。
 */
public final class Screen {

    /** dp 转 px，含全局缩放（原本在 Blocks 里，此处内联以免拖入整套 UI 工具）。 */
    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density * scale() + 0.5f);
    }

    /** 平板 / 折叠屏展开态的内容最大宽度（dp）。 */
    private static final float WIDE_CONTENT_DP = 560f;

    private static int minDp = 360;
    private static int maxDp = 800;
    private static boolean inited = false;

    private Screen() {}

    public static void init(Context c) {
        if (inited) return;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float w = dm.widthPixels / dm.density;
        float h = dm.heightPixels / dm.density;
        minDp = Math.round(Math.min(w, h));
        maxDp = Math.round(Math.max(w, h));
        inited = true;
    }

    public static int minWidthDp() { return minDp; }

    public static int maxHeightDp() { return maxDp; }

    public static boolean wide() { return minDp >= 600; }

    /** 整体缩放：超小屏收紧，大屏与平板放大，主流机型保持不变。 */
    public static float scale() {
        if (minDp >= 600) return 1.16f;
        if (minDp >= 420) return 1.06f;
        if (minDp >= 350) return 1.0f;
        if (minDp >= 320) return 0.93f;
        return 0.86f;
    }

    /** 字号缩放幅度比间距、控件更保守，避免小屏挤、大屏虚。 */
    public static float textScale() {
        float s = scale();
        if (s > 1.1f) return 1.08f;
        if (s < 0.95f) return 0.95f;
        return 1f;
    }

    /**
     * 按屏幕宽度算左右内缩：窄屏用基准值，宽屏把多余空间左右均分，让内容居中不成一条长带。
     */
    public static int sideInset(Context c, float baseDp) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float screenDp = dm.widthPixels / dm.density;
        float extra = 0f;
        if (screenDp > WIDE_CONTENT_DP) extra = (screenDp - WIDE_CONTENT_DP) / 2f;
        return dp(c, baseDp + extra);
    }

    /** 刘海、挖孔的安全高度（px）。状态栏隐藏后，这是顶部唯一需要让开的位置。 */
    public static int cutoutTop(Activity a) {
        WindowInsets wi = a.getWindow().getDecorView().getRootWindowInsets();
        if (wi != null) {
            DisplayCutout dc = wi.getDisplayCutout();
            if (dc != null) return dc.getSafeInsetTop();
        }
        return 0;
    }

    // 状态栏隐藏后的顶部安全距离：有刘海/挖孔就压着安全区往下，没有则给一段保底留白，避免贴顶
    public static int topInset(Activity a, float extraDp) {
        int cut = cutoutTop(a);
        if (cut > 0) return cut + dp(a, extraDp);
        return dp(a, extraDp < 14f ? 14f : extraDp);
    }
}

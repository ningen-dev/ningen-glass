package com.ningen.browser.render;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;

/**
 * 全局视觉常量：只有一套浅色纸感皮（凝恩不要夜间/深色模式），配内置壁纸背景。
 */
public final class Theme {

    public static int CYAN;
    public static int INK;
    public static int WHITE;
    public static int TEXT_1;
    public static int TEXT_2;
    public static int TEXT_3;
    public static int GLASS_TINT;
    public static int GLASS_EDGE;
    public static int GLASS_SHADOW;
    public static int BG;
    public static int BAR_BG;
    private static Typeface TF_LIGHT, TF_MED, TF_BOLD;
    public static boolean light = true;

    static { apply(true); }

    /**
     * 唯一一套外观：浅色纸感 + 内置壁纸背景。深色/夜间分支已彻底移除，
     * 参数仅为兼容旧调用而保留，传入什么都不影响结果。
     */
    public static void apply(boolean ignored) {
        light = true;
        BG = 0xFFF1F5F4;
        BAR_BG = 0xFFF6FAF9;
        CYAN = 0xFF0E9E93;
        INK = 0xFFFFFFFF;
        WHITE = 0xFF0B1615;
        TEXT_1 = 0xFF0B1615;
        TEXT_2 = 0xB30B1615;
        TEXT_3 = 0x730B1615;
        GLASS_TINT = 0x40FFFFFF;
        GLASS_EDGE = 0x33FFFFFF;
        GLASS_SHADOW = 0x26000000;
    }

    /** 半透明黑，用于玻璃高光与描边（浅色底统一走这套）。 */
    public static int W(int alphaByte) {
        return ((alphaByte & 0xFF) << 24) | 0x000000;
    }

    public static final float CORNER_L = 28f;
    public static final float CORNER_M = 22f;
    public static final float CORNER_S = 16f;
    public static Typeface light() { if (TF_LIGHT==null) TF_LIGHT=Typeface.create("sans-serif-light", Typeface.NORMAL); return TF_LIGHT; }
    public static Typeface medium() { if (TF_MED==null) TF_MED=Typeface.create("sans-serif-medium", Typeface.NORMAL); return TF_MED; }
    public static Typeface bold() { if (TF_BOLD==null) TF_BOLD=Typeface.create("sans-serif", Typeface.BOLD); return TF_BOLD; }
    public static int alpha(int color, float a) { int al=(int)(Color.alpha(color)*Math.max(0f,Math.min(1f,a))); return (color&0x00FFFFFF)|(al<<24); }
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) (aa + (ba - aa) * t), rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t), rb = (int) (ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
    public static float dp(Context c,float v) { return v*c.getResources().getDisplayMetrics().density; }
    public static int dpi(Context c,float v) { return Math.round(dp(c,v)); }
    public static boolean hasShader() { return android.os.Build.VERSION.SDK_INT>=33; }
}

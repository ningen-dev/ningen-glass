package com.ningen.browser.core;

/**
 * 缓动曲线集合。按"快出慢收"的节奏取值：
 * 进入用减速曲线（越到终点越粘），退出用加速曲线，位移配合轻微过冲。
 */
public final class Ease {

    private Ease() {}

    /** 标准减速：0.05, 0.7, 0.1, 1 —— 起步干脆、收尾绵长。 */
    public static float out(float t) {
        return bezier(t, 0.05f, 0.7f, 0.1f, 1f);
    }

    /** 强调型减速：0.2, 0, 0, 1 —— 大块面位移用。 */
    public static float emphasized(float t) {
        return bezier(t, 0.2f, 0f, 0f, 1f);
    }

    /** 强调型加速：0.3, 0, 0.8, 0.15 —— 离场用。 */
    public static float inEmphasized(float t) {
        return bezier(t, 0.3f, 0f, 0.8f, 0.15f);
    }

    public static float inOut(float t) {
        return bezier(t, 0.65f, 0f, 0.35f, 1f);
    }

    public static float linear(float t) {
        return t;
    }

    /** 轻微过冲，用于元素落位。 */
    public static float overshoot(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float p = t - 1f;
        return 1f + c3 * p * p * p + c1 * p * p;
    }

    /** 指数衰减的冲击包络，0 -> 1 -> 回落，做"滞留脉冲"。 */
    public static float pulse(float t) {
        if (t <= 0f || t >= 1f) return 0f;
        return (float) (Math.sin(t * Math.PI) * Math.pow(1f - t, 0.35));
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 三次贝塞尔求值：x 为时间，解出参数后再取 y。牛顿迭代 6 次足够。 */
    public static float bezier(float x, float x1, float y1, float x2, float y2) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        float t = x;
        for (int i = 0; i < 6; i++) {
            float cx = curveX(t, x1, x2) - x;
            if (Math.abs(cx) < 1e-4f) break;
            float d = curveDX(t, x1, x2);
            if (Math.abs(d) < 1e-6f) break;
            t -= cx / d;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
        }
        return curveY(t, y1, y2);
    }

    private static float curveX(float t, float p1, float p2) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    private static float curveY(float t, float p1, float p2) {
        return curveX(t, p1, p2);
    }

    private static float curveDX(float t, float p1, float p2) {
        float u = 1f - t;
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2);
    }
}

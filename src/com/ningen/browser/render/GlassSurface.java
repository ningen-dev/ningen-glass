package com.ningen.browser.render;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;

/**
 * 液态玻璃的双管绘制。
 *
 * 高版（33+）：AGSL 运行时着色器一次性完成边缘折射、RGB 色散、法线高光与边缘压暗；
 * 低版（29-32）：退化为"降采样位图 + 盒式模糊 + 渐变高光 + 描边"，观感接近但无折射。
 * 两条管线的接口完全一致，面板层不需要感知版本。
 */
public final class GlassSurface {

    private static final String TAG = "GlassSurface";

    private static final String AGSL =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float radius;\n" +
            "uniform float refr;\n" +
            "uniform float chroma;\n" +
            "uniform float glowStrength;\n" +
            "uniform float2 light;\n" +
            "\n" +
            "float sdRB(float2 p, float2 b, float r) {\n" +
            "    float2 q = abs(p) - b + r;\n" +
            "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 p = coord - halfSize;\n" +
            "    float2 inner = halfSize - 1.5;\n" +
            "    float d = sdRB(p, inner, radius);\n" +
            "    float2 g = float2(sdRB(p + float2(1.0, 0.0), inner, radius) - d,\n" +
            "                      sdRB(p + float2(0.0, 1.0), inner, radius) - d);\n" +
            "    float gl = length(g);\n" +
            "    float2 n = float2(0.0, 0.0);\n" +
            "    if (gl > 0.0001) { n = g / gl; }\n" +
            "    float band = min(halfSize.x, halfSize.y) * 0.72;\n" +
            "    float t = smoothstep(-band, 0.0, d);\n" +
            "    float2 off = n * (t * t) * refr;\n" +
            "    half4 col;\n" +
            "    col.r = content.eval(coord + off * (1.0 + chroma)).r;\n" +
            "    col.g = content.eval(coord + off).g;\n" +
            "    col.b = content.eval(coord + off * (1.0 - chroma)).b;\n" +
            "    col.a = 1.0;\n" +
            "    float2 ln = light;\n" +
            "    if (length(ln) < 0.05) { ln = float2(-0.62, -0.78); }\n" +
            "    ln = normalize(ln);\n" +
            "    float hl = pow(max(dot(n, ln), 0.0), 2.2)\n" +
            "             * smoothstep(-band * 0.45, 0.0, d);\n" +
            "    col.rgb += half3(hl * glowStrength);\n" +
            "    float rim = smoothstep(-3.0, 0.0, d);\n" +
            "    col.rgb *= (1.0 - rim * 0.10);\n" +
            "    return col;\n" +
            "}\n";

    private static RuntimeShader shader;
    private static boolean shaderDead;

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint OVER = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint EDGE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path CLIP = new Path();
    private static final Matrix M = new Matrix();

    private GlassSurface() {}

    public static boolean shaderActive() {
        return Theme.hasShader() && !shaderDead;
    }

    /**
     * 画一块玻璃。调用方需保证 canvas 未裁剪。
     *
     * @param snap   已降采样的背景快照，尺寸与 rect 成比例
     * @param radius 圆角
     * @param frost  磨砂叠加（0~1），越大越"哑"
     * @param glow   高光强度
     */
    public static void draw(Canvas canvas, RectF rect, float radius, Bitmap snap,
                            float frost, float glow) {
        if (rect.width() <= 1f || rect.height() <= 1f) return;

        canvas.save();
        CLIP.reset();
        CLIP.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(CLIP);

        boolean drew = false;
        if (snap != null && shaderActive() && canvas.isHardwareAccelerated()) {
            drew = drawAgsl(canvas, rect, radius, snap, glow);
        }
        if (!drew && snap != null) {
            // 低版管线：快照已模糊，直接铺满
            M.reset();
            M.setScale(rect.width() / snap.getWidth(), rect.height() / snap.getHeight());
            BitmapShader bs = new BitmapShader(snap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bs.setLocalMatrix(M);
            FILL.setShader(bs);
            FILL.setAlpha(255);
            canvas.drawRect(rect, FILL);
            FILL.setShader(null);
            drew = true;
        }
        if (!drew) {
            canvas.drawRect(rect, fillPaint(Theme.BG));
        }

        // 乳白磨砂层：让底图退到"隔着玻璃"的位置
        OVER.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                Theme.W(0x1C), Theme.W(0x0D), Shader.TileMode.CLAMP));
        OVER.setAlpha((int) (255 * (0.55f + 0.45f * frost)));
        canvas.drawRect(rect, OVER);
        OVER.setShader(null);

        // 顶部斜向高光带（方向取实时光向，低版本也能"跟手"）
        float llen = Math.max(0.05f, (float) Math.hypot(Tilt.lightX(), Tilt.lightY()));
        float lx = Tilt.lightX() / llen;
        float ly = Tilt.lightY() / llen;
        float hw = rect.width() * 0.5f;
        float hh = rect.height() * 0.5f;
        OVER.setShader(new LinearGradient(rect.centerX() + lx * hw, rect.centerY() + ly * hh,
                rect.centerX() - lx * hw * 0.6f, rect.centerY() - ly * hh * 0.6f,
                Theme.W(0x30), Theme.W(0x00), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, OVER);
        OVER.setShader(null);

        // 底部内侧阴影，压出发丝厚度
        OVER.setShader(new LinearGradient(rect.left, rect.bottom - rect.height() * 0.55f,
                rect.left, rect.bottom, 0x00000000, 0x1F000000, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, OVER);
        OVER.setShader(null);

        canvas.restore();

        // 描边：上亮下暗的错位双线，模仿玻璃切割面
        EDGE.setStyle(Paint.Style.STROKE);
        EDGE.setStrokeWidth(Math.max(1f, rect.width() / 700f));
        EDGE.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{Theme.W(0x66), Theme.W(0x1A), Theme.W(0x40), Theme.W(0x0D)},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, EDGE);
        EDGE.setShader(null);
        EDGE.setStyle(Paint.Style.FILL);
    }

    private static boolean drawAgsl(Canvas canvas, RectF rect, float radius, Bitmap snap, float glow) {
        try {
            if (shader == null) shader = new RuntimeShader(AGSL);
            float w = rect.width();
            float h = rect.height();

            M.reset();
            M.setScale(w / snap.getWidth(), h / snap.getHeight());
            BitmapShader bs = new BitmapShader(snap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bs.setLocalMatrix(M);

            shader.setInputShader("content", bs);
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("refr", Math.min(w, h) * 0.055f);
            shader.setFloatUniform("chroma", 0.012f);
            shader.setFloatUniform("glowStrength", glow);
            shader.setFloatUniform("light", Tilt.lightX(), Tilt.lightY());

            FILL.setShader(shader);
            int save = canvas.save();
            canvas.translate(rect.left, rect.top);
            canvas.drawRect(0f, 0f, w, h, FILL);
            canvas.restoreToCount(save);
            FILL.setShader(null);
            return true;
        } catch (Throwable t) {
            shaderDead = true;
            Log.w(TAG, "agsl disabled, falling back to cpu", t);
            return false;
        }
    }

    private static Paint fillPaint(int color) {
        FILL.setShader(null);
        FILL.setColor(color);
        return FILL;
    }
}

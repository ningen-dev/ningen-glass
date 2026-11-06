package com.ningen.browser.render;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 倾斜感应：让玻璃边缘的镜面高光跟着手机一起"滑"。
 *
 * 做法是这样——高光不是预烘的动画，而是由重力向量实时算出来的：
 * 取第一帧重力值当作水平中立位，之后手机怎么倾，光向就往哪边偏。
 * 只做低通滤波 + 节流重绘，开销可以忽略。
 */
public final class Tilt implements SensorEventListener {

    private static final String TAG = "Tilt";
    private static final float BASE_X = -0.62f;
    private static final float BASE_Y = -0.78f;
    /** 满量程倾斜（约 57°）时光向偏移多少。 */
    private static final float RANGE = 0.62f;

    private static final Tilt I = new Tilt();

    public static Tilt get() {
        return I;
    }

    private final List<View> hosts = new ArrayList<>();

    private SensorManager sm;
    private Sensor sensor;
    private boolean on;

    private boolean locked;
    private float nX, nY;
    private float sX, sY;
    private long lastInvalidate;

    private Tilt() {
    }

    /** 当前光向（未归一化也可，着色器里会 normalize）。 */
    public static float lightX() {
        return BASE_X + I.sX;
    }

    public static float lightY() {
        return BASE_Y + I.sY;
    }

    public static boolean active() {
        return I.on;
    }

    public void start(Context c) {
        if (on) return;
        try {
            sm = (SensorManager) c.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (sensor == null) sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (sensor == null) return;
            on = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        } catch (Throwable t) {
            Log.w(TAG, "start " + t);
            on = false;
        }
    }

    public void stop() {
        if (sm != null && on) {
            try {
                sm.unregisterListener(this);
            } catch (Throwable ignored) {
            }
        }
        on = false;
    }

    public void watch(View v) {
        if (v != null && !hosts.contains(v)) hosts.add(v);
    }

    public void unwatch(View v) {
        hosts.remove(v);
    }

    /** 重新取中立位：用户换姿势后不会一直偏着。 */
    public void releaseNeutral() {
        locked = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float gx = e.values[0];
        float gy = e.values[1];
        if (!locked) {
            nX = gx;
            nY = gy;
            locked = true;
            return;
        }
        float dx = clamp((gx - nX) / 9.8f) * RANGE;
        float dy = clamp((gy - nY) / 9.8f) * RANGE;
        // 低通，去掉手抖
        sX += (dx - sX) * 0.18f;
        sY += (dy - sY) * 0.18f;
        if (Math.abs(sX) < 0.004f && Math.abs(sY) < 0.004f) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastInvalidate < 32) return;
        lastInvalidate = now;
        for (int i = hosts.size() - 1; i >= 0; i--) {
            View v = hosts.get(i);
            if (v == null) {
                hosts.remove(i);
                continue;
            }
            v.postInvalidateOnAnimation();
        }
    }

    private static float clamp(float v) {
        return v > 1f ? 1f : (v < -1f ? -1f : v);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }
}

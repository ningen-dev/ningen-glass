package com.ningen.browser.core;

/** 单自由度临界阻尼弹簧，导航指示器与面板落位共用。 */
public class Spring {

    private float value;
    private float velocity;
    private float target;
    private float stiffness = 220f;
    private float damping = 1f;

    public Spring(float initial) {
        this.value = initial;
        this.target = initial;
    }

    public Spring configure(float stiffness, float damping) {
        this.stiffness = stiffness;
        this.damping = damping;
        return this;
    }

    public void setTarget(float t) {
        this.target = t;
    }

    public void snapTo(float v) {
        this.value = v;
        this.target = v;
        this.velocity = 0f;
    }

    public float value() {
        return value;
    }

    public float target() {
        return target;
    }

    public float velocity() {
        return velocity;
    }

    /** 固定步长积分，dt 以秒计；内部再拆成 1/240s 子步保证稳定。 */
    public void step(float dt) {
        float remain = dt;
        while (remain > 0f) {
            float h = Math.min(remain, 1f / 240f);
            float accel = -stiffness * (value - target) - 2f * damping * (float) Math.sqrt(stiffness) * velocity;
            velocity += accel * h;
            value += velocity * h;
            remain -= h;
        }
    }

    public boolean idle() {
        return Math.abs(value - target) < 0.02f && Math.abs(velocity) < 0.6f;
    }
}

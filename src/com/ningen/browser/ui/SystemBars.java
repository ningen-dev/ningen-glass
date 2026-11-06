package com.ningen.browser.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** 全屏沉浸：去掉顶部状态栏（时间、电量、信号），保留底部导航栏。 */
public final class SystemBars {

    private SystemBars() {
    }

    public static void hideStatusBar(Activity a) {
        try {
            Window w = a.getWindow();
            w.setStatusBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController ctl = w.getInsetsController();
                if (ctl != null) {
                    ctl.hide(WindowInsets.Type.statusBars());
                    // 从顶部边缘下滑仍可临时呼出状态栏，松手自动收起
                    ctl.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable ignore) {
            // 个别 ROM 的窗口控制器会抛异常；全屏失败也不能让应用起不来
        }
    }

    public static void rehide(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController ctl = a.getWindow().getInsetsController();
                if (ctl != null) ctl.hide(WindowInsets.Type.statusBars());
            } else {
                a.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 给目标视图加上「刘海 + 呼吸间距」的顶部留白，其余三边保持原值。
     * insets 在 onCreate 阶段可能还拿不到，所以一并 post 一次修正。
     */
    public static void topPadding(final Activity a, final View target, final float extraDp) {
        final Context c = a;
        Runnable apply = new Runnable() {
            @Override
            public void run() {
                int top = Screen.topInset(a, extraDp);
                target.setPadding(target.getPaddingLeft(), top,
                        target.getPaddingRight(), target.getPaddingBottom());
            }
        };
        apply.run();
        target.post(apply);
    }
}

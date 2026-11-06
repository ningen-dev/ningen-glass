package com.ningen.browser.core;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 线程与主线程投递的统一入口，避免各处各自 new Handler。 */
public final class Threads {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "ningen-io");
        t.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
        return t;
    });

    private Threads() {}

    public static void main(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    public static void mainDelayed(Runnable r, long delayMs) {
        MAIN.postDelayed(r, delayMs);
    }

    public static void io(Runnable r) {
        IO.execute(r);
    }

    public static void cancelMain(Runnable r) {
        MAIN.removeCallbacks(r);
    }
}

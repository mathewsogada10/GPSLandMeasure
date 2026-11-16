package com.mogada.landmeasure;

import android.app.Application;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LandApp extends Application {
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    Store store;
    volatile String activeId;
    volatile int accepted;
    volatile CaptureRules.Decision lastDecision;
    volatile String failure;
    volatile boolean initialized;
    List<String> recovered = List.of();

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
        io.execute(() -> {
            try {
                recovered = store.initialize();
                initialized = true;
            } catch (Exception e) { failure = "DATABASE_UNAVAILABLE"; }
            publish();
        });
    }

    interface Work<T> { T run() throws Exception; }
    interface Done<T> { void accept(T value); }
    interface Failed { void accept(Exception error); }

    <T> void run(Work<T> work, Done<T> done, Failed failed) {
        io.execute(() -> {
            try {
                T value = work.run();
                main.post(() -> done.accept(value));
            } catch (Exception e) { main.post(() -> failed.accept(e)); }
        });
    }

    void publish() { main.post(() -> { for (Runnable listener : listeners) listener.run(); }); }

    void feedback() {
        run(() -> new boolean[]{"true".equals(store.setting("haptic", "true")),
                "true".equals(store.setting("audio", "false"))}, flags -> {
            if (flags[0]) {
                Vibrator vibrator = getSystemService(Vibrator.class);
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26)
                        vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE));
                    else vibrateLegacy(vibrator);
                }
            }
            if (flags[1]) {
                ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40);
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 80);
                main.postDelayed(tone::release, 150);
            }
        }, error -> { failure = "FEEDBACK_SETTINGS_FAILED"; publish(); });
    }

    @SuppressWarnings("deprecation")
    private static void vibrateLegacy(Vibrator vibrator) { vibrator.vibrate(45); }
}

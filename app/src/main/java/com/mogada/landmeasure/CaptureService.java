package com.mogada.landmeasure;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

public final class CaptureService extends Service implements LocationListener {
    static final String START = "com.mogada.landmeasure.START";
    static final String PAUSE = "com.mogada.landmeasure.PAUSE";
    static final String MARK = "com.mogada.landmeasure.MARK";
    static volatile Location latestFix;
    private static final String CHANNEL = "active_measurement";
    private static final int NOTICE = 1;
    private LandApp app;
    private LocationManager manager;
    private String id;
    private boolean accepting;
    private long checkpoint;

    @Override public void onCreate() {
        super.onCreate();
        app = (LandApp) getApplication();
        manager = getSystemService(LocationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (PAUSE.equals(action)) {
            pause("PAUSED");
        } else if (MARK.equals(action)) {
            Location fix = selectedFix(intent);
            if (accepting && fix != null) process(fix, true, intent.getBooleanExtra("override", false));
        } else if (START.equals(action) && !accepting && id == null) {
            id = intent.getStringExtra("id");
            if (id == null) { stopSelf(); return START_NOT_STICKY; }
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                        || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    throw new SecurityException("Precise GPS unavailable");
                startForeground(NOTICE, notification(R.string.capture_starting));
                String activityId = id;
                app.run(() -> { app.store.start(activityId); return null; }, ignored -> {
                    if (id == null) return;
                    accepting = true;
                    checkpoint = SystemClock.elapsedRealtime();
                    try {
                        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
                        app.lastDecision = null;
                        app.accepted = 0;
                        app.activeId = activityId;
                        app.main.post(tick);
                        updateNotification(R.string.capture_running);
                        app.feedback();
                        app.publish();
                    } catch (SecurityException | IllegalArgumentException e) {
                        failAndPause("LOCATION_UNAVAILABLE");
                    }
                }, error -> failAndPause("START_FAILED"));
            } catch (SecurityException | IllegalStateException e) {
                failAndPause("START_FAILED");
            }
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Location selectedFix(Intent intent) {
        return Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("fix", Location.class)
                : intent.getParcelableExtra("fix");
    }

    private Notification notification(int text) {
        Intent open = new Intent(this, MainActivity.class).putExtra("id", id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent pause = new Intent(this, CaptureService.class).setAction(PAUSE);
        PendingIntent pauseIntent = PendingIntent.getService(this, 1, pause,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_land).setContentTitle(getString(R.string.capture_notification))
                .setContentText(getString(text)).setContentIntent(content).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.pause), pauseIntent).build())
                .build();
    }

    private void updateNotification(int text) {
        getSystemService(NotificationManager.class).notify(NOTICE, notification(text));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!accepting) return;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                failAndPause("LOCATION_REVOKED"); return;
            }
            long now = SystemClock.elapsedRealtime();
            long delta = now - checkpoint; checkpoint = now;
            String activityId = id;
            app.run(() -> { app.store.duration(activityId, delta); return null; },
                    ignored -> {}, error -> failAndPause("STORAGE_WRITE_FAILED"));
            app.main.postDelayed(this, 5000);
        }
    };

    @Override public void onLocationChanged(Location location) {
        if (!accepting) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            failAndPause("LOCATION_REVOKED"); return;
        }
        latestFix = new Location(location);
        String activityId = id;
        app.run(() -> app.store.load(activityId).mode, mode -> {
            if (accepting && "AUTO".equals(mode)) process(location, false, false);
            else app.publish();
        }, error -> failAndPause("STORAGE_READ_FAILED"));
    }

    @SuppressWarnings("deprecation")
    static Models.Point point(Location location, boolean override) {
        return new Models.Point(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : Double.NaN, location.getTime(),
                location.getElapsedRealtimeNanos(), "GPS", override, location.isFromMockProvider());
    }

    private void process(Location location, boolean manual, boolean override) {
        if (!accepting) return;
        String activityId = id;
        Models.Point point = point(location, override);
        long now = SystemClock.elapsedRealtimeNanos();
        app.run(() -> {
            Models.Record current = app.store.load(activityId);
            if (current.status != Models.Status.IN_PROGRESS) return null;
            if (!current.points.isEmpty() && point.time < current.points.get(current.points.size() - 1).time)
                app.store.event(activityId, "CLOCK_ANOMALY");
            return app.store.accept(activityId, point, now, manual, override);
        }, decision -> {
            if (decision == null) return;
            app.lastDecision = decision;
            if (decision == CaptureRules.Decision.ACCEPT) { app.accepted++; app.feedback(); }
            if (decision == CaptureRules.Decision.LIMIT) pause("POINT_LIMIT_REACHED");
            app.publish();
        }, error -> failAndPause("STORAGE_WRITE_FAILED"));
    }

    private void failAndPause(String code) {
        app.failure = code;
        pause(code);
    }

    private void pause(String reason) {
        boolean wasAccepting = accepting;
        accepting = false;
        manager.removeUpdates(this);
        app.main.removeCallbacks(tick);
        String activityId = id;
        long delta = wasAccepting ? SystemClock.elapsedRealtime() - checkpoint : 0;
        id = null; latestFix = null; app.activeId = null;
        if (activityId != null) {
            app.run(() -> {
                app.store.duration(activityId, delta);
                app.store.pause(activityId, reason);
                return null;
            }, ignored -> { app.feedback(); app.publish(); }, error -> {
                app.failure = "STORAGE_WRITE_FAILED"; app.publish();
            });
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) failAndPause("GPS_DISABLED");
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onDestroy() {
        if (id != null) pause("SERVICE_STOPPED");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

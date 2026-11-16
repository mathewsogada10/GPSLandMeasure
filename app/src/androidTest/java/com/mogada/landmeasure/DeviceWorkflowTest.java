package com.mogada.landmeasure;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class DeviceWorkflowTest {
    private Instrumentation instrumentation;
    private LandApp app;
    private MainActivity activity;

    @Before public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        app = (LandApp) instrumentation.getTargetContext().getApplicationContext();
        await(() -> app.initialized, "Database initialized");
        app.io.submit(() -> {
            app.store.getWritableDatabase().delete("activity", null, null);
            app.store.putSetting("onboarding", "true");
        }).get(10, TimeUnit.SECONDS);
    }

    @After public void cleanup() throws Exception {
        instrumentation.runOnMainSync(() -> {
            app.stopService(new Intent(app, CaptureService.class));
            if (activity != null) activity.finish();
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition condition, String reason) throws Exception {
        long end = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < end) {
            if (condition.ready()) return;
            Thread.sleep(100);
        }
        fail("Timed out: " + reason);
    }

    private String create(String mode) throws Exception {
        return app.io.submit(() -> {
            String id = app.store.create();
            app.store.saveMetadata(id, "Device test parcel", new JSONObject().put("usage", "agricultural")
                    .put("location", "Offline fixture"), Models.AreaUnit.ACRE, mode);
            return id;
        }).get(10, TimeUnit.SECONDS);
    }

    private Models.Record load(String id) throws Exception {
        return app.io.submit(() -> app.store.load(id)).get(10, TimeUnit.SECONDS);
    }

    private void open(String id) {
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(app, MainActivity.class)
                .putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private View find(View view, String text) {
        if (view instanceof TextView textView && text.contentEquals(textView.getText())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean has(int resource) {
        boolean[] found = {false};
        instrumentation.runOnMainSync(() ->
                found[0] = find(activity.getWindow().getDecorView(), app.getString(resource)) != null);
        return found[0];
    }

    private void click(int resource) throws Exception {
        await(() -> has(resource), "Button " + app.getString(resource));
        instrumentation.runOnMainSync(() -> {
            View view = find(activity.getWindow().getDecorView(), app.getString(resource));
            assertNotNull(view); assertTrue(view.isEnabled()); view.performClick();
        });
        instrumentation.waitForIdleSync();
    }

    @Test public void offlineReviewCompleteAndReopenResultOnDevice() throws Exception {
        String id = create("MANUAL");
        app.io.submit(() -> {
            app.store.start(id);
            double[][] values = {{0, 36}, {0, 36.001}, {0.001, 36.001}, {0.001, 36}};
            for (int i = 0; i < values.length; i++) {
                long nanos = (i * 60L + 1) * 1000000000;
                Models.Point point = new Models.Point(values[i][0], values[i][1], 5,
                        i * 60000L, nanos, "GPS", false, false);
                app.store.accept(id, point, nanos, true, false);
            }
            app.store.pause(id, "PAUSED");
            return null;
        }).get(10, TimeUnit.SECONDS);
        open(id);
        click(R.string.review);
        await(() -> has(R.string.review_confirm), "Review acknowledgement");
        instrumentation.runOnMainSync(() -> {
            checkAll(activity.getWindow().getDecorView());
            View save = find(activity.getWindow().getDecorView(), app.getString(R.string.save_completed));
            assertNotNull(save); assertTrue(save.isEnabled()); save.performClick();
        });
        await(() -> load(id).status == Models.Status.COMPLETED, "Atomic completion");
        await(() -> has(R.string.final_estimate), "Saved result displayed");
        assertTrue(load(id).area > 12000);
        assertEquals(4, load(id).points.size());
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.INTERNET));
        click(R.string.share);
        assertNotNull(app.getString(R.string.share_privacy));
    }

    private void checkAll(View view) {
        if (view instanceof CheckBox box) box.setChecked(true);
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) checkAll(group.getChildAt(i));
    }

    @Test public void foregroundCaptureAcceptsGpsAndStopsOnPause() throws Exception {
        shell("pm grant com.mogada.landmeasure android.permission.ACCESS_COARSE_LOCATION");
        shell("pm grant com.mogada.landmeasure android.permission.ACCESS_FINE_LOCATION");
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant com.mogada.landmeasure android.permission.POST_NOTIFICATIONS");
        shell("appops set com.mogada.landmeasure android:mock_location allow");
        String id = create("AUTO");
        open(id); await(() -> has(R.string.start), "Visible activity");
        LocationManager manager = app.getSystemService(LocationManager.class);
        try {
            addProvider(manager);
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            instrumentation.runOnMainSync(() -> {
                Intent intent = new Intent(app, CaptureService.class).setAction(CaptureService.START).putExtra("id", id);
                if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent);
                else activity.startService(intent);
            });
            await(() -> id.equals(app.activeId), "Foreground capture started");
            sendFix(manager, 0, 36);
            await(() -> load(id).points.size() == 1, "First GPS point checkpointed");
            Thread.sleep(1500);
            sendFix(manager, 0, 36.00003);
            await(() -> load(id).points.size() == 2, "Movement GPS point checkpointed");
            instrumentation.runOnMainSync(() -> activity.startService(
                    new Intent(app, CaptureService.class).setAction(CaptureService.PAUSE)));
            await(() -> load(id).status == Models.Status.PAUSED, "Pause persisted");
            sendFix(manager, 0.00003, 36.00003);
            Thread.sleep(1200);
            assertEquals(2, load(id).points.size());
            assertNull(app.activeId);
            assertTrue(load(id).mockCount > 0);
        } finally { manager.removeTestProvider(LocationManager.GPS_PROVIDER); }
    }

    @Test public void draftEditorAutosavesUnicodeAndReopensSameRecord() throws Exception {
        String id = create("MANUAL");
        open(id); click(R.string.edit_metadata);
        await(() -> has(R.string.title_label), "Editor loaded");
        instrumentation.runOnMainSync(() -> {
            EditText title = findInput(activity.getWindow().getDecorView(), app.getString(R.string.title_label));
            assertNotNull(title); title.setText("Mũthoni's shamba");
        });
        await(() -> "Mũthoni's shamba".equals(load(id).title), "Draft autosaved");
        click(R.string.save_draft);
        await(() -> has(R.string.edit_metadata), "Same draft reopened");
        assertEquals(Models.Status.DRAFT, load(id).status);
        assertEquals(1, app.io.submit(() -> app.store.list("", "all", "updated").size()).get().intValue());
    }

    private EditText findInput(View view, String description) {
        if (view instanceof EditText edit && description.contentEquals(edit.getContentDescription())) return edit;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findInput(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void combinedRecordFiltersAndRecoveryWorkWithDeviceSqlite() throws Exception {
        String id = create("MANUAL");
        app.io.submit(() -> {
            Models.Record r = app.store.load(id);
            r.metadata.put("county", "Kiambu").put("owner", "Njeri");
            app.store.saveMetadata(id, r.title, r.metadata, r.unit, r.mode);
            app.store.start(id);
            long nanos = SystemClock.elapsedRealtimeNanos();
            Models.Point point = new Models.Point(0, 36, 5, System.currentTimeMillis(), nanos, "GPS", false, false);
            app.store.accept(id, point, nanos, true, false);
            assertEquals(1, app.store.initialize().size());
            assertEquals(Models.Status.PAUSED, app.store.load(id).status);
            assertEquals(1, app.store.load(id).points.size());
            assertEquals(1, app.store.list("njeri", "all", "name", new Store.RecordFilter(
                    "PAUSED", "agricultural", "ACRE", "kiambu", false, 0, Long.MAX_VALUE)).size());
            return null;
        }).get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("deprecation")
    private void addProvider(LocationManager manager) {
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
    }

    private void sendFix(LocationManager manager, double latitude, double longitude) {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(latitude); location.setLongitude(longitude); location.setAccuracy(5);
        location.setTime(System.currentTimeMillis()); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setAltitude(1100); location.setSpeed(2); location.setBearing(90);
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) { /* Drain until the command exits. */ }
        }
    }
}

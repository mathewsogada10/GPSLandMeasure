package com.mogada.landmeasure;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.widget.Spinner;
import android.widget.Button;
import android.widget.ScrollView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            app.store.getWritableDatabase().delete("setting", "key=?", new String[]{"theme"});
            app.theme = AppTheme.SYSTEM;
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
        return hasText(app.getString(resource));
    }

    private boolean hasText(String text) {
        boolean[] found = {false};
        instrumentation.runOnMainSync(() ->
                found[0] = find(activity.getWindow().getDecorView(), text) != null);
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
        app.io.submit(() -> {
            app.store.getWritableDatabase().execSQL("UPDATE activity SET completed=1577934240000,updated=1893542640000 WHERE id=?",
                    new Object[]{id}); return null;
        }).get(10, TimeUnit.SECONDS);
        click(R.string.back);
        click(R.string.filters);
        setAreaFilter(R.string.area_equal, "1.2309", R.string.unit_ha);
        popupClick(R.string.confirm);
        await(() -> has(R.string.records_next), "Compact completed result");
        instrumentation.runOnMainSync(() -> {
            View card = pointButton(activity.getWindow().getDecorView(), "Device test parcel");
            assertNotNull(card);
            String summary = ((Button) card).getText().toString();
            assertTrue(summary.contains("2020")); assertFalse(summary.contains("2030"));
            assertTrue(summary.contains("GPS quality:")); assertTrue(summary.contains("Size:"));
            card.performClick();
        });
        click(R.string.navigate_to_land);
        await(() -> has(R.string.maps_point_hint), "Selectable saved waypoints");
        instrumentation.runOnMainSync(() -> {
            View point = pointButton(activity.getWindow().getDecorView(), "Point 3");
            assertNotNull(point); assertTrue(point.performClick());
        });
        await(() -> dialogHas(app.getString(R.string.open_maps)), "Maps confirmation");
        assertTrue(dialogHas("Open directions to Point 3?"));
        android.view.accessibility.AccessibilityNodeInfo dialog = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(dialog);
        assertTrue(dialog.findAccessibilityNodeInfosByText(app.getString(R.string.cancel)).get(0)
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
        click(R.string.back);
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
        app.io.submit(() -> {
            Models.Record r = app.store.load(id);
            r.metadata.put("county", "Kiambu").put("reference", "00123").put("reference_source", "document")
                    .put("anchor_lat", -1.2).put("anchor_lon", 36.8);
            app.store.saveMetadata(id, r.title, r.metadata, r.unit, r.mode); return null;
        }).get(10, TimeUnit.SECONDS);
        open(id); click(R.string.edit_metadata);
        await(() -> has(R.string.title_label), "Editor loaded");
        instrumentation.runOnMainSync(() -> {
            EditText title = findInput(activity.getWindow().getDecorView(), app.getString(R.string.title_label));
            assertNotNull(title); title.setText("Mũthoni's shamba");
            View root = activity.getWindow().getDecorView();
            assertEquals(6, count(root, EditText.class));
            assertEquals(1, count(root, Spinner.class));
            for (int label : new int[]{R.string.owner, R.string.phone, R.string.email, R.string.consent, R.string.location})
                assertNotNull(findInput(root, app.getString(label)));
            assertNull(findInput(root, app.getString(R.string.county)));
            assertNull(findInput(root, app.getString(R.string.reference)));
        });
        await(() -> "Mũthoni's shamba".equals(load(id).title), "Draft autosaved");
        click(R.string.save_draft);
        await(() -> has(R.string.edit_metadata), "Same draft reopened");
        assertEquals(Models.Status.DRAFT, load(id).status);
        Models.Record saved = load(id);
        assertEquals("Kiambu", saved.metadata.getString("county"));
        assertEquals("00123", saved.metadata.getString("reference"));
        assertEquals("document", saved.metadata.getString("reference_source"));
        assertEquals(-1.2, saved.metadata.getDouble("anchor_lat"), 0);
        assertEquals(Models.AreaUnit.ACRE, saved.unit);
        assertEquals("MANUAL", saved.mode);
        assertEquals(1, app.io.submit(() -> app.store.list("", "all", "updated").size()).get().intValue());
    }

    private int count(View view, Class<? extends View> type) {
        int total = type.isInstance(view) ? 1 : 0;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) total += count(group.getChildAt(i), type);
        return total;
    }

    private View pointButton(View view, String prefix) {
        if (view instanceof android.widget.Button button && button.getText().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = pointButton(group.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean dialogHas(String text) {
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        return root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty();
    }

    private Spinner themeSpinner(View view) {
        if (view instanceof Spinner spinner && app.getString(R.string.display_theme).contentEquals(spinner.getContentDescription()))
            return spinner;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Spinner found = themeSpinner(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void awaitRecreated(MainActivity previous) throws Exception {
        await(() -> {
            MainActivity[] current = {null};
            instrumentation.runOnMainSync(() -> {
                for (android.app.Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (candidate instanceof MainActivity main && main != previous) current[0] = main;
            });
            if (current[0] == null) return false;
            activity = current[0]; return true;
        }, "Activity recreated for display mode");
        await(() -> has(R.string.save_settings), "Settings restored after recreation");
    }

    private boolean darkDisplay() {
        int[] background = {0};
        instrumentation.runOnMainSync(() -> background[0] = new AppTheme.Palette(activity).background);
        return Color.luminance(background[0]) < 0.2f;
    }

    private void selectTheme(int index) throws Exception {
        instrumentation.runOnMainSync(() -> {
            Spinner spinner = themeSpinner(activity.getWindow().getDecorView());
            assertNotNull(spinner); spinner.setSelection(index);
        });
        MainActivity previous = activity;
        click(R.string.save_settings); awaitRecreated(previous);
    }

    @Test public void phoneThemeIsDefaultAndManualOverridesPersist() throws Exception {
        boolean systemSwitch = Build.VERSION.SDK_INT >= 29;
        if (systemSwitch) {
            shell("cmd uimode night yes");
            await(() -> (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES, "Phone dark mode");
        }
        try {
            open(create("MANUAL"));
            await(() -> has(R.string.edit_metadata), "Initial record");
            assertEquals((app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES, darkDisplay());
            click(R.string.back); openSettings();
            await(() -> has(R.string.save_settings), "Settings loaded");
            instrumentation.runOnMainSync(() ->
                    assertEquals(0, themeSpinner(activity.getWindow().getDecorView()).getSelectedItemPosition()));
            selectTheme(1); assertFalse(darkDisplay());
            assertEquals(AppTheme.LIGHT, app.io.submit(() -> app.store.setting("theme", AppTheme.SYSTEM)).get());
            selectTheme(2); assertTrue(darkDisplay());
            instrumentation.runOnMainSync(() -> activity.recreate());
            MainActivity previous = activity;
            awaitRecreated(previous); assertTrue(darkDisplay());
            assertEquals(AppTheme.DARK, app.io.submit(() -> app.store.setting("theme", AppTheme.SYSTEM)).get());
            selectTheme(0);
            if (systemSwitch) {
                previous = activity; shell("cmd uimode night no"); awaitRecreated(previous); assertFalse(darkDisplay());
                previous = activity; shell("cmd uimode night yes"); awaitRecreated(previous); assertTrue(darkDisplay());
            }
        } finally {
            if (systemSwitch) {
                instrumentation.runOnMainSync(() -> { if (activity != null) activity.finish(); });
                instrumentation.waitForIdleSync(); activity = null;
                shell("cmd uimode night no");
            }
        }
    }

    private View described(View root, int resource) {
        if (android.text.TextUtils.equals(app.getString(resource), root.getContentDescription())) return root;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), resource);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void popupClick(int resource) throws Exception {
        await(() -> dialogHas(app.getString(resource)), "Popup item " + app.getString(resource));
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(root);
        android.view.accessibility.AccessibilityNodeInfo item = root.findAccessibilityNodeInfosByText(app.getString(resource)).get(0);
        while (item != null && !item.isClickable()) item = item.getParent();
        assertNotNull(item);
        assertTrue(item.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
    }

    private void openSettings() throws Exception {
        instrumentation.runOnMainSync(() -> {
            View more = described(activity.getWindow().getDecorView(), R.string.more_options);
            assertNotNull(more); assertTrue(more.performClick());
        });
        popupClick(R.string.settings);
        await(() -> has(R.string.save_settings), "Settings opened from overflow");
    }

    private android.view.accessibility.AccessibilityNodeInfo accessibleDescription(
            android.view.accessibility.AccessibilityNodeInfo node, int resource) {
        if (android.text.TextUtils.equals(app.getString(resource), node.getContentDescription())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            android.view.accessibility.AccessibilityNodeInfo found = accessibleDescription(child, resource);
            if (found != null) return found;
        }
        return null;
    }

    private void setAreaFilter(int operator, String amount, int unit) throws Exception {
        await(() -> dialogHas(app.getString(R.string.area_filter)), "Area filter dialog");
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(root);
        assertTrue(root.findAccessibilityNodeInfosByText(app.getString(R.string.county)).isEmpty());
        android.view.accessibility.AccessibilityNodeInfo comparison = accessibleDescription(root, R.string.area_filter);
        assertNotNull(comparison);
        assertTrue(comparison.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
        popupClick(operator);
        root = instrumentation.getUiAutomation().getRootInActiveWindow();
        android.view.accessibility.AccessibilityNodeInfo input = accessibleDescription(root, R.string.area_threshold);
        assertNotNull(input);
        android.os.Bundle value = new android.os.Bundle();
        value.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, amount);
        assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, value));
        android.view.accessibility.AccessibilityNodeInfo units = accessibleDescription(root, R.string.area_filter_unit);
        assertNotNull(units);
        assertTrue(units.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
        popupClick(unit);
    }

    private void listFixtures() throws Exception {
        app.io.submit(() -> {
            for (int i = 1; i <= 23; i++) {
                String id = app.store.create();
                app.store.saveMetadata(id, String.format(Locale.ROOT, "List fixture %03d", i),
                        new JSONObject().put("usage", "agricultural").put("owner", "Hidden owner")
                                .put("location", "Hidden location").put("land", "Hidden parcel")
                                .put("county", "Hidden county"), Models.AreaUnit.ACRE, "MANUAL");
                app.store.getWritableDatabase().execSQL("UPDATE activity SET created=1577934240000,updated=? WHERE id=?",
                        new Object[]{1893542640000L + i * 1000, id});
            }
            app.store.putSetting("sort", "name"); return null;
        }).get(10, TimeUnit.SECONDS);
        open(null);
        awaitPage(1, 10, 23, 1, 3);
    }

    private void awaitPage(int first, int last, int total, int page, int count) throws Exception {
        await(() -> hasText(app.getString(R.string.records_page, first, last, total, page, count)), "Activity page " + page);
    }

    private List<String> listCards(View root) {
        List<String> cards = new ArrayList<>();
        if (root instanceof Button button && button.getText().toString().startsWith("List fixture "))
            cards.add(button.getText().toString());
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) cards.addAll(listCards(group.getChildAt(i)));
        return cards;
    }

    private ScrollView scrollView(View root) {
        if (root instanceof ScrollView scroll) return scroll;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = scrollView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void recordsUseTenItemPagesCompactCardsAndFixedOverflow() throws Exception {
        listFixtures();
        instrumentation.runOnMainSync(() -> {
            List<String> cards = listCards(activity.getWindow().getDecorView());
            assertEquals(10, cards.size()); assertTrue(cards.get(0).startsWith("List fixture 001\n"));
            for (String card : cards) {
                assertTrue(card.contains("Drafted ")); assertTrue(card.contains("2020")); assertFalse(card.contains("2030"));
                assertTrue(card.contains(app.getString(R.string.record_not_measured)));
                assertTrue(card.contains(app.getString(R.string.record_gps_unassessed)));
                assertFalse(card.contains("Hidden ")); assertFalse(card.contains("agricultural"));
            }
            assertFalse(find(activity.getWindow().getDecorView(), app.getString(R.string.records_previous)).isEnabled());
        });
        click(R.string.records_next); awaitPage(11, 20, 23, 2, 3);
        instrumentation.runOnMainSync(() -> assertEquals(10, listCards(activity.getWindow().getDecorView()).size()));
        click(R.string.records_next); awaitPage(21, 23, 23, 3, 3);
        instrumentation.runOnMainSync(() -> {
            assertEquals(3, listCards(activity.getWindow().getDecorView()).size());
            assertFalse(find(activity.getWindow().getDecorView(), app.getString(R.string.records_next)).isEnabled());
        });
        click(R.string.records_previous); awaitPage(11, 20, 23, 2, 3);
        MainActivity previous = activity;
        instrumentation.runOnMainSync(() -> activity.recreate());
        await(() -> {
            instrumentation.runOnMainSync(() -> {
                for (android.app.Activity current : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (current instanceof MainActivity main && main != previous) activity = main;
            });
            return activity != previous;
        }, "Paged list restored after recreation");
        awaitPage(11, 20, 23, 2, 3);
        instrumentation.runOnMainSync(() -> scrollView(activity.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            View more = described(root, R.string.more_options);
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue(scrollView(root).getScrollY() > 0);
            assertTrue(more.getGlobalVisibleRect(visible)); assertTrue(visible.height() > 0);
        });
        openSettings();
        click(R.string.back); awaitPage(11, 20, 23, 2, 3);
    }

    @Test public void clearFiltersResetsSortSearchViewAdvancedCriteriaAndPage() throws Exception {
        listFixtures();
        click(R.string.records_next); awaitPage(11, 20, 23, 2, 3);
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            ((Spinner) described(root, R.string.record_sort)).setSelection(2);
            ((Spinner) described(root, R.string.record_view)).setSelection(1);
        });
        awaitPage(1, 10, 23, 1, 3);
        click(R.string.filters);
        setAreaFilter(R.string.area_greater, "1", R.string.unit_acre);
        popupClick(R.string.confirm);
        await(() -> has(R.string.no_matches), "Advanced criteria applied");
        instrumentation.runOnMainSync(() ->
                findInput(activity.getWindow().getDecorView(), app.getString(R.string.search)).setText("No such title"));
        click(R.string.clear_filters);
        awaitPage(1, 10, 23, 1, 3);
        instrumentation.runOnMainSync(() -> {
            View view = activity.getWindow().getDecorView();
            assertEquals(0, ((Spinner) described(view, R.string.record_sort)).getSelectedItemPosition());
            assertEquals(0, ((Spinner) described(view, R.string.record_view)).getSelectedItemPosition());
            assertEquals("", findInput(view, app.getString(R.string.search)).getText().toString());
            assertTrue(listCards(view).get(0).startsWith("List fixture 023\n"));
        });
        assertEquals("updated", app.io.submit(() -> app.store.setting("sort", "")).get());
        Thread.sleep(400);
        awaitPage(1, 10, 23, 1, 3);
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
                    "PAUSED", "agricultural", Store.AreaComparison.ANY, 0, Models.AreaUnit.ACRE,
                    false, 0, Long.MAX_VALUE)).size());
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

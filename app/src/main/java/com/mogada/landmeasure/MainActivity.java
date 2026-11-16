package com.mogada.landmeasure;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends ComponentActivity implements LocationListener {
    private static final int GREEN = 0xFF174E3C, INK = 0xFF17362D, MUTED = 0xFF52645C;
    private static final int[] STATUS_LABELS = {R.string.status_draft, R.string.status_ready,
            R.string.status_active, R.string.status_paused, R.string.status_completed,
            R.string.status_cancelled, R.string.status_archived};
    private static final int[] DECISION_LABELS = {R.string.decision_accept, R.string.decision_stale,
            R.string.decision_accuracy, R.string.decision_duplicate, R.string.decision_time,
            R.string.decision_movement, R.string.decision_speed, R.string.decision_jump,
            R.string.decision_limit, R.string.decision_invalid};
    private static final int[] USAGE_LABELS = {R.string.usage_agricultural, R.string.usage_residential,
            R.string.usage_commercial, R.string.usage_conservation, R.string.usage_mixed, R.string.usage_other};
    private static final String[] VIEW_KEYS = {"all", "draft", "completed", "archive", "deleted"};
    private static final String[] SORT_KEYS = {"updated", "name", "area", "status", "measured", "oldest", "name_desc", "area_asc"};
    private LandApp app;
    private LocationManager locations;
    private LinearLayout content;
    private ScrollView scroll;
    private String page = "home", id, search = "", view = "all", sort = "updated", lastFailure;
    private Models.Record record;
    private int pointOffset;
    private boolean foreground, loading, binding, editorDirty, anchorMode;
    private Location fix;
    private TextView readinessText, saveState;
    private Button startButton;
    private EditText titleInput;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private Spinner unitInput, modeInput, usageInput, sourceInput;
    private JSONObject editorMetadata;
    private Runnable afterPause;
    private Store.RecordFilter recordFilter = Store.RecordFilter.none();
    private final List<AlertDialog> dialogs = new ArrayList<>();

    private final class DialogBuilder extends AlertDialog.Builder {
        DialogBuilder() { super(MainActivity.this); }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialogs.add(dialog);
            dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
            return dialog;
        }
    }

    private final Runnable changed = () -> {
        if (!foreground) return;
        if (app.failure != null && !app.failure.equals(lastFailure)) {
            lastFailure = app.failure;
            new DialogBuilder().setTitle(R.string.error_title)
                    .setMessage(app.initialized ? getString(R.string.service_failure, app.failure)
                            : getString(R.string.database_failure))
                    .setPositiveButton(android.R.string.ok, null).show();
        }
        if (afterPause != null && app.activeId == null) {
            Runnable next = afterPause; afterPause = null; next.run(); return;
        }
        if (app.initialized && content == null) launchInitial();
        else if ("detail".equals(page) && !loading) loadDetail(false);
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        app = (LandApp) getApplication();
        locations = getSystemService(LocationManager.class);
        if (state != null) {
            id = state.getString("id"); page = state.getString("page", "home");
            search = state.getString("search", ""); view = state.getString("view", "all");
            pointOffset = state.getInt("pointOffset");
        } else if (getIntent().hasExtra("id")) {
            id = getIntent().getStringExtra("id"); page = "detail";
        }
        app.listeners.add(changed);
        if (app.initialized) launchInitial();
        else {
            LinearLayout waiting = new LinearLayout(this);
            waiting.setPadding(dp(24), dp(64), dp(24), dp(24));
            TextView label = new TextView(this); label.setText(R.string.waiting); waiting.addView(label);
            setContentView(waiting);
        }
    }

    private void launchInitial() {
        app.run(() -> new String[]{app.store.setting("onboarding", "false"),
                app.store.setting("sort", "updated")}, values -> {
            if (isFinishing() || isDestroyed()) return;
            sort = values[1];
            if (!"true".equals(values[0])) onboarding();
            else if (id != null && !"home".equals(page) && !"settings".equals(page)) {
                if ("editor".equals(page)) editor(id); else loadDetail(false);
            } else home();
            if (!app.recovered.isEmpty() && "true".equals(values[0])) {
                String recoveredId = app.recovered.get(0);
                app.recovered = List.of();
                new DialogBuilder().setTitle(R.string.recovery_title).setMessage(R.string.recovery_body)
                        .setPositiveButton(R.string.open_recovered, (dialog, which) -> open(recoveredId))
                        .setNegativeButton(android.R.string.ok, null).show();
            }
        }, this::error);
    }

    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if ("readiness".equals(page)) beginReadiness();
        changed.run();
    }

    @Override protected void onPause() {
        if ("editor".equals(page) && editorDirty) saveEditor(null);
        foreground = false; stopReadiness();
        super.onPause();
    }

    @Override protected void onDestroy() {
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        app.listeners.remove(changed); app.main.removeCallbacks(autoSave);
        stopReadiness();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if ("editor".equals(page) && editorDirty) saveEditor(null);
        state.putString("id", id); state.putString("page", page);
        state.putString("search", search); state.putString("view", view);
        state.putInt("pointOffset", pointOffset);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.hasExtra("id")) open(intent.getStringExtra("id"));
    }

    private void navigateBack() {
        if ("editor".equals(page)) saveEditor(() -> open(id));
        else if ("home".equals(page) || "onboarding".equals(page)) finish();
        else if ("points".equals(page) || "review".equals(page) || "history".equals(page)
                || "readiness".equals(page)) open(id);
        else home();
    }

    private void screen(String next, int heading, boolean back) {
        stopReadiness();
        page = next;
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(0xFFF5F7F1);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else applyLegacyInsets(v, insets);
            return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(16), dp(8));
        if (back) {
            Button backButton = button(R.string.back, this::navigateBack);
            header.addView(backButton);
        }
        TextView brand = text(getString(R.string.app_name), 24, GREEN);
        brand.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1);
        brandParams.setMarginStart(dp(10)); header.addView(brand, brandParams);
        shell.addView(header);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(32));
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell); shell.requestApplyInsets();
        if (heading != 0) heading(getString(heading));
    }

    @SuppressWarnings("deprecation")
    private void applyLegacyInsets(View v, WindowInsets insets) {
        v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
    }

    private void onboarding() {
        screen("onboarding", R.string.welcome, false);
        note(R.string.tagline); gap(12); body(R.string.onboarding); gap(12);
        warning(getString(R.string.disclaimer)); body(R.string.safety);
        primary(R.string.agree, () -> app.run(() -> {
            app.store.putSetting("onboarding", "true");
            app.store.putSetting("disclaimer_version", "1.0");
            app.store.putSetting("disclaimer_at", Long.toString(System.currentTimeMillis()));
            return null;
        }, ignored -> home(), this::error));
    }

    private void home() {
        id = null; record = null;
        screen("home", R.string.your_measurements, false);
        note(R.string.local_only);
        primary(R.string.new_measurement, () -> app.run(app.store::create, this::editor, this::error));
        EditText query = input(getString(R.string.search), search, 200);
        content.addView(query);
        LinearLayout filters = row();
        Spinner views = spinner(new int[]{R.string.all_records, R.string.work_in_progress,
                R.string.completed_records, R.string.archive, R.string.recently_deleted}, indexOf(VIEW_KEYS, view));
        Spinner sorts = spinner(new int[]{R.string.sort_updated, R.string.sort_name, R.string.sort_area,
                R.string.sort_status, R.string.sort_measured, R.string.sort_oldest,
                R.string.sort_name_desc, R.string.sort_area_asc}, indexOf(SORT_KEYS, sort));
        filters.addView(views, weighted()); filters.addView(sorts, weighted()); content.addView(filters);
        secondary(R.string.filters, this::filterDialog);
        secondary(R.string.clear_filters, () -> {
            recordFilter = Store.RecordFilter.none(); search = ""; view = "all"; home();
        });
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);
        Runnable refresh = () -> {
            search = query.getText().toString();
            view = VIEW_KEYS[views.getSelectedItemPosition()];
            sort = SORT_KEYS[sorts.getSelectedItemPosition()];
            String wantedSearch = search, wantedView = view, wantedSort = sort;
            app.run(() -> {
                app.store.putSetting("sort", wantedSort);
                return app.store.list(wantedSearch, wantedView, wantedSort, recordFilter);
            }, records -> {
                if (!"home".equals(page) || !wantedSearch.equals(search) || !wantedView.equals(view)) return;
                results.removeAllViews();
                if (records.isEmpty()) {
                    TextView empty = text(getString(search.isEmpty() ? R.string.no_records : R.string.no_matches), 21, INK);
                    results.addView(empty);
                    if (search.isEmpty()) results.addView(text(getString(R.string.no_records_body), 16, MUTED));
                }
                for (Models.Record r : records) {
                    String title = r.title.isBlank() ? getString(R.string.new_title) : r.title;
                    String summary = title + "\n" + status(r) + " · " + date(r.updated)
                            + "\n" + r.metadata.optString("location");
                    if (!r.metadata.optString("owner").isBlank()) summary += "\n" + r.metadata.optString("owner");
                    if (!r.metadata.optString("land").isBlank()) summary += "\n" + r.metadata.optString("land");
                    if (!r.metadata.optString("usage").isBlank()) summary += " · " + metadataValue("usage", r.metadata.optString("usage"));
                    summary += "\n" + (r.hasResult && (r.status == Models.Status.COMPLETED
                            || r.status == Models.Status.ARCHIVED) ? area(r.area, r.unit) + " · " + resultQuality(r)
                            : getString(R.string.quality_not_recorded));
                    Button card = button(summary, () -> open(r.id)); card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    card.setBackground(cardBackground()); results.addView(card, spaced());
                }
                if (records.size() == 500) results.addView(text(getString(R.string.showing_limit), 14, MUTED));
            }, this::error);
        };
        query.addTextChangedListener(watcher(() -> {
            app.main.removeCallbacks(searchRefresh); searchRefresh = refresh; app.main.postDelayed(searchRefresh, 250);
        }));
        selection(views, refresh); selection(sorts, refresh);
        secondary(R.string.settings, this::settings);
        refresh.run();
    }

    private Runnable searchRefresh = () -> {};

    private void editor(String activityId) {
        id = activityId;
        app.run(() -> app.store.load(activityId), r -> {
            if (isFinishing() || isDestroyed()) return;
            record = r; editorDirty = false; binding = true;
            screen("editor", R.string.metadata, true); fields.clear();
            try { editorMetadata = new JSONObject(r.metadata.toString()); }
            catch (JSONException e) { error(e); return; }
            body(R.string.draft_hint);
            titleInput = labeledInput(R.string.title_label, r.title, 200);
            titleInput.addTextChangedListener(watcher(this::dirty));
            for (int i = 0; i < Fields.KEYS.length; i++) {
                String key = Fields.KEYS[i];
                if ("usage".equals(key)) {
                    label(R.string.usage);
                    usageInput = spinner(USAGE_LABELS, indexOf(Fields.USAGES, r.metadata.optString("usage")));
                    content.addView(usageInput); selection(usageInput, this::dirty);
                } else if ("reference_source".equals(key)) {
                    label(R.string.reference_source);
                    sourceInput = spinner(new int[]{R.string.source_user, R.string.source_document, R.string.source_unknown},
                            indexOf(new String[]{"user", "document", "unknown"}, r.metadata.optString(key)));
                    content.addView(sourceInput); selection(sourceInput, this::dirty);
                } else {
                    EditText field = labeledInput(Fields.LABELS[i], r.metadata.optString(key), Fields.limit(key));
                    if ("email".equals(key)) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                    if ("phone".equals(key)) field.setInputType(InputType.TYPE_CLASS_PHONE);
                    if (Fields.limit(key) > 200) field.setMinLines(2);
                    fields.put(key, field); field.addTextChangedListener(watcher(this::dirty));
                }
            }
            label(R.string.unit);
            unitInput = spinner(unitLabels(), r.unit.ordinal()); content.addView(unitInput); selection(unitInput, this::dirty);
            label(R.string.capture_mode);
            modeInput = spinner(new int[]{R.string.mode_auto, R.string.mode_manual}, "MANUAL".equals(r.mode) ? 1 : 0);
            modeInput.setEnabled(r.version == 0); content.addView(modeInput); selection(modeInput, this::dirty);
            body(R.string.mode_hint);
            if (r.metadata.has("anchor_lat")) {
                body(getString(R.string.anchor_detail, number(r.metadata.optDouble("anchor_lat"), 7),
                        number(r.metadata.optDouble("anchor_lon"), 7), number(r.metadata.optDouble("anchor_accuracy"), 1)));
                secondary(R.string.remove_anchor, () -> {
                    editorMetadata.remove("anchor_lat"); editorMetadata.remove("anchor_lon");
                    editorMetadata.remove("anchor_accuracy"); editorMetadata.remove("anchor_time");
                    dirty(); saveEditor(() -> editor(id));
                });
            }
            secondary(R.string.anchor, () -> saveEditor(() -> readiness(true)));
            saveState = body(R.string.saved);
            secondary(R.string.retry, () -> saveEditor(null));
            primary(R.string.save_draft, () -> saveEditor(() -> open(id)));
            if (r.status == Models.Status.DRAFT) primary(R.string.save_ready, () -> {
                if (validateEditor(true)) saveEditor(() -> app.run(() -> { app.store.markReady(id); return null; },
                        ignored -> open(id), this::error));
            });
            binding = false;
        }, this::error);
    }

    private final Runnable autoSave = () -> {
        if ("editor".equals(page) && editorDirty) saveEditor(null);
    };
    private void dirty() {
        if (binding) return;
        editorDirty = true;
        if (saveState != null) saveState.setText(R.string.saving);
        app.main.removeCallbacks(autoSave); app.main.postDelayed(autoSave, 600);
    }

    private boolean validateEditor(boolean required) {
        boolean valid = true;
        EditText email = fields.get("email"), phone = fields.get("phone");
        if (email != null && !email.getText().toString().isBlank()
                && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.getText().toString().trim()).matches()) {
            email.setError(getString(R.string.invalid_email)); valid = false;
        }
        if (phone != null && !phone.getText().toString().isBlank()
                && !phone.getText().toString().matches("[+()0-9 .-]{5,40}")) {
            phone.setError(getString(R.string.invalid_phone)); valid = false;
        }
        if (required) {
            if (titleInput.getText().toString().isBlank()) { titleInput.setError(getString(R.string.required)); valid = false; }
            EditText location = fields.get("location");
            if (location != null && location.getText().toString().isBlank() && !editorMetadata.has("anchor_lat")) {
                location.setError(getString(R.string.required)); valid = false;
            }
        }
        return valid;
    }

    private void saveEditor(Runnable next) {
        app.main.removeCallbacks(autoSave);
        if (titleInput == null || editorMetadata == null) return;
        boolean validContacts = validateEditor(false);
        String activityId = id, title = titleInput.getText().toString().trim();
        JSONObject data;
        try {
            data = new JSONObject(editorMetadata.toString());
            for (Map.Entry<String, EditText> field : fields.entrySet())
                data.put(field.getKey(), field.getValue().getText().toString().trim());
            data.put("usage", Fields.USAGES[usageInput.getSelectedItemPosition()]);
            data.put("reference_source", new String[]{"user", "document", "unknown"}[sourceInput.getSelectedItemPosition()]);
        } catch (JSONException e) { error(e); return; }
        Models.AreaUnit unit = Models.AreaUnit.values()[unitInput.getSelectedItemPosition()];
        String mode = modeInput.getSelectedItemPosition() == 0 ? "AUTO" : "MANUAL";
        editorDirty = false;
        if (saveState != null) saveState.setText(R.string.saving);
        app.run(() -> { app.store.saveMetadata(activityId, title, data, unit, mode); return null; }, ignored -> {
            if ("editor".equals(page) && saveState != null && !editorDirty) saveState.setText(R.string.saved);
            if (next != null && validContacts) next.run();
        }, error -> {
            editorDirty = true;
            if (saveState != null) saveState.setText(R.string.save_failed);
            error(error);
        });
    }

    private void open(String activityId) {
        if (activityId == null) { home(); return; }
        id = activityId; page = "detail"; loadDetail(false);
    }

    private void loadDetail(boolean review) {
        if (id == null || loading) return;
        loading = true;
        String activityId = id;
        String requestedPage = page;
        app.run(() -> {
            Models.Record r = app.store.load(activityId);
            Geometry.Result result = r.points.size() >= 3 ? Geometry.calculate(r.points) : null;
            String distanceUnit = app.store.setting("distance", "m");
            return new Detail(r, result, distanceUnit);
        }, value -> {
            loading = false;
            if (!activityId.equals(id) || !requestedPage.equals(page)) {
                if (id != null && ("detail".equals(page) || "review".equals(page)))
                    loadDetail("review".equals(page));
                return;
            }
            record = value.record; distancePreference = value.distanceUnit;
            if (review) renderReview(record, value.geometry); else renderDetail(record, value.geometry);
        }, error -> { loading = false; error(error); });
    }

    private record Detail(Models.Record record, Geometry.Result geometry, String distanceUnit) {}
    private String distancePreference = "m";

    private void renderDetail(Models.Record r, Geometry.Result geometry) {
        int position = "detail".equals(page) && scroll != null ? scroll.getScrollY() : 0;
        screen("detail", 0, true);
        note(getString(R.string.local_only));
        heading(r.title.isBlank() ? getString(R.string.new_title) : r.title);
        body(status(r) + " · " + date(r.updated));
        if (r.deletedAt > 0) {
            warning(getString(R.string.purge_date, date(r.purgeAfter)));
            primary(R.string.restore, () -> action("restore", () -> open(id)));
            secondary(R.string.purge, () -> confirm(R.string.purge_confirm, () -> action("purge", this::home)));
        }
        boolean finalResult = r.hasResult && (r.status == Models.Status.COMPLETED || r.status == Models.Status.ARCHIVED);
        if (finalResult || r.openGeometry()) {
            note(finalResult ? R.string.final_estimate : R.string.provisional);
            if (finalResult) hero(area(r.area, r.unit));
            else if (geometry != null && geometry.valid()) hero(area(geometry.area(), r.unit));
            else if (geometry != null) warning(geometryMessage(geometry));
            double perimeter = finalResult ? r.perimeter : geometry != null && geometry.valid() ? geometry.perimeter() : 0;
            body(getString(R.string.metrics, r.points.size(), distance(perimeter), duration(r.duration)));
            Location currentFix = CaptureService.latestFix;
            if (r.status == Models.Status.IN_PROGRESS) {
                body(currentFix == null ? getString(R.string.acquiring) : fixText(currentFix));
                if (app.lastDecision != null) body(DECISION_LABELS[app.lastDecision.ordinal()]);
            }
        }
        if (!r.points.isEmpty()) canvas(r.points);
        if (finalResult) {
            body(resultQuality(r));
            body(getString(R.string.quality_stats, number(r.best, 1), number(r.median, 1), number(r.worst, 1)));
            body(getString(R.string.quality_counts, r.rejected, r.overrides, getString(r.mockCount > 0 ? R.string.yes : R.string.no)));
            note(Geometry.ALGORITHM);
        } else if (r.version > 0) {
            body(getString(R.string.quality_counts, r.rejected, r.overrides, getString(r.mockCount > 0 ? R.string.yes : R.string.no)));
        }
        if (r.points.size() >= 9900) warning(getString(R.string.near_point_limit));
        if (r.editable() && r.deletedAt == 0) {
            if (r.status == Models.Status.IN_PROGRESS) {
                primary(R.string.pause, this::pauseCapture);
                secondary(R.string.mark, this::markPoint);
            } else if (r.status == Models.Status.DRAFT || r.status == Models.Status.READY
                    || r.status == Models.Status.PAUSED) {
                primary(r.status == Models.Status.PAUSED ? R.string.resume : R.string.start, () -> readiness(false));
            }
            if (r.openGeometry()) {
                LinearLayout operations = row();
                operations.addView(button(R.string.undo, () -> undo(false)), weighted());
                operations.addView(button(R.string.redo, () -> undo(true)), weighted()); content.addView(operations);
                secondary(R.string.review, () -> {
                    if (r.status == Models.Status.IN_PROGRESS) {
                        pauseCapture(this::showReview);
                    } else showReview();
                });
                secondary(R.string.cancel_attempt, this::cancelOptions);
            }
            if (r.status == Models.Status.COMPLETED) {
                primary(R.string.share, this::share);
                secondary(R.string.revise, () -> confirm(R.string.revise_confirm,
                        () -> app.run(() -> { app.store.revise(id); return null; }, ignored -> showReview(), this::error)));
            }
            if (r.status == Models.Status.CANCELLED)
                secondary(R.string.discard_capture, () -> confirm(R.string.confirm_discard, () -> action("discard", () -> editor(id))));
            secondary(R.string.edit_metadata, () -> editor(id));
        }
        if (!r.points.isEmpty()) secondary(R.string.points, () -> { pointOffset = 0; showPoints(); });
        if (r.status != Models.Status.IN_PROGRESS && r.deletedAt == 0) {
            secondary(R.string.duplicate, () -> app.run(() -> {
                String copy = app.store.duplicate(id);
                Models.Record duplicate = app.store.load(copy);
                String copyTitle = getString(R.string.copy_title, duplicate.title);
                if (copyTitle.length() > 200) copyTitle = copyTitle.substring(0, 200);
                app.store.saveMetadata(copy, copyTitle, duplicate.metadata, duplicate.unit, duplicate.mode);
                return copy;
            }, this::editor, this::error));
            secondary(r.status == Models.Status.ARCHIVED ? R.string.restore_archive : R.string.archive_action,
                    () -> action(r.status == Models.Status.ARCHIVED ? "restoreArchive" : "archive", () -> open(id)));
            secondary(R.string.delete_action, () -> confirm(R.string.delete_confirm, () -> action("delete", this::home)));
        }
        secondary(R.string.history, this::showHistory);
        gap(10); label(R.string.metadata);
        for (int i = 0; i < Fields.KEYS.length; i++) {
            String value = r.metadata.optString(Fields.KEYS[i]);
            if (!value.isBlank()) body(getString(Fields.LABELS[i]) + ": " + metadataValue(Fields.KEYS[i], value));
        }
        body(getString(R.string.record_provenance, date(r.created), date(r.updated), r.version));
        warning(getString(R.string.disclaimer));
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void pauseCapture() {
        pauseCapture(() -> open(id));
    }

    private void pauseCapture(Runnable next) {
        if (app.activeId != null) {
            afterPause = next;
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.PAUSE));
        } else app.run(() -> { app.store.pause(id, "PAUSED"); return null; }, ignored -> next.run(), this::error);
    }

    private void markPoint() {
        Location current = CaptureService.latestFix;
        if (current == null || age(current) > 10) { message(R.string.acquiring); return; }
        double threshold = record.config.accuracy();
        boolean warning = current.getAccuracy() > threshold;
        boolean movementWarning = app.lastDecision == CaptureRules.Decision.SPEED
                || app.lastDecision == CaptureRules.Decision.JUMP;
        String description = fixText(current);
        if (warning || movementWarning) description += "\n\n" + getString(R.string.override_warning);
        final boolean override = warning || movementWarning;
        new DialogBuilder().setTitle(R.string.manual_confirm).setMessage(description)
                .setPositiveButton(override ? R.string.accept_override : R.string.confirm, (dialog, which) ->
                        startService(new Intent(this, CaptureService.class).setAction(CaptureService.MARK)
                                .putExtra("fix", new Location(current))
                                .putExtra("override", override)))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void readiness(boolean anchor) {
        app.failure = null; lastFailure = null;
        anchorMode = anchor;
        if (record == null) return;
        if (!anchor && !Store.ready(record.title, record.metadata)) {
            message(R.string.draft_hint); editor(id); return;
        }
        if (!anchor && app.activeId != null) {
            message(R.string.other_active); open(app.activeId); return;
        }
        screen("readiness", R.string.readiness, true);
        body(R.string.safety); body(R.string.readiness_hint);
        if (!locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) warning(getString(R.string.gps_unavailable));
        readinessText = body(R.string.acquiring);
        secondary(R.string.grant_permission, this::requestPrecise);
        secondary(R.string.open_settings, () -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        secondary(R.string.location_settings, () -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        startButton = primary(anchor ? R.string.save_anchor : R.string.start_now, () -> {
            if (!readyForCapture()) { updateReadiness(); return; }
            Location chosen = new Location(fix);
            if (anchorMode) {
                app.run(() -> {
                    Models.Record r = app.store.load(id);
                    r.metadata.put("anchor_lat", chosen.getLatitude()).put("anchor_lon", chosen.getLongitude())
                            .put("anchor_accuracy", chosen.getAccuracy()).put("anchor_time", chosen.getTime());
                    app.store.saveMetadata(id, r.title, r.metadata, r.unit, r.mode);
                    return null;
                }, ignored -> editor(id), this::error);
            } else {
                Intent service = new Intent(this, CaptureService.class).setAction(CaptureService.START).putExtra("id", id);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
                    fix = null; open(id);
                } catch (IllegalStateException | SecurityException e) { error(e); }
            }
        });
        startButton.setEnabled(false);
        beginReadiness();
    }

    private void requestPrecise() {
        new DialogBuilder().setTitle(R.string.readiness).setMessage(R.string.permission_rationale)
                .setPositiveButton(R.string.grant_permission, (dialog, which) -> {
                    List<String> permissions = new ArrayList<>();
                    permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                    if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
                    requestPermissions(permissions.toArray(new String[0]), 10);
                }).setNegativeButton(R.string.cancel, null).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if ("readiness".equals(page)) beginReadiness();
    }

    private boolean precise() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean gps() {
        return locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)
                && locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void beginReadiness() {
        if (!"readiness".equals(page)) return;
        stopReadiness();
        if (precise() && gps()) {
            try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this); }
            catch (SecurityException e) { error(e); }
        }
        app.main.post(readinessTick);
    }

    private void stopReadiness() {
        if (locations != null) locations.removeUpdates(this);
        if (app != null) app.main.removeCallbacks(readinessTick);
    }

    private final Runnable readinessTick = new Runnable() {
        @Override public void run() {
            if (!foreground || !"readiness".equals(page)) return;
            updateReadiness(); app.main.postDelayed(this, 1000);
        }
    };

    private boolean readyForCapture() {
        return precise() && gps() && fix != null && fix.hasAccuracy() && age(fix) >= 0 && age(fix) <= 10
                && fix.getAccuracy() <= (record == null ? 10 : record.config.accuracy())
                && new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes() >= 2L * 1024 * 1024;
    }

    private void updateReadiness() {
        if (readinessText == null || !"readiness".equals(page)) return;
        boolean storage = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes() >= 2L * 1024 * 1024;
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? 100 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = scale > 0 ? level * 100 / scale : -1;
        PowerManager power = getSystemService(PowerManager.class);
        boolean thermal = Build.VERSION.SDK_INT >= 29
                && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
        String distanceLine = "";
        if (fix != null && record != null && !record.points.isEmpty()) {
            Models.Point last = record.points.get(record.points.size() - 1);
            distanceLine = getString(R.string.distance_to_last,
                    distance(Geometry.distance(last, CaptureService.point(fix, false))));
        }
        readinessText.setText(getString(R.string.readiness_checklist,
                getString(R.string.readiness_permission, getString(precise() ? R.string.pass : R.string.block)),
                getString(R.string.readiness_provider, getString(gps() ? R.string.pass : R.string.block)),
                getString(R.string.readiness_fix, fix == null ? getString(R.string.acquiring) : fixText(fix)),
                getString(R.string.readiness_storage, getString(storage ? R.string.pass : R.string.block)),
                getString(R.string.readiness_battery, percent, getString(
                        percent < 15 || power.isPowerSaveMode() || thermal ? R.string.battery_warning : R.string.battery_ok)),
                distanceLine));
        startButton.setEnabled(readyForCapture());
    }

    @Override public void onLocationChanged(Location location) {
        fix = new Location(location);
        if ("readiness".equals(page)) updateReadiness();
    }
    @Override public void onProviderDisabled(String provider) { if ("readiness".equals(page)) updateReadiness(); }
    @Override public void onProviderEnabled(String provider) { if ("readiness".equals(page)) beginReadiness(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private void showReview() {
        if (id == null) return;
        page = "review"; loadDetail(true);
    }

    private void renderReview(Models.Record r, Geometry.Result geometry) {
        screen("review", R.string.review, true);
        if (r.status != Models.Status.PAUSED || r.deletedAt > 0) { open(id); return; }
        canvas(r.points);
        if (geometry == null || !geometry.valid()) warning(geometryMessage(
                geometry == null ? Geometry.calculate(r.points) : geometry));
        else {
            note(R.string.provisional); hero(area(geometry.area(), r.unit));
            body(getString(R.string.metrics, r.points.size(), distance(geometry.perimeter()), duration(r.duration)));
            body(getString(R.string.closure, distance(geometry.closure())));
        }
        body(getString(R.string.quality_counts, r.rejected, r.overrides, getString(r.mockCount > 0 ? R.string.yes : R.string.no)));
        secondary(R.string.points, () -> { pointOffset = 0; showPoints(); });
        LinearLayout operations = row();
        operations.addView(button(R.string.undo, () -> undo(false)), weighted());
        operations.addView(button(R.string.redo, () -> undo(true)), weighted()); content.addView(operations);
        body(R.string.undo_limit);
        CheckBox closure = null;
        if (geometry != null && geometry.valid() && geometry.closure() > r.config.closure()) {
            closure = check(getString(R.string.closure_confirm, distance(r.config.closure())), false);
            content.addView(closure);
        }
        if (geometry != null && geometry.valid() && geometry.area() < 100) warning(getString(R.string.small_warning));
        warning(getString(R.string.disclaimer));
        CheckBox reviewed = check(getString(R.string.review_confirm), false); content.addView(reviewed);
        CheckBox closureCheck = closure;
        Button complete = primary(R.string.save_completed, () -> {
            if (!reviewed.isChecked() || closureCheck != null && !closureCheck.isChecked()) return;
            app.run(() -> { app.store.complete(id); return null; }, ignored -> { app.feedback(); open(id); }, this::error);
        });
        complete.setEnabled(false);
        Runnable enable = () -> complete.setEnabled(geometry != null && geometry.valid()
                && reviewed.isChecked() && (closureCheck == null || closureCheck.isChecked()));
        reviewed.setOnCheckedChangeListener((button, checked) -> enable.run());
        if (closure != null) closure.setOnCheckedChangeListener((button, checked) -> enable.run());
    }

    private void showPoints() {
        String activityId = id;
        app.run(() -> app.store.load(activityId), r -> {
            record = r; screen("points", R.string.points, true); body(R.string.undo_limit);
            Geometry.Result geometry = Geometry.calculate(r.points);
            if (!geometry.valid()) warning(geometryMessage(geometry));
            int end = Math.min(r.points.size(), pointOffset + 50);
            if (pointOffset >= end) pointOffset = Math.max(0, (r.points.size() - 1) / 50 * 50);
            end = Math.min(r.points.size(), pointOffset + 50);
            body(getString(R.string.point_page, Math.min(pointOffset + 1, end), end, r.points.size()));
            for (int i = pointOffset; i < end; i++) {
                Models.Point point = r.points.get(i);
                String value = getString(R.string.point_row, i + 1, number(point.lat, 7), number(point.lon, 7),
                        number(point.accuracy, 1), date(point.time), point.override ? getString(R.string.override_flag) : "");
                int index = i;
                if (r.status == Models.Status.PAUSED && r.editable())
                    content.addView(button(value, () -> pointActions(index)), spaced());
                else body(value);
            }
            if (pointOffset > 0) secondary(R.string.previous_page, () -> { pointOffset -= 50; showPoints(); });
            if (end < r.points.size()) secondary(R.string.next_page, () -> { pointOffset += 50; showPoints(); });
            if (r.status == Models.Status.PAUSED) {
                secondary(R.string.undo, () -> undo(false)); secondary(R.string.redo, () -> undo(true));
                primary(R.string.review, this::showReview);
            }
        }, this::error);
    }

    private void pointActions(int index) {
        String[] actions = {getString(R.string.edit_coordinates), getString(R.string.replace_gps),
                getString(R.string.move_up), getString(R.string.move_down), getString(R.string.delete_point)};
        new DialogBuilder().setTitle(getString(R.string.point_actions, index + 1)).setItems(actions, (dialog, which) -> {
            if (which == 0) editPoint(index);
            else if (which == 1) {
                Location chosen = fix;
                if (chosen == null || !chosen.hasAccuracy() || age(chosen) > 10 || age(chosen) < 0) {
                    message(R.string.gps_edit_hint); readiness(false); return;
                }
                String warning = fixText(chosen) + "\n\n" + getString(R.string.override_warning);
                new DialogBuilder().setMessage(warning).setPositiveButton(R.string.accept_override, (d, w) -> {
                    List<Models.Point> points = new ArrayList<>(record.points);
                    Models.Point old = points.get(index);
                    points.set(index, new Models.Point(old.id, chosen.getLatitude(), chosen.getLongitude(),
                            chosen.getAccuracy(), old.time, old.nanos, "GPS_EDIT", true, CaptureService.point(chosen, true).mock));
                    persistPoints(points);
                }).setNegativeButton(R.string.cancel, null).show();
            } else {
                List<Models.Point> points = new ArrayList<>(record.points);
                if (which == 2 && index > 0) Collections.swap(points, index, index - 1);
                if (which == 3 && index < points.size() - 1) Collections.swap(points, index, index + 1);
                if (which == 4) points.remove(index);
                persistPoints(points);
            }
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void editPoint(int index) {
        Models.Point old = record.points.get(index);
        LinearLayout form = dialogLayout();
        EditText latitude = input(getString(R.string.latitude), number(old.lat, 8), 30);
        EditText longitude = input(getString(R.string.longitude), number(old.lon, 8), 30);
        latitude.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        longitude.setInputType(latitude.getInputType());
        form.addView(text(getString(R.string.latitude), 14, INK)); form.addView(latitude);
        form.addView(text(getString(R.string.longitude), 14, INK)); form.addView(longitude);
        form.addView(text(getString(R.string.edited_accuracy), 14, MUTED));
        AlertDialog dialog = new DialogBuilder().setTitle(R.string.edit_coordinates).setView(form)
                .setPositiveButton(R.string.confirm, null).setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                double lat = parse(latitude.getText().toString()), lon = parse(longitude.getText().toString());
                if (!Double.isFinite(lat) || Math.abs(lat) > 90) { latitude.setError(getString(R.string.invalid_number)); return; }
                if (!Double.isFinite(lon) || Math.abs(lon) > 180) { longitude.setError(getString(R.string.invalid_number)); return; }
                List<Models.Point> points = new ArrayList<>(record.points);
                points.set(index, new Models.Point(old.id, lat, lon, old.accuracy, old.time, old.nanos, "EDIT", true, old.mock));
                persistPoints(points); dialog.dismiss();
            } catch (java.text.ParseException e) { latitude.setError(getString(R.string.invalid_number)); }
        }));
        dialog.show();
    }

    private void persistPoints(List<Models.Point> points) {
        app.run(() -> { app.store.editPoints(id, points); return null; }, ignored -> showPoints(), this::error);
    }

    private void undo(boolean redo) {
        String previousPage = page;
        app.run(() -> { app.store.undo(id, redo); return null; }, ignored -> {
            if ("points".equals(previousPage)) showPoints();
            else if ("review".equals(previousPage)) showReview();
            else open(id);
        }, this::error);
    }

    private void cancelOptions() {
        Runnable choose = () -> new DialogBuilder().setTitle(R.string.cancel_attempt)
                .setItems(new String[]{getString(R.string.keep_paused), getString(R.string.discard_capture),
                        getString(R.string.mark_cancelled)}, (dialog, which) -> {
                    if (which == 0) open(id);
                    else if (which == 1) confirm(R.string.confirm_discard, () -> action("discard", () -> editor(id)));
                    else confirm(R.string.confirm_cancel, () -> action("cancel", () -> open(id)));
                }).setNegativeButton(R.string.cancel, null).show();
        if (record.status == Models.Status.IN_PROGRESS) {
            pauseCapture(choose);
        } else choose.run();
    }

    private void showHistory() {
        app.run(() -> app.store.history(id), entries -> {
            screen("history", R.string.history, true);
            for (Store.History entry : entries)
                body(getString(R.string.history_row, date(entry.time()), getString(eventLabel(entry.type())), entry.version()));
        }, this::error);
    }

    private void share() {
        Models.Record r = record;
        LinearLayout form = dialogLayout();
        CheckBox coordinates = check(getString(R.string.include_coordinates), false);
        TextView preview = text(shareText(r, false), 15, INK);
        form.addView(text(getString(R.string.share_privacy), 15, MUTED));
        form.addView(coordinates); form.addView(preview);
        coordinates.setOnCheckedChangeListener((button, checked) -> preview.setText(shareText(r, checked)));
        ScrollView container = new ScrollView(this); container.addView(form);
        new DialogBuilder().setTitle(R.string.share_preview).setView(container)
                .setPositiveButton(R.string.share, (dialog, which) -> {
                    String payload = shareText(r, coordinates.isChecked());
                    app.run(() -> { app.store.event(r.id, coordinates.isChecked()
                            ? "TEXT_SHARE_WITH_COORDINATES" : "TEXT_SHARE"); return null; }, ignored -> {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload);
                        try {
                            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)));
                        } catch (android.content.ActivityNotFoundException e) { message(R.string.error_generic); }
                    }, this::error);
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private String shareText(Models.Record r, boolean coordinates) {
        String value = getString(R.string.share_body, r.title, area(r.area, r.unit), distance(r.perimeter),
                r.points.size(), duration(r.duration), resultQuality(r), Geometry.ALGORITHM, getString(R.string.disclaimer));
        if (coordinates) {
            StringBuilder builder = new StringBuilder(value).append(getString(R.string.share_coordinates));
            for (Models.Point p : r.points) builder.append('\n').append(p.lat).append(", ").append(p.lon);
            value = builder.toString();
        }
        return value;
    }

    private void settings() {
        app.run(() -> new String[]{app.store.setting("unit", "ACRE"), app.store.setting("mode", "AUTO"),
                app.store.setting("accuracy", "10"), app.store.setting("movement", "3"),
                app.store.setting("haptic", "true"), app.store.setting("audio", "false"),
                app.store.setting("distance", "m")}, values -> {
            screen("settings", R.string.settings, true);
            label(R.string.default_unit);
            Spinner units = spinner(unitLabels(), Models.AreaUnit.valueOf(values[0]).ordinal()); content.addView(units);
            label(R.string.default_mode);
            Spinner mode = spinner(new int[]{R.string.mode_auto, R.string.mode_manual}, "MANUAL".equals(values[1]) ? 1 : 0);
            content.addView(mode);
            EditText accuracy = labeledInput(R.string.accuracy_threshold, number(Double.parseDouble(values[2]), 1), 8);
            EditText movement = labeledInput(R.string.movement_threshold, number(Double.parseDouble(values[3]), 1), 8);
            accuracy.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            movement.setInputType(accuracy.getInputType());
            label(R.string.distance_unit);
            Spinner distances = spinner(new int[]{R.string.metres, R.string.kilometres}, "km".equals(values[6]) ? 1 : 0);
            content.addView(distances);
            CheckBox haptic = check(getString(R.string.haptic), "true".equals(values[4])); content.addView(haptic);
            CheckBox audio = check(getString(R.string.audio), "true".equals(values[5])); content.addView(audio);
            body(R.string.settings_hint);
            primary(R.string.save_settings, () -> {
                double maxAccuracy, minMovement;
                try {
                    maxAccuracy = parse(accuracy.getText().toString()); minMovement = parse(movement.getText().toString());
                    new CaptureRules.Config(maxAccuracy, minMovement, 1, 3.5, 5000, 10000, 10);
                    if (!Double.isFinite(maxAccuracy) || !Double.isFinite(minMovement)) throw new IllegalArgumentException();
                } catch (java.text.ParseException | IllegalArgumentException e) {
                    accuracy.setError(getString(R.string.invalid_number)); movement.setError(getString(R.string.invalid_number)); return;
                }

                String selectedUnit = Models.AreaUnit.values()[units.getSelectedItemPosition()].name();
                String selectedMode = mode.getSelectedItemPosition() == 0 ? "AUTO" : "MANUAL";
                String selectedDistance = distances.getSelectedItemPosition() == 0 ? "m" : "km";
                boolean hapticEnabled = haptic.isChecked(), audioEnabled = audio.isChecked();
                app.run(() -> {
                    app.store.putSetting("unit", selectedUnit); app.store.putSetting("mode", selectedMode);
                    app.store.putSetting("accuracy", Double.toString(maxAccuracy));
                    app.store.putSetting("movement", Double.toString(minMovement));
                    app.store.putSetting("distance", selectedDistance);
                    app.store.putSetting("haptic", Boolean.toString(hapticEnabled));
                    app.store.putSetting("audio", Boolean.toString(audioEnabled)); return null;
                }, ignored -> { message(R.string.saved); home(); }, this::error);
            });
            secondary(R.string.reset_settings, () -> app.run(() -> {
                app.store.putSetting("accuracy", "10"); app.store.putSetting("movement", "3");
                app.store.putSetting("mode", "AUTO"); return null;
            }, ignored -> settings(), this::error));
            secondary(R.string.recently_deleted, () -> { view = "deleted"; home(); });
            gap(12); body(R.string.privacy_body); warning(getString(R.string.disclaimer));
        }, this::error);
    }

    private void filterDialog() {
        LinearLayout form = dialogLayout();
        int[] statuses = new int[STATUS_LABELS.length + 1]; statuses[0] = R.string.any;
        System.arraycopy(STATUS_LABELS, 0, statuses, 1, STATUS_LABELS.length);
        int statusIndex = recordFilter.status().isEmpty() ? 0 : Models.Status.valueOf(recordFilter.status()).ordinal() + 1;
        Spinner status = spinner(statuses, statusIndex); form.addView(text(getString(R.string.sort_status), 14, INK)); form.addView(status);
        int[] usages = new int[USAGE_LABELS.length + 1]; usages[0] = R.string.any;
        System.arraycopy(USAGE_LABELS, 0, usages, 1, USAGE_LABELS.length);
        Spinner usage = spinner(usages, recordFilter.usage().isEmpty() ? 0 : indexOf(Fields.USAGES, recordFilter.usage()) + 1);
        form.addView(text(getString(R.string.usage), 14, INK)); form.addView(usage);
        int[] units = {R.string.any, R.string.unit_m2, R.string.unit_ha, R.string.unit_acre, R.string.unit_km2};
        Spinner unit = spinner(units, recordFilter.unit().isEmpty() ? 0 : Models.AreaUnit.valueOf(recordFilter.unit()).ordinal() + 1);
        form.addView(text(getString(R.string.unit), 14, INK)); form.addView(unit);
        EditText county = input(getString(R.string.county), recordFilter.county(), 200);
        form.addView(text(getString(R.string.county), 14, INK)); form.addView(county);
        EditText from = input(getString(R.string.from_date), isoDate(recordFilter.from()), 10);
        EditText until = input(getString(R.string.until_date), isoDate(recordFilter.until()), 10);
        form.addView(text(getString(R.string.from_date), 14, INK)); form.addView(from);
        form.addView(text(getString(R.string.until_date), 14, INK)); form.addView(until);
        CheckBox warnings = check(getString(R.string.warning_only), recordFilter.warnings()); form.addView(warnings);
        ScrollView container = new ScrollView(this); container.addView(form);
        AlertDialog dialog = new DialogBuilder().setTitle(R.string.filters).setView(container)
                .setPositiveButton(R.string.confirm, null).setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                long first = parseDate(from.getText().toString(), false);
                long last = parseDate(until.getText().toString(), true);
                if (first > last) throw new java.text.ParseException("", 0);
                recordFilter = new Store.RecordFilter(status.getSelectedItemPosition() == 0 ? ""
                        : Models.Status.values()[status.getSelectedItemPosition() - 1].name(),
                        usage.getSelectedItemPosition() == 0 ? "" : Fields.USAGES[usage.getSelectedItemPosition() - 1],
                        unit.getSelectedItemPosition() == 0 ? "" : Models.AreaUnit.values()[unit.getSelectedItemPosition() - 1].name(),
                        county.getText().toString().trim(), warnings.isChecked(), first, last);
                dialog.dismiss(); home();
            } catch (java.text.ParseException e) { from.setError(getString(R.string.filter_date_error)); }
        }));
        dialog.show();
    }

    private String isoDate(long value) {
        return value == 0 || value == Long.MAX_VALUE ? ""
                : new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(value));
    }

    private long parseDate(String value, boolean end) throws java.text.ParseException {
        if (value.isBlank()) return end ? Long.MAX_VALUE : 0;
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false);
        java.text.ParsePosition position = new java.text.ParsePosition(0);
        Date parsed = format.parse(value, position);
        if (parsed == null || position.getIndex() != value.length()) throw new java.text.ParseException(value, 0);
        java.util.Calendar calendar = java.util.Calendar.getInstance(); calendar.setTime(parsed);
        if (end) { calendar.add(java.util.Calendar.DAY_OF_MONTH, 1); calendar.add(java.util.Calendar.MILLISECOND, -1); }
        return calendar.getTimeInMillis();
    }

    private String metadataValue(String key, String value) {
        if ("usage".equals(key)) return getString(USAGE_LABELS[indexOf(Fields.USAGES, value)]);
        if ("reference_source".equals(key)) return getString(switch (value) {
            case "user" -> R.string.source_user; case "document" -> R.string.source_document; default -> R.string.source_unknown;
        });
        return value;
    }

    private String geometryMessage(Geometry.Result g) {
        return switch (g.error()) {
            case TOO_FEW -> getString(R.string.geometry_too_few, g.point());
            case INVALID_COORDINATE -> getString(R.string.geometry_invalid, g.point() + 1);
            case DUPLICATE -> getString(R.string.geometry_duplicate, g.point() + 1, g.other() + 1);
            case INTERSECTION -> getString(R.string.geometry_intersection, g.point() + 1, g.other() + 1);
            case ZERO_AREA -> getString(R.string.geometry_zero);
            case NONE -> "";
        };
    }

    private static int eventLabel(String type) {
        return switch (type) {
            case "CREATED" -> R.string.event_created;
            case "METADATA_SAVED", "METADATA_INCOMPLETE" -> R.string.event_metadata;
            case "READY" -> R.string.event_ready;
            case "CAPTURE_STARTED" -> R.string.event_started;
            case "PAUSED" -> R.string.event_paused;
            case "RECOVERED" -> R.string.event_recovered;
            case "POINT_ACCEPTED" -> R.string.event_point;
            case "POINT_OVERRIDE_CONFIRMED" -> R.string.event_override;
            case "POINTS_EDITED" -> R.string.event_edit;
            case "UNDO" -> R.string.event_undo;
            case "REDO" -> R.string.event_redo;
            case "GEOMETRY_REVISION_CREATED" -> R.string.event_revision;
            case "COMPLETED" -> R.string.event_complete;
            case "ARCHIVED" -> R.string.event_archive;
            case "RESTORED", "ARCHIVE_RESTORED" -> R.string.event_restore;
            case "SOFT_DELETED" -> R.string.event_delete;
            case "CANCELLED" -> R.string.event_cancel;
            case "CAPTURE_DISCARDED" -> R.string.event_discard;
            case "TEXT_SHARE", "TEXT_SHARE_WITH_COORDINATES" -> R.string.event_share;
            case "CLOCK_ANOMALY" -> R.string.event_clock;
            case "DUPLICATED_METADATA" -> R.string.event_duplicate;
            default -> R.string.event_warning;
        };
    }

    private void action(String action, Runnable next) {
        String activityId = id;
        app.run(() -> { app.store.disposition(activityId, action); return null; }, ignored -> next.run(), this::error);
    }

    private void confirm(int message, Runnable action) {
        new DialogBuilder().setMessage(message).setPositiveButton(R.string.confirm, (dialog, which) -> action.run())
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void error(Exception error) {
        if (isFinishing() || isDestroyed()) return;
        new DialogBuilder().setTitle(R.string.error_title).setMessage(R.string.error_generic)
                .setPositiveButton(android.R.string.ok, null).show();
    }
    private void message(int message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private String status(Models.Record r) { return getString(STATUS_LABELS[r.status.ordinal()]); }
    private String quality(double accuracy) { return getString(accuracy <= 10 ? R.string.good : accuracy <= 20 ? R.string.caution : R.string.poor); }
    private String resultQuality(Models.Record r) {
        return getString(R.string.result_quality, quality(r.worst), getString(
                r.mockCount > 0 ? R.string.simulated_warning : r.overrides > 0 ? R.string.overrides_warning : R.string.no_overrides));
    }
    private String area(double value, Models.AreaUnit unit) { return getString(R.string.area_value, number(value / unit.divisor, 4), getString(unit.label)); }
    private String distance(double value) {
        return "km".equals(distancePreference) ? getString(R.string.distance_km, number(value / 1000, 3))
                : getString(R.string.distance_m, number(value, 1));
    }
    private String duration(long millis) {
        long seconds = millis / 1000;
        return getString(R.string.duration, seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    private String date(long time) {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        return format.format(new Date(time)) + " " + java.util.TimeZone.getDefault().getDisplayName(
                java.util.TimeZone.getDefault().inDaylightTime(new Date(time)), java.util.TimeZone.SHORT);
    }
    private String number(double value, int decimals) {
        NumberFormat format = NumberFormat.getNumberInstance(); format.setGroupingUsed(false);
        format.setMaximumFractionDigits(decimals); return format.format(value);
    }
    private double parse(String value) throws java.text.ParseException {
        java.text.ParsePosition position = new java.text.ParsePosition(0);
        NumberFormat format = NumberFormat.getNumberInstance(); format.setGroupingUsed(false);
        Number parsed = format.parse(value.trim(), position);
        if (parsed == null || position.getIndex() != value.trim().length()) throw new java.text.ParseException(value, position.getIndex());
        return parsed.doubleValue();
    }
    private static long age(Location location) {
        return (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000000;
    }
    private String fixText(Location location) {
        return getString(R.string.fix_detail, number(location.getAccuracy(), 1), age(location),
                location.hasAccuracy() ? quality(location.getAccuracy()) : getString(R.string.unknown));
    }
    private int[] unitLabels() {
        return new int[]{R.string.unit_m2, R.string.unit_ha, R.string.unit_acre, R.string.unit_km2};
    }
    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    private void canvas(List<Models.Point> points) {
        content.addView(new BoundaryView(this, points), new LinearLayout.LayoutParams(-1, dp(260)));
        note(R.string.canvas_caption);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5)); return view;
    }
    private void heading(String value) {
        TextView title = text(value, 28, INK); title.setTypeface(null, Typeface.BOLD); content.addView(title);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    }
    private void hero(String value) {
        TextView text = text(value, 36, GREEN); text.setTypeface(null, Typeface.BOLD); content.addView(text);
    }
    private TextView body(int value) { return body(getString(value)); }
    private TextView body(String value) {
        TextView view = text(value, 16, INK); content.addView(view); return view;
    }
    private void note(int value) { note(getString(value)); }
    private void note(String value) { content.addView(text(value, 12, MUTED)); }
    private void label(int value) {
        TextView label = text(getString(value), 14, INK); label.setTypeface(null, Typeface.BOLD); content.addView(label);
    }
    private void warning(String value) {
        TextView warning = text(value, 15, 0xFF5C4514);
        warning.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(0xFFF3E8CA);
        background.setCornerRadius(dp(10)); warning.setBackground(background); content.addView(warning, spaced());
    }
    private Button button(int value, Runnable action) { return button(getString(value), action); }
    private Button button(String value, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false);
        button.setTextSize(16); button.setMinHeight(dp(52)); button.setTextColor(GREEN);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private Button primary(int value, Runnable action) {
        Button button = button(value, action);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(GREEN); shape.setCornerRadius(dp(12));
        button.setBackground(shape); button.setTextColor(Color.WHITE); button.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.addView(button, spaced()); return button;
    }
    private void secondary(int value, Runnable action) { content.addView(button(value, action), spaced()); }
    private GradientDrawable cardBackground() {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(12)); shape.setStroke(dp(1), 0xFFDCE4D8); return shape;
    }
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, dp(6)); return params;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row;
    }
    private void gap(int size) { View gap = new View(this); content.addView(gap, new LinearLayout.LayoutParams(1, dp(size))); }
    private EditText input(String hint, String value, int limit) {
        EditText input = new EditText(this); input.setText(value); input.setHint(hint);
        input.setContentDescription(hint); input.setTextColor(INK); input.setTextSize(16);
        input.setMinHeight(dp(52)); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        input.setSelectAllOnFocus(false); return input;
    }
    private EditText labeledInput(int label, String value, int limit) {
        label(label);
        EditText input = input(getString(label), value, limit);
        input.setId(View.generateViewId());
        View previous = content.getChildAt(content.getChildCount() - 1);
        previous.setLabelFor(input.getId()); content.addView(input); return input;
    }
    private Spinner spinner(int[] labels, int selected) {
        List<String> values = new ArrayList<>(); for (int label : labels) values.add(getString(label));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(this); spinner.setMinimumHeight(dp(52)); spinner.setAdapter(adapter);
        spinner.setSelection(selected); return spinner;
    }
    private void selection(Spinner spinner, Runnable action) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; }
                action.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    private CheckBox check(String label, boolean checked) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextSize(16); box.setTextColor(INK);
        box.setMinHeight(dp(52)); box.setChecked(checked); return box;
    }
    private LinearLayout dialogLayout() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(12), dp(24), dp(12)); return form;
    }
    private TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { action.run(); }
        };
    }
}

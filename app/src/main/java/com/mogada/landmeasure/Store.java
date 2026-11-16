package com.mogada.landmeasure;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class Store extends SQLiteOpenHelper {
    static final int SCHEMA = 1;
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    Store(Context context) {
        super(context, "landmeasure.db", null, SCHEMA);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE activity (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,"
                + "status TEXT NOT NULL CHECK(status IN ('DRAFT','READY','IN_PROGRESS','PAUSED',"
                + "'COMPLETED','CANCELLED','ARCHIVED')), prior_status TEXT, unit TEXT NOT NULL,"
                + "mode TEXT NOT NULL CHECK(mode IN ('AUTO','MANUAL')), metadata TEXT NOT NULL,"
                + "search_text TEXT NOT NULL,usage TEXT NOT NULL DEFAULT '',county TEXT NOT NULL DEFAULT '',"
                + "version INTEGER NOT NULL DEFAULT 0,"
                + "created INTEGER NOT NULL,updated INTEGER NOT NULL,completed INTEGER,"
                + "deleted INTEGER NOT NULL DEFAULT 0,purge_after INTEGER NOT NULL DEFAULT 0,"
                + "disclaimer_at INTEGER,disclaimer_version TEXT,app_version TEXT NOT NULL DEFAULT '1.0.0',"
                + "schema_version INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE UNIQUE INDEX single_active ON activity(status) WHERE status='IN_PROGRESS'");
        db.execSQL("CREATE INDEX activity_status_date ON activity(status,updated DESC)");
        db.execSQL("CREATE INDEX activity_deleted ON activity(deleted,purge_after)");
        db.execSQL("CREATE TABLE geometry (activity_id TEXT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,"
                + "version INTEGER NOT NULL,started INTEGER NOT NULL,duration INTEGER NOT NULL DEFAULT 0,"
                + "mode TEXT NOT NULL,config TEXT NOT NULL,rejected INTEGER NOT NULL DEFAULT 0,"
                + "reason TEXT NOT NULL DEFAULT '',mock INTEGER NOT NULL DEFAULT 0,"
                + "nanos_offset INTEGER NOT NULL DEFAULT 0,resume_pending INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(activity_id,version))");
        db.execSQL("CREATE TABLE waypoint (activity_id TEXT NOT NULL,version INTEGER NOT NULL,"
                + "seq INTEGER NOT NULL CHECK(seq>=0),id TEXT NOT NULL,lat REAL NOT NULL CHECK(lat BETWEEN -90 AND 90),"
                + "lon REAL NOT NULL CHECK(lon BETWEEN -180 AND 180),accuracy REAL NOT NULL CHECK(accuracy>=0),"
                + "time INTEGER NOT NULL,nanos INTEGER NOT NULL,source TEXT NOT NULL,"
                + "override INTEGER NOT NULL,mock INTEGER NOT NULL,PRIMARY KEY(activity_id,version,seq),"
                + "UNIQUE(activity_id,version,id),FOREIGN KEY(activity_id,version)"
                + " REFERENCES geometry(activity_id,version) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE result (activity_id TEXT NOT NULL,version INTEGER NOT NULL,"
                + "area REAL NOT NULL CHECK(area>0),perimeter REAL NOT NULL CHECK(perimeter>0),"
                + "closure REAL NOT NULL,point_count INTEGER NOT NULL CHECK(point_count>=3),"
                + "best REAL NOT NULL,median REAL NOT NULL,worst REAL NOT NULL,overrides INTEGER NOT NULL,"
                + "mock INTEGER NOT NULL,rejected INTEGER NOT NULL,duration INTEGER NOT NULL,"
                + "algorithm TEXT NOT NULL,unit TEXT NOT NULL,calculated INTEGER NOT NULL,"
                + "PRIMARY KEY(activity_id,version),FOREIGN KEY(activity_id,version)"
                + " REFERENCES geometry(activity_id,version) ON DELETE CASCADE)");
        db.execSQL("CREATE TRIGGER immutable_result BEFORE UPDATE ON result BEGIN "
                + "SELECT RAISE(ABORT,'Result snapshots are immutable'); END");
        db.execSQL("CREATE TRIGGER complete_guard BEFORE UPDATE OF status ON activity "
                + "WHEN NEW.status='COMPLETED' AND NOT EXISTS(SELECT 1 FROM result "
                + "WHERE activity_id=NEW.id AND version=NEW.version) BEGIN "
                + "SELECT RAISE(ABORT,'Completion requires a result'); END");
        db.execSQL("CREATE TABLE event (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "activity_id TEXT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,"
                + "type TEXT NOT NULL,time INTEGER NOT NULL,actor TEXT NOT NULL,version INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX event_activity_time ON event(activity_id,id DESC)");
        db.execSQL("CREATE TRIGGER immutable_event BEFORE UPDATE ON event BEGIN "
                + "SELECT RAISE(ABORT,'Events are append only'); END");
        db.execSQL("CREATE TABLE edit (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "activity_id TEXT NOT NULL,version INTEGER NOT NULL,before_points TEXT NOT NULL,"
                + "after_points TEXT NOT NULL,undone INTEGER NOT NULL DEFAULT 0,"
                + "FOREIGN KEY(activity_id,version) REFERENCES geometry(activity_id,version) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE setting (key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No migration defined from " + oldVersion + " to " + newVersion);
    }

    interface Action<T> { T run() throws Exception; }
    private <T> T transaction(Action<T> action) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            T value = action.run();
            db.setTransactionSuccessful();
            return value;
        } finally { db.endTransaction(); }
    }

    String setting(String key, String fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM setting WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }

    void putSetting(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key); values.put("value", value);
        getWritableDatabase().insertWithOnConflict("setting", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    CaptureRules.Config defaults() {
        return new CaptureRules.Config(Double.parseDouble(setting("accuracy", "10")),
                Double.parseDouble(setting("movement", "3")), 1, 3.5, 5000, 10000, 10);
    }

    private static String configJson(CaptureRules.Config c) throws JSONException {
        return new JSONObject().put("accuracy", c.accuracy()).put("movement", c.movement())
                .put("duplicate", c.duplicate()).put("speed", c.speed()).put("interval", c.intervalMs())
                .put("maxAge", c.maxAgeMs()).put("closure", c.closure()).toString();
    }

    private static CaptureRules.Config parseConfig(String text) throws JSONException {
        JSONObject j = new JSONObject(text);
        return new CaptureRules.Config(j.getDouble("accuracy"), j.getDouble("movement"),
                j.getDouble("duplicate"), j.getDouble("speed"), j.getLong("interval"),
                j.getLong("maxAge"), j.getDouble("closure"));
    }

    List<String> initialize() throws Exception {
        return transaction(() -> {
            SQLiteDatabase db = getWritableDatabase();
            try (Cursor c = db.rawQuery("PRAGMA quick_check", null)) {
                if (!c.moveToFirst() || !"ok".equals(c.getString(0)))
                    throw new IllegalStateException("Database integrity check failed");
            }
            List<String> recovered = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT id FROM activity WHERE status='IN_PROGRESS'", null)) {
                while (c.moveToNext()) recovered.add(c.getString(0));
            }
            for (String id : recovered) changeStatus(id, Models.Status.PAUSED, "RECOVERED");
            db.delete("activity", "deleted>0 AND purge_after<=?",
                    new String[]{Long.toString(System.currentTimeMillis())});
            return recovered;
        });
    }

    String create() throws Exception {
        return transaction(() -> {
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            ContentValues v = new ContentValues();
            v.put("id", id); v.put("title", ""); v.put("status", "DRAFT");
            v.put("unit", setting("unit", "ACRE")); v.put("mode", setting("mode", "AUTO"));
            v.put("metadata", "{}"); v.put("search_text", "");
            v.put("created", now); v.put("updated", now);
            getWritableDatabase().insertOrThrow("activity", null, v);
            event(id, "CREATED");
            return id;
        });
    }

    void saveMetadata(String id, String title, JSONObject metadata, Models.AreaUnit unit, String mode) throws Exception {
        if (title.length() > 200) throw new IllegalArgumentException("Title exceeds limit");
        for (String field : Fields.KEYS) {
            if (metadata.optString(field).length() > Fields.limit(field))
                throw new IllegalArgumentException("Field exceeds limit: " + field);
        }
        transaction(() -> {
            Models.Record record = load(id);
            require(record.editable(), "Restore the record before editing");
            ContentValues v = new ContentValues();
            v.put("title", title); v.put("metadata", metadata.toString()); v.put("unit", unit.name());
            v.put("usage", metadata.optString("usage"));
            v.put("county", metadata.optString("county").toLowerCase(java.util.Locale.ROOT));
            if (record.status == Models.Status.DRAFT || record.status == Models.Status.READY)
                v.put("mode", mode);
            StringBuilder search = new StringBuilder(title);
            for (String field : Fields.KEYS) search.append(' ').append(metadata.optString(field));
            v.put("search_text", search.toString().toLowerCase(java.util.Locale.ROOT));
            v.put("updated", System.currentTimeMillis());
            getWritableDatabase().update("activity", v, "id=?", new String[]{id});
            if (record.status == Models.Status.READY && !ready(title, metadata))
                changeStatus(id, Models.Status.DRAFT, "METADATA_INCOMPLETE");
            event(id, "METADATA_SAVED");
            return null;
        });
    }

    static boolean ready(String title, JSONObject metadata) {
        return !title.trim().isEmpty() && !metadata.optString("usage").isEmpty()
                && (metadata.optString("email").isBlank()
                    || android.util.Patterns.EMAIL_ADDRESS.matcher(metadata.optString("email")).matches())
                && (metadata.optString("phone").isBlank()
                    || metadata.optString("phone").matches("[+()0-9 .-]{5,40}"))
                && (!metadata.optString("location").trim().isEmpty() || metadata.has("anchor_lat"));
    }

    void markReady(String id) throws Exception {
        transaction(() -> {
            Models.Record r = load(id);
            require(r.status == Models.Status.DRAFT && ready(r.title, r.metadata), "Required metadata missing");
            changeStatus(id, Models.Status.READY, "READY");
            return null;
        });
    }

    Models.Record load(String id) throws JSONException {
        Models.Record r = new Models.Record();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM activity WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new IllegalStateException("Record no longer exists");
            r.id = text(c, "id"); r.title = text(c, "title"); r.status = Models.Status.valueOf(text(c, "status"));
            r.unit = Models.AreaUnit.valueOf(text(c, "unit")); r.mode = text(c, "mode");
            r.metadata = new JSONObject(text(c, "metadata")); r.version = integer(c, "version");
            r.created = number(c, "created"); r.updated = number(c, "updated");
            r.deletedAt = number(c, "deleted"); r.purgeAfter = number(c, "purge_after");
        }
        if (r.version > 0) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM geometry WHERE activity_id=? AND version=?",
                    new String[]{id, Integer.toString(r.version)})) {
                require(c.moveToFirst(), "Geometry missing");
                r.config = parseConfig(text(c, "config"));
                r.duration = number(c, "duration"); r.rejected = integer(c, "rejected");
                r.reason = text(c, "reason"); r.mockCount = integer(c, "mock");
                r.mode = text(c, "mode");
            }
            r.points = points(id, r.version);
            for (Models.Point p : r.points) if (p.override) r.overrides++;
            try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM result WHERE activity_id=? AND version=?",
                    new String[]{id, Integer.toString(r.version)})) {
                if (c.moveToFirst()) {
                    r.hasResult = true; r.area = real(c, "area"); r.perimeter = real(c, "perimeter");
                    r.closure = real(c, "closure"); r.best = real(c, "best");
                    r.median = real(c, "median"); r.worst = real(c, "worst");
                }
            }
        } else r.config = defaults();
        return r;
    }

    List<Models.Record> list(String search, String view, String sort) throws JSONException {
        return list(search, view, sort, RecordFilter.none());
    }

    record RecordFilter(String status, String usage, String unit, String county,
                        boolean warnings, long from, long until) {
        static RecordFilter none() { return new RecordFilter("", "", "", "", false, 0, Long.MAX_VALUE); }
    }

    List<Models.Record> list(String search, String view, String sort, RecordFilter filter) throws JSONException {
        String selection = switch (view) {
            case "deleted" -> "deleted>0";
            case "archive" -> "deleted=0 AND status='ARCHIVED'";
            case "draft" -> "deleted=0 AND status IN ('DRAFT','READY','PAUSED','IN_PROGRESS')";
            case "completed" -> "deleted=0 AND status='COMPLETED'";
            default -> "deleted=0 AND status!='ARCHIVED'";
        };
        String order = switch (sort) {
            case "name" -> "a.title COLLATE NOCASE ASC";
            case "name_desc" -> "a.title COLLATE NOCASE DESC";
            case "area" -> "r.area DESC";
            case "area_asc" -> "r.area IS NULL,r.area ASC";
            case "status" -> "a.status,a.updated DESC";
            case "measured" -> "a.completed DESC";
            case "oldest" -> "a.updated ASC";
            default -> "a.updated DESC";
        };
        String escaped = search.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
        List<Models.Record> out = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        arguments.add("%" + escaped + "%");
        StringBuilder extra = new StringBuilder(" AND a.updated>=? AND a.updated<=?");
        arguments.add(Long.toString(filter.from)); arguments.add(Long.toString(filter.until));
        if (!filter.status.isEmpty()) { extra.append(" AND a.status=?"); arguments.add(filter.status); }
        if (!filter.unit.isEmpty()) { extra.append(" AND a.unit=?"); arguments.add(filter.unit); }
        if (filter.warnings) extra.append(" AND (r.worst>10 OR r.mock>0 OR r.overrides>0)");
        if (!filter.usage.isEmpty()) {
            extra.append(" AND a.usage=?"); arguments.add(filter.usage);
        }
        if (!filter.county.isEmpty()) {
            extra.append(" AND a.county=?"); arguments.add(filter.county.toLowerCase(java.util.Locale.ROOT));
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT a.*,r.area,r.worst,r.mock,r.overrides,r.version AS result_version FROM activity a "
                + "LEFT JOIN result r ON a.id=r.activity_id AND a.version=r.version WHERE " + selection
                + " AND search_text LIKE ? ESCAPE '\\'" + extra + " ORDER BY " + order + " LIMIT 500",
                arguments.toArray(new String[0]))) {
            while (c.moveToNext()) {
                Models.Record r = new Models.Record();
                r.id = text(c, "id"); r.title = text(c, "title");
                r.status = Models.Status.valueOf(text(c, "status"));
                r.unit = Models.AreaUnit.valueOf(text(c, "unit"));
                r.metadata = new JSONObject(text(c, "metadata"));
                r.updated = number(c, "updated"); r.purgeAfter = number(c, "purge_after");
                r.deletedAt = number(c, "deleted"); r.hasResult = !c.isNull(c.getColumnIndexOrThrow("result_version"));
                r.area = real(c, "area"); r.worst = real(c, "worst");
                r.mockCount = integer(c, "mock"); r.overrides = integer(c, "overrides");
                out.add(r);
            }
        }
        return out;
    }

    List<Models.Point> points(String id, int version) {
        List<Models.Point> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM waypoint WHERE activity_id=? AND version=? ORDER BY seq",
                new String[]{id, Integer.toString(version)})) {
            while (c.moveToNext()) out.add(new Models.Point(text(c, "id"), real(c, "lat"), real(c, "lon"),
                    real(c, "accuracy"), number(c, "time"), number(c, "nanos"), text(c, "source"),
                    integer(c, "override") == 1, integer(c, "mock") == 1));
        }
        return out;
    }

    void start(String id) throws Exception {
        transaction(() -> {
            Models.Record r = load(id);
            require(r.deletedAt == 0 && (r.status == Models.Status.DRAFT || r.status == Models.Status.READY
                    || r.status == Models.Status.PAUSED), "Invalid capture state");
            require(ready(r.title, r.metadata), "Required metadata missing");
            if (r.version == 0) {
                int nextVersion;
                try (Cursor c = getReadableDatabase().rawQuery(
                        "SELECT COALESCE(MAX(version),0)+1 FROM geometry WHERE activity_id=?", new String[]{id})) {
                    require(c.moveToFirst(), "Version unavailable"); nextVersion = c.getInt(0);
                }
                ContentValues a = new ContentValues(); a.put("version", nextVersion);
                getWritableDatabase().update("activity", a, "id=?", new String[]{id});
                insertGeometry(id, nextVersion, r.mode, defaults());
            }
            Models.Record current = load(id);
            long offset = current.points.isEmpty() ? 0 : Math.max(0,
                    current.points.get(current.points.size() - 1).nanos + 1 - android.os.SystemClock.elapsedRealtimeNanos());
            ContentValues session = new ContentValues();
            session.put("nanos_offset", offset); session.put("resume_pending", current.points.isEmpty() ? 0 : 1);
            getWritableDatabase().update("geometry", session, "activity_id=? AND version=?", args(current));
            changeStatus(id, Models.Status.IN_PROGRESS, "CAPTURE_STARTED");
            return null;
        });
    }

    private void insertGeometry(String id, int version, String mode, CaptureRules.Config config) throws JSONException {
        ContentValues v = new ContentValues(); v.put("activity_id", id); v.put("version", version);
        v.put("started", System.currentTimeMillis()); v.put("mode", mode); v.put("config", configJson(config));
        getWritableDatabase().insertOrThrow("geometry", null, v);
    }

    void pause(String id, String reason) throws Exception {
        transaction(() -> {
            if (load(id).status == Models.Status.IN_PROGRESS) changeStatus(id, Models.Status.PAUSED, reason);
            return null;
        });
    }

    void duration(String id, long deltaMs) {
        getWritableDatabase().execSQL("UPDATE geometry SET duration=duration+? WHERE activity_id=? "
                + "AND version=(SELECT version FROM activity WHERE id=? AND status='IN_PROGRESS')",
                new Object[]{Math.max(0, deltaMs), id, id});
    }

    CaptureRules.Decision accept(String id, Models.Point point, long now, boolean manual, boolean override)
            throws Exception {
        return transaction(() -> {
            Models.Record r = load(id);
            require(r.status == Models.Status.IN_PROGRESS, "Capture is paused");
            long offset; boolean resumed;
            try (Cursor c = getReadableDatabase().rawQuery("SELECT nanos_offset,resume_pending FROM geometry "
                    + "WHERE activity_id=? AND version=?", args(r))) {
                require(c.moveToFirst(), "Session unavailable");
                offset = c.getLong(0); resumed = c.getInt(1) != 0;
            }
            Models.Point normalized = new Models.Point(point.id, point.lat, point.lon, point.accuracy,
                    point.time, point.nanos + offset, point.source, point.override, point.mock);
            Models.Point previous = r.points.isEmpty() ? null : r.points.get(r.points.size() - 1);
            CaptureRules.Decision decision = CaptureRules.evaluate(normalized, previous, r.points.size(),
                    now + offset, r.config, manual, override);
            if (decision == CaptureRules.Decision.ACCEPT && resumed && previous != null
                    && Geometry.distance(previous, normalized) > 50 && !(manual && override))
                decision = CaptureRules.Decision.JUMP;
            if (point.mock) getWritableDatabase().execSQL("UPDATE geometry SET mock=1 WHERE activity_id=? AND version=?",
                    new Object[]{id, r.version});
            if (decision != CaptureRules.Decision.ACCEPT) {
                getWritableDatabase().execSQL("UPDATE geometry SET rejected=rejected+1,reason=? "
                        + "WHERE activity_id=? AND version=?", new Object[]{decision.name(), id, r.version});
                return decision;
            }
            List<Models.Point> after = new ArrayList<>(r.points); after.add(normalized);
            saveEdit(r, after);
            getWritableDatabase().execSQL("UPDATE geometry SET resume_pending=0 WHERE activity_id=? AND version=?",
                    new Object[]{id, r.version});
            event(id, point.override ? "POINT_OVERRIDE_CONFIRMED" : "POINT_ACCEPTED");
            return decision;
        });
    }

    private void insertPoint(String id, int version, int sequence, Models.Point p) {
        ContentValues v = new ContentValues();
        v.put("activity_id", id); v.put("version", version); v.put("seq", sequence); v.put("id", p.id);
        v.put("lat", p.lat); v.put("lon", p.lon); v.put("accuracy", p.accuracy);
        v.put("time", p.time); v.put("nanos", p.nanos); v.put("source", p.source);
        v.put("override", p.override ? 1 : 0); v.put("mock", p.mock ? 1 : 0);
        getWritableDatabase().insertOrThrow("waypoint", null, v);
    }

    void editPoints(String id, List<Models.Point> points) throws Exception {
        transaction(() -> {
            Models.Record r = load(id);
            require(r.status == Models.Status.PAUSED && r.deletedAt == 0, "Pause before editing");
            saveEdit(r, points); event(id, "POINTS_EDITED"); return null;
        });
    }

    private void saveEdit(Models.Record r, List<Models.Point> after) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("edit", "activity_id=? AND version=? AND undone=1", args(r));
        ContentValues v = new ContentValues(); v.put("activity_id", r.id); v.put("version", r.version);
        v.put("before_points", Models.encode(r.points)); v.put("after_points", Models.encode(after));
        db.insertOrThrow("edit", null, v);
        // Keep 20 reversible operations; older geometry is retained in completed versions.
        db.execSQL("DELETE FROM edit WHERE activity_id=? AND version=? AND id NOT IN "
                + "(SELECT id FROM edit WHERE activity_id=? AND version=? ORDER BY id DESC LIMIT 20)",
                new Object[]{r.id, r.version, r.id, r.version});
        if (after.size() == r.points.size() + 1
                && after.subList(0, r.points.size()).equals(r.points)) {
            insertPoint(r.id, r.version, r.points.size(), after.get(after.size() - 1));
        } else replacePoints(r.id, r.version, after);
        touch(r.id);
    }

    private void replacePoints(String id, int version, List<Models.Point> points) {
        getWritableDatabase().delete("waypoint", "activity_id=? AND version=?", new String[]{id, Integer.toString(version)});
        for (int i = 0; i < points.size(); i++) insertPoint(id, version, i, points.get(i));
    }

    void undo(String id, boolean redo) throws Exception {
        transaction(() -> {
            Models.Record r = load(id); require(r.openGeometry(), "No editable geometry");
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id,before_points,after_points FROM edit "
                    + "WHERE activity_id=? AND version=? AND undone=? ORDER BY id " + (redo ? "ASC" : "DESC") + " LIMIT 1",
                    new String[]{id, Integer.toString(r.version), redo ? "1" : "0"})) {
                require(c.moveToFirst(), "No operation available");
                replacePoints(id, r.version, Models.decode(c.getString(redo ? 2 : 1)));
                ContentValues v = new ContentValues(); v.put("undone", redo ? 0 : 1);
                getWritableDatabase().update("edit", v, "id=?", new String[]{c.getString(0)});
            }
            event(id, redo ? "REDO" : "UNDO"); touch(id); return null;
        });
    }

    void revise(String id) throws Exception {
        transaction(() -> {
            Models.Record r = load(id);
            require(r.editable() && r.status == Models.Status.COMPLETED, "Completed record required");
            int version = r.version + 1;
            insertGeometry(id, version, r.mode, r.config);
            replacePoints(id, version, r.points);
            ContentValues v = new ContentValues(); v.put("version", version);
            getWritableDatabase().update("activity", v, "id=?", new String[]{id});
            changeStatus(id, Models.Status.PAUSED, "GEOMETRY_REVISION_CREATED");
            return null;
        });
    }

    void complete(String id) throws Exception {
        transaction(() -> {
            Models.Record r = load(id);
            require(r.status == Models.Status.PAUSED && r.deletedAt == 0, "Pause and review before completion");
            Geometry.Result result = Geometry.calculate(r.points);
            require(result.valid(), "Invalid polygon: " + result.error());
            List<Double> accuracies = new ArrayList<>();
            for (Models.Point p : r.points) accuracies.add(p.accuracy);
            accuracies.sort(Comparator.naturalOrder());
            ContentValues v = new ContentValues();
            v.put("activity_id", id); v.put("version", r.version);
            v.put("area", result.area()); v.put("perimeter", result.perimeter());
            v.put("closure", result.closure()); v.put("point_count", r.points.size());
            v.put("best", accuracies.get(0)); v.put("worst", accuracies.get(accuracies.size() - 1));
            int middle = accuracies.size() / 2;
            v.put("median", accuracies.size() % 2 == 0
                    ? (accuracies.get(middle - 1) + accuracies.get(middle)) / 2 : accuracies.get(middle));
            v.put("overrides", r.overrides); v.put("mock", r.mockCount); v.put("rejected", r.rejected);
            v.put("duration", r.duration); v.put("algorithm", Geometry.ALGORITHM);
            v.put("unit", r.unit.name()); v.put("calculated", System.currentTimeMillis());
            getWritableDatabase().insertOrThrow("result", null, v);
            changeStatus(id, Models.Status.COMPLETED, "COMPLETED");
            ContentValues a = new ContentValues(); a.put("disclaimer_version", "1.0");
            a.put("disclaimer_at", System.currentTimeMillis()); a.put("completed", System.currentTimeMillis());
            getWritableDatabase().update("activity", a, "id=?", new String[]{id});
            return null;
        });
    }

    String duplicate(String id) throws Exception {
        return transaction(() -> {
            Models.Record r = load(id); require(r.status != Models.Status.IN_PROGRESS, "Pause before duplicating");
            String copy = create();
            saveMetadata(copy, r.title, r.metadata, r.unit, r.mode);
            event(copy, "DUPLICATED_METADATA");
            return copy;
        });
    }

    void disposition(String id, String action) throws Exception {
        transaction(() -> {
            Models.Record r = load(id);
            require(r.status != Models.Status.IN_PROGRESS, "Pause capture first");
            SQLiteDatabase db = getWritableDatabase();
            switch (action) {
                case "archive" -> {
                    require(r.editable(), "Already archived or deleted");
                    ContentValues v = new ContentValues(); v.put("prior_status", r.status.name());
                    db.update("activity", v, "id=?", new String[]{id});
                    changeStatus(id, Models.Status.ARCHIVED, "ARCHIVED");
                }
                case "restoreArchive" -> {
                    require(r.status == Models.Status.ARCHIVED && r.deletedAt == 0, "Not archived");
                    String prior;
                    try (Cursor c = db.rawQuery("SELECT prior_status FROM activity WHERE id=?", new String[]{id})) {
                        require(c.moveToFirst(), "Record missing"); prior = c.getString(0);
                    }
                    changeStatus(id, Models.Status.valueOf(prior), "ARCHIVE_RESTORED");
                }
                case "delete" -> {
                    require(r.deletedAt == 0, "Already deleted");
                    long now = System.currentTimeMillis();
                    ContentValues v = new ContentValues(); v.put("deleted", now); v.put("purge_after", now + RETENTION_MS);
                    db.update("activity", v, "id=?", new String[]{id}); event(id, "SOFT_DELETED");
                }
                case "restore" -> {
                    require(r.deletedAt > 0 && r.purgeAfter > System.currentTimeMillis(), "Retention expired");
                    ContentValues v = new ContentValues(); v.put("deleted", 0); v.put("purge_after", 0);
                    db.update("activity", v, "id=?", new String[]{id}); event(id, "RESTORED");
                }
                case "purge" -> {
                    require(r.deletedAt > 0, "Move to Recently Deleted first");
                    db.delete("activity", "id=?", new String[]{id});
                }
                case "cancel" -> {
                    require(r.openGeometry() || r.status == Models.Status.DRAFT || r.status == Models.Status.READY,
                            "Cannot cancel this record");
                    changeStatus(id, Models.Status.CANCELLED, "CANCELLED");
                }
                case "discard" -> {
                    require(r.status == Models.Status.PAUSED || r.status == Models.Status.CANCELLED,
                            "Pause before discarding");
                    // Keep the abandoned attempt as evidence, but detach it from the new draft.
                    ContentValues v = new ContentValues(); v.put("version", 0);
                    db.update("activity", v, "id=?", new String[]{id});
                    changeStatus(id, Models.Status.DRAFT, "CAPTURE_DISCARDED");
                }
                default -> throw new IllegalArgumentException("Unknown action");
            }
            return null;
        });
    }

    private void touch(String id) {
        ContentValues v = new ContentValues(); v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("activity", v, "id=?", new String[]{id});
    }

    private void changeStatus(String id, Models.Status status, String reason) {
        ContentValues v = new ContentValues(); v.put("status", status.name()); v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("activity", v, "id=?", new String[]{id}); event(id, reason);
    }

    void event(String id, String type) {
        ContentValues v = new ContentValues(); v.put("activity_id", id); v.put("type", type);
        v.put("time", System.currentTimeMillis()); v.put("actor", "LOCAL_USER");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT version FROM activity WHERE id=?", new String[]{id})) {
            require(c.moveToFirst(), "Record missing"); v.put("version", c.getInt(0));
        }
        getWritableDatabase().insertOrThrow("event", null, v);
    }

    record History(long time, String type, int version) {}
    List<History> history(String id) {
        List<History> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT time,type,version FROM event WHERE activity_id=? ORDER BY id DESC LIMIT 200", new String[]{id})) {
            while (c.moveToNext()) out.add(new History(c.getLong(0), c.getString(1), c.getInt(2)));
        }
        return out;
    }

    private static String[] args(Models.Record r) { return new String[]{r.id, Integer.toString(r.version)}; }
    private static String text(Cursor c, String key) { return c.getString(c.getColumnIndexOrThrow(key)); }
    private static long number(Cursor c, String key) { return c.getLong(c.getColumnIndexOrThrow(key)); }
    private static int integer(Cursor c, String key) { return c.getInt(c.getColumnIndexOrThrow(key)); }
    private static double real(Cursor c, String key) { return c.getDouble(c.getColumnIndexOrThrow(key)); }
    static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}

package com.mogada.landmeasure;

import static org.junit.Assert.*;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class StoreTest {
    private Store store;
    @Before public void setup() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("landmeasure.db");
        store = new Store(app); store.initialize();
    }
    @After public void cleanup() { store.close(); }

    private String draft() throws Exception {
        String id = store.create();
        store.saveMetadata(id, "O'Neal shamba", new JSONObject().put("usage", "agricultural")
                .put("location", "Kisumu").put("owner", "Njeri").put("reference", "00123"),
                Models.AreaUnit.ACRE, "MANUAL");
        return id;
    }
    private String polygon() throws Exception {
        String id = draft(); store.start(id);
        double[][] coordinates = {{0, 36}, {0, 36.001}, {0.001, 36.001}, {0.001, 36}};
        for (int i = 0; i < coordinates.length; i++) {
            long nanos = (1L + i * 60L) * 1000000000L;
            Models.Point p = new Models.Point(coordinates[i][0], coordinates[i][1], 5 + i,
                    1000 + i * 60000L, nanos, "GPS", false, false);
            assertEquals(CaptureRules.Decision.ACCEPT, store.accept(id, p, nanos, true, false));
        }
        store.duration(id, 180000); store.pause(id, "PAUSED");
        return id;
    }

    @Test public void draftsRoundTripAndDuplicateWithoutGeometry() throws Exception {
        String id = polygon(), copy = store.duplicate(id);
        assertNotEquals(id, copy);
        assertEquals("00123", store.load(copy).metadata.getString("reference"));
        assertTrue(store.load(copy).points.isEmpty());
        assertEquals(Models.Status.DRAFT, store.load(copy).status);
        assertEquals(1, store.history(copy).stream().filter(e -> e.type().equals("CREATED")).count());
    }

    @Test public void recoveredWalkIsPausedWithAcknowledgedPointsAndDuration() throws Exception {
        String id = polygon(); store.start(id); store.duration(id, 5000);
        store.close();
        store = new Store(RuntimeEnvironment.getApplication());
        assertEquals(List.of(id), store.initialize());
        Models.Record r = store.load(id);
        assertEquals(Models.Status.PAUSED, r.status); assertEquals(4, r.points.size());
        assertEquals(185000, r.duration);
    }

    @Test public void onlyOneActiveAndReadinessGuardsAreTransactional() throws Exception {
        String first = draft(), second = draft(); store.start(first);
        assertThrows(SQLiteConstraintException.class, () -> store.start(second));
        assertEquals(Models.Status.DRAFT, store.load(second).status);
        assertEquals(0, store.load(second).version);
        assertThrows(IllegalStateException.class, () -> store.markReady(store.create()));
    }

    @Test public void completionRevisionAndUnitEditsKeepOriginalSnapshot() throws Exception {
        String id = polygon(); store.complete(id);
        double original = store.load(id).area;
        store.saveMetadata(id, "Renamed", store.load(id).metadata, Models.AreaUnit.HA, "MANUAL");
        assertEquals(original, store.load(id).area, 0);
        store.revise(id);
        Models.Record r = store.load(id);
        assertEquals(Models.Status.PAUSED, r.status); assertEquals(2, r.version);
        List<Models.Point> changed = new ArrayList<>(r.points);
        Models.Point p = changed.get(2);
        changed.set(2, new Models.Point(0.002, p.lon, p.accuracy, p.time, p.nanos, "EDIT", true, false));
        store.editPoints(id, changed); store.complete(id);
        assertNotEquals(original, store.load(id).area, 0.1);
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT area FROM result WHERE activity_id=? AND version=1", new String[]{id})) {
            assertTrue(c.moveToFirst()); assertEquals(original, c.getDouble(0), 0);
        }
    }

    @Test public void injectedFailureRollsBackResultAndCompletionTogether() throws Exception {
        String id = polygon();
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_completion BEFORE UPDATE OF status ON activity "
                + "WHEN NEW.status='COMPLETED' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        assertThrows(SQLiteConstraintException.class, () -> store.complete(id));
        assertEquals(Models.Status.PAUSED, store.load(id).status);
        assertFalse(store.load(id).hasResult);
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_completion");
        store.complete(id); assertTrue(store.load(id).hasResult);
    }

    @Test public void immutableResultsAndForeignKeysAndUniqueSequencesEnforced() throws Exception {
        String id = polygon(); store.complete(id);
        assertThrows(SQLiteConstraintException.class, () -> store.getWritableDatabase()
                .execSQL("UPDATE result SET area=1 WHERE activity_id=?", new Object[]{id}));
        assertThrows(SQLiteConstraintException.class, () -> store.getWritableDatabase()
                .execSQL("INSERT INTO geometry(activity_id,version,started,mode,config) VALUES('missing',1,0,'AUTO','{}')"));
        assertThrows(SQLiteConstraintException.class, () -> store.getWritableDatabase()
                .execSQL("INSERT INTO waypoint SELECT * FROM waypoint LIMIT 1"));
    }

    @Test public void undoRedoAndDiscardRestartPersist() throws Exception {
        String id = polygon();
        store.undo(id, false); assertEquals(3, store.load(id).points.size());
        store.undo(id, true); assertEquals(4, store.load(id).points.size());
        store.disposition(id, "discard"); assertEquals(0, store.load(id).version);
        store.start(id); assertEquals(2, store.load(id).version);
        assertTrue(store.load(id).points.isEmpty());
    }

    @Test public void archiveSoftDeleteRestoreAndPurgeCascade() throws Exception {
        String id = polygon(); store.complete(id);
        store.disposition(id, "archive");
        assertTrue(store.list("", "all", "updated").isEmpty());
        assertEquals(1, store.list("", "archive", "updated").size());
        store.disposition(id, "restoreArchive");
        assertEquals(Models.Status.COMPLETED, store.load(id).status);
        store.disposition(id, "delete");
        assertTrue(store.list("", "all", "updated").isEmpty());
        assertEquals(1, store.list("", "deleted", "updated").size());
        store.disposition(id, "restore"); assertEquals(4, store.load(id).points.size());
        store.disposition(id, "delete"); store.disposition(id, "purge");
        for (String table : List.of("geometry", "waypoint", "result", "event", "edit")) {
            try (Cursor c = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
                assertTrue(c.moveToFirst()); assertEquals(table, 0, c.getInt(0));
            }
        }
    }

    @Test public void searchEscapesWildcardsAndPreservesUnicode() throws Exception {
        String id = draft();
        assertEquals(id, store.list("njeri", "all", "name").get(0).id);
        assertTrue(store.list("%", "all", "updated").isEmpty());
        store.saveMetadata(id, "Mũthoni's 100% shamba", store.load(id).metadata, Models.AreaUnit.M2, "AUTO");
        assertEquals(id, store.list("100%", "all", "updated").get(0).id);
        assertEquals("Mũthoni's 100% shamba", store.load(id).title);
    }

    @Test public void unsafeCompletedAndDeletedMutationsAreBlocked() throws Exception {
        String id = polygon(); store.complete(id);
        assertThrows(IllegalStateException.class, () -> store.start(id));
        assertThrows(IllegalStateException.class, () -> store.editPoints(id, List.of()));
        store.disposition(id, "delete");
        assertThrows(IllegalStateException.class, () -> store.revise(id));
    }

    @Test public void snapshotSettingsDoNotChangeDuringSession() throws Exception {
        String id = polygon();
        store.putSetting("accuracy", "15");
        assertEquals(10, store.load(id).config.accuracy(), 0);
        String next = draft(); store.start(next);
        assertEquals(15, store.load(next).config.accuracy(), 0);
    }

    @Test public void themeAndLegacyRecordVersionSurviveReopeningDatabase() throws Exception {
        assertEquals(AppTheme.SYSTEM, store.setting("theme", AppTheme.SYSTEM));
        String old = draft();
        store.getWritableDatabase().execSQL("UPDATE activity SET app_version='1.0.0' WHERE id=?", new Object[]{old});
        store.putSetting("theme", AppTheme.DARK);
        store.close();
        store = new Store(RuntimeEnvironment.getApplication()); store.initialize();
        assertEquals(AppTheme.DARK, store.setting("theme", AppTheme.SYSTEM));
        assertEquals("1.0.0", store.load(old).appVersion);
        assertEquals(BuildConfig.VERSION_NAME, store.load(store.create()).appVersion);
    }

    @Test public void resumeAfterMonotonicClockResetAcceptsNewFixWithoutLosingOrder() throws Exception {
        String id = draft(); store.start(id);
        long oldNanos = android.os.SystemClock.elapsedRealtimeNanos() + 1000000000000L;
        store.accept(id, new Models.Point(0, 36, 5, 1000, oldNanos, "GPS", false, false), oldNanos, true, false);
        store.pause(id, "PAUSED"); store.start(id);
        long freshNanos = android.os.SystemClock.elapsedRealtimeNanos() + 3000000000L;
        assertEquals(CaptureRules.Decision.ACCEPT, store.accept(id,
                new Models.Point(0, 36.00002, 5, 2000, freshNanos, "GPS", false, false),
                freshNanos, true, false));
        List<Models.Point> points = store.load(id).points;
        assertTrue(points.get(1).nanos > points.get(0).nanos);
    }

    @Test public void combinedFiltersUseIndexedColumnsAndLiteralSearch() throws Exception {
        String id = draft();
        Models.Record r = store.load(id);
        r.metadata.put("county", "Kiambu");
        store.saveMetadata(id, r.title, r.metadata, r.unit, r.mode);
        Store.RecordFilter filter = new Store.RecordFilter("DRAFT", "agricultural", Store.AreaComparison.ANY,
                0, Models.AreaUnit.ACRE, false, 0, Long.MAX_VALUE);
        assertEquals(id, store.list("njeri", "all", "name", filter).get(0).id);
        assertTrue(store.list("njeri", "all", "name", new Store.RecordFilter("COMPLETED", "", Store.AreaComparison.ANY,
                0, Models.AreaUnit.ACRE, false, 0, Long.MAX_VALUE)).isEmpty());
    }

    private void pagingDrafts(int count) {
        android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < count; i++) db.execSQL(
                    "INSERT INTO activity(id,title,status,unit,mode,metadata,search_text,usage,county,created,updated) "
                    + "VALUES(?,'Same title','DRAFT','ACRE','MANUAL',?,'same title','agricultural','target',1000,2000)",
                    new Object[]{String.format(Locale.ROOT, "paging-%04d", i),
                            "{\"usage\":\"agricultural\",\"county\":\"Target\"}"});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    @Test public void pagesReachPastFormerLimitWithoutSkippingEqualSortValues() throws Exception {
        pagingDrafts(503);
        List<String> actual = new ArrayList<>(), expected = new ArrayList<>();
        for (int i = 0; i < 503; i++) expected.add(String.format(Locale.ROOT, "paging-%04d", i));
        for (String sort : List.of("updated", "name")) {
            actual.clear();
            for (int page = 0; page < 51; page++) {
                Store.RecordPage result = store.listPage("", "all", sort, Store.RecordFilter.none(), page);
                assertEquals(503, result.total()); assertEquals(51, result.pageCount());
                assertEquals(page == 50 ? 3 : 10, result.records().size());
                for (Models.Record record : result.records()) actual.add(record.id);
            }
            assertEquals(expected, actual);
        }
    }

    @Test public void filteredPagesClampAfterRemovalAndHandleNoMatches() throws Exception {
        pagingDrafts(23);
        Store.RecordFilter filter = new Store.RecordFilter("DRAFT", "agricultural", Store.AreaComparison.ANY,
                0, Models.AreaUnit.ACRE, false, 2000, 2000);
        Store.RecordPage last = store.listPage("same", "draft", "name", filter, 99);
        assertEquals(23, last.total()); assertEquals(2, last.index()); assertEquals(3, last.records().size());
        store.getWritableDatabase().delete("activity", "id>=?", new String[]{"paging-0020"});
        last = store.listPage("same", "draft", "name", filter, 2);
        assertEquals(20, last.total()); assertEquals(1, last.index()); assertEquals(10, last.records().size());
        Store.RecordPage empty = store.listPage("%", "draft", "name", filter, 2);
        assertEquals(0, empty.total()); assertEquals(0, empty.index()); assertTrue(empty.records().isEmpty());
        assertTrue(store.listPage("same", "completed", "name", filter, 0).records().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.listPage("", "all", "name", filter, -1));
    }

    @Test public void areaComparisonsConvertAcrossDisplayUnitsAndMatchRoundedEquality() throws Exception {
        String id = polygon(); store.complete(id); draft();
        assertEquals(Models.AreaUnit.ACRE, store.load(id).unit);
        Store.RecordFilter equal = areaFilter(Store.AreaComparison.EQUAL, 1.2309, Models.AreaUnit.HA);
        assertEquals(id, store.listPage("", "all", "name", equal, 0).records().get(0).id);
        assertEquals(1, store.listPage("", "all", "name", equal, 0).total());
        assertEquals(1, store.listPage("", "all", "name",
                areaFilter(Store.AreaComparison.GREATER, 1, Models.AreaUnit.HA), 0).total());
        assertEquals(1, store.listPage("", "all", "name",
                areaFilter(Store.AreaComparison.LESS, 20000, Models.AreaUnit.M2), 0).total());
        assertEquals(0, store.listPage("", "all", "name",
                areaFilter(Store.AreaComparison.LESS, 0.01, Models.AreaUnit.KM2), 0).total());
        for (Store.AreaComparison comparison : List.of(Store.AreaComparison.LESS, Store.AreaComparison.GREATER))
            assertEquals(0, store.listPage("", "all", "name", areaFilter(comparison, 1.2309, Models.AreaUnit.HA), 0).total());
        assertEquals(2, store.listPage("", "all", "name", Store.RecordFilter.none(), 0).total());
        assertThrows(IllegalArgumentException.class, () -> areaFilter(Store.AreaComparison.EQUAL, Double.NaN, Models.AreaUnit.HA));
        assertThrows(IllegalArgumentException.class, () -> areaFilter(Store.AreaComparison.GREATER, -1, Models.AreaUnit.ACRE));
        assertThrows(IllegalArgumentException.class, () -> areaFilter(Store.AreaComparison.LESS, 0.00001, Models.AreaUnit.M2));
    }

    private Store.RecordFilter areaFilter(Store.AreaComparison comparison, double value, Models.AreaUnit unit) {
        return new Store.RecordFilter("", "", comparison, value, unit, false, 0, Long.MAX_VALUE);
    }
}

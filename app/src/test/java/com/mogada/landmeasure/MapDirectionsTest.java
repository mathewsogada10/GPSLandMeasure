package com.mogada.landmeasure;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ContextWrapper;
import android.content.Intent;

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
public class MapDirectionsTest {
    private Models.Point point(double latitude, double longitude) {
        return new Models.Point(latitude, longitude, 5, 0, 0, "GPS", false, false);
    }

    @Test public void selectedWaypointUsesLatitudeLongitudeOrderInCommaDecimalLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            List<Models.Point> points = List.of(point(0, 36), point(1, 37), point(-1.2345678, -36.9876543));
            Intent intent = MapDirections.intent(points.get(2));
            assertEquals(Intent.ACTION_VIEW, intent.getAction());
            assertEquals("https", intent.getData().getScheme());
            assertEquals("www.google.com", intent.getData().getHost());
            assertEquals("/maps/dir/", intent.getData().getPath());
            assertEquals("1", intent.getData().getQueryParameter("api"));
            assertEquals("-1.2345678,-36.9876543", intent.getData().getQueryParameter("destination"));
            assertEquals("navigate", intent.getData().getQueryParameter("dir_action"));
            assertNull(intent.getExtras());
        } finally { Locale.setDefault(previous); }
    }

    @Test public void installedMapsAppIsPreferred() {
        RecordingContext context = new RecordingContext(0);
        MapDirections.open(context, point(1, 37));
        assertEquals(1, context.attempts.size());
        assertEquals("com.google.android.apps.maps", context.attempts.get(0).getPackage());
    }

    @Test public void missingMapsAppFallsBackToSameUrlWithoutPackageRestriction() {
        RecordingContext context = new RecordingContext(1);
        MapDirections.open(context, point(-1, 36));
        assertEquals(2, context.attempts.size());
        assertEquals("com.google.android.apps.maps", context.attempts.get(0).getPackage());
        assertNull(context.attempts.get(1).getPackage());
        assertEquals(context.attempts.get(0).getData(), context.attempts.get(1).getData());
    }

    @Test public void absentMapsAndBrowserPropagatesErrorForUiToReport() {
        RecordingContext context = new RecordingContext(2);
        assertThrows(ActivityNotFoundException.class, () -> MapDirections.open(context, point(0, 36)));
        assertEquals(2, context.attempts.size());
    }

    private static final class RecordingContext extends ContextWrapper {
        final List<Intent> attempts = new ArrayList<>();
        final int failures;
        RecordingContext(int failures) { super(RuntimeEnvironment.getApplication()); this.failures = failures; }
        @Override public void startActivity(Intent intent) {
            attempts.add(new Intent(intent));
            if (attempts.size() <= failures) throw new ActivityNotFoundException("No handler in fixture");
        }
    }
}

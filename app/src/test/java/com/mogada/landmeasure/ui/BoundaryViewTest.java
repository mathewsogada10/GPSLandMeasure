package com.mogada.landmeasure.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;

import com.mogada.landmeasure.R;
import com.mogada.landmeasure.domain.Models;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class BoundaryViewTest {
    private record Selection(int from, int to, double metres) {}
    private Selection selected;
    private float density;

    private Models.Point point(double lat, double lon) {
        return new Models.Point(lat, lon, 5, 0, 0, "GPS", false, false);
    }

    private BoundaryView boundary(List<Models.Point> points) {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_LandMeasure);
        density = context.getResources().getDisplayMetrics().density;
        BoundaryView view = new BoundaryView(context, points);
        view.setEdgeListener((from, to, metres) -> selected = new Selection(from, to, metres));
        view.layout(0, 0, Math.round(320 * density), Math.round(320 * density));
        return view;
    }

    private BoundaryView square() {
        return boundary(List.of(point(0, 0), point(0, 0.001), point(0.001, 0.001), point(0.001, 0)));
    }

    private void event(BoundaryView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 1, action, x * density, y * density, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private void tap(BoundaryView view, float x, float y) {
        event(view, MotionEvent.ACTION_DOWN, x, y);
        event(view, MotionEvent.ACTION_UP, x, y);
    }

    @Test public void tappingEdgesReportsEndpointNumbersAndWgs84MetresIncludingClosure() {
        BoundaryView view = square();
        tap(view, 160, 288);
        assertNotNull(selected);
        assertEquals(0, selected.from()); assertEquals(1, selected.to());
        assertEquals(111.319490793, selected.metres(), 0.001);
        tap(view, 32, 160);
        assertEquals(3, selected.from()); assertEquals(0, selected.to());
        assertEquals(110.57427582, selected.metres(), 0.001);
    }

    @Test public void missesScrollingAndCancelledTouchesDoNotSelectAnEdge() {
        BoundaryView view = square();
        tap(view, 160, 160); assertNull(selected);
        event(view, MotionEvent.ACTION_DOWN, 160, 288);
        event(view, MotionEvent.ACTION_MOVE, 160, 200);
        event(view, MotionEvent.ACTION_UP, 160, 200);
        assertNull(selected);
        event(view, MotionEvent.ACTION_DOWN, 160, 288);
        event(view, MotionEvent.ACTION_CANCEL, 160, 288);
        event(view, MotionEvent.ACTION_UP, 160, 288);
        assertNull(selected);
        tap(view, 160, 288); assertNotNull(selected);
    }

    @Test public void accessibilityActivationCyclesAllEdgesAndWraps() {
        BoundaryView view = square();
        for (int i = 0; i < 5; i++) {
            assertTrue(view.performClick());
            assertEquals(i % 4, selected.from());
            assertEquals((i + 1) % 4, selected.to());
        }
    }

    @Test public void twoPointsHaveOneEdgeAndSparseBoundariesHaveNone() {
        assertFalse(boundary(List.of()).performClick());
        assertFalse(boundary(List.of(point(0, 0))).performClick());
        assertNull(selected);
        BoundaryView view = boundary(List.of(point(0, 0), point(0, 0.001)));
        tap(view, 160, 160);
        assertEquals(0, selected.from()); assertEquals(1, selected.to());
        assertTrue(view.performClick());
        assertEquals(0, selected.from()); assertEquals(1, selected.to());
    }

    @Test public void antimeridianProjectionAndResizingKeepHitTestingAligned() {
        BoundaryView view = boundary(List.of(point(0, 179.999), point(0, -179.999)));
        tap(view, 160, 160);
        assertNotNull(selected);
        assertEquals(222.638981586, selected.metres(), 0.001);
        selected = null;
        view.layout(0, 0, Math.round(640 * density), Math.round(320 * density));
        tap(view, 320, 160);
        assertNotNull(selected);
        assertEquals(222.638981586, selected.metres(), 0.001);
    }
}

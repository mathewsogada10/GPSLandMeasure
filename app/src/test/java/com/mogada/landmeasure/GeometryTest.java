package com.mogada.landmeasure;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GeometryTest {
    private static Models.Point point(double lat, double lon) {
        return new Models.Point(lat, lon, 5, 0, 0, "GPS", false, false);
    }

    @Test public void equatorialSquareMatchesIndependentWgs84Fixture() {
        Geometry.Result result = Geometry.calculate(List.of(point(0, 0), point(0, 1),
                point(1, 1), point(1, 0)));
        assertTrue(result.valid());
        assertEquals(12308778361.469452, result.area(), 1);
        assertEquals(443770.91724830196, result.perimeter(), 0.01);
    }

    @Test public void highLatitudeMatchesReference() {
        Geometry.Result result = Geometry.calculate(List.of(point(80, 0), point(80, 1),
                point(81, 1), point(81, 0)));
        assertTrue(result.valid());
        assertEquals(2058174639.519043, result.area(), 1);
        assertEquals(260190.77957711602, result.perimeter(), 0.01);
    }

    @Test public void antimeridianReturnsSmallIntendedParcel() {
        Geometry.Result result = Geometry.calculate(List.of(point(0, 179.9), point(0, -179.9),
                point(0.1, -179.9), point(0.1, 179.9)));
        assertTrue(result.valid());
        assertEquals(246181571.60204664, result.area(), 1);
        assertTrue(result.perimeter() < 70000);
    }

    @Test public void equatorAndOrientationDoNotChangeArea() {
        List<Models.Point> points = new ArrayList<>(List.of(point(-0.1, 36), point(-0.1, 36.1),
                point(0.1, 36.1), point(0.1, 36)));
        Geometry.Result forward = Geometry.calculate(points);
        Collections.reverse(points);
        Geometry.Result reverse = Geometry.calculate(points);
        assertTrue(forward.valid()); assertTrue(reverse.valid());
        assertEquals(forward.area(), reverse.area(), 0.001);
        assertEquals(forward.perimeter(), reverse.perimeter(), 0.001);
    }

    @Test public void bowTieReportsIntersectingSegments() {
        Geometry.Result result = Geometry.calculate(List.of(point(0, 0), point(1, 1),
                point(0, 1), point(1, 0)));
        assertEquals(Geometry.Error.INTERSECTION, result.error());
        assertTrue(result.point() >= 0);
        assertTrue(result.other() >= 0);
    }

    @Test public void repeatedVertexAndNonAdjacentTouchAreBlocked() {
        assertEquals(Geometry.Error.DUPLICATE, Geometry.calculate(List.of(
                point(0, 0), point(0, 0), point(1, 1))).error());
        assertFalse(Geometry.calculate(List.of(point(0, 0), point(0, 1), point(1, 1),
                point(0, 1), point(1, 0))).valid());
    }

    @Test public void rejectsTooFewCollinearAndInvalidCoordinates() {
        assertEquals(Geometry.Error.TOO_FEW, Geometry.calculate(List.of(point(0, 0), point(0, 1))).error());
        assertFalse(Geometry.calculate(List.of(point(0, 0), point(0, 1), point(0, 2))).valid());
        assertEquals(Geometry.Error.INVALID_COORDINATE, Geometry.calculate(List.of(
                point(Double.NaN, 0), point(0, 1), point(1, 1))).error());
    }

    @Test public void concavePolygonAndLargeRingAreSupported() {
        assertTrue(Geometry.calculate(List.of(point(0, 0), point(0, 0.1), point(0.05, 0.05),
                point(0.1, 0.1), point(0.1, 0))).valid());
        List<Models.Point> points = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            double angle = 2 * Math.PI * i / 10000;
            points.add(point(Math.sin(angle), 36 + Math.cos(angle)));
        }
        assertTrue(Geometry.calculate(points).valid());
    }

    @Test public void unitChangesPreserveCanonicalArea() {
        double canonical = 10000;
        assertEquals(1, canonical / Models.AreaUnit.HA.divisor, 0);
        assertEquals(2.471053814671653, canonical / Models.AreaUnit.ACRE.divisor, 1e-12);
        assertEquals(0.01, canonical / Models.AreaUnit.KM2.divisor, 0);
        assertEquals(10000, canonical, 0);
    }
}

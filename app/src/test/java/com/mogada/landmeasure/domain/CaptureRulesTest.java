package com.mogada.landmeasure.domain;

import static org.junit.Assert.*;

import org.junit.Test;

public class CaptureRulesTest {
    private final CaptureRules.Config config = CaptureRules.Config.defaults();
    private Models.Point point(double lat, double accuracy, long seconds) {
        return new Models.Point(lat, 36, accuracy, seconds * 1000, seconds * 1000000000L, "GPS", false, false);
    }
    private CaptureRules.Decision evaluate(Models.Point p, Models.Point previous, long now, boolean manual, boolean override) {
        return CaptureRules.evaluate(p, previous, previous == null ? 0 : 1, now * 1000000000L, config, manual, override);
    }

    @Test public void requiresFreshAccurateFirstPoint() {
        assertEquals(CaptureRules.Decision.STALE, evaluate(point(0, 5, 1), null, 12, false, false));
        assertEquals(CaptureRules.Decision.STALE, evaluate(point(0, 5, 12), null, 1, false, false));
        assertEquals(CaptureRules.Decision.ACCURACY, evaluate(point(0, 21, 1), null, 1, false, false));
        assertEquals(CaptureRules.Decision.ACCEPT, evaluate(point(0, 10, 1), null, 1, false, false));
    }

    @Test public void manualOverrideCannotBypassStalenessOrDuplicates() {
        assertEquals(CaptureRules.Decision.ACCEPT, evaluate(point(0, 30, 2), null, 2, true, true));
        assertEquals(CaptureRules.Decision.ACCURACY, evaluate(point(0, 30, 2), null, 2, true, false));
        assertEquals(CaptureRules.Decision.STALE, evaluate(point(0, 30, 2), null, 20, true, true));
        assertEquals(CaptureRules.Decision.DUPLICATE, evaluate(point(0, 30, 3), point(0, 5, 2), 3, true, true));
    }

    @Test public void distanceOrIntervalAndStationaryRejection() {
        Models.Point previous = point(0, 5, 1);
        assertEquals(CaptureRules.Decision.MOVEMENT, evaluate(point(0.000018, 5, 2), previous, 2, false, false));
        assertEquals(CaptureRules.Decision.ACCEPT, evaluate(point(0.000018, 5, 6), previous, 6, false, false));
        assertEquals(CaptureRules.Decision.ACCEPT, evaluate(point(0.00003, 5, 2), previous, 2, false, false));
        assertEquals(CaptureRules.Decision.DUPLICATE, evaluate(point(0.000001, 5, 8), previous, 8, false, false));
    }

    @Test public void usesMonotonicOrderingAndFlagsSpeedAndJump() {
        Models.Point previous = point(0, 5, 1);
        assertEquals(CaptureRules.Decision.TIME, evaluate(point(0.0001, 5, 1), previous, 1, false, false));
        assertEquals(CaptureRules.Decision.SPEED, evaluate(point(0.0001, 5, 2), previous, 2, false, false));
        assertEquals(CaptureRules.Decision.JUMP, evaluate(point(0.001, 5, 2), previous, 2, false, false));
        assertEquals(CaptureRules.Decision.ACCEPT, evaluate(point(0.001, 5, 2), previous, 2, true, true));
    }

    @Test public void enforcesCoordinateAndPointLimits() {
        assertEquals(CaptureRules.Decision.INVALID, evaluate(point(91, 5, 1), null, 1, true, true));
        assertEquals(CaptureRules.Decision.INVALID, evaluate(point(0, -1, 1), null, 1, true, true));
        assertEquals(CaptureRules.Decision.LIMIT, CaptureRules.evaluate(point(0, 5, 1), null, 10000,
                1000000000L, config, false, false));
    }

    @Test public void rejectsUnsafeThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureRules.Config(21, 3, 1, 3.5, 5000, 10000, 10));
        assertThrows(IllegalArgumentException.class, () -> new CaptureRules.Config(10, 0, 1, 3.5, 5000, 10000, 10));
        assertThrows(IllegalArgumentException.class, () -> new CaptureRules.Config(Double.NaN, 3, 1, 3.5, 5000, 10000, 10));
    }
}

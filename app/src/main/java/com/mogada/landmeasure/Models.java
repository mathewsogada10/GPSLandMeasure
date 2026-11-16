package com.mogada.landmeasure;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Models {
    private Models() {}

    enum Status { DRAFT, READY, IN_PROGRESS, PAUSED, COMPLETED, CANCELLED, ARCHIVED }

    enum AreaUnit {
        M2(1, R.string.unit_m2), HA(10000, R.string.unit_ha),
        ACRE(4046.8564224, R.string.unit_acre), KM2(1000000, R.string.unit_km2);
        final double divisor;
        final int label;
        AreaUnit(double divisor, int label) { this.divisor = divisor; this.label = label; }
    }

    static final class Point {
        final String id;
        final double lat, lon, accuracy;
        final long time, nanos;
        final String source;
        final boolean override, mock;

        Point(double lat, double lon, double accuracy, long time, long nanos,
              String source, boolean override, boolean mock) {
            this(UUID.randomUUID().toString(), lat, lon, accuracy, time, nanos, source, override, mock);
        }

        Point(String id, double lat, double lon, double accuracy, long time, long nanos,
              String source, boolean override, boolean mock) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.accuracy = accuracy;
            this.time = time;
            this.nanos = nanos;
            this.source = source;
            this.override = override;
            this.mock = mock;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("lat", lat).put("lon", lon)
                    .put("accuracy", accuracy).put("time", time).put("nanos", nanos)
                    .put("source", source).put("override", override).put("mock", mock);
        }
    }

    static String encode(List<Point> points) throws JSONException {
        JSONArray out = new JSONArray();
        for (Point point : points) out.put(point.json());
        return out.toString();
    }

    static List<Point> decode(String value) throws JSONException {
        JSONArray array = new JSONArray(value);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject p = array.getJSONObject(i);
            out.add(new Point(p.getString("id"), p.getDouble("lat"), p.getDouble("lon"),
                    p.getDouble("accuracy"), p.getLong("time"), p.getLong("nanos"),
                    p.getString("source"), p.getBoolean("override"), p.getBoolean("mock")));
        }
        return out;
    }

    static final class Record {
        String id, title, mode, reason, appVersion;
        Status status;
        AreaUnit unit;
        JSONObject metadata = new JSONObject();
        List<Point> points = new ArrayList<>();
        int version, rejected, overrides, mockCount;
        long created, updated, completed, deletedAt, purgeAfter, duration;
        double area, perimeter, best, median, worst, closure;
        boolean hasResult;
        CaptureRules.Config config;

        boolean editable() { return deletedAt == 0 && status != Status.ARCHIVED; }
        boolean openGeometry() {
            return editable() && (status == Status.PAUSED || status == Status.IN_PROGRESS);
        }
    }
}

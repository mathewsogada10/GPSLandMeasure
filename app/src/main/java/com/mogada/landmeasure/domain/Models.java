package com.mogada.landmeasure.domain;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}

    public enum Status { DRAFT, READY, IN_PROGRESS, PAUSED, COMPLETED, CANCELLED, ARCHIVED }

    public enum AreaUnit {
        M2(1), HA(10000), ACRE(4046.8564224), KM2(1000000);
        public final double divisor;
        AreaUnit(double divisor) { this.divisor = divisor; }
    }

    public static final class Point {
        public final String id;
        public final double lat, lon, accuracy;
        public final long time, nanos;
        public final String source;
        public final boolean override, mock;

        public Point(double lat, double lon, double accuracy, long time, long nanos,
              String source, boolean override, boolean mock) {
            this(UUID.randomUUID().toString(), lat, lon, accuracy, time, nanos, source, override, mock);
        }

        public Point(String id, double lat, double lon, double accuracy, long time, long nanos,
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

    public static String encode(List<Point> points) throws JSONException {
        JSONArray out = new JSONArray();
        for (Point point : points) out.put(point.json());
        return out.toString();
    }

    public static List<Point> decode(String value) throws JSONException {
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

    public static final class Record {
        public String id, title, mode, reason, appVersion;
        public Status status;
        public AreaUnit unit;
        public JSONObject metadata = new JSONObject();
        public List<Point> points = new ArrayList<>();
        public int version, rejected, overrides, mockCount;
        public long created, updated, completed, deletedAt, purgeAfter, duration;
        public double area, perimeter, best, median, worst, closure;
        public boolean hasResult;
        public CaptureRules.Config config;

        public boolean editable() { return deletedAt == 0 && status != Status.ARCHIVED; }
        public boolean openGeometry() {
            return editable() && (status == Status.PAUSED || status == Status.IN_PROGRESS);
        }
    }
}

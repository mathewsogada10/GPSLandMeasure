package com.mogada.landmeasure;

final class CaptureRules {
    static final int LIMIT = 10000;
    enum Decision { ACCEPT, STALE, ACCURACY, DUPLICATE, TIME, MOVEMENT, SPEED, JUMP, LIMIT, INVALID }
    record Config(double accuracy, double movement, double duplicate, double speed,
                  long intervalMs, long maxAgeMs, double closure) {
        static Config defaults() { return new Config(10, 3, 1, 3.5, 5000, 10000, 10); }
        Config {
            if (!Double.isFinite(accuracy) || !Double.isFinite(movement)
                    || !Double.isFinite(duplicate) || !Double.isFinite(speed) || !Double.isFinite(closure)
                    || accuracy < 3 || accuracy > 20 || movement < 1 || movement > 20
                    || duplicate < 0.5 || duplicate > movement || speed < 1 || speed > 10
                    || intervalMs < 1000 || intervalMs > 10000 || maxAgeMs != 10000
                    || closure < 1 || closure > 30)
                throw new IllegalArgumentException("Invalid capture configuration");
        }
    }

    static Decision evaluate(Models.Point candidate, Models.Point previous, int count,
                             long nowNanos, Config config, boolean manual, boolean override) {
        if (count >= LIMIT) return Decision.LIMIT;
        if (!Double.isFinite(candidate.lat) || !Double.isFinite(candidate.lon)
                || Math.abs(candidate.lat) > 90 || Math.abs(candidate.lon) > 180
                || !Double.isFinite(candidate.accuracy) || candidate.accuracy < 0)
            return Decision.INVALID;
        long ageMs = (nowNanos - candidate.nanos) / 1000000;
        if (ageMs < 0 || ageMs > config.maxAgeMs) return Decision.STALE;
        if (candidate.accuracy > config.accuracy && !(manual && override))
            return Decision.ACCURACY;
        if (previous == null) return Decision.ACCEPT;
        long deltaNs = candidate.nanos - previous.nanos;
        if (deltaNs <= 0) return Decision.TIME;
        double distance = Geometry.distance(previous, candidate);
        if (distance < config.duplicate) return Decision.DUPLICATE;
        if (!(manual && override)) {
            if (distance > 50 && deltaNs < 5000000000L) return Decision.JUMP;
            if (distance / (deltaNs / 1e9) > config.speed) return Decision.SPEED;
        }
        if (!manual && distance < config.movement && deltaNs / 1000000 < config.intervalMs)
            return Decision.MOVEMENT;
        return Decision.ACCEPT;
    }

    private CaptureRules() {}
}

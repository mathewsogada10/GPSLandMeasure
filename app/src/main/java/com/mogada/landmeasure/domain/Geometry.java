package com.mogada.landmeasure.domain;

import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.PolygonArea;
import net.sf.geographiclib.PolygonResult;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;

import java.util.List;

public final class Geometry {
    public static final String ALGORITHM = "GeographicLib WGS84 2.1 / Karney; conversion-v1";
    public enum Error { NONE, TOO_FEW, INVALID_COORDINATE, DUPLICATE, INTERSECTION, ZERO_AREA }

    public record Result(double area, double perimeter, double closure, Error error, int point, int other) {
        public boolean valid() { return error == Error.NONE; }
    }

    public static double distance(Models.Point a, Models.Point b) {
        return Geodesic.WGS84.Inverse(a.lat, a.lon, b.lat, b.lon).s12;
    }

    public static double unwrap(double lon, double previous) {
        return previous + Math.IEEEremainder(lon - previous, 360);
    }

    public static Result calculate(List<Models.Point> points) {
        if (points.size() < 3) return invalid(Error.TOO_FEW, points.size(), -1);
        Coordinate[] ring = new Coordinate[points.size() + 1];
        PolygonArea polygon = new PolygonArea(Geodesic.WGS84, false);
        double previous = points.get(0).lon;
        for (int i = 0; i < points.size(); i++) {
            Models.Point p = points.get(i);
            if (!Double.isFinite(p.lat) || !Double.isFinite(p.lon) || Math.abs(p.lat) > 90
                    || Math.abs(p.lon) > 180) return invalid(Error.INVALID_COORDINATE, i, -1);
            if (i > 0 && distance(points.get(i - 1), p) < 1)
                return invalid(Error.DUPLICATE, i - 1, i);
            double x = unwrap(p.lon, previous);
            ring[i] = new Coordinate(x, p.lat);
            previous = x;
            polygon.AddPoint(p.lat, p.lon);
        }
        if (distance(points.get(0), points.get(points.size() - 1)) < 1)
            return invalid(Error.DUPLICATE, 0, points.size() - 1);
        ring[points.size()] = ring[0].copy();
        IsValidOp validation = new IsValidOp(new GeometryFactory().createPolygon(ring));
        if (!validation.isValid()) {
            TopologyValidationError error = validation.getValidationError();
            Coordinate at = error.getCoordinate();
            int first = -1, second = -1;
            if (at != null) {
                for (int i = 0; i < points.size(); i++) {
                    if (org.locationtech.jts.algorithm.Distance.pointToSegment(
                            at, ring[i], ring[i + 1]) < 1e-8) {
                        if (first < 0) first = i; else { second = i; break; }
                    }
                }
            }
            return invalid(Error.INTERSECTION, first, second);
        }
        PolygonResult result = polygon.Compute(false, true);
        double area = Math.abs(result.area);
        if (!Double.isFinite(area) || area < 0.01) return invalid(Error.ZERO_AREA, -1, -1);
        return new Result(area, result.perimeter, distance(points.get(0),
                points.get(points.size() - 1)), Error.NONE, -1, -1);
    }

    private static Result invalid(Error error, int point, int other) {
        return new Result(0, 0, 0, error, point, other);
    }
}

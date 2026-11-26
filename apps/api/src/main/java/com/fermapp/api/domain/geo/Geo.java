package com.fermapp.api.domain.geo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fermapp.api.common.util.Js;
import java.util.ArrayList;
import java.util.List;

/**
 * Parcel geometry (geo.ts): area on the sphere, perimeter, centre and validation.
 * Positions are [longitude, latitude], as in GeoJSON.
 */
public final class Geo {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** A GeoJSON position: [lng, lat]. It is read and written as a two-element array. */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder({"lng", "lat"})
    public record Position(double lng, double lat) {
    }

    /** A GeoJSON Polygon: the outer ring followed by the holes. {@code type} is always "Polygon". */
    public record Polygon(String type, List<List<Position>> coordinates) {
        public Polygon {
            type = "Polygon";
            coordinates = coordinates == null ? List.of() : coordinates;
        }

        public static Polygon of(List<List<Position>> coordinates) {
            return new Polygon("Polygon", coordinates);
        }
    }

    /** {@code { lat, lng }}. */
    public record LatLng(double lat, double lng) {
    }

    /** Hectares, outer perimeter and GPS centre. */
    public record GeoMetrics(double areaHa, double perimeterM, LatLng center) {
    }

    private Geo() {
    }

    private static double toRad(double deg) {
        return deg * Math.PI / 180;
    }

    private static double round(double value, int digits) {
        return Js.round(value, digits);
    }

    private static boolean samePoint(Position a, Position b) {
        return a.lng() == b.lng() && a.lat() == b.lat();
    }

    /** Closes the ring if the last point differs from the first. */
    public static List<Position> closeRing(List<Position> ring) {
        if (ring.isEmpty()) {
            return List.of();
        }
        Position first = ring.get(0);
        Position last = ring.get(ring.size() - 1);
        if (samePoint(first, last)) {
            return ring;
        }
        List<Position> closed = new ArrayList<>(ring);
        closed.add(first);
        return closed;
    }

    public static double haversineM(Position a, Position b) {
        double dLat = toRad(b.lat() - a.lat());
        double dLng = toRad(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(toRad(a.lat())) * Math.cos(toRad(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Area of a ring on the sphere, in m² (Chamberlain-Duquette method). */
    private static double ringAreaM2(List<Position> ring) {
        int n = ring.size() - 1;
        if (n < 3) {
            return 0;
        }
        double total = 0;
        for (int i = 0; i < n; i++) {
            Position prev = ring.get((i + n - 1) % n);
            Position current = ring.get(i);
            Position next = ring.get((i + 1) % n);
            total += toRad(next.lng() - prev.lng()) * Math.sin(toRad(current.lat()));
        }
        return Math.abs(total * EARTH_RADIUS_M * EARTH_RADIUS_M / 2);
    }

    private static double ringLengthM(List<Position> ring) {
        double total = 0;
        for (int i = 1; i < ring.size(); i++) {
            total += haversineM(ring.get(i - 1), ring.get(i));
        }
        return total;
    }

    private static LatLng ringCentroid(List<Position> ring) {
        Position origin = ring.get(0);
        double doubledArea = 0;
        double cx = 0;
        double cy = 0;
        for (int i = 0; i < ring.size() - 1; i++) {
            double x0 = ring.get(i).lng() - origin.lng();
            double y0 = ring.get(i).lat() - origin.lat();
            double x1 = ring.get(i + 1).lng() - origin.lng();
            double y1 = ring.get(i + 1).lat() - origin.lat();
            double cross = x0 * y1 - x1 * y0;
            doubledArea += cross;
            cx += (x0 + x1) * cross;
            cy += (y0 + y1) * cross;
        }
        if (Math.abs(doubledArea) < 1e-14) {
            return new LatLng(origin.lat(), origin.lng());
        }
        return new LatLng(origin.lat() + cy / (3 * doubledArea), origin.lng() + cx / (3 * doubledArea));
    }

    /** Hectares, outer perimeter and GPS centre. Holes are subtracted from the area. */
    public static GeoMetrics computeGeoMetrics(Polygon polygon) {
        List<List<Position>> rings = polygon.coordinates().stream().map(Geo::closeRing).toList();
        if (rings.isEmpty() || rings.get(0).size() < 4) {
            throw new IllegalArgumentException("The polygon needs at least 3 points.");
        }
        List<Position> outer = rings.get(0);
        double areaM2 = ringAreaM2(outer);
        for (int i = 1; i < rings.size(); i++) {
            areaM2 -= ringAreaM2(rings.get(i));
        }
        LatLng center = ringCentroid(outer);
        return new GeoMetrics(
                round(Math.max(0, areaM2) / 10_000, 4),
                round(ringLengthM(outer), 1),
                new LatLng(round(center.lat(), 7), round(center.lng(), 7)));
    }

    private static int orientation(Position a, Position b, Position c) {
        double value = (b.lat() - a.lat()) * (c.lng() - b.lng()) - (b.lng() - a.lng()) * (c.lat() - b.lat());
        if (Math.abs(value) < 1e-16) {
            return 0;
        }
        return value > 0 ? 1 : 2;
    }

    private static boolean onSegment(Position a, Position b, Position c) {
        return b.lng() <= Math.max(a.lng(), c.lng()) && b.lng() >= Math.min(a.lng(), c.lng())
                && b.lat() <= Math.max(a.lat(), c.lat()) && b.lat() >= Math.min(a.lat(), c.lat());
    }

    private static boolean segmentsIntersect(Position p1, Position q1, Position p2, Position q2) {
        int o1 = orientation(p1, q1, p2);
        int o2 = orientation(p1, q1, q2);
        int o3 = orientation(p2, q2, p1);
        int o4 = orientation(p2, q2, q1);
        if (o1 != o2 && o3 != o4) {
            return true;
        }
        return (o1 == 0 && onSegment(p1, p2, q1))
                || (o2 == 0 && onSegment(p1, q2, q1))
                || (o3 == 0 && onSegment(p2, p1, q2))
                || (o4 == 0 && onSegment(p2, q1, q2));
    }

    public static boolean isSelfIntersecting(List<Position> ring) {
        List<Position> closed = closeRing(ring);
        int segments = closed.size() - 1;
        for (int i = 0; i < segments; i++) {
            for (int j = i + 1; j < segments; j++) {
                boolean adjacent = j == i + 1 || (i == 0 && j == segments - 1);
                if (!adjacent && segmentsIntersect(closed.get(i), closed.get(i + 1), closed.get(j), closed.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the reason the polygon is invalid, or null if it is valid. */
    public static String validatePolygon(Polygon polygon) {
        if (polygon.coordinates().isEmpty()) {
            return "The polygon has no outline.";
        }
        List<Position> closed = closeRing(polygon.coordinates().get(0));
        if (closed.size() < 4) {
            return "The outline needs at least 3 distinct points.";
        }
        for (List<Position> ring : polygon.coordinates()) {
            for (Position point : ring) {
                if (!Double.isFinite(point.lng()) || !Double.isFinite(point.lat())
                        || Math.abs(point.lng()) > 180 || Math.abs(point.lat()) > 90) {
                    return "Coordinates must be valid longitude and latitude values.";
                }
            }
        }
        if (isSelfIntersecting(closed)) {
            return "The parcel outline intersects itself.";
        }
        if (computeGeoMetrics(polygon).areaHa() <= 0) {
            return "The parcel area must be greater than zero.";
        }
        return null;
    }
}

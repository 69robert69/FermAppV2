package com.fermapp.api.domain.geo

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fermapp.api.common.util.Js
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parcel geometry (geo.ts): area on the sphere, perimeter, centre and validation.
 * Positions are [longitude, latitude], as in GeoJSON.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** A GeoJSON position: [lng, lat]. It is read and written as a two-element array. */
    @JvmRecord
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder("lng", "lat")
    data class Position(val lng: Double, val lat: Double)

    /**
     * A GeoJSON Polygon: the outer ring followed by the holes. [type] is always "Polygon":
     * create it with [of], and JSON is read through the same normalisation.
     */
    @JvmRecord
    data class Polygon(val type: String, val coordinates: List<List<Position>>) {
        companion object {
            @JvmStatic
            fun of(coordinates: List<List<Position>>?): Polygon = Polygon("Polygon", coordinates ?: listOf())

            /** Any "type" in the JSON becomes "Polygon"; missing coordinates become an empty list. */
            @JvmStatic
            @JsonCreator
            fun fromJson(
                @JsonProperty("type") @Suppress("UNUSED_PARAMETER") type: String?,
                @JsonProperty("coordinates") coordinates: List<List<Position>>?,
            ): Polygon = of(coordinates)
        }
    }

    /** `{ lat, lng }`. */
    @JvmRecord
    data class LatLng(val lat: Double, val lng: Double)

    /** Hectares, outer perimeter and GPS centre. */
    @JvmRecord
    data class GeoMetrics(val areaHa: Double, val perimeterM: Double, val center: LatLng)

    private fun toRad(deg: Double) = deg * Math.PI / 180

    private fun samePoint(a: Position, b: Position) = a.lng == b.lng && a.lat == b.lat

    /** Closes the ring if the last point differs from the first. */
    @JvmStatic
    fun closeRing(ring: List<Position>): List<Position> = when {
        ring.isEmpty() -> listOf()
        samePoint(ring.first(), ring.last()) -> ring
        else -> ring + ring.first()
    }

    @JvmStatic
    fun haversineM(a: Position, b: Position): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLng = toRad(b.lng - a.lng)
        val h = Math.pow(sin(dLat / 2), 2.0) + cos(toRad(a.lat)) * cos(toRad(b.lat)) * Math.pow(sin(dLng / 2), 2.0)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Area of a ring on the sphere, in m² (Chamberlain-Duquette method). */
    private fun ringAreaM2(ring: List<Position>): Double {
        val n = ring.size - 1
        if (n < 3) {
            return 0.0
        }
        var total = 0.0
        for (i in 0 until n) {
            val prev = ring[(i + n - 1) % n]
            val next = ring[(i + 1) % n]
            total += toRad(next.lng - prev.lng) * sin(toRad(ring[i].lat))
        }
        return Math.abs(total * EARTH_RADIUS_M * EARTH_RADIUS_M / 2)
    }

    private fun ringLengthM(ring: List<Position>): Double {
        var total = 0.0
        for (i in 1 until ring.size) {
            total += haversineM(ring[i - 1], ring[i])
        }
        return total
    }

    private fun ringCentroid(ring: List<Position>): LatLng {
        val origin = ring[0]
        var doubledArea = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until ring.size - 1) {
            val x0 = ring[i].lng - origin.lng
            val y0 = ring[i].lat - origin.lat
            val x1 = ring[i + 1].lng - origin.lng
            val y1 = ring[i + 1].lat - origin.lat
            val cross = x0 * y1 - x1 * y0
            doubledArea += cross
            cx += (x0 + x1) * cross
            cy += (y0 + y1) * cross
        }
        if (Math.abs(doubledArea) < 1e-14) {
            return LatLng(origin.lat, origin.lng)
        }
        return LatLng(origin.lat + cy / (3 * doubledArea), origin.lng + cx / (3 * doubledArea))
    }

    /** Hectares, outer perimeter and GPS centre. Holes are subtracted from the area. */
    @JvmStatic
    fun computeGeoMetrics(polygon: Polygon): GeoMetrics {
        val rings = polygon.coordinates.map(::closeRing)
        require(rings.isNotEmpty() && rings[0].size >= 4) { "The polygon needs at least 3 points." }
        val outer = rings[0]
        // Holes are subtracted one by one, in the same order of floating-point operations as before.
        val areaM2 = rings.drop(1).fold(ringAreaM2(outer)) { area, hole -> area - ringAreaM2(hole) }
        val center = ringCentroid(outer)
        return GeoMetrics(
            Js.round(max(0.0, areaM2) / 10_000, 4),
            Js.round(ringLengthM(outer), 1),
            LatLng(Js.round(center.lat, 7), Js.round(center.lng, 7)),
        )
    }

    private fun orientation(a: Position, b: Position, c: Position): Int {
        val value = (b.lat - a.lat) * (c.lng - b.lng) - (b.lng - a.lng) * (c.lat - b.lat)
        return when {
            Math.abs(value) < 1e-16 -> 0
            value > 0 -> 1
            else -> 2
        }
    }

    private fun onSegment(a: Position, b: Position, c: Position) =
        b.lng <= max(a.lng, c.lng) && b.lng >= min(a.lng, c.lng) &&
            b.lat <= max(a.lat, c.lat) && b.lat >= min(a.lat, c.lat)

    private fun segmentsIntersect(p1: Position, q1: Position, p2: Position, q2: Position): Boolean {
        val o1 = orientation(p1, q1, p2)
        val o2 = orientation(p1, q1, q2)
        val o3 = orientation(p2, q2, p1)
        val o4 = orientation(p2, q2, q1)
        if (o1 != o2 && o3 != o4) {
            return true
        }
        return (o1 == 0 && onSegment(p1, p2, q1)) ||
            (o2 == 0 && onSegment(p1, q2, q1)) ||
            (o3 == 0 && onSegment(p2, p1, q2)) ||
            (o4 == 0 && onSegment(p2, q1, q2))
    }

    @JvmStatic
    fun isSelfIntersecting(ring: List<Position>): Boolean {
        val closed = closeRing(ring)
        val segments = closed.size - 1
        for (i in 0 until segments) {
            for (j in i + 1 until segments) {
                val adjacent = j == i + 1 || (i == 0 && j == segments - 1)
                if (!adjacent && segmentsIntersect(closed[i], closed[i + 1], closed[j], closed[j + 1])) {
                    return true
                }
            }
        }
        return false
    }

    /** Returns the reason the polygon is invalid, or null if it is valid. */
    @JvmStatic
    fun validatePolygon(polygon: Polygon): String? {
        if (polygon.coordinates.isEmpty()) {
            return "The polygon has no outline."
        }
        val closed = closeRing(polygon.coordinates[0])
        if (closed.size < 4) {
            return "The outline needs at least 3 distinct points."
        }
        val invalidPoint = polygon.coordinates.flatten().any { point ->
            !point.lng.isFinite() || !point.lat.isFinite() || Math.abs(point.lng) > 180 || Math.abs(point.lat) > 90
        }
        if (invalidPoint) {
            return "Coordinates must be valid longitude and latitude values."
        }
        if (isSelfIntersecting(closed)) {
            return "The parcel outline intersects itself."
        }
        if (computeGeoMetrics(polygon).areaHa <= 0) {
            return "The parcel area must be greater than zero."
        }
        return null
    }
}

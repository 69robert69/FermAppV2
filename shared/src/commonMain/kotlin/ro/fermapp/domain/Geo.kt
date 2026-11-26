package ro.fermapp.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** GeoJSON position, serialized as [longitude, latitude]. */
@Serializable(with = PositionSerializer::class)
data class Position(val lng: Double, val lat: Double)

object PositionSerializer : KSerializer<Position> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor: SerialDescriptor = SerialDescriptor("ro.fermapp.Position", delegate.descriptor)
    override fun serialize(encoder: Encoder, value: Position) =
        encoder.encodeSerializableValue(delegate, listOf(value.lng, value.lat))

    override fun deserialize(decoder: Decoder): Position {
        val values = decoder.decodeSerializableValue(delegate)
        return Position(lng = values[0], lat = values[1])
    }
}

@Serializable
data class PolygonGeometry(
    val type: String = "Polygon",
    val coordinates: List<List<Position>>,
)

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class GeoMetrics(val areaHa: Double, val perimeterM: Double, val center: LatLng)

private const val EARTH_RADIUS_M = 6_371_008.8

internal fun toRad(deg: Double) = deg * PI / 180

/** Closes the ring if the last point differs from the first. */
fun closeRing(ring: List<Position>): List<Position> {
    val first = ring.firstOrNull() ?: return emptyList()
    return if (first == ring.last()) ring else ring + first
}

fun polygonFromPoints(points: List<Position>) = PolygonGeometry(coordinates = listOf(closeRing(points)))

fun haversineM(a: Position, b: Position): Double {
    val dLat = toRad(b.lat - a.lat)
    val dLng = toRad(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) + cos(toRad(a.lat)) * cos(toRad(b.lat)) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/** Area of a ring on the sphere, in m² (Chamberlain-Duquette method). */
private fun ringAreaM2(ring: List<Position>): Double {
    val n = ring.size - 1
    if (n < 3) return 0.0
    var total = 0.0
    for (i in 0 until n) {
        val prev = ring[(i + n - 1) % n]
        val current = ring[i]
        val next = ring[(i + 1) % n]
        total += toRad(next.lng - prev.lng) * sin(toRad(current.lat))
    }
    return abs((total * EARTH_RADIUS_M * EARTH_RADIUS_M) / 2)
}

private fun ringLengthM(ring: List<Position>): Double {
    var total = 0.0
    for (i in 1 until ring.size) total += haversineM(ring[i - 1], ring[i])
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
    if (abs(doubledArea) < 1e-14) return LatLng(lat = origin.lat, lng = origin.lng)
    return LatLng(lng = origin.lng + cx / (3 * doubledArea), lat = origin.lat + cy / (3 * doubledArea))
}

/** Hectares, outer perimeter and GPS centre. Holes are subtracted from the area. */
fun computeGeoMetrics(polygon: PolygonGeometry): GeoMetrics {
    val rings = polygon.coordinates.map(::closeRing)
    val outer = rings.firstOrNull()
    require(outer != null && outer.size >= 4) { "The polygon needs at least 3 points." }
    val areaM2 = rings.drop(1).fold(ringAreaM2(outer)) { area, hole -> area - ringAreaM2(hole) }
    val center = ringCentroid(outer)
    return GeoMetrics(
        areaHa = jsRound(max(0.0, areaM2) / 10_000, 4),
        perimeterM = jsRound(ringLengthM(outer), 1),
        center = LatLng(lat = jsRound(center.lat, 7), lng = jsRound(center.lng, 7)),
    )
}

private fun orientation(a: Position, b: Position, c: Position): Int {
    val value = (b.lat - a.lat) * (c.lng - b.lng) - (b.lng - a.lng) * (c.lat - b.lat)
    if (abs(value) < 1e-16) return 0
    return if (value > 0) 1 else 2
}

private fun onSegment(a: Position, b: Position, c: Position): Boolean =
    b.lng <= max(a.lng, c.lng) && b.lng >= min(a.lng, c.lng) &&
        b.lat <= max(a.lat, c.lat) && b.lat >= min(a.lat, c.lat)

private fun segmentsIntersect(p1: Position, q1: Position, p2: Position, q2: Position): Boolean {
    val o1 = orientation(p1, q1, p2)
    val o2 = orientation(p1, q1, q2)
    val o3 = orientation(p2, q2, p1)
    val o4 = orientation(p2, q2, q1)
    if (o1 != o2 && o3 != o4) return true
    return (o1 == 0 && onSegment(p1, p2, q1)) ||
        (o2 == 0 && onSegment(p1, q2, q1)) ||
        (o3 == 0 && onSegment(p2, p1, q2)) ||
        (o4 == 0 && onSegment(p2, q1, q2))
}

fun isSelfIntersecting(ring: List<Position>): Boolean {
    val closed = closeRing(ring)
    val segments = closed.size - 1
    for (i in 0 until segments) {
        for (j in i + 1 until segments) {
            val adjacent = j == i + 1 || (i == 0 && j == segments - 1)
            if (adjacent) continue
            if (segmentsIntersect(closed[i], closed[i + 1], closed[j], closed[j + 1])) return true
        }
    }
    return false
}

/** Returns the reason the polygon is invalid, or null if it is valid. */
fun validatePolygon(polygon: PolygonGeometry): String? {
    val outer = polygon.coordinates.firstOrNull() ?: return "The polygon has no outline."
    val closed = closeRing(outer)
    if (closed.size < 4) return "The outline needs at least 3 distinct points."
    for (ring in polygon.coordinates) {
        for ((lng, lat) in ring) {
            if (!lng.isFinite() || !lat.isFinite() || abs(lng) > 180 || abs(lat) > 90) {
                return "Coordinates must be valid longitude and latitude values."
            }
        }
    }
    if (isSelfIntersecting(closed)) return "The parcel outline intersects itself."
    if (computeGeoMetrics(polygon).areaHa <= 0) return "The parcel area must be greater than zero."
    return null
}

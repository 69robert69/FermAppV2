package ro.fermapp.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val METERS_PER_DEG_LAT = 110_540.0
private const val METERS_PER_DEG_LNG = 111_320.0
private const val MIN_PASSES = 5
private const val MAX_PASSES = 41

/** A point on the local plane, in metres. */
internal data class PlanePoint(val x: Double, val y: Double)

/** Local planar projection, accurate enough at the scale of a parcel. */
internal class Projection(ring: List<Position>) {
    private val origin = ring[0]
    private val cosLat = cos(origin.lat * PI / 180)

    fun toPlane(p: Position) = PlanePoint(
        (p.lng - origin.lng) * METERS_PER_DEG_LNG * cosLat,
        (p.lat - origin.lat) * METERS_PER_DEG_LAT,
    )

    fun toGeo(p: PlanePoint) = Position(
        lng = origin.lng + p.x / (METERS_PER_DEG_LNG * cosLat),
        lat = origin.lat + p.y / METERS_PER_DEG_LAT,
    )
}

internal fun rotate(p: PlanePoint, angle: Double) = PlanePoint(
    p.x * cos(angle) - p.y * sin(angle),
    p.x * sin(angle) + p.y * cos(angle),
)

/** Machines usually work parallel to the longest side of the parcel. */
internal fun workAngle(points: List<PlanePoint>): Double {
    var longest = 0.0
    var angle = 0.0
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        val length = hypot(dx, dy)
        if (length > longest) {
            longest = length
            angle = atan2(dy, dx)
        }
    }
    return angle
}

/** Longest segment of the horizontal line y that lies inside the polygon. */
internal fun passSegment(points: List<PlanePoint>, y: Double): Pair<Double, Double>? {
    val crossings = mutableListOf<Double>()
    for (i in 1 until points.size) {
        val (x0, y0) = points[i - 1]
        val (x1, y1) = points[i]
        if ((y0 <= y) != (y1 <= y)) crossings.add(x0 + ((y - y0) / (y1 - y0)) * (x1 - x0))
    }
    crossings.sort()
    var best: Pair<Double, Double>? = null
    var i = 0
    while (i + 1 < crossings.size) {
        val segment = crossings[i] to crossings[i + 1]
        if (best == null || segment.second - segment.first > best.second - best.first) best = segment
        i += 2
    }
    return best
}

data class FieldPathOptions(
    /** Working width, which determines the number of passes. */
    val workingWidthM: Double? = null,
    /** Fixed number of passes. A single pass crosses the parcel through the middle. */
    val passes: Int? = null,
)

/**
 * Back-and-forth path of a machine across the parcel.
 * The number of passes is odd, so that at 50% the machine is in the centre of the parcel.
 */
fun buildFieldPath(outerRing: List<Position>, options: FieldPathOptions = FieldPathOptions()): List<Position> {
    val ring = closeRing(outerRing)
    if (ring.size < 4) return emptyList()
    val projection = Projection(ring)
    val plane = ring.map(projection::toPlane)
    val angle = workAngle(plane)
    val aligned = plane.map { rotate(it, -angle) }

    val ys = aligned.map { it.y }
    val minY = ys.min()
    val acrossM = ys.max() - minY
    if (acrossM <= 0) return emptyList()

    val width = options.workingWidthM
    val wanted = options.passes ?: if (width != null && width != 0.0) jsRoundInt(acrossM / width) else MIN_PASSES
    val bounded = if (options.passes != null && options.passes != 0) max(1, wanted) else min(MAX_PASSES, max(MIN_PASSES, wanted))
    val passes = if (bounded % 2 == 0) bounded + 1 else bounded

    val path = mutableListOf<Position>()
    for (pass in 0 until passes) {
        val y = minY + ((pass + 0.5) / passes) * acrossM
        val segment = passSegment(aligned, y) ?: continue
        val (from, to) = if (pass % 2 == 0) segment else segment.second to segment.first
        path.add(projection.toGeo(rotate(PlanePoint(from, y), angle)))
        path.add(projection.toGeo(rotate(PlanePoint(to, y), angle)))
    }
    return path
}

data class FieldPosition(
    val lng: Double,
    val lat: Double,
    /** Heading, in degrees from north. */
    val headingDeg: Double,
    /** Portion of the path already covered. */
    val worked: List<Position>,
)

/** Position on the path corresponding to the progress percentage. */
fun positionOnPath(path: List<Position>, percent: Double): FieldPosition? {
    val start = path.firstOrNull() ?: return null
    val projection = Projection(path)
    val plane = path.map(projection::toPlane)
    val lengths = (1 until plane.size).map { i -> hypot(plane[i].x - plane[i - 1].x, plane[i].y - plane[i - 1].y) }
    val total = lengths.sum()
    var remaining = (percent.clamp(0.0, 100.0) / 100) * total

    val worked = mutableListOf(start)
    for (i in lengths.indices) {
        val length = lengths[i]
        val from = path[i]
        val to = path[i + 1]
        val dx = plane[i + 1].x - plane[i].x
        val dy = plane[i + 1].y - plane[i].y
        val headingDeg = ((atan2(dx, dy) * 180) / PI + 360) % 360
        if (remaining <= length || i == lengths.size - 1) {
            val ratio = if (length > 0) min(1.0, remaining / length) else 0.0
            val lng = from.lng + (to.lng - from.lng) * ratio
            val lat = from.lat + (to.lat - from.lat) * ratio
            worked.add(Position(lng, lat))
            return FieldPosition(lng, lat, headingDeg, worked)
        }
        remaining -= length
        worked.add(to)
    }
    return FieldPosition(start.lng, start.lat, 0.0, worked)
}

/** Checks whether a point lies inside the outline. */
fun pointInRing(point: Position, ring: List<Position>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val (xi, yi) = ring[i]
        val (xj, yj) = ring[j]
        if ((yi > point.lat) != (yj > point.lat) && point.lng < ((xj - xi) * (point.lat - yi)) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}

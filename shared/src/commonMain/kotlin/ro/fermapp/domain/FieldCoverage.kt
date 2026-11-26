package ro.fermapp.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * A machine's working plan on a parcel: straight passes, line after line,
 * joined by U-turns on the headland, and the area covered at any progress.
 *
 * Everything is computed in a local plane rotated so that the passes run
 * along x; strips are stacked along y, one working width each.
 */

private const val METERS_PER_DEG_LAT = 110_540.0
private const val METERS_PER_DEG_LNG = 111_320.0

/** Enough passes to look real on large parcels while keeping the geometry light on a phone. */
private const val DEFAULT_MAX_PASSES = 120
private const val TURN_SEGMENTS = 8

data class Frame(val originLng: Double, val originLat: Double, val cosLat: Double, val angle: Double)

private fun toAligned(frame: Frame, p: Position): PlanePoint {
    val x = (p.lng - frame.originLng) * METERS_PER_DEG_LNG * frame.cosLat
    val y = (p.lat - frame.originLat) * METERS_PER_DEG_LAT
    val c = cos(-frame.angle)
    val s = sin(-frame.angle)
    return PlanePoint(x * c - y * s, x * s + y * c)
}

private fun toGeo(frame: Frame, p: PlanePoint): Position {
    val c = cos(frame.angle)
    val s = sin(frame.angle)
    val x = p.x * c - p.y * s
    val y = p.x * s + p.y * c
    return Position(frame.originLng + x / (METERS_PER_DEG_LNG * frame.cosLat), frame.originLat + y / METERS_PER_DEG_LAT)
}

/** Passes run parallel to the longest side of the parcel, as most operators drive. */
private fun longestSideAngle(ring: List<Position>, originLng: Double, originLat: Double, cosLat: Double): Double {
    val plane = ring.map { PlanePoint((it.lng - originLng) * METERS_PER_DEG_LNG * cosLat, (it.lat - originLat) * METERS_PER_DEG_LAT) }
    return workAngle(plane)
}

/** Sutherland-Hodgman clip of a polygon against one axis-aligned half-plane (axis 0 = x, 1 = y). */
private fun clip(points: List<PlanePoint>, axis: Int, value: Double, keepBelow: Boolean): List<PlanePoint> {
    fun coord(p: PlanePoint) = if (axis == 0) p.x else p.y
    fun inside(p: PlanePoint) = if (keepBelow) coord(p) <= value else coord(p) >= value
    val out = mutableListOf<PlanePoint>()
    for (i in points.indices) {
        val current = points[i]
        val previous = points[(i + points.size - 1) % points.size]
        val currentIn = inside(current)
        val previousIn = inside(previous)
        if (currentIn != previousIn) {
            val t = (value - coord(previous)) / (coord(current) - coord(previous))
            out.add(PlanePoint(previous.x + (current.x - previous.x) * t, previous.y + (current.y - previous.y) * t))
        }
        if (currentIn) out.add(current)
    }
    return out
}

internal fun planarArea(points: List<PlanePoint>): Double {
    var sum = 0.0
    for (i in points.indices) {
        val (x0, y0) = points[i]
        val (x1, y1) = points[(i + 1) % points.size]
        sum += x0 * y1 - x1 * y0
    }
    return abs(sum) / 2
}

data class FieldPass(
    /** Aligned-plane y of the pass centre line. */
    val y: Double,
    val fromX: Double,
    val toX: Double,
    /** Route distance where the pass starts and ends, in metres. */
    val startM: Double,
    val endM: Double,
)

class FieldPlan internal constructor(
    val frame: Frame,
    /** The parcel outline in the aligned plane, without the closing point. */
    internal val outline: List<PlanePoint>,
    val minY: Double,
    val stripM: Double,
    val passes: List<FieldPass>,
    /** The full route, passes and turns, in the aligned plane. */
    internal val route: List<PlanePoint>,
    /** Distance from the start of the route to each route point. */
    val distances: List<Double>,
    val totalM: Double,
    /** Driven distance spent working (without turns). */
    val workingM: Double,
    val areaM2: Double,
)

/** Plans the passes and headland turns of a machine with the given working width. */
fun buildFieldPlan(outerRing: List<Position>, workingWidthM: Double, maxPasses: Int = DEFAULT_MAX_PASSES): FieldPlan? {
    val ring = closeRing(outerRing)
    if (ring.size < 4 || !(workingWidthM > 0)) return null
    val originLng = ring[0].lng
    val originLat = ring[0].lat
    val cosLat = cos(originLat * PI / 180)
    val frame = Frame(originLng, originLat, cosLat, longestSideAngle(ring, originLng, originLat, cosLat))
    val closed = ring.map { toAligned(frame, it) }
    val outline = closed.dropLast(1)
    val ys = outline.map { it.y }
    val minY = ys.min()
    val acrossM = ys.max() - minY
    if (acrossM <= 0) return null

    val count = min(maxPasses, max(1, jsRoundInt(acrossM / workingWidthM)))
    val stripM = acrossM / count
    val passes = mutableListOf<FieldPass>()
    val route = mutableListOf<PlanePoint>()
    val distances = mutableListOf<Double>()
    var distance = 0.0
    fun push(point: PlanePoint) {
        val last = route.lastOrNull()
        if (last != null) distance += hypot(point.x - last.x, point.y - last.y)
        route.add(point)
        distances.add(distance)
    }

    for (index in 0 until count) {
        val y = minY + (index + 0.5) * stripM
        val span = passSegment(closed, y) ?: continue
        val forward = passes.size % 2 == 0
        val (fromX, toX) = if (forward) span else span.second to span.first

        val previous = passes.lastOrNull()
        if (previous != null) {
            // U-turn on the headland: run out past the longer of the two pass ends, then a half circle.
            val outward = if (previous.toX > previous.fromX) 1 else -1
            val turnX = if (outward > 0) max(previous.toX, fromX) else min(previous.toX, fromX)
            val radius = (y - previous.y) / 2
            if (abs(turnX - previous.toX) > 0.01) push(PlanePoint(turnX, previous.y))
            for (step in 1 until TURN_SEGMENTS) {
                val theta = -PI / 2 + (PI * step) / TURN_SEGMENTS
                push(PlanePoint(turnX + outward * radius * cos(theta), previous.y + radius + radius * sin(theta)))
            }
            if (abs(turnX - fromX) > 0.01) push(PlanePoint(turnX, y))
        }
        push(PlanePoint(fromX, y))
        val startM = distance
        push(PlanePoint(toX, y))
        passes.add(FieldPass(y, fromX, toX, startM, distance))
    }
    if (passes.isEmpty()) return null
    val workingM = passes.sumOf { abs(it.toX - it.fromX) }
    return FieldPlan(frame, outline, minY, stripM, passes, route, distances, distance, workingM, planarArea(outline))
}

data class FieldProgress(
    val lng: Double,
    val lat: Double,
    /** Heading in degrees clockwise from north, for rotating the machine icon. */
    val headingDeg: Double,
    /** Zero-based pass being worked, or the last pass when finished. */
    val passIndex: Int,
    val passCount: Int,
    val turning: Boolean,
    val finished: Boolean,
)

/** Index of the route segment that contains the given distance. */
private fun segmentAt(distances: List<Double>, meters: Double): Int {
    var lo = 0
    var hi = distances.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (distances[mid] <= meters) lo = mid else hi = mid
    }
    return lo
}

private fun clampPercent(percent: Double) = percent.clamp(0.0, 100.0)

/** Index of the last usable segment start; a one-point route has none, so it stays at 0. */
private fun lastSegment(plan: FieldPlan) = max(0, plan.route.size - 2)

/**
 * Where the machine is at a progress percentage. Cheap enough to run on every
 * animation frame: one binary search and an interpolation.
 */
fun fieldPositionAt(plan: FieldPlan, percent: Double): FieldProgress {
    val meters = (clampPercent(percent) / 100) * plan.totalM
    val index = min(segmentAt(plan.distances, meters), lastSegment(plan))
    val a = plan.route[index]
    val b = plan.route.getOrNull(index + 1) ?: a
    val length = (plan.distances.getOrNull(index + 1) ?: plan.distances[index]) - plan.distances[index]
    val t = if (length > 0) min(1.0, (meters - plan.distances[index]) / length) else 0.0
    val point = PlanePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    val geo = toGeo(plan.frame, point)

    // Heading from the segment direction, turned back from the aligned plane to the map.
    val ga = toGeo(plan.frame, a)
    val gb = toGeo(plan.frame, b)
    val dx = (gb.lng - ga.lng) * plan.frame.cosLat
    val dy = gb.lat - ga.lat
    val headingDeg = if (dx == 0.0 && dy == 0.0) 0.0 else ((atan2(dx, dy) * 180) / PI + 360) % 360

    var passIndex = plan.passes.indexOfFirst { meters < it.endM }
    if (passIndex < 0) passIndex = plan.passes.size - 1
    val pass = plan.passes[passIndex]
    return FieldProgress(
        lng = geo.lng,
        lat = geo.lat,
        headingDeg = headingDeg,
        passIndex = passIndex,
        passCount = plan.passes.size,
        turning = meters < pass.startM,
        finished = clampPercent(percent) >= 100,
    )
}

data class FieldCoverage(
    /** Worked area as map polygons (outer rings). Usually one or two pieces. */
    val worked: List<List<Position>>,
    /** Share of the parcel area already worked, 0-100. */
    val coveredPct: Double,
    /** Centre lines of the finished passes plus the current one, for rows, furrows or stubble. */
    val rows: List<List<Position>>,
    /** The last metres driven, for a discreet GPS trail. */
    val trail: List<Position>,
)

/** The last metres driven, cheap enough for every animation frame. */
fun fieldTrailAt(plan: FieldPlan, percent: Double, trailM: Double = 80.0): List<Position> {
    val meters = (clampPercent(percent) / 100) * plan.totalM
    if (meters <= 0) return emptyList()
    fun at(m: Double, i: Int): PlanePoint {
        val a = plan.route[i]
        val b = plan.route.getOrNull(i + 1) ?: a
        val length = (plan.distances.getOrNull(i + 1) ?: plan.distances[i]) - plan.distances[i]
        val t = if (length > 0) ((m - plan.distances[i]) / length).clamp(0.0, 1.0) else 0.0
        return PlanePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
    val from = max(0.0, meters - trailM)
    val start = segmentAt(plan.distances, from)
    val end = min(segmentAt(plan.distances, meters), lastSegment(plan))
    val trail = mutableListOf(at(from, start))
    for (i in start + 1..end) trail.add(plan.route[i])
    trail.add(at(meters, end))
    return trail.map { toGeo(plan.frame, it) }
}

/** The area worked so far, clipped exactly to the parcel outline. */
fun fieldCoverageAt(plan: FieldPlan, percent: Double, trailM: Double = 80.0): FieldCoverage {
    val meters = (clampPercent(percent) / 100) * plan.totalM
    fun toMap(points: List<PlanePoint>) = points.map { toGeo(plan.frame, it) }
    val worked = mutableListOf<List<PlanePoint>>()
    val rows = mutableListOf<List<PlanePoint>>()

    var done = 0
    while (done < plan.passes.size && plan.passes[done].endM <= meters) done++
    if (done > 0) {
        // Finished passes are adjacent strips, so together they are one band of the parcel.
        val top = plan.passes[done - 1].y + plan.stripM / 2
        val band = clip(plan.outline, 1, top, true)
        if (band.size >= 3) worked.add(band)
        for (i in 0 until done) {
            val pass = plan.passes[i]
            rows.add(listOf(PlanePoint(pass.fromX, pass.y), PlanePoint(pass.toX, pass.y)))
        }
    }
    val current = plan.passes.getOrNull(done)
    if (current != null && meters > current.startM) {
        val progress = min(1.0, (meters - current.startM) / max(current.endM - current.startM, 1e-9))
        val x = current.fromX + (current.toX - current.fromX) * progress
        var strip = clip(plan.outline, 1, current.y - plan.stripM / 2, false)
        strip = clip(strip, 1, current.y + plan.stripM / 2, true)
        strip = if (current.toX > current.fromX) clip(strip, 0, x, true) else clip(strip, 0, x, false)
        if (strip.size >= 3) worked.add(strip)
        rows.add(listOf(PlanePoint(current.fromX, current.y), PlanePoint(x, current.y)))
    }

    val coveredM2 = worked.sumOf { planarArea(it) }
    return FieldCoverage(
        worked = worked.map(::toMap),
        coveredPct = if (plan.areaM2 > 0) min(100.0, (coveredM2 / plan.areaM2) * 100) else 0.0,
        rows = rows.map(::toMap),
        trail = fieldTrailAt(plan, percent, trailM),
    )
}

data class WorkTiming(val capacityHaH: Double, val totalHours: Double)

/** Field capacity and duration from area, speed and working width, as on the Work screen. */
fun workTiming(areaHa: Double, speedKmh: Double, workingWidthM: Double, efficiencyPct: Double = 100.0): WorkTiming {
    val capacityHaH = (workingWidthM * speedKmh * (efficiencyPct / 100)) / 10
    return WorkTiming(capacityHaH, if (capacityHaH > 0) areaHa / capacityHaH else 0.0)
}

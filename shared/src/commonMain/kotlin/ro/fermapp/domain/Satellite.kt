package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

/*
 * Satellite field monitoring from Sentinel-2 indices: NDVI (biomass), NDRE
 * (chlorophyll and nitrogen) and NDMI (canopy water). Pure analysis; the app
 * fetches the statistics.
 */

@Serializable
data class FieldZone(
    val id: String,
    /** "north-west", "central", ... */
    val name: String,
    val ring: List<Position>,
    /** Share of the field area, 0-1. */
    val share: Double,
)

@Serializable
data class IndexStats(val mean: Double, val std: Double, val p2: Double, val p98: Double)

@Serializable
data class ZoneObservation(val zoneId: String, val ndvi: Double?, val ndre: Double?, val ndmi: Double?)

@Serializable
data class FieldObservation(
    val sceneId: String,
    /** Local acquisition day, YYYY-MM-DD. */
    val date: String,
    /** Share of cloudy or snowy pixels over the field, 0-100. */
    val cloudPct: Double,
    val ndvi: IndexStats,
    val ndre: Double?,
    val ndmi: Double?,
    val zones: List<ZoneObservation>,
)

// ------------------------------------------------------------------ zones

private fun area(ring: List<Position>): Double {
    var sum = 0.0
    for (i in ring.indices) {
        val (x1, y1) = ring[i]
        val (x2, y2) = ring[(i + 1) % ring.size]
        sum += x1 * y2 - x2 * y1
    }
    return abs(sum) / 2
}

private class Edge(val inside: (Position) -> Boolean, val cross: (Position, Position) -> Position)

/** Sutherland–Hodgman: the part of a polygon inside an axis-aligned rectangle. */
private fun clipToRect(ring: List<Position>, minX: Double, minY: Double, maxX: Double, maxY: Double): List<Position> {
    val edges = listOf(
        Edge({ it.lng >= minX }) { a, b -> Position(minX, a.lat + ((b.lat - a.lat) * (minX - a.lng)) / (b.lng - a.lng)) },
        Edge({ it.lng <= maxX }) { a, b -> Position(maxX, a.lat + ((b.lat - a.lat) * (maxX - a.lng)) / (b.lng - a.lng)) },
        Edge({ it.lat >= minY }) { a, b -> Position(a.lng + ((b.lng - a.lng) * (minY - a.lat)) / (b.lat - a.lat), minY) },
        Edge({ it.lat <= maxY }) { a, b -> Position(a.lng + ((b.lng - a.lng) * (maxY - a.lat)) / (b.lat - a.lat), maxY) },
    )
    var output = ring
    for (edge in edges) {
        val input = output
        val next = mutableListOf<Position>()
        for (i in input.indices) {
            val current = input[i]
            val previous = input[(i + input.size - 1) % input.size]
            if (edge.inside(current)) {
                if (!edge.inside(previous)) next.add(edge.cross(previous, current))
                next.add(current)
            } else if (edge.inside(previous)) {
                next.add(edge.cross(previous, current))
            }
        }
        output = next
        if (output.isEmpty()) break
    }
    return output
}

private val ROWS = listOf("north", "", "south")
private val COLS = listOf("west", "", "east")

/** Splits a field into a 3×3 grid (2×2 for small fields), each cell clipped to the outline. */
fun fieldZones(outer: List<Position>, areaHa: Double): List<FieldZone> {
    val ring = if (outer.size > 1 && outer.first() == outer.last()) outer.dropLast(1) else outer
    if (ring.size < 3 || areaHa < 1) return emptyList()
    val n = if (areaHa >= 5) 3 else 2
    val minX = ring.minOf { it.lng }
    val maxX = ring.maxOf { it.lng }
    val minY = ring.minOf { it.lat }
    val maxY = ring.maxOf { it.lat }
    val total = area(ring)
    val zones = mutableListOf<FieldZone>()
    for (row in 0 until n) {
        for (col in 0 until n) {
            val clipped = clipToRect(
                ring,
                minX + ((maxX - minX) * col) / n,
                maxY - ((maxY - minY) * (row + 1)) / n,
                minX + ((maxX - minX) * (col + 1)) / n,
                maxY - ((maxY - minY) * row) / n,
            )
            val share = if (clipped.size >= 3) area(clipped) / total else 0.0
            if (share < 0.04) continue
            val vertical = if (n == 3) ROWS[row] else if (row == 0) "north" else "south"
            val horizontal = if (n == 3) COLS[col] else if (col == 0) "west" else "east"
            val name = when {
                vertical.isNotEmpty() && horizontal.isNotEmpty() -> "$vertical-$horizontal"
                vertical.isNotEmpty() -> vertical
                horizontal.isNotEmpty() -> horizontal
                else -> "central"
            }
            zones.add(FieldZone("z$row$col", name, clipped + clipped.first(), jsRound(share * 1000) / 1000))
        }
    }
    return zones
}

// ------------------------------------------------------------------ health

@Serializable
enum class HealthLevel { EXCELLENT, MODERATE, CRITICAL, NO_CROP }

object HealthColors {
    const val HEALTHY = "#5FB35A"
    const val MODERATE = "#E2C23D"
    const val ISSUES = "#E9883B"
    const val CRITICAL = "#E0584A"
    const val NONE = "#7A7F6E"
}

/** Green healthy, yellow moderate stress, orange potential issues, red critical. */
fun ndviColor(ndvi: Double?): String = when {
    ndvi == null -> HealthColors.NONE
    ndvi >= 0.6 -> HealthColors.HEALTHY
    ndvi >= 0.4 -> HealthColors.MODERATE
    ndvi >= 0.25 -> HealthColors.ISSUES
    else -> HealthColors.CRITICAL
}

@Serializable
data class FieldHealth(val score: Int, val level: HealthLevel, val label: String, val color: String)

/**
 * 0-100 from vigour (NDVI), chlorophyll (NDRE), canopy water (NDMI) and uniformity.
 * Without a crop in the field, low values are expected and no score is given.
 */
fun fieldHealth(ndvi: IndexStats, ndre: Double?, ndmi: Double?, cropGrowing: Boolean): FieldHealth {
    if (!cropGrowing && ndvi.mean < 0.35) return FieldHealth(0, HealthLevel.NO_CROP, "No crop in the field", HealthColors.NONE)
    var score = vegetationHealth(ndvi.mean, ndre).healthIndex
    if (ndmi != null && ndvi.mean > 0.25) {
        when {
            ndmi < -0.2 -> score -= 20
            ndmi < -0.1 -> score -= 10
            ndmi > 0.2 -> score += 5
        }
    }
    if (ndvi.std > 0.15) score -= 8 else if (ndvi.std > 0.1) score -= 4
    score = score.coerceIn(0, 100)
    return when {
        score >= 70 -> FieldHealth(score, HealthLevel.EXCELLENT, "Excellent", HealthColors.HEALTHY)
        score >= 40 -> FieldHealth(score, HealthLevel.MODERATE, "Moderate", HealthColors.MODERATE)
        else -> FieldHealth(score, HealthLevel.CRITICAL, "Critical", HealthColors.CRITICAL)
    }
}

fun fieldHealth(observation: FieldObservation, cropGrowing: Boolean) =
    fieldHealth(observation.ndvi, observation.ndre, observation.ndmi, cropGrowing)

// ------------------------------------------------------------------ findings

@Serializable
enum class FindingKind { LOW_VIGOR, NUTRIENT, WATER_STRESS, UNEVEN_EMERGENCE, DECLINE, NO_CROP, RIPENING }

@Serializable
enum class FindingSeverity { CRITICAL, WARNING, INFO }

@Serializable
data class FindingTask(val type: String, val title: String, val reason: String, val priority: ActionPriority)

@Serializable
data class SatelliteFinding(
    val key: String,
    val kind: FindingKind,
    val severity: FindingSeverity,
    val zoneId: String?,
    val title: String,
    val message: String,
    /** Why the satellite data points to this. */
    val explanation: String,
    val task: FindingTask?,
)

data class FieldContext(
    val name: String,
    /** Crop name in lower case, when one is growing. */
    val crop: String?,
    /** Season progress of the crop, 0-100. */
    val stagePct: Double?,
    val irrigated: Boolean,
)

private fun r2(value: Double) = jsNum(jsRound(value * 100) / 100)
private fun zoneLabel(zone: FieldZone?) = when {
    zone == null -> "field"
    zone.name == "central" -> "central part"
    else -> "${zone.name} section"
}
private fun capital(text: String) = text.replaceFirstChar { it.uppercaseChar() }

/** Whole days between two YYYY-MM-DD dates, measured at noon UTC as in the original. */
internal fun daysApart(from: String, to: String): Int {
    val a = parseJsDate("${from}T12:00:00Z")
    val b = parseJsDate("${to}T12:00:00Z")
    return jsRoundInt((b - a).inWholeMilliseconds.toDouble() / DAY_MS)
}

/** Explains what the latest image shows, zone by zone, and what to do about it. */
fun analyzeField(field: FieldContext, current: FieldObservation, earlier: List<FieldObservation>, zones: List<FieldZone>): List<SatelliteFinding> {
    val findings = mutableListOf<SatelliteFinding>()
    val ndvi = current.ndvi.mean
    val growing = field.crop != null && (field.stagePct ?: 50.0) < 100
    if (!growing && ndvi < 0.35) {
        return listOf(
            SatelliteFinding(
                "no-crop", FindingKind.NO_CROP, FindingSeverity.INFO, null, "No crop in the field",
                "${field.name}: low vegetation (NDVI ${r2(ndvi)}) is expected without a growing crop.",
                "Bare soil and stubble reflect little near-infrared light, so NDVI stays below about 0.3.", null,
            ),
        )
    }
    val ripening = (field.stagePct ?: 0.0) >= 85
    val name = field.name
    fun add(kind: FindingKind, severity: FindingSeverity, zoneId: String?, title: String, message: String, explanation: String, task: FindingTask?) =
        findings.add(SatelliteFinding("$kind-${zoneId ?: "field"}", kind, severity, zoneId, title, message, explanation, task))

    for (zone in current.zones) {
        val shape = zones.firstOrNull { it.id == zone.zoneId }
        val zoneNdvi = zone.ndvi
        if (zoneNdvi == null || shape == null) continue
        val gap = ndvi - zoneNdvi
        if (gap >= 0.08 && !ripening) {
            add(
                FindingKind.LOW_VIGOR,
                if (gap >= 0.15) FindingSeverity.WARNING else FindingSeverity.INFO,
                zone.zoneId,
                "Reduced vigor – ${shape.name}",
                "${capital(zoneLabel(shape))} of $name shows reduced vegetation vigor (NDVI ${r2(zoneNdvi)} vs ${r2(ndvi)} for the field).",
                "Lower NDVI means less green biomass. A patch ${jsRoundInt(gap * 100)} points below the rest of the field often points to waterlogging, compaction, pests or a sowing problem.",
                FindingTask(
                    "FIELD_INSPECTION", "Inspect ${shape.name} zone – $name",
                    "NDVI ${r2(zoneNdvi)} vs ${r2(ndvi)} field average on ${current.date}.",
                    if (gap >= 0.15) ActionPriority.HIGH else ActionPriority.MEDIUM,
                ),
            )
            continue
        }
        val zoneNdre = zone.ndre
        val fieldNdre = current.ndre
        if (zoneNdre != null && fieldNdre != null && fieldNdre - zoneNdre >= 0.06 && abs(gap) < 0.05 && !ripening) {
            add(
                FindingKind.NUTRIENT, FindingSeverity.WARNING, zone.zoneId,
                "Possible nutrient deficiency – ${shape.name}",
                "Potential nutrient deficiency detected in the ${zoneLabel(shape)} of $name: chlorophyll (NDRE ${r2(zoneNdre)}) is low while biomass is normal.",
                "NDRE reacts to chlorophyll, and therefore nitrogen, before NDVI does. Normal NDVI with low NDRE is a typical early sign of nitrogen shortage.",
                FindingTask(
                    "NITROGEN", "Apply nitrogen – ${shape.name} zone, $name",
                    "NDRE ${r2(zoneNdre)} vs ${r2(fieldNdre)} for the field; confirm with a leaf check before applying.",
                    ActionPriority.MEDIUM,
                ),
            )
        }
        val zoneNdmi = zone.ndmi
        val fieldNdmi = current.ndmi
        if (zoneNdmi != null && fieldNdmi != null && fieldNdmi - zoneNdmi >= 0.08 && zoneNdvi > 0.3) {
            val reason = "Drier canopy than the rest of the field on ${current.date}."
            add(
                FindingKind.WATER_STRESS, FindingSeverity.WARNING, zone.zoneId,
                "Water stress – ${shape.name}",
                "Possible drought stress in the ${zoneLabel(shape)} of $name (moisture index ${r2(zoneNdmi)} vs ${r2(fieldNdmi)}).",
                "The moisture index (NDMI) measures water in the leaves. A drier patch can mean lighter soil, poor irrigation coverage or root problems.",
                if (field.irrigated) {
                    FindingTask("IRRIGATION_CHECK", "Check irrigation – ${shape.name} zone, $name", reason, ActionPriority.HIGH)
                } else {
                    FindingTask("FIELD_INSPECTION", "Field visit – ${shape.name} zone, $name", reason, ActionPriority.MEDIUM)
                },
            )
        }
    }

    val fieldNdre = current.ndre
    if (fieldNdre != null && ndvi >= 0.5 && fieldNdre < 0.25 && !ripening && findings.none { it.kind == FindingKind.NUTRIENT }) {
        add(
            FindingKind.NUTRIENT, FindingSeverity.WARNING, null, "Possible nutrient deficiency",
            "Potential nutrient deficiency detected on $name: NDRE ${r2(fieldNdre)} is low for an NDVI of ${r2(ndvi)}.",
            "Good biomass with low chlorophyll usually means the crop is running short of nitrogen.",
            FindingTask("NITROGEN", "Apply nitrogen – $name", "NDRE ${r2(fieldNdre)} on ${current.date}; confirm with a leaf check.", ActionPriority.MEDIUM),
        )
    }
    val fieldNdmi = current.ndmi
    if (fieldNdmi != null && fieldNdmi < -0.05 && ndvi >= 0.3 && findings.none { it.kind == FindingKind.WATER_STRESS }) {
        add(
            FindingKind.WATER_STRESS,
            if (fieldNdmi < -0.15) FindingSeverity.CRITICAL else FindingSeverity.WARNING,
            null, "Possible drought stress",
            "Possible drought stress on $name: the canopy moisture index is ${r2(fieldNdmi)}.",
            "Negative NDMI over a green crop means the leaves hold little water: the crop is closing its stomata to save it.",
            if (field.irrigated) FindingTask("IRRIGATION_CHECK", "Check irrigation – $name", "NDMI ${r2(fieldNdmi)} on ${current.date}.", ActionPriority.HIGH) else null,
        )
    }
    if ((field.stagePct ?: 100.0) <= 30 && (current.ndvi.std >= 0.1 || current.ndvi.p98 - current.ndvi.p2 >= 0.4)) {
        add(
            FindingKind.UNEVEN_EMERGENCE, FindingSeverity.WARNING, null, "Uneven crop emergence",
            "Uneven crop emergence on $name: vegetation varies strongly across the field (NDVI ${r2(current.ndvi.p2)}–${r2(current.ndvi.p98)}).",
            "Early in the season a uniform stand gives uniform NDVI. Large differences point to gaps from seedbed, sowing depth or soil crusting.",
            FindingTask("EMERGENCE_CHECK", "Check emergence – $name", "NDVI spread ${r2(current.ndvi.p2)}–${r2(current.ndvi.p98)} on ${current.date}.", ActionPriority.HIGH),
        )
    }

    val before = earlier
        .filter { it.date < current.date }
        .sortedByDescending { it.date }
        .firstOrNull { daysApart(it.date, current.date) in 5..25 }
    if (before != null) {
        val drop = before.ndvi.mean - ndvi
        if (ripening && drop > 0.05) {
            add(
                FindingKind.RIPENING, FindingSeverity.INFO, null, "Crop ripening",
                "NDVI on $name is falling as ${field.crop ?: "the crop"} ripens (${r2(before.ndvi.mean)} → ${r2(ndvi)}).",
                "Leaves lose chlorophyll as the crop matures, so a falling NDVI near harvest is normal.", null,
            )
        } else if (drop >= 0.08) {
            add(
                FindingKind.DECLINE,
                if (drop >= 0.15) FindingSeverity.CRITICAL else FindingSeverity.WARNING,
                null, "Vegetation decline",
                "NDVI on $name fell from ${r2(before.ndvi.mean)} to ${r2(ndvi)} since ${before.date}.",
                "A drop in green biomass during the growing season points to stress: drought, disease, pests, hail or herbicide injury.",
                FindingTask(
                    "FIELD_INSPECTION", "Field visit – $name",
                    "NDVI −${jsRoundInt(drop * 100)} points in ${daysApart(before.date, current.date)} days.",
                    if (drop >= 0.15) ActionPriority.HIGH else ActionPriority.MEDIUM,
                ),
            )
        }
    }
    // The severity enum is declared CRITICAL, WARNING, INFO, so ordinal order is the display order.
    return findings.sortedBy { it.severity.ordinal }
}

// ------------------------------------------------------------------ history

@Serializable
data class HistoryPoint(val date: String, val ndvi: Double)

@Serializable
data class Comparison(val date: String, val ndvi: Double, val delta: Double)

@Serializable
data class HistoryComparison(
    val latest: HistoryPoint?,
    val d7: Comparison?,
    val d30: Comparison?,
    val d90: Comparison?,
    /** The same time last year, ±20 days. */
    val yearAgo: Comparison?,
)

/** NDVI now against 7, 30 and 90 days ago and the same time last year. */
fun compareHistory(points: List<HistoryPoint>): HistoryComparison {
    val sorted = points.sortedByDescending { it.date }
    val latest = sorted.firstOrNull() ?: return HistoryComparison(null, null, null, null, null)
    fun closest(days: Int, tolerance: Int): Comparison? {
        var best: HistoryPoint? = null
        var bestGap = Int.MAX_VALUE
        for (point in sorted.drop(1)) {
            val gap = abs(daysApart(point.date, latest.date) - days)
            if (gap <= tolerance && gap < bestGap) {
                best = point
                bestGap = gap
            }
        }
        return best?.let { Comparison(it.date, it.ndvi, jsRound((latest.ndvi - it.ndvi) * 100) / 100) }
    }
    return HistoryComparison(latest, closest(7, 5), closest(30, 10), closest(90, 20), closest(365, 20))
}

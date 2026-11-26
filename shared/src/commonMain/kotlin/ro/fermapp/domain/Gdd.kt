package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class DailyTemperature(val date: String, val tMaxC: Double, val tMinC: Double)

/** Degree-days for one day, capped at the base and upper thresholds. */
fun dailyGdd(tMaxC: Double, tMinC: Double, baseC: Double, capC: Double = 30.0): Double {
    val high = min(capC, max(baseC, tMaxC))
    val low = min(capC, max(baseC, tMinC))
    return max(0.0, (high + low) / 2 - baseC)
}

private val STAGES = listOf(
    0.1 to "Emergence",
    0.35 to "Vegetative growth",
    0.55 to "Flowering",
    0.8 to "Grain fill",
    1.0 to "Ripening",
)

@Serializable
data class CropDevelopment(
    val crop: CropCode,
    val accumulatedGdd: Int,
    val requiredGdd: Int,
    val percent: Int,
    val stage: String,
)

fun cropDevelopment(crop: CropCode, days: List<DailyTemperature>): CropDevelopment {
    val profile = crop.profile
    val accumulated = days.sumOf { dailyGdd(it.tMaxC, it.tMinC, profile.gddBaseC, profile.gddCapC) }
    val ratio = accumulated / profile.gddToMaturity
    val stage = if (ratio >= 1) "Mature" else STAGES.first { ratio <= it.first }.second
    return CropDevelopment(
        crop = crop,
        accumulatedGdd = jsRoundInt(accumulated),
        requiredGdd = profile.gddToMaturity.toInt(),
        percent = min(100, jsRoundInt(ratio * 100)),
        stage = stage,
    )
}

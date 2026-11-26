package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class SoilSummary(val ph: Double, val texture: SoilTexture)

@Serializable
data class ClimateSummary(
    /** Mean temperature during the growing months, in °C. */
    val seasonTempC: Double,
    val annualPrecipitationMm: Double,
    /** Annual degree-days above 10 °C. */
    val gddBase10: Double? = null,
)

data class RecommendationInput(
    val year: Int,
    val history: List<CropHistoryEntry>,
    val soil: SoilSummary? = null,
    val climate: ClimateSummary? = null,
    val crops: List<CropCode>? = null,
)

@Serializable
data class CropRecommendation(
    val crop: CropCode,
    val name: String,
    val score: Int,
    val reasons: List<String>,
    /** Criteria without data, which received a neutral score. */
    val missingData: List<String>,
)

private object Weights {
    const val ROTATION = 35.0
    const val SOIL = 25.0
    const val TEMPERATURE = 20.0
    const val WATER = 20.0
}

private const val NEUTRAL = 0.65

/** 1 inside the range, then decreases linearly to 0 at distance [tolerance] from the range. */
internal fun rangeFit(value: Double, range: Range, tolerance: Double): Double {
    if (value >= range.min && value <= range.max) return 1.0
    val distance = if (value < range.min) range.min - value else value - range.max
    return max(0.0, 1 - distance / tolerance)
}

fun recommendCrops(input: RecommendationInput): List<CropRecommendation> {
    val crops = input.crops ?: CROP_CODES

    return crops.map { crop ->
        val profile = crop.profile
        val reasons = mutableListOf<String>()
        val missingData = mutableListOf<String>()

        val rotation = evaluateRotation(crop, input.year, input.history)
        reasons.add("Rotation ${rotation.score}/100: ${rotation.explanation}")

        var soilFit = NEUTRAL
        val soil = input.soil
        if (soil != null) {
            val phFit = rangeFit(soil.ph, profile.phRange, 1.5)
            val textureFit = if (soil.texture in profile.textures) 1.0 else 0.45
            soilFit = phFit * 0.6 + textureFit * 0.4
            val range = "${jsNum(profile.phRange.min)}-${jsNum(profile.phRange.max)}"
            reasons.add(
                if (phFit == 1.0) {
                    "pH ${jsNum(soil.ph)} is within the optimal range of $range."
                } else {
                    "pH ${jsNum(soil.ph)} is outside the optimal range of $range."
                },
            )
            if (textureFit < 1) reasons.add("The soil texture is not among those preferred by the crop.")
        } else {
            missingData.add("soil")
        }

        var temperatureFit = NEUTRAL
        var waterFit = NEUTRAL
        val climate = input.climate
        if (climate != null) {
            temperatureFit = rangeFit(climate.seasonTempC, profile.seasonTempC, 8.0)
            val temp = climate.seasonTempC.toFixed(1)
            reasons.add(
                if (temperatureFit == 1.0) {
                    "The mean temperature of $temp °C is favourable."
                } else {
                    "The mean temperature of $temp °C is outside the optimum of ${jsNum(profile.seasonTempC.min)}-${jsNum(profile.seasonTempC.max)} °C."
                },
            )
            waterFit = rangeFit(climate.annualPrecipitationMm, profile.waterMm, 300.0)
            val rain = jsNum(jsRound(climate.annualPrecipitationMm))
            val minWater = jsNum(profile.waterMm.min)
            reasons.add(
                if (climate.annualPrecipitationMm < profile.waterMm.min) {
                    "Rainfall of $rain mm is below the minimum requirement of $minWater mm."
                } else {
                    "Rainfall of $rain mm covers the minimum requirement of $minWater mm."
                },
            )
        } else {
            missingData.add("climate")
        }

        val score = (rotation.score / 100.0) * Weights.ROTATION +
            soilFit * Weights.SOIL +
            temperatureFit * Weights.TEMPERATURE +
            waterFit * Weights.WATER

        CropRecommendation(crop, profile.name, jsRoundInt(score), reasons, missingData)
    }.sortedByDescending { it.score }
}

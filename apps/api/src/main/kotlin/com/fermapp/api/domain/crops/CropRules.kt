package com.fermapp.api.domain.crops

import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.crops.Crops.SoilTexture
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.rotation.Rotation
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Crop recommendations (recommendations.ts), seasonal water balance (water.ts),
 * degree-days (gdd.ts) and vegetation health (vegetation.ts).
 */
object CropRules {

    @JvmRecord
    data class SoilFit(val ph: Double, val texture: SoilTexture?)

    /**
     * Climate normals of the parcel.
     *
     * @param seasonTempC mean temperature during the growing months
     * @param gddBase10 annual degree-days above 10 °C, when known
     */
    @JvmRecord
    data class ClimateFit(val seasonTempC: Double, val annualPrecipitationMm: Double, val gddBase10: Double?)

    /** A ranked crop. [missingData] lists the criteria without data, which got a neutral score. */
    @JvmRecord
    data class CropRecommendation(
        val crop: CropCode,
        val name: String,
        val score: Int,
        val reasons: List<String>,
        val missingData: List<String>,
    )

    @JvmRecord
    data class WaterBalance(
        val crop: CropCode,
        val requiredMinMm: Double,
        val requiredMaxMm: Double,
        val requiredMm: Double,
        val receivedMm: Double,
        val deficitMm: Double,
        val coveragePct: Double,
        val irrigationPasses: Int,
        val doseMm: Double,
        val suggestion: String,
    )

    @JvmRecord
    data class DailyTemperature(val date: String?, val tMaxC: Double, val tMinC: Double)

    @JvmRecord
    data class CropDevelopment(
        val crop: CropCode,
        val accumulatedGdd: Double,
        val requiredGdd: Double,
        val percent: Double,
        val stage: String,
    )

    enum class HealthClass { GOOD, MODERATE, PROBLEM }

    @JvmRecord
    data class VegetationHealth(val healthIndex: Int, val healthClass: HealthClass, val color: String, val label: String)

    private const val WEIGHT_ROTATION = 35.0
    private const val WEIGHT_SOIL = 25.0
    private const val WEIGHT_TEMPERATURE = 20.0
    private const val WEIGHT_WATER = 20.0
    private const val NEUTRAL = 0.65

    /** Growth stages by the share of degree-days reached. */
    private val STAGES = listOf(
        0.1 to "Emergence",
        0.35 to "Vegetative growth",
        0.55 to "Flowering",
        0.8 to "Grain fill",
        1.0 to "Ripening",
    )

    /** 1 inside the range, then decreases linearly to 0 at distance [tolerance] from the range. */
    private fun rangeFit(value: Double, range: DoubleArray, tolerance: Double): Double {
        val (min, max) = range[0] to range[1]
        if (value in min..max) {
            return 1.0
        }
        val distance = if (value < min) min - value else value - max
        return max(0.0, 1 - distance / tolerance)
    }

    @JvmStatic
    fun recommendCrops(
        year: Int,
        history: List<CropHistoryEntry>,
        soil: SoilFit?,
        climate: ClimateFit?,
        crops: List<CropCode>?,
    ): List<CropRecommendation> {
        val result = (crops ?: Crops.CROP_CODES).map { crop ->
            val profile = Crops.profile(crop)
            val reasons = mutableListOf<String>()
            val missingData = mutableListOf<String>()

            val rotation = Rotation.evaluateRotation(crop, year, history)
            reasons += "Rotation ${rotation.score}/100: ${rotation.explanation}"

            var soilFit = NEUTRAL
            if (soil != null) {
                val phFit = rangeFit(soil.ph, profile.phRange, 1.5)
                val textureFit = if (soil.texture in profile.textures) 1.0 else 0.45
                soilFit = phFit * 0.6 + textureFit * 0.4
                val range = Js.num(profile.phRange[0]) + "-" + Js.num(profile.phRange[1])
                val ph = Js.num(soil.ph)
                reasons += if (phFit == 1.0) {
                    "pH $ph is within the optimal range of $range."
                } else {
                    "pH $ph is outside the optimal range of $range."
                }
                if (textureFit < 1) {
                    reasons += "The soil texture is not among those preferred by the crop."
                }
            } else {
                missingData += "soil"
            }

            var temperatureFit = NEUTRAL
            var waterFit = NEUTRAL
            if (climate != null) {
                temperatureFit = rangeFit(climate.seasonTempC, profile.seasonTempC, 8.0)
                val temp = Js.toFixed(climate.seasonTempC, 1)
                reasons += if (temperatureFit == 1.0) {
                    "The mean temperature of $temp °C is favourable."
                } else {
                    "The mean temperature of $temp °C is outside the optimum of " +
                        Js.num(profile.seasonTempC[0]) + "-" + Js.num(profile.seasonTempC[1]) + " °C."
                }
                waterFit = rangeFit(climate.annualPrecipitationMm, profile.waterMm, 300.0)
                val rain = Math.round(climate.annualPrecipitationMm)
                val minimum = Js.num(profile.waterMin())
                reasons += if (climate.annualPrecipitationMm < profile.waterMin()) {
                    "Rainfall of $rain mm is below the minimum requirement of $minimum mm."
                } else {
                    "Rainfall of $rain mm covers the minimum requirement of $minimum mm."
                }
            } else {
                missingData += "climate"
            }

            val score = rotation.score / 100.0 * WEIGHT_ROTATION + soilFit * WEIGHT_SOIL +
                temperatureFit * WEIGHT_TEMPERATURE + waterFit * WEIGHT_WATER
            CropRecommendation(crop, profile.name, Math.round(score).toInt(), reasons, missingData)
        }
        // Java callers may sort or extend the list, as they could the ArrayList returned before.
        return result.sortedByDescending { it.score }.toMutableList()
    }

    /** Seasonal water balance, relative to the midpoint of the crop's requirement range. */
    @JvmStatic
    fun waterBalance(crop: CropCode, precipitationMm: Double, irrigationMm: Double, doseMm: Double): WaterBalance {
        val profile = Crops.profile(crop)
        val requiredMinMm = profile.waterMin()
        val requiredMaxMm = profile.waterMax()
        val requiredMm = (requiredMinMm + requiredMaxMm) / 2
        val receivedMm = max(0.0, precipitationMm) + max(0.0, irrigationMm)
        val deficitMm = max(0.0, requiredMm - receivedMm)
        val irrigationPasses = if (deficitMm > 0) ceil(deficitMm / doseMm).toInt() else 0
        val dose = Js.num(doseMm)

        val suggestion = when {
            deficitMm == 0.0 -> "${profile.name}: the water requirement is covered, irrigation is not needed."
            receivedMm >= requiredMinMm ->
                "${profile.name}: water received reaches the minimum threshold. Irrigation is optional, with " +
                    "$irrigationPasses passes of $dose mm for maximum yield."
            else ->
                "${profile.name}: deficit of ${Math.round(deficitMm)} mm. $irrigationPasses irrigation passes of " +
                    "$dose mm are recommended."
        }
        return WaterBalance(
            crop, requiredMinMm, requiredMaxMm, requiredMm, Js.round1(receivedMm), Js.round1(deficitMm),
            min(100L, Math.round(receivedMm / requiredMm * 100)).toDouble(), irrigationPasses, doseMm, suggestion,
        )
    }

    /** Degree-days for one day, capped at the base and upper thresholds. */
    @JvmStatic
    @JvmOverloads
    fun dailyGdd(tMaxC: Double, tMinC: Double, baseC: Double, capC: Double = 30.0): Double {
        val max = min(capC, max(baseC, tMaxC))
        val min = min(capC, max(baseC, tMinC))
        return max(0.0, (max + min) / 2 - baseC)
    }

    @JvmStatic
    fun cropDevelopment(crop: CropCode, days: List<DailyTemperature>): CropDevelopment {
        val profile = Crops.profile(crop)
        val accumulated = days.sumOfDoubles { dailyGdd(it.tMaxC, it.tMinC, profile.gddBaseC, profile.gddCapC) }
        val ratio = accumulated / profile.gddToMaturity
        val stage = if (ratio >= 1) "Mature" else STAGES.first { ratio <= it.first }.second
        return CropDevelopment(
            crop, Js.round(accumulated), profile.gddToMaturity,
            min(100L, Math.round(ratio * 100)).toDouble(), stage,
        )
    }

    /** 0-100 index: NDVI shows biomass, and NDRE shows nitrogen and chlorophyll status. */
    @JvmStatic
    fun vegetationHealth(ndvi: Double, ndre: Double?): VegetationHealth {
        val ndviScore = Js.clamp((ndvi - 0.1) / 0.7, 0.0, 1.0)
        val score = if (ndre == null) ndviScore else ndviScore * 0.7 + Js.clamp((ndre - 0.05) / 0.45, 0.0, 1.0) * 0.3
        val healthIndex = Math.round(score * 100).toInt()
        return when {
            healthIndex >= 60 -> VegetationHealth(healthIndex, HealthClass.GOOD, "green", "Good growth")
            healthIndex >= 35 -> VegetationHealth(healthIndex, HealthClass.MODERATE, "yellow", "Moderate stress")
            else -> VegetationHealth(healthIndex, HealthClass.PROBLEM, "red", "Possible problem")
        }
    }
}

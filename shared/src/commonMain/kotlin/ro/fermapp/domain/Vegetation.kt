package ro.fermapp.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HealthClass { GOOD, MODERATE, PROBLEM }

@Serializable
enum class HealthColor {
    @SerialName("green") GREEN,
    @SerialName("yellow") YELLOW,
    @SerialName("red") RED,
}

@Serializable
data class VegetationHealth(
    val healthIndex: Int,
    val healthClass: HealthClass,
    val color: HealthColor,
    val label: String,
)

private fun clamp01(value: Double) = value.clamp(0.0, 1.0)

/** 0-100 index: NDVI shows biomass, and NDRE shows nitrogen and chlorophyll status. */
fun vegetationHealth(ndvi: Double, ndre: Double? = null): VegetationHealth {
    val ndviScore = clamp01((ndvi - 0.1) / 0.7)
    val score = if (ndre == null) ndviScore else ndviScore * 0.7 + clamp01((ndre - 0.05) / 0.45) * 0.3
    val healthIndex = jsRoundInt(score * 100)
    return when {
        healthIndex >= 60 -> VegetationHealth(healthIndex, HealthClass.GOOD, HealthColor.GREEN, "Good growth")
        healthIndex >= 35 -> VegetationHealth(healthIndex, HealthClass.MODERATE, HealthColor.YELLOW, "Moderate stress")
        else -> VegetationHealth(healthIndex, HealthClass.PROBLEM, HealthColor.RED, "Possible problem")
    }
}

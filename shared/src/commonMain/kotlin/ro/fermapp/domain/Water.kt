package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Serializable
data class WaterBalanceInput(
    val crop: CropCode,
    val precipitationMm: Double,
    val irrigationMm: Double = 0.0,
    /** Dose applied in one irrigation pass, in mm. */
    val doseMm: Double = 30.0,
)

@Serializable
data class WaterBalance(
    val crop: CropCode,
    val requiredMinMm: Double,
    val requiredMaxMm: Double,
    val requiredMm: Double,
    val receivedMm: Double,
    val deficitMm: Double,
    val coveragePct: Int,
    val irrigationPasses: Int,
    val doseMm: Double,
    val suggestion: String,
)

/** Seasonal water balance, relative to the midpoint of the crop's requirement range. */
fun waterBalance(input: WaterBalanceInput): WaterBalance {
    val (crop, precipitationMm, irrigationMm, doseMm) = input
    val profile = crop.profile
    val requiredMinMm = profile.waterMm.min
    val requiredMaxMm = profile.waterMm.max
    val requiredMm = (requiredMinMm + requiredMaxMm) / 2
    val receivedMm = max(0.0, precipitationMm) + max(0.0, irrigationMm)
    val deficitMm = max(0.0, requiredMm - receivedMm)
    val irrigationPasses = if (deficitMm > 0) ceil(deficitMm / doseMm).toInt() else 0
    val dose = jsNum(doseMm)

    val suggestion = when {
        deficitMm == 0.0 -> "${profile.name}: the water requirement is covered, irrigation is not needed."
        receivedMm >= requiredMinMm ->
            "${profile.name}: water received reaches the minimum threshold. Irrigation is optional, with $irrigationPasses passes of $dose mm for maximum yield."
        else ->
            "${profile.name}: deficit of ${jsNum(jsRound(deficitMm))} mm. $irrigationPasses irrigation passes of $dose mm are recommended."
    }

    return WaterBalance(
        crop = crop,
        requiredMinMm = requiredMinMm,
        requiredMaxMm = requiredMaxMm,
        requiredMm = requiredMm,
        receivedMm = jsRound(receivedMm, 1),
        deficitMm = jsRound(deficitMm, 1),
        coveragePct = min(100, jsRoundInt((receivedMm / requiredMm) * 100)),
        irrigationPasses = irrigationPasses,
        doseMm = doseMm,
        suggestion = suggestion,
    )
}

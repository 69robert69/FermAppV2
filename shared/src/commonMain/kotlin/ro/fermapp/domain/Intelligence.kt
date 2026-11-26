package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Indicative duration from sowing to harvest, in days. */
val CROP_SEASON_DAYS: Map<CropCode, Int> = mapOf(
    CropCode.WHEAT to 270,
    CropCode.BARLEY to 250,
    CropCode.CORN to 170,
    CropCode.SUNFLOWER to 125,
    CropCode.RAPESEED to 300,
    CropCode.SOY to 130,
    CropCode.ALFALFA to 180,
    CropCode.OAT to 110,
    CropCode.POTATO to 120,
    CropCode.BEET to 190,
    CropCode.VEGETABLES to 100,
)

private fun clamp100(value: Double) = value.clamp(0.0, 100.0)
private fun round1(value: Double) = jsRound(value * 10) / 10

@Serializable
enum class CropProgressStatus { NOT_SOWN, GROWING, READY, HARVESTED }

@Serializable
data class CropProgress(
    val status: CropProgressStatus,
    val vegetationDay: Int,
    val totalDays: Int,
    val percent: Int,
    val estimatedHarvestDate: String?,
)

fun cropProgress(
    crop: CropCode,
    sowingDate: Instant? = null,
    harvestDate: Instant? = null,
    expectedHarvestDate: Instant? = null,
    now: Instant = Clock.System.now(),
): CropProgress {
    val defaultDays = CROP_SEASON_DAYS.getValue(crop)
    if (sowingDate == null || sowingDate > now) {
        return CropProgress(CropProgressStatus.NOT_SOWN, 0, defaultDays, 0, expectedHarvestDate?.toJsIsoString())
    }
    val estimated = harvestDate ?: expectedHarvestDate ?: (sowingDate + (defaultDays * DAY_MS).milliseconds)
    val spanMs = (estimated - sowingDate).inWholeMilliseconds
    val totalDays = max(1, jsRoundInt(spanMs.toDouble() / DAY_MS))
    val reference = if (harvestDate != null && harvestDate < now) harvestDate else now
    val vegetationDay = min(totalDays, floor((reference - sowingDate).inWholeMilliseconds.toDouble() / DAY_MS).toInt() + 1)
    val percent = jsRoundInt((vegetationDay.toDouble() / totalDays) * 100)
    val status = when {
        harvestDate != null && harvestDate <= now -> CropProgressStatus.HARVESTED
        percent >= 95 -> CropProgressStatus.READY
        else -> CropProgressStatus.GROWING
    }
    return CropProgress(status, vegetationDay, totalDays, percent, estimated.toJsIsoString())
}

@Serializable
data class SoilIndices(val soilScore: Int, val fertilityIndex: Int, val waterRetentionIndex: Int)

@Serializable
data class SoilProperties(
    val ph: Double,
    val organicMatterPct: Double,
    val clayPct: Double,
    val sandPct: Double,
    val waterHoldingMmPerM: Double,
)

/** Indicative 0-100 indices, computed from soil properties. */
fun soilIndices(soil: SoilProperties): SoilIndices {
    val phDistance = when {
        soil.ph < 6.2 -> 6.2 - soil.ph
        soil.ph > 7.5 -> soil.ph - 7.5
        else -> 0.0
    }
    val phScore = clamp100(100 - phDistance * 40)
    val organicScore = clamp100((soil.organicMatterPct / 4) * 100)
    val clayDistance = when {
        soil.clayPct < 15 -> 15 - soil.clayPct
        soil.clayPct > 40 -> soil.clayPct - 40
        else -> 0.0
    }
    val textureScore = clamp100(100 - clayDistance * 2.5 - max(0.0, soil.sandPct - 60) * 2)
    val fertilityIndex = jsRoundInt(organicScore * 0.6 + phScore * 0.4)
    val waterRetentionIndex = jsRoundInt(clamp100((soil.waterHoldingMmPerM / 200) * 100))
    return SoilIndices(
        fertilityIndex = fertilityIndex,
        waterRetentionIndex = waterRetentionIndex,
        soilScore = jsRoundInt(fertilityIndex * 0.4 + waterRetentionIndex * 0.3 + textureScore * 0.3),
    )
}

@Serializable
enum class SprayAlertCode { WIND, RAIN }

@Serializable
data class SprayAlert(val code: SprayAlertCode, val message: String)

const val SPRAY_WIND_LIMIT_KMH = 15.0
const val SPRAY_RAIN_LIMIT_PCT = 60.0

data class SprayHour(val windKmh: Double, val precipitationProbabilityPct: Double? = null)

/** Spraying conditions for the next few hours. */
fun sprayingAlerts(hours: List<SprayHour>): List<SprayAlert> {
    val alerts = mutableListOf<SprayAlert>()
    val wind = hours.firstOrNull()?.windKmh ?: 0.0
    if (wind > SPRAY_WIND_LIMIT_KMH) {
        alerts.add(SprayAlert(SprayAlertCode.WIND, "Wind ${jsNum(jsRound(wind))} km/h. Do not spray."))
    }
    val rain = hours.maxOfOrNull { it.precipitationProbabilityPct ?: 0.0 }?.let { max(0.0, it) } ?: 0.0
    if (rain > SPRAY_RAIN_LIMIT_PCT) {
        alerts.add(SprayAlert(SprayAlertCode.RAIN, "Rain probability ${jsNum(jsRound(rain))}%. Postpone spraying."))
    }
    return alerts
}

@Serializable
enum class ActionPriority { LOW, MEDIUM, HIGH }

@Serializable
data class NextAction(
    val code: String,
    val action: String,
    val reason: String,
    val priority: ActionPriority,
    val riskLevel: ActionPriority,
    val confidencePct: Int,
    val dueInDays: Int?,
)

@Serializable
data class WeatherSummary(
    val meanHumidityPct: Double,
    val meanTempC: Double,
    val minTempC: Double,
    val rainyDays: Int,
    val precipitationMm: Double,
    val et0Mm: Double,
)

data class ProgressSnapshot(val status: CropProgressStatus, val percent: Int)

data class RecentWork(val type: String, val date: Instant)

data class NextActionInput(
    val crop: CropCode? = null,
    val progress: ProgressSnapshot? = null,
    val weather: WeatherSummary? = null,
    val soilMoisturePct: Double? = null,
    val healthClass: String? = null,
    val recentWork: List<RecentWork> = emptyList(),
    val now: Instant = Clock.System.now(),
)

private val NITROGEN_CROPS = setOf(CropCode.WHEAT, CropCode.BARLEY, CropCode.CORN, CropCode.RAPESEED, CropCode.OAT)
private const val LOW_SOIL_MOISTURE_PCT = 30.0
private const val WATER_DEFICIT_MM = 20.0

/** Picks the next action using agronomic rules, from most to least urgent. */
fun nextRecommendedAction(input: NextActionInput): NextAction {
    val (crop, progress, weather, soilMoisturePct, healthClass, recentWork, now) = input
    val confidence = 50 +
        (if (weather != null) 15 else 0) +
        (if (progress != null && progress.status != CropProgressStatus.NOT_SOWN) 15 else 0) +
        (if (soilMoisturePct != null) 10 else 0) +
        (if (!healthClass.isNullOrEmpty()) 5 else 0)

    fun result(code: String, action: String, reason: String, priority: ActionPriority, risk: ActionPriority, due: Int?) =
        NextAction(code, action, reason, priority, risk, confidence, due)

    fun workedWithin(type: String, days: Int) =
        recentWork.any { it.type == type && (now - it.date).inWholeMilliseconds <= days * DAY_MS }

    if (crop == null) {
        return result("PLAN_CROP", "Plan the crop for this parcel.", "The parcel has no crop recorded for the current year.", ActionPriority.MEDIUM, ActionPriority.LOW, null)
    }
    val name = crop.profile.name.lowercase()

    if (progress == null || progress.status == CropProgressStatus.NOT_SOWN) {
        return result("SET_SOWING", "Fill in the sowing date.", "Without this date the stage of the $name crop cannot be estimated.", ActionPriority.MEDIUM, ActionPriority.LOW, null)
    }
    if (progress.status == CropProgressStatus.HARVESTED) {
        return result("POST_HARVEST", "Prepare the land for the next crop.", "The $name crop has been harvested.", ActionPriority.LOW, ActionPriority.LOW, 14)
    }
    if (progress.status == CropProgressStatus.READY) {
        return result(
            "HARVEST", "Prepare for harvest.",
            "The $name crop has reached ${progress.percent}% of its estimated cycle. Check grain moisture.",
            ActionPriority.HIGH, ActionPriority.MEDIUM, 7,
        )
    }
    if (weather != null && weather.minTempC <= 0 && progress.percent < 30) {
        return result(
            "FROST", "Check the plants after the expected frost.",
            "${jsNum(jsRound(weather.minTempC))} °C is expected and the crop is at a sensitive stage.",
            ActionPriority.HIGH, ActionPriority.HIGH, 2,
        )
    }
    if (healthClass == "PROBLEM") {
        return result("SCOUT", "Inspect the parcel in the field.", "The latest satellite image indicates a possible vegetation problem.", ActionPriority.HIGH, ActionPriority.HIGH, 2)
    }
    val waterDeficit = if (weather != null) weather.et0Mm - weather.precipitationMm else 0.0
    val lowMoisture = soilMoisturePct != null && soilMoisturePct < LOW_SOIL_MOISTURE_PCT
    if ((lowMoisture || waterDeficit > WATER_DEFICIT_MM) && progress.percent in 15..85) {
        return result(
            "IRRIGATE", "Plan an irrigation within the next 3 days.",
            if (lowMoisture) {
                "Soil moisture is ${jsNum(jsRound(soilMoisturePct!!))}%."
            } else {
                "Evapotranspiration exceeds rainfall by ${jsNum(jsRound(waterDeficit))} mm over the next 7 days."
            },
            ActionPriority.HIGH, ActionPriority.MEDIUM, 3,
        )
    }
    val foliarRisk = weather != null && weather.meanHumidityPct >= 75 && weather.meanTempC >= 12 &&
        weather.meanTempC <= 28 && weather.rainyDays >= 2
    if (foliarRisk && progress.percent in 30..80 && !workedWithin("SPRAYING", 10)) {
        return result(
            "FUNGICIDE", "Check for foliar diseases and prepare a fungicide treatment within 4 days.",
            "Mean humidity of ${jsNum(jsRound(weather!!.meanHumidityPct))}% and ${weather.rainyDays} rainy days raise the risk of foliar diseases.",
            ActionPriority.HIGH, ActionPriority.HIGH, 4,
        )
    }
    if (crop in NITROGEN_CROPS && progress.percent in 15..45 && !workedWithin("FERTILIZING", 30)) {
        return result(
            "NITROGEN", "Plan a nitrogen fertilization.",
            "The $name crop is actively growing and no fertilization has been recorded in the last 30 days.",
            ActionPriority.MEDIUM, ActionPriority.MEDIUM, 7,
        )
    }
    if (progress.percent <= 25 && !workedWithin("SPRAYING", 21)) {
        return result(
            "WEEDS", "Check the level of weed infestation.",
            "In the early stages, weeds compete strongly with the crop for water and nutrients.",
            ActionPriority.MEDIUM, ActionPriority.MEDIUM, 5,
        )
    }
    return result("MONITOR", "Keep monitoring the crop.", "The available data does not indicate an urgent intervention.", ActionPriority.LOW, ActionPriority.LOW, 7)
}

@Serializable
data class ParcelEconomics(
    val seedsCost: Double,
    val fertilizerCost: Double,
    val chemicalCost: Double,
    val fuelCost: Double,
    val laborCost: Double,
    val irrigationCost: Double,
    val otherCost: Double,
    val totalCost: Double,
    val costPerHa: Double,
    val expectedIncome: Double?,
    val expectedProfit: Double?,
    val profitMarginPct: Double?,
    val roiPct: Double?,
    val actualIncome: Double?,
    val actualProfit: Double?,
)

fun parcelEconomics(input: CostSummaryInput, recordedIncome: Double? = null): ParcelEconomics {
    val summary = summarizeCosts(input)
    val expectedIncome = summary.estimatedRevenue
    val expectedProfit = summary.estimatedProfit
    val actualIncome = if (recordedIncome != null && recordedIncome != 0.0) recordedIncome else summary.actualRevenue
    val by = summary.byCategory
    return ParcelEconomics(
        seedsCost = by.getValue(CostCategory.SEED),
        fertilizerCost = by.getValue(CostCategory.FERTILIZER),
        chemicalCost = by.getValue(CostCategory.PESTICIDE),
        fuelCost = by.getValue(CostCategory.FUEL),
        laborCost = by.getValue(CostCategory.LABOR),
        irrigationCost = by.getValue(CostCategory.IRRIGATION),
        otherCost = by.getValue(CostCategory.OTHER),
        totalCost = summary.totalCost,
        costPerHa = summary.costPerHa,
        expectedIncome = expectedIncome,
        expectedProfit = expectedProfit,
        profitMarginPct = if (expectedIncome != null && expectedIncome != 0.0 && expectedProfit != null) round1((expectedProfit / expectedIncome) * 100) else null,
        roiPct = if (summary.totalCost > 0 && expectedProfit != null) round1((expectedProfit / summary.totalCost) * 100) else null,
        actualIncome = actualIncome,
        actualProfit = actualIncome?.let { jsRound((it - summary.totalCost) * 100) / 100 },
    )
}

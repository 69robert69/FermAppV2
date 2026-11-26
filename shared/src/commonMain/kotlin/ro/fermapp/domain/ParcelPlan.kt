package ro.fermapp.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private const val MIN_DAYS_BEFORE_SOWING = 7
private const val IRRIGATION_DOSE_MM = 30

internal val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

const val OTHER_CROP = "OTHER"

@Serializable
enum class PlanStepKey {
    SOIL_PREP, SEED_PURCHASE, BASE_FERTILIZATION, SOWING, PRE_EMERGENT, EMERGENCE_CHECK,
    TOP_DRESSING, DISEASE_MONITORING, PEST_MONITORING, HARVEST_PREP, HARVEST,
}

@Serializable
enum class PlanTaskType {
    SOIL_PREP, SEED_PURCHASE, FERTILIZATION, SOWING, HERBICIDE, EMERGENCE_CHECK,
    DISEASE_CHECK, PEST_CHECK, HARVEST_PREP, HARVEST,
}

data class CropCalendar(
    val sowingMonth: Int,
    val sowingDay: Int,
    val emergenceDays: Int,
    /** Days from sowing. Missing for crops that do not receive top-dressing nitrogen. */
    val topDressingDays: Int?,
    val diseaseDays: Int,
    val pestDays: Int,
    val harvestDays: Int,
    /** Period when water shortage affects yield the most (months, 1-12). */
    val criticalFrom: Int,
    val criticalTo: Int,
    /** Share of annual rainfall available to the crop. */
    val rainShare: Double,
    val priceEurPerT: Double,
    val costEurHa: Double,
    /** N, P₂O₅ and K₂O requirement, in kg per tonne of yield. */
    val nPerT: Double,
    val pPerT: Double,
    val kPerT: Double,
)

/** Indicative values for conditions in Romania. */
val CROP_CALENDARS: Map<CropCode, CropCalendar> = mapOf(
    CropCode.WHEAT to CropCalendar(10, 5, 12, 165, 205, 225, 275, 4, 5, 0.8, 200.0, 800.0, 25.0, 10.0, 6.0),
    CropCode.BARLEY to CropCalendar(9, 28, 12, 165, 195, 215, 270, 4, 5, 0.8, 180.0, 700.0, 24.0, 10.0, 7.0),
    CropCode.CORN to CropCalendar(4, 5, 12, 40, 66, 118, 193, 7, 8, 0.6, 190.0, 1000.0, 23.0, 10.0, 7.0),
    CropCode.SUNFLOWER to CropCalendar(4, 1, 12, 35, 75, 60, 150, 6, 7, 0.6, 430.0, 650.0, 40.0, 18.0, 12.0),
    CropCode.RAPESEED to CropCalendar(8, 28, 8, 190, 225, 205, 305, 4, 5, 0.8, 460.0, 850.0, 50.0, 25.0, 12.0),
    CropCode.SOY to CropCalendar(4, 25, 12, null, 70, 85, 145, 7, 8, 0.6, 400.0, 650.0, 0.0, 15.0, 12.0),
    CropCode.ALFALFA to CropCalendar(4, 1, 10, null, 40, 50, 75, 6, 8, 0.7, 130.0, 500.0, 0.0, 6.0, 20.0),
    CropCode.OAT to CropCalendar(3, 15, 14, 40, 70, 80, 125, 5, 6, 0.6, 170.0, 550.0, 25.0, 10.0, 8.0),
    CropCode.POTATO to CropCalendar(4, 5, 20, 35, 60, 50, 130, 6, 7, 0.6, 200.0, 4000.0, 4.0, 1.5, 6.0),
    CropCode.BEET to CropCalendar(3, 25, 14, 45, 100, 60, 195, 7, 8, 0.65, 40.0, 1800.0, 2.0, 1.0, 2.5),
    CropCode.VEGETABLES to CropCalendar(4, 20, 10, 35, 50, 40, 100, 6, 8, 0.55, 350.0, 6000.0, 3.0, 1.5, 4.0),
)

@Serializable
enum class StepPriority { MEDIUM, HIGH }

@Serializable
data class PlanStep(
    val key: PlanStepKey,
    val title: String,
    val taskType: PlanTaskType,
    val date: String,
    val priority: StepPriority,
    /** The deadline has passed, so the step no longer becomes a task. */
    val past: Boolean,
)

internal fun Instant.utc() = toLocalDateTime(TimeZone.UTC)

internal fun utcInstant(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
    LocalDateTime(year, month, day, hour, minute).toInstant(TimeZone.UTC)

/** Next agricultural year. After 1 August, the current year's harvest is considered the last crop. */
fun nextSeasonYear(now: Instant = Clock.System.now()): Int {
    val utc = now.utc()
    return if (utc.month.ordinal >= 7) utc.year + 1 else utc.year
}

private fun startOfDay(date: Instant): Instant {
    val utc = date.utc()
    return utcInstant(utc.year, utc.month.ordinal + 1, utc.day, 9)
}

internal fun addDays(date: Instant, days: Int): Instant = date + (days * DAY_MS).milliseconds

private fun sowingIn(crop: CropCode, year: Int): Instant {
    val calendar = CROP_CALENDARS.getValue(crop)
    return utcInstant(year, calendar.sowingMonth, calendar.sowingDay, 9)
}

data class CalendarOptions(
    val now: Instant? = null,
    /** Actual sowing date, if it was recorded. */
    val sowingDate: Instant? = null,
    /** Harvest year, for an already recorded crop. Otherwise the next possible window is chosen. */
    val harvestYear: Int? = null,
)

private fun resolveSowing(crop: CropCode, options: CalendarOptions): Instant {
    options.sowingDate?.let { return it }
    val now = options.now ?: Clock.System.now()
    val harvestDays = CROP_CALENDARS.getValue(crop).harvestDays
    val harvestYear = options.harvestYear
    if (harvestYear != null) {
        val sameYear = sowingIn(crop, harvestYear)
        return if (addDays(sameYear, harvestDays).utc().year > harvestYear) sowingIn(crop, harvestYear - 1) else sameYear
    }
    val earliest = addDays(startOfDay(now), MIN_DAYS_BEFORE_SOWING)
    val thisYear = sowingIn(crop, now.utc().year)
    return if (thisYear >= earliest) thisYear else sowingIn(crop, now.utc().year + 1)
}

@Serializable
data class PlanSchedule(val sowingDate: String, val harvestDate: String, val seasonYear: Int, val steps: List<PlanStep>)

/** The crop's technology calendar, computed from the sowing date. */
fun planCalendar(crop: CropCode, options: CalendarOptions = CalendarOptions()): PlanSchedule {
    val calendar = CROP_CALENDARS.getValue(crop)
    val today = startOfDay(options.now ?: Clock.System.now())
    val sowing = resolveSowing(crop, options)
    val harvest = addDays(sowing, calendar.harvestDays)
    val planned = options.harvestYear == null && options.sowingDate == null

    data class Definition(val key: PlanStepKey, val title: String, val taskType: PlanTaskType, val days: Int, val priority: StepPriority = StepPriority.MEDIUM)

    val definitions = buildList {
        add(Definition(PlanStepKey.SOIL_PREP, "Seedbed preparation", PlanTaskType.SOIL_PREP, -26))
        add(Definition(PlanStepKey.SEED_PURCHASE, "Seed purchase", PlanTaskType.SEED_PURCHASE, -11))
        add(Definition(PlanStepKey.BASE_FERTILIZATION, "Base fertilization", PlanTaskType.FERTILIZATION, -6))
        add(Definition(PlanStepKey.SOWING, "Sowing", PlanTaskType.SOWING, 0, StepPriority.HIGH))
        add(Definition(PlanStepKey.PRE_EMERGENT, "Pre-emergence herbicide", PlanTaskType.HERBICIDE, 2))
        add(Definition(PlanStepKey.EMERGENCE_CHECK, "Emergence check", PlanTaskType.EMERGENCE_CHECK, calendar.emergenceDays))
        calendar.topDressingDays?.let { add(Definition(PlanStepKey.TOP_DRESSING, "Top dressing", PlanTaskType.FERTILIZATION, it)) }
        add(Definition(PlanStepKey.PEST_MONITORING, "Pest monitoring", PlanTaskType.PEST_CHECK, calendar.pestDays))
        add(Definition(PlanStepKey.DISEASE_MONITORING, "Disease monitoring", PlanTaskType.DISEASE_CHECK, calendar.diseaseDays))
        add(Definition(PlanStepKey.HARVEST_PREP, "Harvest preparation", PlanTaskType.HARVEST_PREP, calendar.harvestDays - 14))
        add(Definition(PlanStepKey.HARVEST, "Harvest", PlanTaskType.HARVEST, calendar.harvestDays, StepPriority.HIGH))
    }

    val steps = definitions.map { definition ->
        val date = addDays(sowing, definition.days)
        // Overdue preparatory steps in a new plan are done immediately.
        val due = if (planned && date < today) today else date
        PlanStep(definition.key, definition.title, definition.taskType, due.toJsIsoString(), definition.priority, due < today)
    }.sortedBy { it.date }

    return PlanSchedule(sowing.toJsIsoString(), harvest.toJsIsoString(), harvest.utc().year, steps)
}

@Serializable
data class PlanSoil(val ph: Double, val texture: SoilTexture, val label: String? = null)

typealias PlanClimate = ClimateSummary

@Serializable
data class RankedCrop(
    val crop: CropCode,
    val name: String,
    val score: Int,
    val reasons: List<String>,
    val missingData: List<String>,
    /** The reason for the score, in a single sentence. */
    val reason: String,
)

internal fun joinClauses(clauses: List<String>): String {
    if (clauses.size < 2) return clauses.firstOrNull() ?: ""
    return "${clauses.dropLast(1).joinToString(", ")} and ${clauses.last()}"
}

private fun explain(
    recommendation: CropRecommendation,
    year: Int,
    history: List<CropHistoryEntry>,
    soil: PlanSoil?,
    climate: PlanClimate?,
): String {
    val profile = recommendation.crop.profile
    val rotation = evaluateRotation(recommendation.crop, year, history)
    val previous = history.firstOrNull { it.year == year - 1 }
    val good = mutableListOf<String>()
    val weak = mutableListOf<String>()

    if (rotation.score >= 70) {
        if (previous != null && previous.crop != recommendation.crop) {
            val previousProfile = previous.crop.profile
            good.add("the parcel had ${previousProfile.name.lowercase()} last year")
            if (previousProfile.family == CropFamily.LEGUME) {
                good.add("it uses the nitrogen left in the soil by ${previousProfile.name.lowercase()}")
            }
        } else {
            good.add("the rotation is favourable")
        }
        if (profile.family == CropFamily.LEGUME) good.add("it enriches the soil with nitrogen")
    } else {
        weak.add(rotation.explanation.removeSuffix(".").replaceFirstChar { it.lowercaseChar() })
    }

    if (soil != null) {
        val (min, max) = profile.phRange
        if (soil.ph >= min && soil.ph <= max) {
            good.add("the pH of ${jsNum(soil.ph)} is optimal")
        } else {
            weak.add("the pH of ${jsNum(soil.ph)} is outside the ${jsNum(min)}-${jsNum(max)} range")
        }
        if (soil.texture !in profile.textures) weak.add("the soil texture is not suitable")
    }
    if (climate != null) {
        val needed = profile.waterMm.min
        val rain = jsNum(jsRound(climate.annualPrecipitationMm))
        if (climate.annualPrecipitationMm >= needed) {
            good.add("rainfall of $rain mm covers the minimum requirement")
        } else {
            weak.add("rainfall of $rain mm is below the minimum requirement of ${jsNum(needed)} mm")
        }
    }

    if (recommendation.score >= 70 && good.isNotEmpty()) {
        return "${profile.name} is a good fit because ${joinClauses(good)}."
    }
    if (weak.isNotEmpty()) return "${profile.name} has a lower score because ${joinClauses(weak)}."
    return "${profile.name} has an indicative score, because data is missing about ${joinClauses(recommendation.missingData)}."
}

/** Ranks crops by soil, climate and rotation, with an explanation for each score. */
fun rankCropsForParcel(year: Int, history: List<CropHistoryEntry>, soil: PlanSoil?, climate: PlanClimate?): List<RankedCrop> =
    recommendCrops(
        RecommendationInput(year, history, soil?.let { SoilSummary(it.ph, it.texture) }, climate, CROP_CODES),
    ).map {
        RankedCrop(it.crop, it.name, it.score, it.reasons, it.missingData, explain(it, year, history, soil, climate))
    }

@Serializable
data class FertilizationStage(val stage: String, val date: String?, val nKgHa: Int, val pKgHa: Int, val kKgHa: Int)

@Serializable
data class ParcelReport(
    val parcelName: String,
    val areaHa: Double,
    val soilType: String?,
    val lastCropName: String?,
    val plannedCropName: String,
    val recommendedCrop: CropCode,
    val recommendedCropName: String,
    val recommendedScore: Int,
    val compatibilityScore: Int,
    val expectedYieldTHa: Double,
    /** Missing for operators, who have no access to financial data. */
    val expectedProfitEurHa: Double? = null,
    val priceEurPerT: Double? = null,
    val costEurHa: Double? = null,
    val risks: List<String>,
    val recommendation: String,
    val missingData: List<String>,
)

@Serializable
data class PlanIrrigation(
    val requiredMm: Double,
    val effectiveRainMm: Int?,
    val deficitMm: Int?,
    val passes: Int,
    val doseMm: Int,
    val criticalPeriod: String,
)

@Serializable
data class MonitoringItem(val title: String, val date: String)

@Serializable
data class ParcelPlanData(
    val year: Int,
    val crop: CropCode,
    val cropName: String,
    val rotationScore: Int,
    val rotationExplanation: String,
    val sowingDate: String,
    val harvestDate: String,
    val steps: List<PlanStep>,
    val irrigation: PlanIrrigation,
    val fertilization: List<FertilizationStage>,
    val monitoring: List<MonitoringItem>,
    val recommendations: List<RankedCrop>,
    val report: ParcelReport,
)

data class ParcelPlanInput(
    val crop: CropCode,
    val parcelName: String,
    val areaHa: Double,
    /** Previous crops, including the last declared crop. */
    val history: List<CropHistoryEntry>,
    /** Name of the last crop, when it is not part of the catalog. */
    val lastCropName: String? = null,
    val soil: PlanSoil?,
    val climate: PlanClimate?,
    val now: Instant = Clock.System.now(),
    /** Year of an already recorded crop. */
    val year: Int? = null,
    val sowingDate: Instant? = null,
)

private fun roundTo5(value: Double): Int = jsRoundInt(value / 5) * 5

private fun period(from: Int, to: Int) = "${MONTHS[from - 1]}-${MONTHS[to - 1]}"

/** Generates the full crop plan: steps, calendar, water, nutrients, monitoring and report. */
fun buildParcelPlan(input: ParcelPlanInput): ParcelPlanData {
    val crop = input.crop
    val history = input.history
    val soil = input.soil
    val climate = input.climate
    val profile = crop.profile
    val calendar = CROP_CALENDARS.getValue(crop)
    val schedule = planCalendar(crop, CalendarOptions(input.now, input.sowingDate, input.year))
    val year = input.year ?: schedule.seasonYear

    // All crops are compared in the same agricultural year, so the scores stay comparable.
    val latestHistoryYear = history.maxOfOrNull { it.year + 1 } ?: Int.MIN_VALUE
    val comparisonYear = min(year, max(nextSeasonYear(input.now), latestHistoryYear))
    val recommendations = rankCropsForParcel(comparisonYear, history, soil, climate)
    val chosen = recommendations.first { it.crop == crop }
    val best = recommendations.first()
    val rotation = evaluateRotation(crop, comparisonYear, history)

    val requiredMm = (profile.waterMm.min + profile.waterMm.max) / 2
    val effectiveRainMm = climate?.let { jsRoundInt(it.annualPrecipitationMm * calendar.rainShare) }
    val deficitMm = effectiveRainMm?.let { max(0, jsRoundInt(requiredMm - it)) }
    val passes = if (deficitMm != null && deficitMm != 0) ceil(deficitMm.toDouble() / IRRIGATION_DOSE_MM).toInt() else 0
    val criticalPeriod = period(calendar.criticalFrom, calendar.criticalTo)

    val waterFactor = if (effectiveRainMm == null) 1.0 else (effectiveRainMm / requiredMm).clamp(0.6, 1.0)
    val scoreFactor = (0.55 + (chosen.score / 100.0) * 0.5).clamp(0.6, 1.05)
    val expectedYieldTHa = jsRound(profile.referenceYieldTHa * scoreFactor * waterFactor * 10) / 10
    val expectedProfitEurHa = roundTo5(expectedYieldTHa * calendar.priceEurPerT - calendar.costEurHa)

    val previous = history.sortedByDescending { it.year }.firstOrNull()
    val nitrogenCredit = if (previous != null && previous.crop.profile.family == CropFamily.LEGUME) 30 else 0
    val nitrogen = max(0.0, expectedYieldTHa * calendar.nPerT - nitrogenCredit)
    fun dateOf(key: PlanStepKey) = schedule.steps.firstOrNull { it.key == key }?.date
    val hasTopDressing = calendar.topDressingDays != null
    val fertilization = buildList {
        add(
            FertilizationStage(
                stage = "Base fertilization",
                date = dateOf(PlanStepKey.BASE_FERTILIZATION),
                nKgHa = roundTo5(if (hasTopDressing) nitrogen * 0.3 else nitrogen),
                pKgHa = roundTo5(expectedYieldTHa * calendar.pPerT),
                kKgHa = roundTo5(expectedYieldTHa * calendar.kPerT),
            ),
        )
        if (hasTopDressing) add(FertilizationStage("Top dressing", dateOf(PlanStepKey.TOP_DRESSING), roundTo5(nitrogen * 0.7), 0, 0))
    }

    val hasDeficit = deficitMm != null && deficitMm != 0
    val risks = mutableListOf<String>()
    if (hasDeficit) {
        risks.add("Drought during $criticalPeriod.")
        risks.add("Rainfall deficit estimated at $deficitMm mm per season.")
    }
    if (rotation.score < 70) risks.add(rotation.explanation)
    if (soil != null && (soil.ph < profile.phRange.min || soil.ph > profile.phRange.max)) {
        risks.add("The pH of ${jsNum(soil.ph)} is outside the optimal range of ${jsNum(profile.phRange.min)}-${jsNum(profile.phRange.max)}.")
    }
    if (risks.isEmpty()) risks.add("The available data does not indicate major risks.")

    val advice = mutableListOf(
        when {
            hasDeficit ->
                "Plan at least $passes irrigation ${if (passes == 1) "cycle" else "cycles"} of $IRRIGATION_DOSE_MM mm during the critical growth period ($criticalPeriod)."
            effectiveRainMm == null -> "Check the water reserve before the critical period ($criticalPeriod)."
            else -> "Average rainfall covers the water requirement. Monitor soil moisture during $criticalPeriod."
        },
    )
    if (best.crop != crop && best.score - chosen.score >= 10) {
        advice.add("${best.name} scores better on this parcel: ${best.score}%.")
    }

    val lastCropName = input.lastCropName ?: previous?.crop?.profile?.name
    val monitoringKeys = setOf(PlanStepKey.EMERGENCE_CHECK, PlanStepKey.PEST_MONITORING, PlanStepKey.DISEASE_MONITORING)

    return ParcelPlanData(
        year = year,
        crop = crop,
        cropName = profile.name,
        rotationScore = rotation.score,
        rotationExplanation = rotation.explanation,
        sowingDate = schedule.sowingDate,
        harvestDate = schedule.harvestDate,
        steps = schedule.steps,
        irrigation = PlanIrrigation(requiredMm, effectiveRainMm, deficitMm, passes, IRRIGATION_DOSE_MM, criticalPeriod),
        fertilization = fertilization,
        monitoring = schedule.steps.filter { it.key in monitoringKeys }.map { MonitoringItem(it.title, it.date) },
        recommendations = recommendations,
        report = ParcelReport(
            parcelName = input.parcelName,
            areaHa = jsRound(input.areaHa * 100) / 100,
            soilType = soil?.label,
            lastCropName = lastCropName,
            plannedCropName = profile.name,
            recommendedCrop = best.crop,
            recommendedCropName = best.name,
            recommendedScore = best.score,
            compatibilityScore = chosen.score,
            expectedYieldTHa = expectedYieldTHa,
            expectedProfitEurHa = expectedProfitEurHa.toDouble(),
            priceEurPerT = calendar.priceEurPerT,
            costEurHa = calendar.costEurHa,
            risks = risks,
            recommendation = advice.joinToString(" "),
            missingData = chosen.missingData,
        ),
    )
}

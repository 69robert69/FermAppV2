package com.fermapp.api.modules.intelligence

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.crops.CropRules
import com.fermapp.api.domain.crops.CropRules.VegetationHealth
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.intelligence.Intelligence
import com.fermapp.api.domain.intelligence.Intelligence.ActionPriority
import com.fermapp.api.domain.intelligence.Intelligence.CostItem
import com.fermapp.api.domain.intelligence.Intelligence.CostSummaryInput
import com.fermapp.api.domain.intelligence.Intelligence.CropProgress
import com.fermapp.api.domain.intelligence.Intelligence.NextAction
import com.fermapp.api.domain.intelligence.Intelligence.NextActionInput
import com.fermapp.api.domain.intelligence.Intelligence.ParcelEconomics
import com.fermapp.api.domain.intelligence.Intelligence.RecentWork
import com.fermapp.api.domain.intelligence.Intelligence.SprayAlert
import com.fermapp.api.domain.intelligence.Intelligence.WeatherSummary
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.IotDeviceKind
import com.fermapp.api.domain.model.Role
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.model.TaskStatus
import com.fermapp.api.domain.rotation.Rotation
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry
import com.fermapp.api.domain.rotation.Rotation.RotationResult
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.integrations.OpenMeteoClient
import com.fermapp.api.integrations.OpenMeteoClient.ForecastHourFull
import com.fermapp.api.integrations.OpenMeteoClient.WeatherDay
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.MachineLocationView
import com.fermapp.api.modules.common.Views.ObservationView
import com.fermapp.api.modules.common.Views.PhotoView
import com.fermapp.api.modules.common.Views.TaskWithAssignee
import com.fermapp.api.modules.plans.PlanService
import com.fermapp.api.modules.work.WorkService
import com.fermapp.api.modules.work.WorkViews
import com.fermapp.api.modules.work.WorkViews.IrrigationView
import com.fermapp.api.modules.work.WorkViews.LabelledIrrigation
import com.fermapp.api.modules.work.WorkViews.WorkView
import com.fermapp.api.modules.work.WorkViews.WorkWithLocation
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.entity.SatelliteObservation
import com.fermapp.api.persistence.entity.Task
import com.fermapp.api.persistence.entity.WeatherData
import com.fermapp.api.persistence.repository.CostEntryRepository
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.IncomeRepository
import com.fermapp.api.persistence.repository.IotReadingRepository
import com.fermapp.api.persistence.repository.IrrigationRunRepository
import com.fermapp.api.persistence.repository.MachineLocationRepository
import com.fermapp.api.persistence.repository.ObservationRepository
import com.fermapp.api.persistence.repository.ParcelPlanRepository
import com.fermapp.api.persistence.repository.PhotoRepository
import com.fermapp.api.persistence.repository.SatelliteObservationRepository
import com.fermapp.api.persistence.repository.SoilProfileRepository
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.persistence.repository.WeatherDataRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import com.fermapp.api.security.AuthContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** Everything the Parcel Intelligence panel shows (intelligence.service.ts). */
@Service
class IntelligenceService(
    private val access: AccessGuard,
    private val openMeteo: OpenMeteoClient,
    private val weatherData: WeatherDataRepository,
    private val seasons: CropSeasonRepository,
    private val costs: CostEntryRepository,
    private val incomes: IncomeRepository,
    private val satellite: SatelliteObservationRepository,
    private val soilProfiles: SoilProfileRepository,
    private val works: WorkSessionRepository,
    private val runs: IrrigationRunRepository,
    private val locations: MachineLocationRepository,
    private val tasks: TaskRepository,
    private val readings: IotReadingRepository,
    private val photos: PhotoRepository,
    private val observations: ObservationRepository,
    private val plans: ParcelPlanRepository,
    private val tx: TransactionTemplate,
    private val mapper: ObjectMapper,
) {

    // ------------------------------------------------------------------ response shapes

    @JvmRecord
    data class DayWithRain(
        val date: String,
        val tMaxC: Double,
        val tMinC: Double,
        val tMeanC: Double?,
        val precipitationMm: Double,
        val radiationMjM2: Double?,
        val et0Mm: Double?,
        val windMaxKmh: Double?,
        val humidityPct: Double?,
        val rainProbabilityPct: Double?,
    ) {
        companion object {
            fun of(day: WeatherDay, rain: Double?): DayWithRain = DayWithRain(
                day.date, day.tMaxC, day.tMinC, day.tMeanC, day.precipitationMm, day.radiationMjM2,
                day.et0Mm, day.windMaxKmh, day.humidityPct, rain,
            )
        }
    }

    @JvmRecord
    data class ParcelWeather(
        val timezone: String,
        val current: ForecastHourFull?,
        val today: DayWithRain?,
        val next7: List<DayWithRain>,
        val next14: List<DayWithRain>,
        val alerts: List<SprayAlert>,
    )

    @JvmRecord
    data class HistoryItem(val year: Int, val crop: CropCode?, val name: String)

    @JvmRecord
    data class RotationScore(@get:JsonUnwrapped val result: RotationResult, val mode: String, val history: List<HistoryItem>)

    @JvmRecord
    data class EconomicsView(
        val year: Int,
        val seasonId: UUID?,
        val expectedYieldTHa: Double?,
        val expectedPricePerT: Double?,
        @get:JsonUnwrapped val economics: ParcelEconomics,
    )

    @JvmRecord
    data class NdviPoint(val date: String, val ndvi: Double, val ndre: Double?, val healthIndex: Int, val health: VegetationHealth)

    @JvmRecord
    data class NdviComparison(val latest: NdviPoint?, val sevenDaysAgo: NdviPoint?, val thirtyDaysAgo: NdviPoint?)

    @JvmRecord
    data class Header(
        val id: UUID?,
        val name: String?,
        val code: String?,
        val areaHa: Double,
        val county: String?,
        val locality: String?,
        val lat: Double,
        val lng: Double,
    )

    @JvmRecord
    data class CropPanel(
        val seasonId: UUID?,
        val code: CropCode?,
        val name: String,
        val year: Int,
        val sowingDate: Instant?,
        @get:JsonUnwrapped val progress: CropProgress?,
    )

    @JvmRecord
    data class IrrigationPanel(val configured: Boolean, val irrigatedHa: Double?, val notIrrigatedHa: Double?, val coveragePct: Long?)

    @JvmRecord
    data class PlanPanel(val id: UUID?, val year: Int, val createdAt: Instant?, val data: JsonNode?)

    @JvmRecord
    data class PlanHistoryItem(val id: UUID?, val year: Int, val cropName: String, val trigger: String?, val active: Boolean, val createdAt: Instant?)

    @JvmRecord
    data class TaskSummary(val todo: Long, val inProgress: Long, val done: Long)

    @JvmRecord
    data class TaskPanel(val summary: TaskSummary, val items: List<TaskWithAssignee>)

    @JvmRecord
    data class IntelligenceData(
        val generatedAt: String,
        val header: Header,
        val crop: CropPanel?,
        val rotation: RotationScore,
        val irrigation: IrrigationPanel,
        val activeIrrigation: List<LabelledIrrigation>,
        val activeWork: List<WorkWithLocation>,
        val nextAction: NextAction,
        val plan: PlanPanel?,
        val planHistory: List<PlanHistoryItem>,
        val tasks: TaskPanel,
        val weather: ParcelWeather?,
        val soil: ObjectNode,
        val economics: EconomicsView?,
        val satellite: NdviComparison,
        val photos: List<PhotoView>,
        val observations: List<ObservationView>,
    )

    // ------------------------------------------------------------------ sections

    fun parcelWeather(parcel: Parcel): ParcelWeather {
        val forecast = openMeteo.getForecast(parcel.centerLat, parcel.centerLng)
        val now = Times.now()
        val hours = OpenMeteoClient.upcomingHours(forecast, now)
        val today = OpenMeteoClient.localHour(forecast, now).substring(0, 10)

        val rainChance = HashMap<String, Double>()
        for (hour in forecast.hours) {
            val date = hour.time().substring(0, 10)
            val chance = hour.precipitationProbabilityPct() ?: 0.0
            rainChance.merge(date, chance) { a, b -> max(a, b) }
        }
        val days = forecast.days
            .filter { it.date >= today }
            .map { DayWithRain.of(it, rainChance[it.date]) }

        // Daily history is kept for analysis, and an error here does not affect the forecast.
        if (days.isNotEmpty()) {
            val current = days[0]
            try {
                val date = LocalDate.parse(current.date)
                val row = weatherData.findByParcelIdAndDate(parcel.id, date).orElseGet {
                    WeatherData().also {
                        it.parcelId = parcel.id
                        it.date = date
                    }
                }
                row.setTMaxC(current.tMaxC)
                row.setTMinC(current.tMinC)
                row.precipitationMm = current.precipitationMm
                row.humidityPct = current.humidityPct
                row.windMaxKmh = current.windMaxKmh
                row.et0Mm = current.et0Mm
                weatherData.save(row)
            } catch (error: RuntimeException) {
                log.warn("Saving weather failed: {}", error.message)
            }
        }

        val nextHours = hours.subList(0, min(6, hours.size)).map {
            doubleArrayOf(it.windKmh(), it.precipitationProbabilityPct() ?: 0.0)
        }
        return ParcelWeather(
            forecast.timezone, hours.firstOrNull(), days.firstOrNull(),
            days.subList(min(1, days.size), min(8, days.size)),
            days.subList(min(1, days.size), min(15, days.size)),
            Intelligence.sprayingAlerts(nextHours),
        )
    }

    fun rotationScore(parcelId: UUID, now: Instant): RotationScore {
        val list = seasons.findTop10ByParcelIdOrderByYearDesc(parcelId)
        val history = list.map { CropHistoryEntry(it.crop, it.year) }
        val year = Times.localYear(now)
        val items = list.map { HistoryItem(it.year, it.crop, Crops.name(it.crop)) }
        val current = list.firstOrNull { it.year == year }
        if (current != null) {
            return RotationScore(Rotation.evaluateRotation(current.crop, year, history), "CURRENT", items)
        }
        val best = Rotation.rankRotationOptions(year, history, Crops.CROP_CODES)[0]
        return RotationScore(best, "SUGGESTED", items)
    }

    fun economicsFor(parcel: Parcel, requestedYear: Int?): EconomicsView {
        val year = requestedYear ?: Times.currentLocalYear()
        val entries = costs.findByParcelIdAndYear(parcel.id, year)
        val season = seasons.findByParcelIdAndYear(parcel.id, year).orElse(null)
        val realized = incomes.findByParcelIdAndYear(parcel.id, year)
        val expectedPrice = number(season?.expectedPricePerT)
        val input = CostSummaryInput(
            parcel.areaHa,
            entries.map { CostItem(it.category, it.amount.toDouble()) },
            season?.expectedYieldTHa, expectedPrice,
            season?.yieldT, number(season?.actualPricePerT),
        )
        val recordedIncome = realized.sumOfDoubles { it.amount.toDouble() }
        return EconomicsView(
            year, season?.id, season?.expectedYieldTHa, expectedPrice,
            Intelligence.parcelEconomics(input, recordedIncome),
        )
    }

    private fun latestSoilMoisture(parcelId: UUID): Double? {
        val found = readings.findLatestOfKind(
            parcelId, IotDeviceKind.SOIL_MOISTURE,
            Instant.now().minusMillis(2 * Times.DAY_MS), PageRequest.of(0, 1),
        )
        return found.firstOrNull()?.value
    }

    // ------------------------------------------------------------------ the whole panel

    fun buildIntelligence(auth: AuthContext, parcelId: UUID): IntelligenceData {
        val parcel = access.requireParcel(auth.farmId(), parcelId)
        val now = Times.now()
        val year = Times.localYear(now)

        val weather = try {
            parcelWeather(parcel)
        } catch (error: RuntimeException) {
            log.warn("Weather unavailable in the parcel panel: {}", error.message)
            null
        }

        return tx.execute {
            val recorded = seasons.findByParcelIdAndYear(parcelId, year).orElse(null)
            val observationsList = satellite.findByParcelIdOrderByDateDesc(parcelId, PageRequest.of(0, 40))
            val activeWork = works.findByParcelIdAndStatusInOrderByStartedAtDesc(parcelId, WorkService.ACTIVE)
            val activeIrrigation = runs.findByParcelIdAndStatusInOrderByStartedAtAsc(parcelId, WorkService.ACTIVE)
            val recentWork = works.findByParcelIdAndStatusAndStartedAtGreaterThanEqual(
                parcelId, RunStatus.COMPLETED,
                now.minusMillis(60 * Times.DAY_MS),
            )
            val taskList = tasks.findForParcel(parcelId, PageRequest.of(0, 50))
            val soilMoisture = latestSoilMoisture(parcelId)
            val planList = plans.findByParcelIdOrderByCreatedAtDesc(parcelId, PageRequest.of(0, 10))

            val activePlan = planList.filter { it.isActive && it.year >= year }.minByOrNull { it.year }
            val planData = activePlan?.data

            // The last crop declared in the wizard is not the crop in the field when the plan starts in a future year.
            val season: CropSeason? =
                if (recorded != null && activePlan != null && activePlan.year > year && recorded.sowingDate == null) null else recorded
            val progress = season?.let {
                Intelligence.cropProgress(it.crop, it.sowingDate, it.harvestDate, it.expectedHarvestDate, now)
            }

            val week = weather?.next7 ?: listOf()
            val weatherInput = if (week.isEmpty()) {
                null
            } else {
                WeatherSummary(
                    week.sumOfDoubles { it.humidityPct ?: 0.0 } / week.size,
                    week.sumOfDoubles { it.tMeanC ?: ((it.tMaxC + it.tMinC) / 2) } / week.size,
                    week.minOf { it.tMinC },
                    week.count { it.precipitationMm >= 1 },
                    week.sumOfDoubles { it.precipitationMm },
                    week.sumOfDoubles { it.et0Mm ?: 0.0 },
                )
            }

            val irrigated = parcel.irrigatedAreaHa?.let { min(it, parcel.areaHa) }
            val irrigationViews = activeIrrigation.mapIndexed { i, run ->
                LabelledIrrigation(IrrigationView.of(run, now), WorkViews.systemLabel(run.systemType) + " #" + (i + 1))
            }
            val workViews = activeWork.map { work ->
                WorkWithLocation(
                    WorkView.of(work, now),
                    MachineLocationView.of(locations.findFirstByWorkSessionIdOrderByRecordedAtDesc(work.id).orElse(null)),
                )
            }

            val planAction = if (season == null && planData != null) nextPlanAction(planData, taskList, now) else null
            val nextAction = planAction ?: Intelligence.nextRecommendedAction(
                NextActionInput(
                    season?.crop, progress, weatherInput, soilMoisture,
                    observationsList.firstOrNull()?.healthClass,
                    recentWork.map { RecentWork(it.type.name, it.startedAt) }, now,
                ),
            )

            val todo = taskList.count { it.status == TaskStatus.TODO }.toLong()
            val inProgress = taskList.count { it.status == TaskStatus.IN_PROGRESS }.toLong()
            val done = taskList.count { it.status == TaskStatus.DONE }.toLong()

            IntelligenceData(
                Times.iso(now)!!,
                Header(
                    parcel.id, parcel.name, parcel.code, Js.round2(parcel.areaHa), parcel.county,
                    parcel.locality, parcel.centerLat, parcel.centerLng,
                ),
                season?.let { CropPanel(it.id, it.crop, Crops.name(it.crop), it.year, it.sowingDate, progress) },
                rotationScore(parcel.id, now),
                IrrigationPanel(
                    irrigated != null, irrigated?.let { Js.round2(it) },
                    irrigated?.let { Js.round2(parcel.areaHa - it) },
                    irrigated?.let { Math.round(it / parcel.areaHa * 100) },
                ),
                irrigationViews,
                workViews,
                nextAction,
                activePlan?.let { PlanPanel(it.id, it.year, it.createdAt, PlanService.planForRole(planData, auth.role())) },
                planList.map { PlanHistoryItem(it.id, it.year, Crops.name(it.crop), it.trigger, it.isActive, it.createdAt) },
                TaskPanel(TaskSummary(todo, inProgress, done), taskList.map { TaskWithAssignee.of(it) }),
                weather,
                SoilSection.of(soilProfiles.findByParcelId(parcelId).orElse(null), mapper),
                // Financial data is not sent to operators.
                if (auth.role() == Role.OPERATOR) null else economicsFor(parcel, year),
                ndviComparison(observationsList, now),
                photos.findByParcelIdOrderByCreatedAtDesc(parcelId, PageRequest.of(0, 12)).map { PhotoView.of(it) },
                observations.findLatest(parcelId, PageRequest.of(0, 10)).map { ObservationView.of(it) },
            )
        }!!
    }

    companion object {
        private val log = LoggerFactory.getLogger(IntelligenceService::class.java)

        private fun number(value: BigDecimal?): Double? = value?.toDouble()

        /** The observation closest to the target date, if one exists within the given tolerance. */
        private fun nearest(list: List<SatelliteObservation>, target: Long, toleranceDays: Int, exclude: UUID?): SatelliteObservation? {
            var best: SatelliteObservation? = null
            var bestDistance = toleranceDays * Times.DAY_MS
            for (item in list) {
                if (item.id == exclude) {
                    continue
                }
                val distance = abs(Times.atUtcMidnight(item.date)!!.toEpochMilli() - target)
                if (distance <= bestDistance) {
                    best = item
                    bestDistance = distance
                }
            }
            return best
        }

        private fun point(item: SatelliteObservation?): NdviPoint? = item?.let {
            NdviPoint(it.date.toString(), it.ndvi, it.ndre, it.healthIndex, CropRules.vegetationHealth(it.ndvi, it.ndre))
        }

        @JvmStatic
        fun ndviComparison(list: List<SatelliteObservation>, now: Instant): NdviComparison {
            val latest = list.firstOrNull()
            val exclude = latest?.id
            return NdviComparison(
                point(latest),
                point(nearest(list, now.toEpochMilli() - 7 * Times.DAY_MS, 4, exclude)),
                point(nearest(list, now.toEpochMilli() - 30 * Times.DAY_MS, 8, exclude)),
            )
        }

        /** For a planned crop, the next action is the first unfinished step of the plan. */
        private fun nextPlanAction(plan: JsonNode, list: List<Task>, now: Instant): NextAction? {
            val next = list
                .filter { it.planStepKey != null && it.status != TaskStatus.DONE }
                .minWithOrNull(compareBy { it.dueDate })
                ?: return null
            val cropName = plan.path("cropName").asText("")
            return NextAction(
                "PLAN_STEP", next.title,
                "It is the next step in the technology plan for " + cropName.lowercase(Locale.getDefault()) + ".",
                ActionPriority.MEDIUM, ActionPriority.LOW, 80,
                max(0.0, ceil((next.dueDate.toEpochMilli() - now.toEpochMilli()).toDouble() / Times.DAY_MS)).toInt(),
            )
        }
    }
}

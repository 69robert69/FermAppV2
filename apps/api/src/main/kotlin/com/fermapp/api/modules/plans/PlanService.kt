package com.fermapp.api.modules.plans

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fermapp.api.common.cache.CacheService
import com.fermapp.api.common.util.Concurrency
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.crops.Crops.SoilTexture
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.Role
import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.domain.model.TaskPriority
import com.fermapp.api.domain.model.TaskType
import com.fermapp.api.domain.plan.ParcelPlanner
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanData
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanInput
import com.fermapp.api.domain.plan.ParcelPlanner.PlanClimate
import com.fermapp.api.domain.plan.ParcelPlanner.PlanSoil
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry
import com.fermapp.api.domain.soil.SoilRules
import com.fermapp.api.integrations.NasaPowerClient
import com.fermapp.api.integrations.SoilGridsClient
import com.fermapp.api.persistence.entity.CropRecommendationRecord
import com.fermapp.api.persistence.entity.ParcelPlan
import com.fermapp.api.persistence.entity.Task
import com.fermapp.api.persistence.repository.CropRecommendationRepository
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.ParcelPlanRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.persistence.repository.SoilProfileRepository
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** Technology plans: site data, saving, regeneration and role filtering (plan.service.ts). */
@Service
class PlanService(
    private val soilProfiles: SoilProfileRepository,
    private val seasons: CropSeasonRepository,
    private val parcels: ParcelRepository,
    private val plans: ParcelPlanRepository,
    private val tasks: TaskRepository,
    private val recommendations: CropRecommendationRepository,
    private val soilGrids: SoilGridsClient,
    private val nasa: NasaPowerClient,
    private val cache: CacheService,
    private val realtime: RealtimeHub,
    private val tx: TransactionTemplate,
    private val mapper: ObjectMapper,
) {

    /** Soil and climate data for the location; an unavailable source is null and does not stop planning. */
    @JvmRecord
    data class SiteData(val soil: PlanSoil?, val climate: PlanClimate?)

    /** Kept in the cache as JSON. */
    @JvmRecord
    private data class CachedSoil(val ph: Double, val texture: SoilTexture?, val label: String?)

    fun loadSiteData(lat: Double, lng: Double, parcelId: UUID?): SiteData {
        val profile = parcelId?.let { soilProfiles.findByParcelId(it).orElse(null) }
        val savedTexture = texture(profile?.texture)
        val savedPh = profile?.ph
        val saved = if (profile != null && profile.status == SoilStatus.READY && savedPh != null && savedTexture != null) {
            PlanSoil(savedPh, savedTexture, profile.label)
        } else {
            null
        }
        val site = Concurrency.both<PlanSoil?, PlanClimate?>({ saved ?: soilFromSoilGrids(lat, lng) }, { climate(lat, lng) })
        return SiteData(site.first, site.second)
    }

    private fun soilFromSoilGrids(lat: Double, lng: Double): PlanSoil? = try {
        val soil = cache.cached("plan-soil:" + Js.toFixed(lat, 4) + ":" + Js.toFixed(lng, 4), SOIL_CACHE_SECONDS, CachedSoil::class.java) {
            val values = soilGrids.fetch(lat, lng) ?: throw IllegalStateException("SoilGrids has no data for this location.")
            val soilClass = SoilRules.classifySoil(values)
            CachedSoil(values.ph, soilClass.texture, soilClass.label)
        }
        PlanSoil(soil.ph, soil.texture, soil.label)
    } catch (error: RuntimeException) {
        log.warn("Soil unavailable for plan: {}", error.message)
        null
    }

    private fun climate(lat: Double, lng: Double): PlanClimate? = try {
        nasa.getClimateNormals(lat, lng)?.let { PlanClimate(it.seasonTempC, it.annualPrecipitationMm, it.gddBase10) }
    } catch (error: RuntimeException) {
        log.warn("Climate unavailable for plan: {}", error.message)
        null
    }

    fun parcelCropHistory(parcelId: UUID, beforeYear: Int?): List<CropHistoryEntry> {
        val list = if (beforeYear == null) {
            seasons.findByParcelIdOrderByYearDesc(parcelId)
        } else {
            seasons.findByParcelIdAndYearLessThanOrderByYearDesc(parcelId, beforeYear)
        }
        return list.map { CropHistoryEntry(it.crop, it.year) }
    }

    fun toJson(plan: ParcelPlanData): JsonNode = mapper.valueToTree(plan)

    /**
     * Replaces the year's plan and its not-started tasks. Work that was started or completed is kept.
     * Must run inside a transaction.
     */
    fun savePlan(farmId: UUID, parcelId: UUID, plan: ParcelPlanData, trigger: String): ParcelPlan {
        val keptSteps = tasks.findKeptStepKeys(parcelId, plan.year, plan.crop).toHashSet()
        tasks.deleteNotStartedPlanTasks(parcelId, plan.year)
        plans.deactivate(parcelId, plan.year)

        val saved = ParcelPlan()
        saved.parcelId = parcelId
        saved.year = plan.year
        saved.crop = plan.crop
        saved.trigger = trigger
        saved.active = true
        saved.data = toJson(plan)
        plans.saveAndFlush(saved)

        val created = plan.steps.filter { !it.past && it.key !in keptSteps }.map { step ->
            Task().apply {
                this.farmId = farmId
                this.parcelId = parcelId
                planId = saved.id
                planStepKey = step.key
                type = TaskType.valueOf(step.taskType)
                title = step.title + ": " + plan.cropName
                description = "Generated automatically from the ${plan.year} technology plan."
                priority = TaskPriority.valueOf(step.priority)
                dueDate = Instant.parse(step.date)
            }
        }
        tasks.saveAll(created)

        recommendations.deleteForYear(parcelId, plan.year)
        recommendations.saveAll(
            plan.recommendations.map { item ->
                CropRecommendationRecord().apply {
                    this.parcelId = parcelId
                    year = plan.year
                    crop = item.crop
                    score = item.score
                    reasons = mapper.valueToTree(listOf(item.reason) + item.reasons)
                }
            },
        )

        val parcel = parcels.findById(parcelId).orElseThrow()
        parcel.plannedCrop = plan.crop
        parcels.save(parcel)
        return saved
    }

    fun announcePlan(farmId: UUID, parcelId: UUID) {
        val payload = mapOf("parcelId" to parcelId)
        realtime.emitToFarm(farmId, RealtimeEvent.TASK_UPDATED, payload)
        realtime.emitToFarm(farmId, RealtimeEvent.PARCEL_UPDATED, payload)
    }

    /**
     * Recalculates everything that depends on the crop: rotation, calendar, water, fertilization,
     * monitoring, tasks and recommendations. Completed years remain unchanged.
     */
    fun regeneratePlan(farmId: UUID, parcelId: UUID, year: Int, cropChanged: Boolean, now: Instant): ParcelPlan? {
        if (year < Times.utcYear(now)) {
            return null
        }
        val parcel = parcels.findById(parcelId).orElse(null) ?: return null
        val season = seasons.findByParcelIdAndYear(parcelId, year).orElse(null) ?: return null
        val history = parcelCropHistory(parcelId, year)
        val site = loadSiteData(parcel.centerLat, parcel.centerLng, parcelId)
        val plan = ParcelPlanner.buildParcelPlan(
            ParcelPlanInput(season.crop, parcel.name, parcel.areaHa, history, null, site.soil, site.climate, now, year, season.sowingDate),
        )

        val saved = tx.execute {
            val result = savePlan(farmId, parcelId, plan, "CROP_CHANGED")
            val current = seasons.findById(season.id).orElseThrow()
            // Values entered by the user remain, as long as the crop is the same.
            if (cropChanged || current.expectedHarvestDate == null) {
                current.expectedHarvestDate = Instant.parse(plan.harvestDate)
            }
            if (cropChanged || current.expectedYieldTHa == null) {
                current.expectedYieldTHa = plan.report.expectedYieldTHa
            }
            seasons.save(current)
            result
        }
        announcePlan(farmId, parcelId)
        return saved
    }

    /** Saving a crop succeeds even when recalculating the plan fails. */
    fun regeneratePlanSafely(farmId: UUID, parcelId: UUID, year: Int, cropChanged: Boolean) {
        try {
            regeneratePlan(farmId, parcelId, year, cropChanged, Times.now())
        } catch (error: RuntimeException) {
            log.error("Plan recalculation failed", error)
        }
        realtime.emitToFarm(farmId, RealtimeEvent.PARCEL_UPDATED, mapOf("parcelId" to parcelId))
    }

    /** Deleting the crop also removes its not-started tasks. */
    fun removePlan(farmId: UUID, parcelId: UUID, year: Int) {
        tx.executeWithoutResult {
            tasks.deleteNotStartedPlanTasks(parcelId, year)
            plans.deactivate(parcelId, year)
        }
        announcePlan(farmId, parcelId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlanService::class.java)
        private const val SOIL_CACHE_SECONDS = 86_400L

        private fun texture(value: String?): SoilTexture? = SoilTexture.entries.firstOrNull { it.name == value }

        /** The parcel history, completed with the last crop declared in the wizard. */
        @JvmStatic
        fun historyWithLastCrop(history: List<CropHistoryEntry>, lastCrop: String, now: Instant): List<CropHistoryEntry> {
            if (lastCrop == ParcelPlanner.OTHER_CROP) {
                return history
            }
            val year = ParcelPlanner.nextSeasonYear(now) - 1
            return history.filter { it.year != year } + CropHistoryEntry(CropCode.valueOf(lastCrop), year)
        }

        @JvmStatic
        fun lastCropLabel(lastCrop: String, name: String?): String {
            if (lastCrop == ParcelPlanner.OTHER_CROP) {
                // String.trim() semantics: control characters and spaces only.
                val trimmed = name?.trim { it <= ' ' } ?: ""
                return trimmed.ifEmpty { "Other crop" }
            }
            return Crops.name(CropCode.valueOf(lastCrop))
        }

        /** Financial data is not sent to operators. */
        @JvmStatic
        fun planForRole(data: JsonNode?, role: Role?): JsonNode? {
            if (role != Role.OPERATOR || data == null || !data.has("report")) {
                return data
            }
            val copy = data.deepCopy<JsonNode>()
            (copy.get("report") as ObjectNode).remove(listOf("expectedProfitEurHa", "priceEurPerT", "costEurHa"))
            return copy
        }
    }
}

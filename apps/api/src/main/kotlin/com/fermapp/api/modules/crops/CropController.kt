package com.fermapp.api.modules.crops

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Concurrency
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.crops.CropRules
import com.fermapp.api.domain.crops.CropRules.ClimateFit
import com.fermapp.api.domain.crops.CropRules.CropDevelopment
import com.fermapp.api.domain.crops.CropRules.CropRecommendation
import com.fermapp.api.domain.crops.CropRules.DailyTemperature
import com.fermapp.api.domain.crops.CropRules.SoilFit
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.crops.Crops.CropProfile
import com.fermapp.api.domain.crops.Crops.SoilTexture
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.domain.rotation.Rotation
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry
import com.fermapp.api.domain.rotation.Rotation.RotationResult
import com.fermapp.api.integrations.NasaPowerClient
import com.fermapp.api.integrations.NasaPowerClient.ClimateNormals
import com.fermapp.api.integrations.NasaPowerClient.ClimateYear
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.CropSeasonView
import com.fermapp.api.modules.plans.PlanService
import com.fermapp.api.persistence.entity.CropRecommendationRecord
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.entity.SoilProfile
import com.fermapp.api.persistence.repository.CropRecommendationRepository
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.SoilProfileRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.openapitools.jackson.nullable.JsonNullable
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Crop catalog, crop history, rotation, recommendations, climate and crop development (crops.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Crops and rotation")
class CropController(
    private val seasons: CropSeasonRepository,
    private val soilProfiles: SoilProfileRepository,
    private val recommendations: CropRecommendationRepository,
    private val access: AccessGuard,
    private val plans: PlanService,
    private val nasa: NasaPowerClient,
    private val tx: TransactionTemplate,
    private val mapper: ObjectMapper,
) {

    class SeasonCreate(
        @field:NotNull val crop: CropCode?,
        @field:NotNull @field:Min(1990) @field:Max(2100) val year: Int?,
        val sowingDate: Instant?,
        val expectedHarvestDate: Instant?,
        val harvestDate: Instant?,
        @field:DecimalMin("0") @field:DecimalMax("1000000") val yieldT: Double?,
        @field:DecimalMin("0") @field:DecimalMax("100") val harvestMoisture: Double?,
        @field:DecimalMin("0") @field:DecimalMax("500") val expectedYieldTHa: Double?,
        @field:DecimalMin("0") @field:DecimalMax("1000000") val expectedPricePerT: Double?,
        @field:DecimalMin("0") @field:DecimalMax("1000000") val actualPricePerT: Double?,
        notes: String?,
    ) {
        @field:Size(max = 2000)
        val notes: String? = Text.trim(notes)
    }

    /** A partial update: absent fields stay as they are, null clears the nullable ones. */
    class SeasonUpdate(
        crop: JsonNullable<CropCode>?,
        year: JsonNullable<Int>?,
        sowingDate: JsonNullable<Instant>?,
        expectedHarvestDate: JsonNullable<Instant>?,
        harvestDate: JsonNullable<Instant>?,
        yieldT: JsonNullable<Double>?,
        harvestMoisture: JsonNullable<Double>?,
        expectedYieldTHa: JsonNullable<Double>?,
        expectedPricePerT: JsonNullable<Double>?,
        actualPricePerT: JsonNullable<Double>?,
        notes: JsonNullable<String>?,
    ) {
        val crop: JsonNullable<@NotNull CropCode> = crop ?: JsonNullable.undefined()
        val year: JsonNullable<@NotNull @Min(1990) @Max(2100) Int> = year ?: JsonNullable.undefined()
        val sowingDate: JsonNullable<Instant> = sowingDate ?: JsonNullable.undefined()
        val expectedHarvestDate: JsonNullable<Instant> = expectedHarvestDate ?: JsonNullable.undefined()
        val harvestDate: JsonNullable<Instant> = harvestDate ?: JsonNullable.undefined()
        val yieldT: JsonNullable<@DecimalMin("0") @DecimalMax("1000000") Double> = yieldT ?: JsonNullable.undefined()
        val harvestMoisture: JsonNullable<@DecimalMin("0") @DecimalMax("100") Double> = harvestMoisture ?: JsonNullable.undefined()
        val expectedYieldTHa: JsonNullable<@DecimalMin("0") @DecimalMax("500") Double> = expectedYieldTHa ?: JsonNullable.undefined()
        val expectedPricePerT: JsonNullable<@DecimalMin("0") @DecimalMax("1000000") Double> =
            expectedPricePerT ?: JsonNullable.undefined()
        val actualPricePerT: JsonNullable<@DecimalMin("0") @DecimalMax("1000000") Double> = actualPricePerT ?: JsonNullable.undefined()
        val notes: JsonNullable<@Size(max = 2000) String> =
            if (notes == null || !notes.isPresent) JsonNullable.undefined() else JsonNullable.of(Text.trim(notes.get()))
    }

    class EvaluateRequest(
        @field:NotNull val crop: CropCode?,
        @field:NotNull @field:Min(1990) @field:Max(2100) val year: Int?,
    )

    @JvmRecord
    data class RecommendationsResponse(
        val year: Int,
        val soil: String?,
        val climate: ClimateNormals?,
        val recommendations: List<CropRecommendation>,
    )

    @JvmRecord
    data class ClimateResponse(val source: String, val years: List<ClimateYear>)

    @JvmRecord
    data class DevelopmentResponse(val sowingDate: String, val dataUntil: String, @get:JsonUnwrapped val development: CropDevelopment)

    private fun parcelHistory(parcelId: UUID): List<CropHistoryEntry> =
        seasons.findByParcelIdOrderByYearDesc(parcelId).map { CropHistoryEntry(it.crop, it.year) }

    private fun requireSeason(farmId: UUID, id: UUID): CropSeason =
        seasons.findInFarm(id, farmId).orElseThrow { Errors.notFound("Season") }

    @GetMapping("/crops")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Catalog of predefined crops")
    fun catalog(): ResponseEntity<ApiResponse<List<CropProfile>>> = ApiResponse.ok(Crops.catalog())

    @GetMapping("/parcels/{id}/seasons")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Crop history for the last 10 years")
    fun history(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<List<CropSeasonView>>> {
        access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(
            seasons.findByParcelIdAndYearGreaterThanEqualOrderByYearDesc(id, Times.currentLocalYear() - 10).map { CropSeasonView.of(it) },
        )
    }

    @PostMapping("/parcels/{id}/seasons")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Add a crop to the parcel history")
    fun addSeason(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: SeasonCreate): ResponseEntity<ApiResponse<CropSeasonView>> {
        access.requireParcel(auth.farmId(), id)
        val season = CropSeason()
        season.parcelId = id
        season.crop = body.crop
        season.year = body.year!!
        season.sowingDate = body.sowingDate
        season.expectedHarvestDate = body.expectedHarvestDate
        season.harvestDate = body.harvestDate
        season.yieldT = body.yieldT
        season.harvestMoisture = body.harvestMoisture
        season.expectedYieldTHa = body.expectedYieldTHa
        season.expectedPricePerT = decimal(body.expectedPricePerT)
        season.actualPricePerT = decimal(body.actualPricePerT)
        season.notes = body.notes
        val saved = seasons.saveAndFlush(season)
        plans.regeneratePlanSafely(auth.farmId(), id, saved.year, false)
        return ApiResponse.created(CropSeasonView.of(seasons.findById(saved.id).orElseThrow { Errors.recordNotFound() }))
    }

    @PatchMapping("/seasons/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Update a crop record")
    fun updateSeason(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: SeasonUpdate): ResponseEntity<ApiResponse<CropSeasonView>> {
        val before = requireSeason(auth.farmId(), id)
        val beforeCrop = before.crop
        val beforeYear = before.year
        val beforeSowing = before.sowingDate

        val season = tx.execute {
            val current = seasons.findById(id).orElseThrow { Errors.recordNotFound() }
            body.crop.ifPresent { current.crop = it }
            body.year.ifPresent { current.year = it }
            body.sowingDate.ifPresent { current.sowingDate = it }
            body.expectedHarvestDate.ifPresent { current.expectedHarvestDate = it }
            body.harvestDate.ifPresent { current.harvestDate = it }
            body.yieldT.ifPresent { current.yieldT = it }
            body.harvestMoisture.ifPresent { current.harvestMoisture = it }
            body.expectedYieldTHa.ifPresent { current.expectedYieldTHa = it }
            body.expectedPricePerT.ifPresent { current.expectedPricePerT = decimal(it) }
            body.actualPricePerT.ifPresent { current.actualPricePerT = decimal(it) }
            body.notes.ifPresent { current.notes = it }
            seasons.saveAndFlush(current)
        }!!

        val replanned = season.crop != beforeCrop || season.year != beforeYear || season.sowingDate != beforeSowing
        if (!replanned) {
            return ApiResponse.ok(CropSeasonView.of(season))
        }
        if (season.year != beforeYear) {
            plans.removePlan(auth.farmId(), season.parcelId, beforeYear)
        }
        plans.regeneratePlanSafely(auth.farmId(), season.parcelId, season.year, season.crop != beforeCrop)
        return ApiResponse.ok(CropSeasonView.of(seasons.findById(id).orElseThrow { Errors.recordNotFound() }))
    }

    @DeleteMapping("/seasons/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Delete a crop record")
    fun deleteSeason(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<Void> {
        val season = requireSeason(auth.farmId(), id)
        seasons.deleteById(id)
        plans.removePlan(auth.farmId(), season.parcelId, season.year)
        return ApiResponse.noContent()
    }

    @PostMapping("/parcels/{id}/rotation/evaluate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Evaluate the proposed crop")
    fun evaluate(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: EvaluateRequest): ResponseEntity<ApiResponse<RotationResult>> {
        access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(Rotation.evaluateRotation(body.crop!!, body.year!!, parcelHistory(id)))
    }

    @GetMapping("/parcels/{id}/rotation/options")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Rank all crops by rotation score")
    fun options(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(required = false) @Min(1990) @Max(2100) year: Int?,
    ): ResponseEntity<ApiResponse<List<RotationResult>>> {
        access.requireParcel(auth.farmId(), id)
        val target = year ?: (Times.currentLocalYear() + 1)
        return ApiResponse.ok(Rotation.rankRotationOptions(target, parcelHistory(id), Crops.CROP_CODES))
    }

    @GetMapping("/parcels/{id}/recommendations")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Recommend crops by soil, climate and rotation")
    fun recommendations(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(required = false) @Min(1990) @Max(2100) year: Int?,
    ): ResponseEntity<ApiResponse<RecommendationsResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val data = Concurrency.both<SoilProfile?, ClimateNormals?>(
            { soilProfiles.findByParcelId(parcel.id).orElse(null) },
            {
                try {
                    nasa.getClimateNormals(parcel.centerLat, parcel.centerLng)
                } catch (error: RuntimeException) {
                    log.warn("Climate normals unavailable: {}", error.message)
                    null
                }
            },
        )
        val history = parcelHistory(parcel.id)
        val soil = data.first
        val climate = data.second
        val soilTexture = texture(soil?.texture)
        val soilInput = if (soil != null && soil.status == SoilStatus.READY && soil.ph != null && soilTexture != null) {
            SoilFit(soil.ph, soilTexture)
        } else {
            null
        }
        val targetYear = year ?: (Times.currentLocalYear() + 1)
        val result = CropRules.recommendCrops(
            targetYear, history, soilInput,
            climate?.let { ClimateFit(it.seasonTempC, it.annualPrecipitationMm, it.gddBase10) },
            null,
        )

        // The latest result is kept for history, without blocking the response on an error.
        try {
            tx.executeWithoutResult {
                recommendations.deleteForYear(parcel.id, targetYear)
                recommendations.saveAll(
                    result.map { item ->
                        CropRecommendationRecord().apply {
                            parcelId = parcel.id
                            this.year = targetYear
                            crop = item.crop
                            score = item.score
                            reasons = mapper.valueToTree(item.reasons)
                        }
                    },
                )
            }
        } catch (error: RuntimeException) {
            log.warn("Saving recommendations failed: {}", error.message)
        }
        return ApiResponse.ok(RecommendationsResponse(targetYear, soil?.label, climate, result))
    }

    @GetMapping("/parcels/{id}/climate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Compare agricultural years based on NASA POWER data")
    fun climate(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "5") @Min(1) @Max(MAX_CLIMATE_YEARS) years: Int,
    ): ResponseEntity<ApiResponse<ClimateResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val lastYear = Times.utcYear(Instant.now()) - 1
        val days = nasa.getPowerDaily(parcel.centerLat, parcel.centerLng, "${lastYear - years + 1}-01-01", "$lastYear-12-31")
        return ApiResponse.ok(ClimateResponse("NASA POWER", NasaPowerClient.summarizeClimateYears(days)))
    }

    @GetMapping("/parcels/{id}/development")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Estimate the development of the current crop using growing degree-days (GDD)")
    fun development(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(required = false) @Min(1990) @Max(2100) year: Int?,
    ): ResponseEntity<ApiResponse<DevelopmentResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val target = year ?: Times.currentLocalYear()
        val season = seasons.findByParcelIdAndYear(parcel.id, target).orElseThrow { Errors.notFound("The crop for $target") }
        val sowing = season.sowingDate ?: throw Errors.badRequest("Fill in the sowing date to estimate crop development.")
        val start = Times.isoDay(sowing)!!
        // NASA POWER data appears with a delay of a few days.
        val available = Instant.now().minusMillis(5 * Times.DAY_MS)
        val harvest = season.harvestDate
        val stop = if (harvest != null && harvest.isBefore(available)) harvest else available
        val end = Times.isoDay(stop)!!
        if (start > end) {
            throw Errors.badRequest("There is no weather data after the sowing date yet.")
        }
        val days = nasa.getPowerDaily(parcel.centerLat, parcel.centerLng, start, end)
        val development = CropRules.cropDevelopment(season.crop, days.map { DailyTemperature(it.date, it.tMaxC, it.tMinC) })
        return ApiResponse.ok(DevelopmentResponse(start, end, development))
    }

    private companion object {
        val log = LoggerFactory.getLogger(CropController::class.java)
        const val MAX_CLIMATE_YEARS = 10L

        fun decimal(value: Double?): BigDecimal? = value?.let { BigDecimal.valueOf(it) }

        fun texture(value: String?): SoilTexture? = SoilTexture.entries.firstOrNull { it.name == value }
    }
}

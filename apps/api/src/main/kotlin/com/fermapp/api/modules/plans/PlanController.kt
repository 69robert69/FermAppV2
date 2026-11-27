package com.fermapp.api.modules.plans

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.geo.Geo
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.Role
import com.fermapp.api.domain.plan.ParcelPlanner
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanData
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanInput
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.ParcelView
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDraft
import com.fermapp.api.modules.parcels.ParcelService
import com.fermapp.api.modules.parcels.PolygonInput
import com.fermapp.api.modules.soil.SoilService
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.ParcelPlanRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The parcel wizard and the technology plan (plan.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels")
@Tag(name = "Technology plan")
class PlanController(
    private val planService: PlanService,
    private val parcelService: ParcelService,
    private val soil: SoilService,
    private val access: AccessGuard,
    private val parcels: ParcelRepository,
    private val seasons: CropSeasonRepository,
    private val plans: ParcelPlanRepository,
    private val tx: TransactionTemplate,
) {

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("lastCrop", "lastCropName", "plannedCrop", "parcelId", "name", "geometry")
    class AnalyzeRequest(
        @field:NotNull @field:Pattern(regexp = LAST_CROP) val lastCrop: String?,
        lastCropName: String?,
        @field:NotNull val plannedCrop: CropCode?,
        val parcelId: UUID?,
        name: String?,
        @field:Valid val geometry: PolygonInput?,
    ) {
        @field:Size(max = 60)
        val lastCropName: String? = Text.trim(lastCropName)

        @field:Size(max = 120)
        val name: String? = Text.trim(name)

        @get:AssertTrue(message = "Send either the existing parcel or the outline of the new parcel.")
        val isExactlyOneTarget: Boolean
            get() = (parcelId != null) != (geometry != null)
    }

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("lastCrop", "lastCropName", "plannedCrop", "parcelId", "draft")
    class CompleteRequest(
        @field:NotNull @field:Pattern(regexp = LAST_CROP) val lastCrop: String?,
        lastCropName: String?,
        @field:NotNull val plannedCrop: CropCode?,
        val parcelId: UUID?,
        @field:Valid val draft: ParcelDraft?,
    ) {
        @field:Size(max = 60)
        val lastCropName: String? = Text.trim(lastCropName)

        @get:AssertTrue(message = "Send either the existing parcel or the data of the new parcel.")
        val isExactlyOneTarget: Boolean
            get() = (parcelId != null) != (draft != null)
    }

    @JvmRecord
    data class CompleteResponse(val parcel: ParcelView, val plan: JsonNode?)

    @JvmRecord
    data class PlanVersion(
        val id: UUID?,
        val year: Int,
        val crop: CropCode?,
        val active: Boolean,
        val trigger: String?,
        val createdAt: Instant?,
        val data: JsonNode?,
    )

    @JvmRecord
    data class PlanHistory(val active: PlanVersion?, val history: List<PlanVersion>)

    private fun buildPlanFor(
        auth: AuthContext,
        parcelId: UUID?,
        name: String?,
        geometry: PolygonInput?,
        lastCrop: String,
        lastCropName: String?,
        plannedCrop: CropCode,
    ): ParcelPlanData {
        val existing = parcelId?.let { access.requireActiveParcel(auth.farmId(), it) }
        val metrics = if (existing != null) null else Geo.computeGeoMetrics(geometry!!.toPolygon())
        val lat = existing?.centerLat ?: metrics!!.center.lat
        val lng = existing?.centerLng ?: metrics!!.center.lng
        val now = Times.now()

        val site = planService.loadSiteData(lat, lng, existing?.id)
        val history = if (existing != null) planService.parcelCropHistory(existing.id, ParcelPlanner.nextSeasonYear(now)) else listOf()
        return ParcelPlanner.buildParcelPlan(
            ParcelPlanInput(
                plannedCrop,
                existing?.name ?: name ?: "New parcel",
                existing?.areaHa ?: metrics!!.areaHa,
                PlanService.historyWithLastCrop(history, lastCrop, now),
                PlanService.lastCropLabel(lastCrop, lastCropName),
                site.soil, site.climate, now, null, null,
            ),
        )
    }

    @PostMapping("/wizard/analyze")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Analyse the parcel and generate the plan without saving anything")
    fun analyze(auth: AuthContext, @Valid @RequestBody body: AnalyzeRequest): ResponseEntity<ApiResponse<JsonNode?>> {
        val plan = buildPlanFor(auth, body.parcelId, body.name, body.geometry, body.lastCrop!!, body.lastCropName, body.plannedCrop!!)
        return ApiResponse.ok(PlanService.planForRole(planService.toJson(plan), auth.role()))
    }

    @PostMapping("/wizard/complete")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Save the parcel, crops, technology plan and tasks")
    fun complete(auth: AuthContext, @Valid @RequestBody body: CompleteRequest): ResponseEntity<ApiResponse<CompleteResponse>> {
        val draft = body.draft
        if (draft != null && auth.role() == Role.AGRONOMIST) {
            throw Errors.forbidden("Only managers can create new parcels.")
        }
        val lastCrop = body.lastCrop!!
        val plan = buildPlanFor(auth, body.parcelId, draft?.name, draft?.geometry, lastCrop, body.lastCropName, body.plannedCrop!!)
        val lastYear = ParcelPlanner.nextSeasonYear(Times.now()) - 1
        if (plan.year <= lastYear) {
            throw Errors.badRequest("The plan year must be after the last crop.")
        }

        val parcel = tx.execute {
            var target: Parcel = if (body.parcelId != null) {
                parcels.findById(body.parcelId).orElseThrow { Errors.recordNotFound() }
            } else {
                parcelService.newParcel(auth.farmId(), draft!!)
            }
            target.lastCrop = lastCrop
            target = parcels.saveAndFlush(target)

            if (lastCrop != ParcelPlanner.OTHER_CROP) {
                val previous = seasons.findByParcelIdAndYear(target.id, lastYear).orElseGet { CropSeason() }
                previous.parcelId = target.id
                previous.year = lastYear
                previous.crop = CropCode.valueOf(lastCrop)
                seasons.save(previous)
            }
            val planned = seasons.findByParcelIdAndYear(target.id, plan.year).orElseGet { CropSeason() }
            planned.parcelId = target.id
            planned.year = plan.year
            planned.crop = plan.crop
            planned.expectedHarvestDate = Instant.parse(plan.harvestDate)
            planned.expectedYieldTHa = plan.report.expectedYieldTHa
            seasons.saveAndFlush(planned)
            planService.savePlan(auth.farmId(), target.id, plan, "WIZARD")
            target
        }!!

        if (draft != null) {
            soil.loadInBackground(parcel)
        }
        planService.announcePlan(auth.farmId(), parcel.id)
        val fresh = parcels.findById(parcel.id).orElseThrow()
        return ApiResponse.created(CompleteResponse(ParcelView.of(fresh), PlanService.planForRole(planService.toJson(plan), auth.role())))
    }

    @GetMapping("/{id}/plan")
    @Access(AccessGroup.ALL)
    @Operation(summary = "The active plan and saved reports of the parcel")
    fun plan(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<PlanHistory>> {
        access.requireParcel(auth.farmId(), id)
        val history = plans.findByParcelIdOrderByCreatedAtDesc(id, PageRequest.of(0, 20)).map { item ->
            PlanVersion(item.id, item.year, item.crop, item.isActive, item.trigger, item.createdAt, PlanService.planForRole(item.data, auth.role()))
        }
        return ApiResponse.ok(PlanHistory(history.firstOrNull { it.active }, history))
    }

    private companion object {
        const val LAST_CROP = "WHEAT|BARLEY|CORN|SUNFLOWER|RAPESEED|SOY|ALFALFA|OAT|POTATO|BEET|VEGETABLES|OTHER"
    }
}

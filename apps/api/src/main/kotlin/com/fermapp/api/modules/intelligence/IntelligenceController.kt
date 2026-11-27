package com.fermapp.api.modules.intelligence

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.storage.FileStorage
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.model.Role
import com.fermapp.api.domain.report.ReportTable
import com.fermapp.api.domain.report.ReportTable.Column
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.IncomeView
import com.fermapp.api.modules.common.Views.ObservationView
import com.fermapp.api.modules.common.Views.PhotoView
import com.fermapp.api.modules.common.Views.TaskWithAssignee
import com.fermapp.api.modules.intelligence.IntelligenceService.EconomicsView
import com.fermapp.api.modules.intelligence.IntelligenceService.IntelligenceData
import com.fermapp.api.modules.intelligence.IntelligenceService.NdviComparison
import com.fermapp.api.modules.intelligence.IntelligenceService.ParcelWeather
import com.fermapp.api.modules.intelligence.IntelligenceService.RotationScore
import com.fermapp.api.modules.intelligence.IntelligenceService.TaskPanel
import com.fermapp.api.modules.intelligence.IntelligenceService.TaskSummary
import com.fermapp.api.modules.reports.ReportRenderer
import com.fermapp.api.persistence.entity.Income
import com.fermapp.api.persistence.entity.Observation
import com.fermapp.api.persistence.entity.Photo
import com.fermapp.api.persistence.repository.FarmRepository
import com.fermapp.api.persistence.repository.IncomeRepository
import com.fermapp.api.persistence.repository.ObservationRepository
import com.fermapp.api.persistence.repository.PhotoRepository
import com.fermapp.api.persistence.repository.SatelliteObservationRepository
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
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
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** The Parcel Intelligence panel and its parts (intelligence.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels/{id}")
@Tag(name = "Parcel Intelligence")
class IntelligenceController(
    private val intelligence: IntelligenceService,
    private val access: AccessGuard,
    private val incomes: IncomeRepository,
    private val satellite: SatelliteObservationRepository,
    private val tasks: TaskRepository,
    private val observations: ObservationRepository,
    private val photos: PhotoRepository,
    private val farms: FarmRepository,
    private val storage: FileStorage,
    private val realtime: RealtimeHub,
    private val renderer: ReportRenderer,
    private val tx: TransactionTemplate,
) {

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("year", "amount", "source", "date")
    class IncomeRequest(
        @field:NotNull @field:Min(2000) @field:Max(2100) val year: Int?,
        @field:NotNull @field:Positive @field:DecimalMax("100000000") val amount: Double?,
        source: String?,
        val date: Instant?,
    ) {
        @field:Size(max = 120)
        val source: String? = Text.trim(source)
    }

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("text", "lat", "lng")
    class ObservationRequest(
        text: String?,
        @field:DecimalMin("-90") @field:DecimalMax("90") val lat: Double?,
        @field:DecimalMin("-180") @field:DecimalMax("180") val lng: Double?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 2000)
        val text: String? = Text.trim(text)
    }

    private fun parcelUpdated(farmId: UUID, parcelId: UUID) {
        realtime.emitToFarm(farmId, RealtimeEvent.PARCEL_UPDATED, mapOf("parcelId" to parcelId))
    }

    @GetMapping("/intelligence")
    @Access(AccessGroup.ALL)
    @Operation(summary = "All data for the Parcel Intelligence panel")
    fun panel(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<IntelligenceData>> =
        ApiResponse.ok(intelligence.buildIntelligence(auth, id))

    @GetMapping("/weather")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Today’s weather, 7- and 14-day forecast and spraying alerts")
    fun weather(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<ParcelWeather>> =
        ApiResponse.ok(intelligence.parcelWeather(access.requireParcel(auth.farmId(), id)))

    @GetMapping("/rotation-score")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Rotation score and crop history")
    fun rotationScore(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<RotationScore>> {
        access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(intelligence.rotationScore(id, Times.now()))
    }

    @GetMapping("/economics")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Costs, estimated revenue, profit, margin and ROI")
    fun economics(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(required = false) @Min(2000) @Max(2100) year: Int?,
    ): ResponseEntity<ApiResponse<EconomicsView>> =
        ApiResponse.ok(intelligence.economicsFor(access.requireParcel(auth.farmId(), id), year))

    @PostMapping("/incomes")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Record realized revenue")
    fun addIncome(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: IncomeRequest): ResponseEntity<ApiResponse<IncomeView>> {
        access.requireParcel(auth.farmId(), id)
        val income = Income()
        income.parcelId = id
        income.year = body.year!!
        income.amount = BigDecimal.valueOf(body.amount!!)
        income.source = body.source
        income.date = body.date
        val saved = incomes.saveAndFlush(income)
        parcelUpdated(auth.farmId(), id)
        return ApiResponse.created(IncomeView.of(incomes.findById(saved.id).orElse(saved)))
    }

    @GetMapping("/ndvi")
    @Access(AccessGroup.ALL)
    @Operation(summary = "NDVI, NDRE and vegetation status: now, 7 and 30 days ago")
    fun ndvi(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<NdviComparison>> {
        access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(
            IntelligenceService.ndviComparison(satellite.findByParcelIdOrderByDateDesc(id, PageRequest.of(0, 40)), Times.now()),
        )
    }

    @GetMapping("/tasks")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Parcel tasks and status summary")
    fun tasks(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<TaskPanel>> {
        access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(
            tx.execute {
                val items = tasks.findForParcel(id, PageRequest.of(0, Int.MAX_VALUE))
                TaskPanel(
                    TaskSummary(
                        items.count { it.status.name == "TODO" }.toLong(),
                        items.count { it.status.name == "IN_PROGRESS" }.toLong(),
                        items.count { it.status.name == "DONE" }.toLong(),
                    ),
                    items.map { TaskWithAssignee.of(it) },
                )
            }!!,
        )
    }

    @GetMapping("/observations")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Field observations")
    fun listObservations(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<List<ObservationView>>> {
        access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(observations.findLatest(id, PageRequest.of(0, 100)).map { ObservationView.of(it) })
    }

    @PostMapping("/observations")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Add a field observation")
    fun addObservation(
        auth: AuthContext,
        @PathVariable id: UUID,
        @Valid @RequestBody body: ObservationRequest,
    ): ResponseEntity<ApiResponse<ObservationView>> {
        access.requireParcel(auth.farmId(), id)
        val observation = Observation()
        observation.parcelId = id
        observation.authorId = auth.userId()
        observation.text = body.text
        observation.lat = body.lat
        observation.lng = body.lng
        val saved = observations.saveAndFlush(observation)
        val view = tx.execute { ObservationView.of(observations.findById(saved.id).orElseThrow()) }!!
        parcelUpdated(auth.farmId(), id)
        return ApiResponse.created(view)
    }

    @GetMapping("/photos")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Parcel photo gallery")
    fun listPhotos(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<List<PhotoView>>> {
        access.requireParcel(auth.farmId(), id)
        return ApiResponse.ok(photos.findByParcelIdOrderByCreatedAtDesc(id, PageRequest.of(0, 200)).map { PhotoView.of(it) })
    }

    @PostMapping(value = ["/photos"], consumes = ["multipart/form-data"])
    @Access(AccessGroup.ALL)
    @Operation(summary = "Upload a photo with notes")
    @Throws(IOException::class)
    fun uploadPhoto(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestPart(name = "file", required = false) file: MultipartFile?,
        @RequestParam(name = "notes", required = false) @Size(max = 1000) notes: String?,
    ): ResponseEntity<ApiResponse<PhotoView>> {
        access.requireParcel(auth.farmId(), id)
        if (file == null || file.isEmpty) {
            throw Errors.badRequest("Attach the photo in the \"file\" field.")
        }
        if (file.size > MAX_PHOTO_BYTES) {
            throw Errors.badRequest("The photo exceeds the 10 MB limit.")
        }
        val content = file.bytes
        val type = FileStorage.detectImageType(content) ?: throw Errors.badRequest("The file must be a JPEG, PNG, WEBP or HEIC image.")
        val storageKey = storage.save("parcels/$id", type.extension, content)
        val photo = Photo()
        photo.parcelId = id
        photo.authorId = auth.userId()
        photo.storageKey = storageKey
        photo.contentType = type.contentType
        photo.sizeBytes = file.size.toInt()
        val trimmed = Text.trim(notes)
        photo.notes = if (trimmed.isNullOrEmpty()) null else trimmed
        val saved = photos.saveAndFlush(photo)
        parcelUpdated(auth.farmId(), id)
        return ApiResponse.created(PhotoView.of(saved))
    }

    private fun requirePhoto(farmId: UUID, parcelId: UUID, photoId: UUID): Photo =
        photos.findInFarm(farmId, parcelId, photoId).orElseThrow { Errors.notFound("Photo") }

    @GetMapping("/photos/{photoId}/file")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Photo content")
    fun photoFile(auth: AuthContext, @PathVariable id: UUID, @PathVariable photoId: UUID): ResponseEntity<ByteArray> {
        val photo = requirePhoto(auth.farmId(), id, photoId)
        val content = try {
            storage.load(photo.storageKey)
        } catch (error: IOException) {
            throw Errors.notFound("Photo file")
        } catch (error: IllegalArgumentException) {
            throw Errors.notFound("Photo file")
        }
        return ApiResponse.file(content, photo.contentType, "photo-" + photo.id, true)
    }

    @DeleteMapping("/photos/{photoId}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Delete a photo")
    fun deletePhoto(auth: AuthContext, @PathVariable id: UUID, @PathVariable photoId: UUID): ResponseEntity<Void> {
        val photo = requirePhoto(auth.farmId(), id, photoId)
        val mayDelete = auth.role() == Role.ADMIN || auth.role() == Role.FARM_MANAGER || photo.authorId == auth.userId()
        if (!mayDelete) {
            throw Errors.forbidden("You can only delete your own photos.")
        }
        photos.delete(photo)
        storage.remove(photo.storageKey)
        parcelUpdated(auth.farmId(), id)
        return ApiResponse.noContent()
    }

    @GetMapping("/report")
    @Access(AccessGroup.ALL)
    @Operation(summary = "PDF report for the parcel")
    fun report(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val data = intelligence.buildIntelligence(auth, id)
        val farmName = farms.findById(auth.farmId()).orElseThrow { Errors.recordNotFound() }.name
        val rows = mutableListOf<Map<String, Any?>>()
        rows += row("Parcel", "Code", data.header.code)
        rows += row("Parcel", "Area (ha)", data.header.areaHa)
        val locality = listOf(data.header.locality, data.header.county).filter { !it.isNullOrEmpty() }.joinToString(", ")
        rows += row("Parcel", "Locality", locality)
        rows += row("Crop", "Current crop", data.crop?.name ?: "Not recorded")
        rows += row("Crop", "Progress (%)", data.crop?.progress?.percent)
        rows += row("Rotation", "Score", data.rotation.result.score)
        rows += row("Rotation", "Verdict", data.rotation.result.label.replaceFirst(Regex("^\\S+\\s"), ""))
        rows += row("Rotation", "Explanation", data.rotation.result.explanation)
        for (item in data.rotation.history) {
            rows += row("History", item.year.toString(), Crops.name(item.crop!!))
        }
        rows += row("Recommendation", "Next action", data.nextAction.action)
        rows += row("Recommendation", "Reason", data.nextAction.reason)
        val label = data.soil.get("label")
        rows += row("Soil", "Classification", if (label == null || label.isNull) null else label.asText())
        rows += row("Tasks", "Open", data.tasks.summary.todo + data.tasks.summary.inProgress)
        data.economics?.let {
            rows += row("Economics", "Total cost (EUR)", it.economics.totalCost)
            rows += row("Economics", "Cost/ha (EUR)", it.economics.costPerHa)
            rows += row("Economics", "Estimated profit (EUR)", it.economics.expectedProfit)
        }
        val report = ReportTable(
            "Parcel " + data.header.name, "Generated on " + data.generatedAt.substring(0, 10),
            listOf(Column.text("section", "Section", 1.0), Column.text("indicator", "Indicator", 1.4), Column.text("value", "Value", 3.6)),
            rows, null, null, null, null,
        )
        return ApiResponse.file(renderer.toPdf(report, farmName, null), "application/pdf", "parcel-" + data.header.code + ".pdf", false)
    }

    private companion object {
        const val MAX_PHOTO_BYTES = 10L * 1024 * 1024

        fun row(section: String, indicator: String, value: Any?): Map<String, Any?> = linkedMapOf(
            "section" to section,
            "indicator" to indicator,
            "value" to (value ?: "Data unavailable"),
        )
    }
}

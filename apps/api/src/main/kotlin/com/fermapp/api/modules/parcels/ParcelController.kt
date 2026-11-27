package com.fermapp.api.modules.parcels

import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.modules.common.Views.ParcelView
import com.fermapp.api.modules.parcels.ParcelDtos.ImportResponse
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDetail
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDraft
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelListItem
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelUpdate
import com.fermapp.api.modules.parcels.ParcelDtos.TillageRequest
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.io.IOException
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Parcel endpoints (parcels.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels")
@Tag(name = "Parcels")
class ParcelController(private val parcels: ParcelService) {

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List the farm’s parcels")
    fun list(
        auth: AuthContext,
        @RequestParam(defaultValue = "false") @Pattern(regexp = "true|false") archived: String,
        @RequestParam(required = false) @Size(max = 120) search: String?,
    ): ResponseEntity<ApiResponse<List<ParcelListItem>>> {
        // String.trim() semantics: control characters and spaces only.
        val trimmed = search?.trim { it <= ' ' }
        return ApiResponse.ok(parcels.list(auth.farmId(), archived == "true", trimmed))
    }

    @PostMapping
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Create a parcel and start the soil analysis")
    fun create(auth: AuthContext, @Valid @RequestBody body: ParcelDraft): ResponseEntity<ApiResponse<ParcelView>> =
        ApiResponse.created(parcels.create(auth.farmId(), body))

    @PostMapping(value = ["/import"], consumes = ["multipart/form-data"])
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Import parcels from GeoJSON, KML, KMZ, SHP or APIA LPIS")
    @Throws(IOException::class)
    fun importParcels(
        auth: AuthContext,
        @RequestParam @Pattern(regexp = FORMATS) format: String,
        @RequestParam(defaultValue = "false") @Pattern(regexp = "true|false") dryRun: String,
        @RequestPart(name = "file", required = false) file: MultipartFile?,
    ): ResponseEntity<ApiResponse<ImportResponse>> {
        if (file == null || file.isEmpty) {
            throw Errors.badRequest("Attach the file in the \"file\" field.")
        }
        return ApiResponse.created(parcels.importFile(auth.farmId(), format, dryRun == "true", file.bytes))
    }

    @GetMapping("/export")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Export active parcels")
    fun export(auth: AuthContext, @RequestParam @Pattern(regexp = FORMATS) format: String): ResponseEntity<ByteArray> {
        val file = parcels.export(auth.farmId(), format)
        return ApiResponse.file(file.content, file.contentType, "parcels." + file.extension, false)
    }

    @GetMapping("/{id}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Parcel details")
    fun detail(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<ParcelDetail>> =
        ApiResponse.ok(parcels.detail(auth.farmId(), id))

    @PatchMapping("/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Update the parcel data or outline")
    fun update(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: ParcelUpdate): ResponseEntity<ApiResponse<ParcelView>> =
        ApiResponse.ok(parcels.update(auth.farmId(), id, body))

    @PostMapping("/{id}/tillage")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Set the tillage technology of a year; the current one follows the history")
    fun tillage(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: TillageRequest): ResponseEntity<ApiResponse<ParcelView>> =
        ApiResponse.ok(parcels.setTillage(auth.farmId(), id, body.system!!, body.year))

    @PostMapping("/{id}/archive")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Archive the parcel")
    fun archive(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<ParcelView>> =
        ApiResponse.ok(parcels.setArchived(auth.farmId(), id, true))

    @PostMapping("/{id}/restore")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Restore the parcel from the archive")
    fun restore(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<ParcelView>> =
        ApiResponse.ok(parcels.setArchived(auth.farmId(), id, false))

    private companion object {
        const val FORMATS = "geojson|kml|kmz|shp|lpis"
    }
}

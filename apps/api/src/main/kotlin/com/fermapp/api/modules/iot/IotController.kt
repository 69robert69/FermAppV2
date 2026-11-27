package com.fermapp.api.modules.iot

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.common.web.RateLimitPolicy
import com.fermapp.api.common.web.RateLimited
import com.fermapp.api.domain.model.IotDeviceKind
import com.fermapp.api.domain.model.IotProtocol
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.IotDeviceView
import com.fermapp.api.modules.common.Views.IotReadingView
import com.fermapp.api.modules.iot.IotService.Telemetry
import com.fermapp.api.persistence.entity.IotDevice
import com.fermapp.api.persistence.repository.IotDeviceRepository
import com.fermapp.api.persistence.repository.IotReadingRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** IoT devices, their readings and REST ingestion (iot.routes.ts). */
@RestController
@RequestMapping("/api/v1/iot")
@Tag(name = "IoT")
class IotController(
    private val devices: IotDeviceRepository,
    private val readings: IotReadingRepository,
    private val iot: IotService,
    private val access: AccessGuard,
) {

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("name", "kind", "protocol", "vendor", "parcelId")
    class DeviceCreate(
        name: String?,
        @field:NotNull val kind: IotDeviceKind?,
        @field:NotNull val protocol: IotProtocol?,
        vendor: String?,
        val parcelId: UUID?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 120)
        val name: String? = Text.trim(name)

        @field:Size(max = 60)
        val vendor: String? = Text.trim(vendor)
    }

    /** The new device with its key, which is shown only once. */
    @JvmRecord
    data class CreatedDevice(@get:JsonUnwrapped val device: IotDeviceView, val deviceKey: String)

    @JvmRecord
    data class IngestResponse(val accepted: Int)

    @GetMapping("/devices")
    @Access(AccessGroup.ALL)
    @Operation(summary = "List IoT devices")
    fun list(auth: AuthContext): ResponseEntity<ApiResponse<List<IotDeviceView>>> =
        ApiResponse.ok(devices.findByFarmIdOrderByNameAsc(auth.farmId()).map { IotDeviceView.of(it) })

    @PostMapping("/devices")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Register a device. The key is shown only once.")
    fun create(auth: AuthContext, @Valid @RequestBody body: DeviceCreate): ResponseEntity<ApiResponse<CreatedDevice>> {
        if (body.parcelId != null) {
            access.requireParcel(auth.farmId(), body.parcelId)
        }
        val key = IotService.generateDeviceKey()
        val device = IotDevice()
        device.farmId = auth.farmId()
        device.name = body.name!!
        device.kind = body.kind!!
        device.protocol = body.protocol!!
        device.vendor = body.vendor
        device.parcelId = body.parcelId
        device.keyHash = key.keyHash
        return ApiResponse.created(CreatedDevice(IotDeviceView.of(devices.saveAndFlush(device)), key.key))
    }

    @DeleteMapping("/devices/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Delete a device and its readings")
    fun delete(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<Void> {
        if (devices.deleteInFarm(id, auth.farmId()) == 0) {
            throw Errors.notFound("Device")
        }
        return ApiResponse.noContent()
    }

    @GetMapping("/devices/{id}/readings")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Latest readings of a device")
    fun readings(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(required = false) @Size(max = 60) metric: String?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(1000) limit: Int,
    ): ResponseEntity<ApiResponse<List<IotReadingView>>> {
        val device = devices.findByIdAndFarmId(id, auth.farmId()).orElseThrow { Errors.notFound("Device") }
        // String.trim() semantics: control characters and spaces only.
        val filter = metric?.trim { it <= ' ' }
        val page = PageRequest.of(0, limit)
        val found = if (filter == null) {
            readings.findByDeviceIdOrderByRecordedAtDesc(device.id, page)
        } else {
            readings.findByDeviceIdAndMetricOrderByRecordedAtDesc(device.id, filter, page)
        }
        return ApiResponse.ok(found.map { IotReadingView.of(it) })
    }

    @PostMapping("/ingest")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.INGEST)
    @Operation(summary = "Receive sensor readings, with the X-Device-Key header")
    fun ingest(
        @RequestHeader(name = "x-device-key", required = false) deviceKey: String?,
        @Valid @RequestBody body: Telemetry,
    ): ResponseEntity<ApiResponse<IngestResponse>> {
        val device = iot.authenticate(deviceKey ?: "") ?: throw Errors.unauthorized("Invalid device key.")
        return ApiResponse.accepted(IngestResponse(iot.ingest(device, body)))
    }
}

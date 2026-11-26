package com.fermapp.api.modules.iot;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.common.web.RateLimitPolicy;
import com.fermapp.api.common.web.RateLimited;
import com.fermapp.api.domain.model.IotDeviceKind;
import com.fermapp.api.domain.model.IotProtocol;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.IotDeviceView;
import com.fermapp.api.modules.common.Views.IotReadingView;
import com.fermapp.api.modules.iot.IotService.GeneratedKey;
import com.fermapp.api.modules.iot.IotService.Telemetry;
import com.fermapp.api.persistence.entity.IotDevice;
import com.fermapp.api.persistence.repository.IotDeviceRepository;
import com.fermapp.api.persistence.repository.IotReadingRepository;
import com.fermapp.api.realtime.TelemetryIngestor.DeviceIdentity;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IoT devices, their readings and REST ingestion (iot.routes.ts). */
@RestController
@RequestMapping("/api/v1/iot")
@Tag(name = "IoT")
public class IotController {

    public record DeviceCreate(@NotNull @Size(min = 1, max = 120) String name, @NotNull IotDeviceKind kind,
                               @NotNull IotProtocol protocol, @Size(max = 60) String vendor, UUID parcelId) {
        public DeviceCreate {
            name = Text.trim(name);
            vendor = Text.trim(vendor);
        }
    }

    /** The new device with its key, which is shown only once. */
    public record CreatedDevice(@JsonUnwrapped IotDeviceView device, String deviceKey) {
    }

    public record IngestResponse(int accepted) {
    }

    private final IotDeviceRepository devices;
    private final IotReadingRepository readings;
    private final IotService iot;
    private final AccessGuard access;

    public IotController(IotDeviceRepository devices, IotReadingRepository readings, IotService iot, AccessGuard access) {
        this.devices = devices;
        this.readings = readings;
        this.iot = iot;
        this.access = access;
    }

    @GetMapping("/devices")
    @Access(AccessGroup.ALL)
    @Operation(summary = "List IoT devices")
    public ResponseEntity<ApiResponse<List<IotDeviceView>>> list(AuthContext auth) {
        return ApiResponse.ok(devices.findByFarmIdOrderByNameAsc(auth.farmId()).stream().map(IotDeviceView::of).toList());
    }

    @PostMapping("/devices")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Register a device. The key is shown only once.")
    public ResponseEntity<ApiResponse<CreatedDevice>> create(AuthContext auth, @Valid @RequestBody DeviceCreate body) {
        if (body.parcelId() != null) {
            access.requireParcel(auth.farmId(), body.parcelId());
        }
        GeneratedKey key = IotService.generateDeviceKey();
        IotDevice device = new IotDevice();
        device.setFarmId(auth.farmId());
        device.setName(body.name());
        device.setKind(body.kind());
        device.setProtocol(body.protocol());
        device.setVendor(body.vendor());
        device.setParcelId(body.parcelId());
        device.setKeyHash(key.keyHash());
        return ApiResponse.created(new CreatedDevice(IotDeviceView.of(devices.saveAndFlush(device)), key.key()));
    }

    @DeleteMapping("/devices/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Delete a device and its readings")
    public ResponseEntity<Void> delete(AuthContext auth, @PathVariable UUID id) {
        if (devices.deleteInFarm(id, auth.farmId()) == 0) {
            throw Errors.notFound("Device");
        }
        return ApiResponse.noContent();
    }

    @GetMapping("/devices/{id}/readings")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Latest readings of a device")
    public ResponseEntity<ApiResponse<List<IotReadingView>>> readings(AuthContext auth, @PathVariable UUID id,
                                                                     @RequestParam(required = false) @Size(max = 60) String metric,
                                                                     @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        IotDevice device = devices.findByIdAndFarmId(id, auth.farmId()).orElseThrow(() -> Errors.notFound("Device"));
        String filter = metric == null ? null : metric.trim();
        PageRequest page = PageRequest.of(0, limit);
        return ApiResponse.ok((filter == null ? readings.findByDeviceIdOrderByRecordedAtDesc(device.getId(), page)
                : readings.findByDeviceIdAndMetricOrderByRecordedAtDesc(device.getId(), filter, page))
                .stream().map(IotReadingView::of).toList());
    }

    @PostMapping("/ingest")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.INGEST)
    @Operation(summary = "Receive sensor readings, with the X-Device-Key header")
    public ResponseEntity<ApiResponse<IngestResponse>> ingest(@RequestHeader(name = "x-device-key", required = false) String deviceKey,
                                                             @Valid @RequestBody Telemetry body) {
        DeviceIdentity device = iot.authenticate(deviceKey == null ? "" : deviceKey);
        if (device == null) {
            throw Errors.unauthorized("Invalid device key.");
        }
        return ApiResponse.accepted(new IngestResponse(iot.ingest(device, body)));
    }
}

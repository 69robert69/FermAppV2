package com.fermapp.api.modules.work;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.model.WorkType;
import com.fermapp.api.domain.work.WorkMath;
import com.fermapp.api.domain.work.WorkMath.WorkEstimate;
import com.fermapp.api.domain.work.WorkMath.WorkParams;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.MachineLocationView;
import com.fermapp.api.modules.work.WorkViews.WorkView;
import com.fermapp.api.persistence.entity.MachineLocation;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.WorkSession;
import com.fermapp.api.persistence.repository.MachineLocationRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Field operations with live progress (work.routes.ts). */
@RestController
@RequestMapping("/api/v1/work")
@Tag(name = "Work")
public class WorkController {

    public record EstimateRequest(@NotNull UUID parcelId,
                                  @NotNull @Positive @DecimalMax("100") Double workingWidthM,
                                  @NotNull @Positive @DecimalMax("60") Double speedKmh,
                                  @NotNull @Positive @DecimalMax("100") Double efficiencyPct) {
    }

    public record EstimateResponse(double areaHa, double capacityHaH, double durationHours, Instant eta) {
    }

    public record StartRequest(@NotNull UUID parcelId, UUID machineId, UUID operatorId, @NotNull WorkType type,
                               @Size(max = 1000) String notes,
                               @NotNull @Positive @DecimalMax("100") Double workingWidthM,
                               @NotNull @Positive @DecimalMax("60") Double speedKmh,
                               @NotNull @Positive @DecimalMax("100") Double efficiencyPct) {
        public StartRequest {
            notes = Text.trim(notes);
        }
    }

    public record LocationRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                                  @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
                                  @DecimalMin("0") @DecimalMax("360") Double headingDeg,
                                  @DecimalMin("0") @DecimalMax("200") Double speedKmh,
                                  @DecimalMin("0") @DecimalMax("100000") Double accuracyM) {
    }

    /** The latest position, with {@code workId}. */
    public record LocationResponse(String id, UUID workSessionId, UUID machineId, double lat, double lng, Double headingDeg,
                                   Double speedKmh, Double accuracyM, Instant recordedAt, UUID workId) {
        static LocationResponse of(MachineLocationView view, UUID workId) {
            return new LocationResponse(view.id(), view.workSessionId(), view.machineId(), view.lat(), view.lng(),
                    view.headingDeg(), view.speedKmh(), view.accuracyM(), view.recordedAt(), workId);
        }
    }

    private final WorkSessionRepository works;
    private final MachineLocationRepository locations;
    private final WorkService service;
    private final AccessGuard access;
    private final RealtimeHub realtime;

    public WorkController(WorkSessionRepository works, MachineLocationRepository locations, WorkService service,
                          AccessGuard access, RealtimeHub realtime) {
        this.works = works;
        this.locations = locations;
        this.service = service;
        this.access = access;
        this.realtime = realtime;
    }

    private WorkSession requireControllableWork(AuthContext auth, UUID id) {
        WorkSession work = works.findByIdAndFarmId(id, auth.farmId()).orElseThrow(() -> Errors.notFound("Operation"));
        if (auth.role() == Role.OPERATOR && !work.getOperatorId().equals(auth.userId())) {
            throw Errors.forbidden("You can only control your own operations.");
        }
        return work;
    }

    private static WorkEstimate estimate(double areaHa, double width, double speed, double efficiency) {
        try {
            return WorkMath.estimateWork(areaHa, new WorkParams(width, speed, efficiency), Times.now());
        } catch (IllegalArgumentException error) {
            throw Errors.badRequest(error.getMessage());
        }
    }

    @PostMapping("/estimate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Calculate capacity, duration and ETA")
    public ResponseEntity<ApiResponse<EstimateResponse>> estimate(AuthContext auth, @Valid @RequestBody EstimateRequest body) {
        Parcel parcel = access.requireActiveParcel(auth.farmId(), body.parcelId());
        WorkEstimate result = estimate(parcel.getAreaHa(), body.workingWidthM(), body.speedKmh(), body.efficiencyPct());
        return ApiResponse.ok(new EstimateResponse(parcel.getAreaHa(), result.capacityHaH(), result.durationHours(), result.eta()));
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List operations")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<WorkView>>> list(AuthContext auth,
                                                           @RequestParam(required = false) RunStatus status,
                                                           @RequestParam(required = false) UUID parcelId,
                                                           @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        Specification<WorkSession> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("farmId"), auth.farmId()));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (parcelId != null) {
                predicates.add(cb.equal(root.get("parcelId"), parcelId));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Instant now = Times.now();
        return ApiResponse.ok(works.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "startedAt")))
                .stream().map(work -> WorkView.of(work, now)).toList());
    }

    @PostMapping
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Start an operation")
    public ResponseEntity<ApiResponse<WorkView>> start(AuthContext auth, @Valid @RequestBody StartRequest body) {
        Parcel parcel = access.requireActiveParcel(auth.farmId(), body.parcelId());
        if (body.machineId() != null) {
            access.requireMachine(auth.farmId(), body.machineId());
        }
        // Operators can only start operations in their own name.
        UUID operatorId = auth.role() == Role.OPERATOR ? auth.userId() : (body.operatorId() != null ? body.operatorId() : auth.userId());
        access.requireMember(auth.farmId(), operatorId);
        if (body.machineId() != null && works.existsByMachineIdAndStatusIn(body.machineId(), WorkService.ACTIVE)) {
            throw Errors.conflict("The machine is already used in an active operation.");
        }
        WorkEstimate estimate = estimate(parcel.getAreaHa(), body.workingWidthM(), body.speedKmh(), body.efficiencyPct());
        WorkSession work = new WorkSession();
        work.setFarmId(auth.farmId());
        work.setParcelId(parcel.getId());
        work.setMachineId(body.machineId());
        work.setOperatorId(operatorId);
        work.setType(body.type());
        work.setNotes(body.notes());
        work.setAreaHa(parcel.getAreaHa());
        work.setWorkingWidthM(body.workingWidthM());
        work.setSpeedKmh(body.speedKmh());
        work.setEfficiencyPct(body.efficiencyPct());
        work.setCapacityHaH(estimate.capacityHaH());
        work.setDurationHours(parcel.getAreaHa() / (body.workingWidthM() * body.speedKmh() * (body.efficiencyPct() / 100) / 10));
        works.saveAndFlush(work);
        return ApiResponse.created(service.publish(work.getId()));
    }

    @GetMapping("/{id}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Details and progress of an operation")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<WorkView>> get(AuthContext auth, @PathVariable UUID id) {
        WorkSession work = works.findByIdAndFarmId(id, auth.farmId()).orElseThrow(() -> Errors.notFound("Operation"));
        return ApiResponse.ok(WorkView.of(work, Times.now()));
    }

    @PostMapping("/{id}/location")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Send the machine’s GPS position")
    public ResponseEntity<Void> sendLocation(AuthContext auth, @PathVariable UUID id, @Valid @RequestBody LocationRequest body) {
        WorkSession work = requireControllableWork(auth, id);
        if (!WorkService.ACTIVE.contains(work.getStatus())) {
            throw Errors.conflict("The operation has ended.");
        }
        MachineLocation location = new MachineLocation();
        location.setWorkSessionId(work.getId());
        location.setMachineId(work.getMachineId());
        location.setLat(body.lat());
        location.setLng(body.lng());
        location.setHeadingDeg(body.headingDeg());
        location.setSpeedKmh(body.speedKmh());
        location.setAccuracyM(body.accuracyM());
        locations.save(location);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workId", work.getId());
        payload.put("parcelId", work.getParcelId());
        payload.put("machineId", work.getMachineId());
        payload.put("lat", location.getLat());
        payload.put("lng", location.getLng());
        payload.put("headingDeg", location.getHeadingDeg());
        payload.put("speedKmh", location.getSpeedKmh());
        payload.put("recordedAt", location.getRecordedAt());
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.MACHINE_LOCATION, payload);
        return ApiResponse.noContent();
    }

    @GetMapping("/{id}/location")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Latest GPS position of the machine")
    public ResponseEntity<ApiResponse<LocationResponse>> lastLocation(AuthContext auth, @PathVariable UUID id) {
        works.findByIdAndFarmId(id, auth.farmId()).orElseThrow(() -> Errors.notFound("Operation"));
        return ApiResponse.ok(locations.findFirstByWorkSessionIdOrderByRecordedAtDesc(id)
                .map(location -> LocationResponse.of(MachineLocationView.of(location), id))
                .orElse(null));
    }

    @PostMapping("/{id}/pause")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Pause the operation")
    public ResponseEntity<ApiResponse<WorkView>> pause(AuthContext auth, @PathVariable UUID id) {
        requireControllableWork(auth, id);
        if (works.pause(id, Times.now()) == 0) {
            throw Errors.conflict("Only a running operation can be paused.");
        }
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/{id}/resume")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Resume the operation")
    public ResponseEntity<ApiResponse<WorkView>> resume(AuthContext auth, @PathVariable UUID id) {
        WorkSession work = requireControllableWork(auth, id);
        if (work.getStatus() != RunStatus.PAUSED || work.getPausedAt() == null) {
            throw Errors.conflict("The operation is not paused.");
        }
        Instant now = Times.now();
        works.resume(id, now.toEpochMilli() - work.getPausedAt().toEpochMilli(), now);
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/{id}/complete")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Complete the operation")
    public ResponseEntity<ApiResponse<WorkView>> complete(AuthContext auth, @PathVariable UUID id) {
        requireControllableWork(auth, id);
        if (service.complete(id, Times.now()) == null) {
            throw Errors.conflict("The operation has already ended.");
        }
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/{id}/cancel")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Cancel the operation")
    public ResponseEntity<ApiResponse<WorkView>> cancel(AuthContext auth, @PathVariable UUID id) {
        requireControllableWork(auth, id);
        if (works.cancel(id, Times.now(), WorkService.ACTIVE) == 0) {
            throw Errors.conflict("The operation has already ended.");
        }
        return ApiResponse.ok(service.publish(id));
    }
}

package com.fermapp.api.modules.irrigation;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.crops.CropRules;
import com.fermapp.api.domain.crops.CropRules.WaterBalance;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.model.IrrigationSystemType;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.work.WorkMath;
import com.fermapp.api.domain.work.WorkMath.IrrigationEstimate;
import com.fermapp.api.integrations.OpenMeteoClient;
import com.fermapp.api.integrations.OpenMeteoClient.WeatherDay;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.work.WorkService;
import com.fermapp.api.modules.work.WorkViews.IrrigationView;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.IrrigationRun;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.IrrigationRunRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/** Irrigation runs and the seasonal water balance (irrigation.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Irrigation")
public class IrrigationController {

    public record RouteRequest(@NotNull @Positive @DecimalMax("100000") Double routeLengthM,
                               @NotNull @Positive @DecimalMax("10000") Double speedMPerH) {
    }

    public record StartRequest(@NotNull UUID parcelId, @NotNull IrrigationSystemType systemType,
                               @Positive @DecimalMax("200") Double doseMm,
                               @NotNull @Positive @DecimalMax("100000") Double routeLengthM,
                               @NotNull @Positive @DecimalMax("10000") Double speedMPerH) {
    }

    public record Period(String start, String end) {
    }

    public record WaterResponse(int year, String cropName, Period period, double precipitationMm, double irrigationMm,
                                @JsonUnwrapped WaterBalance balance) {
    }

    private final IrrigationRunRepository runs;
    private final CropSeasonRepository seasons;
    private final IrrigationService service;
    private final AccessGuard access;
    private final OpenMeteoClient weather;

    public IrrigationController(IrrigationRunRepository runs, CropSeasonRepository seasons, IrrigationService service,
                                AccessGuard access, OpenMeteoClient weather) {
        this.runs = runs;
        this.seasons = seasons;
        this.service = service;
        this.access = access;
        this.weather = weather;
    }

    private IrrigationRun requireRun(UUID farmId, UUID id) {
        return runs.findByIdAndFarmId(id, farmId).orElseThrow(() -> Errors.notFound("Irrigation"));
    }

    @PostMapping("/irrigation/estimate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Calculate irrigation duration and ETA")
    public ResponseEntity<ApiResponse<IrrigationEstimate>> estimate(@Valid @RequestBody RouteRequest body) {
        return ApiResponse.ok(WorkMath.estimateIrrigation(body.routeLengthM(), body.speedMPerH(), Times.now()));
    }

    @GetMapping("/irrigation")
    @Access(AccessGroup.ALL)
    @Operation(summary = "List irrigations")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<IrrigationView>>> list(AuthContext auth,
                                                                 @RequestParam(required = false) RunStatus status,
                                                                 @RequestParam(required = false) UUID parcelId) {
        Specification<IrrigationRun> spec = (root, query, cb) -> {
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
        return ApiResponse.ok(runs.findAll(spec, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "startedAt")))
                .stream().map(run -> IrrigationView.of(run, now)).toList());
    }

    @PostMapping("/irrigation")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Start an irrigation")
    public ResponseEntity<ApiResponse<IrrigationView>> start(AuthContext auth, @Valid @RequestBody StartRequest body) {
        access.requireActiveParcel(auth.farmId(), body.parcelId());
        if (runs.existsByParcelIdAndStatusIn(body.parcelId(), WorkService.ACTIVE)) {
            throw Errors.conflict("The parcel already has an active irrigation.");
        }
        IrrigationRun run = new IrrigationRun();
        run.setFarmId(auth.farmId());
        run.setParcelId(body.parcelId());
        run.setSystemType(body.systemType());
        run.setDoseMm(body.doseMm());
        run.setRouteLengthM(body.routeLengthM());
        run.setSpeedMPerH(body.speedMPerH());
        run.setDurationHours(body.routeLengthM() / body.speedMPerH());
        runs.saveAndFlush(run);
        return ApiResponse.created(service.publish(run.getId()));
    }

    @GetMapping("/irrigation/{id}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Irrigation details and progress")
    public ResponseEntity<ApiResponse<IrrigationView>> get(AuthContext auth, @PathVariable UUID id) {
        requireRun(auth.farmId(), id);
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/irrigation/{id}/pause")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Pause the irrigation")
    public ResponseEntity<ApiResponse<IrrigationView>> pause(AuthContext auth, @PathVariable UUID id) {
        requireRun(auth.farmId(), id);
        if (runs.pause(id, Times.now()) == 0) {
            throw Errors.conflict("Only a running irrigation can be paused.");
        }
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/irrigation/{id}/resume")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Resume the irrigation")
    public ResponseEntity<ApiResponse<IrrigationView>> resume(AuthContext auth, @PathVariable UUID id) {
        IrrigationRun run = requireRun(auth.farmId(), id);
        if (run.getStatus() != RunStatus.PAUSED || run.getPausedAt() == null) {
            throw Errors.conflict("The irrigation is not paused.");
        }
        Instant now = Times.now();
        runs.resume(id, now.toEpochMilli() - run.getPausedAt().toEpochMilli(), now);
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/irrigation/{id}/complete")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Complete the irrigation")
    public ResponseEntity<ApiResponse<IrrigationView>> complete(AuthContext auth, @PathVariable UUID id) {
        requireRun(auth.farmId(), id);
        if (service.complete(id, Times.now()) == null) {
            throw Errors.conflict("The irrigation has already ended.");
        }
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/irrigation/{id}/cancel")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Cancel the irrigation")
    public ResponseEntity<ApiResponse<IrrigationView>> cancel(AuthContext auth, @PathVariable UUID id) {
        requireRun(auth.farmId(), id);
        if (runs.cancel(id, Times.now(), WorkService.ACTIVE) == 0) {
            throw Errors.conflict("The irrigation has already ended.");
        }
        return ApiResponse.ok(service.publish(id));
    }

    @GetMapping("/parcels/{id}/water")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Water requirement, rainfall received and deficit")
    public ResponseEntity<ApiResponse<WaterResponse>> water(AuthContext auth, @PathVariable UUID id,
                                                           @RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        int target = year != null ? year : Times.currentLocalYear();
        CropSeason season = seasons.findByParcelIdAndYear(parcel.getId(), target)
                .orElseThrow(() -> Errors.notFound("The crop for " + target));

        // Without sowing and harvest dates, the April-September growing season is used.
        String start = Times.isoDay(season.getSowingDate() != null ? season.getSowingDate() : Times.utc(target, 4, 1, 0));
        Instant latestAvailable = Instant.now().minusMillis(2 * Times.DAY_MS);
        Instant seasonEnd = season.getHarvestDate() != null ? season.getHarvestDate() : Times.utc(target, 9, 30, 0);
        String end = Times.isoDay(seasonEnd.isBefore(latestAvailable) ? seasonEnd : latestAvailable);

        List<WeatherDay> days = start.compareTo(end) <= 0
                ? weather.getHistory(parcel.getCenterLat(), parcel.getCenterLng(), start, end)
                : List.of();
        double precipitationMm = days.stream().mapToDouble(WeatherDay::precipitationMm).sum();
        double irrigationMm = runs.findCompletedWithDoseSince(parcel.getId(), Times.utcMidnight(start)).stream()
                .mapToDouble(run -> run.getDoseMm() * run.getProgressPct() / 100).sum();
        return ApiResponse.ok(new WaterResponse(target, Crops.name(season.getCrop()), new Period(start, end),
                Js.round1(precipitationMm), Js.round1(irrigationMm),
                CropRules.waterBalance(season.getCrop(), precipitationMm, irrigationMm, 30)));
    }
}

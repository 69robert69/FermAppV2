package com.fermapp.api.modules.map;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.intelligence.Intelligence;
import com.fermapp.api.domain.intelligence.Intelligence.CropProgress;
import com.fermapp.api.domain.intelligence.Intelligence.CropProgressStatus;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.modules.common.Views.MachineLocationView;
import com.fermapp.api.modules.work.WorkService;
import com.fermapp.api.modules.work.WorkViews;
import com.fermapp.api.modules.work.WorkViews.IrrigationView;
import com.fermapp.api.modules.work.WorkViews.LabelledIrrigation;
import com.fermapp.api.modules.work.WorkViews.WorkView;
import com.fermapp.api.modules.work.WorkViews.WorkWithLocation;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.IrrigationRun;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.ParcelPlan;
import com.fermapp.api.persistence.entity.SatelliteObservation;
import com.fermapp.api.persistence.entity.WorkSession;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.IrrigationRunRepository;
import com.fermapp.api.persistence.repository.MachineLocationRepository;
import com.fermapp.api.persistence.repository.ParcelPlanRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.SatelliteObservationRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live status of every parcel for the map (map.routes.ts). */
@RestController
@RequestMapping("/api/v1/map")
@Tag(name = "Map")
public class MapController {

    public record MapCrop(CropCode code, String name, boolean planned, int year) {
    }

    public record MapParcel(UUID parcelId, MapCrop crop, Integer growthPct, long openTasks, Double ndvi,
                            List<WorkWithLocation> work, List<LabelledIrrigation> irrigation) {
    }

    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final SatelliteObservationRepository satellite;
    private final ParcelPlanRepository plans;
    private final WorkSessionRepository works;
    private final IrrigationRunRepository runs;
    private final MachineLocationRepository locations;
    private final TaskRepository tasks;

    public MapController(ParcelRepository parcels, CropSeasonRepository seasons, SatelliteObservationRepository satellite,
                         ParcelPlanRepository plans, WorkSessionRepository works, IrrigationRunRepository runs,
                         MachineLocationRepository locations, TaskRepository tasks) {
        this.parcels = parcels;
        this.seasons = seasons;
        this.satellite = satellite;
        this.plans = plans;
        this.works = works;
        this.runs = runs;
        this.locations = locations;
        this.tasks = tasks;
    }

    @GetMapping("/overview")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Live status of all parcels, for display on the map")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<MapParcel>>> overview(AuthContext auth) {
        Instant now = Times.now();
        int year = Times.localYear(now);
        List<Parcel> active = parcels.findByFarmIdAndArchivedAtIsNull(auth.farmId());
        List<UUID> ids = active.stream().map(Parcel::getId).toList();
        if (ids.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        Map<UUID, CropSeason> seasonByParcel = seasons.findByParcelIdInAndYear(ids, year).stream()
                .collect(Collectors.toMap(CropSeason::getParcelId, Function.identity(), (a, b) -> a));
        Map<UUID, SatelliteObservation> latest = satellite.findLatestFor(ids).stream()
                .collect(Collectors.toMap(SatelliteObservation::getParcelId, Function.identity(), (a, b) -> a));
        Map<UUID, ParcelPlan> planByParcel = new HashMap<>();
        for (ParcelPlan plan : plans.findActiveFrom(ids, year)) {
            planByParcel.putIfAbsent(plan.getParcelId(), plan);
        }
        List<WorkSession> work = works.findByFarmIdAndStatusInOrderByStartedAtAsc(auth.farmId(), WorkService.ACTIVE);
        List<IrrigationRun> irrigation = runs.findByFarmIdAndStatusInOrderByStartedAtAsc(auth.farmId(), WorkService.ACTIVE);
        Map<UUID, Long> openTasks = new HashMap<>();
        for (Object[] row : tasks.countOpenByParcel(auth.farmId())) {
            openTasks.put((UUID) row[0], (Long) row[1]);
        }

        List<MapParcel> result = new ArrayList<>();
        for (Parcel parcel : active) {
            ParcelPlan planned = planByParcel.get(parcel.getId());
            CropSeason recorded = seasonByParcel.get(parcel.getId());
            // The last crop declared in the wizard is not the crop in the field when the plan starts in a future year.
            CropSeason season = recorded != null && planned != null && planned.getYear() > year && recorded.getSowingDate() == null
                    ? null : recorded;
            CropCode code = season != null ? season.getCrop() : planned != null ? planned.getCrop() : null;
            CropProgress progress = season == null ? null : Intelligence.cropProgress(season.getCrop(), season.getSowingDate(),
                    season.getHarvestDate(), season.getExpectedHarvestDate(), now);
            List<WorkWithLocation> parcelWork = work.stream().filter(item -> item.getParcelId().equals(parcel.getId()))
                    .map(item -> new WorkWithLocation(WorkView.of(item, now),
                            MachineLocationView.of(locations.findFirstByWorkSessionIdOrderByRecordedAtDesc(item.getId()).orElse(null))))
                    .toList();
            List<IrrigationRun> parcelRuns = irrigation.stream().filter(run -> run.getParcelId().equals(parcel.getId())).toList();
            List<LabelledIrrigation> labelled = new ArrayList<>();
            for (int i = 0; i < parcelRuns.size(); i++) {
                IrrigationRun run = parcelRuns.get(i);
                labelled.add(new LabelledIrrigation(IrrigationView.of(run, now), WorkViews.systemLabel(run.getSystemType()) + " #" + (i + 1)));
            }
            SatelliteObservation observation = latest.get(parcel.getId());
            result.add(new MapParcel(parcel.getId(),
                    code == null ? null : new MapCrop(code, Crops.name(code), season == null,
                            season != null ? season.getYear() : planned.getYear()),
                    progress != null && progress.status() != CropProgressStatus.NOT_SOWN ? progress.percent() : null,
                    openTasks.getOrDefault(parcel.getId(), 0L),
                    observation == null ? null : observation.getNdvi(),
                    parcelWork, labelled));
        }
        return ApiResponse.ok(result);
    }
}

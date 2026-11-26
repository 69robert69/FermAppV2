package com.fermapp.api.modules.dashboard;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.TaskStatus;
import com.fermapp.api.modules.common.Views.AlertWithParcel;
import com.fermapp.api.modules.common.Views.CropSeasonView;
import com.fermapp.api.modules.common.Views.MachineView;
import com.fermapp.api.modules.common.Views.ParcelView;
import com.fermapp.api.modules.common.Views.TaskWithRefs;
import com.fermapp.api.modules.work.WorkService;
import com.fermapp.api.modules.work.WorkViews.IrrigationView;
import com.fermapp.api.modules.work.WorkViews.WorkView;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.IrrigationRunRepository;
import com.fermapp.api.persistence.repository.MachineRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.persistence.repository.WeatherAlertRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Main farm indicators and offline sync (dashboard.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Dashboard")
public class DashboardController {

    public record ParcelTotals(int count, double totalAreaHa, double cultivatedAreaHa, double freeAreaHa) {
    }

    public record CropArea(CropCode crop, String name, double areaHa) {
    }

    public record TaskCounts(long open, long overdue) {
    }

    public record Dashboard(int year, ParcelTotals parcels, List<CropArea> crops, List<WorkView> activeWork,
                            List<IrrigationView> activeIrrigation, TaskCounts tasks, List<AlertWithParcel> alerts) {
    }

    public record SyncChanges(String serverTime, List<ParcelView> parcels, List<CropSeasonView> seasons, List<MachineView> machines,
                              List<TaskWithRefs> tasks, List<WorkView> work, List<IrrigationView> irrigation, List<UUID> taskIds) {
    }

    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final WorkSessionRepository works;
    private final IrrigationRunRepository runs;
    private final TaskRepository tasks;
    private final WeatherAlertRepository alerts;
    private final MachineRepository machines;

    public DashboardController(ParcelRepository parcels, CropSeasonRepository seasons, WorkSessionRepository works,
                               IrrigationRunRepository runs, TaskRepository tasks, WeatherAlertRepository alerts,
                               MachineRepository machines) {
        this.parcels = parcels;
        this.seasons = seasons;
        this.works = works;
        this.runs = runs;
        this.tasks = tasks;
        this.alerts = alerts;
        this.machines = machines;
    }

    @GetMapping("/dashboard")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Main farm indicators")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Dashboard>> dashboard(AuthContext auth) {
        int year = Times.currentLocalYear();
        UUID farmId = auth.farmId();
        Instant now = Times.now();
        List<Parcel> active = parcels.findByFarmIdAndArchivedAtIsNull(farmId);
        List<CropSeason> current = seasons.findActiveForYear(farmId, year);

        double totalAreaHa = Js.round2(active.stream().mapToDouble(Parcel::getAreaHa).sum());
        double cultivatedAreaHa = Js.round2(current.stream().mapToDouble(season -> season.getParcel().getAreaHa()).sum());
        Map<CropCode, Double> byCrop = new LinkedHashMap<>();
        for (CropSeason season : current) {
            byCrop.merge(season.getCrop(), season.getParcel().getAreaHa(), Double::sum);
        }
        List<CropArea> crops = new ArrayList<>(byCrop.entrySet().stream()
                .map(entry -> new CropArea(entry.getKey(), Crops.name(entry.getKey()), Js.round2(entry.getValue()))).toList());
        crops.sort(Comparator.comparingDouble(CropArea::areaHa).reversed());

        return ApiResponse.ok(new Dashboard(year,
                new ParcelTotals(active.size(), totalAreaHa, cultivatedAreaHa, Js.round2(Math.max(0, totalAreaHa - cultivatedAreaHa))),
                crops,
                works.findByFarmIdAndStatusInOrderByStartedAtDesc(farmId, WorkService.ACTIVE).stream().map(w -> WorkView.of(w, now)).toList(),
                runs.findByFarmIdAndStatusInOrderByStartedAtDesc(farmId, WorkService.ACTIVE).stream().map(r -> IrrigationView.of(r, now)).toList(),
                new TaskCounts(tasks.countByFarmIdAndStatusNot(farmId, TaskStatus.DONE),
                        tasks.countByFarmIdAndStatusNotAndDueDateBefore(farmId, TaskStatus.DONE, now)),
                alerts.findLatest(farmId, PageRequest.of(0, 5)).stream().map(AlertWithParcel::of).toList()));
    }

    @GetMapping("/sync/changes")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Changes since the last offline sync")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<SyncChanges>> changes(AuthContext auth, @RequestParam(required = false) Instant since) {
        UUID farmId = auth.farmId();
        Instant serverTime = Times.now();
        PageRequest latest = PageRequest.of(0, 500);
        return ApiResponse.ok(new SyncChanges(
                Times.iso(serverTime),
                (since == null ? parcels.findByFarmId(farmId) : parcels.findByFarmIdAndUpdatedAtGreaterThanEqual(farmId, since))
                        .stream().map(ParcelView::of).toList(),
                (since == null ? seasons.findByFarm(farmId) : seasons.findByFarmSince(farmId, since))
                        .stream().map(CropSeasonView::of).toList(),
                (since == null ? machines.findByFarmId(farmId) : machines.findByFarmIdAndUpdatedAtGreaterThanEqual(farmId, since))
                        .stream().map(MachineView::of).toList(),
                (since == null ? tasks.findByFarmId(farmId) : tasks.findByFarmIdAndUpdatedAtGreaterThanEqual(farmId, since))
                        .stream().map(TaskWithRefs::of).toList(),
                (since == null ? works.findByFarmIdOrderByUpdatedAtDesc(farmId, latest)
                        : works.findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(farmId, since, latest))
                        .stream().map(w -> WorkView.of(w, serverTime)).toList(),
                (since == null ? runs.findByFarmIdOrderByUpdatedAtDesc(farmId, latest)
                        : runs.findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(farmId, since, latest))
                        .stream().map(r -> IrrigationView.of(r, serverTime)).toList(),
                // The full list of ids lets the app remove deleted tasks locally.
                tasks.findIdsByFarm(farmId)));
    }
}

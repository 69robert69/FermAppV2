package com.fermapp.api.modules.reports;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory;
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics;
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics;
import com.fermapp.api.domain.economics.Economics;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldTask;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.report.ReportBuilders;
import com.fermapp.api.domain.report.ReportBuilders.ReportMachine;
import com.fermapp.api.domain.report.ReportBuilders.ReportParcel;
import com.fermapp.api.domain.report.ReportBuilders.ReportRun;
import com.fermapp.api.domain.report.ReportBuilders.ReportSeason;
import com.fermapp.api.domain.report.ReportTable;
import com.fermapp.api.domain.report.ReportTable.Column;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.rotation.Rotation.RotationResult;
import com.fermapp.api.domain.tillage.Tillage;
import com.fermapp.api.modules.economics.EconomicsService;
import com.fermapp.api.modules.economics.EconomicsService.FarmEconomicsResult;
import com.fermapp.api.modules.work.RunProgress;
import com.fermapp.api.modules.work.WorkViews;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.IrrigationRun;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.WorkSession;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.FarmRepository;
import com.fermapp.api.persistence.repository.IrrigationRunRepository;
import com.fermapp.api.persistence.repository.MachineRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The eleven farm reports (reports.service.ts). Reports with money in them are hidden from operators. */
@Service
public class ReportService {

    public static final List<String> REPORT_TYPES =
            List.of("costs", "profitability", "farm", "parcels", "work", "machines", "crops", "fuel", "yields", "rotation", "irrigation");
    public static final Set<String> FINANCIAL_REPORTS = Set.of("costs", "profitability", "farm");

    /** The period of a report: from/to for the logs, year for the seasons. */
    public record Range(Instant from, Instant to, int year) {
    }

    private record Sources(List<ReportParcel> parcels, List<FieldTask> tasks, List<ReportRun> runs, List<ReportMachine> machines,
                           List<ReportSeason> seasons) {
    }

    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final WorkSessionRepository works;
    private final IrrigationRunRepository runs;
    private final TaskRepository tasks;
    private final MachineRepository machines;
    private final FarmRepository farms;
    private final EconomicsService economics;

    public ReportService(ParcelRepository parcels, CropSeasonRepository seasons, WorkSessionRepository works,
                         IrrigationRunRepository runs, TaskRepository tasks, MachineRepository machines, FarmRepository farms,
                         EconomicsService economics) {
        this.parcels = parcels;
        this.seasons = seasons;
        this.works = works;
        this.runs = runs;
        this.tasks = tasks;
        this.machines = machines;
        this.farms = farms;
        this.economics = economics;
    }

    private static String day(Instant date) {
        return date == null ? "" : Times.isoDay(date);
    }

    private static double round(double value) {
        return Js.round(value, 2);
    }

    private static double sum(List<Map<String, Object>> rows, String key) {
        return round(rows.stream().mapToDouble(row -> row.get(key) instanceof Number number ? number.doubleValue() : 0).sum());
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String statusLabel(RunStatus status) {
        return switch (status) {
            case RUNNING -> "Running";
            case PAUSED -> "Paused";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
        };
    }

    private static String periodOf(Range range) {
        return day(range.from()) + " - " + day(range.to());
    }

    private ReportTable workReport(UUID farmId, Range range) {
        List<Map<String, Object>> rows = works.findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(farmId, range.from(), range.to())
                .stream().map(item -> row(
                        "date", day(item.getStartedAt()),
                        "parcel", item.getParcel().getName(),
                        "work", WorkViews.workLabel(item.getType()),
                        "machine", item.getMachine() == null ? "" : item.getMachine().getName(),
                        "operator", item.getOperator().getFullName(),
                        "area", round(item.getAreaHa() * item.getProgressPct() / 100),
                        "status", statusLabel(item.getStatus())))
                .collect(Collectors.toList());
        return new ReportTable("Completed work", periodOf(range),
                List.of(Column.text("date", "Date", 1.1), Column.text("parcel", "Parcel", 1.5), Column.text("work", "Operation", 1.1),
                        Column.text("machine", "Machine", 1.4), Column.text("operator", "Operator", 1.4),
                        Column.number("area", "Done (ha)", 1), Column.text("status", "Status", 1.1)),
                rows, row("area", sum(rows, "area")), null, null, null);
    }

    private ReportTable fuelReport(UUID farmId, Range range) {
        Map<String, double[]> byMachine = new LinkedHashMap<>();
        for (WorkSession item : works.findByFarmIdAndStatusAndCompletedAtBetween(farmId, RunStatus.COMPLETED, range.from(), range.to())) {
            String name = item.getMachine() == null ? "No machine" : item.getMachine().getName();
            double[] totals = byMachine.computeIfAbsent(name, key -> new double[3]);
            totals[0] += 1;
            totals[1] += item.getAreaHa() * item.getProgressPct() / 100;
            totals[2] += item.getFuelLiters() == null ? 0 : item.getFuelLiters();
        }
        List<Map<String, Object>> rows = byMachine.entrySet().stream().map(entry -> row(
                "machine", entry.getKey(),
                "sessions", entry.getValue()[0],
                "area", round(entry.getValue()[1]),
                "fuel", round(entry.getValue()[2]),
                "perHa", entry.getValue()[1] > 0 ? round(entry.getValue()[2] / entry.getValue()[1]) : 0.0)).collect(Collectors.toList());
        return new ReportTable("Diesel consumption", periodOf(range),
                List.of(Column.text("machine", "Machine", 2), Column.number("sessions", "Operations", 1),
                        Column.number("area", "Area (ha)", 1), Column.number("fuel", "Diesel (l)", 1), Column.number("perHa", "l/ha", 1)),
                rows, row("sessions", sum(rows, "sessions"), "area", sum(rows, "area"), "fuel", sum(rows, "fuel")), null, null, null);
    }

    /** Real cost per hectare and profit of every parcel: inputs, operations, machinery, labour and recorded expenses. */
    private ReportTable costReport(UUID farmId, Range range) {
        FarmEconomicsResult result = economics.farmEconomics(farmId, range.year(), null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FarmParcelEconomics item : result.parcels()) {
            if (!(item.result().season().total() > 0)) {
                continue;
            }
            Map<String, Object> values = row("parcel", item.name(), "crop", item.cropName() == null ? "" : item.cropName(),
                    "area", Math.round(item.result().areaHa() * 100) / 100.0);
            for (EconomicCategory category : EconomicCategory.values()) {
                values.put(category.name(), item.result().season().byCategory().get(category));
            }
            values.put("total", item.result().season().total());
            values.put("perHa", item.result().season().perHa());
            values.put("estimated", item.result().expected() == null ? null : item.result().expected().profit());
            values.put("actual", item.result().actual() == null ? null : item.result().actual().profit());
            rows.add(values);
        }
        List<Column> columns = new ArrayList<>(List.of(Column.text("parcel", "Parcel", 1.4), Column.text("crop", "Crop", 0.9),
                Column.number("area", "ha", 0.6)));
        for (EconomicCategory category : EconomicCategory.values()) {
            columns.add(Column.money(category.name(), Economics.LABELS.get(category), 0.9));
        }
        columns.add(Column.money("total", "Total", 1));
        columns.add(Column.money("perHa", "Cost/ha", 0.9));
        columns.add(Column.money("estimated", "Estimated profit", 1));
        columns.add(Column.money("actual", "Actual profit", 1));
        FarmEconomics summary = result.summary();
        Map<String, Object> totals = row("area", summary.areaHa());
        for (EconomicCategory category : EconomicCategory.values()) {
            totals.put(category.name(), summary.byCategory().get(category));
        }
        totals.put("total", summary.cost());
        totals.put("perHa", summary.costPerHa());
        if (summary.expectedProfit() != null) {
            totals.put("estimated", summary.expectedProfit());
        }
        if (summary.actualProfit() != null) {
            totals.put("actual", summary.actualProfit());
        }
        return new ReportTable("Costs and profit", "Year " + range.year() + ", in euro", columns, rows, totals, null, null, null);
    }

    private ReportTable yieldReport(UUID farmId, Range range) {
        List<Map<String, Object>> rows = seasons.findForYearOrderByParcelName(farmId, range.year()).stream().map(season -> row(
                "parcel", season.getParcel().getName(),
                "crop", Crops.name(season.getCrop()),
                "area", season.getParcel().getAreaHa(),
                "harvest", day(season.getHarvestDate()),
                "yield", season.getYieldT(),
                "perHa", season.getYieldT() != null ? round(season.getYieldT() / season.getParcel().getAreaHa()) : null,
                "moisture", season.getHarvestMoisture())).collect(Collectors.toList());
        return new ReportTable("Yields", "Year " + range.year(),
                List.of(Column.text("parcel", "Parcel", 1.6), Column.text("crop", "Crop", 1.3), Column.number("area", "Area (ha)", 1),
                        Column.text("harvest", "Harvest", 1.1), Column.number("yield", "Yield (t)", 1), Column.number("perHa", "t/ha", 0.8),
                        Column.number("moisture", "Moisture (%)", 1)),
                rows, row("area", sum(rows, "area"), "yield", sum(rows, "yield")), null, null, null);
    }

    private ReportTable rotationReport(UUID farmId, Range range) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Parcel parcel : parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId)) {
            List<CropSeason> list = seasons.findTop10ByParcelIdOrderByYearDesc(parcel.getId());
            CropSeason current = list.stream().filter(season -> season.getYear() == range.year()).findFirst().orElse(null);
            if (current == null) {
                continue;
            }
            List<CropSeason> history = list.stream().filter(season -> season.getYear() < range.year()).toList();
            RotationResult result = Rotation.evaluateRotation(current.getCrop(), range.year(),
                    history.stream().map(season -> new CropHistoryEntry(season.getCrop(), season.getYear())).toList());
            rows.add(row(
                    "parcel", parcel.getName(),
                    "crop", Crops.name(current.getCrop()),
                    "previous", history.isEmpty() ? "" : Crops.name(history.get(0).getCrop()),
                    "score", result.score(),
                    "verdict", result.label().replaceFirst("^\\S+\\s", ""),
                    "explanation", result.explanation()));
        }
        return new ReportTable("Crop rotation", "Year " + range.year(),
                List.of(Column.text("parcel", "Parcel", 1.2), Column.text("crop", "Crop", 1), Column.text("previous", "Previous crop", 1),
                        Column.number("score", "Score", 0.5), Column.text("verdict", "Verdict", 0.9),
                        Column.text("explanation", "Explanation", 3)),
                rows, null, null, null, null);
    }

    private ReportTable irrigationReport(UUID farmId, Range range) {
        List<Map<String, Object>> rows = runs.findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(farmId, range.from(), range.to())
                .stream().map((IrrigationRun run) -> row(
                        "date", day(run.getStartedAt()),
                        "parcel", run.getParcel().getName(),
                        "system", WorkViews.systemLabel(run.getSystemType()),
                        "route", run.getRouteLengthM(),
                        "hours", round(run.getDurationHours() * run.getProgressPct() / 100),
                        "dose", run.getDoseMm(),
                        "percent", run.getProgressPct(),
                        "status", statusLabel(run.getStatus())))
                .collect(Collectors.toList());
        return new ReportTable("Irrigation", periodOf(range),
                List.of(Column.text("date", "Date", 1.1), Column.text("parcel", "Parcel", 1.5), Column.text("system", "System", 0.9),
                        Column.number("route", "Route (m)", 0.9), Column.number("hours", "Hours", 0.7), Column.number("dose", "Dose (mm)", 0.8),
                        Column.number("percent", "Done (%)", 0.9), Column.text("status", "Status", 1.1)),
                rows, row("hours", sum(rows, "hours")), null, null, null);
    }

    /** Parcels, tasks, runs and machines of the farm in the shape the shared reports read. */
    private Sources sources(UUID farmId, Range range) {
        List<Parcel> parcelList = parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId);
        List<UUID> ids = parcelList.stream().map(Parcel::getId).toList();
        List<CropSeason> yearSeasons = ids.isEmpty() ? List.of() : seasons.findByParcelIdInAndYear(ids, range.year());
        Map<UUID, CropSeason> seasonByParcel = yearSeasons.stream()
                .collect(Collectors.toMap(CropSeason::getParcelId, Function.identity(), (a, b) -> a));
        Instant now = Times.now();
        return new Sources(
                parcelList.stream().map(parcel -> new ReportParcel(parcel.getId().toString(), parcel.getName(), parcel.getCode(),
                        parcel.getAreaHa(), parcel.getLocality(),
                        seasonByParcel.containsKey(parcel.getId()) ? seasonByParcel.get(parcel.getId()).getCrop() : null,
                        Tillage.parse(parcel.getTillageSystem()))).toList(),
                tasks.findByFarmId(farmId).stream().map(task -> new FieldTask(task.getId().toString(), task.getTitle(),
                        task.getType().name(), task.getStatus().name(), Times.iso(task.getDueDate()),
                        task.getParcelId() == null ? null : task.getParcelId().toString())).toList(),
                works.findByFarmId(farmId).stream().map(work -> {
                    double percent = RunProgress.of(work, now).percent();
                    double hours = work.getDurationHours() * percent / 100;
                    Double fuel = work.getFuelLiters() != null ? work.getFuelLiters()
                            : work.getMachine() != null && work.getMachine().getAvgConsumptionLh() != null
                            && work.getMachine().getAvgConsumptionLh() != 0
                            ? work.getMachine().getAvgConsumptionLh() * hours : null;
                    return new ReportRun(work.getParcelId().toString(), work.getMachineId() == null ? null : work.getMachineId().toString(),
                            work.getType().name(), work.getStatus().name(), work.getAreaHa(), percent, hours, fuel,
                            Times.iso(work.getStartedAt()));
                }).toList(),
                machines.findActiveWithOperator(farmId).stream().map(machine -> new ReportMachine(machine.getId().toString(),
                        machine.getName(), machine.getType().name(),
                        machine.getOperator() == null ? null : machine.getOperator().getFullName())).toList(),
                yearSeasons.stream().map(season -> new ReportSeason(season.getParcelId().toString(), season.getCrop(), season.getYieldT()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public ReportTable build(String type, UUID farmId, Range range) {
        return switch (type) {
            case "parcels" -> {
                Sources data = sources(farmId, range);
                yield ReportBuilders.parcelsReport(range.year(), data.parcels(), data.tasks(), data.runs(), Times.now());
            }
            case "machines" -> {
                Sources data = sources(farmId, range);
                yield ReportBuilders.machinesReport(range.year(), data.machines(), data.runs(), id -> data.parcels().stream()
                        .filter(parcel -> parcel.id().equals(id)).map(ReportParcel::name).findFirst().orElse(""));
            }
            case "crops" -> {
                Sources data = sources(farmId, range);
                yield ReportBuilders.cropsReport(range.year(), data.parcels(), data.seasons());
            }
            case "profitability" -> {
                FarmEconomicsResult result = economics.farmEconomics(farmId, range.year(), null);
                yield ReportBuilders.profitabilityReport(range.year(), result.parcels(), result.summary());
            }
            case "farm" -> {
                Sources data = sources(farmId, range);
                FarmEconomicsResult result = economics.farmEconomics(farmId, range.year(), null);
                String farmName = farms.findById(farmId).orElseThrow(Errors::recordNotFound).getName();
                yield ReportBuilders.farmReport(range.year(), farmName, data.parcels(), data.runs(), data.tasks(), result.summary(), Times.now());
            }
            case "work" -> workReport(farmId, range);
            case "fuel" -> fuelReport(farmId, range);
            case "costs" -> costReport(farmId, range);
            case "yields" -> yieldReport(farmId, range);
            case "rotation" -> rotationReport(farmId, range);
            case "irrigation" -> irrigationReport(farmId, range);
            default -> throw Errors.badRequest("Unknown report type.");
        };
    }
}

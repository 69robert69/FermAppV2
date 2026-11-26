package com.fermapp.api.domain.report;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory;
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics;
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics;
import com.fermapp.api.domain.economics.EconomicModels.ParcelEconomicsResult;
import com.fermapp.api.domain.economics.EconomicModels.ProfitResult;
import com.fermapp.api.domain.economics.Economics;
import com.fermapp.api.domain.economics.Money;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldState;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldStatus;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldTask;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.report.ReportTable.Chart;
import com.fermapp.api.domain.report.ReportTable.Column;
import com.fermapp.api.domain.report.ReportTable.Point;
import com.fermapp.api.domain.report.ReportTable.SummaryItem;
import com.fermapp.api.domain.tillage.Tillage;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Report tables shared by the server and the phone, so a report has the same columns,
 * totals, chart and summary whichever backend produced it (reportBuilders.ts).
 */
public final class ReportBuilders {

    public record ReportParcel(String id, String name, String code, double areaHa, String locality, CropCode crop, TillageSystem tillage) {
    }

    public record ReportRun(String parcelId, String machineId, String type, String status, double areaHa, double percent,
                            double hours, Double fuelL, String startedAt) {
    }

    public record ReportMachine(String id, String name, String type, String operatorName) {
    }

    public record ReportSeason(String parcelId, CropCode crop, Double yieldT) {
    }

    private static final Map<String, String> MACHINE_TYPES = Map.of(
            "TRACTOR", "Tractor", "COMBINE", "Combine", "SPRAYER", "Sprayer", "SEEDER", "Seed drill",
            "DISC", "Disc", "CULTIVATOR", "Cultivator", "SUBSOILER", "Subsoiler", "TRAILER", "Trailer");

    private ReportBuilders() {
    }

    private static double round(double value) {
        return Js.round(value, 2);
    }

    private static <T> double sum(List<T> items, ToDoubleFunction<T> pick) {
        return items.stream().mapToDouble(pick).sum();
    }

    private static double numberOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static boolean isActive(ReportRun run) {
        return ("RUNNING".equals(run.status()) || "PAUSED".equals(run.status())) && run.percent() < 100;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static FieldStatus statusOf(ReportParcel parcel, List<FieldTask> tasks, List<ReportRun> runs, Instant now) {
        return FieldStatusRules.fieldStatus(
                tasks.stream().filter(task -> parcel.id().equals(task.parcelId())).toList(),
                (int) runs.stream().filter(run -> run.parcelId().equals(parcel.id()) && isActive(run)).count(),
                now);
    }

    public static ReportTable parcelsReport(int year, List<ReportParcel> parcels, List<FieldTask> tasks, List<ReportRun> runs, Instant now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportParcel parcel : parcels) {
            FieldStatus status = statusOf(parcel, tasks, runs, now);
            rows.add(row(
                    "parcel", parcel.name(),
                    "code", parcel.code(),
                    "area", round(parcel.areaHa()),
                    "crop", parcel.crop() != null ? Crops.name(parcel.crop()) : "",
                    "tillage", parcel.tillage() != null ? Tillage.profile(parcel.tillage()).badge() : "",
                    "status", FieldStatusRules.LABELS.get(status.state()),
                    "open", status.pending() + status.later(),
                    "overdue", status.overdue(),
                    "completion", status.completionScore()));
        }
        List<Map<String, Object>> scored = rows.stream().filter(r -> r.get("completion") != null).toList();
        double totalArea = sum(rows, r -> numberOf(r.get("area")));
        return new ReportTable("Parcels", "Year " + year,
                List.of(Column.text("parcel", "Parcel", 1.4), Column.text("code", "Code", 0.7), Column.number("area", "ha", 0.6),
                        Column.text("crop", "Crop", 0.9), Column.text("tillage", "Tillage", 0.7), Column.text("status", "Status", 1.1),
                        Column.number("open", "Open tasks", 0.7), Column.number("overdue", "Overdue", 0.7),
                        Column.number("completion", "Completion %", 0.8)),
                rows,
                row("area", round(totalArea), "open", sum(rows, r -> numberOf(r.get("open"))),
                        "overdue", sum(rows, r -> numberOf(r.get("overdue")))),
                "landscape",
                new Chart("Area by parcel", "parcel", "area", null, null, "ha"),
                List.of(new SummaryItem("Parcels", String.valueOf(rows.size())),
                        new SummaryItem("Total area", Js.num(Js.round(totalArea, 1)) + " ha"),
                        new SummaryItem("Average completion", scored.isEmpty() ? "-"
                                : Math.round(sum(scored, r -> numberOf(r.get("completion"))) / scored.size()) + "%"),
                        new SummaryItem("With overdue work", String.valueOf(rows.stream().filter(r -> numberOf(r.get("overdue")) > 0).count()))));
    }

    public static ReportTable machinesReport(int year, List<ReportMachine> machines, List<ReportRun> runs,
                                             Function<String, String> parcelName) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportMachine machine : machines) {
            List<ReportRun> own = runs.stream()
                    .filter(run -> machine.id().equals(run.machineId()) && run.startedAt().startsWith(String.valueOf(year))
                            && !"CANCELLED".equals(run.status()))
                    .toList();
            ReportRun active = own.stream().filter(ReportBuilders::isActive).findFirst().orElse(null);
            rows.add(row(
                    "machine", machine.name(),
                    "type", MACHINE_TYPES.getOrDefault(machine.type(), machine.type()),
                    "operator", machine.operatorName() != null ? machine.operatorName() : "",
                    "operations", own.size(),
                    "hours", Js.round(sum(own, ReportRun::hours), 1),
                    "area", Js.round(sum(own, run -> run.areaHa() * Math.min(100, run.percent()) / 100), 1),
                    "fuel", Js.round(sum(own, run -> run.fuelL() == null ? 0 : run.fuelL())),
                    "status", active != null ? "Working, " + parcelName.apply(active.parcelId()) : "Available"));
        }
        double hours = Js.round(sum(rows, r -> numberOf(r.get("hours"))), 1);
        double fuel = sum(rows, r -> numberOf(r.get("fuel")));
        return new ReportTable("Machines", "Year " + year,
                List.of(Column.text("machine", "Machine", 1.4), Column.text("type", "Type", 0.9), Column.text("operator", "Operator", 1.1),
                        Column.number("operations", "Operations", 0.8), Column.number("hours", "Hours", 0.7),
                        Column.number("area", "Area (ha)", 0.8), Column.number("fuel", "Diesel (l)", 0.8), Column.text("status", "Status", 1.3)),
                rows,
                row("operations", sum(rows, r -> numberOf(r.get("operations"))), "hours", hours,
                        "area", Js.round(sum(rows, r -> numberOf(r.get("area"))), 1), "fuel", fuel),
                "landscape",
                new Chart("Working hours by machine", "machine", "hours", null, null, "h"),
                List.of(new SummaryItem("Machines", String.valueOf(rows.size())),
                        new SummaryItem("Working now", String.valueOf(rows.stream().filter(r -> !"Available".equals(r.get("status"))).count())),
                        new SummaryItem("Hours worked", Js.num(Js.round(sum(rows, r -> numberOf(r.get("hours"))), 0)) + " h"),
                        new SummaryItem("Diesel", Js.num(fuel) + " l")));
    }

    private static final class CropGroup {
        int parcels;
        double area;
        double yieldT;
        double harvestedArea;
    }

    public static ReportTable cropsReport(int year, List<ReportParcel> parcels, List<ReportSeason> seasons) {
        Map<CropCode, CropGroup> groups = new LinkedHashMap<>();
        for (ReportSeason season : seasons) {
            ReportParcel parcel = parcels.stream().filter(item -> item.id().equals(season.parcelId())).findFirst().orElse(null);
            if (parcel == null) {
                continue;
            }
            CropGroup group = groups.computeIfAbsent(season.crop(), key -> new CropGroup());
            group.parcels += 1;
            group.area += parcel.areaHa();
            if (season.yieldT() != null) {
                group.yieldT += season.yieldT();
                group.harvestedArea += parcel.areaHa();
            }
        }
        double total = groups.values().stream().mapToDouble(group -> group.area).sum();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<CropCode, CropGroup> entry : groups.entrySet()) {
            CropGroup group = entry.getValue();
            rows.add(row(
                    "crop", Crops.name(entry.getKey()),
                    "parcels", group.parcels,
                    "area", round(group.area),
                    "share", total > 0 ? Js.round(group.area / total * 100, 1) : 0.0,
                    "yield", group.yieldT != 0 ? Js.round(group.yieldT, 1) : null,
                    "perHa", group.harvestedArea != 0 ? round(group.yieldT / group.harvestedArea) : null));
        }
        rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> numberOf(r.get("area"))).reversed());
        double harvested = sum(rows, r -> numberOf(r.get("yield")));
        return new ReportTable("Crops", "Year " + year,
                List.of(Column.text("crop", "Crop", 1.3), Column.number("parcels", "Parcels", 0.7), Column.number("area", "Area (ha)", 0.9),
                        Column.number("share", "Share (%)", 0.8), Column.number("yield", "Yield (t)", 0.9), Column.number("perHa", "t/ha", 0.7)),
                rows,
                row("parcels", sum(rows, r -> numberOf(r.get("parcels"))), "area", round(total), "yield", Js.round(harvested, 1)),
                "portrait",
                new Chart("Area by crop", "crop", "area", null, null, "ha"),
                List.of(new SummaryItem("Crops", String.valueOf(rows.size())),
                        new SummaryItem("Cultivated area", Js.num(Js.round(total, 1)) + " ha"),
                        new SummaryItem("Harvested", Js.num(Js.round(harvested, 0)) + " t")));
    }

    private static ProfitResult best(ParcelEconomicsResult result) {
        return result.actual() != null ? result.actual() : result.expected();
    }

    public static ReportTable profitabilityReport(int year, List<FarmParcelEconomics> parcels, FarmEconomics summary) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FarmParcelEconomics item : parcels) {
            if (!(item.result().season().total() > 0)) {
                continue;
            }
            ProfitResult outlook = best(item.result());
            rows.add(row(
                    "parcel", item.name(),
                    "crop", item.cropName() != null ? item.cropName() : "",
                    "area", round(item.result().areaHa()),
                    "costPerHa", item.result().season().perHa(),
                    "revenuePerHa", outlook != null ? outlook.revenuePerHa() : null,
                    "profitPerHa", outlook != null ? outlook.profitPerHa() : null,
                    "margin", outlook != null ? outlook.marginPct() : null,
                    "profit", outlook != null ? outlook.profit() : null,
                    "basis", item.result().actual() != null ? "Actual" : item.result().expected() != null ? "Expected" : ""));
        }
        return new ReportTable("Profitability", "Year " + year + ", in euro",
                List.of(Column.text("parcel", "Parcel", 1.3), Column.text("crop", "Crop", 0.9), Column.number("area", "ha", 0.6),
                        Column.money("costPerHa", "Cost/ha", 0.9), Column.money("revenuePerHa", "Revenue/ha", 0.9),
                        Column.money("profitPerHa", "Profit/ha", 0.9), Column.number("margin", "Margin %", 0.7),
                        Column.money("profit", "Profit", 1), Column.text("basis", "Basis", 0.7)),
                rows,
                row("area", round(sum(rows, r -> numberOf(r.get("area")))), "profit", round(sum(rows, r -> numberOf(r.get("profit"))))),
                "landscape",
                new Chart("Profit by parcel", "parcel", "profit", null, true, null),
                List.of(new SummaryItem("Average cost", Money.formatEuro(summary.costPerHa(), 0) + "/ha"),
                        new SummaryItem("Average profit", summary.profitPerHa() == null ? "-" : Money.formatEuro(summary.profitPerHa(), 0) + "/ha"),
                        new SummaryItem("Best field", summary.mostProfitableParcel() != null ? summary.mostProfitableParcel().label() : "-"),
                        new SummaryItem("Best crop", summary.mostProfitableCrop() != null ? summary.mostProfitableCrop().label() : "-")));
    }

    public static ReportTable farmReport(int year, String farmName, List<ReportParcel> parcels, List<ReportRun> runs,
                                         List<FieldTask> tasks, FarmEconomics economics, Instant now) {
        List<ReportRun> yearRuns = runs.stream()
                .filter(run -> run.startedAt().startsWith(String.valueOf(year)) && !"CANCELLED".equals(run.status())).toList();
        double area = sum(parcels, ReportParcel::areaHa);
        double cultivated = sum(parcels.stream().filter(parcel -> parcel.crop() != null).toList(), ReportParcel::areaHa);
        List<FieldStatus> statuses = parcels.stream().map(parcel -> statusOf(parcel, tasks, runs, now)).toList();
        List<FieldStatus> scored = statuses.stream().filter(status -> status.completionScore() != null).toList();
        Map<String, Double> byType = new LinkedHashMap<>();
        for (ReportRun run : yearRuns) {
            byType.merge(run.type(), run.areaHa() * Math.min(100, run.percent()) / 100, Double::sum);
        }
        String completion = scored.isEmpty() ? "-"
                : Math.round(scored.stream().mapToDouble(FieldStatus::completionScore).sum() / scored.size()) + "%";

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("metric", "Parcels", "value", String.valueOf(parcels.size())));
        rows.add(row("metric", "Total area", "value", Js.num(Js.round(area, 1)) + " ha"));
        rows.add(row("metric", "Cultivated area", "value", Js.num(Js.round(cultivated, 1)) + " ha"));
        rows.add(row("metric", "Operations carried out", "value", String.valueOf(yearRuns.size())));
        rows.add(row("metric", "Area worked", "value",
                Js.num(Js.round(sum(yearRuns, run -> run.areaHa() * Math.min(100, run.percent()) / 100), 1)) + " ha"));
        rows.add(row("metric", "Diesel used", "value", Math.round(sum(yearRuns, run -> run.fuelL() == null ? 0 : run.fuelL())) + " l"));
        rows.add(row("metric", "Parcels fully completed", "value",
                String.valueOf(statuses.stream().filter(status -> status.state() == FieldState.COMPLETE).count())));
        rows.add(row("metric", "Parcels with overdue work", "value",
                String.valueOf(statuses.stream().filter(status -> status.state() == FieldState.OVERDUE).count())));
        rows.add(row("metric", "Average field completion", "value", completion));
        for (Map.Entry<String, Double> entry : byType.entrySet()) {
            rows.add(row("metric", Economics.WORK_OPERATION_LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                    "value", Js.num(Js.round(entry.getValue(), 1)) + " ha"));
        }
        List<EconomicCategory> categoryRows = new ArrayList<>();
        if (economics != null) {
            rows.add(row("metric", "Production cost", "value", Money.formatEuro(economics.cost(), 0)));
            rows.add(row("metric", "Cost per hectare", "value", Money.formatEuro(economics.costPerHa(), 2)));
            Double profit = economics.actualProfit() != null ? economics.actualProfit() : economics.expectedProfit();
            rows.add(row("metric", "Profit", "value", Money.formatEuro(profit, 0)));
            for (EconomicCategory category : EconomicCategory.values()) {
                if (economics.byCategory().get(category) > 0) {
                    categoryRows.add(category);
                    rows.add(row("metric", "Cost: " + Economics.LABELS.get(category),
                            "value", Money.formatEuro(economics.byCategory().get(category), 0)));
                }
            }
        }
        Chart chart = economics != null && !categoryRows.isEmpty()
                ? new Chart("Production cost by category", null, null,
                        categoryRows.stream().map(category -> new Point(Economics.LABELS.get(category), economics.byCategory().get(category))).toList(),
                        true, null)
                : null;
        List<SummaryItem> summary = new ArrayList<>();
        summary.add(new SummaryItem("Area", Js.num(Js.round(area, 1)) + " ha"));
        summary.add(new SummaryItem("Operations", String.valueOf(yearRuns.size())));
        if (economics != null) {
            summary.add(new SummaryItem("Cost per hectare", Money.formatEuro(economics.costPerHa(), 0)));
        }
        summary.add(new SummaryItem("Field completion", completion));
        return new ReportTable("Farm overview, " + farmName, "Year " + year,
                List.of(Column.text("metric", "Indicator", 2), Column.number("value", "Value", 1.2)),
                rows, null, "portrait", chart, summary);
    }

    /** The chart points of a report, largest first, capped for legibility. */
    public static List<Point> reportChartPoints(ReportTable report, int max) {
        Chart chart = report.chart();
        if (chart == null) {
            return List.of();
        }
        List<Point> points;
        if (chart.points() != null) {
            points = chart.points();
        } else if (chart.labelKey() != null && chart.valueKey() != null) {
            points = report.rows().stream()
                    .map(r -> new Point(Objects.toString(r.get(chart.labelKey()), ""), toNumber(r.get(chart.valueKey()))))
                    .toList();
        } else {
            points = List.of();
        }
        return points.stream()
                .filter(point -> Double.isFinite(point.value()) && point.value() != 0)
                .sorted(Comparator.comparingDouble((Point point) -> Math.abs(point.value())).reversed())
                .limit(max)
                .toList();
    }

    /** {@code Number(value)}: null is 0, text is parsed, anything else is NaN. */
    private static double toNumber(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}

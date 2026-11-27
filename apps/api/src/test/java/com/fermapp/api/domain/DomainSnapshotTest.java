package com.fermapp.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fermapp.api.config.JacksonConfig;
import com.fermapp.api.domain.crops.CropRules;
import com.fermapp.api.domain.crops.CropRules.ClimateFit;
import com.fermapp.api.domain.crops.CropRules.DailyTemperature;
import com.fermapp.api.domain.crops.CropRules.SoilFit;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.crops.Crops.SoilTexture;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet;
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics;
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics;
import com.fermapp.api.domain.economics.EconomicModels.OperationInput;
import com.fermapp.api.domain.economics.EconomicModels.OperationMachine;
import com.fermapp.api.domain.economics.EconomicModels.OperationOperator;
import com.fermapp.api.domain.economics.EconomicModels.ParcelEconomicsResult;
import com.fermapp.api.domain.economics.EconomicModels.RecordedCost;
import com.fermapp.api.domain.economics.Economics;
import com.fermapp.api.domain.economics.ImplementCatalog;
import com.fermapp.api.domain.economics.Money;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldTask;
import com.fermapp.api.domain.geo.Geo;
import com.fermapp.api.domain.geo.Geo.Polygon;
import com.fermapp.api.domain.geo.Geo.Position;
import com.fermapp.api.domain.intelligence.Intelligence;
import com.fermapp.api.domain.intelligence.Intelligence.CostItem;
import com.fermapp.api.domain.intelligence.Intelligence.CostSummaryInput;
import com.fermapp.api.domain.intelligence.Intelligence.CropProgress;
import com.fermapp.api.domain.intelligence.Intelligence.NextActionInput;
import com.fermapp.api.domain.intelligence.Intelligence.RecentWork;
import com.fermapp.api.domain.intelligence.Intelligence.WeatherSummary;
import com.fermapp.api.domain.model.CostCategory;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.plan.ParcelPlanner;
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanInput;
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelReport;
import com.fermapp.api.domain.plan.ParcelPlanner.PlanClimate;
import com.fermapp.api.domain.plan.ParcelPlanner.PlanSoil;
import com.fermapp.api.domain.report.ReportBuilders;
import com.fermapp.api.domain.report.ReportBuilders.ReportMachine;
import com.fermapp.api.domain.report.ReportBuilders.ReportParcel;
import com.fermapp.api.domain.report.ReportBuilders.ReportRun;
import com.fermapp.api.domain.report.ReportBuilders.ReportSeason;
import com.fermapp.api.domain.report.ReportTable;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.soil.SoilRules;
import com.fermapp.api.domain.soil.SoilRules.SoilInput;
import com.fermapp.api.domain.tillage.Tillage;
import com.fermapp.api.domain.tillage.Tillage.TillageHistoryEntry;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import com.fermapp.api.domain.weather.WeatherRules;
import com.fermapp.api.domain.weather.WeatherRules.FieldOperation;
import com.fermapp.api.domain.weather.WeatherRules.ForecastHour;
import com.fermapp.api.domain.work.WorkMath;
import com.fermapp.api.domain.work.WorkMath.WorkParams;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the JSON of every domain rule, written by the production ObjectMapper. The file was
 * recorded from the Java implementation; the Kotlin port must reproduce it byte for byte.
 * Maps built with Map.of iterate in a random order per JVM, so they are sorted here.
 * Run with {@code -Dsnapshot.update=true} to record it again after an intended change.
 */
@SpringBootTest(classes = {JacksonAutoConfiguration.class, JacksonConfig.class})
class DomainSnapshotTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/snapshots/domain.json");
    private static final Instant NOW = Instant.parse("2026-05-20T08:30:00Z");
    private static TimeZone previousZone;

    @Autowired
    private ObjectMapper mapper;

    @BeforeAll
    static void fixZone() {
        // fieldStatus counts days in the server's zone; pin it so the snapshot is portable.
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Bucharest"));
    }

    @AfterAll
    static void restoreZone() {
        TimeZone.setDefault(previousZone);
    }

    private static final List<CropHistoryEntry> HISTORY = List.of(
            new CropHistoryEntry(CropCode.WHEAT, 2025), new CropHistoryEntry(CropCode.SOY, 2024),
            new CropHistoryEntry(CropCode.CORN, 2023), new CropHistoryEntry(CropCode.SUNFLOWER, 2022));

    private static Polygon square(double lng, double lat, double size) {
        return Polygon.of(List.of(List.of(new Position(lng, lat), new Position(lng + size, lat),
                new Position(lng + size, lat + size), new Position(lng, lat + size))));
    }

    @Test
    void domainOutputMatchesTheSnapshot() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        enumsAndCatalogs(out);
        cropsAndRotation(out);
        geoAndSoil(out);
        weather(out);
        intelligence(out);
        tillage(out);
        fieldStatus(out);
        plan(out);
        Map<String, Object> economics = economics(out);
        reports(out, economics);
        deserialization(out);

        String json = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out) + "\n";
        if (Boolean.getBoolean("snapshot.update") || !Files.exists(SNAPSHOT)) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, json, StandardCharsets.UTF_8);
        }
        String expected = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);
        if (!json.equals(expected)) {
            // Written next to the build output so the difference can be read with any diff tool.
            Files.writeString(Path.of("target/domain-snapshot-actual.json"), json, StandardCharsets.UTF_8);
        }
        assertThat(json).isEqualTo(expected);
    }

    private void enumsAndCatalogs(Map<String, Object> out) {
        out.put("cropCatalog", Crops.catalog());
        out.put("cropCodes", Crops.CROP_CODES);
        out.put("isCropCode", List.of(Crops.isCropCode("CORN"), Crops.isCropCode("corn"), Crops.isCropCode(null)));
        out.put("implements", ImplementCatalog.IMPLEMENTS);
        out.put("implementForSowing", ImplementCatalog.forOperation("SOWING").orElse(null));
        out.put("implementForHarvest", ImplementCatalog.forOperation("HARVESTING").isPresent());
        out.put("money", List.of(Money.formatEuro(1250.0), Money.formatEuro(12450.5), Money.formatEuro(-3.456, 2),
                Money.formatEuro(null), Money.formatEuro(Double.NaN), Money.formatEuro(1234567.0, 2), Money.formatEuro(0.004)));
        out.put("toEuro", List.of(Money.toEuro(497, "RON"), Money.toEuro(10, "EUR"), Money.toEuro(10, null), Money.toEuro(10, "USD")));
        out.put("workEstimate", WorkMath.estimateWork(18, new WorkParams(6, 8, 75), NOW));
        out.put("timedProgress", WorkMath.timedProgress(NOW, 3_600_000, 0, null, NOW.plusSeconds(1800)));
        out.put("irrigationEstimate", WorkMath.estimateIrrigation(640, 35, NOW));
        out.put("economicLabels", Economics.LABELS);
        out.put("recordedToEconomic", Economics.RECORDED_TO_ECONOMIC);
        out.put("verdictLabels", new java.util.TreeMap<>(Rotation.VERDICT_LABELS));
        out.put("fieldStateLabels", new java.util.TreeMap<>(FieldStatusRules.LABELS));
        out.put("operationLabels", WeatherRules.OPERATION_LABELS);
        out.put("cropSeasonDays", Intelligence.CROP_SEASON_DAYS);
        out.put("cropCalendars", ParcelPlanner.CROP_CALENDARS);
        out.put("tillageSystems", Tillage.SYSTEMS);
    }

    private void cropsAndRotation(Map<String, Object> out) {
        List<Object> rotations = new ArrayList<>();
        for (CropCode crop : CropCode.values()) {
            rotations.add(Rotation.evaluateRotation(crop, 2026, HISTORY));
        }
        rotations.add(Rotation.evaluateRotation(CropCode.CORN, 2026, List.of()));
        rotations.add(Rotation.evaluateRotation(CropCode.CORN, 2026, List.of(new CropHistoryEntry(CropCode.ALFALFA, 2025))));
        rotations.add(Rotation.evaluateRotation(CropCode.WHEAT, 2026, List.of(new CropHistoryEntry(CropCode.ALFALFA, 2025),
                new CropHistoryEntry(CropCode.WHEAT, 2024), new CropHistoryEntry(CropCode.WHEAT, 2023),
                new CropHistoryEntry(CropCode.WHEAT, 2022))));
        rotations.add(Rotation.evaluateRotation(CropCode.SUNFLOWER, 2026, List.of(new CropHistoryEntry(CropCode.RAPESEED, 2025),
                new CropHistoryEntry(CropCode.SUNFLOWER, 2021))));
        out.put("rotation", rotations);
        out.put("rankRotation", Rotation.rankRotationOptions(2026, HISTORY, List.of(CropCode.WHEAT, CropCode.CORN, CropCode.SOY)));

        out.put("recommendFull", CropRules.recommendCrops(2026, HISTORY, new SoilFit(6.8, SoilTexture.LOAMY),
                new ClimateFit(19.5, 540, 1600.0), null));
        out.put("recommendEmpty", CropRules.recommendCrops(2026, List.of(), null, null, List.of(CropCode.OAT, CropCode.POTATO)));
        out.put("recommendPoor", CropRules.recommendCrops(2026, HISTORY, new SoilFit(4.9, SoilTexture.SANDY),
                new ClimateFit(9.25, 280, null), List.of(CropCode.CORN, CropCode.ALFALFA, CropCode.BEET)));
        out.put("waterBalance", List.of(CropRules.waterBalance(CropCode.CORN, 320, 60, 30),
                CropRules.waterBalance(CropCode.CORN, 500, 100, 30), CropRules.waterBalance(CropCode.WHEAT, 380, 0, 25),
                CropRules.waterBalance(CropCode.SOY, -10, -5, 40)));
        out.put("dailyGdd", List.of(CropRules.dailyGdd(32, 18, 10), CropRules.dailyGdd(8, 2, 10), CropRules.dailyGdd(40, 25, 6, 30)));
        List<DailyTemperature> days = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            days.add(new DailyTemperature("2026-04-" + i, 20 + i % 9, 8 + i % 5));
        }
        out.put("development", List.of(CropRules.cropDevelopment(CropCode.CORN, days.subList(0, 10)),
                CropRules.cropDevelopment(CropCode.CORN, days), CropRules.cropDevelopment(CropCode.WHEAT, days),
                CropRules.cropDevelopment(CropCode.SOY, List.of())));
        out.put("vegetation", List.of(CropRules.vegetationHealth(0.82, 0.41), CropRules.vegetationHealth(0.45, null),
                CropRules.vegetationHealth(0.2, 0.05)));
    }

    private void geoAndSoil(Map<String, Object> out) {
        Polygon field = square(26.1, 44.4, 0.01);
        Polygon withHole = Polygon.of(List.of(field.coordinates().get(0), square(26.102, 44.402, 0.002).coordinates().get(0)));
        Polygon bowtie = Polygon.of(List.of(List.of(new Position(26, 44), new Position(26.01, 44.01),
                new Position(26.01, 44), new Position(26, 44.01))));
        out.put("geoMetrics", List.of(Geo.computeGeoMetrics(field), Geo.computeGeoMetrics(withHole)));
        out.put("geoValidation", java.util.Arrays.asList(Geo.validatePolygon(field), Geo.validatePolygon(bowtie),
                Geo.validatePolygon(Polygon.of(List.of())),
                Geo.validatePolygon(Polygon.of(List.of(List.of(new Position(26, 44), new Position(26.1, 44))))),
                Geo.validatePolygon(Polygon.of(List.of(List.of(new Position(200, 44), new Position(26.1, 44),
                        new Position(26.1, 44.1))))),
                Geo.validatePolygon(Polygon.of(List.of(List.of(new Position(26, 44), new Position(26.1, 44),
                        new Position(26.2, 44)))))));
        out.put("closeRing", List.of(Geo.closeRing(field.coordinates().get(0)), Geo.closeRing(List.of())));
        out.put("haversine", Geo.haversineM(new Position(26, 44), new Position(26.5, 44.5)));
        out.put("polygon", field);

        List<Object> soils = new ArrayList<>();
        soils.add(SoilRules.classifySoil(new SoilInput(7.1, 32, 25, 19, 38.0, 18.0)));
        soils.add(SoilRules.classifySoil(new SoilInput(5.4, 12, 70, 6, null, null)));
        soils.add(SoilRules.classifySoil(new SoilInput(8.5, 52, 10, 12, 30.0, 35.0)));
        soils.add(SoilRules.classifySoil(new SoilInput(6.5, 20, 80, 22, null, 10.0)));
        soils.add(SoilRules.classifySoil(new SoilInput(5.0, 22, 40, 20, null, null)));
        soils.add(SoilRules.classifySoil(new SoilInput(6.0, 22, 40, 8, null, null)));
        out.put("soil", soils);
        out.put("soilIndices", List.of(SoilRules.soilIndices(6.9, 3.2, 28, 30, 180), SoilRules.soilIndices(5.1, 0.8, 8, 78, 60),
                SoilRules.soilIndices(8.4, 5, 55, 5, 260)));
    }

    private static List<ForecastHour> forecast() {
        List<ForecastHour> hours = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            int day = 20 + i / 24;
            int hour = i % 24;
            String time = String.format("2026-05-%02dT%02d:00", day, hour);
            double temp = 9 + hour * 0.9 - (i > 40 ? 12 : 0);
            double wind = i < 3 ? 18 : 4 + (i % 7);
            double rain = i == 14 ? 1.2 : i == 30 ? 0.6 : 0;
            Double probability = i % 5 == 0 ? null : (double) (i % 11) * 6;
            Integer code = i == 20 ? Integer.valueOf(95) : i == 26 ? Integer.valueOf(96) : null;
            hours.add(new ForecastHour(time, i == 18 ? 36 : temp, 50 + (i % 30), rain, probability, wind, code));
        }
        return hours;
    }

    private void weather(Map<String, Object> out) {
        List<ForecastHour> hours = forecast();
        Map<String, Object> windows = new LinkedHashMap<>();
        for (FieldOperation operation : FieldOperation.values()) {
            List<WeatherRules.WorkWindow> found = WeatherRules.findWorkWindows(operation, hours, 2, null);
            windows.put(operation.name(), found);
            windows.put(operation.name() + "-message", WeatherRules.bestWindowMessage(operation, found));
        }
        windows.put("from", WeatherRules.findWorkWindows(FieldOperation.FERTILIZING, hours, 1, "2026-05-21T00:00"));
        windows.put("none", WeatherRules.bestWindowMessage(FieldOperation.HARVESTING, List.of()));
        windows.put("midnight", WeatherRules.findWorkWindows(FieldOperation.SOWING, List.of(
                new ForecastHour("2026-05-20T22:00", 18, 60, 0, 0.0, 9, null),
                new ForecastHour("2026-05-20T23:00", 18, 60, 0, 0.0, 9, null)), 1, null));
        out.put("windows", windows);
        out.put("alerts", WeatherRules.evaluateWeatherAlerts("North field", hours));
        out.put("alertsLongHorizon", WeatherRules.evaluateWeatherAlerts("North field", hours, 60));
        out.put("alertsEmpty", WeatherRules.evaluateWeatherAlerts("Empty", List.of()));
    }

    private void intelligence(Map<String, Object> out) {
        Instant sowing = Instant.parse("2026-04-05T09:00:00Z");
        CropProgress growing = Intelligence.cropProgress(CropCode.CORN, sowing, null, null, NOW);
        out.put("progress", List.of(growing,
                Intelligence.cropProgress(CropCode.WHEAT, null, null, Instant.parse("2026-07-10T00:00:00Z"), NOW),
                Intelligence.cropProgress(CropCode.WHEAT, NOW.plusSeconds(86400), null, null, NOW),
                Intelligence.cropProgress(CropCode.WHEAT, Instant.parse("2025-10-05T09:00:00Z"), null,
                        Instant.parse("2026-05-25T00:00:00Z"), NOW),
                Intelligence.cropProgress(CropCode.BARLEY, Instant.parse("2025-10-01T09:00:00Z"),
                        Instant.parse("2026-05-01T09:00:00Z"), null, NOW)));
        out.put("spraying", List.of(Intelligence.sprayingAlerts(List.of(new double[] {18, 20}, new double[] {5, 75})),
                Intelligence.sprayingAlerts(List.of()), Intelligence.sprayingAlerts(List.of(new double[] {4, 10}))));

        WeatherSummary humid = new WeatherSummary(82, 18, 9, 3, 12, 20);
        WeatherSummary dry = new WeatherSummary(40, 24, 12, 0, 1, 38);
        WeatherSummary frost = new WeatherSummary(60, 6, -2.6, 0, 0, 5);
        CropProgress early = new CropProgress(Intelligence.CropProgressStatus.GROWING, 10, 170, 6, "2026-09-20T09:00:00.000Z");
        CropProgress mid = new CropProgress(Intelligence.CropProgressStatus.GROWING, 60, 170, 35, "2026-09-20T09:00:00.000Z");
        CropProgress ready = new CropProgress(Intelligence.CropProgressStatus.READY, 165, 170, 97, "2026-09-20T09:00:00.000Z");
        CropProgress harvested = new CropProgress(Intelligence.CropProgressStatus.HARVESTED, 170, 170, 100, "2026-09-20T09:00:00.000Z");
        List<RecentWork> sprayed = List.of(new RecentWork("SPRAYING", NOW.minusSeconds(3 * 86400)));
        out.put("nextAction", List.of(
                Intelligence.nextRecommendedAction(new NextActionInput(null, null, null, null, null, null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.CORN, null, humid, null, null, null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.CORN, harvested, humid, 40.0, "GOOD", null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.CORN, ready, humid, 40.0, "GOOD", null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.CORN, early, frost, null, null, null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.CORN, mid, humid, 40.0, "PROBLEM", null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.CORN, mid, humid, 22.4, "", null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.CORN, mid, dry, null, null, null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.SUNFLOWER, mid, humid, 45.0, "GOOD", null, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.WHEAT, mid, humid, 45.0, "GOOD", sprayed, NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.SUNFLOWER, early, null, null, null, List.of(), NOW)),
                Intelligence.nextRecommendedAction(new NextActionInput(CropCode.SUNFLOWER, early, null, null, null, sprayed, NOW))));

        CostSummaryInput costs = new CostSummaryInput(12.5, List.of(new CostItem(CostCategory.SEED, 1830.456),
                new CostItem(CostCategory.FUEL, 700), new CostItem(CostCategory.SEED, 120.1)), 7.5, 190.0, 88.0, 205.0);
        out.put("costSummary", List.of(Intelligence.summarizeCosts(costs),
                Intelligence.summarizeCosts(new CostSummaryInput(0, List.of(), null, null, null, null))));
        out.put("parcelEconomics", List.of(Intelligence.parcelEconomics(costs, 0), Intelligence.parcelEconomics(costs, 15000),
                Intelligence.parcelEconomics(new CostSummaryInput(3, List.of(), null, 100.0, null, null), 0)));
    }

    private void tillage(Map<String, Object> out) {
        List<Object> estimates = new ArrayList<>();
        Map<String, Object> advice = new LinkedHashMap<>();
        for (TillageSystem system : TillageSystem.values()) {
            estimates.add(Tillage.estimateParcelTillage(system, 23.75));
            advice.put(system.name() + "-corn", Tillage.tillageRecommendations(system, CropCode.CORN));
            advice.put(system.name() + "-wheat", Tillage.tillageRecommendations(system, CropCode.WHEAT));
            advice.put(system.name() + "-none", Tillage.tillageRecommendations(system, null));
        }
        out.put("tillageEstimates", estimates);
        out.put("tillageAdvice", advice);
        List<TillageHistoryEntry> history = List.of(new TillageHistoryEntry(2024, TillageSystem.CONVENTIONAL),
                new TillageHistoryEntry(2025, TillageSystem.NO_TILL));
        out.put("tillageInYear", java.util.Arrays.asList(Tillage.tillageInYear(history, 2023), Tillage.tillageInYear(history, 2024),
                Tillage.tillageInYear(history, 2030)));
        out.put("withTillageYear", Tillage.withTillageYear(history, new TillageHistoryEntry(2024, TillageSystem.STRIP_TILL)));
        out.put("withTillageYearNew", Tillage.withTillageYear(history, new TillageHistoryEntry(2020, TillageSystem.MINIMUM_TILL)));
        out.put("tillageParse", java.util.Arrays.asList(Tillage.parse("NO_TILL"), Tillage.parse("no_till"), Tillage.parse(null)));
        out.put("tillageHistory", Tillage.parseTillageHistory(mapper.valueToTree(List.of(
                Map.of("year", 2025, "system", "NO_TILL"), Map.of("year", "2024", "system", "NO_TILL"),
                Map.of("year", 2023, "system", "MAGIC"), Map.of("year", 2022, "system", "STRIP_TILL")))));
        out.put("tillageHistoryNotArray", Tillage.parseTillageHistory(mapper.valueToTree(Map.of("a", 1))));
    }

    private static final List<FieldTask> TASKS = List.of(
            new FieldTask("t1", "Ploughing", "SOIL_PREP", "TODO", "2026-05-15T09:00:00.000Z", "p1"),
            new FieldTask("t2", "Sowing", "SOWING", "IN_PROGRESS", "2026-05-20T06:00:00.000Z", "p1"),
            new FieldTask("t3", "Herbicide", "HERBICIDE", "TODO", "2026-05-24T09:00:00.000Z", "p1"),
            new FieldTask("t4", "Harvest", "HARVEST", "TODO", "2026-09-20T09:00:00.000Z", "p1"),
            new FieldTask("t5", "Base", "FERTILIZATION", "DONE", "2026-05-01T09:00:00.000Z", "p1"),
            new FieldTask("t6", "Check", "PEST_CHECK", "TODO", "2026-05-21T09:00:00.000Z", "p2"),
            new FieldTask("t7", "Old", "HARVEST", "DONE", "2025-08-01T09:00:00.000Z", "p2"),
            new FieldTask("t8", "Today", "PEST_CHECK", "TODO", "2026-05-20T12:00:00.000Z", "p3"));

    private record Open(String status, String type, Instant dueDate) implements FieldStatusRules.OpenTask {
    }

    private void fieldStatus(Map<String, Object> out) {
        Map<String, Object> statuses = new LinkedHashMap<>();
        statuses.put("overdue", FieldStatusRules.fieldStatus(TASKS.stream().filter(t -> t.parcelId().equals("p1")).toList(), 0, NOW));
        statuses.put("tomorrow", FieldStatusRules.fieldStatus(TASKS.stream().filter(t -> t.parcelId().equals("p2")).toList(), 0, NOW));
        statuses.put("today", FieldStatusRules.fieldStatus(TASKS.stream().filter(t -> t.parcelId().equals("p3")).toList(), 0, NOW));
        statuses.put("active", FieldStatusRules.fieldStatus(TASKS.stream().filter(t -> t.parcelId().equals("p3")).toList(), 1, NOW));
        statuses.put("activeIdle", FieldStatusRules.fieldStatus(List.of(), 2, NOW));
        statuses.put("complete", FieldStatusRules.fieldStatus(List.of(), 0, NOW));
        statuses.put("scheduled", FieldStatusRules.fieldStatus(List.of(TASKS.get(2), TASKS.get(5)), 0, NOW));
        out.put("fieldStatus", statuses);
        out.put("workToTaskTypes", new java.util.TreeMap<>(FieldStatusRules.WORK_TO_TASK_TYPES));
        List<Open> open = List.of(new Open("TODO", "SOWING", NOW.plusSeconds(10 * 86400)),
                new Open("TODO", "SOWING", NOW.plusSeconds(2 * 86400)), new Open("DONE", "SOWING", NOW.minusSeconds(86400)),
                new Open("TODO", "HERBICIDE", NOW), new Open("IN_PROGRESS", "FUNGICIDE", NOW.minusSeconds(4 * 86400)));
        out.put("taskForWork", java.util.Arrays.asList(
                FieldStatusRules.taskForFinishedWork(open, "SOWING", NOW).orElse(null),
                FieldStatusRules.taskForFinishedWork(open, "SPRAYING", NOW).orElse(null),
                FieldStatusRules.taskForFinishedWork(open, "HARVESTING", NOW).orElse(null),
                FieldStatusRules.taskForFinishedWork(open, "UNKNOWN", NOW).orElse(null)));
    }

    private void plan(Map<String, Object> out) {
        PlanSoil soil = new PlanSoil(6.4, SoilTexture.LOAMY, "Chernozem (loam)");
        PlanClimate climate = new PlanClimate(18.4, 520, 1550.0);
        Map<String, Object> plans = new LinkedHashMap<>();
        plans.put("corn", ParcelPlanner.buildParcelPlan(new ParcelPlanInput(CropCode.CORN, "North field", 23.456,
                HISTORY, null, soil, climate, NOW, null, null)));
        plans.put("wheatNoData", ParcelPlanner.buildParcelPlan(new ParcelPlanInput(CropCode.WHEAT, "South", 8,
                List.of(), "Tomatoes", null, null, NOW, null, null)));
        plans.put("soyRecorded", ParcelPlanner.buildParcelPlan(new ParcelPlanInput(CropCode.SOY, "East", 12,
                List.of(new CropHistoryEntry(CropCode.SOY, 2025), new CropHistoryEntry(CropCode.ALFALFA, 2024)),
                null, new PlanSoil(4.8, SoilTexture.SANDY, "Luvisol (sandy)"), new PlanClimate(16, 900, null),
                NOW, 2026, null)));
        plans.put("sunflowerSown", ParcelPlanner.buildParcelPlan(new ParcelPlanInput(CropCode.SUNFLOWER, "West", 30,
                HISTORY, null, soil, new PlanClimate(21, 300, null), NOW, null, Instant.parse("2026-04-12T09:00:00Z"))));
        plans.put("rapeseedHarvestYear", ParcelPlanner.buildParcelPlan(new ParcelPlanInput(CropCode.RAPESEED, "Hill", 5,
                HISTORY, null, soil, climate, Instant.parse("2026-09-10T08:00:00Z"), 2027, null)));
        out.put("plans", plans);
        out.put("nextSeasonYear", List.of(ParcelPlanner.nextSeasonYear(NOW),
                ParcelPlanner.nextSeasonYear(Instant.parse("2026-08-01T00:00:00Z"))));
        out.put("planCalendar", ParcelPlanner.planCalendar(CropCode.BEET, NOW, null, null));
        out.put("rankCrops", ParcelPlanner.rankCropsForParcel(2026, HISTORY, soil, null));
        out.put("operatorReport", new ParcelReport("North field", 23.46, null, null, "Corn", CropCode.SOY, "Soybean", 88, 74,
                8.1, null, null, null, List.of("Drought."), "Irrigate.", List.of()));
    }

    private Map<String, Object> economics(Map<String, Object> out) throws Exception {
        JsonNode sheetJson = mapper.readTree("""
                {"seeds":[{"id":"s1","supplier":"Pioneer","hybrid":"P9911","unit":"SEEDS","bagSize":80000,"ratePerHa":72000,
                           "pricePerBag":245},
                          {"supplier":"Local","hybrid":"Glosa","category":"C1","unit":"KG","bagSize":25,"ratePerHa":230,
                           "pricePerBag":18.5,"bagsUsed":95},
                          "not an object"],
                 "fertilizers":[{"id":"f1","product":"NPK 20-20-0","ratePerHaKg":200,"pricePerT":610,"applications":1,"n":20,"p":20},
                                {"id":"f2","product":"Urea","ratePerHaKg":150,"pricePerT":540,"applications":2,"n":46}],
                 "chemicals":[{"id":"c1","product":"Adengo","kind":"HERBICIDE","dosePerHa":0.44,"unit":"L","pricePerUnit":160},
                              {"id":"c2","product":"Karate","kind":"MAGIC","dosePerHa":0.15,"unit":"KG","pricePerUnit":95,
                               "applications":2}],
                 "misc":[{"id":"m1","label":"Insurance","amount":35,"basis":"PER_HA"},{"label":"Transport","amount":900}],
                 "revenue":{"expectedYieldTHa":9.2,"pricePerT":190,"actualYieldTHa":8.7}}
                """);
        JsonNode settingsJson = mapper.readTree("""
                {"fuelPricePerL":1.52,"defaultLaborRate":11,
                 "labor":{"u1":{"monthlySalary":1400},"u2":{"hourlyRate":13.5},"u3":"x"},
                 "machines":{"m1":{"purchasePrice":120000,"residualPct":25,"depreciationYears":12,"annualHours":600,
                                   "serviceCostPerYear":1800,"maintenanceCostPerYear":2400},
                             "m2":{"purchasePrice":50000,"annualHours":0}}}
                """);
        EconomicSheet sheet = Economics.parseSheet(sheetJson);
        EconomicSettings settings = Economics.parseSettings(settingsJson);
        out.put("sheet", sheet);
        out.put("settings", settings);
        out.put("emptySheet", Economics.parseSheet(null));
        out.put("emptySettings", Economics.parseSettings(mapper.readTree("[]")));

        List<OperationInput> ops = List.of(
                new OperationInput("o1", "PLOWING", "COMPLETED", 23.4, 100, 9.5, null,
                        new OperationMachine("m1", "Fendt 724", "TRACTOR", 18.0), new OperationOperator("u1", "Ion"),
                        "2026-03-20T07:00:00.000Z"),
                new OperationInput("o2", "SOWING", "RUNNING", 23.4, 40, 3, 55.0,
                        new OperationMachine("m2", "Seeder", "SEEDER", null), new OperationOperator("u2", "Ana"),
                        "2026-04-05T07:00:00.000Z"),
                new OperationInput("o3", "SPRAYING", "CANCELLED", 23.4, 0, 0, null, null, null, "2026-04-10T07:00:00.000Z"),
                new OperationInput("o4", "DISCING", "CANCELLED", 23.4, 30, 1.2, null, null, new OperationOperator("u9", "X"),
                        "2026-03-25T07:00:00.000Z"));
        List<RecordedCost> recorded = List.of(new RecordedCost(CostCategory.IRRIGATION, 420), new RecordedCost(CostCategory.SEED, 80.55));
        ParcelEconomicsResult corn = Economics.computeParcelEconomics(23.4, sheet, ops, settings, TillageSystem.MINIMUM_TILL,
                CropCode.CORN, recorded);
        ParcelEconomicsResult bare = Economics.computeParcelEconomics(10, Economics.parseSheet(null), List.of(), settings, null, null, null);
        ParcelEconomicsResult loss = Economics.computeParcelEconomics(4, Economics.parseSheet(mapper.readTree("""
                {"misc":[{"label":"Rent","amount":5000}],"revenue":{"expectedYieldTHa":3,"pricePerT":150}}
                """)), List.of(), settings, TillageSystem.NO_TILL, CropCode.WHEAT, List.of());
        out.put("parcelResult", corn);
        out.put("bareResult", bare);
        out.put("lossResult", loss);
        out.put("insights", new java.util.TreeMap<>(Map.of("corn", Economics.economicInsights(corn, TillageSystem.MINIMUM_TILL),
                "bare", Economics.economicInsights(bare, null), "loss", Economics.economicInsights(loss, TillageSystem.NO_TILL),
                "conventional", Economics.economicInsights(corn, TillageSystem.CONVENTIONAL))));
        out.put("fuelPerHaFor", List.of(Economics.fuelPerHaFor("PLOWING", null), Economics.fuelPerHaFor("SOWING", TillageSystem.STRIP_TILL),
                Economics.fuelPerHaFor("HARVESTING", TillageSystem.NO_TILL), Economics.fuelPerHaFor("X", null)));
        out.put("machineCost", List.of(Economics.machineHourlyCost(null, "COMBINE"), Economics.machineHourlyCost(null, null),
                Economics.machineHourlyCost(settings.machines().get("m1"), "TRACTOR")));
        out.put("laborRate", List.of(Economics.laborHourlyRate(settings.labor().get("u1"), 9),
                Economics.laborHourlyRate(settings.labor().get("u2"), 9), Economics.laborHourlyRate(null, 9)));
        out.put("seasonOperations", Economics.seasonOperations(sheet, TillageSystem.CONVENTIONAL, CropCode.CORN));

        List<FarmParcelEconomics> farm = List.of(
                new FarmParcelEconomics("p1", "North field", CropCode.CORN, "Corn", TillageSystem.MINIMUM_TILL, corn),
                new FarmParcelEconomics("p2", "Bare", null, null, null, bare),
                new FarmParcelEconomics("p3", "Hill", CropCode.WHEAT, "Wheat", TillageSystem.NO_TILL, loss));
        FarmEconomics summary = Economics.summarizeFarmEconomics(farm);
        out.put("farmEconomics", summary);
        out.put("farmEconomicsEmpty", Economics.summarizeFarmEconomics(List.of()));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("farm", farm);
        context.put("summary", summary);
        return context;
    }

    @SuppressWarnings("unchecked")
    private void reports(Map<String, Object> out, Map<String, Object> economics) {
        List<ReportParcel> parcels = List.of(
                new ReportParcel("p1", "North field", "NF-1", 23.456, "Ialomita", CropCode.CORN, TillageSystem.MINIMUM_TILL),
                new ReportParcel("p2", "Bare", "B-2", 10, null, null, null),
                new ReportParcel("p3", "Hill", "H-3", 4, "Buzau", CropCode.WHEAT, TillageSystem.NO_TILL));
        List<ReportRun> runs = List.of(
                new ReportRun("p1", "m1", "PLOWING", "COMPLETED", 23.456, 100, 9.5, 180.0, "2026-03-20T07:00:00.000Z"),
                new ReportRun("p1", "m2", "SOWING", "RUNNING", 23.456, 40, 3, null, "2026-04-05T07:00:00.000Z"),
                new ReportRun("p3", "m1", "DISCING", "CANCELLED", 4, 30, 1, 12.0, "2026-03-25T07:00:00.000Z"),
                new ReportRun("p3", "m1", "HARVESTING", "COMPLETED", 4, 120, 2.25, 40.5, "2025-07-10T07:00:00.000Z"));
        List<ReportMachine> machines = List.of(new ReportMachine("m1", "Fendt 724", "TRACTOR", "Ion"),
                new ReportMachine("m2", "Seeder", "SEEDER", null), new ReportMachine("m3", "Old", "PLANE", null));
        List<ReportSeason> seasons = List.of(new ReportSeason("p1", CropCode.CORN, 190.4), new ReportSeason("p3", CropCode.WHEAT, null),
                new ReportSeason("p2", CropCode.CORN, 61.0), new ReportSeason("missing", CropCode.SOY, 5.0));

        Map<String, Object> reports = new LinkedHashMap<>();
        ReportTable parcelsReport = ReportBuilders.parcelsReport(2026, parcels, TASKS, runs, NOW);
        reports.put("parcels", parcelsReport);
        reports.put("parcelsEmpty", ReportBuilders.parcelsReport(2026, List.of(), List.of(), List.of(), NOW));
        reports.put("machines", ReportBuilders.machinesReport(2026, machines, runs, id -> "Parcel " + id));
        reports.put("crops", ReportBuilders.cropsReport(2026, parcels, seasons));
        reports.put("cropsEmpty", ReportBuilders.cropsReport(2026, parcels, List.of()));
        reports.put("profitability", ReportBuilders.profitabilityReport(2026, (List<FarmParcelEconomics>) economics.get("farm"),
                (FarmEconomics) economics.get("summary")));
        ReportTable farmReport = ReportBuilders.farmReport(2026, "Demo farm", parcels, runs, TASKS,
                (FarmEconomics) economics.get("summary"), NOW);
        reports.put("farm", farmReport);
        reports.put("farmNoEconomics", ReportBuilders.farmReport(2026, "Demo farm", parcels, runs, TASKS, null, NOW));
        reports.put("chartPointsParcels", ReportBuilders.reportChartPoints(parcelsReport, 2));
        reports.put("chartPointsFarm", ReportBuilders.reportChartPoints(farmReport, 10));
        reports.put("chartPointsText", ReportBuilders.reportChartPoints(new ReportTable("T", "P",
                List.of(ReportTable.Column.text("a", "A", 1)),
                List.of(Map.of("a", "x", "b", "12.5"), Map.of("a", "y", "b", "abc"), Map.of("a", "z", "b", " ")),
                null, null, new ReportTable.Chart("C", "a", "b", null, null, null), null), 5));
        reports.put("chartPointsNone", ReportBuilders.reportChartPoints(farmReport.chart() == null ? farmReport
                : new ReportTable("T", "P", List.of(), List.of(), null, null, null, null), 5));
        reports.put("columnFlags", List.of(ReportTable.Column.money("a", "A", 1).isMoney(), ReportTable.Column.text("a", "A", 1).isNumeric()));
        out.put("reports", reports);
    }

    private void deserialization(Map<String, Object> out) throws Exception {
        out.put("polygonWithoutType", mapper.readValue("{\"coordinates\":[[[26,44.7],[26.01,44.7],[26.01,44.71]]]}", Polygon.class));
        out.put("polygonWrongType", mapper.readValue("{\"type\":\"Point\",\"coordinates\":[[[26,44.7]]]}", Polygon.class));
        out.put("polygonNoCoordinates", mapper.readValue("{\"type\":\"Polygon\"}", Polygon.class));
        out.put("polygonFromMap", mapper.convertValue(Map.of("type", "Polygon",
                "coordinates", List.of(List.of(List.of(26, 44), List.of(26.5, 44)))), Polygon.class));
        out.put("cachedForecast", mapper.readValue("""
                [{"time":"2026-05-20T10:00","temperatureC":18,"humidityPct":60,"precipitationMm":0,
                  "precipitationProbabilityPct":null,"windKmh":9,"weatherCode":null},
                 {"time":"2026-05-20T11:00","temperatureC":19.5,"humidityPct":58,"precipitationMm":0.2,
                  "precipitationProbabilityPct":35,"windKmh":11,"weatherCode":95},
                 {"time":"2026-05-20T12:00","temperatureC":20,"humidityPct":55,"precipitationMm":0,"windKmh":12}]
                """, new TypeReference<List<ForecastHour>>() { }));
    }
}

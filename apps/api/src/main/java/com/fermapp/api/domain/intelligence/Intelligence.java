package com.fermapp.api.domain.intelligence;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.model.CostCategory;
import com.fermapp.api.domain.model.CropCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parcel intelligence rules (intelligence.ts) and cost summaries (costs.ts). */
public final class Intelligence {

    /** Indicative duration from sowing to harvest, in days. */
    public static final Map<CropCode, Integer> CROP_SEASON_DAYS = new EnumMap<>(Map.ofEntries(
            Map.entry(CropCode.WHEAT, 270), Map.entry(CropCode.BARLEY, 250), Map.entry(CropCode.CORN, 170),
            Map.entry(CropCode.SUNFLOWER, 125), Map.entry(CropCode.RAPESEED, 300), Map.entry(CropCode.SOY, 130),
            Map.entry(CropCode.ALFALFA, 180), Map.entry(CropCode.OAT, 110), Map.entry(CropCode.POTATO, 120),
            Map.entry(CropCode.BEET, 190), Map.entry(CropCode.VEGETABLES, 100)));

    public static final double SPRAY_WIND_LIMIT_KMH = 15;
    public static final double SPRAY_RAIN_LIMIT_PCT = 60;
    private static final List<CropCode> NITROGEN_CROPS =
            List.of(CropCode.WHEAT, CropCode.BARLEY, CropCode.CORN, CropCode.RAPESEED, CropCode.OAT);
    private static final double LOW_SOIL_MOISTURE_PCT = 30;
    private static final double WATER_DEFICIT_MM = 20;

    public enum CropProgressStatus { NOT_SOWN, GROWING, READY, HARVESTED }

    public record CropProgress(CropProgressStatus status, int vegetationDay, int totalDays, int percent, String estimatedHarvestDate) {
    }

    public record SprayAlert(String code, String message) {
    }

    public enum ActionPriority { LOW, MEDIUM, HIGH }

    public record NextAction(String code, String action, String reason, ActionPriority priority,
                             ActionPriority riskLevel, int confidencePct, Integer dueInDays) {
    }

    /** Weekly weather summary used by the next-action rules. */
    public record WeatherSummary(double meanHumidityPct, double meanTempC, double minTempC, int rainyDays,
                                 double precipitationMm, double et0Mm) {
    }

    public record RecentWork(String type, Instant date) {
    }

    public record NextActionInput(CropCode crop, CropProgress progress, WeatherSummary weather, Double soilMoisturePct,
                                  String healthClass, List<RecentWork> recentWork, Instant now) {
    }

    public record CostItem(CostCategory category, double amount) {
    }

    public record CostSummaryInput(double areaHa, List<CostItem> costs, Double expectedYieldTHa, Double expectedPricePerT,
                                   Double actualYieldT, Double actualPricePerT) {
    }

    public record CostSummary(double totalCost, double costPerHa, Map<CostCategory, Double> byCategory,
                              Double estimatedRevenue, Double estimatedProfit, Double actualRevenue, Double actualProfit) {
    }

    public record ParcelEconomics(double seedsCost, double fertilizerCost, double chemicalCost, double fuelCost,
                                  double laborCost, double irrigationCost, double otherCost, double totalCost,
                                  double costPerHa, Double expectedIncome, Double expectedProfit, Double profitMarginPct,
                                  Double roiPct, Double actualIncome, Double actualProfit) {
    }

    private Intelligence() {
    }

    public static CropProgress cropProgress(CropCode crop, Instant sowingDate, Instant harvestDate, Instant expectedHarvestDate, Instant now) {
        int defaultDays = CROP_SEASON_DAYS.get(crop);
        if (sowingDate == null || sowingDate.isAfter(now)) {
            return new CropProgress(CropProgressStatus.NOT_SOWN, 0, defaultDays, 0, Times.iso(expectedHarvestDate));
        }
        Instant estimated = harvestDate != null ? harvestDate
                : expectedHarvestDate != null ? expectedHarvestDate
                : sowingDate.plusMillis(defaultDays * Times.DAY_MS);
        int totalDays = (int) Math.max(1, Math.round((double) (estimated.toEpochMilli() - sowingDate.toEpochMilli()) / Times.DAY_MS));
        Instant reference = harvestDate != null && harvestDate.isBefore(now) ? harvestDate : now;
        int vegetationDay = (int) Math.min(totalDays,
                Math.floor((double) (reference.toEpochMilli() - sowingDate.toEpochMilli()) / Times.DAY_MS) + 1);
        int percent = (int) Math.round((double) vegetationDay / totalDays * 100);
        CropProgressStatus status = harvestDate != null && !harvestDate.isAfter(now) ? CropProgressStatus.HARVESTED
                : percent >= 95 ? CropProgressStatus.READY : CropProgressStatus.GROWING;
        return new CropProgress(status, vegetationDay, totalDays, percent, Times.iso(estimated));
    }

    /** Spraying conditions for the next few hours. */
    public static List<SprayAlert> sprayingAlerts(List<double[]> windAndRainProbability) {
        List<SprayAlert> alerts = new ArrayList<>();
        double wind = windAndRainProbability.isEmpty() ? 0 : windAndRainProbability.get(0)[0];
        if (wind > SPRAY_WIND_LIMIT_KMH) {
            alerts.add(new SprayAlert("WIND", "Wind " + Math.round(wind) + " km/h. Do not spray."));
        }
        double rain = 0;
        for (double[] hour : windAndRainProbability) {
            rain = Math.max(rain, hour[1]);
        }
        if (rain > SPRAY_RAIN_LIMIT_PCT) {
            alerts.add(new SprayAlert("RAIN", "Rain probability " + Math.round(rain) + "%. Postpone spraying."));
        }
        return alerts;
    }

    /** Picks the next action using agronomic rules, from most to least urgent. */
    public static NextAction nextRecommendedAction(NextActionInput input) {
        CropCode crop = input.crop();
        CropProgress progress = input.progress();
        WeatherSummary weather = input.weather();
        Double soilMoisture = input.soilMoisturePct();
        String healthClass = input.healthClass();
        List<RecentWork> recentWork = input.recentWork() == null ? List.of() : input.recentWork();
        Instant now = input.now() == null ? Times.now() : input.now();
        int confidence = 50 + (weather != null ? 15 : 0)
                + (progress != null && progress.status() != CropProgressStatus.NOT_SOWN ? 15 : 0)
                + (soilMoisture != null ? 10 : 0)
                + (healthClass != null && !healthClass.isEmpty() ? 5 : 0);

        java.util.function.BiPredicate<String, Integer> workedWithin = (type, days) -> recentWork.stream()
                .anyMatch(work -> work.type().equals(type) && now.toEpochMilli() - work.date().toEpochMilli() <= days * Times.DAY_MS);

        if (crop == null) {
            return new NextAction("PLAN_CROP", "Plan the crop for this parcel.",
                    "The parcel has no crop recorded for the current year.", ActionPriority.MEDIUM, ActionPriority.LOW, confidence, null);
        }
        String name = Crops.name(crop).toLowerCase();
        if (progress == null || progress.status() == CropProgressStatus.NOT_SOWN) {
            return new NextAction("SET_SOWING", "Fill in the sowing date.",
                    "Without this date the stage of the " + name + " crop cannot be estimated.",
                    ActionPriority.MEDIUM, ActionPriority.LOW, confidence, null);
        }
        if (progress.status() == CropProgressStatus.HARVESTED) {
            return new NextAction("POST_HARVEST", "Prepare the land for the next crop.",
                    "The " + name + " crop has been harvested.", ActionPriority.LOW, ActionPriority.LOW, confidence, 14);
        }
        if (progress.status() == CropProgressStatus.READY) {
            return new NextAction("HARVEST", "Prepare for harvest.",
                    "The " + name + " crop has reached " + progress.percent() + "% of its estimated cycle. Check grain moisture.",
                    ActionPriority.HIGH, ActionPriority.MEDIUM, confidence, 7);
        }
        if (weather != null && weather.minTempC() <= 0 && progress.percent() < 30) {
            return new NextAction("FROST", "Check the plants after the expected frost.",
                    Math.round(weather.minTempC()) + " °C is expected and the crop is at a sensitive stage.",
                    ActionPriority.HIGH, ActionPriority.HIGH, confidence, 2);
        }
        if ("PROBLEM".equals(healthClass)) {
            return new NextAction("SCOUT", "Inspect the parcel in the field.",
                    "The latest satellite image indicates a possible vegetation problem.",
                    ActionPriority.HIGH, ActionPriority.HIGH, confidence, 2);
        }
        double waterDeficit = weather != null ? weather.et0Mm() - weather.precipitationMm() : 0;
        boolean lowMoisture = soilMoisture != null && soilMoisture < LOW_SOIL_MOISTURE_PCT;
        if ((lowMoisture || waterDeficit > WATER_DEFICIT_MM) && progress.percent() >= 15 && progress.percent() <= 85) {
            return new NextAction("IRRIGATE", "Plan an irrigation within the next 3 days.",
                    lowMoisture
                            ? "Soil moisture is " + Math.round(soilMoisture) + "%."
                            : "Evapotranspiration exceeds rainfall by " + Math.round(waterDeficit) + " mm over the next 7 days.",
                    ActionPriority.HIGH, ActionPriority.MEDIUM, confidence, 3);
        }
        boolean foliarRisk = weather != null && weather.meanHumidityPct() >= 75 && weather.meanTempC() >= 12
                && weather.meanTempC() <= 28 && weather.rainyDays() >= 2;
        if (foliarRisk && progress.percent() >= 30 && progress.percent() <= 80 && !workedWithin.test("SPRAYING", 10)) {
            return new NextAction("FUNGICIDE", "Check for foliar diseases and prepare a fungicide treatment within 4 days.",
                    "Mean humidity of " + Math.round(weather.meanHumidityPct()) + "% and " + weather.rainyDays()
                            + " rainy days raise the risk of foliar diseases.",
                    ActionPriority.HIGH, ActionPriority.HIGH, confidence, 4);
        }
        if (NITROGEN_CROPS.contains(crop) && progress.percent() >= 15 && progress.percent() <= 45 && !workedWithin.test("FERTILIZING", 30)) {
            return new NextAction("NITROGEN", "Plan a nitrogen fertilization.",
                    "The " + name + " crop is actively growing and no fertilization has been recorded in the last 30 days.",
                    ActionPriority.MEDIUM, ActionPriority.MEDIUM, confidence, 7);
        }
        if (progress.percent() <= 25 && !workedWithin.test("SPRAYING", 21)) {
            return new NextAction("WEEDS", "Check the level of weed infestation.",
                    "In the early stages, weeds compete strongly with the crop for water and nutrients.",
                    ActionPriority.MEDIUM, ActionPriority.MEDIUM, confidence, 5);
        }
        return new NextAction("MONITOR", "Keep monitoring the crop.",
                "The available data does not indicate an urgent intervention.", ActionPriority.LOW, ActionPriority.LOW, confidence, 7);
    }

    public static CostSummary summarizeCosts(CostSummaryInput input) {
        Map<CostCategory, Double> byCategory = new LinkedHashMap<>();
        for (CostCategory category : CostCategory.values()) {
            byCategory.put(category, 0.0);
        }
        double total = 0;
        for (CostItem cost : input.costs()) {
            byCategory.merge(cost.category(), cost.amount(), Double::sum);
            total += cost.amount();
        }
        byCategory.replaceAll((category, value) -> Js.round2(value));
        double totalCost = Js.round2(total);
        Double estimatedRevenue = input.expectedYieldTHa() != null && input.expectedPricePerT() != null
                ? Js.round2(input.expectedYieldTHa() * input.areaHa() * input.expectedPricePerT()) : null;
        Double actualRevenue = input.actualYieldT() != null && input.actualPricePerT() != null
                ? Js.round2(input.actualYieldT() * input.actualPricePerT()) : null;
        return new CostSummary(
                totalCost,
                input.areaHa() > 0 ? Js.round2(totalCost / input.areaHa()) : 0,
                byCategory,
                estimatedRevenue,
                estimatedRevenue == null ? null : Js.round2(estimatedRevenue - totalCost),
                actualRevenue,
                actualRevenue == null ? null : Js.round2(actualRevenue - totalCost));
    }

    public static ParcelEconomics parcelEconomics(CostSummaryInput input, double recordedIncome) {
        CostSummary summary = summarizeCosts(input);
        Double expectedIncome = summary.estimatedRevenue();
        Double expectedProfit = summary.estimatedProfit();
        Double actualIncome = recordedIncome != 0 ? Double.valueOf(recordedIncome) : summary.actualRevenue();
        Map<CostCategory, Double> c = summary.byCategory();
        return new ParcelEconomics(
                c.get(CostCategory.SEED), c.get(CostCategory.FERTILIZER), c.get(CostCategory.PESTICIDE),
                c.get(CostCategory.FUEL), c.get(CostCategory.LABOR), c.get(CostCategory.IRRIGATION), c.get(CostCategory.OTHER),
                summary.totalCost(), summary.costPerHa(), expectedIncome, expectedProfit,
                expectedIncome != null && expectedIncome != 0 && expectedProfit != null
                        ? Js.round1(expectedProfit / expectedIncome * 100) : null,
                summary.totalCost() > 0 && expectedProfit != null ? Js.round1(expectedProfit / summary.totalCost() * 100) : null,
                actualIncome,
                actualIncome == null ? null : Js.round2(actualIncome - summary.totalCost()));
    }
}

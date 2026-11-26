package com.fermapp.api.domain.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.crops.CropRules;
import com.fermapp.api.domain.crops.CropRules.ClimateFit;
import com.fermapp.api.domain.crops.CropRules.CropRecommendation;
import com.fermapp.api.domain.crops.CropRules.SoilFit;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.crops.Crops.CropFamily;
import com.fermapp.api.domain.crops.Crops.CropProfile;
import com.fermapp.api.domain.crops.Crops.SoilTexture;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.rotation.Rotation.RotationResult;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The technology plan of a parcel for one agricultural year (parcelPlan.ts). */
public final class ParcelPlanner {

    private static final int MIN_DAYS_BEFORE_SOWING = 7;
    private static final int IRRIGATION_DOSE_MM = 30;
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"};

    /** The last crop declared in the wizard can be a catalog crop or OTHER. */
    public static final String OTHER_CROP = "OTHER";

    /**
     * The crop calendar, in days from sowing.
     *
     * @param topDressingDays missing for crops that do not receive top-dressing nitrogen
     * @param criticalFrom first month when water shortage affects yield the most
     * @param rainShare share of annual rainfall available to the crop
     * @param nutrientsPerT N, P2O5 and K2O requirement, in kg per tonne of yield
     */
    public record CropCalendar(int sowingMonth, int sowingDay, int emergenceDays, Integer topDressingDays, int diseaseDays,
                               int pestDays, int harvestDays, int criticalFrom, int criticalTo, double rainShare,
                               double priceEurPerT, double costEurHa, double[] nutrientsPerT) {
    }

    /** Indicative values for conditions in Romania. */
    public static final Map<CropCode, CropCalendar> CROP_CALENDARS = new EnumMap<>(Map.ofEntries(
            Map.entry(CropCode.WHEAT, new CropCalendar(10, 5, 12, 165, 205, 225, 275, 4, 5, 0.8, 200, 800, new double[] {25, 10, 6})),
            Map.entry(CropCode.BARLEY, new CropCalendar(9, 28, 12, 165, 195, 215, 270, 4, 5, 0.8, 180, 700, new double[] {24, 10, 7})),
            Map.entry(CropCode.CORN, new CropCalendar(4, 5, 12, 40, 66, 118, 193, 7, 8, 0.6, 190, 1000, new double[] {23, 10, 7})),
            Map.entry(CropCode.SUNFLOWER, new CropCalendar(4, 1, 12, 35, 75, 60, 150, 6, 7, 0.6, 430, 650, new double[] {40, 18, 12})),
            Map.entry(CropCode.RAPESEED, new CropCalendar(8, 28, 8, 190, 225, 205, 305, 4, 5, 0.8, 460, 850, new double[] {50, 25, 12})),
            Map.entry(CropCode.SOY, new CropCalendar(4, 25, 12, null, 70, 85, 145, 7, 8, 0.6, 400, 650, new double[] {0, 15, 12})),
            Map.entry(CropCode.ALFALFA, new CropCalendar(4, 1, 10, null, 40, 50, 75, 6, 8, 0.7, 130, 500, new double[] {0, 6, 20})),
            Map.entry(CropCode.OAT, new CropCalendar(3, 15, 14, 40, 70, 80, 125, 5, 6, 0.6, 170, 550, new double[] {25, 10, 8})),
            Map.entry(CropCode.POTATO, new CropCalendar(4, 5, 20, 35, 60, 50, 130, 6, 7, 0.6, 200, 4000, new double[] {4, 1.5, 6})),
            Map.entry(CropCode.BEET, new CropCalendar(3, 25, 14, 45, 100, 60, 195, 7, 8, 0.65, 40, 1800, new double[] {2, 1, 2.5})),
            Map.entry(CropCode.VEGETABLES, new CropCalendar(4, 20, 10, 35, 50, 40, 100, 6, 8, 0.55, 350, 6000, new double[] {3, 1.5, 4}))));

    /** One dated step of the plan. {@code past}: the deadline has passed, so the step no longer becomes a task. */
    public record PlanStep(String key, String title, String taskType, String date, String priority, boolean past) {
    }

    public record PlanSchedule(String sowingDate, String harvestDate, int seasonYear, List<PlanStep> steps) {
    }

    public record PlanSoil(double ph, SoilTexture texture, String label) {
    }

    public record PlanClimate(double seasonTempC, double annualPrecipitationMm, Double gddBase10) {
    }

    /** A crop recommendation with the reason for the score in a single sentence. */
    public record RankedCrop(CropCode crop, String name, int score, List<String> reasons, List<String> missingData, String reason) {
    }

    public record FertilizationStage(String stage, String date, double nKgHa, double pKgHa, double kKgHa) {
    }

    public record Irrigation(double requiredMm, Double effectiveRainMm, Double deficitMm, int passes, int doseMm, String criticalPeriod) {
    }

    public record Monitoring(String title, String date) {
    }

    /** The plan report. The three financial fields are left out for operators. */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ParcelReport(String parcelName, double areaHa, @JsonInclude(JsonInclude.Include.ALWAYS) String soilType,
                               @JsonInclude(JsonInclude.Include.ALWAYS) String lastCropName, String plannedCropName,
                               CropCode recommendedCrop, String recommendedCropName, int recommendedScore,
                               int compatibilityScore, double expectedYieldTHa, Double expectedProfitEurHa,
                               Double priceEurPerT, Double costEurHa, List<String> risks, String recommendation,
                               List<String> missingData) {
    }

    public record ParcelPlanData(int year, CropCode crop, String cropName, int rotationScore, String rotationExplanation,
                                 String sowingDate, String harvestDate, List<PlanStep> steps, Irrigation irrigation,
                                 List<FertilizationStage> fertilization, List<Monitoring> monitoring,
                                 List<RankedCrop> recommendations, ParcelReport report) {
    }

    /**
     * Input of {@link #buildParcelPlan}.
     *
     * @param history previous crops, including the last declared crop
     * @param lastCropName name of the last crop, when it is not part of the catalog
     * @param year year of an already recorded crop
     */
    public record ParcelPlanInput(CropCode crop, String parcelName, double areaHa, List<CropHistoryEntry> history,
                                  String lastCropName, PlanSoil soil, PlanClimate climate, Instant now, Integer year,
                                  Instant sowingDate) {
    }

    private ParcelPlanner() {
    }

    /** Next agricultural year. After 1 August, the current year's harvest is considered the last crop. */
    public static int nextSeasonYear(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        return utc.getMonthValue() - 1 >= 7 ? utc.getYear() + 1 : utc.getYear();
    }

    private static Instant startOfDay(Instant date) {
        ZonedDateTime utc = date.atZone(ZoneOffset.UTC);
        return Times.utc(utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(), 9);
    }

    private static Instant addDays(Instant date, int days) {
        return date.plusMillis(days * Times.DAY_MS);
    }

    private static Instant sowingIn(CropCode crop, int year) {
        CropCalendar calendar = CROP_CALENDARS.get(crop);
        return Times.utc(year, calendar.sowingMonth(), calendar.sowingDay(), 9);
    }

    private static Instant resolveSowing(CropCode crop, Instant now, Instant sowingDate, Integer harvestYear) {
        if (sowingDate != null) {
            return sowingDate;
        }
        int harvestDays = CROP_CALENDARS.get(crop).harvestDays();
        if (harvestYear != null) {
            Instant sameYear = sowingIn(crop, harvestYear);
            return Times.utcYear(addDays(sameYear, harvestDays)) > harvestYear ? sowingIn(crop, harvestYear - 1) : sameYear;
        }
        Instant earliest = addDays(startOfDay(now), MIN_DAYS_BEFORE_SOWING);
        Instant thisYear = sowingIn(crop, Times.utcYear(now));
        return !thisYear.isBefore(earliest) ? thisYear : sowingIn(crop, Times.utcYear(now) + 1);
    }

    private record StepDefinition(String key, String title, String taskType, int days, String priority) {
    }

    /** The crop's technology calendar, computed from the sowing date. */
    public static PlanSchedule planCalendar(CropCode crop, Instant now, Instant sowingDate, Integer harvestYear) {
        CropCalendar calendar = CROP_CALENDARS.get(crop);
        Instant today = startOfDay(now);
        Instant sowing = resolveSowing(crop, now, sowingDate, harvestYear);
        Instant harvest = addDays(sowing, calendar.harvestDays());
        boolean planned = harvestYear == null && sowingDate == null;

        List<StepDefinition> definitions = new ArrayList<>();
        definitions.add(new StepDefinition("SOIL_PREP", "Seedbed preparation", "SOIL_PREP", -26, null));
        definitions.add(new StepDefinition("SEED_PURCHASE", "Seed purchase", "SEED_PURCHASE", -11, null));
        definitions.add(new StepDefinition("BASE_FERTILIZATION", "Base fertilization", "FERTILIZATION", -6, null));
        definitions.add(new StepDefinition("SOWING", "Sowing", "SOWING", 0, "HIGH"));
        definitions.add(new StepDefinition("PRE_EMERGENT", "Pre-emergence herbicide", "HERBICIDE", 2, null));
        definitions.add(new StepDefinition("EMERGENCE_CHECK", "Emergence check", "EMERGENCE_CHECK", calendar.emergenceDays(), null));
        if (calendar.topDressingDays() != null) {
            definitions.add(new StepDefinition("TOP_DRESSING", "Top dressing", "FERTILIZATION", calendar.topDressingDays(), null));
        }
        definitions.add(new StepDefinition("PEST_MONITORING", "Pest monitoring", "PEST_CHECK", calendar.pestDays(), null));
        definitions.add(new StepDefinition("DISEASE_MONITORING", "Disease monitoring", "DISEASE_CHECK", calendar.diseaseDays(), null));
        definitions.add(new StepDefinition("HARVEST_PREP", "Harvest preparation", "HARVEST_PREP", calendar.harvestDays() - 14, null));
        definitions.add(new StepDefinition("HARVEST", "Harvest", "HARVEST", calendar.harvestDays(), "HIGH"));

        List<PlanStep> steps = new ArrayList<>();
        for (StepDefinition definition : definitions) {
            Instant date = addDays(sowing, definition.days());
            // Overdue preparatory steps in a new plan are done immediately.
            Instant due = planned && date.isBefore(today) ? today : date;
            steps.add(new PlanStep(definition.key(), definition.title(), definition.taskType(), Times.iso(due),
                    definition.priority() == null ? "MEDIUM" : definition.priority(), due.isBefore(today)));
        }
        steps.sort(Comparator.comparing(PlanStep::date));
        return new PlanSchedule(Times.iso(sowing), Times.iso(harvest), Times.utcYear(harvest), steps);
    }

    private static String joinClauses(List<String> clauses) {
        if (clauses.size() < 2) {
            return clauses.isEmpty() ? "" : clauses.get(0);
        }
        return String.join(", ", clauses.subList(0, clauses.size() - 1)) + " and " + clauses.get(clauses.size() - 1);
    }

    private static String explain(CropRecommendation recommendation, int year, List<CropHistoryEntry> history,
                                  PlanSoil soil, PlanClimate climate) {
        CropProfile profile = Crops.profile(recommendation.crop());
        RotationResult rotation = Rotation.evaluateRotation(recommendation.crop(), year, history);
        Optional<CropHistoryEntry> previous = history.stream().filter(entry -> entry.year() == year - 1).findFirst();
        List<String> good = new ArrayList<>();
        List<String> weak = new ArrayList<>();

        if (rotation.score() >= 70) {
            if (previous.isPresent() && previous.get().crop() != recommendation.crop()) {
                CropProfile previousProfile = Crops.profile(previous.get().crop());
                good.add("the parcel had " + previousProfile.name().toLowerCase() + " last year");
                if (previousProfile.family() == CropFamily.LEGUME) {
                    good.add("it uses the nitrogen left in the soil by " + previousProfile.name().toLowerCase());
                }
            } else {
                good.add("the rotation is favourable");
            }
            if (profile.family() == CropFamily.LEGUME) {
                good.add("it enriches the soil with nitrogen");
            }
        } else {
            String text = rotation.explanation().replaceAll("\\.$", "");
            weak.add(text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1));
        }

        if (soil != null) {
            double min = profile.phRange()[0];
            double max = profile.phRange()[1];
            if (soil.ph() >= min && soil.ph() <= max) {
                good.add("the pH of " + Js.num(soil.ph()) + " is optimal");
            } else {
                weak.add("the pH of " + Js.num(soil.ph()) + " is outside the " + Js.num(min) + "-" + Js.num(max) + " range");
            }
            if (!profile.textures().contains(soil.texture())) {
                weak.add("the soil texture is not suitable");
            }
        }
        if (climate != null) {
            double needed = profile.waterMin();
            long rain = Math.round(climate.annualPrecipitationMm());
            if (climate.annualPrecipitationMm() >= needed) {
                good.add("rainfall of " + rain + " mm covers the minimum requirement");
            } else {
                weak.add("rainfall of " + rain + " mm is below the minimum requirement of " + Js.num(needed) + " mm");
            }
        }

        if (recommendation.score() >= 70 && !good.isEmpty()) {
            return profile.name() + " is a good fit because " + joinClauses(good) + ".";
        }
        if (!weak.isEmpty()) {
            return profile.name() + " has a lower score because " + joinClauses(weak) + ".";
        }
        return profile.name() + " has an indicative score, because data is missing about " + joinClauses(recommendation.missingData()) + ".";
    }

    /** Ranks crops by soil, climate and rotation, with an explanation for each score. */
    public static List<RankedCrop> rankCropsForParcel(int year, List<CropHistoryEntry> history, PlanSoil soil, PlanClimate climate) {
        SoilFit soilFit = soil == null ? null : new SoilFit(soil.ph(), soil.texture());
        ClimateFit climateFit = climate == null ? null
                : new ClimateFit(climate.seasonTempC(), climate.annualPrecipitationMm(), climate.gddBase10());
        return CropRules.recommendCrops(year, history, soilFit, climateFit, Crops.CROP_CODES).stream()
                .map(item -> new RankedCrop(item.crop(), item.name(), item.score(), item.reasons(), item.missingData(),
                        explain(item, year, history, soil, climate)))
                .toList();
    }

    private static double roundTo5(double value) {
        return Math.round(value / 5) * 5.0;
    }

    private static String period(int from, int to) {
        return MONTHS[from - 1] + "-" + MONTHS[to - 1];
    }

    /** Generates the full crop plan: steps, calendar, water, nutrients, monitoring and report. */
    public static ParcelPlanData buildParcelPlan(ParcelPlanInput input) {
        CropCode crop = input.crop();
        Instant now = input.now() == null ? Times.now() : input.now();
        List<CropHistoryEntry> history = input.history();
        PlanSoil soil = input.soil();
        PlanClimate climate = input.climate();
        CropProfile profile = Crops.profile(crop);
        CropCalendar calendar = CROP_CALENDARS.get(crop);
        PlanSchedule schedule = planCalendar(crop, now, input.sowingDate(), input.year());
        int year = input.year() != null ? input.year() : schedule.seasonYear();

        // All crops are compared in the same agricultural year, so the scores stay comparable.
        int latest = nextSeasonYear(now);
        for (CropHistoryEntry entry : history) {
            latest = Math.max(latest, entry.year() + 1);
        }
        int comparisonYear = Math.min(year, latest);
        List<RankedCrop> recommendations = rankCropsForParcel(comparisonYear, history, soil, climate);
        RankedCrop chosen = recommendations.stream().filter(item -> item.crop() == crop).findFirst().orElseThrow();
        RankedCrop best = recommendations.get(0);
        RotationResult rotation = Rotation.evaluateRotation(crop, comparisonYear, history);

        double requiredMm = (profile.waterMin() + profile.waterMax()) / 2;
        Double effectiveRainMm = climate == null ? null : Js.round(climate.annualPrecipitationMm() * calendar.rainShare());
        Double deficitMm = effectiveRainMm == null ? null : Math.max(0, Js.round(requiredMm - effectiveRainMm));
        boolean hasDeficit = deficitMm != null && deficitMm != 0;
        int passes = hasDeficit ? (int) Math.ceil(deficitMm / IRRIGATION_DOSE_MM) : 0;
        String criticalPeriod = period(calendar.criticalFrom(), calendar.criticalTo());

        double waterFactor = effectiveRainMm == null ? 1 : Math.max(0.6, Math.min(1, effectiveRainMm / requiredMm));
        double scoreFactor = Math.max(0.6, Math.min(1.05, 0.55 + chosen.score() / 100.0 * 0.5));
        double expectedYieldTHa = Math.round(profile.referenceYieldTHa() * scoreFactor * waterFactor * 10) / 10.0;
        double expectedProfitEurHa = roundTo5(expectedYieldTHa * calendar.priceEurPerT() - calendar.costEurHa());

        CropHistoryEntry previous = history.stream().max(Comparator.comparingInt(CropHistoryEntry::year)).orElse(null);
        double nitrogenCredit = previous != null && Crops.profile(previous.crop()).family() == CropFamily.LEGUME ? 30 : 0;
        double[] perT = calendar.nutrientsPerT();
        double nitrogen = Math.max(0, expectedYieldTHa * perT[0] - nitrogenCredit);
        boolean hasTopDressing = calendar.topDressingDays() != null;
        List<FertilizationStage> fertilization = new ArrayList<>();
        fertilization.add(new FertilizationStage("Base fertilization", dateOf(schedule, "BASE_FERTILIZATION"),
                roundTo5(hasTopDressing ? nitrogen * 0.3 : nitrogen), roundTo5(expectedYieldTHa * perT[1]),
                roundTo5(expectedYieldTHa * perT[2])));
        if (hasTopDressing) {
            fertilization.add(new FertilizationStage("Top dressing", dateOf(schedule, "TOP_DRESSING"), roundTo5(nitrogen * 0.7), 0, 0));
        }

        List<String> risks = new ArrayList<>();
        if (hasDeficit) {
            risks.add("Drought during " + criticalPeriod + ".");
            risks.add("Rainfall deficit estimated at " + Js.num(deficitMm) + " mm per season.");
        }
        if (rotation.score() < 70) {
            risks.add(rotation.explanation());
        }
        if (soil != null && (soil.ph() < profile.phRange()[0] || soil.ph() > profile.phRange()[1])) {
            risks.add("The pH of " + Js.num(soil.ph()) + " is outside the optimal range of "
                    + Js.num(profile.phRange()[0]) + "-" + Js.num(profile.phRange()[1]) + ".");
        }
        if (risks.isEmpty()) {
            risks.add("The available data does not indicate major risks.");
        }

        List<String> advice = new ArrayList<>();
        if (hasDeficit) {
            advice.add("Plan at least " + passes + " irrigation " + (passes == 1 ? "cycle" : "cycles") + " of "
                    + IRRIGATION_DOSE_MM + " mm during the critical growth period (" + criticalPeriod + ").");
        } else if (effectiveRainMm == null) {
            advice.add("Check the water reserve before the critical period (" + criticalPeriod + ").");
        } else {
            advice.add("Average rainfall covers the water requirement. Monitor soil moisture during " + criticalPeriod + ".");
        }
        if (best.crop() != crop && best.score() - chosen.score() >= 10) {
            advice.add(best.name() + " scores better on this parcel: " + best.score() + "%.");
        }

        String lastCropName = input.lastCropName() != null ? input.lastCropName()
                : previous != null ? Crops.name(previous.crop()) : null;

        List<Monitoring> monitoring = schedule.steps().stream()
                .filter(step -> step.key().equals("EMERGENCE_CHECK") || step.key().equals("PEST_MONITORING")
                        || step.key().equals("DISEASE_MONITORING"))
                .map(step -> new Monitoring(step.title(), step.date()))
                .toList();

        ParcelReport report = new ParcelReport(input.parcelName(), Js.round2(input.areaHa()),
                soil == null ? null : soil.label(), lastCropName, profile.name(), best.crop(), best.name(), best.score(),
                chosen.score(), expectedYieldTHa, expectedProfitEurHa, calendar.priceEurPerT(), calendar.costEurHa(),
                risks, String.join(" ", advice), chosen.missingData());

        return new ParcelPlanData(year, crop, profile.name(), rotation.score(), rotation.explanation(),
                schedule.sowingDate(), schedule.harvestDate(), schedule.steps(),
                new Irrigation(requiredMm, effectiveRainMm, deficitMm, passes, IRRIGATION_DOSE_MM, criticalPeriod),
                fertilization, monitoring, recommendations, report);
    }

    private static String dateOf(PlanSchedule schedule, String key) {
        return schedule.steps().stream().filter(step -> step.key().equals(key)).map(PlanStep::date).findFirst().orElse(null);
    }
}

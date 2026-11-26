package com.fermapp.api.domain.crops;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.crops.Crops.CropProfile;
import com.fermapp.api.domain.crops.Crops.SoilTexture;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.rotation.Rotation.RotationResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Crop recommendations (recommendations.ts), seasonal water balance (water.ts),
 * degree-days (gdd.ts) and vegetation health (vegetation.ts).
 */
public final class CropRules {

    public record SoilFit(double ph, SoilTexture texture) {
    }

    /**
     * Climate normals of the parcel.
     *
     * @param seasonTempC mean temperature during the growing months
     * @param gddBase10 annual degree-days above 10 °C, when known
     */
    public record ClimateFit(double seasonTempC, double annualPrecipitationMm, Double gddBase10) {
    }

    /** A ranked crop. {@code missingData} lists the criteria without data, which got a neutral score. */
    public record CropRecommendation(CropCode crop, String name, int score, List<String> reasons, List<String> missingData) {
    }

    public record WaterBalance(CropCode crop, double requiredMinMm, double requiredMaxMm, double requiredMm,
                               double receivedMm, double deficitMm, double coveragePct, int irrigationPasses,
                               double doseMm, String suggestion) {
    }

    public record DailyTemperature(String date, double tMaxC, double tMinC) {
    }

    public record CropDevelopment(CropCode crop, double accumulatedGdd, double requiredGdd, double percent, String stage) {
    }

    public enum HealthClass { GOOD, MODERATE, PROBLEM }

    public record VegetationHealth(int healthIndex, HealthClass healthClass, String color, String label) {
    }

    private static final double WEIGHT_ROTATION = 35;
    private static final double WEIGHT_SOIL = 25;
    private static final double WEIGHT_TEMPERATURE = 20;
    private static final double WEIGHT_WATER = 20;
    private static final double NEUTRAL = 0.65;

    private record Stage(double upTo, String label) {
    }

    private static final List<Stage> STAGES = List.of(
            new Stage(0.1, "Emergence"),
            new Stage(0.35, "Vegetative growth"),
            new Stage(0.55, "Flowering"),
            new Stage(0.8, "Grain fill"),
            new Stage(1, "Ripening"));

    private CropRules() {
    }

    /** 1 inside the range, then decreases linearly to 0 at distance {@code tolerance} from the range. */
    private static double rangeFit(double value, double[] range, double tolerance) {
        double min = range[0];
        double max = range[1];
        if (value >= min && value <= max) {
            return 1;
        }
        double distance = value < min ? min - value : value - max;
        return Math.max(0, 1 - distance / tolerance);
    }

    public static List<CropRecommendation> recommendCrops(int year, List<CropHistoryEntry> history, SoilFit soil,
                                                          ClimateFit climate, List<CropCode> crops) {
        List<CropCode> list = crops == null ? Crops.CROP_CODES : crops;
        List<CropRecommendation> result = new ArrayList<>();
        for (CropCode crop : list) {
            CropProfile profile = Crops.profile(crop);
            List<String> reasons = new ArrayList<>();
            List<String> missingData = new ArrayList<>();

            RotationResult rotation = Rotation.evaluateRotation(crop, year, history);
            reasons.add("Rotation " + rotation.score() + "/100: " + rotation.explanation());

            double soilFit = NEUTRAL;
            if (soil != null) {
                double phFit = rangeFit(soil.ph(), profile.phRange(), 1.5);
                double textureFit = profile.textures().contains(soil.texture()) ? 1 : 0.45;
                soilFit = phFit * 0.6 + textureFit * 0.4;
                String range = Js.num(profile.phRange()[0]) + "-" + Js.num(profile.phRange()[1]);
                reasons.add(phFit == 1
                        ? "pH " + Js.num(soil.ph()) + " is within the optimal range of " + range + "."
                        : "pH " + Js.num(soil.ph()) + " is outside the optimal range of " + range + ".");
                if (textureFit < 1) {
                    reasons.add("The soil texture is not among those preferred by the crop.");
                }
            } else {
                missingData.add("soil");
            }

            double temperatureFit = NEUTRAL;
            double waterFit = NEUTRAL;
            if (climate != null) {
                temperatureFit = rangeFit(climate.seasonTempC(), profile.seasonTempC(), 8);
                String temp = Js.toFixed(climate.seasonTempC(), 1);
                reasons.add(temperatureFit == 1
                        ? "The mean temperature of " + temp + " °C is favourable."
                        : "The mean temperature of " + temp + " °C is outside the optimum of "
                                + Js.num(profile.seasonTempC()[0]) + "-" + Js.num(profile.seasonTempC()[1]) + " °C.");
                waterFit = rangeFit(climate.annualPrecipitationMm(), profile.waterMm(), 300);
                long rain = Math.round(climate.annualPrecipitationMm());
                reasons.add(climate.annualPrecipitationMm() < profile.waterMin()
                        ? "Rainfall of " + rain + " mm is below the minimum requirement of " + Js.num(profile.waterMin()) + " mm."
                        : "Rainfall of " + rain + " mm covers the minimum requirement of " + Js.num(profile.waterMin()) + " mm.");
            } else {
                missingData.add("climate");
            }

            double score = rotation.score() / 100.0 * WEIGHT_ROTATION + soilFit * WEIGHT_SOIL
                    + temperatureFit * WEIGHT_TEMPERATURE + waterFit * WEIGHT_WATER;
            result.add(new CropRecommendation(crop, profile.name(), (int) Math.round(score), reasons, missingData));
        }
        result.sort(Comparator.comparingInt(CropRecommendation::score).reversed());
        return result;
    }

    /** Seasonal water balance, relative to the midpoint of the crop's requirement range. */
    public static WaterBalance waterBalance(CropCode crop, double precipitationMm, double irrigationMm, double doseMm) {
        CropProfile profile = Crops.profile(crop);
        double requiredMinMm = profile.waterMin();
        double requiredMaxMm = profile.waterMax();
        double requiredMm = (requiredMinMm + requiredMaxMm) / 2;
        double receivedMm = Math.max(0, precipitationMm) + Math.max(0, irrigationMm);
        double deficitMm = Math.max(0, requiredMm - receivedMm);
        int irrigationPasses = deficitMm > 0 ? (int) Math.ceil(deficitMm / doseMm) : 0;
        String dose = Js.num(doseMm);

        String suggestion;
        if (deficitMm == 0) {
            suggestion = profile.name() + ": the water requirement is covered, irrigation is not needed.";
        } else if (receivedMm >= requiredMinMm) {
            suggestion = profile.name() + ": water received reaches the minimum threshold. Irrigation is optional, with "
                    + irrigationPasses + " passes of " + dose + " mm for maximum yield.";
        } else {
            suggestion = profile.name() + ": deficit of " + Math.round(deficitMm) + " mm. " + irrigationPasses
                    + " irrigation passes of " + dose + " mm are recommended.";
        }
        return new WaterBalance(crop, requiredMinMm, requiredMaxMm, requiredMm, Js.round1(receivedMm), Js.round1(deficitMm),
                Math.min(100, Math.round(receivedMm / requiredMm * 100)), irrigationPasses, doseMm, suggestion);
    }

    /** Degree-days for one day, capped at the base and upper thresholds. */
    public static double dailyGdd(double tMaxC, double tMinC, double baseC, double capC) {
        double max = Math.min(capC, Math.max(baseC, tMaxC));
        double min = Math.min(capC, Math.max(baseC, tMinC));
        return Math.max(0, (max + min) / 2 - baseC);
    }

    public static double dailyGdd(double tMaxC, double tMinC, double baseC) {
        return dailyGdd(tMaxC, tMinC, baseC, 30);
    }

    public static CropDevelopment cropDevelopment(CropCode crop, List<DailyTemperature> days) {
        CropProfile profile = Crops.profile(crop);
        double accumulated = days.stream()
                .mapToDouble(day -> dailyGdd(day.tMaxC(), day.tMinC(), profile.gddBaseC(), profile.gddCapC()))
                .sum();
        double ratio = accumulated / profile.gddToMaturity();
        String stage = ratio >= 1 ? "Mature"
                : STAGES.stream().filter(item -> ratio <= item.upTo()).findFirst().orElseThrow().label();
        return new CropDevelopment(crop, Js.round(accumulated), profile.gddToMaturity(),
                Math.min(100, Math.round(ratio * 100)), stage);
    }

    /** 0-100 index: NDVI shows biomass, and NDRE shows nitrogen and chlorophyll status. */
    public static VegetationHealth vegetationHealth(double ndvi, Double ndre) {
        double ndviScore = Js.clamp((ndvi - 0.1) / 0.7, 0, 1);
        double score = ndre == null ? ndviScore : ndviScore * 0.7 + Js.clamp((ndre - 0.05) / 0.45, 0, 1) * 0.3;
        int healthIndex = (int) Math.round(score * 100);
        if (healthIndex >= 60) {
            return new VegetationHealth(healthIndex, HealthClass.GOOD, "green", "Good growth");
        }
        if (healthIndex >= 35) {
            return new VegetationHealth(healthIndex, HealthClass.MODERATE, "yellow", "Moderate stress");
        }
        return new VegetationHealth(healthIndex, HealthClass.PROBLEM, "red", "Possible problem");
    }
}

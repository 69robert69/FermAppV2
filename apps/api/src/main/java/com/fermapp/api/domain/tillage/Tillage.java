package com.fermapp.api.domain.tillage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.model.CropCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Soil tillage technologies (tillage.ts). Each one is a plain profile: a new technology is
 * added to {@link #SYSTEMS} and every calculation below picks it up.
 */
public final class Tillage {

    public enum TillageSystem { CONVENTIONAL, MINIMUM_TILL, STRIP_TILL, NO_TILL }

    /**
     * One recommended operation.
     *
     * @param workType the work log type used when the operation is started
     * @param taskType the task type used when the operation is planned
     * @param offsetDays days after the first operation, used to space the generated tasks
     */
    public record TillageOperation(String key, String label, String workType, String taskType, int offsetDays) {
    }

    /**
     * Profile of a technology.
     *
     * @param soilHealth 0-100
     * @param waterRetention 1-5 stars
     * @param waterGainPct extra water held compared with conventional tillage
     * @param passes machine passes over the field in a season, up to sowing
     * @param discouragedWork work types that contradict the technology, with the reason shown to the user
     */
    public record TillageProfile(TillageSystem code, String name, String badge, String color, String description,
                                 double costPerHa, double fuelLPerHa, int soilHealth, int waterRetention, int waterGainPct,
                                 int passes, List<TillageOperation> operations, Map<String, String> discouragedWork,
                                 Map<String, String> discouragedTasks) {
    }

    public record TillageHistoryEntry(int year, TillageSystem system) {
    }

    public record TillageAdvice(String tone, String message) {
    }

    public record ParcelTillageEstimate(TillageSystem system, double areaHa, double costPerHa, double totalCost,
                                        double costSavings, double fuelPerHa, double seasonFuelL, double annualFuelL,
                                        double annualFuelSavingsL, int passes, int passesSaved, int soilHealth,
                                        int waterRetention, int waterGainPct) {
    }

    public static final TillageSystem BASELINE = TillageSystem.CONVENTIONAL;

    public static final Map<TillageSystem, TillageProfile> SYSTEMS = new EnumMap<>(TillageSystem.class);

    private static final List<CropCode> ROW_CROPS = List.of(CropCode.CORN, CropCode.SUNFLOWER, CropCode.SOY, CropCode.BEET);

    static {
        SYSTEMS.put(TillageSystem.CONVENTIONAL, new TillageProfile(TillageSystem.CONVENTIONAL, "Conventional Tillage", "CONV",
                "#DE4467", "Full inversion with the plough, followed by secondary tillage.", 520, 60, 60, 2, 0, 4,
                List.of(op("plowing", "Ploughing", "PLOWING", "SOIL_PREP", 0),
                        op("discing", "Discing", "DISCING", "SOIL_PREP", 10),
                        op("seedbed", "Seedbed preparation", "DISCING", "SOIL_PREP", 18),
                        op("sowing", "Sowing", "SOWING", "SOWING", 21)),
                Map.of(), Map.of()));
        SYSTEMS.put(TillageSystem.MINIMUM_TILL, new TillageProfile(TillageSystem.MINIMUM_TILL, "Minimum Till", "MIN",
                "#B48A10", "Loosening without inversion; residues stay near the surface.", 420, 35, 75, 4, 15, 3,
                List.of(op("subsoiling", "Subsoiling", "SUBSOILING", "SOIL_PREP", 0),
                        op("chiselling", "Chiselling", "SUBSOILING", "SOIL_PREP", 7),
                        op("shallow-discing", "Shallow discing", "DISCING", "SOIL_PREP", 14),
                        op("sowing", "Sowing", "SOWING", "SOWING", 18)),
                ordered("PLOWING", "Ploughing inverts the soil and cancels the benefits of Minimum Till."), Map.of()));
        SYSTEMS.put(TillageSystem.STRIP_TILL, new TillageProfile(TillageSystem.STRIP_TILL, "Strip Till", "STRIP",
                "#3A8FD0", "Only the seed row strips are tilled and fertilised.", 350, 25, 82, 4, 18, 2,
                List.of(op("strip-prep", "Strip preparation", "SUBSOILING", "SOIL_PREP", 0),
                        op("band-fertilizing", "Band fertilisation", "FERTILIZING", "FERTILIZATION", 0),
                        op("strip-sowing", "Strip sowing", "SOWING", "SOWING", 10)),
                ordered("PLOWING", "Ploughing destroys the untilled strips between rows.",
                        "DISCING", "Full-width discing works the strips that Strip Till keeps covered."),
                Map.of()));
        SYSTEMS.put(TillageSystem.NO_TILL, new TillageProfile(TillageSystem.NO_TILL, "No Till", "NO-TILL",
                "#58A23A", "Direct sowing into the residue of the previous crop.", 250, 12, 90, 5, 20, 1,
                List.of(op("direct-sowing", "Direct sowing", "SOWING", "SOWING", 0)),
                ordered("PLOWING", "No Till does not plough. Ploughing breaks the soil structure built up over years.",
                        "DISCING", "No Till does not disc. Discing buries the residue that protects the soil.",
                        "SUBSOILING", "No Till does not loosen the soil before sowing."),
                ordered("SOIL_PREP", "No Till skips seedbed preparation; sow directly into the residue.")));
    }

    private Tillage() {
    }

    private static TillageOperation op(String key, String label, String workType, String taskType, int offsetDays) {
        return new TillageOperation(key, label, workType, taskType, offsetDays);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    public static TillageProfile profile(TillageSystem system) {
        return SYSTEMS.get(system);
    }

    /** {@code isTillageSystem(value)}: the value as a known technology, or null. */
    public static TillageSystem parse(String value) {
        if (value == null) {
            return null;
        }
        for (TillageSystem system : TillageSystem.values()) {
            if (system.name().equals(value)) {
                return system;
            }
        }
        return null;
    }

    public static double costPerHa(TillageSystem system) {
        return SYSTEMS.get(system).costPerHa();
    }

    public static double fuelPerHa(TillageSystem system) {
        return SYSTEMS.get(system).fuelLPerHa();
    }

    public static ParcelTillageEstimate estimateParcelTillage(TillageSystem system, double areaHa) {
        TillageProfile profile = SYSTEMS.get(system);
        TillageProfile baseline = SYSTEMS.get(BASELINE);
        int seasons = 1;
        double costPerHa = costPerHa(system);
        double fuelPerHa = fuelPerHa(system);
        double seasonFuelL = fuelPerHa * areaHa;
        double baselineFuel = fuelPerHa(BASELINE) * areaHa * seasons;
        return new ParcelTillageEstimate(system, areaHa, costPerHa, Js.round(costPerHa * areaHa * seasons),
                Js.round((costPerHa(BASELINE) - costPerHa) * areaHa * seasons), fuelPerHa, Js.round(seasonFuelL),
                Js.round(seasonFuelL * seasons), Js.round(baselineFuel - seasonFuelL * seasons), profile.passes(),
                baseline.passes() - profile.passes(), profile.soilHealth(), profile.waterRetention(), profile.waterGainPct());
    }

    /** Advice for the technology, adjusted to the crop. */
    public static List<TillageAdvice> tillageRecommendations(TillageSystem system, CropCode crop) {
        boolean rowCrop = crop != null && ROW_CROPS.contains(crop);
        List<TillageAdvice> advice = new ArrayList<>();
        switch (system) {
            case CONVENTIONAL -> advice.add(new TillageAdvice("warning",
                    "High compaction risk from repeated passes. Consider Minimum Till."));
            case MINIMUM_TILL -> {
                advice.add(new TillageAdvice("success", "A good fit for corn and sunflower."));
                if (rowCrop) {
                    advice.add(new TillageAdvice("info", "For row crops, Strip Till can cut costs further with banded fertiliser."));
                }
            }
            case STRIP_TILL -> {
                advice.add(new TillageAdvice("success", "Efficient for row crops and placed fertilisation."));
                if (crop != null && !rowCrop) {
                    advice.add(new TillageAdvice("warning",
                            "Strip Till pays off mostly in wide-row crops; check the row spacing of this crop."));
                }
            }
            case NO_TILL -> {
                advice.add(new TillageAdvice("success", "Excellent moisture conservation in dry conditions."));
                advice.add(new TillageAdvice("info", "Use a No Till drill and manage residue and weeds closely in the first years."));
            }
        }
        return advice;
    }

    /** The technology a parcel used in a year: the latest history entry up to that year. */
    public static TillageSystem tillageInYear(List<TillageHistoryEntry> history, int year) {
        return history.stream()
                .filter(entry -> entry.year() <= year)
                .max(Comparator.comparingInt(TillageHistoryEntry::year))
                .map(TillageHistoryEntry::system)
                .orElse(null);
    }

    /** Sets the technology of one year in a history, keeping one entry per year, oldest first. */
    public static List<TillageHistoryEntry> withTillageYear(List<TillageHistoryEntry> history, TillageHistoryEntry entry) {
        List<TillageHistoryEntry> result = new ArrayList<>(history.stream().filter(item -> item.year() != entry.year()).toList());
        result.add(entry);
        result.sort(Comparator.comparingInt(TillageHistoryEntry::year));
        return result;
    }

    /** Reads a stored history, dropping entries for unknown technologies. */
    public static List<TillageHistoryEntry> parseTillageHistory(JsonNode value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<TillageHistoryEntry> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item != null && item.path("year").isNumber() && parse(item.path("system").asText(null)) != null) {
                result.add(new TillageHistoryEntry(item.path("year").asInt(), parse(item.path("system").asText())));
            }
        }
        result.sort(Comparator.comparingInt(TillageHistoryEntry::year));
        return result;
    }
}

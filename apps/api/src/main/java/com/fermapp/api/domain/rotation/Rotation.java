package com.fermapp.api.domain.rotation;

import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.crops.Crops.CropFamily;
import com.fermapp.api.domain.crops.Crops.CropProfile;
import com.fermapp.api.domain.model.CropCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Crop rotation rules (rotation.ts). */
public final class Rotation {

    /** A past crop. {@code year} is the agricultural year of the harvest. */
    public record CropHistoryEntry(CropCode crop, int year) {
    }

    public enum RotationVerdict { RECOMMENDED, ACCEPTABLE, NOT_RECOMMENDED }

    public record RotationFactor(String code, int impact, String message) {
    }

    public record RotationResult(
            CropCode crop, int year, int score, RotationVerdict verdict, String label, String explanation,
            List<RotationFactor> factors) {
    }

    public static final int THRESHOLD_RECOMMENDED = 70;
    public static final int THRESHOLD_ACCEPTABLE = 40;

    public static final Map<RotationVerdict, String> VERDICT_LABELS = Map.of(
            RotationVerdict.RECOMMENDED, "✅ Recommended",
            RotationVerdict.ACCEPTABLE, "⚠️ Acceptable",
            RotationVerdict.NOT_RECOMMENDED, "❌ Not recommended");

    private static final int BASE_SCORE = 75;

    /** Common diseases (Sclerotinia) make these preceding crops risky for one another. */
    private static final List<CropCode> SCLEROTINIA_HOSTS = List.of(CropCode.SUNFLOWER, CropCode.RAPESEED, CropCode.SOY);

    private Rotation() {
    }

    private static String yearsWord(int n) {
        return n == 1 ? "one year" : n == 2 ? "two years" : n + " years";
    }

    private static String agoPhrase(int n) {
        return n == 0 ? "this year" : n == 1 ? "last year" : n + " years ago";
    }

    /**
     * Evaluates the proposed crop based on the parcel's history.
     * The break is measured in full years without the crop: 2022 and 2027 mean a 4-year break.
     */
    public static RotationResult evaluateRotation(CropCode proposed, int proposedYear, List<CropHistoryEntry> history) {
        CropProfile profile = Crops.profile(proposed);
        List<CropHistoryEntry> past = history.stream()
                .filter(entry -> entry.year() < proposedYear)
                .sorted(Comparator.comparingInt(CropHistoryEntry::year).reversed())
                .toList();
        List<RotationFactor> factors = new ArrayList<>();

        CropHistoryEntry previous = !past.isEmpty() && past.get(0).year() == proposedYear - 1 ? past.get(0) : null;
        Optional<CropHistoryEntry> lastSame = past.stream().filter(entry -> entry.crop() == proposed).findFirst();
        boolean breakViolated = false;

        if (lastSame.isPresent()) {
            int yearsAgo = proposedYear - lastSame.get().year();
            int breakYears = yearsAgo - 1;
            if (breakYears < profile.minBreakYears()) {
                breakViolated = true;
                int missing = profile.minBreakYears() - breakYears;
                factors.add(new RotationFactor("MIN_BREAK", -(35 + missing * 10),
                        profile.name() + " was grown " + agoPhrase(yearsAgo) + " on this parcel. "
                                + "A minimum break of " + yearsWord(profile.minBreakYears()) + " is recommended. "
                                + "The risk of disease and yield loss is high."));
            } else {
                factors.add(new RotationFactor("BREAK_OK", 5, "The " + yearsWord(breakYears) + " break since the last "
                        + profile.name().toLowerCase() + " crop is respected."));
            }
        }

        if (previous != null && previous.crop() != proposed) {
            CropProfile previousProfile = Crops.profile(previous.crop());
            if (previousProfile.family() == profile.family() && profile.family() != CropFamily.VEGETABLE) {
                factors.add(new RotationFactor("SAME_FAMILY", -15, previousProfile.name() + " and "
                        + profile.name().toLowerCase() + " belong to the same family and share diseases and pests."));
            }
            if (proposed == CropCode.CORN && previousProfile.family() == CropFamily.LEGUME) {
                factors.add(new RotationFactor("CORN_AFTER_LEGUME", 15, "Corn uses the nitrogen left in the soil by "
                        + previousProfile.name().toLowerCase() + "."));
            }
            if (proposed == CropCode.SOY && previousProfile.family() == CropFamily.CEREAL) {
                factors.add(new RotationFactor("SOY_AFTER_CEREAL", 15, "Soybean is recommended after cereals, and "
                        + previousProfile.name().toLowerCase() + " is a good preceding crop."));
            }
            if (previous.crop() == CropCode.ALFALFA && proposed != CropCode.CORN) {
                factors.add(new RotationFactor("ALFALFA_NITROGEN", 15, "Alfalfa has improved the soil nitrogen reserve."));
            }
            if (proposed != CropCode.CORN && previous.crop() != CropCode.ALFALFA
                    && profile.family() == CropFamily.CEREAL && previousProfile.family() == CropFamily.LEGUME) {
                factors.add(new RotationFactor("CEREAL_AFTER_LEGUME", 10, "Cereals benefit from the nitrogen fixed by "
                        + previousProfile.name().toLowerCase() + "."));
            }
            if (SCLEROTINIA_HOSTS.contains(proposed) && SCLEROTINIA_HOSTS.contains(previous.crop())) {
                factors.add(new RotationFactor("SCLEROTINIA", -20, previousProfile.name() + " and "
                        + profile.name().toLowerCase() + " are both hosts of white mould (Sclerotinia)."));
            }
        }

        List<CropHistoryEntry> lastDecade = past.stream().filter(entry -> entry.year() >= proposedYear - 10).toList();
        long sameCount = lastDecade.stream().filter(entry -> entry.crop() == proposed).count();
        if (lastDecade.size() >= 4 && (double) sameCount / lastDecade.size() > 0.4) {
            factors.add(new RotationFactor("HIGH_FREQUENCY", -10, profile.name() + " occupied the parcel in " + sameCount
                    + " of the last " + lastDecade.size() + " recorded years."));
        }

        if (past.isEmpty()) {
            factors.add(new RotationFactor("NO_HISTORY", -5, "The parcel has no crop history, so the assessment is indicative."));
        }

        int raw = BASE_SCORE + factors.stream().mapToInt(RotationFactor::impact).sum();
        int capped = breakViolated ? Math.min(raw, THRESHOLD_ACCEPTABLE - 1) : raw;
        int score = Math.max(0, Math.min(100, capped));
        RotationVerdict verdict = score >= THRESHOLD_RECOMMENDED ? RotationVerdict.RECOMMENDED
                : score >= THRESHOLD_ACCEPTABLE ? RotationVerdict.ACCEPTABLE : RotationVerdict.NOT_RECOMMENDED;

        // A stable sort by absolute impact, like Array.prototype.sort.
        List<RotationFactor> ordered = factors.stream()
                .sorted(Comparator.comparingInt((RotationFactor f) -> Math.abs(f.impact())).reversed())
                .toList();
        String explanation = ordered.isEmpty()
                ? profile.name() + " has no rotation restrictions on this parcel."
                : ordered.get(0).message();
        return new RotationResult(proposed, proposedYear, score, verdict, VERDICT_LABELS.get(verdict), explanation, ordered);
    }

    public static List<RotationResult> rankRotationOptions(int proposedYear, List<CropHistoryEntry> history, List<CropCode> crops) {
        return crops.stream()
                .map(crop -> evaluateRotation(crop, proposedYear, history))
                .sorted(Comparator.comparingInt(RotationResult::score).reversed())
                .toList();
    }
}

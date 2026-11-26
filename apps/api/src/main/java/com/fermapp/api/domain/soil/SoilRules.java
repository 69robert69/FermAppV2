package com.fermapp.api.domain.soil;

import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.crops.Crops.SoilTexture;

/** Soil classification (soil.ts) and soil indices (intelligence.ts). */
public final class SoilRules {

    /**
     * Soil properties from SoilGrids.
     *
     * @param organicCarbonGKg organic carbon, in g/kg
     * @param fieldCapacityPct water at field capacity, in % by volume (optional)
     * @param wiltingPointPct water at wilting point, in % by volume (optional)
     */
    public record SoilInput(double ph, double clayPct, double sandPct, double organicCarbonGKg,
                            Double fieldCapacityPct, Double wiltingPointPct) {
    }

    public record SoilClassification(double siltPct, double organicMatterPct, double waterHoldingMmPerM,
                                     SoilTexture texture, String textureLabel, String soilType, String label) {
    }

    public record SoilIndices(int soilScore, int fertilityIndex, int waterRetentionIndex) {
    }

    /** Van Bemmelen factor: organic matter is about 58% carbon. */
    private static final double VAN_BEMMELEN = 1.724;

    private SoilRules() {
    }

    private record Texture(SoilTexture texture, String label) {
    }

    private static Texture classifyTexture(double clayPct, double sandPct) {
        if (clayPct >= 40) {
            return new Texture(SoilTexture.CLAY, "clay");
        }
        if (clayPct >= 27) {
            return new Texture(SoilTexture.CLAY_LOAM, "clay loam");
        }
        if (sandPct >= 65) {
            return new Texture(SoilTexture.SANDY, "sandy");
        }
        return new Texture(SoilTexture.LOAMY, "loam");
    }

    private static String classifySoilType(double ph, double clayPct, double sandPct, double organicMatterPct) {
        if (sandPct >= 75) {
            return "Arenosol";
        }
        if (clayPct >= 50) {
            return "Vertisol";
        }
        if (ph >= 8.3) {
            return "Kastanozem";
        }
        if (organicMatterPct >= 2.8 && ph >= 6 && ph < 8.3) {
            return "Chernozem";
        }
        if (organicMatterPct >= 2.8) {
            return "Phaeozem";
        }
        if (ph < 5.8) {
            return "Luvisol";
        }
        return "Eutric Cambisol";
    }

    /** Estimates available water when SoilGrids does not provide retention values. */
    private static double estimateAvailableWaterPct(double clayPct, double sandPct, double organicMatterPct) {
        double base = 18 - Math.abs(clayPct - 25) * 0.12 - Math.max(0, sandPct - 50) * 0.18;
        return Math.max(5, base + organicMatterPct * 0.6);
    }

    /**
     * Indicative classification based on soil properties.
     * The official soil type can only be established by a pedological survey (SRTS).
     */
    public static SoilClassification classifySoil(SoilInput input) {
        double organicMatterPct = Math.round(input.organicCarbonGKg() * VAN_BEMMELEN / 10 * 100) / 100.0;
        Texture texture = classifyTexture(input.clayPct(), input.sandPct());
        String soilType = classifySoilType(input.ph(), input.clayPct(), input.sandPct(), organicMatterPct);
        double availablePct = input.fieldCapacityPct() != null && input.wiltingPointPct() != null
                && input.fieldCapacityPct() > input.wiltingPointPct()
                ? input.fieldCapacityPct() - input.wiltingPointPct()
                : estimateAvailableWaterPct(input.clayPct(), input.sandPct(), organicMatterPct);
        return new SoilClassification(
                Math.max(0, Js.round1(100 - input.clayPct() - input.sandPct())),
                organicMatterPct,
                Js.round(availablePct * 10),
                texture.texture(),
                texture.label(),
                soilType,
                soilType + " (" + texture.label() + ")");
    }

    /** Indicative 0-100 indices, computed from soil properties. */
    public static SoilIndices soilIndices(double ph, double organicMatterPct, double clayPct, double sandPct, double waterHoldingMmPerM) {
        double phDistance = ph < 6.2 ? 6.2 - ph : ph > 7.5 ? ph - 7.5 : 0;
        double phScore = Js.clamp(100 - phDistance * 40, 0, 100);
        double organicScore = Js.clamp(organicMatterPct / 4 * 100, 0, 100);
        double clayDistance = clayPct < 15 ? 15 - clayPct : clayPct > 40 ? clayPct - 40 : 0;
        double textureScore = Js.clamp(100 - clayDistance * 2.5 - Math.max(0, sandPct - 60) * 2, 0, 100);
        int fertilityIndex = (int) Math.round(organicScore * 0.6 + phScore * 0.4);
        int waterRetentionIndex = (int) Math.round(Js.clamp(waterHoldingMmPerM / 200 * 100, 0, 100));
        int soilScore = (int) Math.round(fertilityIndex * 0.4 + waterRetentionIndex * 0.3 + textureScore * 0.3);
        return new SoilIndices(soilScore, fertilityIndex, waterRetentionIndex);
    }
}

package com.fermapp.api.domain.crops;

import com.fermapp.api.domain.model.CropCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The crop catalog (crops.ts): agronomic profile of every crop code. */
public final class Crops {

    /** Botanical family, used by the rotation rules. */
    public enum CropFamily { CEREAL, LEGUME, ASTERACEAE, BRASSICA, SOLANACEAE, CHENOPOD, VEGETABLE }

    /** Soil texture classes. */
    public enum SoilTexture { SANDY, LOAMY, CLAY_LOAM, CLAY }

    /**
     * Agronomic profile of a crop. The ranges are [min, max] arrays in the JSON, as in TypeScript.
     *
     * @param minBreakYears full years without the crop between two identical crops on the same parcel
     * @param waterMm seasonal water requirement, in mm
     * @param seasonTempC optimal mean temperature of the growing season
     * @param referenceYieldTHa indicative yield, in t/ha
     */
    public record CropProfile(
            CropCode code,
            String name,
            CropFamily family,
            int minBreakYears,
            double[] waterMm,
            double[] phRange,
            double[] seasonTempC,
            double gddBaseC,
            double gddCapC,
            double gddToMaturity,
            List<SoilTexture> textures,
            double referenceYieldTHa) {

        public double waterMin() {
            return waterMm[0];
        }

        public double waterMax() {
            return waterMm[1];
        }
    }

    /** {@code CROP_CODES}, in catalog order. */
    public static final List<CropCode> CROP_CODES = List.of(CropCode.values());

    private static final Map<CropCode, CropProfile> CROPS = new EnumMap<>(CropCode.class);

    static {
        add(CropCode.WHEAT, "Wheat", CropFamily.CEREAL, 1, 350, 600, 6, 7.5, 8, 18, 0, 30, 2100,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM, SoilTexture.CLAY), 6);
        add(CropCode.BARLEY, "Barley", CropFamily.CEREAL, 1, 300, 500, 6, 8, 7, 17, 0, 30, 1800,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 5.5);
        add(CropCode.CORN, "Corn", CropFamily.CEREAL, 1, 450, 800, 5.8, 7.5, 18, 26, 10, 30, 1450,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 8);
        add(CropCode.SUNFLOWER, "Sunflower", CropFamily.ASTERACEAE, 4, 400, 700, 6, 7.8, 17, 26, 6, 30, 1600,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM, SoilTexture.CLAY), 2.8);
        add(CropCode.RAPESEED, "Rapeseed", CropFamily.BRASSICA, 3, 400, 600, 6, 7.5, 7, 17, 5, 30, 1500,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 3.2);
        add(CropCode.SOY, "Soybean", CropFamily.LEGUME, 2, 450, 700, 6, 7.2, 18, 26, 10, 30, 1300,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 2.8);
        add(CropCode.ALFALFA, "Alfalfa", CropFamily.LEGUME, 3, 600, 1000, 6.5, 7.8, 14, 25, 5, 30, 1200,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 9);
        add(CropCode.OAT, "Oats", CropFamily.CEREAL, 2, 350, 550, 5.2, 7, 8, 17, 0, 30, 1700,
                List.of(SoilTexture.SANDY, SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 3.5);
        add(CropCode.POTATO, "Potato", CropFamily.SOLANACEAE, 3, 500, 700, 5, 6.5, 14, 20, 7, 30, 1300,
                List.of(SoilTexture.SANDY, SoilTexture.LOAMY), 28);
        add(CropCode.BEET, "Beet", CropFamily.CHENOPOD, 3, 550, 750, 6.5, 8, 14, 22, 3, 30, 2400,
                List.of(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 60);
        add(CropCode.VEGETABLES, "Vegetables", CropFamily.VEGETABLE, 2, 400, 800, 6, 7.2, 15, 25, 8, 30, 1100,
                List.of(SoilTexture.SANDY, SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 30);
    }

    private Crops() {
    }

    private static void add(CropCode code, String name, CropFamily family, int minBreakYears,
                            double waterMin, double waterMax, double phMin, double phMax,
                            double tempMin, double tempMax, double gddBase, double gddCap, double gddToMaturity,
                            List<SoilTexture> textures, double referenceYield) {
        CROPS.put(code, new CropProfile(code, name, family, minBreakYears, new double[] {waterMin, waterMax},
                new double[] {phMin, phMax}, new double[] {tempMin, tempMax}, gddBase, gddCap, gddToMaturity,
                textures, referenceYield));
    }

    public static CropProfile profile(CropCode code) {
        return CROPS.get(code);
    }

    /** {@code cropName(code)}. */
    public static String name(CropCode code) {
        return CROPS.get(code).name();
    }

    /** {@code value in CROPS}. */
    public static boolean isCropCode(String value) {
        if (value == null) {
            return false;
        }
        for (CropCode code : CropCode.values()) {
            if (code.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** {@code CROP_CODES.map((code) => CROPS[code])}: the catalog served by GET /crops. */
    public static List<CropProfile> catalog() {
        return CROP_CODES.stream().map(CROPS::get).toList();
    }
}

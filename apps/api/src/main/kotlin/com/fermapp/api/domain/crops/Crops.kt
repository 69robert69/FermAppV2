package com.fermapp.api.domain.crops

import com.fermapp.api.domain.model.CropCode
import java.util.EnumMap

/** The crop catalog (crops.ts): agronomic profile of every crop code. */
object Crops {

    /** Botanical family, used by the rotation rules. */
    enum class CropFamily { CEREAL, LEGUME, ASTERACEAE, BRASSICA, SOLANACEAE, CHENOPOD, VEGETABLE }

    /** Soil texture classes. */
    enum class SoilTexture { SANDY, LOAMY, CLAY_LOAM, CLAY }

    /**
     * Agronomic profile of a crop. The ranges are [min, max] arrays in the JSON, as in TypeScript.
     *
     * @param minBreakYears full years without the crop between two identical crops on the same parcel
     * @param waterMm seasonal water requirement, in mm
     * @param seasonTempC optimal mean temperature of the growing season
     * @param referenceYieldTHa indicative yield, in t/ha
     */
    @JvmRecord
    @Suppress("ArrayInDataClass")
    data class CropProfile(
        val code: CropCode,
        val name: String,
        val family: CropFamily,
        val minBreakYears: Int,
        val waterMm: DoubleArray,
        val phRange: DoubleArray,
        val seasonTempC: DoubleArray,
        val gddBaseC: Double,
        val gddCapC: Double,
        val gddToMaturity: Double,
        val textures: List<SoilTexture>,
        val referenceYieldTHa: Double,
    ) {
        fun waterMin(): Double = waterMm[0]

        fun waterMax(): Double = waterMm[1]
    }

    /** `CROP_CODES`, in catalog order. */
    @JvmField
    val CROP_CODES: List<CropCode> = CropCode.entries.toList()

    private val CROPS = EnumMap<CropCode, CropProfile>(CropCode::class.java)

    init {
        add(CropCode.WHEAT, "Wheat", CropFamily.CEREAL, 1, 350, 600, 6, 7.5, 8, 18, 0, 30, 2100,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM, SoilTexture.CLAY), 6)
        add(CropCode.BARLEY, "Barley", CropFamily.CEREAL, 1, 300, 500, 6, 8, 7, 17, 0, 30, 1800,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 5.5)
        add(CropCode.CORN, "Corn", CropFamily.CEREAL, 1, 450, 800, 5.8, 7.5, 18, 26, 10, 30, 1450,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 8)
        add(CropCode.SUNFLOWER, "Sunflower", CropFamily.ASTERACEAE, 4, 400, 700, 6, 7.8, 17, 26, 6, 30, 1600,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM, SoilTexture.CLAY), 2.8)
        add(CropCode.RAPESEED, "Rapeseed", CropFamily.BRASSICA, 3, 400, 600, 6, 7.5, 7, 17, 5, 30, 1500,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 3.2)
        add(CropCode.SOY, "Soybean", CropFamily.LEGUME, 2, 450, 700, 6, 7.2, 18, 26, 10, 30, 1300,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 2.8)
        add(CropCode.ALFALFA, "Alfalfa", CropFamily.LEGUME, 3, 600, 1000, 6.5, 7.8, 14, 25, 5, 30, 1200,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 9)
        add(CropCode.OAT, "Oats", CropFamily.CEREAL, 2, 350, 550, 5.2, 7, 8, 17, 0, 30, 1700,
            listOf(SoilTexture.SANDY, SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 3.5)
        add(CropCode.POTATO, "Potato", CropFamily.SOLANACEAE, 3, 500, 700, 5, 6.5, 14, 20, 7, 30, 1300,
            listOf(SoilTexture.SANDY, SoilTexture.LOAMY), 28)
        add(CropCode.BEET, "Beet", CropFamily.CHENOPOD, 3, 550, 750, 6.5, 8, 14, 22, 3, 30, 2400,
            listOf(SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 60)
        add(CropCode.VEGETABLES, "Vegetables", CropFamily.VEGETABLE, 2, 400, 800, 6, 7.2, 15, 25, 8, 30, 1100,
            listOf(SoilTexture.SANDY, SoilTexture.LOAMY, SoilTexture.CLAY_LOAM), 30)
    }

    private fun add(
        code: CropCode, name: String, family: CropFamily, minBreakYears: Int,
        waterMin: Number, waterMax: Number, phMin: Number, phMax: Number,
        tempMin: Number, tempMax: Number, gddBase: Number, gddCap: Number, gddToMaturity: Number,
        textures: List<SoilTexture>, referenceYield: Number,
    ) {
        CROPS[code] = CropProfile(
            code, name, family, minBreakYears,
            doubleArrayOf(waterMin.toDouble(), waterMax.toDouble()),
            doubleArrayOf(phMin.toDouble(), phMax.toDouble()),
            doubleArrayOf(tempMin.toDouble(), tempMax.toDouble()),
            gddBase.toDouble(), gddCap.toDouble(), gddToMaturity.toDouble(), textures, referenceYield.toDouble(),
        )
    }

    @JvmStatic
    fun profile(code: CropCode): CropProfile = CROPS.getValue(code)

    /** `cropName(code)`. */
    @JvmStatic
    fun name(code: CropCode): String = profile(code).name

    /** `value in CROPS`. */
    @JvmStatic
    fun isCropCode(value: String?): Boolean = value != null && CropCode.entries.any { it.name == value }

    /** `CROP_CODES.map((code) => CROPS[code])`: the catalog served by GET /crops. */
    @JvmStatic
    fun catalog(): List<CropProfile> = CROP_CODES.map(::profile)
}

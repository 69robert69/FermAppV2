package ro.fermapp.domain

import kotlinx.serialization.Serializable

@Serializable
enum class CropFamily { CEREAL, LEGUME, ASTERACEAE, BRASSICA, SOLANACEAE, CHENOPOD, VEGETABLE }

@Serializable
enum class SoilTexture { SANDY, LOAMY, CLAY_LOAM, CLAY }

/** A closed range of numbers, written [min, max] in the original data. */
data class Range(val min: Double, val max: Double)

data class CropProfile(
    val code: CropCode,
    val name: String,
    val family: CropFamily,
    /** Full years without the crop, between two identical crops on the same parcel. */
    val minBreakYears: Int,
    /** Seasonal water requirement, in mm. */
    val waterMm: Range,
    val phRange: Range,
    /** Optimal mean temperature of the growing season, in °C. */
    val seasonTempC: Range,
    val gddBaseC: Double,
    val gddCapC: Double,
    val gddToMaturity: Double,
    val textures: List<SoilTexture>,
    /** Indicative yield, in t/ha. */
    val referenceYieldTHa: Double,
)

private fun profile(
    code: CropCode, name: String, family: CropFamily, minBreakYears: Int,
    waterMm: Pair<Number, Number>, phRange: Pair<Number, Number>, seasonTempC: Pair<Number, Number>,
    gddBaseC: Number, gddToMaturity: Number, textures: List<SoilTexture>, referenceYieldTHa: Number,
) = CropProfile(
    code, name, family, minBreakYears,
    Range(waterMm.first.toDouble(), waterMm.second.toDouble()),
    Range(phRange.first.toDouble(), phRange.second.toDouble()),
    Range(seasonTempC.first.toDouble(), seasonTempC.second.toDouble()),
    gddBaseC.toDouble(), 30.0, gddToMaturity.toDouble(), textures, referenceYieldTHa.toDouble(),
)

/** The order of the entries is the order the crops are listed in everywhere. */
@Serializable
enum class CropCode {
    WHEAT, BARLEY, CORN, SUNFLOWER, RAPESEED, SOY, ALFALFA, OAT, POTATO, BEET, VEGETABLES;

    val profile: CropProfile get() = CROPS.getValue(this)
}

val CROP_CODES: List<CropCode> = CropCode.entries

private val L = SoilTexture.LOAMY
private val CL = SoilTexture.CLAY_LOAM
private val C = SoilTexture.CLAY
private val S = SoilTexture.SANDY

val CROPS: Map<CropCode, CropProfile> = listOf(
    profile(CropCode.WHEAT, "Wheat", CropFamily.CEREAL, 1, 350 to 600, 6 to 7.5, 8 to 18, 0, 2100, listOf(L, CL, C), 6),
    profile(CropCode.BARLEY, "Barley", CropFamily.CEREAL, 1, 300 to 500, 6 to 8, 7 to 17, 0, 1800, listOf(L, CL), 5.5),
    profile(CropCode.CORN, "Corn", CropFamily.CEREAL, 1, 450 to 800, 5.8 to 7.5, 18 to 26, 10, 1450, listOf(L, CL), 8),
    profile(CropCode.SUNFLOWER, "Sunflower", CropFamily.ASTERACEAE, 4, 400 to 700, 6 to 7.8, 17 to 26, 6, 1600, listOf(L, CL, C), 2.8),
    profile(CropCode.RAPESEED, "Rapeseed", CropFamily.BRASSICA, 3, 400 to 600, 6 to 7.5, 7 to 17, 5, 1500, listOf(L, CL), 3.2),
    profile(CropCode.SOY, "Soybean", CropFamily.LEGUME, 2, 450 to 700, 6 to 7.2, 18 to 26, 10, 1300, listOf(L, CL), 2.8),
    profile(CropCode.ALFALFA, "Alfalfa", CropFamily.LEGUME, 3, 600 to 1000, 6.5 to 7.8, 14 to 25, 5, 1200, listOf(L, CL), 9),
    profile(CropCode.OAT, "Oats", CropFamily.CEREAL, 2, 350 to 550, 5.2 to 7, 8 to 17, 0, 1700, listOf(S, L, CL), 3.5),
    profile(CropCode.POTATO, "Potato", CropFamily.SOLANACEAE, 3, 500 to 700, 5 to 6.5, 14 to 20, 7, 1300, listOf(S, L), 28),
    profile(CropCode.BEET, "Beet", CropFamily.CHENOPOD, 3, 550 to 750, 6.5 to 8, 14 to 22, 3, 2400, listOf(L, CL), 60),
    profile(CropCode.VEGETABLES, "Vegetables", CropFamily.VEGETABLE, 2, 400 to 800, 6 to 7.2, 15 to 25, 8, 1100, listOf(S, L, CL), 30),
).associateBy { it.code }

fun cropName(code: CropCode): String = code.profile.name

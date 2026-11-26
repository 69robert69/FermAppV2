package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Serializable
enum class CatalogLevel(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

@Serializable
enum class SeedType(val label: String) { HYBRID("Hybrid"), VARIETY("Variety") }

@Serializable
enum class WorkOperation { PLOWING, DISCING, SUBSOILING, SOWING, FERTILIZING, SPRAYING, HARVESTING }

/** Unknown values stay empty. They are filled in from the manufacturer's data sheet. */
@Serializable
data class SeedEntry(
    val id: String,
    val brand: String? = null,
    val crop: CropCode,
    val name: String,
    val type: SeedType,
    val fao: Double? = null,
    val maturityGroup: String? = null,
    val vegetationDays: Double? = null,
    val yieldPotential: CatalogLevel? = null,
    val expectedYieldMin: Double? = null,
    val expectedYieldMax: Double? = null,
    val waterRequirement: CatalogLevel? = null,
    val droughtTolerance: CatalogLevel? = null,
    val diseaseResistance: List<String> = emptyList(),
    val soilPreference: List<SoilTexture> = emptyList(),
    val recommendedRegions: List<String> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class TractorEntry(
    val id: String,
    val brand: String,
    val series: String? = null,
    val model: String,
    val horsePower: Double,
    val fuelConsumptionMin: Double,
    val fuelConsumptionMax: Double,
    /** Consumption is estimated from power, because it was not entered from the data sheet. */
    val fuelEstimated: Boolean,
    val tankCapacity: Double? = null,
    val weight: Double? = null,
    val driveSystem: String? = null,
    val category: String,
    val recommendedUsage: String,
)

@Serializable
data class ImplementEntry(
    val id: String,
    val name: String,
    val operation: WorkOperation,
    val widthsM: List<Double>,
    val speedKmh: Double,
    val efficiencyPct: Double,
    /** Power required for each metre of working width. */
    val hpPerMeter: Double,
    /** Share of the tractor's consumption range used for this operation. */
    val fuelLoad: Double,
)

fun maturityGroupFor(fao: Double?): String? = when {
    fao == null -> null
    fao < 300 -> "Early"
    fao < 400 -> "Medium-early"
    fao < 500 -> "Medium-late"
    else -> "Late"
}

/** Yield potential relative to the crop's reference yield. */
fun yieldLevelFor(crop: CropCode, expectedYieldMax: Double?): CatalogLevel? {
    if (expectedYieldMax == null) return null
    val ratio = expectedYieldMax / crop.profile.referenceYieldTHa
    return when {
        ratio >= 1.5 -> CatalogLevel.HIGH
        ratio >= 1 -> CatalogLevel.MEDIUM
        else -> CatalogLevel.LOW
    }
}

fun createSeedEntry(
    name: String,
    crop: CropCode,
    type: SeedType,
    id: String? = null,
    brand: String? = null,
    fao: Double? = null,
    maturityGroup: String? = null,
    vegetationDays: Double? = null,
    yieldPotential: CatalogLevel? = null,
    expectedYieldMin: Double? = null,
    expectedYieldMax: Double? = null,
    waterRequirement: CatalogLevel? = null,
    droughtTolerance: CatalogLevel? = null,
    diseaseResistance: List<String> = emptyList(),
    soilPreference: List<SoilTexture> = emptyList(),
    recommendedRegions: List<String> = emptyList(),
    notes: String? = null,
) = SeedEntry(
    id = id ?: "${crop.name.lowercase()}-${slug(name)}",
    brand = brand,
    crop = crop,
    name = name,
    type = type,
    fao = fao,
    maturityGroup = maturityGroup ?: maturityGroupFor(fao),
    vegetationDays = vegetationDays,
    yieldPotential = yieldPotential ?: yieldLevelFor(crop, expectedYieldMax),
    expectedYieldMin = expectedYieldMin,
    expectedYieldMax = expectedYieldMax,
    waterRequirement = waterRequirement,
    droughtTolerance = droughtTolerance,
    diseaseResistance = diseaseResistance,
    soilPreference = soilPreference,
    recommendedRegions = recommendedRegions,
    notes = notes,
)

private fun seed(crop: CropCode, type: SeedType, name: String, brand: String? = null, fao: Int? = null) =
    createSeedEntry(name, crop, type, brand = brand, fao = fao?.toDouble())

/** Starter catalog products. The farm administrator fills in the rest of the data. */
val SEED_CATALOG: List<SeedEntry> = buildList {
    val corn = { name: String, brand: String, fao: Int -> seed(CropCode.CORN, SeedType.HYBRID, name, brand, fao) }
    add(
        createSeedEntry(
            "Pioneer P0710", CropCode.CORN, SeedType.HYBRID, brand = "Pioneer", fao = 410.0, vegetationDays = 160.0,
            droughtTolerance = CatalogLevel.HIGH, expectedYieldMin = 10.0, expectedYieldMax = 16.0,
        ),
    )
    add(corn("Pioneer P9889", "Pioneer", 380))
    add(corn("Dekalb DKC4943", "Dekalb", 390))
    add(corn("KWS Kamparis", "KWS", 420))
    listOf("P64LE99" to "Pioneer", "P64LE137" to "Pioneer", "SY Onestar" to "Syngenta", "LG50500" to "Limagrain", "LG50635" to "Limagrain")
        .forEach { (name, brand) -> add(seed(CropCode.SUNFLOWER, SeedType.HYBRID, name, brand)) }
    listOf("PT303" to "Pioneer", "PT315" to "Pioneer", "Architect" to "Limagrain", "DK Exbury" to "Dekalb", "DK Excited" to "Dekalb")
        .forEach { (name, brand) -> add(seed(CropCode.RAPESEED, SeedType.HYBRID, name, brand)) }
    listOf("Glosa" to "INCDA Fundulea", "Izvor" to "INCDA Fundulea", "PG102" to null, "Accroc" to null, "Sosthene" to null)
        .forEach { (name, brand) -> add(seed(CropCode.WHEAT, SeedType.VARIETY, name, brand)) }
    listOf("ES Mentor" to "Lidea", "ES Director" to "Lidea", "PR91M10" to "Pioneer", "Aurelina" to null)
        .forEach { (name, brand) -> add(seed(CropCode.SOY, SeedType.VARIETY, name, brand)) }
}

private fun tractorCategory(horsePower: Double): Pair<String, String> = when {
    horsePower < 150 -> "Utility" to "Light work, transport and spraying."
    horsePower < 250 -> "Medium" to "Sowing, fertilizing and seedbed preparation."
    else -> "Large" to "Ploughing, subsoiling and wide implements on large areas."
}

fun createTractorEntry(
    brand: String,
    model: String,
    horsePower: Double,
    id: String? = null,
    series: String? = null,
    fuelConsumptionMin: Double? = null,
    fuelConsumptionMax: Double? = null,
    tankCapacity: Double? = null,
    weight: Double? = null,
    driveSystem: String? = "4x4",
    category: String? = null,
    recommendedUsage: String? = null,
): TractorEntry {
    val (defaultCategory, defaultUsage) = tractorCategory(horsePower)
    return TractorEntry(
        id = id ?: slug("$brand-$model"),
        brand = brand,
        series = series,
        model = model,
        horsePower = horsePower,
        fuelConsumptionMin = fuelConsumptionMin ?: jsRound(horsePower * 0.105),
        fuelConsumptionMax = fuelConsumptionMax ?: jsRound(horsePower * 0.16),
        fuelEstimated = fuelConsumptionMin == null || fuelConsumptionMax == null,
        tankCapacity = tankCapacity,
        weight = weight,
        driveSystem = driveSystem,
        category = category ?: defaultCategory,
        recommendedUsage = recommendedUsage ?: defaultUsage,
    )
}

private fun deere(series: String, model: String, horsePower: Int, fuelMin: Int, fuelMax: Int) =
    createTractorEntry("John Deere", model, horsePower.toDouble(), series = series, fuelConsumptionMin = fuelMin.toDouble(), fuelConsumptionMax = fuelMax.toDouble())

private fun tractor(brand: String, series: String, model: String, horsePower: Int) =
    createTractorEntry(brand, model, horsePower.toDouble(), series = series)

val TRACTOR_CATALOG: List<TractorEntry> = listOf(
    deere("6R", "6R 110", 110, 12, 18),
    deere("6R", "6R 140", 140, 14, 22),
    deere("6R", "6R 175", 175, 18, 28),
    deere("6R", "6R 215", 215, 22, 35),
    deere("7R", "7R 290", 290, 30, 45),
    deere("7R", "7R 350", 350, 35, 55),
    deere("8R", "8R 280", 280, 30, 45),
    deere("8R", "8R 340", 340, 35, 55),
    deere("8R", "8R 410", 410, 45, 65),
    tractor("Fendt", "500 Vario", "516 Vario", 163),
    tractor("Fendt", "700 Vario", "724 Vario", 246),
    tractor("Fendt", "900 Vario", "942 Vario", 420),
    tractor("Case IH", "Puma", "Puma 165", 165),
    tractor("Case IH", "Magnum", "Magnum 340", 340),
    tractor("New Holland", "T6", "T6.180", 175),
    tractor("New Holland", "T7", "T7.315", 313),
    tractor("Claas", "Arion 600", "Arion 660", 205),
    tractor("Claas", "Axion 900", "Axion 960", 445),
)

/** Indicative values for common trailed implements. */
val IMPLEMENT_CATALOG: List<ImplementEntry> = listOf(
    ImplementEntry("plug", "Reversible plough", WorkOperation.PLOWING, listOf(1.6, 2.1, 2.8, 3.5), 7.0, 80.0, 90.0, 0.8),
    ImplementEntry("disc", "Disc harrow", WorkOperation.DISCING, listOf(3.0, 4.0, 6.0, 8.0), 11.0, 80.0, 30.0, 0.6),
    ImplementEntry("scarificator", "Subsoiler", WorkOperation.SUBSOILING, listOf(2.5, 3.0, 4.0, 5.0), 8.0, 80.0, 70.0, 0.8),
    ImplementEntry("semanatoare", "Seed drill", WorkOperation.SOWING, listOf(3.0, 4.5, 6.0, 8.0, 12.0), 8.0, 70.0, 22.0, 0.35),
    ImplementEntry("fertilizator", "Fertilizer spreader", WorkOperation.FERTILIZING, listOf(12.0, 18.0, 24.0), 12.0, 70.0, 3.0, 0.25),
    ImplementEntry("erbicidator", "Field sprayer", WorkOperation.SPRAYING, listOf(18.0, 24.0, 28.0), 10.0, 70.0, 4.0, 0.25),
)

/** Diesel price in euro per litre. */
const val DEFAULT_FUEL_PRICE_EUR = 1.45
private const val DEFAULT_FUEL_LOAD = 0.35

/** The operator's hourly cost separates small, slow tractors from large, fast ones. */
private const val OPERATOR_COST_EUR_H = 10.0
private const val SLOPE_POWER_LOSS_PER_PCT = 0.02

private fun roundDigits(value: Double, digits: Int = 1): Double = jsRound(value * 10.0.pow(digits)) / 10.0.pow(digits)

fun fuelLitersPerHour(min: Double, max: Double, load: Double = DEFAULT_FUEL_LOAD) = roundDigits(min + (max - min) * load)

@Serializable
data class WorkCost(val fuelLPerH: Double, val fuelLiters: Double, val fuelCost: Double, val costPerHa: Double)

fun workCost(fuelLPerH: Double, durationHours: Double, fuelPricePerL: Double, areaHa: Double): WorkCost {
    val fuelLiters = roundDigits(fuelLPerH * durationHours)
    val fuelCost = jsRound(fuelLiters * fuelPricePerL)
    return WorkCost(fuelLPerH, fuelLiters, fuelCost, if (areaHa > 0) jsRound(fuelCost / areaHa) else 0.0)
}

@Serializable
data class TractorOption(
    val id: String,
    val name: String,
    val horsePower: Double,
    val fuelConsumptionMin: Double? = null,
    val fuelConsumptionMax: Double? = null,
    /** The tractor exists in the farm's fleet or is only a catalog suggestion. */
    val owned: Boolean,
)

@Serializable
data class MachineRecommendation(
    val fuelLPerH: Double,
    val fuelLiters: Double,
    val fuelCost: Double,
    val costPerHa: Double,
    val tractorId: String,
    val tractorName: String,
    val owned: Boolean,
    val implementName: String,
    val workingWidthM: Double,
    val speedKmh: Double,
    val efficiencyPct: Double,
    val capacityHaH: Double,
    val durationHours: Double,
    val reason: String,
)

/** Picks the tractor with the lowest total cost for the job, among those with enough power. */
fun recommendMachine(
    areaHa: Double,
    operation: WorkOperation,
    tractors: List<TractorOption>,
    implements: List<ImplementEntry>? = null,
    fuelPricePerL: Double = DEFAULT_FUEL_PRICE_EUR,
    slopePct: Double? = null,
): MachineRecommendation? {
    val implement = (implements ?: IMPLEMENT_CATALOG).firstOrNull { it.operation == operation }
    if (implement == null || areaHa <= 0) return null
    val powerShare = 1 - min(0.5, max(0.0, slopePct ?: 0.0) * SLOPE_POWER_LOSS_PER_PCT)

    val options = tractors.mapNotNull { tractor ->
        val usable = tractor.horsePower * powerShare
        val workingWidthM = max(0.0, implement.widthsM.filter { it * implement.hpPerMeter <= usable }.maxOrNull() ?: 0.0)
        if (workingWidthM == 0.0) return@mapNotNull null
        val capacityHaH = roundDigits((workingWidthM * implement.speedKmh * implement.efficiencyPct) / 1000, 2)
        val durationHours = roundDigits(areaHa / capacityHaH, 2)
        val min = tractor.fuelConsumptionMin ?: (tractor.horsePower * 0.105)
        val max = tractor.fuelConsumptionMax ?: (tractor.horsePower * 0.16)
        val cost = workCost(fuelLitersPerHour(min, max, implement.fuelLoad), durationHours, fuelPricePerL, areaHa)
        val recommendation = MachineRecommendation(
            fuelLPerH = cost.fuelLPerH,
            fuelLiters = cost.fuelLiters,
            fuelCost = cost.fuelCost,
            costPerHa = cost.costPerHa,
            tractorId = tractor.id,
            tractorName = tractor.name,
            owned = tractor.owned,
            implementName = implement.name,
            workingWidthM = workingWidthM,
            speedKmh = implement.speedKmh,
            efficiencyPct = implement.efficiencyPct,
            capacityHaH = capacityHaH,
            durationHours = durationHours,
            reason = "${implement.name} of ${jsNum(workingWidthM)} m covers ${jsNum(capacityHaH)} ha/hour with ${jsNum(tractor.horsePower)} HP.",
        )
        recommendation to cost.fuelCost + durationHours * OPERATOR_COST_EUR_H
    }
    // Tractors from the farm's fleet take priority over catalog suggestions.
    val owned = options.filter { it.first.owned }
    return (owned.ifEmpty { options }).sortedBy { it.second }.firstOrNull()?.first
}

@Serializable
data class SeedCompatibility(val seed: SeedEntry, val score: Int, val reason: String, val missingData: List<String>)

private const val NEUTRAL_FIT = 0.65

/** Common approximation: one FAO point corresponds to about 3.5 degree-days above 10 °C. */
private const val GDD_PER_FAO = 3.5

private val DROUGHT_FIT = mapOf(CatalogLevel.LOW to 0.4, CatalogLevel.MEDIUM to 0.7, CatalogLevel.HIGH to 0.95)

/** Ranks compatible products by soil, climate, rotation and irrigation. */
fun rankSeedsForParcel(
    seeds: List<SeedEntry>,
    crop: CropCode,
    year: Int,
    history: List<CropHistoryEntry>,
    soil: SoilSummary? = null,
    climate: ClimateSummary? = null,
    irrigated: Boolean = false,
): List<SeedCompatibility> {
    val base = recommendCrops(RecommendationInput(year, history, soil, climate, listOf(crop))).first()
    val profile = crop.profile
    val requiredMm = (profile.waterMm.min + profile.waterMm.max) / 2

    return seeds.filter { it.crop == crop }.map { seed ->
        val fits = mutableListOf<Double>()
        val good = mutableListOf<String>()
        val weak = mutableListOf<String>()
        val missingData = mutableListOf<String>()
        val gdd = climate?.gddBase10

        if (seed.fao != null && gdd != null && gdd != 0.0) {
            val needed = seed.fao * GDD_PER_FAO
            val fit = if (gdd >= needed) 1.0 else max(0.0, 1 - (needed - gdd) / 400)
            fits.add(fit)
            if (fit == 1.0) good.add("FAO ${jsNum(seed.fao)} reaches maturity in the parcel's climate")
            else weak.add("FAO ${jsNum(seed.fao)} needs a longer season than the local one")
        } else {
            fits.add(NEUTRAL_FIT)
            if (seed.fao == null) missingData.add("FAO")
        }

        val tolerance = seed.droughtTolerance
        when {
            irrigated -> {
                fits.add(1.0)
                good.add("the parcel is irrigated")
            }
            climate != null && climate.annualPrecipitationMm >= requiredMm -> {
                fits.add(1.0)
                good.add("rainfall covers the water requirement")
            }
            tolerance != null -> {
                fits.add(DROUGHT_FIT.getValue(tolerance))
                if (tolerance == CatalogLevel.HIGH) good.add("it tolerates drought well")
                else weak.add("drought tolerance is limited for a non-irrigated parcel")
            }
            else -> {
                fits.add(NEUTRAL_FIT)
                missingData.add("drought tolerance")
            }
        }

        if (soil != null && seed.soilPreference.isNotEmpty()) {
            val matches = soil.texture in seed.soilPreference
            fits.add(if (matches) 1.0 else 0.5)
            if (matches) good.add("the soil texture is suitable")
            else weak.add("the soil texture is not among the preferred ones")
        }

        val fit = fits.sum() / fits.size
        val score = jsRoundInt(base.score * 0.6 + fit * 40)
        val sentences = listOfNotNull(
            if (good.isNotEmpty()) "${seed.name} is a good fit because ${joinClauses(good)}." else null,
            if (weak.isNotEmpty()) "Note: ${joinClauses(weak)}." else null,
            if (missingData.isNotEmpty()) "Indicative score, missing: ${joinClauses(missingData)}." else null,
        )
        SeedCompatibility(seed, score, sentences.joinToString(" ").ifEmpty { "${seed.name} is compatible with this crop." }, missingData)
    }.sortedWith(compareByDescending<SeedCompatibility> { it.score }.then(localeComparator { it.seed.name }))
}

enum class SeedSort { NAME, FAO, YIELD }

data class SeedFilters(
    val search: String? = null,
    val crop: CropCode? = null,
    val brand: String? = null,
    val droughtTolerance: CatalogLevel? = null,
    val waterRequirement: CatalogLevel? = null,
    val yieldPotential: CatalogLevel? = null,
    val faoMin: Double? = null,
    val faoMax: Double? = null,
    val sort: SeedSort = SeedSort.NAME,
)

fun filterSeeds(seeds: List<SeedEntry>, filters: SeedFilters): List<SeedEntry> {
    val query = normalizedText(filters.search)
    val result = seeds.filter { seed ->
        (query.isEmpty() || normalizedText("${seed.name} ${seed.brand ?: ""} ${seed.crop.profile.name}").contains(query)) &&
            (filters.crop == null || seed.crop == filters.crop) &&
            (filters.brand.isNullOrEmpty() || seed.brand == filters.brand) &&
            (filters.droughtTolerance == null || seed.droughtTolerance == filters.droughtTolerance) &&
            (filters.waterRequirement == null || seed.waterRequirement == filters.waterRequirement) &&
            (filters.yieldPotential == null || seed.yieldPotential == filters.yieldPotential) &&
            (filters.faoMin == null || (seed.fao != null && seed.fao >= filters.faoMin)) &&
            (filters.faoMax == null || (seed.fao != null && seed.fao <= filters.faoMax))
    }
    return when (filters.sort) {
        SeedSort.FAO -> result.sortedBy { it.fao ?: Double.POSITIVE_INFINITY }
        SeedSort.YIELD -> result.sortedByDescending { it.expectedYieldMax ?: -1.0 }
        SeedSort.NAME -> result.sortedWith(localeComparator { it.name })
    }
}

enum class TractorSort { NAME, POWER }

fun filterTractors(tractors: List<TractorEntry>, search: String? = null, brand: String? = null, sort: TractorSort = TractorSort.NAME): List<TractorEntry> {
    val query = normalizedText(search)
    val result = tractors.filter { tractor ->
        (query.isEmpty() || normalizedText("${tractor.brand} ${tractor.model} ${tractor.series ?: ""}").contains(query)) &&
            (brand.isNullOrEmpty() || tractor.brand == brand)
    }
    return when (sort) {
        TractorSort.POWER -> result.sortedByDescending { it.horsePower }
        TractorSort.NAME -> result.sortedWith(localeComparator(numeric = true) { "${it.brand} ${it.model}" })
    }
}

private val CROP_ALIASES: Map<String, CropCode> = mapOf(
    "porumb" to CropCode.CORN, "corn" to CropCode.CORN, "maize" to CropCode.CORN,
    "grau" to CropCode.WHEAT, "wheat" to CropCode.WHEAT,
    "orz" to CropCode.BARLEY, "barley" to CropCode.BARLEY,
    "floarea-soarelui" to CropCode.SUNFLOWER, "floarea soarelui" to CropCode.SUNFLOWER, "sunflower" to CropCode.SUNFLOWER,
    "rapita" to CropCode.RAPESEED, "rapeseed" to CropCode.RAPESEED,
    "soia" to CropCode.SOY, "soy" to CropCode.SOY, "soybean" to CropCode.SOY,
    "lucerna" to CropCode.ALFALFA, "alfalfa" to CropCode.ALFALFA,
    "ovaz" to CropCode.OAT, "oat" to CropCode.OAT, "oats" to CropCode.OAT,
    "cartof" to CropCode.POTATO, "potato" to CropCode.POTATO,
    "sfecla" to CropCode.BEET, "sfecla de zahar" to CropCode.BEET, "beet" to CropCode.BEET,
    "legume" to CropCode.VEGETABLES, "vegetables" to CropCode.VEGETABLES,
)

private val LEVEL_ALIASES: Map<String, CatalogLevel> = mapOf(
    "low" to CatalogLevel.LOW, "scazuta" to CatalogLevel.LOW, "scazut" to CatalogLevel.LOW, "mica" to CatalogLevel.LOW,
    "medium" to CatalogLevel.MEDIUM, "medie" to CatalogLevel.MEDIUM, "mediu" to CatalogLevel.MEDIUM,
    "high" to CatalogLevel.HIGH, "ridicata" to CatalogLevel.HIGH, "ridicat" to CatalogLevel.HIGH, "mare" to CatalogLevel.HIGH,
)

private val HEADER_SEPARATORS = Regex("[\\s_-]+")

private fun cellOf(row: Map<String, String>, aliases: List<String>): String? {
    val cells = row.entries.associate { (key, value) -> normalizedText(key).replace(HEADER_SEPARATORS, "") to value.trim() }
    for (alias in aliases) {
        val value = cells[alias]
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

private fun numberOf(row: Map<String, String>, aliases: List<String>): Double? {
    val value = cellOf(row, aliases) ?: return null
    return value.replaceFirst(',', '.').trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun levelOf(row: Map<String, String>, aliases: List<String>) = LEVEL_ALIASES[normalizedText(cellOf(row, aliases))]

/** Result of reading one imported row: the entry, or the reason it was skipped. */
sealed interface RowResult<out T> {
    data class Ok<T>(val value: T) : RowResult<T>
    data class Error(val message: String) : RowResult<Nothing>
}

/** Converts a row from the imported file into a catalog product. */
fun seedFromRow(row: Map<String, String>): RowResult<SeedEntry> {
    val name = cellOf(row, listOf("name", "nume", "hibrid", "soi", "produs")) ?: return RowResult.Error("The product name is missing.")
    val crop = CROP_ALIASES[normalizedText(cellOf(row, listOf("crop", "cultura")))]
        ?: return RowResult.Error("$name: the crop is not recognized.")
    val type = when (normalizedText(cellOf(row, listOf("type", "tip")))) {
        "soi", "variety" -> SeedType.VARIETY
        "hibrid", "hybrid" -> SeedType.HYBRID
        else -> if (crop == CropCode.WHEAT || crop == CropCode.SOY) SeedType.VARIETY else SeedType.HYBRID
    }
    return RowResult.Ok(
        createSeedEntry(
            name = name,
            crop = crop,
            type = type,
            brand = cellOf(row, listOf("brand", "producator", "marca")),
            fao = numberOf(row, listOf("fao")),
            vegetationDays = numberOf(row, listOf("vegetationdays", "zilevegetatie", "zile")),
            expectedYieldMin = numberOf(row, listOf("expectedyieldmin", "productiemin")),
            expectedYieldMax = numberOf(row, listOf("expectedyieldmax", "productiemax")),
            waterRequirement = levelOf(row, listOf("waterrequirement", "necesarapa")),
            droughtTolerance = levelOf(row, listOf("droughttolerance", "tolerance", "toleranta", "tolerantaseceta")),
            notes = cellOf(row, listOf("notes", "observatii")),
        ),
    )
}

fun tractorFromRow(row: Map<String, String>): RowResult<TractorEntry> {
    val brand = cellOf(row, listOf("brand", "marca", "producator"))
    val model = cellOf(row, listOf("model"))
    if (brand == null || model == null) return RowResult.Error("The brand or model is missing.")
    val horsePower = numberOf(row, listOf("horsepower", "cp", "putere", "hp"))
    if (horsePower == null || horsePower <= 0) return RowResult.Error("$brand $model: the power is not valid.")
    return RowResult.Ok(
        createTractorEntry(
            brand = brand,
            model = model,
            horsePower = horsePower,
            series = cellOf(row, listOf("series", "serie")),
            fuelConsumptionMin = numberOf(row, listOf("fuelconsumptionmin", "consummin")),
            fuelConsumptionMax = numberOf(row, listOf("fuelconsumptionmax", "consummax")),
            tankCapacity = numberOf(row, listOf("tankcapacity", "rezervor")),
            weight = numberOf(row, listOf("weight", "greutate", "masa")),
            driveSystem = cellOf(row, listOf("drivesystem", "tractiune")) ?: "4x4",
        ),
    )
}

/** Reads a CSV file, with comma or semicolon separator. */
fun parseCsv(text: String): List<Map<String, String>> {
    val clean = text.removePrefix("﻿")
    val firstLine = clean.lineSequence().firstOrNull() ?: ""
    val separator = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < clean.length) {
        val char = clean[i]
        if (quoted) {
            if (char == '"' && clean.getOrNull(i + 1) == '"') {
                cell.append('"')
                i++
            } else if (char == '"') {
                quoted = false
            } else {
                cell.append(char)
            }
        } else if (char == '"') {
            quoted = true
        } else if (char == separator) {
            row.add(cell.toString())
            cell.clear()
        } else if (char == '\n' || char == '\r') {
            if (char == '\r' && clean.getOrNull(i + 1) == '\n') i++
            row.add(cell.toString())
            rows.add(row)
            row = mutableListOf()
            cell.clear()
        } else {
            cell.append(char)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) rows.add(row + cell.toString())
    return tableToRows(rows)
}

/** The first line of the table contains the column names. */
fun tableToRows(table: List<List<String>>): List<Map<String, String>> {
    val header = table.firstOrNull() ?: return emptyList()
    return table.drop(1)
        .filter { row -> row.any { it.isNotBlank() } }
        .map { row -> header.mapIndexed { index, name -> name.trim() to (row.getOrNull(index) ?: "") }.toMap() }
}

package com.fermapp.api.domain.tillage

import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.model.CropCode
import java.util.EnumMap

/**
 * Soil tillage technologies (tillage.ts). Each one is a plain profile: a new technology is
 * added to [SYSTEMS] and every calculation below picks it up.
 */
object Tillage {

    enum class TillageSystem { CONVENTIONAL, MINIMUM_TILL, STRIP_TILL, NO_TILL }

    /**
     * One recommended operation.
     *
     * @param workType the work log type used when the operation is started
     * @param taskType the task type used when the operation is planned
     * @param offsetDays days after the first operation, used to space the generated tasks
     */
    @JvmRecord
    data class TillageOperation(val key: String, val label: String, val workType: String, val taskType: String, val offsetDays: Int)

    /**
     * Profile of a technology.
     *
     * @param soilHealth 0-100
     * @param waterRetention 1-5 stars
     * @param waterGainPct extra water held compared with conventional tillage
     * @param passes machine passes over the field in a season, up to sowing
     * @param discouragedWork work types that contradict the technology, with the reason shown to the user
     */
    @JvmRecord
    data class TillageProfile(
        val code: TillageSystem,
        val name: String,
        val badge: String,
        val color: String,
        val description: String,
        val costPerHa: Double,
        val fuelLPerHa: Double,
        val soilHealth: Int,
        val waterRetention: Int,
        val waterGainPct: Int,
        val passes: Int,
        val operations: List<TillageOperation>,
        val discouragedWork: Map<String, String>,
        val discouragedTasks: Map<String, String>,
    )

    @JvmRecord
    data class TillageHistoryEntry(val year: Int, val system: TillageSystem)

    @JvmRecord
    data class TillageAdvice(val tone: String, val message: String)

    @JvmRecord
    data class ParcelTillageEstimate(
        val system: TillageSystem,
        val areaHa: Double,
        val costPerHa: Double,
        val totalCost: Double,
        val costSavings: Double,
        val fuelPerHa: Double,
        val seasonFuelL: Double,
        val annualFuelL: Double,
        val annualFuelSavingsL: Double,
        val passes: Int,
        val passesSaved: Int,
        val soilHealth: Int,
        val waterRetention: Int,
        val waterGainPct: Int,
    )

    @JvmField
    val BASELINE: TillageSystem = TillageSystem.CONVENTIONAL

    private val ROW_CROPS = setOf(CropCode.CORN, CropCode.SUNFLOWER, CropCode.SOY, CropCode.BEET)

    private fun op(key: String, label: String, workType: String, taskType: String, offsetDays: Int) =
        TillageOperation(key, label, workType, taskType, offsetDays)

    @JvmField
    val SYSTEMS: Map<TillageSystem, TillageProfile> = EnumMap<TillageSystem, TillageProfile>(TillageSystem::class.java).apply {
        put(
            TillageSystem.CONVENTIONAL,
            TillageProfile(
                TillageSystem.CONVENTIONAL, "Conventional Tillage", "CONV", "#DE4467",
                "Full inversion with the plough, followed by secondary tillage.", 520.0, 60.0, 60, 2, 0, 4,
                listOf(
                    op("plowing", "Ploughing", "PLOWING", "SOIL_PREP", 0),
                    op("discing", "Discing", "DISCING", "SOIL_PREP", 10),
                    op("seedbed", "Seedbed preparation", "DISCING", "SOIL_PREP", 18),
                    op("sowing", "Sowing", "SOWING", "SOWING", 21),
                ),
                mapOf(), mapOf(),
            ),
        )
        put(
            TillageSystem.MINIMUM_TILL,
            TillageProfile(
                TillageSystem.MINIMUM_TILL, "Minimum Till", "MIN", "#B48A10",
                "Loosening without inversion; residues stay near the surface.", 420.0, 35.0, 75, 4, 15, 3,
                listOf(
                    op("subsoiling", "Subsoiling", "SUBSOILING", "SOIL_PREP", 0),
                    op("chiselling", "Chiselling", "SUBSOILING", "SOIL_PREP", 7),
                    op("shallow-discing", "Shallow discing", "DISCING", "SOIL_PREP", 14),
                    op("sowing", "Sowing", "SOWING", "SOWING", 18),
                ),
                mapOf("PLOWING" to "Ploughing inverts the soil and cancels the benefits of Minimum Till."),
                mapOf(),
            ),
        )
        put(
            TillageSystem.STRIP_TILL,
            TillageProfile(
                TillageSystem.STRIP_TILL, "Strip Till", "STRIP", "#3A8FD0",
                "Only the seed row strips are tilled and fertilised.", 350.0, 25.0, 82, 4, 18, 2,
                listOf(
                    op("strip-prep", "Strip preparation", "SUBSOILING", "SOIL_PREP", 0),
                    op("band-fertilizing", "Band fertilisation", "FERTILIZING", "FERTILIZATION", 0),
                    op("strip-sowing", "Strip sowing", "SOWING", "SOWING", 10),
                ),
                mapOf(
                    "PLOWING" to "Ploughing destroys the untilled strips between rows.",
                    "DISCING" to "Full-width discing works the strips that Strip Till keeps covered.",
                ),
                mapOf(),
            ),
        )
        put(
            TillageSystem.NO_TILL,
            TillageProfile(
                TillageSystem.NO_TILL, "No Till", "NO-TILL", "#58A23A",
                "Direct sowing into the residue of the previous crop.", 250.0, 12.0, 90, 5, 20, 1,
                listOf(op("direct-sowing", "Direct sowing", "SOWING", "SOWING", 0)),
                mapOf(
                    "PLOWING" to "No Till does not plough. Ploughing breaks the soil structure built up over years.",
                    "DISCING" to "No Till does not disc. Discing buries the residue that protects the soil.",
                    "SUBSOILING" to "No Till does not loosen the soil before sowing.",
                ),
                mapOf("SOIL_PREP" to "No Till skips seedbed preparation; sow directly into the residue."),
            ),
        )
    }

    @JvmStatic
    fun profile(system: TillageSystem): TillageProfile = SYSTEMS.getValue(system)

    /** `isTillageSystem(value)`: the value as a known technology, or null. */
    @JvmStatic
    fun parse(value: String?): TillageSystem? = TillageSystem.entries.firstOrNull { it.name == value }

    @JvmStatic
    fun costPerHa(system: TillageSystem): Double = profile(system).costPerHa

    @JvmStatic
    fun fuelPerHa(system: TillageSystem): Double = profile(system).fuelLPerHa

    @JvmStatic
    fun estimateParcelTillage(system: TillageSystem, areaHa: Double): ParcelTillageEstimate {
        val profile = profile(system)
        val baseline = profile(BASELINE)
        val seasons = 1
        val costPerHa = costPerHa(system)
        val fuelPerHa = fuelPerHa(system)
        val seasonFuelL = fuelPerHa * areaHa
        val baselineFuel = fuelPerHa(BASELINE) * areaHa * seasons
        return ParcelTillageEstimate(
            system, areaHa, costPerHa,
            Js.round(costPerHa * areaHa * seasons),
            Js.round((costPerHa(BASELINE) - costPerHa) * areaHa * seasons),
            fuelPerHa,
            Js.round(seasonFuelL),
            Js.round(seasonFuelL * seasons),
            Js.round(baselineFuel - seasonFuelL * seasons),
            profile.passes, baseline.passes - profile.passes,
            profile.soilHealth, profile.waterRetention, profile.waterGainPct,
        )
    }

    /** Advice for the technology, adjusted to the crop. */
    @JvmStatic
    fun tillageRecommendations(system: TillageSystem, crop: CropCode?): List<TillageAdvice> {
        val rowCrop = crop != null && crop in ROW_CROPS
        val advice = mutableListOf<TillageAdvice>()
        when (system) {
            TillageSystem.CONVENTIONAL ->
                advice += TillageAdvice("warning", "High compaction risk from repeated passes. Consider Minimum Till.")
            TillageSystem.MINIMUM_TILL -> {
                advice += TillageAdvice("success", "A good fit for corn and sunflower.")
                if (rowCrop) {
                    advice += TillageAdvice("info", "For row crops, Strip Till can cut costs further with banded fertiliser.")
                }
            }
            TillageSystem.STRIP_TILL -> {
                advice += TillageAdvice("success", "Efficient for row crops and placed fertilisation.")
                if (crop != null && !rowCrop) {
                    advice += TillageAdvice(
                        "warning",
                        "Strip Till pays off mostly in wide-row crops; check the row spacing of this crop.",
                    )
                }
            }
            TillageSystem.NO_TILL -> {
                advice += TillageAdvice("success", "Excellent moisture conservation in dry conditions.")
                advice += TillageAdvice("info", "Use a No Till drill and manage residue and weeds closely in the first years.")
            }
        }
        return advice
    }

    /** The technology a parcel used in a year: the latest history entry up to that year. */
    @JvmStatic
    fun tillageInYear(history: List<TillageHistoryEntry>, year: Int): TillageSystem? =
        history.filter { it.year <= year }.maxByOrNull { it.year }?.system

    /** Sets the technology of one year in a history, keeping one entry per year, oldest first. */
    @JvmStatic
    fun withTillageYear(history: List<TillageHistoryEntry>, entry: TillageHistoryEntry): List<TillageHistoryEntry> =
        (history.filter { it.year != entry.year } + entry).sortedBy { it.year }.toMutableList()

    /** Reads a stored history, dropping entries for unknown technologies. */
    @JvmStatic
    fun parseTillageHistory(value: JsonNode?): List<TillageHistoryEntry> {
        if (value == null || !value.isArray) {
            return listOf()
        }
        return value
            .mapNotNull { item ->
                val system = parse(item?.path("system")?.asText(null))
                if (item != null && item.path("year").isNumber && system != null) {
                    TillageHistoryEntry(item.path("year").asInt(), system)
                } else {
                    null
                }
            }
            .sortedBy { it.year }
            .toMutableList()
    }
}

package com.fermapp.api.modules.economics

import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics
import com.fermapp.api.domain.economics.EconomicModels.OperationInput
import com.fermapp.api.domain.economics.EconomicModels.OperationMachine
import com.fermapp.api.domain.economics.EconomicModels.OperationOperator
import com.fermapp.api.domain.economics.EconomicModels.RecordedCost
import com.fermapp.api.domain.economics.Economics
import com.fermapp.api.domain.economics.Money
import com.fermapp.api.domain.tillage.Tillage
import com.fermapp.api.modules.work.RunProgress
import com.fermapp.api.persistence.entity.CostEntry
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.entity.EconomicDocument
import com.fermapp.api.persistence.entity.WorkSession
import com.fermapp.api.persistence.repository.CostEntryRepository
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.EconomicDocumentRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Economic documents and the real cost and profit of every parcel (economics.service.ts). */
@Service
class EconomicsService(
    private val documents: EconomicDocumentRepository,
    private val parcels: ParcelRepository,
    private val seasons: CropSeasonRepository,
    private val costs: CostEntryRepository,
    private val works: WorkSessionRepository,
    private val mapper: ObjectMapper,
) {

    /** The farm's economics for a season: settings, one result per parcel and the summary. */
    @JvmRecord
    data class FarmEconomicsResult(
        val year: Int,
        val settings: EconomicSettings,
        val parcels: List<FarmParcelEconomics>,
        val summary: FarmEconomics,
    )

    fun loadSettings(farmId: UUID): EconomicSettings =
        Economics.parseSettings(documents.findByFarmIdAndKey(farmId, SETTINGS_KEY).map { it.data }.orElse(null))

    @Transactional
    fun saveSettings(farmId: UUID, settings: EconomicSettings): EconomicSettings {
        val document = documents.findByFarmIdAndKey(farmId, SETTINGS_KEY).orElseGet {
            EconomicDocument().apply {
                this.farmId = farmId
                key = SETTINGS_KEY
            }
        }
        document.data = mapper.valueToTree(settings)
        return Economics.parseSettings(documents.saveAndFlush(document).data)
    }

    fun loadSheets(farmId: UUID, year: Int): Map<UUID, EconomicSheet> {
        val sheets = LinkedHashMap<UUID, EconomicSheet>()
        for (document in documents.findSheets(farmId, year)) {
            val parcelId = document.parcelId
            if (parcelId != null) {
                sheets[parcelId] = Economics.parseSheet(document.data)
            }
        }
        return sheets
    }

    fun loadSheet(farmId: UUID, parcelId: UUID, year: Int): EconomicSheet =
        Economics.parseSheet(documents.findByFarmIdAndKey(farmId, sheetKey(parcelId, year)).map { it.data }.orElse(null))

    @Transactional
    fun saveSheet(farmId: UUID, parcelId: UUID, year: Int, sheet: EconomicSheet): EconomicSheet {
        val key = sheetKey(parcelId, year)
        val document = documents.findByFarmIdAndKey(farmId, key).orElseGet {
            EconomicDocument().apply {
                this.farmId = farmId
                this.key = key
                this.parcelId = parcelId
                this.year = year
            }
        }
        document.data = mapper.valueToTree(sheet)
        return Economics.parseSheet(documents.saveAndFlush(document).data)
    }

    /**
     * Real cost and profit of every parcel in a season: the sheet's inputs, the work log priced
     * with the farm's diesel, machine and labour figures, the expenses recorded by category
     * and the operations still to come.
     */
    @Transactional(readOnly = true)
    fun farmEconomics(farmId: UUID, year: Int, parcelId: UUID?): FarmEconomicsResult {
        val start = Times.utc(year, 1, 1, 0)
        val end = Times.utc(year + 1, 1, 1, 0)
        val list = parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId).filter { parcelId == null || it.id == parcelId }
        val ids = list.map { it.id }
        val settings = loadSettings(farmId)
        val sheets = loadSheets(farmId, year)
        // The first row wins when a parcel has two, as Collectors.toMap with (a, b) -> a did.
        val seasonByParcel = HashMap<UUID, CropSeason>()
        val costsByParcel: Map<UUID, List<CostEntry>>
        val workByParcel: Map<UUID, List<WorkSession>>
        if (ids.isEmpty()) {
            costsByParcel = mapOf()
            workByParcel = mapOf()
        } else {
            seasons.findByParcelIdInAndYear(ids, year).forEach { seasonByParcel.putIfAbsent(it.parcelId, it) }
            costsByParcel = costs.findByParcelIdInAndYear(ids, year).groupBy { it.parcelId }
            workByParcel = works.findByParcelIdInAndStartedAtGreaterThanEqualAndStartedAtBefore(ids, start, end).groupBy { it.parcelId }
        }
        val now = Times.now()

        val items = list.map { parcel ->
            val crop = seasonByParcel[parcel.id]?.crop
            val operations = workByParcel[parcel.id].orEmpty().map { work ->
                val percent = RunProgress.of(work, now).percent
                val machine = work.machine
                // Progress follows the clock, so the hours worked are the same share of the planned duration.
                OperationInput(
                    work.id.toString(), work.type.name, work.status.name, work.areaHa, percent, work.durationHours * percent / 100,
                    work.fuelLiters,
                    machine?.let { OperationMachine(it.id.toString(), it.name, it.type.name, it.avgConsumptionLh) },
                    OperationOperator(work.operator.id.toString(), work.operator.fullName),
                    Times.iso(work.startedAt),
                )
            }
            val tillage = Tillage.parse(parcel.tillageSystem)
            val recorded = costsByParcel[parcel.id].orEmpty().map { RecordedCost(it.category, Money.toEuro(it.amount.toDouble(), it.currency)) }
            val result = Economics.computeParcelEconomics(
                parcel.areaHa, sheets[parcel.id] ?: Economics.parseSheet(null), operations, settings, tillage, crop, recorded,
            )
            FarmParcelEconomics(parcel.id.toString(), parcel.name, crop, crop?.let { Crops.name(it) }, tillage, result)
        }
        return FarmEconomicsResult(year, settings, items, Economics.summarizeFarmEconomics(items))
    }

    companion object {
        const val SETTINGS_KEY = "settings"

        @JvmStatic
        fun sheetKey(parcelId: UUID, year: Int): String = "sheet:$parcelId:$year"
    }
}

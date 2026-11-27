package com.fermapp.api.modules.parcels

import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.geo.Geo
import com.fermapp.api.domain.geo.Geo.Polygon
import com.fermapp.api.domain.tillage.Tillage
import com.fermapp.api.domain.tillage.Tillage.TillageHistoryEntry
import com.fermapp.api.domain.tillage.Tillage.TillageSystem
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.CropSeasonView
import com.fermapp.api.modules.common.Views.ParcelView
import com.fermapp.api.modules.common.Views.SatelliteView
import com.fermapp.api.modules.common.Views.SoilProfileView
import com.fermapp.api.modules.parcels.GeoIo.ExportFile
import com.fermapp.api.modules.parcels.GeoIo.ExportParcel
import com.fermapp.api.modules.parcels.ParcelDtos.ImportPreview
import com.fermapp.api.modules.parcels.ParcelDtos.ImportResponse
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDetail
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDraft
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelListItem
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelUpdate
import com.fermapp.api.modules.parcels.ParcelDtos.SatelliteBrief
import com.fermapp.api.modules.parcels.ParcelDtos.SeasonBrief
import com.fermapp.api.modules.parcels.ParcelDtos.SoilBrief
import com.fermapp.api.modules.soil.SoilService
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.entity.SatelliteObservation
import com.fermapp.api.persistence.entity.SoilProfile
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.persistence.repository.SatelliteObservationRepository
import com.fermapp.api.persistence.repository.SoilProfileRepository
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Parcels: listing, creation, import and export, outline changes and tillage history (parcels.routes.ts). */
@Service
class ParcelService(
    private val parcels: ParcelRepository,
    private val seasons: CropSeasonRepository,
    private val soilProfiles: SoilProfileRepository,
    private val satellite: SatelliteObservationRepository,
    private val soil: SoilService,
    private val geoIo: GeoIo,
    private val access: AccessGuard,
    private val mapper: ObjectMapper,
) {

    private fun nextCode(farmId: UUID): String = "P-" + Text.padNumber(parcels.countByFarmId(farmId) + 1, 4)

    /** Area, perimeter and centre, computed from the outline. */
    private fun applyGeometry(parcel: Parcel, geometry: Polygon) {
        val metrics = Geo.computeGeoMetrics(geometry)
        parcel.geometry = mapper.valueToTree(geometry)
        parcel.areaHa = metrics.areaHa
        parcel.perimeterM = metrics.perimeterM
        parcel.centerLat = metrics.center.lat
        parcel.centerLng = metrics.center.lng
    }

    /** The data required to create a parcel; the technology chosen at creation is also the first entry of its history. */
    fun newParcel(farmId: UUID, draft: ParcelDraft): Parcel {
        val parcel = Parcel()
        parcel.farmId = farmId
        parcel.name = draft.name
        parcel.code = draft.code ?: nextCode(farmId)
        parcel.locality = draft.locality
        parcel.county = draft.county
        parcel.notes = draft.notes
        parcel.irrigatedAreaHa = draft.irrigatedAreaHa
        parcel.tillageSystem = draft.tillageSystem?.name
        if (draft.tillageSystem != null) {
            parcel.tillageHistory = mapper.valueToTree(listOf(TillageHistoryEntry(Times.currentLocalYear(), draft.tillageSystem)))
        }
        applyGeometry(parcel, draft.geometry!!.toPolygon())
        return parcel
    }

    @Transactional(readOnly = true)
    fun list(farmId: UUID, archived: Boolean, search: String?): List<ParcelListItem> {
        val year = Times.currentLocalYear()
        val found = parcels.search(farmId, archived, Text.containsPattern(search))
        val ids = found.map { it.id }
        if (ids.isEmpty()) {
            return listOf()
        }
        // The first row wins when a parcel appears twice, as Collectors.toMap with (a, b) -> a did.
        val current = HashMap<UUID, CropSeason>()
        seasons.findByParcelIdInAndYear(ids, year).forEach { current.putIfAbsent(it.parcelId, it) }
        val soils = HashMap<UUID, SoilProfile>()
        soilProfiles.findByParcelIdIn(ids).forEach { soils.putIfAbsent(it.parcelId, it) }
        val latest = HashMap<UUID, SatelliteObservation>()
        satellite.findLatestFor(ids).forEach { latest.putIfAbsent(it.parcelId, it) }
        return found.map { parcel ->
            val season = current[parcel.id]
            val profile = soils[parcel.id]
            val observation = latest[parcel.id]
            ParcelListItem(
                ParcelView.of(parcel),
                if (season == null) listOf() else listOf(SeasonBrief(season.crop, season.year)),
                profile?.let { SoilBrief(it.status, it.label) },
                if (observation == null) {
                    listOf()
                } else {
                    listOf(SatelliteBrief(Times.atUtcMidnight(observation.date), observation.ndvi, observation.healthIndex, observation.healthClass))
                },
            )
        }
    }

    fun create(farmId: UUID, draft: ParcelDraft): ParcelView {
        val parcel = parcels.saveAndFlush(newParcel(farmId, draft))
        soil.loadInBackground(parcel)
        return ParcelView.of(parcel)
    }

    fun importFile(farmId: UUID, format: String, dryRun: Boolean, content: ByteArray): ImportResponse {
        val result = geoIo.parseGeoFile(format, content)
        val preview = result.parcels.map { ImportPreview(it.name, it.code, Geo.computeGeoMetrics(it.geometry).areaHa) }
        if (dryRun) {
            return ImportResponse(true, preview, result.problems, 0)
        }
        val existing = parcels.findByFarmId(farmId)
        val usedCodes = existing.map { it.code }.toHashSet()
        var sequence = existing.size.toLong()
        fun assignCode(preferred: String?): String {
            if (preferred != null && preferred !in usedCodes) {
                usedCodes += preferred
                return preferred
            }
            var code: String
            do {
                sequence += 1
                code = "P-" + Text.padNumber(sequence, 4)
            } while (code in usedCodes)
            usedCodes += code
            return code
        }
        val created = result.parcels.map { imported ->
            Parcel().also { parcel ->
                parcel.farmId = farmId
                parcel.name = Text.slice(imported.name, 120)
                parcel.code = assignCode(imported.code)
                parcel.locality = imported.locality
                parcel.county = imported.county
                parcel.notes = imported.notes
                applyGeometry(parcel, imported.geometry)
            }
        }
        val saved = parcels.saveAllAndFlush(created)
        saved.forEach { soil.loadInBackground(it) }
        return ImportResponse(false, preview, result.problems, saved.size)
    }

    fun export(farmId: UUID, format: String): ExportFile {
        val list = parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId).map {
            ExportParcel(
                it.name, it.code, it.areaHa, it.perimeterM, it.locality, it.county, it.notes,
                mapper.convertValue(it.geometry, Polygon::class.java),
            )
        }
        return geoIo.exportParcels(format, list)
    }

    @Transactional(readOnly = true)
    fun detail(farmId: UUID, id: UUID): ParcelDetail {
        val parcel = access.requireParcel(farmId, id)
        return ParcelDetail(
            ParcelView.of(parcel),
            seasons.findTop10ByParcelIdOrderByYearDesc(id).map { CropSeasonView.raw(it) },
            SoilProfileView.of(soilProfiles.findByParcelId(id).orElse(null)),
            satellite.findByParcelIdOrderByDateDesc(id, PageRequest.of(0, 1)).map { SatelliteView.of(it) },
        )
    }

    fun update(farmId: UUID, id: UUID, body: ParcelUpdate): ParcelView {
        val parcel = access.requireParcel(farmId, id)
        body.name.ifPresent { parcel.name = it }
        body.code.ifPresent { parcel.code = it }
        body.locality.ifPresent { parcel.locality = it }
        body.county.ifPresent { parcel.county = it }
        body.notes.ifPresent { parcel.notes = it }
        body.irrigatedAreaHa.ifPresent { parcel.irrigatedAreaHa = it }
        val redrawn = body.geometry.isPresent
        if (redrawn) {
            applyGeometry(parcel, body.geometry.get().toPolygon())
        }
        val saved = parcels.saveAndFlush(parcel)
        if (redrawn) {
            soil.loadInBackground(saved)
        }
        return ParcelView.of(saved)
    }

    /** Sets the technology of a year; the current technology follows the history. */
    fun setTillage(farmId: UUID, id: UUID, system: TillageSystem, year: Int?): ParcelView {
        val parcel = access.requireParcel(farmId, id)
        val history = Tillage.withTillageYear(
            Tillage.parseTillageHistory(parcel.tillageHistory),
            TillageHistoryEntry(year ?: Times.currentLocalYear(), system),
        )
        parcel.tillageSystem = Tillage.tillageInYear(history, Times.currentLocalYear())?.name
        parcel.tillageHistory = mapper.valueToTree(history)
        return ParcelView.of(parcels.saveAndFlush(parcel))
    }

    fun setArchived(farmId: UUID, id: UUID, archived: Boolean): ParcelView {
        val parcel = access.requireParcel(farmId, id)
        parcel.archivedAt = if (archived) Times.now() else null
        return ParcelView.of(parcels.saveAndFlush(parcel))
    }

    fun require(farmId: UUID, id: UUID): Parcel = parcels.findByIdAndFarmId(id, farmId).orElseThrow { Errors.notFound("Parcel") }
}

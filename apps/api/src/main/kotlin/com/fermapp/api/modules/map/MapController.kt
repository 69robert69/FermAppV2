package com.fermapp.api.modules.map

import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.intelligence.Intelligence
import com.fermapp.api.domain.intelligence.Intelligence.CropProgressStatus
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.modules.common.Views.MachineLocationView
import com.fermapp.api.modules.work.WorkService
import com.fermapp.api.modules.work.WorkViews
import com.fermapp.api.modules.work.WorkViews.IrrigationView
import com.fermapp.api.modules.work.WorkViews.LabelledIrrigation
import com.fermapp.api.modules.work.WorkViews.WorkView
import com.fermapp.api.modules.work.WorkViews.WorkWithLocation
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.entity.ParcelPlan
import com.fermapp.api.persistence.entity.SatelliteObservation
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.IrrigationRunRepository
import com.fermapp.api.persistence.repository.MachineLocationRepository
import com.fermapp.api.persistence.repository.ParcelPlanRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.persistence.repository.SatelliteObservationRepository
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Live status of every parcel for the map (map.routes.ts). */
@RestController
@RequestMapping("/api/v1/map")
@Tag(name = "Map")
class MapController(
    private val parcels: ParcelRepository,
    private val seasons: CropSeasonRepository,
    private val satellite: SatelliteObservationRepository,
    private val plans: ParcelPlanRepository,
    private val works: WorkSessionRepository,
    private val runs: IrrigationRunRepository,
    private val locations: MachineLocationRepository,
    private val tasks: TaskRepository,
) {

    @JvmRecord
    data class MapCrop(val code: CropCode, val name: String, val planned: Boolean, val year: Int)

    @JvmRecord
    data class MapParcel(
        val parcelId: UUID?,
        val crop: MapCrop?,
        val growthPct: Int?,
        val openTasks: Long,
        val ndvi: Double?,
        val work: List<WorkWithLocation>,
        val irrigation: List<LabelledIrrigation>,
    )

    @GetMapping("/overview")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Live status of all parcels, for display on the map")
    @Transactional(readOnly = true)
    fun overview(auth: AuthContext): ResponseEntity<ApiResponse<List<MapParcel>>> {
        val now = Times.now()
        val year = Times.localYear(now)
        val active = parcels.findByFarmIdAndArchivedAtIsNull(auth.farmId())
        val ids = active.map { it.id }
        if (ids.isEmpty()) {
            return ApiResponse.ok(listOf())
        }
        // The first row wins when a parcel appears twice, as Collectors.toMap with (a, b) -> a did.
        val seasonByParcel = LinkedHashMap<UUID, CropSeason>()
        seasons.findByParcelIdInAndYear(ids, year).forEach { seasonByParcel.putIfAbsent(it.parcelId, it) }
        val latest = LinkedHashMap<UUID, SatelliteObservation>()
        satellite.findLatestFor(ids).forEach { latest.putIfAbsent(it.parcelId, it) }
        val planByParcel = HashMap<UUID, ParcelPlan>()
        plans.findActiveFrom(ids, year).forEach { planByParcel.putIfAbsent(it.parcelId, it) }
        val work = works.findByFarmIdAndStatusInOrderByStartedAtAsc(auth.farmId(), WorkService.ACTIVE)
        val irrigation = runs.findByFarmIdAndStatusInOrderByStartedAtAsc(auth.farmId(), WorkService.ACTIVE)
        val openTasks = HashMap<UUID, Long>()
        for (row in tasks.countOpenByParcel(auth.farmId())) {
            openTasks[row[0] as UUID] = row[1] as Long
        }

        val result = active.map { parcel ->
            val planned = planByParcel[parcel.id]
            val recorded = seasonByParcel[parcel.id]
            // The last crop declared in the wizard is not the crop in the field when the plan starts in a future year.
            val season = if (recorded != null && planned != null && planned.year > year && recorded.sowingDate == null) {
                null
            } else {
                recorded
            }
            val code = season?.crop ?: planned?.crop
            val progress = season?.let {
                Intelligence.cropProgress(it.crop, it.sowingDate, it.harvestDate, it.expectedHarvestDate, now)
            }
            val parcelWork = work.filter { it.parcelId == parcel.id }.map { item ->
                WorkWithLocation(
                    WorkView.of(item, now),
                    MachineLocationView.of(locations.findFirstByWorkSessionIdOrderByRecordedAtDesc(item.id).orElse(null)),
                )
            }
            val labelled = irrigation.filter { it.parcelId == parcel.id }.mapIndexed { i, run ->
                LabelledIrrigation(IrrigationView.of(run, now), WorkViews.systemLabel(run.systemType) + " #" + (i + 1))
            }
            MapParcel(
                parcel.id,
                code?.let { MapCrop(it, Crops.name(it), season == null, season?.year ?: planned!!.year) },
                if (progress != null && progress.status != CropProgressStatus.NOT_SOWN) progress.percent else null,
                openTasks[parcel.id] ?: 0L,
                latest[parcel.id]?.ndvi,
                parcelWork,
                labelled,
            )
        }
        return ApiResponse.ok(result)
    }
}

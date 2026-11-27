package com.fermapp.api.modules.dashboard

import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.TaskStatus
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.modules.common.Views.AlertWithParcel
import com.fermapp.api.modules.common.Views.CropSeasonView
import com.fermapp.api.modules.common.Views.MachineView
import com.fermapp.api.modules.common.Views.ParcelView
import com.fermapp.api.modules.common.Views.TaskWithRefs
import com.fermapp.api.modules.work.WorkService
import com.fermapp.api.modules.work.WorkViews.IrrigationView
import com.fermapp.api.modules.work.WorkViews.WorkView
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.IrrigationRunRepository
import com.fermapp.api.persistence.repository.MachineRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.persistence.repository.WeatherAlertRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Main farm indicators and offline sync (dashboard.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Dashboard")
class DashboardController(
    private val parcels: ParcelRepository,
    private val seasons: CropSeasonRepository,
    private val works: WorkSessionRepository,
    private val runs: IrrigationRunRepository,
    private val tasks: TaskRepository,
    private val alerts: WeatherAlertRepository,
    private val machines: MachineRepository,
) {

    @JvmRecord
    data class ParcelTotals(val count: Int, val totalAreaHa: Double, val cultivatedAreaHa: Double, val freeAreaHa: Double)

    @JvmRecord
    data class CropArea(val crop: CropCode, val name: String, val areaHa: Double)

    @JvmRecord
    data class TaskCounts(val open: Long, val overdue: Long)

    @JvmRecord
    data class Dashboard(
        val year: Int,
        val parcels: ParcelTotals,
        val crops: List<CropArea>,
        val activeWork: List<WorkView>,
        val activeIrrigation: List<IrrigationView>,
        val tasks: TaskCounts,
        val alerts: List<AlertWithParcel>,
    )

    @JvmRecord
    data class SyncChanges(
        val serverTime: String?,
        val parcels: List<ParcelView>,
        val seasons: List<CropSeasonView>,
        val machines: List<MachineView>,
        val tasks: List<TaskWithRefs>,
        val work: List<WorkView>,
        val irrigation: List<IrrigationView>,
        val taskIds: List<UUID>,
    )

    @GetMapping("/dashboard")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Main farm indicators")
    @Transactional(readOnly = true)
    fun dashboard(auth: AuthContext): ResponseEntity<ApiResponse<Dashboard>> {
        val year = Times.currentLocalYear()
        val farmId = auth.farmId()
        val now = Times.now()
        val active = parcels.findByFarmIdAndArchivedAtIsNull(farmId)
        val current = seasons.findActiveForYear(farmId, year)

        val totalAreaHa = Js.round2(active.sumOfDoubles { it.areaHa })
        val cultivatedAreaHa = Js.round2(current.sumOfDoubles { it.parcel!!.areaHa })
        val byCrop = LinkedHashMap<CropCode, Double>()
        for (season in current) {
            byCrop.merge(season.crop, season.parcel!!.areaHa) { a, b -> a + b }
        }
        val crops = byCrop.map { (crop, area) -> CropArea(crop, Crops.name(crop), Js.round2(area)) }.sortedByDescending { it.areaHa }

        return ApiResponse.ok(
            Dashboard(
                year,
                ParcelTotals(active.size, totalAreaHa, cultivatedAreaHa, Js.round2(Math.max(0.0, totalAreaHa - cultivatedAreaHa))),
                crops,
                works.findByFarmIdAndStatusInOrderByStartedAtDesc(farmId, WorkService.ACTIVE).map { WorkView.of(it, now) },
                runs.findByFarmIdAndStatusInOrderByStartedAtDesc(farmId, WorkService.ACTIVE).map { IrrigationView.of(it, now) },
                TaskCounts(
                    tasks.countByFarmIdAndStatusNot(farmId, TaskStatus.DONE),
                    tasks.countByFarmIdAndStatusNotAndDueDateBefore(farmId, TaskStatus.DONE, now),
                ),
                alerts.findLatest(farmId, PageRequest.of(0, 5)).map { AlertWithParcel.of(it) },
            ),
        )
    }

    @GetMapping("/sync/changes")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Changes since the last offline sync")
    @Transactional(readOnly = true)
    fun changes(auth: AuthContext, @RequestParam(required = false) since: Instant?): ResponseEntity<ApiResponse<SyncChanges>> {
        val farmId = auth.farmId()
        val serverTime = Times.now()
        val latest = PageRequest.of(0, 500)
        return ApiResponse.ok(
            SyncChanges(
                Times.iso(serverTime),
                (if (since == null) parcels.findByFarmId(farmId) else parcels.findByFarmIdAndUpdatedAtGreaterThanEqual(farmId, since))
                    .map { ParcelView.of(it) },
                (if (since == null) seasons.findByFarm(farmId) else seasons.findByFarmSince(farmId, since))
                    .map { CropSeasonView.of(it) },
                (if (since == null) machines.findByFarmId(farmId) else machines.findByFarmIdAndUpdatedAtGreaterThanEqual(farmId, since))
                    .map { MachineView.of(it) },
                (if (since == null) tasks.findByFarmId(farmId) else tasks.findByFarmIdAndUpdatedAtGreaterThanEqual(farmId, since))
                    .map { TaskWithRefs.of(it) },
                (
                    if (since == null) {
                        works.findByFarmIdOrderByUpdatedAtDesc(farmId, latest)
                    } else {
                        works.findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(farmId, since, latest)
                    }
                    ).map { WorkView.of(it, serverTime) },
                (
                    if (since == null) {
                        runs.findByFarmIdOrderByUpdatedAtDesc(farmId, latest)
                    } else {
                        runs.findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(farmId, since, latest)
                    }
                    ).map { IrrigationView.of(it, serverTime) },
                // The full list of ids lets the app remove deleted tasks locally.
                tasks.findIdsByFarm(farmId),
            ),
        )
    }
}

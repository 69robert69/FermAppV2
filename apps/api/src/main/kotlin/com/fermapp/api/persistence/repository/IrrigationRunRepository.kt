package com.fermapp.api.persistence.repository

import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.persistence.entity.IrrigationRun
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/** Irrigation runs. Statuses are passed as parameters, never as JPQL enum literals. */
interface IrrigationRunRepository : JpaRepository<IrrigationRun, UUID>, JpaSpecificationExecutor<IrrigationRun> {

    fun findByIdAndFarmId(id: UUID, farmId: UUID): Optional<IrrigationRun>

    fun findByFarmIdAndStatusInOrderByStartedAtDesc(farmId: UUID, statuses: Collection<RunStatus>): List<IrrigationRun>

    fun findByFarmIdAndStatusInOrderByStartedAtAsc(farmId: UUID, statuses: Collection<RunStatus>): List<IrrigationRun>

    fun findByParcelIdAndStatusInOrderByStartedAtAsc(parcelId: UUID, statuses: Collection<RunStatus>): List<IrrigationRun>

    fun existsByParcelIdAndStatusIn(parcelId: UUID, statuses: Collection<RunStatus>): Boolean

    fun findByStatus(status: RunStatus): List<IrrigationRun>

    fun findByFarmIdOrderByUpdatedAtDesc(farmId: UUID, page: Pageable): List<IrrigationRun>

    fun findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(farmId: UUID, since: Instant, page: Pageable): List<IrrigationRun>

    fun findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(farmId: UUID, from: Instant, to: Instant): List<IrrigationRun>

    fun findByParcelIdAndStatusAndStartedAtGreaterThanEqualAndDoseMmIsNotNull(parcelId: UUID, status: RunStatus, since: Instant): List<IrrigationRun>

    /** Completed runs with a dose since the given moment, for the water balance. */
    fun findCompletedWithDoseSince(parcelId: UUID, since: Instant): List<IrrigationRun> = findByParcelIdAndStatusAndStartedAtGreaterThanEqualAndDoseMmIsNotNull(parcelId, RunStatus.COMPLETED, since)

    @Transactional
    @Modifying
    @Query("update IrrigationRun r set r.status = :target, r.pausedAt = :now, r.updatedAt = :now where r.id = :id and r.status = :current")
    fun setPaused(@Param("id") id: UUID, @Param("now") now: Instant, @Param("target") target: RunStatus, @Param("current") current: RunStatus): Int

    fun pause(id: UUID, now: Instant): Int = setPaused(id, now, RunStatus.PAUSED, RunStatus.RUNNING)

    @Transactional
    @Modifying
    @Query("""
            update IrrigationRun r set r.status = :target, r.pausedAt = null,
                r.pausedMs = r.pausedMs + :pausedFor, r.updatedAt = :now
            where r.id = :id
            """)
    fun setResumed(@Param("id") id: UUID, @Param("pausedFor") pausedFor: Long, @Param("now") now: Instant, @Param("target") target: RunStatus): Int

    fun resume(id: UUID, pausedFor: Long, now: Instant): Int = setResumed(id, pausedFor, now, RunStatus.RUNNING)

    @Transactional
    @Modifying
    @Query("""
            update IrrigationRun r set r.status = :target, r.completedAt = :now,
                r.pausedAt = null, r.progressPct = :percent, r.updatedAt = :now
            where r.id = :id and r.status in :active
            """)
    fun setCompleted(@Param("id") id: UUID, @Param("now") now: Instant, @Param("percent") percent: Double, @Param("active") active: Collection<RunStatus>, @Param("target") target: RunStatus): Int

    fun complete(id: UUID, now: Instant, percent: Double, active: Collection<RunStatus>): Int = setCompleted(id, now, percent, active, RunStatus.COMPLETED)

    @Transactional
    @Modifying
    @Query("""
            update IrrigationRun r set r.status = :target, r.completedAt = :now, r.pausedAt = null, r.updatedAt = :now
            where r.id = :id and r.status in :active
            """)
    fun setCancelled(@Param("id") id: UUID, @Param("now") now: Instant, @Param("active") active: Collection<RunStatus>, @Param("target") target: RunStatus): Int

    fun cancel(id: UUID, now: Instant, active: Collection<RunStatus>): Int = setCancelled(id, now, active, RunStatus.CANCELLED)

    @Transactional
    @Modifying
    @Query("update IrrigationRun r set r.progressPct = :percent, r.updatedAt = :now where r.id = :id and r.status = :current")
    fun setProgress(@Param("id") id: UUID, @Param("percent") percent: Double, @Param("now") now: Instant, @Param("current") current: RunStatus): Int

    fun updateProgress(id: UUID, percent: Double, now: Instant): Int = setProgress(id, percent, now, RunStatus.RUNNING)
}

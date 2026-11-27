package com.fermapp.api.persistence.repository

import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.persistence.entity.WorkSession
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

/**
 * Field operations. Statuses are always passed as parameters: Hibernate renders enum literals as
 * an unquoted cast ({@code ::RunStatus}) that PostgreSQL folds to lower case.
 */
interface WorkSessionRepository : JpaRepository<WorkSession, UUID>, JpaSpecificationExecutor<WorkSession> {

    fun findByIdAndFarmId(id: UUID, farmId: UUID): Optional<WorkSession>

    fun findByFarmIdAndStatusInOrderByStartedAtDesc(farmId: UUID, statuses: Collection<RunStatus>): List<WorkSession>

    fun findByFarmIdAndStatusInOrderByStartedAtAsc(farmId: UUID, statuses: Collection<RunStatus>): List<WorkSession>

    fun findByParcelIdAndStatusInOrderByStartedAtDesc(parcelId: UUID, statuses: Collection<RunStatus>): List<WorkSession>

    fun findByParcelIdAndStatusAndStartedAtGreaterThanEqual(parcelId: UUID, status: RunStatus, since: Instant): List<WorkSession>

    fun findByStatus(status: RunStatus): List<WorkSession>

    fun existsByMachineIdAndStatusIn(machineId: UUID, statuses: Collection<RunStatus>): Boolean

    fun findByFarmId(farmId: UUID): List<WorkSession>

    fun findByFarmIdOrderByUpdatedAtDesc(farmId: UUID, page: Pageable): List<WorkSession>

    fun findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(farmId: UUID, since: Instant, page: Pageable): List<WorkSession>

    fun findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(farmId: UUID, from: Instant, to: Instant): List<WorkSession>

    fun findByFarmIdAndStatusAndCompletedAtBetween(farmId: UUID, status: RunStatus, from: Instant, to: Instant): List<WorkSession>

    fun findByParcelIdInAndStartedAtGreaterThanEqualAndStartedAtBefore(parcelIds: Collection<UUID>, from: Instant, to: Instant): List<WorkSession>

    fun findTop8ByParcelIdOrderByStartedAtDesc(parcelId: UUID): List<WorkSession>

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.pausedAt = :now, w.updatedAt = :now
            where w.id = :id and w.status = :current
            """)
    fun setPaused(@Param("id") id: UUID, @Param("now") now: Instant, @Param("target") target: RunStatus, @Param("current") current: RunStatus): Int

    /** Pauses a running operation; 0 when it was not running. */
    fun pause(id: UUID, now: Instant): Int = setPaused(id, now, RunStatus.PAUSED, RunStatus.RUNNING)

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.pausedAt = null,
                w.pausedMs = w.pausedMs + :pausedFor, w.updatedAt = :now
            where w.id = :id
            """)
    fun setResumed(@Param("id") id: UUID, @Param("pausedFor") pausedFor: Long, @Param("now") now: Instant, @Param("target") target: RunStatus): Int

    /** Resumes the operation and adds the pause to the paused time. */
    fun resume(id: UUID, pausedFor: Long, now: Instant): Int = setResumed(id, pausedFor, now, RunStatus.RUNNING)

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.completedAt = :now,
                w.pausedAt = null, w.progressPct = :percent, w.fuelLiters = :fuel, w.updatedAt = :now
            where w.id = :id and w.status in :active
            """)
    fun setCompleted(@Param("id") id: UUID, @Param("now") now: Instant, @Param("percent") percent: Double, @Param("fuel") fuel: Double?, @Param("active") active: Collection<RunStatus>, @Param("target") target: RunStatus): Int

    /** Completes an active operation; 0 when it had already ended. */
    fun complete(id: UUID, now: Instant, percent: Double, fuel: Double?, active: Collection<RunStatus>): Int = setCompleted(id, now, percent, fuel, active, RunStatus.COMPLETED)

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.completedAt = :now, w.pausedAt = null, w.updatedAt = :now
            where w.id = :id and w.status in :active
            """)
    fun setCancelled(@Param("id") id: UUID, @Param("now") now: Instant, @Param("active") active: Collection<RunStatus>, @Param("target") target: RunStatus): Int

    /** Cancels an active operation; 0 when it had already ended. */
    fun cancel(id: UUID, now: Instant, active: Collection<RunStatus>): Int = setCancelled(id, now, active, RunStatus.CANCELLED)

    @Transactional
    @Modifying
    @Query("update WorkSession w set w.progressPct = :percent, w.updatedAt = :now where w.id = :id and w.status = :current")
    fun setProgress(@Param("id") id: UUID, @Param("percent") percent: Double, @Param("now") now: Instant, @Param("current") current: RunStatus): Int

    /** Stores the live progress of a running operation. */
    fun updateProgress(id: UUID, percent: Double, now: Instant): Int = setProgress(id, percent, now, RunStatus.RUNNING)
}

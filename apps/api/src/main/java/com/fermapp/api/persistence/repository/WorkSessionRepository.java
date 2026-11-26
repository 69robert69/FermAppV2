package com.fermapp.api.persistence.repository;

import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.persistence.entity.WorkSession;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Field operations. Statuses are always passed as parameters: Hibernate renders enum literals as
 * an unquoted cast ({@code ::RunStatus}) that PostgreSQL folds to lower case.
 */
public interface WorkSessionRepository extends JpaRepository<WorkSession, UUID>, JpaSpecificationExecutor<WorkSession> {

    Optional<WorkSession> findByIdAndFarmId(UUID id, UUID farmId);

    List<WorkSession> findByFarmIdAndStatusInOrderByStartedAtDesc(UUID farmId, Collection<RunStatus> statuses);

    List<WorkSession> findByFarmIdAndStatusInOrderByStartedAtAsc(UUID farmId, Collection<RunStatus> statuses);

    List<WorkSession> findByParcelIdAndStatusInOrderByStartedAtDesc(UUID parcelId, Collection<RunStatus> statuses);

    List<WorkSession> findByParcelIdAndStatusAndStartedAtGreaterThanEqual(UUID parcelId, RunStatus status, Instant since);

    List<WorkSession> findByStatus(RunStatus status);

    boolean existsByMachineIdAndStatusIn(UUID machineId, Collection<RunStatus> statuses);

    List<WorkSession> findByFarmId(UUID farmId);

    List<WorkSession> findByFarmIdOrderByUpdatedAtDesc(UUID farmId, Pageable page);

    List<WorkSession> findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(UUID farmId, Instant since, Pageable page);

    List<WorkSession> findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(UUID farmId, Instant from, Instant to);

    List<WorkSession> findByFarmIdAndStatusAndCompletedAtBetween(UUID farmId, RunStatus status, Instant from, Instant to);

    List<WorkSession> findByParcelIdInAndStartedAtGreaterThanEqualAndStartedAtBefore(Collection<UUID> parcelIds, Instant from, Instant to);

    List<WorkSession> findTop8ByParcelIdOrderByStartedAtDesc(UUID parcelId);

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.pausedAt = :now, w.updatedAt = :now
            where w.id = :id and w.status = :current
            """)
    int setPaused(@Param("id") UUID id, @Param("now") Instant now, @Param("target") RunStatus target,
                  @Param("current") RunStatus current);

    /** Pauses a running operation; 0 when it was not running. */
    default int pause(UUID id, Instant now) {
        return setPaused(id, now, RunStatus.PAUSED, RunStatus.RUNNING);
    }

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.pausedAt = null,
                w.pausedMs = w.pausedMs + :pausedFor, w.updatedAt = :now
            where w.id = :id
            """)
    int setResumed(@Param("id") UUID id, @Param("pausedFor") long pausedFor, @Param("now") Instant now,
                   @Param("target") RunStatus target);

    /** Resumes the operation and adds the pause to the paused time. */
    default int resume(UUID id, long pausedFor, Instant now) {
        return setResumed(id, pausedFor, now, RunStatus.RUNNING);
    }

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.completedAt = :now,
                w.pausedAt = null, w.progressPct = :percent, w.fuelLiters = :fuel, w.updatedAt = :now
            where w.id = :id and w.status in :active
            """)
    int setCompleted(@Param("id") UUID id, @Param("now") Instant now, @Param("percent") double percent,
                     @Param("fuel") Double fuel, @Param("active") Collection<RunStatus> active, @Param("target") RunStatus target);

    /** Completes an active operation; 0 when it had already ended. */
    default int complete(UUID id, Instant now, double percent, Double fuel, Collection<RunStatus> active) {
        return setCompleted(id, now, percent, fuel, active, RunStatus.COMPLETED);
    }

    @Transactional
    @Modifying
    @Query("""
            update WorkSession w set w.status = :target, w.completedAt = :now, w.pausedAt = null, w.updatedAt = :now
            where w.id = :id and w.status in :active
            """)
    int setCancelled(@Param("id") UUID id, @Param("now") Instant now, @Param("active") Collection<RunStatus> active,
                     @Param("target") RunStatus target);

    /** Cancels an active operation; 0 when it had already ended. */
    default int cancel(UUID id, Instant now, Collection<RunStatus> active) {
        return setCancelled(id, now, active, RunStatus.CANCELLED);
    }

    @Transactional
    @Modifying
    @Query("update WorkSession w set w.progressPct = :percent, w.updatedAt = :now where w.id = :id and w.status = :current")
    int setProgress(@Param("id") UUID id, @Param("percent") double percent, @Param("now") Instant now,
                    @Param("current") RunStatus current);

    /** Stores the live progress of a running operation. */
    default int updateProgress(UUID id, double percent, Instant now) {
        return setProgress(id, percent, now, RunStatus.RUNNING);
    }
}

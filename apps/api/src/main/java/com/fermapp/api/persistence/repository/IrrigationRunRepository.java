package com.fermapp.api.persistence.repository;

import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.persistence.entity.IrrigationRun;
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

/** Irrigation runs. Statuses are passed as parameters, never as JPQL enum literals. */
public interface IrrigationRunRepository extends JpaRepository<IrrigationRun, UUID>, JpaSpecificationExecutor<IrrigationRun> {

    Optional<IrrigationRun> findByIdAndFarmId(UUID id, UUID farmId);

    List<IrrigationRun> findByFarmIdAndStatusInOrderByStartedAtDesc(UUID farmId, Collection<RunStatus> statuses);

    List<IrrigationRun> findByFarmIdAndStatusInOrderByStartedAtAsc(UUID farmId, Collection<RunStatus> statuses);

    List<IrrigationRun> findByParcelIdAndStatusInOrderByStartedAtAsc(UUID parcelId, Collection<RunStatus> statuses);

    boolean existsByParcelIdAndStatusIn(UUID parcelId, Collection<RunStatus> statuses);

    List<IrrigationRun> findByStatus(RunStatus status);

    List<IrrigationRun> findByFarmIdOrderByUpdatedAtDesc(UUID farmId, Pageable page);

    List<IrrigationRun> findByFarmIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(UUID farmId, Instant since, Pageable page);

    List<IrrigationRun> findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(UUID farmId, Instant from, Instant to);

    List<IrrigationRun> findByParcelIdAndStatusAndStartedAtGreaterThanEqualAndDoseMmIsNotNull(UUID parcelId, RunStatus status, Instant since);

    /** Completed runs with a dose since the given moment, for the water balance. */
    default List<IrrigationRun> findCompletedWithDoseSince(UUID parcelId, Instant since) {
        return findByParcelIdAndStatusAndStartedAtGreaterThanEqualAndDoseMmIsNotNull(parcelId, RunStatus.COMPLETED, since);
    }

    @Transactional
    @Modifying
    @Query("update IrrigationRun r set r.status = :target, r.pausedAt = :now, r.updatedAt = :now where r.id = :id and r.status = :current")
    int setPaused(@Param("id") UUID id, @Param("now") Instant now, @Param("target") RunStatus target,
                  @Param("current") RunStatus current);

    default int pause(UUID id, Instant now) {
        return setPaused(id, now, RunStatus.PAUSED, RunStatus.RUNNING);
    }

    @Transactional
    @Modifying
    @Query("""
            update IrrigationRun r set r.status = :target, r.pausedAt = null,
                r.pausedMs = r.pausedMs + :pausedFor, r.updatedAt = :now
            where r.id = :id
            """)
    int setResumed(@Param("id") UUID id, @Param("pausedFor") long pausedFor, @Param("now") Instant now,
                   @Param("target") RunStatus target);

    default int resume(UUID id, long pausedFor, Instant now) {
        return setResumed(id, pausedFor, now, RunStatus.RUNNING);
    }

    @Transactional
    @Modifying
    @Query("""
            update IrrigationRun r set r.status = :target, r.completedAt = :now,
                r.pausedAt = null, r.progressPct = :percent, r.updatedAt = :now
            where r.id = :id and r.status in :active
            """)
    int setCompleted(@Param("id") UUID id, @Param("now") Instant now, @Param("percent") double percent,
                     @Param("active") Collection<RunStatus> active, @Param("target") RunStatus target);

    default int complete(UUID id, Instant now, double percent, Collection<RunStatus> active) {
        return setCompleted(id, now, percent, active, RunStatus.COMPLETED);
    }

    @Transactional
    @Modifying
    @Query("""
            update IrrigationRun r set r.status = :target, r.completedAt = :now, r.pausedAt = null, r.updatedAt = :now
            where r.id = :id and r.status in :active
            """)
    int setCancelled(@Param("id") UUID id, @Param("now") Instant now, @Param("active") Collection<RunStatus> active,
                     @Param("target") RunStatus target);

    default int cancel(UUID id, Instant now, Collection<RunStatus> active) {
        return setCancelled(id, now, active, RunStatus.CANCELLED);
    }

    @Transactional
    @Modifying
    @Query("update IrrigationRun r set r.progressPct = :percent, r.updatedAt = :now where r.id = :id and r.status = :current")
    int setProgress(@Param("id") UUID id, @Param("percent") double percent, @Param("now") Instant now,
                    @Param("current") RunStatus current);

    default int updateProgress(UUID id, double percent, Instant now) {
        return setProgress(id, percent, now, RunStatus.RUNNING);
    }
}

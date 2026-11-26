package com.fermapp.api.persistence.repository;

import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.TaskStatus;
import com.fermapp.api.persistence.entity.Task;
import java.time.Instant;
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

/** Tasks. Statuses are passed as parameters, never as JPQL enum literals. */
public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    Optional<Task> findByIdAndFarmId(UUID id, UUID farmId);

    long countByFarmIdAndStatusNot(UUID farmId, TaskStatus status);

    long countByFarmIdAndStatusNotAndDueDateBefore(UUID farmId, TaskStatus status, Instant before);

    List<Task> findByFarmId(UUID farmId);

    List<Task> findByFarmIdAndUpdatedAtGreaterThanEqual(UUID farmId, Instant since);

    List<Task> findByFarmIdAndParcelIdAndStatusNot(UUID farmId, UUID parcelId, TaskStatus status);

    @Query("select t.id from Task t where t.farmId = :farmId")
    List<UUID> findIdsByFarm(@Param("farmId") UUID farmId);

    @Query("select t from Task t left join fetch t.assignee where t.parcelId = :parcelId order by t.status asc, t.dueDate asc")
    List<Task> findForParcel(@Param("parcelId") UUID parcelId, Pageable page);

    @Query("""
            select t.parcelId, count(t) from Task t
            where t.farmId = :farmId and t.status <> :done and t.parcelId is not null
            group by t.parcelId
            """)
    List<Object[]> countOpenByParcelExcept(@Param("farmId") UUID farmId, @Param("done") TaskStatus done);

    /** Open tasks per parcel: rows of [parcelId, count]. */
    default List<Object[]> countOpenByParcel(UUID farmId) {
        return countOpenByParcelExcept(farmId, TaskStatus.DONE);
    }

    @Query("""
            select t from Task t left join fetch t.parcel
            where t.status <> :done and t.reminderSentAt is null and t.assigneeId is not null and t.dueDate <= :limit
            """)
    List<Task> findDueForReminderExcept(@Param("limit") Instant limit, @Param("done") TaskStatus done, Pageable page);

    /** Open, assigned tasks due before the limit that have not been reminded yet. */
    default List<Task> findDueForReminder(Instant limit, Pageable page) {
        return findDueForReminderExcept(limit, TaskStatus.DONE, page);
    }

    @Transactional
    @Modifying
    @Query("update Task t set t.reminderSentAt = :now, t.updatedAt = :now where t.id = :id and t.reminderSentAt is null")
    int markReminderSent(@Param("id") UUID id, @Param("now") Instant now);

    @Query("""
            select t.planStepKey from Task t join t.plan p
            where t.parcelId = :parcelId and t.status <> :todo
              and p.parcelId = :parcelId and p.year = :year and p.crop = :crop
            """)
    List<String> findStartedStepKeys(@Param("parcelId") UUID parcelId, @Param("year") int year, @Param("crop") CropCode crop,
                                     @Param("todo") TaskStatus todo);

    /** Step keys of started or finished tasks of the plan for this year and crop; they survive a re-plan. */
    default List<String> findKeptStepKeys(UUID parcelId, int year, CropCode crop) {
        return findStartedStepKeys(parcelId, year, crop, TaskStatus.TODO);
    }

    @Modifying
    @Query("""
            delete from Task t
            where t.parcelId = :parcelId and t.status = :todo
              and t.planId in (select p.id from ParcelPlan p where p.parcelId = :parcelId and p.year = :year)
            """)
    int deletePlanTasksWithStatus(@Param("parcelId") UUID parcelId, @Param("year") int year, @Param("todo") TaskStatus todo);

    /** Deletes the not-started tasks of the year's plans. */
    default int deleteNotStartedPlanTasks(UUID parcelId, int year) {
        return deletePlanTasksWithStatus(parcelId, year, TaskStatus.TODO);
    }
}

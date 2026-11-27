package com.fermapp.api.persistence.repository

import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.TaskStatus
import com.fermapp.api.persistence.entity.Task
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

/** Tasks. Statuses are passed as parameters, never as JPQL enum literals. */
interface TaskRepository : JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    fun findByIdAndFarmId(id: UUID, farmId: UUID): Optional<Task>

    fun countByFarmIdAndStatusNot(farmId: UUID, status: TaskStatus): Long

    fun countByFarmIdAndStatusNotAndDueDateBefore(farmId: UUID, status: TaskStatus, before: Instant): Long

    fun findByFarmId(farmId: UUID): List<Task>

    fun findByFarmIdAndUpdatedAtGreaterThanEqual(farmId: UUID, since: Instant): List<Task>

    fun findByFarmIdAndParcelIdAndStatusNot(farmId: UUID, parcelId: UUID, status: TaskStatus): List<Task>

    @Query("select t.id from Task t where t.farmId = :farmId")
    fun findIdsByFarm(@Param("farmId") farmId: UUID): List<UUID>

    @Query("select t from Task t left join fetch t.assignee where t.parcelId = :parcelId order by t.status asc, t.dueDate asc")
    fun findForParcel(@Param("parcelId") parcelId: UUID, page: Pageable): List<Task>

    @Query("""
            select t.parcelId, count(t) from Task t
            where t.farmId = :farmId and t.status <> :done and t.parcelId is not null
            group by t.parcelId
            """)
    fun countOpenByParcelExcept(@Param("farmId") farmId: UUID, @Param("done") done: TaskStatus): List<Array<Any?>>

    /** Open tasks per parcel: rows of [parcelId, count]. */
    fun countOpenByParcel(farmId: UUID): List<Array<Any?>> = countOpenByParcelExcept(farmId, TaskStatus.DONE)

    @Query("""
            select t from Task t left join fetch t.parcel
            where t.status <> :done and t.reminderSentAt is null and t.assigneeId is not null and t.dueDate <= :limit
            """)
    fun findDueForReminderExcept(@Param("limit") limit: Instant, @Param("done") done: TaskStatus, page: Pageable): List<Task>

    /** Open, assigned tasks due before the limit that have not been reminded yet. */
    fun findDueForReminder(limit: Instant, page: Pageable): List<Task> = findDueForReminderExcept(limit, TaskStatus.DONE, page)

    @Transactional
    @Modifying
    @Query("update Task t set t.reminderSentAt = :now, t.updatedAt = :now where t.id = :id and t.reminderSentAt is null")
    fun markReminderSent(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Query("""
            select t.planStepKey from Task t join t.plan p
            where t.parcelId = :parcelId and t.status <> :todo
              and p.parcelId = :parcelId and p.year = :year and p.crop = :crop
            """)
    fun findStartedStepKeys(@Param("parcelId") parcelId: UUID, @Param("year") year: Int, @Param("crop") crop: CropCode, @Param("todo") todo: TaskStatus): List<String>

    /** Step keys of started or finished tasks of the plan for this year and crop; they survive a re-plan. */
    fun findKeptStepKeys(parcelId: UUID, year: Int, crop: CropCode): List<String> = findStartedStepKeys(parcelId, year, crop, TaskStatus.TODO)

    @Modifying
    @Query("""
            delete from Task t
            where t.parcelId = :parcelId and t.status = :todo
              and t.planId in (select p.id from ParcelPlan p where p.parcelId = :parcelId and p.year = :year)
            """)
    fun deletePlanTasksWithStatus(@Param("parcelId") parcelId: UUID, @Param("year") year: Int, @Param("todo") todo: TaskStatus): Int

    /** Deletes the not-started tasks of the year's plans. */
    fun deleteNotStartedPlanTasks(parcelId: UUID, year: Int): Int = deletePlanTasksWithStatus(parcelId, year, TaskStatus.TODO)
}

package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.fieldstatus.FieldStatusRules
import com.fermapp.api.domain.model.TaskPriority
import com.fermapp.api.domain.model.TaskStatus
import com.fermapp.api.domain.model.TaskType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** A task; plan-generated tasks keep the plan and the step they come from. */
@Entity
@Table(name = "Task")
class Task : FieldStatusRules.OpenTask {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    var parcelId: UUID? = null

    var assigneeId: UUID? = null

    var planId: UUID? = null

    var planStepKey: String? = null

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    lateinit var type: TaskType

    @Column(nullable = false)
    lateinit var title: String

    var description: String? = null

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var priority: TaskPriority = TaskPriority.MEDIUM

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var status: TaskStatus = TaskStatus.TODO

    @Column(nullable = false)
    lateinit var dueDate: Instant

    var reminderSentAt: Instant? = null

    var completedAt: Instant? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var farm: Farm? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    var parcel: Parcel? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigneeId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    var assignee: UserAccount? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    var plan: ParcelPlan? = null

    override fun status(): String = status.name

    override fun type(): String = type.name

    override fun dueDate(): Instant = dueDate

    @PrePersist
    protected fun onCreate() {
        val now = Times.now()
        if (createdAt == null) {
            createdAt = now
        }
        updatedAt = now
    }

    @PreUpdate
    protected fun onUpdate() {
        updatedAt = Times.now()
    }
}

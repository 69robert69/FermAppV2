package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules;
import com.fermapp.api.domain.model.TaskPriority;
import com.fermapp.api.domain.model.TaskStatus;
import com.fermapp.api.domain.model.TaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A task; plan-generated tasks keep the plan and the step they come from. */
@Entity
@Table(name = "Task")
@Getter
@Setter
public class Task implements FieldStatusRules.OpenTask {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID farmId;

    private UUID parcelId;

    private UUID assigneeId;

    private UUID planId;

    private String planStepKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TaskType type;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.TODO;

    @Column(nullable = false)
    private Instant dueDate;

    private Instant reminderSentAt;

    private Instant completedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Parcel parcel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigneeId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private ParcelPlan plan;

    @Override
    public String status() {
        return status.name();
    }

    @Override
    public String type() {
        return type.name();
    }

    @Override
    public Instant dueDate() {
        return dueDate;
    }

    @PrePersist
    void onCreate() {
        Instant now = Times.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.now();
    }
}

package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.model.WorkType;
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

/** A field operation (ploughing, sowing, spraying...) with timed progress. */
@Entity
@Table(name = "WorkSession")
@Getter
@Setter
public class WorkSession implements TimedRun {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID farmId;

    @Column(nullable = false)
    private UUID parcelId;

    private UUID machineId;

    @Column(nullable = false)
    private UUID operatorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private WorkType type;

    @Column(nullable = false)
    private double areaHa;

    @Column(nullable = false)
    private double workingWidthM;

    @Column(nullable = false)
    private double speedKmh;

    @Column(nullable = false)
    private double efficiencyPct;

    @Column(nullable = false)
    private double capacityHaH;

    @Column(nullable = false)
    private double durationHours;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private RunStatus status = RunStatus.RUNNING;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant pausedAt;

    @Column(nullable = false)
    private long pausedMs;

    private Instant completedAt;

    @Column(nullable = false)
    private double progressPct;

    private Double fuelLiters;

    private String notes;

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
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Parcel parcel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machineId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Machine machine;

    /** ON DELETE RESTRICT: a user with logged work cannot be deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operatorId", insertable = false, updatable = false)
    private UserAccount operator;

    @PrePersist
    void onCreate() {
        Instant now = Times.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (startedAt == null) {
            startedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.now();
    }
}

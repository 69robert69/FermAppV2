package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.MachineType;
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

/** A machine of the fleet. */
@Entity
@Table(name = "Machine")
@Getter
@Setter
public class Machine {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID farmId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private MachineType type;

    private String brand;

    private String model;

    private Double powerHp;

    /** Average consumption, in litres per hour. */
    private Double avgConsumptionLh;

    /** Tillage technologies the machine suits. Empty means the defaults of its type. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] tillageSystems = new String[0];

    private UUID operatorId;

    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operatorId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount operator;

    @PrePersist
    void onCreate() {
        Instant now = Times.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (tillageSystems == null) {
            tillageSystems = new String[0];
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.now();
    }
}

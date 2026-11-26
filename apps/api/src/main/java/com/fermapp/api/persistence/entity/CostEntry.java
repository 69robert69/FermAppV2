package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.CostCategory;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** An expense recorded by category on a parcel. */
@Entity
@Table(name = "CostEntry")
@Getter
@Setter
public class CostEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID farmId;

    @Column(nullable = false)
    private UUID parcelId;

    @Column(nullable = false)
    private int year;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CostCategory category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "EUR";

    @Column(nullable = false)
    private Instant date;

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

    @PrePersist
    void onCreate() {
        Instant now = Times.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (date == null) {
            date = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.now();
    }
}

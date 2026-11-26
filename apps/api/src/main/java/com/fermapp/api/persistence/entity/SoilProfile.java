package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.SoilStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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

/** The SoilGrids profile of a parcel; at most one per parcel. */
@Entity
@Table(name = "SoilProfile")
@Getter
@Setter
public class SoilProfile {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID parcelId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private SoilStatus status = SoilStatus.PENDING;

    private Double ph;
    private Double clayPct;
    private Double sandPct;
    private Double siltPct;
    private Double organicCarbonGKg;
    private Double organicMatterPct;
    private Double waterHoldingMmPerM;
    private String texture;
    private String soilType;
    private String label;
    private String errorMessage;
    private Instant fetchedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Parcel parcel;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Times.now();
    }
}

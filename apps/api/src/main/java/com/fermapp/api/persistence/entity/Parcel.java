package com.fermapp.api.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.CropCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A parcel: a GeoJSON polygon in WGS84 with its computed area, perimeter and centre. */
@Entity
@Table(name = "Parcel", uniqueConstraints = @UniqueConstraint(columnNames = {"farmId", "code"}))
@Getter
@Setter
public class Parcel {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID farmId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    /** GeoJSON polygon in WGS84. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode geometry;

    @Column(nullable = false)
    private double areaHa;

    @Column(nullable = false)
    private double perimeterM;

    @Column(nullable = false)
    private double centerLat;

    @Column(nullable = false)
    private double centerLng;

    private String locality;

    private String county;

    private String notes;

    /** Area equipped for irrigation, in hectares. */
    private Double irrigatedAreaHa;

    /** Last crop declared at setup: a crop code or OTHER. */
    private String lastCrop;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private CropCode plannedCrop;

    /** Soil tillage technology in use: CONVENTIONAL, MINIMUM_TILL, STRIP_TILL or NO_TILL. */
    private String tillageSystem;

    /** One entry per year: [{ year, system }], oldest first. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode tillageHistory;

    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Farm farm;

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    private List<CropSeason> seasons = new ArrayList<>();

    @OneToOne(mappedBy = "parcel", fetch = FetchType.LAZY)
    private SoilProfile soilProfile;

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    private List<WorkSession> workSessions = new ArrayList<>();

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    private List<Task> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    private List<SatelliteObservation> satellite = new ArrayList<>();

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    private List<ParcelPlan> plans = new ArrayList<>();

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

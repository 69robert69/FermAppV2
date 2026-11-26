package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/** Daily weather of a parcel, kept for analysis. */
@Entity
@Table(name = "WeatherData", uniqueConstraints = @UniqueConstraint(columnNames = {"parcelId", "date"}))
@Getter
@Setter
public class WeatherData {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID parcelId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private double tMaxC;

    @Column(nullable = false)
    private double tMinC;

    @Column(nullable = false)
    private double precipitationMm;

    private Double humidityPct;
    private Double windMaxKmh;
    private Double et0Mm;

    @Column(nullable = false)
    private String source = "open-meteo";

    @Column(nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Parcel parcel;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Times.now();
    }
}

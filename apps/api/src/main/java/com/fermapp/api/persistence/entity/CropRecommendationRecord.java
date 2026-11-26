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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** The latest crop recommendation scores of a parcel, kept for history (table "CropRecommendation"). */
@Entity
@Table(name = "CropRecommendation", uniqueConstraints = @UniqueConstraint(columnNames = {"parcelId", "year", "crop"}))
@Getter
@Setter
public class CropRecommendationRecord {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID parcelId;

    @Column(nullable = false)
    private int year;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CropCode crop;

    @Column(nullable = false)
    private int score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode reasons;

    @Column(nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Parcel parcel;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Times.now();
        }
    }
}

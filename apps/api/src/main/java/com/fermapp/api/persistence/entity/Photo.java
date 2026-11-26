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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/** A parcel photo; the file itself lives in the upload directory under {@code storageKey}. */
@Entity
@Table(name = "Photo")
@Getter
@Setter
public class Photo {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID parcelId;

    private UUID observationId;

    @Column(nullable = false)
    private UUID authorId;

    @Column(nullable = false, unique = true)
    private String storageKey;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private int sizeBytes;

    private String notes;

    @Column(nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Parcel parcel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "observationId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Observation observation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authorId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount author;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Times.now();
        }
    }
}

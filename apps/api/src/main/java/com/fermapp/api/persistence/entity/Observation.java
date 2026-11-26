package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/** A field observation written by a team member. */
@Entity
@Table(name = "Observation")
@Getter
@Setter
public class Observation {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID parcelId;

    @Column(nullable = false)
    private UUID authorId;

    @Column(nullable = false)
    private String text;

    private Double lat;

    private Double lng;

    @Column(nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Parcel parcel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authorId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount author;

    @OneToMany(mappedBy = "observation", fetch = FetchType.LAZY)
    private List<Photo> photos = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Times.now();
        }
    }
}

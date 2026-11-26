package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** A farm: the tenant every other record belongs to. */
@Entity
@Table(name = "Farm")
@Getter
@Setter
public class Farm {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String county;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "farm", fetch = FetchType.LAZY)
    private List<FarmMembership> members = new ArrayList<>();

    @OneToMany(mappedBy = "farm", fetch = FetchType.LAZY)
    private List<Parcel> parcels = new ArrayList<>();

    @OneToMany(mappedBy = "farm", fetch = FetchType.LAZY)
    private List<Machine> machines = new ArrayList<>();

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

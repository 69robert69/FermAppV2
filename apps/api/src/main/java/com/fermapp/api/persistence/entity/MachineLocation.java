package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

/** A GPS position sent by the machine during an operation. The id is a BIGSERIAL. */
@Entity
@Table(name = "MachineLocation")
@Getter
@Setter
public class MachineLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID workSessionId;

    private UUID machineId;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    private Double headingDeg;
    private Double speedKmh;
    private Double accuracyM;

    @Column(nullable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workSessionId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private WorkSession workSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machineId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Machine machine;

    @PrePersist
    void onCreate() {
        if (recordedAt == null) {
            recordedAt = Times.now();
        }
    }
}

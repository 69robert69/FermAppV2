package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** A farm: the tenant every other record belongs to. */
@Entity
@Table(name = "Farm")
class Farm {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var name: String

    var county: String? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @OneToMany(mappedBy = "farm", fetch = FetchType.LAZY)
    var members: MutableList<FarmMembership> = ArrayList()

    @OneToMany(mappedBy = "farm", fetch = FetchType.LAZY)
    var parcels: MutableList<Parcel> = ArrayList()

    @OneToMany(mappedBy = "farm", fetch = FetchType.LAZY)
    var machines: MutableList<Machine> = ArrayList()

    @PrePersist
    protected fun onCreate() {
        val now = Times.now()
        if (createdAt == null) {
            createdAt = now
        }
        updatedAt = now
    }

    @PreUpdate
    protected fun onUpdate() {
        updatedAt = Times.now()
    }
}

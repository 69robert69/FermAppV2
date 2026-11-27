package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.SoilProfile
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface SoilProfileRepository : JpaRepository<SoilProfile, UUID> {

    fun findByParcelId(parcelId: UUID): Optional<SoilProfile>

    fun findByParcelIdIn(parcelIds: Collection<UUID>): List<SoilProfile>
}

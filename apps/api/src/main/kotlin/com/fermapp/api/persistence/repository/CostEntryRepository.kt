package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.CostEntry
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface CostEntryRepository : JpaRepository<CostEntry, UUID>, JpaSpecificationExecutor<CostEntry> {

    fun findByIdAndFarmId(id: UUID, farmId: UUID): Optional<CostEntry>

    fun findByParcelIdAndYear(parcelId: UUID, year: Int): List<CostEntry>

    fun findByParcelIdInAndYear(parcelIds: Collection<UUID>, year: Int): List<CostEntry>
}

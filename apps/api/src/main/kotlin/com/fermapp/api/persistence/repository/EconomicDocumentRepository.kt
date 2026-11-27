package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.EconomicDocument
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface EconomicDocumentRepository : JpaRepository<EconomicDocument, UUID> {

    fun findByFarmIdAndKey(farmId: UUID, key: String): Optional<EconomicDocument>

    @Query("select d from EconomicDocument d where d.farmId = :farmId and d.year = :year and d.key like 'sheet:%'")
    fun findSheets(@Param("farmId") farmId: UUID, @Param("year") year: Int): List<EconomicDocument>
}

package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.Machine
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MachineRepository : JpaRepository<Machine, UUID> {

    fun findByIdAndFarmIdAndArchivedAtIsNull(id: UUID, farmId: UUID): Optional<Machine>

    @Query("select m from Machine m left join fetch m.operator where m.farmId = :farmId and m.archivedAt is null order by m.name asc")
    fun findActiveWithOperator(@Param("farmId") farmId: UUID): List<Machine>

    fun findByFarmId(farmId: UUID): List<Machine>

    fun findByFarmIdAndUpdatedAtGreaterThanEqual(farmId: UUID, since: Instant): List<Machine>
}

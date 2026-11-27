package com.fermapp.api.persistence.repository

import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.persistence.entity.Parcel
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ParcelRepository : JpaRepository<Parcel, UUID> {

    fun findByIdAndFarmId(id: UUID, farmId: UUID): Optional<Parcel>

    fun findByIdAndFarmIdAndArchivedAtIsNull(id: UUID, farmId: UUID): Optional<Parcel>

    fun findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId: UUID): List<Parcel>

    fun findByFarmIdAndArchivedAtIsNull(farmId: UUID): List<Parcel>

    fun findByFarmId(farmId: UUID): List<Parcel>

    fun findByFarmIdAndUpdatedAtGreaterThanEqual(farmId: UUID, since: Instant): List<Parcel>

    fun findByArchivedAtIsNull(): List<Parcel>

    fun countByFarmId(farmId: UUID): Long

    /** The parcel list, filtered by archive state and an optional case-insensitive search. */
    @Query("""
            select p from Parcel p
            where p.farmId = :farmId
              and ((:archived = true and p.archivedAt is not null) or (:archived = false and p.archivedAt is null))
              and (:pattern = '' or lower(p.name) like :pattern escape '\' or lower(p.code) like :pattern escape '\')
            order by p.name asc
            """)
    fun search(@Param("farmId") farmId: UUID, @Param("archived") archived: Boolean, @Param("pattern") pattern: String): List<Parcel>

    /** Active parcels whose soil analysis is pending or failed, for the retry job. */
    @Query("select p from Parcel p, SoilProfile s where s.parcelId = p.id and p.archivedAt is null and s.status in :statuses")
    fun findWithSoilStatus(@Param("statuses") statuses: Collection<SoilStatus>, page: Pageable): List<Parcel>
}

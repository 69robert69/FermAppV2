package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.SatelliteObservation
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SatelliteObservationRepository : JpaRepository<SatelliteObservation, UUID> {

    fun findByParcelIdOrderByDateDesc(parcelId: UUID, page: Pageable): List<SatelliteObservation>

    fun findFirstByParcelIdOrderByDateDesc(parcelId: UUID): Optional<SatelliteObservation>

    fun findByParcelIdAndDate(parcelId: UUID, date: LocalDate): Optional<SatelliteObservation>

    fun findByParcelIdInOrderByDateDesc(parcelIds: Collection<UUID>): List<SatelliteObservation>

    /** The latest observation of each of the given parcels. */
    @Query("""
            select s from SatelliteObservation s
            where s.parcelId in :parcelIds
              and s.date = (select max(s2.date) from SatelliteObservation s2 where s2.parcelId = s.parcelId)
            """)
    fun findLatestFor(@Param("parcelIds") parcelIds: Collection<UUID>): List<SatelliteObservation>
}

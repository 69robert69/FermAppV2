package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.CropSeason
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CropSeasonRepository : JpaRepository<CropSeason, UUID> {

    fun findByParcelIdOrderByYearDesc(parcelId: UUID): List<CropSeason>

    fun findTop10ByParcelIdOrderByYearDesc(parcelId: UUID): List<CropSeason>

    fun findByParcelIdAndYearGreaterThanEqualOrderByYearDesc(parcelId: UUID, year: Int): List<CropSeason>

    fun findByParcelIdAndYearLessThanOrderByYearDesc(parcelId: UUID, year: Int): List<CropSeason>

    fun findByParcelIdAndYear(parcelId: UUID, year: Int): Optional<CropSeason>

    fun findByParcelIdInAndYear(parcelIds: Collection<UUID>, year: Int): List<CropSeason>

    fun findByParcelIdIn(parcelIds: Collection<UUID>): List<CropSeason>

    @Query("select s from CropSeason s join s.parcel p where s.id = :id and p.farmId = :farmId")
    fun findInFarm(@Param("id") id: UUID, @Param("farmId") farmId: UUID): Optional<CropSeason>

    @Query("select s from CropSeason s join fetch s.parcel p where s.year = :year and p.farmId = :farmId and p.archivedAt is null")
    fun findActiveForYear(@Param("farmId") farmId: UUID, @Param("year") year: Int): List<CropSeason>

    @Query("select s from CropSeason s join fetch s.parcel p where s.year = :year and p.farmId = :farmId order by p.name asc")
    fun findForYearOrderByParcelName(@Param("farmId") farmId: UUID, @Param("year") year: Int): List<CropSeason>

    @Query("select s from CropSeason s join s.parcel p where p.farmId = :farmId")
    fun findByFarm(@Param("farmId") farmId: UUID): List<CropSeason>

    @Query("select s from CropSeason s join s.parcel p where p.farmId = :farmId and s.updatedAt >= :since")
    fun findByFarmSince(@Param("farmId") farmId: UUID, @Param("since") since: Instant): List<CropSeason>
}

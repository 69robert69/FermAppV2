package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.CropSeason;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CropSeasonRepository extends JpaRepository<CropSeason, UUID> {

    List<CropSeason> findByParcelIdOrderByYearDesc(UUID parcelId);

    List<CropSeason> findTop10ByParcelIdOrderByYearDesc(UUID parcelId);

    List<CropSeason> findByParcelIdAndYearGreaterThanEqualOrderByYearDesc(UUID parcelId, int year);

    List<CropSeason> findByParcelIdAndYearLessThanOrderByYearDesc(UUID parcelId, int year);

    Optional<CropSeason> findByParcelIdAndYear(UUID parcelId, int year);

    List<CropSeason> findByParcelIdInAndYear(Collection<UUID> parcelIds, int year);

    List<CropSeason> findByParcelIdIn(Collection<UUID> parcelIds);

    @Query("select s from CropSeason s join s.parcel p where s.id = :id and p.farmId = :farmId")
    Optional<CropSeason> findInFarm(@Param("id") UUID id, @Param("farmId") UUID farmId);

    @Query("select s from CropSeason s join fetch s.parcel p where s.year = :year and p.farmId = :farmId and p.archivedAt is null")
    List<CropSeason> findActiveForYear(@Param("farmId") UUID farmId, @Param("year") int year);

    @Query("select s from CropSeason s join fetch s.parcel p where s.year = :year and p.farmId = :farmId order by p.name asc")
    List<CropSeason> findForYearOrderByParcelName(@Param("farmId") UUID farmId, @Param("year") int year);

    @Query("select s from CropSeason s join s.parcel p where p.farmId = :farmId")
    List<CropSeason> findByFarm(@Param("farmId") UUID farmId);

    @Query("select s from CropSeason s join s.parcel p where p.farmId = :farmId and s.updatedAt >= :since")
    List<CropSeason> findByFarmSince(@Param("farmId") UUID farmId, @Param("since") Instant since);
}

package com.fermapp.api.persistence.repository;

import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.persistence.entity.Parcel;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParcelRepository extends JpaRepository<Parcel, UUID> {

    Optional<Parcel> findByIdAndFarmId(UUID id, UUID farmId);

    Optional<Parcel> findByIdAndFarmIdAndArchivedAtIsNull(UUID id, UUID farmId);

    List<Parcel> findByFarmIdAndArchivedAtIsNullOrderByNameAsc(UUID farmId);

    List<Parcel> findByFarmIdAndArchivedAtIsNull(UUID farmId);

    List<Parcel> findByFarmId(UUID farmId);

    List<Parcel> findByFarmIdAndUpdatedAtGreaterThanEqual(UUID farmId, Instant since);

    List<Parcel> findByArchivedAtIsNull();

    long countByFarmId(UUID farmId);

    /** The parcel list, filtered by archive state and an optional case-insensitive search. */
    @Query("""
            select p from Parcel p
            where p.farmId = :farmId
              and ((:archived = true and p.archivedAt is not null) or (:archived = false and p.archivedAt is null))
              and (:pattern = '' or lower(p.name) like :pattern escape '\\' or lower(p.code) like :pattern escape '\\')
            order by p.name asc
            """)
    List<Parcel> search(@Param("farmId") UUID farmId, @Param("archived") boolean archived, @Param("pattern") String pattern);

    /** Active parcels whose soil analysis is pending or failed, for the retry job. */
    @Query("select p from Parcel p, SoilProfile s where s.parcelId = p.id and p.archivedAt is null and s.status in :statuses")
    List<Parcel> findWithSoilStatus(@Param("statuses") Collection<SoilStatus> statuses, Pageable page);
}

package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.SatelliteObservation;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SatelliteObservationRepository extends JpaRepository<SatelliteObservation, UUID> {

    List<SatelliteObservation> findByParcelIdOrderByDateDesc(UUID parcelId, Pageable page);

    Optional<SatelliteObservation> findFirstByParcelIdOrderByDateDesc(UUID parcelId);

    Optional<SatelliteObservation> findByParcelIdAndDate(UUID parcelId, LocalDate date);

    List<SatelliteObservation> findByParcelIdInOrderByDateDesc(Collection<UUID> parcelIds);

    /** The latest observation of each of the given parcels. */
    @org.springframework.data.jpa.repository.Query("""
            select s from SatelliteObservation s
            where s.parcelId in :parcelIds
              and s.date = (select max(s2.date) from SatelliteObservation s2 where s2.parcelId = s.parcelId)
            """)
    List<SatelliteObservation> findLatestFor(@org.springframework.data.repository.query.Param("parcelIds") Collection<UUID> parcelIds);
}

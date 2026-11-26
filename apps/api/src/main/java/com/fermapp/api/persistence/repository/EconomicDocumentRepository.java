package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.EconomicDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EconomicDocumentRepository extends JpaRepository<EconomicDocument, UUID> {

    Optional<EconomicDocument> findByFarmIdAndKey(UUID farmId, String key);

    @Query("select d from EconomicDocument d where d.farmId = :farmId and d.year = :year and d.key like 'sheet:%'")
    List<EconomicDocument> findSheets(@Param("farmId") UUID farmId, @Param("year") int year);
}

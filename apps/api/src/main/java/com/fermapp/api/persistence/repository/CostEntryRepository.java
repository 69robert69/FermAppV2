package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.CostEntry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CostEntryRepository extends JpaRepository<CostEntry, UUID>, JpaSpecificationExecutor<CostEntry> {

    Optional<CostEntry> findByIdAndFarmId(UUID id, UUID farmId);

    List<CostEntry> findByParcelIdAndYear(UUID parcelId, int year);

    List<CostEntry> findByParcelIdInAndYear(Collection<UUID> parcelIds, int year);
}

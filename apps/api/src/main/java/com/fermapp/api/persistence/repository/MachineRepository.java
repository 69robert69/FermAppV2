package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.Machine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineRepository extends JpaRepository<Machine, UUID> {

    Optional<Machine> findByIdAndFarmIdAndArchivedAtIsNull(UUID id, UUID farmId);

    @Query("select m from Machine m left join fetch m.operator where m.farmId = :farmId and m.archivedAt is null order by m.name asc")
    List<Machine> findActiveWithOperator(@Param("farmId") UUID farmId);

    List<Machine> findByFarmId(UUID farmId);

    List<Machine> findByFarmIdAndUpdatedAtGreaterThanEqual(UUID farmId, Instant since);
}

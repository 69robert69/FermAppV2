package com.fermapp.api.persistence.repository;

import com.fermapp.api.domain.model.Role;
import com.fermapp.api.persistence.entity.FarmMembership;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FarmMembershipRepository extends JpaRepository<FarmMembership, UUID> {

    @Query("select m from FarmMembership m join fetch m.user where m.farmId = :farmId and m.userId = :userId")
    Optional<FarmMembership> findWithUser(@Param("farmId") UUID farmId, @Param("userId") UUID userId);

    Optional<FarmMembership> findByFarmIdAndUserId(UUID farmId, UUID userId);

    @Query("select m from FarmMembership m join fetch m.user where m.farmId = :farmId order by m.createdAt asc")
    List<FarmMembership> findTeam(@Param("farmId") UUID farmId);

    @Query("select m from FarmMembership m join fetch m.farm where m.userId = :userId order by m.createdAt asc")
    List<FarmMembership> findWithFarmByUser(@Param("userId") UUID userId);

    long countByFarmIdAndRoleAndUserIdNot(UUID farmId, Role role, UUID userId);

    List<FarmMembership> findByFarmIdAndRoleIn(UUID farmId, Collection<Role> roles);

    List<FarmMembership> findByFarmId(UUID farmId);

    List<FarmMembership> findByFarmIdAndUserIdIn(UUID farmId, Collection<UUID> userIds);

    List<FarmMembership> findByFarmIdAndUserIdInAndRoleIn(UUID farmId, Collection<UUID> userIds, Collection<Role> roles);
}

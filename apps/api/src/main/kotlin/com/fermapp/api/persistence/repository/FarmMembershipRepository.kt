package com.fermapp.api.persistence.repository

import com.fermapp.api.domain.model.Role
import com.fermapp.api.persistence.entity.FarmMembership
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FarmMembershipRepository : JpaRepository<FarmMembership, UUID> {

    @Query("select m from FarmMembership m join fetch m.user where m.farmId = :farmId and m.userId = :userId")
    fun findWithUser(@Param("farmId") farmId: UUID, @Param("userId") userId: UUID): Optional<FarmMembership>

    fun findByFarmIdAndUserId(farmId: UUID, userId: UUID): Optional<FarmMembership>

    @Query("select m from FarmMembership m join fetch m.user where m.farmId = :farmId order by m.createdAt asc")
    fun findTeam(@Param("farmId") farmId: UUID): List<FarmMembership>

    @Query("select m from FarmMembership m join fetch m.farm where m.userId = :userId order by m.createdAt asc")
    fun findWithFarmByUser(@Param("userId") userId: UUID): List<FarmMembership>

    fun countByFarmIdAndRoleAndUserIdNot(farmId: UUID, role: Role, userId: UUID): Long

    fun findByFarmIdAndRoleIn(farmId: UUID, roles: Collection<Role>): List<FarmMembership>

    fun findByFarmId(farmId: UUID): List<FarmMembership>

    fun findByFarmIdAndUserIdIn(farmId: UUID, userIds: Collection<UUID>): List<FarmMembership>

    fun findByFarmIdAndUserIdInAndRoleIn(farmId: UUID, userIds: Collection<UUID>, roles: Collection<Role>): List<FarmMembership>
}

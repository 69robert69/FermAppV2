package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.RefreshToken
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {

    fun findByTokenHash(tokenHash: String): Optional<RefreshToken>

    /** Revokes every still-active token of a family. */
    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    fun revokeFamily(@Param("familyId") familyId: UUID, @Param("now") now: Instant): Int

    /** Revokes one token only if it is still active; 0 means another request used it first. */
    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    fun revokeIfActive(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.farmId = :farmId and t.revokedAt is null")
    fun revokeForUserAndFarm(@Param("userId") userId: UUID, @Param("farmId") farmId: UUID, @Param("now") now: Instant): Int
}

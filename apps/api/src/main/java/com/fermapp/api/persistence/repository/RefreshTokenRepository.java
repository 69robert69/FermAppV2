package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes every still-active token of a family. */
    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** Revokes one token only if it is still active; 0 means another request used it first. */
    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.farmId = :farmId and t.revokedAt is null")
    int revokeForUserAndFarm(@Param("userId") UUID userId, @Param("farmId") UUID farmId, @Param("now") Instant now);
}

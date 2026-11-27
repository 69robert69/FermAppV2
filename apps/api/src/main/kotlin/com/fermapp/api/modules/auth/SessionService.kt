package com.fermapp.api.modules.auth

import com.fermapp.api.common.util.Times
import com.fermapp.api.config.AppProperties
import com.fermapp.api.modules.auth.AuthDtos.SessionTokens
import com.fermapp.api.persistence.entity.RefreshToken
import com.fermapp.api.persistence.repository.RefreshTokenRepository
import com.fermapp.api.security.AuthContext
import com.fermapp.api.security.JwtService
import com.fermapp.api.security.TokenHashing
import java.util.UUID
import org.springframework.stereotype.Service

/** Issues sessions: a short-lived access token and a rotating refresh token (lib/tokens.ts). */
@Service
class SessionService(
    private val jwt: JwtService,
    private val refreshTokens: RefreshTokenRepository,
    private val properties: AppProperties,
) {

    fun issueRefreshToken(userId: UUID, farmId: UUID, familyId: UUID?): String {
        val token = TokenHashing.randomBase64Url(48)
        val record = RefreshToken()
        record.userId = userId
        record.farmId = farmId
        record.familyId = familyId ?: UUID.randomUUID()
        record.tokenHash = TokenHashing.sha256(token)
        record.expiresAt = Times.now().plusMillis(properties.refreshTokenTtlDays() * Times.DAY_MS)
        refreshTokens.save(record)
        return token
    }

    @JvmOverloads
    fun issueSession(auth: AuthContext, familyId: UUID? = null): SessionTokens =
        SessionTokens(jwt.sign(auth), issueRefreshToken(auth.userId(), auth.farmId(), familyId), jwt.accessTtlSeconds())
}

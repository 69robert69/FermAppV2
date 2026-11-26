package com.fermapp.api.modules.auth;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.modules.auth.AuthDtos.SessionTokens;
import com.fermapp.api.persistence.entity.RefreshToken;
import com.fermapp.api.persistence.repository.RefreshTokenRepository;
import com.fermapp.api.security.AuthContext;
import com.fermapp.api.security.JwtService;
import com.fermapp.api.security.TokenHashing;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Issues sessions: a short-lived access token and a rotating refresh token (lib/tokens.ts). */
@Service
public class SessionService {

    private final JwtService jwt;
    private final RefreshTokenRepository refreshTokens;
    private final AppProperties properties;

    public SessionService(JwtService jwt, RefreshTokenRepository refreshTokens, AppProperties properties) {
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
    }

    public String issueRefreshToken(UUID userId, UUID farmId, UUID familyId) {
        String token = TokenHashing.randomBase64Url(48);
        RefreshToken record = new RefreshToken();
        record.setUserId(userId);
        record.setFarmId(farmId);
        record.setFamilyId(familyId == null ? UUID.randomUUID() : familyId);
        record.setTokenHash(TokenHashing.sha256(token));
        record.setExpiresAt(Times.now().plusMillis(properties.refreshTokenTtlDays() * Times.DAY_MS));
        refreshTokens.save(record);
        return token;
    }

    public SessionTokens issueSession(AuthContext auth, UUID familyId) {
        return new SessionTokens(jwt.sign(auth), issueRefreshToken(auth.userId(), auth.farmId(), familyId), jwt.accessTtlSeconds());
    }

    public SessionTokens issueSession(AuthContext auth) {
        return issueSession(auth, null);
    }
}

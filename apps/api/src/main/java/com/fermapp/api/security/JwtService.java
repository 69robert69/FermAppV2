package com.fermapp.api.security;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.domain.model.Role;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Access tokens: HS256 JWTs with {@code sub} = user id, {@code farmId}, {@code role} and
 * {@code iss} = "fermapp". The format is the one jsonwebtoken produced, so tokens issued by
 * the previous server keep working with the same secret.
 */
@Service
public class JwtService {

    public static final String ISSUER = "fermapp";
    public static final String EXPIRED_MESSAGE = "Your session has expired. Please sign in again.";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AppProperties properties;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        SecretKey key = new SecretKeySpec(properties.jwtAccessSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // jsonwebtoken has no clock tolerance by default, so neither does this validator.
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(ISSUER)));
        this.decoder = nimbus;
    }

    public String sign(AuthContext auth) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(auth.userId().toString())
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(properties.jwtAccessTtlSeconds()))
                .claim("farmId", auth.farmId().toString())
                .claim("role", auth.role().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Verifies the token; any problem becomes 401 "Your session has expired...". */
    public AuthContext verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return new AuthContext(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("farmId")),
                    Role.valueOf(jwt.getClaimAsString("role")));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw Errors.unauthorized(EXPIRED_MESSAGE);
        }
    }

    public int accessTtlSeconds() {
        return properties.jwtAccessTtlSeconds();
    }
}

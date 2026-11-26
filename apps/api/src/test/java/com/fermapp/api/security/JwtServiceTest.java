package com.fermapp.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fermapp.api.common.error.ApiException;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.domain.model.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Access tokens: round trip, tampering and the secret check. */
class JwtServiceTest {

    static AppProperties properties(String secret) {
        return new AppProperties("test", "http://localhost:8081", null, secret, 900, 30, null, null, null,
                "https://identity.example/token", "https://sh.example", null, "claude-opus-5-5", null, null, null, null,
                ".tmp/uploads", false, false, 600);
    }

    private final JwtService jwt = new JwtService(properties("test-secret-test-secret-test-secret-123456"));

    @Test
    void signsAndVerifiesTheSameContext() {
        AuthContext auth = new AuthContext(UUID.randomUUID(), UUID.randomUUID(), Role.AGRONOMIST);
        assertThat(jwt.verify(jwt.sign(auth))).isEqualTo(auth);
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        JwtService other = new JwtService(properties("another-secret-another-secret-another-1"));
        String token = other.sign(new AuthContext(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN));
        assertThatThrownBy(() -> jwt.verify(token))
                .isInstanceOf(ApiException.class)
                .hasMessage(JwtService.EXPIRED_MESSAGE);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwt.verify("not-a-token")).isInstanceOf(ApiException.class);
    }

    @Test
    void hashesLikeNodeCrypto() {
        assertThat(TokenHashing.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}

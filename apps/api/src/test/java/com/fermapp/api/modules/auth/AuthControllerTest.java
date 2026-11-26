package com.fermapp.api.modules.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fermapp.api.common.web.IdempotencyService;
import com.fermapp.api.common.web.RateLimiter;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.config.JacksonConfig;
import com.fermapp.api.config.SecurityConfig;
import com.fermapp.api.config.ValidationConfig;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.modules.auth.AuthDtos.FarmRef;
import com.fermapp.api.modules.auth.AuthDtos.FarmWithRole;
import com.fermapp.api.modules.auth.AuthDtos.LoginResponse;
import com.fermapp.api.modules.auth.AuthDtos.SessionTokens;
import com.fermapp.api.modules.common.Views.PublicUser;
import com.fermapp.api.security.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract of the auth endpoints: envelope, validation errors and authentication. */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JacksonConfig.class, ValidationConfig.class, RateLimiter.class, JwtService.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
    "fermapp.environment=test",
    "fermapp.cors-origins=http://localhost:8081",
    "fermapp.jwt-access-secret=test-secret-test-secret-test-secret-123456",
    "fermapp.jwt-access-ttl-seconds=900",
    "fermapp.refresh-token-ttl-days=30",
    "fermapp.sentinel-token-url=https://identity.example/token",
    "fermapp.sentinel-api-url=https://sh.example",
    "fermapp.anthropic-model=claude-opus-5-5",
    "fermapp.upload-dir=.tmp/uploads",
    "fermapp.jobs-enabled=false",
    "fermapp.seed-demo-data=false",
    "fermapp.global-rate-limit-per-minute=600"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @Test
    void loginReturnsTheSessionInsideData() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID farmId = UUID.randomUUID();
        when(authService.login(any())).thenReturn(new LoginResponse(
                new PublicUser(userId, "demo@fermapp.local", "Demo"), new FarmRef(farmId, "Demo Farm"), Role.ADMIN,
                List.of(new FarmWithRole(farmId, "Demo Farm", Role.ADMIN)), new SessionTokens("access", "refresh", 900)));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Demo@FermApp.local\",\"password\":\"FermApp-demo-2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.accessToken").value("access"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.session").doesNotExist());
    }

    @Test
    void invalidBodyIsABadRequestWithDetails() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\",\"password\":\"short\",\"fullName\":\"A\",\"farmName\":\"Farm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Invalid data in the request body."))
                .andExpect(jsonPath("$.error.details.length()").value(3));
    }

    @Test
    void protectedEndpointsNeedAToken() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("Authentication required."));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer broken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value(JwtService.EXPIRED_MESSAGE));
    }
}

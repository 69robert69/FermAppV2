package com.fermapp.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration, bound from application.yml (which reads the same environment
 * variables as the TypeScript server). Invalid values stop the application at startup,
 * like the zod schema in config/env.ts did.
 */
@Validated
@ConfigurationProperties(prefix = "fermapp")
public record AppProperties(
        @NotBlank String environment,
        @NotBlank String corsOrigins,
        String redisUrl,
        @NotBlank @Size(min = 32, message = "JWT_ACCESS_SECRET must be at least 32 characters long.") String jwtAccessSecret,
        @Positive int jwtAccessTtlSeconds,
        @Positive int refreshTokenTtlDays,
        String openweatherApiKey,
        String sentinelClientId,
        String sentinelClientSecret,
        @NotBlank String sentinelTokenUrl,
        @NotBlank String sentinelApiUrl,
        String anthropicApiKey,
        @NotBlank String anthropicModel,
        String mqttUrl,
        String mqttUsername,
        String mqttPassword,
        String expoAccessToken,
        @NotBlank String uploadDir,
        boolean jobsEnabled,
        boolean seedDemoData,
        @Min(1) int globalRateLimitPerMinute) {

    public boolean isTest() {
        return "test".equals(environment);
    }

    public boolean isProduction() {
        return "production".equals(environment);
    }

    public List<String> corsOriginList() {
        return Arrays.stream(corsOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public boolean hasRedis() {
        return present(redisUrl);
    }

    public boolean hasOpenWeather() {
        return present(openweatherApiKey);
    }

    public boolean hasSentinel() {
        return present(sentinelClientId) && present(sentinelClientSecret);
    }

    public boolean hasAnthropic() {
        return present(anthropicApiKey);
    }

    public boolean hasMqtt() {
        return present(mqttUrl);
    }

    public boolean hasExpoToken() {
        return present(expoAccessToken);
    }
}

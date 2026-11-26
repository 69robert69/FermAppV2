package com.fermapp.api.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The TypeScript server read DATABASE_URL in the libpq form
 * {@code postgresql://user:password@host:port/database?schema=public}. JDBC needs the URL,
 * the user and the password separately, so the same variable keeps working unchanged.
 * JDBC_DATABASE_URL, when set, takes precedence.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new HashMap<>();
        String jdbc = environment.getProperty("JDBC_DATABASE_URL");
        String raw = environment.getProperty("DATABASE_URL");
        if ((jdbc == null || jdbc.isBlank()) && raw != null && !raw.isBlank()) {
            values.putAll(convert(raw.trim()));
        }
        // Redis is optional: an empty REDIS_URL must not reach Spring Data Redis, which rejects it.
        String redis = environment.getProperty("REDIS_URL");
        if (redis != null && !redis.isBlank()) {
            values.put("spring.data.redis.url", redis.trim());
        }
        if (!values.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("fermappDatabaseUrl", values));
        }
    }

    static Map<String, Object> convert(String raw) {
        Map<String, Object> values = new HashMap<>();
        if (raw.startsWith("jdbc:")) {
            values.put("spring.datasource.url", raw);
            return values;
        }
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(uri.getHost()).append(':').append(port).append(uri.getPath());
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            // The "schema" parameter used by Prisma is "currentSchema" in the PostgreSQL JDBC driver.
            url.append('?').append(query.replaceAll("(^|&)schema=", "$1currentSchema="));
        }
        values.put("spring.datasource.url", url.toString());
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            values.put("spring.datasource.username", URLDecoder.decode(parts[0], StandardCharsets.UTF_8));
            if (parts.length > 1) {
                values.put("spring.datasource.password", URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }
}

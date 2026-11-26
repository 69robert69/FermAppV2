package com.fermapp.api.config;

import com.fermapp.api.security.JwtAuthenticationFilter;
import com.fermapp.api.security.JwtService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless security. The JWT filter authenticates the request; which roles may call an
 * endpoint is declared per handler with {@code @Access} and enforced by the AccessInterceptor,
 * so the error bodies stay exactly the API's own. The response headers reproduce helmet's
 * defaults (without a Content-Security-Policy, as before).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, AppProperties properties) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .defaultsDisabled()
                        .contentTypeOptions(options -> { })
                        .frameOptions(frame -> frame.sameOrigin())
                        .addHeaderWriter(new StaticHeadersWriter("Strict-Transport-Security", "max-age=31536000; includeSubDomains"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin"))
                        .addHeaderWriter(new StaticHeadersWriter("Origin-Agent-Cluster", "?1"))
                        .addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "no-referrer"))
                        .addHeaderWriter(new StaticHeadersWriter("X-DNS-Prefetch-Control", "off"))
                        .addHeaderWriter(new StaticHeadersWriter("X-Download-Options", "noopen"))
                        .addHeaderWriter(new StaticHeadersWriter("X-Permitted-Cross-Domain-Policies", "none"))
                        .addHeaderWriter(new StaticHeadersWriter("X-XSS-Protection", "0")));
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.corsOriginList());
        configuration.setAllowedMethods(List.of("GET", "HEAD", "PUT", "PATCH", "POST", "DELETE"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Disposition", "Idempotency-Replayed", "RateLimit", "RateLimit-Policy"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

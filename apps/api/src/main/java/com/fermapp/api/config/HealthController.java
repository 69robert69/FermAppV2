package com.fermapp.api.config;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /health}: 200 {"status":"ok"} when the database answers, otherwise 503 {"status":"degraded"}. */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (RuntimeException error) {
            return ResponseEntity.status(503).body(Map.of("status", "degraded"));
        }
    }
}

package com.nowserving.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * NS-0: GET /health -> {status, dbConnected, version}.
 *
 * Why hand-roll this instead of using Spring Boot Actuator? Actuator is the
 * production answer (and arrives with monitoring in a later sprint), but a
 * 20-line version teaches what a health check IS: "can I do my job right now?"
 * — and for this app, the job requires a working database connection.
 */
@RestController
@RequiredArgsConstructor // Lombok: constructor for all final fields -> constructor injection
public class HealthController {

    private final DataSource dataSource;

    @Value("${app.version}")
    private String version;

    /** Response shape as a record: immutable, auto-serialized to JSON by Jackson. */
    public record HealthResponse(String status, boolean dbConnected, String version) {}

    @GetMapping("/health")
    public HealthResponse health() {
        boolean dbConnected;
        try (Connection conn = dataSource.getConnection()) {
            // isValid() pings the DB (SELECT 1-style) with a 1-second timeout.
            dbConnected = conn.isValid(1);
        } catch (Exception e) {
            dbConnected = false;
        }
        return new HealthResponse(dbConnected ? "UP" : "DEGRADED", dbConnected, version);
    }
}

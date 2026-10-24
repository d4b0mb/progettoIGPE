package it.ristomanager.cloud.status;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports what this instance is and whether it can reach its database.
 * Unlike the actuator health probe (meant for Kubernetes), this is a
 * readable endpoint for humans and for the end-to-end tests.
 */
@RestController
@RequestMapping("/api/v1/status")
class StatusController {

    private final JdbcClient jdbc;
    private final String application;
    private final String version;

    StatusController(JdbcClient jdbc,
                     @Value("${spring.application.name}") String application,
                     @Value("${app.version}") String version) {
        this.jdbc = jdbc;
        this.application = application;
        this.version = version;
    }

    @GetMapping
    StatusResponse status() {
        return new StatusResponse(application, version, databaseStatus());
    }

    private String databaseStatus() {
        try {
            jdbc.sql("select 1").query(Integer.class).single();
            return "UP";
        } catch (RuntimeException e) {
            return "DOWN";
        }
    }
}

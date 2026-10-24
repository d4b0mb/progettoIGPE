package it.ristomanager.cloud.support;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * A real PostgreSQL in Docker for integration tests, set up the way production is:
 * the image tag matches docker-compose.yml, the same init script creates the
 * restricted application role, Flyway runs as the owner, and the application
 * connects as that restricted role, so row-level security applies in every test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    public static final String APP_ROLE = "ristomanager_app";
    public static final String APP_PASSWORD = "ristomanager_app";

    @Bean
    PostgreSQLContainer postgres() {
        Path script = Path.of("..", "infra", "postgres", "01-app-role.sh").toAbsolutePath().normalize();
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("ristomanager")
                .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(script, 0755),
                        "/docker-entrypoint-initdb.d/01-app-role.sh");
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }
}

package it.ristomanager.cloud.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API contract. The frontend generates its types from frontend/openapi/openapi.json,
 * so that file must always match what the backend really serves. This test fails when
 * they drift. To accept an intended change, run:
 * {@code UPDATE_OPENAPI=true ./mvnw verify -Dit.test=OpenApiContractIT} and commit the file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiContractIT {

    private static final Path CONTRACT = Path.of("..", "frontend", "openapi", "openapi.json");

    @Autowired
    MockMvc mvc;

    @Autowired
    org.springframework.context.ApplicationContext context;

    /** Same content, stable key order and indentation, so only real changes show up as differences. */
    private static String normalize(String json) {
        JsonMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        return mapper.writeValueAsString(mapper.readTree(json)) + "\n";
    }

    /**
     * OpenAPI names a schema after the Java class's simple name, and two different classes with
     * the same simple name are silently merged into one. That once typed the order-status call with
     * the reservation statuses. This fails if it ever happens again.
     */
    @Test
    void noTwoApiTypesShareASchemaName() {
        java.util.Map<String, Class<?>> seen = new java.util.HashMap<>();
        java.util.List<String> clashes = new java.util.ArrayList<>();
        String[] holders = {
                "it.ristomanager.cloud.floor.FloorTypes", "it.ristomanager.cloud.reservation.ReservationTypes",
                "it.ristomanager.cloud.order.OrderTypes", "it.ristomanager.cloud.menu.MenuTypes"};
        java.util.List<Class<?>> candidates = new java.util.ArrayList<>();
        for (String holder : holders) {
            try {
                candidates.addAll(java.util.Arrays.asList(Class.forName(holder).getDeclaredClasses()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        context.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController.class).values()
                .forEach(bean -> candidates.addAll(java.util.Arrays.asList(
                        org.springframework.util.ClassUtils.getUserClass(bean).getDeclaredClasses())));
        for (Class<?> type : candidates) {
            Class<?> other = seen.put(type.getSimpleName(), type);
            if (other != null && !other.equals(type)) {
                clashes.add(type.getSimpleName() + ": " + other.getName() + " and " + type.getName());
            }
        }
        assertThat(clashes).as("API types that would merge into one OpenAPI schema").isEmpty();
    }

    @Test
    void theCommittedContractMatchesTheRunningApi() throws Exception {
        String live = normalize(mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        if ("true".equals(System.getenv("UPDATE_OPENAPI"))) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, live);
            return;
        }
        assertThat(CONTRACT).as("Missing contract file. Run with UPDATE_OPENAPI=true to create it.").exists();
        assertThat(live)
                .as("frontend/openapi/openapi.json is out of date. Run: "
                        + "UPDATE_OPENAPI=true ./mvnw verify -Dit.test=OpenApiContractIT, review the diff and commit it.")
                .isEqualTo(Files.readString(CONTRACT));
    }
}

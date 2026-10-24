package it.ristomanager.cloud.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shapes the API description the frontend generates its types from.
 *
 * By default every field of a Java record is listed as optional, which would force
 * the frontend to check for "undefined" on data that is always there. Here, every
 * field of a <em>response</em> is declared required unless it is marked
 * {@code @Schema(nullable = true)} on the record. Request shapes keep the
 * validation rules from their annotations.
 */
@Configuration
class OpenApiConfig {

    /** Shapes the API returns. Everything else is a request body or a helper type. */
    private static final Set<String> RESPONSES = Set.of(
            "Room", "DiningTable", "Obstacle", "TableView", "Layout",
            "Reservation", "MenuItem", "UserView", "ItemView", "TicketView",
            "TokenResponse", "StatusResponse");

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    OpenApiCustomizer responseFieldsAreRequired() {
        return openApi -> {
            openApi.info(new Info().title("RistoManager Cloud API").version("v1"));
            var schemas = openApi.getComponents().getSchemas();
            if (schemas == null) {
                return;
            }
            schemas.forEach((name, schema) -> {
                Map<String, Schema> properties = schema.getProperties();
                if (RESPONSES.contains(name) && properties != null) {
                    List<String> required = new ArrayList<>();
                    properties.forEach((property, definition) -> {
                        if (!isNullable(definition)) {
                            required.add(property);
                        }
                    });
                    schema.setRequired(required);
                }
            });
            // The live stream is described by hand; the Java helper type is not part of the contract.
            schemas.remove("SseEmitter");
        };
    }

    @SuppressWarnings("rawtypes")
    private static boolean isNullable(Schema property) {
        return (property.getTypes() != null && property.getTypes().contains("null"))
                || Boolean.TRUE.equals(property.getNullable());
    }
}

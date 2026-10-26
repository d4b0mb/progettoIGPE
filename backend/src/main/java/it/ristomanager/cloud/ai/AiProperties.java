package it.ristomanager.cloud.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai")
public record AiProperties(boolean enabled, String baseUrl, String model, double temperature, int contextTokens,
                           String keepAlive, boolean think, Duration connectTimeout, Duration requestTimeout) {
}

package it.ristomanager.cloud.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwtSecret    signing key, at least 32 bytes. Blank means "generate a random one
 *                     at startup" (fine for local runs; tokens die on restart).
 * @param accessTtl    life of an access token; short, because it cannot be revoked
 * @param refreshTtl   life of a refresh token
 * @param cookieSecure send the refresh cookie over HTTPS only (true in production)
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(String jwtSecret, Duration accessTtl, Duration refreshTtl, boolean cookieSecure) {
}

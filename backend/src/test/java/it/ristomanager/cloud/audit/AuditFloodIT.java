package it.ristomanager.cloud.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.OwnerDb;
import it.ristomanager.cloud.support.PostgresTestConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Anonymous failures (an unknown email trying to sign in) leave a row with no restaurant. They are limited
 * per address and overall, and one summary row per minute says how many were left out. Small limits and a
 * clock the test moves keep this fast and independent of the real time.
 */
@SpringBootTest(properties = {
        "app.audit.unscoped-per-address-per-minute=5",
        "app.audit.unscoped-per-minute=50"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class AuditFloodIT {

    private static final int PER_ADDRESS = 5;

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockConfig {
        @Bean
        UnscopedFailureLimiterTest.TestClock auditTestClock() {
            return new UnscopedFailureLimiterTest.TestClock();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    AuditLog auditLog;

    @Autowired
    UnscopedFailureLimiterTest.TestClock clock;

    ApiClient api;

    @BeforeEach
    void freshMinute() {
        api = new ApiClient(mvc);
        // Start every test in a new minute and hand out what earlier tests left over, so counts start clean.
        clock.advance(Duration.ofMinutes(1));
        auditLog.writeSuppressionSummary();
    }

    private static String address() {
        // Any text works as a client address for the log; a unique one keeps the tests apart.
        return "test-" + UUID.randomUUID();
    }

    private void failedLogin(String address, String email) throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"whatever-password-1"}
                                """.formatted(email))
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        }))
                .andExpect(status().isUnauthorized());
    }

    private static String stranger() {
        return "stranger-" + UUID.randomUUID() + "@example.com";
    }

    private long unscopedRowsFrom(OwnerDb db, String address) {
        return db.count("select count(*) from audit_event where restaurant_id is null and action = 'LOGIN_FAILED' and ip = ?", address);
    }

    private long summaryRows(OwnerDb db) {
        return db.count("select count(*) from audit_event where restaurant_id is null and action = 'AUTH_FAILURES_SUPPRESSED'");
    }

    @Test
    void aFloodFromOneAddressLeavesAtMostTheLimitPlusOneSummary() throws Exception {
        String flooder = address();
        int attempts = 30;
        try (OwnerDb db = new OwnerDb(postgres)) {
            long summariesBefore = summaryRows(db);

            for (int i = 0; i < attempts; i++) {
                failedLogin(flooder, stranger());
            }
            assertThat(unscopedRowsFrom(db, flooder)).isEqualTo(PER_ADDRESS);
            assertThat(summaryRows(db)).as("no summary while the minute is running").isEqualTo(summariesBefore);

            clock.advance(Duration.ofMinutes(1));
            auditLog.writeSuppressionSummary();
            auditLog.writeSuppressionSummary();

            assertThat(summaryRows(db)).as("exactly one summary").isEqualTo(summariesBefore + 1);
            String dropped = db.one("""
                    select details->>'dropped' from audit_event
                     where restaurant_id is null and action = 'AUTH_FAILURES_SUPPRESSED'
                     order by occurred_at desc, id desc limit 1
                    """, String.class);
            assertThat(dropped).isEqualTo(Integer.toString(attempts - PER_ADDRESS));
            String summaryDetails = db.one("""
                    select details::text from audit_event
                     where restaurant_id is null and action = 'AUTH_FAILURES_SUPPRESSED'
                     order by occurred_at desc, id desc limit 1
                    """, String.class);
            assertThat(summaryDetails).as("count only, no email").doesNotContain("@").doesNotContain("stranger");
        }
    }

    @Test
    void anotherAddressIsNotAffectedByTheFlood() throws Exception {
        String flooder = address();
        String neighbour = address();
        for (int i = 0; i < 12; i++) {
            failedLogin(flooder, stranger());
        }
        failedLogin(neighbour, stranger());
        failedLogin(neighbour, stranger());

        try (OwnerDb db = new OwnerDb(postgres)) {
            assertThat(unscopedRowsFrom(db, flooder)).isEqualTo(PER_ADDRESS);
            assertThat(unscopedRowsFrom(db, neighbour)).isEqualTo(2);
        }
    }

    @Test
    void aNormalFlowWithAFewFailuresIsRecordedInFullAndLeavesNoSummary() throws Exception {
        String visitor = address();
        try (OwnerDb db = new OwnerDb(postgres)) {
            long summariesBefore = summaryRows(db);
            failedLogin(visitor, stranger());
            failedLogin(visitor, stranger());
            failedLogin(visitor, stranger());

            clock.advance(Duration.ofMinutes(1));
            auditLog.writeSuppressionSummary();

            assertThat(unscopedRowsFrom(db, visitor)).isEqualTo(3);
            assertThat(summaryRows(db)).isEqualTo(summariesBefore);
        }
    }

    @Test
    void entriesOfARestaurantAreNeverLimited() throws Exception {
        Actor owner = api.signup("R");
        String flooder = address();
        for (int i = 0; i < 12; i++) {
            failedLogin(flooder, stranger());
        }
        // The same exhausted address now fails against a real account: that entry belongs to the restaurant.
        int wrongPasswords = PER_ADDRESS + 3;
        for (int i = 0; i < wrongPasswords; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","password":"a-wrong-password-123"}
                                    """.formatted(owner.email()))
                            .with(request -> {
                                request.setRemoteAddr(flooder);
                                return request;
                            }))
                    .andExpect(status().isUnauthorized());
        }

        String log = api.get(owner, "/api/v1/audit?limit=500").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='LOGIN_FAILED')]").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        int failures = log.split("\"LOGIN_FAILED\"", -1).length - 1;
        assertThat(failures).isEqualTo(wrongPasswords);
        assertThat(log).doesNotContain("AUTH_FAILURES_SUPPRESSED");
    }
}

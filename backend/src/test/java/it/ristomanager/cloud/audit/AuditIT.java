package it.ristomanager.cloud.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.OwnerDb;
import it.ristomanager.cloud.support.PostgresTestConfig;
import it.ristomanager.cloud.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class AuditIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    ApiClient api;
    Actor owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc);
        owner = api.signup("R");
    }

    private String log(Actor who) throws Exception {
        return api.get(who, "/api/v1/audit").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void signInsAreLoggedWhetherTheyWorkOrNot() throws Exception {
        api.login(owner.email(), "a-wrong-password-123").andExpect(status().isUnauthorized());
        api.login(owner.email(), ApiClient.PASSWORD).andExpect(status().isOk());

        api.get(owner, "/api/v1/audit").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='SIGNUP')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.action=='LOGIN_SUCCESS')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.action=='LOGIN_FAILED')].outcome").value(org.hamcrest.Matchers.hasItem("FAILURE")))
                .andExpect(jsonPath("$[?(@.action=='LOGIN_FAILED')].details.reason").value(org.hamcrest.Matchers.hasItem("wrong password")));
    }

    @Test
    void aFailedSignInForAnUnknownEmailIsKeptButNotShownToAnyRestaurant() throws Exception {
        String stranger = "stranger-" + UUID.randomUUID() + "@example.com";
        api.login(stranger, "whatever-password-1").andExpect(status().isUnauthorized());

        assertThat(log(owner)).doesNotContain(stranger);
        try (OwnerDb db = new OwnerDb(postgres)) {
            assertThat(db.count("select count(*) from audit_event where restaurant_id is null and actor_label = ? and action = 'LOGIN_FAILED'", stranger))
                    .isEqualTo(1);
        }
    }

    @Test
    void changesAndRefusalsAreRecordedWithWhoDidThem() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        String room = ApiClient.read(api.post(owner, "/api/v1/rooms", "{\"name\":\"Main\",\"widthMeters\":10,\"heightMeters\":8}")
                .andExpect(status().isCreated()), "$.id");
        // The waiter tries something only the owner may do.
        api.post(waiter, "/api/v1/staff", "{\"displayName\":\"X\",\"email\":\"x@example.com\",\"password\":\"%s\",\"role\":\"WAITER\"}"
                .formatted(ApiClient.PASSWORD)).andExpect(status().isForbidden());
        api.delete(waiter, "/api/v1/rooms/" + room).andExpect(status().isForbidden());

        api.get(owner, "/api/v1/audit").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='STAFF_CREATED')].details.role").value(org.hamcrest.Matchers.hasItem("WAITER")))
                .andExpect(jsonPath("$[?(@.action=='ROOM_CREATED')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.action=='ACCESS_DENIED' && @.actorUserId=='" + waiter.userId() + "')].outcome")
                        .value(org.hamcrest.Matchers.hasItems("FAILURE", "FAILURE")));
    }

    @Test
    void ordersLogTheirCodeAndEachStatusStep() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        Actor kitchen = api.staff(owner, "KITCHEN");
        String room = ApiClient.read(api.post(owner, "/api/v1/rooms", "{\"name\":\"Main\",\"widthMeters\":10,\"heightMeters\":8}")
                .andExpect(status().isCreated()), "$.id");
        String table = ApiClient.read(api.post(owner, "/api/v1/rooms/" + room + "/tables",
                "{\"xMeters\":1,\"yMeters\":1,\"widthMeters\":1.2,\"heightMeters\":0.8,\"shape\":\"RECTANGLE\",\"seats\":4}").andExpect(status().isCreated()), "$.id");
        String dish = ApiClient.read(api.post(owner, "/api/v1/menu/items", "{\"name\":\"Steak\",\"course\":\"SECOND\"}").andExpect(status().isCreated()), "$.id");
        String order = ApiClient.read(api.post(waiter, "/api/v1/orders",
                "{\"tableId\":\"%s\",\"items\":[{\"menuItemId\":\"%s\",\"quantity\":1}]}".formatted(table, dish)).andExpect(status().isCreated()), "$.id");
        api.patch(kitchen, "/api/v1/orders/" + order + "/status", "{\"status\":\"PREPARING\"}").andExpect(status().isOk());

        api.get(owner, "/api/v1/audit").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ORDER_CREATED')].details.code").value(org.hamcrest.Matchers.hasItem("C-001")))
                .andExpect(jsonPath("$[?(@.action=='ORDER_STATUS_CHANGED')].details.from").value(org.hamcrest.Matchers.hasItem("NEW")))
                .andExpect(jsonPath("$[?(@.action=='ORDER_STATUS_CHANGED')].details.to").value(org.hamcrest.Matchers.hasItem("PREPARING")));
    }

    @Test
    void passwordsAndAnswersNeverEndUpInTheLog() throws Exception {
        String secret = "Sup3r-Secret-Pw-XYZ";
        Actor waiter = api.staff(owner, "WAITER");
        api.login(owner.email(), secret).andExpect(status().isUnauthorized());
        api.patch(owner, "/api/v1/staff/" + waiter.userId() + "/password", "{\"newPassword\":\"" + secret + "-new\"}").andExpect(status().isNoContent());
        api.put(owner, "/api/v1/me/recovery", """
                {"currentPassword":"%s","question1":"What was my first dog called?","answer1":"Tr0ub4dor-answer",
                 "question2":"Which street did I grow up on?","answer2":"Hidden-street-answer"}
                """.formatted(ApiClient.PASSWORD)).andExpect(status().isNoContent());

        String everything = log(owner);
        assertThat(everything).contains("PASSWORD_RESET_BY_OWNER", "RECOVERY_SET_UP");
        assertThat(everything).doesNotContain(secret).doesNotContain("Tr0ub4dor").doesNotContain("Hidden-street");
    }

    @Test
    void onlyTheOwnerReadsTheLogAndEachRestaurantSeesOnlyItsOwn() throws Exception {
        Actor manager = api.staff(owner, "MANAGER");
        api.get(manager, "/api/v1/audit").andExpect(status().isForbidden());

        Actor other = api.signup("Other");
        String theirs = log(other);
        assertThat(theirs).doesNotContain(owner.userId()).doesNotContain(owner.email());
        assertThat(log(owner)).doesNotContain(other.userId());
    }

    @Test
    void theApplicationCannotChangeOrEraseItsOwnHistory() {
        UUID restaurant = UUID.fromString(owner.restaurantId());
        TenantContext.callAs(restaurant, () -> {
            assertThatThrownBy(() -> jdbc.sql("update audit_event set action = 'NOTHING_HAPPENED'").update())
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> jdbc.sql("delete from audit_event").update())
                    .rootCause().hasMessageContaining("permission denied");
            return null;
        });
    }

    @Test
    void theLogCanBeFilteredAndReadPageByPage() throws Exception {
        for (int i = 0; i < 4; i++) {
            api.post(owner, "/api/v1/rooms", "{\"name\":\"Room " + i + "\",\"widthMeters\":5,\"heightMeters\":5}").andExpect(status().isCreated());
        }
        api.get(owner, "/api/v1/audit?limit=2").andExpect(jsonPath("$.length()").value(2));
        String all = api.get(owner, "/api/v1/audit?limit=500").andReturn().getResponse().getContentAsString();
        assertThat(all).contains("ROOM_CREATED");
        api.get(owner, "/api/v1/audit?actorId=" + UUID.randomUUID()).andExpect(jsonPath("$.length()").value(0));
        api.get(owner, "/api/v1/audit?limit=0").andExpect(status().isBadRequest());
        api.get(owner, "/api/v1/audit?limit=501").andExpect(status().isBadRequest());
        api.get(owner, "/api/v1/audit?from=2030-01-01T00:00:00Z&to=2029-01-01T00:00:00Z").andExpect(status().isBadRequest());
    }
}

package it.ristomanager.cloud.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.ristomanager.cloud.support.PostgresTestConfig;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class AuthFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;

    /** A signed-up restaurant: its owner's access token, refresh cookie and ids. */
    record Tenant(String accessToken, Cookie refreshCookie, String restaurantId, String ownerId, String email) {
    }

    private String uniqueEmail() {
        return "owner-" + UUID.randomUUID() + "@example.com";
    }

    private Tenant signup(String restaurantName) throws Exception {
        String email = uniqueEmail();
        MvcResult res = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantName":"%s","ownerName":"Marco","email":"%s","password":"%s"}
                                """.formatted(restaurantName, email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        return new Tenant(
                JsonPath.read(body, "$.accessToken"),
                res.getResponse().getCookie("rm_refresh"),
                JsonPath.read(body, "$.user.restaurantId"),
                JsonPath.read(body, "$.user.id"),
                email);
    }

    private String createStaff(Tenant owner, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/staff").header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Staff","email":"%s","password":"%s","role":"%s"}
                                """.formatted(email, PASSWORD, role)))
                .andExpect(status().isCreated());
        return email;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password))).andReturn();
    }

    private String tokenOf(MvcResult res) throws Exception {
        return JsonPath.read(res.getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void signupCreatesOwnerAndHttpOnlyRefreshCookie() throws Exception {
        Tenant t = signup("Trattoria Rende");

        assertThat(t.refreshCookie()).isNotNull();
        assertThat(t.refreshCookie().isHttpOnly()).isTrue();
        assertThat(t.refreshCookie().getPath()).isEqualTo("/api/v1/auth");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + t.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.email").value(t.email()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        Tenant t = signup("First");
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantName":"Second","ownerName":"X","email":"%s","password":"%s"}
                                """.formatted(t.email().toUpperCase(), PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void weakOrOverlongPasswordsAreRejected() throws Exception {
        for (String pw : new String[] {"short", "x".repeat(73)}) {
            mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"restaurantName":"R","ownerName":"O","email":"%s","password":"%s"}
                                    """.formatted(uniqueEmail(), pw)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.password").exists());
        }
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        Tenant t = signup("R");
        String wrongPassword = login(t.email(), "wrong-password-123").getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail(), "wrong-password-123").getResponse().getContentAsString();

        assertThat(login(t.email(), "wrong-password-123").getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/staff")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());

        Tenant t = signup("R");
        String tampered = t.accessToken().substring(0, t.accessToken().length() - 2) + "xx";
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheOwnerManagesStaff() throws Exception {
        Tenant owner = signup("R");
        String waiterEmail = createStaff(owner, "WAITER");
        String waiterToken = tokenOf(login(waiterEmail, PASSWORD));

        mvc.perform(get("/api/v1/staff").header("Authorization", "Bearer " + waiterToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/staff").header("Authorization", "Bearer " + waiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"X","email":"x@example.com","password":"%s","role":"WAITER"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/staff").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void ownerRoleCannotBeCreatedAsStaff() throws Exception {
        Tenant owner = signup("R");
        mvc.perform(post("/api/v1/staff").header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"X","email":"%s","password":"%s","role":"OWNER"}
                                """.formatted(uniqueEmail(), PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivatedStaffCannotLogInOrRefresh() throws Exception {
        Tenant owner = signup("R");
        String email = createStaff(owner, "KITCHEN");
        MvcResult staffLogin = login(email, PASSWORD);
        String staffId = JsonPath.read(staffLogin.getResponse().getContentAsString(), "$.user.id");
        Cookie staffCookie = staffLogin.getResponse().getCookie("rm_refresh");

        mvc.perform(delete("/api/v1/staff/" + staffId).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());

        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        mvc.perform(post("/api/v1/auth/refresh").cookie(staffCookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCannotDeactivateSelfOrAnotherRestaurantsStaff() throws Exception {
        Tenant a = signup("A");
        Tenant b = signup("B");

        mvc.perform(delete("/api/v1/staff/" + a.ownerId()).header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/staff/" + b.ownerId()).header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isNotFound());
        // B's owner is still active.
        assertThat(login(b.email(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void refreshRotatesTokensAndDetectsReuse() throws Exception {
        Tenant t = signup("R");

        MvcResult first = mvc.perform(post("/api/v1/auth/refresh").cookie(t.refreshCookie()))
                .andExpect(status().isOk()).andReturn();
        Cookie rotated = first.getResponse().getCookie("rm_refresh");
        assertThat(rotated.getValue()).isNotEqualTo(t.refreshCookie().getValue());

        // The first token is used up. Presenting it again means it was copied.
        mvc.perform(post("/api/v1/auth/refresh").cookie(t.refreshCookie())).andExpect(status().isUnauthorized());
        // Reuse detection signs the user out everywhere, including the rotated token.
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated)).andExpect(status().isUnauthorized());
    }

    @Test
    void sequentialRefreshesKeepWorkingAlongTheChain() throws Exception {
        Tenant t = signup("R");
        Cookie current = t.refreshCookie();
        for (int i = 0; i < 3; i++) {
            MvcResult res = mvc.perform(post("/api/v1/auth/refresh").cookie(current))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andReturn();
            Cookie next = res.getResponse().getCookie("rm_refresh");
            assertThat(next).isNotNull();
            assertThat(next.getValue()).isNotEqualTo(current.getValue());
            current = next;
        }
        mvc.perform(post("/api/v1/auth/refresh").cookie(current)).andExpect(status().isOk());
    }

    @Test
    void twoRefreshesWithTheSameCookieAtTheSameTimeLetExactlyOneWin() throws Exception {
        Tenant t = signup("R");
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(post("/api/v1/auth/refresh").cookie(t.refreshCookie())).andReturn();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<MvcResult> done = new ArrayList<>();
            for (Future<MvcResult> f : results) {
                done.add(f.get(30, TimeUnit.SECONDS));
            }
            List<MvcResult> winners = done.stream().filter(r -> r.getResponse().getStatus() == 200).toList();
            assertThat(winners).as("requests that got a new session").hasSize(1);
            assertThat(done.stream().filter(r -> r.getResponse().getStatus() == 401).count())
                    .as("requests refused").isEqualTo(attempts - 1);
            assertThat(done.stream().filter(r -> r.getResponse().getCookie("rm_refresh") != null
                    && !r.getResponse().getCookie("rm_refresh").getValue().isEmpty()).count())
                    .as("requests handed a new refresh cookie").isEqualTo(1);

            // The refused ones are treated as a reuse of a used token: the whole family is ended,
            // including the winner's new token.
            Cookie winnerCookie = winners.get(0).getResponse().getCookie("rm_refresh");
            mvc.perform(post("/api/v1/auth/refresh").cookie(winnerCookie)).andExpect(status().isUnauthorized());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        Tenant t = signup("R");
        mvc.perform(post("/api/v1/auth/logout").cookie(t.refreshCookie())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(t.refreshCookie())).andExpect(status().isUnauthorized());
    }
}

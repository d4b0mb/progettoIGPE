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
import java.util.UUID;
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
    void logoutRevokesTheRefreshToken() throws Exception {
        Tenant t = signup("R");
        mvc.perform(post("/api/v1/auth/logout").cookie(t.refreshCookie())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(t.refreshCookie())).andExpect(status().isUnauthorized());
    }
}

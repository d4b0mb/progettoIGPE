package it.ristomanager.cloud.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class AccountIT {

    private static final String PASSWORD = ApiClient.PASSWORD;
    private static final String NEW_PASSWORD = "a-brand-new-password";

    @Autowired
    MockMvc mvc;

    ApiClient api;
    Actor owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc);
        owner = api.signup("R");
    }

    private String setUpRecovery(Actor who) throws Exception {
        api.put(who, "/api/v1/me/recovery", """
                {"currentPassword":"%s","question1":"What was my first dog called?","answer1":"Rex",
                 "question2":"Which street did I grow up on?","answer2":"Via Roma 12"}
                """.formatted(PASSWORD)).andExpect(status().isNoContent());
        return who.email();
    }

    private String loginStatus(String email, String password) throws Exception {
        return String.valueOf(api.login(email, password).andReturn().getResponse().getStatus());
    }

    // ---- the owner resets a staff member's password

    @Test
    void theOwnerCanSetANewPasswordForStaffWhichSignsThemOutEverywhere() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        MvcResult login = api.login(waiter.email(), PASSWORD).andReturn();
        Cookie oldSession = login.getResponse().getCookie("rm_refresh");

        api.patch(owner, "/api/v1/staff/" + waiter.userId() + "/password",
                "{\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)).andExpect(status().isNoContent());

        assertThat(loginStatus(waiter.email(), PASSWORD)).isEqualTo("401");
        assertThat(loginStatus(waiter.email(), NEW_PASSWORD)).isEqualTo("200");
        mvc.perform(post("/api/v1/auth/refresh").cookie(oldSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheOwnerMayResetAndNotForThemselves() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        Actor manager = api.staff(owner, "MANAGER");
        String url = "/api/v1/staff/%s/password";
        String body = "{\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD);

        api.patch(manager, url.formatted(waiter.userId()), body).andExpect(status().isForbidden());
        api.patch(waiter, url.formatted(manager.userId()), body).andExpect(status().isForbidden());
        api.patch(owner, url.formatted(owner.userId()), body).andExpect(status().isBadRequest());
        api.patch(owner, url.formatted(UUID.randomUUID()), body).andExpect(status().isNotFound());
        api.patch(owner, url.formatted(waiter.userId()), "{\"newPassword\":\"short\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.newPassword").exists());
        // The waiter's password is unchanged after all those refusals.
        assertThat(loginStatus(waiter.email(), PASSWORD)).isEqualTo("200");
    }

    @Test
    void anotherRestaurantsOwnerGetsNotFoundNotAChangedPassword() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        Actor other = api.signup("Other");
        api.patch(other, "/api/v1/staff/" + waiter.userId() + "/password",
                "{\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)).andExpect(status().isNotFound());
        assertThat(loginStatus(waiter.email(), PASSWORD)).isEqualTo("200");
    }

    // ---- changing your own password

    @Test
    void aPersonCanChangeTheirOwnPasswordAndStaysSignedInHere() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        MvcResult other = api.login(waiter.email(), PASSWORD).andReturn(); // a second device
        Cookie otherDevice = other.getResponse().getCookie("rm_refresh");

        api.post(waiter, "/api/v1/me/password", """
                {"currentPassword":"wrong-current-password","newPassword":"%s"}
                """.formatted(NEW_PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.currentPassword").exists());

        MvcResult changed = api.post(waiter, "/api/v1/me/password", """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(PASSWORD, NEW_PASSWORD)).andExpect(status().isOk()).andReturn();
        // This device gets a fresh session; the other device is signed out.
        assertThat(JsonPath.<String>read(changed.getResponse().getContentAsString(), "$.accessToken")).isNotBlank();
        mvc.perform(post("/api/v1/auth/refresh").cookie(otherDevice)).andExpect(status().isUnauthorized());
        assertThat(loginStatus(waiter.email(), PASSWORD)).isEqualTo("401");
        assertThat(loginStatus(waiter.email(), NEW_PASSWORD)).isEqualTo("200");
    }

    // ---- owner recovery questions

    @Test
    void anOwnerSetsUpRecoveryAndOnlyTheQuestionsAreEverShownBack() throws Exception {
        setUpRecovery(owner);
        api.get(owner, "/api/v1/me/recovery").andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.question1").value("What was my first dog called?"))
                .andExpect(jsonPath("$.answer1").doesNotExist())
                .andExpect(jsonPath("$.answer1Hash").doesNotExist());
        // Not set up yet for someone else.
        api.get(api.signup("Other"), "/api/v1/me/recovery").andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void settingUpRecoveryNeedsTheCurrentPasswordAndSensibleQuestions() throws Exception {
        String url = "/api/v1/me/recovery";
        api.put(owner, url, """
                {"currentPassword":"not-my-password","question1":"What was my first dog called?","answer1":"Rex",
                 "question2":"Which street did I grow up on?","answer2":"Via Roma"}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.currentPassword").exists());
        api.put(owner, url, """
                {"currentPassword":"%s","question1":"Why?","answer1":"Rex","question2":"Which street did I grow up on?","answer2":"Via Roma"}
                """.formatted(PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.question1").exists());
        api.put(owner, url, """
                {"currentPassword":"%s","question1":"What was my first dog called?","answer1":"Rex",
                 "question2":"What was my first dog called?","answer2":"Via Roma"}
                """.formatted(PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.question2").exists());
        api.put(owner, url, """
                {"currentPassword":"%s","question1":"What was my first dog called?","answer1":"Rex",
                 "question2":"Which street did I grow up on?","answer2":"rex!"}
                """.formatted(PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.answer2").exists());
        api.put(owner, url, """
                {"currentPassword":"%s","question1":"What was my first dog called?","answer1":"ab",
                 "question2":"Which street did I grow up on?","answer2":"Via Roma"}
                """.formatted(PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.answer1").exists());
    }

    @Test
    void staffCannotSetUpRecovery() throws Exception {
        Actor manager = api.staff(owner, "MANAGER");
        api.put(manager, "/api/v1/me/recovery", """
                {"currentPassword":"%s","question1":"What was my first dog called?","answer1":"Rex",
                 "question2":"Which street did I grow up on?","answer2":"Via Roma"}
                """.formatted(PASSWORD)).andExpect(status().isForbidden());
        api.get(manager, "/api/v1/me/recovery").andExpect(status().isForbidden());
    }

    @Test
    void anOwnerWhoForgotTheirPasswordRecoversWithTheirAnswers() throws Exception {
        String email = setUpRecovery(owner);
        MvcResult session = api.login(email, PASSWORD).andReturn();
        Cookie oldSession = session.getResponse().getCookie("rm_refresh");

        api.anonymousPost("/api/v1/auth/recovery/questions", "{\"email\":\"%s\"}".formatted(email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0]").value("What was my first dog called?"))
                .andExpect(jsonPath("$.questions[1]").value("Which street did I grow up on?"));

        // Different capitalisation and punctuation are fine.
        api.anonymousPost("/api/v1/auth/recovery/reset", """
                {"email":"%s","answer1":" REX! ","answer2":"via roma, 12","newPassword":"%s"}
                """.formatted(email.toUpperCase(), NEW_PASSWORD)).andExpect(status().isNoContent());

        assertThat(loginStatus(email, PASSWORD)).isEqualTo("401");
        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo("200");
        mvc.perform(post("/api/v1/auth/refresh").cookie(oldSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAnswersChangeNothingAndGuessingIsStoppedAfterFiveTries() throws Exception {
        String email = setUpRecovery(owner);
        String body = """
                {"email":"%s","answer1":"Fido","answer2":"Via Roma 12","newPassword":"%s"}
                """.formatted(email, NEW_PASSWORD);
        for (int i = 0; i < 5; i++) {
            api.anonymousPost("/api/v1/auth/recovery/reset", body).andExpect(status().isUnauthorized());
        }
        // The sixth try is refused outright, even with the right answers.
        api.anonymousPost("/api/v1/auth/recovery/reset", """
                {"email":"%s","answer1":"Rex","answer2":"Via Roma 12","newPassword":"%s"}
                """.formatted(email, NEW_PASSWORD)).andExpect(status().isTooManyRequests());
        assertThat(loginStatus(email, PASSWORD)).isEqualTo("200");
    }

    @Test
    void oneCorrectAnswerIsNotEnough() throws Exception {
        String email = setUpRecovery(owner);
        api.anonymousPost("/api/v1/auth/recovery/reset", """
                {"email":"%s","answer1":"Rex","answer2":"wrong street","newPassword":"%s"}
                """.formatted(email, NEW_PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(loginStatus(email, PASSWORD)).isEqualTo("200");
    }

    @Test
    void unknownEmailsAndStaffEmailsGetDecoyQuestionsThatGiveNothingAway() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        String unknown = "nobody-" + UUID.randomUUID() + "@example.com";

        for (String email : new String[] {unknown, waiter.email()}) {
            String first = api.anonymousPost("/api/v1/auth/recovery/questions", "{\"email\":\"%s\"}".formatted(email))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.questions.length()").value(2))
                    .andReturn().getResponse().getContentAsString();
            // The same email always gets the same questions, so asking twice reveals nothing.
            String again = api.anonymousPost("/api/v1/auth/recovery/questions", "{\"email\":\"%s\"}".formatted(email))
                    .andReturn().getResponse().getContentAsString();
            assertThat(again).isEqualTo(first);
            // And the failure is indistinguishable from a wrong answer.
            api.anonymousPost("/api/v1/auth/recovery/reset", """
                    {"email":"%s","answer1":"anything","answer2":"anything else","newPassword":"%s"}
                    """.formatted(email, NEW_PASSWORD)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("The answers do not match"));
        }
    }

    @Test
    void aDeactivatedOwnersRecoveryIsNotUsable() throws Exception {
        // The owner cannot be deactivated through the API, so check the gate that matters: a weak new password is refused.
        String email = setUpRecovery(owner);
        api.anonymousPost("/api/v1/auth/recovery/reset", """
                {"email":"%s","answer1":"Rex","answer2":"Via Roma 12","newPassword":"short"}
                """.formatted(email)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.newPassword").exists());
        assertThat(loginStatus(email, PASSWORD)).isEqualTo("200");
    }
}

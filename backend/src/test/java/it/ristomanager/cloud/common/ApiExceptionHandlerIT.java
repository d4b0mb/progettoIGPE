package it.ristomanager.cloud.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
import it.ristomanager.cloud.support.ScriptedLlmConfig;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error handling of the whole API. The catch-all handler must turn unexpected failures into a generic 500 whose
 * body and log lines carry nothing of the exception's message (it can quote a guest's row), while security refusals
 * and the client's own mistakes keep their status. Most requests go through MockMvc; the upload limit is the web
 * server's own, so that one runs on a real server on a random port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ScriptedLlmConfig.class, ApiExceptionHandlerIT.FailureController.class})
class ApiExceptionHandlerIT {

    /** Only exists in this test: endpoints that fail on purpose. Registered through @Import, so no other test sees it. */
    @RestController
    @RequestMapping("/api/v1/test-failures")
    static class FailureController {

        static final String MARKER = "MARKER-" + UUID.randomUUID();

        @GetMapping("/constraint")
        String constraint() {
            throw new DataIntegrityViolationException(
                    "PreparedStatementCallback; ERROR: new row violates check constraint. Failing row contains ("
                            + MARKER + ", +39 333 000 1111, guest@example.com)",
                    new IllegalStateException("cause text " + MARKER));
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("something broke for " + MARKER);
        }

        @GetMapping("/api")
        String api() {
            throw ApiException.conflict("A planned conflict");
        }

        @GetMapping("/wrapped-api")
        String wrappedApi() {
            throw new IllegalStateException("wrapper", ApiException.conflict("A planned conflict"));
        }
    }

    private static final String GENERIC = "The request could not be completed";

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    ApiClient api;
    Actor owner;
    ListAppender<ILoggingEvent> logs;
    ch.qos.logback.classic.Logger root;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc);
        owner = api.signup("E");
        root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
    }

    /** Everything written to the log since the test started: messages and any exception text. */
    private String logged() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            all.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                all.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return all.toString();
    }

    private void assertGeneric500AndNothingLeaked(MvcResult result, String exceptionClass) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat((String) JsonPath.read(body, "$.detail")).isEqualTo(GENERIC);
        assertThat(body).doesNotContain(FailureController.MARKER).doesNotContain("Failing row")
                .doesNotContain("guest@example.com").doesNotContain("+39 333");
        String errorId = JsonPath.read(body, "$.errorId");
        assertThat(errorId).isNotBlank();

        String log = logged();
        // The log does say that something happened: the type and the id, which is how it is found again.
        assertThat(log).contains(exceptionClass).contains(errorId);
        // ...and nothing of what the exception said.
        assertThat(log).doesNotContain(FailureController.MARKER).doesNotContain("Failing row")
                .doesNotContain("guest@example.com").doesNotContain("+39 333");
    }

    @Test
    void anUnexpectedConstraintFailureIsAGeneric500AndItsMessageIsNeverLoggedOrReturned() throws Exception {
        MvcResult result = api.get(owner, "/api/v1/test-failures/constraint").andReturn();
        assertGeneric500AndNothingLeaked(result, "org.springframework.dao.DataIntegrityViolationException");
    }

    @Test
    void anyOtherUnexpectedFailureGetsTheSameTreatment() throws Exception {
        MvcResult result = api.get(owner, "/api/v1/test-failures/unexpected").andReturn();
        assertGeneric500AndNothingLeaked(result, "java.lang.IllegalStateException");
    }

    @Test
    void anApiExceptionKeepsItsOwnStatusAndMessage() throws Exception {
        api.get(owner, "/api/v1/test-failures/api").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A planned conflict"));
        // Also when something wrapped it on the way up.
        api.get(owner, "/api/v1/test-failures/wrapped-api").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A planned conflict"));
        api.post(owner, "/api/v1/reservations", "{\"guestName\":\" \",\"startsAt\":\"2099-01-01T20:00:00Z\",\"partySize\":2}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.guestName").exists());
        assertThat(logged()).doesNotContain("(error id");
    }

    @Test
    void aWaiterCallingAManagerOnlyEndpointIsStill403() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        api.post(waiter, "/api/v1/reservations", """
                {"guestName":"X","startsAt":"2099-01-01T20:00:00Z","partySize":2}
                """).andExpect(status().isForbidden());
        assertThat(logged()).doesNotContain("(error id");
    }

    @Test
    void noTokenIsStill401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/reservations?date=2099-01-01")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedJsonBodyIsStill400() throws Exception {
        api.post(owner, "/api/v1/reservations", "{this is not json").andExpect(status().isBadRequest());
        assertThat(logged()).doesNotContain("(error id");
    }

    @Test
    void aMissingOrBadlyTypedParameterIsStill400() throws Exception {
        api.get(owner, "/api/v1/reservations").andExpect(status().isBadRequest());
        api.get(owner, "/api/v1/reservations?date=not-a-date").andExpect(status().isBadRequest());
        api.get(owner, "/api/v1/reservations/not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownPathIsStill404() throws Exception {
        api.get(owner, "/api/v1/this-does-not-exist").andExpect(status().isNotFound());
    }

    @Test
    void aWrongMethodIsStill405AndAWrongContentTypeStill415() throws Exception {
        api.delete(owner, "/api/v1/reservations").andExpect(status().isMethodNotAllowed());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/reservations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void anUploadOverTheLimitIsStill413() throws Exception {
        String room = ApiClient.read(api.post(owner, "/api/v1/rooms", """
                {"name":"Main","widthMeters":12,"heightMeters":9}
                """).andExpect(status().isCreated()), "$.id");
        String boundary = "----limit" + UUID.randomUUID();
        int bytes = 9 * 1024 * 1024;
        ByteArrayOutputStream body = new ByteArrayOutputStream(bytes + 1024);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[bytes]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/rooms/" + room
                        + "/floor-draft?roomWidthMeters=10"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + owner.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(413);
        assertThat(logged()).doesNotContain("(error id");
    }
}

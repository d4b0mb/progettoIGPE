package it.ristomanager.cloud.floor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import it.ristomanager.cloud.support.PostgresTestConfig;
import it.ristomanager.cloud.support.ScriptedLlm;
import it.ristomanager.cloud.support.ScriptedLlmConfig;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The upload size limit of the floor plan photo (app: 8 MB per file). MockMvc does not run the web server's own
 * multipart limits, so this test starts the real server on a random port and sends a real multipart request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfig.class, ScriptedLlmConfig.class})
class FloorDraftUploadLimitIT {

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;

    @Autowired
    ScriptedLlm llm;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        llm.reset();
    }

    private HttpResponse<String> json(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String roomId, String token, int bytes) throws Exception {
        String boundary = "----limit" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream(bytes + 1024);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[bytes]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/rooms/" + roomId
                        + "/floor-draft?roomWidthMeters=10"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aPhotoOverTheLimitIsRefusedAsTooLargeAndNeverReachesTheModel() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> signup = json("POST", "/api/v1/auth/signup", null, """
                {"restaurantName":"R","ownerName":"Marco","email":"%s","password":"%s"}
                """.formatted(email, PASSWORD));
        assertThat(signup.statusCode()).isEqualTo(201);
        String token = JsonPath.read(signup.body(), "$.accessToken");
        HttpResponse<String> room = json("POST", "/api/v1/rooms", token, """
                {"name":"Main","widthMeters":12,"heightMeters":9}
                """);
        assertThat(room.statusCode()).isEqualTo(201);
        String roomId = JsonPath.read(room.body(), "$.id");

        HttpResponse<String> response = upload(roomId, token, 9 * 1024 * 1024);

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(413);
        assertThat(llm.calls()).isZero();
    }
}

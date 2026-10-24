package it.ristomanager.cloud.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import it.ristomanager.cloud.support.PostgresTestConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The live kitchen link, end to end: a real server, real HTTP connections, and the
 * server-sent event stream a kitchen screen would hold open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfig.class)
class OrderStreamIT {

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    String call(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(method + " " + path + " -> " + res.body()).isBetween(200, 299);
        return res.body();
    }

    String signup(String name) throws Exception {
        String body = call("POST", "/api/v1/auth/signup", null, """
                {"restaurantName":"%s","ownerName":"Owner","email":"%s@example.com","password":"%s"}
                """.formatted(name, UUID.randomUUID(), PASSWORD));
        return JsonPath.read(body, "$.accessToken");
    }

    /** Opens the event stream and collects every line it receives. */
    BlockingQueue<String> openStream(String token) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders/stream"))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").build();
        CompletableFuture<?> future = http.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(res -> res.body().forEach(lines::add));
        future.exceptionally(e -> null);
        return lines;
    }

    static String next(BlockingQueue<String> lines, String startsWith) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith(startsWith)) {
                return line;
            }
        }
        throw new AssertionError("No line starting with '" + startsWith + "' arrived within 10 s");
    }

    @Test
    void anOrderAppearsLiveOnTheKitchenScreenOfThatRestaurantOnly() throws Exception {
        String ownerA = signup("A");
        String ownerB = signup("B");

        BlockingQueue<String> streamA = openStream(ownerA);
        BlockingQueue<String> streamB = openStream(ownerB);
        next(streamA, "event:ready");
        next(streamB, "event:ready");

        String room = JsonPath.read(call("POST", "/api/v1/rooms", ownerA,
                "{\"name\":\"Main\",\"widthMeters\":10,\"heightMeters\":8}"), "$.id");
        String table = JsonPath.read(call("POST", "/api/v1/rooms/" + room + "/tables", ownerA,
                "{\"xMeters\":1,\"yMeters\":1,\"widthMeters\":1.2,\"heightMeters\":0.8,\"shape\":\"RECTANGLE\",\"seats\":4}"), "$.id");
        String dish = JsonPath.read(call("POST", "/api/v1/menu/items", ownerA,
                "{\"name\":\"Margherita\",\"course\":\"SECOND\",\"allergens\":[\"GLUTEN\",\"MILK\"]}"), "$.id");

        String created = call("POST", "/api/v1/orders", ownerA, """
                {"tableId":"%s","priority":"URGENT","items":[{"menuItemId":"%s","quantity":2}]}
                """.formatted(table, dish));
        String ticketId = JsonPath.read(created, "$.id");

        // A's screen gets the new ticket, with the dish's allergens attached.
        next(streamA, "event:ticket-created");
        String data = next(streamA, "data:{");
        assertThat((String) JsonPath.read(data.substring("data:".length()), "$.code")).isEqualTo("C-001");
        assertThat((String) JsonPath.read(data.substring("data:".length()), "$.priority")).isEqualTo("URGENT");

        // A status change is pushed too.
        call("PATCH", "/api/v1/orders/" + ticketId + "/status", ownerA, "{\"status\":\"PREPARING\"}");
        next(streamA, "event:ticket-updated");

        // B's screen heard nothing about A's restaurant.
        Thread.sleep(1000);
        assertThat(streamB).as("lines B received").noneMatch(l -> l.startsWith("event:ticket") || l.contains("C-001"));
    }
}

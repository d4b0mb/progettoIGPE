package it.ristomanager.cloud.voice;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A fake speech engine for unit tests: a real HTTP server on a free loopback port that answers whatever the test set
 * up for a path and remembers every request it got. Unknown paths answer 404, like a server without that endpoint.
 */
final class FakeEngine implements AutoCloseable {

    /** One request the fake received. */
    record Seen(String method, String path, String contentType, byte[] body) {
    }

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    FakeEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    /** Answers every request to {@code path} with the status, optional Content-Type and body, after the delay. */
    FakeEngine answer(String path, int status, String contentType, byte[] body, long delayMillis) {
        server.createContext(path, exchange -> {
            try (exchange) {
                byte[] request = exchange.getRequestBody().readAllBytes();
                seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Content-Type"), request));
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                if (contentType != null) {
                    exchange.getResponseHeaders().add("Content-Type", contentType);
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } catch (IOException e) {
                // The client gave up (timeout test); nothing to answer.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Seen> seen() {
        return seen;
    }

    Seen last() {
        return seen.get(seen.size() - 1);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
        }
    }

    /** A loopback address where nothing listens: a port that was free a moment ago. */
    static String deadUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }
}

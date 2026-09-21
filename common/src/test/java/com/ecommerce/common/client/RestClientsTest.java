package com.ecommerce.common.client;

import com.ecommerce.common.tracing.Correlation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the correlation-id propagation added in Phase 5. Without it every
 * callee mints its own id and a user-quoted correlation id only finds the first
 * hop — a regression that is invisible at runtime and would otherwise only be
 * caught by inspecting a live trace.
 *
 * <p>Uses the JDK's built-in HTTP server rather than WireMock so the shared
 * library keeps no test-scoped server dependency.
 */
class RestClientsTest {

    private HttpServer server;
    private final AtomicReference<String> receivedCorrelationId = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ping", exchange -> {
            receivedCorrelationId.set(exchange.getRequestHeaders().getFirst(Correlation.HEADER));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void forwardsCorrelationIdFromMdc() {
        MDC.put(Correlation.MDC_KEY, "corr-123");

        client().get().uri("/ping").retrieve().toBodilessEntity();

        assertThat(receivedCorrelationId.get()).isEqualTo("corr-123");
    }

    @Test
    void omitsCorrelationHeaderWhenNoRequestIsBeingServed() {
        // e.g. a scheduled job or a startup call: there is no inbound request, so
        // sending a stale or empty header would be worse than sending none.
        client().get().uri("/ping").retrieve().toBodilessEntity();

        assertThat(receivedCorrelationId.get()).isNull();
    }

    @Test
    void forwardsCorrelationIdAlongsideServiceToken() {
        MDC.put(Correlation.MDC_KEY, "corr-456");

        RestClients.createWithServiceToken(
                        "http://localhost:" + server.getAddress().getPort(), () -> "test-token")
                .get().uri("/ping").retrieve().toBodilessEntity();

        assertThat(receivedCorrelationId.get()).isEqualTo("corr-456");
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer test-token");
    }

    private RestClient client() {
        return RestClients.create("http://localhost:" + server.getAddress().getPort());
    }
}

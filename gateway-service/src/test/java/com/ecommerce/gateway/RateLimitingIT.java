package com.ecommerce.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Verifies the edge rate limiter rejects requests past the configured limit
 * with a 429 problem response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ecommerce.gateway.rate-limit.max-requests-per-minute=2")
@ActiveProfiles("test")
class RateLimitingIT {

    static final WireMockServer DOWNSTREAM = new WireMockServer(options().port(18080));

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeAll
    static void startDownstream() {
        DOWNSTREAM.start();
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop();
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(WireMock.get("/api/v1/products/1")
                .willReturn(WireMock.okJson("{\"id\":1}")));
    }

    @Test
    void rejectsRequestsBeyondLimitWith429() {
        client.get().uri("/api/v1/products/1").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/products/1").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/products/1")
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Too Many Requests")
                .jsonPath("$.status").isEqualTo(429);

        // only the allowed requests reached the downstream service
        DOWNSTREAM.verify(2, WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/products/1")));
    }
}

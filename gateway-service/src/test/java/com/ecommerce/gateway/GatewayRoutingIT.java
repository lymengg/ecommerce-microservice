package com.ecommerce.gateway;

import com.ecommerce.gateway.filter.CorrelationIdFilter;
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
 * Verifies gateway routing to the downstream service and correlation id
 * propagation (generation + pass-through).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayRoutingIT {

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
                .willReturn(WireMock.okJson("{\"id\":1,\"sku\":\"SKU-1\",\"status\":\"ACTIVE\"}")));
    }

    @Test
    void routesProductRequestsToCatalog() {
        client.get().uri("/api/v1/products/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sku").isEqualTo("SKU-1");

        DOWNSTREAM.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/products/1")));
    }

    @Test
    void propagatesInboundCorrelationIdDownstream() {
        client.get().uri("/api/v1/products/1")
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "trace-abc-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationIdFilter.CORRELATION_ID_HEADER, "trace-abc-123");

        DOWNSTREAM.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/products/1"))
                .withHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, WireMock.equalTo("trace-abc-123")));
    }

    @Test
    void generatesCorrelationIdWhenAbsent() {
        client.get().uri("/api/v1/products/1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(CorrelationIdFilter.CORRELATION_ID_HEADER);

        DOWNSTREAM.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/products/1"))
                .withHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, WireMock.matching("^[0-9a-f-]{36}$")));
    }
}

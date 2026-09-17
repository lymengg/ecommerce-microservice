package com.ecommerce.gateway;

import com.ecommerce.gateway.security.KeycloakJwtAuthoritiesConverter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Edge security policy with mocked JWTs: public product browsing, role-based
 * route rules, hard denial of internal endpoints, and rejection of SERVICE
 * tokens at the gateway. Real token validation (signatures, JWKS) is covered
 * by {@link GatewayKeycloakIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GatewaySecurityIT {

    static final WireMockServer DOWNSTREAM = new WireMockServer(options().port(18080));

    @Autowired
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
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(WireMock.get("/api/v1/products/1")
                .willReturn(WireMock.okJson("{\"id\":1,\"sku\":\"SKU-1\",\"status\":\"ACTIVE\"}")));
        DOWNSTREAM.stubFor(WireMock.get("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .willReturn(WireMock.okJson("{\"orderId\":\"00000000-0000-0000-0000-000000000001\",\"status\":\"DRAFT\"}")));
        DOWNSTREAM.stubFor(WireMock.post("/api/v1/products")
                .willReturn(WireMock.okJson("{\"id\":2,\"sku\":\"SKU-2\",\"status\":\"DRAFT\"}")));
        DOWNSTREAM.stubFor(WireMock.post("/api/v1/payments/webhooks/mock")
                .willReturn(WireMock.okJson("{\"paymentId\":\"00000000-0000-0000-0000-000000000002\",\"status\":\"SUCCEEDED\"}")));
    }

    @Test
    void productBrowsingIsPublic() {
        client.get().uri("/api/v1/products/1").exchange().expectStatus().isOk();
    }

    @Test
    void protectedRouteRequiresAuthentication() {
        client.get().uri("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customerCanReachCustomerRoutes() {
        client.mutateWith(withRole("CUSTOMER"))
                .get().uri("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void customerCannotWriteProducts() {
        client.mutateWith(withRole("CUSTOMER"))
                .post().uri("/api/v1/products")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanWriteProducts() {
        client.mutateWith(withRole("ADMIN"))
                .post().uri("/api/v1/products")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void internalEndpointsAreDenied() {
        // unauthenticated probes get 401; authenticated callers get 403
        client.get().uri("/internal/api/v1/catalog/products/1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);

        client.mutateWith(withRole("CUSTOMER"))
                .get().uri("/internal/api/v1/catalog/products/1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void serviceTokensCannotPassTheGateway() {
        client.mutateWith(withRole("SERVICE"))
                .get().uri("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void providerWebhooksAreCallableWithoutToken() {
        client.post().uri("/api/v1/payments/webhooks/mock")
                .exchange()
                .expectStatus().isOk();
    }

    private static org.springframework.test.web.reactive.server.WebTestClientConfigurer withRole(String role) {
        return mockJwt()
                .jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of(role))))
                // mockJwt ignores realm_access by default (it uses SCOPE_*);
                // apply the production converter so the role mapping is real
                .authorities(new KeycloakJwtAuthoritiesConverter());
    }
}

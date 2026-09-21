package com.ecommerce.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end edge security against a real Keycloak: issuer metadata
 * resolution, JWKS signature validation, route role rules, token relay, and
 * the realm import (users, roles, service-account role mapping). Requires
 * Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayKeycloakIT {

    private static final String REALM = "ecommerce";
    private static final String TEST_CLIENT_SECRET = "dev-test-client-secret";
    private static final String SERVICE_CLIENT_SECRET = "dev-service-client-secret";

    static final WireMockServer DOWNSTREAM = new WireMockServer(options().port(18080));
    static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.7.4")
            .withRealmImportFile("keycloak/ecommerce-realm.json")
            .withStartupTimeout(Duration.ofMinutes(3));

    @LocalServerPort
    private int port;

    private WebTestClient client;

    static {
        DOWNSTREAM.start();
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> authServerUrl() + "realms/" + REALM);
    }

    @AfterAll
    static void stopContainers() {
        KEYCLOAK.stop();
        DOWNSTREAM.stop();
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
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
    void realmImportCreatesUsersAndRoles() throws Exception {
        String customerToken = token("customer1", "customer1-password");
        assertThat(rolesOf(customerToken)).contains("CUSTOMER");
        assertThat(rolesOf(token("admin1", "admin1-password"))).contains("ADMIN");
        assertThat(rolesOf(serviceToken())).contains("SERVICE");

        // Regression guard. Every service derives the caller's identity from the
        // `sub` claim, and `sub` comes from Keycloak's `basic` client scope.
        // Omitting `basic` from a client's defaultClientScopes (which *overrides*
        // the realm default rather than extending it) yields tokens that validate
        // correctly and carry the right roles but have no subject — so every
        // authenticated business call returns 403. Mocked-JWT tests cannot see
        // this because they construct the Authentication directly, so it has to
        // be asserted here, against a real token from a real realm import.
        String subject = subjectOf(customerToken);
        assertThat(subject).as("access token must carry a sub claim").isNotNull();
        assertThat(UUID.fromString(subject)).as("sub must be the user's UUID").isNotNull();
    }

    private static String subjectOf(String jwt) throws Exception {
        JsonNode subject = decode(jwt).get("sub");
        return subject == null ? null : subject.asText();
    }

    private static List<String> rolesOf(String jwt) throws Exception {
        return new ObjectMapper().convertValue(
                decode(jwt).get("realm_access").get("roles"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                });
    }

    @Test
    void productBrowsingIsPublic() {
        client.get().uri("/api/v1/products/1").exchange().expectStatus().isOk();
    }

    @Test
    void protectedRouteWithoutTokenIsUnauthorized() {
        client.get().uri("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customerTokenIsValidatedAndRelayedDownstream() {
        String customerToken = token("customer1", "customer1-password");

        client.get().uri("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .header("Authorization", "Bearer " + customerToken)
                .exchange()
                .expectStatus().isOk();

        DOWNSTREAM.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/orders/00000000-0000-0000-0000-000000000001"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + customerToken)));
    }

    @Test
    void customerCannotWriteProducts() {
        client.post().uri("/api/v1/products")
                .header("Authorization", "Bearer " + token("customer1", "customer1-password"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanWriteProducts() {
        client.post().uri("/api/v1/products")
                .header("Authorization", "Bearer " + token("admin1", "admin1-password"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void serviceTokensCannotPassTheGateway() {
        client.get().uri("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .header("Authorization", "Bearer " + serviceToken())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void providerWebhooksAreCallableWithoutToken() {
        client.post().uri("/api/v1/payments/webhooks/mock")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void garbageTokensAreRejected() {
        client.get().uri("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .header("Authorization", "Bearer not.a.jwt")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String token(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "test-client");
        form.add("client_secret", TEST_CLIENT_SECRET);
        form.add("username", username);
        form.add("password", password);
        return accessToken(form);
    }

    private String serviceToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "service-client");
        form.add("client_secret", SERVICE_CLIENT_SECRET);
        return accessToken(form);
    }

    private String accessToken(MultiValueMap<String, String> form) {
        Map<?, ?> response = RestClient.create().post()
                .uri(authServerUrl() + "realms/" + REALM + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        return (String) response.get("access_token");
    }

    private static JsonNode decode(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(payload));
    }

    private static String authServerUrl() {
        String url = KEYCLOAK.getAuthServerUrl();
        return url.endsWith("/") ? url : url + "/";
    }
}

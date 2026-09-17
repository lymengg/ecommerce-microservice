package com.ecommerce.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecommerce.payment.PaymentServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared Testcontainers base: one PostgreSQL container is started when the
 * first integration test loads and stays up for the whole JVM, so every IT
 * class shares the same cached Spring context. The payment database is
 * truncated after every test. Cross-service calls to order-service are served
 * by a WireMock server whose port is injected via {@code ecommerce.order.base-url}.
 */
@SpringBootTest(classes = PaymentServiceApplication.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        WIRE_MOCK.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ecommerce.order.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("ecommerce.security.service-client.token-uri",
                () -> "http://localhost:" + WIRE_MOCK.port() + "/token");
    }

    @BeforeEach
    void stubServiceToken() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/token"))
                .willReturn(okJson("""
                        {"access_token":"test-service-token","expires_in":300,"token_type":"Bearer"}
                        """)));
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE outbox_events,
                                 webhook_events,
                                 refunds,
                                 provider_transactions,
                                 payment_attempts,
                                 payments
                RESTART IDENTITY CASCADE
                """);
        WIRE_MOCK.resetAll();
    }
}

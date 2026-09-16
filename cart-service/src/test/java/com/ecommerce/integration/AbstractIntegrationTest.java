package com.ecommerce.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecommerce.cart.CartServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared Testcontainers base: one PostgreSQL container is started when the
 * first integration test loads and stays up for the whole JVM, so every IT
 * class shares the same cached Spring context. The cart database is truncated
 * after every test. Cross-service calls to catalog-service are served by a
 * WireMock server whose port is injected via {@code ecommerce.catalog.base-url}.
 */
@SpringBootTest(classes = CartServiceApplication.class)
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
        registry.add("ecommerce.catalog.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE cart_items,
                                 carts,
                                 outbox_events
                RESTART IDENTITY CASCADE
                """);
        WIRE_MOCK.resetAll();
    }
}

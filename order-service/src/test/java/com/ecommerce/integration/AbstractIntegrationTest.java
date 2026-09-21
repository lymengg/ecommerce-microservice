package com.ecommerce.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecommerce.order.OrderServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared Testcontainers base: one PostgreSQL container and one Kafka broker are
 * started when the first integration test loads and stay up for the whole JVM,
 * so every IT class shares the same cached Spring context. The order database
 * is truncated after every test. Cross-service calls to catalog-service are
 * served by a WireMock server whose port is injected via
 * {@code ecommerce.catalog.base-url}.
 *
 * <p>The broker is a <em>real</em> Kafka (doc 10 §3), pinned to the same image
 * the local stack runs, so the tests exercise the actual producer/consumer
 * behaviour — offsets, rebalancing, the dead-letter topic — rather than a mock.
 */
@SpringBootTest(classes = OrderServiceApplication.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        POSTGRES.start();
        WIRE_MOCK.start();
        KAFKA.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ecommerce.catalog.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("ecommerce.security.service-client.token-uri",
                () -> "http://localhost:" + WIRE_MOCK.port() + "/token");
        registry.add("spring.kafka.bootstrap-servers", AbstractIntegrationTest::bootstrapServers);
        // Keep the scheduled publisher out of the way: tests drive
        // publishPendingEvents() themselves so assertions are deterministic.
        registry.add("ecommerce.outbox.poll-interval-ms", () -> "3600000");
    }

    /** Kafka clients want {@code host:port}; the container reports a scheme prefix. */
    static String bootstrapServers() {
        return KAFKA.getBootstrapServers().replace("PLAINTEXT://", "");
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
                TRUNCATE TABLE processed_events,
                                 outbox_events,
                                 order_idempotency_records,
                                 order_status_history,
                                 order_items,
                                 orders
                RESTART IDENTITY CASCADE
                """);
        WIRE_MOCK.resetAll();
    }
}

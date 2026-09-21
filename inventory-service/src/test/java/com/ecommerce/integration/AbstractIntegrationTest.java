package com.ecommerce.integration;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecommerce.inventory.InventoryServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers base: one PostgreSQL container and one Kafka broker are
 * started when the first integration test loads and stay up for the whole JVM
 * (killed by Ryuk on exit), so every IT class shares the same cached Spring
 * context. The inventory database is truncated after every test.
 *
 * <p>The broker is a <em>real</em> Kafka (doc 10 §3), pinned to the same image
 * the local stack runs.
 */
@SpringBootTest(classes = InventoryServiceApplication.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", AbstractIntegrationTest::bootstrapServers);
        // Keep the scheduled publisher out of the way so tests are deterministic.
        registry.add("ecommerce.outbox.poll-interval-ms", () -> "3600000");
    }

    /** Kafka clients want {@code host:port}; the container reports a scheme prefix. */
    static String bootstrapServers() {
        return KAFKA.getBootstrapServers().replace("PLAINTEXT://", "");
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE processed_events,
                                 outbox_events,
                                 inventory_movements,
                                 inventory_reservations,
                                 inventory_items
                RESTART IDENTITY CASCADE
                """);
    }
}

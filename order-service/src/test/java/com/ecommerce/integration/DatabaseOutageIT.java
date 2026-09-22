package com.ecommerce.integration;

import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static com.ecommerce.integration.TestSecurity.asUser;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7e — database outage (doc 10 §7). The expectation is "the service
 * degrades; no silent data loss": a request that needs the database must fail
 * loudly and in bounded time, and nothing committed may vanish.
 *
 * <p>The outage is real: the PostgreSQL container is paused at the Docker level,
 * so nothing answers on an already-open connection (never a mocked DataSource,
 * doc 10 §3). The IT base gives the JDBC URL a 5 s socket timeout, which is what
 * turns "the database is gone" into a bounded failure instead of a hang — and
 * is itself a Phase 7 lesson: an unbounded wait on a dependency is the failure
 * mode, not the mitigation.
 *
 * <p>The container is unpaused in a {@code finally} and again in
 * {@code @AfterEach}, because the rest of this module's ITs share it.
 */
class DatabaseOutageIT extends AbstractIntegrationTest {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void stubCatalog() {
        asUser(UUID.randomUUID(), "CUSTOMER");
        WIRE_MOCK.stubFor(get(urlEqualTo("/internal/api/v1/catalog/products/" + PRODUCT_ID))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-DB-1","name":"Monitor","description":"27 inch",
                         "price":250.00,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    /** Never leave the shared container paused for the next test class. */
    @AfterEach
    void ensurePostgresRunning() {
        unpausePostgresIfPaused();
    }

    @Test
    void aDatabaseOutageFailsLoudlyAndWithinABoundedTimeAndLosesNoData() {
        OrderResponse created = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(PRODUCT_ID, 2))), null);
        UUID orderId = created.orderId();
        long ordersBefore = orderRepository.count();

        pausePostgres();
        long elapsedMs;
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> orderService.get(orderId))
                    .as("an unavailable database must not silently return stale or empty data")
                    .isInstanceOf(RuntimeException.class);
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
        } finally {
            unpausePostgresIfPaused();
        }

        assertThat(elapsedMs)
                .as("bounded by the socket timeout, not an unbounded wait for the database to return")
                .isLessThan(20_000);

        // No silent data loss: everything committed before the outage is intact.
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(orderService.get(orderId).orderId()).isEqualTo(orderId);
    }

    private void pausePostgres() {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    private void unpausePostgresIfPaused() {
        if (Boolean.TRUE.equals(POSTGRES.getDockerClient().inspectContainerCmd(POSTGRES.getContainerId())
                .exec().getState().getPaused())) {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
    }
}

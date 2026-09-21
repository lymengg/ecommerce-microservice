package com.ecommerce.integration;

import com.ecommerce.common.messaging.EventEnvelope;
import com.ecommerce.common.messaging.Topics;
import com.ecommerce.inventory.dto.ReservationRequest;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.model.ReservationStatus;
import com.ecommerce.inventory.repository.InventoryReservationRepository;
import com.ecommerce.inventory.service.InventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6b/6c — inventory consumes {@code order.events} and commits the order's
 * reservations on {@code OrderConfirmed}. Covers the happy path, duplicate
 * delivery (a no-op), and that unrelated events on the same topic are ignored.
 */
class OrderEventsConsumerIT extends AbstractIntegrationTest {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void orderConfirmedCommitsTheOrdersReservations() throws Exception {
        UUID orderId = reserveStock(4);

        sendOrderConfirmed(orderId, UUID.randomUUID());

        await("reservation is COMMITTED", () ->
                inventoryService.getStock(PRODUCT_ID).committedQuantity() == 4);
        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    void duplicateOrderConfirmedIsIdempotent() throws Exception {
        UUID orderId = reserveStock(4);
        UUID eventId = UUID.randomUUID();

        sendOrderConfirmed(orderId, eventId);
        sendOrderConfirmed(orderId, eventId);

        await("reservation is COMMITTED", () ->
                inventoryService.getStock(PRODUCT_ID).committedQuantity() == 4);
        // Committed once, not twice, and only one dedup row.
        assertThat(inventoryService.getStock(PRODUCT_ID).committedQuantity()).isEqualTo(4);
        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    void unrelatedOrderEventsAreIgnored() throws Exception {
        UUID orderId = reserveStock(4);

        sendOrderEvent("OrderCreated", orderId, UUID.randomUUID());

        // Give the consumer a moment; the reservation must still be RESERVED.
        Thread.sleep(1500);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.COMMITTED)).isEmpty();
        assertThat(processedEventCount()).isZero();
    }

    private UUID reserveStock(int quantity) {
        inventoryService.initializeStock(new StockRequest(PRODUCT_ID, "SKU-INV-1", 10));
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve(new ReservationRequest(PRODUCT_ID, quantity, orderId));
        return orderId;
    }

    private void sendOrderConfirmed(UUID orderId, UUID eventId) throws Exception {
        sendOrderEvent("OrderConfirmed", orderId, eventId);
    }

    private void sendOrderEvent(String eventType, UUID orderId, UUID eventId) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", orderId.toString());
        payload.put("status", "PAID");
        EventEnvelope envelope = new EventEnvelope(
                eventId.toString(), eventType, 1, orderId.toString(),
                Instant.now(), "order-service", "corr-inventory-it", null, payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(
                Topics.ORDER, orderId.toString(), objectMapper.writeValueAsString(envelope));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private long processedEventCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Long.class);
        return count == null ? 0 : count;
    }

    private static void await(String description, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }
}

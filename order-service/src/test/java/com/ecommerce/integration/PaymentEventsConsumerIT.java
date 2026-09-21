package com.ecommerce.integration;

import com.ecommerce.common.messaging.EventEnvelope;
import com.ecommerce.common.messaging.Topics;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static com.ecommerce.integration.TestSecurity.asUser;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6b/6c — the consumer contract against a real broker: a
 * {@code PaymentSucceeded} event confirms the order, a duplicate is a no-op,
 * an out-of-order event and a poison message are bounded-retried into the
 * dead-letter topic without wedging the consumer.
 */
class PaymentEventsConsumerIT extends AbstractIntegrationTest {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        asUser(UUID.randomUUID(), "CUSTOMER");
        WIRE_MOCK.stubFor(get(urlMatching("/internal/api/v1/catalog/products/" + PRODUCT_ID))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-ORD-1","name":"Monitor","description":"27 inch",
                         "price":250.00,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    @Test
    void paymentSucceededConfirmsTheOrderAndEmitsOrderConfirmed() throws Exception {
        UUID orderId = orderAwaitingPayment();

        sendPaymentEvent("PaymentSucceeded", orderId, UUID.randomUUID());

        await("order becomes PAID", () -> statusOf(orderId) == OrderStatus.PAID);
        assertThat(countOutbox("OrderConfirmed", orderId)).isEqualTo(1);
        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    void duplicatePaymentSucceededIsProcessedOnlyOnce() throws Exception {
        UUID orderId = orderAwaitingPayment();
        UUID eventId = UUID.randomUUID();

        sendPaymentEvent("PaymentSucceeded", orderId, eventId);
        sendPaymentEvent("PaymentSucceeded", orderId, eventId);

        await("order becomes PAID", () -> statusOf(orderId) == OrderStatus.PAID);
        // The redelivery was a no-op: one confirmation, one dedup row.
        assertThat(countOutbox("OrderConfirmed", orderId)).isEqualTo(1);
        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    void paymentSucceededForAnOrderNotAwaitingPaymentIsDeadLettered() throws Exception {
        // A real state-machine violation: the event arrives for an order that
        // never entered PAYMENT_PENDING (out-of-order delivery).
        UUID orderId = createOrder();

        sendPaymentEvent("PaymentSucceeded", orderId, UUID.randomUUID());

        ConsumerRecord<String, String> dead = consumeOne(
                Topics.deadLetter(Topics.PAYMENT), r -> orderId.toString().equals(r.key()));
        assertThat(dead.value()).contains(orderId.toString());
        assertThat(statusOf(orderId)).as("state machine not corrupted").isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void poisonMessageIsDeadLetteredAndTheConsumerKeepsProcessing() throws Exception {
        UUID unknownOrderId = UUID.randomUUID();
        sendPaymentEvent("PaymentSucceeded", unknownOrderId, UUID.randomUUID());

        consumeOne(Topics.deadLetter(Topics.PAYMENT), r -> unknownOrderId.toString().equals(r.key()));

        // The partition is not wedged: a valid event right after still works.
        UUID orderId = orderAwaitingPayment();
        sendPaymentEvent("PaymentSucceeded", orderId, UUID.randomUUID());
        await("order becomes PAID after the poison message", () -> statusOf(orderId) == OrderStatus.PAID);
    }

    private UUID orderAwaitingPayment() {
        UUID orderId = createOrder();
        orderService.markPending(orderId);
        orderService.markPaymentPending(orderId);
        return orderId;
    }

    private UUID createOrder() {
        OrderResponse order = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(PRODUCT_ID, 2))), null);
        return order.orderId();
    }

    private void sendPaymentEvent(String eventType, UUID orderId, UUID eventId) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("orderId", orderId.toString());
        payload.put("amount", "550.00");
        EventEnvelope envelope = new EventEnvelope(
                eventId.toString(), eventType, 1, UUID.randomUUID().toString(),
                Instant.now(), "payment-service", "corr-consumer-it", null, payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(
                Topics.PAYMENT, orderId.toString(), objectMapper.writeValueAsString(envelope));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private OrderStatus statusOf(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private long countOutbox(String eventType, UUID aggregateId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Long.class, eventType, aggregateId.toString());
        return count == null ? 0 : count;
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

    private ConsumerRecord<String, String> consumeOne(String topic, Predicate<ConsumerRecord<String, String>> match) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching record on topic " + topic + " within 25s");
    }
}

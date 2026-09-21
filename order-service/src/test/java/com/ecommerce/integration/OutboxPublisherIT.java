package com.ecommerce.integration;

import com.ecommerce.common.messaging.EventEnvelope;
import com.ecommerce.common.messaging.Topics;
import com.ecommerce.common.outbox.OutboxPublisher;
import com.ecommerce.common.outbox.OutboxRepository;
import com.ecommerce.common.tracing.Correlation;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import static com.ecommerce.integration.TestSecurity.asUser;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6a — the outbox publisher against a real broker: business rows are
 * drained to the domain topic with the documented envelope, the partition key
 * is the aggregate id, and the correlation id rides along.
 */
class OutboxPublisherIT extends AbstractIntegrationTest {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private ObjectMapper objectMapper;

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
    void publishesOutboxRowsToTheDomainTopicAndMarksThemPublished() throws Exception {
        OrderResponse order = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(PRODUCT_ID, 2))), null);

        assertThat(outboxRepository.countByPublishedAtIsNull()).isEqualTo(1);

        outboxPublisher.publishPendingEvents();

        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();

        String orderId = order.orderId().toString();
        ConsumerRecord<String, String> record = consumeOne(Topics.ORDER, r -> orderId.equals(r.key()));
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        assertThat(record.key()).isEqualTo(orderId);
        assertThat(envelope.eventType()).isEqualTo("OrderCreated");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.aggregateId()).isEqualTo(orderId);
        assertThat(envelope.producer()).isEqualTo("order-service");
        assertThat(envelope.eventId()).isNotBlank();
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.payload().path("orderId").asText()).isEqualTo(orderId);
    }

    @Test
    void carriesTheRecordingRequestsCorrelationIdAcrossKafka() throws Exception {
        MDC.put(Correlation.MDC_KEY, "corr-it-777");
        OrderResponse order;
        try {
            order = orderService.create(
                    new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(PRODUCT_ID, 1))), null);
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }

        outboxPublisher.publishPendingEvents();

        String orderId = order.orderId().toString();
        ConsumerRecord<String, String> record = consumeOne(Topics.ORDER, r -> orderId.equals(r.key()));
        assertThat(new String(record.headers().lastHeader(Correlation.HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("corr-it-777");
        assertThat(objectMapper.readValue(record.value(), EventEnvelope.class).correlationId())
                .isEqualTo("corr-it-777");
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
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching record on topic " + topic + " within 20s");
    }
}

package com.ecommerce.common.outbox;

import com.ecommerce.common.messaging.Topics;
import com.ecommerce.common.tracing.Correlation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The publisher's failure behaviour (doc 10 §7: "Kafka outage").
 *
 * <p>A real broker is used by the integration tests; this test isolates the one
 * thing they cannot easily provoke — a broker that is <em>down</em> — by making
 * the send fail. It pins the two guarantees that matter: a failed publish must
 * not mark the row published (no lost event), and once the broker recovers the
 * same row is drained (no stuck event).
 */
class OutboxPublisherTest {

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final OutboxPublisher publisher =
            new OutboxPublisher(outboxRepository, kafkaTemplate, objectMapper, "order-service", 100);

    private static OutboxEvent event() {
        return new OutboxEvent("order", UUID.randomUUID().toString(), "OrderCreated", 1,
                "{\"orderId\":\"abc\"}", "corr-1", null);
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker down"));
        return future;
    }

    @Test
    void publishesWithTheAggregateKeyAndCorrelationHeaderThenMarksTheRowPublished() {
        OutboxEvent event = event();
        when(outboxRepository.lockUnpublishedBatch(100)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ok());

        publisher.publishPendingEvents();

        assertThat(event.getPublishedAt()).isNotNull();
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo(Topics.ORDER);
        // partition key = aggregate id, so per-aggregate ordering is preserved
        assertThat(sent.getValue().key()).isEqualTo(event.getAggregateId());
        assertThat(new String(sent.getValue().headers().lastHeader(Correlation.HEADER).value(),
                StandardCharsets.UTF_8)).isEqualTo("corr-1");
    }

    @Test
    void retainsTheRowWhenKafkaIsDownAndDrainsItOnRecovery() {
        OutboxEvent event = event();
        when(outboxRepository.lockUnpublishedBatch(100)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed());

        publisher.publishPendingEvents();

        assertThat(event.getPublishedAt()).as("not published").isNull();
        assertThat(event.getAttemptCount()).as("failed attempt counted").isEqualTo(1);

        // Broker comes back: the same unpublished row is picked up again.
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ok());
        publisher.publishPendingEvents();

        assertThat(event.getPublishedAt()).as("drained after recovery").isNotNull();
    }
}

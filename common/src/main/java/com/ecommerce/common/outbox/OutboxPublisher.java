package com.ecommerce.common.outbox;

import com.ecommerce.common.messaging.EventEnvelope;
import com.ecommerce.common.messaging.Topics;
import com.ecommerce.common.messaging.TraceContext;
import com.ecommerce.common.tracing.Correlation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains the transactional outbox to Kafka (doc 06 §8, ADR-009).
 *
 * <p>The business transaction only writes the outbox row. This publisher is the
 * second half: it forwards unpublished rows to the broker and marks them
 * published. Decoupling the two is what removes the dual-write — the request
 * path never talks to Kafka, so a broker outage can never fail or slow a
 * checkout.
 *
 * <h2>Decisions</h2>
 * <ul>
 *   <li><b>Polling interval</b> ({@code ecommerce.outbox.poll-interval-ms},
 *       default 1000 ms). A trade-off: shorter means lower latency and more
 *       queries; longer means a quieter database and laggier events. One second
 *       is a deliberate middle ground for a system whose events drive
 *       eventually-consistent side effects, not user-facing reads.</li>
 *   <li><b>Batch size</b> ({@code ecommerce.outbox.batch-size}, default 100).
 *       Bounds the lock held per cycle.</li>
 *   <li><b>Multi-instance safety</b>: {@code FOR UPDATE SKIP LOCKED} (see
 *       {@link OutboxRepository#lockUnpublishedBatch}) — two publishers never
 *       take the same row, so there are no duplicates from scaling out.</li>
 *   <li><b>Kafka down</b>: the send throws, the transaction rolls back, the
 *       rows stay {@code published_at IS NULL} and are retried next cycle.
 *       Nothing is lost and the request path is untouched.</li>
 *   <li><b>Ordering</b>: rows are processed oldest-first and the key is the
 *       aggregate id, so events for one aggregate land on one partition in
 *       order. A failed send stops the cycle rather than skipping ahead, which
 *       would publish a later event before an earlier one.</li>
 * </ul>
 *
 * <p>Delivery is at-least-once: if the process dies after the send but before
 * the commit, the row is redelivered. That is exactly why consumers are
 * idempotent ({@link com.ecommerce.common.messaging.ConsumerIdempotency}).
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String producerName;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper,
                           String producerName,
                           int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.producerName = producerName;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ecommerce.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> batch = outboxRepository.lockUnpublishedBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
                event.markPublished();
                published++;
            } catch (RuntimeException ex) {
                event.recordFailedAttempt();
                log.warn("Outbox publish failed for event {} ({}); leaving it unpublished for retry",
                        event.getId(), event.getEventType(), ex);
                break;
            }
        }
        if (published > 0) {
            log.debug("Published {} outbox event(s) to Kafka", published);
        }
    }

    private void send(OutboxEvent event) {
        String topic = Topics.forAggregateType(event.getAggregateType());
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, null, event.getAggregateId(), toEnvelopeJson(event));
        if (event.getCorrelationId() != null) {
            record.headers().add(Correlation.HEADER, event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        // Restore the recording request's trace so the consumer's span joins the
        // same trace instead of starting a new one.
        TraceContext.withTraceParent(event.getTraceParent(), () -> {
            try {
                kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted publishing outbox event " + event.getId(), ex);
            } catch (ExecutionException | TimeoutException ex) {
                throw new IllegalStateException("Failed to publish outbox event " + event.getId(), ex);
            }
        });
    }

    private String toEnvelopeJson(OutboxEvent event) {
        try {
            EventEnvelope envelope = new EventEnvelope(
                    event.getId().toString(),
                    event.getEventType(),
                    event.getEventVersion(),
                    event.getAggregateId(),
                    event.getOccurredAt(),
                    producerName,
                    event.getCorrelationId(),
                    TraceContext.traceIdOf(event.getTraceParent()),
                    objectMapper.readTree(event.getPayload()));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize outbox event " + event.getId(), ex);
        }
    }
}

package com.ecommerce.common.outbox;

import com.ecommerce.common.messaging.TraceContext;
import com.ecommerce.common.tracing.Correlation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Writes transactional outbox records within the caller's local transaction
 * (ADR-009). Because this runs inside the business transaction, it is also the
 * right place to snapshot the request's correlation id and trace context: the
 * publisher that forwards the row to Kafka runs later, on a scheduler thread,
 * and would otherwise have no idea which request produced it.
 */
@Service
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void record(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        outboxRepository.save(new OutboxEvent(
                aggregateType, aggregateId, eventType, 1, toJson(payload),
                MDC.get(Correlation.MDC_KEY), TraceContext.capture()
        ));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}

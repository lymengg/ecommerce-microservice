package com.ecommerce.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Writes transactional outbox records within the caller's local transaction
 * (ADR-009). The polling publisher that forwards these to Kafka is added in
 * Phase 5.
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
                aggregateType, aggregateId, eventType, 1, toJson(payload)
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

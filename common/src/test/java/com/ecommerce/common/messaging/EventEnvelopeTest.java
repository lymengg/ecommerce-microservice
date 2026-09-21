package com.ecommerce.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6d — schema evolution. The envelope is a <em>tolerant reader</em>
 * (doc 06 §9): producers may add fields over time without breaking consumers
 * compiled against an older shape. Breaking changes are signalled by bumping
 * {@code eventVersion} instead.
 */
class EventEnvelopeTest {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void deserializesTheDocumentedEnvelope() throws Exception {
        String json = """
                {
                  "eventId": "3f1b2c3d-0000-0000-0000-000000000001",
                  "eventType": "PaymentSucceeded",
                  "eventVersion": 1,
                  "aggregateId": "9c8b7a65-0000-0000-0000-000000000002",
                  "occurredAt": "2026-09-21T10:15:30Z",
                  "producer": "payment-service",
                  "correlationId": "corr-123",
                  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                  "payload": { "orderId": "9c8b7a65-0000-0000-0000-000000000003", "amount": "22.00" }
                }
                """;

        EventEnvelope envelope = mapper.readValue(json, EventEnvelope.class);

        assertThat(envelope.eventType()).isEqualTo("PaymentSucceeded");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.producer()).isEqualTo("payment-service");
        assertThat(envelope.payload().path("amount").asText()).isEqualTo("22.00");
    }

    @Test
    void ignoresUnknownFieldsSoProducersCanAddFieldsAdditively() throws Exception {
        // A v2 producer adds "newField" and "currency"; a v1 consumer must not
        // fail — that is what makes additive change safe without redeploying
        // every consumer first.
        String json = """
                {
                  "eventId": "3f1b2c3d-0000-0000-0000-000000000001",
                  "eventType": "PaymentSucceeded",
                  "eventVersion": 2,
                  "aggregateId": "9c8b7a65-0000-0000-0000-000000000002",
                  "occurredAt": "2026-09-21T10:15:30Z",
                  "producer": "payment-service",
                  "newEnvelopeField": "ignored",
                  "payload": { "orderId": "9c8b7a65-0000-0000-0000-000000000003", "newField": "ignored" }
                }
                """;

        EventEnvelope envelope = mapper.readValue(json, EventEnvelope.class);

        assertThat(envelope.eventVersion()).isEqualTo(2);
        assertThat(envelope.eventType()).isEqualTo("PaymentSucceeded");
        assertThat(envelope.correlationId()).isNull();
    }

    @Test
    void toleratesMissingOptionalFields() throws Exception {
        // correlationId/traceId are absent when the producing request was not
        // traced; consumers must not depend on them.
        String json = """
                {
                  "eventId": "3f1b2c3d-0000-0000-0000-000000000001",
                  "eventType": "InventoryReserved",
                  "eventVersion": 1,
                  "aggregateId": "res-1",
                  "occurredAt": "2026-09-21T10:15:30Z",
                  "producer": "inventory-service",
                  "payload": {}
                }
                """;

        EventEnvelope envelope = mapper.readValue(json, EventEnvelope.class);

        assertThat(envelope.correlationId()).isNull();
        assertThat(envelope.traceId()).isNull();
        assertThat(envelope.payload()).isNotNull();
    }
}

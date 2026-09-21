package com.ecommerce.common.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * The wire format of every domain event (doc 06 §1): a small, stable envelope
 * around a domain-specific payload.
 *
 * <p>The envelope is deliberately generic. Consumers key off
 * {@code eventType}/{@code eventVersion} and read only the payload fields they
 * need, which is what lets producers and consumers evolve independently.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is the <em>tolerant
 * reader</em> (doc 06 §9): a consumer compiled against v1 keeps working when a
 * producer adds a field, because unknown fields are ignored rather than
 * rejected. Schema changes must therefore be additive; removing or renaming a
 * field is a breaking change and needs a new {@code eventVersion}.
 *
 * <p>{@code eventVersion} is part of the contract from day one. It is cheaper
 * to carry a version field you rarely use than to add one after consumers
 * exist in the wild.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        String aggregateId,
        Instant occurredAt,
        String producer,
        String correlationId,
        String traceId,
        JsonNode payload
) {
}

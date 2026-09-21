package com.ecommerce.inventory.messaging;

import com.ecommerce.common.messaging.ConsumerIdempotency;
import com.ecommerce.common.messaging.EventEnvelope;
import com.ecommerce.common.messaging.Topics;
import com.ecommerce.inventory.service.InventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes {@code order.events} and commits the order's reservations when the
 * order is confirmed (Phase 6c — the {@code order -> inventory} leg, converted
 * from a synchronous REST call).
 *
 * <p>{@link InventoryService#commitByOrder} is naturally idempotent (it only
 * touches reservations still in RESERVED), but the durable
 * {@code processed_events} check is still applied: it is the uniform contract
 * every consumer follows, and it makes the "already handled" case explicit
 * rather than an accident of the domain code.
 */
@Component
public class OrderEventsConsumer {

    /** One consumer group per capability (doc 06 §10). */
    static final String GROUP_ID = "inventory-service";

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private final ObjectMapper objectMapper;
    private final ConsumerIdempotency idempotency;
    private final InventoryService inventoryService;

    public OrderEventsConsumer(ObjectMapper objectMapper,
                               ConsumerIdempotency idempotency,
                               InventoryService inventoryService) {
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = Topics.ORDER, groupId = GROUP_ID)
    @Transactional(rollbackFor = Exception.class)
    public void onOrderEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        EventEnvelope event = objectMapper.readValue(record.value(), EventEnvelope.class);
        if (!"OrderConfirmed".equals(event.eventType())) {
            return;
        }
        UUID eventId = UUID.fromString(event.eventId());
        if (idempotency.alreadyProcessed(eventId)) {
            log.debug("Skipping already-processed order event {}", eventId);
            return;
        }
        UUID orderId = UUID.fromString(event.payload().path("orderId").asText());
        inventoryService.commitByOrder(orderId);
        idempotency.markProcessed(eventId, GROUP_ID);
        log.info("Inventory committed for confirmed order {}", orderId);
    }
}

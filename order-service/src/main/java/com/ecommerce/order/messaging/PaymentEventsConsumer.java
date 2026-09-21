package com.ecommerce.order.messaging;

import com.ecommerce.common.messaging.ConsumerIdempotency;
import com.ecommerce.common.messaging.EventEnvelope;
import com.ecommerce.common.messaging.Topics;
import com.ecommerce.order.service.OrderService;
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
 * Consumes {@code payment.events} and confirms the order when the payment
 * succeeds (Phase 6c — the {@code PaymentSucceeded -> order} leg, converted
 * from a synchronous REST call the orchestrator used to make).
 *
 * <p>Three things every consumer in this codebase must do:
 * <ol>
 *   <li><b>Filter by event type</b> — a domain topic carries several event
 *       types; this listener only acts on {@code PaymentSucceeded}.</li>
 *   <li><b>Dedup</b> — check the durable {@code processed_events} table before
 *       acting, because delivery is at-least-once.</li>
 *   <li><b>Be atomic</b> — the business change and the dedup insert share one
 *       transaction, so a redelivery after a crash is a no-op.</li>
 * </ol>
 */
@Component
public class PaymentEventsConsumer {

    /** One consumer group per capability (doc 06 §10). */
    static final String GROUP_ID = "order-service";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsConsumer.class);

    private final ObjectMapper objectMapper;
    private final ConsumerIdempotency idempotency;
    private final OrderService orderService;

    public PaymentEventsConsumer(ObjectMapper objectMapper,
                                 ConsumerIdempotency idempotency,
                                 OrderService orderService) {
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.orderService = orderService;
    }

    @KafkaListener(topics = Topics.PAYMENT, groupId = GROUP_ID)
    @Transactional(rollbackFor = Exception.class)
    public void onPaymentEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        EventEnvelope event = objectMapper.readValue(record.value(), EventEnvelope.class);
        if (!"PaymentSucceeded".equals(event.eventType())) {
            return;
        }
        UUID eventId = UUID.fromString(event.eventId());
        if (idempotency.alreadyProcessed(eventId)) {
            log.debug("Skipping already-processed payment event {}", eventId);
            return;
        }
        UUID orderId = UUID.fromString(event.payload().path("orderId").asText());
        orderService.confirmPayment(orderId);
        idempotency.markProcessed(eventId, GROUP_ID);
        log.info("Order {} confirmed from PaymentSucceeded event {}", orderId, eventId);
    }
}

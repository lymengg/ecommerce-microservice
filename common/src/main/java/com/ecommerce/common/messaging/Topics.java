package com.ecommerce.common.messaging;

/**
 * Topic names for the event backbone (doc 06 §2).
 *
 * <p>Topics are <em>domain-oriented</em> — one per bounded context, not one per
 * table or event type. A consumer subscribes to the stream it cares about
 * ({@code order.events}) and filters by {@code eventType}; the producer never
 * has to know who is listening. This is what keeps the two sides decoupled:
 * adding a new subscriber does not change the producer.
 *
 * <p>Every topic has a dead-letter twin ({@code <topic>.DLT}). A message that
 * cannot be processed after bounded retries is moved there instead of blocking
 * the partition (doc 06 §6-7).
 */
public final class Topics {

    public static final String ORDER = "order.events";
    public static final String PAYMENT = "payment.events";
    public static final String INVENTORY = "inventory.events";
    public static final String CATALOG = "catalog.events";
    public static final String CART = "cart.events";

    /** Suffix appended to a topic to build its dead-letter topic. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    /**
     * Maps an outbox {@code aggregate_type} to its domain topic. A missing
     * mapping is a programming error, so it fails loudly rather than silently
     * publishing to a default topic.
     */
    public static String forAggregateType(String aggregateType) {
        return switch (aggregateType) {
            case "order" -> ORDER;
            case "payment" -> PAYMENT;
            case "inventory" -> INVENTORY;
            case "catalog" -> CATALOG;
            case "cart" -> CART;
            default -> throw new IllegalArgumentException("No topic mapped for aggregate type: " + aggregateType);
        };
    }

    public static String deadLetter(String topic) {
        return topic + DLT_SUFFIX;
    }
}

-- Phase 6: carry the recording request's correlation id and W3C trace context
-- on the outbox row. The publisher runs on a scheduler thread, after the
-- request that recorded the event has finished, so it cannot read them from the
-- request — they have to travel with the row (doc 08 §2, ADR-014).
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(64);
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(128);

-- Durable consumer idempotency (doc 06 §5): the event id of every event this
-- service has already handled. Kafka is at-least-once, so a redelivery is
-- expected; this table makes reprocessing a no-op. The insert shares the
-- consumer's transaction with the business change, so at-least-once delivery
-- produces exactly-once effects.
CREATE TABLE processed_events (
    event_id       UUID PRIMARY KEY,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

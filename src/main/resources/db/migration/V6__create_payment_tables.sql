CREATE TABLE payments (
    id               UUID PRIMARY KEY,
    order_id         UUID NOT NULL UNIQUE,
    status           VARCHAR(25) NOT NULL DEFAULT 'PENDING',
    amount           NUMERIC(12, 2) NOT NULL,
    currency         VARCHAR(3) NOT NULL,
    idempotency_key  VARCHAR(128) UNIQUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version          BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_status CHECK (status IN (
        'PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
        'REFUND_PENDING', 'REFUNDED', 'PARTIALLY_REFUNDED', 'REFUND_FAILED'
    ));

CREATE TABLE payment_attempts (
    id              UUID PRIMARY KEY,
    payment_id      UUID NOT NULL REFERENCES payments (id),
    provider        VARCHAR(50) NOT NULL,
    attempt_number  INTEGER NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

ALTER TABLE payment_attempts
    ADD CONSTRAINT chk_payment_attempts_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'));

CREATE INDEX idx_payment_attempts_payment ON payment_attempts (payment_id);

CREATE TABLE provider_transactions (
    id                     UUID PRIMARY KEY,
    provider               VARCHAR(50) NOT NULL,
    provider_transaction_id VARCHAR(128) NOT NULL UNIQUE,
    payment_id             UUID NOT NULL REFERENCES payments (id),
    amount                 NUMERIC(12, 2) NOT NULL,
    currency               VARCHAR(3) NOT NULL,
    status                 VARCHAR(20) NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_provider_transactions_payment ON provider_transactions (payment_id);

CREATE TABLE refunds (
    id         UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments (id),
    amount     NUMERIC(12, 2) NOT NULL,
    currency   VARCHAR(3) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason     VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version    BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'));

CREATE INDEX idx_refunds_payment ON refunds (payment_id);

CREATE TABLE webhook_events (
    id                UUID PRIMARY KEY,
    provider          VARCHAR(50) NOT NULL,
    provider_event_id VARCHAR(128) NOT NULL,
    type              VARCHAR(50) NOT NULL,
    payload           JSONB,
    processed         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uk_webhook_events_provider_event UNIQUE (provider, provider_event_id)
);

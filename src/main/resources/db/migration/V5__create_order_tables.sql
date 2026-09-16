CREATE TABLE orders (
    id            UUID PRIMARY KEY,
    customer_id   UUID,
    status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    currency      VARCHAR(3) NOT NULL,
    subtotal      NUMERIC(12, 2) NOT NULL,
    discount      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax           NUMERIC(12, 2) NOT NULL DEFAULT 0,
    shipping_cost NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total         NUMERIC(12, 2) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status CHECK (status IN (
        'DRAFT', 'PENDING', 'PAYMENT_PENDING', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED'
    ));

CREATE INDEX idx_orders_customer ON orders (customer_id, created_at);

CREATE TABLE order_items (
    id         UUID PRIMARY KEY,
    order_id   UUID NOT NULL REFERENCES orders (id),
    product_id BIGINT NOT NULL,
    sku        VARCHAR(64) NOT NULL,
    name       VARCHAR(200) NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL,
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    discount   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax        NUMERIC(12, 2) NOT NULL DEFAULT 0,
    currency   VARCHAR(3) NOT NULL
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE order_status_history (
    id          UUID PRIMARY KEY,
    order_id    UUID NOT NULL REFERENCES orders (id),
    from_status VARCHAR(20) NOT NULL,
    to_status   VARCHAR(20) NOT NULL,
    reason      VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, created_at);

CREATE TABLE order_idempotency_records (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
    order_id         UUID NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

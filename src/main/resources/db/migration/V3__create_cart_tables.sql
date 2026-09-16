CREATE TABLE carts (
    id          UUID PRIMARY KEY,
    customer_id UUID,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version     BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE carts
    ADD CONSTRAINT chk_carts_status CHECK (status IN ('ACTIVE', 'CHECKED_OUT'));

CREATE INDEX idx_carts_customer ON carts (customer_id);

CREATE TABLE cart_items (
    id         UUID PRIMARY KEY,
    cart_id    UUID NOT NULL REFERENCES carts (id),
    product_id BIGINT NOT NULL,
    sku        VARCHAR(64) NOT NULL,
    quantity   INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    added_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uk_cart_items_cart_product UNIQUE (cart_id, product_id)
);

CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);

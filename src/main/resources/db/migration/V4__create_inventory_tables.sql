CREATE TABLE inventory_items (
    id                 UUID PRIMARY KEY,
    product_id         BIGINT NOT NULL UNIQUE,
    sku                VARCHAR(64) NOT NULL,
    total_quantity     INTEGER NOT NULL CHECK (total_quantity >= 0),
    reserved_quantity  INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    committed_quantity INTEGER NOT NULL DEFAULT 0 CHECK (committed_quantity >= 0),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version            BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_inventory_items_sku ON inventory_items (sku);

CREATE TABLE inventory_reservations (
    id         UUID PRIMARY KEY,
    product_id BIGINT NOT NULL,
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    order_id   UUID NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at TIMESTAMP WITH TIME ZONE,
    version    BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE inventory_reservations
    ADD CONSTRAINT chk_reservations_status CHECK (status IN ('RESERVED', 'RELEASED', 'COMMITTED', 'EXPIRED'));

CREATE INDEX idx_reservations_order ON inventory_reservations (order_id, status);
CREATE INDEX idx_reservations_expiry ON inventory_reservations (status, expires_at);

CREATE TABLE inventory_movements (
    id         UUID PRIMARY KEY,
    product_id BIGINT NOT NULL,
    type       VARCHAR(20) NOT NULL,
    quantity   INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

ALTER TABLE inventory_movements
    ADD CONSTRAINT chk_movements_type CHECK (type IN ('STOCK_ADJUSTED', 'RESERVED', 'RELEASED', 'COMMITTED', 'EXPIRED'));

CREATE INDEX idx_movements_product ON inventory_movements (product_id, created_at);

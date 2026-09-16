ALTER TABLE products
    ADD COLUMN status  VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE products
    ADD CONSTRAINT chk_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'));

CREATE INDEX idx_products_status ON products (status);

-- One database per service (ADR-003/011): no service may read another's tables,
-- and separate databases make that a physical fact rather than a convention.
--
-- Runs once, from docker-entrypoint-initdb.d, on an empty data directory. Each
-- statement is separate on purpose: CREATE DATABASE cannot run inside a
-- transaction block, and several statements passed in one psql -c are wrapped
-- in one.
--
-- Before Phase 8 this was a docker exec with a multi -c command in the README —
-- which is exactly the kind of setup step that is skipped, mis-typed, or
-- forgotten on a new machine, and then costs an hour to diagnose.
CREATE DATABASE ecommerce_catalog;
CREATE DATABASE ecommerce_cart;
CREATE DATABASE ecommerce_inventory;
CREATE DATABASE ecommerce_order;
CREATE DATABASE ecommerce_payment;

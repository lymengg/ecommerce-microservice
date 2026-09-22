#!/usr/bin/env bash
#
# Checkout load / SLO probe.
#
# Phase 8 (observability) needs to answer two questions that cannot be answered
# by reading code: "is checkout inside its SLO right now?" and "when we degrade
# the system, does anything notice?". Both need a repeatable driver, so it lives
# here rather than in a session transcript.
#
# It measures from *outside* the system on purpose. The point of the phase is
# that without instrumentation you can only see this by hand, one curl at a
# time, and you cannot answer "what was p95 last Tuesday" at all.
#
# Usage:
#   ./scripts/checkout-load.sh                      # 30 checkouts, sequential
#   ITERATIONS=100 ./scripts/checkout-load.sh
#   CONCURRENCY=8 ITERATIONS=80 ./scripts/checkout-load.sh
#
# Output: a summary with p50/p95/p99 and the error rate — the shape the SLO is
# written in.
#
# Note: the gateway rate-limits checkout per subject (10/min by default). Raise
# it for a load run, or the numbers are the rate limiter's, not the system's:
#   STRICT_RATE_LIMIT_MAX_REQUESTS_PER_MINUTE=1000 ./mvnw -pl gateway-service spring-boot:run

set -uo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
TOKEN_URL=${TOKEN_URL:-http://localhost:8087/realms/ecommerce/protocol/openid-connect/token}
CATALOG=${CATALOG_URL:-http://localhost:8081}
INVENTORY=${INVENTORY_URL:-http://localhost:8083}

ITERATIONS=${ITERATIONS:-30}
CONCURRENCY=${CONCURRENCY:-1}
SKU=${SKU:-SLO-PROBE-1}
PRICE=${PRICE:-1.00}
STOCK=${STOCK:-100000}

token() {
    curl -s -X POST "$TOKEN_URL" -H 'Content-Type: application/x-www-form-urlencoded' -d "$1" \
        | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
}

CUST=$(token "grant_type=password&client_id=test-client&client_secret=dev-test-client-secret&username=customer1&password=customer1-password")
SVC=$(token "grant_type=client_credentials&client_id=service-client&client_secret=dev-service-client-secret")
ADMIN=$(token "grant_type=password&client_id=test-client&client_secret=dev-test-client-secret&username=admin1&password=admin1-password")

if [ -z "$CUST" ] || [ -z "$SVC" ]; then
    echo "FATAL: could not obtain tokens from $TOKEN_URL — is Keycloak up?" >&2
    exit 1
fi

# A dedicated product so the probe never disturbs seeded data, re-stocked to a
# known level on every run. The list is split on '{' before grepping so the id is
# read from the object that carries the sku, whatever order the fields are in.
PRODUCT_ID=$(curl -s "$CATALOG/api/v1/products" -H "Authorization: Bearer $ADMIN" \
    | tr '{' '\n' | grep "\"sku\":\"$SKU\"" | head -1 | sed -E 's/.*"id":([0-9]+).*/\1/')

if [ -z "${PRODUCT_ID:-}" ] || [ "$PRODUCT_ID" = "$(curl -s "$CATALOG/api/v1/products" -H "Authorization: Bearer $ADMIN" | tr '{' '\n' | head -1)" ]; then
    PRODUCT_ID=$(curl -s -X POST "$CATALOG/api/v1/products" -H "Authorization: Bearer $ADMIN" \
        -H 'Content-Type: application/json' \
        -d "{\"sku\":\"$SKU\",\"name\":\"SLO probe\",\"description\":\"load probe\",\"price\":$PRICE}" \
        | sed -E 's/.*"id":([0-9]+).*/\1/')
fi

curl -s -o /dev/null -X POST "$CATALOG/api/v1/products/$PRODUCT_ID/activate" -H "Authorization: Bearer $ADMIN"

curl -s -o /dev/null -X POST "$INVENTORY/internal/api/v1/inventory/stock" \
    -H "Authorization: Bearer $SVC" -H 'Content-Type: application/json' \
    -d "{\"productId\":$PRODUCT_ID,\"sku\":\"$SKU\",\"totalQuantity\":$STOCK}"

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

# One full checkout: open a cart, put a line in it, then time only the checkout
# call — the cart setup is the harness, not the thing under measurement.
one_checkout() {
    local tag=$1
    local cart
    cart=$(curl -s "$GATEWAY/api/v1/cart" -H "Authorization: Bearer $CUST" \
        | grep -oE '"cartId":"[^"]+"' | head -1 | cut -d'"' -f4)
    if [ -z "$cart" ]; then
        echo "000 0" >> "$WORKDIR/$tag"
        return
    fi
    curl -s -o /dev/null -X POST "$GATEWAY/api/v1/cart/items" -H "Authorization: Bearer $CUST" \
        -H 'Content-Type: application/json' -d "{\"productId\":$PRODUCT_ID,\"quantity\":1}"

    curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -X POST "$GATEWAY/api/v1/checkout" \
        -H "Authorization: Bearer $CUST" -H 'Content-Type: application/json' \
        -H "Idempotency-Key: slo-$tag-$$-$RANDOM" -d "{\"cartId\":\"$cart\",\"currency\":\"USD\"}" \
        >> "$WORKDIR/$tag"
}

# Warm-up: the first request pays for lazy JWT discovery, connection pools and
# JIT. Including it would report the cold start, not the steady state.
one_checkout warmup > /dev/null 2>&1
rm -f "$WORKDIR/warmup"

if [ "$CONCURRENCY" -le 1 ]; then
    for i in $(seq 1 "$ITERATIONS"); do
        one_checkout "seq" || true
    done
else
    # Each worker writes its own file: concurrent appends to one file interleave
    # and silently lose samples.
    PER=$(( (ITERATIONS + CONCURRENCY - 1) / CONCURRENCY ))
    for w in $(seq 1 "$CONCURRENCY"); do
        (
            for i in $(seq 1 "$PER"); do
                one_checkout "w$w" || true
            done
        ) &
    done
    wait
fi

cat "$WORKDIR"/* > "$WORKDIR/all" 2>/dev/null || true
TOTAL=$(wc -l < "$WORKDIR/all" | tr -d ' ')
if [ "$TOTAL" -eq 0 ]; then
    echo "FATAL: no samples collected — is the stack up?" >&2
    exit 1
fi
OK=$(awk '$1 >= 200 && $1 < 300' "$WORKDIR/all" | wc -l | tr -d ' ')
awk '{printf "%.0f\n", $2 * 1000}' "$WORKDIR/all" | sort -n > "$WORKDIR/lat"

pct() {
    awk -v p="$1" '{a[NR]=$1} END { i = int(p * NR / 100); if (i < 1) i = 1; print a[i] }' "$WORKDIR/lat"
}

printf '\ncheckout SLO probe — %s requests, concurrency %s\n' "$TOTAL" "$CONCURRENCY"
printf '  success rate   %s/%s (%s%%)\n' "$OK" "$TOTAL" "$(awk -v o="$OK" -v t="$TOTAL" 'BEGIN{printf "%.1f", 100*o/t}')"
printf '  error rate     %s%%\n' "$(awk -v o="$OK" -v t="$TOTAL" 'BEGIN{printf "%.1f", 100*(t-o)/t}')"
printf '  p50            %s ms\n' "$(pct 50)"
printf '  p95            %s ms\n' "$(pct 95)"
printf '  p99            %s ms\n' "$(pct 99)"
printf '  max            %s ms\n' "$(sort -n "$WORKDIR/lat" | tail -1)"
printf '  statuses       %s\n' "$(awk '{print $1}' "$WORKDIR/all" | sort | uniq -c | awk '{printf "%s=%s ", $2, $1}')"

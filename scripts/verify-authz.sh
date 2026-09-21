#!/usr/bin/env bash
#
# Real-token authorization smoke test.
#
# Why this exists: the mocked-JWT integration tests inject authorities directly,
# and GatewayKeycloakIT only exercises the gateway (which is reactive and has its
# own security config). Neither drives a *servlet* service with a *real* Keycloak
# token, so the combination "real token -> servlet service -> business endpoint"
# was untested. A missing `basic` client scope meant access tokens carried no
# `sub` claim, and every authenticated business call returned 403 for a whole
# phase without any test noticing.
#
# This script closes that gap manually. Run it against a full local stack:
#
#   docker compose up -d keycloak jaeger otel-collector
#   docker run --name ecommerce-db ... (see README)
#   ./mvnw install -DskipTests
#   # start all seven services (see README)
#   ./scripts/verify-authz.sh
#
# Exit code is non-zero if any check fails, so it can gate a release.

set -uo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
TOKEN_URL=${TOKEN_URL:-http://localhost:8087/realms/ecommerce/protocol/openid-connect/token}
CATALOG=${CATALOG_URL:-http://localhost:8081}
CART=${CART_URL:-http://localhost:8082}
INVENTORY=${INVENTORY_URL:-http://localhost:8083}
ORDER=${ORDER_URL:-http://localhost:8084}
PAYMENT=${PAYMENT_URL:-http://localhost:8085}

pass=0
fail=0

check() { # name expected actual
    if [ "$2" = "$3" ]; then
        printf '  PASS  %-58s %s\n' "$1" "$3"; pass=$((pass + 1))
    else
        printf '  FAIL  %-58s expected %s, got %s\n' "$1" "$2" "$3"; fail=$((fail + 1))
    fi
}

check_in() { # name "expected expected..." actual
    case " $2 " in
        *" $3 "*) printf '  PASS  %-58s %s\n' "$1" "$3"; pass=$((pass + 1)) ;;
        *) printf '  FAIL  %-58s expected one of [%s], got %s\n' "$1" "$2" "$3"; fail=$((fail + 1)) ;;
    esac
}

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

token() { # grant args
    curl -s -X POST "$TOKEN_URL" -H 'Content-Type: application/x-www-form-urlencoded' -d "$1" \
        | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
}
user_token() { token "grant_type=password&client_id=test-client&client_secret=dev-test-client-secret&username=$1&password=$1-password"; }

C1="Authorization: Bearer $(user_token customer1)"
C2="Authorization: Bearer $(user_token customer2)"
ADMIN="Authorization: Bearer $(user_token admin1)"
SERVICE="Authorization: Bearer $(token 'grant_type=client_credentials&client_id=service-client&client_secret=dev-service-client-secret')"

echo
echo "0. tokens carry a subject (the bug that motivated this script)"
CT=$(user_token customer1)
payload=$(echo "$CT" | cut -d. -f2)
case $((${#payload} % 4)) in
    2) payload="$payload==" ;;
    3) payload="$payload=" ;;
esac
decoded=$(echo "$payload" | tr '_-' '/+' | base64 -d 2>/dev/null)
check_in "customer1 token has a sub claim" "yes" "$(echo "$decoded" | grep -q '"sub"' && echo yes || echo no)"

echo
echo "1. catalog: public reads, ADMIN writes"
check "GET /api/v1/products without token" 200 "$(code "$CATALOG/api/v1/products")"
check "POST /api/v1/products as CUSTOMER" 403 "$(code -X POST "$CATALOG/api/v1/products" -H "$C1" -H 'Content-Type: application/json' -d '{"sku":"X","name":"X","description":"x","price":"1.00"}')"
check_in "POST /api/v1/products as ADMIN" "200 201" "$(code -X POST "$CATALOG/api/v1/products" -H "$ADMIN" -H 'Content-Type: application/json' -d "{\"sku\":\"AUTHZ-$$\",\"name\":\"Authz\",\"description\":\"authz smoke\",\"price\":\"1.00\"}")"

echo
echo "2. cart: CUSTOMER only"
check "GET /api/v1/cart as CUSTOMER" 200 "$(code "$CART/api/v1/cart" -H "$C1")"
check "GET /api/v1/cart as ADMIN" 403 "$(code "$CART/api/v1/cart" -H "$ADMIN")"
check "GET /api/v1/cart without token" 401 "$(code "$CART/api/v1/cart")"

echo
echo "3. internal endpoints: SERVICE only, never a user token"
check "POST inventory /internal/.../stock as CUSTOMER" 403 "$(code -X POST "$INVENTORY/internal/api/v1/inventory/stock" -H "$C1" -H 'Content-Type: application/json' -d '{"productId":1,"sku":"X","totalQuantity":1}')"
check_in "POST inventory /internal/.../stock as SERVICE" "200 201" "$(code -X POST "$INVENTORY/internal/api/v1/inventory/stock" -H "$SERVICE" -H 'Content-Type: application/json' -d '{"productId":1,"sku":"SKU-TRACE","totalQuantity":50}')"
check "GET catalog /internal/.../products/1 as CUSTOMER" 403 "$(code "$CATALOG/internal/api/v1/catalog/products/1" -H "$C1")"
check_in "GET catalog /internal/.../products/1 as SERVICE" "200" "$(code "$CATALOG/internal/api/v1/catalog/products/1" -H "$SERVICE")"
check "POST order /internal/.../pending as CUSTOMER" 403 "$(code -X POST "$ORDER/internal/api/v1/orders/00000000-0000-0000-0000-000000000001/pending" -H "$C1")"

echo
echo "4. ADR-013 trust boundary: a mismatched customerId on a CUSTOMER token"
CART_ID=$(curl -s "$GATEWAY/api/v1/cart" -H "$C1" | sed -E 's/.*"cartId":"([^"]+)".*/\1/')
check "POST /api/v1/checkout with a foreign customerId" 403 \
    "$(code -X POST "$GATEWAY/api/v1/checkout" -H "$C1" -H 'Content-Type: application/json' \
        -H "Idempotency-Key: authz-mismatch-$$" \
        -d "{\"cartId\":\"$CART_ID\",\"currency\":\"USD\",\"customerId\":\"00000000-0000-0000-0000-0000000000ff\"}")"

echo
echo "5. object-level ownership: other customers' resources are 404, never 403"
# make customer1 own an order + payment
curl -s -o /dev/null -X POST "$GATEWAY/api/v1/cart/items" -H "$C1" -H 'Content-Type: application/json' -d '{"productId":1,"quantity":1}'
CHECKOUT=$(curl -s -X POST "$GATEWAY/api/v1/checkout" -H "$C1" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: authz-owner-$$" -d "{\"cartId\":\"$CART_ID\",\"currency\":\"USD\"}")
ORDER_ID=$(echo "$CHECKOUT" | sed -E 's/.*"orderId":"([^"]+)".*/\1/')
PAYMENT_ID=$(echo "$CHECKOUT" | sed -E 's/.*"paymentId":"([^"]+)".*/\1/')
echo "       (customer1 orderId=$ORDER_ID paymentId=$PAYMENT_ID)"
check "customer1 reads own order" 200 "$(code "$ORDER/api/v1/orders/$ORDER_ID" -H "$C1")"
check "customer2 reads customer1's order -> 404" 404 "$(code "$ORDER/api/v1/orders/$ORDER_ID" -H "$C2")"
check "customer2 reads customer1's payment -> 404" 404 "$(code "$PAYMENT/api/v1/payments/$PAYMENT_ID" -H "$C2")"
check "customer2 lists own orders" 200 "$(code "$ORDER/api/v1/orders" -H "$C2")"

echo
echo "6. gateway edge"
check "GET /internal/** through the gateway as SERVICE" 403 "$(code "$GATEWAY/internal/api/v1/inventory/stock?productId=1" -H "$SERVICE")"
check "public product GET through the gateway" 200 "$(code "$GATEWAY/api/v1/products/1")"
check "protected route without token" 401 "$(code "$GATEWAY/api/v1/orders")"

echo
echo "---------------------------------------------"
printf 'passed %d, failed %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1

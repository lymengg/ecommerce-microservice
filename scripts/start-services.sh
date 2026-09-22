#!/usr/bin/env bash
#
# Start all seven services in the background, each to its own log file.
#
# Why this exists: the README recipe is seven terminals or seven background
# commands, and Phase 8's baseline needed the *same* seven every time — which is
# how a setup step drifts between runs and makes two measurements incomparable.
#
# Usage:
#   ./scripts/start-services.sh            # start, wait for readiness
#   ./scripts/start-services.sh --stop     # stop everything this script started
#
# Env:
#   DB_PORT (default 5433)          host port of the compose PostgreSQL
#   LOG_DIR (default logs)          where each service's stdout goes
#   RATE_LIMIT (default 100)        gateway checkout/payment limit per subject;
#                                   raise it for a load run so the numbers are
#                                   the system's and not the limiter's

set -uo pipefail

DB_PORT=${DB_PORT:-5433}
LOG_DIR=${LOG_DIR:-logs}
RATE_LIMIT=${RATE_LIMIT:-100}
PID_FILE="$LOG_DIR/.services.pids"

# name:port, in startup order. The gateway is last so it never accepts traffic
# before the services behind it exist.
SERVICES=(
  "catalog-service:8081"
  "cart-service:8082"
  "inventory-service:8083"
  "order-service:8084"
  "payment-service:8085"
  "checkout-service:8086"
  "gateway-service:8080"
)

stop_all() {
    if [ -f "$PID_FILE" ]; then
        while read -r pid; do
            kill "$pid" 2>/dev/null || true
        done < "$PID_FILE"
        rm -f "$PID_FILE"
    fi
    # The maven wrapper forks a JVM; killing the wrapper is not always enough.
    jps -l 2>/dev/null | grep -E "com\.ecommerce\.|classworlds" | awk '{print $1}' \
        | while read -r pid; do
            taskkill //F //PID "$pid" >/dev/null 2>&1 || kill -9 "$pid" 2>/dev/null || true
        done
    echo "stopped"
}

if [ "${1:-}" = "--stop" ]; then
    stop_all
    exit 0
fi

mkdir -p "$LOG_DIR"
: > "$PID_FILE"

for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    port="${entry##*:}"
    if [ "$name" = "gateway-service" ]; then
        STRICT_RATE_LIMIT_MAX_REQUESTS_PER_MINUTE="$RATE_LIMIT" DB_PORT="$DB_PORT" \
            ./mvnw -pl "$name" spring-boot:run > "$LOG_DIR/$name.log" 2>&1 &
    else
        DB_PORT="$DB_PORT" ./mvnw -pl "$name" spring-boot:run > "$LOG_DIR/$name.log" 2>&1 &
    fi
    echo "$!" >> "$PID_FILE"
    printf 'started %-20s :%s\n' "$name" "$port"
done

# Readiness is the actuator health endpoint, which is the same signal
# Kubernetes and Prometheus will use — waiting on anything else would be
# waiting on something that does not exist in production.
echo "waiting for readiness..."
for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    port="${entry##*:}"
    ready=no
    for _ in $(seq 1 40); do
        if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$port/actuator/health")" = "200" ]; then
            ready=yes
            break
        fi
        sleep 5
    done
    if [ "$ready" = yes ]; then
        printf '  up   %-20s :%s\n' "$name" "$port"
    else
        printf '  DOWN %-20s :%s  (see %s/%s.log)\n' "$name" "$port" "$LOG_DIR" "$name"
    fi
done

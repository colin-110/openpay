#!/usr/bin/env bash
#
# Empirically checks the claims in docs/ARCHITECTURE.md's "Failure modes" table against the real
# running stack, instead of trusting that the table is still true. Each scenario pauses a real
# container with `docker pause` (a frozen process, not a stopped one — the fastest, cleanest way
# to simulate "this dependency is unreachable" without tearing anything down) and asserts the
# documented behaviour actually holds, then unpauses and asserts recovery.
#
# This is what "testing is the driver of production-like software" means in practice: a table
# that says "Redis dies, rate limiting fails open" is a claim. This script is what turns it into
# a fact that gets re-checked every time it runs, instead of a sentence that quietly goes stale
# the next time someone changes RateLimitFilter. It already found one real bug: Lettuce's default
# Redis command timeout is 60 seconds, so "fails open" used to mean "hangs for a minute and then
# fails open" — fixed alongside this script by setting spring.data.redis.timeout explicitly in
# auth-service and gateway-service.
#
# Prerequisites:
#   docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d
#   OPENPAY_ADMIN_TOKEN set. Nothing here calls an ops-token or internal-token endpoint, so those
#   two are not needed by this script even though the stack itself requires them to start.
#
# Usage:
#   OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/fault-injection.sh
#
# Exits non-zero if any check fails. Always unpauses every container it paused, even on failure
# or interrupt — see the trap below — so a broken run never leaves the stack half-frozen. That
# trap cannot survive a SIGKILL (nothing can); if this script is force-killed rather than
# interrupted normally, check `docker ps` for anything still (Paused) and unpause it by hand.

set -u

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
AUTH_URL="${AUTH_URL:-http://localhost:8081}"
MERCHANT_URL="${MERCHANT_URL:-http://localhost:8082}"
ADMIN_TOKEN="${OPENPAY_ADMIN_TOKEN:-}"

if [ -z "$ADMIN_TOKEN" ]; then
  echo "OPENPAY_ADMIN_TOKEN is not set. Admin endpoints fail closed without it, so this run" >&2
  echo "would report false failures. Export it and try again." >&2
  exit 2
fi

PY=""
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import json,sys" >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
if [ -z "$PY" ]; then
  echo "A working python3 (or python) is required to read JSON responses." >&2
  exit 2
fi

pass=0
fail=0
paused=()

check() {
  if [ "$2" = "$3" ]; then
    printf "  PASS  %-58s %s\n" "$1" "$3"
    pass=$((pass + 1))
  else
    printf "  FAIL  %-58s expected %s, got %s\n" "$1" "$2" "$3"
    fail=$((fail + 1))
  fi
}

# --max-time on every call: a paused dependency must never make this script itself hang. If a
# request takes longer than this, that is itself the finding — "fails open" only counts if it
# fails open fast, not eventually.
# 20s, not the 8s this used to be. The gateway's own read timeout to auth-service is 10s, and
# `docker pause` freezes the process rather than closing its socket, so a call made while
# auth-service is paused hangs for the full 10s before the gateway gives up and answers 503.
# At 8s curl abandoned the request first and reported 000, which made the "API keys fail closed"
# assertion unobservable — the test could never see the very response it existed to check. Any
# client timeout here has to be longer than the longest server-side timeout being exercised.
code() { curl -s --max-time 20 -o /dev/null -w "%{http_code}" "$@"; }
body() { curl -s --max-time 20 "$@"; }
jget() { "$PY" -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

pause() {
  echo "  ... docker pause $1"
  docker pause "$1" >/dev/null
  paused+=("$1")
}

unpause() {
  echo "  ... docker unpause $1"
  docker unpause "$1" >/dev/null
  paused=("${paused[@]/$1/}")
}

# Belt and braces: whatever else happens, nothing stays frozen when this script exits.
cleanup() {
  for container in "${paused[@]}"; do
    [ -n "$container" ] && docker unpause "$container" >/dev/null 2>&1
  done
}
trap cleanup EXIT INT TERM

JSON='Content-Type: application/json'
ADMIN_HEADER="X-Admin-Token: $ADMIN_TOKEN"

echo "Gateway  $GATEWAY_URL"
echo "Auth     $AUTH_URL"
echo "Merchant $MERCHANT_URL"
echo

# A fresh merchant per scenario, not one shared across the whole run — fraud-service screens on
# velocity per merchant, so a merchant that has already taken a dozen rapid-fire payments in one
# scenario walks into the next one already primed to be held or blocked for reasons that have
# nothing to do with the fault being injected there. Mirrors the same reasoning
# tests/performance/lib/setup.js documents for exactly this trap.
provision_merchant() {
  local label="$1"
  local mid key email token suffix
  # Assigned on its own line, not as part of `local`: `local x=$(...)` makes the exit status that
  # of `local` rather than the command substitution, which silently hides a failure.
  suffix="fault-${label}-$(date +%s)-$$-$RANDOM"
  mid=$(body -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
    -d "{\"merchantCode\":\"$suffix\",\"legalName\":\"Fault Injection $label\",\"webhookUrl\":null,\"defaultCurrency\":\"USD\"}" | jget id)
  key=$(body -X POST "$AUTH_URL/api/v1/api-keys" -H "$ADMIN_HEADER" -H "$JSON" \
    -d "{\"merchantId\":\"$mid\",\"name\":\"$label\",\"scope\":\"payments:write\",\"expiresAt\":null}" | jget apiKey)
  email="$suffix@openpay.test"
  body -X POST "$AUTH_URL/api/v1/users" -H "$ADMIN_HEADER" -H "$JSON" \
    -d "{\"merchantId\":\"$mid\",\"email\":\"$email\",\"password\":\"correct-horse-battery-staple\",\"role\":\"MERCHANT_ADMIN\"}" >/dev/null
  token=$(body -X POST "$AUTH_URL/api/v1/auth/login" -H "$JSON" \
    -d "{\"email\":\"$email\",\"password\":\"correct-horse-battery-staple\"}" | jget token)
  # Caller reads these back via the last-provisioned globals rather than a return value — bash
  # has no structs, and this keeps every call site to one line.
  P_KEY="$key"; P_EMAIL="$email"; P_SESSION="$token"
  if [ -z "$mid" ] || [ -z "$key" ]; then
    echo "Could not provision a merchant and key for scenario '$label'. Is the stack up?" >&2
    exit 2
  fi
}

echo "== Baseline: everything healthy =="
provision_merchant baseline
check "payment succeeds before any fault is injected" 201 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $P_KEY" -H 'Idempotency-Key: fault-baseline' -H "$JSON" \
     -d '{"amount":15000,"currency":"USD"}')"
echo

echo "== Redis down: rate limiting and login throttling must fail open =="
echo "   (docs/ARCHITECTURE.md §5: \"Redis dies → rate limiting stops enforcing, fails open. Everything.\")"
provision_merchant redis
REDIS_KEY="$P_KEY"; REDIS_EMAIL="$P_EMAIL"
pause openpay-redis
# Fire well past the per-merchant write limit (30 requests / 5s). If the limiter were still
# enforcing with no Redis behind it, this would start returning 429; fail-open means every one
# of these should succeed on its own merits. Kept well under the fraud velocity rule's own
# threshold so a HELD/BLOCKED response here can only mean the rate limiter, not screening.
redis_down_failures=0
redis_down_start=$(date +%s)
for i in $(seq 1 12); do
  # Amount varies per call on purpose: 10 identical amounts in one window trips the seeded
  # repeated-identical-amount fraud rule (a real rule, correctly doing its job) — found by this
  # script itself reporting failures during the Redis outage that turned out to be nothing to do
  # with Redis at all.
  status=$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $REDIS_KEY" -H "Idempotency-Key: fault-redis-$i" -H "$JSON" \
    -d "{\"amount\":$((12000 + i)),\"currency\":\"USD\"}")
  [ "$status" = "201" ] || redis_down_failures=$((redis_down_failures + 1))
done
redis_down_elapsed=$(( $(date +%s) - redis_down_start ))
check "12 writes past the normal rate limit all succeed with Redis down" "0" "$redis_down_failures"
# Not a tight bound: the point is distinguishing "fails open" from the original bug, where every
# single request individually stalled for 60s (12 requests would have taken 12 minutes, not
# under a minute). This threshold has a wide margin instead of chasing an exact millisecond
# figure that depends on Lettuce's own reconnect/backoff behaviour, which this test does not
# need to know the internals of to prove is or is not roughly instant.
check "those 12 requests complete in roughly real time, not ~60s each" "fast" \
  "$([ "$redis_down_elapsed" -le 60 ] && echo fast || echo "slow (${redis_down_elapsed}s)")"
check "login still works with Redis down (throttle fails open, not closed)" 200 \
  "$(code -X POST "$AUTH_URL/api/v1/auth/login" -H "$JSON" \
     -d "{\"email\":\"$REDIS_EMAIL\",\"password\":\"correct-horse-battery-staple\"}")"
unpause openpay-redis
sleep 2
echo

echo "== fraud-service down: screening must fail open, not closed =="
echo "   (ADR-0003: an unreachable risk service must not stop every merchant from taking money)"
provision_merchant fraud-down
FRAUD_KEY="$P_KEY"
pause openpay-fraud-service
FAULT_PAYMENT=$(body -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $FRAUD_KEY" -H 'Idempotency-Key: fault-fraud-down' -H "$JSON" \
  -d '{"amount":20000,"currency":"USD"}')
check "payment still succeeds with fraud-service unreachable" 201 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $FRAUD_KEY" -H 'Idempotency-Key: fault-fraud-down-2' -H "$JSON" \
     -d '{"amount":21000,"currency":"USD"}')"
check "the payment is marked UNSCREENED, not silently ALLOWED" "UNSCREENED" \
  "$(echo "$FAULT_PAYMENT" | jget fraudStatus)"
unpause openpay-fraud-service
sleep 2
echo "== fraud-service recovered: screening resumes, on a fresh merchant with no velocity history =="
provision_merchant fraud-recovered
FRAUD_RECOVERED_KEY="$P_KEY"
# INR, not USD: the seeded "extreme-value-payment" BLOCK rule is scoped to currency=INR (see
# `curl localhost:8089/internal/fraud/rules`), so a USD payment of any size never matches it —
# a real finding in its own right (USD/other-currency traffic has no amount-based rule at all in
# this seed set, only the currency-agnostic velocity/repeated-amount ones), not a fraud-service
# bug. 50000001 is one paisa over the 50,000,000 BLOCK threshold.
BLOCKED=$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $FRAUD_RECOVERED_KEY" -H 'Idempotency-Key: fault-fraud-recovered' -H "$JSON" \
  -d '{"amount":50000001,"currency":"INR"}')
check "screening is back: an over-threshold INR payment is refused, not waved through" 422 "$BLOCKED"
echo

echo "== auth-service down: API keys fail closed, existing sessions keep working =="
echo "   (docs/ARCHITECTURE.md §5: sessions verify locally, so they survive auth-service being down)"
provision_merchant auth-down
AUTH_KEY="$P_KEY"; AUTH_SESSION="$P_SESSION"
pause openpay-auth-service
check "a payment authenticated by API key is refused, not silently allowed" 503 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $AUTH_KEY" -H 'Idempotency-Key: fault-auth-down' -H "$JSON" \
     -d '{"amount":10000,"currency":"USD"}')"
check "a dashboard session issued before the outage still reads payments" 200 \
  "$(code "$GATEWAY_URL/api/v1/payments?page=0&size=5" -H "Authorization: Bearer $AUTH_SESSION")"
unpause openpay-auth-service
sleep 3
check "API-key payments work again once auth-service recovers" 201 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $AUTH_KEY" -H 'Idempotency-Key: fault-auth-recovery' -H "$JSON" \
     -d '{"amount":10000,"currency":"USD"}')"
echo

echo "== Kafka down: writes keep working, nothing is lost, just delayed =="
echo "   (docs/ARCHITECTURE.md §5: \"Kafka dies → outbox rows accumulate unpublished and drain on recovery. Nothing is lost.\")"
provision_merchant kafka-down
KAFKA_KEY="$P_KEY"
pause openpay-kafka
KAFKA_DOWN_PAYMENT=$(body -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $KAFKA_KEY" -H 'Idempotency-Key: fault-kafka-down' -H "$JSON" \
  -d '{"amount":30000,"currency":"USD"}')
KAFKA_DOWN_ID=$(echo "$KAFKA_DOWN_PAYMENT" | jget id)
check "payment creation still succeeds with Kafka unreachable (the write is synchronous)" 201 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $KAFKA_KEY" -H 'Idempotency-Key: fault-kafka-down-2' -H "$JSON" \
     -d '{"amount":31000,"currency":"USD"}')"
sleep 3
check "the payment has not advanced past CREATED — nothing to route it yet" "CREATED" \
  "$(body "$GATEWAY_URL/api/v1/payments/$KAFKA_DOWN_ID" -H "X-Api-Key: $KAFKA_KEY" | jget status)"
unpause openpay-kafka
echo "  ... waiting up to 30s for the outbox relay to drain and routing to catch up"
recovered="false"
for i in $(seq 1 15); do
  sleep 2
  status=$(body "$GATEWAY_URL/api/v1/payments/$KAFKA_DOWN_ID" -H "X-Api-Key: $KAFKA_KEY" | jget status)
  if [ "$status" != "CREATED" ]; then
    recovered="true"
    break
  fi
done
check "the payment created during the outage eventually advances — nothing was lost" "true" "$recovered"
echo

echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]

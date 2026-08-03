#!/usr/bin/env bash
#
# End-to-end acceptance run against a live OpenPay stack.
#
# This exercises the behaviour that unit tests cannot: real HTTP, real filters, real routing,
# real database, all four services talking to each other. It caught a bug the unit suite missed.
#
# Prerequisites:
#   docker compose -f platform/docker/docker-compose.yml up -d
#   OPENPAY_ADMIN_TOKEN set, and all four services running
#
# Usage:
#   OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/e2e.sh
#
# Override any base URL via environment, e.g. GATEWAY_URL=https://staging.example.test
#
# Exits non-zero if any check fails.

set -u

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
AUTH_URL="${AUTH_URL:-http://localhost:8081}"
MERCHANT_URL="${MERCHANT_URL:-http://localhost:8082}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8083}"
# Set to empty to skip the asynchronous provider-flow checks.
ROUTER_URL="${ROUTER_URL-http://localhost:8085}"
SETTLEMENT_URL="${SETTLEMENT_URL-http://localhost:8087}"
WEBHOOK_URL="${WEBHOOK_URL-http://localhost:8084}"
FRAUD_URL="${FRAUD_URL-http://localhost:8089}"
ADMIN_TOKEN="${OPENPAY_ADMIN_TOKEN:-}"

if [ -z "$ADMIN_TOKEN" ]; then
  echo "OPENPAY_ADMIN_TOKEN is not set. Admin endpoints fail closed without it, so this run" >&2
  echo "would report false failures. Export it and try again." >&2
  exit 2
fi

# Probe each candidate rather than trusting `command -v`: on Windows, `python3` is often a
# Microsoft Store alias stub that resolves on PATH but is not a working interpreter.
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

# check <name> <expected> <actual>
check() {
  if [ "$2" = "$3" ]; then
    printf "  PASS  %-58s %s\n" "$1" "$3"
    pass=$((pass + 1))
  else
    printf "  FAIL  %-58s expected %s, got %s\n" "$1" "$2" "$3"
    fail=$((fail + 1))
  fi
}

code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
body() { curl -s "$@"; }
jget() { "$PY" -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

JSON='Content-Type: application/json'
ADMIN_HEADER="X-Admin-Token: $ADMIN_TOKEN"
# Service-to-service credential, deliberately not the admin token.
INTERNAL_TOKEN="${OPENPAY_INTERNAL_TOKEN:-dev-internal-token}"
INTERNAL_HEADER="X-Internal-Token: $INTERNAL_TOKEN"
# Operator reporting/administration that does not mint a credential, deliberately a third secret.
OPS_TOKEN="${OPENPAY_OPS_TOKEN:-dev-ops-token}"
OPS_HEADER="X-Ops-Token: $OPS_TOKEN"

echo "Gateway  $GATEWAY_URL"
echo "Auth     $AUTH_URL"
echo "Merchant $MERCHANT_URL"
echo "Payment  $PAYMENT_URL"
echo "Router   ${ROUTER_URL:-<skipped>}"
echo "Webhook  ${WEBHOOK_URL:-<skipped>}"
echo "Fraud    ${FRAUD_URL:-<skipped>}"
echo

echo "== Admin gating =="
check "onboard merchant without admin token" 401 \
  "$(code -X POST "$MERCHANT_URL/api/v1/merchants" -H "$JSON" \
     -d '{"merchantCode":"nope","legalName":"N","webhookUrl":null,"defaultCurrency":"USD"}')"
check "onboard merchant with wrong admin token" 401 \
  "$(code -X POST "$MERCHANT_URL/api/v1/merchants" -H 'X-Admin-Token: wrong' -H "$JSON" \
     -d '{"merchantCode":"nope2","legalName":"N","webhookUrl":null,"defaultCurrency":"USD"}')"
check "issue api key without admin token" 401 \
  "$(code -X POST "$AUTH_URL/api/v1/api-keys" -H "$JSON" \
     -d '{"merchantId":"11111111-1111-1111-1111-111111111111","name":"x","scope":"s","expiresAt":null}')"
# A valid scope on purpose: the point of this check is the unknown merchant, and an invalid
# scope would now be rejected by validation before the lookup ever happened.
check "issue api key for a merchant that does not exist" 422 \
  "$(code -X POST "$AUTH_URL/api/v1/api-keys" -H "$ADMIN_HEADER" -H "$JSON" \
     -d '{"merchantId":"deadbeef-0000-0000-0000-000000000000","name":"attacker","scope":"payments:read","expiresAt":null}')"

SUFFIX="e2e-$(date +%s)-$$"
MID=$(body -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
  -d "{\"merchantCode\":\"$SUFFIX\",\"legalName\":\"E2E Shop\",\"webhookUrl\":null,\"defaultCurrency\":\"USD\"}" | jget id)
check "onboard merchant with admin token" 201 \
  "$(code -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
     -d "{\"merchantCode\":\"$SUFFIX-b\",\"legalName\":\"E2E Shop B\",\"webhookUrl\":null,\"defaultCurrency\":\"USD\"}")"
check "duplicate merchant code rejected" 409 \
  "$(code -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
     -d "{\"merchantCode\":\"$SUFFIX\",\"legalName\":\"Dup\",\"webhookUrl\":null,\"defaultCurrency\":\"USD\"}")"

KEY=$(body -X POST "$AUTH_URL/api/v1/api-keys" -H "$ADMIN_HEADER" -H "$JSON" \
  -d "{\"merchantId\":\"$MID\",\"name\":\"primary\",\"scope\":\"payments:write\",\"expiresAt\":null}" | jget apiKey)
if [ -n "$KEY" ]; then
  printf "  PASS  %-58s %s\n" "issue api key for a real merchant" "${KEY:0:18}..."
  pass=$((pass + 1))
else
  printf "  FAIL  %-58s no key returned\n" "issue api key for a real merchant"
  fail=$((fail + 1))
fi
KEY_HEADER="X-Api-Key: $KEY"

echo "== Payment authentication =="
check "payment via gateway, no key" 401 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H 'Idempotency-Key: k1' -H "$JSON" \
     -d '{"amount":10000,"currency":"USD"}')"
check "payment via gateway, bogus key" 401 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H 'X-Api-Key: opk_bogus.nope' -H 'Idempotency-Key: k1' -H "$JSON" \
     -d '{"amount":10000,"currency":"USD"}')"
# Merchant identity must come from the validated key, never from a client-supplied header.
check "direct to payment-service, spoofed X-Merchant-Id" 401 \
  "$(code -X POST "$PAYMENT_URL/api/v1/payments" -H 'X-Merchant-Id: 11111111-1111-1111-1111-111111111111' \
     -H 'Idempotency-Key: spoof' -H "$JSON" -d '{"amount":5000,"currency":"USD"}')"

echo "== Payment creation and idempotency =="
IK="order-$SUFFIX"
PID=$(body -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H "Idempotency-Key: $IK" -H "$JSON" \
  -d '{"amount":10000,"currency":"USD"}' | jget id)
check "create payment through the gateway" 201 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H "Idempotency-Key: $IK-2" -H "$JSON" \
     -d '{"amount":10000,"currency":"USD"}')"
check "replay, same key and body -> 200" 200 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H "Idempotency-Key: $IK" -H "$JSON" \
     -d '{"amount":10000,"currency":"USD"}')"
check "replay, different amount -> 409" 409 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H "Idempotency-Key: $IK" -H "$JSON" \
     -d '{"amount":99999900,"currency":"USD"}')"
check "replay, different currency -> 409" 409 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H "Idempotency-Key: $IK" -H "$JSON" \
     -d '{"amount":10000,"currency":"EUR"}')"
REPLAY_ID=$(body -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H "Idempotency-Key: $IK" -H "$JSON" \
  -d '{"amount":10000,"currency":"USD"}' | jget id)
check "replay returns the original payment id" "$PID" "$REPLAY_ID"

echo "== Request validation =="
check "missing Idempotency-Key" 400 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H "$JSON" -d '{"amount":1000,"currency":"USD"}')"
check "non-ISO currency ###" 400 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H 'Idempotency-Key: v1' -H "$JSON" \
     -d '{"amount":1000,"currency":"###"}')"
check "lowercase currency" 400 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H 'Idempotency-Key: v2' -H "$JSON" \
     -d '{"amount":1000,"currency":"usd"}')"
# Amounts are minor units; Jackson would truncate 10.99 to 10 if we let it.
check "fractional amount rejected, not truncated" 400 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H 'Idempotency-Key: v3' -H "$JSON" \
     -d '{"amount":10.99,"currency":"USD"}')"
check "negative amount" 400 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H 'Idempotency-Key: v4' -H "$JSON" \
     -d '{"amount":-5000,"currency":"USD"}')"
check "zero amount" 400 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H 'Idempotency-Key: v5' -H "$JSON" \
     -d '{"amount":0,"currency":"USD"}')"
check "malformed json" 400 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" -H 'Idempotency-Key: v6' -H "$JSON" -d '{not json')"

echo "== Reads and the payment state machine =="
check "get payment by id" 200 "$(code "$GATEWAY_URL/api/v1/payments/$PID" -H "$KEY_HEADER")"
check "get unknown payment" 404 \
  "$(code "$GATEWAY_URL/api/v1/payments/00000000-0000-0000-0000-000000000000" -H "$KEY_HEADER")"
check "list payments" 200 "$(code "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER")"
# A merchant must not be able to move its own payment forward; only a verified provider
# callback can. The route is gone entirely.
check "merchant cannot self-capture a payment" 404   "$(code -X POST "$GATEWAY_URL/api/v1/payments/$PID/status" -H "$KEY_HEADER" -H "$JSON" -d '{"status":"CAPTURED"}')"

echo "== Asynchronous provider flow =="
# Routed, dispatched, and completed by provider callback with no further client action.
final_status=""
# The sleep is load-bearing, which is the opposite of how it looks. Without it this loop waits for
# "40 requests" rather than for a duration, so how long the asynchronous flow is actually given
# depends on how fast the API answers — and making the platform faster shortened the wait. That is
# exactly how it broke: a round of latency work cut per-request time enough that forty polls
# elapsed before the second acquirer callback had been relayed, and the payment was still
# AUTHORIZED when the loop gave up. A test whose timeout shrinks as the system improves reports a
# regression precisely when there isn't one. The review-release loop further down always slept;
# this one was simply missed.
for _ in $(seq 1 40); do
  final_status=$(body "$GATEWAY_URL/api/v1/payments/$PID" -H "$KEY_HEADER" | jget status)
  [ "$final_status" = "CAPTURED" ] && break
  [ "$final_status" = "FAILED" ] && break
  sleep 1
done
check "payment reaches CAPTURED with no client action" CAPTURED "$final_status"

if [ -n "${ROUTER_URL:-}" ]; then
  attempts=$(body "$ROUTER_URL/internal/router/payments/$PID/attempts?merchantId=$MID"     -H "$INTERNAL_HEADER")
  case "$attempts" in
    *ACCEPTED*) printf "  PASS  %-58s %s
" "router recorded an accepted provider attempt" "ok"; pass=$((pass + 1));;
    *) printf "  FAIL  %-58s %s
" "router recorded an accepted provider attempt" "$attempts"; fail=$((fail + 1));;
  esac
fi

if [ -n "${WEBHOOK_URL:-}" ]; then
  check "forged callback signature refused" 401     "$(code -X POST "$WEBHOOK_URL/internal/provider/webhooks/mock-bank-a" -H 'X-Provider-Signature: deadbeef' -H "$JSON" -d "{\"eventId\":\"forged\",\"paymentId\":\"$PID\",\"outcome\":\"CAPTURED\"}")"
  check "callback from an unknown provider refused" 401     "$(code -X POST "$WEBHOOK_URL/internal/provider/webhooks/evil-bank" -H 'X-Provider-Signature: whatever' -H "$JSON" -d "{\"eventId\":\"forged2\",\"paymentId\":\"$PID\",\"outcome\":\"CAPTURED\"}")"

  # A capture callback is the instruction that releases funds, so a captured one must not stay
  # usable. These sign with the bank's real secret: the only thing wrong with them is the clock.
  BANK_A_SECRET="${MOCK_BANK_A_SECRET:-bank-a-secret}"
  replay_body() { echo "{\"eventId\":\"replay-$1\",\"paymentId\":\"$PID\",\"outcome\":\"CAPTURED\"}"; }
  sign_at() {  # timestamp, body -> hex signature over "timestamp.body"
    "$PY" -c "import hmac,hashlib,sys;print(hmac.new(sys.argv[1].encode(),(sys.argv[2]+'.'+sys.argv[3]).encode(),hashlib.sha256).hexdigest())" \
      "$BANK_A_SECRET" "$1" "$2"
  }
  NOW=$("$PY" -c 'import time;print(int(time.time()))')
  HOUR_AGO=$((NOW - 3600))

  STALE_BODY=$(replay_body stale)
  check "a correctly signed callback from an hour ago is refused" 401 \
    "$(code -X POST "$WEBHOOK_URL/internal/provider/webhooks/mock-bank-a" \
       -H "X-Provider-Signature: $(sign_at "$HOUR_AGO" "$STALE_BODY")" \
       -H "X-Provider-Timestamp: $HOUR_AGO" -H "$JSON" -d "$STALE_BODY")"

  FRESH_BODY=$(replay_body rewritten)
  check "an old signature replayed with a fresh timestamp is refused" 401 \
    "$(code -X POST "$WEBHOOK_URL/internal/provider/webhooks/mock-bank-a" \
       -H "X-Provider-Signature: $(sign_at "$HOUR_AGO" "$FRESH_BODY")" \
       -H "X-Provider-Timestamp: $NOW" -H "$JSON" -d "$FRESH_BODY")"

  NOTS_BODY=$(replay_body notimestamp)
  check "a callback with no timestamp is refused" 401 \
    "$(code -X POST "$WEBHOOK_URL/internal/provider/webhooks/mock-bank-a" \
       -H "X-Provider-Signature: $(sign_at "$NOW" "$NOTS_BODY")" -H "$JSON" -d "$NOTS_BODY")"

  GOOD_BODY=$(replay_body accepted)
  check "a correctly signed callback sent now is accepted" 200 \
    "$(code -X POST "$WEBHOOK_URL/internal/provider/webhooks/mock-bank-a" \
       -H "X-Provider-Signature: $(sign_at "$NOW" "$GOOD_BODY")" \
       -H "X-Provider-Timestamp: $NOW" -H "$JSON" -d "$GOOD_BODY")"
fi

echo "== Tenant isolation =="
OTHER_MID=$(body -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
  -d "{\"merchantCode\":\"$SUFFIX-other\",\"legalName\":\"Other\",\"webhookUrl\":null,\"defaultCurrency\":\"EUR\"}" | jget id)
OTHER_KEY=$(body -X POST "$AUTH_URL/api/v1/api-keys" -H "$ADMIN_HEADER" -H "$JSON" \
  -d "{\"merchantId\":\"$OTHER_MID\",\"name\":\"other\",\"scope\":\"payments:write\",\"expiresAt\":null}" | jget apiKey)
check "another merchant cannot read the payment" 404 \
  "$(code "$GATEWAY_URL/api/v1/payments/$PID" -H "X-Api-Key: $OTHER_KEY")"
check "another merchant sees an empty list" 0 \
  "$(body "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $OTHER_KEY" | jget totalItems)"

echo "== Authority =="
# Every credential carries an authority. Until it was enforced, a read-only key and a viewer
# session could both move money, which made the scope on a key decoration.
READ_KEY=$(body -X POST "$AUTH_URL/api/v1/api-keys" -H "$ADMIN_HEADER" -H "$JSON" \
  -d "{\"merchantId\":\"$MID\",\"name\":\"$SUFFIX-read\",\"scope\":\"payments:read\",\"expiresAt\":null}" | jget apiKey)
check "read-only key cannot create a payment" 403 \
  "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "X-Api-Key: $READ_KEY" \
     -H "Idempotency-Key: $SUFFIX-ro-1" -H "$JSON" -d '{"amount":1000,"currency":"INR"}')"
check "read-only key cannot issue a refund" 403 \
  "$(code -X POST "$GATEWAY_URL/api/v1/refunds" -H "X-Api-Key: $READ_KEY" \
     -H "Idempotency-Key: $SUFFIX-ro-2" -H "$JSON" -d "{\"paymentId\":\"$PID\",\"amount\":null,\"reason\":\"nope\"}")"
check "read-only key can still read payments" 200 \
  "$(code "$GATEWAY_URL/api/v1/payments/$PID" -H "X-Api-Key: $READ_KEY")"
check "an unrecognised scope cannot be issued" 400 \
  "$(code -X POST "$AUTH_URL/api/v1/api-keys" -H "$ADMIN_HEADER" -H "$JSON" \
     -d "{\"merchantId\":\"$MID\",\"name\":\"$SUFFIX-bad\",\"scope\":\"payments:everything\",\"expiresAt\":null}")"

echo "== Internal surfaces =="
# These were reachable by anyone who could open the port. The service token is deliberately not
# the admin token: payment-service reads attempt history without holding the keys to the platform.
if [ -n "${ROUTER_URL:-}" ]; then
  check "router refuses an unauthenticated caller" 401 "$(code "$ROUTER_URL/internal/router/providers")"
  check "router refuses the platform admin token" 401 "$(code "$ROUTER_URL/internal/router/providers" -H "$ADMIN_HEADER")"
  check "router accepts the service token" 200 "$(code "$ROUTER_URL/internal/router/providers" -H "$INTERNAL_HEADER")"
  check "router scopes attempts to the owning merchant" "[]" \
    "$(body "$ROUTER_URL/internal/router/payments/$PID/attempts?merchantId=$OTHER_MID" -H "$INTERNAL_HEADER")"
fi

echo "== Webhook URL policy =="
# The platform POSTs to whatever is in this column, from inside its own network, signed.
check "webhook URL aimed at cloud metadata is refused" 400 \
  "$(code -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
     -d "{\"merchantCode\":\"$SUFFIX-ssrf1\",\"legalName\":\"S\",\"webhookUrl\":\"http://169.254.169.254/latest/meta-data/\",\"defaultCurrency\":\"INR\"}")"
check "webhook URL on a private range is refused" 400 \
  "$(code -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
     -d "{\"merchantCode\":\"$SUFFIX-ssrf2\",\"legalName\":\"S\",\"webhookUrl\":\"http://10.0.0.5/hook\",\"defaultCurrency\":\"INR\"}")"
check "a public https webhook URL is accepted" 201 \
  "$(code -X POST "$MERCHANT_URL/api/v1/merchants" -H "$ADMIN_HEADER" -H "$JSON" \
     -d "{\"merchantCode\":\"$SUFFIX-ssrf3\",\"legalName\":\"S\",\"webhookUrl\":\"https://example.com/hook\",\"defaultCurrency\":\"INR\"}")"

echo "== Merchant-facing reads =="
# Settlements and delivery history are scoped by the credential, and the operator actions that
# decide when money moves sit on /internal where a merchant key cannot reach them.
check "settlements require a credential" 401 "$(code "$GATEWAY_URL/api/v1/settlements")"
check "a merchant can read its own settlements" 200 \
  "$(code "$GATEWAY_URL/api/v1/settlements" -H "X-Api-Key: $KEY")"
check "delivery history requires a credential" 401 "$(code "$GATEWAY_URL/api/v1/webhooks/deliveries")"
check "a merchant can read its own delivery history" 200 \
  "$(code "$GATEWAY_URL/api/v1/webhooks/deliveries" -H "X-Api-Key: $KEY")"
check "a read-only key can read settlements" 200 \
  "$(code "$GATEWAY_URL/api/v1/settlements" -H "X-Api-Key: $READ_KEY")"

if [ -n "${SETTLEMENT_URL:-}" ]; then
  # Closing a window does not mint a credential, so it takes the ops token rather than the admin
  # token — and the admin token, which used to open this, no longer does.
  check "closing a window needs a credential" 401 \
    "$(code -X POST "$SETTLEMENT_URL/internal/settlements/run")"
  check "the platform admin token no longer opens this" 401 \
    "$(code -X POST "$SETTLEMENT_URL/internal/settlements/run" -H "$ADMIN_HEADER")"
  check "an operator can close a window with the ops token" 200 \
    "$(code -X POST "$SETTLEMENT_URL/internal/settlements/run" -H "$OPS_HEADER")"
  check "a merchant sees only its own payouts" "$MID" \
    "$(body "$GATEWAY_URL/api/v1/settlements" -H "X-Api-Key: $KEY" \
       | "$PY" -c 'import sys,json;i=json.load(sys.stdin)["items"];print(i[0]["merchantId"] if i else "none")')"
fi

echo "== Ops surfaces =="
# Reads and administration that do not mint a credential: the general ledger, cross-merchant
# delivery history. A leaked ops token cannot onboard a merchant or issue an API key, which is the
# whole point of not using the admin token here.
check "the ledger refuses an unauthenticated caller" 401 \
  "$(code "http://localhost:8086/api/v1/ledger/entries?referenceId=$PID")"
check "the ledger refuses the platform admin token" 401 \
  "$(code "http://localhost:8086/api/v1/ledger/entries?referenceId=$PID" -H "$ADMIN_HEADER")"
check "the ledger accepts the ops token" 200 \
  "$(code "http://localhost:8086/api/v1/ledger/entries?referenceId=$PID" -H "$OPS_HEADER")"
check "cross-merchant delivery history refuses the admin token" 401 \
  "$(code "http://localhost:8088/internal/webhooks/deliveries" -H "$ADMIN_HEADER")"
check "cross-merchant delivery history accepts the ops token" 200 \
  "$(code "http://localhost:8088/internal/webhooks/deliveries" -H "$OPS_HEADER")"

if [ -n "${FRAUD_URL:-}" ]; then
  echo "== Risk screening =="
  # Three tiers on one service, because the three things it does carry three different authorities.
  check "the gate refuses an unauthenticated caller" 401 \
    "$(code -X POST "$FRAUD_URL/internal/fraud/checks" -H "$JSON" \
       -d "{\"paymentId\":\"$PID\",\"merchantId\":\"$MID\",\"amount\":100,\"currency\":\"INR\"}")"
  check "the review queue refuses the admin token" 401 \
    "$(code "$FRAUD_URL/internal/fraud/reviews" -H "$ADMIN_HEADER")"
  check "the review queue accepts the ops token" 200 \
    "$(code "$FRAUD_URL/internal/fraud/reviews" -H "$OPS_HEADER")"
  # Editing a rule is standing policy, so it sits with the credential-minting actions.
  check "rule editing refuses the ops token" 401 \
    "$(code "$FRAUD_URL/internal/fraud/rules" -H "$OPS_HEADER")"
  check "rule editing accepts the admin token" 200 \
    "$(code "$FRAUD_URL/internal/fraud/rules" -H "$ADMIN_HEADER")"
  check "a velocity rule with no window is refused" 400 \
    "$(code -X POST "$FRAUD_URL/internal/fraud/rules" -H "$ADMIN_HEADER" -H "$JSON" \
       -d "{\"name\":\"$SUFFIX-nowindow\",\"ruleType\":\"VELOCITY_COUNT\",\"threshold\":5,\"windowSeconds\":null,\"currency\":null,\"action\":\"REVIEW\",\"priority\":99}")"

  # The gate in the payment path, end to end. The seeded rules block anything over 5,00,000 rupees
  # and hold anything over 50,000 for review.
  check "a payment over the block threshold is refused" 422 \
    "$(code -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" \
       -H "Idempotency-Key: $SUFFIX-blocked" -H "$JSON" -d '{"amount":90000000,"currency":"INR"}')"

  HELD_ID=$(body -X POST "$GATEWAY_URL/api/v1/payments" -H "$KEY_HEADER" \
    -H "Idempotency-Key: $SUFFIX-held" -H "$JSON" -d '{"amount":9000000,"currency":"INR"}' | jget id)
  check "a payment over the review threshold is held" HELD \
    "$(body "$GATEWAY_URL/api/v1/payments/$HELD_ID" -H "$KEY_HEADER" | jget fraudStatus)"
  # Held means held: nothing was published, so no acquirer has seen it and it stays CREATED.
  check "a held payment is not routed" CREATED \
    "$(body "$GATEWAY_URL/api/v1/payments/$HELD_ID" -H "$KEY_HEADER" | jget status)"

  check "an operator can release it" 200 \
    "$(code -X POST "$FRAUD_URL/internal/fraud/reviews/$HELD_ID/resolve" -H "$OPS_HEADER" -H "$JSON" \
       -d '{"outcome":"ALLOW","resolvedBy":"e2e"}')"
  check "resolving the same review twice is refused" 409 \
    "$(code -X POST "$FRAUD_URL/internal/fraud/reviews/$HELD_ID/resolve" -H "$OPS_HEADER" -H "$JSON" \
       -d '{"outcome":"BLOCK","resolvedBy":"e2e"}')"

  released=""
  for _ in $(seq 1 40); do
    released=$(body "$GATEWAY_URL/api/v1/payments/$HELD_ID" -H "$KEY_HEADER" | jget fraudStatus)
    [ "$released" = "ALLOWED" ] && break
    sleep 1
  done
  check "the released payment is routed after the review closes" ALLOWED "$released"
fi

if [ -n "${ROUTER_URL:-}" ]; then
  echo "== Routing rules =="
  # Editing the routing table decides where every payment goes, so it sits behind the admin token
  # rather than the ops token that covers the rest of this service.
  check "the routing table refuses an unauthenticated caller" 401 \
    "$(code "$ROUTER_URL/internal/routing-rules")"
  check "the routing table refuses the service token" 401 \
    "$(code "$ROUTER_URL/internal/routing-rules" -H "$INTERNAL_HEADER")"
  check "an administrator can read the routing table" 200 \
    "$(code "$ROUTER_URL/internal/routing-rules" -H "$ADMIN_HEADER")"
  # Seeded from configuration on first start, so an existing deployment routes as it always did.
  check "the table was seeded with both acquirers" 2 \
    "$(body "$ROUTER_URL/internal/routing-rules" -H "$ADMIN_HEADER" \
       | "$PY" -c 'import sys,json;r=json.load(sys.stdin);print(len({x["providerName"] for x in r}))')"
  check "resolving shows what a payment would be tried against" mock-bank-a \
    "$(body "$ROUTER_URL/internal/routing-rules/resolve?merchantId=$MID&currency=INR&amount=10000" \
       -H "$ADMIN_HEADER" | "$PY" -c 'import sys,json;r=json.load(sys.stdin);print(r[0]["providerName"] if r else "none")')"
  check "two rules with the same scope are refused" 400 \
    "$(code -X POST "$ROUTER_URL/internal/routing-rules" -H "$ADMIN_HEADER" -H "$JSON" \
       -d '{"providerName":"mock-bank-a","baseUrl":"http://mock-bank-a:9001","priority":99,"merchantId":null,"currency":null,"minAmount":null,"maxAmount":null}')"
  check "an inverted amount band is refused" 400 \
    "$(code -X POST "$ROUTER_URL/internal/routing-rules" -H "$ADMIN_HEADER" -H "$JSON" \
       -d "{\"providerName\":\"mock-bank-a\",\"baseUrl\":\"http://mock-bank-a:9001\",\"priority\":50,\"merchantId\":\"$MID\",\"currency\":null,\"minAmount\":10000,\"maxAmount\":100}")"
fi

echo "== Dead letter replay =="
# Replaying an event mints no credential, so it sits with the ledger on the ops token. The topic
# list is an allowlist: replay publishes to a topic derived from the request, and accepting an
# arbitrary one would let this token inject any event into the platform.
check "the replay tool refuses an unauthenticated caller" 401 "$(code "$PAYMENT_URL/internal/dlq/topics")"
check "the replay tool refuses the platform admin token" 401 \
  "$(code "$PAYMENT_URL/internal/dlq/topics" -H "$ADMIN_HEADER")"
check "an operator can list the replayable topics" 200 \
  "$(code "$PAYMENT_URL/internal/dlq/topics" -H "$OPS_HEADER")"
check "a topic this service does not consume is refused" 400 \
  "$(code -X POST "$PAYMENT_URL/internal/dlq/replay?topic=settlement.created.v1" -H "$OPS_HEADER")"
check "peeking an empty dead letter topic is not an error" 200 \
  "$(code "$PAYMENT_URL/internal/dlq?topic=refund.callback-received.v1&limit=5" -H "$OPS_HEADER")"

echo "== Audit trail =="
# Reading the log sits on the operator tier, not the admin tier: investigating an incident should
# not require holding the credential that could cause one. There is no write endpoint at all.
check "the audit log refuses an unauthenticated caller" 401 "$(code "$AUTH_URL/internal/audit")"
check "the audit log refuses the platform admin token" 401 "$(code "$AUTH_URL/internal/audit" -H "$ADMIN_HEADER")"
check "an operator can read the audit log" 200 "$(code "$AUTH_URL/internal/audit" -H "$OPS_HEADER")"
check "the audit log cannot be written to" 405 \
  "$(code -X POST "$AUTH_URL/internal/audit" -H "$OPS_HEADER" -H "$JSON" -d '{}')"

# A refused login has to survive the transaction that failed, or the log would contain only the
# sign-ins that worked.
body -X POST "$AUTH_URL/api/v1/auth/login" -H "$JSON" \
  -d "{\"email\":\"ghost-$SUFFIX@openpay.test\",\"password\":\"wrong-password\"}" > /dev/null
check "a refused login is recorded" 1 \
  "$(body "$AUTH_URL/internal/audit?action=LOGIN_FAILED&size=200" -H "$OPS_HEADER" \
     | "$PY" -c "import sys,json;print(sum(1 for e in json.load(sys.stdin) if e['actor']=='ghost-$SUFFIX@openpay.test'))")"
# Issuing the key above must be on the record, with the prefix rather than the key.
check "issuing an api key is recorded against the merchant" 1 \
  "$(body "$AUTH_URL/internal/audit?action=API_KEY_ISSUED&merchantId=$MID&size=200" -H "$OPS_HEADER" \
     | "$PY" -c "import sys,json;e=json.load(sys.stdin);print(1 if any(x['subject'].startswith('opk_') for x in e) else 0)")"
check "onboarding a merchant is recorded" 1 \
  "$(body "$MERCHANT_URL/internal/audit?action=MERCHANT_CREATED&merchantId=$MID" -H "$OPS_HEADER" \
     | "$PY" -c 'import sys,json;print(len(json.load(sys.stdin)))')"

echo "== Gateway routing =="
# A catch-all exception handler must not turn Spring's own 404 into a fabricated 500.
check "unrouted path is 404, not 500" 404 "$(code "$GATEWAY_URL/api/v1/nothing-here" -H "$KEY_HEADER")"
check "open ping stays reachable" 200 "$(code "$GATEWAY_URL/api/v1/ping")"
check "merchant route proxied through gateway" 200 "$(code "$GATEWAY_URL/api/v1/merchants/$MID" -H "$ADMIN_HEADER")"
check "merchant route via gateway without token" 401 "$(code "$GATEWAY_URL/api/v1/merchants/$MID")"

echo
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ] || exit 1

#!/usr/bin/env bash
#
# Puts enough realistic traffic behind the dashboard to be worth looking at: a merchant, a
# dashboard user, an API key, and a spread of rupee payments, some of which are then refunded.
#
# Requires the stack to be running and OPENPAY_ADMIN_TOKEN to be set:
#   OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/seed-demo.sh
#
# Safe to run more than once: the merchant code and the user email are suffixed per run, so a
# second run adds a second merchant rather than colliding with the first.

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
AUTH_URL="${AUTH_URL:-http://localhost:8081}"
ADMIN_TOKEN="${OPENPAY_ADMIN_TOKEN:-}"
PASSWORD="${DEMO_PASSWORD:-dashboard-demo-password}"
EMAIL="${DEMO_EMAIL:-owner@openpay.test}"

if [ -z "$ADMIN_TOKEN" ]; then
  echo "OPENPAY_ADMIN_TOKEN is not set. Onboarding fails closed without it." >&2
  exit 1
fi

# python3 on Windows is a Store alias stub that exits without running anything, so probe rather
# than assume.
PY=""
for candidate in python python3 py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import sys" >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
if [ -z "$PY" ]; then
  echo "A working python is required to read JSON responses." >&2
  exit 1
fi

json() { "$PY" -c "import sys,json;print(json.load(sys.stdin)$1)"; }
uuid() { "$PY" -c "import uuid;print(uuid.uuid4())"; }

JSON="Content-Type: application/json"
ADMIN="X-Admin-Token: $ADMIN_TOKEN"
SUFFIX="$(uuid | cut -c1-8)"

echo "Onboarding merchant demo-$SUFFIX"
MERCHANT_ID=$(curl -sS -X POST "$GATEWAY_URL/api/v1/merchants" -H "$ADMIN" -H "$JSON" \
  -d "{\"merchantCode\":\"demo-$SUFFIX\",\"legalName\":\"Chai Point Retail Pvt Ltd\",\"webhookUrl\":null,\"defaultCurrency\":\"INR\"}" \
  | json "['id']")

API_KEY=$(curl -sS -X POST "$AUTH_URL/api/v1/api-keys" -H "$ADMIN" -H "$JSON" \
  -d "{\"merchantId\":\"$MERCHANT_ID\",\"name\":\"dashboard-demo\",\"scope\":\"payments:write\",\"expiresAt\":null}" \
  | json "['apiKey']")

USER_EMAIL="${SUFFIX}-${EMAIL}"
curl -sS -o /dev/null -X POST "$AUTH_URL/api/v1/users" -H "$ADMIN" -H "$JSON" \
  -d "{\"merchantId\":\"$MERCHANT_ID\",\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\",\"role\":\"MERCHANT_ADMIN\"}"

# Paise, not rupees. Spread across the kind of order values a retail merchant actually sees, and
# weighted towards UPI, which is how most of this traffic would arrive in India. The token is what
# an acquirer would need; the platform accepts it and does not keep it.
ORDERS=(
  '24900|{"type":"upi","vpa":"colin.thomas@okhdfcbank","token":"tok_upi_1"}'
  '149900|{"type":"card","network":"rupay","last4":"4321","token":"tok_card_1"}'
  '89900|{"type":"upi","vpa":"anita.r@ybl","token":"tok_upi_2"}'
  '1250000|{"type":"netbanking","bank":"HDFC","token":"tok_nb_1"}'
  '45000|{"type":"upi","vpa":"kiran@paytm","token":"tok_upi_3"}'
  '320000|{"type":"card","network":"visa","last4":"1881","token":"tok_card_2"}'
  '9900|{"type":"upi","vpa":"s.menon@okaxis","token":"tok_upi_4"}'
  '67500|{"type":"wallet","bank":"Paytm","token":"tok_wal_1"}'
  '199900|{"type":"card","network":"mastercard","last4":"9012","token":"tok_card_3"}'
  '15000|{"type":"upi","vpa":"deepa@okicici","token":"tok_upi_5"}'
  '875000|{"type":"netbanking","bank":"ICICI","token":"tok_nb_2"}'
  '34900|{"type":"upi","vpa":"rahul.v@ybl","token":"tok_upi_6"}'
  '249900|{"type":"card","network":"rupay","last4":"7788","token":"tok_card_4"}'
  # No method at all, because some integrations do not send one and the console should say so
  # rather than invent one.
  '118000|'
  # Over the seeded review threshold of Rs 50,000, so screening holds it. Deliberately last: the
  # console and the review queue both need something in them, and a demo with an empty queue makes
  # the whole review flow look theoretical.
  '9000000|{"type":"card","network":"visa","last4":"4242","token":"tok_card_5"}'
)

echo "Creating ${#ORDERS[@]} payments"
PAYMENT_IDS=()
for order in "${ORDERS[@]}"; do
  amount="${order%%|*}"
  method="${order#*|}"
  if [ -n "$method" ]; then
    body="{\"amount\":$amount,\"currency\":\"INR\",\"paymentMethod\":$method}"
  else
    body="{\"amount\":$amount,\"currency\":\"INR\"}"
  fi
  id=$(curl -sS -X POST "$GATEWAY_URL/api/v1/payments" \
    -H "X-Api-Key: $API_KEY" -H "Idempotency-Key: $(uuid)" -H "$JSON" \
    -d "$body" | json "['id']")
  PAYMENT_IDS+=("$id")
done

# Refunds only apply to a captured payment, and capture happens asynchronously once the acquirer
# calls back. Give the round-trip time rather than racing it.
echo "Waiting for the acquirer round-trip"
sleep 8

echo "Refunding a few"
refunded=0
for id in "${PAYMENT_IDS[@]:0:4}"; do
  status=$(curl -sS "$GATEWAY_URL/api/v1/payments/$id" -H "X-Api-Key: $API_KEY" | json "['status']")
  [ "$status" = "CAPTURED" ] || continue
  # A partial on some, the whole thing on others, which is what a refund list looks like in life.
  body="{\"paymentId\":\"$id\",\"amount\":null,\"reason\":\"Order cancelled by customer\"}"
  if [ $((refunded % 2)) -eq 0 ]; then
    part=$(curl -sS "$GATEWAY_URL/api/v1/payments/$id" -H "X-Api-Key: $API_KEY" \
      | json "['amount']//3")
    body="{\"paymentId\":\"$id\",\"amount\":$part,\"reason\":\"Item returned\"}"
  fi
  curl -sS -o /dev/null -X POST "$GATEWAY_URL/api/v1/refunds" \
    -H "X-Api-Key: $API_KEY" -H "Idempotency-Key: $(uuid)" -H "$JSON" -d "$body"
  refunded=$((refunded + 1))
done

HELD_COUNT=0
for id in "${PAYMENT_IDS[@]}"; do
  fraud=$(curl -sS "$GATEWAY_URL/api/v1/payments/$id" -H "X-Api-Key: $API_KEY" | json "['fraudStatus']")
  [ "$fraud" = "HELD" ] && HELD_COUNT=$((HELD_COUNT + 1))
done

cat <<SUMMARY

Done.

  Merchant   $MERCHANT_ID
  API key    $API_KEY
  Dashboard  $USER_EMAIL / $PASSWORD

  ${#PAYMENT_IDS[@]} payments, $refunded refunded, $HELD_COUNT held for review.

The held one is waiting on a human and has reached no acquirer, which is the point. Work it with:

  curl -s localhost:8089/internal/fraud/reviews -H "X-Ops-Token: \$OPENPAY_OPS_TOKEN"

Dashboard:  http://localhost:5173  (already running if you started the stack with docker compose)
SUMMARY

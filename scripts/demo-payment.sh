#!/usr/bin/env bash
#
# One payment, start to finish, narrated.
#
# Made for showing someone the platform rather than for testing it: it onboards a merchant, takes a
# payment, waits while the simulated acquirer authorises and captures it, then shows the ledger
# entries and the acquirer attempts behind it. Everything it prints is read back from the running
# system, not assumed.
#
#   OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/demo-payment.sh
#
# Pass an existing key to skip onboarding and use a merchant you already have:
#
#   API_KEY=opk_... MERCHANT_ID=... ./scripts/demo-payment.sh

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
AUTH_URL="${AUTH_URL:-http://localhost:8081}"
MERCHANT_URL="${MERCHANT_URL:-http://localhost:8082}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8086}"
ROUTER_URL="${ROUTER_URL:-http://localhost:8085}"
ADMIN_TOKEN="${OPENPAY_ADMIN_TOKEN:-}"
OPS_TOKEN="${OPENPAY_OPS_TOKEN:-dev-ops-token}"
INTERNAL_TOKEN="${OPENPAY_INTERNAL_TOKEN:-dev-internal-token}"
AMOUNT="${AMOUNT:-249900}"
CURRENCY="${CURRENCY:-INR}"

# python3 on Windows is a Store alias that exits without running, so probe rather than assume.
PY=""
for candidate in python python3 py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import sys" >/dev/null 2>&1; then
    PY="$candidate"; break
  fi
done
[ -n "$PY" ] || { echo "No working python on PATH; this script needs one to read JSON." >&2; exit 1; }

json() { "$PY" -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }
step() { printf "\n\033[1m%s\033[0m\n" "$1"; }

API_KEY="${API_KEY:-}"
MERCHANT_ID="${MERCHANT_ID:-}"

if [ -z "$API_KEY" ]; then
  [ -n "$ADMIN_TOKEN" ] || { echo "OPENPAY_ADMIN_TOKEN is not set, and onboarding fails closed without it." >&2; exit 1; }
  step "Onboarding a merchant"
  SUFFIX="demo-$(date +%s)"
  MERCHANT_ID=$(curl -sS -X POST "$MERCHANT_URL/api/v1/merchants" \
    -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"merchantCode\":\"$SUFFIX\",\"legalName\":\"Demo Shop\",\"webhookUrl\":null,\"defaultCurrency\":\"$CURRENCY\"}" \
    | json "['id']")
  echo "  merchant  $MERCHANT_ID"

  API_KEY=$(curl -sS -X POST "$AUTH_URL/api/v1/api-keys" \
    -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"merchantId\":\"$MERCHANT_ID\",\"name\":\"$SUFFIX\",\"scope\":\"payments:write\",\"expiresAt\":null}" \
    | json "['apiKey']")
  echo "  api key   ${API_KEY:0:20}...  (shown once, never stored in plaintext)"
fi

step "Taking a payment"
echo "  This is the call a merchant's own backend makes. Nothing else in the flow is client-driven."
CREATED=$(curl -sS -X POST "$GATEWAY_URL/api/v1/payments" \
  -H 'Content-Type: application/json' \
  -H "X-Api-Key: $API_KEY" \
  -H "Idempotency-Key: demo-$(date +%s)-$$" \
  -d "{\"amount\":$AMOUNT,\"currency\":\"$CURRENCY\",\"paymentMethod\":{\"type\":\"upi\",\"vpa\":\"customer@okhdfcbank\",\"token\":\"tok_demo\"}}")
PAYMENT_ID=$(echo "$CREATED" | json "['id']")
echo "  payment   $PAYMENT_ID"
echo "  status    $(echo "$CREATED" | json "['status']")   screening: $(echo "$CREATED" | json "['fraudStatus']")"
echo "  method    $(echo "$CREATED" | json "['paymentMethod']['vpa']")   <- masked; the platform keeps nothing worth stealing"

step "Waiting for the acquirer"
echo "  Creation returned already. Routing, the bank call and its two signed callbacks all happen"
echo "  after the response, driven by the outbox and Kafka rather than by the caller."
STATUS=""
for i in $(seq 1 30); do
  STATUS=$(curl -sS "$GATEWAY_URL/api/v1/payments/$PAYMENT_ID" -H "X-Api-Key: $API_KEY" | json "['status']")
  printf "  t+%-3ss %s\n" "$i" "$STATUS"
  { [ "$STATUS" = "CAPTURED" ] || [ "$STATUS" = "FAILED" ]; } && break
  sleep 1
done

step "Which acquirer took it"
curl -sS "$ROUTER_URL/internal/router/payments/$PAYMENT_ID/attempts?merchantId=$MERCHANT_ID" \
  -H "X-Internal-Token: $INTERNAL_TOKEN" 2>/dev/null \
  | "$PY" -c "
import sys, json
try:
    rows = json.load(sys.stdin)
except Exception:
    print('  (attempt history unavailable)'); raise SystemExit
rows = rows if isinstance(rows, list) else rows.get('items', [])
for r in rows:
    print(f\"  attempt {r.get('attemptNo','?')}   {r.get('provider','?'):<14} {r.get('status','?'):<10} {r.get('providerReference','') or ''}\")
    if r.get('failureReason'):
        print(f\"               reason: {r['failureReason']}\")
" || true

step "What it did to the books"
curl -sS "$LEDGER_URL/api/v1/ledger/entries?referenceId=$PAYMENT_ID" -H "X-Ops-Token: $OPS_TOKEN" 2>/dev/null \
  | "$PY" -c "
import sys, json
try:
    txns = json.load(sys.stdin)
except Exception:
    print('  (ledger unavailable)'); raise SystemExit
if not txns:
    print('  (nothing yet — the ledger consumes the same events and can lag a moment)')
for t in txns:
    print(f\"  {t.get('description','')}\")
    net = 0
    for line in t.get('lines', []):
        amount = line.get('amount', 0) or 0
        net += amount if line.get('direction') == 'DEBIT' else -amount
        print(f\"    {line.get('direction',''):<7} {amount:>10}   account {str(line.get('accountId',''))[:8]}\")
    print(f\"    {'net':<7} {net:>10}   <- anything else is a transaction the database itself refuses\")
" || true

printf "\n\033[1mDone.\033[0m  %s\n\n" "$STATUS"
echo "  Dashboard   http://localhost:5173"
echo "  Grafana     http://localhost:3000"
echo "  Payment     $PAYMENT_ID"
echo
echo "  Try it again with the same Idempotency-Key and you get this same payment back, not a second one."
echo "  Try AMOUNT=60000000 and screening refuses it outright with 422."
echo "  Try 'docker pause openpay-mock-bank-a' first and it still succeeds, on the other acquirer."

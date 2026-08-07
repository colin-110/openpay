#!/usr/bin/env bash
# An acquirer dies mid-demo, and the payment goes through anyway.
#
# This is the one claim in the README that cannot be shown by looking at a working system, because
# a working system is exactly what it looks like when it succeeds. The only way to see it is to
# break something first — so this stops an acquirer, takes a real payment while it is down, and
# prints the attempt list showing which bank refused and which one took it.
#
# Written to be run in front of someone. It restores the acquirer on the way out, including when
# it fails or is interrupted, because a demo script that leaves the platform in a worse state than
# it found it is a demo script nobody runs twice.
set -uo pipefail

cd "$(dirname "$0")/.."

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
SHOP="${SHOP_URL:-http://localhost:8090}"
VICTIM="${ACQUIRER:-mock-bank-a}"
JSON="Content-Type: application/json"

COMPOSE=(docker compose
    -f platform/docker/docker-compose.yml
    -f platform/docker/docker-compose.apps.yml)

json() { python -c "import sys,json; d=json.load(sys.stdin); print(d$1)" 2>/dev/null; }

# Restored however this exits. The trap is set before the acquirer is stopped, not after, so a
# failure in between cannot leave it down.
restore() {
    echo
    echo "Restoring $VICTIM..."
    "${COMPOSE[@]}" start "$VICTIM" >/dev/null 2>&1
    echo "Done. Both acquirers are back in rotation."
}

# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /demo/... into a Windows path.
CREDS=$(MSYS_NO_PATHCONV=1 docker exec openpay-demo-storefront cat /demo/demo.properties 2>/dev/null | tr -d '\r')
SECRET_KEY=$(echo "$CREDS" | sed -n 's/^storefront\.api-key=//p')
PUB_KEY=$(echo "$CREDS" | sed -n 's/^storefront\.publishable-key=//p')
if [ -z "$SECRET_KEY" ] || [ -z "$PUB_KEY" ]; then
    echo "No provisioned credentials found. Start the shop first: ./scripts/demo.sh" >&2
    exit 1
fi

echo "──────────────────────────────────────────────────────────────────────"
echo " Taking $VICTIM out of service, then paying anyway."
echo "──────────────────────────────────────────────────────────────────────"
echo
echo "Stopping $VICTIM..."
"${COMPOSE[@]}" stop "$VICTIM" >/dev/null 2>&1
trap restore EXIT INT TERM
echo "$VICTIM is down. One acquirer left."
echo

# Tokenised first, exactly as the checkout page does it — the card goes to the vault and comes back
# as a single-use token, and the shop never sees a number. Failover happens after this, at routing.
TOKEN=$(curl -sS -X POST "$GATEWAY/api/v1/tokens" -H "X-Api-Key: $PUB_KEY" -H "$JSON" \
    -d '{"type":"card","number":"4242 4242 4242 4242","expMonth":12,"expYear":2030,"securityCode":"123"}' \
    | json "['token']")
if [ -z "$TOKEN" ]; then
    echo "Could not tokenise a card — is the platform up?" >&2
    exit 1
fi
echo "Card tokenised: ${TOKEN:0:16}..."

PAY=$(curl -sS -X POST "$SHOP/api/checkout" -H "$JSON" \
    -d "{\"amount\":24000,\"currency\":\"INR\",\"token\":\"$TOKEN\"}")
PAYMENT_ID=$(echo "$PAY" | json "['payment']['id']")
if [ -z "$PAYMENT_ID" ]; then
    echo "The shop refused the payment: $PAY" >&2
    exit 1
fi
echo "Payment created: $PAYMENT_ID"
echo

# Polled rather than slept: capture is asynchronous — screening, routing, the acquirer's callback
# and its verification all happen between creation and CAPTURED — and a fixed sleep is either
# longer than the demo needs or shorter than a loaded machine takes.
echo -n "Waiting for capture "
STATUS=""
for _ in $(seq 1 30); do
    STATUS=$(curl -sS "$GATEWAY/api/v1/payments/$PAYMENT_ID" -H "X-Api-Key: $SECRET_KEY" | json "['status']")
    [ "$STATUS" = "CAPTURED" ] && break
    echo -n "."
    sleep 1
done
echo
echo

if [ "$STATUS" = "CAPTURED" ]; then
    echo "  STATUS: $STATUS — with $VICTIM down for the whole payment."
else
    echo "  STATUS: $STATUS — expected CAPTURED. Something is wrong; see the attempts below." >&2
fi
echo

# The point of the whole script. One row per acquirer tried, in order, with the one that refused
# recorded rather than tidied away — a payment platform that forgets which bank failed cannot
# reconcile, and cannot tell a merchant why.
echo "  Attempts:"
curl -sS "$GATEWAY/api/v1/payments/$PAYMENT_ID/attempts" -H "X-Api-Key: $SECRET_KEY" \
    | python -c "
import sys, json
try:
    attempts = json.load(sys.stdin)
except Exception:
    print('    (could not read the attempt list)'); sys.exit(0)
if isinstance(attempts, dict):
    attempts = attempts.get('attempts', attempts.get('content', []))
for a in attempts:
    ref = a.get('providerReference') or '-'
    print('    %-3s %-14s %-10s %s' % (a.get('attemptNo','?'), a.get('provider','?'), a.get('status','?'), ref))
"
echo
echo "  Open $PAYMENT_ID in the dashboard to see the same thing with the timeline."

#!/usr/bin/env bash
# End-to-end check of the tokenisation path against the running stack.
#
# Proves, in order: a publishable key can mint a token; it cannot do anything else; the token
# decides the payment method rather than the caller's claim; and a token cannot be spent twice.
set -uo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
AUTH="${AUTH_URL:-http://localhost:8081}"
SHOP="${SHOP_URL:-http://localhost:8090}"
ADMIN="X-Admin-Token: ${OPENPAY_ADMIN_TOKEN:-dev-admin-token}"
JSON="Content-Type: application/json"

json() { python -c "import sys,json; d=json.load(sys.stdin); print(d$1)" 2>/dev/null; }
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES+1)); }
FAILURES=0

# The merchant the running storefront already pays as.
# Where the shop's secret key actually lives, in order of preference.
#
# The environment first, for a deployment that passes its own. Then the running shop's provisioned
# credentials, which is the normal case since demo-provisioner started minting them — .env is
# deliberately blank there, and reading it would leave this script authenticating as nobody and
# reporting the platform broken when it is not. Then .env, for a shop configured by hand.
ENV_FILE="$(dirname "$0")/../platform/docker/.env"
SECRET_KEY="${STOREFRONT_API_KEY:-}"
if [ -z "$SECRET_KEY" ]; then
    # MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /demo/... into a Windows path before
    # docker ever sees it, which turns this into a confusing "no such file" about C:/Program Files.
    # Ignored everywhere else, so it costs nothing to set unconditionally.
    SECRET_KEY=$(MSYS_NO_PATHCONV=1 docker exec openpay-demo-storefront \
        cat /demo/demo.properties 2>/dev/null | sed -n 's/^storefront\.api-key=//p' | tr -d '\r')
fi
if [ -z "$SECRET_KEY" ] && [ -f "$ENV_FILE" ]; then
    SECRET_KEY=$(grep '^STOREFRONT_API_KEY=' "$ENV_FILE" | cut -d= -f2-)
fi
if [ -z "$SECRET_KEY" ]; then
    echo "No merchant secret key found. Start the shop (--profile shop) or set STOREFRONT_API_KEY." >&2
    exit 1
fi
# From the shop's own provisioned credentials, the same file the secret key came from, because
# demo-provisioner writes the merchant id alongside the keys it minted for it.
#
# The database is the fallback rather than the source, and that ordering is the fix for a real
# failure: the query below reads the merchant off the most recent payment, so on a stack that has
# never taken one it returns empty, every subsequent step authenticates as nobody, and the script
# reports seven failures against a platform that is working perfectly. Anyone running this straight
# after `up` — which is when a reader is most likely to try it — got that, and the reason was that
# the check needed the outcome it was there to verify.
MERCHANT_ID=$(MSYS_NO_PATHCONV=1 docker exec openpay-demo-storefront \
    cat /demo/demo.properties 2>/dev/null | sed -n 's/^storefront\.merchant-id=//p' | tr -d '\r')
if [ -z "$MERCHANT_ID" ]; then
    # For a shop configured by hand, where no provisioner ever wrote that file. Still needs a
    # payment to exist, and says so rather than failing seven checks that all mean this one thing.
    MERCHANT_ID=$(docker exec openpay-postgres psql -U openpay -d openpay_payment -t -A \
        -c "SELECT merchant_id FROM payments ORDER BY created_at DESC LIMIT 1;" | tr -d '\r\n')
fi
if [ -z "$MERCHANT_ID" ]; then
    echo "No merchant found: the shop has no provisioned credentials and no payment exists to infer one from." >&2
    exit 1
fi
echo "merchant $MERCHANT_ID"

echo
echo "1. Issue a publishable key"
PUB=$(curl -sS -X POST "$AUTH/api/v1/api-keys" -H "$ADMIN" -H "$JSON" \
  -d "{\"merchantId\":\"$MERCHANT_ID\",\"name\":\"verify-checkout\",\"scope\":\"tokens:create\",\"expiresAt\":null}" \
  | json "['apiKey']")
case "$PUB" in
  opk_pub_*) pass "publishable key has its own prefix: ${PUB%%.*}" ;;
  *)         fail "expected an opk_pub_ prefix, got ${PUB%%.*}" ;;
esac

echo
echo "2. Tokenise a card with it"
TOKEN_JSON=$(curl -sS -X POST "$GATEWAY/api/v1/tokens" -H "X-Api-Key: $PUB" -H "$JSON" \
  -d '{"type":"card","number":"4242 4242 4242 4242","expMonth":12,"expYear":2030,"securityCode":"123"}')
TOKEN=$(echo "$TOKEN_JSON" | json "['token']")
NETWORK=$(echo "$TOKEN_JSON" | json "['network']")
LAST4=$(echo "$TOKEN_JSON" | json "['last4']")
[ -n "$TOKEN" ] && pass "minted $NETWORK ****$LAST4 -> ${TOKEN:0:12}..." || fail "no token: $TOKEN_JSON"

echo
echo "3. That key must not be able to do anything else"
READ=$(curl -sS -o /dev/null -w "%{http_code}" "$GATEWAY/api/v1/payments" -H "X-Api-Key: $PUB")
[ "$READ" = "403" ] && pass "listing payments refused ($READ)" || fail "expected 403 listing payments, got $READ"
WRITE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "$GATEWAY/api/v1/payments" \
  -H "X-Api-Key: $PUB" -H "$JSON" -H "Idempotency-Key: verify-$RANDOM" \
  -d '{"amount":1000,"currency":"INR"}')
[ "$WRITE" = "403" ] && pass "creating a payment refused ($WRITE)" || fail "expected 403 creating a payment, got $WRITE"

echo
echo "4. A card number must never come back in an error"
BAD=$(curl -sS -X POST "$GATEWAY/api/v1/tokens" -H "X-Api-Key: $PUB" -H "$JSON" \
  -d '{"type":"card","number":"4242424242424241","expMonth":12,"expYear":2030,"securityCode":"123"}')
case "$BAD" in
  *4242*) fail "the response quoted the card number: $BAD" ;;
  *)      FIELD=$(echo "$BAD" | json "['field']")
          [ -n "$FIELD" ] && pass "refusal names the field ('$FIELD'), not the value"                           || fail "no field in the refusal: $BAD" ;;
esac

echo
echo "5. Pay with the token through the shop"
PAY=$(curl -sS -X POST "$SHOP/api/checkout" -H "$JSON" \
  -d "{\"amount\":24000,\"currency\":\"INR\",\"token\":\"$TOKEN\"}")
PAYMENT_ID=$(echo "$PAY" | json "['payment']['id']")
[ -n "$PAYMENT_ID" ] && pass "payment $PAYMENT_ID" || fail "no payment: $PAY"

sleep 6
DETAIL=$(curl -sS "$GATEWAY/api/v1/payments/$PAYMENT_ID" -H "X-Api-Key: $SECRET_KEY")
STATUS=$(echo "$DETAIL" | json "['status']")
PM_NETWORK=$(echo "$DETAIL" | json "['paymentMethod']['network']")
PM_LAST4=$(echo "$DETAIL" | json "['paymentMethod']['last4']")
echo "  status=$STATUS method=$PM_NETWORK ****$PM_LAST4"
[ "$PM_NETWORK" = "visa" ] && [ "$PM_LAST4" = "4242" ] \
  && pass "the payment carries the instrument that was actually tokenised" \
  || fail "expected visa/4242 on the payment, got $PM_NETWORK/$PM_LAST4"
[ "$STATUS" = "CAPTURED" ] && pass "reached CAPTURED" || fail "expected CAPTURED, got $STATUS"

echo
echo "6. The token must not be spendable twice"
REPLAY=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "$SHOP/api/checkout" -H "$JSON" \
  -d "{\"amount\":24000,\"currency\":\"INR\",\"token\":\"$TOKEN\"}")
[ "$REPLAY" = "422" ] && pass "replay refused ($REPLAY)" || fail "expected 422 on replay, got $REPLAY"

echo
echo "7. A merchant must not be able to lie about the instrument"
TOKEN2=$(curl -sS -X POST "$GATEWAY/api/v1/tokens" -H "X-Api-Key: $PUB" -H "$JSON" \
  -d '{"type":"card","number":"378282246310005","expMonth":12,"expYear":2030,"securityCode":"1234"}' | json "['token']")
LIE=$(curl -sS -X POST "$GATEWAY/api/v1/payments" -H "X-Api-Key: $SECRET_KEY" -H "$JSON" \
  -H "Idempotency-Key: verify-lie-$RANDOM" \
  -d "{\"amount\":24000,\"currency\":\"INR\",\"paymentMethod\":{\"type\":\"card\",\"network\":\"visa\",\"last4\":\"9999\",\"token\":\"$TOKEN2\"}}")
LIE_NETWORK=$(echo "$LIE" | json "['paymentMethod']['network']")
LIE_LAST4=$(echo "$LIE" | json "['paymentMethod']['last4']")
[ "$LIE_NETWORK" = "amex" ] && [ "$LIE_LAST4" = "0005" ] \
  && pass "claimed visa/9999, recorded $LIE_NETWORK/$LIE_LAST4 from the token" \
  || fail "expected the token to win, got $LIE_NETWORK/$LIE_LAST4"

echo
[ "$FAILURES" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$FAILURES CHECK(S) FAILED"
exit "$FAILURES"

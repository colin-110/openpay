// Shared setup for the load tests: onboard a throwaway merchant and issue it a key.
//
// Done in k6's setup() rather than by hand before a run, so a test is one command and the
// credentials it uses cannot be stale. Every run gets its own merchant, which matters more than it
// looks: the fraud service counts payment velocity per merchant, and reusing one across runs means
// the second run starts inside a window the first one filled.

import http from 'k6/http';
import { fail } from 'k6';

export const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
export const AUTH = __ENV.AUTH_URL || 'http://localhost:8081';
export const MERCHANT = __ENV.MERCHANT_URL || 'http://localhost:8082';
export const WEBHOOK = __ENV.WEBHOOK_URL || 'http://localhost:8084';
export const ROUTER = __ENV.ROUTER_URL || 'http://localhost:8085';
export const FRAUD = __ENV.FRAUD_URL || 'http://localhost:8089';

export const ADMIN_TOKEN = __ENV.OPENPAY_ADMIN_TOKEN;
export const OPS_TOKEN = __ENV.OPENPAY_OPS_TOKEN || 'dev-ops-token';
export const INTERNAL_TOKEN = __ENV.OPENPAY_INTERNAL_TOKEN || 'dev-internal-token';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * Creates a merchant and an API key for it.
 *
 * Fails the whole run rather than limping on. A load test that silently ran with no credential
 * would report a beautiful 401 latency curve, which is worse than no number at all.
 */
export function provisionMerchant(label) {
  if (!ADMIN_TOKEN) {
    fail('OPENPAY_ADMIN_TOKEN is not set. Admin endpoints fail closed without it, so this run would measure 401s.');
  }

  const suffix = `${label}-${Date.now()}`;
  const merchantResponse = http.post(
    `${MERCHANT}/api/v1/merchants`,
    JSON.stringify({
      merchantCode: suffix,
      legalName: `k6 ${label}`,
      webhookUrl: null,
      defaultCurrency: 'INR',
    }),
    { headers: { ...JSON_HEADERS, 'X-Admin-Token': ADMIN_TOKEN } }
  );

  if (merchantResponse.status !== 201) {
    fail(`could not onboard a merchant (${merchantResponse.status}): ${merchantResponse.body}`);
  }
  const merchantId = merchantResponse.json('id');

  const keyResponse = http.post(
    `${AUTH}/api/v1/api-keys`,
    JSON.stringify({
      merchantId,
      name: suffix,
      scope: 'payments:write',
      expiresAt: null,
    }),
    { headers: { ...JSON_HEADERS, 'X-Admin-Token': ADMIN_TOKEN } }
  );

  if (keyResponse.status !== 201) {
    fail(`could not issue an API key (${keyResponse.status}): ${keyResponse.body}`);
  }

  return { merchantId, apiKey: keyResponse.json('apiKey') };
}

/**
 * The seeded `merchant-velocity-burst` rule: 100 payments per merchant per 60 seconds gets the
 * next one held for review. Mirrored from V1__fraud.sql rather than read at runtime, because a
 * load test that silently adapted to whatever the rules happen to say would stop being a fixed
 * measurement.
 */
const VELOCITY_LIMIT_PER_MINUTE = 100;

/**
 * How many merchants a run at this rate needs so that none of them trips the velocity rule.
 *
 * Every scenario used to drive a single merchant, and the consequence was not subtle: at 20/s a
 * merchant crosses 100-per-minute about five seconds in, and *every payment after that* is held
 * for review. A held payment deliberately skips the PAYMENT_CREATED publish, so it never routes,
 * never reaches an acquirer and never touches the ledger — and it returns 201, so the run reports
 * as a clean pass. A measured stress run found 17,017 payments held against 1,508 created: 92% of
 * the load was exercising the review path while claiming to measure the write path.
 *
 * Real traffic spreads across thousands of merchants and does not do this, so the fix is to look
 * like real traffic rather than to switch the rule off. The 1.4 is headroom for arrival jitter:
 * k6 distributes iterations across the pool evenly on average, not exactly.
 */
export function merchantsForRate(rate) {
  const minimum = Math.ceil((rate * 60) / VELOCITY_LIMIT_PER_MINUTE);
  return Math.max(4, Math.ceil(minimum * 1.4));
}

/**
 * Onboards a pool of merchants, each with its own key.
 *
 * Sequential rather than batched: this runs once in setup() and a few hundred fast local calls
 * cost a couple of seconds, which is not worth the complexity of doing it concurrently. Scenarios
 * that provision a large pool should raise `setupTimeout` accordingly.
 */
export function provisionMerchants(label, count) {
  const merchants = [];
  for (let i = 0; i < count; i++) {
    merchants.push(provisionMerchant(`${label}-${i}`));
  }
  return merchants;
}

/**
 * Picks a merchant for this iteration.
 *
 * Keyed on the iteration counter rather than at random, so the spread across the pool is even by
 * construction instead of even on average — random assignment would leave some merchants
 * meaningfully hotter than others over a short run, which is exactly the condition the pool
 * exists to avoid.
 */
export function merchantFor(merchants, iteration) {
  return merchants[iteration % merchants.length];
}

export function merchantHeaders(apiKey, idempotencyKey) {
  return {
    headers: {
      ...JSON_HEADERS,
      'X-Api-Key': apiKey,
      'Idempotency-Key': idempotencyKey,
    },
  };
}

export function opsHeaders() {
  return { headers: { ...JSON_HEADERS, 'X-Ops-Token': OPS_TOKEN } };
}

export function adminHeaders() {
  return { headers: { ...JSON_HEADERS, 'X-Admin-Token': ADMIN_TOKEN } };
}

/**
 * An amount that stays under the seeded risk thresholds.
 *
 * The default rules hold anything over 50,000 rupees for review and block anything over 500,000.
 * A load test that wandered over either would be measuring the refusal path, and the numbers would
 * look excellent for entirely the wrong reason.
 *
 * The spread is wide for a second reason: `repeated-identical-amount` BLOCKs after ten identical
 * amounts from one merchant in five minutes, which is the card-testing signature. A narrow range
 * would start colliding at high volume and refuse payments for a reason that has nothing to do
 * with the thing being measured.
 */
export function safeAmount() {
  return 10000 + Math.floor(Math.random() * 4000000);
}

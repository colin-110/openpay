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
 */
export function safeAmount() {
  return 10000 + Math.floor(Math.random() * 100000);
}

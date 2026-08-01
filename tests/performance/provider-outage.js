// Resilience, not throughput: an acquirer fails in the middle of a run.
//
// The claim this platform makes is that losing one acquirer costs latency, not payments. That is
// only worth believing if it has been watched happening, so this test takes mock-bank-a out of the
// routing table partway through a steady load and asserts that payments keep completing.
//
//   k6 run tests/performance/provider-outage.js
//
// It restores the rule in teardown, including after a failed run, because a test that leaves an
// acquirer disabled has broken the environment for whoever runs next.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import {
  GATEWAY,
  ROUTER,
  provisionMerchant,
  merchantHeaders,
  adminHeaders,
  safeAmount,
} from './lib/setup.js';

const OUTAGE_AT = Number(__ENV.OUTAGE_AT_SECONDS || 40);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 120);

const accepted = new Counter('payments_accepted');
const acceptedDuringOutage = new Counter('payments_accepted_during_outage');
const acceptanceRate = new Rate('payment_acceptance_rate');

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: 40,
      maxVUs: 200,
    },
  },
  thresholds: {
    // The whole claim, as a number. Losing an acquirer must not cost a single accepted payment:
    // creation does not touch an acquirer at all, and routing happens afterwards, asynchronously.
    payment_acceptance_rate: ['rate>0.999'],
    // Latency is allowed to move. Failing over means one acquirer is tried, times out, and the
    // next is tried — that costs time, and pretending otherwise would be the wrong assertion.
    'http_req_duration{name:create}': ['p(95)<1500'],
  },
};

export function setup() {
  const merchant = provisionMerchant('outage');

  const rules = http.get(`${ROUTER}/internal/routing-rules`, adminHeaders());
  if (rules.status !== 200) {
    throw new Error(`could not read the routing table (${rules.status}): ${rules.body}`);
  }

  const target = rules.json().find((rule) => rule.providerName === 'mock-bank-a' && rule.enabled);
  if (!target) {
    throw new Error('no enabled rule for mock-bank-a; nothing to take out of rotation');
  }

  return { ...merchant, ruleId: target.id, startedAt: Date.now() };
}

export default function (data) {
  const elapsedSeconds = (Date.now() - data.startedAt) / 1000;

  // One VU pulls the acquirer, once, at the appointed moment. Doing it from the default function
  // rather than a separate scenario keeps the outage inside the measured window instead of in a
  // setup step that finishes before load starts.
  if (__VU === 1 && __ITER === 0 && elapsedSeconds < OUTAGE_AT) {
    sleep(OUTAGE_AT - elapsedSeconds);
    const disabled = http.post(
      `${ROUTER}/internal/routing-rules/${data.ruleId}/disable`,
      null,
      adminHeaders()
    );
    check(disabled, { 'acquirer taken out of rotation': (r) => r.status === 200 });
  }

  const response = http.post(
    `${GATEWAY}/api/v1/payments`,
    JSON.stringify({ amount: safeAmount(), currency: 'INR' }),
    {
      ...merchantHeaders(data.apiKey, `k6-outage-${__VU}-${__ITER}-${Date.now()}`),
      tags: { name: 'create' },
    }
  );

  const ok = response.status === 201;
  acceptanceRate.add(ok);
  if (ok) {
    accepted.add(1);
    if ((Date.now() - data.startedAt) / 1000 > OUTAGE_AT) {
      acceptedDuringOutage.add(1);
    }
  }

  check(response, { 'payment accepted despite the outage': () => ok });
}

export function teardown(data) {
  // Always, including after a failed run. Leaving an acquirer disabled would break the environment
  // for whoever runs next, and they would have no reason to suspect this test.
  const restored = http.post(
    `${ROUTER}/internal/routing-rules/${data.ruleId}/enable`,
    null,
    adminHeaders()
  );
  if (restored.status !== 200) {
    console.error(
      `FAILED TO RESTORE mock-bank-a (rule ${data.ruleId}, status ${restored.status}). ` +
        `Re-enable it before running anything else.`
    );
  }
}

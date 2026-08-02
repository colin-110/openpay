// Sustained load on payment creation.
//
// This is the write path that matters: authenticate, screen for risk, persist, and append to the
// outbox — all in one transaction. It is deliberately the whole path through the gateway rather
// than a direct call to payment-service, because the gateway's key validation is a network hop
// that a direct test would hide.
//
//   k6 run tests/performance/payment-create.js
//   k6 run -e RATE=200 -e DURATION=5m tests/performance/payment-create.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  GATEWAY,
  provisionMerchant,
  merchantHeaders,
  safeAmount,
} from './lib/setup.js';

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '2m';

// Separate from the built-in http_req_duration, which would mix these in with the setup calls.
const createDuration = new Trend('payment_create_duration', true);
const created = new Counter('payments_created');
const held = new Counter('payments_held_for_review');

export const options = {
  scenarios: {
    steady: {
      // Arrival rate, not virtual users. A VU-based test slows down when the system does, which
      // hides the thing being measured: real merchants keep sending at their own pace whether or
      // not the platform is keeping up.
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Math.max(100, RATE * 4),
    },
  },
  // p(99) is not in k6's default set. p(95) can sit flat while the worst 1% of requests are
  // already past the point a customer would abandon, so it gets reported alongside.
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // A payment that takes over a second to accept is a customer watching a spinner. p95 rather
    // than an average, because the average hides exactly the tail they experience.
    //
    // Not `payment_create_duration{expected_response:true}`, which is what this used to say:
    // expected_response is an HTTP system tag that k6 only attaches to http_req_* metrics, never
    // to a custom Trend. That submetric therefore matched zero samples, reported as 0s, and
    // passed `p(95)<1000` trivially on every run — a threshold that could not fail, which is
    // worse than no threshold at all because it reads like coverage.
    payment_create_duration: ['p(95)<1000'],
    // Not zero. A load test that trips the platform's own rate limiter is measuring the limiter,
    // and the point here is the write path — but a real error rate above 1% is a failure.
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return provisionMerchant('load');
}

export default function (data) {
  const idempotencyKey = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${GATEWAY}/api/v1/payments`,
    JSON.stringify({ amount: safeAmount(), currency: 'INR' }),
    { ...merchantHeaders(data.apiKey, idempotencyKey), tags: { name: 'POST /api/v1/payments' } }
  );

  createDuration.add(response.timings.duration);

  const ok = check(response, {
    'payment accepted': (r) => r.status === 201,
    'payment id returned': (r) => r.status === 201 && !!r.json('id'),
  });

  if (ok) {
    created.add(1);
    // Worth counting separately. A run where screening starts holding payments is not a slower
    // run — it is a different one, and averaging the two together would be meaningless.
    if (response.json('fraudStatus') === 'HELD') {
      held.add(1);
    }
  }
}

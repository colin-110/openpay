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
import { scenario } from 'k6/execution';
import {
  GATEWAY,
  provisionMerchants,
  merchantsForRate,
  merchantFor,
  merchantHeaders,
  safeAmount,
} from './lib/setup.js';

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '2m';
// Warm-up, for the same reason stress.js has one and discovered the hard way that this script
// needed it too: a run started against a just-restarted stack reported p50 825ms and p95 9.86s at
// a rate that measures 25ms warm. JIT compilation and connection-pool growth are paid by whoever
// arrives first, and with no warm-up that is the measurement itself. Set -e WARMUP=0 to measure
// the cold path deliberately.
const WARMUP = __ENV.WARMUP || '30s';
const WARMING = WARMUP !== '0';

// Separate from the built-in http_req_duration, which would mix these in with the setup calls.
const createDuration = new Trend('payment_create_duration', true);
const created = new Counter('payments_created');
const held = new Counter('payments_held_for_review');

export const options = {
  scenarios: {
    ...(WARMING
      ? {
          warmup: {
            executor: 'ramping-arrival-rate',
            exec: 'warmup',
            startRate: 5,
            timeUnit: '1s',
            stages: [{ target: RATE, duration: WARMUP }],
            preAllocatedVUs: Math.max(20, RATE),
            maxVUs: Math.max(100, RATE * 4),
          },
        }
      : {}),
    steady: {
      // Arrival rate, not virtual users. A VU-based test slows down when the system does, which
      // hides the thing being measured: real merchants keep sending at their own pace whether or
      // not the platform is keeping up.
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // Ten seconds after warm-up ends, so in-flight requests from it drain rather than being
      // counted against the measured window.
      startTime: WARMING ? `${parseSeconds(WARMUP) + 10}s` : '0s',
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Math.max(100, RATE * 4),
    },
  },
  // Provisioning scales with the rate now, and a high-rate run onboards a few hundred merchants
  // before it starts. Still seconds, but more than k6's 60s default allows for comfortably.
  setupTimeout: '10m',
  // p(99) is not in k6's default set. p(95) can sit flat while the worst 1% of requests are
  // already past the point a customer would abandon, so it gets reported alongside.
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // The guard on the finding this whole merchant pool exists to fix. Held payments never route,
    // never settle and never post to the ledger — but they return 201, so without this a run that
    // held every single payment would report as a flawless pass. If the pool is ever too small for
    // the rate, or a rule changes, this fails the run instead of quietly measuring the wrong path.
    payments_held_for_review: ['count<1'],
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
    //
    // Scoped to the tag rather than left global, because the global form also counts the warm-up
    // stage and the few hundred provisioning calls setup() makes. Neither belongs in the number
    // that decides whether the measured window was healthy.
    'http_req_failed{name:POST /api/v1/payments}': ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

function parseSeconds(duration) {
  const match = /^(\d+)(s|m)$/.exec(duration);
  if (!match) throw new Error(`Unrecognised duration: ${duration}`);
  const [, value, unit] = match;
  return unit === 'm' ? Number(value) * 60 : Number(value);
}

export function setup() {
  return { merchants: provisionMerchants('load', merchantsForRate(RATE)) };
}

/**
 * Drives real load through the real path and records none of it.
 *
 * Held payments are still counted, deliberately — the pool being too small is a fact about the run
 * as a whole, not about the measured window, and it should fail the run wherever it shows up.
 */
export function warmup(data) {
  const merchant = merchantFor(data.merchants, scenario.iterationInTest);
  const response = http.post(
    `${GATEWAY}/api/v1/payments`,
    JSON.stringify({ amount: safeAmount(), currency: 'INR' }),
    { ...merchantHeaders(merchant.apiKey, `k6-warm-${__VU}-${__ITER}-${Date.now()}`),
      tags: { name: 'warmup' } }
  );
  if (response.status === 201 && response.json('fraudStatus') === 'HELD') {
    held.add(1);
  }
}

export default function (data) {
  // iterationInTest, not __ITER: __ITER counts within one VU, so every VU's first iteration would
  // pick the same merchant and the pool would not spread at all under an arrival-rate executor.
  const merchant = merchantFor(data.merchants, scenario.iterationInTest);
  const idempotencyKey = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${GATEWAY}/api/v1/payments`,
    JSON.stringify({ amount: safeAmount(), currency: 'INR' }),
    { ...merchantHeaders(merchant.apiKey, idempotencyKey), tags: { name: 'POST /api/v1/payments' } }
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

// Sustained load held for a long time, at a rate already known to be clean — the point is not to
// find a breaking rate (that's stress.js), it's to catch the failure modes a short run can't see
// at all: an outbox relay that keeps up minute to minute but slowly falls behind, a connection
// pool that leaks one connection per thousand requests, latency that creeps rather than jumps.
// None of payment-create.js's 2 minutes would show any of that; this runs long enough that a slow
// leak has time to become visible in-run, not just after the fact.
//
//   k6 run tests/performance/soak.js
//   k6 run -e RATE=20 -e DURATION=20m tests/performance/soak.js
//
// What this script measures is latency and error rate *within the run*, split first-half vs
// second-half so drift shows up in the summary without needing an external time-series backend.
// What it cannot measure from inside k6 — the outbox backlog and the database connection pool —
// is exactly what tests/performance/baseline.md's Observations section already calls out as
// invisible in a k6 summary. Check those after this finishes:
//
//   curl -s 'http://localhost:9090/api/v1/query?query=openpay_outbox_unpublished' | jq
//   curl -s 'http://localhost:9090/api/v1/query?query=hikaricp_connections_active' | jq
//
// A backlog or a connection count that is higher after this run than before it is the soak test
// finding something a shorter one would have missed entirely.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  GATEWAY,
  provisionMerchants,
  merchantsForRate,
  merchantFor,
  merchantHeaders,
  safeAmount,
} from './lib/setup.js';
import { scenario } from 'k6/execution';

const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '15m';

const createDuration = new Trend('payment_create_duration', true);
const durationFirstHalf = new Trend('payment_create_duration_first_half', true);
const durationSecondHalf = new Trend('payment_create_duration_second_half', true);
const failedFirstHalf = new Counter('failed_first_half');
const failedSecondHalf = new Counter('failed_second_half');
const heldForReview = new Counter('payments_held_for_review');

const WARMUP = __ENV.WARMUP || '30s';
const WARMING = WARMUP !== '0';

// The halves are measured from when the soak scenario actually starts, not from process start —
// otherwise the warm-up and its drain gap would be counted into the first half and shift the
// midpoint, putting some genuinely-first-half samples in the second bucket.
const soakStartOffsetMs = WARMING ? parseDurationMs(WARMUP) + 10_000 : 0;
const testStartMs = Date.now() + soakStartOffsetMs;
const durationMs = parseDurationMs(DURATION);
const halfwayMs = testStartMs + durationMs / 2;

function parseDurationMs(duration) {
  const match = /^(\d+)(s|m|h)$/.exec(duration);
  if (!match) throw new Error(`Unrecognised duration: ${duration}`);
  const [, value, unit] = match;
  const multiplier = { s: 1000, m: 60_000, h: 3_600_000 }[unit];
  return Number(value) * multiplier;
}

export const options = {
  scenarios: {
    // Warm-up matters more here than anywhere else, and in the opposite direction to the obvious
    // one. This test's whole finding is "is the second half worse than the first?" — and a cold
    // start makes the *first* half artificially slow, which does not produce a false alarm, it
    // produces a false all-clear: real drift would have to exceed the warm-up penalty before the
    // comparison noticed it. Set -e WARMUP=0 to disable.
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
    soak: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: WARMING ? `${parseDurationMs(WARMUP) / 1000 + 10}s` : '0s',
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Math.max(100, RATE * 4),
    },
  },
  setupTimeout: '10m',
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // A soak is where this mattered most: over a long run against one merchant, essentially every
    // payment after the first minute was held for review, so the drift this test exists to detect
    // was being measured on a path that does almost none of the work.
    payments_held_for_review: ['count<1'],
    // Same bar as payment-create.js's clean-rate result — this rate has already been measured
    // healthy over 2 minutes; a soak failing this bar means it degrades over time, not that the
    // rate itself was ever in question.
    //
    // Untagged, for the reason spelled out in payment-create.js: expected_response never lands on
    // a custom Trend, so the tagged form matched nothing and passed unconditionally.
    payment_create_duration: ['p(95)<1000'],
    // Tagged, so the warm-up stage and setup()'s provisioning calls stay out of the number that
    // decides whether the measured window was healthy.
    'http_req_failed{name:POST /api/v1/payments}': ['rate<0.01'],
    // The one threshold specific to a soak: the second half must not be meaningfully worse than
    // the first. p(95) alone would not catch "still under 1000ms but trending toward it."
    payment_create_duration_second_half: ['p(95)<1200'],
  },
};

export function setup() {
  return { merchants: provisionMerchants('soak', merchantsForRate(RATE)) };
}

/** Real load through the real path, recorded nowhere except the held-payment guard. */
export function warmup(data) {
  const merchant = merchantFor(data.merchants, scenario.iterationInTest);
  const response = http.post(
    `${GATEWAY}/api/v1/payments`,
    JSON.stringify({ amount: safeAmount(), currency: 'INR' }),
    { ...merchantHeaders(merchant.apiKey, `k6-soak-warm-${__VU}-${__ITER}-${Date.now()}`),
      tags: { name: 'warmup' } }
  );
  if (response.status === 201 && response.json('fraudStatus') === 'HELD') {
    heldForReview.add(1);
  }
}

export default function (data) {
  const merchant = merchantFor(data.merchants, scenario.iterationInTest);
  const idempotencyKey = `k6-soak-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${GATEWAY}/api/v1/payments`,
    JSON.stringify({ amount: safeAmount(), currency: 'INR' }),
    { ...merchantHeaders(merchant.apiKey, idempotencyKey), tags: { name: 'POST /api/v1/payments' } }
  );

  createDuration.add(response.timings.duration);

  const ok = check(response, { 'payment accepted': (r) => r.status === 201 });
  if (ok && response.json('fraudStatus') === 'HELD') {
    heldForReview.add(1);
  }

  const inSecondHalf = Date.now() >= halfwayMs;
  if (inSecondHalf) {
    durationSecondHalf.add(response.timings.duration);
    if (!ok) failedSecondHalf.add(1);
  } else {
    durationFirstHalf.add(response.timings.duration);
    if (!ok) failedFirstHalf.add(1);
  }
}

export function handleSummary(data) {
  const p95First = data.metrics.payment_create_duration_first_half?.values?.['p(95)'];
  const p95Second = data.metrics.payment_create_duration_second_half?.values?.['p(95)'];
  const failedFirst = data.metrics.failed_first_half?.values?.count ?? 0;
  const failedSecond = data.metrics.failed_second_half?.values?.count ?? 0;

  const lines = [
    '',
    'Soak test — first half vs second half',
    '='.repeat(40),
    `p(95) latency   first half: ${p95First !== undefined ? p95First.toFixed(0) + 'ms' : 'n/a'}   second half: ${p95Second !== undefined ? p95Second.toFixed(0) + 'ms' : 'n/a'}`,
    `failures        first half: ${failedFirst}   second half: ${failedSecond}`,
    '',
    p95Second !== undefined && p95First !== undefined && p95Second > p95First * 1.2
      ? '⚠ second half is >20% slower than the first — something is degrading over the run.'
      : '✓ no meaningful drift between the first and second half.',
    '',
    'Check the outbox backlog and connection pool now — see the comment at the top of this file.',
    '',
  ];

  return { stdout: lines.join('\n') };
}

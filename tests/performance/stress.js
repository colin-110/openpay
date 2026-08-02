// Finds where the write path actually breaks, rather than confirming a rate we already know is
// fine. payment-create.js answers "is 50/s healthy?" — this answers "what rate stops being
// healthy, and what does it look like when it does?"
//
// Distinct scenarios rather than one ramping executor, on purpose: each rate tier gets its own
// name (via k6/execution's `scenario.name`, tagged onto every request), which is what makes the
// end-of-run summary show a clean before/after instead of one blended average that hides exactly
// the transition this test exists to find. Stages run back to back via startTime, each for long
// enough to be a real read rather than a burst artifact.
//
//   k6 run tests/performance/stress.js
//   k6 run -e STAGE_DURATION=1m tests/performance/stress.js   # slower, steadier read per stage
//
// The platform's own per-merchant write rate limit (30 requests / 5s, ~6/s) is far below every
// tier above the first here on purpose — this test provisions a merchant and, like
// payment-create.js, is measuring the write path itself, not the limiter in front of it. Raise
// it for the run:
//
//   RATE_LIMIT_PER_WINDOW=10000 docker compose -f platform/docker/docker-compose.yml \
//     -f platform/docker/docker-compose.apps.yml up -d gateway-service
//
// and put it back (unset the override, `up -d gateway-service` again) once done. A stress test
// that trips the rate limiter first is a test of the rate limiter, not of the rate the write path
// actually falls over at.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { scenario } from 'k6/execution';
import { GATEWAY, provisionMerchant, merchantHeaders, safeAmount } from './lib/setup.js';

const STAGE_DURATION = __ENV.STAGE_DURATION || '45s';
// The tiers themselves: the first two are already-known-good/known-bad numbers from
// tests/performance/baseline.md, so a rerun on the same machine has something to anchor against;
// the rest climb until whoever is reading the summary can see exactly where it gave way.
const TIERS = (__ENV.TIERS || '20,50,100,150,250')
  .split(',')
  .map((value) => Number(value.trim()));

const createDuration = new Trend('payment_create_duration', true);
const acceptedByStage = {};
const failedByStage = {};
for (const rate of TIERS) {
  acceptedByStage[rate] = new Counter(`accepted_at_${rate}`);
  failedByStage[rate] = new Counter(`failed_at_${rate}`);
}
const overallFailureRate = new Rate('stress_failure_rate');

function buildScenarios() {
  const scenarios = {};
  let offsetSeconds = 0;
  const durationSeconds = parseDurationSeconds(STAGE_DURATION);

  for (const rate of TIERS) {
    scenarios[`rate_${rate}`] = {
      executor: 'constant-arrival-rate',
      exec: 'attempt',
      rate,
      timeUnit: '1s',
      duration: STAGE_DURATION,
      startTime: `${offsetSeconds}s`,
      preAllocatedVUs: Math.max(20, rate),
      maxVUs: Math.max(150, rate * 4),
      // Tag every metric this scenario produces with its own rate, so the summary breaks down
      // by tier without needing an external time-series backend to slice it after the fact.
      tags: { stage: `rate_${rate}` },
    };
    // A gap between tiers, not back-to-back-to-back: lets in-flight requests from one tier
    // drain before the next starts, so a slow tail from tier N doesn't get miscounted against
    // tier N+1.
    offsetSeconds += durationSeconds + 10;
  }
  return scenarios;
}

function parseDurationSeconds(duration) {
  const match = /^(\d+)(s|m)$/.exec(duration);
  if (!match) throw new Error(`Unrecognised duration: ${duration}`);
  const [, value, unit] = match;
  return unit === 'm' ? Number(value) * 60 : Number(value);
}

export const options = {
  scenarios: buildScenarios(),
  // No thresholds that abort the run: the entire point is to keep going past the point where
  // things start failing, so a threshold breach should be information in the summary, not an
  // early exit that throws away the tiers after it.
};

export function setup() {
  return provisionMerchant('stress');
}

export function attempt(data) {
  const idempotencyKey = `k6-stress-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${GATEWAY}/api/v1/payments`,
    JSON.stringify({ amount: safeAmount(), currency: 'INR' }),
    {
      ...merchantHeaders(data.apiKey, idempotencyKey),
      tags: { name: 'POST /api/v1/payments', stage: scenario.name },
    }
  );

  createDuration.add(response.timings.duration, { stage: scenario.name });

  const ok = check(
    response,
    { 'payment accepted': (r) => r.status === 201 },
    { stage: scenario.name }
  );
  overallFailureRate.add(!ok);

  const tierKey = scenario.name.replace('rate_', '');
  if (acceptedByStage[tierKey]) {
    (ok ? acceptedByStage[tierKey] : failedByStage[tierKey]).add(1);
  }
}

export function handleSummary(data) {
  // The default k6 summary blends every scenario into one set of numbers. This appends a
  // stage-by-stage table, because "where did it stop being linear" is a question about the
  // transition between tiers, and that's exactly what gets lost in a blended average.
  const lines = ['', 'Stress test — by rate tier', '='.repeat(40)];
  for (const rate of TIERS) {
    const accepted = data.metrics[`accepted_at_${rate}`]?.values?.count ?? 0;
    const failed = data.metrics[`failed_at_${rate}`]?.values?.count ?? 0;
    const total = accepted + failed;
    const failurePct = total === 0 ? 0 : ((failed / total) * 100).toFixed(1);
    lines.push(`${String(rate).padStart(4)}/s  →  ${total} sent, ${failurePct}% failed`);
  }
  lines.push('');

  return {
    stdout: lines.join('\n') + '\n\n' + textSummaryFallback(data),
  };
}

// k6's own default text summary isn't importable in every k6 version, so this is a minimal
// stand-in that still prints the standard aggregate metrics beneath the per-tier table above.
function textSummaryFallback(data) {
  const httpFailed = data.metrics.http_req_failed?.values?.rate;
  const duration95 = data.metrics.payment_create_duration?.values?.['p(95)'];
  return [
    'Aggregate (all tiers blended — see the table above for what this hides):',
    `  http_req_failed: ${httpFailed !== undefined ? (httpFailed * 100).toFixed(2) + '%' : 'n/a'}`,
    `  payment_create_duration p(95): ${duration95 !== undefined ? duration95.toFixed(0) + 'ms' : 'n/a'}`,
  ].join('\n');
}

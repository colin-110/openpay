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
//
// The top tier is 150 rather than the 250+ it started as, and that is a measured decision rather
// than timidity. Past ~150/s on a single laptop host the write path's latency exceeds the arrival
// interval, so the arrival-rate executor allocates VUs faster than they retire; an earlier run
// with tiers up to 600 pushed k6 past a thousand concurrent VUs and wedged the Docker daemon
// itself, which produces no data at all. Raise TIERS on hardware that deserves it.
const TIERS = (__ENV.TIERS || '20,50,100,150')
  .split(',')
  .map((value) => Number(value.trim()));

// Hard ceiling on concurrency per tier. Once a tier hits it, k6 reports dropped_iterations
// instead of growing without bound — and a dropped iteration is exactly the signal wanted here
// ("the system could not keep up at this rate"), delivered without taking the host down to say so.
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

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
      preAllocatedVUs: Math.min(Math.max(20, rate), MAX_VUS),
      maxVUs: MAX_VUS,
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
  // p(99) is not in k6's default set, and it is the number that actually matters here: p(95)
  // can stay flat while the worst 1% of merchants are already timing out.
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // These are not real thresholds — every one is trivially true. Declaring a tag-filtered
  // threshold is the only way to make k6 materialise a per-tag submetric in handleSummary's
  // data.metrics, which is what lets the per-tier table below print latency per tier instead of
  // one blended figure. `p(99)>=0` can never fail, so this cannot abort the run or mark it
  // failed, which the comment below is otherwise careful to avoid.
  thresholds: Object.fromEntries(
    TIERS.map((rate) => [`payment_create_duration{stage:rate_${rate}}`, ['p(99)>=0']])
  ),
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
  const stageSeconds = parseDurationSeconds(STAGE_DURATION);
  const lines = [
    '',
    'Stress test — by rate tier',
    '='.repeat(78),
    'target    sent   achieved   failed        p50        p95        p99',
    '-'.repeat(78),
  ];
  for (const rate of TIERS) {
    const accepted = data.metrics[`accepted_at_${rate}`]?.values?.count ?? 0;
    const failed = data.metrics[`failed_at_${rate}`]?.values?.count ?? 0;
    const total = accepted + failed;
    const failurePct = total === 0 ? '0.0' : ((failed / total) * 100).toFixed(1);
    // Achieved vs target is the tell: once the host cannot keep up, the arrival-rate executor
    // falls behind its own schedule and this drops below the tier it was asked for.
    const achieved = (total / stageSeconds).toFixed(1);
    const tier = data.metrics[`payment_create_duration{stage:rate_${rate}}`]?.values ?? {};
    lines.push(
      `${String(rate).padStart(4)}/s  ${String(total).padStart(6)}  ${achieved.padStart(7)}/s  ` +
        `${(failurePct + '%').padStart(6)}  ${millis(tier.med).padStart(9)}  ` +
        `${millis(tier['p(95)']).padStart(9)}  ${millis(tier['p(99)']).padStart(9)}`
    );
  }
  lines.push('');

  // Not per-tier — k6 reports this one globally — but it belongs next to the table rather than
  // buried in the aggregate block. A non-zero value means the executor could not start iterations
  // on schedule at some tier, which is saturation showing up as unsent work rather than as errors.
  const dropped = data.metrics.dropped_iterations?.values?.count ?? 0;
  lines.push(`dropped iterations (never sent, all tiers): ${dropped}`);
  lines.push(`VU ceiling per tier: ${MAX_VUS}${dropped > 0 ? '  <- reached' : ''}`);
  lines.push('');

  return {
    stdout: lines.join('\n') + '\n\n' + textSummaryFallback(data),
  };
}

// k6's own default text summary isn't importable in every k6 version, so this is a minimal
// stand-in that still prints the standard aggregate metrics beneath the per-tier table above.
function textSummaryFallback(data) {
  const httpFailed = data.metrics.http_req_failed?.values?.rate;
  const overall = data.metrics.payment_create_duration?.values ?? {};
  return [
    'Aggregate (all tiers blended — see the table above for what this hides):',
    `  http_req_failed: ${httpFailed !== undefined ? (httpFailed * 100).toFixed(2) + '%' : 'n/a'}`,
    `  payment_create_duration p(50)/p(95)/p(99): ` +
      `${millis(overall.med)} / ${millis(overall['p(95)'])} / ${millis(overall['p(99)'])}`,
  ].join('\n');
}

function millis(value) {
  return value === undefined ? 'n/a' : `${value.toFixed(0)}ms`;
}

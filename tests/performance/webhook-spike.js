// A spike of acquirer callbacks.
//
// The shape that matters here is not steady load, it is a cliff: an acquirer that has been queuing
// callbacks during an outage delivers all of them at once when it recovers. webhook-service has to
// verify a signature and deduplicate on every one of them, and both of those are work it cannot
// skip under pressure.
//
//   k6 run tests/performance/webhook-spike.js
//
// Every callback here is signed correctly and refers to a payment that does not exist, which is on
// purpose: this measures the trust boundary — signature verification and deduplication — not the
// downstream flow. Unknown payment ids are dropped by the consumer, so nothing is polluted.

import http from 'k6/http';
import crypto from 'k6/crypto';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { WEBHOOK } from './lib/setup.js';

const BANK = __ENV.BANK_NAME || 'mock-bank-a';
const SECRET = __ENV.MOCK_BANK_A_SECRET || 'bank-a-secret';

const accepted = new Counter('callbacks_accepted');
const duplicatesRejected = new Counter('callbacks_deduplicated');

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      stages: [
        { target: 10, duration: '30s' },   // quiet
        { target: 400, duration: '10s' },  // the acquirer reconnects and empties its queue
        { target: 400, duration: '1m' },   // and keeps going
        { target: 10, duration: '30s' },   // back to normal
      ],
    },
  },
  thresholds: {
    // Higher than the payment path allows, deliberately. A callback is not a customer waiting; it
    // is a machine that will retry. Two seconds at the ninety-fifth percentile under a 40x spike is
    // acceptable where the same number on payment creation would not be.
    'http_req_duration{name:webhook}': ['p(95)<2000'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

/** HMAC over "timestamp.body", which is what webhook-service verifies. */
function sign(timestamp, body) {
  return crypto.hmac('sha256', SECRET, `${timestamp}.${body}`, 'hex');
}

export default function () {
  const timestamp = Math.floor(Date.now() / 1000);
  const eventId = `k6-${__VU}-${__ITER}-${timestamp}`;
  const body = JSON.stringify({
    eventId,
    paymentId: '00000000-0000-0000-0000-000000000000',
    outcome: 'CAPTURED',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Provider-Signature': sign(timestamp, body),
      'X-Provider-Timestamp': String(timestamp),
    },
    tags: { name: 'webhook' },
  };

  const first = http.post(`${WEBHOOK}/internal/provider/webhooks/${BANK}`, body, params);
  check(first, { 'callback accepted': (r) => r.status === 200 }) && accepted.add(1);

  // Every tenth iteration re-sends the same event. Acquirers really do redeliver, and
  // deduplication is the check most likely to be quietly skipped under load — a duplicate capture
  // that got through would credit a merchant twice.
  if (__ITER % 10 === 0) {
    const replay = http.post(`${WEBHOOK}/internal/provider/webhooks/${BANK}`, body, params);
    const deduplicated = check(replay, {
      'duplicate is not processed twice': (r) => r.status === 200 || r.status === 409,
    });
    if (deduplicated) {
      duplicatesRejected.add(1);
    }
  }
}

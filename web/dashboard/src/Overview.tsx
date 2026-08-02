import { useCallback, useEffect, useState } from "react";
import { api, isUnauthorized, type Payment, type Refund, type Session } from "./api";
import { formatAmount, formatAmountByCurrency, formatRelative, methodFamily } from "./format";
import { AreaChart, RateChart, bucketByDay } from "./Chart";
import { CopyableId, EmptyState, StatusPill, rowActivation, useRaceGuard } from "./ui";

/** How much history the tiles are computed over. The API pages, so a figure has to say its scope. */
const WINDOW = 100;

export function Overview({
  session,
  tick,
  onUnauthorized,
  onOpenPayment,
}: {
  session: Session;
  tick: number;
  onUnauthorized: () => void;
  onOpenPayment: (paymentId: string) => void;
}) {
  const [payments, setPayments] = useState<Payment[]>([]);
  const [refunds, setRefunds] = useState<Refund[]>([]);
  const [totalPayments, setTotalPayments] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { startFetch, isCurrent } = useRaceGuard();

  const load = useCallback(async () => {
    const ticket = startFetch();
    try {
      const [paymentPage, refundPage] = await Promise.all([
        api.payments(session.token, { page: 0, size: WINDOW }),
        api.refunds(session.token, { page: 0, size: WINDOW }),
      ]);
      if (!isCurrent(ticket)) return;
      setPayments(paymentPage.items);
      setTotalPayments(paymentPage.totalItems);
      setRefunds(refundPage.items);
      setError(null);
    } catch (caught) {
      if (!isCurrent(ticket)) return;
      if (isUnauthorized(caught)) {
        onUnauthorized();
        return;
      }
      setError(caught instanceof Error ? caught.message : "Could not load the overview");
    } finally {
      if (isCurrent(ticket)) setLoading(false);
    }
  }, [session.token, onUnauthorized, startFetch, isCurrent]);

  useEffect(() => {
    load();
  }, [load, tick]);

  // The merchant's own currency, taken from its traffic rather than assumed. Used only for the
  // chart's axis labels below — unlike the KPI tiles, a single-currency chart is a trend line, not
  // a total, so approximating with the dominant currency is a smaller lie than the one the tiles
  // used to tell.
  const currency = payments[0]?.currency ?? "INR";

  const captured = payments.filter((payment) => payment.status === "CAPTURED");
  const refunded = payments.filter((payment) => payment.status === "REFUNDED");
  const failed = payments.filter(
    (payment) => payment.status === "FAILED" || payment.status === "CANCELLED"
  );
  const inFlight = payments.filter(
    (payment) =>
      payment.status === "CREATED" ||
      payment.status === "PENDING_PROVIDER" ||
      payment.status === "AUTHORIZED"
  );

  // Summed per currency, then joined — not added together as raw integers. A merchant is not
  // guaranteed to transact in only one currency, and paise plus cents is not a number that means
  // anything.
  const capturedValue = formatAmountByCurrency(captured, (p) => p.amount, (p) => p.currency);
  const settled = captured.length + refunded.length;
  const decided = settled + failed.length;
  const successRate = decided === 0 ? null : (settled / decided) * 100;

  const succeededRefunds = refunds.filter((refund) => refund.status === "SUCCEEDED");
  const returnedValue = formatAmountByCurrency(succeededRefunds, (r) => r.amount, (r) => r.currency);
  const refundsInFlight = refunds.filter((refund) => refund.status === "PENDING").length;

  // Fourteen daily buckets, empty days included: dropping them would compress the axis and make
  // a quiet week look busy.
  const volumeByDay = bucketByDay(payments, 14, (p) => p.createdAt, (inBucket) =>
    inBucket
      .filter((p) => p.status === "CAPTURED" || p.status === "REFUNDED")
      .reduce((sum, p) => sum + p.amount, 0)
  );

  const rateByDay = bucketByDay(payments, 14, (p) => p.createdAt, (inBucket) => {
    const settledInBucket = inBucket.filter(
      (p) => p.status === "CAPTURED" || p.status === "REFUNDED"
    ).length;
    const decidedInBucket =
      settledInBucket +
      inBucket.filter((p) => p.status === "FAILED" || p.status === "CANCELLED").length;
    // A day with no decided payments plots as zero rather than being dropped, so the axis stays
    // a real calendar.
    return decidedInBucket === 0 ? 0 : (settledInBucket / decidedInBucket) * 100;
  });

  const scope =
    totalPayments > WINDOW ? `last ${WINDOW} payments` : `all ${totalPayments} payments`;

  // Ordered by how common each family is, so the mix reads without a legend. "Not recorded" is a
  // row rather than a hidden remainder: it is a real thing to know about your own traffic.
  const methodMix = Object.entries(
    payments.reduce<Record<string, number>>((tally, payment) => {
      const family = methodFamily(payment.paymentMethod);
      tally[family] = (tally[family] ?? 0) + 1;
      return tally;
    }, {})
  )
    .sort((left, right) => right[1] - left[1])
    .map(([label, count]) => ({
      label,
      count,
      tone: label === "Not recorded" ? "progress" : "returned",
    }));

  return (
    <>
      {error && <div className="banner bad">{error}</div>}

      <section className="tiles">
        <Tile
          label="Captured volume"
          value={capturedValue}
          note={`${captured.length} captured · ${scope}`}
          loading={loading}
        />
        <Tile
          label="Success rate"
          value={successRate === null ? "—" : `${successRate.toFixed(1)}%`}
          note={
            successRate === null
              ? "No payment has reached an outcome yet"
              : `${settled} settled of ${decided} decided`
          }
          loading={loading}
          tone={successRate === null ? undefined : successRate >= 95 ? "good" : "warn"}
        />
        <Tile
          label="Refunded"
          value={returnedValue}
          note={
            refundsInFlight > 0
              ? `${refunds.length} refunds · ${refundsInFlight} in flight`
              : `${refunds.length} refunds`
          }
          loading={loading}
        />
        <Tile
          label="Awaiting acquirer"
          value={String(inFlight.length)}
          note={inFlight.length === 0 ? "Nothing in flight" : "Settles without any action"}
          loading={loading}
          tone={inFlight.length > 0 ? "warn" : undefined}
        />
      </section>

      <div className="split even">
        <section className="card">
          <header className="card-head">
            <h2>Payment volume</h2>
            <span className="muted">Captured, last 14 days</span>
          </header>
          <div className="card-body">
            <AreaChart buckets={volumeByDay} format={(value) => formatAmount(value, currency)} />
          </div>
        </section>

        <section className="card">
          <header className="card-head">
            <h2>Success rate</h2>
            <span className="muted">Of payments that reached an outcome</span>
          </header>
          <div className="card-body">
            <RateChart buckets={rateByDay} />
          </div>
        </section>
      </div>

      <div className="split">
        <section className="card">
          <header className="card-head">
            <h2>Recent payments</h2>
            <span className="muted">{totalPayments} total</span>
          </header>
          {loading ? (
            <div className="card-body muted">Loading…</div>
          ) : payments.length === 0 ? (
            <EmptyState
              title="No payments yet"
              detail="Create one against the API with your merchant key and it will appear here."
            />
          ) : (
            <table className="grid">
              <thead>
                <tr>
                  <th>Payment</th>
                  <th className="numeric">Amount</th>
                  <th>Status</th>
                  <th className="right">Created</th>
                </tr>
              </thead>
              <tbody>
                {payments.slice(0, 6).map((payment) => (
                  <tr key={payment.id} {...rowActivation(() => onOpenPayment(payment.id))}>
                    <td>
                      <CopyableId id={payment.id} />
                    </td>
                    <td className="numeric">{formatAmount(payment.amount, payment.currency)}</td>
                    <td>
                      <StatusPill status={payment.status} />
                    </td>
                    <td className="right muted">{formatRelative(payment.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <div className="stack">
          <section className="card">
            <header className="card-head">
              <h2>Where payments end up</h2>
              <span className="muted">{scope}</span>
            </header>
            <div className="card-body">
              <Breakdown
                rows={[
                  { label: "Captured", count: captured.length, tone: "success" },
                  { label: "Refunded", count: refunded.length, tone: "returned" },
                  { label: "In flight", count: inFlight.length, tone: "progress" },
                  { label: "Failed", count: failed.length, tone: "failure" },
                ]}
                total={payments.length}
              />
            </div>
          </section>

          <section className="card">
            <header className="card-head">
              <h2>How customers pay</h2>
              <span className="muted">{scope}</span>
            </header>
            <div className="card-body">
              <Breakdown rows={methodMix} total={payments.length} />
            </div>
          </section>
        </div>
      </div>
    </>
  );
}

function Tile({
  label,
  value,
  note,
  loading,
  tone,
}: {
  label: string;
  value: string;
  note: string;
  loading: boolean;
  tone?: "good" | "warn";
}) {
  return (
    <article className="tile">
      <span className="tile-label">{label}</span>
      <strong className={`tile-value ${tone ?? ""}`}>{loading ? "—" : value}</strong>
      <span className="tile-note">{note}</span>
    </article>
  );
}

function Breakdown({
  rows,
  total,
}: {
  rows: { label: string; count: number; tone: string }[];
  total: number;
}) {
  if (total === 0) {
    return <p className="muted">Nothing to break down yet.</p>;
  }
  return (
    <ul className="breakdown">
      {rows.map((row) => (
        <li key={row.label}>
          <span className="breakdown-label">{row.label}</span>
          <span className="bar">
            <span className={`bar-fill ${row.tone}`} style={{ width: `${(row.count / total) * 100}%` }} />
          </span>
          <span className="breakdown-count">{row.count}</span>
        </li>
      ))}
    </ul>
  );
}

import { useCallback, useEffect, useState } from "react";
import { api, isUnauthorized, type Payment, type Refund, type Session } from "./api";
import { formatAmount, formatRelative, methodFamily } from "./format";
import { CopyableId, EmptyState, StatusPill } from "./ui";

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

  const load = useCallback(async () => {
    try {
      const [paymentPage, refundPage] = await Promise.all([
        api.payments(session.token, { page: 0, size: WINDOW }),
        api.refunds(session.token, { page: 0, size: WINDOW }),
      ]);
      setPayments(paymentPage.items);
      setTotalPayments(paymentPage.totalItems);
      setRefunds(refundPage.items);
      setError(null);
    } catch (caught) {
      if (isUnauthorized(caught)) {
        onUnauthorized();
        return;
      }
      setError(caught instanceof Error ? caught.message : "Could not load the overview");
    } finally {
      setLoading(false);
    }
  }, [session.token, onUnauthorized]);

  useEffect(() => {
    load();
  }, [load, tick]);

  // The merchant's own currency, taken from its traffic rather than assumed.
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

  const capturedValue = captured.reduce((sum, payment) => sum + payment.amount, 0);
  const settled = captured.length + refunded.length;
  const decided = settled + failed.length;
  const successRate = decided === 0 ? null : (settled / decided) * 100;

  const returnedValue = refunds
    .filter((refund) => refund.status === "SUCCEEDED")
    .reduce((sum, refund) => sum + refund.amount, 0);
  const refundsInFlight = refunds.filter((refund) => refund.status === "PENDING").length;

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
          value={formatAmount(capturedValue, currency)}
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
          value={formatAmount(returnedValue, currency)}
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
                  <tr key={payment.id} onClick={() => onOpenPayment(payment.id)}>
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

import { useCallback, useEffect, useState } from "react";
import {
  api,
  isUnauthorized,
  type Payment,
  type PaymentAttempt,
  type Refund,
  type Session,
} from "./api";
import {
  describeMethod,
  formatAmount,
  formatDateTime,
  titleCase,
  toMajorUnits,
  toMinorUnits,
} from "./format";
import { CopyableId, StatusPill, useToast } from "./ui";

export function PaymentDrawer({
  session,
  paymentId,
  tick,
  onClose,
  onUnauthorized,
  onChanged,
}: {
  session: Session;
  paymentId: string;
  tick: number;
  onClose: () => void;
  onUnauthorized: () => void;
  onChanged: () => void;
}) {
  const [payment, setPayment] = useState<Payment | null>(null);
  const [refunds, setRefunds] = useState<Refund[]>([]);
  const [attempts, setAttempts] = useState<PaymentAttempt[] | null>(null);
  const [attemptsError, setAttemptsError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [found, itsRefunds] = await Promise.all([
        api.payment(session.token, paymentId),
        api.refundsFor(session.token, paymentId),
      ]);
      setPayment(found);
      setRefunds(itsRefunds);
      setError(null);

      // Attempts live in another service and are fetched separately on purpose: the router being
      // unreachable should cost this panel one section, not the whole payment.
      try {
        setAttempts(await api.attempts(session.token, paymentId));
        setAttemptsError(null);
      } catch (caught) {
        setAttempts(null);
        setAttemptsError(
          caught instanceof Error ? caught.message : "Could not load the acquirer attempts"
        );
      }
    } catch (caught) {
      if (isUnauthorized(caught)) {
        onUnauthorized();
        return;
      }
      setError(caught instanceof Error ? caught.message : "Could not load this payment");
    } finally {
      setLoading(false);
    }
  }, [session.token, paymentId, onUnauthorized]);

  useEffect(() => {
    load();
  }, [load, tick]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const committed = refunds
    .filter((refund) => refund.status !== "FAILED")
    .reduce((sum, refund) => sum + refund.amount, 0);
  const refundable = payment ? payment.amount - committed : 0;

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Payment detail">
        <header className="drawer-head">
          <div>
            <span className="muted">Payment</span>
            {payment ? (
              <div className="drawer-amount">
                {formatAmount(payment.amount, payment.currency)}
                <StatusPill status={payment.status} />
              </div>
            ) : (
              <div className="drawer-amount">{loading ? "Loading…" : "Not found"}</div>
            )}
          </div>
          <button className="icon" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>

        {error && <div className="banner bad">{error}</div>}

        {payment && (
          <div className="drawer-body">
            <section>
              <h3>Summary</h3>
              <dl className="details">
                <dt>Payment ID</dt>
                <dd>
                  <CopyableId id={payment.id} full />
                </dd>
                <dt>Status</dt>
                <dd>{titleCase(payment.status)}</dd>
                <dt>Screening</dt>
                <dd className={payment.fraudStatus === "ALLOWED" ? "" : "muted"}>
                  {describeScreening(payment.fraudStatus)}
                </dd>
                <dt>Amount</dt>
                <dd>{formatAmount(payment.amount, payment.currency)}</dd>
                <dt>Method</dt>
                <dd className={payment.paymentMethod ? "" : "muted"}>
                  {describeMethod(payment.paymentMethod)}
                </dd>
                <dt>Currency</dt>
                <dd>{payment.currency}</dd>
                <dt>Refunded</dt>
                <dd>{formatAmount(committed, payment.currency)}</dd>
                <dt>Still refundable</dt>
                <dd>{formatAmount(refundable, payment.currency)}</dd>
                <dt>Created</dt>
                <dd>{formatDateTime(payment.createdAt)}</dd>
                <dt>Last updated</dt>
                <dd>{formatDateTime(payment.updatedAt)}</dd>
              </dl>
            </section>

            <section>
              <h3>Acquirer attempts</h3>
              {payment.fraudStatus === "HELD" ? (
                // Without this the panel is simply empty, which reads as "the acquirers were not
                // tried yet" when the truth is that they never will be until somebody decides.
                <p className="muted">
                  Held by risk screening. Nothing has been sent to an acquirer, and nothing will be
                  until the review is resolved.
                </p>
              ) : (
                <Attempts attempts={attempts} error={attemptsError} />
              )}
            </section>

            <section>
              <h3>Activity</h3>
              <Timeline payment={payment} refunds={refunds} />
            </section>

            <section>
              <h3>Refunds</h3>
              {refunds.length === 0 ? (
                <p className="muted">None yet.</p>
              ) : (
                <ul className="refund-list">
                  {refunds.map((refund) => (
                    <li key={refund.id}>
                      <div className="refund-line">
                        <strong>{formatAmount(refund.amount, refund.currency)}</strong>
                        <StatusPill status={refund.status} />
                      </div>
                      <div className="refund-meta muted">
                        <CopyableId id={refund.id} />
                        <span>{formatDateTime(refund.createdAt)}</span>
                      </div>
                      {(refund.failureReason ?? refund.reason) && (
                        <p className="refund-reason muted">
                          {refund.failureReason ?? refund.reason}
                        </p>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <RefundPanel
              session={session}
              payment={payment}
              refundable={refundable}
              onDone={() => {
                load();
                onChanged();
              }}
            />
          </div>
        )}
      </aside>
    </>
  );
}

/**
 * What the router tried, in order. This is where a failover becomes visible: a payment that
 * succeeded on the second acquirer looks identical to one that succeeded on the first, until you
 * see that the first refused it.
 */
/**
 * UNSCREENED is deliberately not shown as "Allowed". "We decided this was fine" and "nobody looked"
 * are different claims, and only one of them should be in front of somebody handling a dispute.
 */
function describeScreening(fraudStatus: Payment["fraudStatus"]): string {
  switch (fraudStatus) {
    case "ALLOWED":
      return "Cleared";
    case "HELD":
      return "Held for review";
    case "BLOCKED":
      return "Refused by screening";
    case "UNSCREENED":
      return "Not screened — the risk service was unreachable";
    default:
      return fraudStatus;
  }
}

function Attempts({ attempts, error }: { attempts: PaymentAttempt[] | null; error: string | null }) {
  if (error) {
    // Deliberately not an empty list. "Nothing was tried" and "could not ask" are different
    // answers, and only one of them is true here.
    return <p className="muted">{error}</p>;
  }
  if (attempts === null) {
    return <p className="muted">Loading…</p>;
  }
  if (attempts.length === 0) {
    return <p className="muted">Not dispatched to an acquirer yet.</p>;
  }

  return (
    <ol className="attempts">
      {attempts.map((attempt) => (
        <li key={attempt.attemptNo}>
          <span className={`attempt-no ${attemptTone(attempt.status)}`}>{attempt.attemptNo}</span>
          <div>
            <div className="attempt-line">
              <strong>{attempt.provider}</strong>
              <span className={`chip ${attemptTone(attempt.status)}`}>
                {titleCase(attempt.status)}
              </span>
            </div>
            {attempt.providerReference && (
              <div className="muted small mono">{attempt.providerReference}</div>
            )}
            {attempt.failureReason && <div className="muted small">{attempt.failureReason}</div>}
          </div>
        </li>
      ))}
    </ol>
  );
}

function attemptTone(status: string): string {
  const upper = status.toUpperCase();
  if (upper === "ACCEPTED" || upper === "SUCCEEDED" || upper === "CAPTURED") return "success";
  if (upper === "FAILED" || upper === "DECLINED" || upper === "TIMEOUT") return "failure";
  return "progress";
}

/**
 * Built from timestamps the API actually returns, so it reports what is known rather than
 * narrating a provider round-trip nobody recorded here.
 */
function Timeline({ payment, refunds }: { payment: Payment; refunds: Refund[] }) {
  const events: { at: string; title: string; detail?: string; tone: string }[] = [
    {
      at: payment.createdAt,
      title: "Payment created",
      detail: formatAmount(payment.amount, payment.currency),
      tone: "progress",
    },
  ];

  if (payment.updatedAt !== payment.createdAt) {
    events.push({
      at: payment.updatedAt,
      title: `Payment ${titleCase(payment.status).toLowerCase()}`,
      tone:
        payment.status === "CAPTURED"
          ? "success"
          : payment.status === "REFUNDED"
            ? "returned"
            : payment.status === "FAILED" || payment.status === "CANCELLED"
              ? "failure"
              : "progress",
    });
  }

  for (const refund of refunds) {
    events.push({
      at: refund.createdAt,
      title: "Refund requested",
      detail: formatAmount(refund.amount, refund.currency),
      tone: "progress",
    });
    if (refund.status !== "PENDING") {
      events.push({
        at: refund.updatedAt,
        title: refund.status === "SUCCEEDED" ? "Refund settled" : "Refund failed",
        detail: refund.failureReason ?? formatAmount(refund.amount, refund.currency),
        tone: refund.status === "SUCCEEDED" ? "success" : "failure",
      });
    }
  }

  events.sort((left, right) => left.at.localeCompare(right.at));

  return (
    <ol className="timeline">
      {events.map((event, index) => (
        <li key={`${event.at}-${index}`}>
          <span className={`node ${event.tone}`} />
          <div>
            <strong>{event.title}</strong>
            {event.detail && <span className="muted"> · {event.detail}</span>}
            <div className="muted small">{formatDateTime(event.at)}</div>
          </div>
        </li>
      ))}
    </ol>
  );
}

function RefundPanel({
  session,
  payment,
  refundable,
  onDone,
}: {
  session: Session;
  payment: Payment;
  refundable: number;
  onDone: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const notify = useToast();

  if (payment.status !== "CAPTURED" || refundable <= 0) {
    return (
      <section className="drawer-action muted">
        {payment.status === "REFUNDED"
          ? "Fully refunded."
          : "Only a captured payment can be refunded."}
      </section>
    );
  }

  // Half-typed input is not a number yet, and a button reading "Refund ₹NaN" is worse than one
  // that simply has not caught up.
  const typed = amount.trim() === "" ? refundable : toMinorUnits(amount, payment.currency);
  const preview = Number.isFinite(typed) && typed > 0 ? typed : refundable;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      // Blank means "everything still refundable", which the backend works out itself rather than
      // trusting a number this screen calculated.
      const minorUnits = amount.trim() === "" ? null : toMinorUnits(amount, payment.currency);
      await api.createRefund(session.token, payment.id, minorUnits, reason);
      notify("ok", "Refund submitted. It settles once the acquirer confirms.");
      setAmount("");
      setReason("");
      setOpen(false);
      onDone();
    } catch (caught) {
      notify("bad", caught instanceof Error ? caught.message : "Could not create the refund");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="drawer-action">
      {open ? (
        <form className="refund-form" onSubmit={submit}>
          <h3>Issue a refund</h3>
          <label>
            Amount in {payment.currency} — leave blank to refund the rest
            <input
              type="number"
              step="0.01"
              min="0.01"
              max={toMajorUnits(refundable, payment.currency)}
              value={amount}
              placeholder={toMajorUnits(refundable, payment.currency)}
              onChange={(event) => setAmount(event.target.value)}
            />
          </label>
          <label>
            Reason
            <input
              value={reason}
              maxLength={255}
              placeholder="Customer returned the order"
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <div className="row">
            <button type="submit" className="primary" disabled={busy}>
              {busy ? "Submitting…" : `Refund ${formatAmount(preview, payment.currency)}`}
            </button>
            <button type="button" className="ghost" onClick={() => setOpen(false)}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <button className="primary" onClick={() => setOpen(true)}>
          Issue a refund
        </button>
      )}
    </section>
  );
}

import { useCallback, useEffect, useState } from "react";
import { ApiError, api, formatAmount, type Payment, type Refund, type Session } from "./api";
import "./App.css";

const SESSION_KEY = "openpay.session";

/**
 * Sessions live in sessionStorage rather than localStorage: closing the tab ends the session,
 * which is what someone checking their payments on a shared machine would expect.
 */
function loadSession(): Session | null {
  const raw = sessionStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    const session = JSON.parse(raw) as Session;
    return new Date(session.expiresAt) > new Date() ? session : null;
  } catch {
    return null;
  }
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession);

  const signIn = (next: Session) => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(next));
    setSession(next);
  };

  const signOut = useCallback(() => {
    sessionStorage.removeItem(SESSION_KEY);
    setSession(null);
  }, []);

  return session ? <Dashboard session={session} onSignOut={signOut} /> : <Login onSignIn={signIn} />;
}

function Login({ onSignIn }: { onSignIn: (session: Session) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSignIn(await api.login(email, password));
    } catch (caught) {
      // The backend deliberately does not say which half was wrong, and neither does this.
      setError(caught instanceof ApiError ? caught.message : "Could not reach the server");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="centred">
      <form className="card login" onSubmit={submit}>
        <h1>OpenPay</h1>
        <p className="muted">Merchant dashboard</p>

        <label>
          Email
          <input
            type="email"
            value={email}
            autoComplete="username"
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </label>

        <label>
          Password
          <input
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>

        {error && <p className="error">{error}</p>}

        <button type="submit" disabled={busy}>
          {busy ? "Signing in..." : "Sign in"}
        </button>
      </form>
    </div>
  );
}

function Dashboard({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const [payments, setPayments] = useState<Payment[]>([]);
  const [total, setTotal] = useState(0);
  // The id rather than the payment itself, so the selected row keeps up with polling instead of
  // freezing on the copy that happened to be on screen when it was clicked.
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const page = await api.payments(session.token, 0, 50);
      setPayments(page.items);
      setTotal(page.totalItems);
      setRefreshTick((tick) => tick + 1);
      setError(null);
    } catch (caught) {
      // An expired session is the common case, and the only sane response is to sign out rather
      // than leave someone staring at an empty table.
      if (caught instanceof ApiError && caught.status === 401) {
        onSignOut();
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "Could not load payments");
    } finally {
      setLoading(false);
    }
  }, [session.token, onSignOut]);

  useEffect(() => {
    load();
    // Payments settle asynchronously, so a static table would show stale statuses within seconds.
    const timer = setInterval(load, 4000);
    return () => clearInterval(timer);
  }, [load]);

  const selected = payments.find((payment) => payment.id === selectedId) ?? null;
  const captured = payments.filter((payment) => payment.status === "CAPTURED");
  const capturedValue = captured.reduce((sum, payment) => sum + payment.amount, 0);
  const currency = payments[0]?.currency ?? "USD";
  const inFlight = payments.filter(
    (payment) => payment.status === "PENDING_PROVIDER" || payment.status === "CREATED"
  );

  return (
    <div className="app">
      <header>
        <div>
          <strong>OpenPay</strong> <span className="muted">merchant dashboard</span>
        </div>
        <div className="muted">
          {session.email} &middot; {session.role}
          <button className="link" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </header>

      <section className="stats">
        <Stat label="Payments" value={String(total)} />
        <Stat label="Captured" value={String(captured.length)} />
        <Stat label="Captured value" value={formatAmount(capturedValue, currency)} />
        <Stat label="Awaiting provider" value={String(inFlight.length)} />
      </section>

      {error && <p className="error">{error}</p>}

      <div className="split">
        <section className="card">
          <h2>Payments</h2>
          {loading ? (
            <p className="muted">Loading...</p>
          ) : payments.length === 0 ? (
            <p className="muted">No payments yet.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Created</th>
                  <th>Amount</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {payments.map((payment) => (
                  <tr
                    key={payment.id}
                    className={selectedId === payment.id ? "selected" : ""}
                    onClick={() => setSelectedId(payment.id)}
                  >
                    <td>{new Date(payment.createdAt).toLocaleString()}</td>
                    <td className="numeric">{formatAmount(payment.amount, payment.currency)}</td>
                    <td>
                      <StatusBadge status={payment.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <PaymentDetail
          session={session}
          payment={selected}
          refreshTick={refreshTick}
          onChanged={load}
        />
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="card stat">
      <div className="muted">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const tone =
    status === "CAPTURED" || status === "AUTHORIZED" || status === "SUCCEEDED"
      ? "ok"
      : status === "FAILED" || status === "CANCELLED"
        ? "bad"
        : status === "REFUNDED"
          ? "warn"
          : "pending";
  return <span className={`badge ${tone}`}>{status.replace("_", " ").toLowerCase()}</span>;
}

function PaymentDetail({
  session,
  payment,
  refreshTick,
  onChanged,
}: {
  session: Session;
  payment: Payment | null;
  refreshTick: number;
  onChanged: () => void;
}) {
  const [refunds, setRefunds] = useState<Refund[]>([]);
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const paymentId = payment?.id ?? null;

  // Keyed on the payment alone. Folding this into the refresh below would wipe out whatever the
  // merchant was halfway through typing every few seconds.
  useEffect(() => {
    setMessage(null);
    setError(null);
    setAmount("");
    setReason("");
  }, [paymentId]);

  // A refund is only PENDING until the provider answers, so this has to keep looking.
  useEffect(() => {
    if (!paymentId) {
      setRefunds([]);
      return;
    }
    api
      .refundsFor(session.token, paymentId)
      .then(setRefunds)
      .catch(() => setRefunds([]));
  }, [paymentId, refreshTick, session.token]);

  if (!payment) {
    return (
      <section className="card">
        <h2>Detail</h2>
        <p className="muted">Select a payment to see its refunds.</p>
      </section>
    );
  }

  const refunded = refunds
    .filter((refund) => refund.status !== "FAILED")
    .reduce((sum, refund) => sum + refund.amount, 0);
  const refundable = payment.amount - refunded;

  async function submitRefund(event: React.FormEvent) {
    event.preventDefault();
    if (!payment) return;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      // Blank means "everything still refundable", which the backend works out itself rather than
      // trusting a number this screen calculated.
      const minorUnits = amount.trim() === "" ? null : Math.round(Number(amount) * 100);
      await api.createRefund(session.token, payment.id, minorUnits, reason);
      setMessage("Refund submitted. It completes once the provider confirms.");
      setRefunds(await api.refundsFor(session.token, payment.id));
      onChanged();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not create the refund");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>Payment</h2>
      <dl>
        <dt>Amount</dt>
        <dd>{formatAmount(payment.amount, payment.currency)}</dd>
        <dt>Status</dt>
        <dd>
          <StatusBadge status={payment.status} />
        </dd>
        <dt>Refunded</dt>
        <dd>{formatAmount(refunded, payment.currency)}</dd>
        <dt>Still refundable</dt>
        <dd>{formatAmount(refundable, payment.currency)}</dd>
      </dl>

      <h3>Refunds</h3>
      {refunds.length === 0 ? (
        <p className="muted">None.</p>
      ) : (
        <ul className="refunds">
          {refunds.map((refund) => (
            <li key={refund.id}>
              {formatAmount(refund.amount, refund.currency)} <StatusBadge status={refund.status} />
              {refund.failureReason && <span className="muted"> {refund.failureReason}</span>}
            </li>
          ))}
        </ul>
      )}

      {payment.status === "CAPTURED" && refundable > 0 ? (
        <form onSubmit={submitRefund} className="refund-form">
          <h3>Refund</h3>
          <label>
            Amount ({payment.currency}) &mdash; blank refunds the rest
            <input
              type="number"
              step="0.01"
              min="0.01"
              max={(refundable / 100).toFixed(2)}
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              placeholder={(refundable / 100).toFixed(2)}
            />
          </label>
          <label>
            Reason
            <input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={255} />
          </label>
          <button type="submit" disabled={busy}>
            {busy ? "Submitting..." : "Refund"}
          </button>
        </form>
      ) : (
        <p className="muted">
          {payment.status === "REFUNDED"
            ? "Fully refunded."
            : "Only a captured payment can be refunded."}
        </p>
      )}

      {message && <p className="ok-text">{message}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  );
}

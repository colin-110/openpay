import { useState } from "react";
import { ApiError, api, type Session } from "./api";

export function Login({ onSignIn }: { onSignIn: (session: Session) => void }) {
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
    <div className="auth">
      <div className="auth-panel">
        <div className="auth-brand">
          <Mark />
          <span>OpenPay</span>
        </div>
        <h1>Payments, end to end.</h1>
        <p>
          Route to acquirers, watch every payment settle, and return money to customers — from one
          console.
        </p>
        <ul className="auth-points">
          <li>Automatic acquirer failover with a per-provider circuit breaker</li>
          <li>Double-entry ledger, append-only and enforced by the database</li>
          <li>Idempotent APIs and signed webhooks in both directions</li>
        </ul>
      </div>

      <div className="auth-form-side">
        <form className="auth-form" onSubmit={submit}>
          <div className="auth-form-head">
            <h2>Sign in</h2>
            <span className="env-pill">Sandbox</span>
          </div>
          <p className="muted">Use the dashboard credentials issued for your merchant account.</p>

          <label>
            Email
            <input
              type="email"
              value={email}
              autoComplete="username"
              placeholder="you@company.com"
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
              placeholder="••••••••"
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </label>

          {error && <p className="form-error">{error}</p>}

          <button type="submit" className="primary" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>

          <p className="fineprint">
            Sessions are short-lived and expire on their own. Closing this tab signs you out.
          </p>
        </form>
      </div>
    </div>
  );
}

export function Mark() {
  return (
    <svg className="mark" viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="currentColor" />
      <path
        d="M10 22V10h6.2a4.2 4.2 0 0 1 0 8.4H13.4"
        fill="none"
        stroke="#fff"
        strokeWidth="2.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

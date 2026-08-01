import { useCallback, useEffect, useState } from "react";
import type { Session } from "./api";
import { Login, Mark } from "./Login";
import { Overview } from "./Overview";
import { PaymentDrawer } from "./PaymentDrawer";
import { Payments } from "./Payments";
import { Developers } from "./Developers";
import { Refunds } from "./Refunds";
import { Settlements } from "./Settlements";
import { shortId } from "./format";
import { ToastProvider } from "./ui";
import "./styles.css";

const SESSION_KEY = "openpay.session";
const POLL_INTERVAL_MS = 5000;

type View = "overview" | "payments" | "refunds" | "settlements" | "developers";

const VIEWS: { id: View; label: string; title: string; subtitle: string }[] = [
  {
    id: "overview",
    label: "Overview",
    title: "Overview",
    subtitle: "How payments are going right now",
  },
  {
    id: "payments",
    label: "Payments",
    title: "Payments",
    subtitle: "Every payment taken through the gateway",
  },
  {
    id: "refunds",
    label: "Refunds",
    title: "Refunds",
    subtitle: "Money returned to customers",
  },
  {
    id: "settlements",
    label: "Settlements",
    title: "Settlements",
    subtitle: "When the money reaches your bank, and what was deducted",
  },
  {
    id: "developers",
    label: "Developers",
    title: "Developers",
    subtitle: "Your integration, and every webhook we have sent you",
  },
];

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

/**
 * The URL carries the view and the open payment, so a link to one payment is a link someone can
 * actually send. Hash routing keeps that true without a router or a server that knows the routes.
 */
function readRoute(): { view: View; paymentId: string | null } {
  const [rawView, rawId] = location.hash.replace(/^#\/?/, "").split("/");
  const view = VIEWS.some((candidate) => candidate.id === rawView) ? (rawView as View) : "overview";
  return { view, paymentId: rawId || null };
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

  if (!session) {
    return <Login onSignIn={signIn} />;
  }
  return (
    <ToastProvider>
      <Console session={session} onSignOut={signOut} />
    </ToastProvider>
  );
}

function Console({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const [route, setRoute] = useState(readRoute);
  const [live, setLive] = useState(true);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const onHashChange = () => setRoute(readRoute());
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  // Payments settle a few seconds after they are created, with no client action, so a console
  // that only loaded once would be wrong almost immediately. It is a toggle because a screen that
  // reorders itself under you is its own kind of wrong.
  useEffect(() => {
    if (!live) return;
    const timer = setInterval(() => setTick((value) => value + 1), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [live]);

  const refresh = useCallback(() => setTick((value) => value + 1), []);
  const go = useCallback((view: View, paymentId?: string) => {
    location.hash = paymentId ? `#/${view}/${paymentId}` : `#/${view}`;
  }, []);

  const openPayment = useCallback(
    (paymentId: string) => go(route.view === "overview" ? "payments" : route.view, paymentId),
    [go, route.view]
  );
  const closePayment = useCallback(() => go(route.view), [go, route.view]);

  const current = VIEWS.find((candidate) => candidate.id === route.view) ?? VIEWS[0];

  return (
    <div className="shell">
      <nav className="sidebar">
        <div className="brand">
          <Mark />
          <span>OpenPay</span>
        </div>
        <span className="env-pill">Sandbox</span>

        <ul className="nav">
          {VIEWS.map((view) => (
            <li key={view.id}>
              <button
                className={route.view === view.id ? "active" : ""}
                onClick={() => go(view.id)}
              >
                {view.label}
              </button>
            </li>
          ))}
        </ul>

        <div className="sidebar-foot">
          <div className="who">
            <strong>{session.email}</strong>
            <span>{session.role.replace("_", " ").toLowerCase()}</span>
            <span className="mono muted" title={session.merchantId}>
              {shortId(session.merchantId)}
            </span>
          </div>
          <button className="ghost on-dark" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </nav>

      <main>
        <header className="topbar">
          <div>
            <h1>{current.title}</h1>
            <p className="muted">{current.subtitle}</p>
          </div>
          <div className="topbar-actions">
            <button
              className={`live ${live ? "on" : ""}`}
              onClick={() => setLive((value) => !value)}
              title={live ? "Refreshing every 5 seconds" : "Auto-refresh is paused"}
            >
              <span className="dot" />
              {live ? "Live" : "Paused"}
            </button>
            <button className="ghost" onClick={refresh}>
              Refresh
            </button>
          </div>
        </header>

        <div className="page">
          {route.view === "overview" && (
            <Overview
              session={session}
              tick={tick}
              onUnauthorized={onSignOut}
              onOpenPayment={openPayment}
            />
          )}
          {route.view === "payments" && (
            <Payments
              session={session}
              tick={tick}
              onUnauthorized={onSignOut}
              onOpenPayment={openPayment}
              selectedId={route.paymentId}
            />
          )}
          {route.view === "refunds" && (
            <Refunds
              session={session}
              tick={tick}
              onUnauthorized={onSignOut}
              onOpenPayment={openPayment}
            />
          )}
          {route.view === "settlements" && (
            <Settlements
              session={session}
              tick={tick}
              onUnauthorized={onSignOut}
              onOpenPayment={openPayment}
            />
          )}
          {route.view === "developers" && (
            <Developers session={session} tick={tick} onUnauthorized={onSignOut} />
          )}
        </div>
      </main>

      {route.paymentId && (
        <PaymentDrawer
          session={session}
          paymentId={route.paymentId}
          tick={tick}
          onClose={closePayment}
          onUnauthorized={onSignOut}
          onChanged={refresh}
        />
      )}
    </div>
  );
}

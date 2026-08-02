import { useCallback, useEffect, useRef, useState } from "react";
import { api, type Session } from "./api";
import { Developers } from "./Developers";
import { Login, Mark } from "./Login";
import { Overview } from "./Overview";
import { PaymentDrawer } from "./PaymentDrawer";
import { Payments } from "./Payments";
import { Refunds } from "./Refunds";
import { Settlements } from "./Settlements";
import { shortId } from "./format";
import {
  DevelopersIcon,
  HomeIcon,
  PaymentsIcon,
  RefundsIcon,
  SettlementsIcon,
} from "./icons";
import { ToastProvider } from "./ui";
import "./styles.css";

const SESSION_KEY = "openpay.session";
const POLL_INTERVAL_MS = 5000;

type View = "overview" | "payments" | "refunds" | "settlements" | "developers";

type ViewDef = {
  id: View;
  label: string;
  title: string;
  subtitle: string;
  group: string;
  Icon: (props: { className?: string }) => React.ReactElement;
};

/**
 * Grouped the way the work is grouped, not alphabetically. Someone reconciling yesterday's takings
 * is in Operations all morning and never touches Developers.
 */
const VIEWS: ViewDef[] = [
  {
    id: "overview",
    label: "Home",
    title: "Overview",
    subtitle: "How payments are going right now",
    group: "",
    Icon: HomeIcon,
  },
  {
    id: "payments",
    label: "Payments",
    title: "Payments",
    subtitle: "Every payment taken through the gateway",
    group: "Operations",
    Icon: PaymentsIcon,
  },
  {
    id: "refunds",
    label: "Refunds",
    title: "Refunds",
    subtitle: "Money returned to customers",
    group: "Operations",
    Icon: RefundsIcon,
  },
  {
    id: "settlements",
    label: "Settlements",
    title: "Settlements",
    subtitle: "When the money reaches your bank, and what was deducted",
    group: "Operations",
    Icon: SettlementsIcon,
  },
  {
    id: "developers",
    label: "Webhooks & API",
    title: "Developers",
    subtitle: "Your integration, and every webhook we have sent you",
    group: "Developers",
    Icon: DevelopersIcon,
  },
];

/**
 * Sessions live in sessionStorage rather than localStorage: closing the tab ends the session,
 * which is what someone checking their payments on a shared machine would expect. The refresh
 * token lives right beside the access token in the same object, so that property holds for it too
 * — closing the tab does not leave a long-lived credential sitting in a browser's local storage.
 *
 * <p>Checked against refreshExpiresAt, not expiresAt. The access token expiring on its own is not
 * the end of the session — that is exactly what the refresh token is for — so discarding the whole
 * session the moment the short-lived half expires would make refreshing pointless. Only once the
 * refresh token itself has expired is there nothing left to renew with.
 */
function loadSession(): Session | null {
  const raw = sessionStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    const session = JSON.parse(raw) as Session;
    return new Date(session.refreshExpiresAt) > new Date() ? session : null;
  } catch {
    return null;
  }
}

/** Refresh this long before the access token actually expires, so a request mid-refresh never
 *  races an expiry. Comfortably larger than any request this dashboard makes. */
const REFRESH_SKEW_MS = 60_000;

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
  // Guards against a duplicate refresh firing twice for the same session — React's effect can
  // re-run (StrictMode's double-invoke in development, or a re-render for an unrelated reason)
  // before the first refresh call has finished, and a refresh token only works once.
  const refreshing = useRef(false);

  const signIn = (next: Session) => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(next));
    setSession(next);
  };

  const signOut = useCallback(() => {
    // Best effort: the session ends locally regardless of whether the network call lands. Not
    // awaited, because a person clicking "sign out" should see it happen immediately rather than
    // wait on a request whose result changes nothing they can see.
    if (session) {
      api.logout(session.refreshToken).catch(() => {});
    }
    sessionStorage.removeItem(SESSION_KEY);
    setSession(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  // Silent renewal. Scheduled a minute ahead of the access token's real expiry rather than at the
  // instant it lapses, so a request that happens to fire in that last minute is never caught
  // holding a token the server has just started rejecting.
  useEffect(() => {
    if (!session) return;

    const msUntilRefresh = new Date(session.expiresAt).getTime() - Date.now() - REFRESH_SKEW_MS;

    const doRefresh = () => {
      if (refreshing.current) return;
      refreshing.current = true;
      api
        .refresh(session.refreshToken)
        .then((renewed) => {
          sessionStorage.setItem(SESSION_KEY, JSON.stringify(renewed));
          setSession(renewed);
        })
        .catch(() => {
          // The refresh token itself is gone — expired, logged out elsewhere, or (rare, and
          // treated as theft server-side) already used once. Either way there is nothing left to
          // renew with, so this is a real end of session rather than something to retry.
          sessionStorage.removeItem(SESSION_KEY);
          setSession(null);
        })
        .finally(() => {
          refreshing.current = false;
        });
    };

    if (msUntilRefresh <= 0) {
      doRefresh();
      return;
    }
    const timer = setTimeout(doRefresh, msUntilRefresh);
    return () => clearTimeout(timer);
  }, [session]);

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
  const groups = [...new Set(VIEWS.map((view) => view.group))];
  const initials = session.email.slice(0, 2).toUpperCase();

  return (
    <div className="shell">
      <nav className="rail">
        <div className="rail-brand">
          <Mark />
          <span>OpenPay</span>
        </div>

        <div className="switcher" title={`Merchant ${session.merchantId}`}>
          <span className="switcher-avatar">{initials}</span>
          <span className="switcher-body">
            <strong>{session.email.split("@")[0]}</strong>
            <span className="mono">{shortId(session.merchantId)}</span>
          </span>
        </div>

        {groups.map((group) => (
          <div className="rail-group" key={group || "root"}>
            {group && <p className="rail-group-label">{group}</p>}
            <ul>
              {VIEWS.filter((view) => view.group === group).map((view) => (
                <li key={view.id}>
                  <button
                    className={route.view === view.id ? "active" : ""}
                    onClick={() => go(view.id)}
                    aria-current={route.view === view.id ? "page" : undefined}
                  >
                    <view.Icon className="rail-icon" />
                    {view.label}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}

        <div className="rail-foot">
          <div className="who">
            <strong>{session.email}</strong>
            <span>{session.role.replace("_", " ").toLowerCase()}</span>
          </div>
          <button className="ghost on-dark" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </nav>

      <main>
        <header className="topbar">
          <div className="topbar-lead">
            <nav className="crumbs" aria-label="Breadcrumb">
              <button onClick={() => go("overview")}>Home</button>
              {current.id !== "overview" && (
                <>
                  <span aria-hidden="true">/</span>
                  <span className="crumb-current">{current.title}</span>
                </>
              )}
            </nav>
            <h1>{current.title}</h1>
            <p className="muted">{current.subtitle}</p>
          </div>
          <div className="topbar-actions">
            <span className="env-pill">Sandbox</span>
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
            <span className="avatar" title={session.email}>
              {initials}
            </span>
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

import { useCallback, useEffect, useState } from "react";
import { BASE as API_BASE, api, isUnauthorized, type Delivery, type Session } from "./api";
import { formatDateTime, formatRelative, titleCase } from "./format";
import { CopyableId, EmptyState, Pagination, SkeletonRows, useRaceGuard } from "./ui";

/**
 * The integration view: what the platform is sending, and what a developer needs to receive it.
 *
 * <p>The delivery log is the useful half. "The webhook never arrived" is the most common
 * integration complaint, and the answer is nearly always in the response code and the error.
 */
export function Developers({
  session,
  tick,
  onUnauthorized,
}: {
  session: Session;
  tick: number;
  onUnauthorized: () => void;
}) {
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [meta, setMeta] = useState({ totalItems: 0, totalPages: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { startFetch, isCurrent } = useRaceGuard();

  const load = useCallback(async () => {
    const ticket = startFetch();
    try {
      const result = await api.deliveries(session.token, { page, size });
      if (!isCurrent(ticket)) return;
      setDeliveries(result.items);
      setMeta({ totalItems: result.totalItems, totalPages: result.totalPages });
      setError(null);
    } catch (caught) {
      if (!isCurrent(ticket)) return;
      if (isUnauthorized(caught)) {
        onUnauthorized();
        return;
      }
      setError(caught instanceof Error ? caught.message : "Could not load the delivery log");
    } finally {
      if (isCurrent(ticket)) setLoading(false);
    }
  }, [session.token, page, size, onUnauthorized, startFetch, isCurrent]);

  useEffect(() => {
    load();
  }, [load, tick]);

  const failing = deliveries.filter((d) => d.status === "FAILED").length;
  const retrying = deliveries.filter((d) => d.status === "PENDING" && d.attempts > 1).length;

  return (
    <>
      <div className="split">
        <section className="card">
          <header className="card-head">
            <h2>Your integration</h2>
          </header>
          <div className="card-body">
            <dl className="details">
              <dt>Merchant ID</dt>
              <dd>
                <CopyableId id={session.merchantId} full />
              </dd>
              <dt>API base</dt>
              <dd className="mono">{API_BASE}</dd>
              <dt>Authentication</dt>
              <dd>
                <code>X-Api-Key</code> for servers, or a session for this console
              </dd>
              <dt>Write access</dt>
              <dd>
                {session.role === "MERCHANT_ADMIN" ? (
                  "This account can create payments and issue refunds"
                ) : (
                  <span className="muted">
                    This account is read-only. Creating payments and issuing refunds need an
                    administrator.
                  </span>
                )}
              </dd>
            </dl>
          </div>
        </section>

        <section className="card">
          <header className="card-head">
            <h2>Events we send you</h2>
          </header>
          <div className="card-body">
            <ul className="event-list">
              <li>
                <code>payment.status-updated</code>
                <span className="muted">Every time a payment moves — captured, failed, refunded</span>
              </li>
              <li>
                <code>refund.succeeded</code>
                <span className="muted">A refund has actually left the acquirer</span>
              </li>
            </ul>
            <p className="muted small">
              Each request is signed with HMAC-SHA256 over the raw body. Verify it before trusting
              the payload, and reply 2xx — anything else is retried up to 8 times over about six
              hours.
            </p>
          </div>
        </section>
      </div>

      {error && <div className="banner bad">{error}</div>}

      <section className="card">
        <header className="card-head">
          <h2>Delivery log</h2>
          <span className="muted">
            {failing > 0 && `${failing} failed · `}
            {retrying > 0 && `${retrying} retrying · `}
            {meta.totalItems} total
          </span>
        </header>
        <table className="grid">
          <thead>
            <tr>
              <th>Event</th>
              <th>Status</th>
              <th className="numeric">Attempts</th>
              <th className="numeric">Response</th>
              <th>Detail</th>
              <th className="right">When</th>
            </tr>
          </thead>
          <tbody>
            {loading && deliveries.length === 0 ? (
              <SkeletonRows rows={5} columns={6} />
            ) : (
              deliveries.map((delivery) => (
                <tr key={delivery.id}>
                  <td className="mono">{delivery.eventType}</td>
                  <td>
                    <span className={`pill ${deliveryTone(delivery.status)}`}>
                      <span className="dot" />
                      {titleCase(delivery.status)}
                    </span>
                  </td>
                  <td className="numeric">{delivery.attempts}</td>
                  <td className="numeric muted">{delivery.responseStatus ?? "—"}</td>
                  <td className="muted truncate">
                    {delivery.lastError ??
                      (delivery.nextAttemptAt && delivery.status === "PENDING"
                        ? `Next attempt ${formatRelative(delivery.nextAttemptAt)}`
                        : "—")}
                  </td>
                  <td className="right muted">
                    {formatDateTime(delivery.deliveredAt ?? delivery.createdAt)}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {!loading && deliveries.length === 0 && (
          <EmptyState
            title="Nothing sent yet"
            detail="Webhooks are delivered once a merchant has a URL configured and a payment reaches an outcome."
          />
        )}

        <Pagination
          page={page}
          size={size}
          totalItems={meta.totalItems}
          totalPages={meta.totalPages}
          onPage={setPage}
        />
      </section>
    </>
  );
}

function deliveryTone(status: string): string {
  const upper = status.toUpperCase();
  if (upper === "DELIVERED" || upper === "SUCCEEDED") return "success";
  if (upper === "FAILED") return "failure";
  return "progress";
}

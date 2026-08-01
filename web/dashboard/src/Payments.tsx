import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  api,
  isUnauthorized,
  type Payment,
  type PaymentStatus,
  type Session,
} from "./api";
import { formatAmount, formatDateTime, formatRelative } from "./format";
import { CopyableId, EmptyState, Pagination, SkeletonRows, StatusPill } from "./ui";

const STATUSES: PaymentStatus[] = [
  "CREATED",
  "PENDING_PROVIDER",
  "AUTHORIZED",
  "CAPTURED",
  "FAILED",
  "CANCELLED",
  "REFUNDED",
];

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function Payments({
  session,
  tick,
  onUnauthorized,
  onOpenPayment,
  selectedId,
}: {
  session: Session;
  tick: number;
  onUnauthorized: () => void;
  onOpenPayment: (paymentId: string) => void;
  selectedId: string | null;
}) {
  const [payments, setPayments] = useState<Payment[]>([]);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<PaymentStatus | "">("");
  const [search, setSearch] = useState("");
  const [applied, setApplied] = useState("");
  const [meta, setMeta] = useState({ totalItems: 0, totalPages: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      // A payment ID is a UUID, so a full one is a direct lookup rather than a filter. Anything
      // else would be a substring search the API cannot honestly serve.
      if (applied) {
        const payment = await api.payment(session.token, applied);
        setPayments([payment]);
        setMeta({ totalItems: 1, totalPages: 1 });
        setError(null);
        return;
      }
      const result = await api.payments(session.token, {
        page,
        size,
        status: status || null,
      });
      setPayments(result.items);
      setMeta({ totalItems: result.totalItems, totalPages: result.totalPages });
      setError(null);
    } catch (caught) {
      if (isUnauthorized(caught)) {
        onUnauthorized();
        return;
      }
      if (applied && caught instanceof ApiError && caught.status === 404) {
        setPayments([]);
        setMeta({ totalItems: 0, totalPages: 0 });
        setError(null);
        return;
      }
      setError(caught instanceof Error ? caught.message : "Could not load payments");
    } finally {
      setLoading(false);
    }
  }, [session.token, page, size, status, applied, onUnauthorized]);

  useEffect(() => {
    load();
  }, [load, tick]);

  function submitSearch(event: React.FormEvent) {
    event.preventDefault();
    const trimmed = search.trim();
    if (trimmed && !UUID_PATTERN.test(trimmed)) {
      setError("Search takes a full payment ID.");
      return;
    }
    setError(null);
    setPage(0);
    setApplied(trimmed);
  }

  const filtered = applied !== "" || status !== "";

  return (
    <>
      <div className="toolbar">
        <form className="search" onSubmit={submitSearch}>
          <input
            type="search"
            value={search}
            placeholder="Search by payment ID"
            onChange={(event) => setSearch(event.target.value)}
          />
          <button type="submit" className="ghost">
            Search
          </button>
        </form>

        <div className="toolbar-right">
          <label className="inline">
            Status
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as PaymentStatus | "");
                setPage(0);
              }}
            >
              <option value="">All</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value.replace("_", " ").toLowerCase()}
                </option>
              ))}
            </select>
          </label>

          <label className="inline">
            Rows
            <select
              value={size}
              onChange={(event) => {
                setSize(Number(event.target.value));
                setPage(0);
              }}
            >
              {[20, 50, 100].map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>

          {filtered && (
            <button
              className="ghost"
              onClick={() => {
                setStatus("");
                setSearch("");
                setApplied("");
                setPage(0);
              }}
            >
              Clear
            </button>
          )}
        </div>
      </div>

      {error && <div className="banner bad">{error}</div>}

      <section className="card">
        <table className="grid">
          <thead>
            <tr>
              <th>Payment ID</th>
              <th className="numeric">Amount</th>
              <th>Status</th>
              <th>Created</th>
              <th className="right">Last updated</th>
            </tr>
          </thead>
          <tbody>
            {loading && payments.length === 0 ? (
              <SkeletonRows rows={6} columns={5} />
            ) : (
              payments.map((payment) => (
                <tr
                  key={payment.id}
                  className={selectedId === payment.id ? "selected" : ""}
                  onClick={() => onOpenPayment(payment.id)}
                >
                  <td>
                    <CopyableId id={payment.id} />
                  </td>
                  <td className="numeric">{formatAmount(payment.amount, payment.currency)}</td>
                  <td>
                    <StatusPill status={payment.status} />
                  </td>
                  <td className="muted">{formatDateTime(payment.createdAt)}</td>
                  <td className="right muted">{formatRelative(payment.updatedAt)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {!loading && payments.length === 0 && (
          <EmptyState
            title={filtered ? "Nothing matches" : "No payments yet"}
            detail={
              filtered
                ? "Try a different status, or clear the filters."
                : "Create one against the API with your merchant key and it will appear here."
            }
          />
        )}

        {!applied && (
          <Pagination
            page={page}
            size={size}
            totalItems={meta.totalItems}
            totalPages={meta.totalPages}
            onPage={setPage}
          />
        )}
      </section>
    </>
  );
}

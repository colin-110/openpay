import { useCallback, useEffect, useState } from "react";
import { api, isUnauthorized, type Refund, type RefundStatus, type Session } from "./api";
import { formatAmount, formatDateTime } from "./format";
import { CopyableId, EmptyState, Pagination, SkeletonRows, StatusPill, rowActivation, useRaceGuard } from "./ui";

const STATUSES: RefundStatus[] = ["PENDING", "SUCCEEDED", "FAILED"];

export function Refunds({
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
  const [refunds, setRefunds] = useState<Refund[]>([]);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<RefundStatus | "">("");
  const [meta, setMeta] = useState({ totalItems: 0, totalPages: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { startFetch, isCurrent } = useRaceGuard();

  const load = useCallback(async () => {
    const ticket = startFetch();
    try {
      const result = await api.refunds(session.token, { page, size, status: status || null });
      if (!isCurrent(ticket)) return;
      setRefunds(result.items);
      setMeta({ totalItems: result.totalItems, totalPages: result.totalPages });
      setError(null);
    } catch (caught) {
      if (!isCurrent(ticket)) return;
      if (isUnauthorized(caught)) {
        onUnauthorized();
        return;
      }
      setError(caught instanceof Error ? caught.message : "Could not load refunds");
    } finally {
      if (isCurrent(ticket)) setLoading(false);
    }
  }, [session.token, page, size, status, onUnauthorized, startFetch, isCurrent]);

  useEffect(() => {
    load();
  }, [load, tick]);

  return (
    <>
      <div className="toolbar">
        <p className="toolbar-lead muted">
          Every refund this merchant has issued, newest first. A refund stays pending until the
          acquirer confirms it.
        </p>
        <div className="toolbar-right">
          <label className="inline">
            Status
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as RefundStatus | "");
                setPage(0);
              }}
            >
              <option value="">All</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value.toLowerCase()}
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
        </div>
      </div>

      {error && <div className="banner bad">{error}</div>}

      <section className="card">
        <table className="grid">
          <thead>
            <tr>
              <th>Refund ID</th>
              <th>Payment</th>
              <th className="numeric">Amount</th>
              <th>Status</th>
              <th>Reason</th>
              <th className="right">Created</th>
            </tr>
          </thead>
          <tbody>
            {loading && refunds.length === 0 ? (
              <SkeletonRows rows={6} columns={6} />
            ) : (
              refunds.map((refund) => (
                <tr key={refund.id} {...rowActivation(() => onOpenPayment(refund.paymentId))}>
                  <td>
                    <CopyableId id={refund.id} />
                  </td>
                  <td>
                    <CopyableId id={refund.paymentId} />
                  </td>
                  <td className="numeric">{formatAmount(refund.amount, refund.currency)}</td>
                  <td>
                    <StatusPill status={refund.status} />
                  </td>
                  <td className="muted truncate">
                    {refund.failureReason ?? refund.reason ?? "—"}
                  </td>
                  <td className="right muted">{formatDateTime(refund.createdAt)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {!loading && refunds.length === 0 && (
          <EmptyState
            title={status ? "Nothing matches" : "No refunds yet"}
            detail={
              status
                ? "Try a different status."
                : "Open a captured payment and return some of it to see refunds here."
            }
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

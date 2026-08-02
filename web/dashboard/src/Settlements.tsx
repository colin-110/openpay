import { useCallback, useEffect, useState } from "react";
import {
  api,
  isUnauthorized,
  type Session,
  type Settlement,
  type SettlementDetail,
} from "./api";
import { formatAmount, formatAmountByCurrency, formatDateTime } from "./format";
import { CopyableId, EmptyState, Pagination, SkeletonRows, StatusPill, rowActivation, useRaceGuard } from "./ui";

/**
 * When the merchant gets paid, and what was taken out on the way.
 *
 * <p>A payout is only trustworthy if you can see the payments inside it, so opening a row expands
 * into its items rather than just showing a total.
 */
export function Settlements({
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
  const [settlements, setSettlements] = useState<Settlement[]>([]);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [meta, setMeta] = useState({ totalItems: 0, totalPages: 0 });
  const [openId, setOpenId] = useState<string | null>(null);
  const [detail, setDetail] = useState<SettlementDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { startFetch, isCurrent } = useRaceGuard();

  const load = useCallback(async () => {
    const ticket = startFetch();
    try {
      const result = await api.settlements(session.token, { page, size });
      if (!isCurrent(ticket)) return;
      setSettlements(result.items);
      setMeta({ totalItems: result.totalItems, totalPages: result.totalPages });
      setError(null);
    } catch (caught) {
      if (!isCurrent(ticket)) return;
      if (isUnauthorized(caught)) {
        onUnauthorized();
        return;
      }
      setError(caught instanceof Error ? caught.message : "Could not load settlements");
    } finally {
      if (isCurrent(ticket)) setLoading(false);
    }
  }, [session.token, page, size, onUnauthorized, startFetch, isCurrent]);

  useEffect(() => {
    load();
  }, [load, tick]);

  // Items are fetched only for the row actually opened: most payouts are never expanded, and
  // fetching every one of them up front would be a lot of rows nobody asked for. The `current`
  // check is what stops expanding row B, while row A's detail fetch is still in flight, from
  // having A's line items flash under B once A's slower response finally lands.
  useEffect(() => {
    if (!openId) {
      setDetail(null);
      return;
    }
    const requestedFor = openId;
    let current = true;
    api
      .settlement(session.token, requestedFor)
      .then((result) => {
        if (current) setDetail(result);
      })
      .catch(() => {
        if (current) setDetail(null);
      });
    return () => {
      current = false;
    };
  }, [openId, session.token]);

  // Summed per currency, then joined — see Overview.tsx for why raw addition across currencies is
  // wrong on a KPI tile.
  const pending = settlements.filter((s) => s.status !== "COMPLETED");
  const awaiting = formatAmountByCurrency(pending, (s) => s.netAmount, (s) => s.currency);
  const feesTaken = formatAmountByCurrency(settlements, (s) => s.feeAmount, (s) => s.currency);

  return (
    <>
      <section className="tiles">
        <Tile
          label="Awaiting payout"
          value={loading ? "—" : awaiting}
          note={`${pending.length} window${pending.length === 1 ? "" : "s"} not yet paid`}
        />
        <Tile
          label="Fees on this page"
          value={loading ? "—" : feesTaken}
          note="Deducted from gross before payout"
        />
        <Tile
          label="Payouts"
          value={loading ? "—" : String(meta.totalItems)}
          note="Settlement windows closed so far"
        />
      </section>

      {error && <div className="banner bad">{error}</div>}

      <div className="toolbar">
        <div className="toolbar-right">
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

      <section className="card">
        <table className="grid">
          <thead>
            <tr>
              <th>Settlement date</th>
              <th className="numeric">Gross</th>
              <th className="numeric">Fee</th>
              <th className="numeric">Net</th>
              <th className="numeric">Payments</th>
              <th>Status</th>
              <th className="right">Payout ID</th>
            </tr>
          </thead>
          <tbody>
            {loading && settlements.length === 0 ? (
              <SkeletonRows rows={5} columns={7} />
            ) : (
              settlements.map((settlement) => (
                <SettlementRow
                  key={settlement.id}
                  settlement={settlement}
                  open={openId === settlement.id}
                  detail={openId === settlement.id ? detail : null}
                  onToggle={() => setOpenId(openId === settlement.id ? null : settlement.id)}
                  onOpenPayment={onOpenPayment}
                />
              ))
            )}
          </tbody>
        </table>

        {!loading && settlements.length === 0 && (
          <EmptyState
            title="No payouts yet"
            detail="A settlement window closes once captured payments clear the hold period. Nothing has been batched for this merchant so far."
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

function SettlementRow({
  settlement,
  open,
  detail,
  onToggle,
  onOpenPayment,
}: {
  settlement: Settlement;
  open: boolean;
  detail: SettlementDetail | null;
  onToggle: () => void;
  onOpenPayment: (paymentId: string) => void;
}) {
  return (
    <>
      <tr {...rowActivation(onToggle)} className={open ? "selected" : ""}>
        <td>
          <span className="disclosure" aria-hidden="true">
            {open ? "▾" : "▸"}
          </span>
          {settlement.settlementDate}
        </td>
        <td className="numeric">{formatAmount(settlement.grossAmount, settlement.currency)}</td>
        <td className="numeric muted">
          −{formatAmount(settlement.feeAmount, settlement.currency)}
        </td>
        <td className="numeric">
          <strong>{formatAmount(settlement.netAmount, settlement.currency)}</strong>
        </td>
        <td className="numeric muted">{settlement.itemCount}</td>
        <td>
          <StatusPill status={settlement.status === "COMPLETED" ? "SUCCEEDED" : "PENDING"} />
        </td>
        <td className="right">
          <CopyableId id={settlement.id} />
        </td>
      </tr>
      {open && (
        <tr className="expansion">
          <td colSpan={7}>
            {detail === null ? (
              <p className="muted">Loading the payments in this payout…</p>
            ) : detail.items.length === 0 ? (
              <p className="muted">This payout has no line items recorded.</p>
            ) : (
              <table className="grid nested">
                <thead>
                  <tr>
                    <th>Payment</th>
                    <th className="numeric">Gross</th>
                    <th className="numeric">Fee</th>
                    <th className="numeric">Net</th>
                    <th className="right">Captured</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.items.map((item) => (
                    <tr
                      key={item.paymentId}
                      {...rowActivation(() => onOpenPayment(item.paymentId))}
                      onClick={(event) => {
                        event.stopPropagation();
                        onOpenPayment(item.paymentId);
                      }}
                    >
                      <td>
                        <CopyableId id={item.paymentId} />
                      </td>
                      <td className="numeric">
                        {formatAmount(item.grossAmount, settlement.currency)}
                      </td>
                      <td className="numeric muted">
                        −{formatAmount(item.feeAmount, settlement.currency)}
                      </td>
                      <td className="numeric">
                        {formatAmount(item.netAmount, settlement.currency)}
                      </td>
                      <td className="right muted">{formatDateTime(item.capturedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </td>
        </tr>
      )}
    </>
  );
}

function Tile({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <article className="tile">
      <span className="tile-label">{label}</span>
      <strong className="tile-value">{value}</strong>
      <span className="tile-note">{note}</span>
    </article>
  );
}

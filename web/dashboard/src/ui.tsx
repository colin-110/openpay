import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { KeyboardEvent } from "react";
import type { PaymentStatus, RefundStatus } from "./api";
import { shortId, titleCase } from "./format";

/* -- race guard ----------------------------------------------------------- */

/**
 * Guards against an out-of-order response clobbering newer state — the 5-second auto-refresh tick
 * overlapping a manual "Refresh" click, or two filter changes fired in quick succession, can
 * resolve in either order over the network. Without this, whichever response *arrives* last wins
 * even if it was not the last one *sent*, and the screen ends up showing results for a filter that
 * is no longer selected.
 *
 * Usage: `const ticket = startFetch(); ...await...; if (!isCurrent(ticket)) return;` before every
 * setState that follows an await. Cheaper to retrofit than plumbing an AbortController through
 * every api.ts call site, and equivalent for this purpose — the stale request still completes, its
 * result is just never applied.
 */
export function useRaceGuard() {
  const ticket = useRef(0);
  const startFetch = useCallback(() => ++ticket.current, []);
  const isCurrent = useCallback((t: number) => t === ticket.current, []);
  return { startFetch, isCurrent };
}

/* -- rows as buttons ------------------------------------------------------ */

/**
 * Spreadable props for a `<tr onClick={...}>` that drills into a detail view. A bare onClick on a
 * table row is mouse-only — no keyboard or screen-reader user can reach it — so every row that
 * opens something spreads this instead of wiring onClick directly.
 */
export function rowActivation(onActivate: () => void) {
  return {
    onClick: onActivate,
    tabIndex: 0,
    role: "button" as const,
    onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        onActivate();
      }
    },
  };
}

/* -- status ------------------------------------------------------------- */

type Tone = "success" | "failure" | "progress" | "returned";

const TONES: Record<PaymentStatus | RefundStatus, Tone> = {
  CREATED: "progress",
  PENDING_PROVIDER: "progress",
  PENDING: "progress",
  AUTHORIZED: "progress",
  CAPTURED: "success",
  SUCCEEDED: "success",
  FAILED: "failure",
  CANCELLED: "failure",
  REFUNDED: "returned",
};

export function StatusPill({ status }: { status: PaymentStatus | RefundStatus }) {
  return (
    <span className={`pill ${TONES[status] ?? "progress"}`}>
      <span className="dot" />
      {titleCase(status)}
    </span>
  );
}

/* -- identifiers -------------------------------------------------------- */

/** IDs are the thing people paste into a support ticket, so copying one is one click. */
export function CopyableId({ id, full = false }: { id: string; full?: boolean }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  return (
    <button
      type="button"
      className="copyable"
      title={`${id} — click to copy`}
      onClick={(event) => {
        event.stopPropagation();
        navigator.clipboard.writeText(id).then(() => {
          setCopied(true);
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => setCopied(false), 1200);
        });
      }}
    >
      <span className="mono">{full ? id : shortId(id)}</span>
      <span className="copy-hint">{copied ? "copied" : "copy"}</span>
    </button>
  );
}

/* -- table furniture ---------------------------------------------------- */

export function SkeletonRows({ rows, columns }: { rows: number; columns: number }) {
  return (
    <>
      {Array.from({ length: rows }, (_, row) => (
        <tr key={row} className="skeleton-row">
          {Array.from({ length: columns }, (_, column) => (
            <td key={column}>
              <span className="skeleton" />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}

export function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="empty">
      <div className="empty-mark" aria-hidden="true" />
      <h3>{title}</h3>
      <p>{detail}</p>
    </div>
  );
}

export function Pagination({
  page,
  size,
  totalItems,
  totalPages,
  onPage,
}: {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  const first = totalItems === 0 ? 0 : page * size + 1;
  const last = Math.min((page + 1) * size, totalItems);

  return (
    <div className="pagination">
      <span className="muted">
        {totalItems === 0 ? "Nothing to show" : `${first}–${last} of ${totalItems}`}
      </span>
      <div className="pager">
        <button className="ghost" disabled={page <= 0} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <span className="muted">
          Page {totalPages === 0 ? 0 : page + 1} of {totalPages}
        </span>
        <button
          className="ghost"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          Next
        </button>
      </div>
    </div>
  );
}

/* -- toasts ------------------------------------------------------------- */

type Toast = { id: number; tone: "ok" | "bad"; message: string };

const ToastContext = createContext<(tone: Toast["tone"], message: string) => void>(() => {});

export function useToast() {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(0);

  const notify = useCallback((tone: Toast["tone"], message: string) => {
    const id = next.current++;
    setToasts((current) => [...current, { id, tone, message }]);
    window.setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 4500);
  }, []);

  const value = useMemo(() => notify, [notify]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast ${toast.tone}`}>
            {toast.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

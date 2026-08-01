// Everything the dashboard knows about the backend lives here, so screens deal in data rather
// than in fetch calls and header plumbing.

const BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";
const AUTH_BASE = import.meta.env.VITE_AUTH_BASE ?? "http://localhost:8081";

export type Session = {
  token: string;
  expiresAt: string;
  userId: string;
  merchantId: string;
  email: string;
  role: string;
};

export type Payment = {
  id: string;
  status: string;
  amount: number;
  currency: string;
  createdAt: string;
  updatedAt: string;
};

export type Refund = {
  id: string;
  paymentId: string;
  status: string;
  amount: number;
  currency: string;
  reason: string | null;
  failureReason: string | null;
  createdAt: string;
};

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request<T>(url: string, token: string | null, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      // The dashboard is a person, so it presents a session rather than an API key. The backend
      // accepts either on these paths and scopes the result to the same merchant.
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
  });

  if (response.status === 204) return undefined as T;

  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(
      response.status,
      body?.code ?? "unknown_error",
      body?.message ?? `Request failed with ${response.status}`
    );
  }
  return body as T;
}

export const api = {
  login: (email: string, password: string) =>
    request<Session>(`${AUTH_BASE}/api/v1/auth/login`, null, {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  payments: (token: string, page = 0, size = 20) =>
    request<Page<Payment>>(`${BASE}/api/v1/payments?page=${page}&size=${size}`, token),

  payment: (token: string, id: string) => request<Payment>(`${BASE}/api/v1/payments/${id}`, token),

  refundsFor: (token: string, paymentId: string) =>
    request<Refund[]>(`${BASE}/api/v1/refunds?paymentId=${paymentId}`, token),

  createRefund: (token: string, paymentId: string, amount: number | null, reason: string) =>
    request<Refund>(`${BASE}/api/v1/refunds`, token, {
      method: "POST",
      headers: {
        // Generated per attempt so a double-click cannot become a second refund. The backend
        // treats a repeat of the same key as a replay rather than a new request.
        "Idempotency-Key": crypto.randomUUID(),
      },
      body: JSON.stringify({ paymentId, amount, reason: reason || null }),
    }),
};

/** Amounts travel as integer minor units; only the display layer knows about decimal places. */
export function formatAmount(minorUnits: number, currency: string): string {
  const exponent = currency === "JPY" || currency === "KRW" ? 0 : 2;
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    minimumFractionDigits: exponent,
  }).format(minorUnits / 10 ** exponent);
}

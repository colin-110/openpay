// Everything the dashboard knows about the backend lives here, so screens deal in data rather
// than in fetch calls and header plumbing.

/**
 * A browser loaded over HTTPS refuses to call a plain-HTTP API — "mixed content" — so the default
 * has to follow whichever protocol served this page rather than being fixed at build time. Loaded
 * via the plain :5173 container, that is the direct HTTP ports; loaded via Caddy's TLS front door
 * on :5443 (platform/docker/caddy/Caddyfile), it is the matching HTTPS ports Caddy terminates.
 * VITE_API_BASE / VITE_AUTH_BASE still override this outright when set.
 */
const usingTls = typeof window !== "undefined" && window.location.protocol === "https:";
const BASE = import.meta.env.VITE_API_BASE ?? (usingTls ? "https://localhost:8443" : "http://localhost:8080");
const AUTH_BASE = import.meta.env.VITE_AUTH_BASE ?? (usingTls ? "https://localhost:8444" : "http://localhost:8081");

export type Session = {
  token: string;
  expiresAt: string;
  /** Renews the session without asking for a password again. Never sent to anything but auth. */
  refreshToken: string;
  refreshExpiresAt: string;
  userId: string;
  merchantId: string;
  email: string;
  role: string;
};

export type PaymentStatus =
  | "CREATED"
  | "PENDING_PROVIDER"
  | "AUTHORIZED"
  | "CAPTURED"
  | "FAILED"
  | "CANCELLED"
  | "REFUNDED";

export type RefundStatus = "PENDING" | "SUCCEEDED" | "FAILED";

/** The safe half of an instrument: enough to recognise a payment, never enough to reuse it. */
export type PaymentMethod = {
  type: string | null;
  network: string | null;
  last4: string | null;
  vpa: string | null;
  bank: string | null;
};

/**
 * Where a payment stands with risk screening. Orthogonal to its status: a HELD payment is still
 * CREATED, because holding it interrupts routing rather than moving it somewhere.
 */
export type FraudStatus = "ALLOWED" | "HELD" | "BLOCKED" | "UNSCREENED";

export type Payment = {
  id: string;
  status: PaymentStatus;
  amount: number;
  currency: string;
  /** Null for a payment created without one, which is not the same as an unknown card. */
  paymentMethod: PaymentMethod | null;
  fraudStatus: FraudStatus;
  createdAt: string;
  updatedAt: string;
};

/** One try at getting a payment through an acquirer. A payment can have several. */
export type PaymentAttempt = {
  attemptNo: number;
  provider: string;
  status: string;
  providerReference: string | null;
  failureReason: string | null;
};

export type Refund = {
  id: string;
  paymentId: string;
  status: RefundStatus;
  amount: number;
  currency: string;
  reason: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
};

export type Settlement = {
  id: string;
  merchantId: string;
  currency: string;
  settlementDate: string;
  grossAmount: number;
  feeAmount: number;
  netAmount: number;
  itemCount: number;
  status: string;
  createdAt: string;
};

export type SettlementItem = {
  paymentId: string;
  grossAmount: number;
  feeAmount: number;
  netAmount: number;
  capturedAt: string;
};

export type SettlementDetail = Settlement & { items: SettlementItem[] };

export type Delivery = {
  id: string;
  merchantId: string;
  eventType: string;
  status: string;
  attempts: number;
  responseStatus: number | null;
  lastError: string | null;
  nextAttemptAt: string | null;
  deliveredAt: string | null;
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

/** An expired session is the one failure every screen has to react to the same way. */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

async function request<T>(url: string, token: string | null, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        // The dashboard is a person, so it presents a session rather than an API key. The backend
        // accepts either on these paths and scopes the result to the same merchant.
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init.headers ?? {}),
      },
    });
  } catch {
    // A network-level failure has no status and no body, but callers still need something with a
    // message they can put on screen.
    throw new ApiError(0, "network_error", "Could not reach the server");
  }

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

function query(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== "") search.set(key, String(value));
  }
  const rendered = search.toString();
  return rendered ? `?${rendered}` : "";
}

export const api = {
  login: (email: string, password: string) =>
    request<Session>(`${AUTH_BASE}/api/v1/auth/login`, null, {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  /** Trades a refresh token for a brand new session. The old refresh token stops working either way. */
  refresh: (refreshToken: string) =>
    request<Session>(`${AUTH_BASE}/api/v1/auth/refresh`, null, {
      method: "POST",
      body: JSON.stringify({ refreshToken }),
    }),

  /**
   * Revokes the refresh token server-side. Best-effort: the caller clears its own local session
   * regardless of whether this succeeds, since the point of signing out is that the browser stops
   * acting authenticated — the network call is what stops the token being useful to anyone else.
   */
  logout: (refreshToken: string) =>
    request<void>(`${AUTH_BASE}/api/v1/auth/logout`, null, {
      method: "POST",
      body: JSON.stringify({ refreshToken }),
    }),

  payments: (
    token: string,
    options: { page?: number; size?: number; status?: PaymentStatus | null } = {}
  ) =>
    request<Page<Payment>>(
      `${BASE}/api/v1/payments${query({
        page: options.page ?? 0,
        size: options.size ?? 20,
        status: options.status,
      })}`,
      token
    ),

  payment: (token: string, id: string) => request<Payment>(`${BASE}/api/v1/payments/${id}`, token),

  attempts: (token: string, paymentId: string) =>
    request<PaymentAttempt[]>(`${BASE}/api/v1/payments/${paymentId}/attempts`, token),

  refunds: (
    token: string,
    options: { page?: number; size?: number; status?: RefundStatus | null } = {}
  ) =>
    request<Page<Refund>>(
      `${BASE}/api/v1/refunds${query({
        page: options.page ?? 0,
        size: options.size ?? 20,
        status: options.status,
      })}`,
      token
    ),

  refundsFor: (token: string, paymentId: string) =>
    request<Refund[]>(`${BASE}/api/v1/refunds${query({ paymentId })}`, token),

  settlements: (token: string, options: { page?: number; size?: number } = {}) =>
    request<Page<Settlement>>(
      `${BASE}/api/v1/settlements${query({ page: options.page ?? 0, size: options.size ?? 20 })}`,
      token
    ),

  settlement: (token: string, id: string) =>
    request<SettlementDetail>(`${BASE}/api/v1/settlements/${id}`, token),

  deliveries: (token: string, options: { page?: number; size?: number } = {}) =>
    request<Page<Delivery>>(
      `${BASE}/api/v1/webhooks/deliveries${query({ page: options.page ?? 0, size: options.size ?? 20 })}`,
      token
    ),

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

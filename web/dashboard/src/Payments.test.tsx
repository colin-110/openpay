import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Payments } from "./Payments";
import type { Page, Payment, Session } from "./api";

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    payment: vi.fn(),
    payments: vi.fn(),
  },
}));

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return { ...actual, api: apiMock, isUnauthorized: actual.isUnauthorized, ApiError: actual.ApiError };
});

const session: Session = {
  token: "tok",
  expiresAt: "2026-01-01T00:00:00Z",
  refreshToken: "rt",
  refreshExpiresAt: "2026-02-01T00:00:00Z",
  userId: "u1",
  merchantId: "m1",
  email: "merchant@openpay.test",
  role: "MERCHANT_ADMIN",
};

function payment(id: string): Payment {
  return {
    id,
    status: "CAPTURED",
    amount: 10000,
    currency: "INR",
    paymentMethod: null,
    fraudStatus: "ALLOWED",
    createdAt: "2026-08-02T10:00:00Z",
    updatedAt: "2026-08-02T10:00:00Z",
  };
}

function page(items: Payment[]): Page<Payment> {
  return { items, page: 0, size: 20, totalItems: items.length, totalPages: 1 };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("Payments race guard", () => {
  it("does not let an older, slower request overwrite what a newer one already showed", async () => {
    // The exact bug this guards against: a slow response for the *previous* set of filters
    // resolves after a *newer* request has already landed, and without the guard, whichever
    // resolves last wins on screen even though it isn't the current filter selection.
    let resolveFirst: (value: Page<Payment>) => void = () => {};
    const firstCall = new Promise<Page<Payment>>((resolve) => {
      resolveFirst = resolve;
    });
    // Short ids on purpose: CopyableId truncates anything over 13 characters for display, and
    // a truncated id would make this assertion match on a coincidental substring instead of
    // actually proving which response won.
    apiMock.payments
      .mockReturnValueOnce(firstCall)
      .mockResolvedValueOnce(page([payment("id-newer")]));

    const { rerender } = render(
      <Payments session={session} tick={0} onUnauthorized={() => {}} onOpenPayment={() => {}} selectedId={null} />
    );

    // Triggers the second, faster request while the first is still in flight.
    rerender(
      <Payments session={session} tick={1} onUnauthorized={() => {}} onOpenPayment={() => {}} selectedId={null} />
    );

    await waitFor(() => expect(screen.getByText("id-newer")).toBeInTheDocument());

    // The slow first request finally resolves — it must be ignored, not overwrite the table.
    resolveFirst(page([payment("id-stale")]));
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(screen.getByText("id-newer")).toBeInTheDocument();
    expect(screen.queryByText("id-stale")).not.toBeInTheDocument();
  });
});

describe("Payments filters during an exact-ID search", () => {
  it("disables Status and Rows while a payment-ID search is active", async () => {
    apiMock.payments.mockResolvedValue(page([payment("abc")]));
    apiMock.payment.mockResolvedValue(
      payment("4463f7f1-cd6d-4f7e-a2b9-6da40c78639e")
    );

    render(
      <Payments session={session} tick={0} onUnauthorized={() => {}} onOpenPayment={() => {}} selectedId={null} />
    );
    await waitFor(() => expect(apiMock.payments).toHaveBeenCalled());

    const search = screen.getByPlaceholderText("Search by payment ID");
    fireEvent.change(search, { target: { value: "4463f7f1-cd6d-4f7e-a2b9-6da40c78639e" } });
    fireEvent.click(screen.getByText("Search"));

    await waitFor(() => expect(apiMock.payment).toHaveBeenCalled());

    expect(screen.getByLabelText("Status")).toBeDisabled();
    expect(screen.getByLabelText("Rows")).toBeDisabled();
  });

  it("keeps Status and Rows enabled when no search is active", async () => {
    apiMock.payments.mockResolvedValue(page([payment("abc")]));

    render(
      <Payments session={session} tick={0} onUnauthorized={() => {}} onOpenPayment={() => {}} selectedId={null} />
    );
    await waitFor(() => expect(apiMock.payments).toHaveBeenCalled());

    expect(screen.getByLabelText("Status")).not.toBeDisabled();
    expect(screen.getByLabelText("Rows")).not.toBeDisabled();
  });

  it("rejects a search that is not a full payment ID rather than silently querying it", async () => {
    apiMock.payments.mockResolvedValue(page([]));

    render(
      <Payments session={session} tick={0} onUnauthorized={() => {}} onOpenPayment={() => {}} selectedId={null} />
    );
    await waitFor(() => expect(apiMock.payments).toHaveBeenCalled());

    fireEvent.change(screen.getByPlaceholderText("Search by payment ID"), {
      target: { value: "not-a-uuid" },
    });
    fireEvent.click(screen.getByText("Search"));

    expect(await screen.findByText("Search takes a full payment ID.")).toBeInTheDocument();
    expect(apiMock.payment).not.toHaveBeenCalled();
  });
});

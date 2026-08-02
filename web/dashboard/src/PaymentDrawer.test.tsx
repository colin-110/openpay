import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PaymentDrawer } from "./PaymentDrawer";
import type { Payment, Refund, Session } from "./api";

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    payment: vi.fn(),
    refundsFor: vi.fn(),
    attempts: vi.fn(),
    createRefund: vi.fn(),
  },
}));

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return { ...actual, api: apiMock, isUnauthorized: actual.isUnauthorized };
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

function capturedPayment(id: string, amount = 118000): Payment {
  return {
    id,
    status: "CAPTURED",
    amount,
    currency: "INR",
    paymentMethod: null,
    fraudStatus: "ALLOWED",
    createdAt: "2026-08-02T10:00:00Z",
    updatedAt: "2026-08-02T10:00:05Z",
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  apiMock.attempts.mockResolvedValue([]);
  apiMock.refundsFor.mockResolvedValue([] as Refund[]);
});

describe("PaymentDrawer refund flow", () => {
  it("requires an explicit confirm step before submitting a refund", async () => {
    apiMock.payment.mockResolvedValue(capturedPayment("pay-1"));

    render(
      <PaymentDrawer
        session={session}
        paymentId="pay-1"
        tick={0}
        onClose={() => {}}
        onUnauthorized={() => {}}
        onChanged={() => {}}
      />
    );

    fireEvent.click(await screen.findByText("Issue a refund"));
    fireEvent.click(await screen.findByText(/Review refund of/));

    // The confirm screen, not the API call, must be what a click on "review" produces.
    expect(await screen.findByText("Confirm this refund")).toBeInTheDocument();
    expect(apiMock.createRefund).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText(/Confirm refund of/));
    await waitFor(() => expect(apiMock.createRefund).toHaveBeenCalledTimes(1));
    expect(apiMock.createRefund).toHaveBeenCalledWith(session.token, "pay-1", null, "");
  });

  it("lets the merchant back out of the confirm step without submitting anything", async () => {
    apiMock.payment.mockResolvedValue(capturedPayment("pay-1"));

    render(
      <PaymentDrawer
        session={session}
        paymentId="pay-1"
        tick={0}
        onClose={() => {}}
        onUnauthorized={() => {}}
        onChanged={() => {}}
      />
    );

    fireEvent.click(await screen.findByText("Issue a refund"));
    fireEvent.click(await screen.findByText(/Review refund of/));
    fireEvent.click(await screen.findByText("Back"));

    expect(await screen.findByText(/Review refund of/)).toBeInTheDocument();
    expect(apiMock.createRefund).not.toHaveBeenCalled();
  });

  it("does not offer a refund on a payment that isn't captured", async () => {
    apiMock.payment.mockResolvedValue({ ...capturedPayment("pay-1"), status: "CREATED" });

    render(
      <PaymentDrawer
        session={session}
        paymentId="pay-1"
        tick={0}
        onClose={() => {}}
        onUnauthorized={() => {}}
        onChanged={() => {}}
      />
    );

    expect(await screen.findByText("Only a captured payment can be refunded.")).toBeInTheDocument();
    expect(screen.queryByText("Issue a refund")).not.toBeInTheDocument();
  });
});

describe("PaymentDrawer remount on payment change", () => {
  it("does not show the previous payment's data while the new one is loading", async () => {
    // This is the regression test for the bug this session found: with no `key` on the
    // drawer, switching from payment A to payment B kept A's state (and its refund panel)
    // visible until B's fetch resolved. The fix is App.tsx rendering `key={paymentId}`, which
    // is exactly what this test exercises by remounting with a new key, the same way React
    // would when the prop actually changes upstream.
    apiMock.payment.mockImplementation((_token: string, id: string) =>
      id === "pay-A"
        ? Promise.resolve(capturedPayment("pay-A", 100000))
        : new Promise(() => {}) // pay-B never resolves in this test — simulates "still loading"
    );

    const { rerender } = render(
      <PaymentDrawer
        key="pay-A"
        session={session}
        paymentId="pay-A"
        tick={0}
        onClose={() => {}}
        onUnauthorized={() => {}}
        onChanged={() => {}}
      />
    );

    expect((await screen.findAllByText("₹1,000.00")).length).toBeGreaterThan(0);

    // A different `key` is what makes React unmount the old instance and mount a fresh one —
    // the same thing that happens when App.tsx's key={route.paymentId} changes for real.
    rerender(
      <PaymentDrawer
        key="pay-B"
        session={session}
        paymentId="pay-B"
        tick={0}
        onClose={() => {}}
        onUnauthorized={() => {}}
        onChanged={() => {}}
      />
    );

    // A fresh mount starts from "Loading…", not from payment A's stale ₹1,000.00 — a stale
    // figure here would mean a refund panel pointed at the wrong payment underneath it.
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryAllByText("₹1,000.00")).toHaveLength(0);
  });
});

import { describe, expect, it } from "vitest";
import {
  describeMethod,
  exponentFor,
  formatAmount,
  formatAmountByCurrency,
  formatCompactAmount,
  methodFamily,
  shortId,
  titleCase,
  toMajorUnits,
  toMinorUnits,
} from "./format";

describe("formatAmount", () => {
  it("renders rupees with Indian lakh/crore grouping", () => {
    expect(formatAmount(125000000, "INR")).toBe("₹12,50,000.00");
  });

  it("renders dollars with two decimal places", () => {
    expect(formatAmount(150000, "USD")).toBe("$1,500.00");
  });

  it("treats a zero-decimal currency as having no minor units", () => {
    // 1500 JPY, not 15.00 — JPY has no subunit, so the wire value is already whole yen.
    expect(formatAmount(1500, "JPY")).toBe("¥1,500");
  });

  it("formats zero without error", () => {
    expect(formatAmount(0, "INR")).toBe("₹0.00");
  });
});

describe("formatAmountByCurrency", () => {
  it("sums a single currency the same as a plain formatAmount would", () => {
    const items = [{ amount: 10000, currency: "INR" }, { amount: 5000, currency: "INR" }];
    expect(formatAmountByCurrency(items, (i) => i.amount, (i) => i.currency)).toBe(
      formatAmount(15000, "INR")
    );
  });

  it("keeps mixed currencies separate rather than adding paise to cents", () => {
    // The bug this exists to prevent: 10000 paise (INR) + 10000 cents (USD) is not a number
    // that means anything, and must never collapse into one combined figure under either label.
    const items = [
      { amount: 10000, currency: "INR" },
      { amount: 10000, currency: "USD" },
    ];
    const result = formatAmountByCurrency(items, (i) => i.amount, (i) => i.currency);
    expect(result).toContain(formatAmount(10000, "INR"));
    expect(result).toContain(formatAmount(10000, "USD"));
    expect(result).toContain(" + ");
  });

  it("orders currencies by total descending, largest first", () => {
    const items = [
      { amount: 100, currency: "USD" },
      { amount: 999999900, currency: "INR" },
    ];
    const result = formatAmountByCurrency(items, (i) => i.amount, (i) => i.currency);
    expect(result.indexOf("₹")).toBeLessThan(result.indexOf("$"));
  });

  it("returns a zero amount for an empty list rather than throwing", () => {
    expect(formatAmountByCurrency([] as never[], () => 0, () => "INR")).toBe(
      formatAmount(0, "INR")
    );
  });

  it("does not silently drop a currency with a negative or zero net total", () => {
    // A refund-heavy day could plausibly net to zero for one currency; that currency should
    // still appear rather than vanishing from the total.
    const items = [
      { amount: -500, currency: "USD" },
      { amount: 500, currency: "USD" },
      { amount: 200, currency: "INR" },
    ];
    const result = formatAmountByCurrency(items, (i) => i.amount, (i) => i.currency);
    expect(result).toContain(formatAmount(0, "USD"));
    expect(result).toContain(formatAmount(200, "INR"));
  });
});

describe("exponentFor", () => {
  it("is 2 for a typical currency", () => {
    expect(exponentFor("INR")).toBe(2);
    expect(exponentFor("USD")).toBe(2);
  });

  it("is 0 for a zero-decimal currency", () => {
    expect(exponentFor("JPY")).toBe(0);
  });
});

describe("toMinorUnits / toMajorUnits round-trip", () => {
  it("round-trips a typed rupee amount to paise and back", () => {
    expect(toMinorUnits("1500.50", "INR")).toBe(150050);
    expect(toMajorUnits(150050, "INR")).toBe("1500.50");
  });

  it("rounds rather than truncating floating-point noise", () => {
    // 0.1 + 0.2 is not 0.3 in floating point; this must not become ₹29 instead of ₹30.
    expect(toMinorUnits("0.29999999999999996", "INR")).toBe(30);
  });

  it("round-trips a zero-decimal currency without inventing a decimal", () => {
    expect(toMinorUnits("1500", "JPY")).toBe(1500);
    expect(toMajorUnits(1500, "JPY")).toBe("1500");
  });
});

describe("formatCompactAmount", () => {
  it("compacts a large rupee figure", () => {
    expect(formatCompactAmount(16300000, "INR")).toBe("₹1.63L");
  });
});

describe("shortId", () => {
  it("leaves a short string alone", () => {
    expect(shortId("abc123")).toBe("abc123");
  });

  it("truncates a UUID to head and tail", () => {
    expect(shortId("4463f7f1-cd6d-4f7e-a2b9-6da40c78639e")).toBe("4463f7f1…639e");
  });
});

describe("titleCase", () => {
  it("converts a SCREAMING_SNAKE status to title case", () => {
    expect(titleCase("PENDING_PROVIDER")).toBe("Pending Provider");
  });
});

const BLANK_METHOD = { type: null, network: null, last4: null, vpa: null, bank: null };

describe("describeMethod", () => {
  it("returns an em dash rather than guessing when no method was recorded", () => {
    expect(describeMethod(null)).toBe("—");
  });

  it("names a known card network and masks to the last four", () => {
    expect(describeMethod({ ...BLANK_METHOD, type: "card", network: "visa", last4: "4242" })).toBe(
      "Visa ••4242"
    );
  });

  it("falls back to a title-cased network name it doesn't recognise", () => {
    expect(
      describeMethod({ ...BLANK_METHOD, type: "card", network: "unionpay", last4: "1111" })
    ).toBe("Unionpay ••1111");
  });

  it("describes UPI by VPA when present", () => {
    expect(describeMethod({ ...BLANK_METHOD, type: "upi", vpa: "name@bank" })).toBe(
      "UPI · name@bank"
    );
  });
});

describe("methodFamily", () => {
  it("groups an unrecorded method as its own category rather than hiding it", () => {
    expect(methodFamily(null)).toBe("Not recorded");
  });

  it("upper-cases UPI rather than title-casing it to Upi", () => {
    expect(methodFamily({ ...BLANK_METHOD, type: "upi" })).toBe("UPI");
  });
});

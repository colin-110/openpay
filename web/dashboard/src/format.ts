import type { PaymentMethod } from "./api";

// Amounts cross the wire as integer minor units — paise for INR, cents for USD. Nothing above this
// file is allowed to know that, and nothing below it is allowed to see a decimal.

const ZERO_DECIMAL_CURRENCIES = new Set(["JPY", "KRW", "VND", "CLP", "ISK"]);

export function exponentFor(currency: string): number {
  return ZERO_DECIMAL_CURRENCIES.has(currency) ? 0 : 2;
}

/**
 * Indian grouping for rupees: ₹12,50,000.00, not ₹1,250,000.00. The lakh/crore grouping is what a
 * rupee figure is expected to look like, and getting it wrong is the kind of detail a merchant
 * notices immediately.
 */
function localeFor(currency: string): string {
  return currency === "INR" ? "en-IN" : "en-US";
}

export function formatAmount(minorUnits: number, currency: string): string {
  const exponent = exponentFor(currency);
  return new Intl.NumberFormat(localeFor(currency), {
    style: "currency",
    currency,
    minimumFractionDigits: exponent,
    maximumFractionDigits: exponent,
  }).format(minorUnits / 10 ** exponent);
}

/**
 * Sums minor units *within* each currency present, then formats and joins them — rather than
 * summing raw integers across currencies and labeling the result with whichever currency happened
 * to belong to the first record. A merchant is not guaranteed to transact in only one currency
 * (the payment API accepts whatever `currency` a request specifies), so a KPI tile that just adds
 * paise to cents and calls the total rupees would be reporting a number that means nothing.
 */
export function formatAmountByCurrency<T>(
  items: T[],
  amountOf: (item: T) => number,
  currencyOf: (item: T) => string
): string {
  const totals = new Map<string, number>();
  for (const item of items) {
    const currency = currencyOf(item);
    totals.set(currency, (totals.get(currency) ?? 0) + amountOf(item));
  }
  if (totals.size === 0) return formatAmount(0, "INR");
  return [...totals.entries()]
    .sort((left, right) => right[1] - left[1])
    .map(([currency, amount]) => formatAmount(amount, currency))
    .join(" + ");
}

/** ₹1.63L rather than ₹1,63,240.00, for figures that have to fit in a tile. */
export function formatCompactAmount(minorUnits: number, currency: string): string {
  const exponent = exponentFor(currency);
  return new Intl.NumberFormat(localeFor(currency), {
    style: "currency",
    currency,
    notation: "compact",
    maximumFractionDigits: 2,
  }).format(minorUnits / 10 ** exponent);
}

/** Rupees as typed into a form, back to paise. Rounded because 0.1 + 0.2 is not 0.3. */
export function toMinorUnits(amount: string, currency: string): number {
  return Math.round(Number(amount) * 10 ** exponentFor(currency));
}

export function toMajorUnits(minorUnits: number, currency: string): string {
  const exponent = exponentFor(currency);
  return (minorUnits / 10 ** exponent).toFixed(exponent);
}

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(iso));
}

/** "2 min ago" — how long a payment has been sitting is more useful than when it started. */
export function formatRelative(iso: string): string {
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return "just now";
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["minute", 60],
    ["hour", 3600],
    ["day", 86400],
  ];
  const relative = new Intl.RelativeTimeFormat("en-IN", { numeric: "auto" });
  let chosen: [Intl.RelativeTimeFormatUnit, number] = units[0];
  for (const unit of units) {
    if (seconds >= unit[1]) chosen = unit;
  }
  return relative.format(-Math.floor(seconds / chosen[1]), chosen[0]);
}

/** Identifiers are UUIDs. Showing the head and tail keeps them recognisable without the width. */
export function shortId(id: string): string {
  return id.length <= 13 ? id : `${id.slice(0, 8)}…${id.slice(-4)}`;
}

const NETWORK_NAMES: Record<string, string> = {
  rupay: "RuPay",
  visa: "Visa",
  mastercard: "Mastercard",
  amex: "Amex",
  diners: "Diners",
};

/**
 * How a payment reads in a table. A payment created without a method returns an em dash rather
 * than a guess: not knowing is a real answer, and "Card" would be a made-up one.
 */
export function describeMethod(method: PaymentMethod | null): string {
  if (!method || !method.type) return "—";
  switch (method.type) {
    case "card": {
      const network = method.network ? (NETWORK_NAMES[method.network] ?? titleCase(method.network)) : "Card";
      return method.last4 ? `${network} ••${method.last4}` : network;
    }
    case "upi":
      return method.vpa ? `UPI · ${method.vpa}` : "UPI";
    case "netbanking":
      return method.bank ? `Netbanking · ${method.bank}` : "Netbanking";
    case "wallet":
      return method.bank ? `Wallet · ${method.bank}` : "Wallet";
    default:
      return titleCase(method.type);
  }
}

/** Just the family, for grouping: "UPI", "Card", "Netbanking". */
export function methodFamily(method: PaymentMethod | null): string {
  if (!method || !method.type) return "Not recorded";
  return method.type === "upi" ? "UPI" : titleCase(method.type);
}

export function titleCase(status: string): string {
  return status
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

/**
 * Two letters for the avatar, taken from the person's name rather than the raw string.
 *
 * The obvious `email.slice(0, 2)` is wrong for any address that does not begin with a name, and
 * the demo seeder produced exactly that: it prefixed a run id, so `232f9ebc-owner@openpay.test`
 * rendered an avatar reading "23".
 *
 * Splitting on separators and dropping the segments that contain digits, rather than splitting on
 * every non-letter — a hex run id like `232f9ebc` is mostly letters, so the naive version picked
 * "FE" out of the middle of it, which looks like a name and is not one.
 */
export function initialsFor(email: string): string {
  const localPart = (email ?? "").split("@")[0] ?? "";
  const named = localPart
    .split(/[.\-_+]+/)
    .filter((segment) => segment.length > 0 && !/\d/.test(segment));

  if (named.length === 0) {
    return "?";
  }
  if (named.length === 1) {
    return named[0].slice(0, 2).toUpperCase();
  }
  return (named[0][0] + named[1][0]).toUpperCase();
}

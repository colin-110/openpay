# ADR-0006: Money is `BIGINT` minor units everywhere

**Status:** Accepted

## Context

An amount has to be stored, transported over JSON, compared, summed, and split into a fee and a net.
The candidates are a floating point type, a `DECIMAL`, or an integer count of the currency's smallest
unit.

Floating point is out immediately — `0.1 + 0.2` is a well-known reason not to hold money in a
`double`, and a payments platform that rounds is a payments platform that loses arguments with
merchants.

`DECIMAL(19,4)` is the conventional answer and it is defensible. The problem is not the database
type, it is everything around it: JSON has no decimal type, so a `DECIMAL` column reaches a client as
a JSON number and comes back through some library as a `double`. The precision survives storage and
is lost in transit, which is worse than losing it consistently.

## Decision

`BIGINT`, holding the count of the currency's smallest unit — paise for INR, cents for USD.

Jackson is configured with `accept-float-as-int: false`, so `{"amount": 10.99}` is a `400` rather
than a payment for 10. Silently truncating a fractional amount would charge a customer the wrong
number and return `201`.

Fees are computed in basis points against the integer amount, so the arithmetic stays in integers
from end to end.

## Consequences

**Every amount in the system is an integer, in every layer.** Column, entity, event payload, API
response, and dashboard. There is no boundary at which a conversion could go wrong, because there is
no conversion.

**Amounts are unreadable at a glance.** `5000000` is fifty thousand rupees, and nothing in a log
line says so. Formatting is the client's job — the dashboard does it, and the API deliberately does
not, because an API that returns a formatted string has made a locale decision on the caller's
behalf.

**A minor-unit threshold is meaningless without a currency.** 5,000,000 paise and 5,000,000 cents
are not the same policy, which is why an `AMOUNT_OVER` fraud rule is refused unless it names one,
and why routing rules match currency before amount.

**Currencies with no minor unit still work**, because the "smallest unit" is defined per currency
rather than assumed to be one hundredth. JPY amounts are whole yen.

**64 bits is not a constraint worth worrying about.** The largest representable INR amount is a
number with seventeen digits in front of the decimal point.

## Alternatives considered

**`DECIMAL` in the database, string in the API.** Solves the transit problem by making the wire
format a string. Rejected because every client then has to parse it, and the first one that does
`parseFloat` has undone the whole scheme without anything failing.

**A money type with an embedded currency.** Better modelling — an amount without a currency is not a
quantity of anything. Rejected as more machinery than this earns: the currency is already beside the
amount in every table and every payload, and an embeddable would add a column mapping without
removing a class of bug.

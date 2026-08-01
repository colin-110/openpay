# ADR-0010: Correlation IDs instead of a distributed tracing backend

**Status:** Accepted

## Context

A payment touches the gateway, auth-service, payment-service, fraud-service, Kafka,
provider-router-service, an acquirer, webhook-service, and then the ledger, settlement, and
notification consumers. When something goes wrong, the question is always the same: what happened
to *this one payment*, everywhere.

The complete answer is distributed tracing — OpenTelemetry instrumentation, W3C context propagation,
and a backend (Tempo, Jaeger, Zipkin) to store and query the spans. It gives timing per hop, which a
log line cannot.

## Decision

A correlation id, propagated everywhere and put in every log line, with Loki as the query surface.

`CorrelationIdFilter` reads `X-Correlation-Id` or mints one, puts it in the SLF4J MDC, and echoes it
on the response. It travels through Kafka in the event envelope, and every consumer restores it into
the MDC before handling a message and clears it afterwards. The log pattern in every service
includes it, Promtail ships those lines to Loki, and Grafana turns the id in a line into a link that
pulls up every service's view of that request.

## Consequences

**One search answers "what happened to this payment".** `{container=~"openpay-.+"} |= "<id>"`
returns every line from every service, in order, including the asynchronous hops — which is the part
a request-scoped tracing header would not have covered without deliberate propagation through the
event envelope anyway.

**There are no span timings.** "Where did the two seconds go" is answerable from log timestamps and
from the RED metrics per service, which is coarser than a flame graph. That is the real cost.

**The correlation id is not a Loki label.** It is unbounded — one value per request — and a label
like that gives Loki a stream per request, which is how a small deployment runs out of memory. It
stays in the line, where a filter expression finds it just as well.

**Nothing had to be added to the dependency tree.** No agent, no exporter, no backend to run,
operate, and retain.

## Alternatives considered

**Micrometer Tracing with an OTLP exporter to Tempo.** The right answer, and the natural next step:
Spring Boot's instrumentation is largely automatic, and the correlation id could stay alongside the
trace id rather than being replaced by it. Rejected for now because it means running and retaining
another datastore for a platform whose slowest operation is deliberately a simulated bank call, and
because the questions actually being asked here — *what happened*, not *where did the time go* — are
answered by the logs.

**Trace id only, with no correlation id.** Rejected because a trace id is generated per request by
the tracing library, and a merchant who wants to reference one of their own requests in a support
conversation has no way to supply it. `X-Correlation-Id` is a header they can set.

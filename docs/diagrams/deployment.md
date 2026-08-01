# Deployment

Two topologies, one set of jars. Every service is configured entirely through environment variables,
which is what makes the same artefact runnable on a laptop, in Compose, and in a cluster without a
separate profile.

## Local

```mermaid
flowchart TB
    subgraph host["Developer machine"]
        subgraph infra["docker-compose.yml — infrastructure"]
            pg[("PostgreSQL :5433")]
            redis[("Redis :6379")]
            kafka[("Kafka :9092 / :29092")]
            prom["Prometheus :9090"]
            graf["Grafana :3000"]
            loki["Loki :3100"]
            promtail["Promtail"]
        end

        subgraph apps["docker-compose.apps.yml — services"]
            services["11 services<br/>:8080-:8089, :9001, :9002"]
        end

        maven["…or the same services<br/>from Maven, on the host"]
    end

    services --> pg
    services --> redis
    services --> kafka
    maven -.->|"same ports, same variables"| pg
    promtail -->|"container logs"| loki
    prom -->|"scrape /actuator/prometheus"| services
    graf --> prom
    graf --> loki
```

**Kafka advertises two addresses**, and it has to. A container resolves `kafka:29092`; a process on
the host cannot, and needs `localhost:9092`. Advertising only one of them leaves every client on the
other side unable to connect after its first metadata fetch — which looks like a working broker
until the first consumer starts.

**Prometheus scrapes two addresses per service** for the same reason, and accepts that whichever
workflow is not in use leaves a permanently red target. That is the honest trade: one red row in the
Prometheus UI, in exchange for metrics that work in both workflows without editing a file.

## Kubernetes

```mermaid
flowchart TB
    internet(("Internet"))

    subgraph cluster["Cluster"]
        ingress["ingress-nginx<br/><i>TLS terminates here</i>"]

        subgraph ns["namespace: openpay"]
            subgraph published["Published"]
                gw["gateway-service"]
                auth["auth-service<br/><i>/api/v1/auth/login only</i>"]
                wh["webhook-service"]
            end

            subgraph unpublished["Cluster-internal"]
                rest["merchant, payment, router,<br/>ledger, settlement,<br/>notification, fraud"]
            end

            subgraph data["Datastores"]
                pgk[("PostgreSQL<br/>StatefulSet")]
                redisk[("Redis")]
                kafkak[("Kafka<br/>StatefulSet")]
            end
        end
    end

    internet -->|"api.*"| ingress
    internet -->|"auth.*"| ingress
    internet -->|"webhooks.*"| ingress
    ingress --> gw
    ingress --> auth
    ingress --> wh
    gw --> rest
    published --> data
    unpublished --> data
```

**Three hosts published, nothing else.** Every operator surface — the ledger, the review queue,
settlement runs, the routing table, the audit log, dead-letter replay — is guarded by a shared
token, and a shared token is a reasonable control inside a cluster and a poor one facing the
internet. They are reached with `kubectl port-forward` or an internal-only ingress.

**The network policy enforces the same split at the socket level.** The token tiers guard HTTP
handlers, not ports: without a policy, a compromised mock-bank pod opens a connection straight to
the payments database and none of them apply.

**Replica counts are reasoned, not uniform.** payment-service scales because the outbox relay claims
rows with `FOR UPDATE SKIP LOCKED`; provider-router-service does not, because its circuit breaker
state is per instance; settlement-service does not, because its job is scheduled. See
[platform/k8s/README.md](../../platform/k8s/README.md).

## Probes

```mermaid
flowchart LR
    start["Pod starts"] --> startup["startupProbe<br/>/actuator/health/liveness<br/><i>up to 3 minutes</i>"]
    startup --> live["livenessProbe<br/>/actuator/health/liveness<br/><i>the process only</i>"]
    startup --> ready["readinessProbe<br/>/actuator/health/readiness<br/><i>ready for traffic</i>"]
    live -->|"fails"| restart["restart the pod"]
    ready -->|"fails"| remove["remove from the Service"]
```

Three probes rather than one, and each answers a different question:

- **Startup** is generous because a service applying Flyway migrations to a cold database takes far
  longer to start than it ever takes to answer once running. One probe for both would mean choosing
  between killing a pod mid-migration and taking a minute to notice a hung one.
- **Liveness** reports on the process and nothing else. A liveness probe that fails because a
  *dependency* is unwell restarts a healthy pod, and during a database blip that becomes a restart
  loop across the whole platform at the worst possible moment.
- **Readiness** uses `/actuator/health/readiness`, not the aggregate `/actuator/health` — which
  includes dependencies, and would therefore pull a pod out of rotation because something downstream
  is unwell, turning one service's problem into everyone's outage.

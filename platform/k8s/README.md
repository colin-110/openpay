# Deploying OpenPay to Kubernetes

Plain manifests with a kustomization, not a Helm chart. There is one deployment of this platform,
every service is configured identically, and the only thing that varies between environments is the
image tag and a handful of hostnames. A chart would add templating in exchange for nothing.

## What is here

| File | What it does |
| --- | --- |
| `00-namespace.yaml` | The `openpay` namespace |
| `10-config.yaml` | Everything that is not a secret: hostnames, ports, service URLs |
| `11-secrets.example.yaml` | A **template**. Placeholders only — see below |
| `20-infrastructure.yaml` | PostgreSQL, Redis, Kafka, for a demo cluster |
| `30-services.yaml` | The twelve application Deployments and Services |
| `40-ingress.yaml` | The three hosts reachable from outside |
| `50-autoscaling.yaml` | HPAs and pod disruption budgets |
| `60-network-policy.yaml` | Default-deny, then what the architecture actually needs |

## Deploying

Build and push the images first. The tag is whatever your pipeline produced:

```bash
docker build -t openpay/payment-service:1.4.2 --build-arg MODULE=services/payment-service --build-arg ARTIFACT=payment-service .
```

Create the Secret out of band. It is not in the repo, and it should not be:

```bash
kubectl create namespace openpay
```

```bash
kubectl -n openpay create secret generic openpay-secrets --from-literal=OPENPAY_ADMIN_TOKEN="$(openssl rand -hex 32)" --from-literal=OPENPAY_OPS_TOKEN="$(openssl rand -hex 32)" --from-literal=OPENPAY_INTERNAL_TOKEN="$(openssl rand -hex 32)" --from-literal=OPENPAY_JWT_SECRET="$(openssl rand -hex 32)" --from-literal=DB_PASSWORD="$(openssl rand -hex 24)" --from-literal=MOCK_BANK_A_SECRET="$(openssl rand -hex 32)" --from-literal=MOCK_BANK_B_SECRET="$(openssl rand -hex 32)"
```

Four distinct tokens, not one repeated four times. The entire point of the tiers is that leaking the
credential embedded in a reporting dashboard does not also leak the one that onboards merchants;
reusing a value undoes that quietly and completely.

Then set the tag and apply:

```bash
kustomize edit set image openpay/payment-service=openpay/payment-service:1.4.2
```

```bash
kubectl apply -k platform/k8s
```

Check it came up:

```bash
kubectl -n openpay rollout status deployment/payment-service --timeout=5m
```

## Probes

Three probes per service, and the split is deliberate.

**Startup** is generous — up to three minutes — because a service applying Flyway migrations to a
cold database takes far longer to start than it ever takes to answer once it is running. Folding
this into liveness would mean choosing between killing a pod mid-migration and taking a full minute
to notice a hung one.

**Liveness** hits `/actuator/health/liveness`, which reports on the process and nothing else. A
liveness probe that fails because a *dependency* is unwell restarts a perfectly healthy pod, and
during a database blip that becomes a restart loop across the whole platform at the worst moment.

**Readiness** hits `/actuator/health/readiness`, not `/actuator/health`. The aggregate health of a
service includes its dependencies, so using it would take a pod out of rotation because something
downstream is unwell — turning one service's problem into an outage for everyone.

## Scaling

Only the four services on the synchronous path of a merchant's request have an HPA: the gateway,
auth, payments, and fraud screening. The rest are event consumers, and adding replicas to a consumer
does nothing until there are more partitions to read — which an HPA cannot know.

Two services are pinned to one replica on purpose:

- **provider-router-service** keeps circuit breaker state in memory, so a second replica has its own
  opinion about which acquirers are unhealthy and has to learn it independently. Sharing that state
  is real work and has not been done.
- **settlement-service** runs a scheduled job, and a second instance would process the same window
  concurrently. The unique constraint on `(merchant, currency, date)` stops that becoming two
  payouts, but relying on a constraint to catch a design mistake is not a plan.

**payment-service** scales freely, because the outbox relay claims rows with
`FOR UPDATE SKIP LOCKED`. That is what makes a second replica divide the work rather than publish
every event twice.

## What is not exposed

The ingress publishes three hosts: merchant API traffic, the login endpoint, and acquirer callbacks.

Everything else is absent on purpose. The ledger, the review queue, settlement runs, the routing
table, the audit log, and dead-letter replay are operator surfaces. They are guarded by a shared
token, and a shared token is a reasonable control inside a cluster and a poor one facing the
internet. Reach them with `kubectl port-forward`, or put them behind an internal-only ingress with
real authentication in front:

```bash
kubectl -n openpay port-forward svc/fraud-service 8089:8089
```

## Network policy

Default-deny ingress, then three allowances: the ingress controller may reach the three published
services, application pods may reach each other, and only application pods may reach the datastores.

That last rule is why the file exists. Without it a compromised mock-bank pod can open a socket
straight to the payments database, and none of the token tiers help — they guard HTTP handlers, not
sockets. The mock banks are excluded by name because they stand in for an external party, and an
external party has no business reaching the payments database.

This needs a CNI that enforces NetworkPolicy. On one that does not, these apply cleanly and do
nothing at all, which is worth confirming before relying on them.

## What would have to change for this to be real

Stated plainly, because manifests that look production-ready and are not are worse than none:

- **The datastores.** One PostgreSQL pod, one Redis pod, one Kafka broker, each with a single
  volume. A real deployment uses a managed database with backups and a managed broker with a
  replication factor above one. The Kafka settings here (`replication-factor: 1`,
  `min.insync.replicas: 1`) exist because there is only one broker, and every one of them is wrong
  for production.
- **Secrets.** These come from `kubectl create secret`, which puts them in etcd and in the shell
  history of whoever ran it. Real deployments source them from a secret manager — External Secrets,
  Sealed Secrets, or the cloud provider's CSI driver.
- **The acquirers.** `mock-bank-a` and `mock-bank-b` are simulators. In a real deployment they are
  replaced by real acquirer endpoints in `provider_routing_rules`, and these two Deployments are
  deleted.
- **Resource requests.** The values here are guesses that let everything schedule on a laptop
  cluster. Load testing (`tests/performance/`) is what replaces them with measurements.

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

## Verified against a real cluster

Deployed to a real (if single-node, laptop-class) Kubernetes cluster — Docker Desktop's built-in
control plane, ingress-nginx installed on top — rather than only validated with `kubectl kustomize`.
That surfaced three bugs `kubectl kustomize` had no way to catch, because none of them are a YAML
problem:

1. **The image tag placeholder didn't work.** `openpay/<service>:${TAG}` looks like a normal
   build-arg substitution, but `${` and `}` are not legal characters in a Docker tag, so
   kustomize's `images:` transformer silently failed to parse the reference and left it exactly as
   written. `kubectl apply -k` reported success; every pod came up requesting the literal string
   `openpay/auth-service:${TAG}` and sat in `InvalidImageName`. Fixed by using a syntactically valid
   placeholder tag (`:placeholder`) that kustomize can actually parse and replace — see
   `30-services.yaml`.
2. **`runAsNonRoot: true` refused to start any service.** Both Dockerfiles set a non-root user by
   *name* (`USER openpay`, `USER nginx`), which is exactly what a container image is supposed to
   do — but the kubelet cannot resolve a name to a UID without running the container, and
   `runAsNonRoot` has to know the number before it starts anything. Every pod sat in
   `CreateContainerConfigError`. Fixed by pinning a fixed numeric uid/gid in each Dockerfile and
   stating the same number in `runAsUser`/`runAsGroup` in the manifest, so the kubelet is told
   rather than asked to infer. The dashboard's nginx also had to move off port 80, since a
   non-root process can't bind a privileged port without a capability this container deliberately
   does not have.
3. **kafka-0 deadlocked on its own DNS record.** A headless Service only publishes a pod's DNS
   record once that pod is Ready — the same property that makes the four service Deployments'
   pod-to-pod discovery work correctly. Single-node KRaft resolves its own advertised hostname
   (`kafka-0.kafka:9093`) to reach its own controller quorum *during* startup, before it can be
   Ready. That's a pod that can never become Ready trying to publish the DNS record its own
   readiness depends on — worked every time against Docker Compose, where DNS has no such gate,
   and never once against a real cluster. Fixed with `publishNotReadyAddresses: true` on the kafka
   Service.

None of the three showed up in `docker compose`, and none of them are visible by reading the YAML —
they only exist at the boundary between the manifest and a real kubelet/DNS/image-transform
pipeline, which is the whole reason to actually deploy rather than trust that valid YAML means a
working cluster.

**What that same deployment measured**, worth recording because it is a number rather than a
guess: every service scaled to exactly one replica (`minReplicas: 1` on every HPA, `replicas: 1` on
every Deployment) still holds **97% of a 7.4 GiB node** at rest. The committed manifests default
several services to 2 replicas and four HPAs to a floor of 2 — that configuration does not fit a
node this size at all; it needs either more memory allocated to the cluster or a deliberately
smaller demo footprint. This is the concrete version of the "resource requests are guesses" note
below, not a new problem — just no longer a guess.

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
- **Resource requests.** The values here are guesses, and measurement (see above) already shows
  they do not fit a modest single-node cluster at the committed replica counts. Load testing
  (`tests/performance/`) is what replaces the per-service numbers with real measurements; a real
  deployment also needs a node pool sized for more than one node's worth of memory.

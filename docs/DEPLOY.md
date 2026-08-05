# Deploying OpenPay

A single host running `docker compose`, with Caddy terminating TLS. Not Kubernetes: the manifests
in [`platform/k8s/`](../platform/k8s/) exist and have been run against a real cluster, but a managed
control plane costs more per month than the machine this whole platform fits on, and for one
instance it buys nothing.

## What it needs

Thirteen JVMs, PostgreSQL, Kafka, Redis, Prometheus, Grafana, Loki.

**Measured: about 3.7 GB for the twenty containers a payment actually needs**, and roughly 4.1 GB
with the observability stack switched on. The declared `mem_limit` values add up to more than that
because they are *ceilings*, not reservations — a JVM sizes its heap as a percentage of the cap and
only grows into it under pressure.

Take the number from `docker stats` filtered to this stack, not from the raw total. The unfiltered
figure counts every container on the machine, which is how an earlier draft of this page reported
3.2 GB — a number that happened to be lower while being measured wrongly:

```bash
docker stats --no-stream --format "{{.Name}}|{{.MemUsage}}" | grep "^openpay"
```

The distinction matters when choosing a host. A 4 GB machine runs the lean stack; earlier drafts of
this page said 8 GB minimum, which would have sent you to a machine twice the size and price.

`demo-storefront` is not among the thirteen: it is a merchant integration, not part of the platform,
and [step 6](#6-the-shop-on-a-second-host) puts it on its own host. Left in the platform's compose
file it would run here quite happily — it is the smallest service in the repository — but that
arrangement makes a claim about the architecture that is not true.

| | Minimum (lean) | Comfortable (with observability) |
| --- | --- | --- |
| RAM | 4 GB | 8 GB |
| vCPU | 2 | 4 |
| Disk | 20 GB | 40 GB |

**4 GB is genuinely enough**, given the 3.7 GB measured above — but it leaves nothing for the
first build, which compiles the whole Maven reactor and wants more memory than the running stack
does. Either build elsewhere and pull the images (CI publishes them on every push to `main`), or
drop Loki and Promtail while building (`docker compose ... up -d --scale loki=0 --scale
promtail=0`). They are the least useful of the observability stack for a demo and the heaviest
after Kafka; Grafana and Prometheus are worth keeping, being most of the visible payoff.

Roughly what that costs, running continuously:

| Host | Specs | Per month |
| --- | --- | --- |
| Hetzner CX32 | 4 vCPU, 8 GB | ~€7 |
| AWS t3.large | 2 vCPU, 8 GB | ~$60 on-demand, ~$38 on a 1-year Savings Plan |
| AWS t3.xlarge | 4 vCPU, 16 GB | ~$120 |
| Oracle Cloud A1.Flex | 4 ARM cores, 24 GB | free, permanently |

Oracle's free tier genuinely fits this and costs nothing, with two catches: the images are built
`linux/amd64` and would need `docker buildx build --platform linux/arm64` (easy here — Temurin
publishes ARM images and Java bytecode does not care), and the free ARM capacity is heavily
oversubscribed, so provisioning can take several attempts.

Skip EKS unless something else requires it: the control plane alone is $73/month, before nodes,
and a load balancer and NAT gateway add roughly $48 more. That is more than the server.

## 1. Generate real credentials

The tokens in the main README are development defaults and are published, which means they are
public. **Every one of them has to be replaced before the platform is reachable from the internet.**
`OPENPAY_ADMIN_TOKEN` alone can onboard merchants and mint API keys.

```bash
cat > platform/docker/.env <<EOF
OPENPAY_ADMIN_TOKEN=$(openssl rand -base64 32)
OPENPAY_OPS_TOKEN=$(openssl rand -base64 32)
OPENPAY_INTERNAL_TOKEN=$(openssl rand -base64 32)
OPENPAY_JWT_SECRET=$(openssl rand -base64 48)
MOCK_BANK_A_SECRET=$(openssl rand -base64 24)
MOCK_BANK_B_SECRET=$(openssl rand -base64 24)
OPENPAY_DASHBOARD_ORIGINS=https://pay.example.com
EOF
chmod 600 platform/docker/.env
```

`.env` is in `.gitignore`. Check that it still is before committing anything.

`OPENPAY_JWT_SECRET` must be at least 32 bytes — auth-service refuses to start otherwise, which is
deliberate. Everything else fails closed too: an unset admin token does not mean "no auth", it
means the stack will not come up.

## 2. Firewall

```bash
# AWS security group, or ufw, or whatever the host uses — the rule is the same.
80/tcp    from anywhere      # Caddy redirects to 443
443/tcp   from anywhere      # Caddy terminates TLS
22/tcp    from your IP only
```

**Nothing else.** In particular not 8080–8089, 5433, 9092, 6379, 3000 or 9090. Those are published
to the host in the compose file for local development, and on a public machine each is a way past
the gateway:

- **5433** is PostgreSQL with the credentials in the compose file.
- **6379** is Redis with no password at all.
- **9090 and 3000** are Prometheus and Grafana, and Grafana is configured for anonymous viewer
  access — fine on a laptop, an information leak on the internet.
- **8081–8089** are the services *behind* the gateway, so reaching them directly skips the rate
  limiting and, for the internal endpoints, is only protected by the internal token.

If the host has a public IP and no security group, remove the `ports:` mappings for everything
except Caddy rather than trusting a firewall you have to remember to configure.

## 3. TLS

Caddy is already in the compose file and gets a certificate from Let's Encrypt automatically, given
a domain that resolves to the host.

```bash
# platform/docker/caddy/Caddyfile
pay.example.com {
    reverse_proxy gateway-service:8080
}

dashboard.example.com {
    reverse_proxy dashboard:8080
}
```

Set `OPENPAY_DASHBOARD_ORIGINS` to the dashboard's real origin. It is empty by default and an empty
value refuses every cross-origin request, so the dashboard will silently fail to call the API until
this matches exactly — scheme included.

**Then set `RATE_LIMIT_TRUST_FORWARDED_FOR=true`, and only once Caddy is actually in front.**

Tokenisation is rate limited per caller as well as per merchant, because a publishable key is
presented by every visitor rather than by one merchant's server. Behind a proxy, every request's
remote address is *the proxy's* — so with this left off, every visitor on the internet shares one
bucket and a per-caller limit of 20 a minute becomes a global one. The symptom is a checkout that
starts refusing strangers the moment it gets busy, which is the worst possible time.

The reverse is equally wrong: turned on with nothing overwriting `X-Forwarded-For`, any caller can
reset their own bucket by sending the header. There is no default that is correct in both places,
so it is a deployment decision rather than a guess:

```bash
# In platform/docker/.env — correct because step 3 puts Caddy in front.
RATE_LIMIT_TRUST_FORWARDED_FOR=true
```

## 4. Start it

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

The first build compiles the whole Maven reactor and takes 10–20 minutes on a small host. It also
wants more memory than a 2 vCPU / 8 GB box has if it builds services in parallel — build one first
so the shared build stage is cached, then the rest reuse it:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml build gateway-service
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml build
```

Better still, build the images somewhere with more memory and push them to a registry; CI already
publishes them to GHCR on every push to `main`.

## 5. Check it actually works

```bash
OPENPAY_ADMIN_TOKEN=... OPENPAY_OPS_TOKEN=... OPENPAY_INTERNAL_TOKEN=... \
  GATEWAY_URL=https://pay.example.com ./scripts/e2e.sh
```

96 checks against the running deployment, including the asynchronous capture flow. If that passes,
the deployment is real rather than merely up.

## 6. The shop, on a second host

Everything above deploys the platform. The shop that pays into it — `demo-storefront` — is meant to
run somewhere else, and it is worth being clear about why, because deploying it alongside is one
line of compose and is the wrong answer.

Co-located, "the shop talks to the gateway" and "the shop *is* the gateway" look identical. Both are
on the same Docker network, both resolve each other by service name, and the one claim the shop
exists to make — that an ordinary server, holding nothing but an API key, is the whole integration —
is exactly the claim that arrangement cannot make. On its own host it has no database, no broker, no
shared network and no secret beyond one merchant key, and the only thing crossing the boundary is
HTTPS to a public hostname. That is a merchant integration, and it is visibly one.

It also demonstrates the interesting failure. Stop the platform and the shop stays up and says the
payment could not be taken; stop the shop and the platform never notices. Two deployments, one
integration, failing independently — which is the thing a single compose file quietly hides.

The shop needs one merchant's API key. Issue it **on the platform host, over localhost** — not
remotely from the shop's host:

```bash
OPENPAY_ADMIN_TOKEN=... ./scripts/seed-demo.sh
```

That is not an arbitrary preference. Issuing an API key is `POST /api/v1/api-keys` on auth-service,
and step 3's Caddyfile publishes only `/login`, `/refresh` and `/logout` from that service — key
issuance, merchant onboarding and user creation are deliberately not reachable from the internet,
because the admin token is the one credential that mints other credentials. Running the seeder on
the host is what keeps that true. The script defaults to `localhost:8080` and `localhost:8081`,
which is exactly right there.

Copy the key it prints to the shop's host by hand. It is the only thing that needs to travel
between the two.

Then, on the second host — 512 MB of RAM and outbound HTTPS is genuinely all it needs:

```bash
cat > platform/docker/.env <<EOF
GHCR_OWNER=your-github-username
OPENPAY_TAG=$(git rev-parse HEAD)
SHOP_HOSTNAME=shop.example.com
OPENPAY_GATEWAY_URL=https://pay.example.com
OPENPAY_DASHBOARD_URL=https://dashboard.example.com
# Both keys the seeder printed. The secret one stays on this host and takes the payment; the
# publishable one is rendered into the checkout page and can only mint a token.
STOREFRONT_API_KEY=<the secret key seed-demo.sh printed>
STOREFRONT_PUBLISHABLE_KEY=<the publishable key seed-demo.sh printed>
EOF
chmod 600 platform/docker/.env
```

```bash
docker compose -f platform/docker/docker-compose.storefront.yml up -d
```

It pulls the image CI published rather than building, so there is no JDK and no 20-minute Maven
reactor on this box. Every variable above is mandatory — the compose file fails to start rather
than defaulting, because each of the quiet defaults here is a shop that comes up looking fine and
paying into localhost.

Four things worth knowing:

- **`SHOP_HOSTNAME` must already resolve to this host** before the first `up`. Caddy orders a
  certificate immediately, and a failed order backs off rather than retrying tightly.
- **The shop's origin must be in `OPENPAY_DASHBOARD_ORIGINS` on the platform**, alongside the
  dashboard's. This is the one place the two deployments are not fully independent, and it is worth
  understanding rather than just copying.

  The checkout page sends the card **straight to the gateway**, not to the shop's server, and gets
  back a token — which is precisely why the shop's server never holds a card number and could not
  leak one. That single call is cross-origin, so the gateway has to allow it. Everything else still
  goes shop-server-to-gateway with no browser involved.

  What makes that safe is not the origin list but the credential: the page carries a **publishable**
  key (`opk_pub_…`, scope `tokens:create`), which mints tokens and is refused by every read and
  write path on the platform. The shop's secret key never reaches the browser.

  ```bash
  OPENPAY_DASHBOARD_ORIGINS=https://dashboard.example.com,https://shop.example.com
  ```
- **The API key is scoped to one merchant** and never reaches the page. The worst it can do if this
  host is compromised is create and read that merchant's payments. Rotate by reissuing and
  restarting; nothing caches it.
- **The gateway rate limit applies**, and the checkout page polls. Polls are `GET`, and the limiter
  only counts mutating requests, so a busy page cannot rate-limit itself out — but `POST /payments`
  is counted, at 30 per 5-second window per merchant, shared across everything using that key.

Check it end to end by buying something at `https://shop.example.com` and watching the payment
appear in the dashboard. That path — browser to shop to gateway to acquirer and back — is the one
that proves both deployments and the network between them, and it is the one to demo.

## Keeping it up

**Restarts are already handled.** Every service is `restart: unless-stopped`, so a crash or a host
reboot brings the platform back without a systemd unit.

**Watch three things.** Grafana has the panels:

- `openpay_outbox_unpublished` — if this climbs and stays up, events are not being relayed. Payments
  are still being accepted and none of them are advancing, which every liveness probe reports as
  perfectly healthy.
- `hikaricp_connections_pending` — requests waiting for a database connection. Non-zero means the
  pool is the bottleneck; the ceilings are per service in each `application.yml`.
- Disk. Kafka retains segments and PostgreSQL grows; neither is bounded by anything in this repo.

**Back up PostgreSQL.** The `postgres-data` volume is the entire platform state — payments, the
ledger, audit log, everything. Nothing here backs it up.

```bash
docker exec openpay-postgres pg_dumpall -U openpay | gzip > openpay-$(date +%F).sql.gz
```

## What this deployment is not

Worth being plain about, because a public URL invites the assumption that it is more than it is:

- **No real money moves.** Both acquirers are simulated. Settlement clears a payable in the ledger
  and sends nothing anywhere.
- **One instance of everything.** No redundancy: the host is a single point of failure, and so is
  PostgreSQL. The outbox relay is safe to run on several replicas (`FOR UPDATE SKIP LOCKED`), which
  is the interesting half of the problem, but nothing here runs more than one.
- **No secret management.** Credentials live in a `.env` file on the host. A real deployment uses a
  secret manager, and the Kubernetes manifests already expect `Secret` objects rather than this.
- **Throughput is one machine's.** [The measured numbers](../tests/performance/baseline.md) are
  from a laptop running all 22 containers; a dedicated host does better, and neither is a claim
  about what the architecture can do given real hardware.

# E-commerce fulfillment reference

Java 21 / Spring Boot 4.0.8 / Spring Cloud 2025.1.3. Seven independently runnable services demonstrate Kafka
choreography, an event-sourced Order aggregate, CQRS, transactional outbox/inbox, provider reconciliation, JWT
authorization and reconnectable SSE. Payment and shipping are **deterministic local simulators** with their own durable
effects database.

## Run locally

Requirements: Java 21, Docker Compose, approximately 8 GB free container memory, Python 3 and curl. Maven Wrapper is
included. On macOS select Java 21 with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

```sh
./mvnw -B -ntp verify
# Tests create isolated PostgreSQL, Kafka and Redis containers; Docker must be available.
docker compose up --build -d --wait
# Browser: http://localhost:8080
```

If port 8080 is occupied, use `GATEWAY_PORT=18080 docker compose up --build -d --wait` and open http://localhost:18080.
The local realm permits both ports; set `BASE_URL=http://localhost:18080` for demo.sh and `--url http://localhost:18080`
for load.py.

Demo accounts: `alice / alice-local-only`, `bob / bob-local-only`. Browser login uses authorization code + PKCE;
access/refresh tokens stay in memory. The command-line token script uses a development-only password grant. Keycloak
administration is at http://localhost:8180 with `admin / local-admin-only`. Local operator client: shop-ops /
local-ops-only, client_credentials grant. These credentials are explicitly local; replace the realm and all secrets
before deployment.

```sh
./scripts/demo.sh SUCCESS SUCCESS
./scripts/demo.sh REJECT SUCCESS          # payment rejection, release inventory
./scripts/demo.sh SUCCESS REJECT          # shipping rejection, void payment
./scripts/demo.sh TIMEOUT_AFTER SUCCESS   # performed authorization, then timeout
./scripts/demo.sh COMPENSATION_RETRY REJECT # first void fails, durable retry succeeds
./scripts/demo.sh AMBIGUOUS SUCCESS       # unresolved result; see reconciliation runbook
TOKEN=$(./scripts/token.sh alice) python3 scripts/load.py --orders 30 --rate 2
```

The demo command streams SSE until interrupted. Use the browser to list orders, cancel a pending order or reconnect.
Immediate BFF details expose command state plus projection freshness; a just-created order may not yet be visible to the
query service.

```sh
docker compose stop kafka     # place an order; local transaction still returns 202
docker compose start kafka    # committed outbox work recovers
docker compose logs --tail=100 order-service
docker compose ps
docker compose down           # preserves data volumes
```

Do not use `down -v` unless you intentionally want to erase all demo data. Ports bind to loopback; only Gateway 8080 and
Keycloak 8180 are exposed. Service APIs, databases and provider endpoints remain on the Compose network.

## Repository guide

- [Architecture, state machines and diagrams](docs/architecture.md)
- [Verified dependency compatibility](docs/versions.md)
- [Architecture decisions](docs/decisions.md)
- [OpenAPI HTTP contract](docs/openapi.json)
- [Integration events and JSON schema](docs/events/README.md)
- [Recovery runbooks](docs/runbooks.md)
- [Requirement/code/test traceability](docs/traceability.md)
- [Verification evidence and limitations](docs/verification.md)
- [Kubernetes templates](deploy/k8s/README.md)

`contracts` contains wire records/JSON utilities; `service-runtime` contains only technical messaging, transaction,
security and provider HTTP utilities. Service-specific aggregates, stock entities, task state machines and migrations
remain inside the owning service. There are no cross-service SQL joins or shared JPA entities. Integration tests inspect
databases only as a test oracle.

## Troubleshooting

- Wrong Java: run `java -version` and `./mvnw -version`; set JAVA_HOME to Java 21.
- Docker unavailable: start Docker/OrbStack. Testcontainers must reach its socket; with a nonstandard socket set
  DOCKER_HOST. Tests intentionally fail rather than silently skip.
- Startup: inspect `docker compose ps` and the failing service log. Flyway runs before business traffic. Keycloak import
  runs at first startup; local realm edits require an explicit identity reset/reimport.
- 401: tokens must have issuer http://localhost:8180/realms/shop and audience shop; internal JWKS URL differs only for
  container networking. Do not use an arbitrary identity header.
- 409: reuse an idempotency key only for the identical request; reload commandVersion before a new cancellation.
- 429: Gateway allows 10 requests/s with burst 20 per customer across instances. SSE connection capacity is also
  bounded.
- Slow/stuck order: inspect quarantine, outbox and participant tasks using the runbooks. Unknown provider outcomes are
  never fabricated as success or failure.

## Load assumptions

The reproducible driver defaults to 30 single-item successful orders at 2 orders/s, at most eight HTTP workers, and
polls terminal state under the per-customer gateway budget. It reports measured acceptance p50/p95/max and terminal
outcomes. This is a functional baseline, not a capacity claim. Increase customers, stock and rate limits deliberately
when benchmarking. Only measurements actually executed appear in verification.md.

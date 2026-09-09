# Verification and production limitations

Verified **2026-09-10** with Java 21 on macOS ARM64 and Docker/OrbStack 29.4.0. The final command was `./mvnw -B -ntp clean verify` with JAVA_HOME set to the installed Java 21 JDK and DOCKER_HOST set to the OrbStack socket.

| Check | Executed result |
|---|---|
| Maven reactor | All 12 projects successful; 2 min 19 s |
| Aggregate unit tests | 3 passed, 0 failures/errors/skips |
| PostgreSQL/Kafka integration tests | 23 passed, 0 failures/errors/skips; 133.759 s |
| Packaged security dependency | All six servlet services contain Tomcat 11.0.25 |
| Compose | All 18 containers running and healthy on gateway port 18080 |
| Real OIDC smoke | Keycloak login, BFF aggregation, cross-customer 404, Gateway SSE resume passed |
| Load | 30/30 confirmed at 2 orders/s, maximum 8 HTTP workers; 20.29 s elapsed |
| Acceptance latency | Median 34.1 ms, p95 1038.5 ms, maximum 1584.5 ms |
| Tracing | Collector recorded exported trace batches during the real HTTP/Kafka workload |
| Kubernetes | Application manifests rendered successfully; no cluster deployment |
| Browser / script checks | HTML served with HTTP 200; JavaScript, shell and Python syntax checked |
| Visual browser login | Not executed: no browser is available through the connected UI tools |

The load result is a small local functional baseline, not a capacity or production SLA claim. The pre-fix load run failed all 30 requests because the development realm omitted the subject mapper; its result is retained separately. The successful result above was rerun against the final patched stack. No findings were hidden by treating failed requests as successful latency measurements.

## Security evidence

The repository/configuration scan reported 16 MEDIUM and 9 LOW configuration findings, with no HIGH/CRITICAL findings. A separate scan of the built Order image initially exposed three CRITICAL Tomcat advisories. These were fixed with the officially verified 11.0.25 patch; inspecting the nested jar also caught and fixed stale incremental repackaging.

Final built-image scans report:

| Image | HIGH / CRITICAL | MEDIUM | LOW |
|---|---|---|---|
| Order (shared servlet dependency family) | 0 / 0 | 101 | 30 |
| Gateway | 0 / 0 | 100 | 30 |
| BFF | 0 / 0 | 100 | 30 |

Lower-severity findings remain in the full reports and require deployment-specific review. These scans cover the application dependency families, not every infrastructure image or a penetration test. CI now scans built images as well as source/configuration; hosted CI itself was not invoked.

## Evidence locations

- `docs/evidence/maven-verify.log`: complete current Maven Wrapper output.
- `integration-tests/target/failsafe-reports`: JUnit integration reports.
- `order-service/target/surefire-reports`: aggregate unit reports.
- `docs/evidence/compose-start.log`: actual Compose startup output.
- `docs/evidence/security.json`: executed Trivy scan.
- `docs/evidence/kubernetes-rendered.yaml`: rendered application manifests.
- `docs/evidence/junit-summary.json`: retained test counts and timings.
- `docs/evidence/load.json`, `http-smoke.json`, `compose-health.txt`, `trace-export.txt`: final runtime evidence.
- `docs/evidence/image-security.json`, `api-gateway-security.json`, `customer-bff-security.json`: final image findings.
- `docs/evidence/artifact-versions.json`: inspected nested dependency versions.

## Material production deployment gaps

- Payment and shipping are simulations, not real financial/carrier integrations. Orders have one SKU and a demonstration client-supplied amount; production needs authoritative catalog/pricing/tax and provider-specific reconciliation/cancellation contracts.
- Local Kafka is a single broker; databases and Redis are single instances. Production needs HA, TLS, authenticated broker/Redis access, Kafka ACLs and capacity planning.
- Development credentials, password grant, HTTP OIDC and loopback browser origins are local shortcuts. Production requires a hardened identity deployment and secret manager.
- Event-type producer fields assume trusted services. Shared-topic ACLs alone cannot prevent one authorized producer from impersonating another event producer.
- Inbox/archive retention is unbounded. Projection rebuild final catch-up scans all archived orders; high-volume deployments need incremental cutover and archival policies.
- SSE uses database polling with bounded connection counts. Multi-instance correctness follows durable shared history; fleet capacity and slow-client saturation require deployment-scale measurements.
- The local trace collector exports debug summaries; production needs retained tracing, alert routing, dashboards and private metrics access.
- Kubernetes application templates render, but are not deployed to a cluster and do not provision infrastructure, network policies, backups/PITR or disaster-recovery automation.
- Hosted CI and production deployment/security approval are not executed here. Passing compilation or functional tests is not a production-readiness certification.

## Completion checklist

- [x] Seven service boundaries and an independently persisted provider simulator.
- [x] Order event sourcing, snapshots/upcasting, choreography, compensation and durable deadlines.
- [x] Atomic outbox/inbox, ordered relays, duplicate recovery and operator quarantine.
- [x] CQRS projections, gap handling, concurrent rebuild and resumable SSE.
- [x] JWT subject/issuer/audience checks, ownership, shared rate limits and request bounds.
- [x] Provider/BFF circuit protection, bounded retries, bulkheads and safe degradation.
- [x] Compose, local OIDC, client, schemas, OpenAPI, diagrams, ADRs and runbooks.
- [x] Automated failure scenarios, CI configuration, security scans and load evidence.
- [x] Kubernetes templates rendered; production deployment gaps documented.
- [ ] Visual browser QA and hosted CI/cluster execution: unavailable or outside this local verification.

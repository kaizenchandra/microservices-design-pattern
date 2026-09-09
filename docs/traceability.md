# Requirement-to-code-and-test traceability

Tests are in `integration-tests/src/test/java/com/synechis/fulfillment/PlatformIT.java` and launch executable services against real PostgreSQL and Kafka. See [verification evidence](verification.md) for execution results.

| Scenario | Automated test | Implementation |
|---|---|---|
| 1 | `successfulFulfillment` | Orders, OrderStore, Inventory, PaymentParticipant, ShippingParticipant |
| 2 | `insufficientInventoryNeverAuthorizesPayment` | Inventory / StockRepository |
| 3 | `rejectedPaymentReleasesInventory` | PaymentParticipant and Inventory |
| 4 | `rejectedShippingVoidsPayment` | ShippingParticipant and PaymentParticipant |
| 5 | `duplicateEventsHaveNoDuplicateEffects` | Messaging inbox and participant state guards |
| 6 | `lastUnitCannotBeOversold` | StockRepository pessimistic SKU lock; stock constraint |
| 7 | `concurrentCancellationCommandsAppendOnce` | Orders.cancel and OrderStore.append version guard |
| 8 | `committedOrderSurvivesKafkaOutage` | OutboxRelay persisted retries |
| 9 | `relayCrashAfterSendRedeliversSafely` | OutboxRelay.relayOne crash injection and inbox |
| 10 | `consumerAtomicCommitAndRedelivery` | Messaging.accept transactional handler rollback/redelivery |
| 11 | `projectionBuffersGapsAndRejectsStaleState` | Projection.advance contiguous versions |
| 12 | `timeoutAfterProviderEffectIsReconciled` | ProviderClient, participant reconciliation and Simulator |
| 13 | `compensationFailureIsRetried` | PaymentParticipant durable backoff; Simulator |
| 14 | `projectionRebuildIsEquivalentAndSideEffectFree` | Projection.rebuild and OrderStore.load full replay |
| 15 | `providerCircuitOpensAndRecovers` | ProviderClient circuit; Bff degraded projection |
| 16 | `ownershipAndJwtAreEnforced` | SecurityConfiguration, EdgeSecurity and ownership checks |
| 17 | `sseReconnectRecoversMissedVersions` | QueryController, Projection.changes and Bff.events |
| 18 | `cancellationAndExpiryFenceLateSuccess` | Orders, Inventory expiry, participant abort tombstones |
| 19 | `conflictingIdempotencyKeysAreRejected` | Orders.place command_key fingerprint |
| 20 | `ambiguousOutcomeRequiresManualRecovery` | Participant MANUAL state and RecoveryController |
| 21 | `rebuildCutoverHandlesArrivingEvents` | Projection shadow generation and cutover lock |
| 22 | `edgeLimitsAndProblemsAreEnforced` | GatewayRoutes RedisRateLimiter, BodyLimit and ProblemResponses |
| 23 | `missingSubjectIsRejectedAtEveryBoundary` | JwtConfiguration at every HTTP boundary; GatewayRoutes null-safe key resolver |


| Requirement | Code / artifact | Verification |
|---|---|---|
| Java 21, Boot 4 and compatible BOMs | Root and module POMs; Maven Wrapper | Full reactor build; docs/versions.md |
| Immutable events, snapshots, upcasting | OrderAggregate, OrderStore; Order V2 migration | OrderAggregateTest; integration test 14 |
| Service-owned databases and Flyway | Module migrations, Compose DB services | Fresh DB startup in every integration run |
| Atomic outbox/inbox and ordering | service-runtime Messaging and OutboxRelay | Tests 5, 8, 9, 10 |
| Durable deadlines and compensation | Orders.deadlines, Inventory.expire, participant work | Tests 12, 13, 18, 20 |
| CQRS gaps and online rebuild | Projection archive, advance and rebuild | Tests 11, 14, 21 |
| JWT, authorization, shared edge limits | SecurityConfiguration, EdgeSecurity, GatewayRoutes | Tests 16, 22 |
| SSE disconnect/reconnect and bounded resources | QueryController, Bff.events, app.js | Test 17; browser syntax check |
| HTTP and event contracts | docs/openapi.json, docs/events | JSON parse validation; actual APIs in integration tests |
| Tracing, logs and metrics | Correlation, OutboxRelay, OpenTelemetry starter, OperationalMetrics | Runtime startup; internal Prometheus endpoints |
| Infrastructure and browser demo | compose.yml, Dockerfile, Keycloak realm, static client | Compose health and load evidence when executed |
| CI and security checks | .github/workflows/verify.yml; Dependabot | Local Maven and saved Trivy report; hosted CI not invoked |
| Kubernetes probes, resources, non-root | deploy/k8s | kubectl kustomize rendering; no cluster deployment |
| Recovery procedures and decisions | docs/runbooks.md, architecture.md, decisions.md | Review against implemented endpoints and state transitions |

Crash tests 9 and 10 use deterministic fault injection at the production transaction boundary. Test 9 throws after a real Kafka acknowledgement and before the outbox commit. Test 10 executes the production inbox transaction with an injected handler failure, then redelivers after commit; it does not claim to kill an OS process at an exact CPU instruction. Test 7 checks concurrent cancellation commands and a single append, rather than arbitrary concurrent commands for unimplemented business features.

# Architecture decision records

1. **Choreography, not a workflow coordinator.** Participants react to predecessor facts; Order enforces its own
   confirmation/cancellation invariant. This preserves service autonomy at the cost of distributed recovery visibility.
2. **Event sourcing only for Order.** Lifecycle history, replay and optimistic command versions are valuable there.
   Conventional local persistence is simpler for stock, provider tasks and read projections.
3. **Polling outbox.** PostgreSQL row locks and an oldest-unpublished predicate are inspectable and runnable without a
   CDC platform. The tradeoff is bounded DB connections held during Kafka publication and modest polling latency.
   Production throughput must be measured before scaling relay batches.
4. **One keyed business topic.** Preserves causal delivery within each order's partition and simplifies failure
   demonstrations. Per-service ACLs cannot protect individual event types on a shared topic; trusted producers, schemas
   and source validation are assumed. A hostile-service threat model needs signed events or separate producer topics
   with sequence fences.
5. **Unknown external outcome is durable state.** Reconciliation precedes retries. Stable provider keys prevent repeat
   effects, including across crashes and task retries. A real provider must offer equivalent idempotency and
   authoritative status queries.
6. **Reservation commitment fences confirmation.** Booking alone cannot confirm an expired hold. Inventory emits
   commitment under the same lock that arbitrates expiry; Order serializes that fact with cancellation.
7. **Archive-backed SSE and generation projections.** Simplifies reconnect and multi-instance correctness. Polling and
   rebuild catch-up scans constrain throughput, which must be assessed against deployment workloads.
8. **Separate edge and BFF.** Gateway owns route/security/limits; BFF adapts command/projection responses. Neither owns
   fulfillment decisions.

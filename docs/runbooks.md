# Operational runbooks

## Observe first

Each application exposes `/actuator/health/liveness`, `/actuator/health/readiness` and `/actuator/prometheus` on its internal port. Inspect `outbox_backlog`, `outbox_oldest_seconds`, `consumer_quarantine`, `saga_oldest_seconds`, `compensation_pending`, `compensation_failures_total`, `projection_gaps`, `projection_delivery_lag_seconds` and `provider_circuit_state`. Kafka client metrics include records-lag; inspect broker consumer groups with `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --all-groups --describe`. Counters/timers use Micrometer's Prometheus naming conventions.

Do not log tokens, provider credentials, full request bodies or quarantine payloads in incident tickets. Use order ID, correlation ID, event ID and partition/offset.

## Kafka or relay outage

`docker compose stop kafka`, place an order (202 still commits), inspect Order outbox pending rows, then `docker compose start kafka`. The relay retries committed work. Do not delete outbox rows to clear a backlog. A relay process death after send can duplicate publication; inbox deduplication handles it. If a backlog does not shrink, verify broker connectivity, the oldest failing aggregate row, ACLs and serialization before restarting instances.

## Quarantine / dead-letter recovery

Use an operator token with `scope=ops`. Call the affected service internally: `GET /admin/quarantine`. Obtain the original source record securely from its DB or Kafka using the listed partition/offset. Repair the incompatible code/schema, or publish a corrected event with the correct aggregate/version and a new event ID. After repair, `POST /admin/quarantine/{partition}/{offset}/skip` permits the source offset to advance. This is a consequential operator action; ensure the missing business fact has been repaired first. Never invent a success event to unblock a saga. For unchanged replay, preserve eventId so processed consumers deduplicate.

## Provider reconciliation / manual recovery

Inspect the participant's `operation` row and its provider key `(payment|shipping, orderId)`. States UNKNOWN and COMPENSATING continue retrying. MANUAL stops automatic calls after eight failed attempts. Obtain an authoritative provider result, then `POST /admin/operations/{id}/retry` with an ops JWT. The worker performs a status lookup before any new side effect.

For the **local simulator only**, resolve AMBIGUOUS using `POST /sim/payment/resolve/{id}` (or shipping) with `X-Simulator-Key` and `{"state":"SUCCEEDED"}` or `{"state":"REJECTED"}`. Resolution does not itself change the participant DB. Retry the manual task afterward. For COMPENSATION_RETRY the first compensation returns 503 and the next succeeds automatically. Real provider reconciliation requires evidence from that provider, not this simulator API.

## Stuck saga

Inspect full Order replay via `GET /admin/orders/{id}/replay`. Compare expected facts with participant states, inbox/quarantine and oldest outbox rows. PENDING past 120 seconds becomes COMPENSATING. Inventory holds expire after 90 seconds unless committed. MANUAL_RECOVERY is an alert, not a successful completion. Do not force CONFIRMED: recover participants and let their durable facts finish the saga.

## Event replay and snapshots

`GET /admin/orders/{id}/replay` rehydrates the immutable stream without side effects. Delete an expendable snapshot only during an authorized repair; the next load replays all domain events. Never update domain_event rows: the database trigger rejects it. Ship an upcaster for historical schema changes. Domain-event replay must never publish integration events or call a provider.

## Projection rebuild

`POST /admin/rebuild` on Query creates a shadow generation and atomically switches after catch-up. New ingestion continues during the build. Verify equivalent bodies and per-order versions. Prior generations remain available. For a large rebuild, monitor DB load and cutover-lock wait time. Restarting a failed rebuild leaves unused shadow rows, not a partially switched live projection. Remove abandoned generations only after checking no rebuild is active. Restoring an old generation without catching up would serve stale data; do not simply flip its pointer.

## Database recovery

Back up each independently owned database, including event streams, command keys, outbox, inbox, provider task state and projection archive. Enable WAL archiving/PITR in production and regularly restore into an isolated environment. Freeze consumer/relay traffic for the affected service before recovery. Restore its local transactionally consistent state, inspect its persisted consumer positions and Kafka retention, and replay from an earlier retained offset. Duplicates are expected. If Kafka retention no longer covers the gap, restore an archive; do not infer missing facts. External providers may have effects newer than the restored DB: reconcile stable provider keys before resuming tasks. This repository does not provision a production backup operator.

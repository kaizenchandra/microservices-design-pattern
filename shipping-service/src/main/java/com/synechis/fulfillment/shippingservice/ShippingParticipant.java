package com.synechis.fulfillment.shippingservice;

import com.synechis.fulfillment.contracts.Event;
import com.synechis.fulfillment.contracts.Json;
import com.synechis.fulfillment.runtime.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ShippingParticipant implements EventHandler {
    private final Database db;
    private final Messaging messaging;
    private final ProviderClient provider;
    private final MeterRegistry metrics;

    public ShippingParticipant(Database db, Messaging messaging, ProviderClient provider, MeterRegistry metrics) {
        this.db = db;
        this.messaging = messaging;
        this.provider = provider;
        this.metrics = metrics;
        metrics.gauge("compensation.pending", this, p -> p.db.sql.queryForObject("select count(*) from operation where aborted and state not in ('COMPENSATED','REJECTED')", Long.class));
    }

    public void handle(Event e) {
        if (!Set.of("PaymentAuthorized", "OrderAborting").contains(e.eventType())) return;
        if (e.eventType().equals("OrderAborting") && !e.producer().equals("order-service"))
            throw new IllegalArgumentException("Unexpected abort producer");
        if (e.eventType().equals("PaymentAuthorized") && !e.producer().equals("payment-service"))
            throw new IllegalArgumentException("Unexpected trigger producer");
        var rows = db.sql.queryForList("select * from operation where order_id=?", e.aggregateId());
        if (e.eventType().equals("OrderAborting")) {
            if (rows.isEmpty()) {
                db.sql.update("insert into operation(order_id,state,aborted,body) values(?,'COMPENSATED',true,?::jsonb)", e.aggregateId(), Json.write(e.payload()));
                emit(e.aggregateId(), "ShipmentCancelled", e.payload(), e);
            } else {
                var row = rows.getFirst();
                String state = row.get("state").toString();
                db.sql.update("update operation set aborted=true,next_attempt=now(),state=case when state='SUCCEEDED' then 'COMPENSATING' else state end where order_id=?", e.aggregateId());
                if (state.equals("REJECTED")) {
                    db.sql.update("update operation set state='COMPENSATED' where order_id=?", e.aggregateId());
                    emit(e.aggregateId(), "ShipmentCancelled", Json.map(row.get("body").toString()), e);
                }
            }
        } else if (rows.isEmpty())
            db.sql.update("insert into operation(order_id,state,body,cause) values(?,'PENDING',?::jsonb,?::jsonb)", e.aggregateId(), Json.write(e.payload()), Json.write(e));
    }

    @Scheduled(fixedDelayString = "${app.provider.delay:250}")
    public void work() {
        for (UUID id : db.sql.queryForList("select order_id from operation where state in ('PENDING','UNKNOWN','COMPENSATING') and next_attempt<=now() order by next_attempt limit 16", UUID.class))
            process(id);
    }

    public void process(UUID id) {
        // Bounded provider calls under an aggregate lock: abort waits at most the HTTP budget;
        // a process crash rolls back local state, then reconciliation observes the durable provider key.
        db.transaction(() -> {
            db.lock(id);
            var row = db.sql.queryForMap("select * from operation where order_id=?", id);
            String state = row.get("state").toString();
            if (!Set.of("PENDING", "UNKNOWN", "COMPENSATING").contains(state)) return null;
            if (row.get("cause") != null)
                Correlation.set(Correlation.from(Json.read(row.get("cause").toString(), Event.class)));
            var body = Json.map(row.get("body").toString());
            boolean aborted = (Boolean) row.get("aborted");
            try {
                var result = provider.call("shipping", id.toString(), null);
                String remote = result.get("state").toString();
                if (remote.equals("ABSENT")) {
                    if (aborted) {
                        finish(id, "COMPENSATED", "ShipmentCancelled", body);
                        return null;
                    }
                    result = provider.call("shipping", id.toString(), Map.of("mode", body.get("shippingMode"), "request", body));
                    remote = result.get("state").toString();
                }
                if (remote.equals("REJECTED")) {
                    finish(id, aborted ? "COMPENSATED" : "REJECTED", aborted ? "ShipmentCancelled" : "ShipmentRejected", body);
                    return null;
                }
                if (!remote.equals("SUCCEEDED") && !remote.equals("COMPENSATED"))
                    throw new IllegalStateException("Unresolved provider outcome");
                if (aborted) {
                    if (!remote.equals("COMPENSATED"))
                        provider.call("shipping/compensate", id.toString(), Map.of("mode", body.get("shippingMode")));
                    finish(id, "COMPENSATED", "ShipmentCancelled", body);
                } else if (remote.equals("SUCCEEDED")) finish(id, "SUCCEEDED", "ShipmentBooked", body);
                else throw new IllegalStateException("Unexpected provider compensation");
            } catch (Exception ex) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                db.sql.update("update operation set state=?,attempts=?,next_attempt=now()+(least(30,power(2,least(?,5)))+random())*interval '1 second' where order_id=?", attempts >= 8 ? "MANUAL" : aborted ? "COMPENSATING" : "UNKNOWN", attempts, attempts, id);
                metrics.counter(aborted ? "compensation.failures" : "provider.unknown").increment();
                if (attempts >= 8) emit(id, "RecoveryRequired", body, null);
            } finally {
                Correlation.clear();
            }
            return null;
        });
    }

    private void finish(UUID id, String state, String type, Map<String, Object> body) {
        db.sql.update("update operation set state=?,attempts=0 where order_id=?", state, id);
        emit(id, type, body, null);
    }

    private void emit(UUID id, String type, Map<String, Object> body, Event cause) {
        if (cause == null) {
            String raw = db.sql.queryForObject("select cause::text from operation where order_id=?", String.class, id);
            if (raw != null) cause = Json.read(raw, Event.class);
        }
        long version = db.sql.queryForObject("update operation set version=version+1 where order_id=? returning version", Long.class, id);
        messaging.publish(Event.next(type, id, version, "shipping-service", body, cause));
    }

    public void retry(UUID id) {
        db.transaction(() -> {
            db.lock(id);
            db.sql.update("update operation set state=case when aborted then 'COMPENSATING' else 'UNKNOWN' end,attempts=0,next_attempt=now() where order_id=? and state='MANUAL'", id);
            return null;
        });
    }
}

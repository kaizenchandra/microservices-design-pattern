package com.synechis.fulfillment.orderservice;

import com.synechis.fulfillment.contracts.Event;
import com.synechis.fulfillment.contracts.Json;
import com.synechis.fulfillment.contracts.PlaceOrder;
import com.synechis.fulfillment.runtime.Database;
import com.synechis.fulfillment.runtime.EventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class Orders implements EventHandler {
    private final Database db;
    private final OrderStore store;

    public Orders(Database db, OrderStore store, MeterRegistry metrics) {
        this.db = db;
        this.store = store;
        metrics.gauge("saga.oldest.seconds", this, o -> o.db.sql.queryForObject("select coalesce(extract(epoch from now()-min(created_at)),0) from order_stream where not terminal", Double.class));
    }

    public Map<String, Object> place(String customer, String key, PlaceOrder request) {
        if (key == null || !key.matches("[A-Za-z0-9-]{8,100}"))
            throw new IllegalArgumentException("Invalid idempotency key");
        String fingerprint = Json.canonical(Json.map(Json.write(request)));
        return db.transaction(() -> {
            db.lock(customer + ":" + key);
            var existing = db.sql.queryForList("select * from command_key where customer=? and key=?", customer, key);
            if (!existing.isEmpty()) {
                var e = existing.getFirst();
                if (!fingerprint.equals(e.get("fingerprint")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused with different request");
                return store.load((UUID) e.get("order_id"), true).view((UUID) e.get("order_id"));
            }
            UUID id = UUID.randomUUID();
            db.sql.update("insert into order_stream(id) values(?)", id);
            db.sql.update("insert into command_key values(?,?,?,?)", customer, key, fingerprint, id);
            var payload = new LinkedHashMap<>(Json.map(fingerprint));
            payload.put("customer", customer);
            var a = new OrderAggregate();
            store.append(id, a, "Placed", payload, null);
            return a.view(id);
        });
    }

    public Map<String, Object> owned(UUID id, String customer) {
        return db.transaction(() -> {
            var a = store.load(id, true);
            check(a, customer);
            return a.view(id);
        });
    }

    private void check(OrderAggregate a, String customer) {
        if (a.version == 0 || !customer.equals(a.data.get("customer")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
    }

    public Map<String, Object> cancel(UUID id, String customer, long expected) {
        return db.transaction(() -> {
            db.lock(id);
            var a = store.load(id, true);
            check(a, customer);
            if (a.aborting()) return a.view(id);
            if (a.version != expected) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stale order version");
            if (a.terminal())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmed orders cannot be cancelled");
            store.append(id, a, "Aborting", Map.of("reason", "CANCELLED"), null);
            return a.view(id);
        });
    }

    public void handle(Event e) {
        if (e.producer().equals("order-service")) return;
        var a = store.load(e.aggregateId(), true);
        if (a.version == 0) return;
        String type = e.eventType();
        if (!Set.of("InventoryReserved", "InventoryRejected", "InventoryExpired", "InventoryCommitted", "InventoryReleased", "PaymentAuthorized", "PaymentRejected", "PaymentVoided", "ShipmentBooked", "ShipmentRejected", "ShipmentCancelled", "RecoveryRequired").contains(type))
            return;
        if (a.facts.contains(type)) return;
        // Producers own these facts. Kafka ACLs in production must enforce producer identities.
        String owner = type.startsWith("Inventory") ? "inventory-service" : type.startsWith("Payment") ? "payment-service" : type.startsWith("Shipment") ? "shipping-service" : e.producer();
        if (!owner.equals(e.producer())) throw new IllegalArgumentException("Invalid event producer");
        if (a.terminal()) return;
        if (type.equals("RecoveryRequired")) {
            if (!a.aborting()) store.append(e.aggregateId(), a, "Aborting", Map.of("reason", "PROVIDER_UNKNOWN"), e);
            store.append(e.aggregateId(), a, "RecoveryRequired", Map.of(), e);
            return;
        }
        store.append(e.aggregateId(), a, type, Map.of(), e);
        if ((type.endsWith("Rejected") || type.equals("InventoryExpired")) && !a.aborting())
            store.append(e.aggregateId(), a, "Aborting", Map.of("reason", type), e);
        if (a.status.equals("PENDING") && a.facts.containsAll(Set.of("InventoryCommitted", "ShipmentBooked", "PaymentAuthorized")))
            store.append(e.aggregateId(), a, "Confirmed", Map.of(), e);
        if (a.aborting() && a.facts.containsAll(Set.of("InventoryReleased", "PaymentVoided", "ShipmentCancelled")))
            store.append(e.aggregateId(), a, "Compensated", Map.of(), e);
    }

    @Scheduled(fixedDelay = 1000)
    public void deadlines() {
        for (UUID id : db.sql.queryForList("select id from order_stream where not closed and deadline<now() limit 100", UUID.class))
            db.transaction(() -> {
                db.lock(id);
                var a = store.load(id, true);
                if (!a.terminal() && !a.aborting())
                    store.append(id, a, "Aborting", Map.of("reason", "FULFILLMENT_TIMEOUT"), null);
                return null;
            });
    }
}

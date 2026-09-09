package com.synechis.fulfillment.inventoryservice;

import com.synechis.fulfillment.contracts.Event;
import com.synechis.fulfillment.contracts.Json;
import com.synechis.fulfillment.runtime.Database;
import com.synechis.fulfillment.runtime.EventHandler;
import com.synechis.fulfillment.runtime.Messaging;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class Inventory implements EventHandler {
    private final Database db;
    private final StockRepository stocks;
    private final Messaging messaging;

    public Inventory(Database db, StockRepository stocks, Messaging messaging) {
        this.db = db;
        this.stocks = stocks;
        this.messaging = messaging;
    }

    public void handle(Event e) {
        if (!Set.of("OrderPlaced", "OrderAborting", "ShipmentBooked").contains(e.eventType())) return;
        if (e.eventType().startsWith("Order") && !e.producer().equals("order-service"))
            throw new IllegalArgumentException("Unexpected producer");
        if (e.eventType().equals("ShipmentBooked") && !e.producer().equals("shipping-service"))
            throw new IllegalArgumentException("Unexpected producer");
        var rows = db.sql.queryForList("select * from reservation where order_id=?", e.aggregateId());
        if (e.eventType().equals("OrderAborting")) {
            if (rows.isEmpty()) {
                db.sql.update("insert into reservation(order_id,state,body) values(?,'RELEASED',?::jsonb)", e.aggregateId(), Json.write(e.payload()));
                emit(e, "InventoryReleased", 1);
            } else if (!rows.getFirst().get("state").equals("RELEASED")) {
                release(rows.getFirst());
                emit(e, "InventoryReleased", ((Number) rows.getFirst().get("version")).longValue() + 1);
            }
            return;
        }
        if (e.eventType().equals("OrderPlaced")) {
            if (!rows.isEmpty()) return;
            var stock = stocks.locked(e.text("sku"));
            boolean reserved = stock.isPresent() && stock.get().available >= e.number("quantity");
            if (reserved) {
                stock.get().available -= e.number("quantity");
                stocks.flush();
            }
            db.sql.update("insert into reservation(order_id,sku,quantity,state,version,body) values(?,?,?,?,1,?::jsonb)", e.aggregateId(), e.text("sku"), e.number("quantity"), reserved ? "RESERVED" : "REJECTED", Json.write(e.payload()));
            emit(e, reserved ? "InventoryReserved" : "InventoryRejected", 1);
            return;
        }
        if (!rows.isEmpty() && rows.getFirst().get("state").equals("RESERVED")) {
            if (db.sql.update("update reservation set state='COMMITTED',version=version+1 where order_id=? and expires_at>now()", e.aggregateId()) == 1)
                emit(e, "InventoryCommitted", ((Number) rows.getFirst().get("version")).longValue() + 1);
            else expire(rows.getFirst());
        }
    }

    private void release(Map<String, Object> row) {
        if (Set.of("RESERVED", "COMMITTED").contains(row.get("state"))) {
            var s = stocks.locked(row.get("sku").toString()).orElseThrow();
            s.available += ((Number) row.get("quantity")).intValue();
            stocks.flush();
        }
        db.sql.update("update reservation set state='RELEASED',version=version+1 where order_id=?", row.get("order_id"));
    }

    private void emit(Event cause, String type, long version) {
        messaging.publish(Event.next(type, cause.aggregateId(), version, "inventory-service", cause.payload(), cause));
    }

    private void expire(Map<String, Object> row) {
        release(row);
        var e = Event.next("InventoryExpired", (UUID) row.get("order_id"), ((Number) row.get("version")).longValue() + 1, "inventory-service", Json.map(row.get("body").toString()), null);
        messaging.publish(e);
        // Expiry itself has released the hold; the later abort must also report the terminal fact.
        messaging.publish(Event.next("InventoryReleased", e.aggregateId(), e.aggregateVersion() + 1, "inventory-service", e.payload(), e));
    }

    @Scheduled(fixedDelay = 1000)
    public void expire() {
        for (UUID id : db.sql.queryForList("select order_id from reservation where state='RESERVED' and expires_at<=now() limit 100", UUID.class))
            db.transaction(() -> {
                db.lock(id);
                var row = db.sql.queryForMap("select * from reservation where order_id=?", id);
                if (row.get("state").equals("RESERVED")) expire(row);
                return null;
            });
    }
}

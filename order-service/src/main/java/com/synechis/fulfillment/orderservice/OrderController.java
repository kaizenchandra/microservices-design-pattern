package com.synechis.fulfillment.orderservice;

import com.synechis.fulfillment.contracts.PlaceOrder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class OrderController {
    private final Orders orders;
    private final OrderStore store;

    public OrderController(Orders orders, OrderStore store) {
        this.orders = orders;
        this.store = store;
    }

    @PostMapping("/orders")
    ResponseEntity<Map<String, Object>> place(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PlaceOrder body) {
        var result = orders.place(jwt.getSubject(), key, body);
        return ResponseEntity.accepted().header("Location", "/api/orders/" + result.get("orderId")).body(result);
    }

    @GetMapping("/orders/{id}")
    Map<String, Object> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return orders.owned(id, jwt.getSubject());
    }

    @PostMapping("/orders/{id}/cancel")
    ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestHeader("If-Match") long version) {
        return ResponseEntity.accepted().body(orders.cancel(id, jwt.getSubject(), version));
    }

    @GetMapping("/admin/orders/{id}/replay")
    Map<String, Object> replay(@PathVariable UUID id) {
        return store.load(id, false).view(id);
    }
}

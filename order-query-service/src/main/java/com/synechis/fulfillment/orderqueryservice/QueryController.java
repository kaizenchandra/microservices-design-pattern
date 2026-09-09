package com.synechis.fulfillment.orderqueryservice;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class QueryController {
    private final Projection projection;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
    private final Semaphore slots = new Semaphore(128);

    public QueryController(Projection projection) {
        this.projection = projection;
    }

    @GetMapping("/orders/{id}")
    Map<String, Object> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return projection.get(id, jwt.getSubject());
    }

    @GetMapping("/orders")
    Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return projection.list(jwt.getSubject(), page, size);
    }

    @PostMapping("/admin/rebuild")
    Map<String, Object> rebuild() {
        return Map.of("generation", projection.rebuild());
    }

    @GetMapping(value = "/orders/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long last) {
        projection.get(id, jwt.getSubject());
        if (!slots.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "SSE capacity exhausted");
        long lifetime = Math.max(1, Math.min(60000, java.time.Duration.between(java.time.Instant.now(), jwt.getExpiresAt()).toMillis()));
        SseEmitter emitter = new SseEmitter(lifetime);
        AtomicLong cursor = new AtomicLong(last);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable close = () -> {
            if (done.compareAndSet(false, true)) {
                slots.release();
                var f = future.get();
                if (f != null) f.cancel(false);
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(() -> {
            close.run();
            emitter.complete();
        });
        emitter.onError(e -> close.run());
        future.set(scheduler.scheduleWithFixedDelay(() -> {
            if (done.get()) return;
            try {
                if (java.time.Instant.now().isAfter(jwt.getExpiresAt())) {
                    close.run();
                    emitter.complete();
                    return;
                }
                for (var change : projection.changes(id, jwt.getSubject(), cursor.get())) {
                    emitter.send(SseEmitter.event().id(change.get("id").toString()).name(change.get("event").toString()).data(change.get("data")));
                    cursor.set(((Number) change.get("id")).longValue());
                }
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                close.run();
                emitter.completeWithError(e);
            }
        }, 0, 1, TimeUnit.SECONDS));
        if (done.get()) future.get().cancel(false);
        return emitter;
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}

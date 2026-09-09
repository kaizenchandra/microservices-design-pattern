package com.synechis.fulfillment.runtime;

import com.synechis.fulfillment.contracts.Json;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class ProviderClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final CircuitBreaker breaker = CircuitBreaker.of("provider", CircuitBreakerConfig.custom().slidingWindowSize(6).minimumNumberOfCalls(4).failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(5)).permittedNumberOfCallsInHalfOpenState(2).build());
    private final Bulkhead bulkhead = Bulkhead.of("provider", BulkheadConfig.custom().maxConcurrentCalls(8).maxWaitDuration(Duration.ZERO).build());
    private final String base;
    private final String key;

    public ProviderClient(@Value("${PROVIDER_URL:http://localhost:8090}") String base, @Value("${PROVIDER_KEY:local-simulator-only}") String key, MeterRegistry metrics) {
        this.base = base;
        this.key = key;
        metrics.gauge("provider.circuit.state", breaker, b -> b.getState().getOrder());
    }

    public Map<String, Object> call(String operation, String id, Map<String, Object> body) {
        return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, () -> {
            try {
                var b = HttpRequest.newBuilder(URI.create(base + "/sim/" + operation + "/" + id)).timeout(Duration.ofSeconds(2)).header("X-Simulator-Key", key).header("Content-Type", "application/json");
                Correlation.current().forEach((k, v) -> b.header(k.equals("correlationId") ? "X-Correlation-ID" : k, v));
                var response = http.send(body == null ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) return Map.<String, Object>of("state", "ABSENT");
                if (response.statusCode() != 200)
                    throw new IllegalStateException("Provider outcome unknown; HTTP " + response.statusCode());
                return Json.map(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted provider call", e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Provider outcome unknown", e);
            }
        })).get();
    }
}

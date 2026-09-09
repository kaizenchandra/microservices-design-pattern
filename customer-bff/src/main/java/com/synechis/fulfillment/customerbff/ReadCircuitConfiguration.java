package com.synechis.fulfillment.customerbff;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ReadCircuitConfiguration {
    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> readCircuit() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(5)).ignoreException(e -> e instanceof org.springframework.web.reactive.function.client.WebClientResponseException w && w.getStatusCode().is4xxClientError()).build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()).build());
    }
}

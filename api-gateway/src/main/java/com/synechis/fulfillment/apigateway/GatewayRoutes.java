package com.synechis.fulfillment.apigateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class GatewayRoutes {
    @Bean
    KeyResolver customerKey() {
        return exchange -> exchange.getPrincipal().map(java.security.Principal::getName);
    }

    @Bean
    RedisRateLimiter limiter() {
        return new RedisRateLimiter(10, 20, 1);
    }

    @Bean
    RouteLocator routes(RouteLocatorBuilder b, RedisRateLimiter limiter, KeyResolver customerKey, @Value("${BFF_URL:http://localhost:8086}") String bff) {
        return b.routes()
                .route("sse", r -> r.path("/api/orders/*/events").filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(customerKey))).metadata("response-timeout", -1).metadata("connect-timeout", 1000).uri(bff))
                .route("api", r -> r.path("/api/**").filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(customerKey))).metadata("response-timeout", 5000).metadata("connect-timeout", 1000).uri(bff))
                .route("demo", r -> r.path("/", "/index.html", "/app.js").uri(bff)).build();
    }

    @Bean
    GlobalFilter correlation() {
        return (exchange, chain) -> {
            String given = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
            String id = given != null && given.matches("[A-Za-z0-9-]{1,64}") ? given : UUID.randomUUID().toString();
            var request = exchange.getRequest().mutate().headers(h -> {
                h.remove("X-Customer-ID");
                h.remove("X-User-ID");
                h.remove("X-Roles");
                h.set("X-Correlation-ID", id);
            }).build();
            exchange.getResponse().getHeaders().set("X-Correlation-ID", id);
            return chain.filter(exchange.mutate().request(request).build());
        };
    }
}

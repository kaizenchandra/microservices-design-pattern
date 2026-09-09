package com.synechis.fulfillment.apigateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class EdgeSecurity {
    @org.springframework.beans.factory.annotation.Value("${FRONTEND_ORIGIN:http://localhost:8080}")
    private String origin;

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http.csrf(c -> c.disable()).cors(c -> c.configurationSource(cors())).authorizeExchange(a -> a.pathMatchers("/", "/index.html", "/app.js", "/actuator/health/**", "/actuator/prometheus").permitAll().anyExchange().authenticated()).oauth2ResourceServer(o -> o.jwt(j -> {
        })).build();
    }

    private UrlBasedCorsConfigurationSource cors() {
        var c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin));
        c.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match", "Last-Event-ID", "X-Correlation-ID"));
        c.setExposedHeaders(List.of("Location", "X-Correlation-ID"));
        var s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}

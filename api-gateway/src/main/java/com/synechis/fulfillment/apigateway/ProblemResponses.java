package com.synechis.fulfillment.apigateway;

import com.synechis.fulfillment.contracts.Json;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@Order(-200)
public class ProblemResponses implements WebFilter {
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                var status = getStatusCode();
                if (status != null && status.isError() && !isCommitted()) {
                    getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                    byte[] bytes = Json.write(Map.of("type", "about:blank", "status", status.value(), "title", HttpStatus.valueOf(status.value()).getReasonPhrase())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    return writeWith(Mono.just(bufferFactory().wrap(bytes)));
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }
}

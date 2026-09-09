package com.synechis.fulfillment.customerbff;
import org.springframework.context.annotation.*;
import org.springframework.web.server.WebFilter;
import org.springframework.web.reactive.function.client.*;
@Configuration
public class CorrelationPropagation {
 @Bean WebFilter correlationContext(){return (exchange,chain)->{String value=exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");return chain.filter(exchange).contextWrite(c->c.put("correlationId",value==null?java.util.UUID.randomUUID().toString():value));};}
 @Bean org.springframework.boot.webclient.WebClientCustomizer correlationClient(){return builder->builder.filter((request,next)->reactor.core.publisher.Mono.deferContextual(c->next.exchange(ClientRequest.from(request).header("X-Correlation-ID",c.getOrDefault("correlationId",java.util.UUID.randomUUID().toString())).build())));}
}

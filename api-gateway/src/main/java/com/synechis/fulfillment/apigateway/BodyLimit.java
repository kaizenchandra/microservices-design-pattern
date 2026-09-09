package com.synechis.fulfillment.apigateway;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.core.io.buffer.*;
import reactor.core.publisher.*;
@Component @Order(-50)
public class BodyLimit implements WebFilter {
 public Mono<Void> filter(ServerWebExchange e,WebFilterChain chain){
  if(e.getRequest().getMethod()!=HttpMethod.POST)return chain.filter(e);
  return DataBufferUtils.join(e.getRequest().getBody(),16384).map(buffer->{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);DataBufferUtils.release(buffer);return bytes;}).defaultIfEmpty(new byte[0]).flatMap(bytes->{
   var request=new ServerHttpRequestDecorator(e.getRequest()){@Override public Flux<DataBuffer> getBody(){return Flux.defer(()->Flux.just(e.getResponse().bufferFactory().wrap(bytes)));}};
   return chain.filter(e.mutate().request(request).build());
  }).onErrorResume(DataBufferLimitException.class,x->{e.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);e.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap("{\"status\":413,\"title\":\"Request too large\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));});
 }
}

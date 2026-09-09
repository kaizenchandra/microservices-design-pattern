package com.synechis.fulfillment.customerbff;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import reactor.core.publisher.*;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
@RestController
public class Bff {
 private final WebClient client;private final String orders,queries;private final ReactiveCircuitBreakerFactory<?,?> breakers;
 private final Semaphore bulkhead=new Semaphore(64);
 public Bff(WebClient.Builder builder,@Value("${ORDER_URL:http://localhost:8081}")String orders,@Value("${QUERY_URL:http://localhost:8085}")String queries,ReactiveCircuitBreakerFactory<?,?> breakers){
  this.orders=orders;this.queries=queries;this.breakers=breakers;
  this.client=builder.clientConnector(new ReactorClientHttpConnector(HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS,1000).responseTimeout(Duration.ofSeconds(4)))).codecs(c->c.defaultCodecs().maxInMemorySize(262144)).build();
 }
 private Mono<Map> get(String uri,String token){
  return Mono.defer(()->{if(!bulkhead.tryAcquire())return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Read capacity exhausted"));
   return breakers.create(uri.startsWith(orders)?"order-read":"query").run(client.get().uri(uri).header("Authorization",token).retrieve().bodyToMono(Map.class).retryWhen(Retry.backoff(1,Duration.ofMillis(100)).jitter(.5).filter(e->!(e instanceof WebClientResponseException w)||w.getStatusCode().is5xxServerError())),e->Mono.error(e)).timeout(Duration.ofSeconds(3)).doFinally(s->bulkhead.release());});
 }
 @PostMapping("/api/orders") Mono<ResponseEntity<String>> place(@RequestHeader("Authorization")String token,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> body){return client.post().uri(orders+"/orders").header("Authorization",token).header("Idempotency-Key",key).bodyValue(body).exchangeToMono(r->r.toEntity(String.class));}
 @PostMapping("/api/orders/{id}/cancel") Mono<ResponseEntity<String>> cancel(@PathVariable UUID id,@RequestHeader("Authorization")String token,@RequestHeader("If-Match")String version){return client.post().uri(orders+"/orders/"+id+"/cancel").header("Authorization",token).header("If-Match",version).exchangeToMono(r->r.toEntity(String.class));}
 @GetMapping("/api/orders") Mono<Map> list(@RequestHeader("Authorization")String token,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){if(page<0||page>100000||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");return get(queries+"/orders?page="+page+"&size="+size,token);}
 @GetMapping("/api/orders/{id}") Mono<Map<String,Object>> detail(@PathVariable UUID id,@RequestHeader("Authorization")String token){
  // Command service also checks ownership. Its version exposes projection lag to the customer.
  Mono<Map> current=get(orders+"/orders/"+id,token);
  Mono<Map> projected=get(queries+"/orders/"+id,token).onErrorResume(e->e instanceof WebClientResponseException w && (w.getStatusCode().value()==401||w.getStatusCode().value()==403)?Mono.error(e):Mono.just(Map.of("unavailable",true)));
  return Mono.zip(current,projected).map(pair->{Map a=pair.getT1(),q=pair.getT2();return Map.<String,Object>of("order",a,"projection",q,"degraded",q.containsKey("unavailable"),"commandVersion",a.get("version"));});
 }
 @GetMapping(value="/api/orders/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
 Flux<ServerSentEvent<String>> events(@PathVariable UUID id,@RequestHeader("Authorization")String token,@RequestHeader(value="Last-Event-ID",defaultValue="0")String last){
  return client.get().uri(queries+"/orders/"+id+"/events").header("Authorization",token).header("Last-Event-ID",last).retrieve().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>(){}).limitRate(16).onBackpressureBuffer(64);
 }
 @ExceptionHandler(WebClientResponseException.class) ResponseEntity<ProblemDetail> downstream(WebClientResponseException e){return ResponseEntity.status(e.getStatusCode()).body(ProblemDetail.forStatusAndDetail(e.getStatusCode(),"Downstream request rejected"));}
 @ExceptionHandler({java.util.concurrent.TimeoutException.class,io.github.resilience4j.circuitbreaker.CallNotPermittedException.class,WebClientRequestException.class}) ResponseEntity<ProblemDetail> unavailable(Exception e){return ResponseEntity.status(503).body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"Service temporarily unavailable; retry with the same command key"));}
}

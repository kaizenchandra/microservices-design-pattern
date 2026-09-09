package com.synechis.fulfillment.runtime;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.core.KafkaTemplate;
import com.synechis.fulfillment.contracts.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
@Component
public class OutboxRelay {
 @org.springframework.beans.factory.annotation.Autowired(required=false) private io.micrometer.tracing.Tracer tracer;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private io.micrometer.tracing.propagation.Propagator propagator;
 private final Database db; private final KafkaTemplate<String,String> kafka; private final MeterRegistry metrics;
 @Value("${app.relay.enabled:true}") boolean enabled;
 public OutboxRelay(Database db,KafkaTemplate<String,String> kafka,MeterRegistry metrics){this.db=db;this.kafka=kafka;this.metrics=metrics;
  metrics.gauge("outbox.backlog",this,r->r.db.sql.queryForObject("select count(*) from outbox where published_at is null",Long.class));
 }
 @Scheduled(fixedDelayString="${app.relay.delay:100}") public void tick(){if(enabled)for(int i=0;i<32;i++)if(!relayOne(false))break;}
 public boolean relayOne(boolean crashAfterSend){return db.transaction(()->{
  var rows=db.sql.queryForList("select o.* from outbox o where published_at is null and next_attempt<=now() and not exists(select 1 from outbox p where p.aggregate_id=o.aggregate_id and p.id<o.id and p.published_at is null) order by id limit 1 for update of o skip locked");
  if(rows.isEmpty())return false;
  var row=rows.getFirst();long id=((Number)row.get("id")).longValue();
  io.micrometer.tracing.Span span=null;
  if(tracer!=null&&propagator!=null&&row.get("topic").equals("fulfillment.v1")){
   Event event=Json.read(row.get("body").toString(),Event.class);
   span=propagator.extract(event.traceContext(),(Map<String,String> carrier,String key)->carrier.get(key)).name("outbox.publish").start();
  }
  try(var scope=tracer==null||span==null?null:tracer.withSpan(span)){
   kafka.send(row.get("topic").toString(),row.get("aggregate_id").toString(),row.get("body").toString()).get(5,TimeUnit.SECONDS);
   if(crashAfterSend)throw new SimulatedCrash();
   db.sql.update("update outbox set published_at=now() where id=?",id);metrics.counter("outbox.published").increment();
  }catch(SimulatedCrash e){throw e;}catch(Exception e){
   db.sql.update("update outbox set attempts=attempts+1,next_attempt=now()+(least(60,power(2,least(attempts,6)))+random())*interval '1 second' where id=?",id);
   metrics.counter("outbox.failures").increment();
  }finally{if(span!=null)span.end();}return true;
 });}
 @Scheduled(fixedDelay=3600000) public void cleanup(){db.sql.update("delete from outbox where published_at<now()-interval '7 days'");}
 public static class SimulatedCrash extends RuntimeException {}
}

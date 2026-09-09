package com.synechis.fulfillment.runtime;
import com.synechis.fulfillment.contracts.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.util.*;
@Component
public class Messaging {
 private final Database db; private final org.springframework.beans.factory.ObjectProvider<EventHandler> handlers;
 public Messaging(Database db,org.springframework.beans.factory.ObjectProvider<EventHandler> handlers){this.db=db;this.handlers=handlers;}
 public void publish(Event original){Event event=Correlation.enrich(original);db.sql.update("insert into outbox(event_id,aggregate_id,body) values(?,?,?::jsonb)",event.eventId(),event.aggregateId(),Json.write(event));}
 @KafkaListener(topics="fulfillment.v1",groupId="${spring.application.name}",autoStartup="${app.consumer.enabled:true}")
 public void receive(ConsumerRecord<String,String> record,Acknowledgment ack){
  Boolean skip=db.sql.queryForObject("select coalesce((select approved_skip from quarantine where partition_id=? and position=?),false)",Boolean.class,record.partition(),record.offset());
  if(!skip)try{accept(record.value(),record.partition(),record.offset());}catch(RuntimeException error){
   db.transaction(()->{if(db.sql.update("insert into quarantine(partition_id,position,body,reason) values(?,?,?,?) on conflict do nothing",record.partition(),record.offset(),record.value(),error.getClass().getSimpleName())==1){
    UUID id=UUID.randomUUID();db.sql.update("insert into outbox(event_id,aggregate_id,body,topic) values(?,?,?::jsonb,'fulfillment.dlt.v1')",id,id,Json.write(Map.of("partition",record.partition(),"offset",record.offset(),"raw",record.value(),"error",error.getClass().getSimpleName())));
   }return null;});throw error;
  }
  // A process death here redelivers; the committed inbox makes that harmless.
  ack.acknowledge();
 }
 public void accept(String body,int partition,long offset){
  Event event=Json.read(body,Event.class);
  Correlation.set(Correlation.from(event));
  try{db.transaction(()->{
   db.lock(event.aggregateId());
   if(db.sql.update("insert into inbox(event_id,received_at) values(?,now()) on conflict do nothing",event.eventId())==0)return null;
   if(event.schemaVersion()!=1)throw new IllegalArgumentException("Unsupported integration schema; partition remains blocked");
   for(EventHandler h:handlers)h.handle(event);
   db.sql.update("insert into consumer_position(partition_id,position,updated_at) values(?,?,now()) on conflict(partition_id) do update set position=excluded.position,updated_at=now()",partition,offset);
   return null;
  });}finally{Correlation.clear();}
 }
}

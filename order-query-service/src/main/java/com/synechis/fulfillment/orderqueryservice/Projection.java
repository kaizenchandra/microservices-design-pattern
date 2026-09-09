package com.synechis.fulfillment.orderqueryservice;
import com.synechis.fulfillment.runtime.*;
import com.synechis.fulfillment.contracts.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
@Service
public class Projection implements EventHandler {
 private final Database db;private final MeterRegistry metrics;
 public Projection(Database db,MeterRegistry metrics){this.db=db;this.metrics=metrics;
  metrics.gauge("projection.gaps",this,p->p.db.sql.queryForObject("select count(*) from event_archive a left join order_view v on v.order_id=a.order_id and v.generation=(select generation from projection_head) where a.version>coalesce(v.version,0)",Long.class));
 }
 public void handle(Event e){
  if(!e.eventType().equals("OrderState"))return;
  if(!e.producer().equals("order-service"))throw new IllegalArgumentException("Invalid projection producer");
  db.lock("projection-cutover");
  db.sql.update("insert into event_archive(event_id,order_id,version,body,occurred_at) values(?,?,?,?::jsonb,?) on conflict(event_id) do nothing",e.eventId(),e.aggregateId(),e.aggregateVersion(),Json.write(e.payload()),java.sql.Timestamp.from(e.occurredAt()));
  advance(head(),e.aggregateId());metrics.timer("projection.delivery.lag").record(java.time.Duration.between(e.occurredAt(),java.time.Instant.now()).abs());
 }
 private UUID head(){return db.sql.queryForObject("select generation from projection_head where id=1",UUID.class);}
 private void advance(UUID generation,UUID id){
  long version=db.sql.queryForObject("select coalesce((select version from order_view where generation=? and order_id=?),0)",Long.class,generation,id);
  for(var row:db.sql.queryForList("select * from event_archive where order_id=? and version>? order by version",id,version)){
   long next=((Number)row.get("version")).longValue();if(next!=version+1)break;
   var body=Json.map(row.get("body").toString());
   db.sql.update("insert into order_view(generation,order_id,customer,version,body) values(?,?,?,?,?::jsonb) on conflict(generation,order_id) do update set version=excluded.version,body=excluded.body,projected_at=now()",generation,id,body.get("customer"),next,Json.write(body));version=next;
  }
 }
 public Map<String,Object> get(UUID id,String customer){var rows=db.sql.queryForList("select * from order_view where generation=(select generation from projection_head) and order_id=? and customer=?",id,customer);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not visible; projection may be catching up");var row=rows.getFirst();var result=new LinkedHashMap<>(Json.map(row.get("body").toString()));result.put("projectedAt",row.get("projected_at").toString());result.put("consistency","eventual");return result;}
 public Map<String,Object> list(String customer,int page,int size){if(page<0||page>100000||size<1||size>100)throw new IllegalArgumentException("Invalid pagination");var rows=db.sql.queryForList("select body from order_view where generation=(select generation from projection_head) and customer=? order by order_id limit ? offset ?",customer,size,(long)page*size);return Map.of("items",rows.stream().map(r->Json.map(r.get("body").toString())).toList(),"page",page,"size",size,"consistency","eventual");}
 public List<Map<String,Object>> changes(UUID id,String customer,long after){
  var current=get(id,customer);long version=((Number)current.get("version")).longValue();
  if(after<0||after>version||version-after>100)return List.of(Map.of("event","reset","id",version,"data",current));
  return db.sql.queryForList("select version,body from event_archive where order_id=? and version>? and version<=? order by version limit 100",id,after,version).stream().map(r->Map.<String,Object>of("event","order","id",r.get("version"),"data",Json.map(r.get("body").toString()))).toList();
 }
 public UUID rebuild(){
  UUID generation=UUID.randomUUID();
  // Build shadow rows in short transactions while ingestion continues. The final pass
  // takes the SAME lock as ingestion and catches every committed archived order.
  var ids=db.sql.queryForList("select distinct order_id from event_archive",UUID.class);
  for(UUID id:ids)db.transaction(()->{advance(generation,id);return null;});
  db.transaction(()->{db.lock("projection-cutover");for(UUID id:db.sql.queryForList("select distinct order_id from event_archive",UUID.class))advance(generation,id);db.sql.update("update projection_head set generation=? where id=1",generation);return null;});
  return generation;
 }
}

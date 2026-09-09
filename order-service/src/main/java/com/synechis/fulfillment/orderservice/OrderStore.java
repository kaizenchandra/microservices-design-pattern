package com.synechis.fulfillment.orderservice;
import com.synechis.fulfillment.runtime.*;
import com.synechis.fulfillment.contracts.*;
import org.springframework.stereotype.Component;
import org.springframework.dao.OptimisticLockingFailureException;
import java.util.*;
@Component
public class OrderStore {
 private final Database db;private final Messaging messaging;
 public OrderStore(Database db,Messaging messaging){this.db=db;this.messaging=messaging;}
 public OrderAggregate load(UUID id,boolean snapshots){
  OrderAggregate a=new OrderAggregate();
  if(snapshots){var rows=db.sql.queryForList("select * from order_snapshot where aggregate_id=? and schema_version=1",id);
   if(!rows.isEmpty())a=Json.read(rows.getFirst().get("body").toString(),OrderAggregate.class);}
  for(var row:db.sql.queryForList("select * from domain_event where aggregate_id=? and version>? order by version",id,a.version)){
   if(((Number)row.get("version")).longValue()!=a.version+1)throw new IllegalStateException("Corrupt event stream");
   a.apply(row.get("type").toString(),((Number)row.get("schema_version")).intValue(),Json.map(row.get("body").toString()));
  }return a;
 }
 public void append(UUID id,OrderAggregate a,String type,Map<String,Object> payload,Event cause){
  if(db.sql.update("update order_stream set version=version+1 where id=? and version=?",id,a.version)!=1)throw new OptimisticLockingFailureException("Stale aggregate version");
  db.sql.update("insert into domain_event(aggregate_id,version,type,schema_version,body) values(?,?,?,1,?::jsonb)",id,a.version+1,type,Json.write(payload));
  a.apply(type,1,payload);
  if(a.version%5==0)db.sql.update("insert into order_snapshot values(?,?,1,?::jsonb) on conflict(aggregate_id) do update set version=excluded.version,body=excluded.body,schema_version=1",id,a.version,Json.write(a));
  db.sql.update("update order_stream set closed=?,terminal=? where id=?",a.terminal()||a.aborting(),a.terminal(),id);
  messaging.publish(Event.next("OrderState",id,a.version,"order-service",a.view(id),cause));
  if(Set.of("Placed","Aborting","Confirmed").contains(type))messaging.publish(Event.next("Order"+type,id,a.version,"order-service",a.view(id),cause));
 }
}

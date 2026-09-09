package com.synechis.fulfillment.orderservice;
import java.util.*;
/** Replayed state is local to Order; no method here performs I/O. */
public class OrderAggregate {
 public long version; public String status="NEW"; public Map<String,Object> data=new LinkedHashMap<>();
 public Set<String> facts=new HashSet<>();
 public void apply(String type,int schemaVersion,Map<String,Object> payload){
  if(schemaVersion==0){payload=new LinkedHashMap<>(payload);if(payload.containsKey("customerId"))payload.put("customer",payload.remove("customerId"));}
  else if(schemaVersion!=1)throw new IllegalArgumentException("Unknown domain schema");
  facts.add(type);
  switch(type){
   case "Placed" -> {data.putAll(payload);status="PENDING";}
   case "Aborting" -> {status="COMPENSATING";data.put("reason",payload.get("reason"));}
   case "Confirmed" -> {if(!status.equals("PENDING"))throw new IllegalStateException("Confirmation after abort");status="CONFIRMED";}
   case "Compensated" -> status="CANCELLED".equals(data.get("reason"))?"CANCELLED":"FAILED";
   case "RecoveryRequired" -> status="MANUAL_RECOVERY";
   default -> { }
  }
  version++;
 }
 public boolean aborting(){return Set.of("COMPENSATING","MANUAL_RECOVERY","CANCELLED","FAILED").contains(status);}
 public boolean terminal(){return Set.of("CONFIRMED","CANCELLED","FAILED").contains(status);}
 public Map<String,Object> view(UUID id){var p=new LinkedHashMap<>(data);p.put("orderId",id.toString());p.put("status",status);p.put("version",version);p.put("progress",facts.stream().sorted().toList());return p;}
}

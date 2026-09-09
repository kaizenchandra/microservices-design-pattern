package com.synechis.fulfillment.contracts;
import java.time.Instant;
import java.util.*;
public record Event(UUID eventId, String eventType, int schemaVersion, UUID aggregateId,
 long aggregateVersion, Instant occurredAt, String producer, String correlationId,
 String causationId, Map<String,String> traceContext, Map<String,Object> payload) {
 public String text(String key) { return Objects.toString(payload.get(key), ""); }
 public int number(String key) { return Integer.parseInt(text(key)); }
 public static Event next(String type, UUID id, long version, String producer, Map<String,Object> payload, Event cause) {
  return new Event(UUID.randomUUID(),type,1,id,version,Instant.now(),producer,
   cause==null?id.toString():cause.correlationId(),cause==null?"":cause.eventId().toString(),cause==null?Map.of():cause.traceContext(),Map.copyOf(payload));
 }
}

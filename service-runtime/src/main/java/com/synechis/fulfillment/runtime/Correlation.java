package com.synechis.fulfillment.runtime;
import com.synechis.fulfillment.contracts.Event;
import java.util.*;
import org.slf4j.MDC;
public final class Correlation {
 private static final ThreadLocal<Map<String,String>> CONTEXT=new ThreadLocal<>();
 public static Map<String,String> current(){var c=CONTEXT.get();return c==null?Map.of():c;}
 public static void set(Map<String,String> values){CONTEXT.set(values);MDC.put("correlationId",values.getOrDefault("correlationId",""));String trace=values.getOrDefault("traceparent","");if(trace.length()==55)MDC.put("traceId",trace.substring(3,35));}
 public static void clear(){CONTEXT.remove();MDC.remove("correlationId");MDC.remove("traceId");}
 public static Event enrich(Event e){var c=current();return new Event(e.eventId(),e.eventType(),e.schemaVersion(),e.aggregateId(),e.aggregateVersion(),e.occurredAt(),e.producer(),c.getOrDefault("correlationId",e.correlationId()),e.causationId(),e.traceContext().isEmpty()?c:e.traceContext(),e.payload());}
 public static Map<String,String> from(Event e){var c=new HashMap<>(e.traceContext());c.put("correlationId",e.correlationId());return c;}
}

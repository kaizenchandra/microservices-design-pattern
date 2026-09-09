package com.synechis.fulfillment.runtime;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
@Component
public class OperationalMetrics {
 public OperationalMetrics(Database db,MeterRegistry metrics){
  metrics.gauge("outbox.oldest.seconds",db,d->d.sql.queryForObject("select coalesce(extract(epoch from now()-min(created_at)),0) from outbox where published_at is null",Double.class));
  metrics.gauge("consumer.quarantine",db,d->d.sql.queryForObject("select count(*) from quarantine where not approved_skip",Long.class));
 }
}

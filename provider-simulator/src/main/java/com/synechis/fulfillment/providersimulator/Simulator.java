package com.synechis.fulfillment.providersimulator;
import com.synechis.fulfillment.runtime.Database;
import com.synechis.fulfillment.contracts.Json;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController @RequestMapping("/sim")
public class Simulator {
 private final Database db;private final String key;
 public Simulator(Database db,@Value("${PROVIDER_KEY:local-simulator-only}") String key){this.db=db;this.key=key;}
 private void authorize(String supplied,String kind){if(!java.security.MessageDigest.isEqual(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);if(!Set.of("payment","shipping").contains(kind))throw new IllegalArgumentException("kind");}
 @GetMapping("/{kind}/{id}") ResponseEntity<?> get(@PathVariable String kind,@PathVariable UUID id,@RequestHeader("X-Simulator-Key") String supplied){authorize(supplied,kind);var rows=db.sql.queryForList("select state from provider_operation where kind=? and id=?",kind,id);return rows.isEmpty()?ResponseEntity.notFound().build():ResponseEntity.ok(rows.getFirst());}
 @PostMapping("/{kind}/{id}") ResponseEntity<?> create(@PathVariable String kind,@PathVariable UUID id,@RequestHeader("X-Simulator-Key") String supplied,@RequestBody Map<String,Object> body){
  authorize(supplied,kind);return db.transaction(()->{db.lock(id);var rows=db.sql.queryForList("select * from provider_operation where kind=? and id=?",kind,id);String fingerprint=Json.canonical(body);
   if(!rows.isEmpty()){var old=rows.getFirst();if(!old.get("fingerprint").equals(fingerprint))return ResponseEntity.status(409).body(Map.of("error","Conflicting provider idempotency key"));
    if(old.get("state").equals("ABSENT")){db.sql.update("update provider_operation set state='SUCCEEDED',attempts=attempts+1 where kind=? and id=?",kind,id);return ResponseEntity.ok(Map.of("state","SUCCEEDED"));}
    return ResponseEntity.ok(Map.of("state",old.get("state")));}
   String mode=body.get("mode").toString();String state=switch(mode){case "REJECT"->"REJECTED";case "AMBIGUOUS"->"UNKNOWN";case "TIMEOUT_BEFORE"->"ABSENT";default->"SUCCEEDED";};
   db.sql.update("insert into provider_operation(kind,id,fingerprint,mode,state) values(?,?,?,?,?)",kind,id,fingerprint,mode,state);
   // Return timeout AFTER the DB transaction commits a provider effect. No arbitrary sleep.
   return mode.startsWith("TIMEOUT")?ResponseEntity.status(504).body(Map.of("state","UNKNOWN")):ResponseEntity.ok(Map.of("state",state));
  });
 }
 @PostMapping("/{kind}/compensate/{id}") ResponseEntity<?> compensate(@PathVariable String kind,@PathVariable UUID id,@RequestHeader("X-Simulator-Key") String supplied){
  authorize(supplied,kind);return db.transaction(()->{db.lock(id);var row=db.sql.queryForMap("select * from provider_operation where kind=? and id=?",kind,id);
   db.sql.update("update provider_operation set compensation_attempts=compensation_attempts+1 where kind=? and id=?",kind,id);
   if(row.get("mode").equals("COMPENSATION_RETRY")&&((Number)row.get("compensation_attempts")).intValue()==0)return ResponseEntity.status(503).body(Map.of("state","UNKNOWN"));
   if(row.get("state").equals("UNKNOWN"))return ResponseEntity.status(409).body(Map.of("state","UNKNOWN"));
   db.sql.update("update provider_operation set state='COMPENSATED' where kind=? and id=?",kind,id);return ResponseEntity.ok(Map.of("state","COMPENSATED"));
  });
 }
 @PostMapping("/{kind}/resolve/{id}") Map<String,Object> resolve(@PathVariable String kind,@PathVariable UUID id,@RequestHeader("X-Simulator-Key") String supplied,@RequestBody Map<String,String> body){authorize(supplied,kind);String state=body.get("state");if(!Set.of("SUCCEEDED","REJECTED").contains(state))throw new IllegalArgumentException("Resolution must be authoritative");db.transaction(()->{db.lock(id);db.sql.update("update provider_operation set state=? where kind=? and id=? and state='UNKNOWN'",state,kind,id);return null;});return Map.of("state",state);}
}

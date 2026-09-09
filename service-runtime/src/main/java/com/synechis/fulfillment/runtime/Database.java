package com.synechis.fulfillment.runtime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.function.Supplier;
@Component
public class Database {
 public final JdbcTemplate sql; private final TransactionTemplate tx;
 public Database(JdbcTemplate sql,TransactionTemplate tx){this.sql=sql;this.tx=tx;}
 public <T>T transaction(Supplier<T> action){return tx.execute(s->action.get());}
 public void lock(UUID id){sql.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,id.toString());}
 public void lock(String id){sql.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,id);}
}

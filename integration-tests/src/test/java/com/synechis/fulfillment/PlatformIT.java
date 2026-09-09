package com.synechis.fulfillment;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.synechis.fulfillment.contracts.Event;
import com.synechis.fulfillment.contracts.Json;
import com.synechis.fulfillment.runtime.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Runs the actual executable jars, isolated service databases, real Kafka and signed JWTs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformIT {
    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8.2.1-alpine")).withExposedPorts(6379);
    final String[] stateful = {"order-service", "inventory-service", "payment-service", "shipping-service", "order-query-service", "provider-simulator"};
    final Map<String, Process> processes = new ConcurrentHashMap<>();
    final Map<String, Integer> ports = new HashMap<>();
    final Map<String, JdbcTemplate> databases = new HashMap<>();
    final Path root = Path.of("..").toAbsolutePath().normalize();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    HttpServer oidc;
    RSAKey rsa;
    String issuer, alice, bob, ops;
    KafkaTemplate<String, String> producer;

    @BeforeAll
    void start() throws Exception {
        postgres.start();
        kafka.start();
        redis.start();
        rsa = new RSAKeyGenerator(2048).keyID("integration").generate();
        oidc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + oidc.getAddress().getPort();
        oidc.createContext("/jwks", x -> {
            byte[] body = new JWKSet(rsa.toPublicJWK()).toString().getBytes();
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        oidc.start();
        alice = token("alice", "customer");
        bob = token("bob", "customer");
        ops = token("ops", "ops");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        for (String m : stateful) {
            String name = m.replace('-', '_');
            admin.execute("create role " + name + " login password 'integration-only'");
            admin.execute("create database " + name + " owner " + name);
            admin.execute("revoke connect on database " + name + " from public");
            databases.put(m, new JdbcTemplate(new DriverManagerDataSource(dbUrl(m), name, "integration-only")));
        }
        var config = new HashMap<String, Object>();
        config.put("bootstrap.servers", kafka.getBootstrapServers());
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("delivery.timeout.ms", 3000);
        config.put("request.timeout.ms", 1000);
        config.put("max.block.ms", 3000);
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        for (String m : stateful) ports.put(m, freePort());
        ports.put("customer-bff", freePort());
        ports.put("api-gateway", freePort());
        for (String m : stateful) launch(m);
        for (String m : stateful) ready(m);
        launch("customer-bff");
        ready("customer-bff");
        launch("api-gateway");
        ready("api-gateway");
    }

    String dbUrl(String m) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + m.replace('-', '_');
    }

    int freePort() throws Exception {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    String token(String user, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(user).audience("shop").claim("scope", scope).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    void launch(String m) throws Exception {
        var p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar", root.resolve(m + "/target/" + m + "-1.0.0-SNAPSHOT.jar").toString());
        var env = p.environment();
        env.put("MANAGEMENT_TRACING_EXPORT_ENABLED", "false");
        env.put("PORT", ports.get(m).toString());
        env.put("OIDC_ISSUER", issuer);
        env.put("OIDC_JWKS", issuer + "/jwks");
        env.put("KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers());
        env.put("DB_URL", dbUrl(m));
        env.put("DB_USER", m.replace('-', '_'));
        env.put("DB_PASSWORD", "integration-only");
        env.put("PROVIDER_URL", url("provider-simulator"));
        env.put("ORDER_URL", url("order-service"));
        env.put("QUERY_URL", url("order-query-service"));
        env.put("BFF_URL", url("customer-bff"));
        env.put("REDIS_HOST", redis.getHost());
        env.put("SPRING_DATA_REDIS_PORT", redis.getMappedPort(6379).toString());
        Files.createDirectories(root.resolve("integration-tests/target/service-logs"));
        p.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("integration-tests/target/service-logs/" + m + ".log").toFile()));
        processes.put(m, p.start());
    }

    String url(String m) {
        return "http://127.0.0.1:" + ports.get(m);
    }

    void ready(String m) {
        await().ignoreExceptions().failFast(() -> !processes.get(m).isAlive()).atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            assertThat(processes.get(m).isAlive()).as(m + " process, see target/service-logs").isTrue();
            assertThat(send(m, "GET", "/actuator/health/readiness", null, null, Map.of()).statusCode()).isEqualTo(200);
        });
    }

    void stop(String m) throws Exception {
        Process p = processes.remove(m);
        if (p != null) {
            p.destroy();
            if (!p.waitFor(25, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @AfterAll
    void close() throws Exception {
        for (String m : new ArrayList<>(processes.keySet())) stop(m);
        if (producer != null) producer.destroy();
        if (oidc != null) oidc.stop(0);
        redis.stop();
        kafka.stop();
        postgres.stop();
    }

    HttpResponse<String> send(String service, String method, String path, String token, Object body, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url(service) + path)).timeout(Duration.ofSeconds(12));
        if (token != null) b.header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> request(String sku, String payment, String shipping) {
        return Map.of("sku", sku, "quantity", 1, "amount", 19.99, "currency", "USD", "paymentMode", payment, "shippingMode", shipping);
    }

    UUID place(String sku, String payment, String shipping) throws Exception {
        var r = send("order-service", "POST", "/orders", alice, request(sku, payment, shipping), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        return UUID.fromString(Json.map(r.body()).get("orderId").toString());
    }

    Map<String, Object> order(UUID id) throws Exception {
        var r = send("order-service", "GET", "/orders/" + id, alice, null, Map.of());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.map(r.body());
    }

    void status(UUID id, String expected) {
        await().ignoreExceptions().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(order(id).get("status")).isEqualTo(expected));
    }

    JdbcTemplate db(String service) {
        return databases.get(service);
    }

    long count(String service, String sql, Object... args) {
        return db(service).queryForObject(sql, Long.class, args);
    }

    void remote(UUID id, String kind, String state) {
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(db("provider-simulator").queryForObject("select state from provider_operation where kind=? and id=?", String.class, kind, id)).isEqualTo(state));
    }

    void publish(Event e) throws Exception {
        producer.send("fulfillment.v1", e.aggregateId().toString(), Json.write(e)).get(10, TimeUnit.SECONDS);
    }

    @Test
    @Order(1)
    void successfulFulfillment() throws Exception {
        UUID id = place("DEMO", "SUCCESS", "SUCCESS");
        status(id, "CONFIRMED");
        remote(id, "payment", "SUCCEEDED");
        remote(id, "shipping", "SUCCEEDED");
        assertThat(count("inventory-service", "select count(*) from reservation where order_id=? and state='COMMITTED'", id)).isEqualTo(1);
    }

    @Test
    @Order(2)
    void insufficientInventoryNeverAuthorizesPayment() throws Exception {
        UUID id = place("EMPTY", "SUCCESS", "SUCCESS");
        status(id, "FAILED");
        assertThat(count("provider-simulator", "select count(*) from provider_operation where id=?", id)).isZero();
    }

    @Test
    @Order(3)
    void rejectedPaymentReleasesInventory() throws Exception {
        UUID id = place("DEMO", "REJECT", "SUCCESS");
        status(id, "FAILED");
        assertThat(count("inventory-service", "select count(*) from reservation where order_id=? and state='RELEASED'", id)).isEqualTo(1);
        assertThat(count("provider-simulator", "select count(*) from provider_operation where id=? and kind='shipping'", id)).isZero();
    }

    @Test
    @Order(4)
    void rejectedShippingVoidsPayment() throws Exception {
        UUID id = place("DEMO", "SUCCESS", "REJECT");
        status(id, "FAILED");
        remote(id, "payment", "COMPENSATED");
    }

    @Test
    @Order(5)
    void duplicateEventsHaveNoDuplicateEffects() throws Exception {
        UUID id = place("DEMO", "SUCCESS", "SUCCESS");
        status(id, "CONFIRMED");
        long version = ((Number) order(id).get("version")).longValue();
        String raw = db("order-service").queryForObject("select body::text from outbox where aggregate_id=? and body->>'eventType'='OrderPlaced'", String.class, id);
        Event e = Json.read(raw, Event.class);
        publish(e);
        publish(e);
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("inventory-service", "select count(*) from inbox where event_id=?", e.eventId())).isEqualTo(1));
        assertThat(((Number) order(id).get("version")).longValue()).isEqualTo(version);
        assertThat(count("provider-simulator", "select sum(attempts) from provider_operation where id=?", id)).isEqualTo(2);
    }

    @Test
    @Order(6)
    void lastUnitCannotBeOversold() throws Exception {
        db("inventory-service").update("update stock set available=1 where sku='LAST'");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> {
                gate.await();
                return place("LAST", "SUCCESS", "SUCCESS");
            });
            var b = pool.submit(() -> {
                gate.await();
                return place("LAST", "SUCCESS", "SUCCESS");
            });
            gate.countDown();
            UUID x = a.get(), y = b.get();
            await().ignoreExceptions().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                var states = List.of(order(x).get("status"), order(y).get("status"));
                assertThat(states).containsExactlyInAnyOrder("CONFIRMED", "FAILED");
            });
            assertThat(count("inventory-service", "select available from stock where sku='LAST'")).isZero();
        }
    }

    @Test
    @Order(7)
    void concurrentCancellationCommandsAppendOnce() throws Exception {
        UUID id = place("DEMO", "AMBIGUOUS", "SUCCESS");
        remote(id, "payment", "UNKNOWN");
        var version = order(id).get("version").toString();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                calls.add(pool.submit(() -> {
                    gate.await();
                    return send("order-service", "POST", "/orders/" + id + "/cancel", alice, null, Map.of("If-Match", version)).statusCode();
                }));
            gate.countDown();
            for (var f : calls) assertThat(f.get()).isEqualTo(202);
        }
        assertThat(count("order-service", "select count(*) from domain_event where aggregate_id=? and type='Aborting'", id)).isEqualTo(1);
    }

    @Test
    @Order(8)
    void committedOrderSurvivesKafkaOutage() throws Exception {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        UUID id;
        try {
            id = place("DEMO", "SUCCESS", "SUCCESS");
            await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count("order-service", "select count(*) from outbox where aggregate_id=? and attempts>0 and published_at is null", id)).isPositive());
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }
        status(id, "CONFIRMED");
    }

    @Test
    @Order(9)
    void relayCrashAfterSendRedeliversSafely() throws Exception {
        stop("order-query-service");
        try {
            Database database = new Database(db("order-query-service"), new TransactionTemplate(new DataSourceTransactionManager(db("order-query-service").getDataSource())));
            Event e = Event.next("RelayProbe", UUID.randomUUID(), 1, "test", Map.of(), null);
            database.sql.update("insert into outbox(event_id,aggregate_id,body) values(?,?,?::jsonb)", e.eventId(), e.aggregateId(), Json.write(e));
            OutboxRelay relay = new OutboxRelay(database, producer, new SimpleMeterRegistry());
            assertThatThrownBy(() -> relay.relayOne(true)).isInstanceOf(OutboxRelay.SimulatedCrash.class);
            assertThat(count("order-query-service", "select count(*) from outbox where event_id=? and published_at is null", e.eventId())).isEqualTo(1);
            assertThat(relay.relayOne(false)).isTrue();
            await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("inventory-service", "select count(*) from inbox where event_id=?", e.eventId())).isEqualTo(1));
        } finally {
            launch("order-query-service");
            ready("order-query-service");
        }
    }

    @Test
    @Order(10)
    void consumerAtomicCommitAndRedelivery() throws Exception {
        JdbcTemplate sql = db("provider-simulator");
        sql.execute("create table if not exists crash_probe(id uuid primary key,hits int not null)");
        Database database = new Database(sql, new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())));
        var fail = new AtomicBoolean(true);
        var beans = new StaticListableBeanFactory();
        beans.addBean("handler", (EventHandler) e -> {
            sql.update("insert into crash_probe values(?,1) on conflict(id) do update set hits=crash_probe.hits+1", e.aggregateId());
            if (fail.get()) throw new IllegalStateException("crash before commit");
        });
        Messaging messaging = new Messaging(database, beans.getBeanProvider(EventHandler.class));
        Event e = Event.next("CrashProbe", UUID.randomUUID(), 1, "test", Map.of(), null);
        assertThatThrownBy(() -> messaging.accept(Json.write(e), 0, 10)).isInstanceOf(IllegalStateException.class);
        assertThat(count("provider-simulator", "select count(*) from crash_probe where id=?", e.aggregateId())).isZero();
        fail.set(false);
        messaging.accept(Json.write(e), 0, 10);
        messaging.accept(Json.write(e), 0, 10);
        assertThat(count("provider-simulator", "select hits from crash_probe where id=?", e.aggregateId())).isEqualTo(1);
    }

    @Test
    @Order(11)
    void projectionBuffersGapsAndRejectsStaleState() throws Exception {
        UUID id = UUID.randomUUID();
        Event two = Event.next("OrderState", id, 2, "order-service", Map.of("customer", "alice", "status", "PENDING", "version", 2), null);
        publish(two);
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("order-query-service", "select count(*) from event_archive where event_id=?", two.eventId())).isEqualTo(1));
        assertThat(count("order-query-service", "select count(*) from order_view where order_id=?", id)).isZero();
        Event one = Event.next("OrderState", id, 1, "order-service", Map.of("customer", "alice", "status", "PENDING", "version", 1), null);
        publish(one);
        publish(two);
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("order-query-service", "select version from order_view where order_id=? and generation=(select generation from projection_head)", id)).isEqualTo(2));
    }

    @Test
    @Order(12)
    void timeoutAfterProviderEffectIsReconciled() throws Exception {
        UUID id = place("DEMO", "TIMEOUT_AFTER", "TIMEOUT_AFTER");
        status(id, "CONFIRMED");
        assertThat(count("provider-simulator", "select sum(attempts) from provider_operation where id=?", id)).isEqualTo(2);
    }

    @Test
    @Order(13)
    void compensationFailureIsRetried() throws Exception {
        UUID id = place("DEMO", "COMPENSATION_RETRY", "REJECT");
        status(id, "FAILED");
        remote(id, "payment", "COMPENSATED");
        assertThat(count("provider-simulator", "select compensation_attempts from provider_operation where id=? and kind='payment'", id)).isEqualTo(2);
    }

    @Test
    @Order(14)
    void projectionRebuildIsEquivalentAndSideEffectFree() throws Exception {
        UUID id = place("DEMO", "SUCCESS", "SUCCESS");
        status(id, "CONFIRMED");
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("order-query-service", "select version from order_view where order_id=? and generation=(select generation from projection_head)", id)).isEqualTo(((Number) order(id).get("version")).longValue()));
        String before = db("order-query-service").queryForObject("select body::text from order_view where order_id=? and generation=(select generation from projection_head)", String.class, id);
        long effects = count("provider-simulator", "select sum(attempts+compensation_attempts) from provider_operation where id=?", id);
        var r = send("order-query-service", "POST", "/admin/rebuild", ops, null, Map.of());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(db("order-query-service").queryForObject("select body::text from order_view where order_id=? and generation=(select generation from projection_head)", String.class, id)).isEqualTo(before);
        assertThat(count("provider-simulator", "select sum(attempts+compensation_attempts) from provider_operation where id=?", id)).isEqualTo(effects);
        var replay = send("order-service", "GET", "/admin/orders/" + id + "/replay", ops, null, Map.of());
        assertThat(Json.map(replay.body())).isEqualTo(order(id));
    }

    @Test
    @Order(15)
    void providerCircuitOpensAndRecovers() throws Exception {
        var healthy = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            byte[] b = "{\"state\":\"SUCCEEDED\"}".getBytes();
            x.sendResponseHeaders(healthy.get() ? 200 : 503, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        try {
            ProviderClient client = new ProviderClient("http://127.0.0.1:" + server.getAddress().getPort(), "test", new SimpleMeterRegistry());
            for (int i = 0; i < 4; i++)
                assertThatThrownBy(() -> client.call("payment", "probe", null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.call("payment", "probe", null)).isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
            healthy.set(true);
            await().ignoreExceptions().atMost(Duration.ofSeconds(12)).untilAsserted(() -> assertThat(client.call("payment", "probe", null).get("state")).isEqualTo("SUCCEEDED"));
        } finally {
            server.stop(0);
        }
        UUID id = place("DEMO", "SUCCESS", "SUCCESS");
        status(id, "CONFIRMED");
        stop("order-query-service");
        try {
            var r = send("customer-bff", "GET", "/api/orders/" + id, alice, null, Map.of());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(Json.map(r.body()).get("degraded")).isEqualTo(true);
        } finally {
            launch("order-query-service");
            ready("order-query-service");
        }
    }

    @Test
    @Order(16)
    void ownershipAndJwtAreEnforced() throws Exception {
        UUID id = place("DEMO", "SUCCESS", "SUCCESS");
        status(id, "CONFIRMED");
        assertThat(send("order-service", "GET", "/orders/" + id, bob, null, Map.of("X-Customer-ID", "alice")).statusCode()).isEqualTo(404);
        assertThat(send("order-service", "GET", "/orders/" + id, null, null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(send("api-gateway", "GET", "/api/orders/" + id, bob, null, Map.of("X-Customer-ID", "alice")).statusCode()).isEqualTo(404);
        assertThat(send("order-query-service", "POST", "/admin/rebuild", alice, null, Map.of()).statusCode()).isEqualTo(403);
    }

    @Test
    @Order(17)
    void sseReconnectRecoversMissedVersions() throws Exception {
        UUID id = place("DEMO", "SUCCESS", "SUCCESS");
        status(id, "CONFIRMED");
        long version = ((Number) order(id).get("version")).longValue();
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("order-query-service", "select version from order_view where order_id=? and generation=(select generation from projection_head)", id)).isEqualTo(version));
        var request = HttpRequest.newBuilder(URI.create(url("customer-bff") + "/api/orders/" + id + "/events")).header("Authorization", "Bearer " + alice).header("Last-Event-ID", Long.toString(version - 2)).timeout(Duration.ofSeconds(10)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body())); var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                var ids = new ArrayList<Long>();
                String line;
                while (ids.size() < 2 && (line = reader.readLine()) != null)
                    if (line.startsWith("id:")) ids.add(Long.valueOf(line.substring(3).trim()));
                return ids;
            });
            assertThat(result.get(10, TimeUnit.SECONDS)).containsExactly(version - 1, version);
        }
    }

    @Test
    @Order(18)
    void cancellationAndExpiryFenceLateSuccess() throws Exception {
        UUID id = place("DEMO", "AMBIGUOUS", "SUCCESS");
        remote(id, "payment", "UNKNOWN");
        String version = order(id).get("version").toString();
        assertThat(send("order-service", "POST", "/orders/" + id + "/cancel", alice, null, Map.of("If-Match", version)).statusCode()).isEqualTo(202);
        send("provider-simulator", "POST", "/sim/payment/resolve/" + id, null, Map.of("state", "SUCCEEDED"), Map.of("X-Simulator-Key", "local-simulator-only"));
        db("payment-service").update("update operation set next_attempt=now() where order_id=?", id);
        status(id, "CANCELLED");
        remote(id, "payment", "COMPENSATED");
        assertThat(count("order-service", "select count(*) from domain_event where aggregate_id=? and type='Confirmed'", id)).isZero();
        stop("shipping-service");
        try {
            UUID expired = place("DEMO", "SUCCESS", "SUCCESS");
            remote(expired, "payment", "SUCCEEDED");
            db("inventory-service").update("update reservation set expires_at=now()-interval '1 second' where order_id=?", expired);
            await().ignoreExceptions().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(order(expired).get("status")).isEqualTo("COMPENSATING"));
            launch("shipping-service");
            ready("shipping-service");
            status(expired, "FAILED");
            assertThat(count("order-service", "select count(*) from domain_event where aggregate_id=? and type='Confirmed'", expired)).isZero();
        } finally {
            if (!processes.containsKey("shipping-service")) {
                launch("shipping-service");
                ready("shipping-service");
            }
        }
    }

    @Test
    @Order(19)
    void conflictingIdempotencyKeysAreRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        var a = send("order-service", "POST", "/orders", alice, request("DEMO", "SUCCESS", "SUCCESS"), Map.of("Idempotency-Key", key));
        var b = send("order-service", "POST", "/orders", alice, request("EMPTY", "SUCCESS", "SUCCESS"), Map.of("Idempotency-Key", key));
        assertThat(a.statusCode()).isEqualTo(202);
        assertThat(b.statusCode()).isEqualTo(409);
    }

    @Test
    @Order(20)
    void ambiguousOutcomeRequiresManualRecovery() throws Exception {
        UUID id = place("DEMO", "AMBIGUOUS", "SUCCESS");
        remote(id, "payment", "UNKNOWN");
        db("payment-service").update("update operation set attempts=7,next_attempt=now() where order_id=?", id);
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(db("payment-service").queryForObject("select state from operation where order_id=?", String.class, id)).isEqualTo("MANUAL"));
        status(id, "MANUAL_RECOVERY");
        assertThat(send("provider-simulator", "POST", "/sim/payment/resolve/" + id, null, Map.of("state", "SUCCEEDED"), Map.of("X-Simulator-Key", "local-simulator-only")).statusCode()).isEqualTo(200);
        assertThat(send("payment-service", "POST", "/admin/operations/" + id + "/retry", ops, null, Map.of()).statusCode()).isEqualTo(200);
        status(id, "FAILED");
        remote(id, "payment", "COMPENSATED");
    }

    @Test
    @Order(21)
    void rebuildCutoverHandlesArrivingEvents() throws Exception {
        long generations = count("order-query-service", "select count(distinct generation) from order_view");
        try (var connection = db("order-query-service").getDataSource().getConnection(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended('projection-cutover',0))")) {
                lock.execute();
            }
            var rebuild = pool.submit(() -> send("order-query-service", "POST", "/admin/rebuild", ops, null, Map.of()));
            await().ignoreExceptions().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count("order-query-service", "select count(distinct generation) from order_view")).isGreaterThan(generations));
            UUID id = place("DEMO", "SUCCESS", "SUCCESS");
            connection.commit();
            assertThat(rebuild.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            status(id, "CONFIRMED");
            await().ignoreExceptions().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(count("order-query-service", "select version from order_view where order_id=? and generation=(select generation from projection_head)", id)).isEqualTo(((Number) order(id).get("version")).longValue()));
        }
    }

    @Test
    @Order(22)
    void edgeLimitsAndProblemsAreEnforced() throws Exception {
        var oversized = send("api-gateway", "POST", "/api/orders", alice, Map.of("padding", "x".repeat(17000)), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(Json.map(oversized.body()).get("status")).isEqualTo(413);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            String limited = token("rate-test", "customer");
            var calls = new ArrayList<Future<Integer>>();
            var gate = new CountDownLatch(1);
            for (int i = 0; i < 60; i++)
                calls.add(pool.submit(() -> {
                    gate.await();
                    return send("api-gateway", "GET", "/api/orders", limited, null, Map.of()).statusCode();
                }));
            gate.countDown();
            var codes = new ArrayList<Integer>();
            for (var f : calls) codes.add(f.get(15, TimeUnit.SECONDS));
            assertThat(codes).contains(429);
        }
    }

    @Test
    @Order(23)
    void missingSubjectIsRejectedAtEveryBoundary() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("shop")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(60))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        for (String service : List.of("api-gateway", "customer-bff", "order-service", "order-query-service")) {
            String path = service.equals("api-gateway") || service.equals("customer-bff") ? "/api/orders" : "/orders";
            assertThat(send(service, "GET", path, jwt.serialize(), null, Map.of()).statusCode()).isEqualTo(401);
        }
    }

}

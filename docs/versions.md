# Verified dependency matrix

Verified 2026-09-09 against official documentation and resolved Maven artifacts. Java 21 is installed and used for
verification, even though the shell default is newer.

| Dependency                            | Version      | Authority                                                  |
|---------------------------------------|--------------|------------------------------------------------------------|
| Java                                  | 21           | Compiler release, runtime image and CI                     |
| Maven                                 | 3.9.16       | Generated Maven Wrapper; wrapper distribution 3.3.4        |
| Spring Boot                           | 4.0.8        | Boot parent/BOM                                            |
| Spring Cloud                          | 2025.1.3     | Cloud BOM; officially compatible with Boot 4.0.x           |
| Spring Cloud Gateway / CircuitBreaker | 5.0.3        | Cloud BOM                                                  |
| Resilience4j                          | 2.3.0        | Cloud CircuitBreaker BOM, core modules (no Boot 3 starter) |
| Hibernate ORM                         | 7.2.24.Final | Boot BOM                                                   |
| Spring Data release train             | 2025.1.7     | Boot BOM                                                   |
| Spring Kafka                          | 4.0.7        | Boot BOM                                                   |
| Kafka client and broker               | 4.1.2        | Boot BOM and Apache image                                  |
| Flyway                                | 11.14.1      | Boot BOM                                                   |
| PostgreSQL JDBC                       | 42.7.13      | Boot BOM                                                   |
| Jackson                               | 3.1.5        | Boot BOM; `tools.jackson` API                              |
| Testcontainers                        | 2.0.5        | Boot BOM                                                   |
| PostgreSQL server                     | 17.6-alpine  | Explicit reproducible local image                          |
| Redis                                 | 8.2.1-alpine | Explicit local image                                       |
| Keycloak                              | 26.6.3       | Explicit local image                                       |

Official sources:

- https://spring.io/projects/spring-cloud/ — release compatibility matrix.
- https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html — Boot managed versions.
- https://docs.spring.io/spring-cloud-circuitbreaker/reference/spring-cloud-circuitbreaker-resilience4j.html — supported
  integration.
- https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html —
  Redis rate limiting.
- https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html —
  route metadata timeout units.
- https://java.testcontainers.org/modules/kafka/ — current KafkaContainer API.
- https://kafka.apache.org/41/getting-started/docker/ — official Kafka 4.1.2 image.
- https://www.postgresql.org/docs/release/17.6/ — PostgreSQL release.
- https://www.keycloak.org/2026/06/keycloak-2663-released — Keycloak release.

No dependency compatibility verifier is disabled. OpenAPI is maintained as a static 3.1 contract, avoiding an
unnecessary documentation runtime dependency. Runtime compilation/startup and tests are additional compatibility
evidence, not a substitute for the published matrix. Local image pins are reproducibility baselines, not a promise of
latest security patches; scan and refresh before deployment.

## Security patch override verified 2026-09-10

Tomcat is explicitly pinned to **11.0.25**, overriding Boot 4.0.8's 11.0.24 through the supported parent property `tomcat.version`. The executed image scan found CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525 in 11.0.24. Apache lists fixes in the same 11.0 patch line: https://tomcat.apache.org/security-11.html and https://tomcat.apache.org/tomcat-11.0-doc/changelog.html. Boot and Cloud versions remain unchanged; the full integration suite is rerun after this override.

The local Keycloak realm explicitly defines the basic subject mapper (`oidc-sub-mapper`), verified against https://github.com/keycloak/keycloak/blob/26.6.3/services/src/main/java/org/keycloak/protocol/oidc/mappers/SubMapper.java. All resource servers additionally require a nonempty sub claim.

Executable jars are recreated before Spring Boot repackaging (`maven.jar.forceCreation=true`) to prevent stale dependencies after a BOM-only edit. The final verification uses `clean verify` and inspects nested Tomcat jar versions. Maven documents this post-processing requirement at https://maven.apache.org/plugins/maven-jar-plugin/jar-mojo.html#forceCreation.

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

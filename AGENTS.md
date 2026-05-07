# AGENTS.md - bluetape4k-workshop

Backend examples using bluetape4k libraries.

- Kotlin 2.3.20
- Java 25
- Spring Boot 4.0.3
- bluetape4k 1.5.0-Beta2

## Commands

```bash
./gradlew build
./gradlew :exposed-domain:build
./gradlew :exposed-domain:test
./gradlew :exposed-domain:test --tests "io.bluetape4k.workshop.exposed.domain.SomeTest.testMethod"
./gradlew detekt
./gradlew clean build
```

## Module Groups

`settings.gradle.kts` auto-registers submodules with `{domain}-{submodule}`
names.

| Directory | Purpose |
|---|---|
| `aws/` | S3 and Spring Cloud examples |
| `exposed/` | Exposed DAO/SQL DSL, relations, custom columns, Spring transactions |
| `gateway/` | API gateway plus customers/orders microservices |
| `gatling/` | Gatling performance tests |
| `io/` | Okio examples |
| `json/` | Jackson 3 and JsonView examples |
| `kotlin/` | Coroutines, design patterns, Kotlin workshops |
| `mapping/` | MapStruct mapping |
| `messaging/` | Kafka examples |
| `observability/` | Micrometer observation/tracing with coroutines |
| `ratelimit/` | Bucket4j rate limiting |
| `reactive/` | Mutiny reactive streams |
| `redis/` | Redisson and cluster examples |
| `spring-boot/` | WebFlux, cache, Resilience4j, and Spring Boot features |
| `spring-data/` | R2DBC, JPA/QueryDSL, MongoDB, Elasticsearch |
| `spring-modulith/` | Spring Modulith events and JPA demos |
| `spring-security/` | MVC/WebFlux security examples |
| `vertx/` | Vert.x coroutines, SQL client, WebClient |
| `virtualthreads/` | Virtual threads with MVC/WebFlux |
| `shared/` | Shared test utilities |

## Rules

- Dependency versions live in `buildSrc/src/main/kotlin/Libs.kt`.
- Package prefix: `io.bluetape4k.workshop.{module}.*`.
- Tests are serialized by `TestMutexService` to avoid DB conflicts.
- JVM uses ZGC, 2-4 GB heap, and preview features.
- Spring Boot modules use `springBoot { mainClass.set(...) }` and extend test
  dependencies from `compileOnly`/`runtimeOnly` where the repo already does so.
- Common bluetape4k modules include logging, JUnit5, coroutines, Exposed, and
  Testcontainers.

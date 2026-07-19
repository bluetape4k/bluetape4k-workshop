# AGENTS.md - bluetape4k-workshop

This repository inherits the workspace guidance from `../AGENTS.md`.
Read and follow the workspace root guide first. This file only adds
repo-specific layout, commands, domain rules, and local exceptions.


Backend examples using bluetape4k libraries.

- Kotlin 2.4.0
- Java 21
- Spring Boot 4.0.6
- bluetape4k 1.7.0

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
| `commerce/` | PostgreSQL-authoritative order and reservation lifecycle examples |
| `commerce/` | Order, payment, inventory, fulfillment, and refund lifecycle examples |
| `exposed/` | Exposed DAO/SQL DSL, relations, custom columns, Spring transactions |
| `gateway/` | API gateway plus customers/orders microservices |
| `gatling/` | Gatling performance tests |
| `graph/` | TinkerGraph, traversal, graph-io, lineage, and audit examples |
| `image-processing/` | Image upload, moderation, derivatives, and OCR examples |
| `io/` | Okio examples |
| `json/` | Jackson 3 and JsonView examples |
| `kotlin/` | Coroutines, design patterns, Kotlin workshops |
| `leader/` | Distributed leader election and scheduling examples |
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

- Dependency versions live in `gradle/libs.versions.toml`.
- Package prefix: `io.bluetape4k.workshop.{module}.*`.
- Tests are serialized by `TestMutexService` to avoid DB conflicts.
- JVM uses the Java 21 toolchain, ZGC, 2-4 GB heap, and preview features.
- Spring Boot modules use `springBoot { mainClass.set(...) }` and extend test
  dependencies from `compileOnly`/`runtimeOnly` where the repo already does so.
- Common bluetape4k modules include logging, JUnit5, coroutines, Exposed, and
  Testcontainers.

## Dependency Version Management (MANDATORY)

Workshop modules are **consumer projects**, not bluetape4k library modules.
Use only `bluetape4k-dependencies` BOM for version management.

- **DO**: `platform(libs.bluetape4k.dependencies)` at root — this governs all `io.github.bluetape4k.*` versions
- **DO**: Declare module aliases without version in `libs.versions.toml` (resolved via BOM)
- **DON'T**: Import individual library BOMs (e.g., `platform(libs.bluetape4k.graph.bom)`)
- **DON'T**: Pin explicit bluetape4k module versions in `libs.versions.toml`

Reason: `bluetape4k-dependencies` overrides individual library BOMs in Gradle's version resolution.
Importing both creates redundancy and version confusion — the individual BOM may silently lose.

## Repo-Specific Guards

- For added, converted, archived, or moved workshop modules, update the
  validation matrix, smoke/full workflow groups, stale-check scripts, and
  lessons in the same branch.
- Consumer examples must use the `bluetape4k-dependencies` BOM only. Keep smoke
  modules separate from full container-backed modules.

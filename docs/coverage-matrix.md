# bluetape4k-workshop — Library Coverage Matrix

Maps each bluetape4k library to existing workshop examples and identifies coverage gaps
with proposed Basic/Advanced scenarios.

> Last updated: 2026-05-24

---

## How to Read This Matrix

| Column | Meaning |
|--------|---------|
| **bluetape4k lib** | Published module in `bluetape4k-dependencies` BOM |
| **Existing example** | Current workshop module demonstrating the lib |
| **Coverage level** | ✅ Good · ⚠️ Partial · ❌ Missing |
| **Gap** | What is not yet demonstrated |
| **Proposed Basic** | Minimal, in-memory scenario |
| **Proposed Advanced** | Production-shaped, Testcontainers scenario |
| **Issue** | Tracking GitHub issue |

---

## Core Libraries

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-core` | (used transitively everywhere) | ⚠️ Partial | No dedicated validation / support-ext demo | Dedicated CRUD service using `requireNotBlank`, `requirePositiveNumber` | N/A | #79 |
| `bluetape4k-logging` | All modules | ✅ Good | — | — | — | — |
| `bluetape4k-coroutines` | `kotlin/coroutines`, `observability-*`, `redis-distributed-lock` | ✅ Good | Structured concurrency patterns incomplete | — | — | — |
| `bluetape4k-junit5` | All test modules | ✅ Good | `SuspendedJobTester` / `MultithreadingTester` not shown | Concurrency test harness demo | — | — |
| `bluetape4k-assertions` | All test modules | ✅ Good | — | — | — | — |
| `bluetape4k-testcontainers` | `exposed-*`, `spring-data-*`, `redis-*`, `messaging-*` | ✅ Good | — | — | — | — |

---

## Data Access

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-exposed` (JDBC) | `exposed-mvc-jdbc` | ✅ Good | Batch insert, custom columns | — | — | #79 |
| `bluetape4k-exposed` (R2DBC) | `exposed-webflux-r2dbc` | ✅ Good | Coroutine transaction rollback | — | — | — |
| `bluetape4k-spring-boot-r2dbc` | `spring-data-r2dbc-webflux-exposed` | ⚠️ Partial | Auto-config usage not explicit | Simple R2DBC CRUD with BT auto-config | Full WebFlux CRUD + integration test | #79 |
| `bluetape4k-spring-boot-redis` | `spring-boot-cache-redis` | ⚠️ Partial | Custom codec, TTL per-key | Caffeine-first cache with Redis L2 fallback | Distributed cache cluster with TTL override | — |
| `bluetape4k-redis` | `redis-redisson-examples`, `redis-distributed-lock` | ✅ Good | Reactive Redisson not shown | — | Reactive Redisson RMapReactive example | — |
| `bluetape4k-redisson` | `redis-distributed-lock`, `redis-cluster-demo` | ✅ Good | Redisson Spring Boot auto-config | — | — | — |
| `bluetape4k-idgenerators` | `redis-distributed-lock` (indirect) | ❌ Missing | No standalone ID generator demo | `SnowflakeId` / `TimebasedUuid` generation benchmark | Distributed unique ID under concurrent load | #62 |

---

## Spring Boot Integration

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-spring-boot4-core` | `spring-boot-webflux-coroutines`, `spring-security-*` | ⚠️ Partial | WebClient BT extensions not shown | WebFlux controller + suspend handler with BT helpers | Full CRUD API with BT auto-config | #82 |
| `bluetape4k-resilience4j` | `spring-boot-resilience4j-coroutines` | ✅ Good | Bulkhead not shown | — | Bulkhead + circuit breaker + fallback pipeline | — |
| `bluetape4k-micrometer` | `observability-basic`, `observability-advanced`, `micrometer-*` | ✅ Good | Custom meter registry not shown | — | Custom MeterRegistry + Prometheus endpoint | — |

---

## Messaging

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-jackson3` | `json/jackson-examples`, `messaging-kafka` | ✅ Good | Schema evolution / compatibility not shown | Jackson 3 schema migration demo | Kafka + Avro schema registry | #83 |
| Kafka (via Spring Kafka) | `messaging-kafka`, `messaging-kafka-reply` | ✅ Good | Dead letter queue not shown | — | Kafka DLQ + retry topic pattern | #83 |

---

## Async / Reactive

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-coroutines` | `kotlin/coroutines` | ⚠️ Partial | Flow backpressure, SharedFlow not shown | Flow + StateFlow producer/consumer | Coroutine channel fan-out with backpressure | — |
| Virtual threads (JDK 21) | `virtualthreads-*` | ✅ Good | Pinning detection tooling not shown | — | Async profiler pinning report | — |
| Vert.x + coroutines | `vertx-*` | ✅ Good | — | — | — | — |

---

## Observability

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-micrometer` | `observability-basic` | ✅ Good | Exemplar linking not shown | — | Prometheus exemplar + Grafana Tempo link | — |
| Distributed tracing | `micrometer-tracing-coroutines`, `observability-advanced` | ✅ Good | — | — | — | — |

---

## Architecture / Infrastructure

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-leader` (Redis) | `leader-leader-election` | ✅ Good | Virtual thread variant less prominent | — | Leader election with health endpoint | — |
| Rate limiting | `ratelimit-*` | ✅ Good | Adaptive rate limit not shown | — | Adaptive Bucket4j + Redis with sliding window | — |
| Spring Cloud Gateway | `gateway-api-gateway` | ⚠️ Partial | Circuit breaker filter not shown | — | Gateway + Resilience4j circuit breaker filter | — |
| Spring Modulith | `spring-modulith-*` | ⚠️ Partial | Module testing isolation not shown | — | Modulith ApplicationModuleTest per bounded context | — |
| AWS S3 | `aws-s3-spring-cloud` | ⚠️ Partial | Multipart upload not shown | — | S3 multipart upload with LocalStack | — |
| `bluetape4k-aws` | `aws-s3-spring-cloud` | ⚠️ Partial | BT AWS Kotlin SDK wrappers not shown | — | AWS Kotlin SDK + coroutine suspend wrappers | — |

---

## Notable Gaps Summary

### Tier 1 — High priority (no example exists)

| Gap | Proposed module | Issue |
|-----|----------------|-------|
| `bluetape4k-idgenerators` standalone demo | `kotlin/idgenerator-workshop` | #62 |
| `bluetape4k-core` validation/support-ext demo | `kotlin/data-access-basic` | #79 |
| WebFlux CRUD with `bluetape4k-spring-boot4-core` auto-config | `spring-boot/spring-boot-basic` | #82 |
| Jackson 3 schema evolution + Kafka Avro | `messaging/messaging-basic` | #83 |

### Tier 2 — Medium priority (partial coverage)

| Gap | Proposed improvement |
|-----|---------------------|
| Redisson reactive data structures | Add to `redis-redisson-examples` |
| S3 multipart upload | Extend `aws-s3-spring-cloud` |
| Modulith `ApplicationModuleTest` | Add to `spring-modulith-jpa-demo` |
| Gateway Resilience4j filter | Add to `gateway-api-gateway` |
| Flow backpressure / SharedFlow | Add to `kotlin/coroutines` |

---

## Coverage Statistics

| Domain | Total libs tracked | ✅ Good | ⚠️ Partial | ❌ Missing |
|--------|-------------------|---------|-----------|----------|
| Core | 6 | 5 | 1 | 0 |
| Data Access | 7 | 4 | 2 | 1 |
| Spring Boot | 3 | 1 | 2 | 0 |
| Messaging | 2 | 1 | 1 | 0 |
| Async/Reactive | 3 | 2 | 1 | 0 |
| Observability | 2 | 2 | 0 | 0 |
| Architecture/Infra | 7 | 2 | 5 | 0 |
| **Total** | **30** | **17 (57%)** | **12 (40%)** | **1 (3%)** |

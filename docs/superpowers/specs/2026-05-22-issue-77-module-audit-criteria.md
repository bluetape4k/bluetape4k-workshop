# Issue #77 — Module Audit and Basic/Advanced Classification Criteria

**Date**: 2026-05-22
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/77
**Parent Epic**: #76
**Status**: Draft

---

## 1. Audit Methodology

### Scoring Dimensions

Each module is scored across four dimensions:

| Dimension | Signal | Weight |
|-----------|--------|--------|
| **BT-ref count** | `bluetape4k` references in `build.gradle.kts` | Primary |
| **Specific BT modules** | High-value libs: `bucket`, `cache`, `redis`, `virtualthread`, `exposed`, `kafka`, `micrometer`, `resilience`, `hibernate`, `r2dbc` | Secondary |
| **Production source** | `src/main` Kotlin file count | Secondary |
| **Test coverage** | `src/test` Kotlin file count | Tertiary |

### BT Value Score

| Score | Criteria |
|-------|----------|
| **HIGH** | bt-ref ≥ 6 **or** (bt-ref ≥ 4 and ≥ 3 high-value BT libs) |
| **MEDIUM** | bt-ref 3–5 and at least 1 high-value BT lib |
| **LOW** | bt-ref ≤ 2 **or** only `core`/`coroutines`/`junit`/`io` (infrastructure-only, no domain BT value) |

### Verdict Categories

| Verdict | Meaning |
|---------|---------|
| **KEEP** | Retain as-is; already demonstrates BT value |
| **CONVERT** | Retain but must increase BT-first usage in next epic work |
| **ARCHIVE** | Move to `_archive/` or remove from `settings.gradle.kts` |
| **REWRITE** | Full replacement planned (tracked by separate issue) |

---

## 2. Basic / Advanced Classification Criteria

### Basic Level

A **Basic** example satisfies ALL of the following:
- Demonstrates one primary `bluetape4k-*` library or shortcut
- Runnable with a single `./gradlew :{module}:test` or `bootRun` command
- Shows the boilerplate that BT removes (before/after or inline comment)
- Requires no external prerequisite beyond Docker/Testcontainers
- README explains the BT benefit in ≤ 5 sentences

### Advanced Level

An **Advanced** example satisfies ALL of the following:
- Demonstrates two or more BT libraries in a coordinated scenario
- Covers at least one production concern: transactions, concurrency, observability, failure handling, performance, distributed infrastructure, or cross-module composition
- Has a runnable Spring entrypoint, API endpoints, and integration tests
- README includes architecture diagram (ERD or sequence) and `Used Bluetape4k features` table

### Single-Level Exception

A module may remain single-level if:
- The domain has no natural "add complexity" dimension (e.g., `io/okio-examples` — Okio is the full story)
- Document the reason explicitly in the audit table

---

## 3. Module Audit Table (61 active modules)

> **Active**: registered in `settings.gradle.kts` as of 2026-05-22.
> `bt-ref` = bluetape4k references in `build.gradle.kts`.
> `src/test` = Kotlin file counts under `src/main` / `src/test`.

### Domain: Data Access

| Module | bt-ref | src/test | BT Score | Verdict | Level | Notes |
|--------|-------:|----------|----------|---------|-------|-------|
| `exposed/domain` | 7 | 2/163 | HIGH | **REWRITE** | — | Test-only API dump; no app structure. → #97 |
| `exposed/dao-web-transaction` | 5 | 12/3 | MEDIUM | **REWRITE** | — | Partial app shape. → #97 (MVC+JDBC style) |
| `exposed/spring-transaction` | 3 | 0/23 | MEDIUM | **REWRITE** | — | No `src/main`. Test-only. → #97 |
| `exposed/sql-web-virtualthread` | 7 | 22/8 | HIGH | **REWRITE** | — | Good VT+JDBC pattern. → #97 (MVC+VT style) |
| `exposed/sql-webflux-coroutines` | 5 | 21/8 | MEDIUM | **REWRITE** | — | Good WebFlux+R2DBC shape. → #97 (WebFlux+Coroutines style) |
| `spring-data/r2dbc-examples` | 6 | 8/6 | HIGH | KEEP | Basic | R2DBC entity/repo basics; BT R2DBC + Spring Boot |
| `spring-data/r2dbc-coroutines` | 8 | 11/5 | HIGH | KEEP | Advanced | BT R2DBC coroutine patterns + Testcontainers |
| `spring-data/r2dbc-webflux` | 5 | 10/7 | MEDIUM | CONVERT | Advanced | Tests disabled (#120); needs schema init fix |
| `spring-data/r2dbc-webflux-exposed` | 3 | 11/7 | LOW | CONVERT | Advanced | Low BT usage; add BT Exposed R2DBC helpers |
| `spring-data/jpa-querydsl` | 4 | 12/7 | MEDIUM | KEEP | Basic | BT Hibernate + QueryDSL; classic JPA pattern |
| `spring-data/mongodb-coroutines` | 5 | 9/10 | MEDIUM | KEEP | Advanced | BT coroutines + MongoDB async |
| `spring-data/mongodb-transactions` | 4 | 9/4 | MEDIUM | KEEP | Basic | MongoDB multi-doc transaction |
| `spring-data/elasticsearch` | 6 | 6/5 | HIGH | KEEP | Basic | BT Jackson + Spring Boot ES |
| `spring-data/elasticsearch-webflux` | 5 | 18/8 | MEDIUM | KEEP | Advanced | ES WebFlux reactive path |
| `spring-data/redis-examples` | 9 | 15/16 | HIGH | KEEP | Advanced | High BT: redis, idgenerators, spring.boot, testcontainers |
| `vertx/vertx-sqlclient` | 7 | 0/7 | HIGH | KEEP | Advanced | BT Vert.x SQL client; test-only but high BT |

### Domain: Spring Boot Operations

| Module | bt-ref | src/test | BT Score | Verdict | Level | Notes |
|--------|-------:|----------|----------|---------|-------|-------|
| `spring-boot/cache-caffeine` | 5 | 6/3 | MEDIUM | KEEP | Basic | BT cache.core + Caffeine |
| `spring-boot/cache-redis` | 8 | 6/3 | HIGH | KEEP | Advanced | BT Lettuce + Redis cache; spring.boot starter |
| `spring-boot/resilience4j-coroutines` | 4 | 20/12 | MEDIUM | KEEP | Advanced | BT resilience + coroutines; good production pattern |
| `spring-boot/problem` | 4 | 11/3 | MEDIUM | KEEP | Basic | BT resilience Problem detail |
| `spring-boot/webflux-coroutines` | 5 | 8/5 | MEDIUM | KEEP | Basic | BT coroutines WebFlux; entry-level |
| `spring-boot/webflux-websocket` | 6 | 7/1 | HIGH | KEEP | Advanced | BT idgenerators + WebSocket reactive |
| `spring-boot/chaos-monkey` | 4 | 5/2 | MEDIUM | KEEP | Advanced | Chaos + BT coroutines; production resilience |
| `spring-boot/application-event-demo` | 3 | 14/2 | MEDIUM | KEEP | Basic | Spring events with BT coroutines |
| `spring-boot/stomp-websocket` | 4 | 6/2 | MEDIUM | KEEP | Basic | STOMP/WebSocket pattern |
| `spring-boot/async-logging` | 2 | 3/2 | LOW | **ARCHIVE** | — | Only BT coroutines infra; no domain BT value |
| `spring-boot/cbor-mvc` | 3 | 5/2 | LOW | CONVERT | Basic | CBOR serialization niche; needs BT Jackson3 path |
| `spring-boot/protobuf-mvc` | 3 | 5/3 | LOW | CONVERT | Basic | Protobuf/gRPC; add BT grpc helpers |
| `gateway/api-gateway` | 9 | 5/2 | HIGH | KEEP | Advanced | High BT: bucket, cache, resilience, netty |
| `gateway/customers` | 5 | 7/0 | MEDIUM | KEEP | Advanced | Microservice shape; BT coroutines + netty |
| `gateway/orders` | 5 | 9/1 | MEDIUM | KEEP | Advanced | Microservice shape; BT idgenerators |
| `spring-modulith/events-deep-dive` | 3 | 28/8 | MEDIUM | KEEP | Advanced | Spring Modulith + BT Hibernate + idgenerators |
| `spring-modulith/jpa-demo` | 4 | 24/4 | MEDIUM | KEEP | Basic | Modulith basics + BT Hibernate |

### Domain: Serialization and Messaging

| Module | bt-ref | src/test | BT Score | Verdict | Level | Notes |
|--------|-------:|----------|----------|---------|-------|-------|
| `json/jackson-examples` | 5 | 1/14 | MEDIUM | KEEP | Basic | BT Jackson3 features; test-rich |
| `json/jsonview-examples` | 6 | 5/2 | HIGH | KEEP | Advanced | BT Jackson3 JsonView; needs more tests |
| `io/okio-examples` | 5 | 42/39 | MEDIUM | KEEP | Basic | BT Okio; single-level OK (Okio is the story) |
| `messaging/kafka` | 7 | 12/3 | HIGH | KEEP | Basic | BT Kafka + coroutines; Spring Kafka basics |
| `messaging/kafka-reply` | 6 | 5/1 | HIGH | KEEP | Advanced | BT Kafka reply pattern; request-reply flow |

### Domain: Async and Reactive

| Module | bt-ref | src/test | BT Score | Verdict | Level | Notes |
|--------|-------:|----------|----------|---------|-------|-------|
| `kotlin/coroutines` | 4 | 0/34 | MEDIUM | KEEP | Basic | BT coroutines patterns; test-only OK for learning |
| `kotlin/design-patterns` | 3 | 29/9 | LOW | KEEP | Advanced | Design patterns in Kotlin; BT coroutines + IO |
| `kotlin/workshop` | 3 | 0/4 | LOW | **ARCHIVE** | — | 4 test files only; no discernible BT value |
| `reactive/mutiny` | 2 | 0/12 | LOW | **ARCHIVE** | — | Quarkus-adjacent; quarkus/ domain already disabled |
| `vertx/coroutines` | 5 | 2/1 | MEDIUM | KEEP | Basic | BT Vert.x coroutines; minimal but focused |
| `vertx/vertx-webclient` | 5 | 0/4 | MEDIUM | KEEP | Advanced | BT Vert.x WebClient; higher-order Vert.x |

### Domain: Observability and Performance

| Module | bt-ref | src/test | BT Score | Verdict | Level | Notes |
|--------|-------:|----------|----------|---------|-------|-------|
| `observability/micrometer-observation` | 4 | 8/3 | MEDIUM | KEEP | Basic | BT Micrometer observation API |
| `observability/micrometer-tracing-coroutines` | 6 | 11/6 | HIGH | KEEP | Advanced | BT Micrometer tracing + coroutines + Testcontainers |
| `gatling/gradle-plugin-demo` | 0 | 0/0 | LOW | **ARCHIVE** | — | Zero BT refs, zero source; Gradle plugin config only |
| `gatling/virtualthread-simulation` | 6 | 7/3 | HIGH | KEEP | Advanced | BT Gatling + virtual threads; load test pattern |
| `virtualthreads/rules` | 4 | 0/13 | MEDIUM | KEEP | Basic | BT VirtualThread API rules; test-only learning |
| `virtualthreads/spring-mvc-tomcat` | 9 | 18/8 | HIGH | KEEP | Advanced | High BT: cache, core, virtualthread.api/jdk, Hibernate |
| `virtualthreads/spring-webflux` | 9 | 12/7 | HIGH | KEEP | Advanced | High BT: core, virtualthread.api/jdk; compare with MVC |

### Domain: Architecture Extensions

| Module | bt-ref | src/test | BT Score | Verdict | Level | Notes |
|--------|-------:|----------|----------|---------|-------|-------|
| `aws/s3-spring-cloud` | 4 | 1/2 | MEDIUM | KEEP | Basic | BT AWS + Jackson; needs expansion for #107 |
| `redis/cluster-demo` | 7 | 2/3 | HIGH | KEEP | Advanced | BT Redis cluster + idgenerators; Redis topology |
| `redis/redisson-examples` | 8 | 0/40 | HIGH | KEEP | Advanced | High BT: cache, redis, grpc, idgenerators; rich test suite |
| `ratelimit/bucker4j-bluetape4k-webflux` | 7 | 12/3 | HIGH | KEEP | Advanced | BT bucket + Redis + WebFlux; production rate-limit |
| `ratelimit/bucket4j-caffeine-web` | 3 | 2/1 | LOW | CONVERT | Basic | Low BT; needs BT bucket helper path |
| `ratelimit/bucket4j-redis` | 6 | 5/3 | HIGH | KEEP | Advanced | BT Redis + bucket; distributed rate-limit |
| `spring-security/mvc/hello` | 3 | 3/2 | LOW | CONVERT | Basic | Low BT; add BT jwt/security helpers |
| `spring-security/webflux/hello-security` | 4 | 3/2 | MEDIUM | KEEP | Basic | BT coroutines + security |
| `spring-security/webflux/jwt` | 4 | 4/2 | MEDIUM | KEEP | Advanced | BT coroutines + JWT; reactive security |
| `mapping/mapstruct` | 1 | 1/3 | LOW | **ARCHIVE** | — | Only BT io infra; MapStruct is not a BT feature |

---

## 4. Summary Statistics

| Verdict | Count |
|---------|------:|
| KEEP | 40 |
| CONVERT | 6 |
| ARCHIVE | 6 |
| REWRITE (→ #97) | 5 |
| **Total** | **57** |

### Archive Candidates (#78 scope)

| Module | Reason |
|--------|--------|
| `spring-boot/async-logging` | bt-ref=2; only logging infra, no domain BT |
| `kotlin/workshop` | bt-ref=3; 4 test files; no discernible learning outcome |
| `reactive/mutiny` | bt-ref=2; Quarkus-adjacent; `quarkus/` domain already disabled |
| `gatling/gradle-plugin-demo` | bt-ref=0; zero source; Gradle config demo only |
| `mapping/mapstruct` | bt-ref=1; MapStruct is not a Bluetape4k feature |

> Note: `quarkus/hibernate-reactive-panache` and `quarkus/rest-coroutine` are already commented out in `settings.gradle.kts`.

### Convert Candidates (BT value improvement needed)

| Module | Required action |
|--------|----------------|
| `spring-data/r2dbc-webflux` | Fix disabled tests (#120) first, then promote |
| `spring-data/r2dbc-webflux-exposed` | Add BT Exposed R2DBC repository helpers |
| `spring-boot/cbor-mvc` | Add BT Jackson3 CBOR codec path |
| `spring-boot/protobuf-mvc` | Add BT gRPC helper usage |
| `ratelimit/bucket4j-caffeine-web` | Show BT bucket abstraction, not raw Bucket4j |
| `spring-security/mvc/hello` | Add BT security/jwt helpers |

### Rewrite Candidates (→ Issue #97)

All five `exposed/` modules replace with three production-shaped apps:
1. `exposed/mvc-jdbc` (MVC + JDBC + Exposed JDBC)
2. `exposed/mvc-virtualthread` (MVC + Virtual Threads + Exposed JDBC)
3. `exposed/webflux-r2dbc` (WebFlux + Coroutines + Exposed R2DBC)

---

## 5. New Example Backlog Gaps (→ Issue #92)

Bluetape4k libraries with **zero or thin** workshop coverage:

| BT Library | Current Coverage | Priority |
|------------|-----------------|----------|
| `bluetape4k-leader` | None | HIGH (#106) |
| `bluetape4k-javers` | None | HIGH (#100) |
| `bluetape4k-image` | None | MEDIUM (#93, #94) |
| `bluetape4k-text` | None | MEDIUM (#105) |
| `bluetape4k-idgenerators` | Thin (embedded in redis/exposed modules) | MEDIUM (#62 partial) |
| Idempotency pattern | None | HIGH (#98) |
| Transactional Outbox | None | HIGH (#99) |
| Multi-tenant isolation | None | HIGH (#104) |

---

## 6. Acceptance Criteria (DoD for #77)

- [x] All 57+ active modules scored (BT value + verdict + level)
- [x] Basic/Advanced classification criteria defined with examples
- [x] Archive candidate list produced for #78
- [x] Convert candidate list produced for domain epics (#79–#88)
- [x] Rewrite scope confirmed for #97
- [x] BT coverage gap list produced for #92

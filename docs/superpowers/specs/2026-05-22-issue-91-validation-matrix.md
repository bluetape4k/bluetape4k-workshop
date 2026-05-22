# Issue #91 — Workshop Validation Matrix

**Date**: 2026-05-22
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/91
**Parent Epic**: #76
**Status**: Active

---

## 1. Validation Tiers

| Tier | Trigger | Scope | Docker required |
|------|---------|-------|-----------------|
| **T1 Compile** | Every push / PR | `./gradlew build -x test` all 56 modules | No |
| **T2 Smoke** | Every weekday (nightly) | In-memory modules only (no Testcontainers) | No |
| **T3 Full** | Every Sunday (nightly) | All 56 modules including Testcontainers | Yes |
| **T4 Local group** | Developer convenience | Per-domain `scripts/smoke-validate.sh <group>` | Depends |

---

## 2. Module Classification

### T2 Smoke — No Testcontainers (23 modules)

| Module (Gradle project) | Domain group |
|-------------------------|-------------|
| `:jackson-examples` | Serialization |
| `:jsonview-examples` | Serialization |
| `:okio-examples` | Serialization |
| `:kotlin-design-patterns` | Async/Reactive |
| `:spring-boot-application-event-demo` | Spring Boot Operations |
| `:spring-boot-cache-caffeine` | Spring Boot Operations |
| `:spring-boot-cbor-mvc` | Spring Boot Operations |
| `:spring-boot-chaos-monkey` | Spring Boot Operations |
| `:spring-boot-problem` | Spring Boot Operations |
| `:spring-boot-protobuf-mvc` | Spring Boot Operations |
| `:spring-boot-resilience4j-coroutines` | Spring Boot Operations |
| `:spring-boot-stomp-websocket` | Spring Boot Operations |
| `:spring-boot-webflux-coroutines` | Spring Boot Operations |
| `:spring-boot-webflux-websocket` | Spring Boot Operations |
| `:spring-data-r2dbc-examples` | Data Access |
| `:spring-data-r2dbc-webflux-exposed` | Data Access |
| `:spring-modulith-events-deep-dive` | Spring Boot Operations |
| `:spring-security-mvc-hello` | Spring Boot Operations |
| `:spring-security-webflux-hello-security` | Spring Boot Operations |
| `:spring-security-webflux-jwt` | Spring Boot Operations |
| `:vertx-coroutines` | Async/Reactive |
| `:virtualthreads-rules` | Observability/Performance |
| `:micrometer-observation` | Observability/Performance |

> **Known skip**: `:spring-data-r2dbc-webflux` — tests disabled pending #120 schema fix.

### T3 Full — Testcontainers (33 modules)

| Module | Infrastructure needed |
|--------|-----------------------|
| `:exposed-domain` | PostgreSQL |
| `:exposed-dao-web-transaction` | PostgreSQL |
| `:exposed-spring-transaction` | PostgreSQL |
| `:exposed-sql-web-virtualthread` | PostgreSQL |
| `:exposed-sql-webflux-coroutines` | PostgreSQL |
| `:spring-data-r2dbc-coroutines` | PostgreSQL |
| `:spring-data-r2dbc-webflux` | PostgreSQL (#120 disabled) |
| `:spring-data-jpa-querydsl` | PostgreSQL |
| `:spring-data-mongodb-coroutines` | MongoDB |
| `:spring-data-mongodb-transactions` | MongoDB |
| `:spring-data-elasticsearch` | Elasticsearch |
| `:spring-data-elasticsearch-webflux` | Elasticsearch |
| `:spring-data-redis-examples` | Redis |
| `:spring-boot-cache-redis` | Redis |
| `:spring-modulith-jpa-demo` | PostgreSQL |
| `:messaging-kafka` | Kafka |
| `:messaging-kafka-reply` | Kafka |
| `:redis-cluster-demo` | Redis Cluster |
| `:redis-redisson-examples` | Redis |
| `:bucker4j-bluetape4k-webflux` | Redis |
| `:bucket4j-redis` | Redis |
| `:micrometer-tracing-coroutines` | Zipkin / OTLP |
| `:gatling-virtualthread-simulation` | HTTP target |
| `:kotlin-coroutines` | (misc Testcontainers) |
| `:virtualthreads-spring-mvc-tomcat` | PostgreSQL |
| `:virtualthreads-spring-webflux` | PostgreSQL |
| `:vertx-vertx-sqlclient` | PostgreSQL |
| `:vertx-vertx-webclient` | HTTP target |
| `:aws-s3-spring-cloud` | LocalStack S3 |
| `:api-gateway` | Redis + downstream |
| `:customers` | (none currently) |
| `:orders` | (none currently) |
| `:spring-data-r2dbc-webflux-exposed` | (H2 in-memory — smoke OK) |

---

## 3. Per-Domain Smoke Commands

Run via `scripts/smoke-validate.sh <group>` or directly:

### Data Access (in-memory subset)
```bash
./gradlew :spring-data-r2dbc-examples:test :spring-data-r2dbc-webflux-exposed:test --continue
```

### Data Access (Testcontainers)
```bash
./gradlew :exposed-dao-web-transaction:test :exposed-spring-transaction:test \
  :exposed-sql-web-virtualthread:test :exposed-sql-webflux-coroutines:test \
  :spring-data-r2dbc-coroutines:test :spring-data-jpa-querydsl:test \
  :spring-data-mongodb-coroutines:test :spring-data-mongodb-transactions:test \
  :spring-data-elasticsearch:test :spring-data-elasticsearch-webflux:test \
  :spring-data-redis-examples:test --continue --max-workers=1
```

### Spring Boot Operations (smoke)
```bash
./gradlew :spring-boot-application-event-demo:test :spring-boot-cache-caffeine:test \
  :spring-boot-problem:test :spring-boot-resilience4j-coroutines:test \
  :spring-boot-webflux-coroutines:test :spring-boot-webflux-websocket:test \
  :spring-boot-chaos-monkey:test :spring-boot-stomp-websocket:test \
  :spring-modulith-events-deep-dive:test \
  :spring-security-mvc-hello:test :spring-security-webflux-hello-security:test \
  :spring-security-webflux-jwt:test --continue
```

### Serialization and Messaging (smoke)
```bash
./gradlew :jackson-examples:test :jsonview-examples:test :okio-examples:test --continue
```

### Serialization and Messaging (Testcontainers)
```bash
./gradlew :messaging-kafka:test :messaging-kafka-reply:test --continue --max-workers=1
```

### Async and Reactive
```bash
./gradlew :kotlin-coroutines:test :kotlin-design-patterns:test \
  :vertx-coroutines:test :vertx-vertx-sqlclient:test :vertx-vertx-webclient:test --continue
```

### Observability and Performance
```bash
./gradlew :micrometer-observation:test :micrometer-tracing-coroutines:test \
  :virtualthreads-rules:test :virtualthreads-spring-mvc-tomcat:test \
  :virtualthreads-spring-webflux:test --continue --max-workers=1
```

### Redis / Architecture Extensions
```bash
./gradlew :redis-cluster-demo:test :redis-redisson-examples:test \
  :bucker4j-bluetape4k-webflux:test :bucket4j-redis:test --continue --max-workers=1
```

---

## 4. Stale-Include Guard

```bash
# Verify no stale Gradle project registrations
./gradlew projects -q | grep "Project ':" | wc -l
# Expected: 56 (as of 2026-05-22 after #78)

# Verify removed modules are not referenced in any README
for m in async-logging kotlin/workshop reactive/mutiny gatling/gradle-plugin-demo mapping/mapstruct; do
  rg "$m" --include="*.md" . && echo "STALE: $m" || true
done
```

---

## 5. README Link Check

```bash
# Check all relative image links in READMEs resolve to existing files
fd README.md . --exclude .worktrees | xargs -I{} bash -c '
  dir=$(dirname {})
  rg "!\[.*\]\(([^)]+)\)" {} -o -r '"'"'$1'"'"' | while read link; do
    [[ "$link" =~ ^http ]] && continue
    [[ -f "$dir/$link" ]] || echo "BROKEN: {} → $link"
  done
'
```

---

## 6. Acceptance Criteria Status

- [x] Validation matrix defined (T1/T2/T3/T4 tiers)
- [x] Module classification: 23 smoke-safe, 33 Testcontainers
- [x] Per-domain smoke commands documented
- [x] `scripts/smoke-validate.sh` added
- [x] Nightly CI updated with daily T2 smoke-test job
- [x] Stale-include check: 56 modules, no stale refs
- [x] README link check command documented
- [ ] #120 (`r2dbc-webflux` disabled tests) tracked separately

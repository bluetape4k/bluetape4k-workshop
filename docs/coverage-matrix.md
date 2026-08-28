# bluetape4k-workshop — Library Coverage Matrix

각 bluetape4k library를 기존 workshop 예제와 연결하고, Basic/Advanced 제안 시나리오와
함께 coverage gap을 식별합니다.

> 마지막 갱신: 2026-08-25

## 2026-08-25 ecosystem 재사용 2차 gate

`bluetape4k-assertions`는 모든 테스트에 전이되어 있지만, legacy raw assertion과
module-local fallback이 남아 있어 전체 `Good`으로 판정하지 않는다. 실제 caller,
capability API, BOM alias, source/test anchor, fallback 사유는
[`docs/ecosystem-reuse-inventory.md`](ecosystem-reuse-inventory.md)에서 issue별로
추적한다. `A1`은 Field Service, `A2`는 commerce·leader·operations·planning의
assertion migration을 맡고, framework/protocol 학습 대상 raw check는
`behavior-under-test`로 분리한다.

| 확인 항목 | 기준 | 현재 상태 | 다음 gate |
|---|---|---|---|
| assertions matcher | touched assertion block은 `bluetape4k-assertions` 우선 | ⚠️ Partial | #783, #785~#791; A1/A2 exact selectors |
| capability 재사용 | released API와 실제 import/source/test anchor를 inventory에 기록 | ⚠️ Partial | #777, #793~#808; P0 checker |
| raw fallback | 다섯 분류와 비어 있지 않은 `fallback_reason` | ⚠️ Partial | `behavior-under-test`/`documented-raw-fallback` negative test |
| BOM/version | `bluetape4k-dependencies`만 버전 authority | ✅ Guarded | child build-file diff gate |

이 표의 `Good`은 구현 완료를 뜻하지 않으며, child exact-head receipt와 serial
closeout가 `status=verified`로 전환한 뒤에만 회복할 수 있다. `build-logic`의
consumer가 아닌 assertion은 이 Epic의 자동 migration 범위 밖이며 inventory에
별도 사유를 남긴다.

---

## How to Read This Matrix

| Column | Meaning |
|--------|---------|
| **bluetape4k lib** | `bluetape4k-dependencies` BOM에 게시된 module |
| **Existing example** | 해당 library를 보여주는 현재 workshop module |
| **Coverage level** | ✅ Good · ⚠️ Partial · ❌ Missing |
| **Gap** | 아직 보여주지 않은 내용 |
| **Proposed Basic** | 최소 in-memory scenario |
| **Proposed Advanced** | production 형태의 Testcontainers scenario |
| **Issue** | 추적 GitHub issue |

---

## Core Libraries

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-core` | (used transitively everywhere) | ⚠️ Partial | dedicated validation / support-ext demo 없음 | `requireNotBlank`, `requirePositiveNumber`를 사용하는 전용 CRUD service | N/A | #79 |
| `bluetape4k-logging` | All modules | ✅ Good | — | — | — | — |
| `bluetape4k-coroutines` | `kotlin/coroutines`, `observability-*`, `redis-distributed-lock` | ✅ Good | structured concurrency pattern이 불완전함 | — | — | — |
| `bluetape4k-junit5` | All test modules | ✅ Good | `SuspendedJobTester` / `MultithreadingTester` 미노출 | concurrency test harness demo | — | — |
| `bluetape4k-assertions` | All test modules | ⚠️ Partial | legacy raw assertion과 module별 matcher 적용 편차 | assertion migration + raw fallback inventory | exact-head matcher migration with failure-message parity | #792 |
| `bluetape4k-testcontainers` | `exposed-*`, `spring-data-*`, `redis-*`, `messaging-*` | ✅ Good | — | — | — | — |

---

## Data Access

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-exposed` (JDBC) | `exposed-mvc-jdbc` | ✅ Good | batch insert, custom columns | — | — | #79 |
| `bluetape4k-exposed` (R2DBC) | `exposed-webflux-r2dbc` | ✅ Good | coroutine transaction rollback | — | — | — |
| `bluetape4k-spring-boot-r2dbc` | `spring-data-r2dbc-webflux-exposed` | ⚠️ Partial | auto-config 사용이 명시적이지 않음 | BT auto-config를 쓰는 simple R2DBC CRUD | full WebFlux CRUD + integration test | #79 |
| `bluetape4k-spring-boot-redis` | `spring-boot-cache-redis` | ⚠️ Partial | custom codec, key별 TTL | Redis L2 fallback을 가진 Caffeine-first cache | TTL override를 포함한 distributed cache cluster | — |
| `bluetape4k-redis` | `redis-redisson-examples`, `redis-distributed-lock` | ✅ Good | reactive Redisson 미노출 | — | Reactive Redisson RMapReactive example | — |
| `bluetape4k-redisson` | `redis-distributed-lock`, `redis-cluster-demo` | ✅ Good | Redisson Spring Boot auto-config | — | — | — |
| `bluetape4k-lettuce` | `redis/cluster-demo` | ✅ Good | low-level typed codec 소비가 기존 Long 중심 | — | `LettuceIntCodec`와 `LettuceLongCodec`의 coroutine async round trip | — |
| `bluetape4k-idgenerators` | `redis-distributed-lock` (indirect) | ❌ Missing | standalone ID generator demo 없음 | `SnowflakeId` / `TimebasedUuid` generation benchmark | concurrent load에서 distributed unique ID | #62 |

---

## Spring Boot Integration

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-spring-boot4-core` | `spring-boot-webflux-coroutines`, `spring-security-*` | ⚠️ Partial | WebClient BT extension 미노출 | BT helper가 있는 WebFlux controller + suspend handler | BT auto-config를 포함한 full CRUD API | #82 |
| `bluetape4k-resilience4j` | `spring-boot-resilience4j-coroutines` | ✅ Good | bulkhead 미노출 | — | bulkhead + circuit breaker + fallback pipeline | — |
| `bluetape4k-micrometer` | `observability-basic`, `observability-advanced`, `micrometer-*` | ✅ Good | custom meter registry 미노출 | — | Custom MeterRegistry + Prometheus endpoint | — |

---

## Messaging

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-jackson3` | `json/jackson-examples`, `messaging-kafka` | ✅ Good | schema evolution / compatibility 미노출 | Jackson 3 schema migration demo | Kafka + Avro schema registry | #83 |
| Kafka (via Spring Kafka) | `messaging-kafka`, `messaging-kafka-reply` | ✅ Good | dead letter queue 미노출 | — | Kafka DLQ + retry topic pattern | #83 |

---

## Async / Reactive

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-coroutines` | `kotlin/coroutines` | ⚠️ Partial | Flow backpressure, SharedFlow 미노출 | Flow + StateFlow producer/consumer | backpressure가 있는 coroutine channel fan-out | — |
| Virtual threads (JDK 21) | `virtualthreads-*` | ✅ Good | pinning detection tooling 미노출 | — | async profiler pinning report | — |
| Vert.x + coroutines | `vertx-*` | ✅ Good | — | — | — | — |

---

## Observability

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-micrometer` | `observability-basic` | ✅ Good | exemplar linking 미노출 | — | Prometheus exemplar + Grafana Tempo link | — |
| Distributed tracing | `micrometer-tracing-coroutines`, `observability-advanced` | ✅ Good | — | — | — | — |

---

## Architecture / Infrastructure

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-leader` (Redis) | `leader-leader-election` | ✅ Good | virtual thread variant가 덜 두드러짐 | — | health endpoint가 있는 leader election | — |
| Rate limiting | `ratelimit-*` | ✅ Good | adaptive rate limit 미노출 | — | sliding window를 쓰는 Adaptive Bucket4j + Redis | — |
| Spring Cloud Gateway | `gateway-api-gateway` | ⚠️ Partial | circuit breaker filter 미노출 | — | Gateway + Resilience4j circuit breaker filter | — |
| Spring Modulith | `spring-modulith-*` | ⚠️ Partial | module testing isolation 미노출 | — | bounded context별 Modulith ApplicationModuleTest | — |
| AWS S3 | `aws-s3-spring-cloud` | ⚠️ Partial | multipart upload 미노출 | — | LocalStack 기반 S3 multipart upload | — |
| `bluetape4k-aws` | `aws-s3-spring-cloud` | ⚠️ Partial | BT AWS Kotlin SDK wrapper 미노출 | — | AWS Kotlin SDK + coroutine suspend wrapper | — |
| Bedrock Converse | `aws/bedrock-converse` | ✅ Good | 실제 AWS 호출은 기본 경로에서 제외 | credential-free fake로 request mapping과 cold Flow | 명시적 `real-aws` opt-in과 client lifecycle | #741 |
| `bluetape4k-images` / `bluetape4k-images-spring-boot` | `image-processing/advanced-workflow`, `image-processing/ocr-api`, `image-processing/profile-image-moderation` | ✅ Good | — | — | private original, blurred pending image, default fallback을 포함한 profile upload moderation | — |
| `bluetape4k-images-barcode-api` / `bluetape4k-images-barcode-zxing` | `image-processing/barcode-api` | ✅ Good | provider-neutral reader와 bounded upload API를 새로 노출 | — | provider capability matrix와 운영 provider 교체 계약 | — |

---

## Notable Gaps Summary

### Tier 1 — High priority (no example exists)

| Gap | Proposed module | Issue |
|-----|----------------|-------|
| `bluetape4k-idgenerators` standalone demo | `kotlin/idgenerator-workshop` | #62 |
| `bluetape4k-core` validation/support-ext demo | `kotlin/data-access-basic` | #79 |
| `bluetape4k-spring-boot4-core` auto-config를 쓰는 WebFlux CRUD | `spring-boot/spring-boot-basic` | #82 |
| Jackson 3 schema evolution + Kafka Avro | `messaging/messaging-basic` | #83 |

### Tier 2 — Medium priority (partial coverage)

| Gap | Proposed improvement |
|-----|---------------------|
| Redisson reactive data structures | `redis-redisson-examples`에 추가 |
| S3 multipart upload | `aws-s3-spring-cloud` 확장 |
| Modulith `ApplicationModuleTest` | `spring-modulith-jpa-demo`에 추가 |
| Gateway Resilience4j filter | `gateway-api-gateway`에 추가 |
| Flow backpressure / SharedFlow | `kotlin/coroutines`에 추가 |

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

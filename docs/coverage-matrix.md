# bluetape4k-workshop — Library Coverage Matrix

각 bluetape4k library를 기존 workshop 예제와 연결하고, Basic/Advanced 제안 시나리오와
함께 coverage gap을 식별합니다.

> 마지막 갱신: 2026-09-04

## 2026-08-25 ecosystem 재사용 2차 gate

`bluetape4k-assertions`는 모든 테스트에 전이되어 있지만, legacy raw assertion과
module-local fallback이 남아 있어 전체 `Good`으로 판정하지 않는다. #776에서
consumer 테스트의 generic Boolean/null matcher 19개를 의도 기반 matcher로
전환했고, 정적 guard가 1,151개 Kotlin 테스트 파일을 검사한다. `build-logic`의
16개 legacy import는 별도 allowlist로 집계한다. 실제 caller, capability API,
BOM alias, source/test anchor, fallback 사유는
[`docs/ecosystem-reuse-inventory.md`](ecosystem-reuse-inventory.md)에서 issue별로
추적한다. `A1`은 Field Service, `A2`는 commerce·leader·operations·planning의
assertion migration을 맡고, framework/protocol 학습 대상 raw check는
`behavior-under-test`로 분리한다.

| 확인 항목 | 기준 | 현재 상태 | 다음 gate |
|---|---|---|---|
| assertions matcher | touched assertion block은 `bluetape4k-assertions` 우선 | ⚠️ Partial / 회귀 guard ✅ | #776, #783, #785~#791; A1/A2 exact selectors |
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
| `bluetape4k-assertions` | All test modules | ⚠️ Partial | module별 matcher 적용 편차; #776 guard는 consumer generic Boolean/null을 0건으로 유지 | assertion migration + raw fallback inventory | exact-head matcher migration with failure-message parity | #776, #792 |
| `bluetape4k-testcontainers` | `exposed-*`, `spring-data-*`, `redis-*`, `messaging-*` | ✅ Good | — | — | — | — |
| `bluetape4k-graph` | `graph/social-network`, `graph/knowledge-graph` | ✅ Good | 기존 social path 예제가 hop 수만 비교하고 knowledge graph가 schema drift를 계획하지 않았음 | `PathOptions.weightProperty`와 누적 `GraphPath.totalWeight`를 사용하는 deterministic social path | Neo4j/Memgraph backend의 `maxDepth`/`maxVisited`·missing-weight conformance, Entity/Concept/Document `GraphSchemaDriftPlanner` 계획과 unsupported 보고 | #886, #887 |
| `bluetape4k-graph-io` | `graph/io-pipeline` | ✅ Good | 운영용 durable store와 graph+store atomic exactly-once는 범위 밖 | `InMemoryGraphImportCheckpointStore`를 사용한 CSV 실패·재개 | 공유 CAS/lease store와 backend transaction을 포함한 운영 연동 | #863 |

---

## Data Access

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-exposed` (JDBC) | `exposed-mvc-jdbc` | ✅ Good | cursor token의 인코딩·서명·tenant scope는 caller-owned | — | `BookRepository.findCursorPage`의 primary-key keyset, pageSize+1 sentinel, sparse ID mutation boundary | #79, #881 |
| `bluetape4k-exposed-ktor` | `ktor/exposed-rest` | ✅ Good | legacy aggregator가 core/backend 경계를 함께 노출했음 | backend-neutral core health/readiness와 선택형 JDBC adapter | R2DBC/cache adapter를 classpath 조건으로 추가하는 Ktor consumer fixture | #880 |
| `bluetape4k-exposed` (R2DBC) | `exposed-webflux-r2dbc` | ✅ Good | cursor token의 인코딩·서명·tenant scope는 caller-owned | — | `LongR2dbcRepository.findCursorPage`, suspend cancellation/resource release, sparse ID mutation boundary | #881 |
| `bluetape4k-spring-boot-r2dbc` | `spring-data-r2dbc-webflux-exposed` | ✅ Good | QBE matcher의 ignore-case는 upstream compiler 범위 밖 | Spring Data Exposed QBE + FluentQuery projection/page/count/exists를 사용하는 WebFlux CRUD | production query policy와 projection contract를 포함한 full WebFlux CRUD | #79, #882 |
| `bluetape4k-spring-boot-redis` | `spring-boot-cache-redis` | ⚠️ Partial | custom codec, key별 TTL | Redis L2 fallback을 가진 Caffeine-first cache | TTL override를 포함한 distributed cache cluster | — |
| `bluetape4k-redis` | `redis-redisson-examples`, `redis-distributed-lock` | ✅ Good | reactive Redisson 미노출 | — | Reactive Redisson RMapReactive example | — |
| `bluetape4k-redisson` | `redis-distributed-lock`, `redis-cluster-demo` | ✅ Good | Redisson Spring Boot auto-config 및 `RLocalCachedMap` 숫자 원자 갱신/다중 client 무효화가 별도 경계였음 | `CompositeCodec(String, Int/Double, Int/Double)`과 `addAndGetAsync` | 두 독립 client의 bounded 무효화와 취소 전파를 포함한 Redis-backed 검증 | #878 |
| `bluetape4k-lettuce` | `redis/cluster-demo` | ✅ Good | low-level typed codec 소비가 기존 Long 중심 | — | `LettuceIntCodec`와 `LettuceLongCodec`의 coroutine async round trip | — |
| `bluetape4k-idgenerators` | `redis-distributed-lock` (indirect) | ❌ Missing | standalone ID generator demo 없음 | `SnowflakeId` / `TimebasedUuid` generation benchmark | concurrent load에서 distributed unique ID | #62 |

---

## Spring Boot Integration

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-spring-boot4-core` | `spring-boot-webflux-coroutines`, `spring-security-*` | ⚠️ Partial | WebClient BT extension 미노출 | BT helper가 있는 WebFlux controller + suspend handler | BT auto-config를 포함한 full CRUD API | #82 |
| `bluetape4k-resilience4j` | `spring-boot-resilience4j-coroutines` | ✅ Good | bulkhead 미노출 | — | bulkhead + circuit breaker + fallback pipeline | — |
| `bluetape4k-micrometer` | `observability-basic`, `observability-advanced`, `micrometer-*` | ✅ Good | custom meter registry 미노출 | — | Custom MeterRegistry + Prometheus endpoint | — |
| `bluetape4k-tenant` / `bluetape4k-tenant-reactor` | `spring-boot-multi-tenant-data-isolation` | ✅ Good | 기존 예제에 실행 경계별 context 전파·정리와 원문 tenant redaction이 없었음 | ThreadLocal 중첩/예외 정리, ScopedValue virtual thread, Reactor scheduler hop·취소 | 실제 MVC/WebFlux filter와 인증 경계 연동 | #877 |

---

## Messaging

| bluetape4k lib | Existing example | Coverage | Gap | Proposed Basic | Proposed Advanced | Issue |
|----------------|-----------------|----------|-----|----------------|-------------------|-------|
| `bluetape4k-jackson3` | `json/jackson-examples`, `messaging-kafka` | ✅ Good | schema evolution / compatibility 미노출 | Jackson 3 schema migration demo | Kafka + Avro schema registry | #83 |
| Kafka (via Spring Kafka) | `messaging-kafka`, `messaging-kafka-reply` | ✅ Good | DLQ와 callback producer lifecycle을 한 표면에서 함께 다루지 않음 | collection-scoped producer `callbackFlow`와 bounded in-flight/backpressure | Kafka DLQ + retry topic pattern과 callback producer bridge | #83, #879 |
| `bluetape4k-aws` SNS Spring batch | `aws/sqs-sns-coroutines` | ✅ Good | SNS PublishBatch entry 결과를 기존 주문 알림 경계에서 소비하지 않았음 | 최대 10개 local batch와 validation | Floci-backed partial response·transport reconciliation | #873 |
| `bluetape4k-aws` SQS listener observation | `aws/sqs-sns-coroutines` | ✅ Good | receive/process/ack observation parentage와 coroutine context 전파를 기존 one-shot 경계에서 확인하지 않았음 | opt-in local `@SqsListener`, NOOP/disabled guard, cancellation | Floci-backed listener heartbeat·redelivery·ack failure와 trace backend 연동 | #874 |
| `bluetape4k-aws` DynamoDB Streams Flow | `aws/ktor-dynamodb` | ✅ Good | 기존 Ktor DynamoDB 예제에 stream shard Flow와 checkpoint/resume 경계가 없었음 | stream-enabled local table, `TRIM_HORIZON`/`LATEST`, bounded consume | Floci multi-shard/backpressure와 durable checkpoint store 연동 | #875 |
| `bluetape4k-aws` Spring Modulith SNS/SQS externalization | `aws/sqs-sns-coroutines` | ✅ Good | 기존 주문 알림 예제에 versioned event envelope, target routing, publication completion, consumer idempotency/ack 경계가 없었음 | local-first opt-in externalizer, redacted correlation header, direct SQS consume, FIFO key, retry/ack reports | Floci partial publish·unknown version·redrive/DLQ 및 trace correlation 검증 | #876 |

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
| `bluetape4k-leader` audit exporter | `leader/job-safety-lab` | ✅ Good | Redis acquire/release lifecycle과 bounded audit export 경계가 없었음 | `MEMORY` redacted report와 queue/drop/retry snapshot | trusted HTTPS endpoint, allow-list/header 검증, bounded shutdown | #867 |
| `bluetape4k-leader` lease-extension observation | `leader/job-safety-lab` | ✅ Good | watchdog와 사용자 extension terminal outcome을 Micrometer에서 확인할 수 없었음 | global observer 등록/해제, `source`·`outcome` bounded tag, owner-thread proxy | released scoped-registration ABI와 context별 isolation | #868 |
| `bluetape4k-leader-spring-boot` / `bluetape4k-leader-micrometer` | `leader/backend-comparison-lab` | ✅ Good | 정적 descriptor, bounded connectivity probe, Actuator health, low-cardinality metric 계약을 credential-free로 검증 | — | 실제 backend practice module에서 client lifecycle·failover 및 credential 경계 검증 | #866 |
| `bluetape4k-leader-spring-boot` scheduled policy | `leader/tenant-scheduler` | ✅ Good | 기존 reducer에는 YAML `@Scheduled` selector와 policy precedence/lifecycle 실행 예제가 없었음 | exact selector, fail-fast binding, `@LeaderScheduled` precedence, Spring proxy observation, context close | 실제 backend의 distributed scheduler failover와 hot reload | #869 |
| Rate limiting | `ratelimit-*` | ✅ Good | adaptive rate limit 미노출 | — | sliding window를 쓰는 Adaptive Bucket4j + Redis | — |
| Spring Cloud Gateway | `gateway-api-gateway` | ⚠️ Partial | circuit breaker filter 미노출 | — | Gateway + Resilience4j circuit breaker filter | — |
| Spring Modulith | `spring-modulith-*` | ⚠️ Partial | module testing isolation 미노출 | — | bounded context별 Modulith ApplicationModuleTest | — |
| AWS S3 | `aws-s3-spring-cloud`, `aws/storage-abstraction` | ⚠️ Partial | 실제 AWS multipart 운영과 managed key lifecycle 미검증 | exact `S3Resource`, literal 단일 bucket wildcard `ResourcePatternResolver`, Floci 1,001+ object pagination·정렬·empty match·metadata·stream lifecycle, AES/RSA client-side encrypted byte/stream/file transfer, staging 승격/실패 cleanup, configured byte/global file ciphertext bound와 bounded destination rollback | 실제 AWS multipart와 KMS/HSM·key rotation 및 per-call file bound API 검증 | #871, #872 |
| `bluetape4k-aws` | `aws-s3-spring-cloud`, `aws/storage-abstraction` | ⚠️ Partial | AWS Kotlin SDK wrapper와 managed key lifecycle 운영 경계 미노출 | `S3ClientSideEncryptionProviderTemplate`/`S3ClientSideEncryptionTransferTemplate` consumer 조립, AES/RSA provider metadata와 bounded read | AWS Kotlin SDK + coroutine suspend wrapper, KMS/HSM·rotation | #872 |
| Bedrock Converse | `aws/bedrock-converse` | ✅ Good | 실제 AWS 호출은 기본 경로에서 제외 | credential-free fake로 request mapping과 cold Flow | 명시적 `real-aws` opt-in과 client lifecycle | #741 |
| AWS settings boundary | `aws/settings-boundary` | ✅ Good | provider-neutral settings와 Spring Boot ConfigData/runtime reload caller 경계가 없었음 | Secrets Manager/secure Parameter Store fake lookup과 fallback, AppConfig ConfigData prefix/optional contract | full-replacement refresh, atomic Environment reload, timeout/lifecycle cleanup 및 명시적 live AWS factory | #742, #870 |
| `bluetape4k-aws-java` Kinesis consumer | `aws/kinesis-coroutines` | ✅ Good | multi-shard discovery, bounded concurrency, checkpoint/lease fencing 미노출 | credential-free 2-shard `consumerFlow`와 emit 후 checkpoint | real AWS consumer group과 durable checkpoint/lease adapter | #864 |
| `bluetape4k-images` / `bluetape4k-images-spring-boot` | `image-processing/advanced-workflow`, `image-processing/ocr-api`, `image-processing/profile-image-moderation` | ✅ Good | 기존 workflow가 object metadata를 body download 없이 확인하지 않았고 profile public derivative를 strict privacy 검증하지 않았음 | `ImageObjectMetadataReader` capability, Local stat/S3 HEAD metadata, bounded multi-page TIFF structured OCR, `PrivacyDerivativePipeline`의 metadata/GPS 제거·orientation·redaction 및 fail-closed verification | private original, privacy-safe blurred pending/approved image, default fallback을 포함한 image workflow | #883, #884, #885 |
| `bluetape4k-images-barcode-api` / `bluetape4k-images-barcode-zxing` | `image-processing/barcode-api` | ✅ Good | provider-neutral reader와 strict external image decode 경계를 함께 검증 | — | provider capability matrix와 운영 provider 교체 계약 | #865 |

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
| 실제 AWS S3 multipart 운영 검증 | `aws-s3-spring-cloud`와 `aws/storage-abstraction` 확장 |
| Modulith `ApplicationModuleTest` | `spring-modulith-jpa-demo`에 추가 |
| Gateway Resilience4j filter | `gateway-api-gateway`에 추가 |
| Flow backpressure / SharedFlow | `kotlin/coroutines`에 추가 |

---

## Coverage Statistics

| Domain | Total libs tracked | ✅ Good | ⚠️ Partial | ❌ Missing |
|--------|-------------------|---------|-----------|----------|
| Core | 7 | 6 | 1 | 0 |
| Data Access | 7 | 4 | 2 | 1 |
| Spring Boot | 3 | 1 | 2 | 0 |
| Messaging | 2 | 1 | 1 | 0 |
| Async/Reactive | 3 | 2 | 1 | 0 |
| Observability | 2 | 2 | 0 | 0 |
| Architecture/Infra | 8 | 3 | 5 | 0 |
| **Total** | **32** | **19 (59%)** | **12 (38%)** | **1 (3%)** |

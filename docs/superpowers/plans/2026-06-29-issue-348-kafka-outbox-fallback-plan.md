# Kafka-첫 번째 Outbox 대체 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 핫 트랜잭션에 `orders`만 저장하고, 커밋 후 Kafka에 직접 게시하고, 실패한 게시 행을 내구성 있는 대체 행으로 저장하고, 나중에 대체 행을 전달하는 새로운 `messaging/kafka-outbox-fallback` 워크샵 모듈로 Issue #348를 빌드합니다.

**아키텍처:** `messaging/transactional-outbox`을 모델로 한 독립형 Spring Boot 4 + Exposed + Kafka 모듈을 추가하되 `PlaceOrderUseCase`에 공개 주문 배치 경계를 유지합니다. `PlaceOrderUseCase`는 내부 트랜잭션 순서 쓰기, 제한된 직접 Kafka 게시, 실패 시 대체 upsert, relay/reconciler 복구, 안전한 검사 엔드포인트, README 다이어그램 및 CI/smoke 등록을 조율합니다.

**기술 스택:** Kotlin, Spring Boot 4 Web MVC, Spring Kafka `KafkaTemplate`, JetBrains Exposed JDBC/Spring 트랜잭션, PostgreSQL Testcontainers, Kafka Testcontainers, MockK/springmockk, bluetape4k assertions/logging/Jackson/testcontainers, Micrometer.

---

## 소스 진실

- 사양: `docs/superpowers/specs/2026-06-29-issue-348-kafka-outbox-fallback-design.md`
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/348
- 참조 모듈: `messaging/transactional-outbox`

## 파일 구조

모듈 파일을 생성합니다:

- `messaging/kafka-outbox-fallback/build.gradle.kts`: Spring Boot, Exposed, Kafka, Testcontainers, Micrometer 종속성.
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackApplication.kt`: 앱 진입점.
- `.../api/OrderRequest.kt`: Bean 유효성 검사를 통해 DTO를 요청합니다.
- `.../api/OrderResponse.kt`: `publicationStatus`로 주문 응답.
- `.../api/OrderPublicationStatus.kt`: 발신자 측 출판 결과 열거형.
- `.../api/PublicationResponse.kt`: 안전한 게시 상태 DTO; 원시 페이로드가 없습니다.
- `.../api/AdminActionResponse.kt`: 데모 관리자 relay/reconcile 작업에 대한 응답 DTO입니다.
- `.../api/OrderController.kt`: REST 엔드포인트.
- `.../api/RestExceptionHandler.kt`: 검증 실패에 대한 `400` 응답을 정리했습니다.
- `.../config/ExposedConfig.kt`: 시작 시 테이블을 만듭니다.
- `.../config/KafkaConfig.kt`: `KafkaTemplate<String, String>` 로컬 Spring Kafka 패턴을 따르는 생산자 구성입니다.
- `.../config/FallbackOutboxProperties.kt`: 주제, 재시도, 시간 초과, 배치, 스케줄러 토글.
- `.../config/ClockConfig.kt`: 결정론적 조정자 테스트에 주입 가능한 `Clock`입니다.
- `.../domain/OrderStatus.kt`: 열거형.
- `.../domain/OrderTable.kt`: `orders` 테이블.
- `.../domain/OrderRecord.kt`: 내부 주문 투영.
- `.../domain/TransactionalOrderWriter.kt`: 내부 `@Transactional` writer/reader.
- `.../domain/PlaceOrderUseCase.kt`: 공개 오케스트레이션 경계.
- `.../publication/OrderPlacedEvent.kt`: 입력된 이벤트 DTO 및 결정적 이벤트 ID입니다.
- `.../publication/EventPublicationStatus.kt`: `NOT_PUBLISHED`, `PUBLISHED`, `FAILED`, `DEAD_LETTER`.
- `.../publication/EventPublicationTable.kt`: 대체 게시 테이블입니다.
- `.../publication/EventPublicationRecord.kt`: 내부 행 투영.
- `.../publication/EventPublicationRepository.kt`: insert/upsert, 청구, 상태 업데이트, 안전한 쿼리 도우미.
- `.../publication/OrderEventPublisher.kt`: 제한된 직접 Kafka 게시 및 대체 upsert.
- `.../publication/EventPublicationRelay.kt`: 예정된 claim/send/update 릴레이.
- `.../publication/PublicationReconciler.kt`: 결정론적 대체 재구성.
- `.../publication/PublicationQueryService.kt`: REST에 대한 안전한 게시 상태 쿼리입니다.
- `.../observability/OutboxMetrics.kt`: Micrometer counters/timer/gauge 등록 도우미.
- `messaging/kafka-outbox-fallback/src/main/resources/application.yml`: 데모 구성.
- `messaging/kafka-outbox-fallback/src/test/resources/junit-platform.properties`
- `messaging/kafka-outbox-fallback/src/test/resources/logback-test.xml`
- `messaging/kafka-outbox-fallback/src/test/kotlin/io/bluetape4k/workshop/messaging/fallback/AbstractKafkaOutboxFallbackTest.kt`
- `.../KafkaOutboxFallbackFlowTest.kt`: 통합 및 구성요소 테스트.

공유 프로젝트 파일 수정:

- `README.md` 및 `README.ko.md`: 메시징 행 및 대상 테스트 명령을 추가합니다.
- `.github/workflows/Examples.yml`: 경로 필터, 컨테이너 테스트 작업, 아티팩트 경로, 요약 종속성을 추가합니다.
- `scripts/smoke-validate.sh`: 메시징 테스트 작업을 추가합니다.
- `scripts/validate-readme-architecture-diagrams.mjs`
- `scripts/validate-sequence-diagrams.mjs`
- SVG/PNG 존재, README 참조, 다이어그램 레이아웃 증거 게이트를 통해 상태 다이어그램을 확인합니다. 현재 이 저장소에는 별도의 상태 유효성 검사기가 없습니다.

다이어그램 만들기:

- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-architecture-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-architecture-01.png`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.png`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-state-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-state-01.png`

## 작업 1: 모듈 뼈대 및 구성

**파일:**
- 생성: `messaging/kafka-outbox-fallback/build.gradle.kts`
- 생성: `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackApplication.kt`
- 생성: `messaging/kafka-outbox-fallback/src/main/resources/application.yml`
- 만들기: 위에 나열된 테스트 리소스입니다.

- [ ] **1단계: `messaging/transactional-outbox/build.gradle.kts`을 수정하여 `build.gradle.kts` 생성**

트랜잭션 발신함과 동일한 종속성 계열을 사용합니다. 세트:

```kotlin
exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.messaging.fallback"
        databaseUrl = "jdbc:h2:mem:messaging-kafka-outbox-fallback-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackApplicationKt")
}
```

Redis 또는 `bluetape4k-kafka4`을 추가하지 마세요.

- [ ] **2단계: 애플리케이션 진입점 생성**

```kotlin
package io.bluetape4k.workshop.messaging.fallback

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class KafkaOutboxFallbackApplication

fun main(args: Array<String>) {
    runApplication<KafkaOutboxFallbackApplication>(*args)
}
```

- [ ] **3단계: `application.yml` 만들기**

데이터 소스, Kafka 부트스트랩 자리 표시자, Jackson, 안전 오류 기본값, 액추에이터 health/readiness/liveness 및 다음을 포함합니다.

```yaml
server:
  error:
    include-message: never
    include-stacktrace: never
    include-binding-errors: never

management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
  endpoints:
    web:
      exposure:
        include: health,info,prometheus

workshop:
  kafka-outbox-fallback:
    topic: order-events
    direct-publish-attempts: 3
    direct-publish-timeout: 500ms
    direct-publish-total-timeout: 1600ms
    relay-max-retries: 3
    relay-batch-size: 25
    relay-fixed-delay: 2000ms
    relay-claim-ttl: 30s
    reconciler-grace: 30s
    max-payload-bytes: 8192
    direct-publish-enabled: true
    relay-enabled: true
    reconciler-enabled: true
    demo-admin-endpoints-enabled: false
```

- [ ] **4단계: 검증된 속성 계약 구현**

`FallbackOutboxProperties`은 `@ConfigurationProperties("workshop.kafka-outbox-fallback")` 및 `@Validated`를 사용해야 합니다.
범위:

- `topic`은(는) `order-events`과(와) 같아야 합니다.
- 워크숍의 경우 `directPublishAttempts`은(는) 정확히 `3`이어야 합니다.
- `relayMaxRetries`, `relayBatchSize` 및 기간은 양수여야 합니다.
- `maxPayloadBytes`은(는) `1024`과 `65536` 사이에 있어야 합니다.
- `demoAdminEndpointsEnabled`의 기본값은 `false`입니다.

유효하지 않은 주제, 제로 타임아웃, 제로 배치 크기, 초과된 페이로드 제한이 시작 또는 바인딩 검증에 실패하는 구성 검증 테스트를 추가합니다.

- [ ] **5단계: 모듈 검색 확인**

달리다:

```bash
./gradlew projects | rg "messaging-kafka-outbox-fallback"
```

예상: `Project ':messaging-kafka-outbox-fallback'`이 나타납니다.

## 작업 2: 도메인 테이블, DTO, 검증 및 테스트 격리

**파일:**
- 위에 나열된 domain/API 파일을 만듭니다.
- 테스트: `KafkaOutboxFallbackFlowTest.kt`

- [ ] **1단계: 트랜잭션 기록기 및 REST 유효성 검사에 대한 실패한 테스트 작성**

다음 이름의 테스트를 만듭니다.

```kotlin
@Test
fun `transactional writer stores only order row`()

@Test
fun `POST api-orders rejects invalid input with safe 400 and zero persistence`()
```

Assert 작성기 성공으로 `orders` 행 1개와 `event_publications` 행 0개가 생성됩니다. REST 유효성 검사는 공백, 길이 오버플로, 제어 문자, 수량 `0` 및 수량 `1001`(`400 Bad Request` 포함), 삭제된 오류 본문 및 0개의 지속 행을 거부합니다.

- [ ] **2단계: request/response DTO 구현**

사용:

```kotlin
data class OrderRequest(
    @field:NotBlank @field:Size(max = 80) val customerId: String,
    @field:NotBlank @field:Size(max = 120) val product: String,
    @field:Min(1) @field:Max(1000) val quantity: Int,
) : Serializable
```

대체 행 상태와 별도로 공개 `OrderPublicationStatus`를 정의합니다.

```kotlin
enum class OrderPublicationStatus {
    PUBLISHED_DIRECT,
    FALLBACK_STORED,
    FALLBACK_STORE_FAILED,
    UNKNOWN,
}
```

`OrderResponse`을 `id`, `customerId`, `product`, `quantity`, `status`, `publicationStatus`, `createdAt`, `updatedAt`로 정의합니다. 읽기 엔드포인트의 경우 생성 흐름에서 발신자 측 결과가 알려지지 않는 한 `publicationStatus`는 `UNKNOWN`입니다.

- [ ] **3단계: 테이블 및 트랜잭션 기록기 구현**

`OrderTable`은 트랜잭션 발신함 `orders`을 미러링합니다. `TransactionalOrderWriter.saveOrder(...)`는 다음을 검증합니다:

```kotlin
customerId.requireNotBlank("customerId")
product.requireNotBlank("product")
quantity.requirePositiveNumber("quantity")
require(customerId.length <= 80) { "customerId must be 80 characters or less" }
require(product.length <= 120) { "product must be 120 characters or less" }
require(quantity <= 1000) { "quantity must be 1000 or less" }
require(customerId.none(Char::isISOControl)) { "customerId must not contain control characters" }
require(product.none(Char::isISOControl)) { "product must not contain control characters" }
```

- [ ] **4단계: 정리된 예외 매핑 추가 및 수명 주기 격리 테스트**

원시 customer/product 값을 에코하지 않고 `MethodArgumentNotValidException` 및 `IllegalArgumentException`를 `400 Bad Request`에 매핑하는 `RestExceptionHandler`을 만듭니다. `AbstractKafkaOutboxFallbackTest`에서 동적 속성을 설정합니다.

```text
workshop.kafka-outbox-fallback.relay-enabled=false
workshop.kafka-outbox-fallback.reconciler-enabled=false
workshop.kafka-outbox-fallback.demo-admin-endpoints-enabled=false
```

`TransactionTemplate`를 사용하여 테스트 사이에 `event_publications` 및 `orders`을 정리합니다. Testcontainers 런처 싱글톤과 `@DynamicPropertySource`을 유지하세요.
예약된 진입점은 이러한 플래그를 직접 적용해야 합니다. 수동 서비스 방법
`relayOnce()` 및 `reconcileOnce()` 등은 테스트에서 계속 호출 가능하지만
`scheduledRelay()` 및 `scheduledReconcile()`은 부작용 없이 반환되어야 합니다.
그들의 깃발이 거짓일 때.

- [ ] **5단계: 타겟 테스트 실행**

달리다:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --tests '*transactional writer stores only order row*' --tests '*safe 400*' --max-workers=1
```

예상: PASS.

## 작업 3: 직접 Kafka 게시 및 대체 지속성

**파일:**
- `FallbackOutboxProperties.kt` 생성
- `OrderPlacedEvent.kt` 생성
- `EventPublicationTable.kt`, `EventPublicationStatus.kt`, `EventPublicationRepository.kt` 생성
- `OrderEventPublisher.kt` 생성
- `OutboxMetrics.kt` 생성
- `PlaceOrderUseCase.kt` 생성
- 테스트: `KafkaOutboxFallbackFlowTest.kt`

- [ ] **1단계: 직접 retry/fallback에 대한 실패한 테스트 작성**

테스트를 추가합니다:

```kotlin
@Test
fun `placeOrder stores only order row and returns PUBLISHED_DIRECT when direct Kafka publish succeeds`()

@Test
fun `direct publish retries three times then stores NOT_PUBLISHED fallback row`()

@Test
fun `direct publish timeout stores NOT_PUBLISHED fallback row`()

@Test
fun `fallback insert failure returns FALLBACK_STORE_FAILED and records safe metric and log`()
```

`@MockkBean(relaxed = true) KafkaTemplate<String, String>`를 사용하세요. 시간 초과의 경우 불완전한 `CompletableFuture<SendResult<String, String>>()`을 반환하고, 작은 시도당 시간 초과와 총 직접 게시 시간 초과를 설정하고, 경과 시간이 총 예산 미만으로 유지되고 시간 초과된 미래가 취소되는지 확인합니다. 시간 초과된 전송에 알 수 없는 Kafka 결과가 있고 대체 릴레이가 나중에 결정적 `eventId`을 게시할 때 중복될 수 있다는 문서입니다.

- [ ] **2단계: 결정적 이벤트 구현 DTO**

`OrderPlacedEvent`은(는) `serialVersionUID`가 있는 `data class : Serializable`이어야 합니다. 사용:

```kotlin
val eventId: String get() = "order-placed:$orderId:v1"
```

이 닫힌 DTO만 직렬화하세요. Jackson 기본 유형 지정 또는 클래스 이름 다형성을 활성화하지 마십시오. 어설션 페이로드 JSON에는 `@class`이 포함되어 있지 않으며 패키지 이름, 스택 추적 또는 원시 예외 텍스트가 있으며 직렬화된 바이트는 `<= maxPayloadBytes`입니다.

- [ ] **3단계: 대체 저장소 구현**

저장소 작업:

- `upsertNotPublished(event, directAttemptCount, errorCode, errorSummary)`
- `countByAggregateId(orderId)`
- `findSafeResponses(orderId?)`

고유한 `event_id`를 사용하세요. PostgreSQL/H2 이식성을 위해 트랜잭션 내부에 select-then-insert/update을 구현하고 이 워크숍을 위해 멱등성을 유지하세요.

- [ ] **4단계: 직접 게시자 구현**

사용:

```kotlin
kafkaTemplate.send(topic, event.eventId, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
```

정확히 `directPublishAttempts` 다시 시도하세요. code/summary에서 오류를 삭제합니다. 원시 스택 추적을 유지하지 마십시오.
`directPublishTotalTimeout` 시행: 시도가 남아 있더라도 총 예산이 소진되면 재시도를 중지하고, `future.cancel(true)`을 사용하여 시간 초과된 미래를 취소하고 대체를 `NOT_PUBLISHED`로 저장합니다.

- [ ] **5단계: `PlaceOrderUseCase` 구현**

비트랜잭션 조정자 흐름:

1. `val order = transactionalOrderWriter.saveOrder(...)`
2. 빌드 유형 `OrderPlacedEvent`
3. `orderEventPublisher.publishDirectOrFallback(event)`에 전화하세요.
4. `OrderResponse(... publicationStatus = result.status)` 반환

결과 상태 매핑은 다음과 같습니다.

| Direct/fallback 결과 | `OrderResponse.publicationStatus` |
|---|---|
| Kafka 전송 확인 | `PUBLISHED_DIRECT` |
| Kafka failed/timed 출력 및 대체 행이 업데이트됨 | `FALLBACK_STORED` |
| Kafka failed/timed out 및 fallback upsert 실패 | `FALLBACK_STORE_FAILED` |

- [ ] **6단계: 타겟 테스트 실행**

달리다:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --tests '*placeOrder stores only order row*' --tests '*direct publish*' --tests '*fallback insert failure*' --max-workers=1
```

예상: PASS.

## 작업 4: 릴레이, 조정자, 안전한 쿼리 API 및 관찰 가능성

**파일:**
- `EventPublicationRelay.kt` 생성
- `PublicationReconciler.kt` 생성
- `PublicationQueryService.kt` 생성
- Create/modify `OrderController.kt`, `PublicationResponse.kt`
- 테스트: `KafkaOutboxFallbackFlowTest.kt`

- [ ] **1단계: 실패한 테스트 작성**

테스트를 추가합니다:

```kotlin
@Test
fun `relay publishes fallback row and marks it PUBLISHED`()

@Test
fun `relay failure increments relay retry and moves to DEAD_LETTER`()

@Test
fun `concurrent relay calls cannot claim the same row twice`()

@Test
fun `stale relay claim becomes eligible after claim ttl`()

@Test
fun `reconciler reconstructs deterministic fallback row and documents duplicate risk`()

@Test
fun `reconciler repair covers fallback store failure after grace duration`()

@Test
fun `demo admin relay and reconcile endpoints are disabled by default`()

@Test
fun `scheduled relay and reconciler do nothing when disabled`()

@Test
fun `publication endpoint never exposes raw payload or raw exception text`()

@Test
fun `health readiness liveness and safe error defaults expose no sensitive details`()

@Test
fun `metrics and structured logs record direct failure fallback relay and reconciler outcomes`()
```

- [ ] **2단계: 원자적 클레임 구현**

PostgreSQL/H2 호환성을 위해 낙관적인 클레임-토큰 계약을 사용하세요.

1. 실행별 `claimToken`을 생성합니다.
2. 한 번의 트랜잭션에서 `next_attempt_at`부터 `relayBatchSize`까지 정렬된 후보 ID를 선택합니다.
3. 각 후보에 대해 `id`, 적격 상태, 재시도 횟수 및 stale/null `claimed_until`에 대한 조건자로 업데이트합니다.
4. 성공적인 업데이트를 계산합니다.
5. `claimed_by == claimToken`인 행만 다시 로드하고 반환합니다.
6. 업데이트 횟수가 `0`인 경우 해당 행을 보내지 마세요.

적임:

```text
status in (NOT_PUBLISHED, FAILED)
relay_retry_count < relayMaxRetries
next_attempt_at <= now
claimed_until is null or claimed_until < now
```

테스트에서는 동일한 행에 대해 두 개의 동시 릴레이 호출을 실행하고 정확히 한 번의 Kafka 전송을 확인해야 합니다. 과거에 `claimed_until`을 설정하고 행이 다시 적합함을 증명하는 오래된 클레임 테스트를 추가합니다.

- [ ] **3단계: 릴레이 구현**

청구된 각 행에 대해 제한된 시간 제한을 사용하여 Kafka로 보냅니다. 성공 표시 `PUBLISHED`. 실패 증가 시 `relay_retry_count`, `FAILED` 또는 `DEAD_LETTER`을 설정하고, 클레임을 지우고, 삭제된 오류 필드를 설정하고, `next_attempt_at`를 설정합니다.
예약된 진입점과 수동 진입점을 분할합니다.

- `scheduledRelay()`은(는) `@Scheduled`로 주석을 달고, `relayEnabled`를 확인하고, 활성화된 경우에만 `relayOnce()`에 위임합니다.
- `relayOnce()`에는 claim/send/update 로직이 포함되어 있으며 테스트 및 데모 관리 엔드포인트에서 호출할 수 있습니다.
- 비활성화된 스케줄러 테스트는 적합한 행을 삽입하고 `scheduledRelay()`을 호출하며 Kafka 전송이 없고 행 상태가 변경되지 않았는지 확인합니다.

- [ ] **4단계: 조정자 구현**

`Clock`을 주입하고 `reconcilerGrace`을 사용하세요. 일치하는 `event_id`가 없는 유예 기간보다 오래된 주문을 찾고, `NOT_PUBLISHED`을 upsert하고, `workshop.outbox.reconciler.repairs`를 늘리고, `order.event.reconciler.repaired`을 내보냅니다. "너무 새로운" 사례와 "은혜보다 오래된" 사례에 대한 정지 시간을 테스트합니다.
예약된 진입점과 수동 진입점을 분할합니다.

- `scheduledReconcile()`은(는) `@Scheduled`로 주석을 달고, `reconcilerEnabled`를 확인하고, 활성화된 경우에만 `reconcileOnce()`에 위임합니다.
- `reconcileOnce()`에는 결정론적 재구성이 포함되어 있으며 테스트 및 데모 관리 엔드포인트에서 호출할 수 있습니다.
- 비활성화된 스케줄러 테스트는 적격한 이전 주문을 생성하고 `scheduledReconcile()`를 호출하며 대체 행이 생성되지 않았는지 확인합니다.

- [ ] **5단계: 안전한 REST 엔드포인트 구현**

경로:

- `POST /api/orders`
- `GET /api/orders/{id}`
- `GET /api/orders`
- `GET /api/publications`
- `POST /api/publications/relay`
- `POST /api/publications/reconcile`

`PublicationResponse`은 `payload` 및 원시 오류를 제외합니다. 여기에는 삭제된 `lastErrorCode` 및 `lastErrorSummary`이 포함됩니다.
`POST /api/publications/relay` 및 `POST /api/publications/reconcile`은 데모 관리 엔드포인트입니다. `404` 또는 `403`을 반환하고 `demoAdminEndpointsEnabled=true`가 아니면 아무 작업도 수행하지 않습니다. 테스트에서는 기본적으로 비활성화되어 있으며 엔드포인트별 테스트에서만 활성화됩니다. `AdminActionResponse`에는 `requested`, `processed`, `published`, `failed` 및 `repaired` 개수가 포함됩니다. README는 이러한 엔드포인트가 local/demo-only임을 명시해야 하며 프로덕션 사용 전에 실제 인증, 속도 제한 및 감사 로깅이 필요합니다.

- [ ] **6단계: 타겟 테스트 실행**

달리다:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --tests '*relay*' --tests '*claim*' --tests '*reconciler*' --tests '*publication endpoint*' --tests '*health*' --tests '*metrics*' --tests '*structured logs*' --max-workers=1
```

예상: PASS.

## 작업 5: README 쌍 및 다이어그램

**파일:**
- 생성: `messaging/kafka-outbox-fallback/README.md`
- 생성: `messaging/kafka-outbox-fallback/README.ko.md`
- `docs/images/readme-diagrams/` 아래에 다이어그램 SVG/PNG 자산을 생성합니다.
- 다이어그램 유효성 검사기 스크립트를 수정합니다.

- [ ] **1단계: 영어로 README.md 초안**

필수 섹션:

- 언어 스위치.
- 건축 이미지.
- 시퀀스 이미지.
- 상태 수명 주기 이미지.
- 클래식 트랜잭션 발신함과 Kafka-첫 번째 대체 비교표.
- REST 예.
- 실패 의미론.
- 보장되지 않습니다.
- 운영자 실행서.
- Tests/running.
- `OrderPublicationStatus`에 대한 공개 API 상태 매핑 테이블입니다.
- 데모 관리자 엔드포인트 응답 예시 및 기본적으로 비활성화되는 동작.
- 공개 DTOs/controllers/services에 대한 KDoc 적용 참고 사항입니다.

- [ ] **2단계: README.ko.md를 자연스러운 한국어로 초안**

소스와 동등한 콘텐츠 및 언어 전환 `[English](README.md) | 한국어`을 유지합니다.

- [ ] **3단계: bluetape4k-diagram 규칙을 사용하여 다이어그램 생성**

공유 Kafka/database 아이콘을 사용하세요. 하드 비주얼 게이트:

- 카드 라벨 겹침 = 0
- 라벨-카드 겹침 = 0
- 엔드포인트 감사 = PASS
- 대각선 카드-카드 커넥터 = 0 불가피하고 문서화되지 않는 한
- 보고된 행 중심 및 간격
- PNG 렌더링 및 육안 검사

- [ ] **4단계: 유효성 검사기 업데이트**

기존 허용 목록에 새로운 아키텍처 및 시퀀스 기본 이름을 추가합니다. 로컬 상태 유효성 검사기가 있는 경우에만 상태 유효성 검사를 추가합니다. 그렇지 않으면 SVG/PNG 존재와 참조를 검증하십시오.

- [ ] **5단계: docs/diagram 검사 실행**

달리다:

```bash
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
rg -n "direct-publish-enabled|relay-enabled|reconciler-enabled|NOT_PUBLISHED|DEAD_LETTER|re-drive|rollback|migration|SELECT|UPDATE" messaging/kafka-outbox-fallback/README.md messaging/kafka-outbox-fallback/README.ko.md
git diff --check
```

예상: PASS.

## 작업 6: 루트 README, CI, Smoke 및 확인

**파일:**
- 수정: `README.md`
- 수정: `README.ko.md`
- 수정: `.github/workflows/Examples.yml`
- 수정: `scripts/smoke-validate.sh`

- [ ] **1단계: 루트 README 행 추가**

두 루트 README 파일의 메시징 아래에 `messaging/kafka-outbox-fallback`를 추가합니다. 대상 명령 언급:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1
```

- [ ] **2단계: 예시 워크플로 업데이트**

경로 필터 추가:

```yaml
      - 'messaging/kafka-outbox-fallback/**'
```

순차적 컨테이너 레인 작업을 추가합니다.

```bash
./gradlew :messaging-kafka:test :messaging-kafka-outbox-fallback:test --continue --max-workers=1
```

아티팩트 경로 추가:

```yaml
messaging/kafka-outbox-fallback/build/test-results/test/
messaging/kafka-outbox-fallback/build/reports/tests/test/
```

- [ ] **3단계: 연기 스크립트 업데이트**

메시징 그룹에 `:messaging-kafka-outbox-fallback:test`을 추가합니다.

- [ ] **4단계: 전체 대상 확인 실행**

달리다:

```bash
./gradlew projects
./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1
./gradlew :messaging-kafka-outbox-fallback:test --tests '*health*' --max-workers=1
bash -n scripts/smoke-validate.sh
./scripts/smoke-validate.sh messaging
actionlint .github/workflows/Examples.yml
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
rg -n "OrderPublicationStatus|PUBLISHED_DIRECT|FALLBACK_STORED|FALLBACK_STORE_FAILED|demo-admin|health|readiness|liveness" messaging/kafka-outbox-fallback/README.md messaging/kafka-outbox-fallback/README.ko.md
git diff --check
```

예상: 모두 통과. `actionlint`이 설치되지 않은 경우 누락된 도구를 보고하고 차선 증거로 `gh workflow view Examples` 또는 YAML 구문 분석을 통해 워크플로 구문을 검증합니다.

## 작업 7: 강의, 커밋, PR 및 라이브 메타데이터

**파일:**
- 생성: `docs/lessons/2026-06-29-issue-348-kafka-outbox-fallback.md`

- [ ] **1단계: 간결한 강의 작성**

상황, 결정, 결과, 확인 증거 및 향후 에이전트 경고를 다룹니다.

- 이 패턴은 핫 트랜잭션 DB 작업을 낮추지만 원자성을 약화시킵니다.
- 조정자는 중복된 위험으로 인한 손실 회피입니다.
- 안전한 게시 엔드포인트은 원시 payload/error을 노출해서는 안 됩니다.
- 다이어그램 레이아웃 증거는 실제로 렌더링된 PNG 증거여야 합니다.
- 기사 후속 패킷: 최종 코드 경로, 검증된 다이어그램 경로, 메트릭
  이름, 지원되지 않는 기능, 클래식 대 대체 비교 앵커 및
  duplicate/idempotency 경고.

- [ ] **2단계: Lore 프로토콜로 커밋**

커밋 메시지 의도 라인:

```text
feat: teach Kafka-first outbox fallback trade-offs
```

예고편 포함:

```text
Constraint: Issue #348 requires order-only hot transaction and Kafka-first publication with durable fallback.
Rejected: Redis Stream fallback in v1 | It would obscure the core outbox trade-off and add a second durability system.
Confidence: high
Scope-risk: moderate
Directive: Keep classic transactional-outbox intact; this module is a complementary at-least-once fallback example.
Tested: <commands that passed>
Not-tested: <only if any required check could not run>
```

- [ ] **3단계: 분기를 푸시하고 PR 열기**

PR 생성 전에 이슈 메타데이터를 새로 고칩니다.

```bash
gh issue view 348 --json assignees,milestone,labels,state,url
```

지원되는 경우 `gh pr create` / `gh pr edit`에 대해 반환된 담당자, 마일스톤 및 레이블을 사용하세요. PR 본문의 마지막 섹션은 `## DoD Status`이어야 합니다.

- [ ] **4단계: 라이브 PR 메타데이터 확인**

달리다:

```bash
gh issue view 348 --json assignees,milestone,labels,state
gh pr view <number> --json assignees,milestone,labels,body,url
```

예상됨: issue 및 PR assignee/milestone/labels이 정확합니다. PR 본문에는 `## DoD Status`이(가) 포함됩니다.

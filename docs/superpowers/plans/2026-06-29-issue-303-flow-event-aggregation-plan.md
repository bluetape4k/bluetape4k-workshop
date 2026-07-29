# Flow 이벤트 집계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 새로운 인메모리 Flow 이벤트 집계 워크숍 예시로 이슈 #303를 구축합니다.

**아키텍처:** 제한된 일괄 처리, 롤링 창, 그룹화, 상태 누적, 변경되지 않은 상태 억제, 전환 감지 및 디버그 감사 로깅을 보여주는 집중된 주문 이벤트 도메인과 `OrderEventAggregationPipeline` 기능이 있는 `kotlin/flow-extensions-event-aggregation`을 추가합니다. 내구성 있는 이벤트 인프라를 추가하기 전에 학습자가 Flow 의미를 이해할 수 있도록 예시 프로세스를 로컬 및 결정론적으로 유지하세요.

**기술 스택:** Kotlin/JVM, Java 21, `bluetape4k-coroutines`, `bluetape4k-junit5`, `bluetape4k-assertions`, `kotlinx-coroutines-test`, CairoSVG-렌더링된 README 다이어그램.

---

## 파일 구조

- `kotlin/flow-extensions-event-aggregation/build.gradle.kts` 생성: 형제 Flow 모듈과 일치하는 종속성.
- `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventDomain.kt` 만들기: 직렬화 가능한 이벤트, 읽기 모델, 요약, 상태 실행, 전환 및 감사 항목.
- `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipeline.kt`: Flow 확장 파이프라인을 생성합니다.
- `kotlin/flow-extensions-event-aggregation/src/test/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipelineTest.kt` 생성: 승인 테스트.
- 테스트 리소스 `junit-platform.properties` 및 `logback-test.xml`를 생성합니다.
- `kotlin/flow-extensions-event-aggregation/README.md` 및 `README.ko.md`를 생성합니다.
- 루트 `README.md` 및 `README.ko.md` 비동기 및 반응형 테이블을 수정합니다.
- `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg/png` 아래에 README 다이어그램 자산을 만듭니다.
- 유효성 검사기 허용 목록에 새 다이어그램 슬러그가 필요한 경우에만 `scripts/validate-readme-architecture-diagrams.mjs` 및 `scripts/validate-sequence-diagrams.mjs`을 수정하세요.
- `.github/workflows/Examples.yml` 및 `scripts/smoke-validate.sh`을 수정하여 이 인메모리 Flow 모듈이 연기 예제 적용 범위에 합류하도록 합니다.
- `docs/review/2026-06-29-issue-303-flow-event-aggregation-review.md`를 생성합니다.
- `docs/lessons/2026-06-29-issue-303-flow-event-aggregation.md`를 생성합니다.

## 작업 1: 모듈 뼈대 및 도메인

**복잡성:** 중간
**적용:** `$bluetape4k-code-patterns`

**파일:**
- 생성: `kotlin/flow-extensions-event-aggregation/build.gradle.kts`
- 생성: `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventDomain.kt`
- 생성: `kotlin/flow-extensions-event-aggregation/src/test/resources/junit-platform.properties`
- 생성: `kotlin/flow-extensions-event-aggregation/src/test/resources/logback-test.xml`

- [ ] 형제 Flow 모듈에서 복사된 Gradle 종속성을 추가합니다.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.logback.lib)
}
```

- [ ] 직렬화 가능한 도메인 모델을 추가합니다.
  - `sealed interface OrderEvent : Serializable { val orderId: String; val occurredAt: Instant; val eventType: String }`
  - 이벤트 클래스: `OrderCreated`, `LineAdded`, `PaymentAuthorized`, `ShipmentStarted`, `OrderCancelled`.
  - `enum class OrderStatus { NEW, CREATED, PAID, SHIPPED, CANCELLED }`.
  - 데이터 클래스: `OrderState`, `OrderReadModel`, `OrderActivitySummary`, `OrderStatusRun`, `OrderTransition`, `OrderAuditEntry`.
- [ ] bluetape4k 유효성 검사 도우미와 함께 생성자 `init` 블록을 사용하세요.
  - `orderId.requireNotBlank("orderId")`
  - `customerId.requireNotBlank("customerId")`
  - `sku.requireNotBlank("sku")`
  - `quantity.requirePositiveNumber("quantity")`
  - `amountCents.requirePositiveNumber("amountCents")`
  - `carrier.requireNotBlank("carrier")`
  - `trackingNumber.requireNotBlank("trackingNumber")`
  - `reason.requireNotBlank("reason")`
- [ ] 고객 ID, 추적 번호 및 취소 이유를 숨기는 이벤트 클래스에 대해 안전한 `toString()` 재정의를 추가합니다.
- [ ] 전용 생성자와 동반 팩토리를 사용하여 이벤트 클래스를 일반 검증 클래스로 구현합니다. 이벤트 입력에 대해 공개 데이터 클래스 `copy(...)`를 노출하지 마십시오. 비공개 데이터 클래스가 내부적으로 사용되는 경우 저장소의 `@ConsistentCopyVisibility` 패턴을 적용하고 공개 `copy(...)` 경로가 잘리지 않은 제어 문자 또는 너무 긴 값을 생성할 수 없음을 증명하는 검토 체크리스트 항목을 추가합니다.
- [ ] 모든 문자열에 대해 제어 문자와 너무 긴 값을 거부합니다. `orderId`, `sku` 및 `carrier`와 같은 로그 표시 식별자에 대해 인쇄 가능한 ASCII 토큰 패턴을 사용합니다.
- [ ] `OrderAuditEntry`을 삭제된 필드(`sequence`, `eventType`, `orderId`, `status`, 개수, 금액, 버전 및 타임스탬프)로만 제한합니다. 원시 `customerId`, `trackingNumber` 또는 취소 `reason`를 저장하지 마십시오.
- [ ] 각각의 구체적인 직렬화 가능 클래스 컴패니언에 `private const val serialVersionUID: Long = 1L`를 추가합니다.
- [ ] 공개 도메인 유형에 대해 영어 KDoc을 추가하고 `Serializable`은 persistence/untrusted-deserialization 기능이 아니라 저장소 규칙이라는 점에 유의하세요.
- [ ] README이 없음을 확인합니다. KDoc이나 예제에서는 Java 개체 역직렬화 또는 지속성 의미를 소개합니다.
- [ ] 형제 Flow 모듈 패턴을 복사하여 표준 테스트 리소스를 추가합니다.
- [ ] 모듈이 존재하면 모듈 등록 확인을 실행합니다.

```bash
./gradlew projects --console=plain
```

예상 증거: `:kotlin-flow-extensions-event-aggregation`이 나타납니다.

## 작업 2: RED Flow 의미론 테스트

**복잡성:** 높음
**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

**파일:**
- 생성: `kotlin/flow-extensions-event-aggregation/src/test/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipelineTest.kt`

- [ ] 구현 전에 테스트를 추가합니다. 테스트 이름:
  - ``chunked activity emits bounded event summaries``
  - ``rolling activity emits overlapping summary windows``
  - ``grouped events partition completed stream by order id``
  - ``read models accumulate state per order id``
  - ``unchanged status runs collapse repeated created updates``
  - ``transitions emit lifecycle changes only``
  - ``audit stream preserves readable event order``
  - ``domain values reject blank ids and non positive amounts``
  - ``domain values reject control characters and overlong identifiers``
  - ``sensitive fields are trimmed bounded and reject control characters``
  - ``event construction has no public copy bypass for validation``
  - ``debug rendering hides customer tracking and cancellation details``
  - ``invalid pipeline parameters fail before collection``
  - ``cancelled status stays terminal while audit version advances``
  - ``duplicate and out of order lifecycle events converge deterministically``
  - ``finite high cardinality grouping emits every order group once``
  - ``bounded read model growth remains predictable for many active orders``
  - ``long unchanged status run collapses but retains run until boundary``
  - ``rolling activity emits full and partial tail windows``
  - ``collector cancellation stops upstream collection``
  - ``upstream failure propagates through each aggregation path``
  - ``cancellation exception is not wrapped by aggregation paths``
- [ ] `io.bluetape4k.junit5.coroutines.runSuspendTest`를 사용하세요.
- [ ] `io.bluetape4k.assertions.assertFailsWith`, `shouldBeEqualTo`, `shouldContain`, `shouldHaveSize`, `shouldBeEmpty` 및 도트 호출 부울 매처를 사용하세요.
- [ ] JUnit, AssertJ, Kluent 또는 `kotlin.test` 어설션을 사용하지 마세요.
- [ ] 일반 동작, 그룹화, 윈도우화 및 프로젝션 테스트에는 유한 `flowOf(...)` 입력을 사용합니다. 절전 모드, 타이머 또는 벽시계 스케줄러 어설션을 사용하지 마세요.
- [ ] 취소 테스트의 경우에만 `cancelAndJoin`와 함께 제어된 `flow { emit(...); awaitCancellation() }` 또는 게이트된 `MutableSharedFlow` 소스를 사용하고 `onCompletion` 또는 `finally` 플래그를 지정하여 업스트림 취소가 절전 모드 없이 결정적이 되도록 합니다.
- [ ] `associateBy { it.key }` 또는 정렬된 키로 `groupedByOrder` 결과 순서를 확인합니다. 각 그룹의 `values` 내에서만 엄격한 원래 순서를 검증하십시오.
- [ ] `withTimeout` 내부의 `flatMapMerge` 기본 동시성보다 더 고유한 주문 ID를 사용하여 높은 카디널리티 `groupedByOrder`을 확인하고 이를 유한 데모로 문서화하세요. 시간 초과 위험이 노출되면 동시성을 명시적으로 바인딩하고 일치하도록 README/KDoc을 업데이트하세요.
- [ ] 기능별 오류 전파를 확인합니다.
  - `chunkedActivity`, `rollingActivity`, `readModels`, `statusRuns`, `transitions` 및 `audit`는 원래 업스트림 예외를 전파합니다.
  - `groupedByOrder`은 원래 예외를 `cause`로 사용하여 `FlowOperationException`의 취소되지 않는 업스트림 실패를 래핑합니다.
  - 모든 경로는 래핑 없이 `CancellationException` 전파됩니다.
- [ ] 공개 `readModels(events)`이 `scanWith`에서 초기 빈 `OrderReadModel`을 내보내도록 결정하고 문서화합니다. 테스트 및 README는 첫 번째 방출의 이름을 지정해야 합니다.
- [ ] 빈 스트림 동작, 첫 번째 이벤트 전환 동작, 알 수 없는 주문 ID 예상 동작, 중복 결제 동작, 주문이 잘못된 배송 동작 및 터미널 `CANCELLED` 버전 향상을 테스트합니다.
- [ ] 민감한 필드 유효성 검사 테스트는 `orderId`, `sku` 또는 `carrier`뿐만 아니라 `customerId`, `trackingNumber` 및 취소 `reason`에 대한 공백, 자르기, 너무 긴 제어 문자 동작을 다루어야 합니다.
- [ ] 프로덕션 구현 전에 RED를 확인합니다.

```bash
./gradlew :kotlin-flow-extensions-event-aggregation:test --tests "io.bluetape4k.workshop.flow.event.aggregation.OrderEventAggregationPipelineTest" --console=plain
```

예상되는 증거: `OrderEventAggregationPipeline` 동작이 아직 구현되지 않았기 때문에 테스트가 실패합니다. 작업 1이 이미 도메인 유형을 생성한 경우 RED 증거는 해결되지 않은 파이프라인 기호 또는 실패한 동작 어설션일 수 있으며 반드시 도메인 클래스가 누락된 것은 아닙니다.

## 작업 3: 파이프라인 구현

**복잡성:** 높음
**적용:** `$bluetape4k-code-patterns`

**파일:**
- 생성: `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventAggregationPipeline.kt`
- 수정: `kotlin/flow-extensions-event-aggregation/src/main/kotlin/io/bluetape4k/workshop/flow/event/aggregation/OrderEventDomain.kt`

- [ ] `OrderState.apply(event: OrderEvent): OrderState` 구현:
  - `OrderCreated`이(가) `NEW -> CREATED` 이동합니다.
  - `LineAdded`은 라인 수와 항목 수량을 증가시키지만 상태가 `NEW`가 아닌 이상 현재 수명 주기 상태를 유지하고 `CREATED`를 사용합니다.
  - `PaymentAuthorized`은 `authorizedAmountCents`을 설정하고 취소되지 않은 주문을 `PAID`로 이동합니다.
  - `ShipmentStarted`은 취소되지 않은 주문을 `SHIPPED`로 이동합니다.
  - `OrderCancelled`은 `CANCELLED` 터미널로 이동합니다.
  - 일단 `CANCELLED`이면 나중에 취소되지 않는 이벤트는 `CANCELLED` 상태를 유지하면서 감사 가시성을 위해 version/last 이벤트를 계속 증가시킵니다.
- [ ] 순서가 잘못된 투영 이벤트를 거부하는 대신 수락합니다.
  - `ShipmentStarted` 결제 전 `SHIPPED`로 이동합니다.
  - 중복된 `PaymentAuthorized`은(는) `PAID`로 유지되고 취소되지 않으면 업데이트(amount/version)됩니다.
  - `SHIPPED` 이후 `OrderCancelled`은 `CANCELLED` 터미널로 이동합니다.
  - `CANCELLED` 이후 취소되지 않는 이벤트는 `CANCELLED`을 유지합니다.
- [ ] 불변 지도 복사본에서 변경된 순서 항목만 교체하여 `OrderReadModel.apply(event)`을 구현합니다.
- [ ] `OrderEventAggregationPipeline` 구현:
  - `chunkedActivity(events, chunkSize)`은 `chunkSize > 0`의 유효성을 검사하고 `events.chunked(chunkSize, partialWindow = true)`를 호출한 다음 각 배치를 `OrderActivitySummary`에 매핑합니다.
  - `rollingActivity(events, size, step)`은 `windowed`과 동일한 제약 조건을 검증하고 `events.windowed(size, step, partialWindow = true)`를 호출한 다음 각 창을 `OrderActivitySummary`에 매핑합니다.
  - `groupedByOrder(events)`은 `Flow<GroupItem<String, OrderEvent>>`을 반환하고 `events.groupBy { it.orderId }.flatMapMerge(concurrency = GROUPING_CONCURRENCY) { it.toGroupItems() }`를 호출합니다. 여기서 `GROUPING_CONCURRENCY`은 유한 데모 가드로 문서화되어 있습니다.
  - `readModels(events)`은 `events.scanWith({ OrderReadModel.empty() }) { model, event -> model.apply(event) }`을 호출하고 의도적으로 초기 빈 모델을 내보냅니다.
  - `statusRuns(events, orderId)`은 하나의 주문을 필터링하고, `scanWith`를 적용하고, 초기 `NEW` 상태를 삭제하고, `bufferUntilChanged { it.status }`를 호출하고, 각 실행 목록을 최종 상태가 있는 하나의 DTO에 매핑하여 `Flow<OrderStatusRun>`를 반환합니다.
  - `transitions(events, orderId)`은 `statusRuns(...).map { it.finalState }.zipWithNext { previous, current -> OrderTransition(...) }`을 호출하고 변경되지 않은 상태를 필터링합니다.
  - `audit(events)`은 먼저 정리된 `OrderAuditEntry`에 매핑된 다음 `.log("order-event-aggregation")`를 호출합니다.
- [ ] `groupBy` 대 `bufferUntilChanged` 구별 및 `groupedByOrder`에 대한 유한 스트림 주의 사항을 포함하여 각 공용 파이프라인 기능에 대해 영어 KDoc를 추가합니다.
- [ ] KDoc 및 README의 문서 할당 동작:
  - `windowed`/`chunked`은 목록을 내보내고 겹치는 창은 유지된 요소를 복제합니다.
  - `rollingActivity(size = 3, step = 1, partialWindow = true)`은(는) 증폭된 꼬리 창을 방출할 수 있습니다. 테스트에서는 카운트를 잠급니다.
  - `statusRuns`은 `bufferUntilChanged`을 사용하고 상태가 변경되거나 업스트림이 완료될 때까지 하나의 실행을 유지한 다음 해당 실행 목록을 복사합니다. 비용은 `O(runLength)` 유지입니다.
  - 불변 `OrderReadModel` 스냅샷은 학습 명확성을 위해 의도적으로 이벤트별로 할당합니다. 유한 교육 모델에서 복사 비용은 `O(events * activeOrders)`입니다.
  - 높은 처리량 생산 예측은 이벤트별로 전체 활성 주문 맵을 복사하는 대신 제한된 변경 가능한 내부 상태, 체크포인트 예측 및 내구성 있는 저장소를 고려해야 합니다.
  - `audit`은 진단 전용이며 처리량이 많은 생산 경로에서는 gated/removed이어야 합니다.
  - `audit(events)`은 임의의 업스트림 예외 메시지가 아닌 내보낸 감사 값을 삭제합니다. examples/tests 민감하지 않은 실패 메시지를 사용합니다.
- [ ] 녹색이 될 때까지 대상 테스트를 실행합니다.

```bash
./gradlew :kotlin-flow-extensions-event-aggregation:test --rerun-tasks --console=plain
```

예상 증거: 모듈 테스트가 통과되었습니다.

## 작업 4: 문서 및 다이어그램

**복잡성:** 높음
**적용:** `$bluetape4k-blog`, `$bluetape4k-diagram`

**파일:**
- 생성: `kotlin/flow-extensions-event-aggregation/README.md`
- 생성: `kotlin/flow-extensions-event-aggregation/README.ko.md`
- 수정: `README.md`
- 수정: `README.ko.md`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-scenario-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-architecture-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-domain-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-sequence-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-contact-sheet-01.png`

- [ ] README.md 섹션:
  - 언어 스위치: `[한국어](README.ko.md) | English`
  - 개요
  - 이전: 변경 가능한 맵 및 예약된 플러시
  - 이후: Flow 확장 파이프라인
  - 아키텍처 및 시나리오 다이어그램
  - 핵심 도메인
  - 파이프라인 연습
  - 중고 Bluetape4k 기능 표
  - 범위 및 지속성 이벤트 store/outbox 주의사항
  - 리소스 소유권 및 복구 의미론
  - 내구성 있는 store/outbox 책임 테이블
  - 디버그 audit/logging 경계
  - 롤아웃, 롤백 및 운영자 확인 노트
  - 명령 실행
- [ ] README.ko.md는 동일한 원본 사실을 자연 한국어로 반영합니다.
  - 언어 스위치: `[English](README.md) | 한국어`
  - 문자 그대로의 번역을 피하세요. 기술 용어를 명확하게 유지하세요.
- [ ] 루트 README 테이블에 기본 비동기 및 반응 행이 추가됩니다.
  - `flow-extensions-event-aggregation`
  - 라이브러리: `coroutines`, `junit5`
  - 인프라: `In-memory`
  - 학습 결과: chunk/window/group/scan/suppression/transition Flow 확장을 사용한 이벤트 집계.
- [ ] 영어 레이블, `Architects Daughter` 및 `Comic Mono`, Graphviz 없음, 고안된 인프라 아이콘 없음, 표시 레이어가 있는 위에서 아래로 아키텍처 흐름을 사용하여 다이어그램을 생성합니다.
- [ ] `~/.local/bin/cairosvg`을 사용하여 모든 SVG부터 PNG까지 렌더링합니다.
- [ ] 다이어그램 유효성 검사를 실행합니다.

```bash
xmllint --noout docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-event-aggregation-readme-*.svg
```

- [ ] 모든 SVG에 대해 CairoSVG 렌더링 성공을 기록합니다.
- [ ] 육안 검사를 통해 접착 시트와 모든 전체 크기 PNG를 만들고 검사합니다. 증거를 `docs/review/2026-06-29-issue-303-flow-event-aggregation-review.md`에 저장하세요.

예상 증거: 유효성 검사기 통과, 모든 SVG 렌더링 및 육안 검사 통과.

## 작업 5: 연기 등록 및 확인

**복잡성:** 중간
**적용:** `$bluetape4k-code-patterns`

**파일:**
- 수정: `.github/workflows/Examples.yml`
- 수정: `scripts/smoke-validate.sh`

- [ ] `.github/workflows/Examples.yml`의 푸시 및 풀 요청 경로 필터에 `kotlin/flow-extensions-event-aggregation/**`을 추가합니다.
- [ ] `smoke-examples` Gradle 명령에 `:kotlin-flow-extensions-event-aggregation:test`을 추가합니다.
- [ ] 연기 아티팩트 업로드에 정확한 새 테스트 결과 경로를 추가합니다.
  - `kotlin/flow-extensions-event-aggregation/build/test-results/test/*.xml`
  - `kotlin/flow-extensions-event-aggregation/build/reports/tests/test/`
- [ ] `all-smoke` 및 `async`의 `scripts/smoke-validate.sh`에 `:kotlin-flow-extensions-event-aggregation:test`을 추가합니다.
- [ ] `all-smoke`에 작업을 추가하면 의도적으로 `.github/workflows/nightly.yml` 연기 실행이 포함됩니다. 직접적인 워크플로 그룹화가 변경되지 않는 한 야간 워크플로 편집은 필요하지 않습니다.
- [ ] `./gradlew projects` 개수를 확인한 후 `stale-check` 예상 프로젝트 개수를 +1 업데이트합니다.
- [ ] 달리다:

```bash
actionlint .github/workflows/Examples.yml
./gradlew projects --console=plain
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
./scripts/smoke-validate.sh async
./scripts/smoke-validate.sh stale-check
git diff --check
```

예상 증거: actionlint 통과, 프로젝트 수가 현재 상태, 비동기 연기 통과, diff 확인이 깨끗함.

## 작업 6: 리뷰, 강의, PR 및 CI

**복잡성:** 높음
**적용:** `$bluetape4k-workflow`, `$bluetape4k-full-feature`

**파일:**
- 생성: `docs/review/2026-06-29-issue-303-flow-event-aggregation-review.md`
- 생성: `docs/lessons/2026-06-29-issue-303-flow-event-aggregation.md`

- [ ] 이슈 #303 및 spec/plan에 대해 5단계 검증 도구 확인을 실행합니다.
- [ ] 6개의 독립적인 관점과 기본 통합을 통해 6-R단계 검토를 실행합니다. P0 = 0과 P1 = 0으로 수렴합니다.
- [ ] 간결한 강의 만들기:
  - 컨텍스트: 이벤트 집계 Flow 예.
  - 결정: `bufferUntilChanged` 인접 억제에서 `groupBy` 전역 그룹화를 분리합니다.
  - 결과: 테스트와 README는 구별을 명확하게 합니다.
  - 향후 지침: `bufferUntilChanged`을 모든 순서 그룹화 기본 요소로 사용하지 마세요.
- [ ] Lore 커밋 프로토콜을 사용하여 구현을 커밋합니다.
- [ ] `develop`에 대해 PR를 생성합니다.
  - 제목: `feat: add Flow event aggregation workshop`
  - 담당자: `debop`
  - 마일스톤: `1.2.0`
  - 이슈 #303에서 미러링된 라벨: `documentation`, `enhancement`, `difficulty:intermediate`, `area:async-reactive`, `coroutines`
  - 본문 링크 `Closes #303`
  - 최종 Markdown `##` 섹션은 정확히 `## DoD Status`입니다.
- [ ] 라이브 메타데이터를 확인합니다.

```bash
gh issue view 303 --json assignees,labels,milestone,state
gh pr view <number> --json assignees,labels,milestone,body
```

- [ ] PR 검사를 실행하고, PR body/metadata을 확인하고, 검사가 녹색이면 reviews/review 스레드를 다시 읽으세요.
- [ ] 사용자가 이 PR에 대해 병합을 명시적으로 요청하지 않는 한 병합 준비 상태를 보고하고 중지합니다.
- [ ] 사용자가 명시적으로 병합을 요청하는 경우 병합 직전에 reviews/review 스레드를 다시 읽고, 리베이스로 병합하고, 로컬 `develop`을 동기화하고, 병합 조상이 입증된 후에만 기능 작업 트리를 제거하고, 이슈 #303가 닫혔는지 확인합니다.

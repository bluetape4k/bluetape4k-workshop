# 이벤트 소싱 assertion 이관 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`-`) syntax for tracking.

**목표:** `:commerce-usage-metering-billing-event-sourcing`의 이벤트 소싱 테스트 파일 21개를 JUnit/Kotlin assertion API에서 릴리스된 `bluetape4k-assertions` API로 이관합니다. 이때 프로덕션 동작, 픽스처, coroutine/Awaitility 타이밍, Testcontainers 수명 주기, 워크플로 구성은 변경하지 않습니다.

**아키텍처:** 제품 변경 범위는 고정된 21개 Kotlin 테스트 매니페스트로 제한합니다. 기존 호출 지점에서 assertion을 의도별 Bluetape 매처로 교체하고, 필요한 경우 명시적 로컬 변수를 사용해 nullable/type narrowing 동작을 보존합니다. 기존 mutex와 직렬화된 Gradle 토폴로지를 사용해 단위 테스트, 통합 테스트, 스트레스 레인을 검증합니다. 구현 후 별도의 한국어 작업 산출물에 이관 결정과 증거를 기록합니다.

**기술 스택:** Kotlin 2.4.0, JUnit 5, Kotlin coroutines, Awaitility, Testcontainers, Gradle, `bluetape4k-dependencies:1.3.1` BOM으로 해석되는 `io.github.bluetape4k:bluetape4k-assertions:1.11.0`.

---

## 고정 매니페스트

Kotlin 소스 변경은 다음 테스트 파일 21개에만 허용합니다.

```text
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/BillingEventSourcingStressTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/EventSourcingRuntimeContractTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/KotlinPatternArchitectureTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/TenantIsolationIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/CommandServicePostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/CorrectionReconciliationIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/DomainEventJsonCodecTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/AggregateReducerTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/EventContractTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/AggregateReplayTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/CanonicalEventHashTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/EventCodecRegistryTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandFingerprintTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandReceiptPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/EventStorePostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/RepositoryArchitectureTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/SnapshotPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionCoordinatorPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionGenerationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionRecoveryPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/web/EventSourcingHttpIntegrationTest.kt
```

프로덕션 소스, 빌드 스크립트, 의존성 카탈로그, 워크플로, 자격 증명, 픽스처 수명 주기, coroutine/Awaitility 코드, 생성된 리포트, raw log는 변경하지 않습니다.

## Assertion 매핑 계약

다음 릴리스 API를 사용하고 expected/actual 순서를 보존합니다.

```kotlin
actual.shouldBeEqualTo(expected)
actual.shouldNotBeEqualTo(expected)
condition.shouldBeTrue()
condition.shouldBeFalse()
value.shouldBeNull()
value.shouldNotBeNull()
value.shouldBeInstanceOf<Foo>()
text.shouldNotBeBlank()
collection.shouldBeEmpty()
collection.shouldContain(element)
number.shouldBeGreaterThan(expected)
number.shouldBeLessThan(expected)
number.shouldBeLessOrEqualTo(expected)
assertFailsWith<ExpectedException> { operation() }
```

표현식이 assertion 인자일 때만 의도별 매처를 적용합니다. `if (page.isEmpty()) return`, `it.name.contains("skip")`, `violations.isEmpty()`처럼 assertion 인자가 아닌 helper/control-flow predicate는 그대로 둡니다. 진단 전용 JUnit message parameter는 제거하되, response-body/header와 exception-field 동작은 별도의 assertion으로 보존합니다.

## 작업 1: 베이스라인과 경로 가드 고정

**파일:**
- 읽기: `docs/superpowers/specs/2026-08-05-issue-566-event-sourcing-assertions-design.md`
- 수정: 없음

- [ ] **1단계: 브랜치와 clean state 확인**

```bash
git status --short
git rev-parse HEAD
git show -s --format='%H %s' ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d
```

예상 결과: 승인된 설계/계획 커밋만 존재하고 관련 없는 변경 파일은 없습니다.

- [ ] **2단계: 매니페스트 재계산**

```bash
rg -l 'org\.junit\.jupiter\.api\.Assertions|kotlin\.test\.assert|assert[A-Z][A-Za-z0-9_]*\(|assertThat|org\.assertj|io\.kotest|should[A-Z][A-Za-z0-9_]*' \
  commerce/usage-metering-billing-event-sourcing/src/test/kotlin -g '*Test.kt' | sort
```

예상 결과: 고정 매니페스트의 21개 경로와 정확히 일치합니다. 집합이 다르면 `PENDING` 상태로 중지합니다.

- [ ] **3단계: 비교 가능한 split 베이스라인 캡처**

새 `/bin/bash -Eeuo pipefail` 프로세스에서 `clean`, `test`, `integrationTest`, `stressTest`를 각각 별도의 Gradle 호출로 실행합니다. 각 호출에 `--no-build-cache --max-workers=1`, `/usr/bin/time -p`, 즉시 `PIPESTATUS` 캡처, 저장소 외부 임시 로그, 호출당 15분 supervisor timeout을 사용합니다. 합계를 `B_split`으로 기록합니다. 예상 테스트 수는 `test=19`, `integrationTest=35`, `stressTest=1`이며 failures/errors/skips는 모두 0이어야 합니다. raw log는 커밋하지 않습니다.

## 작업 2: 런타임, 아키텍처, 도메인, 이벤트 저장소, fingerprint 테스트 이관

**파일:**
- 수정: `BillingEventSourcingStressTest.kt`, `EventSourcingRuntimeContractTest.kt`, `KotlinPatternArchitectureTest.kt`, `AggregateReducerTest.kt`, `EventContractTest.kt`, `AggregateReplayTest.kt`, `CanonicalEventHashTest.kt`, `EventCodecRegistryTest.kt`, `CommandFingerprintTest.kt`

- [ ] **1단계: import 교체**

```kotlin
// before
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

// after
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
```

각 파일에서 사용하는 matcher import만 추가하고, 변환 후 모든 JUnit/Kotlin assertion import를 제거합니다.

- [ ] **2단계: equality, boolean, null 호출 변환**

```kotlin
assertEquals(expected, actual)     // actual.shouldBeEqualTo(expected)
assertNotEquals(expected, actual) // actual.shouldNotBeEqualTo(expected)
assertTrue(condition)             // condition.shouldBeTrue()
assertFalse(condition)            // condition.shouldBeFalse()
assertNull(value)                 // value.shouldBeNull()
assertNotNull(value)              // value.shouldNotBeNull()
```

이벤트 생성, replay state, stress coroutine 구조, 픽스처 호출은 변경하지 않습니다.

- [ ] **3단계: exception 호출 변환**

```kotlin
assertThrows(IllegalStateException::class.java) { operation() }
// becomes
assertFailsWith<IllegalStateException> { operation() }
```

- [ ] **4단계: 순수 unit 그룹 컴파일 및 실행**

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:compileTestKotlin \
  --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:test \
  --tests '*EventSourcingRuntimeContractTest' \
  --tests '*KotlinPatternArchitectureTest' \
  --tests '*AggregateReducerTest' \
  --tests '*EventContractTest' \
  --tests '*AggregateReplayTest' \
  --tests '*CanonicalEventHashTest' \
  --tests '*EventCodecRegistryTest' \
  --tests '*CommandFingerprintTest' \
  --no-build-cache --max-workers=1
```

예상 결과: 컴파일과 선택한 단위 테스트가 통과합니다.

## 작업 3: 애플리케이션, 멱등성, 영속성 테스트 이관

**파일:**
- 수정: `CommandServicePostgresIntegrationTest.kt`, `CorrectionReconciliationIntegrationTest.kt`, `DomainEventJsonCodecTest.kt`, `CommandReceiptPostgresIntegrationTest.kt`, `EventStorePostgresIntegrationTest.kt`, `RepositoryArchitectureTest.kt`, `SnapshotPostgresIntegrationTest.kt`

- [ ] **1단계: nullable 의미 보존**

```kotlin
// before
assertNotNull(finding)
assertEquals(expected, finding!!.eventId)

// after
val actualFinding = finding.shouldNotBeNull()
actualFinding.eventId.shouldBeEqualTo(expected)
```

null만 확인할 때는 `shouldBeNull()`을 사용합니다. 이후 코드에서 좁혀진 값이 필요하면 `shouldNotBeNull()`이 반환한 로컬 변수를 사용합니다.

- [ ] **2단계: Java Class 타입 좁히기 보존**

```kotlin
// before
assertInstanceOf(CommandAcquireResult.Owned::class.java, second)
service.succeed(second as CommandAcquireResult.Owned, 201, "{}", acquiredAt)

// after
val secondOwned = second.shouldBeInstanceOf<CommandAcquireResult.Owned>()
service.succeed(secondOwned, 201, "{}", acquiredAt)
```

- [ ] **3단계: 예외 필드 보존**

```kotlin
val failure = assertThrows(IllegalArgumentException::class.java) { codec.decode(payload) }
assertEquals("event_payload_too_large", failure.message)

// after
val failure = assertFailsWith<IllegalArgumentException> { codec.decode(payload) }
failure.message.shouldBeEqualTo("event_payload_too_large")
```

`assertEquals(200, response.status.value(), diagnostic)`에서는 `diagnostic`만 제거하고 body/header 확인은 모두 유지합니다.

- [ ] **4단계: application/persistence 그룹 컴파일**

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:compileTestKotlin \
  --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:test \
  --tests '*DomainEventJsonCodecTest' \
  --tests '*RepositoryArchitectureTest' \
  --no-build-cache --max-workers=1
```

예상 결과: 컴파일과 선택한 단위 테스트가 통과합니다. 태그된 PostgreSQL 테스트는 assertion syntax만 변경하고 나머지는 그대로 둡니다.

## 작업 4: 테넌트, 프로젝션, HTTP, 스트레스 assertion 이관

**파일:**
- 수정: `TenantIsolationIntegrationTest.kt`, `ProjectionCoordinatorPostgresIntegrationTest.kt`, `ProjectionGenerationTest.kt`, `ProjectionRecoveryPostgresIntegrationTest.kt`, `EventSourcingHttpIntegrationTest.kt`
- 검토: `BillingEventSourcingStressTest.kt` (작업 2에서 이미 변경했으며 관련 없는 두 번째 수정은 하지 않음)

- [ ] **1단계: 테넌트 및 권한 부여 불변식 보존**

기존 테넌트 범위의 `Owned` 결과, 테넌트 합계, 프로젝션/대사 확인, admin `403`, 운영자 성공 응답, 응답 헤더/본문, 인증되지 않은 health 동작을 유지합니다. 허용되는 변경은 syntax뿐입니다.

```kotlin
assertEquals(1, fixture.executor.transaction { eventStore.load(stream(TENANT_A)).size })
// becomes
fixture.executor.transaction { eventStore.load(stream(TENANT_A)).size }.shouldBeEqualTo(1)
```

새 tenant-b negative case나 receipt-ID assertion은 추가하지 않습니다.

- [ ] **2단계: 문자열 predicate를 의도에 맞게 변환**

```kotlin
assertTrue(commandId.isNotBlank())                 // commandId.shouldNotBeBlank()
assertFalse(actuatorBody.contains("projectionPosition")) // actuatorBody.shouldNotContain("projectionPosition")
assertTrue(operatorHealthBody.contains("projectionPosition"), operatorHealthBody) // operatorHealthBody.shouldContain("projectionPosition")
```

진단 전용 message만 제거합니다. MockK field variable, response value, coroutine/Awaitility polling, 픽스처, timestamp는 보존합니다.

- [ ] **3단계: projection exception과 predicate 호출 변환**

순서 assertion에는 `assertFailsWith<T>`, `shouldBeTrue/shouldBeFalse`, `shouldBeEqualTo`, 기존 numeric matcher를 사용합니다. 프로덕션 또는 수명 주기 코드는 변경하지 않습니다.

- [ ] **4단계: 전체 source 변환 후 컴파일**

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:compileTestKotlin \
  --no-build-cache --max-workers=1
```

예상 결과: 컴파일이 성공하고 매니페스트에 금지된 assertion import가 남지 않습니다.

## 작업 5: 전체 split 검증 및 잔여 assertion 감사

**파일:**
- 읽기: 21개 Kotlin 테스트 파일과 생성된 JUnit XML/리포트
- 첫 번째 검증 단계에서는 수정하지 않음

- [ ] **1단계: security-matrix 통합 테스트를 먼저 실행**

`integrationTest`에서 `TenantIsolationIntegrationTest`와 `EventSourcingHttpIntegrationTest`를 필터링해 `--no-build-cache --max-workers=1`로 실행합니다. 예상 결과: 두 테스트가 통과하고 XML에 기존 테넌트 격리 및 권한 부여 테스트 케이스가 포함됩니다.

- [ ] **2단계: 표준 레인을 순차 실행**

승인된 Bash 캡처와 supervisor timeout을 사용합니다.

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:clean --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:test --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:integrationTest --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:stressTest --no-build-cache --max-workers=1
```

예상 결과: 테스트 수는 `19/35/1`, failures/errors/skips는 모두 0이며 timeout이나 픽스처 잔여물이 없습니다. 최종 split wall-clock은 `2 × B_split`보다 크지 않아야 합니다.

- [ ] **3단계: assertion 잔여 항목과 허용 목록 감사**

```bash
rg -n 'org\.junit\.jupiter\.api\.Assertions|kotlin\.test\.assert|assert[A-Z][A-Za-z0-9_]*\(|assertThat|org\.assertj|io\.kotest' \
  commerce/usage-metering-billing-event-sourcing/src/test/kotlin -g '*Test.kt'
git diff --name-only ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin
git diff --check
```

예상 결과: 금지된 assertion API가 남아 있지 않고(JUnit annotation은 허용), 소스 diff 경로가 21개 매니페스트와 일치하며 diff check가 통과합니다.

- [ ] **4단계: Kotlin-pattern 최종 체크리스트 적용**

receiver 중심 assertion, nullable semantic drift 없음, 변경되지 않은 structured concurrency, MockK field declaration, 프로덕션/의존성 변경 없음, 그리고 해당하지 않는 항목을 사유와 함께 `N/A`로 이관 기록에 기록합니다.

## 작업 6: 이관 기록과 한국어 lesson 작성

**파일:**
- 생성: `docs/review/2026-08-05-issue-566-event-sourcing-assertions-migration-record.md`
- 생성: `docs/lessons/2026-08-05-issue-566-event-sourcing-assertions.md`

- [ ] **1단계: migration record 작성**

다음 heading을 정확히 사용합니다: `Context`, `Base commit`, `Manifest`, `Resolved artifact`, `Unmapped API table`, `Diagnostic-only message table`, `Security traceability`, `Test evidence`, `Redaction audit`, `Owner`, `Status`. 정확한 아티팩트 쌍, 21개 경로, B_split/final timing, XML count, residual scan, 해당하는 경우 exact-head CI `N/A`를 포함합니다. credential, header, request body, raw log는 절대 복사하지 않습니다.

- [ ] **2단계: 한국어 lesson 작성**

`Context / Decision / Outcome / Evidence / Misses / Future guard`를 사용합니다. 매처 매핑, Java `Class<T>` 타입 좁히기, diagnostic-message 처리, control-flow 보존, 향후 manifest/redaction guard를 설명합니다. redacted command와 count만 포함합니다.

- [ ] **3단계: 문서 및 보안 검증 실행**

`git diff --check`, raw log와 migration/spec-review/lesson 문서에 대한 승인된 scanner, XML/리포트 inventory를 실행합니다. 두 번째 scanner pass는 exit 0이어야 하며 raw log는 삭제하고 존재하지 않음을 확인합니다. 누락되었거나 redaction되지 않은 증거는 `PENDING` 상태로 남깁니다.

## 작업 7: 검토 가능한 커밋과 외부 부작용 게이트

**파일:**
- 수정: 21개 Kotlin 파일과 두 개의 증거 문서만

- [ ] **1단계: staged diff 검사**

```bash
git diff --stat
git diff --check
git status --short
```

예상 결과: 허용 목록 경로만 staged 상태이고 generated report, credential, workflow, production, dependency 파일은 staged 상태가 아닙니다.

- [ ] **2단계: Lore trailer와 함께 커밋**

`Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested`를 포함한 intent-first message를 사용합니다. 남은 exact-head CI 또는 timing gap은 `Not-tested`에 명시합니다.

- [ ] **3단계: 외부 부작용 전에 중지**

정확한 구현 `HEAD`, checks, reviews와 해당 작업에 대한 별도 승인을 다시 확인하기 전에는 PR 생성/수정, Nightly/Examples dispatch, 후속 issue 생성, merge, tag, publish, worktree 삭제를 하지 않습니다.

## 자체 검토

- [ ] 매니페스트에 실제 경로가 정확히 21개인지 확인합니다.
- [ ] 모든 작업에 정확한 경로, 명령, 예상 결과가 있고 모호한 placeholder가 없는지 확인합니다.
- [ ] 설계 범위가 API 매핑, nullable semantics, message/exception 보존, 테넌트/보안 불변식, redaction, split timing, rollback, exact-head, 독립적인 부작용 승인을 다루는지 확인합니다.
- [ ] 어떤 작업도 프로덕션, 의존성, 워크플로, 자격 증명, 픽스처 수명 주기, coroutine, Awaitility 변경을 허용하지 않는지 확인합니다.

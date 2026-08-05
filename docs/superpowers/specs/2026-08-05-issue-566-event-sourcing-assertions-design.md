# Issue #566 이벤트 소싱 assertion 이관 설계

## 1. 결정 요약

`commerce/usage-metering-billing-event-sourcing` 테스트 21개 파일의 JUnit/Kotlin assertion idiom을 이미 릴리스된 `bluetape4k-assertions` API로 이관한다. 테스트의 도메인 동작, coroutine/Awaitility/Testcontainers 수명주기, MockK 상호작용, JUnit 실행 구조는 보존한다.

이번 작업은 assertion 표현의 일관성과 실패 메시지의 의도를 개선하는 테스트 리팩터링이다. production Kotlin, Gradle 의존성, 모듈 경계, 다른 commerce 모듈은 변경하지 않는다.

## 2. 근거와 현재 상태

- 대상 issue: [#566](https://github.com/bluetape4k/bluetape4k-workshop/issues/566)
- 대상 모듈: `:commerce-usage-metering-billing-event-sourcing`
- 대상 범위: 모듈 `src/test` 아래 assertion API를 사용하는 21개 Kotlin 테스트 파일
- 초기 smoke baseline: `./gradlew :commerce-usage-metering-billing-event-sourcing:cleanTest :commerce-usage-metering-billing-event-sourcing:test --no-build-cache`
- 초기 smoke baseline 결과: `BUILD SUCCESSFUL` (25초). 이 task는 `integration`·`stress` tag를 제외하므로 전체 migration proof로 사용하지 않는다.
- 전체 baseline: `./gradlew :commerce-usage-metering-billing-event-sourcing:clean :commerce-usage-metering-billing-event-sourcing:test :commerce-usage-metering-billing-event-sourcing:integrationTest :commerce-usage-metering-billing-event-sourcing:stressTest --no-build-cache --max-workers=1`
- 전체 baseline 결과: `BUILD SUCCESSFUL` (101.95초 wall-clock), `test` 19개, `integrationTest` 35개, `stressTest` 1개 통과. 이 값은 한 Gradle invocation에 task를 연결한 초기 context baseline이며, 최종 split invocation의 성능 comparator로 직접 사용하지 않는다. 세 task가 모두 실행되어야 전체 migration proof가 된다.
- comparable split baseline: 구현 전 동일한 `/bin/bash -Eeuo pipefail` capture로 `clean`, `test`, `integrationTest`, `stressTest`를 별도 invocation하고 합산 wall-clock을 `B_split`으로 기록한다. 최종 threshold는 `2 × B_split`이며, `B_split`이 없으면 성능 DoD는 `PENDING`이다.
- baseline 기준 commit: `ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d`
- 대상 모듈은 이미 `bluetape4k-assertions` 테스트 의존성을 선언하고 있다.
- 저장소의 인접 테스트에서 `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`, `shouldBeNull`, `shouldNotBeNull`, `shouldBeInstanceOf`, `shouldContain`, `shouldHaveSize`, `shouldNotBeEqualTo`, `assertFailsWith`를 사용하고 있다.
- baseline dependency insight가 `bluetape4k-dependencies:1.3.1` BOM을 통해 `io.github.bluetape4k:bluetape4k-assertions:1.11.0`을 선택함을 확인했다. 선언을 바꾸거나 버전을 pin하지 않고 이 resolved artifact의 overload를 기준으로 이관한다.
- 저장소 root의 `test-mutex` BuildService는 `maxParallelUsages=1`로 등록되어 있고 대상 모듈의 `Test` task가 이를 사용한다. 모듈 `junit-platform.properties`도 JUnit parallel execution을 비활성화한다. `org.gradle.parallel=true` 자체는 유지하되 이 모듈의 테스트 경합은 이 명시적 mutex 계약으로 제한된다.

## 3. 목표

1. 대상 21개 테스트에서 JUnit static assertion 및 `kotlin.test` assertion 의존성을 제거한다.
2. 값의 의도를 드러내는 Bluetape matcher와 Bluetape 예외 assertion을 적용한다.
3. 기존 테스트가 검증하는 이벤트 소싱 계약과 비동기/통합 테스트 수명주기를 그대로 유지한다.
4. Kotlin pattern 규칙에 맞는 import, 이름, 구조, coroutine 테스트 방식을 유지한다.
5. 전체 모듈 테스트를 순차 실행해 migration이 실제 동작을 보존함을 증명한다.

## 4. 비목표와 불변 조건

- production source, API, schema, fixture, Gradle dependency/version, workflow는 수정하지 않는다.
- 공통 assertion wrapper, 새 helper, 새 abstraction을 추가하지 않는다.
- assertion migration을 이유로 테스트의 timeout, retry, `Awaitility`, `runTest`, dispatcher, Testcontainers lifecycle을 조정하지 않는다.
- JUnit annotation/import는 assertion과 분리해 필요한 경우 유지한다.
- 재사용 MockK mock은 기존 field 선언을 유지한다. 새 mock을 만들 필요가 생기더라도 local mock으로 우회하지 않고 Kotlin pattern 기준을 따른다.
- 대상 모듈 밖의 assertion migration은 이번 PR에 포함하지 않는다.

## 5. 적용 방식

### 5.1 API mapping

| 기존 표현 | 적용할 Bluetape 표현 | 적용 원칙 |
| --- | --- | --- |
| `assertEquals(expected, actual)` | `actual.shouldBeEqualTo(expected)` | actual을 receiver로 두고 실패 의도를 보존한다. |
| `assertNotEquals(expected, actual)` | `actual.shouldNotBeEqualTo(expected)` | 단순 부등식만 유지한다. |
| `assertTrue(condition)` | `condition.shouldBeTrue()` | 조건식 자체를 receiver로 둔다. |
| `assertFalse(condition)` | `condition.shouldBeFalse()` | 부정 조건을 이중 부정으로 만들지 않는다. |
| 숫자 비교 predicate (`actual > expected`, `actual <= expected`) | `shouldBeGreaterThan`, `shouldBeLessOrEqualTo` 등 | assertion argument의 비교 의도만 Boolean으로 감싸지 않는다. |
| `text.isNotBlank()` | `text.shouldNotBeBlank()` | assertion argument의 문자열 blank 의도만 직접 표현한다. |
| `collection.contains(value)` | `collection.shouldContain(value)` 또는 `shouldNotContain` | assertion argument의 containment와 전체 equality를 구분한다. |
| `collection.isEmpty()` | `collection.shouldBeEmpty()` | assertion argument의 empty collection 의도만 직접 표현한다. |
| opaque Boolean predicate | `condition.shouldBeTrue()`/`shouldBeFalse()` | 위 intent-specific matcher가 없는 불투명 조건에만 사용한다. |
| `assertNull(value)` | `value.shouldBeNull()` | nullable 결과의 의도를 명시한다. |
| `assertNotNull(value)` | `value.shouldNotBeNull()` | smart cast가 필요한 경우 반환값/지역 변수 구조를 확인한다. |
| `assertInstanceOf<T>(value)` 또는 `assertIs<T>(value)` | `value.shouldBeInstanceOf<T>()` | 필요한 타입 추론과 반환 후 narrowed type 사용을 컴파일로 확인한다. |
| JUnit `assertInstanceOf(Foo::class.java, value)` | `value.shouldBeInstanceOf<Foo>()` | released API에 Java `Class<T>` overload가 없으므로 reified matcher로 바꾸고, 반환값을 후속 `Owned` 사용에 대입한다. |
| collection equality/size/contains | `shouldBeEqualTo`, `shouldHaveSize`, `shouldContain` 등 | 전체 equality와 부분 containment를 구분한다. |
| `org.junit.jupiter.api.Assertions.assertThrows(...)` 또는 `kotlin.test.assertFailsWith { ... }` | `io.bluetape4k.assertions.assertFailsWith<T> { ... }` | JUnit/Kotlin test exception API를 남기지 않는다. |

mapping에 없는 assertion은 기계적으로 치환하지 않는다. 해당 값의 타입과 테스트 의도를 확인한 뒤 저장소에 이미 사용 중인 intent-specific matcher를 선택하고, 컴파일 오류가 나면 가장 작은 표현 변경으로 해결한다.

`contains`, `isEmpty`, `isNotBlank`, 숫자 비교 mapping은 JUnit/Kotlin assertion의 expected/actual 인자 또는 assertion-only predicate에만 적용한다. control-flow/helper predicate는 보존한다. 예를 들어 `if (page.isEmpty()) return position`, `it.name.contains("skip")`의 내부 조건은 matcher로 바꾸지 않으며, `assertTrue(violations.isEmpty(), diagnostic)`, `assertTrue(eventStore.load(stream).isEmpty())`, HTTP body 검증처럼 assertion argument인 경우에만 intent-specific matcher를 적용한다. diagnostic-only message는 별도 behavior assertion을 보존한 뒤 record한다.

nullable receiver에 대한 Bluetape matcher의 null-as-empty/null-as-pass overload는 JUnit의 nullable Boolean/size 의미와 다를 수 있다. 따라서 nullable expression은 먼저 `shouldNotBeNull()`로 원래 실패 조건을 고정하거나 명시적 지역 변수로 non-null을 증명한 뒤 matcher를 적용하고, 현재 manifest의 non-null receiver만 직접 치환한다.

`assertIs<T>(value)`/`shouldBeInstanceOf<T>()`의 반환값은 후속 코드가 narrowed type을 요구할 때 지역 변수로 보존한다. assertion-only 호출처럼 반환값을 사용하지 않는 현재 `TenantIsolationIntegrationTest` 경로는 type-check 목적이므로 반환값을 버려도 되며, migration record에 이 의도를 명시한다.

대응 API가 없는 assertion이나 다른 provider의 특수 overload는 임의의 wrapper·대체 assertion으로 숨기지 않는다. 파일, 심볼, 원래 의미, 후보 API, 컴파일 결과를 migration record에 남기고 `PENDING`으로 멈춘 뒤 사용자 승인 없이 범위를 넓히지 않는다. `org.junit.jupiter.api.Assertions`, `kotlin.test.assert*`, AssertJ/Kluent assertion 및 JUnit `assertThrows`의 잔존은 annotation을 제외하고 forbidden으로 판정한다.

### 5.2 메시지와 예외 의미 보존

값 assertion의 JUnit 세 번째 인자는 진단용 문자열일 뿐 검증 의미가 아니다. released `shouldBeEqualTo`에는 message overload가 없으므로 진단 문자열을 wrapper로 재현하지 않는다. 대신 실제로 의미 있는 response body/exception field는 별도 Bluetape assertion으로 보존하고, 단순 diagnostic-only message는 migration record와 lesson에 `diagnostic-only, intentionally dropped`로 기록한다. `assertFailsWith`가 message parameter를 제공하는 경우에는 `assertFailsWith(message = "...") { ... }`를 사용한다.

대표적인 before/after는 다음과 같다.

```kotlin
// before: diagnostic-only JUnit message
assertEquals(200, actuatorHealth.status.value(), actuatorBody)
assertFalse(actuatorBody.contains("projectionPosition"))

// after: status 의미를 직접 검증하고 body 의미는 별도 assertion으로 보존
actuatorHealth.status.value().shouldBeEqualTo(200)
actuatorBody.shouldNotContain("projectionPosition")

// before: exception type + field
val failure = assertThrows(IllegalArgumentException::class.java) { codec.decodeOversized(payload) }
assertEquals("event_payload_too_large", failure.message)

// after: Bluetape exception result + field assertion
val failure = assertFailsWith<IllegalArgumentException> { codec.decodeOversized(payload) }
failure.message.shouldBeEqualTo("event_payload_too_large")

// before: assertIs narrows and returns the same acquired value
val acquired = receipts.acquire(scope, fingerprint, NOW)
val owned = kotlin.test.assertIs<CommandAcquireResult.Owned>(acquired)
owned.receiptId.toString().isNotBlank().shouldBeTrue()

// after: Bluetape matcher narrows the same value; UUID is compared as text only for this example
val ownedAfter = acquired.shouldBeInstanceOf<CommandAcquireResult.Owned>()
ownedAfter.receiptId.toString().shouldNotBeBlank()

// before: Java Class overload returns a narrowed value that the test later casts
assertInstanceOf(CommandAcquireResult.Owned::class.java, second)
service.succeed(second as CommandAcquireResult.Owned, 201, "{}", acquiredAt)

// after: released API has no Class<T> overload; retain the returned narrowed value
val secondOwned = second.shouldBeInstanceOf<CommandAcquireResult.Owned>()
service.succeed(secondOwned, 201, "{}", acquiredAt)
```

예시와 실제 변환이 다르면 실제 assertion의 behavior를 우선하고, message/field 검증을 삭제하지 않는다.

### 5.3 고정 manifest와 보안 traceability

아래 21개 경로가 migration manifest다. 구현 시 변경된 Kotlin test 경로의 집합은 이 manifest와 정확히 일치해야 하며, 파일 추가/제외는 별도 승인 없이는 완료로 간주하지 않는다.

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

`EventStorePostgresIntegrationTest`를 포함한 manifest path는 구현 전 `rg -l 'org\.junit\.jupiter\.api\.Assertions|kotlin\.test\.assert|assert[A-Z][A-Za-z0-9_]*\(|assertThat|org\.assertj|io\.kotest|should[A-Z][A-Za-z0-9_]*' commerce/usage-metering-billing-event-sourcing/src/test/kotlin -g '*Test.kt' | sort` 결과와 대조한다. 구현 중에는 `BASE=ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d; git diff --name-only "$BASE" -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin; git ls-files --others --exclude-standard -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin`와 `git status --porcelain=v1 -z -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin`를 함께 읽어 test-source staged/unstaged/untracked path를 manifest와 비교한다. 문서/record allowlist는 별도 path-filtered status로 확인하고, 최종 commit 이후에만 `git diff --name-only "$BASE"...HEAD -- ...`를 보조 증거로 사용한다. untracked/runtime evidence가 하나라도 allowlist 밖이면 구현을 중단한다.

보안 traceability는 다음 invariant를 별도로 기록한다.

| Test surface | 보존해야 할 invariant | 필수 lane |
| --- | --- | --- |
| `TenantIsolationIntegrationTest` | 현재 테스트가 검증하는 tenant별 scope에서 동일 external id acquire가 각각 `Owned`가 되는 결과와 projection/reconciliation assertions를 보존한다. 이 assertion-only PR은 receipt ID/row 차이나 새 cross-tenant negative case를 추가하지 않는다. | `integrationTest` |
| `EventSourcingHttpIntegrationTest` | 현재 테스트의 tenant-a credential에 대한 admin projection/reconciliation/metrics `403`, operator 허용, tenant summary의 status/header/body와 unauthenticated health 비공개 필드를 보존한다. tenant-b URL에 대한 새 HTTP negative case는 이 범위의 증거로 주장하지 않는다. | `integrationTest` |
| `DomainEventJsonCodecTest` 및 exception tests | payload limit의 exception type과 `event_payload_too_large` field가 보존된다. | `test` |

변경 path allowlist는 위 21개 test 파일과 이 spec/plan/review/lesson 문서, 그리고 migration record인 `docs/review/2026-08-05-issue-566-event-sourcing-assertions-migration-record.md`로 한정한다. `src/main`, `build.gradle.kts`, `gradle.properties`, `application*.yml`, test credentials, generated reports, captured logs는 변경하지 않는다. review/CI evidence에 Authorization header, password, datasource credential, request body가 포함되면 redact한다.

migration record는 main lane `issue-566-main`이 작성하며 다음 고정 schema를 사용한다: `Context`, `Base commit`, `Manifest`, `Resolved artifact`, `Unmapped API table`, `Diagnostic-only message table`, `Security traceability`, `Test evidence`, `Redaction audit`, `Owner`, `Status`. unsupported API/message drop가 없으면 빈 표와 `none`을 명시한다.

test-only credential contract:

- HTTP integration test credentials는 `EventSourcingHttpIntegrationTest.TestUsers`의 `PASSWORD="test-secret"`와 `{noop}` test user뿐이다.
- PostgreSQL credentials는 `PostgreSQLServer` Testcontainers launcher가 생성한 test-only 값이다.
- CI의 `DD_API_KEY=test-api-key`/`DD_APPLICATION_KEY=test-application-key`와 Testcontainers의 `testcontainers.postgresql.{username,password}=test`는 key/value exact test-only pair로만 감사에서 허용한다.
- production secret, local credential, Authorization header, bearer token, credential-bearing JDBC URL/password는 migration record나 security evidence에 저장하지 않는다. 비밀이 없는 Testcontainers JDBC endpoint는 test-only runtime metadata로 기록할 수 있지만, 위 test-only pair가 아닌 datasource/credential match는 즉시 reject한다.

local evidence는 raw log(`ISSUE566_LOG`), `commerce/usage-metering-billing-event-sourcing/build/test-results/{test,integrationTest,stressTest}/`, `commerce/usage-metering-billing-event-sourcing/build/reports/tests/{test,integrationTest,stressTest}/`, migration record와 최종 evidence 문서를 commit/upload 전에 검사한다. 기존 CI/Nightly/Examples workflow의 `if: always()` artifact upload는 이번 assertion-only PR에서 수정하지 않으므로, CI scanner는 다운로드 후 감사(post-upload audit)일 뿐 pre-upload prevention gate가 아니다. CI artifact는 security evidence로 PASS 처리하지 않으며 unredacted/missing/corrupt이면 즉시 `PENDING`과 후속 workflow-hardening issue로 기록한다.
```bash
MODULE=commerce/usage-metering-billing-event-sourcing
MIGRATION_RECORD=docs/review/2026-08-05-issue-566-event-sourcing-assertions-migration-record.md
SPEC_REVIEW_RECORD=docs/review/2026-08-05-issue-566-event-sourcing-assertions-spec-review.md
LESSON_RECORD=docs/lessons/2026-08-05-issue-566-event-sourcing-assertions.md
CI_AUDIT_RECORD=docs/review/2026-08-05-issue-566-event-sourcing-assertions-ci-audit.md
REDACTION_DOCS="${REDACTION_DOCS:-$MIGRATION_RECORD:$SPEC_REVIEW_RECORD:$LESSON_RECORD}"
CI_ARTIFACT_APPLICABLE="${CI_ARTIFACT_APPLICABLE:-false}"
CI_ARTIFACT_DIR="${CI_ARTIFACT_DIR:-}"

python3 -B - "${ISSUE566_LOG:-}" "$MODULE" "$MIGRATION_RECORD" "$REDACTION_DOCS" "$CI_ARTIFACT_APPLICABLE" "$CI_ARTIFACT_DIR" <<'PY'
from pathlib import Path
import os
import re
import sys
import xml.etree.ElementTree as ET

raw_log, module, migration_record, redaction_docs, ci_applicable, ci_dir = sys.argv[1:]
ci_mode = ci_applicable.casefold() == "true"
lanes = ("test", "integrationTest", "stressTest")
expected_counts = {"test": 19, "integrationTest": 35, "stressTest": 1}
scan_files = []
allowed_pairs = {
    ("password", "test-secret"),
    ("password", "{noop}test-secret"),
    ("testcontainers.postgresql.username", "test"),
    ("testcontainers.postgresql.password", "test"),
    ("dd_api_key", "test-api-key"),
    ("dd_application_key", "test-application-key"),
}
credential_re = re.compile(
    r"""(?ix)
    "?(?P<key>
        testcontainers\.postgresql\.(?:username|password)
        |authorization|password|username|user|api[_-]?key|application[_-]?key
        |access[_-]?token|refresh[_-]?token|client[_-]?secret|secret
        |dd_(?:api|application)_key
    )"?\s*[:=]\s*
    (?P<value>"[^"]*"|'[^']*'|[^,\s]+)
    |(?P<bearer>bearer\s+[A-Za-z0-9._-]+)
    |(?P<jdbc>jdbc:[^ \t\r\n]+:[^ \t\r\n]+@)
    """
)

def fail(message):
    print(f"redaction-preflight: {message}", file=sys.stderr)
    raise SystemExit(2)

def require_file(path):
    candidate = Path(path)
    if candidate.is_symlink() or not candidate.is_file() or not os.access(candidate, os.R_OK) or candidate.stat().st_size == 0:
        fail(f"missing, symlinked, unreadable, or empty file: {candidate}")
    if candidate not in scan_files:
        scan_files.append(candidate)

def require_xml_lane(root, lane):
    if root.is_symlink() or not root.is_dir() or not os.access(root, os.R_OK):
        fail(f"missing or unreadable XML directory: {root}")
    entries = sorted(root.rglob("*"))
    if any(path.is_symlink() for path in entries):
        fail(f"symlink in XML directory: {root}")
    allowed_binary = {
        Path("binary"),
        Path("binary/output-events.bin"),
        Path("binary/results-generic.bin"),
    }
    xml_files = sorted(root.glob("*.xml"))
    if not xml_files:
        fail(f"missing XML glob: {root}/*.xml")
    for path in entries:
        relative = path.relative_to(root)
        if path.is_dir():
            if relative not in allowed_binary:
                fail(f"unexpected nested XML directory: {relative}")
        elif path.is_file():
            if relative in allowed_binary:
                require_file(path)
            elif path.parent == root and path.suffix == ".xml":
                require_file(path)
            else:
                fail(f"unexpected nested or non-XML file: {relative}")
        else:
            fail(f"unsupported XML entry: {relative}")
    return xml_files

def require_report_lane(root, lane):
    if root.is_symlink() or not root.is_dir() or not os.access(root, os.R_OK):
        fail(f"missing or unreadable report directory: {root}")
    entries = sorted(root.rglob("*"))
    if any(path.is_symlink() for path in entries):
        fail(f"symlink in report directory: {root}")
    if any(not path.is_file() and not path.is_dir() for path in entries):
        fail(f"unsupported report entry: {root}")
    report_files = sorted(path for path in entries if path.is_file())
    if not report_files:
        fail(f"missing report files: {root}/**/*")
    if root / "index.html" not in report_files:
        fail(f"missing top-level report index: {root}/index.html")
    for report_file in report_files:
        require_file(report_file)

def require_artifact_target(root):
    if root.is_symlink() or not root.is_dir() or not os.access(root, os.R_OK):
        fail(f"missing or unreadable CI artifact target: {root}")
    entries = sorted(root.rglob("*"))
    if any(path.is_symlink() for path in entries):
        fail(f"symlink in CI artifact target: {root}")
    allowed_dirs = {
        Path("build"),
        Path("build/test-results"),
        Path("build/test-results/test"),
        Path("build/test-results/integrationTest"),
        Path("build/test-results/stressTest"),
        Path("build/reports"),
        Path("build/reports/kover"),
    }
    for path in entries:
        relative = path.relative_to(root)
        if path.is_dir():
            if relative not in allowed_dirs:
                fail(f"unexpected CI artifact directory: {relative}")
            continue
        if not path.is_file():
            fail(f"unsupported CI artifact entry: {relative}")
        parts = relative.parts
        allowed_xml = len(parts) == 4 and parts[0:2] == ("build", "test-results") and parts[2] in lanes and parts[3].endswith(".xml")
        if not (allowed_xml or relative == Path("build/reports/kover/report.xml")):
            fail(f"unexpected CI artifact file: {relative}")
    xml_by_lane = {}
    for lane in lanes:
        xml_by_lane[lane] = require_xml_lane(root / "build" / "test-results" / lane, lane)
    require_file(root / "build" / "reports" / "kover" / "report.xml")
    return xml_by_lane

def parse_counts(xml_files, lane):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except (ET.ParseError, OSError, ValueError, TypeError) as exc:
            fail(f"invalid JUnit XML {xml_file}: {exc}")
        suites = [
            suite
            for suite in root.iter()
            if suite.tag.rsplit("}", 1)[-1] == "testsuite"
        ]
        for suite in suites:
            try:
                for key in totals:
                    totals[key] += int(suite.attrib.get(key, "0"))
            except (ValueError, TypeError, AttributeError) as exc:
                fail(f"invalid JUnit count in {xml_file}: {exc}")
    if totals["tests"] != expected_counts[lane] or any(totals[key] != 0 for key in totals if key != "tests"):
        fail(f"{lane} count drift: {totals}, expected tests={expected_counts[lane]} and zero failures/errors/skipped")
    return totals

if ci_mode:
    if not ci_dir:
        fail("CI_ARTIFACT_APPLICABLE=true requires CI_ARTIFACT_DIR")
    xml_by_lane = require_artifact_target(Path(ci_dir) / module)
else:
    if ci_dir:
        fail("CI_ARTIFACT_DIR is set without CI_ARTIFACT_APPLICABLE=true")
    if not raw_log:
        fail("local scan requires ISSUE566_LOG")
    require_file(raw_log)
    require_file(migration_record)
    xml_by_lane = {}
    module_root = Path(module)
    for lane in lanes:
        xml_by_lane[lane] = require_xml_lane(module_root / "build" / "test-results" / lane, lane)
        require_report_lane(module_root / "build" / "reports" / "tests" / lane, lane)

for document in filter(None, redaction_docs.split(":")):
    require_file(document)

for lane, xml_files in xml_by_lane.items():
    parse_counts(xml_files, lane)

matches = placeholders = 0
unexpected = []
for path in scan_files:
    try:
        if path.suffix == ".bin":
            content = path.read_bytes().decode("utf-8", errors="replace")
        else:
            content = path.read_text(encoding="utf-8", errors="strict")
    except (OSError, UnicodeError) as exc:
        fail(f"cannot read {path}: {exc}")
    for match in credential_re.finditer(content):
        matches += 1
        line_number = content.count("\n", 0, match.start()) + 1
        key = match.group("key")
        value = match.group("value")
        if key is not None and value is not None:
            value = value.strip("\"'")
            pair = (key.casefold(), value)
            if pair in allowed_pairs:
                placeholders += 1
                continue
        unexpected.append(f"{path}:{line_number}:credential-match")

print(
    f"redaction-scan matches={matches} placeholders={placeholders} "
    f"unexpected={len(unexpected)} ci_artifact_applicable={ci_mode}"
)
if unexpected:
    print("\n".join(unexpected), file=sys.stderr)
    raise SystemExit(1)
PY
```

`ISSUE566_LOG="$ISSUE566_LOG" CI_ARTIFACT_APPLICABLE=false`인 local invocation은 raw log·migration record·spec-review record·lesson·모든 nested report file(CSS/JS 포함)과 각 lane의 direct XML을 pre-commit 검사한다. Gradle이 생성하는 각 lane의 고정 `binary/output-events.bin` 및 `binary/results-generic.bin` metadata만 exact allowlist로 추가해 credential regex를 적용하고 XML count에서는 제외하며, 다른 nested path는 fail closed 한다. `CI_ARTIFACT_APPLICABLE=true CI_ARTIFACT_DIR="$CI_ARTIFACT_DIR" REDACTION_DOCS="$MIGRATION_RECORD:$SPEC_REVIEW_RECORD:$LESSON_RECORD:$CI_AUDIT_RECORD" ISSUE566_LOG=""`인 Nightly post-upload 감사는 target-only로 추출된 subtree의 anchored-exact XML/Kover inventory를 scanner에 넘긴다. repository-wide `test-results` artifact 또는 Examples의 nested report/CSS/JS directory를 target-only subtree로 추출할 수 없으면 CI audit는 N/A/PENDING이며 security PASS가 아니다. scanner는 preflight/read/encoding/count 오류에서 exit 2, key/value exact pair 이외의 credential match에서 exit 1, clean에서 exit 0을 반환하며 CI 결과는 prevention이 아닌 audit로 migration record에 기록한다.

`allowed_pairs`는 key/value를 함께 제한한다: `password=test-secret`, `password={noop}test-secret`, `testcontainers.postgresql.username=test`, `testcontainers.postgresql.password=test`, `DD_API_KEY=test-api-key`, `DD_APPLICATION_KEY=test-application-key`만 test-only pair로 허용한다. 이 exact pair가 raw log나 generated report에 나타날 때도 match path와 count를 기록하며, Authorization/secret/bearer/credential-bearing JDBC와 다른 key의 동일 value는 항상 reject한다. JSON quoted key, 일반 key/value, multiline serialization을 전체 content 기준으로 탐지하고 local report의 모든 nested report file과 direct XML/binary metadata, CI target-only Kover XML input을 읽는다. 최종 evidence run에서는 위 네 concrete path를 `REDACTION_DOCS`의 colon-delimited 목록으로 확장하고 각 파일의 존재/readable/non-empty를 검사한다. CI artifact는 target-only subtree를 업로드 후 audit하며, missing/unreadable/extra/symlink/unredacted이면 PASS로 전이하지 않는다. raw temp log는 trap으로 audit 후 폐기하고, migration record에는 scan command, timestamp, match path, match count, allowed-pair count, unexpected count, owner, cleanup 결과만 남긴다.

scanner 회귀 fixture는 다음을 최소 입력으로 포함한다: `password=prod-secret`, `password=test-secret}`, `{"apiKey":"prod"}`, `"password":\n "prod"`, `Authorization: test-secret`, `secret=<test-placeholder>`, `Bearer abc.def`, `jdbc:postgresql://user:password@host/db`는 각각 unexpected match로 exit 1이어야 한다. 반대로 위 six exact test-only pair는 allowed placeholder count에만 포함되어 exit 0이어야 하며, quoted key/value·일반 key/value·multiline serialization을 동일하게 통과해야 한다. 이 fixture 결과와 scanner exit code는 migration record의 `Redaction audit`에 기록한다.

운영 evidence 경로와 owner는 다음과 같다.

| Lane | Local XML/HTML evidence | CI/Nightly artifact | Owner/record link |
| --- | --- | --- | --- |
| `test` | `commerce/usage-metering-billing-event-sourcing/build/test-results/test/*.xml`, `commerce/usage-metering-billing-event-sourcing/build/reports/tests/test/**/*.html` | PR `CI / Build (compile only)` does not run tests; local XML/HTML is canonical. Weekly/manual Nightly `test-results` post-upload audit includes broad `**/build/test-results/test/*.xml` (`retention-days: 14`). | `issue-566-main`, migration record `Test evidence` |
| `integrationTest` | `commerce/usage-metering-billing-event-sourcing/build/test-results/integrationTest/*.xml`, `commerce/usage-metering-billing-event-sourcing/build/reports/tests/integrationTest/**/*.html` | `Examples / Container Examples (sequential)` uploads broad `container-example-test-results` with nested report/CSS/JS (`retention-days: 7`) and is metadata-only unless separately extracted; Nightly `test-results` (`retention-days: 14`) is target-auditable only after target extraction. Neither is a prevention gate. | `issue-566-main`, security matrix rows |
| `stressTest` | `commerce/usage-metering-billing-event-sourcing/build/test-results/stressTest/*.xml`, `commerce/usage-metering-billing-event-sourcing/build/reports/tests/stressTest/**/*.html` | Only weekly/manual `Nightly / Test (Testcontainers)` runs this target and uploads target XML (`retention-days: 14`); audit result is live evidence, not pre-upload prevention. | `issue-566-main`, migration record `Test evidence` |
| raw timing | repo 밖 `ISSUE566_LOG` temp file, `/usr/bin/time -p` output | CI raw log는 별도 업로드하지 않음; Nightly/CI job log는 live evidence로 링크만 기록 | `issue-566-main`, `Redaction audit` |

각 경로는 존재·readable·non-empty와 expected count/failure fields를 확인한다. XML/HTML/record가 없거나 unreadable, unredacted이면 해당 lane은 PASS가 아니며 `PENDING`으로 fail closed 한다.

RACI와 상태 전이:

- `issue-566-main`은 구현, evidence 수집, migration record 갱신, rollback 실행 주체(A/R)다.
- native review lanes는 read-only reviewer(C)이고 main session이 severity와 최종 통합을 결정한다.
- 사용자는 설계/계획 승인과 merge 권한(A)이며, 범위·API 예외·release side effect 변경은 사용자 승인 없이는 진행하지 않는다.
- `verifier` lane은 artifact/test evidence의 독립 검증(C)이며, P0/P1=0과 모든 필수 row PASS를 확인한 뒤 main이 `PASS`로 전이한다.
- `PENDING`은 missing/failing evidence, count drift, unredacted match, timeout, required-check 미완료에 사용한다. main만 새 evidence를 붙여 `PENDING → PASS`를 전이할 수 있고, review P0/P1 재검토가 필요한 경우 해당 lane을 재실행한다.
- `BLOCKED`는 credential/CI/infrastructure authority가 외부에서 해소되지 않아 같은 조건이 세 번 반복된 경우에만 기록하며, 그때까지는 대체 검증을 시도한다.
- 다음 stop trigger 중 하나라도 발생하면 즉시 중단한다: credential match, missing/corrupt artifact, count/failure/skip drift, 15분 supervisor timeout, applicable exact-head CI/Nightly check failure, allowlist 밖 diff, unresolved P0/P1.

product 범위와 process evidence 범위를 분리한다. product diff는 manifest의 21개 Kotlin test 파일 assertion/import 변경으로만 제한하며 production source, dependency, schema, workflow, credentials, release 산출물은 변경하지 않는다. Type A workflow가 요구하는 spec/plan/review/migration record/lesson은 사용자에게 보이는 process artifact이지만 runtime product diff가 아니며, allowlist 문서로만 별도 관리한다. local test/XML/redaction proof는 구현 DoD이고, CI/Examples/Nightly artifact audit은 exact implementation `HEAD` SHA에 연결된 read-only evidence가 있을 때만 적용한다. exact-head run/artifact가 없으면 workflow dispatch 없이 `N/A (no exact-head evidence; dispatch not authorized)`로 기록하며, broad artifact의 security PASS를 주장하지 않는다. workflow-hardening 후속 issue는 권고만 migration record에 남기고 별도 issue 생성 승인을 받는다.

행위별 승인 게이트는 다음과 같이 독립적이다.

| Action | Authority and boundary |
| --- | --- |
| local implementation, tests, spec/plan/review/lesson records | approved Issue #566 plan scope; no external side effect |
| PR creation/update | separate explicit user approval naming repository, base, and head; exact-head checks are re-read before creation |
| Nightly/Examples workflow dispatch | separate explicit approval; otherwise no dispatch and exact-head evidence is `N/A` |
| follow-up issue creation | separate explicit approval; record recommendation only until then |
| revert/rollback commit | separate explicit approval after stop trigger; do not auto-revert |
| merge | separate fresh merge approval after exact-head CI, review/thread, and mergeability revalidation; merge approval does not authorize release/tag/publication |
| release/tag/publication | separate release authority and preflight for each side effect, followed by its own explicit approval; never implied by plan or merge approval |

rollback authority/target은 `issue-566-main`이 마지막 green base `ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d`로 migration commit을 revert하거나 승인된 inverse patch를 적용하는 것이다. rollback 후 manifest 21/21, residual scan, `test=19/integrationTest=35/stressTest=1`, failures/errors/skips=0인 full command proof를 다시 남긴다.

CI/release impact matrix:

| Surface | Current command/check | Impact and gate |
| --- | --- | --- |
| PR CI | `CI / Build (compile only)` excludes `test`, `integrationTest`, and `stressTest`; `CI / CI Status` aggregates the compile job only. | No workflow edit. Record run ID/attempt, commit SHA, UTC timestamp, check URL, applicability and gate decision. CI compile green is not runtime/security evidence; local XML is canonical. |
| PR Examples | `Examples / Container Examples (sequential)` runs target `integrationTest` plus Kover and uploads `container-example-test-results` with nested report/CSS/JS directories (7-day retention). | No workflow edit. Record run/artifact IDs, SHA, timestamp, URLs and retention. Because this broad upload cannot be scanned pre-upload or target-extracted by the canonical scanner, it is metadata-only; any required security evidence is `PENDING` and cannot authorize security PASS. |
| Weekly/manual full Nightly | `Nightly / Test (Testcontainers)` runs target `integrationTest` and `stressTest` with `--max-workers=1`; job uses `-Xmx4g`, Docker socket, and existing workflow timeout policy; artifact `test-results` retention is 14 days with exact target XML/Kover inventory above. | No workflow edit or dispatch in this design. Audit only an existing run whose commit SHA exactly equals implementation `HEAD`; otherwise record `N/A (no exact-head evidence; dispatch not authorized)`. Post-upload audit is not a pre-upload secret-prevention gate. |
| Smoke CI/Nightly | `Smoke Test (no Testcontainers)` and `smoke-test-results` (7-day retention) cover only untagged smoke tests | Not a substitute for target integration/stress proof; record as N/A for this issue. |
| Package/publication/release | No package, Maven publication, tag, release, schema, or dependency mutation | Explicitly N/A; no release side effect is authorized by this design. |

### 5.4 변경 순서

1. 대상 21개 파일의 assertion import와 호출을 목록화하고 manifest와 path 집합을 비교한다. 구현 중 재현 명령은 `BASE=ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d; git diff --name-only "$BASE" -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin; git ls-files --others --exclude-standard -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin; git status --porcelain=v1 -z -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin`이며, test-source 범위의 tracked/staged/unstaged/untracked 집합이 manifest 21줄과 다르면 중단한다. 문서/record allowlist는 별도 path-filtered status로 확인한다. 최종 commit 후에만 `git diff --name-only "$BASE"...HEAD -- ...`를 보조 증거로 남긴다.
2. 이미 Bluetape matcher를 사용하는 인접 commerce 테스트와 resolved `bluetape4k-assertions:1.11.0` source/jar overload를 대조한다.
3. 파일별로 import를 정리하고 assertion 호출만 변환한다. mapping 밖 API는 즉시 PENDING record로 남긴다.
4. 각 변환 후 해당 테스트 클래스의 compile/test를 실행한다. 공통 compile 명령은 `./gradlew :commerce-usage-metering-billing-event-sourcing:compileTestKotlin --no-build-cache --max-workers=1`; unit class는 `./gradlew :commerce-usage-metering-billing-event-sourcing:test --tests '<FQCN>' --no-build-cache --max-workers=1`, tagged class는 동일한 명령에서 `test`를 `integrationTest` 또는 `stressTest`로 바꾼다. custom diagnostic message와 exception field의 before/after 의미를 확인한다.
5. `TenantIsolationIntegrationTest`와 `EventSourcingHttpIntegrationTest`를 먼저 `integrationTest`에서 검증해 security matrix를 고정한다.
6. Gradle task graph에 integration→stress hard edge가 없으므로 `set -euo pipefail` 셸에서 `clean`, `test`, `integrationTest`, `stressTest`를 별도 invocation으로 순서대로 실행한다. 각 invocation은 `--max-workers=1`이며 root `test-mutex` (`maxParallelUsages=1`)와 모듈 JUnit parallel-disabled 설정을 함께 확인한다. 각 task의 XML count/failure를 canonical parser로 기록하고 기대 count는 `test=19`, `integrationTest=35`, `stressTest=1`, failures/errors/skips는 모두 0이어야 한다.
7. `clean`, `test`, `integrationTest`, `stressTest` 각각에 외부 실행 supervisor의 15분 timeout을 적용한다. timeout/deadlock은 검증 실패로 분류하고 Gradle/JUnit 로그와 가능한 thread/container 상태를 수집한다. 테스트 소스의 timeout/retry/lifecycle semantics는 바꾸지 않는다.
8. 최종 evidence 문서 네 개(`$MIGRATION_RECORD`, `$SPEC_REVIEW_RECORD`, `$LESSON_RECORD`, applicable 시 `$CI_AUDIT_RECORD`)를 concrete path로 먼저 생성하고, `REDACTION_DOCS`를 그 colon-delimited 목록으로 설정한다. capture는 반드시 새 Bash 프로세스 `/bin/bash -Eeuo pipefail` 안에서 실행한다(zsh에서 `PIPESTATUS`를 해석하지 않는다). 그 Bash 프로세스에서 `/usr/bin/time -p`와 `set -o pipefail`을 사용해 comparable split baseline과 최종 invocation을 동일 방식으로 측정한다. `ISSUE566_LOG="$(mktemp -t issue-566-full-suite.XXXXXX)"`는 repo 밖에 만들고, 즉시 `cleanup_issue566_log() { local status=$?; trap - EXIT INT TERM; rm -f -- "$ISSUE566_LOG"; test ! -e "$ISSUE566_LOG" || status=2; exit "$status"; }; trap cleanup_issue566_log EXIT INT TERM`를 등록한다. raw log는 다음 함수로 네 Gradle invocation의 stdout/stderr를 모두 채집하며 Gradle와 `tee` 양쪽 exit status를 즉시 보존한다: `run_gradle() { local -a pipeline_status; if /usr/bin/time -p ./gradlew "$@" 2>&1 | tee -a "$ISSUE566_LOG"; then pipeline_status=("${PIPESTATUS[@]}"); else pipeline_status=("${PIPESTATUS[@]}"); fi; if (( pipeline_status[0] != 0 )); then return "${pipeline_status[0]}"; fi; if (( pipeline_status[1] != 0 )); then return "${pipeline_status[1]}"; fi; return 0; }; run_gradle :commerce-usage-metering-billing-event-sourcing:clean --no-build-cache --max-workers=1; run_gradle :commerce-usage-metering-billing-event-sourcing:test --no-build-cache --max-workers=1; run_gradle :commerce-usage-metering-billing-event-sourcing:integrationTest --no-build-cache --max-workers=1; run_gradle :commerce-usage-metering-billing-event-sourcing:stressTest --no-build-cache --max-workers=1`. 첫 scanner 결과를 `Redaction audit`에 append한 뒤 동일한 최종 문서 목록으로 scanner를 한 번 더 실행한다. 두 번째 scan의 exit 0, 모든 문서의 readable/non-empty, cleanup exit 0과 `! -e "$ISSUE566_LOG"`가 모두 충족될 때만 redaction PASS로 전이한다. scanner 성공/실패/interrupt 모두에서 raw log를 업로드/커밋하지 않는다. `real` 값·호스트/JDK·컨테이너 메모와 redacted failure 요약만 migration record에 기록한다. main lane `issue-566-main`이 owner이며 final split wall-clock이 `2 × B_split`을 초과하면 record status를 `PENDING`으로 두고 원인/후속 issue를 기록한다.
9. JUnit/Kotlin assertion 잔존 검색, diff 검토, Kotlin checklist를 수행한다.

성능 기준 정정: 초기 combined baseline `101.95초`와 그 산술값 `203.90초`는 context evidence일 뿐 최종 gate가 아니다. 구현 전·후 모두 동일한 네 split invocation으로 `B_split`과 final wall-clock을 수집하고, 검증 계약의 `2 × B_split`만 성능 gate로 사용한다.

## 6. 위험과 완화

### R1. matcher overload 또는 nullable smart cast 불일치

- 증상: 컴파일 시 generic/type inference 오류 또는 nullable receiver 오류.
- 완화: API mapping을 타입별로 적용하고, 문제 파일의 기존 변수 구조를 보존한 채 명시적 지역 변수/타입만 최소 추가한다. wrapper를 추가하지 않는다.

### R2. 비동기/컨테이너 테스트 동작 회귀

- 증상: assertion 변경과 무관해 보이는 timeout, port, database lifecycle 실패.
- 완화: 초기 combined context baseline과 별도로 comparable split baseline `B_split`을 확보하고, coroutine/Awaitility/Testcontainers 코드는 변경하지 않는다. `clean`·`test`·`integrationTest`·`stressTest`를 순서대로 실행하며 각 invocation의 15분 supervisor timeout을 넘기면 실패로 분류하고 로그/가능한 thread/container 상태를 남긴다. 클래스 단위 확인 뒤 동일 환경에서 전체 모듈을 split invocation으로 실행하고 실패 시 assertion diff와 lifecycle diff를 분리한다.
- `EventStoreDatabaseFixture`의 process-scoped `AutoCloseable` 소유권은 기존 테스트 계약으로 취급한다. migration은 fixture 생성/close 범위를 바꾸지 않으며, 각 tagged lane 뒤 Gradle task 종료·비어 있지 않은 JUnit XML·잔류 container/process 여부를 확인한다. 기존 process-scoped 정리가 관찰되면 N/A로 기록하고 별도 lifecycle refactor를 이 PR에 끌어들이지 않는다.

### R3. import 충돌 또는 잘못된 API 잔존

- 증상: JUnit `assertEquals`, `org.junit.jupiter.api.Assertions.assertThrows`, `kotlin.test.assert*`가 남거나 같은 이름의 다른 assertion이 자동 import된다.
- 완화: import를 명시적으로 정리하고 `org.junit.jupiter.api.Assertions` static assertion, `kotlin.test.assert*`, AssertJ/Kluent assertion, JUnit `assertThrows` 잔존을 대상 디렉터리에서 검색한다. JUnit annotation은 허용 목록으로 구분하고, 예외 assertion은 정확히 `io.bluetape4k.assertions.assertFailsWith`인지 확인한다.

### R4. assertion 의미가 단순 치환 과정에서 바뀜

- 증상: collection equality와 containment, exception type과 message 검증의 의미가 달라짐.
- 완화: 각 assertion을 expected/actual와 검증 목적에 맞춰 intent-specific matcher로 변환하고, 테스트 본문/fixture/예외 검증 범위를 변경하지 않는다.

## 7. 검증 계약

구현 완료를 주장하려면 다음을 모두 만족해야 한다.

1. 대상 21개 파일 외의 production/의존성 변경이 없다.
2. 대상 파일에서 JUnit/Kotlin assertion API가 제거되고 Bluetape assertion import가 의도에 맞게 사용된다.
3. 변경된 각 테스트 클래스의 compile/test가 통과한다. integration/stress 파일은 해당 tagged task에서도 통과해야 한다.
4. `set -euo pipefail; ./gradlew :commerce-usage-metering-billing-event-sourcing:clean --no-build-cache --max-workers=1; ./gradlew :commerce-usage-metering-billing-event-sourcing:test --no-build-cache --max-workers=1; ./gradlew :commerce-usage-metering-billing-event-sourcing:integrationTest --no-build-cache --max-workers=1; ./gradlew :commerce-usage-metering-billing-event-sourcing:stressTest --no-build-cache --max-workers=1` 순차 실행이 통과한다.
5. 네 invocation의 test count/failure, 각 15분 supervisor timeout 미초과, JUnit XML 생성, tagged lane 뒤 잔류 container/process 상태를 기록한다. 최종 count는 `19/35/1`, failures/errors/skips는 0이며 baseline과 다르면 DoD를 보류한다.
6. comparable split baseline `B_split`과 final split wall-clock을 동일한 네 invocation topology로 비교한다. final이 `2 × B_split` 이하이거나, 초과 시 원인이 assertion migration이 아님을 fresh evidence로 설명하고 별도 후속 조치를 기록한다. `B_split`이 없으면 DoD를 `PENDING`으로 둔다.
7. root `test-mutex`의 `maxParallelUsages=1`과 대상 모듈의 JUnit parallel-disabled 설정이 유지됨을 확인한다.
8. `git diff --check`가 통과한다.
9. `$bluetape-kotlin-patterns`의 테스트 및 final checklist 항목을 적용하고, 해당 없는 production/coroutine 항목은 근거와 함께 N/A로 기록한다.
10. 한국어 lesson에 이관 범위, API mapping, 검증 결과, 향후 assertion migration 시 주의점을 기록한다.

11. migration record가 고정 schema와 allowlist 경로에 따라 작성되고, resolved BOM/artifact(`1.3.1`/`1.11.0`), unmapped API, diagnostic-only message, security traceability, redaction, owner/status를 기록한다.
12. 21개 manifest path 집합과 실제 Kotlin test diff가 일치하고, security matrix의 tenant isolation/authorization/exception-field 결과가 tagged XML에 존재한다.
13. 세 lane의 XML/HTML와 raw timing evidence가 표의 exact path에 존재하고 readable/non-empty이며, local/CI artifact 이름·retention·migration record link가 기록된다. missing/unreadable/unredacted artifact는 fail closed 한다.
14. local redaction scan의 key/value exact allowed-pair match 이외 count가 0이고, credential leak/missing artifact/count drift/timeout/required-check failure/allowlist 밖 diff/P0/P1 발견 시 stop trigger가 기록된다. CI/Examples/Nightly artifact는 exact implementation `HEAD` SHA에 연결된 기존 run이 있을 때만 read-only post-upload audit하며, unredacted이면 security PASS가 아니라 `PENDING`과 workflow-hardening 권고다. exact-head run이 없으면 dispatch 없이 `N/A`로 기록하고 후속 issue는 별도 승인 전 생성하지 않는다.
15. RACI에 따라 owner/status transition, escalation cadence, rollback target/authority와 post-revert full proof가 migration record에 기록된다.
16. 모든 CI surface는 implementation `HEAD` SHA와 exact-match인 기존 run이 있을 때만 read-only conclusion을 정확한 run/SHA/URL로 기록한다. `CI / Build (compile only)`, `CI / CI Status`, `Examples / Container Examples (sequential)` 및 `Nightly / Test (Testcontainers)`에 exact-head run/artifact가 없으면 `N/A (no exact-head evidence; dispatch not authorized)`로 남긴다. package/publication/tag/release side effect가 없음을 확인한다.

lesson은 `docs/lessons/YYYY-MM-DD-issue-566-event-sourcing-assertions.md`에 작성하고 `Context / Decision / Outcome / Evidence / Misses / Future guard` 필드를 포함한다. lesson에는 secret/header/body를 원문으로 복사하지 않는다.

추가 traceability 검증:

- 구현 중 `git diff --name-only ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin`, `git ls-files --others --exclude-standard -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin`, `git status --porcelain=v1 -z -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin`의 test-source tracked/staged/unstaged/untracked 집합이 위 21개 manifest와 정확히 일치한다. 문서/record allowlist는 별도 path-filtered status로 확인한다. 최종 commit 후에만 `git diff --name-only ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d...HEAD -- ...`를 보조 증거로 남긴다.
- diff path가 allowlist 밖이면 `PENDING`으로 멈추고 해당 변경을 별도 승인 없이 포함하지 않는다.
- `TenantIsolationIntegrationTest`의 기존 tenant-scoped `Owned` 결과와 `EventSourcingHttpIntegrationTest`의 tenant-a/operator authorization 결과를 integration XML의 named testcase에서 직접 확인한다. 새 cross-tenant receipt-ID/tenant-b HTTP negative invariant는 이 PR의 DoD로 주장하지 않는다.
- `shouldBeEqualTo`가 지원하지 않는 diagnostic-only message를 삭제한 경우 migration record에 파일/라인/원문 의미를 남기고, behavior assertion을 별도로 보존했는지 확인한다.

실패한 검증은 숨기지 않고 DoD에 `PENDING` 또는 `BLOCKED`로 남긴다. 테스트 인프라의 일시적 실패는 재현 횟수와 로그 증거를 함께 기록한 뒤, 코드 변경과 분리해 판단한다.

## 8. 대안 검토

### 대안 A: 공통 assertion wrapper

거부한다. 이 issue의 목적은 Bluetape matcher를 테스트 코드에 직접 적용해 intent를 드러내는 것이며, wrapper는 API 의미와 실패 위치를 숨긴다.

### 대안 B: 여러 branch/PR로 패키지 분할

거부한다. 내부 검토는 패키지 순서로 나눌 수 있지만, 모듈 전체의 assertion contract를 하나의 독립적으로 검증 가능한 변경으로 제공하는 편이 issue 범위와 CI 증거를 명확하게 한다.

## 9. 롤백

모든 변경은 테스트 파일의 import와 assertion 표현에 한정한다. 검증 불합격 시 failing file을 조용히 제외하지 않는다. partial state는 `PENDING`으로 유지하고, 원인 기록 후 마지막 green commit을 기준으로 migration commit을 `git revert`하거나 승인된 inverse patch를 적용해 전체 manifest를 다시 검증한다. production/runtime state나 외부 데이터는 변경하지 않는다.

## 10. 설계 승인 기준

- [x] issue 범위와 baseline 근거가 명시되었다.
- [x] 권장안과 거부한 대안의 이유가 명시되었다.
- [x] API mapping과 불변 조건이 명시되었다.
- [x] async/container/import/의미 보존 위험과 완화책이 명시되었다.
- [x] 구현·검증·lesson의 완료 기준이 명시되었다.
- [x] six-lens 설계 review 완료 (`docs/review/2026-08-05-issue-566-event-sourcing-assertions-spec-review.md`)
- [ ] 구현 plan review 및 사용자 plan 승인

# Issue #566 Event-Sourcing Assertion Migration Record

## Context

Issue #566은 usage-metering event-sourcing 예제의 테스트 assertion을
`bluetape4k-assertions`의 intent-specific matcher로 통일하는 Type A 유지보수
작업이다. 승인된 설계와 plan의 범위만 적용했으며 production source,
fixture lifecycle, coroutine timing, dependency, workflow, credential, schema는
변경하지 않았다.

## Base commit

- Repository: `bluetape4k-workshop`
- Branch: `refactor/issue-566-event-sourcing-assertions`
- Base: `ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d`
- Issue: [#566](https://github.com/bluetape4k/bluetape4k-workshop/issues/566)
- Design hash: `4c75ea854fbaa07acd4ad61c92fc231cc9901e9070aaf3fe8a76adc107a33ee2`

## Manifest

승인된 product manifest는 다음 21개 test source로 고정했다.

1. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/BillingEventSourcingStressTest.kt`
2. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/EventSourcingRuntimeContractTest.kt`
3. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/KotlinPatternArchitectureTest.kt`
4. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/TenantIsolationIntegrationTest.kt`
5. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/CommandServicePostgresIntegrationTest.kt`
6. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/CorrectionReconciliationIntegrationTest.kt`
7. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/DomainEventJsonCodecTest.kt`
8. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/AggregateReducerTest.kt`
9. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/EventContractTest.kt`
10. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/AggregateReplayTest.kt`
11. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/CanonicalEventHashTest.kt`
12. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/EventCodecRegistryTest.kt`
13. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandFingerprintTest.kt`
14. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandReceiptPostgresIntegrationTest.kt`
15. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/EventStorePostgresIntegrationTest.kt`
16. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/RepositoryArchitectureTest.kt`
17. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/SnapshotPostgresIntegrationTest.kt`
18. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionCoordinatorPostgresIntegrationTest.kt`
19. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionGenerationTest.kt`
20. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionRecoveryPostgresIntegrationTest.kt`
21. `commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/web/EventSourcingHttpIntegrationTest.kt`

`git diff --name-only`와 manifest 비교 결과 product source 집합은 21/21이며,
추가 test source나 untracked source는 없다.

## Resolved artifact

`bluetape4k-dependencies:1.3.1`이 해석하는
`bluetape4k-assertions:1.11.0` API를 사용했다. 변환 규칙은 다음과 같다.

- equality/inequality: `shouldBeEqualTo`, `shouldNotBeEqualTo`
- boolean/collection/nullability: `shouldBeTrue`, `shouldBeFalse`,
  `shouldBeEmpty`, `shouldBeNull`, `shouldNotBeNull`, `shouldNotBeBlank`
- type/containment/order: `shouldBeInstanceOf`, `shouldContain`,
  `shouldNotContain`, `shouldBeGreaterThan`
- exception type: `io.bluetape4k.assertions.assertFailsWith<T>`, exception field는
  별도 matcher로 유지

## Unmapped API table

| API | Status | Decision |
| --- | --- | --- |
| none | PASS | 승인된 mapping으로 21개 파일의 기존 assertion API를 모두 대체했다. |

## Diagnostic-only message table

| Source | 이전 diagnostic message | 처리 |
| --- | --- | --- |
| `KotlinPatternArchitectureTest` | `violations.joinToString("\\n")` | `shouldBeEmpty()`로 emptiness 의미를 유지했다. 상세 문자열은 실패 시 부가 진단일 뿐이므로 삭제했다. |
| `EventSourcingHttpIntegrationTest` | actuator status assertion의 response body | status와 body 필드 assertion을 분리해 보존하고, 중복 diagnostic 인자는 삭제했다. |
| `EventSourcingHttpIntegrationTest` | operator health body를 boolean assertion message로 전달 | `shouldContain("projectionPosition")`로 body 의미를 직접 검증해 message 인자를 삭제했다. |

기능 assertion, 예외 type/message, tenant 경계, projection 상태, HTTP status/body
검증은 삭제하지 않았다.

## Security traceability

- 구현/문서 diff에는 production secret, local credential, authorization header,
  인증 토큰, credential-bearing JDBC endpoint를 저장하지 않았다.
- 테스트 실행 raw log는 `/tmp/issue566-final-raw.log`에만 보관하고 scanner 완료
  후 삭제한다. repository에는 log, generated report, binary metadata를 추가하지
  않는다.
- local scanner는 raw log, 세 lane의 direct XML/binary metadata, nested HTML
  report, 이 migration record, spec review, lesson을 fail-closed로 검사한다.
- CI/Nightly exact-head artifact는 아직 생성하거나 dispatch하지 않았다.
  따라서 CI security evidence는 `N/A (no exact-head evidence; dispatch not
  authorized)`이며, broad artifact의 security PASS를 주장하지 않는다.

## Test evidence

모든 invocation은 `/bin/bash -Eeuo pipefail`, 외부 15분 supervisor,
`--no-build-cache --max-workers=1` 조건으로 같은 순서(`clean`, `test`,
`integrationTest`, `stressTest`)에서 실행했다.

| Evidence | Result |
| --- | --- |
| `compileTestKotlin` | exit 0 |
| `detekt` | exit 0 |
| `test` | 19 tests, failures/errors/skipped 0 |
| `integrationTest` | 35 tests, failures/errors/skipped 0 |
| `stressTest` | 1 test, failures/errors/skipped 0 |
| residual JUnit/Kotlin assertion scan | 21 target files, forbidden matches 0 |
| `git diff --check` | PASS |
| pre-implementation `B_split` | 119.450s |
| final `B_final_split` | 99.920s |
| performance gate (`B_final_split <= 2 * B_split`) | PASS; 99.920s <= 238.900s |

최종 XML inventory는 `test=19`, `integrationTest=35`, `stressTest=1`이며
각 lane의 failures/errors/skipped는 모두 0이다. 최종 실행의 report/XML
경로는 scanner가 직접 확인한다.

## Redaction audit

- 2026-08-05 local first scan: exit 0, `matches=12`,
  `allowed-pair placeholders=12`, `unexpected=0`, `ci_artifact_applicable=false`.
  세 lane XML count drift, missing/extra/symlink artifact, unreadable input은
  없었다.
- Scanner regression fixtures: 8 malicious fixtures all rejected (exit 1), 6
  exact test-only pairs all accepted (exit 0).
- 2026-08-05 local second scan: exit 0, `matches=12`,
  `allowed-pair placeholders=12`, `unexpected=0`,
  `ci_artifact_applicable=false`; first scan과 동일한 결과였다.
- second scan 뒤 `/tmp/issue566-final-raw.log`를 삭제했고
  `test ! -e /tmp/issue566-final-raw.log` 확인 결과 `RAW_LOG_CLEANUP=PASS`다.

현재 상태: `PASS` — 두 scanner 실행, fixture 회귀, XML/report preflight,
unexpected match 0, raw-log cleanup을 모두 완료했다.

## Owner

`issue-566-main`이 구현, evidence 수집, scanner 실행, record 갱신의 A/R
owner다. native review lane은 read-only C 역할이며 외부 CI/PR 권한은 갖지
않는다.

## Status

`PASS (local implementation)` — 로컬 구현, 테스트, 정적 분석, 성능,
redaction evidence가 승인된 범위에서 완료됐다. PR 생성, workflow dispatch,
follow-up issue, merge, release는 별도 승인 없이는 수행하지 않는다.

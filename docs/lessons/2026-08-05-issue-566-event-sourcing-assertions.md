# Issue #566 Event-Sourcing Assertion Migration Lesson

## Context

event-sourcing 예제 21개 테스트가 JUnit/Kotlin assertion과 diagnostic 인자를
혼용하고 있었다. 목표는 테스트가 검증하는 도메인 의도를 바꾸지 않고
bluetape4k assertion vocabulary로 통일하는 것이었다.

## Decision

승인된 manifest 안에서만 assertion import와 호출을 바꾸고, nullable 결과는
`shouldNotBeNull()` 반환값으로 최소 범위에서 좁혔다. 예외 검증은
`assertFailsWith<T>`로 옮겼으며 fixture, coroutine, retry, timeout, workflow와
dependency는 건드리지 않았다. 비교 가능한 split baseline과 final split을
같은 Gradle 순서로 측정해 성능 판정을 별도로 남겼다.

## Outcome

21개 파일의 기존 assertion을 intent-specific matcher로 전환했다. 단위 19개,
통합 35개, stress 1개가 모두 실패·오류·skip 없이 통과했고, detekt와
`compileTestKotlin`도 통과했다. final split은 99.920초로 baseline 119.450초의
두 배 한계 안에 있다.

## Evidence

- design/review/plan의 고정 manifest: 21/21
- forbidden JUnit/Kotlin assertion residual: 0
- `git diff --check`: PASS
- local XML/report inventory와 redaction scanner: migration record에 기록
- exact-head CI/Nightly 실행: 승인 전이므로 N/A

## Misses

`shouldBeEmpty()`와 intent matcher는 기존 assertion의 custom message overload를
그대로 보존하지 않는다. 이번 범위에서는 message가 기능 검증이 아니라 실패
진단이므로 삭제했지만, 운영 진단이 필요한 경우에는 별도 diagnostic assertion
또는 구조화된 failure data를 설계해야 한다. PR head에 대한 원격 CI artifact도
아직 없다.

## Future guard

새 assertion migration은 먼저 고정 manifest와 API mapping을 만들고, nullable
narrowing 반환값과 diagnostic-only drop을 review record에 명시한다. target
module의 `compileTestKotlin`, detekt, 세 lane XML count, residual scan,
comparable split timing을 함께 실행하며, raw log와 generated report는 commit
전에 fail-closed redaction scanner로 검사한다.

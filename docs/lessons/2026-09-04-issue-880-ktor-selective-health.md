# Issue #880 Ktor Exposed backend-selective health/readiness

## Context

기존 `ktor/exposed-rest`는 `bluetape4k-exposed-ktor` legacy aggregator 하나에
health/readiness, JDBC transaction, error mapping을 모두 의존했다. 2.0.0에서는
backend-neutral core와 JDBC/R2DBC/cache adapter가 분리되었으므로, JDBC만 사용하는
consumer가 불필요한 backend를 끌어오지 않는 예제가 필요했다.

## Decision or Finding

- 예제의 직접 의존성을 `bluetape4k-exposed-ktor-core`와
  `bluetape4k-exposed-ktor-jdbc`로 나누고 두 alias 모두 root
  `bluetape4k-dependencies` BOM에서 버전을 해석한다.
- core가 고정 error catalog, redaction, monotonic readiness deadline과 route를
  소유하고, JDBC adapter가 caller-owned dispatcher의 `runInterruptible`
  readiness와 Exposed transaction을 소유한다.
- R2DBC와 cache alias는 catalog에 등록하지만 이 JDBC consumer의 classpath에는
  추가하지 않는다. 해당 backend를 선택하는 consumer만 adapter를 활성화한다.
- legacy `bluetape4k-exposed-ktor` aggregator는 호환성 경로로 문서에 남기되 신규
  consumer 코드는 필요한 backend 표면만 선택한다.

## Outcome

`ktor/exposed-rest`는 기존 PostgreSQL CRUD/rollback/error/cancellation 동작을
유지하면서 선택형 health/readiness 구성을 보여준다. core-only fake probe의 단일
deadline과 고정 오류 응답 redaction 테스트를 추가했고, README 양쪽 언어, root
module map, coverage matrix, workflow/stale guard, review를 같은 계약에 맞췄다.

## Verification

- `./gradlew :ktor-exposed-rest:test --no-daemon --max-workers=1`: 8 tests passed
- selective core readiness/error tests: 2 tests passed
- `./gradlew :ktor-exposed-rest:build -x test --no-daemon`: successful
- root detekt, README parity/language, stale-check, workflow actionlint, JSON
  validation, `git diff --check`: all passed
- dependency graph confirms direct core + JDBC aliases only; R2DBC/cache are
  optional and absent from this consumer

## Future Guidance

Ktor Exposed consumer를 추가할 때는 route와 backend transaction을 한 aggregator에
묶지 말고 core 계약과 실제 backend adapter를 분리한다. readiness probe는 caller가
소유한 dispatcher/resource를 사용하고, 전체 probe 목록에 하나의 monotonic budget을
적용하며, raw SQL/URL/user/password를 오류 응답에 포함하지 않는다.

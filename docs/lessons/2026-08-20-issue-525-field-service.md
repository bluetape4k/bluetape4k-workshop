# Issue #525 Field Service Dispatch 독립 예제의 경계

## Context

Issue #525는 Home Care와 Field Service Routing reference application을 요구했지만,
실제 provider tenant와 production map 데이터는 이 workshop의 검증 범위가 아니었다.
이번 구현은 #524 planning contract와 같은 구현을 공유하지 않는 독립적인 Field Service
예제로 진행하면서, synthetic 방문·worker와 PostgreSQL 권위 상태를 사용한다.

## Decision or Finding

- `optimization/field-service-dispatch`는 `settings.gradle.kts`의 자동 등록 규칙을
  사용하고 `:optimization-planning-contracts` 구현에는 의존하지 않는다.
- 기본 실행은 deterministic planning fixture다. callback fixture와 benchmark 결과를
  live Timefold tenant, provider entitlement, 또는 실제 route quality의 증거로
  승격하지 않는다.
- planning proposal과 committed dispatch를 분리한다. approval은 proposal 상태만
  변경하고, worker route confirmation은 현재 worker·visit version과
  `workerScheduleRevision`을 다시 확인한 뒤 route 전체를 원자적으로 commit한다.
- local callback envelope와 #524 wire contract를 별도 type으로 유지한다. callback의
  stale revision, request generation, signature, digest 검증은 local state를 바꾸기
  전에 수행한다.
- PostgreSQL/Testcontainers 모듈은 optimization full smoke와 Examples container job에
  등록하고 `all-smoke`에는 중복 등록하지 않는다. 새 모듈은 README 양쪽, workflow path와
  artifact, optimization smoke, stale-check 필수 파일 목록을 함께 갱신해야 한다.

## Outcome

deterministic planner, PostgreSQL schema/repository, event/outbox replay, callback seam,
REST 경계, redacted static console, benchmark contract를 독립 모듈 아래에 배치했다.
README는 synthetic-only 흐름과 demo loopback/operator guard를 설명하며 credential,
address, PHI 예제를 포함하지 않는다. schema는 `SchemaUtils`로 만드는 disposable
fixture이고 production migration은 추가하지 않았다.

## Verification

- `./gradlew :optimization-field-service-dispatch:cleanTest --no-build-cache :optimization-field-service-dispatch:test --max-workers=1` — 전체 51 tests passed.
- `./gradlew :optimization-field-service-dispatch:build --max-workers=1` — `BUILD SUCCESSFUL`.
- chunked mutation body가 256 KiB 경계를 넘을 때 `BODY_TOO_LARGE` 413 응답을 반환하는 회귀 테스트 통과.
- `./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceCallbackEnvelopeTest' --tests '*FieldServiceBenchmarkContractTest' --max-workers=1` — 7 tests passed.
- `./gradlew projects --console=plain` — `:optimization-field-service-dispatch`가 자동 등록됨.
- `bash scripts/smoke-validate.sh stale-check` — required module files, stale refs, broken image links 통과.
- `bash scripts/smoke-validate.sh optimization` — 두 optimization test task가 포함된 경로가 `BUILD SUCCESSFUL`로 완료됨(당시 Gradle test task는 cache hit).
- `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml` — 통과.
- `git diff --check` — 통과.
- 변경한 Field Service README pair — language switch, heading/code-fence parity, Korean terminology audit 통과.
- 저장소 전체 `validate-readme-parity.mjs`에는 기존 `aws/kinesis-coroutines` README language switch 누락이 남아 있다. 이 lane에서는 unrelated 문서를 수정하지 않았다.

## Future Guidance

새 독립 optimization 예제를 추가할 때는 먼저 provider 증거와 deterministic fixture의
경계를 문서화한다. proposal approval과 committed route confirmation을 같은 상태나
같은 row에 덮어쓰지 말고, route 전체 CAS와 schedule revision을 유지한다. 새
Testcontainers 모듈은 `settings.gradle.kts` 자동 등록만으로 완료된 것으로 보지 말고,
README locale pair, Examples path/task/artifact, group smoke, stale-check, full-nightly
경로를 함께 확인한다. 실제 Timefold provider나 map integration을 추가할 때는 별도
authority, credential 보관, 비용·정리 절차, production 인증 경계를 새 계획으로 승인받는다.

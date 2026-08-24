# #526 Shift Coverage 구현 계획 preflight 검토

- 검토일: 2026-08-24
- 대상 계획: `docs/superpowers/plans/2026-08-24-issue-526-shift-coverage.md`
- 계획 SHA-256: `7f933db7c25b3fddb5d856372f31fe753ea32d6a0fa2f8410b1d9482aa06a196`
- 기준 설계: `docs/superpowers/specs/2026-08-24-issue-526-shift-coverage-design.md`
- 설계 SHA-256: `460a0c38051077679a8f29dceb01a41195cfb315d35c0e74f41d688b95b842e3`
- 근거 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/526
- 검토 범위: 구현 전 계획의 요구사항 추적성, Bluetape 재사용, TDD 순서, PostgreSQL
  권위, Java 25 lifecycle, HTTP 보안, 운영 복구, 문서·CI 등록

## 검토 원장

| 근거 | 사용 목적 | 확인 결과 |
|---|---|---|
| 승인 설계와 live Issue #526 | acceptance 1–12, 비목표, route·state·failure 계약 | 계획 Task 1–10과 traceability 표가 모든 요구를 연결한다. |
| GNO 전역 query 및 보존된 Timefold 연구 | provider/custom Solver 경계, PostgreSQL 권위, fake boundary | live provider credential 없이 normalized ABI와 deterministic fake를 유지한다. |
| `$bluetape-kotlin-patterns` 및 references | Kotlin/Exposed/Spring/JUnit/virtual-thread 규칙 | `require*`, `Uuid.V7`, `Base58`, UUID Exposed repository, Bluetape assertions/testers, KLogging, cancellation/IO 경계를 매트릭스와 Task에 반영했다. |
| root `build.gradle.kts`와 기존 `optimization` 모듈 | BOM, Java 25, test serialization, build/task 등록 | `bluetape4k-dependencies` BOM, root `test-mutex` BuildService, existing repository/fixture/API 패턴을 재사용한다. |
| resolved `bluetape4k-testcontainers:1.11.0`/`bluetape4k-junit5:1.11.0` | 공개 artifact capability 확인 | artifact에 module helper는 없지만 root Gradle BuildService가 표준 `test` task를 직렬화한다. 미확인 module helper는 추가하지 않는다. |

## 독립 관점 검토

| Priority | Lens | 결과·근거 | 필요한 수정 |
|---|---|---|---|
| P0/P1 | Performance / Exposed | PASS. UUID PK와 `AuditableUUIDTable`/`UUIDAuditableJdbcRepository`가 기존 `PlanningRequestRepository`와 일치한다. `PlannerClock`/`StepBudget`, 50,000 후보·5초 경계, fixed 4/queue 8, canonical lock tuple, named deadlock/timeout test, outbox queue saturation을 RED/GREEN 명령까지 추적했다. | 없음 |
| P0/P1 | Stability | PASS. `DELIVERY_UNKNOWN`은 definitive lookup 전 redrive하지 않고 `NOT_FOUND` 후에만 manager redrive한다. `RETRY_EXHAUSTED` inbox는 별도 manager-only requeue, stored digest/new request ID/audit/no-write를 가진다. root `test-mutex`와 PENDING 규칙도 명시됐다. | 없음 |
| P0/P1 | Security | PASS. HMAC length-prefixed `schemaVersion`/`generationId` context, exact signature headers, preflight no-write, principal-scoped fingerprint schema/restart, 400/401/403/404 negative matrix, loopback/CORS/log redaction canary가 plan/design에 정합하다. | 없음 |
| P0/P1 | Operator/Ops | PASS. DB clock/lock·statement timeout, bounded audit, lease/effect fencing, readiness/liveness, operator reason/request ID, diagnostics/PENDING, smoke·workflow·ABI 검증과 rollback boundary가 명시됐다. | 없음 |
| P0/P1 | Developer/API | PASS. module은 BOM-only consumer이고 `planning-contracts` implementation dependency가 없다. full route/header/error matrix, closed DTO, stable DTO/error ABI, exact RED command, test task 및 custom integration serialization 규칙이 있다. | 없음 |
| P0/P1 | User/caller | PASS. manager/worker subject mapping과 allowlist, same-key cross-principal no-write, stale 409, 413/429/202 nextAction, browser keyboard state, redacted read model이 고정됐다. | 없음 |
| P0/P1 | Main integration | PASS. Task 0 preflight review가 Task 1 전에 위치하고 Task 10은 구현 후 delta review로 분리됐다. Task 1–10과 acceptance 1–12, stop condition, workflow lane이 서로 모순되지 않는다. | 없음 |

## TDD 및 실행 게이트

- Task 5–8의 named behavior가 각 Task의 RED command에 포함된다. 예상된 missing
  project/class/compilation failure를 먼저 관찰하고 최소 구현 후 동일 command를 GREEN으로
  재실행한다.
- Docker/Colima 또는 live HTTP가 unavailable이면 로그/report를 보존하고 해당 evidence를
  `PENDING`으로 남긴다. skipped container를 PASS로 변환하지 않는다.
- 구현을 시작하기 전 계획 검토의 최종 판정은 **PASS**이며, P0=0, P1=0, P2=0, P3=0이다.
- 구현/Testcontainers 실행 증거는 이 artifact의 범위 밖이며 Task 10에서 fresh evidence로
  수집한다.

## Gate result

- **PASS — Task 0 preflight review complete**
- P0: `0`
- P1: `0`
- P2: `0`
- P3: `0`
- Task 1 구현 착수 조건: 충족

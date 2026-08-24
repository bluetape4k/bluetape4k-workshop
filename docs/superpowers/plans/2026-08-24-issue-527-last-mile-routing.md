# Issue #527 Last-Mile Routing 구현 계획

> **실행자 안내:** 이 계획은 승인된 설계를 작은 RED/GREEN 단위로 실행한다. 계획
> review가 P0/P1=0인지 확인하기 전에는 Task 1 구현을 시작하지 않는다.

**Goal:** `optimization/last-mile-routing`에 synthetic pickup·delivery routing
참조 애플리케이션을 추가하고, 고정 travel matrix, PostgreSQL 권위 상태,
provider-neutral callback, deterministic hard constraint, 안전한 browser
projection을 재현 가능한 테스트로 증명한다.

**Architecture:** 새 Spring MVC 애플리케이션이 domain, planner, Exposed
persistence, provider port, event/inbox/outbox lifecycle, HTTP와 redacted
browser read model을 소유한다. #524 구현 모듈은 의존하지 않고, 실제 provider는
고정 deterministic provider 뒤의 normalized port로만 향후 추가한다. proposal과
committed route를 분리하며 approval은 plan/job/carrier/matrix version을
transaction 안에서 재검증한다.

**Tech Stack:** Kotlin 2.4, Java 25, Spring Boot 4.0.6 MVC/Validation,
Exposed 1.4 계열, PostgreSQL/Testcontainers, Jackson 3, Bluetape BOM·logging·
id generator·virtual-thread runtime, JUnit 5, Kluent/MockK, Micrometer/Actuator.

## 실행 전 계약과 공통 규칙

- 작업 디렉터리: `.worktrees/feat-issue-527-last-mile-routing`.
- 새 dependency와 개별 Bluetape version pin을 추가하지 않는다. root BOM만 사용한다.
- Java 25 toolchain과 virtual-thread lifecycle을 사용하고 JDK 21 fallback을 만들지 않는다.
- Testcontainers는 `TestMutexService`와 `--max-workers=1`로 직렬 실행한다.
- `optimization` 모듈은 기존 정책상 local `detekt` task가 없다. root
  `./gradlew detekt`를 repository gate로 실행하고, module-local task는 `N/A`로
  기록한다. 이 정책을 바꾸는 것은 별도 범위다.
- raw provider payload, address, customer, secret, unbounded provider string을
  domain/log/metric/SSE/browser에 넣지 않는다.
- HTTP는 loopback synthetic demo이며 production auth/CSRF/tenant 경계를 약속하지 않는다.
- 모든 Task는 이전 Task의 파일과 검증을 전제로 하며 실패 시 해당 RED 단계로 돌아간다.

## 변경 파일 지도

### 모듈 골격과 문서

- Create: `optimization/last-mile-routing/build.gradle.kts`
- Create: `optimization/last-mile-routing/README.md`
- Create: `optimization/last-mile-routing/README.ko.md`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/LastMileRoutingApplication.kt`
- Create: `optimization/last-mile-routing/src/main/resources/application.yml`
- Create: `optimization/last-mile-routing/src/main/resources/logback-spring.xml`
- Create: `optimization/last-mile-routing/src/test/resources/junit-platform.properties`
- Create: `optimization/last-mile-routing/src/test/resources/logback-test.xml`

### Domain/planner

- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/domain/LastMileIds.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/domain/LastMileModels.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/domain/LastMileEvents.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/domain/LastMileErrors.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/planner/TravelTimeMatrix.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/planner/DeterministicLastMilePlanner.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/planner/LastMileFixtureData.kt`
- Create: matching unit/property/complexity tests under `optimization/last-mile-routing/src/test/kotlin/io/bluetape4k/workshop/optimization/lastmile/planner`

### Persistence/lifecycle/provider

- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/persistence/LastMileTables.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/persistence/LastMileRepositories.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/persistence/LastMileDatabaseInitializer.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/application/LastMileReplanService.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/application/LastMileApprovalService.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/application/LastMileEventService.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/application/LastMileCallbackService.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/application/LastMileOutboxWorker.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/application/LastMileLifecycle.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/provider/RoutingProvider.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/provider/DeterministicRoutingProvider.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/provider/RoutingCallbackCanonicalizer.kt`
- Create: matching repository/CAS/callback/lifecycle tests under `optimization/last-mile-routing/src/test/kotlin/io/bluetape4k/workshop/optimization/lastmile`

### HTTP/browser/운영 등록

- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/adapter/http/LastMileRoutingController.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/adapter/http/LastMileRoutingDtos.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/adapter/http/LastMileRoutingExceptionHandler.kt`
- Create: `optimization/last-mile-routing/src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/adapter/http/LastMileConsoleController.kt`
- Create: `optimization/last-mile-routing/src/main/resources/static/last-mile-routing/index.html`
- Create: `optimization/last-mile-routing/src/main/resources/static/last-mile-routing/app.js`
- Create: matching MVC/browser/observability tests under `optimization/last-mile-routing/src/test/kotlin/io/bluetape4k/workshop/optimization/lastmile`
- Modify: `optimization/README.md`, `optimization/README.ko.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh` and its optimization/stale validation entries
- Modify: validation matrix or stale-check source only where the current script requires it

`settings.gradle.kts`는 auto-registration을 사용하므로 직접 수정하지 않는다. Task 1의
`./gradlew projects`가 이를 증명한다.

## Task 0: 설계·계획 gate와 baseline을 고정한다

**Files:** 설계, 설계 review, 이 계획, 계획 review, workflow receipt만.

- [ ] 설계 review의 six lanes와 main integration에서 P0/P1=0을 확인한다.
- [ ] `git status --short --branch`가 clean이며 base가 `origin/develop`인지 확인한다.
- [ ] `./gradlew :optimization-field-service-dispatch:test --max-workers=1 --console=plain`을 실행한다.
- [ ] GNO 결과는 historical evidence로만 보관하고 Issue #527/현재 tree를 live `gh`로 재확인한다.
- [ ] 계획 review가 PASS이기 전에는 모듈 파일을 생성하지 않는다.

## Task 1: 모듈 골격과 검증 RED를 만든다

**Files:** build.gradle, application/config/test resources, module README, initial tests.

- [ ] `optimization/last-mile-routing`의 BOM-only build와 Java 25 Spring MVC
  main class를 만든다. `project(":optimization-planning-contracts")`는 넣지 않는다.
- [ ] `LastMileRoutingModuleContractTest`를 먼저 작성한다. `projects` 등록, package
  prefix, BOM alias, absent module detekt 정책을 문서화한다.
- [ ] module README/README.ko에 synthetic-only, fixed matrix, provider-neutral
  boundary, loopback/auth 비목표, test commands를 같은 목차로 기록한다.
- [ ] RED: 파일/태스크가 없어 compile 또는 contract assertion이 실패하는 것을 확인한다.
- [ ] GREEN 후 `./gradlew projects`와 module compile을 실행한다.

## Task 2: 식별자, 입력 상한, canonical event를 TDD로 만든다

**Files:** `domain/LastMileIds.kt`, `domain/LastMileEvents.kt`, `domain/LastMileErrors.kt`와 tests.

- [ ] value/sealed type으로 job, pickup/delivery, vehicle, driver, route, plan,
  matrix/provider/carrier version, event, request generation을 정의한다.
- [ ] 길이·문자 집합·양수/유한 수치·time window 순서를 생성 시 검증한다.
- [ ] return request, pickup-window change, driver check-in, vehicle breakdown,
  carrier cancellation, no-show, traffic-duration update를 canonical event로 만든다.
- [ ] 동일 aggregate/event key의 동일 digest는 idempotent, 다른 digest는
  `DIGEST_CONFLICT`, 과대 입력은 `400` 계약으로 테스트한다.
- [ ] 테스트: `LastMileIdsTest`, `LastMileEventCanonicalizerTest`.

## Task 3: fixed matrix와 deterministic planner를 구현한다

**Files:** `planner/TravelTimeMatrix.kt`, `planner/DeterministicLastMilePlanner.kt`,
fixture와 planner tests.

- [ ] matrix revision, sorted coordinate set, finite non-negative edge, O(1) lookup,
  duplicate/unknown/missing edge 검증을 구현한다.
- [ ] planner 입력 상한을 worker/job/stop/edge별로 고정하고 deterministic sort를
  `priority -> window start -> job id -> phase`로 적용한다.
- [ ] pickup-before-delivery, capacity, time window, skill, started/manual pin을
  hard constraint로 적용한다. violation은 `MISSING_SKILL`, `CAPACITY`,
  `TIME_WINDOW`, `MATRIX_MISS`, `PIN_CONFLICT` 등의 구조화 reason으로 반환한다.
- [ ] score는 finite numeric summary만 만들고 provider 설명 문자열을 받지 않는다.
- [ ] RED/GREEN tests:
  `fixed matrix produces same proposal and revision`,
  `pickup always precedes delivery`,
  `capacity and time window violations become unassigned`,
  `missing skill is explicit`,
  `started stop remains pinned`,
  `matrix miss never silently falls back`,
  `planner stays within max envelope`.

## Task 4: Exposed schema와 PostgreSQL CAS를 구현한다

**Files:** `persistence/LastMileTables.kt`, repositories, initializer와 tests.

- [ ] driver/vehicle/job/matrix revision+edge/plan proposal+stop/unassigned/
  committed stop/events/callback inbox/outbox/audit table을 정의한다.
- [ ] `(provider,event_id)` unique, payload digest lookup, `(job_id)` committed
  unique, `(plan_id,plan_revision)` history, bounded status/next-attempt index를 둔다.
- [ ] repository API는 create/read/keyset/claim/mark/CAS를 노출하고 stable id ordering을
  사용한다. update WHERE에 expected job/carrier/plan version을 포함한다.
- [ ] approval transaction은 lock order를 지키며 한 차량 묶음의 partial commit을
  허용하지 않는다. `SchemaUtils.create`는 disposable Testcontainers DB에서만 쓴다.
- [ ] RED/GREEN tests: `LastMileRepositoryTest`, `LastMileCasIntegrationTest`,
  duplicate/digest/rollback/unique/index assertions.

## Task 5: normalized provider port와 callback inbox/outbox를 연결한다

**Files:** `provider/*`, `application/LastMileReplanService.kt`,
`LastMileCallbackService.kt`, `LastMileOutboxWorker.kt`와 tests.

- [ ] `RoutingProvider`는 canonical request, submission, normalized result,
  callback decision만 사용한다. domain row·HTTP DTO·credential을 port로 넘기지 않는다.
- [ ] `DeterministicRoutingProvider`는 같은 input digest와 matrix revision에 같은
  result를 반환한다. network fallback은 없다.
- [ ] submit → callback/poll → proposal history 흐름에 request generation,
  provider revision, matrix revision, carrier version을 저장한다.
- [ ] 동일 digest callback은 no-op, 다른 digest는 conflict, stale provider revision은
  audit-only, outage는 bounded retry/dead-letter/`PROVIDER_UNAVAILABLE`로 처리한다.
- [ ] outbox claim은 lease/fence와 shutdown 순서를 보장하고 restart/replay를 수렴시킨다.
- [ ] tests: `DeterministicRoutingProviderTest`, `LastMileCallbackServiceTest`,
  `LastMileOutboxLifecycleTest`, provider outage/retry/replay fixtures.

## Task 6: event coalescing, reconnect와 approval/publication을 구현한다

**Files:** `LastMileEventService.kt`, `LastMileApprovalService.kt`,
`LastMileLifecycle.kt`와 application/integration tests.

- [ ] traffic/pickup-window burst는 request generation별 1회 replan으로 coalesce하고
  최신 canonical digest를 보존한다.
- [ ] cancellation/no-show/breakdown은 미착수 stop을 재계획하되 started pin은 변경하지 않는다.
- [ ] driver check-in/reconnect는 carrier version과 현재 route를 재검증해 안전하게 복구한다.
- [ ] approve는 plan/job/carrier/matrix version과 stop order를 transaction 안에서
  재확인하고 실패 시 `STALE_ROUTE_APPROVAL`과 no-write를 보장한다.
- [ ] committed stop과 outbox는 CAS 성공 때만 함께 기록하고 전체 rollback을 테스트한다.
- [ ] tests: `LastMileEventCoalescingTest`, `LastMileReconnectTest`,
  `LastMileApprovalCasIntegrationTest`, concurrent approval/callback cases.

## Task 7: HTTP contract와 안전한 browser projection을 구현한다

**Files:** HTTP DTO/controller/handler, static HTML/JS와 tests.

- [ ] plan query, replan, approve, callback, event, reconnect endpoint를 만들고
  idempotency key, bounded body, `ETag`/revision, 명시적 오류 enum을 적용한다.
- [ ] redacted read model에 polyline, depot/stop marker, ETA, capacity, window,
  skill, unassigned, score, revision diff, started pin만 허용한다.
- [ ] browser는 synthetic coordinate 선분만 그리며 `innerHTML`/inline handler/raw
  JSON 대신 `textContent`/DOM API, CSP nonce/allowlist를 사용한다.
- [ ] tests: `LastMileRoutingControllerTest`, `LastMileRoutingBrowserContractTest`,
  `LastMileRoutingRedactionTest`, `LastMileRoutingObservabilityTest`.
- [ ] `PROVIDER_UNAVAILABLE`, `MATRIX_MISS`, `DIGEST_CONFLICT`,
  `STALE_ROUTE_APPROVAL`이 HTTP와 화면에 동일하게 나타나는지 확인한다.

## Task 8: ecosystem 문서와 workflow/smoke 등록을 갱신한다

**Files:** module/root README 두 locale, `.github/workflows/Examples.yml`,
`scripts/smoke-validate.sh`, validation/stale source와 tests.

- [ ] optimization README pair에 모듈 목적, 실행 명령, fixed matrix와 provider 비목표,
  PostgreSQL/브라우저 redaction을 추가한다.
- [ ] smoke/full workflow에서 module test와 browser/DB 그룹을 올바른 비용 tier에 등록한다.
- [ ] stale-check와 validation matrix가 module/README/workflow가 함께 존재하는지 검사하게 한다.
- [ ] `node scripts/validate-readme-language.mjs`, `node scripts/validate-readme-parity.mjs`,
  `bash scripts/smoke-validate.sh stale-check`, `bash scripts/smoke-validate.sh optimization`을
  RED/GREEN으로 실행한다.

## Task 9: 전체 검증과 증거를 수집한다

**Files:** benchmark/receipt artifacts under `.bluetape` only; lesson is next task.

- [ ] Docker/Colima health를 확인하고 Testcontainers를 skip하지 않는다.
- [ ] 순차 실행:

```bash
./gradlew :optimization-last-mile-routing:test --max-workers=1 --console=plain
./gradlew :optimization-last-mile-routing:build --max-workers=1 --console=plain
./gradlew detekt --max-workers=1 --console=plain
./gradlew projects --console=plain
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
bash scripts/smoke-validate.sh stale-check
bash scripts/smoke-validate.sh optimization
git diff --check
```

- [ ] targeted tests, PostgreSQL CAS, callback replay, browser smoke, health/readiness,
  Prometheus bounded labels를 각각 receipt에 남긴다.
- [ ] module-local detekt task가 없으면 정확한 `N/A (repository policy)`와 root detekt
  PASS를 기록하며 source lint PASS로 과장하지 않는다.

## Task 10: implementation review와 lesson을 만든다

**Files:**

- Create: `docs/review/2026-08-24-issue-527-last-mile-routing-implementation-review.md`
- Create: `docs/lessons/2026-08-24-issue-527-last-mile-routing.md`

- [ ] 구현 diff와 이 계획의 Task/수용 추적성을 대조한다.
- [ ] Performance, Stability, Security, Operator/Ops, Developer/API, User/caller
  여섯 관점과 main integration을 독립적으로 재검토한다. P0/P1은 0이어야 한다.
- [ ] known gap(실제 provider, auth, GPS, production migration)은 PASS로 승격하지 않는다.
- [ ] Korean writer audit와 `git diff --check`를 다시 실행한다.
- [ ] PR/merge 전에 정확한 branch/head/CI/review 상태를 새로 읽는다.

## 의존성 순서와 중단/재실행 지점

```text
Task 0 gate
  -> Task 1 module
  -> Task 2 ids/events
  -> Task 3 matrix/planner
  -> Task 4 persistence/CAS
  -> Task 5 provider/inbox/outbox
  -> Task 6 lifecycle/approval
  -> Task 7 HTTP/browser
  -> Task 8 ecosystem registration
  -> Task 9 verification receipt
  -> Task 10 implementation review/lesson
```

각 단계의 RED 실패는 해당 Task 안에서만 수정한다. 스키마·HTTP·provider
경계를 바꾸면 Task 0 설계/계획 review로 되돌아가 영향 lane을 재실행한다.

## 설계 수용 기준 → 계획 추적성

| 설계 기준 | 계획 Task | 주 검증 |
|---|---|---|
| pickup-before-delivery/capacity/window/skill/pin | 2–3, 6–7 | planner/property/CAS/browser tests |
| fixed matrix revision/miss와 provider outage | 3, 5, 9 | matrix/provider/replay receipts |
| PostgreSQL authority와 stale approval | 4, 6 | SQL CAS/rollback integration |
| duplicate callback/digest conflict/stale revision | 2, 5, 7 | canonicalizer/inbox/controller tests |
| event burst/coalescing/reconnect | 2, 6 | event/reconnect lifecycle tests |
| redaction/CSP/ETag/health | 7, 9 | MVC/browser/observability smoke |
| BOM/module/README/workflow/stale 등록 | 1, 8, 9 | projects/parity/smoke/detekt |

## 계획 review 전 상태

이 계획은 Issue #527 범위에 맞춘 실행 단위와 검증 명령을 고정한다. 계획 review가
완료되고 사용자 승인이 확인되기 전에는 Task 1의 구현 파일을 생성하지 않는다.
PR 생성, push, merge, Epic #523 종료는 이 계획에 포함되지 않는다.

## 문서 작성 게이트

| 항목 | 상태 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | Goal/Architecture/Tech Stack, Issue·설계·현재 저장소 경계 |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | 공통 규칙, Task 2–10, 추적성/중단 절 |
| SPW-03 한국어 기술 문체·용어 | PASS | 실행자용 한국어와 보존된 코드/명령/API 토큰 |
| SPW-04 현재 소스·외부 계약 대조 | PASS | #524/#525 패턴, Gradle 정책, Timefold 경계 반영 |
| SPW-05 read-back·Markdown·공백 | PASS | 작성 후 read-back, fence/placeholder scan, `git diff --check` 예정 |

# Issue #528 Clinic Appointment Solver 구현 계획

> **실행자 안내:** 이 계획은 승인된 `embedded timefold-solver-core` bounded slice를
> 순서대로 실행한다. 각 Task의 RED/GREEN 증거를 확인한 뒤 다음 Task로 이동한다.
> 이 계획은 Timefold Platform tenant/API/webhook이나 실제 예약 commit을 추가하지
> 않는다.

**Goal:** `optimization/clinic-appointment-solver`에 synthetic clinic appointment를
> Timefold Solver로 배치하는 독립 reference application을 추가하고, hard/soft
> constraint, pin 보존, deterministic proposal 경계를 테스트로 증명한다.

**Architecture:** 새 모듈은 자체 planning domain, facts, `ConstraintProvider`,
> bounded `SolverFactory`, read-only proposal port, fixture를 소유한다. 결과 적용,
> PostgreSQL/CAS, browser UI, Platform HTTP는 후속 범위다.

**Tech Stack:** Kotlin 2.4.0, Java 25, Spring Boot 4.0.6, Timefold Solver core
> (root `bluetape4k-dependencies:1.4.0`가 2.4.0 선택), Bluetape assertions/logging/
> JUnit5, JUnit 5 `ConstraintVerifier`.

## 실행 전 계약

- 작업 디렉터리는 `/Users/debop/work/bluetape4k/bluetape4k-workshop-issue-528-timefold-solver`다.
- root `platform(libs.bluetape4k.dependencies)`가 유일한 Bluetape/Timefold version 권위다.
- `gradle/libs.versions.toml`에는 `timefold-solver-core` versionless alias만 추가한다.
  stale `timefold-solver = "2.2.0"` 변수는 제거한다. 개별 Timefold BOM은 추가하지 않는다.
- production source는 `io.bluetape4k.workshop.optimization.clinicappointment` 하위만
  사용하고 `clinic-appointment` repository나 `optimization-planning-contracts`를
  dependency로 추가하지 않는다.
- 모든 변경은 TDD RED → 최소 GREEN → regression 순서로 수행한다.
- Solver unit/constraint test는 외부 네트워크와 Testcontainers 없이 실행한다.
- Kotlin public API의 값 검증과 테스트 assertion에는 bluetape4k helper를 우선 사용한다.
- module-local `detekt` task가 없으면 root `detekt`만 실행하고 module detekt는 N/A로
  기록한다. 없는 gate를 새로 만들지 않는다.
- 계획의 각 Task는 이전 Task가 GREEN인 경우에만 시작한다. 실패 시 마지막 RED 단계로
  돌아가 원인을 수정하고 같은 명령을 재실행한다.

## 변경 파일 지도

### 모듈·문서·검증 등록

- Modify: `gradle/libs.versions.toml`
- Create: `optimization/clinic-appointment-solver/build.gradle.kts`
- Create: `optimization/clinic-appointment-solver/README.md`
- Create: `optimization/clinic-appointment-solver/README.ko.md`
- Create: `optimization/clinic-appointment-solver/src/main/resources/application.yml`
- Create: `optimization/clinic-appointment-solver/src/test/resources/junit-platform.properties`
- Modify: `optimization/README.md`
- Modify: `optimization/README.ko.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`
- Create: `docs/lessons/2026-08-25-issue-528-clinic-appointment-solver.md`

### Kotlin production source

- Create: `optimization/clinic-appointment-solver/src/main/kotlin/io/bluetape4k/workshop/optimization/clinicappointment/ClinicAppointmentSolverApplication.kt`
- Create: `.../domain/ClinicAppointmentPlanning.kt`
- Create: `.../domain/ClinicSchedule.kt`
- Create: `.../domain/ClinicAppointmentModels.kt`
- Create: `.../constraint/ClinicAppointmentConstraintProvider.kt`
- Create: `.../solver/ClinicAppointmentSolver.kt`
- Create: `.../fixture/ClinicAppointmentFixtures.kt`
- Create: `.../web/ClinicAppointmentDemoController.kt` (read-only synthetic query)

### Tests

- Create: `optimization/clinic-appointment-solver/src/test/kotlin/io/bluetape4k/workshop/optimization/clinicappointment/ClinicAppointmentRuntimeContractTest.kt`
- Create: `.../domain/ClinicAppointmentPlanningTest.kt`
- Create: `.../constraint/ClinicAppointmentConstraintProviderTest.kt`
- Create: `.../solver/ClinicAppointmentSolverTest.kt`
- Create: `.../web/ClinicAppointmentDemoControllerTest.kt`

## Task 1: 모듈 골격과 dependency contract

**RED**

1. `ClinicAppointmentRuntimeContractTest`와 `ClinicAppointmentPlanningTest`를 먼저
   작성한다. 아직 module/alias가 없으므로 다음 명령이 task 또는 symbol 부재로
   실패하는 것을 확인한다.

   ```bash
   ./gradlew :optimization-clinic-appointment-solver:test \
     --tests '*ClinicAppointmentRuntimeContractTest' \
     --no-build-cache --no-daemon --max-workers=1 --console=plain
   ```

2. 실패 출력이 새 module 부재 또는 planning type 부재인지 기록한다. unrelated root
   failure이면 해당 RED를 유효한 근거로 사용하지 말고 먼저 환경을 고친다.

**GREEN**

1. catalog에 다음 versionless alias를 추가한다.

   ```toml
   timefold-solver-core = { module = "ai.timefold.solver:timefold-solver-core" }
   ```

2. `build.gradle.kts`는 기존 optimization Spring Boot module convention을 따라
   `kotlin.spring`, `spring.boot`와 root BOM, `implementation(libs.timefold.solver.core)`,
   `implementation(libs.spring.boot.starter.webmvc.lib)`,
   `testImplementation(libs.bluetape4k.assertions)`,
   `testImplementation(libs.bluetape4k.junit5)`, `testImplementation(project(":shared"))`
   만 필요한 범위로 선언한다. Timefold benchmark/individual BOM/explicit version은
   넣지 않는다.

3. Spring Boot main class와 loopback-only `application.yml`을 추가한다. 기본 profile은
   `demo`이며, controller는 synthetic read-only route만 제공한다.

4. 아래 명령으로 module auto-registration과 dependency resolution을 확인한다.

   ```bash
   ./gradlew projects --no-daemon --console=plain
   ./gradlew :optimization-clinic-appointment-solver:dependencies \
     --configuration testRuntimeClasspath --no-daemon --console=plain
   ```

   `ai.timefold.solver:timefold-solver-core:2.4.0`이 root BOM에서 선택되어야 한다.

## Task 2: planning domain과 fixture

**RED**

1. `ClinicAppointmentPlanningTest`에 다음 계약을 작성한다.
   - required service/provider/room/equipment/date/start time을 보유한다.
   - confirmed appointment는 pin 상태다.
   - nullable planning variable과 no-arg constructor가 Timefold domain metadata를
     만족한다.
   - fixture의 id와 value range가 stable order를 가진다.
2. 새 type이 없거나 annotation/constructor가 맞지 않아 컴파일 또는 테스트가 실패함을
   확인한다.

**GREEN**

1. `ClinicAppointmentModels.kt`에 synthetic immutable facts와 read-only
   `ClinicAppointmentSnapshot`, `ClinicAppointmentProposal`, 닫힌 reason enum을 만든다.
2. `ClinicAppointmentPlanning.kt`에 `@PlanningEntity`, `@PlanningPin`, 4개 planning
   variable과 Kotlin no-arg constructor를 구현한다.
3. `ClinicSchedule.kt`에 `@PlanningSolution`, `@ProblemFactCollectionProperty`,
   `@ValueRangeProvider`, `@PlanningEntityCollectionProperty`, `@PlanningScore`를
   구현한다.
4. `ClinicAppointmentFixtures.kt`는 최소 3 provider/2 room/2 equipment/2 date/6 slot,
   overlapping appointment와 pinned appointment를 deterministic하게 제공한다.
5. domain 테스트를 Bluetape assertions(`shouldBeEqualTo`, `shouldBeTrue`,
   `shouldBeFalse`, `assertFailsWith`) 중심으로 GREEN으로 만든다.

## Task 3: constraint provider와 verifier

**RED**

1. `ClinicAppointmentConstraintProviderTest`에 qualification, provider availability,
   operating/request window, provider overlap, room overlap, equipment overlap, pin
   fixture를 `ConstraintVerifier`로 작성한다.
2. 아직 `ConstraintProvider`가 없으므로 컴파일 실패를 확인한다.

**GREEN**

1. `ClinicAppointmentConstraintProvider.kt`에 hard constraint를 우선 작성하고,
   `HardSoftScore.ofHard(...)`/`ofSoft(...)`의 `Long` weight contract를 지킨다.
2. requested provider/slot과 load balance를 soft constraint로 추가한다.
3. `ConstraintVerifier.build(ClinicAppointmentConstraintProvider::class.java,
   ClinicSchedule::class.java, ClinicAppointmentPlanning::class.java)`로 각 규칙을
   독립 검증한다.
4. 점수의 hard/soft sign, overlap interval 경계(끝 시각과 다음 시작 시각이 같으면
   겹치지 않음), unassigned reason을 테스트로 고정한다.

## Task 4: embedded solver adapter와 read-only route

**RED**

1. `ClinicAppointmentSolverTest`에 동일 기준 데이터로 2회 solve한 결과의 sorted assignment,
   score, feasibility 일치를 작성한다.
2. pinned appointment 보존, empty 기준 데이터 조기 거부, solver failure 전파를 작성한다.
3. `ClinicAppointmentDemoControllerTest`에 GET만 허용하고 POST/PUT/DELETE가 없으며,
   response가 synthetic id/score만 포함하고 raw request를 포함하지 않는 계약을 작성한다.

**GREEN**

1. `ClinicAppointmentSolver.kt`에 `ClinicAppointmentSolverPort`와 구현을 작성한다.
   `SolverFactory.create(...)`로 `ClinicSchedule`과 entity/constraint provider를
   등록하고 bounded termination을 설정한다. 결과 entity는 ID 오름차순 proposal로
   변환하며 input entity를 반환하지 않는다.
2. `ClinicAppointmentDemoController`는 fixture solve 결과를 GET `/api/clinic-appointments/demo`
   로 반환한다. mutation, persistence, external HTTP client는 추가하지 않는다.
3. solver/controller 테스트를 순차적으로 GREEN으로 만든다.

## Task 5: 문서·workflow·smoke 등록

1. 모듈 README 한·영에 embedded library와 Platform 비목표, 실행/검증 명령, constraint
   표를 기록한다.
2. optimization index 한·영에 `clinic-appointment-solver` 행을 추가한다.
3. `.github/workflows/Examples.yml` Java 25 optimization test와 artifact paths에
   `:optimization-clinic-appointment-solver:test`를 추가한다. 기존 path filter는
   `optimization/**`이므로 범위를 넓히지 않는다.
4. `scripts/smoke-validate.sh` optimization test list, module registration list,
   summary에 새 module을 추가한다.
5. `docs/lessons/2026-08-25-issue-528-clinic-appointment-solver.md`에 GNO 근거,
   BOM 선택, bounded scope, verification 결과와 미검증 잔여 범위를 기록한다.

## Task 6: 검증과 handoff

각 명령의 출력과 exit code를 읽은 뒤에만 다음 DoD를 갱신한다.

```bash
./gradlew :optimization-clinic-appointment-solver:cleanTest \
  :optimization-clinic-appointment-solver:test \
  --no-build-cache --no-daemon --max-workers=1 --console=plain

./gradlew :optimization-clinic-appointment-solver:dependencyInsight \
  --dependency ai.timefold.solver:timefold-solver-core \
  --configuration testRuntimeClasspath --no-daemon --console=plain

./gradlew detekt --no-build-cache --no-daemon --max-workers=1 --console=plain
bash scripts/smoke-validate.sh optimization
git diff --check
```

필요하면 `actionlint`와 `bash -n scripts/smoke-validate.sh`를 추가한다. Testcontainers를
추가하지 않는 bounded slice이므로 Colima/PostgreSQL 증거는 N/A다. module-local
`detekt` task도 현재 optimization 정책에 따라 N/A다.

검증 실패 시 해당 Task만 수정하고 clean test를 다시 실행한다. 새 공통 모듈 추출,
Timefold Platform credential 사용, 예약 commit 같은 scope expansion은 이 계획에
몰래 넣지 않고 별도 issue/승인 대상으로 남긴다.

## 완료 조건

- [ ] Task 1~5가 각각 RED/GREEN evidence와 함께 완료됐다.
- [ ] module test와 root detekt가 fresh 결과로 통과했다.
- [ ] Timefold core가 BOM으로 2.4.0에 resolve됐다.
- [ ] smoke/workflow/README/lesson 등록이 누락되지 않았다.
- [ ] 변경 branch와 root develop dirty file이 분리되어 있다.
- [ ] PR 생성은 target/base/head가 명시적으로 승인된 경우에만 수행한다.
- [ ] merge, branch 삭제, worktree cleanup은 별도 fresh approval gate다.

## Writer gate evidence

- **SPW-01 PASS** — 계획의 대상 독자, branch/module, GNO·live GitHub source, exact
  dependency와 unresolved residual scope를 고정했다.
- **SPW-02 PASS** — dependency order, exact files, RED/GREEN commands, expected
  evidence, rollback/rerun, validation and approval gates를 포함했다.
- **SPW-03 PASS** — Korean technical register와 KO-01..KO-07을 적용하고 code token,
  command, URL, version을 보존했다.
- **SPW-04 PASS** — 설계의 planning/constraint/DoD와 plan task/file/test mapping을
  대조해 drift가 없도록 했다.
- **SPW-05 PASS** — 최종 Markdown read-back으로 headings, tables, code fences,
  checkbox, shell command line wrapping을 확인했다.

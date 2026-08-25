# Issue #528 Clinic Appointment Solver 구현 교훈

## 배경

[Issue #528](https://github.com/bluetape4k/bluetape4k-workshop/issues/528)의 범위와
Epic #523의 선행 결정을 GNO 전역 query로 다시 조사했다.

```bash
gno query --fast -n 8 --md \
  'Epic 523 issue 528 embedded Timefold Solver clinic appointment custom solver BOM'
```

검색 결과는 다음 경계를 일관되게 가리켰다.

- Epic과 조사 문서는 Timefold Platform 연동과 애플리케이션 내부 Solver library
  embedding을 별도 선택지로 구분한다.
- `clinic-appointment` 자료는 `PlanningEntity`, `PlanningSolution`,
  `ConstraintVerifier`, `PlanningPin`, source-version CAS를 설명하지만,
  workshop에 내부 모듈을 그대로 복사하거나 공통 solver 모듈을 먼저 추출할 근거는
  제공하지 않는다.
- `bluetape4k-dependencies:1.4.0`이 Timefold Solver `2.4.0`을 관리하므로 consumer
  프로젝트에서 개별 Timefold BOM이나 버전을 고정하지 않아야 한다.

GNO는 설계 검색을 위한 근거로만 사용하고, 현재 issue 상태와 저장소 파일은 live
GitHub와 작업트리에서 다시 확인했다.

## 결정

이번 슬라이스는 `optimization/clinic-appointment-solver` 독립 모듈로 한정했다.
Solver를 Spring Boot 애플리케이션 내부의 embedded library로 감싸고, 외부에는
deterministic read-only proposal만 노출한다.

- 합성 fixture만 사용하며 PHI/EHR, Platform tenant/API/webhook, PostgreSQL,
  source-version CAS, slot hold, waitlist, browser 인증은 포함하지 않는다.
- hard constraint는 provider 자격·가용성·운영/요청 시간대, provider/room/equipment
  호환성, 중복 배정, pin 보존이다.
- soft constraint는 provider/slot 선호와 load balance다.
- solver 종료는 wall-clock이 아닌 `stepCountLimit=200`으로 고정해 같은 기준 데이터가
  같은 결과를 재현하도록 했다.
- Timefold dependency는 root `bluetape4k-dependencies` BOM과 versionless alias만
  사용한다.

## 구현 결과

- `ClinicAppointmentPlanning`, `ClinicSchedule`, 불변 기준 데이터/fixture와
  `ConstraintVerifier` 기반 constraint 계약을 추가했다.
- `ClinicAppointmentSolverPort`와 Spring `ClinicAppointmentSolver`를 두어 solver
  구현과 호출 경계를 분리했다.
- `GET /api/clinic-appointments/demo`만 제공하는 read-only controller를 추가했다.
  mutation route나 운영 인증을 암시하지 않으며, 응답에는 score·assignment·reason만
  포함한다.
- domain, constraint, solver, Spring runtime, controller contract 테스트를 추가했다.
- optimization README 양쪽, `Examples.yml`, `smoke-validate.sh`, stale validation
  목록과 lessons index를 함께 갱신했다.

## 구현 중 발견한 점

- nullable planning variable이 있는 entity에는 Timefold의 `forEach`가 해당 entity를
  stream에서 제외할 수 있다. 미할당 상태도 `assignmentComplete`와 reason 계산에
  포함해야 하므로 `forEachIncludingUnassigned`를 사용하고, 필요한 constraint에서
  assigned 상태를 명시적으로 확인한다.
- `FIRST_FIT_DECREASING`을 사용하려면 planning entity에 difficulty comparator가
  필요하다. 요청 날짜·duration·equipment 요구를 기준으로 안정적인 comparator를
  제공했다.
- Spring Boot 4의 `@AutoConfigureMockMvc`는 `spring-boot-starter-webmvc-test`에서
  제공되므로, web test 의존성을 일반 starter test와 분리해 선언했다.

## 검증

```bash
./gradlew :optimization-clinic-appointment-solver:test \
  --no-build-cache --no-daemon --max-workers=1 --console=plain
./gradlew :optimization-clinic-appointment-solver:dependencyInsight \
  --dependency ai.timefold.solver:timefold-solver-core \
  --configuration testRuntimeClasspath
./gradlew detekt --no-build-cache --no-daemon --max-workers=1 --console=plain
bash scripts/smoke-validate.sh optimization
actionlint .github/workflows/Examples.yml
bash -n scripts/smoke-validate.sh
git diff --check
```

- module test: 14 tests passed, failures/errors/skipped 0개.
- boot smoke: `GET /api/clinic-appointments/demo`가 `hardScore=0`,
  `feasible=true`, `assignments=3`, `hasPinned=true`를 반환했다.
- dependency insight: root BOM `bluetape4k-dependencies:1.4.0` 경유
  `timefold-solver-core:2.4.0`을 확인했다.
- root `detekt`: 110 actionable tasks가 성공했다. 이 모듈에는 별도 `detekt` task가
  등록되어 있지 않아 module-local detekt는 N/A다.
- optimization smoke, workflow lint, shell syntax, diff check가 모두 성공했다.

## 남은 범위와 재발 방지

이번 구현은 Issue #528의 embedded reference slice만 완료했다. Platform credential,
실시간 availability, PostgreSQL reservation/CAS, hold/expiry, waitlist, provider
webhook, browser 인증과 운영성 stress/end-to-end 검증은 후속 이슈 범위로 남긴다.

후속 모듈을 추가할 때도 root BOM 단일 권위, versionless Bluetape/Timefold alias,
read-only planner와 mutation authority 분리, nullable planning variable 테스트,
workflow/smoke/stale 목록 동시 갱신 규칙을 유지한다.

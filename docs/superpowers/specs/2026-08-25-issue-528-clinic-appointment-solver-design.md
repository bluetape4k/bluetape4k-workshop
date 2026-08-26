# Issue #528 Clinic Appointment Solver 설계

- 날짜: 2026-08-25
- 저장소: `bluetape4k/bluetape4k-workshop`
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/528
- 상위 Epic: https://github.com/bluetape4k/bluetape4k-workshop/issues/523
- 작업 브랜치: `feat/issue-528-timefold-solver`
- 대상 모듈: `optimization/clinic-appointment-solver`

## 결정 요약

`optimization/clinic-appointment-solver`를 독립 Kotlin/Spring Boot reference
application으로 추가한다. 모듈은 `ai.timefold.solver:timefold-solver-core`를
애플리케이션 내부에서 직접 실행하고, synthetic clinic appointment와 provider,
room, equipment, time-slot을 하나의 planning 기준 데이터로 최적화한다.

Solver의 책임은 proposal 계산과 `HardSoftScore` 반환까지다. 예약을 확정하거나
외부 시스템에 webhook을 보내거나 데이터베이스 상태를 변경하지 않는다. 이번 child의
첫 slice는 embedded Solver와 deterministic fixture를 검증하고, #528의 영속 상태,
slot hold/CAS, browser console, 실제 Platform tenant/API/webhook 연동은 별도 후속
범위로 남긴다. 이렇게 해야 library embedding과 Platform 서비스 연동을 같은 실행
경계로 오해하지 않는다.

## 현재 근거와 source ledger

| 근거 | 확인한 사실 | 설계에 반영한 결정 |
|---|---|---|
| GNO 전역 query `Epic 523 issue 528 embedded Timefold Solver clinic appointment custom solver BOM` | `#523`, `#528`, clinic-appointment의 Solver 2 migration, 기존 Timefold 연구 문서가 검색된다. | collection을 강제하지 않고 전역 검색으로 관련 issue·문서·lesson을 먼저 수집한다. |
| `gno://bluetape4k-github/bluetape4k-workshop/issues/000523.md` | Epic은 Timefold Platform과 custom Solver를 별도 경로로 구분하고, live tenant/API/webhook은 기본 전제가 아니라고 명시한다. | Platform HTTP adapter를 이번 모듈의 실행 경계에 넣지 않는다. |
| `gno://bluetape4k-github/bluetape4k-workshop/issues/000528.md` | synthetic clinic capacity booking, pinned confirmed booking, provider/equipment outage, waitlist, expiry, revision/hold expiry를 요구한다. PHI/EHR은 비목표다. | 핵심 hard rule과 pin을 먼저 구현하고, durable lifecycle은 후속 범위로 명시한다. |
| `gno://bluetape4k-wiki/research/2026-07-18-timefold-solver-optimization-reference-applications.md` | standalone service가 권장되지만 직접 library embedding은 advanced path이며, workshop v1은 `PlanningEngine` port 뒤의 reproducible embedded library로 시작할 수 있다. | 모듈 내부 `ClinicAppointmentSolver`를 port처럼 사용하고 결과를 읽기 전용 proposal로 제한한다. |
| `gno://bluetape4k-docs/clinic-appointment/appointment-solver/README.ko.md` | `@PlanningEntity`, `@PlanningSolution`, `SolverService`, `ConstraintVerifier`, `@PlanningPin`, source-version CAS 분리가 이미 검증됐다. | domain annotation과 verifier 패턴은 참고하되, clinic-appointment 모듈을 의존하지 않고 독립 synthetic 모델로 작성한다. |
| `gno://bluetape4k-docs/clinic-appointment/docs/lessons/2026-08-09-issue-253-dependencies-1.4.0.md` | `bluetape4k-dependencies`가 Timefold 2.4.0을 선택하며 개별 Timefold BOM/version pin을 제거했다. | root catalog에는 versionless Timefold alias만 추가하고 BOM을 단일 권위로 둔다. |
| live `gh issue view 528` | issue가 OPEN, assignee `debop`, milestone `1.4.0`, labels `enhancement`, `difficulty:expert`, `area:data-access`, `area:spring-boot`, `area:async-reactive`다. | 이번 변경은 #528의 bounded child slice로 기록하고, 전체 issue 완료를 주장하지 않는다. |
| 현재 workshop source | `optimization`은 디렉터리 자동 등록이며 README, `Examples.yml`, `scripts/smoke-validate.sh`에 module 목록이 중복된다. | 새 디렉터리와 모든 validation surface를 같은 변경에 등록한다. |

GNO 검색 결과는 live GitHub의 현재 issue state를 대체하지 않는다. issue/branch/
workflow 상태는 `gh`와 현재 worktree를 기준으로 재확인한다.

## 목표

1. synthetic appointment 요청을 provider, room, equipment, date, start time에 배정하는
   Timefold planning model을 제공한다.
2. 다음 hard constraint를 `ConstraintProvider`로 검증한다.
   - provider qualification과 availability를 지킨다.
   - clinic operating hours와 appointment requested window를 지킨다.
   - 같은 provider 또는 room을 겹치는 예약에 배정하지 않는다.
   - equipment가 필요한 예약은 같은 시간에 장비를 중복 사용하지 않는다.
   - confirmed appointment는 `@PlanningPin`으로 이동하지 않는다.
3. 다음 soft constraint를 finite한 점수로 최적화한다.
   - 요청 provider와 요청 slot을 선호한다.
   - provider load를 균형화한다.
   - 예약을 가능한 이른 slot에 배치한다.
4. 동일한 기준 데이터와 동일한 solver 설정으로 반복 실행했을 때 결과의 정렬된
   assignment와 score가 수렴하는 deterministic fixture를 제공한다.
5. `ConstraintVerifier`로 각 hard/soft 규칙을 단위 검증하고, SolverService 수준에서
   pinned appointment 보존과 proposal read-only 경계를 검증한다.
6. Spring Boot 앱의 기본 실행은 외부 Timefold Platform credential과 네트워크 없이
   fixture를 사용한다.

## 비목표와 잔여 범위

- PHI, 환자 식별자, EHR/FHIR, 진단·보험·의료 조언을 저장하거나 처리하지 않는다.
- 실제 tenant, Timefold Platform API key, webhook, SSE, browser 인증을 추가하지 않는다.
- PostgreSQL schema, appointment slot hold, outbox, source-version CAS, waitlist/expiry
  상태 전이는 이번 slice의 contract가 아니다.
- Solver 결과를 자동으로 예약 확정하거나 다른 시스템에 publish하지 않는다.
- `planning-contracts` 또는 clinic-appointment repository의 내부 클래스를 import하지
  않는다. 두 예제에서 공통 seam이 실제로 반복될 때 별도 공통화 issue로 다룬다.
- Timefold benchmark, persistence common, 별도 solver BOM/dependency를 추가하지 않는다.

## 선택지와 결정

### A. Timefold Platform HTTP client

Platform endpoint와 tenant/API key/webhook을 reference app의 기본 실행에 포함하는
방식이다. Epic이 Platform과 custom Solver를 분리하고, 현재 CI에 credential이 없다는
조건과 충돌한다. 이번 slice에서는 채택하지 않는다.

### B. 외부 clinic-appointment 모듈 재사용

이미 풍부한 Solver 모델과 Exposed repository를 가져올 수 있지만, workshop 모듈이
다른 repository의 내부 domain과 persistence에 결합된다. 독립 실행 가능한 workshop
example이라는 목표와 BOM·module boundary를 훼손하므로 채택하지 않는다.

### C. 독립 embedded `timefold-solver-core` 모듈 (채택)

새 모듈이 synthetic domain, planning solution, constraints, solver configuration,
read-only proposal adapter를 소유한다. root `bluetape4k-dependencies` BOM이
Timefold version을 관리하고, 테스트는 Bluetape assertions와 `ConstraintVerifier`를
사용한다. 이 구조는 local library execution을 증명하면서 이후 Platform adapter나
durable apply service를 추가할 경계를 남긴다.

## 모듈 구조와 책임

```text
ClinicAppointmentController (synthetic demo query only, optional)
                    │
                    v
          ClinicAppointmentSolverPort
                    │
                    v
        ClinicAppointmentSolverService
          ├── SolverFactory<ClinicSchedule>
          ├── ClinicAppointmentConstraintProvider
          └── deterministic fixture 기준 데이터
                    │
                    v
        Read-only ClinicAppointmentProposal
```

초기 구현 파일은 다음 책임을 가진다.

| 영역 | 파일 | 책임 |
|---|---|---|
| 모듈 | `optimization/clinic-appointment-solver/build.gradle.kts` | Spring Boot, Timefold core, Bluetape BOM 기반 versionless dependency |
| 진입점 | `.../ClinicAppointmentSolverApplication.kt` | 로컬 synthetic demo용 Spring Boot 진입점 |
| domain | `.../domain/ClinicAppointmentPlanning.kt` | `@PlanningEntity`, pin, planning variables, immutable facts |
| solution | `.../domain/ClinicSchedule.kt` | `@PlanningSolution`, facts, value ranges, score |
| constraints | `.../constraint/ClinicAppointmentConstraintProvider.kt` | hard/soft constraint stream 정의 |
| solver | `.../solver/ClinicAppointmentSolver.kt` | solver factory, bounded termination, read-only result mapping |
| fixture | `.../fixture/ClinicAppointmentFixtures.kt` | synthetic provider/resource/appointment 기준 데이터와 stable ordering |
| web/config | `.../config` 및 `.../web` | 기본 profile, score/assignment read model; mutation route 없음 |
| tests | `src/test/...` | domain, `ConstraintVerifier`, solver convergence/runtime contract |

Production source의 public surface는 `ClinicAppointmentSolverPort`, 기준 데이터/proposal
DTO, fixture entrypoint처럼 예제의 학습 계약에 필요한 타입으로 제한한다. internal
implementation은 외부 모듈에 공개하지 않는다.

## Planning contract

### Problem facts

- `ClinicFact`: synthetic clinic id와 UTC operating window.
- `ProviderFact`: provider id, qualified service set, availability window, daily load limit.
- `RoomFact`: room id와 지원 service/equipment set.
- `EquipmentFact`: equipment id와 availability window.
- `TimeSlotFact`: 날짜와 시작 시간, 운영 시간 안의 30분 단위 slot.

### Planning entity

`ClinicAppointmentPlanning`은 request id, required service, duration, requested provider/
slot, confirmed 여부를 불변 입력으로 보유한다. `providerId`, `roomId`, `date`, `startTime`
은 `@PlanningVariable`이다. confirmed 입력은 `@PlanningPin`으로 고정한다. Timefold의
Kotlin reflection/no-arg 요구를 만족하도록 기본 생성자를 제공하고, solver가 변경하는
property만 `var`로 둔다.

### Planning solution

`ClinicSchedule`은 facts, value ranges, appointment entity list, `HardSoftScore`를
보유한다. value range는 fixture 기준 데이터에서 미리 정렬한 provider/room/date/start
값을 사용한다. 모든 id와 slot은 synthetic allowlist에 속하고, 결과 mapping 전에
assignment를 `appointmentId` 오름차순으로 정렬한다.

## Constraint contract

| 코드 | 유형 | 검증 내용 | 실패 시 결과 |
|---|---|---|---|
| `PROVIDER_QUALIFICATION` | hard | provider가 required service를 제공한다. | hard score 감소, proposal은 infeasible |
| `PROVIDER_AVAILABILITY` | hard | provider window와 appointment interval이 겹친다. | hard score 감소 |
| `OPERATING_WINDOW` | hard | clinic 운영 시간과 requested window 안에 끝난다. | hard score 감소 |
| `PROVIDER_OVERLAP` | hard | 같은 provider의 appointment interval이 겹치지 않는다. | hard score 감소 |
| `ROOM_OVERLAP` | hard | 같은 room의 appointment interval이 겹치지 않는다. | hard score 감소 |
| `EQUIPMENT_OVERLAP` | hard | 필요한 equipment를 같은 시간에 중복 사용하지 않는다. | hard score 감소 |
| `REQUESTED_PROVIDER` | soft | 요청 provider를 우선한다. | soft score 감소 |
| `REQUESTED_SLOT` | soft | 요청 날짜·시작 시간을 우선한다. | soft score 감소 |
| `LOAD_BALANCE` | soft | provider별 appointment 수 편차를 줄인다. | soft score 감소 |

unassigned 값은 nullable planning variable로 허용한다. hard score가 0보다 작으면
`ClinicAppointmentProposal.feasible=false`로 반환하고, unassigned appointment id와
닫힌 `ConstraintReasonCode`만 설명한다. provider raw score 문자열이나 arbitrary
explanation은 반환하지 않는다.

## 실행과 실패 경계

1. `ClinicAppointmentSolverPort.solve(input)`가 입력 기준 데이터의 defensive copy를 만든다.
2. `SolverFactory`는 `FIRST_FIT_DECREASING` construction phase와 제한된
   `LATE_ACCEPTANCE` local search를 사용하고, 기본 termination은 고정 step count로
   둔다. fixture 테스트는 wall-clock time limit에 의존하지 않고 score/assignment
   계약을 검증한다.
3. solver 결과를 `ClinicAppointmentProposal`로 변환한다. 원본 기준 데이터나 entity를
   호출자에게 노출하지 않는다.
4. 입력이 비어 있거나 value range가 없으면 `IllegalArgumentException`으로 조기 거부한다.
5. solver가 유한한 score를 만들지 못하거나 결과 entity id가 사라지면
   `IllegalStateException`으로 실패한다. 실패를 fake success로 바꾸지 않는다.
6. timeout/cancellation은 호출자에게 전파하며, proposal을 부분 결과로 반환하지 않는다.
7. apply/commit 경계는 존재하지 않으므로 이 모듈만으로 예약 상태가 변경되지 않는다.

## 호환성과 재사용 계약

| 책임 | 재사용 | 경계 |
|---|---|---|
| dependency version | root `platform(libs.bluetape4k.dependencies)` | Timefold/Bluetape 개별 version pin 금지 |
| assertions | `io.bluetape4k.assertions`의 `shouldBeEqualTo`, `shouldBeTrue`, `assertFailsWith` | helper가 없는 구조 비교만 JUnit assertion 사용 |
| test lifecycle | `bluetape4k-junit5`, root test mutex, `--max-workers=1` | Solver unit test는 DB/Testcontainers를 띄우지 않는다 |
| logging | Bluetape `KLogging` | synthetic id/count/score만 남기고 raw request를 기록하지 않는다 |
| Kotlin/Spring | 기존 Java 25 optimization module convention | 새로운 공통 optimization SDK를 추출하지 않는다 |

## acceptance와 DoD

- [ ] `:optimization-clinic-appointment-solver`가 자동 등록되고 `test`가 실행된다.
- [ ] `ai.timefold.solver:timefold-solver-core`가 versionless alias로 선언되고,
      dependency insight가 `bluetape4k-dependencies:1.4.0`의 Timefold `2.4.0` 선택을
      증명한다.
- [ ] qualification, availability, operating window, provider/room/equipment overlap,
      pin 규칙에 대한 `ConstraintVerifier` 테스트가 있다.
- [ ] 동일 fixture를 두 번 solve했을 때 정렬된 proposal과 score가 동일하다.
- [ ] pinned confirmed appointment의 assignment가 보존된다.
- [ ] solver 결과가 read-only proposal이며 DB/외부 네트워크를 사용하지 않는다.
- [ ] module README 한·영, optimization index, `Examples.yml`, `smoke-validate.sh`,
      lesson/validation surface가 등록된다.
- [ ] root `detekt`, module fresh test, dependency check, shell/action workflow syntax,
      `git diff --check`를 실행하고 module-local detekt가 없으면 N/A로 기록한다.

이번 DoD는 #528 전체 완료가 아니다. PostgreSQL/CAS, hold/expiry/waitlist, browser
console, Platform adapter가 필요하면 후속 issue 또는 후속 PR의 별도 acceptance로
추적한다.

## Writer gate evidence

- **SPW-01 PASS** — artifact는 Korean technical design이며 대상 독자는 workshop
  유지보수자다. GNO URI, live issue, branch, module, dependency/version, 미확정 잔여
  범위를 source ledger에 기록했다.
- **SPW-02 PASS** — 문제, 선택지, 구조, planning/constraint contract, 실패 경계,
  호환성, acceptance/DoD를 포함했다.
- **SPW-03 PASS** — `bluetape-writer` Korean naturalness checklist의 KO-01..KO-07을
  적용했고 API/명령/URL/숫자/식별자는 원문 토큰을 보존했다.
- **SPW-04 PASS** — GNO 연구와 clinic-appointment source 패턴을 현재 workshop
  module 등록 구조 및 live `gh issue view 528`와 대조했다. Platform과 embedded
  library의 책임을 분리했다.
- **SPW-05 PASS** — 최종 Markdown을 read-back해 표, code span, 링크, checkbox와
  잔여 범위가 렌더링 가능한지 확인했다. 계획 문서가 이 설계의 파일·검증 계약을
  그대로 추적한다.

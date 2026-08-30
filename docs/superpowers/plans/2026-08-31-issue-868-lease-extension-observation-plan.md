# Issue #868 lease-extension observation 구현 계획

## 목표

기존 `leader/job-safety-lab`의 Redis elector lifecycle에
`bluetape4k-leader:2.0.0-SNAPSHOT` lease-extension observation을 연결한다. 요청 thread의
user 시도와 owner thread의 watchdog 시도가 같은 bounded Micrometer observation으로 보이고,
thread-affine lock handle을 복제하지 않는 경계를 테스트와 양국 README로 고정한다.

## 선행 조건

- Issue #868 live metadata: OPEN, milestone `2.0.0`, assignee `debop`;
- upstream public API source와 tests 및 실제 snapshot jar ABI를 확인;
- #867 stacked parent 최신 head `b557b0b231ec4d7317d42efb3b0b3c98b7befcbb` 유지;
- 새 모듈·개별 BOM·직접 버전 고정 없음;
- 현재 snapshot artifact가 제공하지 않는 scoped observer API를 consumer에 참조하지 않음;
- upstream `leader-spring-boot` 내부 registry manager를 consumer에 복사하지 않음.

## 파일 책임 지도

| 경로 | 책임 |
|---|---|
| `leader/job-safety-lab/src/main/kotlin/.../config/JobSafetyLeaderObservationProperties.kt` | `bluetape4k.leader.observation` 설정과 안전한 기본값 |
| `leader/job-safety-lab/src/main/kotlin/.../config/JobSafetyLeaseExtensionObservationRegistration.kt` | 단일 context global registration, close, NOOP/disabled gate |
| `leader/job-safety-lab/src/main/kotlin/.../config/JobSafetyConfiguration.kt` | properties를 기존 `LeaderObservationOptions`에 매핑하고 bean wiring |
| `leader/job-safety-lab/src/main/kotlin/.../coordination/LeaderElectionPort.kt` | `LeaderLease.extendViaLockExtender` proxy 계약(기존 구현 호환 기본값) |
| `leader/job-safety-lab/src/main/kotlin/.../coordination/JobRunCoordinator.kt` | 기존 `run` 회귀 유지와 `runWithLease` user extension 경계 |
| `leader/job-safety-lab/src/main/kotlin/.../coordination/redis/RedisLeaderElectionAdapter.kt` | owner thread command queue와 actual `LockExtender` 호출 |
| `leader/job-safety-lab/src/test/kotlin/.../config/JobSafetyLeaseExtensionObservationRegistrationTest.kt` | gating, 단일 bean, idempotent close |
| `leader/job-safety-lab/src/test/kotlin/.../coordination/JobRunCoordinatorTest.kt` | `runWithLease`와 기존 lifecycle 회귀 |
| `leader/job-safety-lab/src/test/kotlin/.../coordination/redis/RedisLeaderElectionAdapterTest.kt` | owner thread 실행, queue timeout/release, proxy 동작 |
| `leader/job-safety-lab/src/test/kotlin/.../leader/...` | 실제 `LockExtender` `Extended`/`source=user` 및 watchdog observation |
| `leader/job-safety-lab/README.md`, `README.ko.md` | 설정, observation name/tag, user/watchdog 예제와 한계 |
| `docs/coverage-matrix.md` | leader observation coverage row 갱신 |
| `docs/lessons/2026-08-31-issue-868-lease-extension-observation.md` | thread-affinity와 observation lesson |
| `docs/lessons/README.md` | lesson index |
| `docs/review/issue-868-workflow-checklist.md` 및 `docs/review/2026-08-31-issue-868-*.md` | Type A gate evidence |

## 구현 순서

### 1. 설계·검토 gate

- [x] spec과 이 plan을 작성한다.
- [x] API, operations, stability, performance, security, user 관점에서 독립 검토한다.
- [x] P0/P1을 제거하고 checklist에 Action/Evidence/Failure 근거를 기록한다.

### 2. 설정과 registration (TDD)

- [x] properties RED: 안전한 기본값과 prefix bind, registration RED: disabled/NOOP gate 테스트;
- [x] `@ConfigurationProperties("bluetape4k.leader.observation")`와 immutable options 추가;
- [x] 현재 artifact의 global `addObserver`를 사용하고, 단일 Spring context의
  `@ConditionalOnMissingBean` registration을 구현;
- [x] close가 중복되어도 global observer registration을 두 번 닫지 않는지 검증;
- [x] GREEN: 설정·registration targeted test 실행.

### 3. thread-affinity를 보존하는 scope/lease bridge

- [x] `LeaderLease`에 `extendViaLockExtender(Duration)`를 추가하되 기존 fake 구현의
  source compatibility를 보존하는 기본 `Rejected` 결과를 둔다;
- [x] `RedisLeaderElectionAdapter`에 bounded `ArrayBlockingQueue` command session을
  구현한다. raw `LeaderLockHandle`은 owner thread에만 둔다;
- [x] owner action 안에서 실제 `LockExtender.extendActiveLockDetailed`를 호출하고,
  요청 thread에는 `Extended`/`Rejected`/`NotHeld`/`BackendError`만 반환한다;
- [x] `JobRunCoordinator.runWithLease` overload에서 user extension 시점을 명시하고,
  기존 `run` 호출 결과와 event 순서를 유지한다;
- [x] global observer에서는 별도 scope를 설치하지 않으며, artifact 갱신 뒤 scoped API로
  재개할 후속 조건을 문서에 남긴다;
- [x] RED/GREEN으로 bounded queue response 경계, release/cancel lifecycle, owner-thread actual call을 고정한다.

### 4. observation contract와 lifecycle

- [x] actual `Extended` user event와 watchdog event가 각각 `source=user/watchdog`로
  도착하는 테스트를 추가한다. synthetic event publish는 사용하지 않는다;
- [x] `CancellationException`과 elapsed tag 의미는 released upstream contract에 위임하고
  README/spec에 rethrow/no-event 경계를 기록한다;
- [x] queue timeout은 `Rejected`/`skipped`, backend failure는 `BackendError`/`error`로
  구분하는 adapter mapping을 구현하고 elapsed time tag를 만들지 않는다;
- [x] includeLockName/includeLeaderId 기본 off와 sanitizer option mapping을 검증한다;
- [x] context close 뒤 global registration 제거, queued command bounded drain/timeout, lease release,
  executor 종료를 검증한다.

### 5. README·저장소 등록

- [x] README EN/KO에 동일한 설정 예제, name/low-cardinality tags, proxy 경계와 sample을
  추가한다. upstream Spring prefix와 consumer prefix를 구분한다;
- [x] `docs/coverage-matrix.md` row를 `#868`로 갱신한다;
- [x] Korean lesson과 lesson index를 추가한다;
- [x] README validator, workflow/stale-check 등록 상태를 확인한다.

### 6. 검증·delivery

- [x] targeted tests: properties/registration/observer/adapter/coordinator;
- [x] module unit test와 integration test를 serial mode로 실행한다;
- [x] `node scripts/validate-job-safety-lab-readme.mjs`, `git diff --check`,
  `scripts/smoke-validate.sh leader-full`, `scripts/smoke-validate.sh stale-check` 실행;
- [ ] stacked branch exact head와 ecosystem scope를 갱신하고 PR을 만든다;
- [ ] live metadata/CI를 읽어 `Required checks: X/Y; N/A: N; Blocked: 0`을 산출한다;
- [ ] fresh exact-head `승인` 전 merge하지 않는다.

## 위험과 대응

| 위험 | 신호 | 대응 |
|---|---|---|
| thread-affinity 위반 | user call이 항상 `NotHeld` 또는 token 누출 | raw handle을 owner session에만 두고 command queue로 실제 `LockExtender` 호출 |
| global observer 누락 | watchdog/user event 0건 | owner action의 actual `LockExtender` 호출과 source별 assertion |
| callback 중복 | 동일 event가 2회 이상 도착 | 단일 context bean과 idempotent close, cross-context는 upstream artifact 갱신 후 사용 |
| disabled/NOOP 누수 | disabled인데 global/scoped observer 존재 | bean gate와 registration count 0 회귀 테스트 |
| raw identity 노출 | lock/leader/message가 tag/log에 존재 | sanitizer 결과와 allow-list assertion |
| cancellation 오해 | cancellation event를 terminal로 집계 | upstream rethrow/무-event contract 테스트 |
| 기존 leader 동작 회귀 | acquire/release 순서 또는 결과 변경 | 기존 coordinator/adapter/full module test |
| context close 누락 | global callback registration 또는 worker 미종료 | restart integration에서 callback 부재/terminated 확인 |

## 롤백

관찰 기능에 문제가 생기면 properties를 disabled로 두거나 registration bean을 제거해
기존 manual leader lifecycle observation만 남긴다. lease/fencing/business 결과를
변경하는 수정은 이 issue에서 수행하지 않는다.

## 커밋 순서

1. 설계·plan·review checklist 기록;
2. properties/registration tests와 구현;
3. scope 전파, proxy bridge 및 lifecycle tests;
4. README/matrix/lesson;
5. final review evidence와 CI/PR metadata.

모든 commit은 Lore format Korean trailer를 사용한다.

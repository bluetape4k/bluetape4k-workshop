# Issue #868 lease-extension observation 구현 계획

## 목표

기존 `leader/job-safety-lab`의 실제 Redis elector lifecycle에
`bluetape4k-leader:2.0.0-SNAPSHOT` lease-extension observation을 연결한다. user와
watchdog event가 같은 bounded Micrometer observation으로 보이고, NOOP/disabled,
중복 registry, context close 경계를 테스트와 양국 README로 고정한다.

## 선행 조건

- Issue #868 live metadata: OPEN, milestone `2.0.0`, assignee `debop`;
- upstream public API source와 tests를 로컬 checkout에서 확인;
- #867 stacked parent head `6bec68ff043bb262c930fc01a86729cb675d8e08` 유지;
- 새 모듈·개별 BOM·직접 버전 고정 없음.

## 파일 책임 지도

| 경로 | 책임 |
|---|---|
| `leader/job-safety-lab/src/main/kotlin/.../config/JobSafetyLeaderObservationProperties.kt` | `bluetape4k.leader.observation` 설정과 validation |
| `leader/job-safety-lab/src/main/kotlin/.../config/JobSafetyLeaseExtensionObservationRegistration.kt` | registry identity 공유, scope 생성/해제, NOOP/disabled gate |
| `leader/job-safety-lab/src/main/kotlin/.../config/JobSafetyConfiguration.kt` | properties/registry/registration bean wiring |
| `leader/job-safety-lab/src/main/kotlin/.../coordination/JobRunCoordinator.kt` | run 경계에 optional scope 설치 |
| `leader/job-safety-lab/src/main/kotlin/.../coordination/redis/RedisLeaderElectionAdapter.kt` | backend executor thread로 scope 전파 |
| `leader/job-safety-lab/src/test/kotlin/.../config/JobSafetyLeaseExtensionObservationRegistrationTest.kt` | gating, identity sharing, close contract |
| `leader/job-safety-lab/src/test/kotlin/.../coordination/JobRunCoordinatorTest.kt` | user extension scope와 기존 lifecycle 회귀 |
| `leader/job-safety-lab/src/test/kotlin/.../coordination/redis/RedisLeaderElectionAdapterTest.kt` | executor scope 전달과 watchdog source |
| `leader/job-safety-lab/src/test/kotlin/.../JobSafetyContextRestartIntegrationTest.kt` | context close 후 scope/resource 상태 |
| `leader/job-safety-lab/README.md`, `README.ko.md` | 설정, observation name/tag, user/watchdog 예제 |
| `docs/coverage-matrix.md` | leader observation coverage row 갱신 |
| `docs/lessons/2026-08-31-issue-868-lease-extension-observation.md` | 재발 방지 lesson |
| `docs/lessons/README.md` | lesson index |
| `docs/review/issue-868-workflow-checklist.md` 및 `docs/review/2026-08-31-issue-868-*.md` | Type A gate evidence |

## 구현 순서

### 1. 설계·검토 gate

- [ ] spec과 이 plan을 작성한다.
- [ ] API, operations, stability, performance, security, user 관점에서 독립 검토한다.
- [ ] P0/P1을 제거하고 checklist에 근거를 기록한다.

### 2. 설정과 registration

- [ ] properties RED: default/disabled/NOOP/invalid option 테스트를 먼저 작성한다.
- [ ] 최소 구현: `@ConfigurationProperties("bluetape4k.leader.observation")`와
  immutable options를 추가한다.
- [ ] registry identity 기준 reference-counted registration을 구현한다.
- [ ] close가 중복되어도 scope/observer를 두 번 닫지 않는지 검증한다.
- [ ] GREEN: 설정·registration targeted test를 실행한다.

### 3. scope 전파

- [ ] `JobRunCoordinator.run`을 `scope.withScope`로 감싸되 scope가 없으면 기존 경로와
  동일하게 실행한다.
- [ ] `RedisLeaderElectionAdapter`가 현재 scope를 캡처해 executor 작업에 설치한다.
- [ ] backend watchdog과 execute 내부 `LockExtender`가 각각 `watchdog`/`user` source로
  도착하는 deterministic fake test를 추가한다.
- [ ] lock/leader identity는 raw 값 대신 기존 hash sanitizer 결과만 observation에
  남는지 확인한다.

### 4. README·저장소 등록

- [ ] README EN/KO에 설정 예제와 실제 observation sample을 동일하게 추가한다.
- [ ] `docs/coverage-matrix.md` row를 `#868`로 갱신한다.
- [ ] Korean lesson과 lesson index를 추가한다.
- [ ] README validator, workflow/stale-check 등록 상태를 확인한다.

### 5. 검증·delivery

- [ ] targeted tests: properties/registration/observer/adapter/coordinator.
- [ ] module unit test와 integration test를 serial mode로 실행한다.
- [ ] `node scripts/validate-job-safety-lab-readme.mjs`, `git diff --check`,
  `scripts/smoke-validate.sh leader-full`, `scripts/smoke-validate.sh stale-check`를
  실행한다.
- [ ] stacked branch exact head와 ecosystem scope를 갱신하고 PR을 만든다.
- [ ] live metadata/CI를 읽어 `Required checks: X/Y; N/A: N; Blocked: 0`을 산출한다.
- [ ] fresh exact-head `승인` 전 merge하지 않는다.

## 위험과 대응

| 위험 | 신호 | 대응 |
|---|---|---|
| scope 누락 | watchdog event 0건, user만 관찰 | executor 작업에서 `scope.withScope`를 검증하고 fail closed |
| callback 중복 | 동일 event가 2회 이상 registry handler에 도착 | registry identity reference count와 마지막 close 테스트 |
| disabled/NOOP 누수 | disabled인데 global/scoped observer 존재 | bean gate와 registration count 0 회귀 테스트 |
| raw identity 노출 | lock/leader/message가 observation tag/log에 존재 | sanitizer 결과와 key allow-list assertion |
| 기존 leader 동작 회귀 | acquire/release 순서 또는 결과 변경 | 기존 coordinator/adapter 테스트와 full module test |
| context close 누락 | scope active 또는 executor 미종료 | restart integration에서 inactive/terminated 확인 |

## 롤백

관찰 기능에 문제가 생기면 properties를 disabled로 두거나 registration bean을 제거해
기존 manual leader lifecycle observation만 남긴다. lease/fencing/business 결과를
변경하는 수정은 이 issue에서 수행하지 않는다.

## 커밋 순서

1. 설계·plan·review checklist 기록;
2. properties/registration tests와 구현;
3. scope 전파 및 lifecycle tests;
4. README/matrix/lesson;
5. final review evidence와 CI/PR metadata.

모든 commit은 Lore format Korean trailer를 사용한다.

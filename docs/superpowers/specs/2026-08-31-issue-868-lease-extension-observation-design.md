# Issue #868 lease-extension observation 경계 설계

## 문서 상태

- 대상 이슈: [#868](https://github.com/bluetape4k/bluetape4k-workshop/issues/868)
- 대상 모듈: `leader/job-safety-lab`
- 기준 의존성: `bluetape4k-dependencies:2.0.0-SNAPSHOT`
- 기준 upstream: 현재 BOM이 제공하는 `bluetape4k-leader`의
  `LeaderLeaseExtensionEvent`, `LeaderLeaseExtensionObservers.addObserver`,
  `LockExtender`, `MicrometerObservationLeaderLeaseExtensionObserver`
- 범위: 기존 Redis leader/job 실행 예제에 user/watchdog lease-extension observation 연결

이 문서는 live Issue #868과 2026-08-31 현재 로컬 upstream source를 구현 기준으로
삼는다. upstream develop에만 존재하고 현재 snapshot artifact에는 없는 scoped observer API를
참조하지 않고, 현재 소비 가능한 global observer wiring과 backend thread-affinity bridge를
검증한다.

## 문제와 근거

`leader/job-safety-lab`은 `MicrometerObservationLeaderAopMetricsRecorder`와
`MicrometerObservationLeaderElectionListener`로 leader lifecycle을 관찰한다. 그러나 현재
custom `RedisLeaderElectionAdapter`는 `runIfLeader`를 별도 executor에서 실행하고 요청
thread에는 lease handle을 공개하지 않는다. 따라서 요청 thread에서 `LockExtender`를
그대로 호출하면 lock context가 없어 `NotHeld`가 되며, 단순히 observation scope만 복사해도
실제 lease extension은 동작하지 않는다.

upstream develop에는 이 경계를 위한 scoped observer API가 추가되어 있지만, 현재
2.0.0-SNAPSHOT BOM이 해석하는 `bluetape4k-leader-core:1.0.0-SNAPSHOT` jar에는 global
`addObserver`만 포함되어 있다. Issue #868은 현재 배포 artifact로 동작하는 global
observation과 backend lock token/thread-affine handle을 안전하게 교차하지 않는 예제를
먼저 고정하고, scoped API 적용은 artifact 갱신 후 후속 작업으로 남긴다.

## 목표와 제외 범위

### 목표

1. `ObservationRegistry`가 정상이고 `bluetape4k.leader.observation.enabled=true`일 때만
   application-owned global observer를 한 번 등록한다.
2. 이 workshop의 단일 Spring context 안에서는 registration bean이 하나이고 close가
   idempotent하다. 현재 snapshot artifact가 global observer만 제공하므로 여러 context의
   registry별 격리는 upstream scoped API artifact가 갱신된 뒤 별도 후속 범위로 남긴다.
3. Redis elector owner thread와 user proxy가 같은 process-local global observer를 사용한다.
4. 요청 thread의 user extension은 `LeaderLease.extendViaLockExtender` proxy를 통해 owner
   thread의 실제 `LockExtender.extendActiveLockDetailed`를 호출한다. owner thread가
   보유한 raw handle 밖으로 token을 복사하지 않으며, event source는 `user`로 기록된다.
5. watchdog은 backend elector가 보유한 handle에서 기존처럼 실행되고 event source는
   `watchdog`로 기록된다.
6. `bluetape4k.leader.observation` 설정으로 `enabled`, `includeLockName`,
   `includeLeaderId`, `includeExceptionDetails`를 선택하고 README 양쪽에 실행 예제를
   제공한다. 이 prefix는 `leader-job-safety-lab` consumer adapter의 설정이며,
   upstream Spring auto-configuration의 `bluetape4k.leader.observability` prefix와
   혼동하지 않는다.
7. observation 이름 `bluetape4k.leader.lease.extension`과 low-cardinality
   `source`, `execution`, `outcome`, `result` tag를 회귀 테스트로 고정한다.

### 제외 범위

- lease 알고리즘, fencing protocol, watchdog scheduler 정책 변경;
- upstream `MicrometerObservationLeaderLeaseExtensionObserver`, scoped registry,
  `leader-spring-boot` auto-configuration 내부 manager 복제;
- audit HTTP 전송과 payload schema 변경(이는 #867 범위);
- 실제 Redis 장애 주입, 장기 soak, 새로운 Spring Boot starter/module 추가;
- suspend lease API. 이 adapter는 blocking `LeaderElector` consumer 예제이며 suspend
  context bridge는 upstream API 사용 예제로만 안내한다.

## 선택한 구조

### 설정과 options 단일 소스

`JobSafetyLeaderObservationProperties`를 `bluetape4k.leader.observation` prefix로
등록한다. 기본값은 `enabled=true`, `includeLockName=false`, `includeLeaderId=false`,
`includeExceptionDetails=false`다. `JobSafetyConfiguration`은 이 properties를
`LeaderObservationOptions`로 변환하고 lifecycle recorder/listener와 lease-extension
observer가 같은 sanitizer 정책을 사용하도록 한다. identity를 켜더라도 raw 값이 아니라
기존 `LeaderMetricTagSanitizer`의 redacted/hash 결과만 high-cardinality tag가 된다.

`ObservationRegistry`가 없으면 현재 `ObservationRegistry.NOOP` bean을 사용한다. 실제
registry가 NOOP이거나 설정이 disabled면 observer와 global callback registration을 생성하지
않는다.

### application-owned global registration 수명

`JobSafetyLeaseExtensionObservation`은 단일 Spring context가 소유하는
`AutoCloseable` global registration이다.

- 정상 registry + enabled: 현재 artifact의
  `LeaderLeaseExtensionObservers.addObserver(...)`를 정확히 한 번 호출하고 handle을
  보관한다.
- NOOP/disabled: registration count 0, callback 없음.
- close: observer handle을 최대 한 번만 닫고, 이미 닫힌 registration은 no-op이다.
- 같은 context의 중복 방지는 `@ConditionalOnMissingBean`과 단일 bean wiring으로
  보장한다. cross-context registry 공유와 scope 격리가 필요하면 scoped API가 포함된
  upstream artifact와 Spring integration을 사용하며 이 예제의 local manager로 흉내 내지
  않는다.

현재 global observer 계약에서는 별도 scope를 설치하지 않는다. `RedisLeaderElectionAdapter`
는 owner executor의 `runIfLeader` action에서 실제 `LockExtender`를 호출하므로 global
observer가 user/watchdog event를 수신한다.

### thread-affinity를 보존하는 user extension proxy

`LeaderLease`에 workshop 전용 `extendViaLockExtender(Duration): ExtendOutcome`을
추가한다. `RedisLeaderLease`는 bounded `ArrayBlockingQueue`와 bounded future를 소유하며
요청 thread의 호출을 `ExtendViaLockExtender` command로 owner thread에 전달한다.
owner thread는 `runIfLeader` action 안에서 실제 `LockExtender.extendActiveLockDetailed`를
호출하므로 upstream `LockStateHolder`의 real handle과 thread affinity가 유지된다.

- queue가 가득 차거나 응답 deadline을 넘으면 `Rejected`를 반환한다.
- release/cancel 이후 호출은 `NotHeld`를 반환한다.
- backend 예외는 `BackendError`로 반환되고 observer가 error 결과를 기록한다.
- 요청 thread가 raw `LeaderLockHandle` 또는 backend token을 직접 보유하지 않는다.

기존 `run(request, execute: (FencingLease) -> JobMutation)` API는 그대로 유지한다. 새
예제 경로에는 `runWithLease(request, execute: (LeaderLease, FencingLease) -> JobMutation)`
overload를 추가해 user extension 호출 시점을 명시한다. business callback을 owner
thread로 옮기지 않고, extension command만 owner thread에서 실행한다.

### observation 의미와 redaction

observer는 upstream 구현을 그대로 사용한다.

| 항목 | 값 |
|---|---|
| name | `bluetape4k.leader.lease.extension` |
| low tag | `source`, `execution`, `outcome`, `result` |
| source | `user`, `watchdog` |
| execution | `blocking`, `suspend` |
| outcome | `extended`, `rejected`, `not_held`, `wrong_thread`, `backend_error` |
| result | `success`, `skipped`, `error` |

upstream observer는 `elapsedNanos`를 tag나 별도 duration 필드로 사용하지 않고
observation의 start/stop duration만 남긴다. 따라서 README와 테스트는 duration 수치나
elapsed tag를 약속하지 않는다.

`LockExtender`가 `CancellationException`을 받으면 upstream contract대로 예외를 다시
던지고 lease-extension event를 발행하지 않는다. 이 예제의 proxy queue timeout은
`Rejected`/`skipped`이며 cancellation을 가짜 terminal event로 만들지 않는다.

lock name과 leader id는 옵션을 켠 경우에도 기존 sanitizer를 거친 high-cardinality tag다.
exception details는 `includeExceptionDetails=true`일 때만 observation error로 설정하고,
raw exception message를 별도 tag나 로그에 복사하지 않는다.

### 실제 실행 데이터 흐름

```text
JobRunCoordinator.runWithLease
  -> global observation registration (caller)
  -> RedisLeaderElectionAdapter.tryAcquire
  -> owner executor { LettuceLeaderElector.runIfLeader }
  -> LeaderLeaseAutoExtender watchdog event (source=watchdog)
  -> caller LeaderLease.extendViaLockExtender(duration)
  -> owner command queue -> LockExtender.extendActiveLockDetailed (source=user)
  -> MicrometerObservationLeaderLeaseExtensionObserver
  -> ObservationRegistry
```

기존 leader acquire/release, PostgreSQL fencing, audit export와 business 결과는 변경하지
않는다. observation callback 실패도 lease 결과를 바꾸지 않는다.

## 검증 전략

- properties: 기본값, prefix bind, disabled/NOOP gating, 기존 lifecycle options 전달;
- registration: 단일 context global bean, idempotent close, close 뒤 callback 부재;
- adapter: owner thread command queue, actual `LockExtender` invocation, queue timeout,
  release/cancel 경계;
- coordinator: 기존 overload 회귀와 `runWithLease` user extension event;
- observer: actual `Extended` user event, watchdog event, outcome mapping, low-cardinality
  고정, redaction, exception opt-in;
- lifecycle: context close 뒤 global registration 제거, queued command drain/timeout, lease release,
  worker 종료;
- README EN/KO parity, validation matrix/workflow/stale-check/lesson 등록.

## DoD

- Issue #868 acceptance 항목이 설계·구현·테스트·README에 모두 매핑된다.
- 실제 user path가 owner-thread `LockExtender` bridge를 거치며 synthetic event를 만들지
  않는다.
- 변경된 consumer는 versionless alias와 root dependencies BOM만 사용한다.
- targeted/module/integration 검증과 README/stale/workflow helper가 fresh head에서
  통과한다.
- PR body와 live GitHub metadata는 `[2.0.0]`, milestone `2.0.0`, 한국어 상태를
  유지하며, fresh exact-head 승인 전 merge하지 않는다.

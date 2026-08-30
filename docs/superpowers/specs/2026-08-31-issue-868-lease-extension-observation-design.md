# Issue #868 lease-extension observation 경계 설계

## 문서 상태

- 대상 이슈: [#868](https://github.com/bluetape4k/bluetape4k-workshop/issues/868)
- 대상 모듈: `leader/job-safety-lab`
- 기준 의존성: `bluetape4k-dependencies:2.0.0-SNAPSHOT`
- 기준 upstream: `bluetape4k-leader`의 `LeaderLeaseExtensionEvent`,
  `LeaderLeaseExtensionObservers`, `LeaderLeaseExtensionObservationScope`,
  `MicrometerObservationLeaderLeaseExtensionObserver`
- 범위: 기존 Redis leader/job 실행 예제에 user/watchdog lease-extension observation 연결

이 문서는 live Issue #868과 2026-08-31 현재 로컬 upstream source를 구현 기준으로
삼는다. upstream API의 동작을 복제하지 않고 consumer example의 wiring과 회귀 증거만
추가한다.

## 문제와 근거

`leader/job-safety-lab`은 `MicrometerObservationLeaderAopMetricsRecorder`와
`MicrometerObservationLeaderElectionListener`로 leader lifecycle을 관찰한다. 그러나
현재 custom `RedisLeaderElectionAdapter`는 `runIfLeader`를 별도 executor에서 실행할 때
lease-extension observation scope를 전달하지 않는다. 따라서 다음 terminal event가
Micrometer observation에 나타나지 않는다.

- `LockExtender`를 호출한 user blocking/suspend extension;
- `LeaderLeaseAutoExtender`가 수행한 watchdog blocking/suspend extension;
- `Rejected`, `NotHeld`, `WrongThread`, `BackendError`와 cancellation/timeout 경계.

upstream 2.0.0-SNAPSHOT에는 이 경계를 위한 public event/observer/scope API와
`MicrometerObservationLeaderLeaseExtensionObserver`가 이미 존재한다. upstream
Spring Boot integration은 registry identity별 한 observer와 context별 scope를 공유하고,
NOOP/disabled 조건에서는 registration을 만들지 않는다. Issue #868은 그 기능을
job-safety consumer에서 실제로 보여 주는 예제 작업이다.

## 목표와 제외 범위

### 목표

1. `ObservationRegistry`가 정상이고 `bluetape4k.leader.observation.enabled=true`일 때만
   lease-extension observer와 scope를 한 번 등록한다.
2. 같은 `ObservationRegistry`를 여러 bean/context가 공유해도 callback은 중복되지 않고,
   마지막 owner가 닫힐 때만 scope/observer가 제거되도록 한다.
3. `JobRunCoordinator` 실행 경계와 Redis elector executor 경계에 같은 scope를 설치해
   user와 watchdog event를 관찰한다.
4. `bluetape4k.leader.observation` 설정으로 `enabled`, `includeLockName`,
   `includeLeaderId`, `includeExceptionDetails`를 선택하고 README 양쪽에 실행 예제를
   제공한다.
5. observation 이름 `bluetape4k.leader.lease.extension`과 low-cardinality
   `source`, `execution`, `outcome`, `result` tag를 회귀 테스트로 고정한다.
6. lock name/leader id는 기존 hash sanitizer를 사용한 high-cardinality opt-in으로만
   노출하며, raw identity와 exception message는 metric tag/log/report에 넣지 않는다.
7. 정상 연장, 거절/timeout, cancellation, context close, 같은 registry 중복 등록을
   fake elector/clock 또는 deterministic delegate로 검증한다.

### 제외 범위

- lease 알고리즘, fencing protocol, watchdog scheduler 정책 변경;
- upstream `MicrometerObservationLeaderLeaseExtensionObserver` 또는 scope registry 복제;
- audit HTTP 전송과 payload schema 변경(이는 #867 범위);
- 실제 Redis 장애 주입, 장기 soak, 새로운 Spring Boot starter/module 추가.

## 선택한 구조

### 설정

`JobSafetyLeaderObservationProperties`를 `bluetape4k.leader.observation` prefix로
등록한다. 기본값은 `enabled=true`, `includeLockName=false`, `includeLeaderId=false`,
`includeExceptionDetails=false`다. 기존 `LeaderObservationOptions` bean은 유지하되,
lease-extension observer도 같은 tag sanitizer 옵션을 공유한다. `ObservationRegistry`가
없으면 현재 `ObservationRegistry.NOOP` bean을 사용하고, 실제 registry가 NOOP이거나
설정이 disabled면 observer bean/scope를 생성하지 않는다.

### registration과 scope 수명

`JobSafetyLeaseExtensionObservationRegistration`은 다음 계약을 가진 application-owned
`AutoCloseable`이다.

- 정상 registry + enabled: `LeaderLeaseExtensionObservers.addScopedObserver(...)`를
  정확히 한 번 호출하고 active scope를 반환한다.
- 동일 registry의 두 registration: identity 기준으로 동일 scope를 공유하고 reference
  count만 증가한다. 옵션이 다르면 startup을 fail closed한다.
- close: handle별 reference count를 감소시키며 마지막 close에서 scope를 닫는다.
  close는 idempotent하다.
- NOOP/disabled: registration count 0, scope 없음, callback 없음.

`JobRunCoordinator`는 registration의 active scope를 optional dependency로 받아
`scope.withScope { runInternal(...) }`로 전체 run을 감싼다. custom
`RedisLeaderElectionAdapter`는 acquire caller의 현재 scope를 캡처하고 backend executor
작업 안에서 `scope.withScope { runIfLeader(...) }`를 실행한다. 이를 통해 elector가
소유한 watchdog thread에도 scope가 전달된다. suspend API는 이 issue의 custom adapter
범위 밖이며, upstream scope context bridge의 존재를 README에서 명시한다.

### observation과 redaction

observer는 upstream 구현을 그대로 사용한다. 따라서 observation 이름과 low-cardinality
tag는 upstream mapping을 따른다.

| 항목 | 값 |
|---|---|
| name | `bluetape4k.leader.lease.extension` |
| low tag | `source`, `execution`, `outcome`, `result` |
| source | `user`, `watchdog` |
| execution | `blocking`, `suspend` |
| outcome | `extended`, `rejected`, `not_held`, `wrong_thread`, `backend_error` |
| result | `success`, `skipped`, `error` |

elapsed time은 tag가 아니며 observation duration으로만 기록된다. lock name과 leader id는
옵션을 켠 경우에도 기존 `LeaderMetricTagSanitizer`를 거친 high-cardinality tag다.
exception details는 `includeExceptionDetails=true`일 때만 Observation error로 설정하고,
raw exception message를 별도 tag나 로그에 복사하지 않는다.

### 실제 실행 데이터 흐름

```text
JobRunCoordinator.run
  -> active scope.withScope
  -> RedisLeaderElectionAdapter.tryAcquire
  -> executor scope.withScope { LettuceLeaderElector.runIfLeader }
  -> LeaderLeaseAutoExtender watchdog event (source=watchdog)
  -> user LockExtender call in execute (source=user)
  -> MicrometerObservationLeaderLeaseExtensionObserver
  -> ObservationRegistry
```

기존 leader acquire/release, PostgreSQL fencing, audit export와 business 결과는 변경하지
않는다. observation callback 실패도 upstream contract대로 lease 결과를 바꾸지 않는다.

## 검증 전략

- properties: 기본값, prefix bind, disabled/NOOP gating, 옵션 전달;
- registration: 동일 registry 공유, 옵션 불일치 fail-closed, idempotent close, 마지막
  owner 종료 후 callback 부재;
- adapter/coordinator: scope가 executor thread까지 전파되고 execute 내 user event와
  watchdog event가 모두 관찰됨;
- observer: outcome mapping, low-cardinality 고정, redaction, exception opt-in;
- lifecycle: context close 뒤 scope가 inactive이고 executor/leader lease release가
  그대로 수행됨;
- README EN/KO parity, validation matrix/workflow/stale-check/lesson 등록.

## DoD

- Issue #868 acceptance 항목이 설계·구현·테스트·README에 모두 매핑된다.
- 변경된 consumer는 versionless alias와 root dependencies BOM만 사용한다.
- targeted/module/integration 검증과 README/stale/workflow helper가 fresh head에서
  통과한다.
- PR body와 live GitHub metadata는 `[2.0.0]`, milestone `2.0.0`, 한국어 상태를
  유지하며, fresh exact-head 승인 전 merge하지 않는다.

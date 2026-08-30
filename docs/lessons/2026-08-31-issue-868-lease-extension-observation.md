# Issue #868 lease-extension observation과 released ABI 경계

## Context

`leader/job-safety-lab`은 Redis leader acquire/release와 PostgreSQL fencing을
관찰했지만 watchdog와 사용자 lease-extension terminal outcome을 확인할 수
없었습니다. Issue #868은 `2.0.0-SNAPSHOT`의 lease-extension event와
Micrometer adapter를 기존 예제에 연결하는 작업입니다.

## Decision or Finding

- consumer의 실제 BOM artifact는 `LeaderLeaseExtensionEvent`,
  `LeaderLeaseExtensionObservers.addObserver`,
  `MicrometerObservationLeaderLeaseExtensionObserver`를 공개합니다.
- upstream develop에는 scoped registration도 있지만 현재 consumer artifact에는
  `LeaderLeaseExtensionObservationScope`와 `addScopedObserver`가 없어 해당 API를
  예제에서 참조하지 않습니다.
- `RedisLeaderElectionAdapter`는 thread-bound `LeaderLockHandle`을 복사하지
  않고 bounded owner-thread command queue로 `LockExtender`를 호출합니다.
- observation 등록은 process-local global registration 하나로 제한하고,
  `ObservationRegistry.NOOP` 또는 disabled 설정에서는 등록하지 않습니다.
- user와 watchdog event는 `source`, `execution`, `outcome`, `result` bounded
  tag로 확인하며 lock/leader 식별자는 정책을 켠 경우에도 sanitized
  high-cardinality 값으로만 기록합니다.

## Outcome

기존 `run` API의 호출자는 그대로 유지하면서 `runWithLease`에서만 user
extension을 opt-in할 수 있게 했습니다. 실제 Redis integration test는
`Extended` user event와 자동 watchdog event를 모두 확인하며, registration
close는 context 종료와 함께 idempotent하게 수행됩니다.

## Verification

- targeted unit tests: 20 passing, 0 failures
- real Redis/PostgreSQL `JobSafetyEndToEndIntegrationTest`: 3 passing, 0 failures
- full module unit tests: 99 passing, 0 failures, 0 skipped
- full module integration tests: 13 passing, 0 failures, 0 skipped
- `JobSafetyLeaseExtensionObservationRegistrationTest`: disabled/NOOP gating,
  duplicate close, post-close no-event 확인
- `JobSafetyLeaderObservationPropertiesTest`: safe defaults와 명시적 binding 확인
- Docker runtime: Colima running, Docker Engine reachable

## Future Guidance

다음 snapshot이 scoped registration ABI를 실제 BOM에 포함하면 context별
observer isolation을 재평가합니다. 그 전에는 global registry 계약을 유지하고
새 upstream API를 consumer 소스에 선제적으로 복사하지 않습니다.

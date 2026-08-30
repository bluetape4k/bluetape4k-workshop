# Issue #868 설계 spec review

## 검토 시점과 범위

- 검토일: 2026-08-31
- 대상: `docs/superpowers/specs/2026-08-31-issue-868-lease-extension-observation-design.md`
- 근거: upstream `LeaderElectorLeaseAdapter`, `LockExtender`,
  `MicrometerObservationLeaderLeaseExtensionObserver`, local
  `RedisLeaderElectionAdapter`/`JobRunCoordinator` source
- 이전 판정: REQUEST CHANGES (P1=5). caller thread에서 실제 handle이 없는 문제,
  upstream manager 복제 모순, cancellation/elapsed 의미, options SSOT, checklist 순서를
  수정한 뒤 문서 자체를 재검토했다.

## 여섯 관점 판정

| 관점 | 확인한 계약 | 결과 | 남은 위험 |
|---|---|---|---|
| API/안정성 | 기존 `run` 유지, `runWithLease` overload, `LeaderLease.extendViaLockExtender`의 명시적 proxy 경계 | PASS | 구현 시 기존 fake의 source compatibility 유지 필요 |
| 운영/관측성 | 단일 context global registration, NOOP/disabled gate, upstream observer 이름·low tag 고정 | PASS | scoped API는 snapshot artifact 갱신 뒤 upstream Spring integration 범위 |
| 안정성/동시성 | raw handle을 owner thread에만 두고 bounded command queue로 요청을 직렬화 | PASS | queue timeout과 release race를 테스트로 고정해야 함 |
| 성능 | unbounded wait와 global identity map을 제거하고 bounded future/queue를 선택 | PASS | 실제 event당 allocation은 bounded stress에서 확인 |
| 보안 | identity 기본 off, opt-in도 기존 sanitizer, raw token/message 비노출 | PASS | properties→options 단일 매핑을 코드/테스트에서 확인 |
| 사용자/문서 | consumer prefix와 upstream prefix를 구분하고 cancellation은 무관찰로 설명 | PASS | README sample이 elapsed tag를 약속하지 않아야 함 |

## 수정 확인

1. caller scope만 복사하는 설계를 제거하고 owner-thread `LockExtender` command bridge를
   명시했다.
2. 현재 released snapshot의 global `addObserver`만 사용하고, upstream
   `LeaseExtensionObservationRegistrationManager`/scoped API 복제를 제외했다.
3. cancellation은 예외 재전파/무-event, queue timeout은 `Rejected`/`skipped`,
   backend failure는 `BackendError`/`error`로 분리했다. `elapsedNanos` duration 주장은
   제거했다.
4. `JobSafetyLeaderObservationProperties`의 identity 기본 off와 기존
   `LeaderObservationOptions` 매핑을 단일 소스로 지정했다.
5. checklist의 A-03→A-04→A-05 순서와 Action/Evidence/Failure 기록을 복구했다.

## 판정

**PASS — released artifact 기준 문서 구현 gate 통과 (P0=0, P1=0, P2=0).**

다음 gate는 이 설계를 코드로 옮긴 뒤 실제 `LockExtender`가 `Extended`를 반환하고
`source=user` observation을 발행하는 테스트이다. 그 증거가 없으면 구현 완료로 판정하지
않는다.

# Lessons — leader-election 고급 패턴 예제 (PR-A)

**날짜**: 2026-05-25  
**브랜치**: feat/leader-election-advanced  
**이슈**: #10 bluetape4k-leader 예제 제작

---

## 작업 요약

`leader/leader-election` 모듈에 `bluetape4k-leader` v0.2.1의 고급 API 예제를 추가했다:
- `LockExtender.extendActiveLock()` — 잠금 임차 연장
- `LockAssert.assertLocked()` / `isLocked()` — 잠금 소유 런타임 검증
- `LettuceSuspendLeaderElector` — 코루틴 기반 리더 선출
- `ListeningLeaderElector` + `events: Flow` / `LeaderElectionListener` — 이벤트 옵저버

---

## 핵심 발견사항

### 1. `LeaderElectionListener` 콜백 시그니처에 `state` 파라미터 없음

- **증상**: 컴파일 오류 — `onElected(lockName: String, state: LeaderState)` 시그니처로 구현 시 "Nothing to override"
- **원인**: 실제 인터페이스 시그니처는 `onElected(lockName: String)` (state 파라미터 없음)
- **교훈**: API 시그니처를 IDE 자동완성 또는 소스 jar 디컴파일로 먼저 확인해야 함

### 2. `SuspendedJobTester`는 `io.bluetape4k.junit5.coroutines` 패키지에 있음

- **잘못된 import**: `io.bluetape4k.junit5.concurrency.SuspendedJobTester`
- **올바른 import**: `io.bluetape4k.junit5.coroutines.SuspendedJobTester`
- **교훈**: `bluetape4k-junit5` 소스 jar에서 실제 패키지 경로 확인 필수

### 3. `SuspendedJobTester.run()`은 `suspend fun` → `runBlocking` 필요

- 비가상 시간 테스트(실제 Redis I/O 포함)이므로 `runTest` 대신 `runBlocking` 사용
- `runTest`는 가상 시간을 진행시켜 `delay(300)` 같은 실제 잠금 대기를 건너뜀

### 4. 동시성 테스트: sequential 실행은 contention이 아님

- **잘못된 패턴**: `elector1.runIfLeader(...)` → `elector2.runIfLeader(...)` (순차 실행)
  - elector1이 먼저 완료되면 lock 해제 → elector2가 정상 획득
- **올바른 패턴**: elector1 body 내부에서 elector2 시도 (중첩 패턴)
  ```kotlin
  val result1 = elector1.runIfLeader(lockName) {
      result2 = elector2.runIfLeader(lockName) { "follower" }  // 잠금 보유 중 경합
      "leader"
  }
  ```

### 5. Spring: 동일 타입 bean 2개 → `@Qualifier` 필수

- `StatefulRedisConnection<String, String>` bean 2개 (`lettuceConnection`, `lettuceSuspendConnection`)
- 파라미터 이름 매칭은 `-parameters` 컴파일러 플래그 의존 → 리팩토링에 취약
- **명시적 `@Qualifier`가 항상 안전**

### 6. `CancellationException` swallow 주의

- Flow collector의 `catch (e: Exception)` 블록이 `CancellationException`을 삼킴
- `scope.cancel()` 시 정상 취소가 오류로 로깅됨
- 항상 `catch (e: CancellationException) { throw e }` 먼저 추가

### 7. SharedFlow `replay=0` 경쟁 조건

- `scope.launch { flow.collect {} }` 후 즉시 emit 시 subscriber가 아직 등록 전일 수 있음
- `replay=0`이면 구독 전 이벤트는 유실됨
- **해결**: emit 전 `Thread.sleep(100)` 또는 `subscriptionCount.first { it > 0 }` 대기

### 8. `LettuceSuspendLeaderElector`에는 별도 connection 필요

- 동일 connection을 blocking/suspend elector가 공유하면 pipelined command ordering 오류 발생
- **패턴**: Spring config에 `lettuceSuspendConnection` 별도 bean 선언

---

## 테스트 결과

```
Module : :leader-leader-election
Result : 26 passing, 0 failed, 0 skipped
Command: ./gradlew :leader-leader-election:test --no-daemon
```

---

## 코드 리뷰 결과

| 우선순위 | 이슈 | 처리 |
|---------|------|------|
| HIGH | CancellationException swallowed in Flow collector | ✅ 수정 |
| HIGH | `assertThrows` → `assertFailsWith` | ✅ 수정 |
| HIGH | SharedFlow replay=0 race condition | ✅ 수정 |
| MEDIUM | suspendLeaderElector missing @Qualifier | ✅ 수정 |

---

## 미래 작업

- **PR-B**: `leader/leader-zookeeper/` 모듈 — ZooKeeper backend 예제 (Type-A Full Design)
- **PR-C**: `leader/leader-k8s/` 모듈 — Kubernetes leader election 예제 (Type-A Full Design)

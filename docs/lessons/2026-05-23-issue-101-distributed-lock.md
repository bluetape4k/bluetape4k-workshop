# 교훈: redis/distributed-lock 모듈 구현 (Issue #101)

**날짜**: 2026-05-23  
**작업자**: debop  
**관련 이슈**: #101  
**브랜치**: `feat/issue-101-distributed-lock`  
**모듈**: `redis/distributed-lock`

---

## 작업 요약

Redisson을 활용한 분산 락 전략 4단계 워크샵 모듈 신규 구현.  
비안전 공유 상태(oversell 재현) → RLock → RFencedLock (블로킹) → 코루틴 네이티브 RFencedLock 순으로 시연.

---

## 발견된 버그 및 수정 내역

### B1. `log.warn { }` 람다 구문 컴파일 에러

**원인**: bluetape4k의 lazy lambda 로깅 확장은 `import io.bluetape4k.logging.warn`이 별도로 필요함.  
표준 SLF4J `Logger`는 람다 형식이 아닌 문자열만 받음.

**수정**: 세 서비스 파일에 `import io.bluetape4k.logging.warn` 추가.  
**교훈**: `log.warn { }` / `log.debug { }` 람다 구문을 쓸 때는 `io.bluetape4k.logging.*` 확장 import가 반드시 필요함.

---

### B2. `NoClassDefFoundError: Snowflakers` — 런타임 실패

**원인**: `getLockId()` 함수가 내부적으로 `bluetape4k-idgenerators` 모듈의 `Snowflakers`를 사용하지만,  
`bluetape4k-redisson`이 해당 모듈을 transitive로 포함하지 않아 런타임에 누락됨.

**수정**: `build.gradle.kts`에 `implementation(libs.bluetape4k.idgenerators)` 명시적 추가.  
**교훈**: `getLockId()`를 쓰는 모든 Redisson 모듈은 `bluetape4k-idgenerators`를 명시 의존으로 선언해야 함.

---

### B3. Smoke 태그 제외가 Gradle에서 미동작

**원인**: `junit-platform.properties`의 `junit.jupiter.execution.exclude.tags=smoke`는  
Gradle JUnit Platform Provider에서 신뢰성 있게 존중되지 않음.

**수정**:  
```kotlin
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("smoke") }
}
```
을 `build.gradle.kts`에 명시적으로 추가.  
**교훈**: Gradle에서 JUnit 태그 제외는 반드시 `tasks.named<Test>()` DSL로 설정해야 함.  
`junit-platform.properties` 단독 설정은 불충분.

---

### B4. `SuspendedJobTester.rounds()` 의미론 오해

**원인**: `SuspendedJobTester.workers(20).rounds(1)` → `totalUnits = 1 × blockCount(1) = 1`만 실행.  
stock=100/qty=10 → 성공 1건 예상했으나 10건 필요.

**수정**: `rounds(20)` + `waitMs = 5000L`로 변경.  
**공식**: `totalUnits = rounds × blockCount` (workers는 병렬 실행자 수).  
**교훈**: `SuspendedJobTester`에서 총 시도 횟수 = `rounds * blockCount`. `workers`는 동시 실행자 수.

---

### B5. RLock/RFencedLock 재진입 가능 — 동일 스레드 락 선점 무효

**원인**: P1 리뷰 수정 중 LockNotAcquired 테스트에서 메인 테스트 스레드로 락을 선점한 후  
동일 스레드에서 서비스를 호출했더니 락이 재진입 허용되어 `Success` 반환.

**수정**: 별도 `Thread { }` + `CountDownLatch(1)`로 백그라운드 스레드에서 락 선점.  
```kotlin
val holder = Thread {
    val held = lock.tryLock(...)
    acquireLatch.countDown()
    if (held) { releaseLatch.await(); lock.unlock() }
}
holder.start()
acquireLatch.await()  // 선점 완료 확인 후 테스트 진행
```
**교훈**: Redisson RLock/RFencedLock은 재진입 가능(reentrant). LockNotAcquired 테스트는 반드시 다른 스레드에서 락을 선점해야 함.

---

## 설계 결정

### D1. FencedResource 토큰 비교 — strict less-than

`token < current` (엄격 미만)을 사용. `token == current`는 **허용** (동일 리스 기간 내 재진입).  
`token <= current` 사용 시 정상 재진입도 거부하여 Redisson 토큰 동작과 불일치.

### D2. SuspendingFencedInventoryService — NonCancellable unlock

`withContext(NonCancellable) { fLock.unlockAsync(lockId).await() }`는 필수 패턴.  
이 없으면 코루틴 취소 시 `await()`가 `CancellationException`을 던지고 unlock이 완료되지 않아  
리스 만료 시까지 락이 유지됨.

### D3. Two-Step Acquire — tryLockAsync + tokenAsync

Redisson 4.x에는 `tryLockAndGetTokenAsync(lockId)` 오버로드가 없음.  
올바른 패턴: `tryLockAsync(wait, lease, unit, lockId)` 후 `fLock.tokenAsync.await()` 별도 호출.

### D4. getLockId() Snowflake ID

`redisson.getLockId(lockName)`은 `bluetape4k-redisson`이 제공하는 확장 함수.  
내부적으로 Snowflake ID를 사용해 코루틴-안전한 락 식별자를 생성함.  
coroutine에서 threadId 기반 락 ID를 쓰면 동일 스레드 재사용 시 충돌 위험.

### D5. beforeWork 테스트 심

`SuspendingFencedInventoryService`에 `beforeWork: suspend () -> Unit = {}` 파라미터를 주입.  
취소 테스트에서 `delay(500)` 주입 → 락 보유 중 취소 시점을 결정론적으로 만들 수 있음.

---

## 코드 리뷰 결과 (Step 6-R)

| 심각도 | 초기 | 최종 |
|--------|------|------|
| P0 (CRITICAL) | 0 | 0 |
| P1 (HIGH) | 2 | 0 |
| P2 (MEDIUM) | 3 | 0 |

**P1 수정:**
1. `Rejected` 서비스 경로 미테스트 → `FencedLockTest` + `SuspendFencedLockTest`에 pre-seed 테스트 추가
2. `LockNotAcquired` 서비스 경로 미테스트 → 3개 테스트 클래스에 백그라운드 스레드 테스트 추가

**P2 수정:**
1. `assert(raceObserved)` → `raceObserved.shouldBeTrue()` (bluetape4k-assertions 준수)
2. 이중 cast `(result as InsufficientStock)` 두 번 → `val insufficient = result as InsufficientStock`

---

## 최종 테스트 결과

```
BaselineRaceTest:      3 tests, 0 failures
DistributedLockTest:   6 tests, 0 failures
FencedLockTest:        5 tests, 0 failures
SuspendFencedLockTest: 6 tests, 0 failures
Total:                20 tests, 0 failures, 0 errors
```

Smoke 테스트 (`FencedStaleHolderTest`, `LockFailureTest`)는 기본 CI에서 제외.

---

## 미래 참고 사항

- `SuspendedJobTester.workers(N).rounds(R)` → `totalUnits = R × blockCount` (N은 병렬성)
- bluetape4k의 `log.warn { }` 사용 시 `import io.bluetape4k.logging.warn` 필수
- `getLockId()` 사용 시 `bluetape4k-idgenerators`를 명시 의존으로 선언
- Gradle에서 smoke 태그 제외는 `tasks.named<Test>() { useJUnitPlatform { excludeTags("smoke") } }`
- Redisson RLock/RFencedLock 재진입 허용 → LockNotAcquired 테스트는 별도 스레드 사용
